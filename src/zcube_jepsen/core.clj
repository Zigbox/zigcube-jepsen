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
            [zcube-jepsen [postgres :as pg]
                          [register :as register]]))

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

(defn sanity-checker
  "Guards against a vacuous pass: the run must have completed operations of
  every kind and at least `min-failovers` failovers."
  [min-failovers]
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
        {:valid?    (boolean (and (every? #(pos? (get oks % 0)) [:read :write :cas])
                                  (<= min-failovers fos)))
         :ok-counts oks
         :failovers fos
         :rejoins   rejoins}))))

(defn register-test
  [opts]
  (let [state (atom nil)]
    (merge tests/noop-test
           opts
           {:name      "zcube-register-pg"
            :os        os/noop
            :db        (pg/db)
            :client    (register/client)
            :nemesis   (nemesis/compose
                         {{:start-partition :start
                           :stop-partition  :stop} (nemesis/partition-random-halves)
                          #{:failover :rejoin}     (pg/failover-nemesis state)})
            :checker   (checker/compose
                         {:linear (independent/checker
                                    (checker/compose
                                      {:linear   (checker/linearizable
                                                   {:model     (model/cas-register)
                                                    :algorithm :linear})
                                       :timeline (timeline/html)}))
                          :sanity (sanity-checker (:min-failovers opts))})
            :generator (gen/phases
                         (->> (independent/concurrent-generator
                                2 (range)
                                (fn [k]
                                  (->> (gen/mix [r w cas])
                                       (gen/stagger 1/10)
                                       (gen/limit (:ops-per-key opts)))))
                              (gen/nemesis (nemesis-schedule))
                              (gen/time-limit (:time-limit opts)))
                         (gen/log "Healing and rejoining")
                         (gen/nemesis [{:type :info :f :stop-partition}
                                       {:type :info :f :rejoin}]))})))

(def cli-opts
  [[nil "--ops-per-key N" "Operations per independent key."
    :default 60 :parse-fn parse-long]
   [nil "--min-failovers N" "Fail the run if fewer failovers completed."
    :default 1 :parse-fn parse-long]])

(defn -main [& args]
  (cli/run! (merge (cli/single-test-cmd {:test-fn  register-test
                                         :opt-spec cli-opts})
                   (cli/serve-cmd))
            args))
