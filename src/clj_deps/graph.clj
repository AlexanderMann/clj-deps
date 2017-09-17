(ns clj-deps.graph
  (:require [clojure.spec.alpha :as s]
            [clojure.test.check.generators :as gen]))

(s/def ::id (s/with-gen (s/every string? :kind vector?)
                        #(gen/vector gen/string 1 3)))

;; Nodes references each other as follows:
;; org -> repo -> project -> version -> version ...

(def types #{:org
             :repo
             :project
             :version})
(s/def ::type (s/with-gen types
                          #(gen/elements types)))
(s/def ::uid (s/keys :req-un [::id ::type]))
(s/def ::children (s/coll-of ::uid))
(s/def ::node (s/keys :req-un [::uid ::children]))
(s/def ::nodes (s/coll-of ::node))
(s/def ::desc string?)
(s/def ::at inst?)
(s/def ::root ::uid)
(s/def ::graph (s/keys :req-un [::desc ::root ::nodes ::at]))

(defn nodes->map
  "Take nodes and turn them into a map of :uid -> node, merging
  dupilcates' children along the way"
  [nodes]
  (reduce (fn [accum {uid :uid
                      children :children}]
            (update-in accum [uid :children] into children))
          (->> nodes
               (map (fn [dep]
                      [(:uid dep) dep]))
               (into {}))
          nodes))

(s/fdef
  nodes->map
  :args (s/cat :nodes ::nodes)
  :ret map?
  :fn (fn [{{nodes :nodes} :args
            node-map       :ret}]
        (and (<= (count (into #{} (vals node-map)))
                 (count (into #{} nodes)))
             (every? (fn [[k {v :uid}]]
                       (= k v))
                     node-map))))

(defn merge-nodes
  "Given a seq of nodes, merge them based on :uid."
  [nodes]
  (->> nodes
       nodes->map
       vals
       (into #{})))

(s/fdef
  merge-nodes
  :args (s/cat :nodes ::nodes)
  :ret ::nodes
  :fn (fn [{{nodes :nodes} :args
            ret-nodes :ret}]
        (= (->> nodes
                (map :uid)
                (into #{}))
           (->> ret-nodes
                (map :uid)
                (into #{})))))
