(ns craft-drift.boundary
  "Effects of the drift probe: git, the filesystem and extractor processes. Collects, per app,
   the facts craft-drift.report folds into a report.

   Per app:
     1. `git fetch origin` in the storytold clone (<clones>/<repo>); fetch only, its working tree
        is never touched, and the pinned ../clones-ref trees are never read or moved
     2. HEAD = the clone's origin/HEAD (origin/main when origin/HEAD is unset)
     3. a throwaway `git clone --shared --no-checkout` of the clone under the work dir, detached at
        HEAD, so extractors read HEAD (and their `git rev-parse HEAD` names it) without a checkout
        of any pinned tree
     4. the app's extractor reads that tree; its catalog is written to <out>/catalogs/<app>.edn
     5. the pinned catalog is read from the sibling checkout's file, else from the classpath

   Every external command goes through the ctx's :sh, (fn [argv {:keys [dir timeout-ms]}])
   -> {:exit :out :err}, so the boundary can be driven without git or a JVM."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [craft-drift.apps :as apps])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)
           (java.util.concurrent TimeUnit)))

;; ---------------------------------------------------------------------------
;; Processes

(defn sh!
  "Run `argv` in `dir`, killing it after `timeout-ms`. {:exit :out :err}; exit -1 on timeout."
  [argv {:keys [dir timeout-ms] :or {timeout-ms 600000}}]
  (let [pb (cond-> (ProcessBuilder. ^java.util.List (vec argv)) dir (.directory (io/file dir)))
        p (.start pb)
        out (future (slurp (.getInputStream p)))
        err (future (slurp (.getErrorStream p)))]
    (if (.waitFor p timeout-ms TimeUnit/MILLISECONDS)
      {:exit (.exitValue p) :out @out :err @err}
      (do (.destroyForcibly p)
          {:exit -1 :out "" :err (str "timed out after " timeout-ms " ms: " (str/join " " argv))}))))

(defn- run
  "Run through the ctx's :sh; throws ex-info tagged with `stage` on a non-zero exit."
  [{:keys [sh]} stage argv opts]
  (let [{:keys [exit out err] :as result} (sh argv opts)]
    (when-not (zero? exit)
      (throw (ex-info (str (str/join " " (take 3 argv)) " exited " exit ": "
                           (str/trim (last (str/split-lines (str (not-empty err) out " ")))))
                      {:stage stage :argv argv :result result})))
    (str/trim out)))

(defn- git [ctx stage dir & args]
  (run ctx stage (into ["git" "-C" (str dir)] args) {}))

;; ---------------------------------------------------------------------------
;; Git: fetch, resolve HEAD, materialize

(defn fetch!
  "Fetch origin in `clone`. A failed fetch is a warning: HEAD falls back to the last fetch."
  [ctx clone]
  (try (git ctx :fetch clone "fetch" "--quiet" "--prune" "origin") nil
       (catch clojure.lang.ExceptionInfo e (str "fetch failed, using the last fetched HEAD: " (ex-message e)))))

(defn head-sha
  "The clone's upstream HEAD: origin/HEAD, else origin/main."
  [ctx clone]
  (or (some (fn [ref]
              (try (git ctx :head clone "rev-parse" "--verify" "--quiet" (str ref "^{commit}"))
                   (catch clojure.lang.ExceptionInfo _ nil)))
            ["refs/remotes/origin/HEAD" "refs/remotes/origin/main"])
      (throw (ex-info "No origin/HEAD or origin/main in the clone" {:stage :head :clone (str clone)}))))

(defn behind
  "Commits from `pin` to `head` in `clone`, or nil when the pin is unknown there."
  [ctx clone pin head]
  (when (seq pin)
    (try (parse-long (git ctx :behind clone "rev-list" "--count" (str pin ".." head)))
         (catch clojure.lang.ExceptionInfo _ nil))))

(defn materialize!
  "A detached checkout of `sha` at `dir`, sharing `clone`'s objects; returns `dir`."
  [ctx clone sha dir]
  (run ctx :checkout ["git" "clone" "--quiet" "--shared" "--no-checkout" (str clone) (str dir)] {})
  (git ctx :checkout dir "-c" "advice.detachedHead=false" "checkout" "--quiet" "--detach" sha)
  dir)

;; ---------------------------------------------------------------------------
;; Files

(defn temp-dir! [prefix]
  (str (Files/createTempDirectory prefix (make-array FileAttribute 0))))

