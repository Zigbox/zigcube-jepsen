(ns zcube-jepsen.checkers-test
  "The versioned-register encoding and the outage measure, on small
  hand-written histories: a clean one, a lost acknowledged write (what the
  negative control must catch), a version re-used for two values, and a
  stale read."
  (:require [clojure.test :refer [deftest is testing]]
            [elle.list-append :as la]
            [jepsen.checker :as checker]
            [jepsen.history :as h]
            [zcube-jepsen.checkers :as c]))

(defn hist
  "[process type f value version?] tuples, a millisecond apart."
  [& ops]
  (h/history
    (vec (map-indexed
           (fn [i [p type f value ver]]
             (cond-> {:index i :time (* i 1000000) :process p :type type
                      :f f :value value}
               ver (assoc :version ver)))
           ops))))

(defn store-op [rows]
  [:nemesis :info :store-read {:node "n1" :rows rows}])

(def parse   ; rows are [k v ver]
  (fn [rows] (into {} (for [[k v ver] rows] [k [v ver]]))))

(defn elle [history]
  (let [store (c/store-read history parse)
        vo    (c/version-orders (c/observations history store))]
    {:vo vo
     :res (la/check {:consistency-models [:strict-serializable]}
                    (c/elle-history history (:orders vo) store))}))

(def clean
  [[0 :invoke :write [1 0]]      [0 :ok :write [1 0] 1]
   [1 :invoke :cas [1 [0 1]]]    [1 :ok :cas [1 [0 1]] 2]
   [0 :invoke :read [1 nil]]     [0 :ok :read [1 1] 2]
   [1 :invoke :write [1 2]]      [1 :ok :write [1 2] 3]
   [1 :invoke :cas [1 [1 3]]]    [1 :fail :cas [1 [1 3]]]
   [0 :invoke :read [1 nil]]     [0 :ok :read [1 2] 3]])

(deftest clean-history
  (let [{:keys [vo res]} (elle (apply hist (conj clean (store-op [[1 2 3]]))))]
    (is (empty? (:version-conflicts vo)))
    (is (empty? (:value-conflicts vo)))
    (is (true? (:valid? res)) (pr-str res))))

(deftest lost-acknowledged-write
  (testing "the store came back at version 2: the acked write of 2 is gone"
    (let [{:keys [res]} (elle (apply hist (conj clean (store-op [[1 1 2]]))))]
      (is (false? (:valid? res)) (pr-str res)))))

(deftest version-reused
  (testing "after a loss, a new write lands on a version a lost write had"
    (let [h (apply hist (concat clean
                                [[0 :invoke :write [1 9]] [0 :ok :write [1 9] 3]]))
          {:keys [vo]} (elle h)]
      (is (= [{:key 1 :version 3 :values [2 9]}] (:version-conflicts vo))))))

(deftest stale-read
  (testing "a read after the write's ack sees the value before it"
    (let [h (hist [0 :invoke :write [1 0]] [0 :ok :write [1 0] 1]
                  [0 :invoke :write [1 1]] [0 :ok :write [1 1] 2]
                  [1 :invoke :read [1 nil]] [1 :ok :read [1 0] 1])
          {:keys [res]} (elle h)]
      (is (false? (:valid? res)) (pr-str res)))))

(deftest outage-per-failover
  (let [h (hist [0 :invoke :write [1 0]]                        ; 0
                [0 :ok :write [1 0] 1]                          ; 1
                [:nemesis :info :failover nil]                  ; 2
                [:nemesis :info :failover [:promoted "n2"]]     ; 3
                [0 :invoke :write [1 1]]                        ; 4
                [0 :fail :write [1 1]]                          ; 5
                [0 :invoke :write [1 2]]                        ; 6
                [0 :ok :write [1 2] 2])                         ; 7
        r (checker/check (c/outage) {} h {})
        [fo] (:failovers r)]
    (is (= 5 (:writes-ok-after fo)))   ; 7ms - 2ms
    (is (= 6 (:gap fo)))               ; 7ms - 1ms
    (is (= 1 (:promoted fo)))
    (is (= 1 (:failed fo)))
    (is (= 0 (:never-recovered r)))))
