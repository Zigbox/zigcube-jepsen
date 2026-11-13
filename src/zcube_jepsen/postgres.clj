(ns zcube-jepsen.postgres
  "Postgres primary + standby on two nodes, and a failover nemesis: promote
  the standby (after killing the primary, or not), later re-seed the old
  primary as the new standby.

  The standby is synchronous (`remote_apply`, Zigcube's supported setup)
  unless the test's :replication is :async, which is the negative control."
  (:require [clojure.string :as str]
            [clojure.tools.logging :refer [info warn]]
            [jepsen [control :as c]
                    [db :as db]
                    [nemesis :as nemesis]
                    [util :as util]]
            [jepsen.control.net :as cn]))

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

(defn conf
  "postgresql.conf additions. :sync is Zigcube's supported setup
  (docs/operations.md); :async (no synchronous standby, a commit is
  acknowledged once it is on the primary's disk) is the documented
  unsupported one, used only by the negative control."
  [replication]
  (str "listen_addresses = '*'\n"
       "wal_level = replica\n"
       "max_wal_senders = 10\n"
       "hot_standby = on\n"
       "wal_keep_size = '256MB'\n"
       (case replication
         :sync  (str "synchronous_commit = remote_apply\n"
                     "synchronous_standby_names = '*'\n")
         :async (str "synchronous_commit = on\n"
                     "synchronous_standby_names = ''\n"))
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

(defn init-primary! [test]
  (pg! :initdb :-D data-dir :-E :UTF8 :--locale=C.UTF-8)
  (c/su (c/exec :echo (conf (:replication test :sync))
                :>> (str data-dir "/postgresql.conf"))
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

(defn await-standby!
  "Blocks until the primary reports its standby: synchronous for
  :replication :sync, streaming for :async."
  [test primary]
  (let [sql (case (:replication test :sync)
              :sync  "select count(*) from pg_stat_replication where sync_state = 'sync'"
              :async "select count(*) from pg_stat_replication where state = 'streaming'")]
    (util/await-fn
      (fn []
        (let [n (pg! :psql :-h primary :-U :jepsen :-d :jepsen :-Atc sql)]
          (when-not (= "1" n)
            (throw (RuntimeException. (str "standbys: " n))))))
      {:retry-interval 500 :log-interval 5000 :timeout 90000
       :log-message "waiting for the standby"})))

(defn setup-node!
  "Sets up Postgres on this node if it is one of the pair."
  [test node]
  (let [[primary standby] (pg-nodes test)]
    (condp = node
      primary (do (wipe!)
                  (init-primary! test)
                  (await-standby! test primary)
                  ; Created here, once, rather than by racing clients. `ver`
                  ; is the row's version: 1 on insert, +1 on every update,
                  ; so the pg workload is a versioned register too.
                  (pg! :psql :-U :jepsen :-d :jepsen :-v "ON_ERROR_STOP=1" :-c
                       (str "CREATE TABLE registers "
                            "(k bigint PRIMARY KEY, v bigint NOT NULL,"
                            " ver bigint NOT NULL)")))
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

(defn isolate-pair!
  "Drops all traffic between nodes a and b, both ways, with rules of its own
  (heal-pair! removes exactly these, leaving any other partition alone)."
  [test a b]
  (let [ips {a (cn/ip b) b (cn/ip a)}]
    (c/on-nodes test [a b]
                (fn [_ node]
                  (c/su (c/exec :iptables :-A :INPUT :-s (ips node) :-j :DROP
                                :-w))))))

(defn heal-pair!
  "Removes isolate-pair!'s rules (all copies; none is fine)."
  [test a b]
  (let [ips {a (cn/ip b) b (cn/ip a)}]
    (c/on-nodes test [a b]
                (fn [_ node]
                  (c/su (c/exec :bash :-c
                                (str "while iptables -w -D INPUT -s " (ips node)
                                     " -j DROP 2>/dev/null; do :; done")))))))

(defn promote! [test node]
  (c/on-nodes test [node]
              (fn [_ _] (pg! :pg_ctl :-D data-dir :-w :-t 60 :promote))))

(defn store-rows
  "The store's state as one psql query on `node`, for the final store read.
  `sql` must return rows of tab-separated columns. Returns the rows as vectors
  of strings."
  [test node sql]
  (let [out (-> (c/on-nodes test [node]
                            (fn [_ _]
                              (pg! :psql :-h node :-U :jepsen :-d :jepsen
                                   :-v "ON_ERROR_STOP=1" :-At :-F "\t"
                                   :-c sql)))
                (get node))]
    (->> (str/split-lines out)
         (remove str/blank?)
         (mapv #(str/split % #"\t" 3)))))

(defn failover-nemesis
  "Handles three ops.

  :failover    How depends on the test's :failover mode.
               :fenced    SIGKILLs the primary and checks that no postgres
                          process is left on it (fence!); only then promotes
                          the standby. If the old primary cannot be confirmed
                          stopped, nothing is promoted and the op's value is
                          [:failover-failed ...], which fails the run.
               :unfenced  Cuts the primary off from the standby (only from
                          it: the server and the clients still reach it),
                          promotes the standby with the old primary alive,
                          and repoints the server only :repoint-delay-ms
                          later. Two primaries exist until :rejoin.
               :lagged    Cuts the standby off for :lag-ms while the primary
                          keeps acknowledging (only an async standby lets it),
                          then fails over as :fenced. With :replication
                          :async this loses acknowledged writes for certain:
                          the negative control.
  :rejoin      Heals the pair, re-seeds the old primary from the new one as
               its standby (which kills an old primary still running). With
               a synchronous standby, commits on the new primary block until
               this succeeds.
  :store-read  Reads the register state straight from the current primary
               with `store-sql`, for a check that does not depend on the
               server answering (a halted server answers nothing). The value
               is {:node n :rows [[col ...] ...]}.

  `state` is an atom {:primary node, :standby node, :standby-up? bool,
  :failovers n}. `on-promote`, if given, is called with the test and the new
  primary after each promotion (to repoint whatever names the primary)."
  [state on-promote store-sql]
  (reify nemesis/Nemesis
    (setup! [this test]
      (let [[p s] (pg-nodes test)]
        (reset! state {:primary p :standby s :standby-up? true :failovers 0}))
      this)
    (invoke! [_ test op]
      (let [{:keys [primary standby standby-up?]} @state
            mode    (:failover test :fenced)
            repoint (fn [] (when on-promote (on-promote test standby)))
            done    (fn [value]
                      (swap! state #(-> %
                                        (assoc :primary standby :standby primary
                                               :standby-up? false)
                                        (update :failovers inc)))
                      (assoc op :value value))]
        (case (:f op)
          :failover
          (if-not standby-up?
            (assoc op :value [:skipped :no-standby])
            (try
              (case mode
                :fenced
                (do (c/on-nodes test [primary] (fn [_ _] (fence!)))
                    (promote! test standby)
                    (repoint)
                    (done [:promoted standby :killed primary :fenced]))

                :unfenced
                (let [d (:repoint-delay-ms test 5000)]
                  (isolate-pair! test primary standby)
                  (promote! test standby)
                  (Thread/sleep (long d))
                  (repoint)
                  (done [:promoted standby :unfenced primary
                         :repointed-after-ms d]))

                :lagged
                (let [lag (:lag-ms test 4000)]
                  (isolate-pair! test primary standby)
                  (Thread/sleep (long lag))
                  (c/on-nodes test [primary] (fn [_ _] (fence!)))
                  (promote! test standby)
                  (repoint)
                  (heal-pair! test primary standby)
                  (done [:promoted standby :killed primary :lagged-ms lag])))
              (catch Exception e
                (warn e "failover failed")
                (assoc op :value [:failover-failed primary (.getMessage e)]))))

          :rejoin
          (if standby-up?
            (assoc op :value [:skipped :standby-up])
            (try
              (heal-pair! test primary standby)
              (c/on-nodes test [standby] (fn [_ _] (seed-standby! primary)))
              (c/on-nodes test [primary] (fn [_ _] (await-standby! test primary)))
              (swap! state assoc :standby-up? true)
              (assoc op :value [:rejoined standby :primary primary])
              (catch Exception e
                (warn e "rejoin failed")
                (assoc op :value [:rejoin-failed standby (.getMessage e)]))))

          :store-read
          (try
            ; From the true current primary: `state` follows the nemesis's
            ; own promotions, so check that the node is not in recovery.
            (let [rec (-> (c/on-nodes test [primary]
                                      (fn [_ _]
                                        (pg! :psql :-h primary :-U :jepsen
                                             :-d :jepsen :-Atc
                                             "SELECT pg_is_in_recovery()")))
                          (get primary) str/trim)]
              (if (not= "f" rec)
                (assoc op :value [:store-read-failed primary
                                  (str "pg_is_in_recovery: " rec)])
                (assoc op :value {:node primary
                                  :rows (store-rows test primary store-sql)})))
            (catch Exception e
              (warn e "store read failed")
              (assoc op :value [:store-read-failed primary (.getMessage e)]))))))
    (teardown! [_ test])))
