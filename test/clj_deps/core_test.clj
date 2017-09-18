(ns clj-deps.core-test
  (:require [clj-deps.core :as deps]
            [clojure.spec.test.alpha :as stest]
            [clj-deps.test-utils :as tu]
            [clojure.test :refer :all]))

(deftest gen-testing
  (testing "project.clj based fns"
    (is (tu/stest-w-report [`deps/build-repo-graph
                            `deps/build-org-graph]
                           :num-tests 5))))

(deftest main
  (testing "main works"
    (is (not-empty (deps/main)))))
