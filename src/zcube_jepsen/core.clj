(ns zcube-jepsen.core
  (:require [clojure.tools.logging :refer [info]]
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
          :ops        (fn [] [r w cas])   ; cas needs a small value domain
          :fs         [:read :write :cas]
          :model      model/cas-register
          :on-promote nil}
   "api" {:name       "zcube-register-api"
          :db         zcube/db
          :client     api/client
          :ops        (fn [] [r (unique-writes)])
          :fs         [:read :write]
          :model      model/register
          :on-promote zcube/on-promote}})

(defn error-codes
  "Every string inside an op's :error, so a code is found whatever the shape."
  [op]
  (filter string? (tree-seq coll? seq (:error op))))

(defn sanity-checker
  "Guards against a vacuous pass. The run fails unless:
  - it completed operations of every kind in `fs`;
  - at least `min-failovers` failovers completed, and none failed (the old
    primary was not confirmed stopped, or the promotion threw);
  - no op saw ZC-E0215: Zigcube halts with it when it detects that a
    failover lost committed writes, and answers every later call 502, which
    the register client records as :info/:fail, so Knossos cannot see it;
  - every key that got a final read (after heal, rejoin and a settle) got at
    least one :ok final read, so a loss cannot hide behind a key that was
    never read again."
  [fs min-failovers]
  (reify checker/Checker
    (check [_ test history opts]
      (let [oks  (->> history
                      (filter #(and (= :ok (:type %)) (number? (:process %))))
                      (map :f) frequencies)
            nem  (fn [f] (filter #(and (= :nemesis (:process %)) (= f (:f %))
                                       (= :info (:type %))
                                       (some? (:value %)))
                                 history))
            fos  (count (filter #(= :promoted (first (:value %))) (nem :failover)))
            bad-fos (filter #(= :failover-failed (first (:value %))) (nem :failover))
            rejoins (count (filter #(= :rejoined (first (:value %))) (nem :rejoin)))
            halted  (->> history
                         (filter #(some #{"ZC-E0215"} (error-codes %)))
                         (map #(select-keys % [:index :process :f :type :error]))
                         (take 5))
            ; Final reads: client ops after the :final-phase nemesis op. (Not
            ; marked on the ops: an extra key on an op corrupts how Knossos
            ; reads the per-key histories.)
            start   (or (->> history
                             (filter #(and (= :nemesis (:process %))
                                           (= :final-phase (:f %))))
                             (map :index) first)
                        Long/MAX_VALUE)
            final   (filter #(and (number? (:process %)) (> (:index %) start))
                            history)
            final-keys    (->> final (filter #(= :invoke (:type %)))
                               (map #(first (:value %))) set)
            final-ok-keys (->> final (filter #(= :ok (:type %)))
                               (map #(first (:value %))) set)
            final-missing (sort (remove final-ok-keys final-keys))]
        {:valid?    (boolean (and (every? #(pos? (get oks % 0)) fs)
                                  (<= min-failovers fos)
                                  (empty? bad-fos)
                                  (empty? halted)
                                  (seq final-keys)
                                  (empty? final-missing)))
         :ok-counts oks
         :failovers fos
         :failover-failures (map :value bad-fos)
         :rejoins   rejoins
         :halted-ops (vec halted)
         :final-read-keys   (count final-keys)
         :final-reads-ok    (count (filter #(= :ok (:type %)) final))
         :final-read-keys-without-ok (vec final-missing)}))))

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
                           :stop-partition  :stop} (nemesis/partition-random-halves)
                          #{:failover :rejoin}     (pg/failover-nemesis state (:on-promote wl))
                          #{:final-phase}          (final-phase-nemesis final?)})
            :checker   (checker/compose
                         {:linear (independent/checker
                                    (checker/compose
                                      {:linear   (checker/linearizable
                                                   {:model     ((:model wl))
                                                    :algorithm :linear})
                                       :timeline (timeline/html)}))
                          :sanity (sanity-checker (:fs wl) (:min-failovers opts))})
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
                         (gen/clients (final-reads keys*)))})))

(def cli-opts
  [[nil "--workload NAME" "pg (the store alone) or api (through zcube-server)."
    :default "api" :validate [workloads "must be pg or api"]]
   [nil "--zcube-bin DIR" "Directory holding zcube-server and zcube-agent."
    :default "zigcube/zig-out/bin"]
   [nil "--zcube-images FILE" "The fake runtime's image list."
    :default "zigcube/fixtures/images.zon"]
   [nil "--lease-ttl-ms N" "zcube-server's --lease-ttl-ms."
    :default 10000 :parse-fn parse-long]
   [nil "--ops-per-key N" "Operations per independent key."
    :default 60 :parse-fn parse-long]
   [nil "--min-failovers N" "Fail the run if fewer failovers completed."
    :default 1 :parse-fn parse-long]])

(defn -main [& args]
  (cli/run! (merge (cli/single-test-cmd {:test-fn  register-test
                                         :opt-spec cli-opts})
                   (cli/serve-cmd))
            args))
