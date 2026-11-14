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
  (testing "a registration again on the same lease is not stale"
    (is (empty? (sp/stale-registrations
                  [(row 1 "/nodes/n4" 2 node)
                   (row 4 "/nodes/n4" 2 node)]))))
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

(deftest replay-ready-test
  (testing "observations on a node that is not ready are not admissible"
    ; Both nodes hold a-1 (an :assigned break either way), but n4 is lost:
    ; only n5's observation is admissible.
    (let [base [(row 1 "/nodes/n4" 2 node)
                (row 2 "/nodes/n5" 3 node)
                (row 3 "/node-status/n4" 0 {:state "lost"})
                (row 4 "/node-status/n5" 0 {:state "ready"})
                (row 5 "/assigned/n4/a-1" 0 {:service "a"})
                (row 6 "/assigned/n5/a-1" 0 {:service "a"})
                (row 7 "/observed/n4/a-1" 0 (obs 1 5))
                (row 8 "/observed/n5/a-1" 0 (obs 2 6))]
          r (sp/replay base)]
      (is (some :assigned (:i1a r)))
      (is (not (some :admissible (:i1a r))))
      (testing "and both are once n4 is ready again"
        (is (some :admissible
                  (:i1a (sp/replay (conj base (row 9 "/node-status/n4" 0 {:state "ready"}))))))))))

(deftest overlap-test
  (let [procs {"n4" ["1000000000000 a-1" "1000000000100 a-1" "1000000003000 a-1"
                     "1000000003100"]
               "n5" ["1000000001000" "1000000002000 a-2" "1000000002100 a-2"
                     "1000000005000 a-2"]}
        ov (sp/overlap procs {"a-1" "a" "a-2" "a"})]
    (testing "a gap in the samples is unknown, not absent: it does not split a run"
      (is (= [[1000000000000 1000000003100]] (get-in ov [:intervals ["n4" "a-1"]]))))
    (testing "a run is widened to the samples on either side"
      ; a-2 is absent at 1000 and present from 2000: it may have started
      ; just after 1000.
      (is (= [[1000000001000 1000000005000]] (get-in ov [:intervals ["n5" "a-2"]]))))
    (testing "the longest overlap of two replicas of one service"
      ; a-1 may have run [0,3100], a-2 [1000,5000].
      (is (= 2100 (:max-ms ov)))))
  (testing "absence between two runs splits them"
    (is (= [[1000000000000 1000000000200] [1000000000300 1000000000400]]
           (get-in (sp/overlap {"n4" ["1000000000000 a-1" "1000000000100 a-1"
                                      "1000000000200" "1000000000300"
                                      "1000000000400 a-1"]}
                               {})
                   [:intervals ["n4" "a-1"]]))))
  ; (n5 has no sample before 1100 to widen a-2 to.)
  (testing "a replica with no /assigned row is mapped by its name"
    (is (= 1800 (:max-ms (sp/overlap {"n4" (for [t (range 0 3000 100)] (str (+ 1000000000000 t) " a-1"))
                                      "n5" (for [t (range 1100 5000 100)] (str (+ 1000000000000 t) " a-2"))}
                                     {"a-1" "a"})))))
  (let [procs {"n4" (for [t (range 0 3000 100)] (str (+ 1000000000000 t) " a-1"))
               "n5" (cons "1000000001000"
                          (for [t (range 1100 5000 100)] (str (+ 1000000000000 t) " a-2")))}]
    ; a-2 from 1000 (widened to the sample before), a-1 to 2900: 1900 ms.
    (is (= 1900 (:max-ms (sp/overlap procs {"a-1" "a" "a-2" "a"})))))
  (testing "other services and non-sample lines are ignored"
    (is (= 0 (:max-ms (sp/overlap {"n4" ["Jepsen starting" "1000000000000 a-1 b-1"]}
                                  {"a-1" "a" "b-1" "b"}))))))

(def ka "/v1/agent/nodes/n4/lease/%d/keepalive?known=7")
(defn ka-at [lease end] {:method "POST" :path (format ka lease) :at (- end 2) :end end :status 200})

(def stale-rows
  "L2 registered (rev 1), the stale L1 put (rev 15), then L3 (rev 18)."
  [(row 1 "/nodes/n4" 2 node)
   (row 2 "/node-status/n4" 0 {:state "ready" :tenure 1 :at_ms 10})
   (row 15 "/nodes/n4" 1 node)
   (row 16 "/node-status/n4" 0 {:state "ready" :tenure 15 :at_ms 100})
   (row 18 "/nodes/n4" 3 node)
   (row 19 "/node-status/n4" 0 {:state "ready" :tenure 18 :at_ms 200})])

