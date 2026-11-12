(ns zcube-jepsen.register
  "Compare-and-set registers, one row per key.

  Slice 1 talks to Postgres directly (the store under zcube-server). The
  zcube-server HTTP client plugs in here behind the same op shapes; see
  docs/plan.md."
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

(defn query-one [^Connection c sql & params]
  (with-open [st (.prepareStatement c sql)]
    (doseq [[i p] (map-indexed vector params)] (.setObject st (inc i) p))
    (with-open [rs (.executeQuery st)]
      (when (.next rs) (.getObject rs 1)))))

(defn execute! [^Connection c sql & params]
  (with-open [st (.prepareStatement c sql)]
    (doseq [[i p] (map-indexed vector params)] (.setObject st (inc i) p))
    (.executeUpdate st)))

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
            :read
            (let [x (query-one c "SELECT v FROM registers WHERE k = ?" k)]
              (assoc op :type :ok :value (independent/tuple k x)))

            :write
            (do (execute! c "INSERT INTO registers (k, v) VALUES (?, ?)
                             ON CONFLICT (k) DO UPDATE SET v = EXCLUDED.v" k v)
                (assoc op :type :ok))

            :cas
            (let [[old new] v
                  n (execute! c "UPDATE registers SET v = ? WHERE k = ? AND v = ?"
                              new k old)]
              (assoc op :type (if (= 1 n) :ok :fail))))
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
