(ns zcube-jepsen.hold-proxy
  "A TCP proxy between one agent and zcube-server that can hold one
  registration PUT and deliver it later.

  It runs in the Jepsen control process; the agent is started with
  `--server <control host>:<port>`. Zigcube's agent makes one request per
  connection, so each connection is one request. The proxy reads the request
  head; if holding is armed and the request is the agent's registration
  (`PUT /v1/agent/nodes/<node>`, nothing after the node), it reads the body,
  keeps the bytes and never answers: the agent waits, as it would for a
  server that is slow to commit. Every other request is relayed both ways
  unchanged. `release!` later sends the held bytes to the server on a fresh
  connection, which is what the server sees when a request it was holding
  (a thread waiting for a pool connection or the revision lock,
  zigcube-tla FINDINGS.md finding 1) finally commits.

  Every request is logged with its arrival time, method, path, the status of
  the server's answer, and when the answer ended (ms, wall clock of the
  control host, which the containers share)."
  (:require [clojure.string :as str]
            [clojure.tools.logging :refer [info warn]])
  (:import (java.io ByteArrayOutputStream InputStream OutputStream)
           (java.net InetSocketAddress ServerSocket Socket SocketException)
           (java.nio.charset StandardCharsets)))

(defn now-ms [] (System/currentTimeMillis))

(defn- read-head
  "Reads up to and including the blank line that ends an HTTP head. Returns
  the bytes, or nil on EOF before it."
  ^bytes [^InputStream in]
  (let [buf (ByteArrayOutputStream.)]
    (loop [last4 0]
      (let [b (.read in)]
        (cond
          (neg? b) nil
          :else (let [_ (.write buf b)
                      l (bit-and (bit-or (bit-shift-left last4 8) b) 0xffffffff)]
                  (if (= l 0x0d0a0d0a)
                    (.toByteArray buf)
                    (if (> (.size buf) 65536) nil (recur l)))))))))

(defn- head-text ^String [^bytes head] (String. head StandardCharsets/ISO_8859_1))

