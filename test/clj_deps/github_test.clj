(ns clj-deps.github-test
  (:require [clj-deps.github :as github]
            [clj-deps.test-utils :as tu]
            [clojure.java.io :as io]
            [clojure.test :refer :all]))

(deftest fetch-repos
  (testing "can fetch repos from GH"
    (is (tu/stest-w-report `github/fetch-repos
                           :num-tests 5))))

(deftest sha
  (testing "clj-deps has a git sha with only HEX characters"
    (is (github/git-sha (io/file "/code/")))
    (is (= (re-find #"^[a-fA-F0-9]+$" (github/git-sha (io/file "/code/")))
           (github/git-sha (io/file "/code/"))))))
