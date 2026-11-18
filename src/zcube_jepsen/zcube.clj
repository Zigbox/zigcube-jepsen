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
            [zcube-jepsen.postgres :as pg])
  (:import (java.io File)
           (java.nio.file Files)
           (java.nio.file.attribute FileAttribute PosixFilePermissions)
           (java.security KeyFactory KeyStore)
           (java.security.cert Certificate CertificateException
                               CertificateFactory X509Certificate)
           (java.security.spec PKCS8EncodedKeySpec)
           (java.util Base64)
           (javax.net.ssl KeyManagerFactory SSLContext TrustManager
                          TrustManagerFactory X509ExtendedTrustManager)))

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

(def server-state-dir (str state-dir "/server"))

(def store-spec
  (str "postgres:host=pg port=5432 user=jepsen dbname=jepsen connect_timeout=3"))

;; ---------------------------------------------------------------- mTLS
;;
;; Since Zigcube's B7, `zcube-server --listen` serves TLS 1.3 only and wants a
;; client certificate from the store's CA (Zigcube docs/mtls.md); plain HTTP
;; is `--insecure-listen`, on loopback only, which this harness never uses.
;; The server's Ed25519 certificate carries one DNS name, `--tls-name`; every
;; client here checks that name and the CA, whatever address it dials.
;;
;; The server node issues, with `zcube-server ca issue-client|issue-node`
;; against the server's --store and --state-dir, an admin client certificate
;; (`jepsen`) and one node certificate per agent node, under `tls-dir`. The
;; control node downloads them into a directory of its own (mode 0700, files
;; 0600, outside Jepsen's store so no key lands in the results) and uploads
;; each agent node's pair to that node. Keys only ever appear on argv as file
;; paths. A Zigcube from before B7 (no `ca issue-client` in its --help) is
;; run as before, with plain HTTP on --listen.
;;
;; The mode and the control node's copies live in the test's :zcube-tls atom
;; (`tls-state`): nil until the server node has set them up, then
;; {:tls? false} or {:tls? true :dir :ca :cert :key :nodes :ssl-context}, or
;; {:failed e} if the server node's setup failed (the agent nodes, waiting in
;; `await-tls`, then fail at once). Jepsen cannot write an SSLContext, and the
;; key paths have no business in test.jepsen, so every test map that holds
;; the atom also lists it in :nonserializable-keys (`tls-test-keys`).

(def tls-name
  "The DNS name in the server's certificate (zcube-server's default)."
  "zcube-server")
(def tls-dir (str state-dir "/tls"))   ; on the server node and the agents
(def client-name "jepsen")

(defn tls-state
  "The value of a test's :zcube-tls."
  []
  (atom nil))

(defn tls-test-keys
  "What a test map needs for the TLS mode: the :zcube-tls atom, kept out of
  what Jepsen's store writes (it holds an SSLContext, which Fressian cannot
  write, and the control node's key paths)."
  []
  {:zcube-tls           (tls-state)
   :nonserializable-keys #{:zcube-tls}})

(defn- tls-atom [test]
  (or (:zcube-tls test)
      (throw (IllegalStateException. "the test map has no :zcube-tls (zcube/tls-state)"))))

(defn- check-tls [state]
  (when-let [e (:failed state)]
    (throw (ex-info "the server node's setup failed" {:type ::server-setup-failed} e)))
  state)

(defn tls
  "The TLS mode the server node set up; throws if it has not yet, or if its
  setup failed."
  [test]
  (or (check-tls @(tls-atom test))
      (throw (IllegalStateException. "zcube-server's TLS mode is not set up yet"))))

(defn await-tls
  "Waits for the server node to set up the TLS mode, and returns it. Throws at
  once if the server node's setup failed, rather than waiting out the
  timeout."
  ([test] (await-tls test 300000))
  ([test timeout-ms]
   (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
     (loop [logged (System/currentTimeMillis)]
       (if-let [state (check-tls @(tls-atom test))]
         state
         (let [now (System/currentTimeMillis)]
           (when (> now deadline)
             (throw (ex-info "timed out waiting for the server node's certificates"
                             {:type ::tls-timeout :timeout-ms timeout-ms})))
           (when (> (- now logged) 10000)
             (info "waiting for the server node's certificates"))
           (Thread/sleep 500)
           (recur (if (> (- now logged) 10000) now logged))))))))

(defn tls? [test] (:tls? (tls test)))

(defn tls-capable?
  "True if the installed zcube-server issues client certificates (B7 and
  later), so its --listen is mTLS. Run on the server node. `mode` is the
  test's :zcube-tls-mode (--zcube-tls): :on or :off override the guess,
  which reads the top-level --help (bounded, so a binary that ignores --help
  cannot hang setup)."
  ([] (tls-capable? :auto))
  ([mode]
   (case (or mode :auto)
     :on   true
     :off  false
     :auto (-> (c/exec :bash :-c (str "timeout 10 " dir "/zcube-server --help 2>&1 || true"))
               (str/includes? "ca issue-client")))))

(defn base-url
  "Where the control node's clients reach the API."
  [test]
  (str (if (tls? test) "https://" "http://") (server-node test) ":" port))

(def ^:private owner-only
  (PosixFilePermissions/fromString "rw-------"))

(defn- rm-local! [^File f]
  (when (.isDirectory f) (run! rm-local! (.listFiles f)))
  (.delete f))

(defn- local-tls-dir!
  "A fresh directory of the control node's, mode 0700. Removed at teardown,
  on a failed setup, and, failing both (--leave-db-running, an interrupted
  run), when the JVM exits."
  ^File []
  (let [d (.toFile (Files/createTempDirectory
                     "zcube-tls-"
                     (into-array FileAttribute
                                 [(PosixFilePermissions/asFileAttribute
                                    (PosixFilePermissions/fromString "rwx------"))])))]
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. ^Runnable (fn [] (rm-local! d)) "zcube-tls-cleanup"))
    d))

