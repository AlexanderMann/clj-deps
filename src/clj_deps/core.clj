(ns clj-deps.core
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clj-deps.github :as github]
            [clojure.java.shell :refer [sh]]
            [clojure.spec.alpha :as s]
            [clojure.string :as string]
            [clojure.test.check.generators :as gen]
            [taoensso.timbre :as log]
            [clj-deps.lein :as lein]
            [clj-deps.filesystem :as fs]
            [clj-deps.graph :as graph])
  (:import [java.io File]
           [java.util Date]))

(defn cleaned-name
  [x]
  (-> (if (instance? File x)
        (.getAbsolutePath x)
        x)
      (string/replace-first "/code/tmp/" "")
      (string/replace-first "/code/" "")
      (string/replace "/" "__")
      (string/replace "." "_")))

(defn repo-node
  [{link ::github/link} children]
  {:id       [link]
   :type     :repo
   :children (into #{} children)})

(defn org-node
  [org-name children]
  {:id       [org-name]
   :type     :org
   :children (into #{} children)})

(defn build-repo-graph
  [{repo-name ::github/repo-name
    :as       repo}
   ^File project-clj]
  (let [graph (lein/nodes project-clj)
        repo-graph {:desc  (pr-str {:project-desc (:desc graph)
                                    :repo         repo})
                    :root  (repo-node repo nil)
                    :nodes (conj (:nodes graph)
                                 (repo-node repo
                                            #{(get-in graph [:root :id])}))}]
    (when (seq (:nodes graph))
      repo-graph)))

(s/fdef
  build-repo-graph
  :args (s/cat :repo ::github/repo :project-clj ::lein/project-clj)
  :ret (s/or :nil nil?
             :graph ::graph/graph))

(defn build-repo-graphs!
  [token {repo-name ::github/repo-name :as repo}]
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
  [org-name]
  (log/info "Building org graph...")
  {:desc  (pr-str {:org          org-name
                   :run-at       (.toGMTString (Date.))
                   :built-from-n (count (fs/clj-deps-paths))})
   :root  (org-node org-name nil)
   :nodes (->> (fs/clj-deps-paths)
               (map (comp read-string slurp))
               (remove (comp empty? :nodes))
               (reduce (fn [accum-nodes
                            {nodes :nodes
                             root  :root}]
                         (graph/merge-nodes
                           (concat accum-nodes
                                   nodes
                                   (org-node org-name #{root}))))))})

(s/fdef
  build-org-graph
  :args (s/cat :name string?)
  :ret ::graph/graph)

(defn build-org-graph!
  [org-name]
  (fs/store "/" (build-org-graph org-name)))

(defn main
  "Using env vars build graphs."
  ([]
   (main (System/getenv "CLJ_DEPS__GH__TOKEN")
         (System/getenv "CLJ_DEPS__GH__ORG")))
  ([token org-name]
   (log/info "Building graphs for:" org-name)
   (let [repos (github/fetch-repos token org-name)
         _ (log/info "Repos to build graphs for:\n" (mapv ::github/repo-name repos))
         graph-map (->> repos
                        (map (fn [repo]
                               [repo (seq (build-repo-graphs! token repo))]))
                        (filter second)
                        (into {}))]
     (assoc graph-map
       org-name (build-org-graph! org-name)))))

(comment
  (defn snag
    [arg]
    (def snagged arg)
    arg)

  (main "<redacted>"
        "AlexanderMann")

  (github/fetch-repos "<redacted>"
                      "BambooBuds"))
