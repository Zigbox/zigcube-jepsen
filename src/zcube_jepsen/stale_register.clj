(ns zcube-jepsen.stale-register
  "The stale registration of Zigbox/zigcube#237, held at the server itself.

  Zigcube's test-only registration barrier (`zig build
  -Dregister-barrier=true`, src/server_register_barrier.zig; never in a
  shipped build) holds an armed `PUT /v1/agent/nodes/<node>` just before its
  transaction, while ZCUBE_TEST_REGISTER_BARRIER names a directory. The
  protocol, all files in that directory on the server node:

    the test writes    arm-<node>
    the server writes  held-<node>-<lease>     and waits
    the test writes    release-<node>-<lease>
    the server writes  done-<node>-<lease>     succeeded 0|1 revision N at_ms T

  (A held request with no release runs after 300 s.)

  This workload talks to the real server over its mTLS --listen: agents and
  the harness's client present their certificates (README, \"mTLS: Zigcube
  B7 and later\"). Nothing sits between agent and server; stale-put's plain
  HTTP hold proxy is not used.

  Steps (the nemesis does them in order, each an op in the history), for the
  first agent node (n4; the brief's n1):
    :start-agent :await-registered     the agent holds a tenure (L0)
    :kill-agent :await-expired         stopped past the ttl: L0 ends and
                                       /nodes/n4 is deleted
    :arm :start-agent                  arm-n4; it grants L1 and registers
    :await-held                        held-n4-L1: the PUT on L1 waits
    :kill-agent :forget-lease          killed, its saved lease (lease.json)
    :start-agent                       removed: it grants L2 (L1 still
                                       live) and registers
    :await-registered                  /nodes/n4 on L2 (> L1), ready
    :release :await-done               release-n4-L1, then done-n4-L1
    :read-nodes                        GET /v1/nodes?at=N
    :collect                           the store's log (psql)

  N is the revision the released PUT wrote when it succeeded, else the
  server's revision read right after done-n4-L1 appeared.

  A server with #281's lease order refuses the released PUT (503 ZC-E0205):
  `succeeded 0`, /nodes/n4 still on L2 at its own tenure, and no put of
  /nodes/n4 on a lease lower than an earlier one in the store (I2). A server
  without it (190f8dc) applies it: /nodes/n4 moves back to L1, a lease
  nobody renews, while the agent holds L2. The checker fails that run and
  says so (:outcome :stale-registration-landed).

  The run is vacuous (it fails, as :vacuous) when a step did not happen as
  planned, when L2 is not newer than L1, or when the released transaction
  ran after L1 may have ended: L1 was granted after the second
  :start-agent began, so it lives until at least that + the ttl, and done's
  at_ms (the server's clock) must be a margin before it. A PUT on an ended
  lease is refused for a reason that is not #237's."
  (:require [clojure.java.io :as io]
            [clojure.pprint :refer [pprint]]
            [clojure.string :as str]
            [clojure.tools.logging :refer [info warn]]
            [jepsen [checker :as checker]
                    [cli :as cli]
                    [control :as c]
                    [db :as db]
                    [generator :as gen]
                    [nemesis :as nemesis]
                    [os :as os]
                    [store :as store]
                    [tests :as tests]]
            [jepsen.control.net :as cn]
            [zcube-jepsen [api :as api]
                          [stale-put :as stale-put]
                          [zcube :as zcube]]))

(def barrier-dir
  "The barrier's directory on the server node. Outside zcube/state-dir,
  which the server's setup removes after this db made the directory."
  "/var/lib/zj-barrier")

(def lease-file
  "Where the agent keeps its lease, in its --state-dir (Zigcube agent.zig,
  `lease_file`)."
  "lease.json")

(def clock-margin-ms
  "How far before L1's earliest end the released transaction must run: the
  control host's clock against the server's, and the agent's grant after
  its start."
  1000)

(defn held-node [test] (first (zcube/agent-nodes test)))

;; ---------------------------------------------------------------- db

(defn db
  "zcube/db, with the barrier's directory made, empty, on the server node
  before the server starts (with ZCUBE_TEST_REGISTER_BARRIER pointing at it,
  :zcube-server-env), and no agent started: the nemesis starts them."
  []
  (let [zdb (zcube/db)]
    (reify db/DB
      (setup! [_ test node]
        (cond
          (= node (zcube/server-node test))
          (do (c/su (c/exec :rm :-rf barrier-dir)
                    (c/exec :mkdir :-p barrier-dir)
                    (c/exec :chmod "0700" barrier-dir))
              (db/setup! zdb test node))

          (some #{node} (zcube/agent-nodes test))
          (do (zcube/kill-all!)
              (c/su (c/exec :rm :-rf zcube/state-dir))
              (zcube/install! test :agent)
              (c/su (c/exec :mkdir :-p (str zcube/state-dir "/agent")))
              (when (:tls? (zcube/await-tls test))
                (zcube/install-node-tls! test node))
              (zcube/await-server! test))

          :else (db/setup! zdb test node)))
      (teardown! [_ test node]
        (db/teardown! zdb test node)
        (when (= node (zcube/server-node test))
          (c/su (c/exec :rm :-rf barrier-dir))))
      db/LogFiles
      (log-files [_ test node]
        (db/log-files zdb test node)))))

;; ---------------------------------------------------------------- the barrier

(defn parse-done
  "done-<node>-<lease>'s line as {:succeeded bool :revision n :at-ms t}, or
  nil while it is not all there."
  [s]
  (when-let [[_ ok rev at] (re-find #"^succeeded ([01]) revision (\d+) at_ms (\d+)\n"
                                    (or s ""))]
    {:succeeded (= "1" ok) :revision (parse-long rev) :at-ms (parse-long at)}))

(defn held-lease
  "The lease of the first held-<node>-<lease> among `names`, or nil."
  [names node]
  (some #(some-> (re-matches (re-pattern (str "held-" node "-(\\d+)")) %)
                 second parse-long)
        names))

(defn- on-server [test f]
  (get (c/on-nodes test [(zcube/server-node test)] (fn [_ _] (c/su (f))))
       (zcube/server-node test)))

(defn- touch! [test file]
  (on-server test #(c/exec :touch (str barrier-dir "/" file))))

(defn- barrier-files [test]
  (str/split-lines (on-server test #(c/exec :ls :-1 barrier-dir))))

(defn- read-barrier-file [test file]
  (on-server test #(c/exec :bash :-c
                           (str "cat " barrier-dir "/" file " 2>/dev/null || true"))))

;; ---------------------------------------------------------------- nemesis

(defn now-ms [] (System/currentTimeMillis))

(defn poll!
  "Calls (f) every 200 ms until it is truthy or `ms` pass; its value or nil."
  [ms f]
  (let [deadline (+ (now-ms) ms)]
    (loop []
      (let [v (try (f) (catch Exception e (warn "poll:" (.getMessage e)) nil))]
        (cond v v
              (< (now-ms) deadline) (do (Thread/sleep 200) (recur))
              :else nil)))))

(defn nodes-view
  "GET /v1/nodes[?at=N]: {:status :revision :truncated :node} with :node the
  row's /nodes/<node> kv (nil when absent)."
  [http test node at]
  (let [res (api/request http (zcube/base-url test) :get
                         (str "/v1/nodes" (when at (str "?at=" at))) nil)
        b   (:body res)
        row (first (filter #(= node (:name %)) (:nodes b)))]
    {:status (:status res) :revision (:revision b) :truncated (:truncated b)
     :node (when-let [kv (:node row)]
             {:lease (:lease kv) :mod-revision (:mod_revision kv)})
     :state (-> row :status :value :state)}))

(defn step!
  "Does one step; returns the op's value."
  [test st op]
  (let [http (:http @st)
        n    (held-node test)]
    (case (:f op)
      :start-agent
      (let [t (now-ms)]
        (c/on-nodes test [n] (fn [_ _] (zcube/start-agent! test n)))
        (swap! st assoc :started-at t)
        {:node n :at t})

      :kill-agent
      (do (c/on-nodes test [n] (fn [_ _] (stale-put/kill-agent!))) n)

      :await-registered
      ; A registration newer than any lease seen so far, and ready.
      (let [above (or (:l1 @st) 0)
            r (poll! 30000
                     (fn []
                       (let [v (nodes-view http test n nil)]
                         (when (and (= 200 (:status v)) (:node v)
                                    (< above (-> v :node :lease))
                                    (= "ready" (:state v)))
                           (assoc (:node v) :revision (:revision v) :at (now-ms))))))]
        (if r
          (do (swap! st assoc :registered r) r)
          (do (warn "agent on" n "never registered; its log:\n"
                    (stale-put/agent-log-tail test n))
              :timed-out)))

      :await-expired
      ; The stopped agent's lease (L0) ran out: the server deleted /nodes/<n>,
      ; so the next start must grant a new lease and register.
      (let [ms (+ (:lease-ttl-ms test) (:lost-grace-ms test 1000) 15000)
            r  (poll! ms (fn []
                           (let [v (nodes-view http test n nil)]
                             (when (and (= 200 (:status v)) (nil? (:node v)))
                               {:revision (:revision v) :at (now-ms)}))))]
        (or r :timed-out))

      :forget-lease
      ; The agent keeps its lease in its state directory and resumes it after
      ; a restart (0.5.2, agent.lease_file). Without it, the restarted agent
      ; grants a new lease while the old one is still live.
      (do (c/on-nodes test [n]
                      (fn [_ _] (c/su (c/exec :rm :-f (str zcube/state-dir "/agent/" lease-file)))))
          lease-file)

      :arm (do (touch! test (str "arm-" n)) (str "arm-" n))

      :await-held
      (let [l1 (poll! 20000 #(held-lease (barrier-files test) n))]
        (if l1
          (do (swap! st assoc :l1 l1 :l1-started (:started-at @st))
              {:lease l1 :agent-started (:started-at @st)
               :held (str/trim (read-barrier-file test (str "held-" n "-" l1)))})
          :timed-out))

      :release
      (let [l1 (or (:l1 @st) (throw (ex-info "no lease was held" {})))
            t  (now-ms)]
        (touch! test (str "release-" n "-" l1))
        {:lease l1 :at t})

      :await-done
      (let [l1 (or (:l1 @st) (throw (ex-info "no lease was held" {})))
            d  (poll! 30000 #(parse-done (read-barrier-file test (str "done-" n "-" l1))))]
        (if d
          (let [now-rev (:revision (nodes-view http test n nil))]
            (swap! st assoc :done d :now-revision now-rev)
            (assoc d :lease l1 :server-revision-after now-rev))
          :timed-out))

      :read-nodes
      (let [{:keys [done now-revision]} @st
            at (if (:succeeded done) (:revision done) now-revision)]
        (when-not at (throw (ex-info "no revision to read at" {})))
        (assoc (nodes-view http test n at) :at at))

      :collect
      {:store (stale-put/read-store test) :end (now-ms)})))

(defn the-nemesis []
  (let [st (atom {})]
    (reify nemesis/Nemesis
      (setup! [this test]
        (swap! st assoc :http (api/http-client test))
        this)
      (invoke! [_ test op]
        (let [v (try (step! test st op)
                     (catch Exception e
                       (warn e "step" (:f op))
                       [:error (.getMessage e)]))]
          (info "step" (:f op) "->" (if (= :collect (:f op))
                                       (str (count (-> v :store :rows)) " store rows")
                                       (pr-str v)))
          (assoc op :value v)))
      (teardown! [_ test]))))

(defn steps [_test]
  (mapv (fn [f] {:type :info :f f :value nil})
        [:start-agent :await-registered
         :kill-agent :await-expired
         :arm :start-agent :await-held
         :kill-agent :forget-lease :start-agent :await-registered
         :release :await-done :read-nodes :collect]))

;; ---------------------------------------------------------------- checks

(defn analyse
  "What the checker judges, from the steps' values."
  [history node ttl-ms]
  (let [infos (filter #(and (= :info (:type %)) (= :nemesis (:process % :nemesis))) history)
        vals  (fn [f] (->> infos (filter #(= f (:f %))) (map :value)))
        regs  (vals :await-registered)
        held  (last (vals :await-held))
        done  (last (vals :await-done))
        read  (last (vals :read-nodes))
        col   (last (vals :collect))
        l0    (first regs)
        l2    (second regs)
        l1    (when (map? held) (:lease held))
        rows  (if (map? col) (-> col :store :rows) [])
        stale (stale-put/stale-registrations rows)
        live-until (when (and (map? held) (integer? (:agent-started held)))
                     (+ (:agent-started held) ttl-ms))]
    {:failed-steps (vec (for [o infos :when (stale-put/failed-step? (:value o))]
                          [(:f o) (:value o)]))
     :l0 (when (map? l0) (:lease l0))
     :l1 l1
     :l2 (when (map? l2) (:lease l2))
     :l2-tenure (when (map? l2) (:mod-revision l2))
     :done (when (map? done) done)
     :l1-live-until live-until
     :in-time (boolean (and (map? done) live-until
                            (< (:at-ms done) (- live-until clock-margin-ms))))
     :nodes-at (when (map? read) read)
     :stale-registrations (vec (filter #(= (str "/nodes/" node) (:key %)) stale))
     :store-collected (map? col)
     :rows rows}))

(defn judge
  "{:valid? :outcome :why} from analyse's map."
  [a]
  (let [{:keys [l1 l2 l2-tenure done nodes-at]} a
        n-lease (-> nodes-at :node :lease)
        vacuous (cond (seq (:failed-steps a)) "a step did not happen as planned"
                      (not (and l1 l2 (< l1 l2))) "L2 is not a lease newer than L1"
                      (not (:in-time a)) (str "the released transaction ran after L1"
                                              " may have ended, or its time is unknown")
                      (not= 200 (:status nodes-at)) "GET /v1/nodes?at=N failed"
                      (:truncated nodes-at) "GET /v1/nodes?at=N was truncated"
                      (not (:store-collected a)) "the store's log was not read")
        landed  (or (:succeeded done) (= l1 n-lease) (seq (:stale-registrations a)))
        kept    (and (false? (:succeeded done)) (= l2 n-lease)
                     (= l2-tenure (-> nodes-at :node :mod-revision))
                     (empty? (:stale-registrations a)))]
    (cond
      vacuous {:valid? false :outcome :vacuous :why vacuous}
      landed  {:valid? false :outcome :stale-registration-landed
               :why (str "the registration on L1 (" l1 "), released after the node"
                         " registered on L2 (" l2 ") while L1 was live, "
                         (if (:succeeded done) "succeeded" "was refused")
                         "; at revision " (:at nodes-at) " /nodes/<node> is on lease "
                         (pr-str n-lease)
                         (when (seq (:stale-registrations a))
                           (str "; the store holds a put on a lease lower than an earlier one: "
                                (pr-str (:stale-registrations a)))))}
      kept    {:valid? true :outcome :refused
               :why (str "the registration on L1 (" l1 ") was refused (succeeded 0); at"
                         " revision " (:at nodes-at) " /nodes/<node> is still on L2 ("
                         l2 ") at its tenure " l2-tenure)}
      :else   {:valid? false :outcome :unexpected
               :why (str "refused, but /nodes/<node> at revision " (:at nodes-at)
                         " is " (pr-str (:node nodes-at)) ", not L2 " l2
                         " at tenure " l2-tenure)})))

(defn checker []
  (reify checker/Checker
    (check [_ test history _opts]
      (let [a (analyse history (held-node test) (:lease-ttl-ms test))
            j (judge a)
            summary (merge j (dissoc a :rows))]
        (info "stale-register:" (pr-str (select-keys j [:valid? :outcome :why])))
        (with-open [w (io/writer (store/path! test "stale-register.edn"))]
          (binding [*out* w]
            (pprint (assoc summary
                           :steps (vec (for [o history :when (= :nemesis (:process o))
                                             :when (not= :collect (:f o))]
                                         (select-keys o [:time :type :f :value])))
                           :store-rows (:rows a)))))
        summary))))

(defn stale-register-test [opts]
  (merge tests/noop-test
         opts
         (zcube/tls-test-keys)
         {:name      (str "zcube-stale-register" (when-let [l (:label opts)] (str "-" l)))
          :os        os/noop
          :db        (db)
          :zcube-server-env {"ZCUBE_TEST_REGISTER_BARRIER" barrier-dir}
          :nemesis   (the-nemesis)
          :checker   (checker)
          :generator (gen/nemesis (steps opts))}))

(def cli-opts
  [[nil "--zcube-bin DIR"
    (str "Directory holding zcube-server (built with -Dregister-barrier=true)"
         " and zcube-agent.")
    :default "zigcube/zig-out/bin"]
   [nil "--zcube-images FILE" "The fake runtime's image list."
    :default "zigcube/fixtures/images.zon"]
   [nil "--lease-ttl-ms N" "zcube-server's --lease-ttl-ms."
    :default 10000 :parse-fn parse-long]
   [nil "--replication MODE" "The Postgres pair: sync (the supported setup)."
    :default :sync :parse-fn keyword]
   [nil "--zcube-tls MODE"
    "auto (the server's --help decides), on or off."
    :id :zcube-tls-mode :default :auto :parse-fn keyword
    :validate [#{:auto :on :off} "must be auto, on or off"]]
   [nil "--label NAME" "Added to the test's name (candidate, control)."
    :default nil]])

(defn -main [& args]
  (cli/run! (merge (cli/single-test-cmd {:test-fn  stale-register-test
                                         :opt-spec cli-opts})
                   (cli/serve-cmd))
            args))
