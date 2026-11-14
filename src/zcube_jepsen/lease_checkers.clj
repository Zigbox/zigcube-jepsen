(ns zcube-jepsen.lease-checkers
  "Checkers of the lease workload (slice 2), as pure functions of what the run
  collected, so that they can be tested without a cluster.

    I1a       at every revision of the store, each replica id has at most one
              assignment (`/assigned/<node>/<replica>`): a replay of the watch.
    I1b       a replaced replica's old instance and its replacement do not run
              at the same time for longer than lost_grace_ms + kill latency:
              measured from the instances' own records, and reported as the
              measured maximum, not only pass or fail.
    fencing   fence_margin >= fence_stop + ttl x skew + kill latency, with the
              constants of the Zigcube that ran (src/constants.zig) and the
              ttl and lost grace the server was started with.

  docs/authority.md section 3 and 7 (Zigbox/zigcube) state all three."
  (:require [clojure.string :as str]))

;; The recorder's heartbeat interval (zcube-jepsen.lease): what a measured
;; overlap may hide.
(def heartbeat-ms* 50)

;; ---------------------------------------------------------------- fencing

(def constant-names
  ["lease_ttl_ms" "fence_stop_ms" "clock_skew_per_mille" "fence_margin_ms"
   "fence_kill_latency_ms" "fence_window_min_ms" "lost_grace_ms"
   "fence_poll_ms" "stop_grace_ms"])

