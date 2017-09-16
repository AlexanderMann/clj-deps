(ns clj-deps.spec
  (:require [clojure.spec.alpha :as s]
            [clojure.test.check.generators :as gen])
  (:import [java.net URISyntaxException URI]))

(s/def ::exit number?)
(s/def ::out string?)
(s/def ::err string?)
(s/def ::sh (s/keys :req-un [::err ::exit ::out]))

(defn- authority-resolves?
  [^String conformed-spec]
  (try
    (.getAuthority (URI. conformed-spec))
    conformed-spec
    (catch URISyntaxException e
      false)))
(s/def ::authority authority-resolves?)
(s/def ::url (s/and string?
                    ::authority))

(s/def ::pos? (s/with-gen pos?
                          (constantly
                            (gen/choose 1 10))))
(s/def ::non-negative? (s/or :0 zero?
                             :+ ::pos?))
