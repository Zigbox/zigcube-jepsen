(ns zcube-jepsen.stale-put-test
  (:require [clojure.test :refer [deftest is testing]]
            [zcube-jepsen.stale-put :as sp]))

(defn row [rev k lease value & {:keys [deleted]}]
  {:rev rev :idx 0 :key k :deleted (boolean deleted) :lease lease :value value})

(def node {:v 1 :name "n4"})

(deftest parse-log-rows-test
  (let [p (sp/parse-log-rows
            ["1\t0\t/nodes/n4\tf\t2\t{\"v\":1}"
             "3\t0\t/observed/n4/a-1\tt\t0\t"
             "0\t-1\tcompacted\tf\t0\t"])]
    (is (= 0 (:compacted p)))
    (is (= [{:rev 1 :idx 0 :key "/nodes/n4" :deleted false :lease 2 :value {:v 1}}
            {:rev 3 :idx 0 :key "/observed/n4/a-1" :deleted true :lease 0 :value nil}]
           (:rows p)))))

(deftest stale-registrations-test
  (testing "a registration on a lower lease than an earlier one is stale"
    (is (= [{:rev 15 :key "/nodes/n4" :lease 1 :newer-lease 2}]
           (sp/stale-registrations
             [(row 1 "/nodes/n4" 2 node)
              (row 15 "/nodes/n4" 1 node)
              (row 18 "/nodes/n4" 3 node)]))))
  (testing "growing leases, deletes and other nodes are not"
    (is (empty? (sp/stale-registrations
                  [(row 1 "/nodes/n4" 2 node)
                   (row 2 "/nodes/n5" 1 node)
                   (row 3 "/nodes/n4" 2 nil :deleted true)
                   (row 4 "/nodes/n4" 3 node)])))))

(defn obs [tenure assignment]
  {:status "running" :tenure tenure :assignment assignment})

(deftest replay-test
  (testing "one admissible observation per replica holds"
    (let [r (sp/replay [(row 1 "/nodes/n4" 2 node)
                        (row 2 "/node-status/n4" 0 {:state "ready"})
                        (row 3 "/assigned/n4/a-1" 0 {:service "a"})
                        (row 4 "/observed/n4/a-1" 0 (obs 1 3))])]
      (is (empty? (:i1a r)))
      (is (empty? (:superseded-observed r)))))
  (testing "an observation under a superseded tenure"
    (let [r (sp/replay [(row 1 "/nodes/n4" 2 node)
                        (row 5 "/nodes/n4" 1 node)
                        (row 6 "/observed/n4/a-1" 0 (obs 1 3))])]
      (is (= [{:rev 6 :key "/observed/n4/a-1" :tenure 1 :nodes-revision 5}]
             (:superseded-observed r)))))
  (testing "two admissible observations of one replica id break I1a"
    (let [r (sp/replay [(row 1 "/nodes/n4" 2 node)
                        (row 2 "/nodes/n5" 3 node)
                        (row 3 "/node-status/n4" 0 {:state "ready"})
                        (row 4 "/node-status/n5" 0 {:state "ready"})
                        (row 5 "/assigned/n4/a-1" 0 {:service "a"})
                        (row 6 "/assigned/n5/a-1" 0 {:service "a"})
                        (row 7 "/observed/n4/a-1" 0 (obs 1 5))
                        (row 8 "/observed/n5/a-1" 0 (obs 2 6))])]
      (is (some :admissible (:i1a r)))
      (is (some :assigned (:i1a r)))))
  (testing "an observation stops being admissible when its node is not ready"
    (let [r (sp/replay [(row 1 "/nodes/n4" 2 node)
                        (row 2 "/nodes/n5" 3 node)
                        (row 3 "/node-status/n4" 0 {:state "lost"})
                        (row 4 "/node-status/n5" 0 {:state "ready"})
                        (row 5 "/assigned/n4/a-1" 0 {:service "a"})
                        (row 6 "/observed/n4/a-1" 0 (obs 1 5))
                        (row 7 "/assigned/n4/a-1" 0 nil :deleted true)
                        (row 8 "/assigned/n5/a-2" 0 {:service "a"})
                        (row 9 "/observed/n5/a-2" 0 (obs 2 8))])]
      (is (empty? (:i1a r))))))

