(ns zcube-jepsen.checkers
  "Checkers beyond Knossos: the history as a versioned register checked with
  Elle, the write outage per failover, the guard against vacuous runs, and
  the verdict, which depends on what the run is expected to show.

  Versioned register. Every :ok op of both workloads carries :version: the
  store revision of the value it wrote or read (api: the service key's
  `mod_revision`; pg: the row's own counter). Versions are totally ordered
  per key, so the observations of a key line up into one sequence of values,
  and a register read of value v at version n is the same thing as a read of
  that sequence up to n. That turns the register history into an Elle
  list-append history: a write of v is an append of v, a read is a read of
  the list of every value at a version up to its own. Elle then checks it
  for strict serializability (one key per op, so: linearizability), with
  cycle explanations, in time linear in the history instead of Knossos's
  search. What Elle cannot see, because the encoding assumes it, is two
  values at one version of a key, or one value at two versions: those are
  reported here as :version-conflicts and :value-conflicts, and fail the
  check (a store that went back and re-used a version does that).

  That last guard needs per-key versions. pg's are (the row's counter); api's
  are not: `mod_revision` is the store's global revision, and after a store
  goes back, writes to other keys move it past a lost write's revision before
  the key is written again, so no version is re-used and the lost write would
  be spliced into the order. So the api store read also returns the key's
  history as the surviving store kept it (Zigcube's `store_log`, which the
  server compacts to its last 1000 revisions), the :chains. Above the
  compaction point the chain, not the observations, decides the order, and an
  acknowledged write or read of a [k version value] that is not on it is
  reported as :off-chain and fails the check: the surviving store never had
  it. Observations at or below the compaction point cannot be checked that
  way and are counted as :unverifiable.

  Values must never repeat within a key (core's unique-writes)."
  (:require [clojure.tools.logging :refer [info warn]]
            [elle.list-append :as la]
            [jepsen [checker :as checker]
                    [history :as h]
                    [store :as store]]))

(defn client-op? [op] (number? (:process op)))

(defn kv [op] (:value op))   ; [k v], an independent tuple

;; ---------------------------------------------------------------------------
;; The final store read

(defn store-read
  "The :store-read nemesis op's rows, parsed with `parse`, or nil if there was
  none or it failed. `parse` returns either {k [v version]} (the live rows)
  or {:live {k [v version]}, :chains {k {version v}}, :compacted rev}; the
  result is always the second shape (:chains nil when the store has none)."
  [history parse]
  (when-let [op (->> history
                     (filter #(and (= :nemesis (:process %))
                                   (= :store-read (:f %))
                                   (map? (:value %))))
                     last)]
    (let [p (parse (:rows (:value op)))]
      (if (contains? p :live)
        p
        {:live p :chains nil :compacted nil}))))

;; ---------------------------------------------------------------------------
;; Version orders

