(defproject zcube-jepsen "0.1.0-SNAPSHOT"
  :description "Jepsen battery for Zigcube"
  :license {:name "Apache-2.0"
            :url  "https://www.apache.org/licenses/LICENSE-2.0"}
  :dependencies [[org.clojure/clojure "1.11.3"]
                 [jepsen "0.3.5"]
                 [org.postgresql/postgresql "42.7.4"]
                 [cheshire "5.13.0"]]
  :main zcube-jepsen.core
  :jvm-opts ["-Xmx3g" "-Djava.awt.headless=true"]
  :repl-options {:init-ns zcube-jepsen.core})
