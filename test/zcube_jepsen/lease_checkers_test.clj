(ns zcube-jepsen.lease-checkers-test
  (:require [clojure.test :refer [deftest is testing]]
            [zcube-jepsen.lease-checkers :as lc]))

(def constants-zig
  "Lines as src/constants.zig has them (Zigbox/zigcube f7736a7)."
  (str "pub const lease_ttl_ms_min: u32 = 100;\n"
       "pub const lease_ttl_ms: u32 = node_lease_ttl_ms;\n"
       "pub const fence_stop_ms: u32 = 2000;\n"
       "/// The skew.\n"
       "pub const clock_skew_per_mille: u32 = 10;\n"
       "pub const fence_margin_ms: u32 = 3000;\n"
       "pub const fence_window_min_ms: u32 = 5000;\n"
       "pub const fence_poll_ms: u32 = 100;\n"
       "pub const lost_grace_ms: u32 = 1000;\n"
       "pub const lost_grace_ms_max: u32 = 60 * 60 * 1000;\n"
       "pub const fence_kill_latency_ms: u32 = 900;\n"
       "pub const stop_grace_ms: u32 = 5_000;\n"))

(def c (lc/parse-constants constants-zig))

(deftest parse-constants
  (is (= {:fence-stop-ms 2000 :clock-skew-per-mille 10 :fence-margin-ms 3000
          :fence-window-min-ms 5000 :fence-poll-ms 100 :lost-grace-ms 1000
          :fence-kill-latency-ms 900 :stop-grace-ms 5000}
         c)))

(deftest fencing
  (testing "the default ttl meets the inequality with no slack"
    (let [f (lc/fencing c 10000 1000)]
      (is (true? (:valid? f)))
      (is (= 3000 (:margin-needed-ms f)))
      (is (= 0 (:slack-ms f)))
      (is (= 1900 (:i1b-bound-ms f)))))
  (testing "a ttl the margin cannot cover (margin_too_small)"
    (is (false? (:valid? (lc/fencing c 10001 1000)))))
  (testing "a ttl too short for a renewal (ttl_too_short)"
    (is (false? (:valid? (lc/fencing c 7999 1000))))
    (is (true? (:valid? (lc/fencing c 8000 1000)))))
  (testing "constants that could not be read"
    (is (false? (:valid? (lc/fencing {} 10000 1000))))))

(defn ev [rev type k] {:rev rev :type type :key k})

