(ns zcube-jepsen.stale-put
  "Slice 5: a registration PUT that lands after a newer one (Zigcube #237).

  The agent on n4 reaches zcube-server through a proxy in the control process
  (zcube-jepsen.hold-proxy). The proxy holds the agent's first registration,
  `PUT /v1/agent/nodes/n4` on lease L1, and never answers it. The agent is
  then killed (SIGKILL) and started again, as an agent restart would: it is
  granted L2 and registers on it. Work is placed on n4 and starts. The proxy
  then delivers the held PUT, while L1 is still live. The server applies a
  registration with no compare, so on an affected server /nodes/n4 moves back
  to L1 at a new revision: n4's tenure changes under the agent, which still
  renews L2, and when L1 ends the node is taken for lost while its containers
  are still up. n5 starts afterwards and is where the replacements go.

  Steps, each an op in the history (the nemesis does them in order):
    :arm :start-agent :await-held :kill-agent :start-agent :await-registered
    :apply :await-running :release :start-agent(n5) :observe :collect

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
                         replica on two nodes) ran at once, against the I1b
                         window of 1.9 s. Slice 2 builds the general overlap
                         checker; this is the smallest one this slice needs.
  and from the proxy's log:
    orphan-keepalives    renewals of the agent's lease, answered 200, after
                         /nodes/n4 had stopped naming it.
  Liveness is reported, not judged: n4's final registration and status.

  --expect decides what passes:
    bug    the release must be answered 200 and a stale registration must be
           in the store (the expected failure while #237 is open).
    valid  everything must hold, and the release must have been refused while
           L1 was still live (a fixed server).
    auto   bug if the release was answered 200, valid if it was refused with
           anything but ZC-E0205 (lease ended), else the run is vacuous and
           invalid."
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
              (c/upload (str (io/resource "proc-watch.sh")) watcher)
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
        deadline (fn [] (+ (:l1-granted @st) (:lease-ttl-ms test) -1500))]
    (case (:f op)
      :arm (do (hp/arm! proxy) :armed)

      :start-agent
      (let [node (:value op)]
        (c/on-nodes test [node]
                    (fn [_ _]
                      (if (= node n4)
                        (start-agent! node (str (:control-ip @st) ":" (:proxy-port test)))
                        (start-agent! node (str (zcube/server-node test) ":" zcube/port)))))
        node)

      :await-held
      (let [h (poll! (+ (hp/now-ms) 20000) #(hp/held proxy))
            grant (->> (hp/requests proxy)
                       (filter #(and (= "POST" (:method %))
                                     (re-matches #"/v1/agent/nodes/[^/]+/lease" (:path %))))
                       first)
            lease (some-> h :body (json/parse-string true) :lease)]
        (when-not h (throw (ex-info "no registration was held" {})))
        (swap! st assoc :l1 lease :l1-granted (:end grant))
        {:lease lease :held-at (:at h) :granted-at (:end grant) :body (:body h)})

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
        (swap! st assoc :l2 (:lease r))
        (or r :timed-out))

      :apply
      (vec (for [i (range (:services test))]
             (let [res (api/request http (server-base test) :post "/v1/apply"
                                    (apply-body test i))]
               [(svc-name i) (:status res) (-> res :body :revision)])))

      :await-running
      (if (= :registered (:release-after test))
        :skipped
        (let [r (poll! (deadline)
                       (fn []
                         (let [kvs (list-prefix http test (str "/observed/" n4 "/"))
                               up  (filter #(= "running" (-> % :value :status)) kvs)]
                           (when (<= (:services test) (count up))
                             {:running (mapv :key up) :at (hp/now-ms)}))))]
          (or r :deadline)))

      :release
      (let [r (hp/release! proxy)]
        (assoc r :l1-deadline (+ (:l1-granted @st) (:lease-ttl-ms test))
               :code (some-> (:body r) (json/parse-string true)
                             :diagnostics first :code)))

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
  "{[node id] [[start end] ...]} from each node's samples: a replica's
  presence in consecutive samples (gap < 1 s) is one interval."
  [procs]
  (into {}
        (for [[node lines] procs
              :let [samples (parse-procs lines)]
              id (distinct (mapcat second samples))]
          [[node id]
           (->> samples
                (filter #(contains? (second %) id))
                (map first)
                (reduce (fn [acc t]
                          (let [[s e] (peek acc)]
                            (if (and e (< (- t e) 1000))
                              (conj (pop acc) [s t])
                              (conj acc [t t]))))
                        []))])))

(defn overlap
  "Per service, the longest time two different [node replica] ran at once.
  `service-of` maps a replica id to its service."
  [procs service-of]
  (let [iv (intervals procs)
        by-svc (group-by (fn [[[_ id] _]] (service-of id)) iv)
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

(defn orphan-keepalives
  "Keepalives answered 200 after the release on a lease other than the one
  the stale registration put back."
  [proxy-log release l1]
  (vec (for [r proxy-log
             :let [[_ lease] (re-matches #"/v1/agent/nodes/[^/]+/lease/(\d+)/keepalive" (str (:path r)))]
             :when (and lease (= 200 (:status r)) (> (:at r) (:answered release))
                        (not= l1 (parse-long lease)))]
         (select-keys r [:at :path]))))

(defn analyse
  "Everything the checker judges, from the history's step values."
  [history n4]
  (let [v (fn [f] (->> history (filter #(and (= :info (:type %)) (= f (:f %))
                                             (some? (:value %))))
                       (map :value) last))
        held (v :await-held)
        rel  (v :release)
        col  (v :collect)
        rows (-> col :store :rows)
        {:keys [superseded-observed i1a final]} (replay rows)
        stale (stale-registrations rows)
        ov   (overlap (:procs col) (service-map rows))
        status (some-> (final (str "/node-status/" n4)) :value)]
    {:held held
     :registered (v :await-registered)
     :running (v :await-running)
     :release rel
     :compacted (-> col :store :compacted)
     :first-rev (:rev (first rows))
     :stale-registrations stale
     :superseded-observed superseded-observed
     :i1a i1a
     :overlap (dissoc ov :intervals)
     :intervals (:intervals ov)
     :orphan-keepalives (count (when (and (seq stale) (map? rel))
                                 (orphan-keepalives (:proxy col) rel (:lease held))))
     :n4-final {:registered (some? (final (str "/nodes/" n4)))
                :lease (:lease (first (filter #(= (str "/nodes/" n4) (:key %))
                                              (reverse rows))))
                :status (:state status)
                :status-at (:at_ms status)
                :lost-for-ms (when (and (:at_ms status) (not= "ready" (:state status)))
                               (- (:end col) (:at_ms status)))}
     :rows rows
     :proxy (:proxy col)}))

(defn judge
  "{:valid? :outcome ...} from analyse's map and the expectation."
  [a expect]
  (let [rel     (:release a)
        landed  (and (map? rel) (= 200 (:status rel)))
        refused (and (map? rel) (integer? (:status rel)) (<= 400 (:status rel) 499)
                     (not= "ZC-E0205" (:code rel))
                     (< (:answered rel) (:l1-deadline rel)))
        bug?    (and landed (seq (:stale-registrations a)))
        clean?  (and (empty? (:stale-registrations a))
                     (empty? (:superseded-observed a))
                     (empty? (:i1a a))
                     (<= (-> a :overlap :max-ms) window-ms))
        expect  (if (= :auto expect)
                  (cond landed :bug refused :valid :else :vacuous)
                  expect)
        outcome (case expect
                  :bug     (if bug? :expected-failure-237 (if landed :landed-without-stale :not-landed))
                  :valid   (if (and refused clean?) :fixed (if refused :refused-but-unsafe :not-refused))
                  :vacuous :vacuous)]
    {:valid?  (boolean (case expect :bug bug? :valid (and refused clean?) :vacuous false))
     :expect  expect
     :outcome outcome
     :release-landed landed
     :release-refused refused
     :window-ms window-ms}))

(defn checker [expect]
  (reify checker/Checker
    (check [_ test history opts]
      (let [a (analyse history (held-node test))
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

(defn steps [test]
  (let [n4 (held-node test) n5 (spare-node test)]
    (->> [[:arm] [:start-agent n4] [:await-held] [:kill-agent n4]
          [:start-agent n4] [:await-registered] [:apply] [:await-running]
          [:release] [:start-agent n5] [:observe] [:collect]]
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
