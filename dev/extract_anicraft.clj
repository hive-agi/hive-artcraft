(ns extract-anicraft
  "Derive resources/hive_artcraft/catalogs/anicraft.edn from an AniCraft checkout.
   Run from this repository: clojure -M:dev -m extract-anicraft [root]
   Default root: ../clones-ref/anicraft. AniCraft has no external control surface: its only
   automation is `anicraft-cli mcp` and `anicraft-cli serve`, both on the child's stdin/stdout
   (crates/cli/src/lib.rs, docs/mcp.md), and nothing binds a socket. The catalog lists the
   engine's CommandSpec registry (crates/engine/src, `c(...)` helper calls and
   `CommandSpec { id: ... }` literals) as :engine, the five stdio MCP tools of
   crates/cli/src/mcp.rs as :mcp, and no control methods. Pure reads; never builds or runs it."
  (:require [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [artcraft-extract :as x]))

(def default-root "../clones-ref/anicraft")

(def helper-re
  "The per-file constructor closure: `let c = |id, label, ..., params, ...| CommandSpec { ... }`."
  #"let c = \|([^|]*)\|\s*CommandSpec")

(defn- balanced
  "Text between the `open` delimiter at `start` and its matching close, quote-aware."
  [text start open close]
  (loop [i (inc start) depth 1 quoted false escaped false]
    (when (= i (count text))
      (throw (ex-info "Unclosed Rust literal" {:start start})))
    (let [ch (.charAt ^String text i)
          depth' (if quoted depth (condp = ch open (inc depth) close (dec depth) depth))
          quoted' (if (and (= ch \") (not escaped)) (not quoted) quoted)
          escaped' (and quoted (= ch \\) (not escaped))]
      (if (zero? depth')
        (subs text (inc start) i)
        (recur (inc i) depth' quoted' escaped')))))

(defn- positions [re text]
  (let [m (re-matcher re text)]
    (loop [found []] (if (.find m) (recur (conj found (.end m))) found))))

(defn- string-literal [s]
  (let [v (x/literal s)] (when (and (str/starts-with? s "\"") (string? v)) v)))

(defn- helper-entries
  "Commands built by the file's `c(` helper; field order comes from the closure signature."
  [text]
  (when-let [[_ sig] (re-find helper-re text)]
    (let [names (mapv str/trim (str/split sig #","))
          at (fn [k] (.indexOf ^java.util.List names k))]
      (for [end (positions #"(?<![A-Za-z0-9_.:])c\(" text)
            :let [parts (x/fields (balanced text (dec end) \( \)))
                  id (string-literal (first parts))]
            :when (and id (str/includes? id ".") (<= (count names) (count parts)))]
        {:id id :label (str (string-literal (nth parts (at "label"))))
         :params (str (string-literal (nth parts (at "params"))))}))))

(defn- struct-entries
  "Commands written as `CommandSpec { id: \"...\", label: ..., params: ..., ... }` literals."
  [text]
  (for [end (positions #"CommandSpec \{" text)
        :let [kv (into {} (keep (fn [field]
                                  (when-let [[_ k v] (re-matches #"(?s)([a-z_]+)\s*:\s*(.*)" field)]
                                    [k v]))
                                (x/fields (balanced text (dec end) \{ \}))))
              id (some-> (get kv "id") string-literal)]
        :when id]
    {:id id :label (str (some-> (get kv "label") string-literal))
     :params (str (some-> (get kv "params") string-literal))}))

(defn- test-file? [^java.io.File f]
  (let [n (.getName f)] (or (str/includes? n "_tests") (str/starts-with? n "tests"))))

(defn engine [root]
  (->> (file-seq (io/file root "crates/engine/src"))
       (filter #(and (.isFile ^java.io.File %) (str/ends-with? (.getName ^java.io.File %) ".rs")
                     (not (test-file? %))))
       (sort-by #(.getPath ^java.io.File %))
       (mapcat (fn [^java.io.File file]
                 (let [text (slurp file)]
                   (map #(assoc % :source (str "crates/engine/src/" (.getName file)))
                        (concat (helper-entries text) (struct-entries text))))))
       (sort-by :id) vec))

(def tool-re
  #"\"name\": \"([a-z_]+)\",\s*\"title\": (\"(?:[^\"\\]|\\.)*\"),\s*\"description\": (\"(?:[^\"\\]|\\.)*\")")

(defn mcp [root]
  (vec (for [[_ name title description] (re-seq tool-re (slurp (io/file root "crates/cli/src/mcp.rs")))]
         {:name name :title (x/literal title) :description (x/literal description)
          :source "crates/cli/src/mcp.rs"})))

(defn extract
  "AniCraft catalog: CommandSpec ids as :engine, stdio MCP tools as :mcp, no control methods."
  [root]
  {:reference-revision (x/head-sha root) :engine (engine root) :mcp (mcp root) :control []})

(defn -main [& [root]]
  (let [catalog (extract (or root default-root))
        dupes (keep (fn [[id n]] (when (< 1 n) id)) (frequencies (map :id (:engine catalog))))]
    (when (or (empty? (:engine catalog)) (empty? (:mcp catalog)))
      (throw (ex-info "Incomplete extraction" (update-vals (dissoc catalog :reference-revision) count))))
    (when (seq dupes) (throw (ex-info "Duplicate command ids" {:ids (vec dupes)})))
    (with-open [writer (io/writer "resources/hive_artcraft/catalogs/anicraft.edn")]
      (binding [*out* writer] (pprint/pprint catalog)))
    (println "Extracted anicraft" (:reference-revision catalog) "-" (count (:engine catalog))
             "engine commands," (count (:mcp catalog)) "MCP tools")
    (shutdown-agents)))