(deftest i1a
  (testing "a replacement under a new id, and a release"
    (let [r (lc/i1a [(ev 5 :put "/assigned/n4/l0x-1")
                     (ev 5 :put "/replicas/l0x/l0x-1")
                     (ev 9 :delete "/replicas/l0x/l0x-1")
                     (ev 9 :put "/replicas/l0x/l0x-2")
                     (ev 10 :put "/assigned/n5/l0x-2")
                     (ev 11 :delete "/assigned/n4/l0x-1")]
                    [])]
      (is (true? (:valid? r)))
      (is (= 4 (:revisions-checked r)))
      (is (= 2 (:replicas-assigned r)))))
  (testing "two assignments of one replica at one revision"
    (let [r (lc/i1a [(ev 5 :put "/assigned/n4/l0x-1")
                     (ev 7 :put "/assigned/n5/l0x-1")
                     (ev 8 :delete "/assigned/n4/l0x-1")]
                    [])]
      (is (false? (:valid? r)))
      (is (= [{:rev 7 :replica "l0x-1" :nodes ["n4" "n5"]}] (:violations r)))))
  (testing "a move within one revision is not two assignments"
    (let [r (lc/i1a [(ev 5 :put "/assigned/n4/l0x-1")
                     (ev 7 :delete "/assigned/n4/l0x-1")
                     (ev 7 :put "/assigned/n5/l0x-1")]
                    [])]
      (is (true? (:valid? r)))
      (is (= [{:replica "l0x-1" :nodes ["n4" "n5"]}] (:moved r)))))
  (testing "a gap in the watch makes it unknown"
    (is (= :unknown (:valid? (lc/i1a [(ev 5 :put "/assigned/n4/l0x-1")
                                      (ev 6 :delete "/assigned/n4/l0x-1")]
                                     [{:from 3 :resumed 900}])))))
  (testing "nothing to check is unknown, not true"
    (is (= :unknown (:valid? (lc/i1a [] []))))
    (is (= :unknown (:valid? (lc/i1a [(ev 5 :put "/replicas/l0x/l0x-1")
                                      (ev 6 :delete "/replicas/l0x/l0x-1")]
                                     [])))
        "a renamed /assigned/ prefix")
    (is (= :unknown (:valid? (lc/i1a [(ev 5 :put "/assigned/n4/l0x-1")] [])))
        "no assignment ever deleted: no handover was replayed")
    (is (= :unknown (:valid? (lc/i1a [(ev 5 :put "/replicas/l0x/l0x-1")
                                      (ev 6 :delete "/assigned/n4/l0x-1")]
                                     [])))
        "no assignment ever put")
    (is (= :unknown (:valid? (lc/i1a [(ev 5 :put "/assigned/n4/l0x-1")
                                      (ev 6 :delete "/assigned/n4/l0x-1")
                                      (ev 7 :PUT "/assigned/n5/l0x-1")]
                                     [])))
        "an event type other than put and delete")
    (is (false? (:valid? (lc/i1a [(ev 5 :put "/assigned/n4/l0x-1")
                                  (ev 6 :put "/assigned/n5/l0x-1")]
                                 [])))
        "a violation is false even with no delete")
    (is (false? (:valid? (lc/i1a [(ev 5 :put "/assigned/n4/l0x-1")
                                  (ev 6 :modify "/assigned/n4/l0x-1")
                                  (ev 7 :put "/assigned/n5/l0x-1")
                                  (ev 8 :delete "/assigned/n4/l0x-1")]
                                 [])))
        "an unknown event type is not read as a delete")))

(defn evt [rev type k t] {:rev rev :type type :key k :t t})

(deftest assignment-periods
  (is (= [{:node "n4" :replica "l0x-1" :from-t 1000 :to-t 9000}
          {:node "n5" :replica "l0x-2" :from-t 9500 :to-t 60000}]
         (lc/assignment-periods [(evt 5 :put "/assigned/n4/l0x-1" 1000)
                                 (evt 6 :put "/assigned/n4/l0x-1" 2000)
                                 (evt 7 :put "/replicas/l0x/l0x-1" 2000)
                                 (evt 9 :delete "/assigned/n4/l0x-1" 9000)
                                 (evt 10 :put "/assigned/n5/l0x-2" 9500)]
                                60000))
      "a put of a key already present does not open a second period"))


(defn inst [node svc r pid start last & [term gap]]
  {:node node :svc svc :replica r :pid pid :start start :last last :term term
   :gap gap})

(deftest instance-end
  (is (= 1000.0 (lc/instance-end {:start 0.0 :last 1000.0 :term nil})))
  (is (= 1500.0 (lc/instance-end {:start 0.0 :last 1000.0 :term 1500.0}))
      "a TERM noted after the last heartbeat (killed before the next one)")
  (is (= 1000.0 (lc/instance-end {:start 0.0 :last 1000.0 :term 500.0}))))

