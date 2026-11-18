(ns zcube-jepsen.stale-register-test
  (:require [clojure.test :refer [deftest is testing]]
            [zcube-jepsen.stale-register :as sr]))

(deftest parse-done-test
  (is (= {:succeeded false :revision 0 :at-ms 1763500000123}
         (sr/parse-done "succeeded 0 revision 0 at_ms 1763500000123\n")))
  (is (= {:succeeded true :revision 41 :at-ms 1763500000123}
         (sr/parse-done "succeeded 1 revision 41 at_ms 1763500000123\n")))
  (testing "a line not all written yet, or none"
    (is (nil? (sr/parse-done "succeeded 1 revision 41 at_ms 17635")))
    (is (nil? (sr/parse-done "")))
    (is (nil? (sr/parse-done nil)))))

(deftest held-lease-test
  (is (= 7 (sr/held-lease ["arm-n5" "held-n4-7" "done-n4-6"] "n4")))
  (is (nil? (sr/held-lease ["held-n44-7" "held-n5-7" ""] "n4"))))

(deftest put-log-line-test
  (is (= {:at-ms 1004101 :request-id "r-17" :method "PUT" :path "/v1/agent/nodes/n4"
          :status 503 :took-ms 3101}
         (sr/put-log-line "[1004101] r-17 PUT /v1/agent/nodes/n4 -> 503 (3101 ms)")))
  (is (nil? (sr/put-log-line "[1004101] tls: ZC-E0801: nope")))
  (is (nil? (sr/put-log-line nil))))

(deftest parse-lease-rows-test
  (is (= [{:id 4 :deadline-ms 1011000 :live true :read-at-ms 1004500}
          {:id 5 :deadline-ms 1013000 :live false :read-at-ms 1004500}]
         (sr/parse-lease-rows ["4\t1011000\tt\t1004500" "5\t1013000\tf\t1004500" ""]))))

(deftest released-put-line-test
  (let [lines ["[1002500] a PUT /v1/agent/nodes/n4 -> 200 (12 ms)"     ; L2's registration
               "[1004100] b PUT /v1/agent/nodes/n44 -> 503 (3100 ms)"  ; another node
               "[1004102] c PUT /v1/agent/nodes/n4 -> 200 (5 ms)"      ; too short to be the held one
               "[1004103] d PUT /v1/agent/nodes/n4 -> 503 (3103 ms)"]]
    (is (= "d" (:request-id (sr/released-put-line lines "n4" 1001000 1004100)))))
  (is (nil? (sr/released-put-line ["[1004103] d PUT /v1/agent/nodes/n4 -> 503 (3103 ms)"]
                                  "n4" nil 1004100))))

(defn- op [f v] {:type :info :process :nemesis :f f :value v})

(def ttl 10000)

(def live-leases
  [{:id 4 :deadline-ms 1011000 :live true :read-at-ms 1004500}
   {:id 5 :deadline-ms 1013000 :live true :read-at-ms 1004500}])

(def refused-line "[1004101] r-9 PUT /v1/agent/nodes/n4 -> 503 (3101 ms)")

(defn- history
  "A run's steps: L0 = 3, L1 = 4, L2 = 5 (tenure 9); held at 1001000, released
  at 1004000. `done` and `read` are what the barrier and GET /v1/nodes?at=N
  gave; `rows` the store's /nodes/n4 puts; `log` the server's PUT lines."
  [{:keys [done read rows started log leases l1 l0 release-at]
    :or {started 1000000 log [refused-line] leases live-leases l1 4 l0 3
         release-at 1004000}}]
  [(op :start-agent {:node "n4" :at 990000})
   (op :await-registered {:lease l0 :mod-revision 2 :revision 3 :at 995000})
   (op :kill-agent "n4")
   (op :await-expired {:revision 5 :at 999000})
   (op :arm "arm-n4")
   (op :start-agent {:node "n4" :at started})
   (op :await-held {:lease l1 :agent-started started
                    :held "succeeded 0 revision 0 at_ms 1001000"})
   (op :kill-agent "n4")
   (op :forget-lease "lease.json")
   (op :start-agent {:node "n4" :at 1002000})
   (op :await-registered {:lease 5 :mod-revision 9 :revision 10 :at 1003000})
   (op :release {:lease l1 :at release-at})
   (op :await-done (if (map? done) (assoc done :leases leases) done))
   (op :read-nodes read)
   (op :observe-after {:for-ms 16000 :changes [] :last nil})
   (op :collect {:store {:rows rows :compacted 0} :server-log log :end 1030000})])

