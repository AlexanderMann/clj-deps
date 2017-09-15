(ns clj-deps.core
  (:require [clojure.java.io :as io]
            [clojure.java.shell :refer [sh]]
            [clojure.string :as string]
            [taoensso.timbre :as log]
            [tentacles.repos :as repos]
            [clojure.spec.alpha :as s])
  (:import [java.io File]))

(def tmp-dir "/code/tmp/")

(defn cleaned-name
  [x]
  (-> (if (instance? File x)
        (.getAbsolutePath x)
        x)
      (string/replace-first tmp-dir "")
      (string/replace-first "/code/" "")
      (string/replace  "/" "_")))

(defn snag
  [arg]
  (def snagged arg)
  arg)

(defn clone-url
  [token {:keys [clone_url] :as repo}]
  ;; https://<token>:x-oauth-basic@github.com/owner/repo.git
  (string/replace
    clone_url #"//" (str "//" token ":x-oauth-basic@")))

(defn- delete-recursively
  [fname]
  (log/warn "deleting recursively: " fname)
  (let [func (fn [func f]
               (when (.isDirectory f)
                 (doseq [f2 (.listFiles f)]
                   (func func f2)))
               (io/delete-file f))]
    (func func (io/file fname))))

(defn git-clone!
  "Takes a Github Oauth Token, a Repo response map, and returns
  the outcome of running `git clone` for the repo.

  Additionally, the returned map has a key:
  :cleanup-fn

  The value there is a function which can be called to cleanup
  all actions made by this fn."
  [token repo]
  (log/info "cloning: " (:full_name repo))
  (io/make-parents (str tmp-dir ".clj-deps"))
  (assoc (sh "git" "clone" (clone-url token repo)
             :dir tmp-dir)
    ::cleanup-fn (fn [] (delete-recursively tmp-dir))))

(defn project-clj-paths
  []
  (->> tmp-dir
       io/file
       file-seq
       (filter (fn [file]
                 (= "project.clj" (.getName file))))))

(defn lein-deps
  [^File project-clj]
  (log/info "fetching lein deps for: " (.getAbsolutePath project-clj))
  (snag project-clj)
  (sh "lein" "deps" ":tree"
      :dir (.getParentFile project-clj)))

(defn deps->data
  "Takes a deps-tree string and turns it into usable data."
  [deps-tree]
  (->> deps-tree
       snag
       string/split-lines
       (remove empty?)
       (map (fn [s]
              [(-> (re-find #"\s*" s)
                   count
                   dec
                   (/ 2))
               (read-string s)]))
       (map (fn [[depth [dep-name version _ exclusions]]]
              {::depth      depth
               ::dep-name   dep-name
               ::version    version
               ::exclusions (flatten exclusions)}))))

(s/def ::exclusions (s/or :nil nil?
                          :v (s/coll-of symbol?)))
(s/def ::version string?)
(s/def ::dep-name symbol?)
(s/def ::depth (s/or :0 zero?
                     :+ pos?))
(s/def ::node (s/keys :req [::depth ::dep-name ::version ::exclusions]))
(s/def ::deps (s/coll-of ::node))

(s/fdef
  deps->data
  :args (s/cat :deps-tree string?)
  :ret ::deps)

(defn deps->edges
  "Takes deps and turns them into edges.
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
  [[{age ::depth :as ancestor} & family]]
  (let [younger? (fn [member]
                   (< age
                      (::depth member)))
        child? (fn [descendant]
                 (= age
                    (dec (::depth descendant))))]
    (concat (->> (take-while younger? family)
                 (filter child?)
                 (map (partial vector ancestor)))
            (when family
              (deps->edges family)))))

(comment
  (->> [{::depth 0 ::k 0}
        {::depth 1 ::k 1}
        {::depth 2 ::k 2}
        {::depth 1 ::k 3}
        {::depth 0 ::k 4}]
       deps->edges
       (map (fn [edge] (map ::k edge)))))

(s/def ::edge (s/cat :parent ::node
                     :child ::node))

(s/fdef
  deps->edges
  :args (s/cat :deps ::deps)
  :ret (s/coll-of ::edge))

(defn build-graph
  [^File project-clj]
  (log/info "building graph for: " (.getAbsolutePath project-clj))
  (let [result (lein-deps project-clj)
        deps (when (zero? (:exit result))
               (->> result
                    :out
                    deps->data
                    (map #(assoc % ::project-clj project-clj))))]
    (when deps
      {::nodes deps
       ::edges (deps->edges deps)})))

(defn build-graphs!
  [token repo]
  (log/info "building graphs")
  (let [cleanup (::cleanup-fn (git-clone! token repo))
        path0 (format "/code/storage/%s" (cleaned-name (:full_name repo)))]
    (log/debug "path0 is: " path0)
    (doseq [project-clj (project-clj-paths)]
      (let [graph (build-graph project-clj)
            path (format "%s/%s/clj-deps"
                         "/code/storage/circleci_circle"
                         (cleaned-name (.getParentFile project-clj)))]
        (log/debug "path is: " path)
        (if graph
          (do (log/info "storing " path)
              (io/make-parents path)
              (spit path graph))
          (log/info "skipping " path))))
    (log/info "cleaning up cruft")
    (cleanup)))

(defn fetch-repos
  [token org-name]
  (->> {:oauth-token token
        :all-pages   true}
       (tentacles.repos/org-repos org-name)
       (map #(select-keys % [:clone_url
                             :full_name]))))

(comment
  (->> (fetch-repos "484be452c3ffed9bcfa7afa3c35525b4d89cb090"
                    "circleci")
       (take 1)

       (map (partial build-graphs! "484be452c3ffed9bcfa7afa3c35525b4d89cb090")))

  (->> (project-clj-paths)
       first
       lein-deps
       :out
       snag
       deps->data)

  (re-find #"\s*" (last (string/split-lines snagged)))

  (s/explain-data (s/coll-of ::deps-entry) (deps->data snagged))

  (->> (deps->data snagged)
       deps->edges)
  )
