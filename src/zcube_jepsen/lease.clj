(ns zcube-jepsen.lease
  "Slice 2: leases and fencing.

  Services of one replica each, placed by zcube-server on the agents (n4, n5,
  FakeRuntime). Every replica runs the recorder below, so each instance writes
  down its own life on the shared host clock: when it started, a heartbeat
  every 50 ms, and the TERM it got (it ignores TERM, so the agent's KILL is
  what ends it: the fence's worst case). The FakeRuntime's own state has no
  stop time (run.json says how a command ended, not when), hence the
  recorder.

  The faults are an agent cut off from the server and the server cut off from
  Postgres, each for longer than the lease, and, for the negative control, an
  agent stopped with SIGSTOP (the wedged node of docs/authority.md, which must
  break I1b).

  A watch on /assigned/ and /replicas/ runs for the whole test, from revision
  1, so that I1a can be checked at every revision (zcube-jepsen.lease-checkers).
  The :collect op at the end gathers the watch and every node's records into
  the history."
  (:require [cheshire.core]
            [clojure.string :as str]
            [clojure.tools.logging :refer [info warn]]
            [jepsen [client :as client]
                    [control :as c]
                    [nemesis :as nemesis]
                    [net :as net]
                    [util :as util]]
            [zcube-jepsen [api :as api]
                          [lease-checkers :as lc]
                          [zcube :as zcube]])
  (:import (java.net URI)
           (java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers)
           (java.time Duration)))

(def recorder-path (str zcube/dir "/zj-rec.sh"))
(def records-dir (str zcube/state-dir "/rec"))
(def heartbeat-ms lc/heartbeat-ms*)

(def recorder
  "The command of every replica. Its parent is the FakeRuntime supervisor,
  `zcube-agent supervise <state>/containers/<replica id>`, so the replica id
  is the last word of the parent's command line. One file per instance
  (replica id and pid), lines:
    S <epoch s.us> <service> <replica> <pid>   once, at start
    H <epoch s.us>                             every heartbeat
    T <epoch s.us>                             at each TERM (ignored)
  The sleep is `read -t` on a FIFO held open by the script itself: a builtin,
  so no fork per heartbeat and a TERM is noted at once."
  (str "#!/bin/bash\n"
       "d=" records-dir "\n"
       "mkdir -p \"$d\"\n"
       "id=$(tr '\\0' '\\n' < /proc/$PPID/cmdline | tail -n 1)\n"
       "id=${id##*/}\n"
       "f=\"$d/$id.$$.log\"\n"
       "p=\"$d/.fifo.$$\"\n"
       "mkfifo \"$p\" && exec 3<>\"$p\" && rm -f \"$p\"\n"
       "echo \"S $EPOCHREALTIME ${ZJ_SVC:-?} $id $$\" >> \"$f\"\n"
       "trap 'echo \"T $EPOCHREALTIME\" >> \"$f\"' TERM\n"
       "while :; do\n"
       "  echo \"H $EPOCHREALTIME\" >> \"$f\"\n"
       "  read -t " (format "%.3f" (/ heartbeat-ms 1000.0)) " -u 3 _\n"
       "done\n"))

(def summarize
  "One line per instance file: svc replica pid start last-heartbeat term."
  (str "for f in " records-dir "/*.log; do [ -e \"$f\" ] || continue; "
       "awk 'NR==1{svc=$3; id=$4; pid=$5; start=$2} "
       "$1==\"H\"{last=$2} $1==\"T\" && term==\"\"{term=$2} "
       "END{if (start!=\"\") print svc, id, pid, start, "
       "(last==\"\" ? \"-\" : last), (term==\"\" ? \"-\" : term)}' \"$f\"; done"))

(defn svc-name [k] (str "l" k "x"))

;; ---------------------------------------------------------------- db

(defn install-recorder! []
  (c/su (c/exec :mkdir :-p records-dir)
        (c/exec :bash :-c (str "cat > " recorder-path " <<'ZJEOF'\n" recorder "ZJEOF\n"
                               "chmod +x " recorder-path))))

(defn kill-recorders! []
  (c/su (c/exec :bash :-c "pkill -9 -f '[z]j-rec.sh'; true")))

(defn db
  "zcube/db, plus the recorder on the agents, and no recorder left over."
  []
  (let [inner (zcube/db)]
    (reify jepsen.db/DB
      (setup! [_ test node]
        (when (some #{node} (zcube/agent-nodes test))
          (kill-recorders!)
          (c/su (c/exec :mkdir :-p zcube/dir))
          (install-recorder!))
        (jepsen.db/setup! inner test node))
      (teardown! [_ test node]
        (when (some #{node} (zcube/agent-nodes test))
          (c/su (c/exec :bash :-c "pkill -CONT -x zcube-agent; true"))
          (kill-recorders!))
        (jepsen.db/teardown! inner test node))
      jepsen.db/LogFiles
      (log-files [_ test node] (jepsen.db/log-files inner test node)))))

;; ---------------------------------------------------------------- client

(defrecord Client [http base]
  client/Client
  (open! [this test node]
    (assoc this :http (api/http-client)
           :base (str "http://" (zcube/server-node test) ":" zcube/port)))
  (setup! [this test])
  (invoke! [this test op]
    (try
      (case (:f op)
        :apply
        (let [name (svc-name (:value op))
              res  (api/request http base :post "/v1/apply"
                                {:name name :image api/image :replicas 1
                                 :command ["/bin/bash" recorder-path]
                                 :env [["ZJ_SVC" name]]
                                 :limits {:cpu_millis 10 :memory_bytes 4194304}
                                 :applied_by "jepsen"})]
          (if (= 200 (:status res))
            (assoc op :type :ok :version (-> res :body :revision))
            (assoc op :type :info :error [(:status res) (api/code res)])))

        :read
        (let [res (api/request http base :get "/v1/list?prefix=/assigned/&limit=500" nil)]
          (if (= 200 (:status res))
            (assoc op :type :ok
                   :value (->> res :body :kvs
                               (keep (comp lc/assigned-key :key))
                               (group-by first)
                               (map (fn [[n rs]] [n (count rs)]))
                               (into (sorted-map))))
            (assoc op :type :fail :error [(:status res) (api/code res)]))))
      (catch Exception e
        (assoc op :type (if (= :read (:f op)) :fail :info)
               :error [:http (.getName (class e)) (.getMessage e)]))))
  (teardown! [this test])
  (close! [this test]))

(defn client [] (Client. nil nil))

;; ---------------------------------------------------------------- watch

(defn- get-json [^HttpClient http url timeout-s]
  (let [req (-> (HttpRequest/newBuilder (URI. url))
                (.timeout (Duration/ofSeconds timeout-s))
                (.GET) (.build))
        res (.send http req (HttpResponse$BodyHandlers/ofString))]
    {:status (.statusCode res)
     :body   (try (cheshire.core/parse-string (.body res) true)
                  (catch Exception _ nil))}))

(defn start-watch!
  "Follows /assigned/ and /replicas/ from revision 1 in a thread of its own.
  Returns the watch's state, an atom of {:events [...] :from n :gaps [...]
  :errors n :running? bool}."
  [test]
  (let [http  (api/http-client)
        base  (str "http://" (zcube/server-node test) ":" zcube/port)
        st    (atom {:events [] :from 1 :gaps [] :errors 0 :running? true
                     :last-error nil})
        url   (fn [from] (str base "/v1/watch?prefix=/assigned/&prefix=/replicas/"
                              "&from=" from "&timeout=5000"))]
    (future
      (while (:running? @st)
        (let [from (:from @st)]
          (try
            (let [{:keys [status body]} (get-json http (url from) 20)]
              (case (int status)
                200 (swap! st (fn [s]
                                (-> s
                                    (update :events into
                                            (for [e (:events body)]
                                              {:rev  (-> e :kv :mod_revision)
                                               :type (keyword (:type e))
                                               :key  (-> e :kv :key)}))
                                    (assoc :from (inc (:revision body))))))
                410 (let [r (:body (get-json http (str base "/v1/status") 10))]
                      (warn "watch compacted at" from)
                      (swap! st #(-> %
                                     (update :gaps conj {:from from
                                                         :resumed (inc (:revision r))})
                                     (assoc :from (inc (:revision r))))))
                (do (swap! st #(-> % (update :errors inc)
                                   (assoc :last-error [status (api/code {:body body})])))
                    (Thread/sleep 500))))
            (catch Throwable e
              (swap! st #(-> % (update :errors inc)
                             (assoc :last-error (str e))))
              (Thread/sleep 500))))))
    st))

