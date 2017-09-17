(ns clj-deps.core
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

(defn- org-node
  [org-name children]
  {:uid      {:id   [org-name]
              :type :org}
   :children (into #{} children)})

(defn build-repo-graph
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
                         (graph/merge-nodes
                           (concat accum-nodes
                                   nodes
                                   #{(org-node org-name #{root})})))
                       #{}))})

(s/fdef
  build-org-graph
  :args (s/cat :name string?)
  :ret ::graph/graph)

(defn build-org-graph!
  [org-name]
  (fs/store "" (build-org-graph org-name)))

(defn main
  "Using env vars build graphs."
  ([]
   (main (System/getenv "CLJ_DEPS__GH__TOKEN")
         (System/getenv "CLJ_DEPS__GH__ORG")))
  ([token org-name]
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

(comment
  (defn snag
    [arg]
    (def snagged arg)
    arg)

  (main "<redacted>"
        "AlexanderMann")

  (github/fetch-repos "<redacted>"
                      "BambooBuds"))
