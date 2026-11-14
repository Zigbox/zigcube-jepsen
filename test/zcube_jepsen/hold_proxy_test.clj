(ns zcube-jepsen.hold-proxy-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [zcube-jepsen.hold-proxy :as hp])
  (:import (java.io ByteArrayInputStream)
           (java.net InetSocketAddress ServerSocket Socket)
           (java.nio.charset StandardCharsets)))

(defn- bytes-of ^bytes [^String s] (.getBytes s StandardCharsets/ISO_8859_1))
(defn- text [^bytes b] (String. b StandardCharsets/UTF_8))

(deftest parse-head-test
  (is (= {:method "PUT" :path "/v1/agent/nodes/n4?known=0" :content-length 12 :chunked? nil}
         (hp/parse-head "PUT /v1/agent/nodes/n4?known=0 HTTP/1.1\r\nHost: x\r\nContent-Length: 12\r\n\r\n")))
  (is (= {:method "POST" :path "/a" :content-length nil :chunked? true}
         (hp/parse-head "POST /a HTTP/1.1\r\ntransfer-encoding: gzip, Chunked\r\n\r\n"))))

(deftest registration?-test
  (testing "the node's registration, with or without a query"
    (is (hp/registration? {:method "PUT" :path "/v1/agent/nodes/n4"} "n4"))
    (is (hp/registration? {:method "PUT" :path "/v1/agent/nodes/n4?known=0"} "n4")))
  (testing "anything else is not"
    (is (not (hp/registration? {:method "POST" :path "/v1/agent/nodes/n4"} "n4")))
    (is (not (hp/registration? {:method "PUT" :path "/v1/agent/nodes/n5"} "n4")))
    (is (not (hp/registration? {:method "PUT" :path "/v1/agent/nodes/n4/store?known=1"} "n4")))
    (is (not (hp/registration? {:method "PUT" :path "/v1/agent/nodes/n4x"} "n4")))
    (is (not (hp/registration? {:method "PUT" :path "/v1/apply"} "n4")))))

(deftest read-chunked-test
  (let [wire "4\r\nWiki\r\n6;ext=1\r\npedia \r\nE\r\nin \r\n\r\nchunks.\r\n0\r\nX-T: 1\r\n\r\nNEXT"
        in (ByteArrayInputStream. (bytes-of wire))
        {:keys [raw data]} (hp/read-chunked in)]
    (is (= "Wikipedia in \r\n\r\nchunks." (text data)))
    (is (= (subs wire 0 (- (count wire) 4)) (String. ^bytes raw StandardCharsets/ISO_8859_1)))
    (is (= "NEXT" (String. (.readAllBytes in) StandardCharsets/ISO_8859_1))
        "nothing past the trailer is consumed"))
  (is (thrown? java.io.EOFException
               (hp/read-chunked (ByteArrayInputStream. (bytes-of "5\r\nab"))))))

(deftest status-tracker-test
  (let [st (atom nil) f (hp/status-tracker st)]
    (f (bytes-of "HTTP/1") 6)
    (is (nil? @st))
    (f (bytes-of ".1 40") 5)
    (f (bytes-of "9 Conflict\r\n") 12)
    (is (= 409 @st))))

;; ------------------------------------------------------------ loopback

