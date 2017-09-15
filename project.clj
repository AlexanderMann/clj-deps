(defproject clj-deps "0.1.0-SNAPSHOT"
  :description "A simple project for tracking your Clojure dependencies in more than just your current `project.clj`"
  :url "https://github.com/AlexanderMann/clj-deps"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :min-lein-version "2.0.0"
  :jvm-opts ["-Xmx2g"]
  :monkeypatch-clojure-test false
  :dependencies [[com.taoensso/timbre "4.10.0"]
                 [org.clojure/clojure "1.9.0-alpha20"]
                 [org.clojure/test.check "0.9.0"]
                 ;; out of date deps
                 [cheshire "5.8.0"]
                 [clj-http "3.7.0"]
                 [tentacles "0.5.1"
                  :exclusions [cheshire
                               clj-http]]])
