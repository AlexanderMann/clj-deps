(ns clj-deps.core
  (:require [clojure.java.io :as io]
            [clojure.java.shell :refer [sh]]
            [clojure.spec.alpha :as s]
            [clojure.string :as string]
            [clojure.test.check.generators :as gen]
            [taoensso.timbre :as log]
            [tentacles.repos :as repos])
  (:import [java.io File]))

(defn fetch-repos
  "Given a token and a target org name, fetch basic information about all repos
  present in github. See specs for more details.

  NOTE: if no org is able to be found, this will try to pull a user's repos.
  This is because Github's users are orgs but their orgs aren't users and
  if you think this docstring just got hella confusing you are not alone..."
  [token org-name]
  (let [auth {:oauth-token token
              :all-pages   true}
        not-found->empty (fn [repos?]
                           (if (and (map? repos?)
                                    (-> repos?
                                        :status
                                        (= 404)))
                             []
                             repos?))
        org-repos (not-found->empty (tentacles.repos/org-repos org-name auth))
        user-repos (not-found->empty (tentacles.repos/user-repos org-name auth))]
    (map #(select-keys % [:clone_url
                          :full_name
                          :html_url])
         (concat org-repos user-repos))))

(s/def ::clone_url string?)
(s/def ::full_name string?)
(s/def ::html_url string?)
(s/def ::repo (s/keys :req-un [::clone_url ::full_name ::html_url]))

(s/fdef
  fetch-repos
  :args (s/cat :token string? :org-name string?)
  :ret (s/coll-of ::repo))

(def tmp-dir "/code/tmp/")
(def storage-dir "/code/storage/")
(def clj-deps-file "clj-deps.edn")

(defn cleaned-name
  [x]
  (-> (if (instance? File x)
        (.getAbsolutePath x)
        x)
      (string/replace-first tmp-dir "")
      (string/replace-first "/code/" "")
      (string/replace "/" "_")))

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

(defn- find-paths
  [root target]
  (let [paths (->> root
                   io/file
                   file-seq
                   (filter (fn [file]
                             (and (not (.isDirectory file))
                                  (= target (.getName file))))))]
    (log/infof "found paths for %s*%s:\n%s"
               root
               target
               (pr-str (mapv (fn [file] (.getAbsolutePath file))
                             paths)))
    paths))

(defn- project-clj-paths
  []
  (find-paths tmp-dir "project.clj"))

(defn- clj-deps-paths
  []
  (find-paths storage-dir clj-deps-file))

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
                                      (gen/choose 1 10)))))
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
  (log/debugf "turning deps into edges for: %d nodes"
              (count nodes))
  (let [edges (->> nodes
                   (reduce (fn [{:keys [::accum ::lookup]} node]
                             {::accum  (if-let [parent (->> node
                                                            ::depth
                                                            dec
                                                            (get lookup))]
                                         (conj accum [parent node])
                                         accum)
                              ::lookup (assoc lookup
                                         (::depth node) node)})
                           {::accum []})
                   ::accum)]
    (log/debugf "turned deps into edges for: %d nodes, resulting in: %d edges"
                (count nodes)
                (count edges))
    edges))

(s/def ::edge (s/cat :parent ::node
                     :child ::node))

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

(defn build-graph
  [^File project-clj labels]
  (log/info "building graph for: " (.getAbsolutePath project-clj))
  (let [result (lein-deps project-clj)
        deps (when (zero? (:exit result))
               (->> result
                    :out
                    deps->data
                    (map #(merge %
                                 {:project-clj (.getAbsolutePath project-clj)}
                                 labels))))]
    {::nodes (into #{} deps)
     ::edges (->> deps
                  deps->edges
                  (into #{}))}))

(s/def ::nodes (s/coll-of ::node))
(s/def ::edges (s/coll-of ::edge))
(s/def ::graph (s/keys :req [::nodes ::edges]))

(s/fdef
  build-graph
  :args (s/cat :project-clj ::project-clj
               :labels (s/keys :opt-un []))
  :ret ::graph)

(defn build-graphs!
  [token {full-name :full_name :as repo}]
  (log/info "building graphs for: " full-name)
  (let [cleanup (::cleanup-fn (git-clone! token repo))
        graph-paths (->> (project-clj-paths)
                         (map (fn [project-clj]
                                (let [graph (build-graph project-clj (select-keys repo [:full_name
                                                                                        :html_url]))
                                      path (format "%s%s/%s/%s"
                                                   storage-dir
                                                   (cleaned-name full-name)
                                                   (cleaned-name (.getParentFile project-clj))
                                                   clj-deps-file)]
                                  (if (seq (::nodes graph))
                                    (do (log/info "storing " path)
                                        (io/make-parents path)
                                        (spit path graph)
                                        path)
                                    (log/info "skipping " path)))))
                         (remove nil?)
                         doall)]
    (cleanup)
    graph-paths))

(defn build-org-wide-graph
  []
  (log/info "Building org graph...")
  (->> (clj-deps-paths)
       (map (comp read-string slurp))
       (reduce (fn [{accum-nodes ::nodes accum-edges ::edges}
                    {nodes ::nodes edges ::edges}]
                 (let [root-nodes (filter (fn [node]
                                            (zero? (::depth node)))
                                          nodes)
                       {repo-name :full_name
                        repo-url :html_url} (first root-nodes)
                       repo-node {::depth -1
                                  ::dep-name (symbol repo-name)
                                  ::exclusions nil
                                  :full_name repo-name
                                  :html_url repo-url
                                  ::version (.toGMTString (java.util.Date.))}
                       repo-edges (map (partial vector repo-node)
                                       root-nodes)
                       inc-node-depth (fn [node]
                                        (update node ::depth inc))
                       inc-edge-depths (fn [edge]
                                         (mapv inc-node-depth edge))]
                   {::nodes (->> (concat accum-nodes nodes [repo-node])
                                 (mapv inc-node-depth)
                                 (into #{}))
                    ::edges (->> (concat accum-edges edges repo-edges)
                                 (mapv inc-edge-depths)
                                 (into #{}))}))
               {::nodes #{}
                ::edges #{}})))

(defn build-org-wide-graph!
  []
  (let [path (str storage-dir clj-deps-file)]
    (io/make-parents path)
    (spit path
          (build-org-wide-graph))
    [path]))

(defn build-graphs-for-org!
  [token org-name]
  (let [repos (fetch-repos token org-name)
        _ (log/info "Repos to build graphs for:\n" (mapv :full_name repos))
        graph-map (->> repos
                       (map (fn [repo]
                              [repo (seq (build-graphs! token repo))]))
                       (filter second)
                       (into {}))]
    (assoc graph-map
      org-name (build-org-wide-graph!))))

(defn run!
  "Using env vars build graphs."
  []
  (build-graphs-for-org! (System/getenv "CLJ_DEPS__GH__TOKEN")
                         (System/getenv "CLJ_DEPS__GH__ORG")))

(comment
  (defn snag
    [arg]
    (def snagged arg)
    arg)

  (build-graphs-for-org! "<redacted>"
                         "AlexanderMann")

  (->> (fetch-repos "<redacted>"
                    "BambooBuds")
       (take 1)

       ;(map (partial build-graphs! "<redacted>"))
       )

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