(defn parse-head
  "{:method :path :content-length :chunked?} from a request head's text."
  [^String text]
  (let [lines (str/split-lines text)
        [method path] (str/split (first lines) #" ")
        header (fn [h] (some (fn [l]
                               (let [[k v] (str/split l #":" 2)]
                                 (when (and v (= h (str/lower-case (str/trim k))))
                                   (str/trim v))))
                             (rest lines)))]
    {:method method
     :path   path
     :content-length (some-> (header "content-length") parse-long)
     :chunked? (some-> (header "transfer-encoding") str/lower-case (str/includes? "chunked"))}))

(defn registration?
  "The agent's registration of `node`: PUT /v1/agent/nodes/<node>, exactly."
  [{:keys [method path]} node]
  (and (= "PUT" method)
       (= (str "/v1/agent/nodes/" node) (first (str/split (str path) #"\?")))))

(defn- read-line-bytes
  "Reads one CRLF-terminated line into `raw`; returns it without the CRLF,
  or nil on EOF."
  ^String [^InputStream in ^ByteArrayOutputStream raw]
  (let [line (ByteArrayOutputStream.)]
    (loop [prev -1]
      (let [b (.read in)]
        (cond
          (neg? b) nil
          :else (do (.write raw b)
                    (if (and (= b 10) (= prev 13))
                      (let [bs (.toByteArray line)]
                        (String. bs 0 (max 0 (dec (alength bs))) StandardCharsets/ISO_8859_1))
                      (do (.write line b) (recur b)))))))))

(defn read-chunked
  "Reads a chunked body (RFC 9112 7.1) through its last chunk and trailer.
  Returns {:raw bytes-as-sent :data decoded-bytes}."
  [^InputStream in]
  (let [raw (ByteArrayOutputStream.)
        data (ByteArrayOutputStream.)
        eof #(throw (java.io.EOFException. "chunked body cut short"))]
    (loop []
      (let [line (or (read-line-bytes in raw) (eof))
            size (Long/parseLong (str/trim (first (str/split line #";" 2))) 16)]
        (if (zero? size)
          ; Trailer fields, up to the empty line.
          (loop [] (let [l (or (read-line-bytes in raw) (eof))]
                     (when-not (str/blank? l) (recur))))
          (let [bs (.readNBytes in (int size))]
            (when (< (alength bs) size) (eof))
            (.write raw bs) (.write data bs)
            (when-not (= "" (read-line-bytes in raw)) (eof))
            (recur)))))
    {:raw (.toByteArray raw) :data (.toByteArray data)}))

(defn read-body
  "The body that follows a head: {:raw bytes-as-sent :data decoded-bytes}.
  content-length bytes, a chunked body through its trailer, or nothing."
  [^InputStream in {:keys [content-length chunked?]}]
  (cond
    chunked?       (read-chunked in)
    content-length (let [bs (.readNBytes in (int content-length))]
                     {:raw bs :data bs})
    :else          {:raw (byte-array 0) :data (byte-array 0)}))

(defn- pump!
  "Copies in to out until EOF, calling (on-data bytes n) on every read.
  Closes out's socket for writing at the end."
  [^InputStream in ^OutputStream out on-data]
  (let [buf (byte-array 16384)]
    (loop []
      (let [n (try (.read in buf) (catch SocketException _ -1))]
        (when (pos? n)
          (on-data buf n)
          (when (try (.write out buf 0 n) (.flush out) true
                     (catch SocketException _ false))
            (recur)))))))

(defn status-of
  "The status code at the start of an HTTP answer's bytes, or nil."
  [^bytes buf n]
  (let [s (String. buf 0 (int (min n 32)) StandardCharsets/ISO_8859_1)]
    (some-> (re-find #"^HTTP/1\.[01] (\d{3})" s) second parse-long)))

(defn status-tracker
  "An (on-data bytes n) for pump! that gathers the answer's first bytes,
  however the reads split them, until `status` (an atom) is set."
  [status]
  (let [prefix (ByteArrayOutputStream.)]
    (fn [^bytes buf n]
      (when (and (nil? @status) (< (.size prefix) 32))
        (.write prefix buf 0 (int (min n (- 32 (.size prefix)))))
        (let [bs (.toByteArray prefix)]
          (when-let [st (status-of bs (alength bs))]
            (reset! status st)))))))

(defn- log! [proxy entry] (swap! (:log proxy) conj entry))

(defn- relay!
  [proxy ^Socket client ^bytes head req t0]
  (let [[host port] (:upstream proxy)
        up     (Socket.)
        status (atom nil)]
    (try
      (.connect up (InetSocketAddress. ^String host (int port)) 3000)
      (let [uo (.getOutputStream up)]
        (.write uo head)
        (.flush uo)
        (let [t (Thread. ^Runnable
                         (fn []
                           (pump! (.getInputStream client) uo (fn [_ _] nil))
                           (try (.shutdownOutput up) (catch Exception _))))]
          (.setDaemon t true)
          (.start t)
          (pump! (.getInputStream up) (.getOutputStream client)
                 (status-tracker status))))
      (catch Exception e
        (reset! status [:error (.getName (class e))]))
      (finally
        (.close up)
        (.close client)
        (log! proxy (assoc req :at t0 :status @status :end (now-ms)))))))

(defn- handle!
  [proxy ^Socket client]
  (let [t0   (now-ms)
        in   (.getInputStream client)
        head (try (read-head in) (catch Exception _ nil))]
    (if-not head
      (.close client)
      (let [req (select-keys (parse-head (head-text head)) [:method :path])
            req-full (parse-head (head-text head))
            ^bytes head head
            held (:held proxy)]
        (if (and (registration? req-full (:node proxy))
                 (compare-and-set! (:armed proxy) true false))
          (let [{:keys [^bytes raw ^bytes data]} (read-body in req-full)
                all  (byte-array (+ (alength head) (alength raw)))
                text (String. data StandardCharsets/UTF_8)]
            (System/arraycopy head 0 all 0 (alength head))
            (System/arraycopy raw 0 all (alength head) (alength raw))
            (reset! held {:at t0 :bytes all :body text :client client})
            (log! proxy (assoc req :at t0 :status :held))
            (info "hold-proxy: holding" (:method req) (:path req) text))
          ; The head may have been followed by body bytes already buffered in
          ; `in`; they are still in the stream, and the pump sends them on.
          (relay! proxy client head req t0))))))

(defn start!
  "Listens on port; relays to [host port] upstream. `node` is the agent whose
  registration can be held. Returns the proxy."
  [port upstream node]
  (let [ss    (doto (ServerSocket.) (.setReuseAddress true)
                (.bind (InetSocketAddress. "0.0.0.0" (int port))))
        proxy {:server ss :upstream upstream :node node
               :armed (atom false) :held (atom nil) :released (atom nil)
               :log (atom [])}
        t (Thread. ^Runnable
                   (fn []
                     (while (not (.isClosed ss))
                       (try
                         (let [s (.accept ss)
                               w (Thread. ^Runnable (fn [] (try (handle! proxy s)
                                                                (catch Exception e
                                                                  (warn e "hold-proxy")))))]
                           (.setDaemon w true)
                           (.start w))
                         (catch SocketException _)))))]
    (.setDaemon t true)
    (.start t)
    (info "hold-proxy: listening on" port "for" node "->" upstream)
    proxy))

(defn arm! [proxy] (reset! (:armed proxy) true))

(defn held [proxy] (some-> @(:held proxy) (dissoc :bytes :client)))

(defn release!
  "Sends the held request to the server on a new connection and reads its
  whole answer. Returns {:sent ms :answered ms :status n :body text}."
  [proxy]
  (let [{:keys [^bytes bytes]} @(:held proxy)
        [host port] (:upstream proxy)
        sent (now-ms)]
    (when-not bytes (throw (IllegalStateException. "nothing held")))
    (with-open [s (Socket.)]
      (.connect s (InetSocketAddress. ^String host (int port)) 3000)
      (.setSoTimeout s 20000)
      (let [o (.getOutputStream s)]
        (.write o bytes)
        (.flush o)
        ; The server may keep the connection open after answering, so read
        ; the head and then the body by its framing, not up to EOF.
        (let [in     (.getInputStream s)
              head   (read-head in)
              _      (when-not head (throw (java.io.IOException. "no answer")))
              ; parse-head wants a request line first; the status line is
              ; replaced by a dummy one.
              framing (parse-head (str "X / HTTP/1.1\r\n"
                                       (second (str/split (head-text head) #"\r\n" 2))))
              body   (:data (read-body in framing))
              r      {:sent sent :answered (now-ms)
                      :status (status-of head (alength head))
                      :body (String. body StandardCharsets/UTF_8)}]
          (reset! (:released proxy) r)
          (log! proxy {:method "PUT" :path (str "/v1/agent/nodes/" (:node proxy))
                       :at sent :status (:status r) :end (:answered r)
                       :released true})
          (info "hold-proxy: released; server answered" (:status r) (:body r))
          r)))))

(defn requests [proxy] @(:log proxy))

(defn stop! [proxy]
  (.close ^ServerSocket (:server proxy))
  (some-> @(:held proxy) :client (#(.close ^Socket %))))
