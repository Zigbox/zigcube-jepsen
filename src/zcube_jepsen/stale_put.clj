(ns zcube-jepsen.stale-put
  "Slice 5: a registration PUT that lands after a newer one (Zigcube #237).

  The agent on n4 reaches zcube-server through a proxy in the control process
  (zcube-jepsen.hold-proxy). The proxy holds the agent's first registration,
  `PUT /v1/agent/nodes/n4` on lease L1, and never answers it. The agent is
  then killed (SIGKILL) and started again, as an agent restart would: it is
  granted L2 and registers on it. Work is placed on n4 and starts. The proxy
  then delivers the held PUT, while L1 is still live. The server applies a
  registration with no compare, so on an affected server /nodes/n4 moves back
  to L1 at a new revision: n4's tenure changes under an agent that still
  renews L2.

  Steps, each an op in the history (the nemesis does them in order):
    :arm :start-agent(n4) :await-held :kill-agent :start-agent(n4)
    :await-registered, then
      --release-after running:    :apply :await-running :release
      --release-after registered: :release :apply
    with :start-agent(n5) :await-ready(n5) just before the release
    (--spare-at before) or just after it, then :observe :collect. --services
    0 applies nothing: the idle node of #237's liveness note. A wait that
    fails (:timed-out, :deadline) or a step that throws makes the run vacuous.

  What is checked, from the store's log (zcube.store_log, read at the end):
    stale-registrations  I2: a put of /nodes/<node> on a lower lease than an
                         earlier put of that key (lease ids only grow), i.e.
                         a registration committed although a newer tenure had
                         superseded it.
    superseded-observed  I2's tenure half: a put of /observed/<node>/<id>
                         whose tenure is not the revision /nodes/<node> had
                         just before it.
    i1a                  replaying the log: at every revision each replica id
                         has at most one /assigned key and at most one node
                         under which its observation is admissible
                         (docs/authority.md).
  and from the nodes' own process records (resources/proc-watch.sh):
    overlap              per service, how long two different replicas (or one
                         replica on two nodes) may have run at once, against
                         the I1b window of 1.9 s. Each run of samples is
                         widened to the samples around it. Slice 2 builds the
                         general overlap checker; this is the smallest one
                         this slice needs.
  and from the proxy's log against the store:
    orphan-keepalives    renewals of a lease of n4's, answered 200 after the
                         release, while /nodes/n4 (replayed from the store, at
                         the time of the answer) named another lease or none.
  Liveness is reported, not judged: n4's final registration and status.

  --expect decides what passes:
    bug    the release must be answered 200, and the stale registration in
           the store must be the release's own put (its revision, on L1): the
           expected failure while #237 is open.
    valid  the release must have been refused while L1 was still live, by a
           4xx that is not ZC-E0205 (lease ended) and either names a code in
           `refusal-codes` or left /nodes/n4 on L2; the run must be complete
           (collect worked, the store log starts at revision 1 uncompacted,
           L2's registration is in it, every agent node has process samples)
           and every check must hold (a fixed server).
    auto   bug if the release was answered 200, valid if it was refused as
           above, else the run is vacuous and invalid."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
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
                    [tests :as tests]
                    [util :as util]]
            [jepsen.control.util :as cu]
            [jepsen.control.net :as cn]
            [zcube-jepsen [api :as api]
                          [hold-proxy :as hp]
                          [postgres :as pg]
                          [zcube :as zcube]]))

(def window-ms
  "The I1b window, lost_grace_ms + kill latency (docs/authority.md)."
  1900)

(def procs-log (str zcube/state-dir "/procs.log"))
(def procs-pid (str zcube/state-dir "/procs.pid"))
(def watcher (str zcube/dir "/proc-watch.sh"))

(defn held-node [test] (first (zcube/agent-nodes test)))
(defn spare-node [test] (second (zcube/agent-nodes test)))

;; ---------------------------------------------------------------- db

(defn start-watcher! []
  (c/su (cu/start-daemon! {:logfile procs-log :pidfile procs-pid :chdir "/"}
                          watcher)))

