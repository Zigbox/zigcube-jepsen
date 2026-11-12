(ns zcube-jepsen.core
  (:require [clojure.tools.logging :refer [info]]
            [jepsen [checker :as checker]
                    [cli :as cli]
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
  api: registers through zcube-server's HTTP API (read and write only)."
  {"pg"  {:name       "zcube-register-pg"
          :db         pg/db
          :client     register/client
          :ops        [r w cas]
          :fs         [:read :write :cas]
          :model      model/cas-register
          :on-promote nil}
   "api" {:name       "zcube-register-api"
          :db         zcube/db
          :client     api/client
          :ops        [r w]
          :fs         [:read :write]
          :model      model/register
          :on-promote zcube/on-promote}})

(defn sanity-checker
  "Guards against a vacuous pass: the run must have completed operations of
  every kind in `fs` and at least `min-failovers` failovers."
  [fs min-failovers]
  (reify checker/Checker
    (check [_ test history opts]
      (let [oks  (->> history
                      (filter #(and (= :ok (:type %)) (number? (:process %))))
                      (map :f) frequencies)
            fos  (->> history
                      (filter #(and (= :nemesis (:process %)) (= :failover (:f %))
                                    (= :promoted (first (:value %)))))
                      count)
            rejoins (->> history
                         (filter #(and (= :nemesis (:process %)) (= :rejoin (:f %))
                                       (= :rejoined (first (:value %)))))
                         count)]
        {:valid?    (boolean (and (every? #(pos? (get oks % 0)) fs)
                                  (<= min-failovers fos)))
         :ok-counts oks
         :failovers fos
         :rejoins   rejoins}))))

(defn register-test
  [opts]
  (let [state (atom nil)
        wl    (workloads (:workload opts))]
    (merge tests/noop-test
           opts
           {:name      (:name wl)
            :os        os/noop
            :db        ((:db wl))
            :client    ((:client wl))
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
                                  (->> (gen/mix (:ops wl))
                                       (gen/stagger 1/10)
                                       (gen/limit (:ops-per-key opts)))))
                              (gen/nemesis (nemesis-schedule))
                              (gen/time-limit (:time-limit opts)))
                         (gen/log "Healing and rejoining")
                         (gen/nemesis [{:type :info :f :stop-partition}
                                       {:type :info :f :rejoin}]))})))

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
