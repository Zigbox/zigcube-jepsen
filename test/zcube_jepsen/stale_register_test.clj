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

(defn- op [f v] {:type :info :process :nemesis :f f :value v})

(def ttl 10000)

(defn- history
  "A run's steps: L0 = 3, L1 = 4, L2 = 5; `done` and `read` are what the
  barrier and GET /v1/nodes?at=N gave; `rows` the store's /nodes/n4 puts."
  [{:keys [done read rows started] :or {started 1000000}}]
  [(op :start-agent {:node "n4" :at 990000})
   (op :await-registered {:lease 3 :mod-revision 2 :revision 3 :at 995000})
   (op :kill-agent "n4")
   (op :await-expired {:revision 5 :at 999000})
   (op :arm "arm-n4")
   (op :start-agent {:node "n4" :at started})
   (op :await-held {:lease 4 :agent-started started :held "succeeded 0 revision 0 at_ms 1"})
   (op :kill-agent "n4")
   (op :forget-lease "lease.json")
   (op :start-agent {:node "n4" :at 1002000})
   (op :await-registered {:lease 5 :mod-revision 9 :revision 10 :at 1003000})
   (op :release {:lease 4 :at 1004000})
   (op :await-done done)
   (op :read-nodes read)
   (op :collect {:store {:rows rows :compacted 0} :end 1010000})])

(defn- put [rev lease] {:rev rev :idx 0 :key "/nodes/n4" :deleted false :lease lease})

(defn- judged [h] (sr/judge (sr/analyse h "n4" ttl)))

(deftest candidate-passes
  (let [j (judged (history
                    {:done {:succeeded false :revision 0 :at-ms 1004100 :lease 4}
                     :read {:status 200 :revision 12 :truncated false :at 12
                            :node {:lease 5 :mod-revision 9} :state "ready"}
                     :rows [(put 2 3) (put 9 5)]}))]
    (is (:valid? j) (pr-str j))
    (is (= :refused (:outcome j)))))

(deftest invocations-are-skipped
  (testing "each step's invocation (value nil) before its completion, as Jepsen records them"
    (let [h (history {:done {:succeeded false :revision 0 :at-ms 1004100 :lease 4}
                      :read {:status 200 :revision 12 :truncated false :at 12
                             :node {:lease 5 :mod-revision 9} :state "ready"}
                      :rows [(put 2 3) (put 9 5)]})
          j (sr/judge (sr/analyse (mapcat (fn [o] [(assoc o :value nil) o]) h) "n4" ttl))]
      (is (= :refused (:outcome j)) (pr-str j)))))

(deftest control-fails-and-says-so
  (let [j (judged (history
                    {:done {:succeeded true :revision 13 :at-ms 1004100 :lease 4}
                     :read {:status 200 :revision 13 :truncated false :at 13
                            :node {:lease 4 :mod-revision 13} :state "ready"}
                     :rows [(put 2 3) (put 9 5) (put 13 4)]}))]
    (is (not (:valid? j)))
    (is (= :stale-registration-landed (:outcome j)))
    (is (re-find #"succeeded" (:why j)))
    (is (re-find #"lower than an earlier" (:why j)))))

(deftest refused-but-moved-is-not-a-pass
  (testing "succeeded 0, yet the store shows a put on L1 after L2"
    (let [j (judged (history
                      {:done {:succeeded false :revision 0 :at-ms 1004100 :lease 4}
                       :read {:status 200 :revision 13 :truncated false :at 13
                              :node {:lease 4 :mod-revision 13} :state "ready"}
                       :rows [(put 2 3) (put 9 5) (put 13 4)]}))]
      (is (= :stale-registration-landed (:outcome j)))))
  (testing "succeeded 0, but /nodes/n4 is on L2 at another tenure"
    (let [j (judged (history
                      {:done {:succeeded false :revision 0 :at-ms 1004100 :lease 4}
                       :read {:status 200 :revision 13 :truncated false :at 13
                              :node {:lease 5 :mod-revision 11} :state "ready"}
                       :rows [(put 2 3) (put 9 5) (put 11 5)]}))]
      (is (= :unexpected (:outcome j))))))

(deftest vacuous-runs-fail
  (let [ok-read {:status 200 :revision 12 :truncated false :at 12
                 :node {:lease 5 :mod-revision 9} :state "ready"}
        refused {:succeeded false :revision 0 :at-ms 1004100 :lease 4}]
    (testing "released after L1 may have ended"
      (let [j (judged (history {:done (assoc refused :at-ms (+ 1000000 ttl -500))
                                :read ok-read :rows [(put 2 3) (put 9 5)]}))]
        (is (= :vacuous (:outcome j)) (pr-str j))
        (is (not (:valid? j)))))
    (testing "the barrier never said done"
      (let [j (judged (history {:done :timed-out :read ok-read
                                :rows [(put 2 3) (put 9 5)]}))]
        (is (= :vacuous (:outcome j)))))
    (testing "a truncated view"
      (let [j (judged (history {:done refused :read (assoc ok-read :truncated true)
                                :rows [(put 2 3) (put 9 5)]}))]
        (is (= :vacuous (:outcome j)))))))