(deftest i1b
  (testing "a fenced instance gone before its replacement: a gap, reported negative"
    (let [r (lc/i1b [(inst "n4" "l0x" "l0x-1" 10 1000.0 20000.0 18000.0)
                     (inst "n5" "l0x" "l0x-2" 11 21500.0 60000.0)
                     (inst "n5" "l1x" "l1x-1" 12 1000.0 60000.0)]
                    1900)]
      (is (true? (:valid? r)))
      (is (= 1 (:handovers r)))
      (is (= 1 (:cross-node-handovers r)))
      (is (= -1500.0 (:max-overlap-ms r)))
      (is (= -1450.0 (:max-overlap-upper-ms r)))))
  (testing "an overlap within the window"
    (is (true? (:valid? (lc/i1b [(inst "n4" "l0x" "l0x-1" 10 0.0 21800.0)
                                 (inst "n5" "l0x" "l0x-2" 11 20000.0 60000.0)]
                                1900)))))
  (testing "an overlap past the window (a wedged node)"
    (let [r (lc/i1b [(inst "n4" "l0x" "l0x-1" 10 0.0 45000.0 44000.0)
                     (inst "n5" "l0x" "l0x-2" 11 20000.0 60000.0)]
                    1900)]
      (is (false? (:valid? r)))
      (is (false? (:overlap-valid? r)))
      (is (= 25000.0 (:max-overlap-ms r)))
      (is (= 1 (count (:violations r))))))
  (testing "the bound, lower and upper"
    (let [at (fn [end & [gap]]
               (lc/i1b [(inst "n4" "l0x" "l0x-1" 10 0.0 end nil gap)
                        (inst "n5" "l0x" "l0x-2" 11 20000.0 60000.0)]
                       1900))]
      (is (true? (:valid? (at 21850.0))) "upper bound exactly at the window")
      (is (= :unknown (:valid? (at 21851.0))) "upper bound 1 ms over it")
      (is (= :unknown (:valid? (at 21900.0)))
          "lower bound exactly at the window: not certain, not proven within")
      (is (false? (:valid? (at 21901.0))) "lower bound 1 ms over it")
      (is (= 1500.0 (:max-overlap-ms (at 21500.0))))
      (is (= :unknown (:valid? (at 21500.0 800.0)))
          "a recorder that went 800 ms without a line: the upper bound grows")
      (is (= 2300.0 (:max-overlap-upper-ms (at 21500.0 800.0))))
      (is (= 800.0 (:max-heartbeat-gap-ms (at 21500.0 800.0))))))
  (testing "a TERM after the last heartbeat is the instance's end"
    (let [r (lc/i1b [(inst "n4" "l0x" "l0x-1" 10 0.0 21000.0 22000.0)
                     (inst "n5" "l0x" "l0x-2" 11 20000.0 60000.0)]
                    1900)]
      (is (= 2000.0 (:max-overlap-ms r)))
      (is (false? (:valid? r)))))
  (testing "the worst earlier instance counts, not just the latest"
    (let [r (lc/i1b [(inst "n4" "l0x" "l0x-1" 10 0.0 50000.0)
                     (inst "n5" "l0x" "l0x-2" 11 20000.0 21000.0)
                     (inst "n5" "l0x" "l0x-3" 12 30000.0 60000.0)]
                    1900)]
      (is (= 2 (:handovers r)))
      (is (= 20000.0 (:max-overlap-ms r)))))
  (testing "the worst earlier instance is neither the first nor the latest"
    (let [r (lc/i1b [(inst "n4" "l0x" "a" 1 0.0 10000.0)
                     (inst "n5" "l0x" "b" 2 5000.0 60000.0)
                     (inst "n4" "l0x" "c" 3 20000.0 25000.0)
                     (inst "n4" "l0x" "d" 4 40000.0 70000.0)]
                    1900)]
      (is (= 20000.0 (:max-overlap-ms r)))
      (is (= 20050.0 (:max-overlap-upper-ms r)))
      (is (= {:node "n5" :replica "b" :pid 2 :start 5000.0}
             (:old (first (:worst r)))))))
  (testing "handovers on one node are not cross-node"
    (let [r (lc/i1b [(inst "n4" "l0x" "l0x-1" 10 0.0 20000.0)
                     (inst "n4" "l0x" "l0x-2" 11 22000.0 60000.0)]
                    1900)]
      (is (= 1 (:handovers r)))
      (is (= 0 (:cross-node-handovers r)))))
  (testing "a KILL that came late, hidden from the overlap by a later replacement"
    (let [r (lc/i1b [(inst "n4" "l0x" "l0x-1" 10 0.0 20160.0 10000.0)
                     (inst "n5" "l0x" "l0x-2" 11 30000.0 60000.0)]
                    1900 2950)]
      (is (true? (:overlap-valid? r)))
      (is (false? (:valid? r)))
      (is (= 10160.0 (get-in r [:kill :max-term-to-last-ms])))
      (is (= 1 (count (get-in r [:kill :violations]))))))
  (testing "the kill bound, exactly and 1 ms over"
    (let [k (fn [last] (:valid? (lc/kill-timing
                                  [(inst "n4" "l0x" "l0x-1" 10 0.0 last 10000.0)]
                                  2950)))]
      (is (true? (k 12950.0)))
      (is (false? (k 12951.0))))))

