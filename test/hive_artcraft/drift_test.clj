(ns hive-artcraft.drift-test
  "The craft catalog drift probe (dev/craft_drift): the pure report, argument parsing, and the
   boundary driven against throwaway git repositories (no network, no storytold clone)."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [craft-drift.apps :as apps]
            [craft-drift.boundary :as boundary]
            [craft-drift.main :as main]
            [craft-drift.report :as report]))

;; ---------------------------------------------------------------------------
;; Pure report

(def pin-catalog
  {:reference-revision "aaaaaaa"
   :engine [{:id "a.one"} {:id "a.two"}]
   :mcp [{:name "t1"}]
   :control ["app.ping" "ui.open"]})

(defn facts [head-catalog & {:as extra}]
  (merge {:app "toy" :pin-catalog pin-catalog :head-sha "bbbbbbbbbb" :behind 3
          :head-catalog head-catalog :warnings []}
         extra))

(deftest kind-ids-read-both-catalog-shapes
  (is (= {:engine #{"a.one" "a.two"} :mcp #{"t1"} :control #{"app.ping" "ui.open"}}
         (report/catalog-ids pin-catalog)))
  (testing "hive-photocraft names engine :commands and control :methods, and has no MCP"
    (is (= {:engine #{"x.y"} :mcp nil :control #{"app.drag"}}
           (report/catalog-ids {:commands [{:id "x.y"}] :methods ["app.drag"]})))))

(deftest a-moved-head-with-the-same-ids-is-stable
  (let [row (report/app-row (facts pin-catalog))]
    (is (= :stable (:status row)))
    (is (= {:pin 2 :head 2 :added [] :removed []} (get-in row [:kinds :engine])))))

(deftest the-pin-at-head-is-current
  (is (= :current (:status (report/app-row (facts pin-catalog :head-sha "aaaaaaa0123" :behind 0))))))

(deftest added-and-removed-ids-are-drift-not-failure
  (let [head (-> pin-catalog
                 (update :engine conj {:id "a.three"})
                 (assoc :control ["app.ping" "ui.close"]))
        row (report/app-row (facts head))]
    (is (= :drifted (:status row)))
    (is (= {:pin 2 :head 3 :added ["a.three"] :removed []} (get-in row [:kinds :engine])))
    (is (= {:pin 2 :head 2 :added ["ui.close"] :removed ["ui.open"]} (get-in row [:kinds :control])))
    (is (zero? (report/exit-code [row])))))

(deftest a-kind-emptied-at-head-is-an-extractor-failure
  (let [row (report/app-row (facts (assoc pin-catalog :mcp [])))]
    (is (= :failed (:status row)))
    (is (= {:stage :extract :kinds [:mcp]} (select-keys (:failure row) [:stage :kinds])))
    (is (= 1 (report/exit-code [row])))))

(deftest a-kind-empty-at-both-ends-is-fine
  (let [studio {:reference-revision "aaaaaaa" :engine [{:id "x"}] :mcp [] :control []}]
    (is (= :stable (:status (report/app-row (facts studio :pin-catalog studio)))))))

(deftest collection-errors-are-failures
  (let [row (report/app-row {:app "toy" :pin-catalog pin-catalog :warnings []
                             :error {:stage :extract :message "boom"}})]
    (is (= {:status :failed :failure {:stage :extract :message "boom"}}
           (select-keys row [:status :failure])))
    (is (= 1 (report/exit-code [row])))))

(deftest the-report-is-valid-data-and-renders
  (let [rows [(facts pin-catalog :app "same")
              (facts (update pin-catalog :engine conj {:id "a.new"}) :app "moved")
              {:app "broken" :pin-catalog pin-catalog :warnings ["fetch failed"]
               :error {:stage :checkout :message "no such sha"}}]
        r (report/report "2026-11-18T00:00:00Z" rows)
        md (report/markdown r)]
    (is (report/valid? r))
    (is (= 1 (:exit r)))
    (is (= {:drifted ["moved"] :failed ["broken"] :stable ["same"]} (:by-status (:summary r))))
    (is (= {:added 1 :removed 0} (get-in r [:summary :totals :engine])))
    (is (str/includes? md "| moved | aaaaaaa | bbbbbbbb | 3 | 2 → 3 (+1/−0) | 1 | 2 | drifted |"))
    (is (str/includes? md "- **broken** (checkout): no such sha"))
    (is (str/includes? md "- engine added: `a.new`"))
    (is (str/includes? md "- **broken**: fetch failed"))))

;; ---------------------------------------------------------------------------
;; Apps and arguments

(deftest every-catalog-in-resources-is-probed
  (let [own (->> (io/file "resources/hive_artcraft/catalogs") .listFiles
                 (map #(.getName %)) (filter #(str/ends-with? % ".edn"))
                 (map #(str/replace % #"\.edn$" "")) set)]
    (is (= (into own #{"photocraft" "vectorcraft"}) (set (map :app apps/apps))))
    (is (every? (comp qualified-symbol? :in-process :extractor)
                (filter (comp own :app) apps/apps)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown app" (apps/select ["nocraft"])))))

(deftest arguments
  (let [env {:home "/h" :cwd "/w/hive-artcraft" :today "2026-11-18"}]
    (is (= {:clones-root "/h/PP/artcraft" :hive-root "/w" :out-dir "/w/hive-artcraft/target/drift/2026-11-18"
            :fetch? true :apps []}
           (main/parse-args env [])))
    (is (= {:clones-root "/c" :hive-root "/r" :out-dir "/o" :fetch? false :apps ["pdfcraft" "studio"]}
           (main/parse-args env ["--clones" "/c" "pdfcraft" "--no-fetch" "--hive-root" "/r"
                                 "--out" "/o" "studio"])))
    (is (:error (main/parse-args env ["--out"])))
    (is (:error (main/parse-args env ["--out" "--no-fetch"])))
    (is (:error (main/parse-args env ["--bogus"])))
    (is (:help (main/parse-args env ["--help"])))))

;; ---------------------------------------------------------------------------
;; Boundary, against throwaway repositories

(defn toy-extract
  "An in-process extractor: one engine id per line of commands.txt."
  [root]
  (let [ids (str/split-lines (slurp (io/file root "commands.txt")))]
    (when (some #{"explode"} ids) (throw (IllegalStateException. "layout moved")))
    {:reference-revision (str/trim (:out (boundary/sh! ["git" "-C" (str root) "rev-parse" "HEAD"] {})))
     :engine (mapv #(hash-map :id %) ids) :mcp [] :control []}))

(defn git! [dir & args]
  (let [{:keys [exit err out]} (boundary/sh! (into ["git" "-C" (str dir) "-c" "user.name=t"
                                                    "-c" "user.email=t@t" "-c" "commit.gpgsign=false"]
                                                   args) {})]
    (when-not (zero? exit) (throw (ex-info err {:args args})))
    (str/trim out)))

(defn commit! [repo lines]
  (spit (io/file repo "commands.txt") (str/join "\n" lines))
  (git! repo "add" "commands.txt")
  (git! repo "commit" "--quiet" "-m" (first lines))
  (git! repo "rev-parse" "HEAD"))

(defn with-world
  "Calls (f world) with an upstream repo, a clone of it, a pinned catalog and an output dir."
  [f]
  (let [tmp (boundary/temp-dir! "drift-test-")
        upstream (io/file tmp "upstream")
        clones (io/file tmp "clones")]
    (try
      (.mkdirs upstream)
      (git! upstream "init" "--quiet" "-b" "main")
      (let [pin (commit! upstream ["a.one" "a.two"])]
        (.mkdirs clones)
        (git! clones "clone" "--quiet" (str upstream) "toycraft")
        (boundary/write-edn! (io/file tmp "hive" "pin.edn")
                             (assoc (toy-extract (io/file clones "toycraft")) :reference-revision pin))
        (f {:tmp tmp :upstream upstream :clone (io/file clones "toycraft") :pin pin
            :ctx {:hive-root (str tmp "/hive") :clones-root (str clones) :out-dir (str tmp "/out")
                  :fetch? true :sh boundary/sh!}
            :spec {:app "toycraft" :repo "toycraft" :pin {:file "pin.edn"}
                   :extractor {:in-process `toy-extract}}}))
      (finally (boundary/delete-tree! tmp)))))

(deftest the-probe-fetches-and-extracts-head-without-touching-the-clone-tree
  (with-world
    (fn [{:keys [upstream clone pin ctx spec]}]
      (let [head (commit! upstream ["a.one" "a.three"])
            [facts] (boundary/collect ctx [spec])
            row (report/app-row facts)]
        (is (= head (:head-sha facts)))
        (is (= 1 (:behind facts)))
        (is (= head (:reference-revision (:head-catalog facts))) "the extractor read HEAD")
        (is (= :drifted (:status row)))
        (is (= {:pin 2 :head 2 :added ["a.three"] :removed ["a.two"]} (get-in row [:kinds :engine])))
        (testing "fetch only: the clone's checkout stays at the pin"
          (is (= pin (git! clone "rev-parse" "HEAD")))
          (is (= "a.one\na.two" (slurp (io/file clone "commands.txt")))))
        (is (= (:head-catalog facts)
               (edn/read-string (slurp (io/file (:out-dir ctx) "catalogs" "toycraft.edn")))))))))

(deftest without-fetch-the-last-fetched-head-is-used
  (with-world
    (fn [{:keys [upstream pin ctx spec]}]
      (commit! upstream ["a.one" "a.three"])
      (let [[facts] (boundary/collect (assoc ctx :fetch? false) [spec])]
        (is (= pin (:head-sha facts)))
        (is (= :current (:status (report/app-row facts))))))))

(deftest an-extractor-that-throws-is-reported-not-raised
  (with-world
    (fn [{:keys [upstream ctx spec]}]
      (commit! upstream ["explode"])
      (let [[facts] (boundary/collect ctx [spec])
            r (report/report "now" [facts])]
        (is (= {:stage :extract :message "IllegalStateException: layout moved"} (:error facts)))
        (is (= 1 (:exit r)))))))

(deftest a-missing-clone-or-pin-fails-that-app-only
  (with-world
    (fn [{:keys [ctx spec]}]
      (let [[no-clone no-pin ok] (boundary/collect ctx [(assoc spec :app "a" :repo "nocraft")
                                                        (assoc spec :app "b" :pin {:file "none.edn"})
                                                        spec])]
        (is (= :clone (get-in no-clone [:error :stage])))
        (is (= :pin (get-in no-pin [:error :stage])))
        (is (nil? (:error ok)))))))
