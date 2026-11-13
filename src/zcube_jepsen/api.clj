(ns zcube-jepsen.api
  "A register per service, through zcube-server's HTTP API.

  The API has no general compare-and-set. What it has is a service's replica
  count: `POST /v1/apply` sets it (the server does a compare-and-set against
  the store underneath and retries), and `GET /v1/list` reads it back. So:

    write v   POST /v1/apply {name r<k>x, replicas v+1, ...}
    read      GET  /v1/list?prefix=/services/r<k>x  ->  replicas-1, or nil

  (v+1 so the register's 0 does not depend on what a service of no replicas
  means.)

  There is no cas op in this workload."
  (:require [cheshire.core :as json]
            [clojure.tools.logging :refer [info warn]]
            [jepsen [client :as client]
                    [independent :as independent]]
            [zcube-jepsen.zcube :as zcube])
  (:import (java.net URI ConnectException)
           (java.net.http HttpClient HttpClient$Builder HttpClient$Version
                          HttpRequest HttpRequest$BodyPublishers
                          HttpResponse$BodyHandlers
                          HttpConnectTimeoutException)
           (java.time Duration)))

(def image "b3:51aa9c03") ; in Zigcube's fixtures/images.zon

(defn svc-name [k] (str "r" k "x"))

(defn http-client ^HttpClient []
  (-> (HttpClient/newBuilder)
      (.version HttpClient$Version/HTTP_1_1)
      (.proxy HttpClient$Builder/NO_PROXY)
      (.connectTimeout (Duration/ofSeconds 3))
      (.build)))

(defn request
  "Returns {:status n :body parsed-json-or-nil}."
  [^HttpClient http base method path body]
  (let [b   (-> (HttpRequest/newBuilder (URI. (str base path)))
                (.timeout (Duration/ofSeconds 5)))
        b   (case method
              :get  (.GET b)
              :post (-> b
                        (.header "Content-Type" "application/json")
                        (.POST (HttpRequest$BodyPublishers/ofString
                                 (json/generate-string body)))))
        res (.send http (.build b) (HttpResponse$BodyHandlers/ofString))]
    {:status (.statusCode res)
     :body   (try (json/parse-string (.body res) true)
                  (catch Exception _ nil))}))

(defn code [res] (-> res :body :diagnostics first :code))

(defn not-sent?
  "True if the exception proves the request never reached the server."
  [^Throwable e]
  (or (instance? ConnectException e)
      (instance? HttpConnectTimeoutException e)
      (some-> (.getCause e) not-sent?)))

(defrecord Client [http base]
  client/Client
  (open! [this test node]
    (assoc this
           :http (http-client)
           :base (str "http://" (zcube/server-node test) ":" zcube/port)))

  (setup! [this test])

  (invoke! [this test op]
    (let [[k v] (:value op)
          name  (svc-name k)]
      (try
        (case (:f op)
          :read
          (let [res (request http base :get
                             (str "/v1/list?prefix=/services/" name "&limit=10")
                             nil)]
            (if (= 200 (:status res))
              (let [kv (->> res :body :kvs
                            (filter #(= (str "/services/" name) (:key %)))
                            first)]
                (assoc op :type :ok
                       :value (independent/tuple k (some-> kv :value :replicas dec))))
              (assoc op :type :fail :error [(:status res) (code res)])))

          :write
          (let [res (request http base :post "/v1/apply"
                             {:name name :image image :replicas (inc v)
                              :command ["sleep" "86400"]
                              :env [["ZJ" "zj-replica"]]
                              :limits {:cpu_millis 10 :memory_bytes 4194304}
                              :applied_by "jepsen"})
                s   (:status res)]
            (cond
              (= 200 s) (assoc op :type :ok)
              ; Refused before or instead of writing: 400 invalid, or 409
              ; ZC-E0105 (the compare kept failing, so nothing was written).
              ; Any other 409 (E0131, E0402, ...) could follow a partial
              ; write, and 404 is unexpected: both stay :info.
              (or (= 400 s) (and (= 409 s) (= "ZC-E0105" (code res))))
              (assoc op :type :fail :error [s (code res)])
              ; Everything else, 5xx included: the store may have committed.
              :else (assoc op :type :info :error [s (code res)]))))
        (catch Exception e
          (assoc op
                 :type  (if (or (= :read (:f op)) (not-sent? e)) :fail :info)
                 :error [:http (.getName (class e)) (.getMessage e)])))))

  (teardown! [this test])
  (close! [this test]))

(defn client [] (Client. nil nil))
