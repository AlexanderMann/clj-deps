(ns clj-deps.github
  (:require [clj-deps.filesystem :as fs]
            clj-deps.spec
            [clojure.java.io :as io]
            [clojure.java.shell :refer [sh]]
            [clojure.spec.alpha :as s]
            [clojure.string :as string]
            [taoensso.timbre :as log]
            [tentacles.repos :as repos]
            [clojure.test.check.generators :as gen])
  (:import [java.io File]))

(def tmp-dir "/code/tmp/")

(s/def ::token (s/with-gen string?
                           #(gen/return (or (System/getenv "CLJ_DEPS__GH__TOKEN")
                                            ""))))

(s/def ::clone-url :clj-deps.spec/url)
(s/def ::repo-name string?)
(s/def ::link string?)
(s/def ::repo (s/keys :req-un [::clone-url ::repo-name ::link]))

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
        org-repos (not-found->empty (repos/org-repos org-name auth))
        user-repos (not-found->empty (repos/user-repos org-name auth))]
    (map (fn [{:keys [clone_url
                      full_name
                      html_url]}]
           {::clone-url clone_url
            ::repo-name full_name
            ::link html_url})
         (concat org-repos user-repos))))

(s/fdef
  fetch-repos
  :args (s/cat :token ::token :org-name string?)
  :ret (s/coll-of ::repo))

(defn- clone-url
  [token {clone-url ::clone-url}]
  ;; https://<token>:x-oauth-basic@github.com/owner/repo.git
  (string/replace
    clone-url #"//" (str "//" token ":x-oauth-basic@")))

(defn github-clone!
  "Takes a Github Oauth Token, a Repo response map, and returns
  the outcome of running `git clone` for the repo.

  Additionally, the returned map has a key:
  :cleanup-fn

  The value there is a function which can be called to cleanup
  all actions made by this fn."
  [token repo]
  (log/info "cloning: " (::repo-name repo))
  (fs/delete-recursively tmp-dir)
  (io/make-parents (str tmp-dir ".clj-deps"))
  (let [result (assoc (sh "git" "clone" (clone-url token repo)
                          :dir tmp-dir)
                 ::dir tmp-dir)]
    (log/debug result)
    result))

(s/fdef
  github-clone!
  :args (s/cat :token ::token :rep ::repo)
  :ret (s/merge :clj-deps.spec/sh
                (s/keys :req [::dir])))

(defn git-sha
  [^File root]
  (let [{:keys [exit out]} (sh "git" "rev-parse" "HEAD"
                               :dir (if (.isDirectory root)
                                      root
                                      (.getParentFile root)))]
    (when (zero? exit)
      (string/trim out))))
