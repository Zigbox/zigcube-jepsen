(ns zcube-jepsen.nemesis
  "Partitions that cut the clients off too.

  The clients run in the Jepsen control process, on the host, which a plain
  node partition never includes: whatever the halves, every client still
  reaches every node. Here the control host is a member of the partition: it
  falls on one side at random, and every node on the other side drops what
  the clients send it (the client ports only: Postgres 5432 and zcube-server
  7070). So a client is cut off from exactly the nodes a node on its side
  would be.

  SSH (port 22) is left alone on purpose: it is Jepsen's control plane, which
  the nemesis needs in order to heal, not a client of the system under test.
  The rules are plain iptables INPUT rules like the node grudge's, so the
  usual heal (`iptables -F` on every node) removes both."
  (:require [clojure.tools.logging :refer [info]]
            [jepsen [control :as c]
                    [nemesis :as nemesis]
                    [net :as net]]
            [jepsen.control.net :as cn]))

(def client-ports
  "What a client of either workload connects to."
  "5432,7070")

(defn control-ip
  "The control host's address as the nodes see it (from $SSH_CLIENT)."
  [test]
  (-> (c/on-nodes test [(first (:nodes test))] (fn [_ _] (cn/control-ip)))
      vals
      first))

(defn cut-clients!
  "Drops client traffic from the control host on `nodes`."
  [test ctl nodes]
  (when (seq nodes)
    (c/on-nodes test nodes
                (fn [_ _]
                  (c/su (c/exec :iptables :-A :INPUT :-s ctl :-p :tcp
                                :-m :multiport :--dports client-ports
                                :-j :DROP :-w))))))

(defn plan
  "Random halves of `nodes`, and the side the control host is on (nil: none,
  the old behaviour). Returns {:halves [a b], :control-side a-or-b-or-nil,
  :cut nodes-clients-cannot-reach}."
  [nodes clients?]
  (let [[a b] (->> nodes shuffle (split-at (quot (count nodes) 2))
                   (map (comp vec sort)))
        side (when clients? (rand-nth [a b]))]
    {:halves       [a b]
     :control-side side
     :cut          (cond (nil? side) []
                         (= side a)  b
                         :else       a)}))

(defn partition-nemesis
  "Handles :start (random halves of the nodes, the control host on one side
  when the test's :client-partition is true) and :stop (heal everything)."
  []
  (let [ctl (atom nil)]
    (reify nemesis/Nemesis
      (setup! [this test]
        (net/heal! (:net test) test)
        (reset! ctl (control-ip test))
        (info "control host as the nodes see it:" @ctl)
        this)

      (invoke! [_ test op]
        (case (:f op)
          :start
          (let [{:keys [halves control-side cut]}
                (plan (:nodes test) (:client-partition test true))]
            (net/drop-all! test (nemesis/complete-grudge halves))
            (cut-clients! test @ctl cut)
            (assoc op :value {:halves       halves
                              :clients-with (or control-side :all)
                              :clients-cut-from cut}))
          :stop
          (do (net/heal! (:net test) test)
              (assoc op :value :network-healed))))

      (teardown! [_ test]
        (net/heal! (:net test) test)))))
