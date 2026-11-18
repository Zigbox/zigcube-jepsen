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
    :observe-after                     /nodes/n4 through a ttl and grace
    :collect                           the store's log (psql), the
                                       server log's PUT lines for n4

  N is the revision the released PUT wrote when it succeeded, else the
  server's revision read right after done-n4-L1 appeared. Right after
  done-n4-L1 the step reads L1's and L2's rows from the store's lease table;
  :observe-after then watches /nodes/n4 through a ttl and the lost grace.

  Each verdict is proved from evidence, never from its absence. The run is
  :vacuous (it did not set up #237's race) unless all of these hold: every
  step happened; the run used mTLS; held-n4-L1 was written (the hold was
  reached); L0 < L1 < L2; done-n4-L1 holds its succeeded flag; done came at
  or after the release (less a clock margin); a PUT that succeeded committed
  above L2's tenure; done came a margin before L1's earliest end (the
  agent's start + the ttl); and the store's lease table had L1 and L2 live
  right after done (so live when the PUT ran). Finding (a), the landing:
    :landed   done succeeded, and the store has /nodes/n4 put on L1 at
              done's revision
    :refused  done refused, the server log answered the held PUT 503 (its
              line: at or after done, as long as the hold), and the store
              has no put of /nodes/n4 on L1 after L2's tenure
    :unknown  anything else (a missing log line, a store that disagrees)
  Finding (b), the resulting state: /nodes/n4 at N (on L1 at done's
  revision, on L2 at its tenure, absent, other), and what it did after.

  :outcome is :stale-registration-landed when (a) is :landed and /nodes/n4
  is on L1 at N; :refused when (a) is :refused, /nodes/n4 is on L2 at its
  tenure at N and the store has no put on a lower lease after a higher one
  (I2); :vacuous or :unknown as above; else :unexpected. --expect names the
  outcome that passes: refused (a server with #281's lease order) or
  stale-registration-landed (the 0.5.2 control, a known-bad control).

  The barrier records only whether the answer was 200, so ZC-E0205 itself is
  not observed: the 503 with both leases live in the store and the key on
  the newer one is what #281's lease order gives. A 503 for another reason
  at that moment (an unavailable store) would also read as :refused."
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
                          [postgres :as pg]
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

(def log-window-ms
  "How long after done's at_ms the server's log line for the released PUT
  may come (it is written once the answer is)."
  5000)

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

(defn- read-barrier-file
  "The file's bytes, \"\" when absent. A `|` is printed after them and taken
  off here, since c/exec trims the trailing newline that parse-done needs to
  see the line is complete."
  [test file]
  (let [out (on-server test #(c/exec :bash :-c
                                     (str "cat " barrier-dir "/" file " 2>/dev/null; echo '|'")))]
    (cond-> out (str/ends-with? out "|") (subs 0 (dec (count out))))))

;; ---------------------------------------------------------------- the store's leases

(defn lease-sql
  "The rows of `ids` in the store's lease table, each with whether it is
  live now on the database's clock (Zigcube store_postgres.zig, `lease_now`
  and `sql_lease_alive`). Lease ids are never reused and an ended lease's
  row is deleted, so a row live now was live at every moment since its
  grant."
  [ids]
  (str "SELECT id, deadline_ms,"
       " (deadline_ms >= FLOOR(EXTRACT(EPOCH FROM clock_timestamp()) * 1000)::bigint),"
       " FLOOR(EXTRACT(EPOCH FROM clock_timestamp()) * 1000)::bigint"
       " FROM zcube.store_lease WHERE id IN (" (str/join ", " (map long ids)) ") ORDER BY id"))

(defn parse-lease-rows
  "psql -At rows as [{:id :deadline-ms :live :read-at-ms}]."
  [lines]
  (vec (for [l lines :when (not (str/blank? l))
             :let [[id dl live at] (str/split l #"\t")]]
         {:id (parse-long id) :deadline-ms (parse-long dl) :live (= "t" live)
          :read-at-ms (parse-long at)})))

(defn read-leases [test ids]
  (let [node (first (pg/pg-nodes test))]
    (-> (c/on-nodes test [node]
                    (fn [_ _]
                      (pg/pg! :psql :-h node :-U :jepsen :-d :jepsen
                              :-v "ON_ERROR_STOP=1" :-At :-F "\t" :-c (lease-sql ids))))
        (get node)
        str/split-lines
        parse-lease-rows)))

(defn server-put-lines
  "The server log's lines for PUT /v1/agent/nodes/<node>."
  [test node]
  (str/split-lines
    (on-server test #(c/exec :bash :-c
                             (str "grep -F ' PUT /v1/agent/nodes/" node " -> ' "
                                  zcube/server-log " || true")))))

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
          ; At once: L1 and L2 live in the store now means live when the
          ; released PUT ran (lease-sql).
          (let [leases  (read-leases test (keep identity [l1 (-> @st :registered :lease)]))
                now-rev (:revision (nodes-view http test n nil))]
            (swap! st assoc :done d :now-revision now-rev)
            (assoc d :lease l1 :leases leases :server-revision-after now-rev))
          :timed-out))

      :read-nodes
      (let [{:keys [done now-revision]} @st
            at (if (:succeeded done) (:revision done) now-revision)]
        (when-not at (throw (ex-info "no revision to read at" {})))
        (assoc (nodes-view http test n at) :at at))

      :observe-after
      ; Finding (b): what /nodes/<n> does after N, through L1's ttl and the
      ; lost grace. Observed and recorded, not judged.
      (let [ms  (+ (:lease-ttl-ms test) (:lost-grace-ms test 1000) 5000)
            end (+ (now-ms) ms)]
        (loop [seen []]
          (let [v   (try (nodes-view http test n nil) (catch Exception e {:error (.getMessage e)}))
                obs {:node (:node v) :state (:state v) :status (:status v)}
                seen (if (= obs (dissoc (peek seen) :at-ms))
                       seen
                       (conj seen (assoc obs :at-ms (now-ms))))]
            (if (< (now-ms) end)
              (do (Thread/sleep 500) (recur seen))
              {:for-ms ms :changes seen :last (peek seen)}))))

      :collect
      {:store (stale-put/read-store test)
       :server-log (server-put-lines test n)
       :end (now-ms)})))

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
         :release :await-done :read-nodes :observe-after :collect]))

