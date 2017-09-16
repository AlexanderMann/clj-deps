(ns clj-deps.filesystem
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.java.shell :refer [sh]]
            [clojure.spec.alpha :as s]
            [taoensso.timbre :as log])
  (:import [java.io File]))

(def clj-deps-fname "clj-deps")
(def storage-dir "/code/storage/")

(defn delete-recursively
  [path]
  (log/warn "deleting:" path)
  (let [f (io/file path)]
    (when (.exists f)
      (sh "rm" "-rf"
          (if (.isDirectory f)
            (.getAbsolutePath f)
            (.getAbsolutePath (.getParentFile f)))))))

(defn store
  "Given a path, prefix with the storage-dir, save an edn version,
   and a json version of the data at the clj-deps file name.

  Expects no leading /, and a trailing /
  ex. hello/world/"
  [path data]
  (let [full-path (str storage-dir path clj-deps-fname)]
    (log/info "storing:" full-path)
    (io/make-parents full-path)
    (spit (str full-path ".edn") (pr-str data))
    (spit (str full-path ".json") (json/encode data))
    [(str full-path ".edn")
     (str full-path ".json")]))

(defn find-paths
  "Given a root path to start searching from,
  and a target file name, return all paths found
  recursively"
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

(defn clj-deps-paths
  []
  (find-paths storage-dir (str clj-deps-fname ".edn")))