(defn- fetch!
  "Downloads `remote` on this node into `local`, mode 0600."
  [remote ^File local]
  (c/download remote (str local))
  (Files/setPosixFilePermissions (.toPath local) owner-only)
  (str local))

(defn- pem-certs [file]
  (with-open [in (io/input-stream (io/file file))]
    (vec (.generateCertificates (CertificateFactory/getInstance "X.509") in))))

(defn- pem-key
  "An Ed25519 key, PKCS#8 PEM (what `ca issue-*` writes)."
  [file]
  (let [b64 (->> (str/split-lines (slurp file))
                 (remove #(str/starts-with? % "-----"))
                 (apply str))]
    (.generatePrivate (KeyFactory/getInstance "Ed25519")
                      (PKCS8EncodedKeySpec. (.decode (Base64/getMimeDecoder) ^String b64)))))

(defn names-host?
  "True if the certificate's subjectAltName has the DNS name `host`."
  [^X509Certificate cert host]
  (boolean (some (fn [[kind v]] (and (= 2 kind) (= host v)))
                 (.getSubjectAlternativeNames cert))))

(defn- named-trust-manager
  "Checks the server's chain with `pkix` (the CA, no host name), then that its
  leaf names `host`. The address dialled is not what the certificate names
  (it names --tls-name only), so the JDK's own host check, against the URL's
  host, is replaced by this one. Refuses every client chain."
  [^X509ExtendedTrustManager pkix host]
  (let [check (fn [chain auth]
                (.checkServerTrusted pkix chain auth)
                (when-not (names-host? (first chain) host)
                  (throw (CertificateException.
                           (str "the server's certificate does not name " host)))))
        refuse (fn [] (throw (CertificateException. "not a server")))]
    (proxy [X509ExtendedTrustManager] []
      (checkClientTrusted
        ([chain auth] (refuse))
        ([chain auth _] (refuse)))
      (checkServerTrusted
        ([chain auth] (check chain auth))
        ([chain auth _] (check chain auth)))
      (getAcceptedIssuers [] (.getAcceptedIssuers pkix)))))

(defn ssl-context
  "TLS 1.3 with the client certificate `cert`/`key`, trusting the server only
  if the CA `ca` signed it and it names `host`."
  ^SSLContext [ca cert key host]
  (let [trust (doto (KeyStore/getInstance "PKCS12") (.load nil nil))
        _     (.setCertificateEntry trust "ca" (first (pem-certs ca)))
        tmf   (doto (TrustManagerFactory/getInstance "PKIX") (.init trust))
        pkix  (first (filter #(instance? X509ExtendedTrustManager %)
                             (.getTrustManagers tmf)))
        keys  (doto (KeyStore/getInstance "PKCS12") (.load nil nil))
        _     (.setKeyEntry keys "client" (pem-key key) (char-array 0)
                            (into-array Certificate (pem-certs cert)))
        kmf   (doto (KeyManagerFactory/getInstance "SunX509")
                (.init keys (char-array 0)))]
    (doto (SSLContext/getInstance "TLSv1.3")
      (.init (.getKeyManagers kmf)
             (into-array TrustManager [(named-trust-manager pkix host)])
             nil))))

(defn issue!
  "`zcube-server ca issue-client NAME --scope admin` (kind :client) or
  `ca issue-node NAME` (:node) against the server's store, into `out`. Run on
  the server node after the server has made the store's CA."
  [kind name out]
  (c/su
    (c/exec :install :-d :-m "0700" out)
    (apply c/exec (str dir "/zcube-server") :ca
           (concat (case kind
                     :client [:issue-client name :--scope :admin]
                     :node   [:issue-node name])
                   [:--store store-spec :--state-dir server-state-dir
                    :--out out :--replace]))))

(def server-ca
  "The cluster CA certificate of a Postgres store, in the server's state
  directory (Zigcube's server_ca.zig: `ca.crt` for a store with no file)."
  (str server-state-dir "/ca.crt"))

(defn setup-tls!
  "On the server node, once the server has started with --listen: issues the
  certificates, copies them to the control node, and returns the TLS mode."
  [test]
  (util/await-fn (fn [] (c/exec :test :-s server-ca))
                 {:retry-interval 200 :timeout 60000
                  :log-message "waiting for the store's CA"})
  (c/su (c/exec :install :-d :-m "0700" tls-dir)
        (c/exec :install :-m "0644" server-ca (str tls-dir "/ca.crt")))
  (issue! :client client-name (str tls-dir "/client"))
  (doseq [n (agent-nodes test)]
    (issue! :node n (str tls-dir "/nodes")))
  (let [local (local-tls-dir!)
        get!  (fn [remote local-name] (fetch! remote (io/file local local-name)))]
    (try
      (let [ca    (get! (str tls-dir "/ca.crt") "ca.crt")
            cert  (get! (str tls-dir "/client/" client-name ".crt") "client.crt")
            key   (get! (str tls-dir "/client/" client-name ".key") "client.key")
            nodes (into {} (for [n (agent-nodes test)]
                             [n {:cert (get! (str tls-dir "/nodes/" n ".crt") (str n ".crt"))
                                 :key  (get! (str tls-dir "/nodes/" n ".key") (str n ".key"))}]))]
        {:tls?        true
         :dir         (str local)
         :ca          ca
         :cert        cert
         :key         key
         :nodes       nodes
         :ssl-context (ssl-context ca cert key tls-name)})
      (catch Throwable t
        (rm-local! local)
        (throw t)))))

(defn install-node-tls!
  "On an agent node: its node certificate, key and the CA, from the control
  node's copies, into `tls-dir` (0700; the key 0600)."
  [test node]
  (let [{:keys [ca nodes]} (tls test)
        {:keys [cert key]} (get nodes node)]
    (c/su (c/exec :install :-d :-m "0700" tls-dir))
    (c/upload ca (str tls-dir "/ca.crt"))
    (c/upload cert (str tls-dir "/node.crt"))
    (c/upload key (str tls-dir "/node.key"))
    (c/su (c/exec :chmod "0600" (str tls-dir "/node.key") (str tls-dir "/node.crt")))))

(defn teardown-tls!
  "Removes the control node's copies, and sets the TLS mode to `state` (nil:
  not set up)."
  ([test] (teardown-tls! test nil))
  ([test state]
   (when-let [{:keys [dir]} @(tls-atom test)]
     (when dir (rm-local! (io/file dir))))
   (reset! (tls-atom test) state)))

(defn start-server!
  "`tls?`: the binary serves mTLS on --listen (`tls-capable?`)."
  [test tls?]
  (c/su
    (apply cu/start-daemon!
      {:logfile server-log :pidfile server-pid :chdir dir}
      (str dir "/zcube-server")
      (concat
        [:--listen (str "0.0.0.0:" port)]
        ; The certificate the agents and the clients check names --tls-name.
        ; No --tls-san: that is the browser path's P-256 certificate, which
        ; the JDK and curl (both offer ed25519) never see.
        (when tls? [:--tls-name tls-name])
        [:--store store-spec
         :--state-dir server-state-dir
         :--lease-ttl-ms (:lease-ttl-ms test 10000)
         :--lost-grace-ms (:lost-grace-ms test 1000)
         :--create-store]))))

(defn stop-server! []
  (c/su (cu/stop-daemon! (str dir "/zcube-server") server-pid)))

(def node-status-code
  "What /v1/status answers a node certificate: 403 with this code (a node may
  call its own paths only, Zigcube docs/mtls.md)."
  "ZC-E0822")

