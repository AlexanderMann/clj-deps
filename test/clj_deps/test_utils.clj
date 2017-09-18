(ns clj-deps.test-utils
  (:require [clojure.spec.test.alpha :as stest]))

(defn stest-w-report
  [sym-or-syms & {:as opts}]
  (-> sym-or-syms
      (stest/check {:clojure.spec.test.check/opts opts})
      stest/summarize-results
      (dissoc :total)
      (dissoc :check-passed)
      empty?))
