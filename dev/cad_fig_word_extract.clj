(ns cad-fig-word-extract
  "Catalog extraction for CADCraft, FigCraft and WordCraft, whose registries differ from the
   vectorcraft shape artcraft-extract reads (there are no `cmd!` arms, which is why the shared
   extractor finds 0 engine commands in all three):
   - CADCraft: builder chains `CommandSpec::new(id, label, run).menu(&[..]).alias(&[..]).params(..)
     .key(..).noundo()` in crates/engine/src (cmd/ and sub-modules); MCP tools are
     `json!({\"name\": .., \"description\": ..})` objects in crates/mcp/src/tools.rs (the last three only
     when a window exists); control = the first `| Method |` table of docs/control-protocol.md.
   - FigCraft: the `spec!(id, label, params, history, run)` macro across crates/engine/src; MCP
     tools are `tool(name, title, description, schema, read_only)` calls; the control protocol has no
     method table, each method is a `### `method {..}`` heading under `## Methods`.
   - WordCraft: builder chains `CommandSpec::new(id, label, location, run).params(..).key(..)` in
     crates/engine/src, two of them with ids held in a same-file `const NAME: &str = \"..\"` (resolved
     here); MCP tools are `tool(..)` calls as in FigCraft; control = the first `| Method |` table.
   Sources are normalized first (comments blanked, raw strings rewritten as escaped literals,
   quote/paren char literals neutralised) so the shared `calls`/`fields` splitters stay exact.
   A command-building macro (CADCraft's gc!/dc!) and any `CommandSpec::new` whose id is neither a
   literal nor a same-file const is NOT expanded: it is counted per source file under :unexpanded,
   never dropped silently. A params doc held in a constant (e.g. `PARAMS`, `BOOL_PARAMS`) is kept
   as its Rust expression with :params-expr true. Pure reads of a reference checkout."
  (:require [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [artcraft-extract :as x]))

;; ---------- source normalization ----------

(defn- ident-char? [ch] (boolean (and ch (or (Character/isLetterOrDigit (char ch)) (= \_ ch)))))

(defn- raw-open
  "At `i`: [prefix-length hashes] when a raw string literal r#*\" or br#*\" starts there."
  [^String t i]
  (when (or (zero? i) (not (ident-char? (.charAt t (dec i)))))
    (when-let [[m h] (re-find #"^b?r(#*)\"" (subs t i (min (count t) (+ i 300))))]
      [(count m) h])))

(defn normalize
  "Rust source with comments blanked (newlines kept), raw strings rewritten as ordinary escaped
   string literals, and char literals holding a quote, paren, bracket or brace replaced by '_'."
  [^String t]
  (let [n (count t) sb (StringBuilder.)]
    (loop [i 0]
      (if (>= i n)
        (str sb)
        (let [ch (.charAt t i) nx (when (< (inc i) n) (.charAt t (inc i)))]
          (cond
            (and (= ch \/) (= nx \/))
            (recur (or (str/index-of t "\n" i) n))

            (and (= ch \/) (= nx \*))
            (let [e (loop [j (+ i 2) d 1]
                      (cond (zero? d) j
                            (>= j n) n
                            (str/starts-with? (subs t j (min n (+ j 2))) "/*") (recur (+ j 2) (inc d))
                            (str/starts-with? (subs t j (min n (+ j 2))) "*/") (recur (+ j 2) (dec d))
                            :else (recur (inc j) d)))]
              (.append sb (str/replace (subs t i e) #"[^\n]" " "))
              (recur e))

            (raw-open t i)
            (let [[plen h] (raw-open t i)
                  close (str "\"" h)
                  e (or (str/index-of t close (+ i plen)) (throw (ex-info "Unclosed raw string" {:at i})))
                  body (subs t (+ i plen) e)]
              (.append sb (str "\"" (-> body (str/replace "\\" "\\\\") (str/replace "\"" "\\\"")) "\""))
              (recur (+ e (count close))))

            (= ch \")
            (let [e (loop [j (inc i)]
                      (cond (>= j n) (throw (ex-info "Unclosed string" {:at i}))
                            (= \\ (.charAt t j)) (recur (+ j 2))
                            (= \" (.charAt t j)) (inc j)
                            :else (recur (inc j))))]
              (.append sb (subs t i e))
              (recur e))

            (= ch \')
            (if-let [lit (re-find #"^'(?:\\(?:u\{[0-9a-fA-F]+\}|x[0-9a-fA-F]{2}|.)|[^\\'])'" (subs t i (min n (+ i 14))))]
              (do (.append sb (if (re-find #"[\"()\[\]{}]" lit) "'_'" lit)) (recur (+ i (count lit))))
              (do (.append sb ch) (recur (inc i))))

            (ident-char? ch)
            (let [e (loop [j i] (if (and (< j n) (ident-char? (.charAt t j))) (recur (inc j)) j))]
              (.append sb (subs t i e))
              (recur e))

            :else (do (.append sb ch) (recur (inc i)))))))))

(defn rs-files
  "Non-test .rs files under `dir` of `root`, as [relative-path normalized-text], sorted."
  [root dir]
  (let [base (str (.getPath (io/file root)) "/")]
    (->> (file-seq (io/file root dir))
         (filter #(and (.isFile %) (str/ends-with? (.getName %) ".rs")))
         (remove #(re-find #"(^|_)tests?\.rs$" (.getName %)))
         (map (fn [f] [(str/replace-first (.getPath f) base "") (normalize (slurp f))]))
         (sort-by first))))

;; ---------- Rust literals ----------

(def ^:private string-lit-re #"(?s)\"(?:[^\"\\]|\\.)*\"")

(defn string-lit? [s] (boolean (and (string? s) (re-matches string-lit-re (str/trim s)))))

(defn rust-str
  "Decode a (normalized) Rust string literal, line continuations and \\u{..} escapes included."
  [s]
  (let [body (let [t (str/trim s)] (subs t 1 (dec (count t))))]
    (-> body
        (str/replace #"\\\r?\n\s*" "")
        (str/replace #"\\u\{([0-9a-fA-F]+)\}" (fn [[_ hex]] (String. (Character/toChars (Integer/parseInt hex 16)))))
        (str/replace #"\\(.)" (fn [[_ c]] (case c "n" "\n" "t" "\t" "r" "\r" "0" "\u0000" c))))))

(defn- str-vec
  "`&[\"a\", \"b\"]` as [\"a\" \"b\"], or nil when it is not a literal slice of strings."
  [s]
  (when-let [[_ inner] (re-matches #"(?s)&\s*\[(.*)\]" (str/trim s))]
    (let [parts (remove str/blank? (x/fields inner))]
      (when (every? string-lit? parts) (mapv rust-str parts)))))

;; ---------- call sites ----------

(defn sites
  "Every `marker(...)` call not preceded by an identifier char, `:`, `.` or `$`: [{:start :end :body}]."
  [^String text marker]
  (let [m (re-matcher (re-pattern (str "(?<![A-Za-z0-9_:.$])" (java.util.regex.Pattern/quote marker))) text)]
    (loop [out []]
      (if (.find m)
        (let [body (first (x/calls (subs text (.start m)) marker))]
          (recur (conj out {:start (.start m) :end (+ (.start m) (count marker) (count body) 1) :body body})))
        out))))

(defn chain
  "The `.method(args)` calls chained after position `at`: [[method args] ...]."
  [^String text at]
  (loop [i at out []]
    (let [m (re-matcher #"^\s*\.\s*([A-Za-z_][A-Za-z0-9_]*)\s*\(" (subs text i (min (count text) (+ i 200))))]
      (if (.find m)
        (let [nm (.group m 1)
              body (first (x/calls (subs text (+ i (.start m 1))) (str nm "(")))]
          (recur (+ i (.end m) (count body) 1) (conj out [nm body])))
        out))))

(defn macro-bodies
  "{name [start end]} of every macro_rules! definition in `text` (braces balanced)."
  [^String text]
  (into {} (for [[m nm] (re-seq #"macro_rules!\s*(\w+)\s*\{" text)
                 :let [start (str/index-of text m)
                       open (+ start (count m))
                       end (loop [j open d 1]
                             (cond (zero? d) j
                                   (>= j (count text)) j
                                   :else (recur (inc j) (case (.charAt text j) \{ (inc d) \} (dec d) d))))]]
             [nm [start end]])))

(defn- inside? [ranges pos] (some (fn [[s e]] (< s pos e)) ranges))

(defn consts
  "Same-file `const NAME: &str = \"..\";` values."
  [text]
  (into {} (for [[_ nm v] (re-seq #"const\s+([A-Z][A-Z0-9_]*)\s*:\s*&(?:'static\s+)?str\s*=\s*(\"(?:[^\"\\]|\\.)*\")\s*;" text)]
             [nm (rust-str v)])))

(defn- params-of
  "{:params ..} for a params field/arg: the decoded literal, else the Rust expression and :params-expr."
  [expr default]
  (cond (nil? expr) {:params default}
        (string-lit? expr) {:params (rust-str expr)}
        :else {:params (str/trim expr) :params-expr true}))

(defn- dedupe-sort [entries]
  (->> entries (reduce (fn [m e] (if (contains? m (:id e)) m (assoc m (:id e) e))) {}) vals (sort-by :id) vec))

;; ---------- engine: builder chains (CADCraft, WordCraft) ----------

(defn builder-engine
  "Commands of `CommandSpec::new(id, label, ..)` builder chains under `dir`. `arity` is 3 (CADCraft:
   id, label, run) or 4 (WordCraft: id, label, location, run). Sites inside a macro_rules! body are
   the macro's template, not a command; invocations of macros whose body builds a CommandSpec, and
   sites whose id is neither a literal nor a same-file const, are counted under :unexpanded."
  [root dir arity]
  (let [files (rs-files root dir)
        rows (for [[path text] files
                   :let [bodies (macro-bodies text)
                         ranges (vals bodies)
                         cs (consts text)]
                   {:keys [start end body]} (sites text "CommandSpec::new(")
                   :when (not (inside? ranges start))
                   :let [parts (x/fields body)
                         lit (fn [src] (cond (string-lit? src) (rust-str src) (cs src) (cs src)))
                         id (lit (first parts))
                         label (lit (second parts))
                         ch (into {} (map (fn [[k v]] [k (first (x/fields v))])) (reverse (chain text end)))]]
               (if (and id label (<= arity (count parts)))
                 {:entry (cond-> (merge {:id id :label label}
                                        (params-of (ch "params") (if (= arity 3) "" "{}"))
                                        {:source path})
                           (and (= arity 4) (string-lit? (nth parts 2))) (assoc :location (rust-str (nth parts 2)))
                           (some-> (ch "menu") str-vec seq) (assoc :menu (str-vec (ch "menu")))
                           (some-> (ch "alias") str-vec seq) (assoc :aliases (str-vec (ch "alias")))
                           (string-lit? (ch "key")) (assoc :shortcut (rust-str (ch "key"))))}
                 {:gap {:macro "CommandSpec::new(<expr>)" :source path}}))
        macro-gaps (for [[path text] files
                         [nm [s e]] (macro-bodies text)
                         :when (re-find #"CommandSpec" (subs text s e))
                         :let [n (count (re-seq (re-pattern (str "(?<![A-Za-z0-9_])" nm "!\\(")) text))]
                         :when (pos? n)]
                     {:macro (str nm "!") :count n :source path})
        expr-gaps (->> (keep :gap rows) frequencies
                       (map (fn [[g n]] (assoc g :count n))))]
    {:engine (dedupe-sort (keep :entry rows))
     :unexpanded (vec (sort-by (juxt :source :macro) (concat macro-gaps expr-gaps)))}))

;; ---------- engine: spec! macro (FigCraft) ----------

(defn spec-macro-engine
  "Commands of FigCraft's `spec!(id, label, params, history, run)` under `dir`; a spec! whose id or
   label is not a literal is counted under :unexpanded."
  [root dir]
  (let [rows (for [[path text] (rs-files root dir)
                   {:keys [body]} (sites text "spec!(")
                   :let [[id label params :as parts] (x/fields body)]]
               (if (and (<= 5 (count parts)) (string-lit? id) (string-lit? label))
                 {:entry (merge {:id (rust-str id) :label (rust-str label)} (params-of params "") {:source path})}
                 {:gap {:macro "spec!(<expr>)" :source path}}))]
    {:engine (dedupe-sort (keep :entry rows))
     :unexpanded (vec (for [[g n] (frequencies (keep :gap rows))] (assoc g :count n)))}))

;; ---------- MCP ----------

(defn json-object-tools
  "CADCraft: `json!({\"name\": .., \"description\": .., ..})` tool objects; those after `if has_ui`
   are listed only when a window is available (:ui-only true)."
  [root path]
  (let [text (normalize (slurp (io/file root path)))
        ui-at (or (str/index-of text "if has_ui") (count text))
        m (re-matcher #"json!\(\{\s*\"name\":\s*(\"(?:[^\"\\]|\\.)*\"),\s*\"description\":\s*(\"(?:[^\"\\]|\\.)*\")" text)]
    (loop [out []]
      (if (.find m)
        (recur (conj out (cond-> {:name (rust-str (.group m 1)) :description (rust-str (.group m 2)) :source path}
                           (> (.start m) ui-at) (assoc :ui-only true))))
        out))))

(defn tool-fn-tools
  "FigCraft/WordCraft: `tool(name, title, description, schema, read_only)` calls. A description built
   at run time (format!) is kept as its Rust expression with :description-expr true."
  [root path]
  (vec (for [{:keys [body]} (sites (normalize (slurp (io/file root path))) "tool(")
             ;; a multi-line call may end with a trailing comma: drop the empty last field
             :let [[nm title desc schema ro :as parts] (vec (remove str/blank? (x/fields body)))]
             :when (and (= 5 (count parts)) (string-lit? nm))]
         (cond-> {:name (rust-str nm) :title (rust-str title)}
           (string-lit? desc) (assoc :description (rust-str desc))
           (not (string-lit? desc)) (assoc :description (str/trim desc) :description-expr true)
           true (assoc :input-schema-source schema :read-only (= "true" ro) :source path)))))

;; ---------- control ----------

(defn heading-methods
  "FigCraft: the `### `method {..}`` headings under `## Methods` of docs/control-protocol.md."
  [root]
  (let [lines (str/split-lines (slurp (io/file root "docs/control-protocol.md")))
        section (->> lines (drop-while #(not= "## Methods" (str/trim %))) rest
                     (take-while #(not (str/starts-with? % "## "))))]
    (->> section
         (keep #(second (re-find #"^###\s+`([a-z][A-Za-z]*(?:\.[A-Za-z]+)+)[\s`]" %)))
         distinct sort vec)))

;; ---------- writing ----------

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
