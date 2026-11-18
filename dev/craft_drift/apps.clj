(ns craft-drift.apps
  "Which apps the drift probe covers, where each one's pinned catalog lives and how its
   extractor runs. Data plus pure command builders; nothing here touches disk or git.

   :pin        where the committed catalog is read: a :file (relative to the hive checkout root,
               tried first) and/or a classpath :resource
   :extractor  {:in-process qualified-symbol}  a (fn [root] catalog) of this repo's dev/ tree
               {:script path :output path}      a foreign `clojure -M script root` extractor
               {:dev-dir path :main ns :output path}  a foreign `-m ns root` extractor
               Foreign extractors write `:output` relative to their cwd, a fresh temp dir.")

(def own
  "hive-artcraft's own catalogs, extracted by dev/extract_<app>.clj (or the shared extractor)."
  {"anicraft" 'extract-anicraft/extract
   "cadcraft" 'extract-cadcraft/extract
   "deckcraft" 'artcraft-extract/extract
   "designcraft" 'artcraft-extract/extract
   "effectcraft" 'extract-effectcraft/extract
   "figcraft" 'extract-figcraft/extract
   "filmcraft" 'extract-filmcraft/extract
   "gridcraft" 'artcraft-extract/extract
   "lightcraft" 'artcraft-extract/extract
   "materialcraft" 'extract-materialcraft/extract
   "pdfcraft" 'extract-pdfcraft/extract
   "soundcraft" 'extract-soundcraft/extract
   "studio" 'extract-studio/extract
   "texturecraft" 'extract-texturecraft/extract
   "wordcraft" 'extract-wordcraft/extract})

(def repo-of
  "The storytold repository each app is extracted from, when it differs from the app id."
  {"studio" "artcraft"})

(defn own-app [[app extractor]]
  {:app app
   :repo (repo-of app app)
   :pin {:file (str "hive-artcraft/resources/hive_artcraft/catalogs/" app ".edn")
         :resource (str "hive_artcraft/catalogs/" app ".edn")}
   :extractor {:in-process extractor}})

(def delegated
  "Apps whose catalog ships in a delegate library (hive-vectorcraft, hive-photocraft)."
  [{:app "vectorcraft" :repo "vectorcraft"
    :pin {:file "hive-vectorcraft/resources/hive_vectorcraft/catalog.edn"
          :resource "hive_vectorcraft/catalog.edn"}
    :extractor {:script "hive-vectorcraft/dev/extract_catalog.clj"
                :output "resources/hive_vectorcraft/catalog.edn"}}
   {:app "photocraft" :repo "photocraft"
    :pin {:file "hive-photocraft/resources/hive_photocraft/catalog.edn"
          :resource "hive_photocraft/catalog.edn"}
    :extractor {:dev-dir "hive-photocraft/dev" :main "extract-catalog"
                :output "resources/hive_photocraft/catalog.edn"}}])

(def apps
  "Every probed app, sorted by id."
  (vec (sort-by :app (concat (map own-app own) delegated))))

(defn select
  "The apps named in `ids` (all when empty), or an ex-info naming unknown ids."
  [ids]
  (let [known (into #{} (map :app) apps)
        unknown (remove known ids)]
    (when (seq unknown)
      (throw (ex-info "Unknown app" {:unknown (vec unknown) :known (vec (sort known))})))
    (if (seq ids) (filterv (comp (set ids) :app) apps) apps)))

(defn resolve-path
  "`path` of an app spec, absolute under the hive checkout root."
  [hive-root path]
  (str hive-root "/" path))

(defn extractor-argv
  "argv running a foreign extractor against the checkout at `root`."
  [hive-root {:keys [script dev-dir main]} root]
  (cond
    script ["clojure" "-M" (resolve-path hive-root script) root]
    dev-dir ["clojure" "-Sdeps" (pr-str {:paths [(resolve-path hive-root dev-dir)]})
             "-M" "-m" main root]
    :else (throw (ex-info "Not a foreign extractor" {:main main}))))
