(ns clj-deps.lein
  (:require [clj-deps.filesystem :as fs]
            [clj-deps.github :as github]
            [clj-deps.graph :as graph]
            clj-deps.spec
            [clojure.java.io :as io]
            [clojure.java.shell :refer [sh]]
            [clojure.spec.alpha :as s]
            [clojure.string :as string]
            [clojure.test.check.generators :as gen]
            [taoensso.timbre :as log])
  (:import [java.io File]
           [java.util Date]))

(defn project-clj-paths
  [root]
  (fs/find-paths root "project.clj"))

(s/def ::project-clj (s/with-gen (partial instance? File)
                                 (constantly
                                   (gen/elements
                                     (project-clj-paths "/code/")))))

(defn lein-deps
  [^File project-clj]
  (log/info "fetching lein deps for: " (.getAbsolutePath project-clj))
  (sh "lein" "deps" ":tree"
      :dir (.getParentFile project-clj)))

(s/fdef
  lein-deps
  :args (s/cat :project-clj ::project-clj)
  :ret :clj-deps.spec/sh)

(defn deps->nodes
  "Takes a deps-tree string and turns it into usable data."
  [deps-tree]
  (->> deps-tree
       string/split-lines
       (remove empty?)
       (map (fn [s]
              [(-> (re-find #"\s*" s)
                   count
                   dec
                   (/ 2))
               (try
                 (read-string s)
                 (catch Exception e
                   nil))]))
       (map (fn [[depth [dep-name version _ exclusions]]]
              {:id       [(str dep-name) (str version)]
               :children #{}
               :type     :version
               ::depth   depth}))
       (filter :id)))

(s/def ::depth :clj-deps.spec/non-negative?)
(s/def ::dep (s/keys :re-un [::graph/id ::graph/children]
                     :req [::depth]))
(s/def ::deps (s/coll-of ::dep))

(s/fdef
  deps->nodes
  :args (s/cat :deps-tree string?)
  :ret ::deps)

(defn deps->edges
  "Takes deps and turns them into edges. Returned format is in
  [clj-deps.graph/id clj-deps.graph/id] format.
  Ex:
    deps:
    [[0 k0]
     [1 k1]
     [2 k2]
     [1 k3]
     [0 k4]]
    =loosely=> ;; eg, ordering may be different
    [[k0 k1]
     [k1 k2]
     [k0 k3]]"
  [deps]
  (log/debugf "turning deps into edges for: %d nodes"
              (count deps))
  (let [edges (->> deps
                   (reduce (fn [{:keys [::accum ::lookup]} node]
                             {::accum  (if-let [parent (some->> node
                                                                ::depth
                                                                dec
                                                                (get lookup))]
                                         (conj accum (map :id [parent node]))
                                         accum)
                              ::lookup (assoc lookup
                                         (::depth node) node)})
                           {::accum []})
                   ::accum)]
    (log/debugf "turned deps into edges for: %d nodes, resulting in: %d edges"
                (count deps)
                (count edges))
    edges))

(s/def ::edge (s/cat :parent ::graph/id
                     :child ::graph/id))

(s/fdef
  deps->edges
  :args (s/cat :deps ::deps)
  :ret (s/coll-of ::edge)
  :fn (fn [{edges :ret}]
        (every? (fn [{{[_ parent-depth] ::depth} :parent
                      {[_ child-depth] ::depth}  :child}]
                  (= parent-depth
                     (dec child-depth)))
                edges)))

(defn ->id
  "Given a path to a project-clj, return the clj-deps.graph/id
  representation of it, or nil if no id can be generated."
  [project-clj]
  (try
    [(-> project-clj
         slurp
         read-string
         second
         str)
     (github/git-sha project-clj)]
    (catch Exception e
      nil)))

(s/fdef
  ->id
  :args (s/cat :project-clj ::project-clj)
  :ret (s/or :nil nil?
             :id (s/and ::graph/id
                        #(-> % count (= 2)))))

(defn- project-nodes
  "Version nodes have an implicit project node in them.
  Returns nodes which represent projects."
  [nodes]
  (let [version-ids (->> nodes
                         (filter (fn [node]
                                   (-> node
                                       :type
                                       (= :version))))
                         (map :id))]
    (reduce (fn [node-map version-id]
              (update-in node-map
                         [[(first version-id)] :children]
                         conj version-id))
            (->> version-ids
                 (map (fn [[project]]
                        {:id       [project]
                         :children #{}
                         :type     :project}))
                 graph/nodes->map)
            version-ids)))

(defn nodes
  "Given the version associated to a project.clj and a path to a project.clj,
  return all nodes associated with the project."
  [^File project-clj]
  (log/info "building nodes for: " (.getAbsolutePath project-clj))
  (let [sh-result (lein-deps project-clj)
        deps (when (zero? (:exit sh-result))
               (->> sh-result
                    :out
                    deps->nodes
                    (map #(update % ::depth inc))
                    (cons {:id       (->id project-clj)
                           :children #{}
                           :type     :version
                           ::depth   0})))]
    (graph/merge-nodes
      (concat (vals (reduce (fn [node-map [parent-id child-id]]
                              (update-in node-map [parent-id :children] conj child-id))
                            (graph/nodes->map deps)
                            (deps->edges deps)))
              (vals (project-nodes deps))))))

(s/fdef
  nodes
  :args (s/cat :project-clj ::project-clj)
  :ret (s/coll-of ::graph/node)
  :fn (fn [{ret-nodes :ret}]
        (= (->> ret-nodes
                (map :id)
                (into #{}))
           (->> ret-nodes
                (map :children)
                flatten
                (into #{})))))

(defn graph
  [^File project-clj]
  {:desc (pr-str {:file-path (.getAbsolutePath project-clj)
                  :sha (github/git-sha project-clj)})
   :root {:id (->id project-clj)
          :type :project
          :children #{}}
   :nodes (nodes project-clj)
   :at (Date.)})

(s/fdef
  graph
  :args (s/cat :project-clj ::project-clj)
  :ret ::graph/graph)
