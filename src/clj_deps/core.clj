(ns clj-deps.core
  (:require [clojure.java.io :as io]
            [clojure.java.shell :refer [sh]]
            [clojure.spec.alpha :as s]
            [clojure.string :as string]
            [clojure.test.check.generators :as gen]
            [taoensso.timbre :as log]
            [tentacles.repos :as repos])
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

(defn- project-clj-paths
  []
  (->> tmp-dir
       io/file
       file-seq
       (filter (fn [file]
                 (= "project.clj" (.getName file))))))

(s/def ::project-clj (s/with-gen (partial instance? File)
                                 (constantly
                                   (gen/elements
                                     [(io/file "/code/project.clj")
                                      (io/file "/code/resources/test/project.clj")]))))

(defn lein-deps
  [^File project-clj]
  (log/info "fetching lein deps for: " (.getAbsolutePath project-clj))
  (sh "lein" "deps" ":tree"
      :dir (.getParentFile project-clj)))

(s/def ::exit number?)
(s/def ::out string?)
(s/def ::err string?)
(s/fdef
  lein-deps
  :args (s/cat :project-clj ::project-clj)
  :ret (s/keys :req-un [::exit ::out ::err]))

(defn- deps->data
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
              {::depth      depth
               ::dep-name   dep-name
               ::version    version
               ::exclusions (flatten exclusions)}))
       (filter ::dep-name)))

(s/def ::exclusions (s/or :nil nil?
                          :v (s/coll-of symbol?)))
(s/def ::version string?)
(s/def ::dep-name symbol?)
(s/def ::depth (s/or :0 zero?
                     :+ (s/with-gen pos?
                                    (constantly
                                      (gen/choose 1 100)))))
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
  [nodes]
  (->> nodes
       (reduce (fn [{:keys [::accum ::lookup]} node]
                 {::accum (if-let [parent (->> node
                                               ::depth
                                               dec
                                               (get lookup))]
                            (conj accum [parent node])
                            accum)
                  ::lookup (assoc lookup
                             (::depth node) node)})
               {::accum []})
       ::accum))

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
    {::nodes (or deps [])
     ::edges (deps->edges deps)}))

(s/def ::nodes (s/coll-of ::node))
(s/def ::edges (s/coll-of ::edge))
(s/def ::graph (s/keys :req [::nodes ::edges]))

(s/fdef
  build-graph
  :args (s/cat :project-clj ::project-clj)
  :ret ::graph)

(defn build-graphs!
  [token repo]
  (log/info "building graphs")
  (let [cleanup (::cleanup-fn (git-clone! token repo))
        path0 (format "/code/storage/%s" (cleaned-name (:full_name repo)))]
    (doseq [project-clj (project-clj-paths)]
      (let [graph (build-graph project-clj)
            path (format "%s/%s/clj-deps"
                         "/code/storage/circleci_circle"
                         (cleaned-name (.getParentFile project-clj)))]
        (if (seq (::nodes graph))
          (do (log/info "storing " path)
              (io/make-parents path)
              (spit path graph))
          (log/info "skipping " path))))
    (cleanup)))

(defn fetch-repos
  [token org-name]
  (->> {:oauth-token token
        :all-pages   true}
       (tentacles.repos/org-repos org-name)
       (map #(select-keys % [:clone_url
                             :full_name]))))

(comment
  (defn snag
    [arg]
    (def snagged arg)
    arg)

  (->> (fetch-repos "<redacted>"
                    "circleci")
       (take 1)

       (map (partial build-graphs! "<redacted>")))

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
