(ns clj-deps.core-test
  (:require [clj-deps.core :as deps]
            [clojure.spec.test.alpha :as stest]
            [clojure.test :refer :all]))

(defn stest-w-report
  [sym-or-syms & {:as opts}]
  (-> sym-or-syms
      (stest/check {:clojure.spec.test.check/opts opts})
      stest/summarize-results
      (dissoc :total)
      (dissoc :check-passed)
      empty?))

(deftest gen-testing
  (testing "project.clj based fns"
    (is (stest-w-report [`deps/lein-deps
                         `deps/build-graph]
                        :num-tests 5)))
  (testing "generated data-able"
    (is (stest-w-report `deps/deps->edges
                        :num-tests 100))))

(deftest deps->edges
  (testing "basic edge test"
    (is (= #{[0 1]
             [1 2]
             [0 3]}
           (->> [{::deps/depth 0 ::k 0}
                 {::deps/depth 1 ::k 1}
                 {::deps/depth 2 ::k 2}
                 {::deps/depth 1 ::k 3}
                 {::deps/depth 0 ::k 4}]
                deps/deps->edges
                (map (fn [edge] (map ::k edge)))
                (into #{}))))))

(deftest main
  (testing "main works"
    (is (not-empty (deps/main)))))
