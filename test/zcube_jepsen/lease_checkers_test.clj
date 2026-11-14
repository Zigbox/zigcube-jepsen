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
    (is (= :unknown (:valid? (lc/i1a [(ev 5 :put "/assigned/n4/l0x-1")]
                                     [{:from 3 :resumed 900}]))))))

(defn inst [node svc r pid start last & [term]]
  {:node node :svc svc :replica r :pid pid :start start :last last :term term})

(deftest i1b
  (testing "a fenced instance gone before its replacement: a gap, reported negative"
    (let [r (lc/i1b [(inst "n4" "l0x" "l0x-1" 10 1000.0 20000.0 18000.0)
                     (inst "n5" "l0x" "l0x-2" 11 21500.0 60000.0)
                     (inst "n5" "l1x" "l1x-1" 12 1000.0 60000.0)]
                    1900 50)]
      (is (true? (:valid? r)))
      (is (= 1 (:handovers r)))
      (is (= 1 (:cross-node-handovers r)))
      (is (= -1500.0 (:max-overlap-ms r)))
      (is (= -1450.0 (:max-overlap-upper-ms r)))))
  (testing "an overlap within the window"
    (is (true? (:valid? (lc/i1b [(inst "n4" "l0x" "l0x-1" 10 0.0 21800.0)
                                 (inst "n5" "l0x" "l0x-2" 11 20000.0 60000.0)]
                                1900 50)))))
  (testing "an overlap past the window (a wedged node)"
    (let [r (lc/i1b [(inst "n4" "l0x" "l0x-1" 10 0.0 45000.0 44000.0)
                     (inst "n5" "l0x" "l0x-2" 11 20000.0 60000.0)]
                    1900 50)]
      (is (false? (:valid? r)))
      (is (= 25000.0 (:max-overlap-ms r)))
      (is (= 1 (count (:violations r))))))
  (testing "the worst earlier instance counts, not just the latest"
    (let [r (lc/i1b [(inst "n4" "l0x" "l0x-1" 10 0.0 50000.0)
                     (inst "n5" "l0x" "l0x-2" 11 20000.0 21000.0)
                     (inst "n5" "l0x" "l0x-3" 12 30000.0 60000.0)]
                    1900 50)]
      (is (= 2 (:handovers r)))
      (is (= 20000.0 (:max-overlap-ms r))))))

(deftest parse-record-line
  (is (= {:node "n4" :svc "l0x" :replica "l0x-1" :pid 77
          :start 1763078400123.456 :last 1763078410000.0 :term nil}
         (lc/parse-record-line
           "n4" "l0x l0x-1 77 1763078400.123456 1763078410.000000 -")))
  (is (nil? (lc/parse-record-line "n4" "garbage"))))

(deftest analyze
  (let [collect {:events [(ev 5 :put "/assigned/n4/l0x-1")]
                 :gaps []
                 :instances [(inst "n4" "l0x" "l0x-1" 10 0.0 20000.0)
                             (inst "n5" "l0x" "l0x-2" 11 22000.0 60000.0)]}
        base    {:constants c :ttl 10000 :grace 1000 :collect collect
                 :applies [{:type :ok :f :apply :value 0}]
                 :faults [] :min-handovers 1}]
    (is (true? (:valid? (lc/analyze (assoc base :expect :valid)))))
    (is (false? (:valid? (lc/analyze (assoc base :expect :overlap))))
        "the negative control must not pass on a run with no overlap")
    (is (false? (:valid? (lc/analyze (assoc base :expect :valid
                                            :min-handovers 2))))
        "a run with too few handovers is vacuous")
    (is (false? (:valid? (lc/analyze (assoc base :expect :valid :collect nil)))))
    (let [wedged (assoc-in collect [:instances 0 :last] 40000.0)]
      (is (true? (:valid? (lc/analyze (assoc base :expect :overlap
                                             :collect wedged)))))
      (is (false? (:valid? (lc/analyze (assoc base :expect :valid
                                              :collect wedged))))))))