(defn delete-tree! [path]
  (let [root (io/file path)]
    (when (.exists root)
      (doseq [f (reverse (file-seq root))] (.delete ^java.io.File f)))))

(defn read-edn [source] (edn/read-string (slurp source)))

(defn write-edn! [file data]
  (io/make-parents file)
  (with-open [w (io/writer file)]
    (binding [*out* w *print-length* nil *print-level* nil] (pprint/pprint data)))
  (str file))

(defn pin-catalog
  "The pinned catalog of an app spec: the sibling checkout's file, else the classpath resource."
  [{:keys [hive-root]} {{:keys [file resource]} :pin :keys [app]}]
  (let [on-disk (when file (io/file (apps/resolve-path hive-root file)))]
    (cond (and on-disk (.isFile on-disk)) [(read-edn on-disk) (str on-disk)]
          (and resource (io/resource resource)) [(read-edn (io/resource resource)) (str (io/resource resource))]
          :else (throw (ex-info (str "No pinned catalog for " app) {:stage :pin :file file :resource resource})))))

;; ---------------------------------------------------------------------------
;; Extractors

(defn extract!
  "Run the app's extractor against `root`; returns the HEAD catalog."
  [{:keys [hive-root work-dir] :as ctx} {:keys [app extractor]} root]
  (try
    (if-let [f (:in-process extractor)]
      ((requiring-resolve f) root)
      (let [cwd (str work-dir "/run-" app)
            output (io/file cwd (:output extractor))]
        ;; Foreign extractors write a repo-relative path and do not create its parents.
        (io/make-parents output)
        (run ctx :extract (apps/extractor-argv hive-root extractor root) {:dir cwd})
        (read-edn output)))
    (catch clojure.lang.ExceptionInfo e
      (throw (if (:stage (ex-data e)) e (ex-info (ex-message e) {:stage :extract} e))))
    (catch Throwable t
      (throw (ex-info (str (.getSimpleName (class t)) ": " (ex-message t)) {:stage :extract} t)))))

;; ---------------------------------------------------------------------------
;; Collect

(defn collect-app
  "The facts craft-drift.report/app-row promotes, for one app spec. Never throws: a failure at
   any stage becomes :error {:stage :message}."
  [{:keys [clones-root work-dir out-dir fetch?] :as ctx} {:keys [app repo] :as spec}]
  (let [clone (io/file clones-root repo)
        facts (atom {:app app :warnings []})]
    (try
      (let [[pin-catalog pin-source] (pin-catalog ctx spec)
            _ (swap! facts assoc :pin-catalog pin-catalog :pin-source pin-source)
            _ (when-not (.isDirectory (io/file clone ".git"))
                (throw (ex-info (str "No storytold clone at " clone) {:stage :clone})))
            warning (when fetch? (fetch! ctx clone))
            _ (when warning (swap! facts update :warnings conj warning))
            sha (head-sha ctx clone)
            pin (:reference-revision pin-catalog)
            _ (swap! facts assoc :head-sha sha :behind (behind ctx clone pin sha))
            _ (when-not pin (swap! facts update :warnings conj "pinned catalog has no :reference-revision"))
            tree (materialize! ctx clone sha (str work-dir "/tree-" app))
            ;; hive-photocraft's extractor records no revision; name what was read.
            catalog (update (try (extract! ctx spec tree) (finally (delete-tree! tree)))
                            :reference-revision #(or % sha))]
        (write-edn! (io/file out-dir "catalogs" (str app ".edn")) catalog)
        (assoc @facts :head-catalog catalog))
      (catch clojure.lang.ExceptionInfo e
        (assoc @facts :error {:stage (:stage (ex-data e) :extract) :message (ex-message e)}))
      (catch Throwable t
        (assoc @facts :error {:stage :extract :message (str (.getSimpleName (class t)) ": " (ex-message t))})))))

(defn collect
  "Facts for every app spec, in order. `ctx` needs :hive-root :clones-root :out-dir :fetch? :sh;
   a scratch :work-dir is created and removed around the run."
  [ctx specs]
  (let [work-dir (temp-dir! "craft-drift-")]
    (try (mapv #(collect-app (assoc ctx :work-dir work-dir) %) specs)
         (finally (delete-tree! work-dir)))))

(defn write-report!
  "Write the report as <out>/drift.edn and <out>/drift.md; returns both paths."
  [out-dir report markdown]
  (let [edn-file (write-edn! (io/file out-dir "drift.edn") report)
        md-file (io/file out-dir "drift.md")]
    (spit md-file markdown)
    {:edn edn-file :markdown (str md-file)}))
