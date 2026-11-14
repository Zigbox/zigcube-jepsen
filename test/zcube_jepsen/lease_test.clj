(ns zcube-jepsen.lease-test
  "The recorder and its summary, run here with the local bash and awk (the
  nodes run them the same way), and the shape of the lease schedule."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :refer [sh]]
            [clojure.test :refer [deftest is testing]]
            [zcube-jepsen.core :as core]
            [zcube-jepsen.lease :as lease]
            [zcube-jepsen.lease-checkers :as lc]))

(defn- temp-dir []
  (let [d (.toFile (java.nio.file.Files/createTempDirectory
                     "zj-rec" (make-array java.nio.file.attribute.FileAttribute 0)))]
    (.deleteOnExit d)
    (.getPath d)))

(defn- summary [dir]
  (let [{:keys [exit out err]} (sh "bash" "-c" (lease/summarize dir))]
    (is (= 0 exit) err)
    (lc/parse-records "n4" out)))

(deftest summarize
  (let [dir (temp-dir)
        w   (fn [f s] (spit (io/file dir f) s))]
    (w "l0x-1.10.log" (str "S 100.000000 l0x l0x-1 10\n"
                           "H 100.010000\nH 100.060000\nT 100.070000\n"
                           "H 100.900000\nT 100.920000\nH 100.950000\n"))
    (w "l1x-1.11.log" "S 200.000000 l1x l1x-1 11\n")
    (w "l2x-1.12.log" "H 300.000000\nH 300.050000\n")
    (w "l3x-1.13.log" "")
    (let [{:keys [instances errors]} (summary dir)
          by (into {} (map (juxt :replica identity) instances))]
      (testing "times, the first TERM and the longest gap between two lines"
        (let [i (by "l0x-1")]
          (is (= ["l0x" 10 100000.0 100950.0 100070.0]
                 ((juxt :svc :pid :start :last :term) i)))
          (is (< 829.0 (:gap i) 831.0))))
      (testing "a start line alone: no heartbeat, no gap"
        (is (= [200000.0 nil nil nil]
               ((juxt :start :last :term :gap) (by "l1x-1")))))
      (testing "files with no start line are errors, not skipped"
        (is (= 2 (count instances)))
        (is (= 2 (count errors)))
        (is (every? #(re-find #"no-start" (:line %)) errors))))))

(deftest recorder
  (testing "a recorder that ignores TERM and is KILLed, under a parent whose
            last argument names the replica as the FakeRuntime supervisor's does"
    (let [dir (temp-dir)
          rec (io/file dir "zj-rec.sh")]
      (spit rec (lease/recorder-script dir))
      (let [{:keys [exit err]}
            (sh "bash" "-c"
                (str "ZJ_SVC=l0x bash \"$0\" & p=$!; sleep 0.5; kill -TERM $p; "
                     "sleep 0.3; kill -KILL $p; wait $p; true")
                (.getPath rec) "/state/containers/l0x-9")]
        (is (= 0 exit) err))
      (let [{:keys [instances errors]} (summary dir)
            [i] instances]
        (is (empty? errors))
        (is (= 1 (count instances)))
        (is (= ["l0x" "l0x-9"] [(:svc i) (:replica i)]))
        (is (some? (:term i)) "the TERM was noted")
        (is (< (:start i) (:term i) (:last i))
            "it ran on after the TERM, until the KILL")
        (is (< 200.0 (- (:last i) (:term i)) 400.0))
        (is (< 40.0 (:gap i) 300.0) "heartbeats about 50 ms apart")))))

(deftest stopped?
  (is (true? (lease/stopped? "T\nTl\n")))
  (is (false? (lease/stopped? "T\nS\n")) "one process still running")
  (is (false? (lease/stopped? "")) "no process at all"))

(def opts {:lease-ttl-ms 10000 :lost-grace-ms 1000 :fault-ms 25000})

(deftest lease-schedule
  (testing "partition: both faults, each healed, each held --fault-ms"
    (let [s (take 8 (core/lease-schedule (assoc opts :expect :valid)))]
      (is (= [:sleep :isolate-agent :sleep :heal :sleep :isolate-store :sleep :heal]
             (map #(if (= :sleep (:type %)) :sleep (:f %)) s)))
      (is (= [15 25 15 25] (map :value (filter #(= :sleep (:type %)) s))))))
  (testing "the default with --expect overlap is the wedge"
    (let [s (take 4 (core/lease-schedule (assoc opts :expect :overlap)))]
      (is (= [:sleep :wedge :sleep :unwedge]
             (map #(if (= :sleep (:type %)) :sleep (:f %)) s)))
      (is (= 25 (:value (nth s 2))))))
  (testing "--lease-faults partition with --expect overlap (the late-kill control)"
    (is (= :isolate-agent
           (:f (second (core/lease-schedule (assoc opts :expect :overlap
                                                   :lease-faults :partition)))))))
  (testing "a hold shorter than ttl + lost grace is refused"
    (is (thrown? IllegalArgumentException
                 (core/lease-schedule (assoc opts :expect :valid :fault-ms 10999))))
    (is (some? (core/lease-schedule (assoc opts :expect :valid :fault-ms 11000))))))