(deftest orphan-keepalives-test
  (testing "renewals of a lease /nodes/n4 no longer names count"
    (is (= [{:at 148 :end 150 :path (format ka 2) :named 1}]
           (sp/orphan-keepalives
             [(ka-at 2 50)                          ; before the release
              (ka-at 2 150)                         ; on L2 while n4 names L1
              (assoc (ka-at 2 160) :status 404)     ; refused
              (ka-at 3 250) (ka-at 3 300)]          ; L3, after it registered
             stale-rows {:answered 100} "n4"))))
  (testing "an L3 re-registration's renewals do not"
    (is (empty? (sp/orphan-keepalives [(ka-at 3 250) (ka-at 3 300)]
                                      stale-rows {:answered 100} "n4"))))
  (testing "renewals of L2 after /nodes/n4 was deleted count (the idle case)"
    (let [rows [(row 1 "/nodes/n4" 2 node)
                (row 2 "/node-status/n4" 0 {:state "ready" :tenure 1 :at_ms 10})
                (row 4 "/nodes/n4" 1 node)
                (row 5 "/node-status/n4" 0 {:state "ready" :tenure 4 :at_ms 100})
                (row 6 "/nodes/n4" 1 nil :deleted true)
                (row 7 "/node-status/n4" 0 {:state "lost" :tenure 4 :at_ms 500})]]
      (is (= [nil 1] (map :named (sp/orphan-keepalives [(ka-at 2 600) (ka-at 2 300)]
                                                       rows {:answered 100} "n4"))))))
  (testing "without node-status rows, the proxy's registration answers place the puts"
    (let [rows [(row 1 "/nodes/n4" 2 node) (row 15 "/nodes/n4" 1 node) (row 18 "/nodes/n4" 3 node)]
          log [{:method "PUT" :path "/v1/agent/nodes/n4?known=0" :status 200 :end 10}
               {:method "PUT" :path "/v1/agent/nodes/n4" :status 200 :end 100 :released true}
               {:method "PUT" :path "/v1/agent/nodes/n4?known=17" :status 200 :end 200}]]
      (is (= [150] (map :end (sp/orphan-keepalives (into log [(ka-at 2 150) (ka-at 3 250)])
                                                   rows {:answered 100} "n4")))))))

(def release-ok {:status 200 :answered 100 :l1-deadline 1000 :revision 15})
(def stale [{:rev 15 :key "/nodes/n4" :lease 1 :newer-lease 2}])
(def clean {:collected true :first-rev 1 :compacted 0 :l2-registered true
            :procs-missing [] :failed-steps []
            :held {:lease 1}
            :registered {:lease 2 :tenure 1}
            :stale-registrations [] :superseded-observed [] :i1a []
            :overlap {:max-ms 0}})
(def refusal {:status 409 :code "ZC-E0105" :answered 100 :l1-deadline 1000
              :n4-after {:lease 2 :tenure 1}})

(defn outcome [a] (:outcome (sp/judge a :auto)))

