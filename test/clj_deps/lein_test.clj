(ns clj-deps.lein-test
  (:require [clj-deps.lein :as lein]
            [clj-deps.test-utils :as tu]
            [clojure.java.io :as io]
            [clojure.test :refer :all]))

(deftest deps->edges
  (testing "basic edge test"
    (is (= #{[0 1]
             [1 2]
             [0 3]}
           ;; note, this format doesn't match the intended spec of uid, but it works
           ;; currently and makes grokking what's going on here much simpler.
           (->> [{::lein/depth 0 :uid 0}
                 {::lein/depth 1 :uid 1}
                 {::lein/depth 2 :uid 2}
                 {::lein/depth 1 :uid 3}
                 {::lein/depth 0 :uid 4}]
                lein/deps->edges
                (map (fn [edge] (vec edge)))
                (into #{}))))))

(deftest nodes
  (testing "the simplest project.clj returns some nodes"
    (is (not-empty (lein/nodes (io/file "/code/resources/simplest/project.clj")))))
  (testing "simplest and are both a :project, and a :version node"
    (is (= 2
           (->> (lein/nodes (io/file "/code/resources/simplest/project.clj"))
                (map :uid)
                (map :id)
                (filter (fn [[project]] (= "simplest" project)))
                count)))
    (is (= 2
           (->> (lein/nodes (io/file "/code/resources/simplest/project.clj"))
                (map :uid)
                (map :id)
                (filter (fn [[project]] (= "org.clojure/clojure" project)))
                count)))))

(deftest gen-testing
  (testing "project.clj based fns"
    (is (tu/stest-w-report [`lein/lein-deps
                            `lein/->version-uid
                            `lein/->project-uid
                            `lein/nodes
                            `lein/graph]
                           ; since we have limited project.clj files to work with
                           ; there's no point in running these more than a few times
                           :num-tests 10))))
