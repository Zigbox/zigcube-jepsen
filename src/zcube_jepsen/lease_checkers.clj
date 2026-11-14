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
         ; A fence's TERM to the instance's last heartbeat: the KILL is due
         ; fence_stop_ms after TERM, within the kill latency, and the last
         ; heartbeat is at most one interval before it.
         :kill-bound-ms   (+ (:fence-stop-ms c) (:fence-kill-latency-ms c)
                             heartbeat-ms*)
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

  It is also :unknown when it had nothing to check: no assignment ever put,
  or never one deleted (every run that hands a replica over deletes the old
  assignment), or an event of a type other than :put and :delete (a renamed
  type would otherwise read as a delete).

  Also lists the replicas that were ever assigned to more than one node, one
  after the other (a move: authority.md allows it, so it is information, not
  a violation)."
  [events gaps]
  (let [bad    (remove #(#{:put :delete} (:type %)) events)
        by-rev (partition-by :rev events)
        step   (fn [{:keys [owners] :as st} evs]
                 (let [owners' (reduce
                                 (fn [o {:keys [type key]}]
                                   (if-let [[node r] (assigned-key key)]
                                     (case type
                                       :put    (update o r (fnil conj #{}) node)
                                       :delete (let [s (disj (get o r #{}) node)]
                                                 (if (seq s) (assoc o r s) (dissoc o r)))
                                       o)
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
                 {:replica r :nodes (vec (sort nodes))})
        akeys  (filter (comp assigned-key :key) events)
        puts   (count (filter #(= :put (:type %)) akeys))
        dels   (count (filter #(= :delete (:type %)) akeys))]
    {:valid?             (cond (seq (:violations st)) false
                               (seq gaps)             :unknown
                               (seq bad)              :unknown
                               (zero? puts)           :unknown
                               (zero? dels)           :unknown
                               :else                  true)
     :revisions-checked  (:revisions st)
     :first-revision     (:rev (first events))
     :last-revision      (:rev (last events))
     :replicas-assigned  (count (:ever st))
     :assignment-puts    puts
     :assignment-deletes dels
     :bad-events         (vec (take 20 bad))
     :violations         (vec (take 20 (:violations st)))
     :moved              (vec (take 20 moved))
     :gaps               (vec gaps)}))

(defn assignment-periods
  "Every period during which an /assigned/<node>/<replica> key existed, in
  the watch: {:node :replica :from-t :to-t}, times in epoch ms as the watch
  received the events (:t), nil where unknown. A key still present at the
  end is closed at `end-t`."
  [events end-t]
  (let [{:keys [open done]}
        (reduce (fn [{:keys [open] :as st} {:keys [type key t]}]
                  (if-let [k (assigned-key key)]
                    (case type
                      :put    (if (contains? open k)
                                st
                                (assoc-in st [:open k] t))
                      :delete (if (contains? open k)
                                (-> st
                                    (update :open dissoc k)
                                    (update :done conj [k (get open k) t]))
                                st)
                      st)
                    st))
                {:open {} :done []} events)]
    (vec (for [[[node r] from to] (concat done (for [[k from] open]
                                                 [k from end-t]))]
           {:node node :replica r :from-t from :to-t to}))))

;; ---------------------------------------------------------------- completeness

(defn records-check
  "Whether the records account for every assignment the watch saw, so that
  no handover can go unmeasured because an instance's record is missing:
    - every [node replica] the watch assigned has a record on that node,
      unless each of its assignments lasted less than `startup-ms` (by the
      watch's receipt times; an assignment of unknown length counts as long:
      the agent may not have started it yet when it was taken away);
    - no [node replica] has more records than it had assignments, and every
      record belongs to an assignment;
    - every record has a start and a heartbeat;
    - the nodes reported no record file they could not read (`errors`)."
  [periods instances errors startup-ms]
  (let [ps       (group-by (juxt :node :replica) periods)
        rs       (group-by (juxt :node :replica) instances)
        long?    (fn [{:keys [from-t to-t]}]
                   (or (nil? from-t) (nil? to-t) (>= (- to-t from-t) startup-ms)))
        missing  (for [[k kps] ps :when (and (empty? (get rs k)) (some long? kps))]
                   k)
        short    (for [[k kps] ps :when (and (empty? (get rs k))
                                             (not-any? long? kps))]
                   k)
        extra    (for [[k krs] rs :when (> (count krs) (count (get ps k)))]
                   {:assignment k :records (count krs)
                    :assignments (count (get ps k))})
        partial  (for [i instances :when (or (nil? (:start i)) (nil? (:last i)))]
                   (select-keys i [:node :svc :replica :pid]))]
    {:valid?      (and (boolean (seq instances)) (empty? missing) (empty? extra)
                       (empty? partial) (empty? errors))
     :assignments (count ps)
     :records     (count instances)
     :missing     (vec (take 20 (map vec missing)))
     :short-unrecorded (vec (take 20 (map vec short)))
     :extra       (vec (take 20 extra))
     :partial     (vec (take 20 partial))
     :errors      (vec (take 20 errors))}))

;; ---------------------------------------------------------------- I1b

(defn instance-end
  "The last moment an instance is known to have run: its last heartbeat, or
  the TERM it noted, whichever is later. The true end is no later than one
  heartbeat gap after it (a SIGKILL leaves no line)."
  [{:keys [last term start]}]
  (max (or last start) (or term start)))

(defn instance-slack
  "How long after instance-end the instance may still have run: the longest
  gap between two of its own lines, or the nominal heartbeat if longer or
  unmeasured."
  [{:keys [gap]}]
  (max heartbeat-ms* (or gap 0)))

(defn overlap-ms
  "How long two instances ran at the same time (negative: the gap between
  them), from their records: a lower bound."
  [a b]
  (- (min (instance-end a) (instance-end b))
     (max (:start a) (:start b))))

(defn overlap-upper-ms
  "overlap-ms, with each end moved out by the instance's slack: the longest
  the two can have run at the same time."
  [a b]
  (- (min (+ (instance-end a) (instance-slack a))
          (+ (instance-end b) (instance-slack b)))
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
                            (for [j (range i)] [(insts j) (overlap-ms (insts j) b)]))
              up (apply max (for [j (range i)] (overlap-upper-ms (insts j) b)))]]
    {:svc svc
     :old (select-keys a [:node :replica :pid :start])
     :old-end (instance-end a)
     :old-termed? (boolean (:term a))
     :new (select-keys b [:node :replica :pid :start])
     :overlap-ms ov
     :overlap-upper-ms up}))

(defn kill-timing
  "The fence's own timing, per instance: from the TERM it noted to its last
  heartbeat (a lower bound on when the KILL came) must be within `bound-ms`
  (fence_stop + kill latency + one heartbeat). Unlike the overlap, this does
  not depend on when the replacement started, so a KILL that came late does
  not hide behind a replacement that came later still."
  [instances bound-ms]
  (let [ks  (sort-by :term-to-last-ms >
                     (for [i instances :when (and (:term i) (:last i))]
                       (assoc (select-keys i [:node :svc :replica :pid])
                              :term-to-last-ms (- (:last i) (:term i)))))
        bad (filter #(> (:term-to-last-ms %) bound-ms) ks)]
    {:valid?             (empty? bad)
     :bound-ms           bound-ms
     :termed             (count ks)
     :max-term-to-last-ms (:term-to-last-ms (first ks))
     :violations         (vec (take 20 bad))}))

(defn i1b
  "The overlaps of every handover, the measured maximum, and whether it is
  within `bound-ms` (lost grace + kill latency); and the fence's kill timing
  per instance (`kill-bound-ms`). Each overlap is measured as a lower bound
  (from the last line each instance wrote) and an upper bound (each end
  moved out by the longest gap that instance's recorder left between two
  lines, at least the nominal heartbeat). The overlap check is:
    false     some lower bound exceeds the window (certain);
    :unknown  none does, but some upper bound does;
    true      every upper bound is within it.
  :valid? is the overlap check and the kill timing together."
  ([instances bound-ms] (i1b instances bound-ms Long/MAX_VALUE))
  ([instances bound-ms kill-bound-ms]
   (let [hs    (sort-by :overlap-ms > (handovers instances))
         cross (filter #(not= (:node (:old %)) (:node (:new %))) hs)
         mx    (:overlap-ms (first hs))
         bad   (filter #(> (:overlap-ms %) bound-ms) hs)
         maybe (filter #(> (:overlap-upper-ms %) bound-ms) hs)
         ov?   (cond (seq bad) false (seq maybe) :unknown :else true)
         kill  (kill-timing instances kill-bound-ms)
         gaps  (keep :gap instances)]
     {:valid?             (cond (false? ov?) false
                                (not (:valid? kill)) false
                                :else ov?)
      :overlap-valid?     ov?
      :kill               kill
      :bound-ms           bound-ms
      :heartbeat-ms       heartbeat-ms*
      :max-heartbeat-gap-ms (when (seq gaps) (apply max gaps))
      :instances          (count instances)
      :services           (count (set (map :svc instances)))
      :handovers          (count hs)
      :cross-node-handovers (count cross)
      :max-overlap-ms     mx
      :max-overlap-upper-ms (when (seq hs) (apply max (map :overlap-upper-ms hs)))
      :overlapping        (count (filter #(pos? (:overlap-ms %)) hs))
      :violations         (vec (take 20 bad))
      :uncertain          (vec (take 20 (remove (set bad) maybe)))
      :worst              (vec (take 10 hs))
      :all-overlaps-ms    (vec (map :overlap-ms hs))})))

;; ---------------------------------------------------------------- records

(defn parse-record-line
  "One line of the recorder summary a node prints (zcube-jepsen.lease):
    <svc> <replica> <pid> <start> <last-heartbeat|-> <term|-> <max-gap|->
  times in epoch seconds with microseconds ($EPOCHREALTIME), the gap in
  seconds. Returns an instance with times in epoch milliseconds (doubles),
  or nil."
  [node line]
  (let [[svc r pid start last term gap :as fs] (str/split (str/trim line) #"\s+")
        t (fn [s] (when (and s (not= "-" s))
                    (* 1000.0 (Double/parseDouble s))))]
    (when (and (= 7 (count fs)) (not= "E" svc))
      (try
        (let [i {:node node :svc svc :replica r :pid (parse-long pid)
                 :start (t start) :last (t last) :term (t term) :gap (t gap)}]
          (when (and (:pid i) (:start i)) i))
        (catch NumberFormatException _ nil)))))

(defn parse-records
  "A node's summary: {:instances [...] :errors [...]}. A line the recorder
  summary marks as an error (`E <file> <reason>`: a file with no start line)
  or that does not parse is an error, not skipped."
  [node out]
  (reduce (fn [acc line]
            (cond (str/blank? line) acc
                  (str/starts-with? line "E ")
                  (update acc :errors conj {:node node :line line})
                  :else
                  (if-let [i (parse-record-line node line)]
                    (update acc :instances conj i)
                    (update acc :errors conj {:node node :line line}))))
          {:instances [] :errors []}
          (str/split-lines (or out ""))))

;; ---------------------------------------------------------------- faults

(def fault-heal
  "Each fault op and the op that ends it."
  {:isolate-agent :heal, :isolate-store :heal, :wedge :unwedge})

(def schedule-faults
  "The fault ops each schedule must have completed."
  {:partition [:isolate-agent :isolate-store]
   :wedge     [:wedge]})

(defn fault-kind
  "The schedule a lease run uses: --lease-faults, or by default wedge with
  --expect overlap, else partition."
  [lease-faults expect]
  (or lease-faults (if (= :overlap expect) :wedge :partition)))

(defn fault-windows
  "Each completed fault op with a value (a :wedge only if every agent process
  on the node was seen stopped), paired with the first op after it that ends
  it, and how long it held in ms (nil if never ended). `faults` are the
  completed nemesis ops, with :time in ns."
  [faults]
  (let [ops (vec (sort-by :time faults))]
    (vec
      (for [[i {:keys [f value time]}] (map-indexed vector ops)
            :when (and (fault-heal f) (some? value)
                       (or (not= :wedge f) (true? (:stopped? value))))
            :let [end (first (filter #(= (fault-heal f) (:f %))
                                     (subvec ops (inc i))))]]
        {:f f :value value
         :held-ms (when end (/ (- (:time end) time) 1e6))}))))

(defn faults-check
  "Whether the run did what it is for: every fault op of the schedule
  `kind` completed at least once and held for at least `min-hold-ms` (ttl +
  lost grace: shorter, the node is not lost and nothing is replaced; the
  last fault of a run can be cut short by the time limit)."
  [faults kind min-hold-ms]
  (let [ws   (fault-windows faults)
        held (filter #(and (:held-ms %) (>= (:held-ms %) min-hold-ms)) ws)
        need (schedule-faults kind)
        got  (frequencies (map :f held))
        lack (vec (remove got need))]
    {:valid?      (and (some? need) (empty? lack))
     :kind        kind
     :min-hold-ms min-hold-ms
     :held        got
     :missing     (if need lack [:unknown-kind kind])
     :windows     (vec (take 40 (map #(select-keys % [:f :held-ms]) ws)))}))

;; ---------------------------------------------------------------- verdict

(def startup-ms*
  "An assignment that lasted less than this may have no record: the agent
  need not have started it."
  3000)

(defn analyze
  "Everything the verdict needs, from the history's ops and the constants.
  `collect` is the :collect op's value; `applies` the completed :apply ops;
  `faults` the completed nemesis ops (with :time); `fault-kind` the schedule
  (fault-kind)."
  [{:keys [constants ttl grace collect applies faults expect min-handovers
           fault-kind]}]
  (let [fence  (fencing constants ttl grace)
        bound  (or (:i1b-bound-ms fence) 0)
        kbound (or (:kill-bound-ms fence) 0)
        insts  (:instances collect)
        i1a*   (i1a (:events collect) (:gaps collect))
        i1b*   (i1b insts bound kbound)
        recs   (records-check (assignment-periods (:events collect)
                                                  (:finished-at collect))
                              insts (:record-errors collect) startup-ms*)
        flt    (faults-check faults fault-kind (+ ttl grace))
        oks    (count (filter #(= :ok (:type %)) applies))
        svcs   (count (set (map :value applies)))
        sanity {:applies-ok oks
                :services svcs
                :faults flt
                :records recs
                :collected? (some? collect)
                :watch-errors (:watch-errors collect)
                :cross-node-handovers (:cross-node-handovers i1b*)
                :min-handovers min-handovers}
        fine?  (boolean
                 (and (some? collect)
                      (pos? svcs) (= oks svcs)
                      (:valid? flt)
                      (:valid? recs)
                      (>= (:cross-node-handovers i1b*) min-handovers)))]
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
       ; The wedged node: I1b's overlap must break, certainly (authority.md:
       ; "a wedged node must break I1b"), and I1a must hold all the same (no
       ; fault exempts it).
       :overlap (and fine? (true? (:valid? fence)) (true? (:valid? i1a*))
                     (false? (:overlap-valid? i1b*))))}))
