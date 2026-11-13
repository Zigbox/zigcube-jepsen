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
            final   (filter :final history)
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

(defn retrying
  "Wraps a client so a final read that does not return :ok is retried (the
  cluster may still be settling) a bounded number of times."
  [c]
  (reify client/Client
    (open! [_ test node] (retrying (client/open! c test node)))
    (setup! [this test] (client/setup! c test) this)
    (invoke! [_ test op]
      (loop [tries 0]
        (let [res (client/invoke! c test op)]
          (if (and (:final op) (not= :ok (:type res)) (< tries 14))
            (do (Thread/sleep 2000) (recur (inc tries)))
            res))))
    (teardown! [_ test] (client/teardown! c test))
    (close! [_ test] (client/close! c test))))

(defn register-test
  [opts]
  (let [state (atom nil)
        keys* (atom (sorted-set))   ; every key a worker was ever given
        wl    (workloads (:workload opts))]
    (merge tests/noop-test
           opts
           {:name      (:name wl)
            :os        os/noop
            :db        ((:db wl))
            :client    (retrying ((:client wl)))
            :nemesis   (nemesis/compose
                         {{:start-partition :start
                           :stop-partition  :stop} (nemesis/partition-random-halves)
                          #{:failover :rejoin}     (pg/failover-nemesis state (:on-promote wl))})
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
                         ; Built when this phase starts, so it sees every key.
                         (gen/clients
                           (delay
                             (independent/concurrent-generator
                               2 (vec @keys*)
                               (fn [_]
                                 (repeat 2 {:type :invoke :f :read
                                            :value nil :final true}))))))})))

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
