(ns zcube-jepsen.zcube-test
  "The mTLS client (B7+) without a Zigcube: the test map Jepsen writes, the
  JDK client's check of the server's CA and name against an in-process TLS
  1.3 server with openssl-made Ed25519 certificates, the readiness probe's
  shell logic, the fail-fast TLS state, and the api workload's value cap.
  Needs openssl and bash on the path."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :refer [sh]]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.tools.cli :as tcli]
            [jepsen.cli :as jcli]
            [jepsen.store :as store]
            [jepsen.store.format :as fmt]
            [zcube-jepsen.api :as api]
            [zcube-jepsen.core :as core]
            [zcube-jepsen.stale-put :as stale-put]
            [zcube-jepsen.zcube :as zcube])
  (:import (java.io File)
           (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)
           (java.security KeyStore)
           (java.security.cert Certificate X509Certificate)
           (javax.net.ssl KeyManagerFactory SSLContext SSLServerSocket
                          SSLSocket TrustManagerFactory)))

;; ---------------------------------------------------------------- PKI

(defn- temp-dir ^File [prefix]
  (.toFile (Files/createTempDirectory prefix (make-array FileAttribute 0))))

(defn- rm-rf [^File f]
  (when (.isDirectory f) (run! rm-rf (.listFiles f)))
  (.delete f))

(defn- openssl [dir & args]
  (let [{:keys [exit err]} (apply sh "openssl" (concat args [:dir (str dir)]))]
    (when-not (zero? exit)
      (throw (ex-info (str "openssl " (first args) " failed: " err) {})))))

(defn- ca! [dir n]
  (openssl dir "genpkey" "-algorithm" "ed25519" "-out" (str n ".key"))
  (openssl dir "req" "-x509" "-new" "-key" (str n ".key") "-subj" (str "/CN=" n)
           "-days" "2" "-out" (str n ".crt")
           "-addext" "basicConstraints=critical,CA:TRUE"
           "-addext" "keyUsage=critical,keyCertSign"))

(defn- leaf!
  "An Ed25519 leaf `n` signed by CA `ca`, with `ext` (openssl x509v3 lines)."
  [dir ca n ext]
  (spit (io/file dir (str n ".ext")) (str/join "\n" ext))
  (openssl dir "genpkey" "-algorithm" "ed25519" "-out" (str n ".key"))
  (openssl dir "req" "-new" "-key" (str n ".key") "-subj" (str "/CN=" n)
           "-out" (str n ".csr"))
  (openssl dir "x509" "-req" "-in" (str n ".csr") "-CA" (str ca ".crt")
           "-CAkey" (str ca ".key") "-set_serial" (str (rand-int 1000000))
           "-days" "2" "-extfile" (str n ".ext") "-out" (str n ".crt")))

(def ^:dynamic *pki* nil)

(defn- path [n] (str (io/file *pki* n)))

(defn- with-pki [t]
  (let [d (temp-dir "zj-pki")]
    (try
      (ca! d "ca")
      (ca! d "other-ca")
      (leaf! d "ca" "good" ["subjectAltName=DNS:zcube-server"
                            "extendedKeyUsage=serverAuth"])
      (leaf! d "ca" "wrong-name" ["subjectAltName=DNS:other-name"
                                  "extendedKeyUsage=serverAuth"])
      (leaf! d "other-ca" "foreign" ["subjectAltName=DNS:zcube-server"
                                     "extendedKeyUsage=serverAuth"])
      (leaf! d "ca" "jepsen" ["extendedKeyUsage=clientAuth"])
      (binding [*pki* d] (t))
      (finally (rm-rf d)))))

(use-fixtures :once with-pki)

(defn- client-context ^SSLContext []
  (zcube/ssl-context (path "ca.crt") (path "jepsen.crt") (path "jepsen.key")
                     zcube/tls-name))

;; ---------------------------------------------------------------- a server

