(ns zcube-jepsen.zcube
  "The Zigcube deployment: zcube-server on the third node, zcube-agents with
  the fake runtime on the rest, over the Postgres pair of the first two."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :refer [info warn]]
            [jepsen [control :as c]
                    [db :as db]
                    [net :as net]
                    [util :as util]]
            [jepsen.control [net :as cn]
                            [util :as cu]]
            [zcube-jepsen.postgres :as pg]))

(def dir "/opt/zcube")
(def state-dir "/var/lib/zj")
(def server-log (str state-dir "/server.log"))
(def server-pid (str state-dir "/server.pid"))
(def agent-log (str state-dir "/agent.log"))
(def agent-pid (str state-dir "/agent.pid"))
(def port 7070)

(defn server-node [test] (nth (:nodes test) 2))
(defn agent-nodes [test] (vec (drop 3 (:nodes test))))

(defn point-pg!
  "Zigcube has no multi-host connection string: its store address is one
  name, which the operator repoints after a failover (docs/operations.md).
  Here the name is `pg` in the server node's /etc/hosts. Run on that node."
  [primary]
  (let [ip (cn/ip primary)]
    ; /etc/hosts is a bind mount in Docker: rewrite in place, never rename.
    (c/su (c/exec :bash :-c
                  (str "grep -v ' pg$' /etc/hosts > /tmp/hosts.new; "
                       "echo '" ip " pg' >> /tmp/hosts.new; "
                       "cat /tmp/hosts.new > /etc/hosts")))))

(defn start-server! [test]
  (c/su
    (cu/start-daemon!
      {:logfile server-log :pidfile server-pid :chdir dir}
      (str dir "/zcube-server")
      :--listen (str "0.0.0.0:" port)
      :--store (str "postgres:host=pg port=5432 user=jepsen dbname=jepsen"
                    " connect_timeout=3")
      :--state-dir (str state-dir "/server")
      :--lease-ttl-ms (:lease-ttl-ms test 10000)
      :--create-store)))

(defn stop-server! []
  (c/su (cu/stop-daemon! (str dir "/zcube-server") server-pid)))

(defn await-server!
  "Blocks until the server answers /v1/status. Run on any node."
  [test]
  (let [url (str "http://" (cn/ip (server-node test)) ":" port "/v1/status")]
    (util/await-fn
      (fn [] (c/exec :curl :-fsS :-m 3 :-o "/dev/null" url))
      {:retry-interval 500 :log-interval 5000 :timeout 90000
       :log-message "waiting for zcube-server"})))

(defn start-agent! [test node]
  (c/su
    (cu/start-daemon!
      {:logfile agent-log :pidfile agent-pid :chdir dir}
      (str dir "/zcube-agent")
      :--node node
      :--server (str (cn/ip (server-node test)) ":" port)
      :--state-dir (str state-dir "/agent")
      :--cpu-millis 4000
      :--memory-bytes 8589934592
      :--images (str dir "/images.zon"))))

(defn kill-all!
  "Kills the server, the agent and everything the fake runtime started."
  []
  (c/su (c/exec :bash :-c
                ; [z]: so that -f does not match this very shell.
                (str "pkill -9 -x zcube-server; pkill -9 -x zcube-agent; "
                     "pkill -9 -f '[z]cube-agent supervise'; true"))))

(defn install! [test role]
  (let [bin (:zcube-bin test)]
    (c/su (c/exec :mkdir :-p dir (str state-dir "/server") (str state-dir "/agent")))
    (doseq [f (case role
                :server ["zcube-server"]
                :agent  ["zcube-agent"])]
      (c/upload (str (io/file bin f)) (str dir "/" f))
      (c/su (c/exec :chmod :+x (str dir "/" f))))
    (when (= role :agent)
      (c/upload (:zcube-images test) (str dir "/images.zon")))))

(defn db
  "Postgres pair, server, agents."
  []
  (reify db/DB
    (setup! [_ test node]
      (let [[primary _] (pg/pg-nodes test)]
        (cond
          (some #{node} (pg/pg-nodes test))
          (pg/setup-node! test node)

          (= node (server-node test))
          (do (kill-all!)
              (c/su (c/exec :rm :-rf state-dir))
              (install! test :server)
              (point-pg! primary)
              (pg/await-primary! "pg")
              (pg/await-sync-standby! "pg")
              (start-server! test)
              (await-server! test))

          :else
          (do (kill-all!)
              (c/su (c/exec :rm :-rf state-dir))
              (install! test :agent)
              (await-server! test)
              (start-agent! test node)))))

    (teardown! [_ test node]
      (if (some #{node} (pg/pg-nodes test))
        (pg/wipe!)
        (do (kill-all!)
            (c/su (c/exec :rm :-rf state-dir)))))

    db/LogFiles
    (log-files [_ test node]
      (cond (some #{node} (pg/pg-nodes test)) [pg/log-file]
            (= node (server-node test))       [server-log]
            :else                             [agent-log]))))

(defn on-promote
  "After a failover, repoint the server's store name at the new primary."
  [test new-primary]
  (c/on-nodes test [(server-node test)]
              (fn [_ _] (point-pg! new-primary))))