(deftest overlap-test
  (let [procs {"n4" ["1000000000000 a-1" "1000000000100 a-1" "1000000003000 a-1"
                     "1000000003100"]
               "n5" ["1000000001000" "1000000002000 a-2" "1000000002100 a-2"
                     "1000000005000 a-2"]}
        ov (sp/overlap procs {"a-1" "a" "a-2" "a"})]
    (testing "a gap of a second or more splits an interval"
      (is (= [[1000000000000 1000000000100] [1000000003000 1000000003000]]
             (get-in ov [:intervals ["n4" "a-1"]]))))
    (testing "the longest overlap of two replicas of one service"
      ; a-1 ran [0,100] and [3000,3000], a-2 [2000,2100] and [5000,5000]:
      ; they never meet.
      (is (= 0 (:max-ms ov)))))
  (let [procs {"n4" (for [t (range 0 3000 100)] (str (+ 1000000000000 t) " a-1"))
               "n5" (for [t (range 1000 5000 100)] (str (+ 1000000000000 t) " a-2"))}]
    (is (= 1900 (:max-ms (sp/overlap procs {"a-1" "a" "a-2" "a"})))))
  (testing "other services and non-sample lines are ignored"
    (is (= 0 (:max-ms (sp/overlap {"n4" ["Jepsen starting" "1000000000000 a-1 b-1"]}
                                  {"a-1" "a" "b-1" "b"}))))))

(deftest orphan-keepalives-test
  (is (= [{:at 30 :path "/v1/agent/nodes/n4/lease/2/keepalive?known=7"}]
         (sp/orphan-keepalives
           [{:path "/v1/agent/nodes/n4/lease/2/keepalive?known=7" :at 30 :status 200}
            {:path "/v1/agent/nodes/n4/lease/2/keepalive?known=6" :at 5 :status 200}
            {:path "/v1/agent/nodes/n4/lease/1/keepalive" :at 31 :status 200}
            {:path "/v1/agent/nodes/n4/lease/2/keepalive" :at 32 :status 404}]
           {:answered 10} 1))))

(def release-ok {:status 200 :answered 100 :l1-deadline 1000})
(def stale [{:rev 15 :key "/nodes/n4" :lease 1 :newer-lease 2}])
(def clean {:stale-registrations [] :superseded-observed [] :i1a []
            :overlap {:max-ms 0}})

(deftest judge-test
  (testing "auto: a landed stale registration is the expected failure"
    (let [j (sp/judge (assoc clean :release release-ok :stale-registrations stale) :auto)]
      (is (:valid? j))
      (is (= :expected-failure-237 (:outcome j)))))
  (testing "auto: a landed release that left no stale registration fails"
    (is (not (:valid? (sp/judge (assoc clean :release release-ok) :auto)))))
  (testing "auto: a refusal in time is a fix, and must then be clean"
    (let [rel {:status 409 :code "ZC-E0105" :answered 100 :l1-deadline 1000}]
      (is (= :fixed (:outcome (sp/judge (assoc clean :release rel) :auto))))
      (is (:valid? (sp/judge (assoc clean :release rel) :auto)))
      (is (not (:valid? (sp/judge (assoc clean :release rel :overlap {:max-ms 2500})
                                  :auto))))))
  (testing "auto: a refusal because L1 had ended proves nothing"
    (let [rel {:status 404 :code "ZC-E0205" :answered 100 :l1-deadline 1000}]
      (is (= :vacuous (:outcome (sp/judge (assoc clean :release rel) :auto))))
      (is (not (:valid? (sp/judge (assoc clean :release rel) :auto))))))
  (testing "auto: nothing released is vacuous"
    (is (not (:valid? (sp/judge (assoc clean :release [:error "nothing held"]) :auto)))))
  (testing "valid: a landed release fails"
    (is (not (:valid? (sp/judge (assoc clean :release release-ok
                                       :stale-registrations stale) :valid))))))