(defn- server-context
  "A TLS server with leaf `n`, which wants a client certificate from `ca`."
  ^SSLContext [n]
  (let [ks  (doto (KeyStore/getInstance "PKCS12") (.load nil nil))
        _   (.setKeyEntry ks "server" (#'zcube/pem-key (path (str n ".key")))
                          (char-array 0)
                          (into-array Certificate (#'zcube/pem-certs (path (str n ".crt")))))
        kmf (doto (KeyManagerFactory/getInstance "SunX509") (.init ks (char-array 0)))
        ts  (doto (KeyStore/getInstance "PKCS12") (.load nil nil))
        _   (.setCertificateEntry ts "ca" (first (#'zcube/pem-certs (path "ca.crt"))))
        tmf (doto (TrustManagerFactory/getInstance "PKIX") (.init ts))]
    (doto (SSLContext/getInstance "TLSv1.3")
      (.init (.getKeyManagers kmf) (.getTrustManagers tmf) nil))))

(defn- serve-once
  "Serves one HTTPS request with leaf `n` (TLS 1.3, client certificate
  required), answering 200. Returns [port result-future]; the future yields
  {:client-cn ...} or {:error ...}."
  [n]
  (let [^SSLServerSocket ss (.createServerSocket
                              (.getServerSocketFactory (server-context n))
                              0 1 (java.net.InetAddress/getLoopbackAddress))]
    (.setEnabledProtocols ss (into-array String ["TLSv1.3"]))
    (.setNeedClientAuth ss true)
    (.setSoTimeout ss 10000)
    [(.getLocalPort ss)
     (future
       (try
         (with-open [^SSLSocket s (.accept ss)]
           (.setSoTimeout s 10000)
           (.startHandshake s)
           (let [peer ^X509Certificate (first (.getPeerCertificates (.getSession s)))
                 in   (io/reader (.getInputStream s))]
             (loop [] (when-not (str/blank? (.readLine in)) (recur)))
             (doto (.getOutputStream s)
               (.write (.getBytes (str "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n"
                                       "Content-Length: 2\r\nConnection: close\r\n\r\n{}")))
               (.flush))
             {:client-cn (str (.getSubjectX500Principal peer))}))
         (catch Exception e {:error (str e)})
         (finally (.close ss))))]))

(defn- call
  "GET /v1/status at 127.0.0.1:port through api/http-client, as the api and
  lease clients do. Returns the status or the exception."
  [port]
  (let [test {:zcube-tls (atom {:tls? true :ssl-context (client-context)})}]
    (try (:status (api/request (api/http-client test)
                               (str "https://127.0.0.1:" port) :get "/v1/status" nil))
         (catch Exception e e))))

(defn- cause-messages [^Throwable e]
  (->> (iterate #(.getCause ^Throwable %) e)
       (take-while some?)
       (map #(str (.getMessage ^Throwable %)))
       (str/join " | ")))

(deftest names-host
  (let [cert #(first (#'zcube/pem-certs (path %)))]
    (is (zcube/names-host? (cert "good.crt") "zcube-server"))
    (is (not (zcube/names-host? (cert "wrong-name.crt") "zcube-server")))
    (is (not (zcube/names-host? (cert "good.crt") "127.0.0.1")))))

(deftest server-name-and-ca-are-checked
  (testing "the right CA and name: 200, with the client certificate presented"
    (let [[port result] (serve-once "good")]
      (is (= 200 (call port)))
      (is (re-find #"CN=jepsen" (str (:client-cn @result))) (pr-str @result))))
  (testing "the right CA, the wrong name: refused by the name check"
    (let [[port result] (serve-once "wrong-name")
          r (call port)]
      (is (instance? Exception r) (pr-str r))
      (when (instance? Exception r)
        (is (str/includes? (cause-messages r) "does not name zcube-server")
            (cause-messages r)))
      @result))
  (testing "the right name from a foreign CA: refused by PKIX"
    (let [[port result] (serve-once "foreign")
          r (call port)]
      (is (instance? Exception r) (pr-str r))
      (when (instance? Exception r)
        (is (not (str/includes? (cause-messages r) "does not name"))
            (cause-messages r)))
      @result)))

;; ---------------------------------------------------------------- test.jepsen

(def nodes ["n1" "n2" "n3" "n4" "n5"])

(defn- defaults [spec]
  (let [{:keys [options errors]} (tcli/parse-opts [] (concat jcli/test-opt-spec spec))]
    (assert (empty? errors) (pr-str errors))
    (assoc options :nodes nodes :time-limit 10)))

(defn- tests []
  (let [opts (defaults core/cli-opts)]
    {"api"       (core/register-test (assoc opts :workload "api"))
     "lease"     (core/lease-test (assoc opts :workload "lease" :expect :valid))
     "stale-put" (stale-put/stale-put-test (defaults stale-put/cli-opts))}))

(defn- fill!
  "The :zcube-tls of a B7+ run once the server node has set it up."
  [test]
  (reset! (:zcube-tls test)
          {:tls?        true
           :dir         "/tmp/zcube-tls-x"
           :ca          "/tmp/zcube-tls-x/ca.crt"
           :cert        "/tmp/zcube-tls-x/client.crt"
           :key         "/tmp/zcube-tls-x/client.key"
           :nodes       {"n4" {:cert "/tmp/zcube-tls-x/n4.crt"
                               :key  "/tmp/zcube-tls-x/n4.key"}}
           :ssl-context (client-context)})
  test)

(defn- write!
  "Writes `stest` as jepsen.core/run! does (write-initial-test! at the start,
  write-test-with-history! in save-1!), and reads it back."
  [file stest]
  (let [h (fmt/open file)]
    (try
      (let [t (fmt/write-initial-test! h stest)]
        (fmt/write-test-with-history! h t))
      (finally (fmt/close! h))))
  (let [h (fmt/open file)]
    (try (into {} (fmt/read-test h))
         (finally (fmt/close! h)))))

(deftest test-map-is-writable
  (let [d (temp-dir "zj-store")]
    (try
      (doseq [[wl t] (tests)]
        (testing wl
          (fill! t)
          (is (contains? (store/nonserializable-keys t) :zcube-tls))
          (testing "(control: the filled atom itself is not writable)"
            (is (thrown? Exception
                         (write! (io/file d (str wl "-raw.jepsen"))
                                 (apply dissoc t store/default-nonserializable-keys)))))
          (let [back (write! (io/file d (str wl ".jepsen")) (store/serializable-test t))]
            (is (= (:name t) (:name back)))
            (is (not (contains? back :zcube-tls)))
            (is (not (str/includes? (pr-str back) "zcube-tls-x"))))))
      (finally (rm-rf d)))))

;; ---------------------------------------------------------------- await

(defn- probe
  "Runs await-server-cmd with a stub curl that answers `body` and `code` (or
  fails with `exit`). Returns the command's exit status."
  [tls? server? {:keys [body code exit]}]
  (let [d (temp-dir "zj-curl")]
    (try
      (spit (io/file d "curl")
            (if exit
              (str "#!/bin/bash\nexit " exit "\n")
              ; As `curl -w '\n%{http_code}'`: the body, a newline, the code.
              (str "#!/bin/bash\nprintf '%s\\n%s' '" body "' '" code "'\n")))
      (.setExecutable (io/file d "curl") true)
      (:exit (sh "bash" "-c" (zcube/await-server-cmd tls? server? "10.0.0.3")
                 :env {"PATH" (str d ":" (System/getenv "PATH"))}))
      (finally (rm-rf d)))))

(deftest await-server-readiness
  (let [refusal (str "{\"diagnostics\":[{\"code\":\"" zcube/node-status-code "\"}]}")]
    (testing "an agent node wants exactly the node's 403"
      (is (= 0 (probe true false {:body refusal :code 403})))
      (is (not= 0 (probe true false {:body "{}" :code 403})))
      (is (not= 0 (probe true false {:body refusal :code 401})))
      (is (not= 0 (probe true false {:body "{}" :code 404})))
      (is (not= 0 (probe true false {:body "{}" :code 200})))
      (is (not= 0 (probe true false {:exit 35}))))
    (testing "the server node wants a 2xx"
      (is (= 0 (probe true true {:body "{}" :code 200})))
      (is (not= 0 (probe true true {:body refusal :code 403})))
      (is (not= 0 (probe true true {:exit 60}))))
    (testing "the server-node and agent commands check zcube-server's name"
      (doseq [server? [true false]]
        (let [cmd (zcube/await-server-cmd true server? "10.0.0.3")]
          (is (str/includes? cmd "--resolve zcube-server:7070:10.0.0.3"))
          (is (str/includes? cmd "--noproxy '*'"))
          (is (not (str/includes? cmd " -k"))))))))

;; ---------------------------------------------------------------- TLS state

(deftest failed-setup-fails-fast
  (let [d    (doto (temp-dir "zcube-tls-") (-> (io/file "client.key") (spit "x")))
        test {:zcube-tls (atom {:tls? true :dir (str d)})}]
    (zcube/teardown-tls! test {:failed (ex-info "boom" {})})
    (is (not (.exists d)) "the control node's copies are removed")
    (let [t0 (System/currentTimeMillis)
          e  (try (zcube/await-tls test) nil (catch Exception e e))]
      (is (some? e))
      (is (< (- (System/currentTimeMillis) t0) 2000) "not the 300 s timeout")
      (is (= "boom" (some-> e .getCause .getMessage))))
    (is (thrown? Exception (zcube/tls test)))
    (zcube/teardown-tls! test)
    (is (nil? @(:zcube-tls test)))
    (testing "an unset state times out"
      (is (thrown? Exception (zcube/await-tls test 600))))))

(deftest tls-mode-override
  (is (true? (zcube/tls-capable? :on)))
  (is (false? (zcube/tls-capable? :off)))
  (is (= :on (:zcube-tls-mode (:options (tcli/parse-opts ["--zcube-tls" "on"] core/cli-opts)))))
  (is (seq (:errors (tcli/parse-opts ["--zcube-tls" "maybe"] core/cli-opts)))))

;; ---------------------------------------------------------------- api values

(deftest api-values-stay-below-the-replica-cap
  (let [[r w] (core/capped-ops core/api-value-limit)
        ops   (loop [out []]
                (let [op (if (even? (count out)) (w nil nil) (r nil nil))]
                  (if op (recur (conj out op)) out)))
        vals  (keep #(when (= :write (:f %)) (:value %)) ops)]
    (is (= (range core/api-value-limit) vals) "0..127, each once")
    (is (every? #(< % 128) vals))
    (is (nil? (w nil nil)) "the writes are used up")
    (is (nil? (r nil nil)) "and so are the key's reads: the key ends")))