(defn- put [rev lease] {:rev rev :idx 0 :key "/nodes/n4" :deleted false :lease lease})

(defn- judged
  ([h] (judged h :refused))
  ([h expect] (sr/judge (assoc (sr/analyse h "n4" ttl) :tls? true) expect)))

(def refused {:succeeded false :revision 0 :at-ms 1004100 :lease 4})
(def l2-read {:status 200 :revision 12 :truncated false :at 12
              :node {:lease 5 :mod-revision 9} :state "ready"})
(def landed {:succeeded true :revision 13 :at-ms 1004100 :lease 4})
(def l1-read {:status 200 :revision 13 :truncated false :at 13
              :node {:lease 4 :mod-revision 13} :state "ready"})

(deftest candidate-passes
  (let [j (judged (history {:done refused :read l2-read :rows [(put 2 3) (put 9 5)]}))]
    (is (:valid? j) (pr-str j))
    (is (= :refused (:outcome j)))
    (is (= :refused (-> j :finding-a :result)))
    (is (= :l2-at-tenure (-> j :finding-b :at-n :on)))))

(deftest invocations-are-skipped
  (testing "each step's invocation (value nil) before its completion, as Jepsen records them"
    (let [h (history {:done refused :read l2-read :rows [(put 2 3) (put 9 5)]})
          j (sr/judge (assoc (sr/analyse (mapcat (fn [o] [(assoc o :value nil) o]) h) "n4" ttl)
                             :tls? true))]
      (is (= :refused (:outcome j)) (pr-str j)))))

(deftest control-lands-and-says-so
  (let [h (history {:done landed :read l1-read :log []
                    :rows [(put 2 3) (put 9 5) (put 13 4)]})]
    (testing "expected by the control"
      (let [j (judged h :stale-registration-landed)]
        (is (:valid? j) (pr-str j))
        (is (= :stale-registration-landed (:outcome j)))
        (is (= :landed (-> j :finding-a :result)))
        (is (= :l1 (-> j :finding-b :at-n :on)))))
    (testing "a failure for a candidate"
      (let [j (judged h)]
        (is (not (:valid? j)))
        (is (= :stale-registration-landed (:outcome j)))))))

(deftest an-in-order-write-is-not-a-landing
  (testing "succeeded below L2's tenure (the hold ended early)"
    (let [j (judged (history {:done (assoc landed :revision 7 :at-ms 1004100)
                              :read (assoc l1-read :node {:lease 5 :mod-revision 9})
                              :rows [(put 2 3) (put 7 4) (put 9 5)]})
                    :stale-registration-landed)]
      (is (= :vacuous (:outcome j)) (pr-str j))
      (is (not (:valid? j)))))
  (testing "done before the release"
    (let [j (judged (history {:done (assoc landed :at-ms 1002000) :read l1-read
                              :rows [(put 2 3) (put 9 5) (put 13 4)]})
                    :stale-registration-landed)]
      (is (= :vacuous (:outcome j)) (pr-str j))))
  (testing "succeeded, but the store has no put on L1 at that revision"
    (let [j (judged (history {:done landed :read l1-read :rows [(put 2 3) (put 9 5)]})
                    :stale-registration-landed)]
      (is (= :unknown (:outcome j)) (pr-str j)))))

