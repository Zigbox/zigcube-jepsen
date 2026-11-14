(ns zcube-jepsen.core
  (:require [clojure.java.io :as io]
            [clojure.tools.logging :refer [info]]
            [jepsen [checker :as checker]
                    [cli :as cli]
                    [client :as client]
                    [generator :as gen]
                    [independent :as independent]
                    [nemesis :as nemesis]
                    [os :as os]
                    [tests :as tests]]
            [jepsen.checker.timeline :as timeline]
            [knossos.model :as model]
            [zcube-jepsen [api :as api]
                          [checkers :as checkers]
                          [lease :as lease]
                          [lease-checkers :as lc]
                          [nemesis :as zn]
                          [postgres :as pg]
                          [register :as register]
                          [zcube :as zcube]]))

(defn r   [_ _] {:type :invoke :f :read  :value nil})
(defn w   [_ _] {:type :invoke :f :write :value (rand-int 5)})
(defn cas [_ _] {:type :invoke :f :cas   :value [(rand-int 5) (rand-int 5)]})

(defn unique-writes
  "A write op generator whose values are never repeated within one key (call it
  once per key; the two threads of a key share the counter). With a small
  random domain an :info write of value x could explain any later read of x,
  hiding a rollback; with unique values a read names the exact write it saw."
  []
  (let [n (atom -1)]
    (fn [_ _] {:type :invoke :f :write :value (swap! n inc)})))

(defn unique-ops
  "Writes and cas ops whose new values are never repeated within one key (call
  it once per key). A cas expects the value of the latest write handed out
  (not a cas's: most of those fail), so a fair share of them succeed. Before
  the key's first write, it writes."
  []
  (let [n      (atom -1)
        last-w (atom nil)
        write  (fn [] (let [v (swap! n inc)]
                        (reset! last-w v)
                        {:type :invoke :f :write :value v}))]
    [(fn [_ _] (write))
     (fn [_ _] (if-let [old @last-w]
                 {:type :invoke :f :cas :value [old (swap! n inc)]}
                 (write)))]))

(defn nemesis-schedule
  "Partition, heal, fail over, rejoin; repeat."
  []
  (cycle [(gen/sleep 8)
          {:type :info :f :start-partition}
          (gen/sleep 8)
          {:type :info :f :stop-partition}
          (gen/sleep 4)
          {:type :info :f :failover}
          (gen/sleep 3)
          {:type :info :f :rejoin}]))

(def workloads
  "pg:  CAS registers straight on the Postgres pair (the store alone).
  api: registers through zcube-server's HTTP API (read and write only).
  :ops is called once per key, so per-key state (the write counter) is fresh."
  {"pg"  {:name       "zcube-register-pg"
          :db         pg/db
          :client     register/client
          :ops        (fn [] (cons r (unique-ops)))
          :fs         [:read :write :cas]
          :model      model/cas-register
          :on-promote nil
          :store-sql  register/store-sql
          :parse-store register/parse-store-rows}
   "api" {:name       "zcube-register-api"
          :db         zcube/db
          :client     api/client
          :ops        (fn [] [r (unique-writes)])
          :fs         [:read :write]
          :model      model/register
          :on-promote zcube/on-promote
          :store-sql  api/store-sql
          :parse-store api/parse-store-rows}})

(defn final-phase-nemesis
  "Handles :final-phase: flips `final?` so `retrying` knows the final reads
  have started, and marks their start in the history."
  [final?]
  (reify nemesis/Nemesis
    (setup! [this test] this)
    (invoke! [_ test op] (reset! final? true) (assoc op :value :started))
    (teardown! [_ test])))

(defn retrying
  "Wraps a client so that, once `final?` is set, a read that does not return
  :ok is retried (the cluster may still be settling) a bounded number of
  times."
  [c final?]
  (reify client/Client
    (open! [_ test node] (retrying (client/open! c test node) final?))
    (setup! [this test] (client/setup! c test) this)
    (invoke! [_ test op]
      (loop [tries 0]
        (let [res (client/invoke! c test op)]
          (if (and @final? (not= :ok (:type res)) (< tries 14))
            (do (Thread/sleep 2000) (recur (inc tries)))
            res))))
    (teardown! [_ test] (client/teardown! c test))
    (close! [_ test] (client/close! c test))))

