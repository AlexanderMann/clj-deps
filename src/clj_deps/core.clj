(ns clj-deps.core
  "Main ns for CLJ-Deps. Use this to build general graphs."
  (:require [cheshire.core :as json]
            [clj-deps.filesystem :as fs]
            [clj-deps.github :as github]
            [clj-deps.graph :as graph]
            [clj-deps.lein :as lein]
            [clojure.java.io :as io]
            [clojure.java.shell :refer [sh]]
            [clojure.spec.alpha :as s]
            [clojure.string :as string]
            [clojure.test.check.generators :as gen]
            [taoensso.timbre :as log])
  (:import [java.io File]
           [java.util Date])
  (:gen-class))

(defn cleaned-name
  [x]
  (-> (if (instance? File x)
        (.getAbsolutePath x)
        x)
      (string/replace-first "/code/tmp/" "")
      (string/replace-first "/code/" "")
      (string/replace "/" "__")
      (string/replace "." "_")))

(defn- org-node
  [org-name children]
  {:uid      {:id   [org-name]
              :type :org}
   :children (into #{} children)})

(defn build-repo-graph
  "Given a `repo` and a project clj, build a solitary `repo` -> `project` graph"
  [repo ^File project-clj]
  (let [{:keys [desc root nodes]} (lein/graph project-clj)
        new-root {:uid      {:id   [(:html_url repo)]
                             :type :repo}
                  :children #{root}}
        repo-graph {:desc  (pr-str {:project-desc desc
                                    :repo         repo})
                    :root  (:uid new-root)
                    :at    (Date.)
                    :nodes (conj nodes
                                 new-root)}]
    (when (seq nodes)
      repo-graph)))

(s/fdef
  build-repo-graph
  :args (s/cat :repo ::github/repo :project-clj ::lein/project-clj)
  :ret (s/or :nil nil?
             :graph ::graph/graph))

(defn build-repo-graphs!
  "Like build-repo-graph, except builds all `repo` -> `project` graphs,
  and stores them."
  [token {repo-name :full_name :as repo}]
  (log/info "building graphs for: " repo-name)
  (->> (github/github-clone! token repo)
       ::github/dir
       lein/project-clj-paths
       (map (fn [project-clj]
              (if-let [repo-graph (build-repo-graph repo project-clj)]
                (fs/store (format "%s/%s/"
                                  (cleaned-name repo-name)
                                  (cleaned-name (.getParentFile project-clj)))
                          repo-graph)
                (log/info "skipping " (.getAbsolutePath project-clj)))))
       (remove nil?)
       doall))

(defn build-org-graph
  "Assuming that the fs has been setup with all association `repo` graphs,
  builds a global `org` graph which is the result of merging all `repo` graphs
  underneath an `org` node."
  [org-name]
  (log/info "Building org graph...")
  {:desc  (pr-str {:org          org-name
                   :built-from-n (count (fs/clj-deps-paths))})
   :root  (:uid (org-node org-name nil))
   :at    (Date.)
   :nodes (->> (fs/clj-deps-paths)
               (map (comp read-string slurp))
               (remove (comp empty? :nodes))
               (reduce (fn [accum-nodes
                            {nodes :nodes
                             root  :root}]
                         (if (-> root :type (= :repo))
                           (graph/merge-nodes
                             (concat accum-nodes
                                     nodes
                                     #{(org-node org-name #{root})}))
                           accum-nodes))
                       #{}))})

(s/fdef
  build-org-graph
  :args (s/cat :name string?)
  :ret ::graph/graph)

(defn build-org-graph!
  "Like build-org-graph except store it."
  [org-name]
  (fs/store "" (build-org-graph org-name)))

(defn main
  "Using env vars build graphs."
  ([]
   (main (System/getenv "CLJ_DEPS__GH__TOKEN")
         (System/getenv "CLJ_DEPS__GH__ORG")))
  ([token org-name]
   (when (not (and token org-name))
     (log/fatal "missing token or org-name, exiting...")
     (System/exit 27))
   (log/info "Building graphs for:" org-name)
   (let [repos (github/fetch-repos token org-name)
         _ (log/info "Repos to build graphs for:\n" (mapv :full_name repos))
         graph-map (->> repos
                        (map (fn [repo]
                               [repo (seq (build-repo-graphs! token repo))]))
                        (filter second)
                        (into {}))]
     (assoc graph-map
       org-name (build-org-graph! org-name)))))

(defn -main
  "Main entrypoint, just like main"
  [& args]
  (log/info "Entered the main route of clj-deps. Using sys env vars to build graphs...")
  (main))

(comment
  (defn snag
    "Simple arg def snagger for debugging."
    [arg]
    (def snagged arg)
    arg)

  (main "<redacted>"
        "AlexanderMann")

  (github/fetch-repos "<redacted>"
                      "BambooBuds"))
