(ns zcube-jepsen.postgres
  "Postgres primary + synchronous standby on two nodes, and a failover
  nemesis: kill the primary, promote the standby, later re-seed the old
  primary as the new synchronous standby."
  (:require [clojure.tools.logging :refer [info warn]]
            [jepsen [control :as c]
                    [db :as db]
                    [nemesis :as nemesis]
                    [util :as util]]))

(def data-dir "/var/lib/zj/pg")
(def log-file "/var/lib/zj/pg.log")
(def bin "/usr/lib/postgresql/16/bin")

(defn pg-nodes
  "The two nodes that host Postgres."
  [test]
  (vec (take 2 (:nodes test))))

(defn pg!
  "Runs a postgres binary as the postgres user."
  [cmd & args]
  (c/su (apply c/exec :sudo :-u :postgres (str bin "/" (name cmd)) args)))

(def conf
  (str "listen_addresses = '*'\n"
       "wal_level = replica\n"
       "max_wal_senders = 10\n"
       "hot_standby = on\n"
       "wal_keep_size = '256MB'\n"
       ; Zigcube's supported setup (docs/operations.md).
       "synchronous_commit = remote_apply\n"
       "synchronous_standby_names = '*'\n"
       "max_connections = 200\n"))

(def hba
  (str "local all all trust\n"
       "host all all 0.0.0.0/0 trust\n"
       "host replication all 0.0.0.0/0 trust\n"))

(defn start! []
  (pg! :pg_ctl :-D data-dir :-l log-file :-w :-t 60 :start))

(defn kill!
  "SIGKILL every postgres process on this node."
  []
  (c/su (c/exec :pkill :-9 :-u :postgres (c/lit "|| true"))
        (c/exec :rm :-f (str data-dir "/postmaster.pid"))))

(defn postgres-procs
  "How many processes the postgres user has on this node."
  []
  (parse-long (c/su (c/exec :bash :-c "pgrep -c -u postgres || true"))))

(defn fence!
  "SIGKILLs Postgres on this node and checks that it is gone: no process of
  the postgres user left. Retries the kill briefly (a backend may be forking),
  then throws, so a primary that is still alive is never failed over from."
  []
  (loop [tries 0]
    (kill!)
    (let [n (postgres-procs)]
      (cond (zero? n) :stopped
            (< tries 10) (do (Thread/sleep 200) (recur (inc tries)))
            :else (throw (ex-info (str "primary not stopped: " n
                                       " postgres processes left")
                                  {:processes n}))))))

(defn wipe! []
  (kill!)
  (c/su (c/exec :rm :-rf "/var/lib/zj")
        (c/exec :mkdir :-p "/var/lib/zj")
        (c/exec :chown :postgres:postgres "/var/lib/zj")))

(defn init-primary! []
  (pg! :initdb :-D data-dir :-E :UTF8 :--locale=C.UTF-8)
  (c/su (c/exec :echo conf :>> (str data-dir "/postgresql.conf"))
        (c/exec :echo hba :> (str data-dir "/pg_hba.conf")))
  (start!)
  ; No standby yet, so commits would wait forever: commit locally for setup.
  (c/su (c/exec :sudo :-u :postgres :env "PGOPTIONS=-c synchronous_commit=local"
                (str bin "/psql") :-v "ON_ERROR_STOP=1" :-c
                "CREATE ROLE jepsen LOGIN SUPERUSER"))
  (c/su (c/exec :sudo :-u :postgres :env "PGOPTIONS=-c synchronous_commit=local"
                (str bin "/psql") :-v "ON_ERROR_STOP=1" :-c
                "CREATE DATABASE jepsen OWNER jepsen")))

(defn seed-standby!
  "Wipes this node and starts it as a streaming standby of `primary`."
  [primary]
  (wipe!)
  (pg! :pg_basebackup :-h primary :-U :postgres :-D data-dir :-R :-X :stream
       :-c :fast)
  (start!))

