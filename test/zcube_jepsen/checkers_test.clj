(ns zcube-jepsen.checkers-test
  "The versioned-register encoding and the outage measure, on small
  hand-written histories: a clean one, a lost acknowledged write (what the
  negative control must catch), a version re-used for two values, and a
  stale read."
  (:require [clojure.test :refer [deftest is testing]]
            [elle.list-append :as la]
            [jepsen.checker :as checker]
            [jepsen.history :as h]
            [jepsen.store :as store]
            [zcube-jepsen.api :as api]
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

;; ---------------------------------------------------------------------------
;; api: global revisions, checked against the surviving store's log

(defn api-store
  "A store read in api's parsed shape: live {k [v rev]}, chains {k {rev v}}."
  [live chains compacted]
  [:nemesis :info :store-read {:node "n1" :rows {:live live :chains chains
                                                 :compacted compacted}}])

(def api-parse identity)

(defn vr [history]
  ; Elle writes its explanations under the test's store directory.
  (let [dir (doto (java.io.File/createTempFile "elle" "") (.delete) (.mkdirs))]
    (with-redefs [store/path! (fn [& _] dir)]
      (checker/check (c/versioned-register api-parse) {} history {}))))

(def splice
  ; The reviewer's history: 1 is acked at revision 100 on the old primary,
  ; lost in the failover; the store's revision rewinds, other keys move it
  ; past 100, and 2 lands at 103. No version is re-used.
  [[0 :invoke :write [1 0]] [0 :ok :write [1 0] 90]
   [0 :invoke :write [1 1]] [0 :ok :write [1 1] 100]
   [0 :invoke :write [1 2]] [0 :ok :write [1 2] 103]
   [1 :invoke :read [1 nil]] [1 :ok :read [1 2] 103]])

(deftest global-revision-splice
  (testing "without the log, the lost write is spliced in (why the log is read)"
    (let [{:keys [vo res]} (elle (apply hist (conj splice (store-op [[1 2 103]]))))]
      (is (empty? (:version-conflicts vo)))
      (is (true? (:valid? res)))))
  (testing "with the log, the acked write of 1 at 100 is off the chain"
    (let [r (vr (apply hist (conj splice (api-store {1 [2 103]}
                                                    {1 {90 0 103 2}} 0))))]
      (is (false? (:valid? r)) (pr-str r))
      (is (= [[1 100 1]] (:off-chain r)))))
  (testing "the same history with the write kept is clean"
    (let [r (vr (apply hist (conj splice (api-store {1 [2 103]}
                                                    {1 {90 0 100 1 103 2}} 0))))]
      (is (true? (:valid? r)) (pr-str r))
      (is (= 0 (:unverifiable r))))))

(deftest off-chain-read
  (testing "a read of a value the surviving store never had (its write timed out)"
    (let [h (hist [0 :invoke :write [1 0]] [0 :ok :write [1 0] 90]
                  [0 :invoke :write [1 5]] [0 :info :write [1 5]]
                  [1 :invoke :read [1 nil]] [1 :ok :read [1 5] 95]
                  (api-store {1 [0 90]} {1 {90 0}} 0))
          r (vr h)]
      (is (false? (:valid? r)))
      (is (= [[1 95 5]] (:off-chain r))))))

(deftest below-compaction-unverifiable
  (testing "below the compaction point only the kept put can be checked"
    (let [h (apply hist (conj splice (api-store {1 [2 103]} {1 {90 0 103 2}} 100)))
          r (vr h)]
      (is (empty? (:off-chain r)))
      (is (= 1 (:unverifiable r))))))

(deftest api-store-rows
  (let [j (fn [n] (str "{\"name\":\"r1x\",\"replicas\":" n "}"))]
    (is (= {:live {1 [2 103]} :chains {1 {90 0 103 2}} :compacted 7}
           (api/parse-store-rows
             [["compacted" "7" ""]
              ["live /services/r1x" "103" (j 3)]
              ["log /services/r1x" "90" (j 1)]
              ["log /services/r1x" "103" (j 3)]])))))

;; ---------------------------------------------------------------------------
;; Verdict: the negative control needs the checkers, not the halt

(defn verdict-of [results expect]
  (let [fixed (fn [r] (reify checker/Checker (check [_ _ _ _] r)))
        v (c/verdict {:linear (fixed (:linear results))
                      :elle   (fixed (:elle results))
                      :sanity (fixed (:sanity results))})]
    (checker/check v {:expect expect :failover :lagged} (hist) {})))

(deftest negative-control-needs-a-checker
  (let [sanity {:valid? true :halted? true :store-read {:node "n1" :rows 1}
                :final-read-keys-without-ok []}]
    (testing "halted, but Knossos and Elle passed: the control fails"
      (let [r (verdict-of {:linear {:valid? true} :elle {:valid? true}
                           :sanity sanity} :lost-write)]
        (is (false? (:valid? r)))
        (is (true? (:halted? r)))
        (is (= [] (:caught-by r)))))
    (testing "Elle caught it: the control passes"
      (let [r (verdict-of {:linear {:valid? true} :elle {:valid? false}
                           :sanity sanity} :lost-write)]
        (is (true? (:valid? r)))
        (is (= [:elle] (:caught-by r)))))))

;; ---------------------------------------------------------------------------
;; Outage: only writes issued after the promotion count

(deftest outage-ignores-old-primary-acks
  (testing "a write acked on the old primary before the promotion ends"
    (let [h (hist [:nemesis :info :failover nil]                ; 0
                  [0 :invoke :write [1 1]]                      ; 1
                  [0 :ok :write [1 1] 1]                        ; 2
                  [:nemesis :info :failover [:promoted "n2"]]   ; 3
                  [0 :invoke :write [1 2]]                      ; 4
                  [0 :fail :write [1 2]]                        ; 5
                  [0 :invoke :write [1 3]]                      ; 6
                  [0 :ok :write [1 3] 2])                       ; 7
          [fo] (:failovers (checker/check (c/outage) {} h {}))]
      (is (= 7 (:writes-ok-after fo))))))

(deftest outage-gap-counts-every-ack
  (testing "a write issued before the failover and acked during it splits the gap"
    (let [h (hist [0 :invoke :write [1 0]]                      ; 0
                  [0 :ok :write [1 0] 1]                        ; 1
                  [1 :invoke :write [1 1]]                      ; 2
                  [:nemesis :info :failover nil]                ; 3
                  [:nemesis :info :failover [:promoted "n2"]]   ; 4
                  [0 :invoke :write [1 2]]                      ; 5
                  [1 :ok :write [1 1] 2]                        ; 6
                  [2 :info :nothing nil]                        ; 7
                  [2 :info :nothing nil]                        ; 8
                  [0 :ok :write [1 2] 3])                       ; 9
          [fo] (:failovers (checker/check (c/outage) {} h {}))]
      (is (= 6 (:writes-ok-after fo)))    ; 9 - 3
      (is (= 5 (:gap fo))))))             ; 1 -> 6 (not 1 -> 9)
