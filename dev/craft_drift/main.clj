(ns craft-drift.main
  "Weekly drift probe of every craft catalog extractor against storytold HEAD.

     clojure -M:drift [--clones DIR] [--hive-root DIR] [--out DIR] [--no-fetch] [app ...]

   --clones     storytold clones, fetched (never checked out)  default ~/PP/artcraft
   --hive-root  checkout holding hive-artcraft, hive-photocraft, hive-vectorcraft
                                                               default: parent of the cwd
   --out        report directory                               default target/drift/<UTC date>
   --no-fetch   compare against the last fetched HEAD
   app ...      only these apps (default: all)

   Writes <out>/drift.edn, <out>/drift.md and <out>/catalogs/<app>.edn (the HEAD catalogs), prints
   the markdown, and exits 1 only when an extractor failed (threw, or lost a whole kind); drift
   alone exits 0."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [craft-drift.apps :as apps]
            [craft-drift.boundary :as boundary]
            [craft-drift.report :as report])
  (:import (java.time Instant LocalDate ZoneOffset)))

(def usage (str "usage: clojure -M:drift [--clones DIR] [--hive-root DIR] [--out DIR] "
                "[--no-fetch] [app ...]"))

(def value-flags {"--clones" :clones-root "--hive-root" :hive-root "--out" :out-dir})

(defn parse-args
  "Options map of `args`, or {:error message} / {:help usage}. Pure: defaults come in through
   `env` {:home :cwd :today}."
  [{:keys [home cwd today]} args]
  (loop [opts {:clones-root (str home "/PP/artcraft")
               :hive-root (str (.getParent (io/file cwd)))
               :out-dir (str cwd "/target/drift/" today)
               :fetch? true
               :apps []}
         [arg value :as remaining] args]
    (cond
      (empty? remaining) opts
      (value-flags arg) (if (and value (not (str/starts-with? value "--")))
                          (recur (assoc opts (value-flags arg) value) (drop 2 remaining))
                          {:error (str arg " needs a value\n" usage)})
      (= "--no-fetch" arg) (recur (assoc opts :fetch? false) (rest remaining))
      (#{"-h" "--help"} arg) {:help usage}
      (str/starts-with? arg "--") {:error (str "unknown option " arg "\n" usage)}
      :else (recur (update opts :apps conj arg) (rest remaining)))))

(defn probe!
  "Collect, report and write. Returns the report plus the written :files."
  [{:keys [apps out-dir] :as opts}]
  (let [specs (apps/select apps)
        facts (boundary/collect (assoc opts :sh boundary/sh!) specs)
        r (report/report (str (Instant/now)) facts)
        md (report/markdown r)]
    (assoc r :markdown md :files (boundary/write-report! out-dir r md))))

(defn -main [& args]
  (let [opts (parse-args {:home (System/getProperty "user.home")
                          :cwd (System/getProperty "user.dir")
                          :today (str (LocalDate/now ZoneOffset/UTC))}
                         args)]
    (cond
      (:help opts) (do (println (:help opts)) (shutdown-agents))
      (:error opts) (do (binding [*out* *err*] (println (:error opts)))
                        (shutdown-agents) (System/exit 64))
      :else (let [{:keys [markdown files exit]} (probe! opts)]
              (println markdown)
              (println "Wrote" (:edn files) "and" (:markdown files))
              (shutdown-agents)
              (System/exit exit)))))
