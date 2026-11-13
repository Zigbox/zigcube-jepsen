(ns zcube-jepsen.register
  "Compare-and-set registers, one row per key, straight on the Postgres pair
  (the store under zcube-server) over JDBC. This is the `pg` workload: it
  tests the store alone and does not touch zcube-server. The client that goes
  through zcube-server's HTTP API is in zcube-jepsen.api."
  (:require [clojure.tools.logging :refer [info warn]]
            [jepsen [client :as client]
                    [independent :as independent]]
            [zcube-jepsen.postgres :as pg])
  (:import (java.sql Connection DriverManager SQLException)))

(defn url [test]
  (let [[a b] (pg/pg-nodes test)]
    (str "jdbc:postgresql://" a ":5432," b ":5432/jepsen"
         "?user=jepsen&targetServerType=primary&hostRecheckSeconds=1"
         "&connectTimeout=3&loginTimeout=5&socketTimeout=5")))

(defn connect ^Connection [test]
  (DriverManager/getConnection (url test)))

(defn close-quietly [^Connection c]
  (when c (try (.close c) (catch Exception _))))

(defn query-row
  "The first row as a vector of its columns, or nil."
  [^Connection c sql & params]
  (with-open [st (.prepareStatement c sql)]
    (doseq [[i p] (map-indexed vector params)] (.setObject st (inc i) p))
    (with-open [rs (.executeQuery st)]
      (when (.next rs)
        (mapv #(.getObject rs (int %))
              (range 1 (inc (.getColumnCount (.getMetaData rs)))))))))

(def store-sql
  "The final store read: every register, as k, ver, v."
  "SELECT k, ver, v FROM registers ORDER BY k")

(defn parse-store-rows
  "store-sql's rows as {k [v ver]}."
  [rows]
  (into {} (for [[k ver v] rows]
             [(parse-long k) [(parse-long v) (parse-long ver)]])))

; conn is an atom holding a Connection or nil; we connect lazily so that a
; worker can open while no primary is reachable.
(defrecord Client [conn]
  client/Client
  (open! [this test node] (assoc this :conn (atom nil)))

  (setup! [this test])

  (invoke! [this test op]
    (let [[k v] (:value op)
          c (or @conn
                (try (reset! conn (connect test))
                     (catch SQLException e nil)))]
      (if-not c
        ; Nothing was sent: a definite failure for every op type.
        (assoc op :type :fail :error :no-primary)
        (try
          (case (:f op)
            ; Every :ok carries :version, the row's ver (0: no row), so the
            ; history is a versioned register (zcube-jepsen.checkers).
            :read
            (let [[x ver] (query-row c "SELECT v, ver FROM registers WHERE k = ?" k)]
              (assoc op :type :ok :value (independent/tuple k x)
                     :version (or ver 0)))

            :write
            (let [[ver] (query-row c "INSERT INTO registers (k, v, ver) VALUES (?, ?, 1)
                                      ON CONFLICT (k) DO UPDATE
                                      SET v = EXCLUDED.v, ver = registers.ver + 1
                                      RETURNING ver" k v)]
              (assoc op :type :ok :version ver))

            :cas
            (let [[old new] v
                  [ver] (query-row c "UPDATE registers SET v = ?, ver = ver + 1
                                      WHERE k = ? AND v = ? RETURNING ver"
                                   new k old)]
              (if ver
                (assoc op :type :ok :version ver)
                (assoc op :type :fail))))
          (catch SQLException e
            (close-quietly c)
            (reset! conn nil)
            ; A read has no effect, so failing it is safe. A write or cas
            ; may have committed: indeterminate.
            (assoc op
                   :type  (if (= :read (:f op)) :fail :info)
                   :error [:sql (.getSQLState e) (.getMessage e)]))))))

  (teardown! [this test])

  (close! [this test]
    (close-quietly @conn)))

(defn client [] (Client. nil))
