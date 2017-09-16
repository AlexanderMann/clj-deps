(ns clj-deps.graph-test
  (:require [clj-deps.graph :as graph]
            [clj-deps.test-utils :as tu]
            [clojure.test :refer :all]))

(deftest gen-testing
  (testing "check all of the fdefs"
    (is (tu/stest-w-report [`graph/merge-nodes
                            `graph/nodes->map]))))