(defn await-server-cmd
  "The shell command `await-server!` retries: exits 0 once the server is up.
  Over mTLS the server node presents the admin certificate and wants a 2xx;
  an agent node presents its node certificate, which /v1/status refuses, so
  there only that exact refusal (403 `node-status-code`) means the server is
  up and took the node's certificate as a node's."
  [tls? server? ip]
  (if-not tls?
    (str "curl -fsS -m 3 -o /dev/null http://" ip ":" port "/v1/status")
    (let [[cert key] (if server?
                       [(str tls-dir "/client/" client-name ".crt")
                        (str tls-dir "/client/" client-name ".key")]
                       [(str tls-dir "/node.crt") (str tls-dir "/node.key")])
          curl (str "curl -sS -m 3 --noproxy '*' -w '\\n%{http_code}'"
                    " --cacert " tls-dir "/ca.crt"
                    " --cert " cert " --key " key
                    " --resolve " tls-name ":" port ":" ip
                    " https://" tls-name ":" port "/v1/status")]
      (str "out=$(" curl ") || exit 1; code=${out##*$'\\n'}; echo \"HTTP $code\"; "
           (if server?
             "[ \"$code\" -ge 200 ] && [ \"$code\" -lt 300 ]"
             (str "[ \"$code\" = 403 ] && case \"$out\" in *" node-status-code
                  "*) true;; *) false;; esac"))))))