(defn parse-constants
  "The integer constants of src/constants.zig that the inequality uses, as a
  map of keyword to long: every `pub const <name>: <type> = <integer>;`."
  [text]
  (into {}
        (for [[_ name v] (re-seq #"(?m)^pub const ([a-z_]+): u\d+ = ([0-9_]+);"
                                 text)
              :when (some #{name} constant-names)]
          [(keyword (str/replace name "_" "-"))
           (parse-long (str/replace v "_" ""))])))

(defn skew-ms
  "fencing.zig: the difference of two hosts' clocks over ttl, rounded up."
  [c ttl]
  (quot (+ (* ttl (:clock-skew-per-mille c)) 999) 1000))

(defn fencing
  "The inequality of src/fencing.zig for the ttl and lost grace that ran, and
  I1b's bound (lost grace + kill latency). :valid? false if a constant is
  missing or the inequality does not hold (the server would have refused the
  ttl with ZC-E0121, so a run that got this far and fails it means the
  harness read other constants than the binaries were built with)."
  [c ttl grace]
  (let [need   [:fence-stop-ms :clock-skew-per-mille :fence-margin-ms
                :fence-kill-latency-ms :fence-window-min-ms]
        absent (remove c need)]
    (if (seq absent)
      {:valid? false :error [:constants-missing (vec absent)] :constants c}
      (let [skew   (skew-ms c ttl)
            needed (+ (:fence-stop-ms c) skew (:fence-kill-latency-ms c))
            margin (:fence-margin-ms c)
            window (- ttl margin)]
        {:valid?          (and (>= margin needed)
                               (>= window (:fence-window-min-ms c)))
         :ttl-ms          ttl
         :lost-grace-ms   grace
         :fence-margin-ms margin
         :margin-needed-ms needed
         :slack-ms        (- margin needed)
         :terms           {:fence-stop-ms (:fence-stop-ms c)
                           :skew-ms skew
                           :fence-kill-latency-ms (:fence-kill-latency-ms c)}
         :fence-at-ms     window
         :i1b-bound-ms    (+ grace (:fence-kill-latency-ms c))
         :constants       c}))))

;; ---------------------------------------------------------------- I1a

(defn assigned-key
  "[node replica] of an /assigned/<node>/<replica> key, or nil."
  [k]
  (when-let [[_ node r] (re-matches #"/assigned/([^/]+)/([^/]+)" k)]
    [node r]))

(defn i1a
  "Replays watch events, grouped by revision (a revision's events are never
  split across replies), and checks after each revision that no replica id
  has two assignments. `events` are {:rev :type (:put|:delete) :key} in watch
  order; `gaps` are the places the watch could not cover (a 410 or a resume
  that skipped revisions), which make the result :unknown, not true.

  Also lists the replicas that were ever assigned to more than one node, one
  after the other (a move: authority.md allows it, so it is information, not
  a violation)."
  [events gaps]
  (let [by-rev (partition-by :rev events)
        step   (fn [{:keys [owners] :as st} evs]
                 (let [owners' (reduce
                                 (fn [o {:keys [type key]}]
                                   (if-let [[node r] (assigned-key key)]
                                     (if (= :put type)
                                       (update o r (fnil conj #{}) node)
                                       (let [s (disj (get o r #{}) node)]
                                         (if (seq s) (assoc o r s) (dissoc o r))))
                                     o))
                                 owners evs)
                       rev     (:rev (first evs))
                       dup     (for [[r nodes] owners' :when (< 1 (count nodes))]
                                 {:rev rev :replica r :nodes (vec (sort nodes))})
                       seen'   (reduce (fn [s {:keys [type key]}]
                                         (if-let [[node r] (assigned-key key)]
                                           (if (= :put type)
                                             (update s r (fnil conj #{}) node)
                                             s)
                                           s))
                                       (:ever st) evs)]
                   (-> st
                       (assoc :owners owners' :ever seen')
                       (update :revisions inc)
                       (update :violations into dup))))
        st     (reduce step {:owners {} :ever {} :revisions 0 :violations []}
                       by-rev)
        moved  (for [[r nodes] (:ever st) :when (< 1 (count nodes))]
                 {:replica r :nodes (vec (sort nodes))})]
    {:valid?             (cond (seq (:violations st)) false
                               (seq gaps)             :unknown
                               :else                  true)
     :revisions-checked  (:revisions st)
     :first-revision     (:rev (first events))
     :last-revision      (:rev (last events))
     :replicas-assigned  (count (:ever st))
     :violations         (vec (take 20 (:violations st)))
     :moved              (vec (take 20 moved))
     :gaps               (vec gaps)}))

;; ---------------------------------------------------------------- I1b

(defn instance-end
  "The last moment an instance is known to have run: its last heartbeat, or
  the TERM it noted, whichever is later. The true end is no later than one
  heartbeat interval after it (a SIGKILL leaves no line)."
  [{:keys [last term start]}]
  (max (or last start) (or term start)))

(defn overlap-ms
  "How long two instances ran at the same time (negative: the gap between
  them), from their records."
  [a b]
  (- (min (instance-end a) (instance-end b))
     (max (:start a) (:start b))))

(defn handovers
  "For each service, its instances in order of start, and for each instance
  after the first, the overlap with every earlier one (the largest is the
  one that matters). A service in this workload has one replica, so any two
  of its instances that ran at the same time are an old instance and its
  replacement."
  [instances]
  (for [[svc insts] (group-by :svc instances)
        :let [insts (vec (sort-by :start insts))]
        i (range 1 (count insts))
        :let [b (insts i)
              [a ov] (apply max-key second
                            (for [j (range i)] [(insts j) (overlap-ms (insts j) b)]))]]
    {:svc svc
     :old (select-keys a [:node :replica :pid :start])
     :old-end (instance-end a)
     :old-termed? (boolean (:term a))
     :new (select-keys b [:node :replica :pid :start])
     :overlap-ms ov}))

(defn i1b
  "The overlaps of every handover, the measured maximum, and whether it is
  within `bound-ms` (lost grace + kill latency). `slack-ms` is what the
  recorder's heartbeat interval may hide: the measured overlap is a lower
  bound, and the true one is at most slack-ms more. A violation is reported
  only when the lower bound exceeds the window, so it is certain; the upper
  bound is reported alongside."
  [instances bound-ms slack-ms]
  (let [hs    (sort-by :overlap-ms > (handovers instances))
        cross (filter #(not= (:node (:old %)) (:node (:new %))) hs)
        mx    (:overlap-ms (first hs))
        bad   (filter #(> (:overlap-ms %) bound-ms) hs)]
    {:valid?             (empty? bad)
     :bound-ms           bound-ms
     :heartbeat-ms       slack-ms
     :instances          (count instances)
     :services           (count (set (map :svc instances)))
     :handovers          (count hs)
     :cross-node-handovers (count cross)
     :max-overlap-ms     mx
     :max-overlap-upper-ms (some-> mx (+ slack-ms))
     :overlapping        (count (filter #(pos? (:overlap-ms %)) hs))
     :violations         (vec (take 20 bad))
     :worst              (vec (take 10 hs))
     :all-overlaps-ms    (vec (map :overlap-ms hs))}))

;; ---------------------------------------------------------------- records

(defn parse-record-line
  "One line of the recorder summary a node prints (zcube-jepsen.lease):
    <svc> <replica> <pid> <start> <last-heartbeat|-> <term|->
  times in epoch seconds with microseconds ($EPOCHREALTIME). Returns an
  instance with times in epoch milliseconds (doubles), or nil."
  [node line]
  (let [[svc r pid start last term :as fs] (str/split (str/trim line) #"\s+")
        t (fn [s] (when (and s (not= "-" s))
                    (* 1000.0 (Double/parseDouble s))))]
    (when (= 6 (count fs))
      {:node node :svc svc :replica r :pid (parse-long pid)
       :start (t start) :last (t last) :term (t term)})))

;; ---------------------------------------------------------------- verdict

(defn analyze
  "Everything the verdict needs, from the history's ops and the constants.
  `collect` is the :collect op's value; `applies` the completed :apply ops;
  `faults` the completed nemesis ops."
  [{:keys [constants ttl grace collect applies faults expect min-handovers]}]
  (let [fence  (fencing constants ttl grace)
        bound  (or (:i1b-bound-ms fence) 0)
        i1a*   (i1a (:events collect) (:gaps collect))
        i1b*   (i1b (:instances collect) bound heartbeat-ms*)
        oks    (count (filter #(= :ok (:type %)) applies))
        svcs   (count (set (map :value applies)))
        fcount (frequencies (map :f faults))
        sanity {:applies-ok oks
                :services svcs
                :faults fcount
                :collected? (some? collect)
                :watch-errors (:watch-errors collect)
                :cross-node-handovers (:cross-node-handovers i1b*)
                :min-handovers min-handovers}
        fine?  (and (some? collect)
                    (pos? svcs) (= oks svcs)
                    (>= (:cross-node-handovers i1b*) min-handovers))]
    {:fencing  fence
     :i1a      i1a*
     :i1b      i1b*
     :sanity   (assoc sanity :valid? fine?)
     :expected expect
     :valid?
     (case expect
       ; Every check holds, and the run did what it is for.
       :valid   (and fine? (true? (:valid? fence)) (true? (:valid? i1a*))
                     (true? (:valid? i1b*)))
       ; The wedged node: I1b must break (authority.md: "a wedged node must
       ; break I1b"), and I1a must hold all the same (no fault exempts it).
       :overlap (and fine? (true? (:valid? fence)) (true? (:valid? i1a*))
                     (false? (:valid? i1b*))))}))