;; ---------------------------------------------------------------- checks

(defn put-log-line
  "One line of the server's request log (Zigcube server_http.zig,
  `log_request`: \"[<unix ms>] <request id> <method> <path> -> <status>
  (<ms> ms)\"), or nil."
  [line]
  (when-let [[_ at id method path status took]
             (re-find #"^\[(\d+)\] (\S+) (\S+) (\S+) -> (\d+) \((\d+) ms\)" (or line ""))]
    {:at-ms (parse-long at) :request-id id :method method :path path
     :status (parse-long status) :took-ms (parse-long took)}))

(defn released-put-line
  "The log line of the released PUT: the first PUT of
  /v1/agent/nodes/<node> logged at or after done's at_ms (the server logs a
  request after it has answered, and the barrier writes done before that),
  within `log-window-ms`, that took at least as long as it was held (from
  held's at_ms to done's, less the margin). Nil when there is none: the
  server logs a request only once its answer is written, so a reply to a
  connection that is gone may not be logged."
  [lines node held-at done-at]
  (when (and (integer? held-at) (integer? done-at))
    (let [path (str "/v1/agent/nodes/" node)
          held-ms (- done-at held-at)]
      (->> lines
           (keep put-log-line)
           (filter #(and (= "PUT" (:method %)) (= path (:path %))
                         (<= done-at (:at-ms %) (+ done-at log-window-ms))
                         (>= (:took-ms %) (- held-ms clock-margin-ms))))
           first))))