(defn finish-watch!
  "Lets the watch catch up with the store's head, then stops it."
  [test st]
  (let [http (api/http-client)
        base (str "http://" (zcube/server-node test) ":" zcube/port)
        head (util/await-fn
               (fn [] (let [r (get-json http (str base "/v1/status") 10)]
                        (or (some-> r :body :revision)
                            (throw (ex-info "no status" r)))))
               {:retry-interval 1000 :timeout 60000})]
    (util/await-fn
      (fn [] (when (<= (:from @st) head) (throw (ex-info "behind" {})))
        true)
      {:retry-interval 500 :timeout 60000 :log-message "watch catching up"})
    (swap! st assoc :running? false)
    (assoc @st :head head)))

;; ---------------------------------------------------------------- nemesis

(defn on-agents [test nodes f]
  (c/on-nodes test nodes (fn [_ _] (c/su (f)))))

(defn records
  "Every instance every agent node recorded."
  [test]
  (->> (c/on-nodes test (zcube/agent-nodes test)
                   (fn [_ node] (c/su (c/exec :bash :-c summarize))))
       (mapcat (fn [[node out]]
                 (keep #(lc/parse-record-line node %) (str/split-lines out))))
       (sort-by :start)
       vec))

(defn lease-nemesis
  "Handles
    :isolate-agent  cut one agent (value, or a random one) off from the server
    :isolate-store  cut the server off from both Postgres nodes
    :heal           heal every partition
    :wedge          SIGSTOP one agent (value, or a random one): the wedged node
    :unwedge        SIGCONT every agent
    :collect        finish the watch and gather every agent's records"
  []
  (let [watch (atom nil)]
    (reify nemesis/Nemesis
      (setup! [this test]
        (net/heal! (:net test) test)
        (reset! watch (start-watch! test))
        this)

      (invoke! [_ test op]
        (let [server (zcube/server-node test)
              pick   (fn [] (or (:value op) (rand-nth (zcube/agent-nodes test))))]
          (case (:f op)
            :isolate-agent
            (let [n (pick)]
              (net/drop-all! test {server #{n}, n #{server}})
              (assoc op :value n))

            :isolate-store
            (let [pgs (take 2 (:nodes test))]
              (net/drop-all! test (into {server (set pgs)}
                                        (for [p pgs] [p #{server}])))
              (assoc op :value [server :from pgs]))

            :heal
            (do (net/heal! (:net test) test)
                (assoc op :value :healed))

            :wedge
            (let [n (pick)]
              (on-agents test [n] #(c/exec :pkill :-STOP :-x "zcube-agent"))
              (assoc op :value n))

            :unwedge
            (do (on-agents test (zcube/agent-nodes test)
                           #(c/exec :bash :-c "pkill -CONT -x zcube-agent; true"))
                (assoc op :value :all))

            :collect
            (let [w     (finish-watch! test @watch)
                  insts (records test)]
              (info "watch:" (count (:events w)) "events up to revision" (:head w)
                    "gaps" (:gaps w) "errors" (:errors w))
              (info "recorded instances:" (count insts))
              (assoc op :value {:events    (:events w)
                                :gaps      (:gaps w)
                                :head      (:head w)
                                :watch-errors (:errors w)
                                :instances insts})))))

      (teardown! [_ test]
        (some-> @watch (swap! assoc :running? false))
        (net/heal! (:net test) test)))))