(defn await-primary! [primary]
  (util/await-fn
    (fn [] (pg! :psql :-h primary :-U :jepsen :-d :jepsen :-Atc "select 1"))
    {:retry-interval 500 :log-interval 5000 :timeout 90000
     :log-message (str "waiting for primary " primary)}))

(defn await-sync-standby!
  "Blocks until the primary reports a synchronous standby."
  [primary]
  (util/await-fn
    (fn []
      (let [n (pg! :psql :-h primary :-U :jepsen :-d :jepsen :-Atc
                   "select count(*) from pg_stat_replication where sync_state = 'sync'")]
        (when-not (= "1" n)
          (throw (RuntimeException. (str "sync standbys: " n))))))
    {:retry-interval 500 :log-interval 5000 :timeout 90000
     :log-message "waiting for synchronous standby"}))

(defn setup-node!
  "Sets up Postgres on this node if it is one of the pair."
  [test node]
  (let [[primary standby] (pg-nodes test)]
    (condp = node
      primary (do (wipe!)
                  (init-primary!)
                  (await-sync-standby! primary)
                  ; Created here, once, rather than by racing clients.
                  (pg! :psql :-U :jepsen :-d :jepsen :-v "ON_ERROR_STOP=1" :-c
                       (str "CREATE TABLE registers "
                            "(k bigint PRIMARY KEY, v bigint NOT NULL)")))
      standby (do (await-primary! primary) (seed-standby! primary))
      nil)))

(defn db
  "Postgres on the first two nodes; the others are left empty."
  []
  (reify db/DB
    (setup! [_ test node] (setup-node! test node))
    (teardown! [_ test node]
      (when (some #{node} (pg-nodes test))
        (wipe!)))
    db/LogFiles
    (log-files [_ test node]
      (if (some #{node} (pg-nodes test)) [log-file] []))))

(defn failover-nemesis
  "Handles two ops.

  :failover  SIGKILLs the primary and checks that no postgres process is left
             on it (fence!); only then promotes the standby. If the old
             primary cannot be confirmed stopped, nothing is promoted and the
             op's value is [:failover-failed ...], which fails the run. The
             old primary stays down until :rejoin.
  :rejoin    Re-seeds the old primary from the new one as its synchronous
             standby. Until this succeeds, commits on the new primary block.

  `state` is an atom {:primary node, :standby node, :standby-up? bool,
  :failovers n}. `on-promote`, if given, is called with the test and the new
  primary after each promotion (to repoint whatever names the primary)."
  [state on-promote]
  (reify nemesis/Nemesis
    (setup! [this test]
      (let [[p s] (pg-nodes test)]
        (reset! state {:primary p :standby s :standby-up? true :failovers 0}))
      this)
    (invoke! [_ test op]
      (let [{:keys [primary standby standby-up?]} @state]
        (case (:f op)
          :failover
          (if-not standby-up?
            (assoc op :value [:skipped :no-standby])
            (try
              (c/on-nodes test [primary] (fn [_ _] (fence!)))
              (c/on-nodes test [standby]
                          (fn [_ _] (pg! :pg_ctl :-D data-dir :-w :-t 60 :promote)))
              (when on-promote (on-promote test standby))
              (swap! state #(-> %
                                (assoc :primary standby :standby primary
                                       :standby-up? false)
                                (update :failovers inc)))
              (assoc op :value [:promoted standby :killed primary :fenced])
              (catch Exception e
                (warn e "failover failed")
                (assoc op :value [:failover-failed primary (.getMessage e)]))))

          :rejoin
          (if standby-up?
            (assoc op :value [:skipped :standby-up])
            (try
              (c/on-nodes test [standby] (fn [_ _] (seed-standby! primary)))
              (c/on-nodes test [primary] (fn [_ _] (await-sync-standby! primary)))
              (swap! state assoc :standby-up? true)
              (assoc op :value [:rejoined standby :primary primary])
              (catch Exception e
                (warn e "rejoin failed")
                (assoc op :value [:rejoin-failed standby (.getMessage e)])))))))
    (teardown! [_ test])))