(defn await-server!
  "Blocks until the server answers /v1/status (`await-server-cmd`). Run on
  the server node or an agent node."
  [test]
  (let [cmd (await-server-cmd (tls? test) (= c/*host* (server-node test))
                              (cn/ip (server-node test)))]
    (util/await-fn
      (fn [] (c/su (c/exec :bash :-c cmd)))
      {:retry-interval 500 :log-interval 5000 :timeout 90000
       :log-message "waiting for zcube-server"})))

(defn diagnose-server!
  "Logs what the server node looks like, for a server that never answered.
  Run on the server node."
  []
  (doseq [cmd ["ps -eo pid,stat,etime,args | grep -E '[z]cube|[p]sql' || true"
               "(ss -ltnp 2>/dev/null || true) | head -20"
               "cat /etc/hosts"
               "getent hosts pg || true"
               "tail -n 20 /var/lib/zj/server.log || true"]]
    (warn "server diagnostics:" cmd "\n"
          (try (c/su (c/exec :bash :-c cmd))
               (catch Exception e (.getMessage e))))))

(defn agent-tls-args
  "zcube-agent's mTLS flags, or none for a server without TLS."
  [test]
  (when (tls? test)
    [:--ca (str tls-dir "/ca.crt")
     :--cert (str tls-dir "/node.crt")
     :--key (str tls-dir "/node.key")
     :--tls-name tls-name]))

(defn start-agent!
  ([test node] (start-agent! test node (str (cn/ip (server-node test)) ":" port)))
  ([test node server]
   (c/su
     (apply cu/start-daemon!
       {:logfile agent-log :pidfile agent-pid :chdir dir}
       (str dir "/zcube-agent")
       :--node node
       :--server server
       :--state-dir (str state-dir "/agent")
       :--cpu-millis 4000
       :--memory-bytes 8589934592
       :--images (str dir "/images.zon")
       (agent-tls-args test)))))

(defn kill-all!
  "Kills the server, the agent and everything the fake runtime started."
  []
  (c/su (c/exec :bash :-c
                ; [z]: so that -f does not match this very shell.
                (str "pkill -9 -x zcube-server; pkill -9 -x zcube-agent; "
                     "pkill -9 -f '[z]cube-agent supervise'; "
                     "pkill -9 -f '[z]j-rec.sh'; true"))))

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
      (pg/flush-rules!)
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
              (pg/await-standby! test "pg")
              (let [tls? (tls-capable? (:zcube-tls-mode test))]
                (info "zcube-server" (if tls? "speaks mTLS on --listen" "has no TLS (before B7)"))
                (try (start-server! test tls?)
                     ; Set only once complete: the agent nodes wait for it.
                     (reset! (tls-atom test) (if tls? (setup-tls! test) {:tls? false}))
                     (await-server! test)
                     (catch Exception e
                       (diagnose-server!)
                       ; Remove the control node's keys, and let the agent
                       ; nodes fail now rather than at await-tls's timeout.
                       (teardown-tls! test {:failed e})
                       (throw e)))))

          :else
          (do (kill-all!)
              (c/su (c/exec :rm :-rf state-dir))
              (install! test :agent)
              (when (:tls? (await-tls test))
                (install-node-tls! test node))
              (await-server! test)
              (start-agent! test node)))))

    (teardown! [_ test node]
      (if (some #{node} (pg/pg-nodes test))
        (pg/wipe!)
        (do (kill-all!)
            (c/su (c/exec :rm :-rf state-dir))
            (when (= node (server-node test))
              (teardown-tls! test)))))

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
