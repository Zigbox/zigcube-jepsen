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
  "The :store-read nemesis op's rows, parsed with `parse` into
  {k [v version]}, or nil if there was none or it failed."
  [history parse]
  (when-let [op (->> history
                     (filter #(and (= :nemesis (:process %))
                                   (= :store-read (:f %))
                                   (map? (:value %))))
                     last)]
    (parse (:rows (:value op)))))

;; ---------------------------------------------------------------------------
;; Version orders

(defn observations
  "Every [k version value] the history shows: :ok writes and cas (the value
  written, at the version answered), :ok reads (the value read, at the key's
  version), and the store read."
  [history store]
  (concat
    (for [op history
          :when (and (client-op? op) (= :ok (:type op)) (:version op))
          :let [[k v] (kv op)]]
      (case (:f op)
        :write [k (:version op) v]
        :cas   [k (:version op) (second v)]
        :read  [k (:version op) v]))
    (for [[k [v ver]] store] [k ver v])))

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
                 [[:r k (prefix orders k ver old)] [:append k new]]
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
                                  (let [[v ver] (get store k [nil 0])]
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
            vo     (version-orders (observations history store))
            eh     (elle-history history (:orders vo) store)
            dir    (.getCanonicalPath
                     (store/path! test (:subdirectory opts) "elle"))
            res    (la/check {:consistency-models [:strict-serializable]
                              :directory dir}
                             eh)
            conflicts? (or (seq (:version-conflicts vo))
                           (seq (:value-conflicts vo)))]
        (merge res
               {:valid? (if conflicts? false (:valid? res))
                :store-read? (some? store)
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

  :writes-ok-after  when the first write issued after the failover began was
                    acknowledged (nil: none was, before the run ended). This
                    is the number to quote: no write issued in that window
                    succeeded.
  :gap             the longest stretch around the failover with no write
                    acknowledged at all: from the last ack before it to the
                    first ack after.
  :promoted, :rejoined  when the promotion and the rejoin finished.
  :failed, :indeterminate  writes issued in the window that failed, or
                    timed out."
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
                                                (filter #(<= start (:invoke-time %)))
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
                                             (ms (- first-ok last-ok)))
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
    that the promoted standby never got. A halt is never a pass by itself: the
    store read still has to show every acknowledged write (Elle).

  :expect :lost-write (the negative control)
    The run is not vacuous, and something caught the loss: Knossos or Elle
    found a violation, or the server halted with ZC-E0215. Valid means the
    checkers have teeth here."
  [checkers]
  (let [inner (checker/compose checkers)]
    (reify checker/Checker
      (check [_ test history opts]
        (let [r       (checker/check inner test history opts)
              sanity  (:sanity r)
              linear  (:valid? (:linear r))
              elle    (:valid? (:elle r))
              halted? (:halted? sanity)
              fine?   (:valid? sanity)
              sread?  (map? (:store-read sanity))
              halt-ok? (= :unfenced (:failover test))
              finals? (empty? (:final-read-keys-without-ok sanity))
              caught  (cond-> []
                        (false? linear) (conj :knossos)
                        (false? elle)   (conj :elle)
                        halted?         (conj :zc-e0215))]
          (assoc r
                 :expected (:expect test :valid)
                 :caught-by caught
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
                   (boolean (and fine? (seq caught))))))))))
