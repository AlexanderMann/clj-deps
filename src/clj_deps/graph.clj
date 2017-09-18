(ns clj-deps.graph
  "A detailed ns meant to document the clj-deps graph data structure format."
  (:require [clojure.spec.alpha :as s]
            [clojure.test.check.generators :as gen]))

;;; SPEC CHECKS

;; Nodes references each other as follows:
;; org -> repo -> project -> version -> version ...
(def type-map {:org     :repo
               :repo    :project
               :project :version
               :version :version})

(defn- child-types
  [children]
  (->> children
       (map :type)
       (into #{})))

(defn- types-const?
  [children]
  (->> children
       child-types
       count
       (>= 1)))

(defn- child-types-conform?
  [{{node-type :type} :uid children :children}]
  (let [child-types (child-types children)]
    (or (empty? child-types)
        (= #{(get type-map node-type)}))))

;;; GEN HELPERS

(def children-gen
  #(gen/let [t (s/gen (s/spec ::type))
             ids (gen/set (s/gen (s/spec ::id)))]
            (gen/return
              (->> ids
                   (map (fn [id] {:id id :type t}))
                   (into #{})))))
(def node-gen
  #(gen/let [uid (s/gen (s/spec ::uid))
             ids (gen/set (s/gen (s/spec ::id)))]
            (gen/return
              (let [t (get type-map (:type uid))]
                (def args [uid ids t])
                {:uid      uid
                 :children (->> ids
                                (map (fn [id] {:id id :type t}))
                                (into #{}))}))))

;;; SPECS

(s/def ::id (-> (s/every string? :kind vector?)
                (s/with-gen #(gen/vector gen/string 1 3))))
(s/def ::type (-> type-map
                  (s/with-gen #(gen/elements (keys type-map)))))
(s/def ::uid (s/keys :req-un [::id ::type]))
(s/def ::children (-> (s/and (s/coll-of ::uid)
                             types-const?)
                      (s/with-gen children-gen)))
(s/def ::node (-> (s/and (s/keys :req-un [::uid ::children])
                         child-types-conform?)
                  (s/with-gen node-gen)))
(s/def ::nodes (s/coll-of ::node))
(s/def ::desc string?)
(s/def ::at inst?)
(s/def ::root ::uid)
(s/def ::graph (s/keys :req-un [::desc ::root ::nodes ::at]))

;;; FNS

(defn nodes->map
  "Take nodes and turn them into a map of :uid -> node, merging
  dupilcates' children along the way"
  [nodes]
  (reduce (fn [accum {uid      :uid
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
            ret-nodes      :ret}]
        (= (->> nodes
                (map :uid)
                (into #{}))
           (->> ret-nodes
                (map :uid)
                (into #{})))))
