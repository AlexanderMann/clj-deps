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
(s/def ::children (s/coll-of ::id))
(s/def ::node (s/keys :req-un [::id ::children ::type]))
(s/def ::nodes (s/coll-of ::node))
(s/def ::desc string?)
(s/def ::root ::node)
(s/def ::graph (s/keys :req-un [::desc ::root ::nodes]))

(defn nodes->map
  "Take nodes and turn them into a map of :id -> node"
  [nodes]
  (->> nodes
       (map (fn [dep]
              [(:id dep) dep]))
       (into {})))

(s/fdef
  nodes->map
  :args (s/cat :nodes ::nodes)
  :ret map?
  :fn (fn [{{nodes :nodes} :args
            node-map       :ret}]
        (and (= (into #{} nodes)
                (into #{} (vals node-map)))
             (every? (fn [[k {v :id}]]
                       (= k v))
                     node-map))))

(defn merge-nodes
  "Given a seq of nodes, merge them based on :id and :type."
  [nodes]
  (->> nodes
       (reduce (fn [accum {:keys [id type children]}]
                 (update-in accum [[id type] :children] into children))
               (->> nodes
                    (map (fn [dep]
                           [[(:id dep) (:type dep)] dep]))
                    (into {})))
       vals
       (into #{})))

(s/fdef
  merge-nodes
  :args (s/cat :nodes ::nodes)
  :ret ::nodes
  :fn (fn [{{nodes :nodes} :args
            ret-nodes :ret}]
        (= (->> nodes
                (map :id)
                (into #{}))
           (->> ret-nodes
                (map :id)
                (into #{})))))