(defn- read-request
  "Head text and decoded body of one request from `in`."
  [in]
  (let [head (#'hp/read-head in)]
    (when head
      (let [h (String. ^bytes head StandardCharsets/ISO_8859_1)]
        {:head h :body (text (:data (hp/read-body in (hp/parse-head h))))}))))

(defn- stub-server
  "A server on a free loopback port. (answer req) gives the bytes to send, as
  a seq of strings written with a pause between them. Records the requests."
  [answer]
  (let [ss (ServerSocket. 0 50 (java.net.InetAddress/getLoopbackAddress))
        reqs (atom [])
        t (Thread.
            ^Runnable
            (fn []
              (while (not (.isClosed ss))
                (try
                  (let [s (.accept ss)]
                    (future
                      (with-open [s s]
                        (when-let [r (read-request (.getInputStream s))]
                          (swap! reqs conj r)
                          (let [o (.getOutputStream s)]
                            (doseq [part (answer r)]
                              (.write o (bytes-of part)) (.flush o) (Thread/sleep 20))
                            ; Keep the connection open a while, as a
                            ; keep-alive server would.
                            (Thread/sleep 300))))))
                  (catch Exception _)))))]
    (.setDaemon t true) (.start t)
    {:ss ss :port (.getLocalPort ss) :reqs reqs}))

(defn- send-request!
  "Sends raw text to the proxy; returns the open socket."
  ^Socket [port ^String raw]
  (let [s (Socket.)]
    (.connect s (InetSocketAddress. "127.0.0.1" (int port)) 2000)
    (.setSoTimeout s 3000)
    (doto (.getOutputStream s) (.write (bytes-of raw)) (.flush))
    s))

(defn- await-true [f]
  (loop [i 0] (cond (f) true (< i 100) (do (Thread/sleep 20) (recur (inc i))) :else false)))

(defn- chunked [^String s] (format "%x\r\n%s\r\n" (count s) s))

(def reg-body "{\"lease\":1}")
(def reg-request
  (str "PUT /v1/agent/nodes/n4?known=0 HTTP/1.1\r\nHost: s\r\nContent-Length: "
       (count reg-body) "\r\n\r\n" reg-body))

(deftest hold-and-release-test
  (let [stub (stub-server
               (fn [r]
                 (if (str/starts-with? (:head r) "PUT /v1/agent/nodes/n4")
                   ; Chunked, split over writes, connection kept open.
                   ["HTTP/1.1 409 Conflict\r\nTransfer-Encoding: chunked\r\n\r\n"
                    (chunked "{\"diagnostics\":[")
                    (str (chunked "{\"code\":\"ZC-E0105\"}]}      ") "0\r\n\r\n")]
                   ["HT" "TP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok"])))
        proxy (hp/start! 0 ["127.0.0.1" (:port stub)] "n4")
        port (.getLocalPort ^ServerSocket (:server proxy))]
    (try
      (testing "unarmed, a registration is relayed"
        (with-open [s (send-request! port reg-request)]
          (is (str/starts-with? (String. (.readAllBytes (.getInputStream s))) "HTTP/1.1 409")))
        (is (nil? (hp/held proxy))))
      (testing "other requests are relayed, their status read across split reads"
        (with-open [s (send-request! port "GET /v1/agent/nodes/n4/assigned HTTP/1.1\r\n\r\n")]
          (is (str/ends-with? (String. (.readAllBytes (.getInputStream s))) "ok")))
        (is (await-true #(some (fn [r] (and (= "/v1/agent/nodes/n4/assigned" (:path r))
                                            (= 200 (:status r))))
                               (hp/requests proxy)))))
      (testing "armed, the next registration is held and not answered"
        (hp/arm! proxy)
        (let [before (count @(:reqs stub))]
          (with-open [s (send-request! port reg-request)]
            (is (await-true #(hp/held proxy)))
            (is (= reg-body (:body (hp/held proxy))))
            (is (thrown? java.net.SocketTimeoutException
                         (do (.setSoTimeout s 300) (.read (.getInputStream s)))))
            (is (= before (count @(:reqs stub))) "the server never saw it")
            (testing "a second registration is not held: holding is one-shot"
              (with-open [s2 (send-request! port reg-request)]
                (is (str/starts-with? (String. (.readAllBytes (.getInputStream s2)))
                                      "HTTP/1.1 409"))))
            (testing "release! delivers the held bytes and reads a chunked answer"
              (let [n (count @(:reqs stub))
                    r (hp/release! proxy)]
                (is (= 409 (:status r)))
                (is (= "{\"diagnostics\":[{\"code\":\"ZC-E0105\"}]}      " (:body r)))
                (is (<= (:sent r) (:answered r)))
                (is (< (- (:answered r) (:sent r)) 250)
                    "read by framing, not until the server closes")
                (is (await-true #(= (inc n) (count @(:reqs stub)))))
                (is (= reg-body (:body (last @(:reqs stub)))))
                (is (some :released (hp/requests proxy))))))))
      (finally
        (hp/stop! proxy)
        (.close ^ServerSocket (:ss stub))))))