(defn final-reads
  "Two reads of every key a worker was ever given, after heal and settle. The
  key range is taken when the phase starts (a delay, and a lazy take-while),
  not when the generator is built, so it sees every key; keys are the
  contiguous prefix of (range) that the main phase consumed."
  [keys*]
  (delay
    (independent/concurrent-generator
      2 (take-while #(contains? @keys* %) (range))
      (fn [_] (gen/limit 2 (repeat {:type :invoke :f :read :value nil}))))))

(defn register-test
  [opts]
  (let [state (atom nil)
        keys* (atom (sorted-set))   ; every key a worker was ever given
        final? (atom false)
        wl    (workloads (:workload opts))]
    (merge tests/noop-test
           opts
           {:name      (:name wl)
            :os        os/noop
            :db        ((:db wl))
            :client    (retrying ((:client wl)) final?)
            :nemesis   (nemesis/compose
                         {{:start-partition :start
                           :stop-partition  :stop} (zn/partition-nemesis)
                          #{:failover :rejoin :store-read}
                          (pg/failover-nemesis state (:on-promote wl)
                                               (:store-sql wl))
                          #{:final-phase}          (final-phase-nemesis final?)})
            :checker   (checkers/verdict
                         {:linear (independent/checker
                                    (checker/compose
                                      {:linear   (checker/linearizable
                                                   {:model     ((:model wl))
                                                    :algorithm :linear})
                                       :timeline (timeline/html)}))
                          :elle   (checkers/versioned-register (:parse-store wl))
                          :outage (checkers/outage)
                          :sanity (checkers/sanity (:fs wl) (:min-failovers opts))})
            :generator (gen/phases
                         (->> (independent/concurrent-generator
                                2 (range)
                                (fn [k]
                                  (swap! keys* conj k)
                                  (->> (gen/mix ((:ops wl)))
                                       (gen/stagger 1/10)
                                       (gen/limit (:ops-per-key opts)))))
                              (gen/nemesis (nemesis-schedule))
                              (gen/time-limit (:time-limit opts)))
                         (gen/log "Healing and rejoining")
                         (gen/nemesis [{:type :info :f :stop-partition}
                                       {:type :info :f :rejoin}])
                         (gen/log "Letting the cluster settle")
                         (gen/nemesis (gen/sleep 15))
                         (gen/log "Final reads of every key")
                         (gen/nemesis {:type :info :f :final-phase})
                         (gen/clients (final-reads keys*))
                         (gen/log "Reading the store directly")
                         (gen/nemesis {:type :info :f :store-read}))})))

;; ---------------------------------------------------------------- lease

(defn lease-schedule
  "valid: an agent cut off from the server, then the server cut off from
  Postgres, each for longer than the lease (the ttl plus the lost grace and
  then some, so that the node is lost and its replicas are replaced).
  wedge (the negative control): an agent stopped with SIGSTOP as long.
  --lease-faults picks one; by default wedge with --expect overlap, else
  partition."
  [opts]
  (let [hold (gen/sleep (/ (:fault-ms opts) 1000))
        gap  (gen/sleep 15)]
    (case (or (:lease-faults opts)
              (if (= :overlap (:expect opts)) :wedge :partition))
      :wedge (cycle [gap {:type :info :f :wedge} hold
                       {:type :info :f :unwedge}])
      (cycle [gap {:type :info :f :isolate-agent} hold {:type :info :f :heal}
              gap {:type :info :f :isolate-store} hold {:type :info :f :heal}]))))

(defn lease-checker
  "zcube-jepsen.lease-checkers/analyze over the history, with the constants
  of the Zigcube that ran (read from its src/constants.zig)."
  []
  (reify checker/Checker
    (check [_ test history opts]
      (let [ops     (filter #(not= :invoke (:type %)) history)
            collect (->> ops (filter #(= :collect (:f %))) last :value)
            text    (slurp (io/file (:zcube-constants test)))]
        (let [r (lc/analyze {:constants     (lc/parse-constants text)
                     :ttl           (:lease-ttl-ms test)
                     :grace         (:lost-grace-ms test)
                     :collect       (when (map? collect) collect)
                     :applies       (filter #(and (= :apply (:f %))
                                                  (number? (:process %)))
                                            ops)
                     ; A nemesis op is :info both when invoked and when
                     ; done; the completion is the one with a value.
                     :faults        (filter #(and (= :nemesis (:process %))
                                                  (some? (:value %))
                                                  (not= :collect (:f %)))
                                            ops)
                     :expect        (:expect test)
                     :min-handovers (:min-handovers test)})]
            (info "lease:" (pr-str
                             {:valid? (:valid? r) :expected (:expected r)
                              :i1a (select-keys (:i1a r) [:valid? :revisions-checked
                                                          :replicas-assigned :gaps])
                              :i1b (select-keys (:i1b r) [:valid? :bound-ms :max-overlap-ms
                                                          :max-overlap-upper-ms :handovers
                                                          :cross-node-handovers])
                              :fencing (select-keys (:fencing r) [:valid? :ttl-ms :fence-margin-ms
                                                                  :margin-needed-ms :i1b-bound-ms])
                              :sanity (:sanity r)}))
            (info "lease: overlaps (ms, negative = a gap):"
                  (pr-str (:all-overlaps-ms (:i1b r))))
            r)))))

(defn lease-test
  [opts]
  (let [n (:services opts)]
    (merge tests/noop-test
           opts
           {:name      (str "zcube-lease-" (name (:expect opts))
                             (some->> (:lease-faults opts) name (str "-")))
            :os        os/noop
            :db        (lease/db)
            :client    (lease/client)
            :nemesis   (lease/lease-nemesis)
            :checker   (checker/compose
                         {:lease    (lease-checker)
                          :timeline (timeline/html)})
            :generator (gen/phases
                         (gen/log "Applying services of one replica")
                         (gen/clients (for [k (range n)]
                                        {:type :invoke :f :apply :value k}))
                         (gen/log "Letting them be placed and started")
                         (gen/sleep 15)
                         (->> (gen/stagger 2 (repeat {:type :invoke :f :read}))
                              (gen/nemesis (lease-schedule opts))
                              (gen/time-limit (:time-limit opts)))
                         (gen/log "Healing")
                         (gen/nemesis [{:type :info :f :heal}
                                       {:type :info :f :unwedge}])
                         (gen/log "Letting the replicas settle")
                         (gen/sleep 30)
                         (gen/clients {:type :invoke :f :read})
                         (gen/log "Collecting the watch and the records")
                         (gen/nemesis {:type :info :f :collect}))})))

(defn any-test
  [opts]
  (if (= "lease" (:workload opts))
    (lease-test opts)
    (register-test opts)))

(def cli-opts
  [[nil "--workload NAME"
    (str "pg (the store alone), api (through zcube-server), or lease (leases"
         " and fencing: replicas on the agents, I1a, I1b, the inequality).")
    :default "api" :validate [#(or (workloads %) (= "lease" %))
                              "must be pg, api or lease"]]
   [nil "--zcube-constants FILE"
    "lease: the src/constants.zig of the Zigcube that runs."
    :default "zigcube/src/constants.zig"]
   [nil "--lost-grace-ms N" "zcube-server's --lost-grace-ms."
    :default 1000 :parse-fn parse-long]
   [nil "--services N" "lease: services of one replica."
    :default 6 :parse-fn parse-long]
   [nil "--fault-ms N" "lease: how long each fault holds (longer than the ttl)."
    :default 25000 :parse-fn parse-long]
   [nil "--lease-faults KIND"
    "lease: partition (an agent from the server, the server from Postgres) or wedge (SIGSTOP an agent node). Default: wedge with --expect overlap, else partition."
    :parse-fn keyword
    :validate [#{:partition :wedge} "must be partition or wedge"]]
   [nil "--min-handovers N"
    "lease: fail the run if fewer replicas were replaced onto another node."
    :default 1 :parse-fn parse-long]
   [nil "--zcube-bin DIR" "Directory holding zcube-server and zcube-agent."
    :default "zigcube/zig-out/bin"]
   [nil "--zcube-images FILE" "The fake runtime's image list."
    :default "zigcube/fixtures/images.zon"]
   [nil "--lease-ttl-ms N" "zcube-server's --lease-ttl-ms."
    :default 10000 :parse-fn parse-long]
   [nil "--ops-per-key N" "Operations per independent key."
    :default 60 :parse-fn parse-long]
   [nil "--min-failovers N" "Fail the run if fewer failovers completed."
    :default 1 :parse-fn parse-long]
   [nil "--failover MODE"
    (str "fenced: stop the old primary, then promote. unfenced: cut the pair"
         " apart and promote with the old primary still up; api: zcube-server"
         " stays on it for --repoint-delay-ms; pg: the JDBC URL lists both"
         " hosts, so clients may reach either primary at once. With"
         " --replication sync neither side can commit until the rejoin."
         " lagged: cut the pair apart"
         " for --lag-ms, then fence and promote (only loses writes with"
         " --replication async).")
    :default :fenced :parse-fn keyword
    :validate [#{:fenced :unfenced :lagged} "must be fenced, unfenced or lagged"]]
   [nil "--replication MODE"
    "sync (remote_apply, the supported setup) or async (the negative control)."
    :default :sync :parse-fn keyword
    :validate [#{:sync :async} "must be sync or async"]]
   [nil "--expect OUTCOME"
    (str "valid: the run must find nothing. lost-write: the negative control;"
         " the run passes only if the checkers catch a loss. overlap (lease):"
         " the wedged-agent control; passes only if I1b is caught broken.")
    :default :valid :parse-fn keyword
    :validate [#{:valid :lost-write :overlap}
               "must be valid, lost-write or overlap"]]
   [nil "--repoint-delay-ms N" "unfenced, api only: how long zcube-server stays on the old primary (pg clients have no repoint)."
    :default 5000 :parse-fn parse-long]
   [nil "--lag-ms N" "lagged: how long the standby is cut off before promotion."
    :default 4000 :parse-fn parse-long]
   [nil "--[no-]client-partition"
    "Put the control host (the clients) on one side of every partition."
    :default true]])

(defn -main [& args]
  (cli/run! (merge (cli/single-test-cmd {:test-fn  any-test
                                         :opt-spec cli-opts})
                   (cli/serve-cmd))
            args))