(deftest judge-test
  (testing "auto: a landed stale registration is the expected failure"
    (let [j (sp/judge (assoc clean :release release-ok :stale-registrations stale) :auto)]
      (is (:valid? j))
      (is (= :expected-failure-237 (:outcome j)))))
  (testing "auto: the stale row must be the release's own"
    (is (not (:valid? (sp/judge (assoc clean :release (assoc release-ok :revision 16)
                                       :stale-registrations stale) :auto))))
    (is (not (:valid? (sp/judge (assoc clean :release release-ok :held {:lease 7}
                                       :stale-registrations stale) :auto))))
    (testing "older histories carry the revision in the body only"
      (is (:valid? (sp/judge (assoc clean :release (-> release-ok (dissoc :revision)
                                                       (assoc :body "{\"revision\":15}"))
                                    :stale-registrations stale) :auto)))))
  (testing "auto: a landed release that left no stale registration fails"
    (is (not (:valid? (sp/judge (assoc clean :release release-ok) :auto)))))
  (testing "auto: a refusal in time is a fix, and must then be clean"
    (is (= :fixed (outcome (assoc clean :release refusal))))
    (is (:valid? (sp/judge (assoc clean :release refusal) :auto)))
    (doseq [[what a] [["overlap" (assoc clean :overlap {:max-ms 2500})]
                      ["stale registration" (assoc clean :stale-registrations stale)]
                      ["superseded observation" (assoc clean :superseded-observed [{:rev 6}])]
                      ["i1a" (assoc clean :i1a [{:rev 6 :replica "a-1"}])]]]
      (testing what
        (is (= :refused-but-unsafe (outcome (assoc a :release refusal))))
        (is (not (:valid? (sp/judge (assoc a :release refusal) :auto)))))))
  (testing "auto: a fix needs a whole run to stand on"
    (doseq [[what a] [["collect failed" (assoc clean :collected false)]
                      ["store compacted" (assoc clean :compacted 12)]
                      ["history starts late" (assoc clean :first-rev 13)]
                      ["no L2 registration in the store" (assoc clean :l2-registered false)]
                      ["a node has no process samples" (assoc clean :procs-missing ["n5"])]]]
      (testing what
        (is (= :refused-but-incomplete (outcome (assoc a :release refusal))))
        (is (not (:valid? (sp/judge (assoc a :release refusal) :auto)))))))
  (testing "auto: a refusal must say why, or have left n4 on L2"
    (is (= :fixed (outcome (assoc clean :release (assoc refusal :code "ZC-E0510" :n4-after nil)))))
    (is (= :fixed (outcome (assoc clean :release (assoc refusal :code nil)))))
    (is (= :vacuous (outcome (assoc clean :release (assoc refusal :code nil :n4-after nil)))))
    (is (= :vacuous (outcome (assoc clean :release (assoc refusal :code "ZC-E0999"
                                                          :n4-after {:lease 1 :tenure 15}))))))
  (testing "auto: a refusal at or after L1's deadline proves nothing"
    (is (= :vacuous (outcome (assoc clean :release (assoc refusal :answered 1000)))))
    (is (= :vacuous (outcome (assoc clean :release (assoc refusal :answered 1500))))))
  (testing "auto: a 5xx is not a refusal"
    (is (= :vacuous (outcome (assoc clean :release (assoc refusal :status 503)))))
    (is (= :vacuous (outcome (assoc clean :release (assoc refusal :status 500 :code nil))))))
  (testing "auto: a refusal because L1 had ended proves nothing"
    (let [rel (assoc refusal :status 404 :code "ZC-E0205")]
      (is (= :vacuous (outcome (assoc clean :release rel))))
      (is (not (:valid? (sp/judge (assoc clean :release rel) :auto))))))
  (testing "auto: nothing released is vacuous"
    (is (not (:valid? (sp/judge (assoc clean :release [:error "nothing held"]) :auto)))))
  (testing "a failed wait makes the run vacuous, whatever was expected"
    (doseq [f [[:await-registered :timed-out] [:await-running :deadline]
               [:await-ready :timed-out] [:collect [:error "boom"]]]]
      (is (= :vacuous (outcome (assoc clean :release refusal :failed-steps [f]))))
      (is (not (:valid? (sp/judge (assoc clean :release release-ok :stale-registrations stale
                                         :failed-steps [f]) :bug))))))
  (testing "valid: a landed release fails"
    (is (not (:valid? (sp/judge (assoc clean :release release-ok
                                       :stale-registrations stale) :valid))))))

(deftest analyse-test
  (let [hist (fn [& ops] (vec (for [[f v] ops] {:type :info :process :nemesis :f f :value v})))
        rows [(row 1 "/nodes/n4" 2 node)
              (row 2 "/node-status/n4" 0 {:state "ready" :tenure 1 :at_ms 10})]
        col {:store {:rows rows :compacted 0} :proxy [] :end 50
             :procs {"n4" ["1000000000000"] "n5" ["1000000000000"]}}
        a (sp/analyse (hist [:await-held nil] [:await-held {:lease 1}]
                            [:await-registered nil] [:await-registered {:lease 2 :tenure 1}]
                            [:start-agent "n5"] [:start-agent "n5"]
                            [:await-ready "n5"] [:await-ready :timed-out]
                            [:release nil] [:release refusal]
                            [:collect nil] [:collect col])
                      "n4" ["n4" "n5"])]
    (is (:collected a))
    (is (:l2-registered a))
    (is (= 1 (:first-rev a)))
    (is (= [] (:procs-missing a)))
    (is (= [[:await-ready :timed-out]] (:failed-steps a)))
    (is (= :vacuous (outcome a)))
    (testing "a node with no samples is missing"
      (is (= ["n5"] (:procs-missing
                      (sp/analyse (hist [:collect (assoc-in col [:procs "n5"] ["junk"])])
                                  "n4" ["n4" "n5"])))))
    (testing "a failed collect"
      (let [a (sp/analyse (hist [:collect [:error "x"]]) "n4" ["n4" "n5"])]
        (is (not (:collected a)))
        (is (= [[:collect [:error "x"]]] (:failed-steps a)))))))