(defn db
  "zcube-jepsen.zcube's db for the Postgres pair and the server. The agent
  nodes get the agent binary and a process watcher, but no agent: the
  nemesis starts them."
  []
  (let [zdb (zcube/db)]
    (reify db/DB
      (setup! [_ test node]
        (if (some #{node} (zcube/agent-nodes test))
          (do (pg/flush-rules!)
              (zcube/kill-all!)
              (c/su (c/exec :bash :-c "pkill -f '[p]roc-watch.sh'; true")
                    (c/exec :rm :-rf zcube/state-dir))
              (zcube/install! test :agent)
              (c/su (c/exec :mkdir :-p (str zcube/state-dir "/agent")))
              (let [f (java.io.File/createTempFile "proc-watch" ".sh")]
                (spit f (slurp (io/resource "proc-watch.sh")))
                (c/upload (str f) watcher))
              (c/su (c/exec :chmod :+x watcher))
              (start-watcher!)
              (zcube/await-server! test))
          (db/setup! zdb test node)))
      (teardown! [_ test node]
        (when (some #{node} (zcube/agent-nodes test))
          (c/su (c/exec :bash :-c "pkill -f '[p]roc-watch.sh'; true")))
        (db/teardown! zdb test node))
      db/LogFiles
      (log-files [_ test node]
        (cond-> (db/log-files zdb test node)
          (some #{node} (zcube/agent-nodes test)) (conj procs-log))))))

;; ---------------------------------------------------------------- agents

(defn start-agent!
  "zcube/start-agent!, with --server at `server` (host:port)."
  [node server]
  (c/su
    (cu/start-daemon!
      {:logfile zcube/agent-log :pidfile zcube/agent-pid :chdir zcube/dir}
      (str zcube/dir "/zcube-agent")
      :--node node
      :--server server
      :--state-dir (str zcube/state-dir "/agent")
      :--cpu-millis 4000
      :--memory-bytes 8589934592
      :--images (str zcube/dir "/images.zon"))))

(defn agent-log-tail
  "The last lines of the agent's log on `node`, for a diagnosis."
  [test node]
  (try (-> (c/on-nodes test [node]
                       (fn [_ _] (c/su (c/exec :tail :-n 30 zcube/agent-log))))
           (get node))
       (catch Exception e (.getMessage e))))

(defn kill-agent!
  "SIGKILL to the agent alone: its containers' supervisors are in their own
  sessions and stay up, as they would through an agent crash."
  []
  (c/su (c/exec :bash :-c (str "kill -9 $(cat " zcube/agent-pid ")"))))

(defn control-ip
  "The control host as the node sees it: the node's default gateway."
  []
  (str/trim (c/exec :bash :-c
                    "ip -4 route show default | awk '{print $3; exit}'")))

;; ---------------------------------------------------------------- the server

(defn server-base [test]
  (str "http://" (zcube/server-node test) ":" zcube/port))

(defn list-prefix
  "The kvs under `prefix` through /v1/list."
  [http test prefix]
  (let [res (api/request http (server-base test) :get
                         (str "/v1/list?prefix=" prefix "&limit=100") nil)]
    (when (= 200 (:status res)) (-> res :body :kvs))))

(defn poll!
  "Calls (f) every 200 ms until it is truthy or deadline-ms (wall clock)
  passes; returns its value or nil."
  [deadline-ms f]
  (loop []
    (let [v (try (f) (catch Exception e (warn "poll:" (.getMessage e)) nil))]
      (cond v v
            (< (hp/now-ms) deadline-ms) (do (Thread/sleep 200) (recur))
            :else nil))))

(defn svc-name [i] (str "sp" i))

(defn apply-body [test i]
  {:name (svc-name i) :image api/image :replicas 1
   :command (if (:ignore-term test)
              ["sh" "-c" "trap '' TERM; while :; do sleep 1; done"]
              ["sleep" "86400"])
   :env [["ZJ" "zj-stale-put"]]
   :limits {:cpu_millis 10 :memory_bytes 4194304}
   :applied_by "jepsen"})

;; ---------------------------------------------------------------- the store

(def log-sql
  (str "SELECT revision, idx, key, deleted, coalesce(lease, 0),"
       " coalesce(convert_from(value, 'UTF8'), '')"
       " FROM zcube.store_log WHERE key LIKE '/nodes/%' OR key LIKE '/node-status/%'"
       " OR key LIKE '/observed/%' OR key LIKE '/assigned/%'"
       " UNION ALL SELECT compacted_revision, -1, 'compacted', false, 0, ''"
       " FROM zcube.store_meta WHERE id = 1"
       " ORDER BY 1, 2"))

(defn parse-log-rows
  "psql -At rows (tab separated) as {:rows [{:rev :idx :key :deleted :lease
  :value}] :compacted n}. :value is the parsed JSON, or nil."
  [lines]
  (let [rows (for [l lines
                   :when (not (str/blank? l))
                   :let [[rev idx k del lease v] (str/split l #"\t" 6)]]
               {:rev (parse-long rev) :idx (parse-long idx) :key k
                :deleted (= "t" del) :lease (parse-long lease)
                :value (when-not (str/blank? v)
                         (try (json/parse-string v true) (catch Exception _ v)))})]
    {:rows      (vec (remove #(= "compacted" (:key %)) rows))
     :compacted (some #(when (= "compacted" (:key %)) (:rev %)) rows)}))

(defn read-store [test]
  (let [node (first (pg/pg-nodes test))
        out  (-> (c/on-nodes test [node]
                             (fn [_ _]
                               (pg/pg! :psql :-h node :-U :jepsen :-d :jepsen
                                       :-v "ON_ERROR_STOP=1" :-At :-F "\t"
                                       :-c log-sql)))
                 (get node))]
    (parse-log-rows (str/split-lines out))))

;; ---------------------------------------------------------------- nemesis

(defn step!
  "Does one step; returns the op's value."
  [test st op]
  (let [{:keys [proxy http]} @st
        n4 (held-node test) n5 (spare-node test)
        deadline (fn []
                   (if-let [g (:l1-granted @st)]
                     (+ g (:lease-ttl-ms test) -1500)
                     (throw (ex-info "L1's grant time is unknown" {}))))]
    (case (:f op)
      :arm (do (hp/arm! proxy) :armed)

      :start-agent
      (let [node (:value op)]
        (c/on-nodes test [node]
                    (fn [_ _]
                      ; An address, not the name: the agent's --server takes
                      ; host:port as an IP (zcube/start-agent! does the same).
                      (if (= node n4)
                        (start-agent! node (str (:control-ip @st) ":" (:proxy-port test)))
                        (start-agent! node (str (cn/ip (zcube/server-node test))
                                                ":" zcube/port)))))
        node)

      :await-held
      (let [h (poll! (+ (hp/now-ms) 20000) #(hp/held proxy))
            ; The proxy logs a relayed request when its answer ends, which
            ; can be after the agent already sent the PUT the proxy holds:
            ; wait for the grant's entry too.
            grant (poll! (+ (hp/now-ms) 5000)
                         (fn []
                           (->> (hp/requests proxy)
                                (filter #(and (= "POST" (:method %)) (= 200 (:status %))
                                              (re-matches #"/v1/agent/nodes/[^/?]+/lease(?:\?.*)?"
                                                          (:path %))))
                                first)))
            lease (some-> h :body (json/parse-string true) :lease)]
        (when-not h (throw (ex-info "no registration was held" {})))
        (when-not grant (throw (ex-info "no lease grant was logged" {})))
        ; L1 can have been granted no earlier than the grant request arrived,
        ; so it lives at least until that + ttl: the deadline errs early.
        (swap! st assoc :l1 lease :l1-granted (:at grant))
        {:lease lease :held-at (:at h) :granted-at (:at grant)
         :grant-answered (:end grant) :body (:body h)})

      :await-ready
      (let [node (:value op)
            ; Before the release the spare must be ready while L1 still runs;
            ; after it, there is no such bound.
            until (if (= :before (:spare-at test))
                    (deadline)
                    (+ (hp/now-ms) 20000))
            r (poll! until
                     (fn []
                       (let [kv (first (list-prefix http test (str "/nodes/" node)))
                             ns (first (list-prefix http test (str "/node-status/" node)))]
                         (when (and kv (= "ready" (-> ns :value :state)))
                           {:lease (:lease kv) :tenure (:mod_revision kv)
                            :at (hp/now-ms)}))))]
        (or r
            (do (warn "agent on" node "never registered; its log:\n"
                      (agent-log-tail test node))
                :timed-out)))

      :kill-agent (do (c/on-nodes test [(:value op)] (fn [_ _] (kill-agent!)))
                      (:value op))

      :await-registered
      (let [r (poll! (deadline)
                     (fn []
                       (let [kv (first (list-prefix http test (str "/nodes/" n4)))
                             ns (first (list-prefix http test (str "/node-status/" n4)))]
                         (when (and kv (not= (:l1 @st) (:lease kv))
                                    (= "ready" (-> ns :value :state)))
                           {:lease (:lease kv) :tenure (:mod_revision kv)
                            :at (hp/now-ms)}))))]
        (swap! st assoc :l2 (:lease r) :l2-tenure (:tenure r))
        (or r :timed-out))

      :apply
      (vec (for [i (range (:services test))]
             (let [res (api/request http (server-base test) :post "/v1/apply"
                                    (apply-body test i))]
               [(svc-name i) (:status res) (-> res :body :revision)])))

      :await-running
      (do
        (let [r (poll! (deadline)
                       (fn []
                         (let [kvs (list-prefix http test (str "/observed/" n4 "/"))
                               up  (filter #(= "running" (-> % :value :status)) kvs)]
                           (when (<= (:services test) (count up))
                             {:running (mapv :key up) :at (hp/now-ms)}))))]
          (or r :deadline)))

      :release
      (let [l1-deadline (+ (or (:l1-granted @st)
                               (throw (ex-info "L1's grant time is unknown" {})))
                           (:lease-ttl-ms test))
            r    (hp/release! proxy)
            body (try (json/parse-string (:body r) true) (catch Exception _ nil))
            ; What /nodes/n4 names right after the answer: a refusal must
            ; have left it on L2.
            kv   (try (first (list-prefix http test (str "/nodes/" n4)))
                      (catch Exception _ nil))]
        (assoc r :l1-deadline l1-deadline
               :code (when (map? body) (-> body :diagnostics first :code))
               :revision (when (map? body) (:revision body))
               :n4-after (when kv {:lease (:lease kv) :tenure (:mod_revision kv)})))

      :observe (do (Thread/sleep (* 1000 (:observe-s test))) (:observe-s test))

      :collect
      (let [procs (c/on-nodes test [n4 n5]
                              (fn [_ _] (c/exec :cat procs-log)))]
        {:store   (read-store test)
         :proxy   (hp/requests proxy)
         :procs   (into {} (for [[n out] procs] [n (str/split-lines out)]))
         :end     (hp/now-ms)}))))

(defn the-nemesis []
  (let [st (atom {})]
    (reify nemesis/Nemesis
      (setup! [this test]
        (let [ip (-> (c/on-nodes test [(held-node test)] (fn [_ _] (control-ip)))
                     first val)
              proxy (hp/start! (:proxy-port test)
                               [(zcube/server-node test) zcube/port]
                               (held-node test))]
          (swap! st assoc :proxy proxy :http (api/http-client) :control-ip ip)
          this))
      (invoke! [_ test op]
        (let [v (try (step! test st op)
                     (catch Exception e
                       (warn e "step" (:f op))
                       [:error (.getMessage e)]))]
          (assoc op :value v)))
      (teardown! [_ test]
        (some-> @st :proxy hp/stop!)))))

;; ---------------------------------------------------------------- checks

(defn stale-registrations
  "Puts of /nodes/<node> on a lease lower than an earlier put of that key."
  [rows]
  (->> rows
       (filter #(and (str/starts-with? (:key %) "/nodes/") (not (:deleted %))))
       (reduce (fn [[top out] r]
                 (let [hi (get top (:key r) 0)]
                   [(update top (:key r) (fnil max 0) (:lease r))
                    (if (< (:lease r) hi)
                      (conj out (assoc (select-keys r [:rev :key :lease])
                                       :newer-lease hi))
                      out)]))
               [{} []])
       second))

(defn node-of [k] (second (re-matches #"/[a-z-]+/([^/]+)(?:/.*)?" k)))
(defn id-of [k] (second (re-matches #"/[a-z-]+/[^/]+/(.+)" k)))

(defn replay
  "Replays the rows. Returns {:superseded-observed [...] :i1a [...]}."
  [rows]
  (loop [groups (partition-by :rev rows)
         live   {}
         sup    []
         i1a    []]
    (if-let [g (first groups)]
      (let [rev (:rev (first g))
            sup (into sup
                      (for [r g
                            :when (and (not (:deleted r))
                                       (str/starts-with? (:key r) "/observed/"))
                            :let [t (-> r :value :tenure)
                                  cur (:rev (live (str "/nodes/" (node-of (:key r)))))]
                            :when (and t (not= t cur))]
                        {:rev rev :key (:key r) :tenure t :nodes-revision cur}))
            live (reduce (fn [m r]
                           (if (:deleted r)
                             (dissoc m (:key r))
                             (assoc m (:key r) {:rev rev :value (:value r)})))
                         live g)
            assigned (group-by id-of (filter #(str/starts-with? % "/assigned/") (keys live)))
            admissible (fn [k]
                         (let [n (node-of k) id (id-of k)
                               o (:value (live k))]
                           (and (= (:tenure o) (:rev (live (str "/nodes/" n))))
                                (= (:assignment o) (:rev (live (str "/assigned/" n "/" id))))
                                (= "ready" (-> (live (str "/node-status/" n)) :value :state)))))
            adm (->> (keys live)
                     (filter #(str/starts-with? % "/observed/"))
                     (filter admissible)
                     (group-by id-of))
            bad (concat
                  (for [[id ks] assigned :when (< 1 (count ks))]
                    {:rev rev :replica id :assigned ks})
                  (for [[id ks] adm :when (< 1 (count ks))]
                    {:rev rev :replica id :admissible ks}))]
        (recur (rest groups) live sup (into i1a bad)))
      {:superseded-observed sup :i1a i1a :final live})))

(defn parse-procs
  "proc-watch lines as [[t #{ids}] ...]."
  [lines]
  (vec (for [l lines
             :let [[t & ids] (str/split (str/trim l) #"\s+")]
             :when (and t (re-matches #"\d{13}" t))]
         [(parse-long t) (set ids)])))

(defn intervals
  "{[node id] [[start end] ...]} from each node's samples. A run of
  consecutive samples naming a replica is one interval, widened to the node's
  samples on either side of the run: the replica may have started just after
  the sample before and stopped just before the sample after. A gap between
  samples (the watcher stalled) is unknown, not absent: it never splits a
  run, and a run next to it is widened across it."
  [procs]
  (into {}
        (for [[node lines] procs
              :let [samples (vec (sort-by first (parse-procs lines)))
                    n (count samples)
                    t (fn [i] (first (samples i)))]
              id (distinct (mapcat second samples))]
          [[node id]
           (loop [i 0 out [] start nil]
             (if (= i n)
               (if start (conj out [start (t (dec n))]) out)
               (let [in? (contains? (second (samples i)) id)]
                 (cond
                   (and in? (nil? start)) (recur (inc i) out (t (max 0 (dec i))))
                   (and (not in?) start)  (recur (inc i) (conj out [start (t i)]) nil)
                   :else                  (recur (inc i) out start)))))])))

(defn service-of-id
  "A replica id's service: <service>-<n>."
  [id]
  (second (re-matches #"(.+)-\d+" id)))

(defn overlap
  "Per service, the longest time two different [node replica] ran at once.
  `service-of` maps a replica id to its service; an id it does not know is
  mapped by its name (<service>-<n>)."
  [procs service-of]
  (let [iv (intervals procs)
        by-svc (group-by (fn [[[_ id] _]] (or (service-of id) (service-of-id id))) iv)
        per (into {}
                  (for [[svc xs] by-svc :when svc]
                    [svc (reduce max 0
                                 (for [[a as] xs [b bs] xs :when (pos? (compare a b))
                                       [s1 e1] as [s2 e2] bs]
                                   (max 0 (- (min e1 e2) (max s1 s2)))))]))]
    {:per-service per
     :max-ms (reduce max 0 (vals per))
     :intervals (into (sorted-map) iv)}))

(defn service-map
  "replica id -> service, from the /assigned puts."
  [rows]
  (into {} (for [r rows
                 :when (and (str/starts-with? (:key r) "/assigned/") (not (:deleted r)))]
             [(id-of (:key r)) (-> r :value :service)])))

(defn missing-procs
  "The nodes whose process records hold no sample although they must: every
  agent node, and every node a replica was assigned to."
  [procs rows agent-nodes]
  (let [assigned (keep #(when (str/starts-with? (:key %) "/assigned/") (node-of (:key %)))
                       rows)]
    (vec (sort (for [n (distinct (concat agent-nodes assigned))
                     :when (empty? (parse-procs (get procs n)))]
                 n)))))

(defn registration-times
  "[[t lease-or-nil] ...] for `node`: when /nodes/<node> came to name each
  lease (nil: deleted), in revision order. A put's time is the at_ms of the
  /node-status/<node> the server wrote for that tenure; failing that, the
  answer of the matching registration PUT in the proxy log (the k-th put
  answered 200 is the k-th put in the store). A delete takes the time of the
  next node-status row after it."
  [rows proxy-log node]
  (let [nk (str "/nodes/" node) sk (str "/node-status/" node)
        status (filter #(and (= sk (:key %)) (not (:deleted %))) rows)
        at-tenure (into {} (for [r (reverse status)
                                 :let [ten (-> r :value :tenure)]
                                 :when ten]
                             [ten (-> r :value :at_ms)]))
        puts (filter #(and (= nk (:key %)) (not (:deleted %))) rows)
        ok-puts (->> proxy-log
                     (filter #(and (= "PUT" (:method %)) (= 200 (:status %))
                                   (= (str "/v1/agent/nodes/" node)
                                      (first (str/split (str (:path %)) #"\?")))))
                     (sort-by :end)
                     (mapv :end))
        put-idx (into {} (map-indexed (fn [i r] [(:rev r) i]) puts))]
    (vec (for [r rows
               :when (= nk (:key r))
               :let [t (if (:deleted r)
                         (some #(when (> (:rev %) (:rev r)) (-> % :value :at_ms)) status)
                         (or (at-tenure (:rev r))
                             (get ok-puts (put-idx (:rev r)))))]
               :when t]
           [t (when-not (:deleted r) (:lease r))]))))

(defn orphan-keepalives
  "Keepalives of `node`'s leases answered 200 after the release while
  /nodes/<node>, as replayed from the store at the time of the answer, named
  another lease (or none): renewals the server granted although the lease no
  longer held the node."
  [proxy-log rows release node]
  (let [times (sort-by first (registration-times rows proxy-log node))
        named (fn [t] (some-> (last (take-while #(<= (first %) t) times)) second))]
    (vec (for [r proxy-log
               :let [[_ lease] (re-matches #"/v1/agent/nodes/[^/]+/lease/(\d+)/keepalive(?:\?.*)?"
                                           (str (:path r)))
                     lease (some-> lease parse-long)]
               :when (and lease (= 200 (:status r)) (:end r)
                          (> (:end r) (:answered release))
                          (not= lease (named (:end r))))]
           (assoc (select-keys r [:at :end :path]) :named (named (:end r)))))))

(def refusal-codes
  "Codes a fixed server may refuse the stale registration with: a failed
  compare (ZC-E0105) or the tenure compare's refusal (ZC-E0510). Any other
  code counts only if /nodes/n4 was still on L2 right after the answer."
  #{"ZC-E0105" "ZC-E0510"})

(defn release-revision
  "The revision the release's answer names (older histories have it only in
  the body)."
  [rel]
  (or (:revision rel)
      (try (:revision (json/parse-string (:body rel) true)) (catch Exception _ nil))))

(def failed-wait? #{:timed-out :deadline})

(defn failed-step?
  "A step value that means the schedule did not happen as planned."
  [v]
  (or (failed-wait? v) (and (vector? v) (= :error (first v)))))

(defn analyse
  "Everything the checker judges, from the history's step values."
  [history n4 agent-nodes]
  (let [infos (filter #(and (= :info (:type %)) (= :nemesis (:process % :nemesis))
                            (some? (:value %)))
                      history)
        ; Each step's completion comes after its invocation, whose value is
        ; nil or the step's node.
        v (fn [f] (->> infos (filter #(= f (:f %))) (map :value) last))
        held (v :await-held)
        rel  (v :release)
        col  (v :collect)
        col-ok (map? col)
        rows (if col-ok (-> col :store :rows) [])
        {:keys [superseded-observed i1a final]} (replay rows)
        stale (stale-registrations rows)
        ov   (overlap (when col-ok (:procs col)) (service-map rows))
        status (some-> (get final (str "/node-status/" n4)) :value)
        reg (v :await-registered)]
    {:held held
     :registered reg
     :running (v :await-running)
     :spare-ready (v :await-ready)
     :failed-steps (vec (for [o infos :when (failed-step? (:value o))]
                          [(:f o) (:value o)]))
     :collected col-ok
     :release rel
     :compacted (when col-ok (-> col :store :compacted))
     :first-rev (:rev (first rows))
     :l2-registered (boolean (and (map? reg)
                                  (some #(and (= (str "/nodes/" n4) (:key %)) (not (:deleted %))
                                              (= (:lease reg) (:lease %)) (= (:tenure reg) (:rev %)))
                                        rows)))
     :procs-missing (if col-ok (missing-procs (:procs col) rows agent-nodes) (vec agent-nodes))
     :stale-registrations stale
     :superseded-observed superseded-observed
     :i1a i1a
     :overlap (dissoc ov :intervals)
     :intervals (:intervals ov)
     :orphan-keepalives (count (when (and (seq stale) (map? rel) col-ok)
                                 (orphan-keepalives (:proxy col) rows rel n4)))
     :n4-final {:registered (some? (get final (str "/nodes/" n4)))
                :lease (:lease (first (filter #(= (str "/nodes/" n4) (:key %))
                                              (reverse rows))))
                :status (:state status)
                :status-at (:at_ms status)
                :lost-for-ms (when (and col-ok (:at_ms status) (not= "ready" (:state status)))
                               (- (:end col) (:at_ms status)))}
     :rows rows
     :proxy (when col-ok (:proxy col))}))

(defn judge
  "{:valid? :outcome ...} from analyse's map and the expectation."
  [a expect]
  (let [rel     (:release a)
        reg     (:registered a)
        landed  (and (map? rel) (= 200 (:status rel)))
        ; Refused while L1 still ran, by a 4xx that says why, or that at least
        ; left /nodes/n4 where L2's registration put it.
        refused (boolean
                  (and (map? rel) (integer? (:status rel)) (<= 400 (:status rel) 499)
                       (integer? (:answered rel)) (integer? (:l1-deadline rel))
                       (< (:answered rel) (:l1-deadline rel))
                       (not= "ZC-E0205" (:code rel))
                       (or (contains? refusal-codes (:code rel))
                           (and (map? reg) (map? (:n4-after rel))
                                (= (:lease reg) (:lease (:n4-after rel)))
                                (= (:tenure reg) (:tenure (:n4-after rel)))))))
        ; The stale row is the one the release itself committed.
        bug?    (boolean
                  (and landed
                       (some #(and (= (release-revision rel) (:rev %))
                                   (= (-> a :held :lease) (:lease %)))
                             (:stale-registrations a))))
        ; What a :fixed verdict stands on: a whole store history from rev 1,
        ; L2's registration in it, process records from every node that needs
        ; them.
        complete? (and (:collected a)
                       (= 1 (:first-rev a))
                       (= 0 (:compacted a))
                       (:l2-registered a)
                       (empty? (:procs-missing a)))
        clean?  (and complete?
                     (empty? (:stale-registrations a))
                     (empty? (:superseded-observed a))
                     (empty? (:i1a a))
                     (<= (-> a :overlap :max-ms) window-ms))
        failed  (seq (:failed-steps a))
        expect  (cond failed :vacuous
                      (= :auto expect) (cond landed :bug refused :valid :else :vacuous)
                      :else expect)
        outcome (case expect
                  :bug     (if bug? :expected-failure-237 (if landed :landed-without-stale :not-landed))
                  :valid   (cond (and refused clean?) :fixed
                                 (and refused (not complete?)) :refused-but-incomplete
                                 refused :refused-but-unsafe
                                 :else :not-refused)
                  :vacuous :vacuous)]
    {:valid?  (boolean (case expect :bug bug? :valid (and refused clean?) :vacuous false))
     :expect  expect
     :outcome outcome
     :release-landed landed
     :release-refused refused
     :complete complete?
     :window-ms window-ms}))

(defn checker [expect]
  (reify checker/Checker
    (check [_ test history opts]
      (let [a (analyse history (held-node test) (zcube/agent-nodes test))
            j (judge a expect)
            summary (merge j (dissoc a :rows :proxy :intervals))]
        ; The reproducing history: every step, the store rows, the proxy's
        ; requests and each replica's run intervals.
        (with-open [w (io/writer (store/path! test "stale-put.edn"))]
          (binding [*out* w]
            (pprint (assoc summary
                           :steps (vec (for [o history :when (= :nemesis (:process o))
                                             :when (not= :collect (:f o))]
                                         (select-keys o [:time :type :f :value])))
                           :intervals (:intervals a)
                           :store-rows (:rows a)
                           :proxy (:proxy a)))))
        summary))))

;; ---------------------------------------------------------------- test

(defn steps
  "running: the release comes once n4 reports its replicas running.
  registered: it comes right after n4 registered on L2, before the apply."
  [test]
  (let [n4 (held-node test) n5 (spare-node test)
        release (if (= :before (:spare-at test))
                  [[:start-agent n5] [:await-ready n5] [:release]]
                  [[:release] [:start-agent n5] [:await-ready n5]])]
    (->> (concat [[:arm] [:start-agent n4] [:await-held] [:kill-agent n4]
                  [:start-agent n4] [:await-registered]]
                 (if (= :registered (:release-after test))
                   (concat release [[:apply]])
                   (concat [[:apply] [:await-running]] release))
                 [[:observe] [:collect]])
         (mapv (fn [[f v]] {:type :info :f f :value v})))))

(defn stale-put-test [opts]
  (merge tests/noop-test
         opts
         {:name      (str "zcube-stale-put-" (name (:release-after opts)))
          :os        os/noop
          :db        (db)
          :nemesis   (the-nemesis)
          :checker   (checker (:expect opts))
          :generator (gen/nemesis (steps opts))}))

(def cli-opts
  [[nil "--zcube-bin DIR" "Directory holding zcube-server and zcube-agent."
    :default "zigcube/zig-out/bin"]
   [nil "--zcube-images FILE" "The fake runtime's image list."
    :default "zigcube/fixtures/images.zon"]
   [nil "--lease-ttl-ms N" "zcube-server's --lease-ttl-ms."
    :default 10000 :parse-fn parse-long]
   [nil "--replication MODE" "The Postgres pair: sync (the supported setup)."
    :default :sync :parse-fn keyword]
   [nil "--proxy-port N" "Where the hold proxy listens on the control host."
    :default 17070 :parse-fn parse-long]
   [nil "--services N" "Services of one replica each placed on n4."
    :default 3 :parse-fn parse-long]
   [nil "--release-after WHEN"
    (str "running: deliver the held PUT once n4 reports its replicas running"
         " (#237 case a). registered: right after n4 registered again, before"
         " any work (case b).")
    :default :running :parse-fn keyword
    :validate [#{:running :registered} "must be running or registered"]]
   [nil "--spare-at WHEN"
    (str "before: start n5's agent just before the release, so a replacement"
         " has somewhere to go at once. after: just after it.")
    :default :before :parse-fn keyword
    :validate [#{:before :after} "must be before or after"]]
   [nil "--[no-]ignore-term" "The replicas' command ignores SIGTERM, so a stop takes stop_grace."
    :default true]
   [nil "--observe-s N" "Seconds to watch after the release."
    :default 30 :parse-fn parse-long]
   [nil "--expect OUTCOME" "auto, bug (#237 must show) or valid (must be fixed)."
    :default :auto :parse-fn keyword
    :validate [#{:auto :bug :valid} "must be auto, bug or valid"]]])

(defn -main [& args]
  (cli/run! (merge (cli/single-test-cmd {:test-fn  stale-put-test
                                         :opt-spec cli-opts})
                   (cli/serve-cmd))
            args))
