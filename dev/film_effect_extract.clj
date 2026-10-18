(ns film-effect-extract
  "Catalog extraction for FilmCraft and EffectCraft, whose registries differ from the
   vectorcraft shape that artcraft-extract reads:
   - commands are the 7-field `cmd!` (EffectCraft adds an arm whose shortcut is a vector) and
     the 4-field `query!`, spread over the whole engine crate (FilmCraft: commands.rs plus
     sibling modules; EffectCraft: commands/*.rs plus a few crate-root modules);
   - every OTHER construct that builds commands (wrapper macros such as align_cmd!/label_cmd!/
     style_cmd!/fe!/res!, the per-module `spec(..)`/`query(..)` helper fns, bare
     `CommandSpec { .. }` literals) is NOT expanded. Each one is counted per source file and
     listed under the catalog's :unexpanded key, so the static catalog never hides a gap.
     The running app's `engine.commands` is the complete list.
   Pure reads of a reference checkout; never builds or runs the app."
  (:require [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [artcraft-extract :as x]))

(defn rs-files
  "Non-test .rs files under `dir`, as [relative-path text], sorted."
  [root dir]
  (let [base (str (.getPath (io/file root)) "/")]
    (->> (file-seq (io/file root dir))
         (filter #(and (.isFile %) (str/ends-with? (.getName %) ".rs")))
         (remove #(re-find #"(^|_)tests?\.rs$" (.getName %)))
         (map (fn [f] [(str/replace-first (.getPath f) base "") (slurp f)]))
         (sort-by first))))

(defn invocations
  "Bodies of `name!(..)` calls not preceded by an identifier char, `:` or `.`."
  [text nm]
  (let [m (re-matcher (re-pattern (str "(?<![A-Za-z0-9_:.$])" nm "!\\(")) text)]
    (loop [out []]
      (if (.find m)
        (recur (conj out (first (x/calls (subs text (.start m)) (str nm "!(")))))
        out))))

(defn- string-literal? [s] (and (str/starts-with? s "\"") (string? (x/literal s))))

(defn engine-entries
  "cmd! (7 fields, plain or vector-shortcut arm) and query! (4 fields) commands of one file.
   The run closure `|s, p| ..` holds a depth-0 comma, so the shared splitter yields extra
   trailing fields; the leading fields (id, label, menu, shortcut, params) are exact."
  [path text]
  (let [rust-str (fn [s] (if-let [[_ _ body] (re-matches #"(?s)r(#*)\"(.*)\"\1" s)]
                           body
                           (str (x/literal s))))]
    (concat
     (for [body (invocations text "cmd")
           :let [parts (x/fields body)]
           :when (and (<= 7 (count parts)) (string-literal? (first parts)))]
       {:id (x/literal (nth parts 0)) :label (rust-str (nth parts 1))
        :params (rust-str (nth parts 4)) :source path})
     (for [body (invocations text "query")
           :let [parts (x/fields body)]
           :when (and (<= 4 (count parts)) (string-literal? (first parts)))]
       {:id (x/literal (nth parts 0)) :label (rust-str (nth parts 1))
        :params (rust-str (nth parts 2)) :source path}))))

(defn command-macros
  "Names of macro_rules! (other than cmd/query) whose body builds commands or their run fns."
  [files]
  (->> (for [[_ text] files
             [_ nm] (re-seq #"macro_rules!\s*(\w+)\s*\{" text)
             :let [start (str/index-of text (str "macro_rules! " nm))
                   body (subs text start (min (count text) (+ start 1200)))]
             :when (and (not (#{"cmd" "query"} nm))
                        (re-find #"CommandSpec|cmd!\(|spec\(|fn \$" body))]
         nm)
       distinct sort vec))

(defn unexpanded
  "Command-building sites the extractor does not expand: {:macro :count :source} per file."
  [files]
  (let [macros (command-macros files)
        counters (concat
                  (for [nm macros] [(str nm "!") (re-pattern (str "(?<![A-Za-z0-9_])" nm "!\\("))])
                  [["spec()" #"(?<![A-Za-z0-9_!:.])spec\(\s*\""]
                   ["query()" #"(?<![A-Za-z0-9_!:.])query\(\s*\""]
                   ["CommandSpec {}" #"CommandSpec\s*\{\s*id:\s*(?:\"|concat!)"]])]
    (vec (for [[path text] files
               [label re] counters
               :let [n (count (re-seq re text))]
               :when (pos? n)]
           {:macro label :count n :source path}))))

(defn engine
  "Engine commands (deduplicated by id, sorted) and the unexpanded sites under `dir`."
  [root dir]
  (let [files (rs-files root dir)
        entries (mapcat (fn [[p t]] (engine-entries p t)) files)]
    {:engine (->> entries (reduce (fn [m e] (if (contains? m (:id e)) m (assoc m (:id e) e))) {})
                  vals (sort-by :id) vec)
     :unexpanded (unexpanded files)}))

(defn rmcp-tools
  "rmcp `#[tool(..)]` attributed fns: tool name = fn name, plus description/title."
  [root path]
  (let [text (slurp (io/file root path))
        m (re-matcher #"#\[tool\(" text)]
    (loop [out []]
      (if (.find m)
        (let [body (first (x/calls (subs text (.start m)) "#[tool("))
              after (subs text (+ (.start m) (count body)))
              kv (into {} (keep #(when-let [[_ k v] (re-matches #"(?s)(\w+)\s*=\s*(.*)" %)] [k v]))
                       (x/fields body))
              fn-name (second (re-find #"fn\s+(\w+)" after))]
          (recur (conj out (cond-> {:name fn-name :description (str (x/literal (get kv "description" "\"\"")))
                                    :source path}
                             (kv "title") (assoc :title (str (x/literal (kv "title"))))))))
        out))))

(defn tooldef-tools
  "`ToolDef { name: \"..\", description: \"..\", bridge_only: b, .. }` struct literals."
  [root path]
  (vec (for [[_ nm desc bridge] (re-seq #"ToolDef \{\s*name:\s*(\"[^\"]*\"),\s*description:\s*(\"(?:[^\"\\]|\\.)*\"),\s*bridge_only:\s*(true|false)"
                                       (slurp (io/file root path)))]
         {:name (x/literal nm) :description (str (x/literal desc)) :bridge-only (= "true" bridge)
          :source path})))

(defn control
  "Methods in the first cell of every `| Method |` table of docs/control-protocol.md, up to the
   first heading matching `stop` (FilmCraft documents engine commands in later tables)."
  [root stop]
  (let [lines (str/split-lines (slurp (io/file root "docs/control-protocol.md")))
        lines (if stop (take-while #(not (re-find stop %)) lines) lines)]
    (loop [ls lines table? false out []]
      (if-let [l (first ls)]
        (cond
          (str/starts-with? l "| Method |") (recur (drop 2 ls) true out)
          (and table? (str/starts-with? l "|"))
          (let [cell (second (x/split-on l "|"))]
            (recur (rest ls) true (into out (filter #(re-matches x/method-re %) (x/backtick-values cell)))))
          :else (recur (rest ls) false out))
        (vec (sort (distinct out)))))))

(defn extract!
  "Write `app`'s catalog to resources/hive_artcraft/catalogs/<app>.edn and return it."
  [app catalog]
  (let [counts (update-vals (select-keys catalog [:engine :mcp :control]) count)]
    (when (some zero? (vals counts)) (throw (ex-info "Incomplete extraction" counts)))
    (with-open [w (io/writer (io/file "resources/hive_artcraft/catalogs" (str app ".edn")))]
      (binding [*out* w *print-length* nil *print-level* nil] (pprint/pprint catalog)))
    (println "Extracted" app (:reference-revision catalog) "-" (:engine counts) "engine,"
             (:mcp counts) "mcp," (:control counts) "control,"
             (reduce + (map :count (:unexpanded catalog))) "unexpanded sites in"
             (count (:unexpanded catalog)) "file/macro rows")
    catalog))
