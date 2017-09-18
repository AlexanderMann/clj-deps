(ns clj-deps.lein
  "An ns intended to deal with interactions with `lein`.

  Deals with running `lein` to get a dep tree, parsing the tree,
  turning the parsed representation into a clj-deps.graph."
  (:require [clj-deps.filesystem :as fs]
            [clj-deps.github :as github]
            [clj-deps.graph :as graph]
            clj-deps.spec
            [clojure.java.io :as io]
            [clojure.java.shell :refer [sh]]
            [clojure.set :as set]
            [clojure.spec.alpha :as s]
            [clojure.string :as string]
            [clojure.test.check.generators :as gen]
            [taoensso.timbre :as log])
  (:import [java.io File]
           [java.util Date]))

(defn project-clj-paths
  "Return all `project.clj` Files underneath root."
  [root]
  (fs/find-paths root "project.clj"))

(s/def ::project-clj (s/with-gen (partial instance? File)
                                 (constantly
                                   (gen/elements
                                     (project-clj-paths "/code/")))))

(defn lein-deps
  "Run the `lein deps :tree` file on the passed in `project.clj`."
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
              {:uid      {:id   [(str dep-name) (str version)]
                          :type :version}
               :children #{}
               ::depth   depth}))
       (filter :uid)))

(s/def ::depth :clj-deps.spec/non-negative?)
(s/def ::dep (s/merge ::graph/node
                      (s/keys :req [::depth])))
(s/def ::deps (s/coll-of ::dep))

(s/fdef
  deps->nodes
  :args (s/cat :deps-tree string?)
  :ret ::deps)

(defn deps->edges
  "Takes deps and turns them into edges. Returned format is in
  [clj-deps.graph/uid clj-deps.graph/uid] format.
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
                                         (conj accum (map :uid [parent node]))
                                         accum)
                              ::lookup (assoc lookup
                                         (::depth node) node)})
                           {::accum []})
                   ::accum)]
    (log/debugf "turned deps into edges for: %d nodes, resulting in: %d edges"
                (count deps)
                (count edges))
    edges))

(s/def ::edge (s/cat :parent ::graph/uid
                     :child ::graph/uid))

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

(defn ->version-uid
  "Given a path to a project-clj, return the clj-deps.graph/uid
  :version representation of it, or nil if no uid can be generated."
  [project-clj]
  (try
    {:id   [(-> project-clj
                slurp
                read-string
                second
                str)
            (github/git-sha project-clj)]
     :type :version}
    (catch Exception e
      nil)))

(s/fdef
  ->version-uid
  :args (s/cat :project-clj ::project-clj)
  :ret (s/or :nil nil?
             :uid (s/and ::graph/uid
                         #(= :version (:type %)))))

(defn ->project-uid
  ":projects and :versions share similar uids. This translates a :version uid
  into its corresponding :project uid"
  [{[project] :id}]
  {:id   [project]
   :type :project})

(s/fdef
  ->project-uid
  :args (s/cat :project-clj ::graph/uid)
  :ret (s/and ::graph/uid
              #(= :project (:type %))))

(defn- project-nodes
  "Version nodes have an implicit project node in them.
  Returns nodes which represent projects."
  [nodes]
  (let [version-ids (->> nodes
                         (filter (fn [{{node-type :type} :uid}]
                                   (= :version node-type)))
                         (map :uid))]
    (reduce (fn [node-map version-uid]
              (update-in node-map
                         [(->project-uid version-uid) :children]
                         conj version-uid))
            (->> version-ids
                 (map (fn [version-id]
                        {:uid      (->project-uid version-id)
                         :children #{}}))
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
                    (cons {:uid      (->version-uid project-clj)
                           :children #{}
                           ::depth   0})))]
    (graph/merge-nodes
      (concat (vals (reduce (fn [node-map [parent-uid child-uid]]
                              (update-in node-map [parent-uid :children] conj child-uid))
                            (graph/nodes->map deps)
                            (deps->edges deps)))
              (vals (project-nodes deps))))))

(s/fdef
  nodes
  :args (s/cat :project-clj ::project-clj)
  :ret ::graph/nodes
  :fn (fn [{ret-nodes :ret :as args}]
        (set/subset? (->> ret-nodes
                          (map :children)
                          (apply concat)
                          (into #{}))
                     (->> ret-nodes
                          (map :uid)
                          (into #{})))))

(defn graph
  "Build a clj-deps.graph representing the passed in `project.clj`"
  [^File project-clj]
  {:desc  (pr-str {:file-path (.getAbsolutePath project-clj)
                   :sha       (github/git-sha project-clj)})
   :root  (-> project-clj
              ->version-uid
              ->project-uid)
   :nodes (nodes project-clj)
   :at    (Date.)})

(s/fdef
  graph
  :args (s/cat :project-clj ::project-clj)
  :ret ::graph/graph)