(deftest parse-record-line
  (is (= {:node "n4" :svc "l0x" :replica "l0x-1" :pid 77
          :start 1763078400123.456 :last 1763078410000.0 :term nil :gap 52.0}
         (lc/parse-record-line
           "n4" "l0x l0x-1 77 1763078400.123456 1763078410.000000 - 0.052000")))
  (is (nil? (lc/parse-record-line "n4" "garbage")))
  (is (nil? (lc/parse-record-line "n4" "l0x l0x-1 x 1.0 2.0 - -"))))

(deftest parse-records
  (let [r (lc/parse-records
            "n4" (str "l0x l0x-1 77 1.000000 2.000000 - 0.050000\n"
                      "E /var/lib/zcube/rec/l1x-1.80.log no-start\n"
                      "\n"
                      "half a line\n"))]
    (is (= 1 (count (:instances r))))
    (is (= [{:node "n4" :line "E /var/lib/zcube/rec/l1x-1.80.log no-start"}
            {:node "n4" :line "half a line"}]
           (:errors r)))))

(defn period [node r from to] {:node node :replica r :from-t from :to-t to})

(deftest records-check
  (let [ps    [(period "n4" "l0x-1" 0 20000) (period "n5" "l0x-2" 21000 60000)]
        rs    [(inst "n4" "l0x" "l0x-1" 1 100.0 20000.0)
               (inst "n5" "l0x" "l0x-2" 2 21100.0 60000.0)]
        check #(lc/records-check %1 %2 %3 3000)]
    (is (true? (:valid? (check ps rs []))))
    (testing "an assigned replica with no record"
      (let [r (check ps [(first rs)] [])]
        (is (false? (:valid? r)))
        (is (= [["n5" "l0x-2"]] (:missing r)))))
    (testing "the record is on another node than the assignment"
      (is (false? (:valid? (check ps [(first rs) (assoc (second rs) :node "n4")] [])))))
    (testing "an assignment too short to have started needs no record"
      (let [r (check (conj ps (period "n4" "l1x-1" 30000 31000)) rs [])]
        (is (true? (:valid? r)))
        (is (= [["n4" "l1x-1"]] (:short-unrecorded r)))))
    (testing "an assignment of unknown length needs a record"
      (is (false? (:valid? (check (conj ps (period "n4" "l1x-1" nil nil)) rs [])))))
    (testing "two records of one assignment"
      (is (false? (:valid? (check ps (conj rs (assoc (second rs) :pid 3)) [])))))
    (testing "a record of no assignment"
      (is (false? (:valid? (check ps (conj rs (inst "n4" "l9x" "l9x-1" 9 1.0 2.0)) [])))))
    (testing "a record with no heartbeat"
      (is (false? (:valid? (check ps (assoc-in rs [1 :last] nil) [])))))
    (testing "a record file a node could not summarize"
      (is (false? (:valid? (check ps rs [{:node "n4" :line "E f no-start"}])))))
    (testing "no records at all"
      (is (false? (:valid? (check [] [] [])))))))

(defn op [f value t-s] {:type :info :process :nemesis :f f :value value
                        :time (long (* t-s 1e9))})

(def partition-faults
  [(op :isolate-agent "n4" 15) (op :heal :healed 40)
   (op :isolate-store ["n3" :from ["n1" "n2"]] 55) (op :heal :healed 80)])

(deftest faults-check
  (is (true? (:valid? (lc/faults-check partition-faults :partition 11000))))
  (is (false? (:valid? (lc/faults-check [] :partition 11000))))
  (is (false? (:valid? (lc/faults-check (take 2 partition-faults) :partition 11000)))
      "no store partition")
  (is (false? (:valid? (lc/faults-check (take 3 partition-faults) :partition 11000)))
      "a store partition never healed")
  (is (false? (:valid? (lc/faults-check (assoc-in partition-faults [3 :time] 60e9)
                                        :partition 11000)))
      "a store partition held 5 s: cut short")
  (is (true? (:valid? (lc/faults-check (assoc-in partition-faults [3 :time] 66e9)
                                       :partition 11000)))
      "held exactly ttl + grace")
  (is (false? (:valid? (lc/faults-check (assoc-in partition-faults [2 :value] nil)
                                        :partition 11000)))
      "a fault op that did not complete")
  (is (false? (:valid? (lc/faults-check partition-faults :wedge 11000))))
  (let [w [(op :wedge {:node "n4" :stopped? true} 15) (op :unwedge :all 45)]]
    (is (true? (:valid? (lc/faults-check w :wedge 11000))))
    (is (false? (:valid? (lc/faults-check (assoc-in w [0 :value :stopped?] false)
                                          :wedge 11000)))
        "a wedge that stopped nothing")
    (is (false? (:valid? (lc/faults-check (assoc-in w [1 :f] :heal) :wedge 11000)))
        "a wedge is ended by an unwedge, not a heal"))
  (is (false? (:valid? (lc/faults-check partition-faults nil 11000)))))

(deftest fault-kind
  (is (= :wedge (lc/fault-kind nil :overlap)))
  (is (= :partition (lc/fault-kind nil :valid)))
  (is (= :partition (lc/fault-kind :partition :overlap))))

;; A run of two services on n4 and n5: l0x is handed over from n4 to n5 (TERM
;; at 18000, last heartbeat at 20000, replacement at 22000), l1x stays on n5.
(def collect
  {:events [(evt 5 :put "/assigned/n4/l0x-1" 1000)
            (evt 6 :put "/assigned/n5/l1x-1" 1000)
            (evt 10 :delete "/assigned/n4/l0x-1" 21000)
            (evt 11 :put "/assigned/n5/l0x-2" 21000)]
   :gaps []
   :finished-at 70000
   :record-errors []
   :instances [(inst "n4" "l0x" "l0x-1" 10 1100.0 20000.0 18000.0 50.0)
               (inst "n5" "l1x" "l1x-1" 11 1100.0 65000.0 nil 50.0)
               (inst "n5" "l0x" "l0x-2" 12 22000.0 65000.0 nil 50.0)]})

;; A double grant of l0x-1 to n5 while n4 holds it, too short to need a
;; record (so that only I1a sees it).
(defn double-grant [c]
  (update c :events #(vec (concat (take 2 %)
                                  [(evt 7 :put "/assigned/n5/l0x-1" 5000)
                                   (evt 8 :delete "/assigned/n5/l0x-1" 5100)]
                                  (drop 2 %)))))

