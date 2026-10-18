(ns extract-studio
  "Derive resources/hive_artcraft/catalogs/studio.edn (ArtCraft Studio) from an ArtCraft checkout.
   Run from this repository: clojure -M:dev -m extract-studio [root]
   Default root: ../clones-ref/artcraft. The catalog lists the #[tauri::command]
   fn names under crates/: webview IPC handlers, NOT an external surface. They document the app's
   vocabulary; nothing outside the process can call them. Pure reads; never builds or runs the app."
  (:require [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [artcraft-extract :as x]))

(def default-root "../clones-ref/artcraft")

(def command-fn
  "#[tauri::command] (optionally with args), any further attributes, then the fn header."
  #"#\[tauri::command(?:\([^)]*\))?\]\s*(?:#\[[^\]]*\]\s*)*(?:pub(?:\([^)]*\))?\s+)?(?:async\s+)?fn\s+([A-Za-z_][A-Za-z0-9_]*)")

(defn label [fn-name]
  (let [words (str/split (str/replace fn-name #"_command$" "") #"_")]
    (str/join " " (cons (str/capitalize (first words)) (rest words)))))

(defn engine [root]
  (let [base (.toPath (io/file root))]
    (->> (file-seq (io/file root "crates"))
         (filter #(and (.isFile ^java.io.File %) (str/ends-with? (.getName ^java.io.File %) ".rs")))
         (mapcat (fn [file]
                   (for [[_ fn-name] (re-seq command-fn (slurp file))]
                     {:id fn-name :label (label fn-name)
                      :source (str (.relativize base (.toPath ^java.io.File file)))})))
         (sort-by (juxt :id :source)) vec)))

(defn extract
  "Studio catalog: tauri command names as :engine, no control methods, no MCP tools."
  [root]
  {:reference-revision (x/head-sha root) :engine (engine root) :control [] :mcp []})

(defn -main [& [root]]
  (let [catalog (extract (or root default-root))
        dupes (keep (fn [[id n]] (when (< 1 n) id)) (frequencies (map :id (:engine catalog))))]
    (when (empty? (:engine catalog)) (throw (ex-info "No #[tauri::command] fns found" {:root root})))
    (when (seq dupes) (throw (ex-info "Duplicate tauri command names" {:ids (vec dupes)})))
    (with-open [writer (io/writer "resources/hive_artcraft/catalogs/studio.edn")]
      (binding [*out* writer] (pprint/pprint catalog)))
    (println "Extracted studio" (:reference-revision catalog) "-" (count (:engine catalog)) "tauri commands")
    (shutdown-agents)))