(defn observations
  "Every [k version value] the history shows: :ok writes and cas (the value
  written, at the version answered), :ok reads (the value read, at the key's
  version; for a cas also the value it replaced, at the version before),
  and the store read."
  [history store]
  (concat
    (mapcat
      (fn [op]
        (let [[k v] (kv op) ver (:version op)]
          (case (:f op)
            :write [[k ver v]]
            ; A cas's version is its new value's; the value it replaced was
            ; at the version before (only the pg workload has cas, and its
            ; versions count up by one per change).
            :cas   [[k (dec ver) (first v)] [k ver (second v)]]
            :read  [[k ver v]])))
      (filter #(and (client-op? %) (= :ok (:type %)) (:version %)) history))
    (for [[k [v ver]] (:live store)] [k ver v])
    (for [[k chain] (:chains store) [ver v] chain] [k ver v])))

(defn off-chain
  "With the store's :chains: splits the client observations [k version value]
  into {:off-chain [...] :unverifiable n}. Off chain: above the compaction
  point, and not on the key's chain. Unverifiable: at or below it, and not
  the entry the compaction kept. Without :chains: nil."
  [client-obs {:keys [chains compacted]}]
  (when chains
    (let [c (or compacted 0)
          on? (fn [[k ver v]] (= v (get-in chains [k ver] ::none)))
          [low high] ((juxt filter remove) (fn [[_ ver _]] (<= ver c))
                      (remove (fn [[_ ver _]] (zero? ver)) client-obs))]
      {:off-chain    (vec (distinct (remove on? high)))
       :unverifiable (count (distinct (remove on? low)))})))

(defn beyond-store
  "Acknowledged writes (and cas) the store read cannot hold: at a version
  above the store's version of the key, or of a key the store has no row
  for. Such a write was acknowledged and is not in the surviving store
  (versions only grow), whatever happened to other keys. Nil without a
  store read."
  [history store]
  (when store
    (vec (for [op history
               :when (and (client-op? op) (= :ok (:type op))
                          (#{:write :cas} (:f op)) (:version op))
                     :let [[k v] (kv op)
                           ver (:version op)
                           [_ sver] (get (:live store) k [nil 0])]
                     :when (< sver ver)]
           [k ver (if (= :cas (:f op)) (second v) v)]))))

(defn surviving-obs
  "The observations that make the version order: with :chains, the chain
  entries and whatever is at or below the compaction point (off-chain ones
  are left out, so they cannot be spliced in); without, all of them."
  [obs {:keys [chains compacted]}]
  (if chains
    (let [c (or compacted 0)]
      (filter (fn [[k ver v]]
                (or (<= ver c) (= v (get-in chains [k ver] ::none))))
              obs))
    obs))

(defn version-orders
  "Returns {:orders {k (sorted-map version value)}, :version-conflicts [...],
  :value-conflicts [...]}. Version 0 is the key's absence (value nil) and is
  not part of the order."
  [obs]
  (let [by-key (group-by first (remove (fn [[_ ver _]] (zero? ver)) obs))
        vconf  (for [[k os] by-key
                     [ver vs] (group-by second os)
                     :let [vals (distinct (map #(nth % 2) vs))]
                     :when (< 1 (count vals))]
                 {:key k :version ver :values (vec vals)})
        valconf (for [[k os] by-key
                      [v vs] (group-by #(nth % 2) os)
                      :let [vers (distinct (map second vs))]
                      :when (< 1 (count vers))]
                  {:key k :value v :versions (vec (sort vers))})]
    {:orders (into {}
                   (for [[k os] by-key]
                     [k (reduce (fn [m [_ ver v]]
                                  (if (contains? m ver) m (assoc m ver v)))
                                (sorted-map) os)]))
     :version-conflicts (vec vconf)
     :value-conflicts   (vec valconf)}))

(defn prefix
  "The list a read of v at version `ver` saw: every value of the key at a
  version below `ver`, in version order, then v itself."
  [orders k ver v]
  (let [below (->> (get orders k)
                   (take-while (fn [[ver' _]] (< ver' ver)))
                   (map val))]
    (vec (if (nil? v) below (concat below [v])))))

;; ---------------------------------------------------------------------------
;; The Elle history

(defn txn
  "A client op as a list-append transaction."
  [orders op]
  (let [[k v] (kv op)
        ok?   (= :ok (:type op))
        ver   (:version op)]
    (case (:f op)
      :write [[:append k v]]
      :read  (if (and ok? ver)
               [[:r k (prefix orders k ver v)]]
               [[:r k nil]])
      :cas   (let [[old new] v]
               (if (and ok? ver)
                 [[:r k (prefix orders k (dec ver) old)] [:append k new]]
                 [[:r k nil] [:append k new]])))))

(defn elle-history
  "The client ops as Elle list-append ops, then the store read (if any) as
  one more transaction, by a process of its own, after everything: a read of
  every key the clients used. A key the store has no row for reads []."
  [history orders store]
  (let [ops    (->> history
                    (filter client-op?)
                    (map (fn [op]
                           {:type    (:type op)
                            :process (:process op)
                            :time    (:time op)
                            :f       :txn
                            :value   (txn orders op)})))
        keys   (->> history (filter client-op?) (map (comp first kv))
                    (remove nil?) distinct sort)
        p      (inc (reduce max 0 (map :process ops)))
        t      (inc (reduce max 0 (keep :time history)))
        sread  (when store
                 [{:type :invoke :process p :time t :f :txn
                   :value (mapv (fn [k] [:r k nil]) keys)}
                  {:type :ok :process p :time (inc t) :f :txn
                   :value (mapv (fn [k]
                                  (let [[v ver] (get (:live store) k [nil 0])]
                                    [:r k (if (zero? ver)
                                            []
                                            (prefix orders k ver v))]))
                                keys)}])]
    (h/history (vec (concat ops sread)) {:dense-indices? false})))

(defn versioned-register
  "Elle over the history as a versioned register. `parse` turns the store
  read's rows into {k [v version]}."
  [parse]
  (reify checker/Checker
    (check [_ test history opts]
      (let [store  (store-read history parse)
            obs    (observations history store)
            chain  (off-chain (observations history nil) store)
            vo     (version-orders (surviving-obs obs store))
            eh     (elle-history history (:orders vo) store)
            dir    (.getCanonicalPath
                     (store/path! test (:subdirectory opts) "elle"))
            models {:consistency-models [:strict-serializable]}
            ; Elle draws each cycle it finds with graphviz's `dot`; without
            ; it the check throws, so it runs again without the drawings.
            res    (try (la/check (assoc models :directory dir) eh)
                        (catch Exception e
                          (warn e "Elle with plots failed; checking without")
                          (assoc (la/check models eh)
                                 :plots-failed (.getMessage e))))
            beyond (beyond-store history store)
            conflicts? (or (seq (:version-conflicts vo))
                           (seq (:value-conflicts vo))
                           (seq (:off-chain chain))
                           (seq beyond))]
        (merge res
               {:valid? (if conflicts? false (:valid? res))
                :store-read? (some? store)
                :chains? (some? (:chains store))
                :compacted (:compacted store)
                :off-chain (take 10 (:off-chain chain))
                :off-chain-count (count (:off-chain chain))
                :unverifiable (:unverifiable chain)
                :beyond-store (take 10 beyond)
                :beyond-store-count (count beyond)
                ; Direct evidence that an acknowledged write is not in the
                ; surviving store (what the negative control must show).
                :lost-evidence (cond-> []
                                 (seq (:version-conflicts vo)) (conj :version-conflicts)
                                 (seq (:off-chain chain))      (conj :off-chain)
                                 (seq beyond)                  (conj :beyond-store))
                :keys (count (:orders vo))
                :version-conflicts (take 10 (:version-conflicts vo))
                :value-conflicts   (take 10 (:value-conflicts vo))})))))

;; ---------------------------------------------------------------------------
;; Outage per failover

(defn completions
  "Client ops paired: [{:f :type :invoke-time :complete-time} ...]."
  [history]
  (loop [ops history, open {}, out (transient [])]
    (if-let [op (first ops)]
      (if (client-op? op)
        (if (= :invoke (:type op))
          (recur (next ops) (assoc open (:process op) op) out)
          (let [inv (open (:process op))]
            (recur (next ops) (dissoc open (:process op))
                   (conj! out {:f (:f op) :type (:type op)
                               :invoke-time (:time inv)
                               :complete-time (:time op)}))))
        (recur (next ops) open out))
      (persistent! out))))

(defn nemesis-spans
  "Completed nemesis ops of `f`: [{:start t :end t :value v}]."
  [history f]
  (loop [ops history, start nil, out []]
    (if-let [op (first ops)]
      (if (and (= :nemesis (:process op)) (= f (:f op)))
        (if (nil? (:value op))
          (recur (next ops) (:time op) out)
          (recur (next ops) nil
                 (conj out {:start start :end (:time op) :value (:value op)})))
        (recur (next ops) start out))
      out)))

(defn ms [nanos] (when nanos (Math/round (/ nanos 1e6))))

(defn outage
  "For every failover that promoted: how long writes failed. Times are ms from
  the start of the failover op.

  :writes-ok-after  when the first write issued after the promotion finished
                    was acknowledged (nil: none was, before the run ended),
                    measured from the failover's start. This is the number
                    to quote. Writes issued between the start and the
                    promotion's end do not count: one can still commit on
                    the old primary before the fence lands, and its ack says
                    nothing about the new one.
  :gap             the longest stretch between two consecutive write acks
                    (of any write, whenever issued) from the last ack before
                    the failover to that first ack after the promotion.
  :promoted, :rejoined  when the promotion and the rejoin finished.
  :failed, :indeterminate  writes issued from the failover's start until that
                    first ack that failed, or timed out."
  []
  (reify checker/Checker
    (check [_ test history opts]
      (let [writes  (->> (completions history)
                         (filter #(#{:write :cas} (:f %))))
            oks     (filter #(= :ok (:type %)) writes)
            rejoins (->> (nemesis-spans history :rejoin)
                         (filter #(= :rejoined (first (:value %)))))
            fos     (->> (nemesis-spans history :failover)
                         (filter #(= :promoted (first (:value %)))))
            per     (vec
                      (for [{:keys [start end value]} fos
                            :let [first-ok (->> oks
                                                (filter #(<= end (:invoke-time %)))
                                                (map :complete-time)
                                                (reduce min Long/MAX_VALUE))
                                  first-ok (when (< first-ok Long/MAX_VALUE) first-ok)
                                  last-ok  (->> oks
                                                (map :complete-time)
                                                (filter #(< % start))
                                                (reduce max -1))
                                  rejoin   (->> rejoins (map :end)
                                                (filter #(< start %)) sort first)
                                  window   (filter #(and (<= start (:invoke-time %))
                                                         (or (nil? first-ok)
                                                             (< (:invoke-time %) first-ok)))
                                                   writes)]]
                        {:failover         value
                         :promoted         (ms (- end start))
                         :rejoined         (some-> rejoin (- start) ms)
                         :writes-ok-after  (some-> first-ok (- start) ms)
                         :gap              (when (and first-ok (<= 0 last-ok))
                                             (let [acks (->> oks
                                                             (map :complete-time)
                                                             (filter #(< last-ok % first-ok))
                                                             sort)
                                                   ts   (concat [last-ok] acks [first-ok])]
                                               (ms (reduce max (map - (rest ts) ts)))))
                         :failed           (count (filter #(= :fail (:type %)) window))
                         :indeterminate    (count (filter #(= :info (:type %)) window))}))
            outs    (keep :writes-ok-after per)]
        (info "write outage per failover (ms from the failover's start to the"
              "first ack of a write issued after it):" (vec outs))
        {:valid?       true      ; a measurement, not a check
         :failovers    per
         :writes-ok-after-ms {:min  (when (seq outs) (reduce min outs))
                              :max  (when (seq outs) (reduce max outs))
                              :mean (when (seq outs)
                                      (Math/round (double (/ (reduce + outs)
                                                             (count outs)))))}
         :never-recovered (count (filter #(nil? (:writes-ok-after %)) per))}))))

;; ---------------------------------------------------------------------------
;; Sanity

(defn error-codes
  "Every string inside an op's :error, so a code is found whatever the shape."
  [op]
  (filter string? (tree-seq coll? seq (:error op))))

(defn sanity
  "Facts about the run that decide whether it showed anything, and how it
  ended. Its :valid? is only the vacuity guard; the verdict combines the rest:
  - every op kind in `fs` completed :ok at least once;
  - at least `min-failovers` failovers completed, and none failed;
  - :halted-ops, the ops that saw ZC-E0215 (Zigcube's halt when the store
    went back past a revision it had seen; answered 502, which the clients
    record as :info/:fail, so neither Knossos nor Elle sees it);
  - the final reads (after heal, rejoin and a settle): keys read without an
    :ok answer;
  - whether the final store read worked."
  [fs min-failovers]
  (reify checker/Checker
    (check [_ test history opts]
      (let [oks  (->> history
                      (filter #(and (= :ok (:type %)) (client-op? %)))
                      (map :f) frequencies)
            nem  (fn [f] (filter #(and (= :nemesis (:process %)) (= f (:f %))
                                       (= :info (:type %))
                                       (some? (:value %)))
                                 history))
            fos  (count (filter #(= :promoted (first (:value %))) (nem :failover)))
            bad-fos (filter #(= :failover-failed (first (:value %))) (nem :failover))
            rejoins (count (filter #(= :rejoined (first (:value %))) (nem :rejoin)))
            bad-rejoins (filter #(= :rejoin-failed (first (:value %))) (nem :rejoin))
            halted  (->> history
                         (filter #(some #{"ZC-E0215"} (error-codes %))))
            sread   (last (nem :store-read))
            ; Final reads: client ops after the :final-phase nemesis op. (Not
            ; marked on the ops: an extra key on an op corrupts how Knossos
            ; reads the per-key histories.)
            start   (or (->> history
                             (filter #(and (= :nemesis (:process %))
                                           (= :final-phase (:f %))))
                             (map :index) first)
                        Long/MAX_VALUE)
            final   (filter #(and (client-op? %) (> (:index %) start)) history)
            final-keys    (->> final (filter #(= :invoke (:type %)))
                               (map #(first (:value %))) set)
            final-ok-keys (->> final (filter #(= :ok (:type %)))
                               (map #(first (:value %))) set)
            final-missing (sort (remove final-ok-keys final-keys))
            vacuous (cond-> []
                      (not-every? #(pos? (get oks % 0)) fs)
                      (conj :an-op-kind-never-succeeded)
                      (< fos min-failovers) (conj :too-few-failovers)
                      (seq bad-fos)         (conj :a-failover-failed)
                      ; After a failed rejoin every later failover is skipped
                      ; and a sync primary takes no writes: the rest of the
                      ; schedule would do nothing.
                      (seq bad-rejoins)     (conj :a-rejoin-failed)
                      (empty? final-keys)   (conj :no-final-reads))]
        {:valid?    (empty? vacuous)
         :vacuous   vacuous
         :ok-counts oks
         :failovers fos
         :failover-failures (mapv :value bad-fos)
         :rejoins   rejoins
         :rejoin-failures (mapv :value bad-rejoins)
         :halted?   (boolean (seq halted))
         :halted-ops (->> halted
                          (map #(select-keys % [:index :process :f :type :error]))
                          (take 5) vec)
         :store-read (cond (nil? sread) :none
                           (map? (:value sread)) {:node (:node (:value sread))
                                                  :rows (count (:rows (:value sread)))}
                           :else (:value sread))
         :final-read-keys   (count final-keys)
         :final-reads-ok    (count (filter #(= :ok (:type %)) final))
         :final-read-keys-without-ok (vec final-missing)}))))

;; ---------------------------------------------------------------------------
;; Verdict

(defn verdict
  "Runs the named checkers and decides :valid? from what the run is meant to
  show (the test's :expect, :failover).

  :expect :valid (every run but the negative control)
    Knossos and Elle find nothing, the run is not vacuous, the store read
    worked, and either every finally-read key got an :ok read, or the server
    halted with ZC-E0215 where that is an allowed ending: only with
    :failover :unfenced, where the old primary may have made a commit visible
    that the promoted standby never got. A halt is never a pass by itself:
    Elle must still pass, and for api that includes the store chain check
    (every acknowledged write above the compaction point is on the surviving
    store's history of its key).

  :expect :lost-write (the negative control)
    The run is not vacuous, the store read worked, Knossos or Elle found a
    violation, and Elle's store-based facts show the loss itself
    (:lost-evidence: a version re-used for two values, an acknowledged op off
    the surviving chain, or an acknowledged write beyond the store's version
    of its key). Any other anomaly alone does not count: it would hide a
    control that stopped losing writes. A ZC-E0215 halt is reported
    (:halted?) but does not count either: it is Zigcube catching the loss,
    and the control is there to show that the checkers do. Valid means the
    checkers have teeth here.

  Sanity's :valid? must be exactly true: a sanity checker that threw comes
  back as :unknown, which is no vacuity guard."
  [checkers]
  (let [inner (checker/compose checkers)]
    (reify checker/Checker
      (check [_ test history opts]
        (let [r       (checker/check inner test history opts)
              sanity  (:sanity r)
              linear  (:valid? (:linear r))
              elle    (:valid? (:elle r))
              halted? (:halted? sanity)
              fine?   (true? (:valid? sanity))
              sread?  (map? (:store-read sanity))
              halt-ok? (= :unfenced (:failover test))
              finals? (empty? (:final-read-keys-without-ok sanity))
              caught  (cond-> []
                        (false? linear) (conj :knossos)
                        (false? elle)   (conj :elle))]
          (assoc r
                 :expected (:expect test :valid)
                 :caught-by caught
                 :lost-evidence (vec (:lost-evidence (:elle r)))
                 :halted? (boolean halted?)
                 :valid?
                 (case (:expect test :valid)
                   :valid
                   (cond (or (false? linear) (false? elle)
                             (not fine?) (not sread?))  false
                         halted?  (and halt-ok? (true? elle)
                                       (checker/merge-valid [linear true]))
                         (not finals?) false
                         :else (checker/merge-valid [linear elle]))

                   :lost-write
                   (boolean (and fine? sread? (seq caught)
                                 (seq (:lost-evidence (:elle r))))))))))))