(defn analyse
  "The evidence the checker judges, from the steps' values."
  [history node ttl-ms]
  (let [infos (filter #(and (= :info (:type %)) (= :nemesis (:process % :nemesis))) history)
        ; The nemesis logs each step twice, invoked (value nil) and done.
        vals  (fn [f] (->> infos (filter #(= f (:f %))) (keep :value)))
        regs  (vals :await-registered)
        held  (last (vals :await-held))
        rel   (last (vals :release))
        done  (last (vals :await-done))
        read  (last (vals :read-nodes))
        after (last (vals :observe-after))
        col   (last (vals :collect))
        l0    (first regs)
        l2    (second regs)
        l1    (when (map? held) (:lease held))
        held-at (when (map? held) (:at-ms (parse-done (str (:held held) "\n"))))
        done* (when (map? done) (select-keys done [:succeeded :revision :at-ms]))
        rows  (if (map? col) (-> col :store :rows) [])
        k     (str "/nodes/" node)
        live-until (when (and (map? held) (integer? (:agent-started held)))
                     (+ (:agent-started held) ttl-ms))
        leases (when (map? done) (:leases done))
        line  (when (map? col)
                (released-put-line (:server-log col) node held-at (:at-ms done*)))]
    {:failed-steps (vec (for [o infos :when (stale-put/failed-step? (:value o))]
                          [(:f o) (:value o)]))
     :l0 (when (map? l0) (:lease l0))
     :l1 l1
     :l2 (when (map? l2) (:lease l2))
     :l2-tenure (when (map? l2) (:mod-revision l2))
     :held-at-ms held-at
     :release-at-ms (when (map? rel) (:at rel))
     :done done*
     :l1-live-until live-until
     :in-time (boolean (and done* live-until
                            (< (:at-ms done*) (- live-until clock-margin-ms))))
     :leases-after-done leases
     :released-put-log line
     :nodes-at (when (map? read) read)
     :after (when (map? after) after)
     :l1-puts-after-l2 (vec (for [r rows
                                  :when (and (= k (:key r)) (not (:deleted r))
                                             (= l1 (:lease r)) l2
                                             (integer? (:mod-revision l2))
                                             (< (:mod-revision l2) (:rev r)))]
                              (select-keys r [:rev :key :lease])))
     :stale-registrations (vec (filter #(= k (:key %)) (stale-put/stale-registrations rows)))
     :store-collected (map? col)
     :rows rows}))

(defn- live? [leases id]
  (true? (:live (first (filter #(= id (:id %)) leases)))))

(defn- vacuous-why
  "Why the run did not set up #237's race, or nil when every precondition is
  proved."
  [a]
  (let [{:keys [l0 l1 l2 l2-tenure done held-at-ms release-at-ms leases-after-done]} a]
    (cond (seq (:failed-steps a)) "a step did not happen as planned"
          (not (true? (:tls? a))) "the run did not use mTLS"
          (not (and l1 held-at-ms)) "the hold was not reached (no held-<node>-<lease>)"
          (not (and l0 l1 l2 (< l0 l1 l2)))
          "the leases are not in order L0 < L1 < L2 (the held PUT may be on a resumed lease)"
          (not (integer? l2-tenure)) "L2's tenure is unknown"
          (not release-at-ms) "the release was not written"
          (not done) "no done-<node>-<lease> with its succeeded flag"
          (< (:at-ms done) (- release-at-ms clock-margin-ms))
          "the held PUT finished before the release: the hold did not hold"
          (and (:succeeded done) (<= (:revision done) l2-tenure))
          "the held PUT committed at or before L2's tenure: an in-order write, not a late one"
          (not (:in-time a)) (str "the released transaction ran after L1"
                                  " may have ended, or its time is unknown")
          (not (seq leases-after-done)) "the store's leases were not read after done"
          (not (live? leases-after-done l1)) "L1 was not live in the store after done"
          (not (live? leases-after-done l2)) "L2 was not live in the store after done"
          (not= 200 (:status (:nodes-at a))) "GET /v1/nodes?at=N failed"
          (:truncated (:nodes-at a)) "GET /v1/nodes?at=N was truncated"
          (not (:store-collected a)) "the store's log was not read")))

(defn finding-a
  "Finding (a), the landing: {:result :landed|:refused|:unknown :why}, each
  proved from the barrier's done file, the store and the server log; never
  from the absence of evidence."
  [a]
  (let [{:keys [l1 l2 l2-tenure done released-put-log]} a
        put-at-done (some #(and (= (:revision done) (:rev %)) %) (:l1-puts-after-l2 a))]
    (cond
      (:succeeded done)
      (if put-at-done
        {:result :landed
         :why (str "the PUT on L1 (" l1 "), held until after the node registered on L2 ("
                   l2 ", tenure " l2-tenure ") and released while both were live, succeeded"
                   " at revision " (:revision done) " > " l2-tenure
                   "; the store has /nodes/<node> put on L1 at that revision")}
        {:result :unknown
         :why (str "done says succeeded at revision " (:revision done)
                   ", but the store has no put of /nodes/<node> on L1 there")})

      (seq (:l1-puts-after-l2 a))
      {:result :unknown
       :why (str "done says refused, but the store has puts on L1 after L2's tenure: "
                 (pr-str (:l1-puts-after-l2 a)))}

      (nil? released-put-log)
      {:result :unknown
       :why "done says refused (not 200), but the server log has no line for the released PUT"}

      (not= 503 (:status released-put-log))
      {:result :unknown
       :why (str "the released PUT was answered " (:status released-put-log) ", not 503")}

      :else
      {:result :refused
       :why (str "the PUT on L1 (" l1 "), released after the node registered on L2 ("
                 l2 ") while the store had both leases live, was answered 503 ("
                 (:request-id released-put-log) ") and wrote nothing")})))

(defn finding-b
  "Finding (b), the resulting state: /nodes/<node> at N, and what the node's
  key did in the ttl and grace after it (observed, not judged)."
  [a]
  (let [{:keys [l1 l2 l2-tenure done nodes-at after]} a
        n (:node nodes-at)]
    {:at-n {:revision (:at nodes-at) :node n :state (:state nodes-at)
            :on (cond (nil? n) :absent
                      (and (= l1 (:lease n)) (= (:revision done) (:mod-revision n))) :l1
                      (and (= l2 (:lease n)) (= l2-tenure (:mod-revision n))) :l2-at-tenure
                      :else :other)}
     :after after}))

(defn judge
  "{:valid? :outcome :expected :why :finding-a :finding-b} from analyse's
  map (with :tls?) and the expected outcome: :refused (a server with #281)
  or :stale-registration-landed (the 0.5.2 control)."
  ([a] (judge a :refused))
  ([a expect]
   (let [vacuous (vacuous-why a)
         fa (when-not vacuous (finding-a a))
         fb (finding-b a)
         on (-> fb :at-n :on)
         [outcome why]
         (cond
           vacuous [:vacuous vacuous]
           (= :unknown (:result fa)) [:unknown (:why fa)]
           (and (= :landed (:result fa)) (= :l1 on))
           [:stale-registration-landed
            (str (:why fa) "; at revision " (-> fb :at-n :revision)
                 " /nodes/<node> is on L1, which nobody renews")]
           (and (= :refused (:result fa)) (= :l2-at-tenure on)
                (empty? (:stale-registrations a)))
           [:refused (str (:why fa) "; at revision " (-> fb :at-n :revision)
                          " /nodes/<node> is still on L2 at its tenure")]
           :else
           [:unexpected (str (:why fa) "; but at revision " (-> fb :at-n :revision)
                             " /nodes/<node> is " (pr-str (-> fb :at-n :node))
                             (when (seq (:stale-registrations a))
                               (str "; the store holds a put on a lease lower than an earlier one: "
                                    (pr-str (:stale-registrations a)))))])]
     {:valid? (= expect outcome) :outcome outcome :expected expect :why why
      :finding-a fa :finding-b fb})))

(defn checker [expect]
  (reify checker/Checker
    (check [_ test history _opts]
      (let [a (assoc (analyse history (held-node test) (:lease-ttl-ms test))
                     :tls? (try (zcube/tls? test) (catch Exception _ nil)))
            j (judge a expect)
            summary (merge j (dissoc a :rows))]
        (info "stale-register:" (pr-str (select-keys j [:valid? :outcome :expected :why])))
        (with-open [w (io/writer (store/path! test "stale-register.edn"))]
          (binding [*out* w]
            (pprint (assoc summary
                           :steps (vec (for [o history :when (= :nemesis (:process o))
                                             :when (not= :collect (:f o))]
                                         (select-keys o [:time :type :f :value])))
                           :store-rows (:rows a)))))
        summary))))

(def expect-outcomes #{:refused :stale-registration-landed})

(defn stale-register-test [opts]
  (merge tests/noop-test
         opts
         (zcube/tls-test-keys)
         {:name      (str "zcube-stale-register" (when-let [l (:label opts)] (str "-" l)))
          :os        os/noop
          :db        (db)
          ; The owner's requirement is the real server over mTLS: never the
          ; --help guess, never plain HTTP. A binary without mTLS fails setup.
          :zcube-tls-mode :on
          :zcube-server-env {"ZCUBE_TEST_REGISTER_BARRIER" barrier-dir}
          :nemesis   (the-nemesis)
          :checker   (checker (:expect opts))
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
   [nil "--expect OUTCOME"
    (str "refused (a server with #281's lease order) or stale-registration-landed"
         " (the 0.5.2 control). The test passes only on that outcome.")
    :default :refused :parse-fn keyword
    :validate [expect-outcomes "must be refused or stale-registration-landed"]]
   [nil "--label NAME" "Added to the test's name (candidate, control)."
    :default nil]])

(defn -main [& args]
  (cli/run! (merge (cli/single-test-cmd {:test-fn  stale-register-test
                                         :opt-spec cli-opts})
                   (cli/serve-cmd))
            args))