(deftest a-refusal-must-be-proved
  (testing "refused before the release"
    (let [j (judged (history {:done (assoc refused :at-ms 1000500) :read l2-read
                              :rows [(put 2 3) (put 9 5)]}))]
      (is (= :vacuous (:outcome j)) (pr-str j))))
  (testing "no server log line for the released PUT"
    (let [j (judged (history {:done refused :read l2-read :log []
                              :rows [(put 2 3) (put 9 5)]}))]
      (is (= :unknown (:outcome j)) (pr-str j))
      (is (not (:valid? j)))))
  (testing "answered 500, not 503"
    (let [j (judged (history {:done refused :read l2-read
                              :log ["[1004101] r-9 PUT /v1/agent/nodes/n4 -> 500 (3101 ms)"]
                              :rows [(put 2 3) (put 9 5)]}))]
      (is (= :unknown (:outcome j)) (pr-str j))))
  (testing "L1 not live in the store after done"
    (let [j (judged (history {:done refused :read l2-read
                              :leases [(assoc (first live-leases) :live false)
                                       (second live-leases)]
                              :rows [(put 2 3) (put 9 5)]}))]
      (is (= :vacuous (:outcome j)) (pr-str j))))
  (testing "L1's row gone (expired or revoked)"
    (let [j (judged (history {:done refused :read l2-read :leases [(second live-leases)]
                              :rows [(put 2 3) (put 9 5)]}))]
      (is (= :vacuous (:outcome j)) (pr-str j))))
  (testing "the held PUT was on L0, a resumed lease"
    (let [j (judged (history {:done refused :read l2-read :l1 3
                              :rows [(put 2 3) (put 9 5)]}))]
      (is (= :vacuous (:outcome j)) (pr-str j))))
  (testing "not over mTLS"
    (let [j (sr/judge (assoc (sr/analyse (history {:done refused :read l2-read
                                                   :rows [(put 2 3) (put 9 5)]})
                                         "n4" ttl)
                             :tls? false))]
      (is (= :vacuous (:outcome j)) (pr-str j)))))

(deftest refused-but-moved-is-not-a-pass
  (testing "succeeded 0, yet the store shows a put on L1 after L2"
    (let [j (judged (history {:done refused :read l1-read
                              :rows [(put 2 3) (put 9 5) (put 13 4)]}))]
      (is (= :unknown (:outcome j)) (pr-str j))
      (is (not (:valid? j)))))
  (testing "succeeded 0, but /nodes/n4 is on L2 at another tenure"
    (let [j (judged (history {:done refused
                              :read (assoc l2-read :node {:lease 5 :mod-revision 11})
                              :rows [(put 2 3) (put 9 5) (put 11 5)]}))]
      (is (= :unexpected (:outcome j)))
      (is (not (:valid? j))))))

(deftest vacuous-runs-fail
  (testing "released after L1 may have ended"
    (let [j (judged (history {:done (assoc refused :at-ms (+ 1000000 ttl -500))
                              :read l2-read :rows [(put 2 3) (put 9 5)]}))]
      (is (= :vacuous (:outcome j)) (pr-str j))
      (is (not (:valid? j)))))
  (testing "the barrier never said done, for either expectation"
    (doseq [e [:refused :stale-registration-landed]]
      (let [j (judged (history {:done :timed-out :read l2-read :rows [(put 2 3) (put 9 5)]}) e)]
        (is (= :vacuous (:outcome j)))
        (is (not (:valid? j))))))
  (testing "the hold was never reached"
    (let [h (mapv #(if (= :await-held (:f %)) (assoc % :value :timed-out) %)
                  (history {:done refused :read l2-read :rows [(put 2 3) (put 9 5)]}))
          j (judged h :stale-registration-landed)]
      (is (= :vacuous (:outcome j)))))
  (testing "a truncated view"
    (let [j (judged (history {:done refused :read (assoc l2-read :truncated true)
                              :rows [(put 2 3) (put 9 5)]}))]
      (is (= :vacuous (:outcome j))))))
