(ns clj-deps.filesystem-test
  (:require [clj-deps.filesystem :as fs]
            [clojure.test :refer :all]
            [clj-deps.test-utils :as tu]))

(deftest gen-testing
  (testing "gen tests for fs"
    (is (tu/stest-w-report [`fs/clj-deps-paths]
                           :num-tests 10))))