;; The old instance of l0x runs on past its replacement's start.
(defn wedged [c] (assoc-in c [:instances 0 :last] 40000.0))

(def base
  {:constants c :ttl 10000 :grace 1000 :collect collect
   :applies [{:type :ok :f :apply :value 0} {:type :ok :f :apply :value 1}]
   :faults partition-faults :fault-kind :partition :min-handovers 1})

(defn verdict [& kvs] (:valid? (lc/analyze (apply assoc base kvs))))

(deftest analyze
  (testing "the fixture"
    (let [r (lc/analyze (assoc base :expect :valid))]
      (is (true? (:valid? r)))
      (is (true? (get-in r [:sanity :valid?])))
      (is (true? (get-in r [:i1a :valid?])))
      (is (true? (get-in r [:i1b :valid?])))
      (is (= -2000.0 (get-in r [:i1b :max-overlap-ms])))
      (is (= 2000.0 (get-in r [:i1b :kill :max-term-to-last-ms])))
      (is (= 2950 (get-in r [:fencing :kill-bound-ms]))))
    (is (false? (verdict :expect :overlap))
        "the negative control must not pass on a run with no overlap")
    (is (true? (verdict :expect :overlap :collect (wedged collect))))
    (is (false? (verdict :expect :valid :collect (wedged collect)))))
  (testing "I1a: a double grant fails both expectations"
    (is (false? (verdict :expect :valid :collect (double-grant collect))))
    (is (false? (verdict :expect :overlap
                         :collect (double-grant (wedged collect))))))
  (testing "fencing"
    (let [bad (assoc c :fence-margin-ms 2000)]
      (is (false? (verdict :expect :valid :constants bad)))
      (is (false? (verdict :expect :overlap :constants bad
                           :collect (wedged collect))))))
  (testing "sanity: applies"
    (is (false? (verdict :expect :valid
                         :applies (assoc-in (:applies base) [1 :type] :fail))))
    (is (false? (verdict :expect :overlap :collect (wedged collect)
                         :applies (assoc-in (:applies base) [1 :type] :info))))
    (is (false? (verdict :expect :valid :applies []))))
  (testing "sanity: faults"
    (is (false? (verdict :expect :valid :faults [])))
    (is (false? (verdict :expect :valid :faults (take 2 partition-faults))))
    (is (false? (verdict :expect :overlap :collect (wedged collect)
                         :faults [] :fault-kind :wedge))))
  (testing "sanity: records"
    (is (false? (verdict :expect :valid
                         :collect (update collect :instances #(vec (take 2 %)))))
        "the replacement's record is missing")
    (is (false? (verdict :expect :valid
                         :collect (update collect :instances #(vec (rest %)))))
        "the old instance's record is missing")
    (is (false? (verdict :expect :valid
                         :collect (assoc collect :record-errors
                                         [{:node "n4" :line "E f no-start"}])))))
  (testing "sanity: handovers"
    (is (false? (verdict :expect :valid :min-handovers 2))
        "a run with too few handovers is vacuous")
    (let [same (-> collect
                   (assoc-in [:events 3 :key] "/assigned/n4/l0x-2")
                   (assoc-in [:instances 2 :node] "n4"))]
      (is (true? (verdict :expect :valid :collect same :min-handovers 0)))
      (is (false? (verdict :expect :valid :collect same))
          "a handover on one node is not a cross-node handover")))
  (testing "sanity: collect"
    (is (false? (verdict :expect :valid :collect nil))))
  (testing "I1b: a late KILL with no overlap fails valid, and is not an overlap"
    (let [late (-> collect
                   (assoc-in [:instances 0 :term] 10000.0)
                   (assoc-in [:instances 0 :last] 13000.0))]
      (is (false? (verdict :expect :valid :collect late)))
      (is (false? (verdict :expect :overlap :collect late)))))
  (testing "I1b: an uncertain overlap passes neither"
    (let [maybe (assoc-in collect [:instances 0 :last] 23880.0)]
      (is (= :unknown (get-in (lc/analyze (assoc base :expect :valid :collect maybe))
                              [:i1b :overlap-valid?])))
      (is (false? (verdict :expect :valid :collect maybe)))
      (is (false? (verdict :expect :overlap :collect maybe))))))
