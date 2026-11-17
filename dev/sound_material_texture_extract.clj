(ns sound-material-texture-extract
  "Catalog extraction for SoundCraft, MaterialCraft and TextureCraft, whose sources the shared
   artcraft-extract engine cannot read as-is:
   - SoundCraft keeps the vectorcraft `cmd!` registry under crates/engine/src/cmd (arms `query`,
     `noundo` and `view`), but its run closures hold Rust char literals such as `'\"'`
     (cmd/options.rs `trim_matches('\"')`). artcraft-extract/calls reads that quote as the start of
     a string and never finds the closing paren (\"Unclosed Rust invocation\"). `rust-calls` and
     `rust-fields` here are a lexer-aware scanner (strings, raw strings, char literals vs
     lifetimes, line and block comments) used for SoundCraft only; artcraft-extract is unchanged.
     Wrapper macros (toggle!, flag!, radio!, option!) are counted under :unexpanded.
   - MaterialCraft and TextureCraft register 7-field `cmd!` across crates/engine/src (read with
     film-effect-extract/engine) and serve MCP through rmcp #[tool] fns.
   - Control vocabularies come from each docs/control-protocol.md, restricted to what the
     running app's `--control PORT` channel answers (see `control-where` and `control-until`).
   Pure reads of a reference checkout; never builds or runs the apps."
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [artcraft-extract :as x]
            [film-effect-extract :as fe]))

(defn- ident-char? [c] (or (Character/isLetterOrDigit (char c)) (= c \_)))

(defn skip
  "Index just past the non-code token (string, raw string, char literal, comment) that starts at
   `i` in `text`, or nil when `i` is code. A `'` not closed two chars later (and not an escape) is
   a lifetime and stays code."
  [^String text i]
  (let [n (count text)
        at (fn [k] (when (< k n) (.charAt text k)))
        c (at i)]
    (cond
      (= c \") (loop [k (inc i)]
                 (cond (>= k n) n
                       (= (at k) \\) (recur (+ k 2))
                       (= (at k) \") (inc k)
                       :else (recur (inc k))))
      (and (= c \/) (= (at (inc i)) \/)) (or (some-> (str/index-of text "\n" i) inc) n)
      (and (= c \/) (= (at (inc i)) \*)) (or (some-> (str/index-of text "*/" (+ i 2)) (+ 2)) n)
      (and (= c \r) (not (and (pos? i) (ident-char? (at (dec i)))))
           (re-find #"^r#*\"" (subs text i (min n (+ i 40)))))
      (let [hashes (count (second (re-find #"^r(#*)\"" (subs text i (min n (+ i 40))))))
            close (str "\"" (apply str (repeat hashes "#")))]
        (or (some-> (str/index-of text close (+ i 2 hashes)) (+ (count close))) n))
      (= c \') (cond (= (at (inc i)) \\) (or (some-> (str/index-of text "'" (+ i 2)) inc) n)
                     (= (at (+ i 2)) \') (+ i 3)
                     :else nil)
      :else nil)))

(defn- close-paren
  "Index of the `)` matching the `(` just before `open`."
  [^String text open]
  (loop [i open depth 1]
    (when (>= i (count text))
      (throw (ex-info "Unclosed Rust invocation" {:at open})))
    (if-let [j (skip text i)]
      (recur j depth)
      (let [depth' (case (.charAt text i) \( (inc depth) \) (dec depth) depth)]
        (if (zero? depth') i (recur (inc i) depth'))))))

(defn rust-calls
  "Bodies of `opener` (e.g. \"cmd!(\" or \"tool(\") calls that appear in code (not in strings or
   comments) and are not preceded by an identifier char, `:`, `.` or `$`."
  [^String text ^String opener]
  (loop [i 0 out []]
    (if (>= i (count text))
      out
      (if-let [j (skip text i)]
        (recur j out)
        (if (and (.startsWith text opener (int i))
                 (not (and (pos? i) (let [p (.charAt text (dec i))] (or (ident-char? p) (#{\: \. \$} p))))))
          (let [open (+ i (count opener))
                end (close-paren text open)]
            (recur (inc end) (conj out (subs text open end))))
          (recur (inc i) out))))))

(defn rust-fields
  "Split a call body on depth-0 commas, skipping strings, char literals and comments."
  [^String body]
  (loop [i 0 start 0 depth 0 out []]
    (if (>= i (count body))
      (conj out (str/trim (subs body start)))
      (if-let [j (skip body i)]
        (recur j start depth out)
        (let [c (.charAt body i)]
          (cond
            (and (= c \,) (zero? depth)) (recur (inc i) (inc i) depth (conj out (str/trim (subs body start i))))
            (#{\( \[ \{} c) (recur (inc i) start (inc depth) out)
            (#{\) \] \}} c) (recur (inc i) start (dec depth) out)
            :else (recur (inc i) start depth out)))))))

(defn rust-str
  "The value of a Rust string literal (plain or raw)."
  [s]
  (if-let [[_ _ body] (re-matches #"(?s)r(#*)\"(.*)\"\1" s)]
    body
    (str (x/literal s))))

(def sound-arm
  "SoundCraft's `cmd!` arms besides the plain one: `query`, `noundo` and `view` (cmd.rs:120-122)."
  #"^(?:query|noundo|view)\s+")

(defn sound-engine
  "SoundCraft engine commands under crates/engine/src/cmd (deduplicated by id, sorted) and the
   wrapper-macro sites the static extractor does not expand."
  [root]
  (let [files (fe/rs-files root "crates/engine/src/cmd")
        entries (for [[path text] files
                      body (rust-calls text "cmd!(")
                      :let [parts (update (rust-fields body) 0 #(str/replace-first % sound-arm ""))]
                      :when (and (<= 7 (count parts)) (str/starts-with? (first parts) "\"")
                                 (string? (x/literal (first parts))))]
                  {:id (x/literal (nth parts 0)) :label (rust-str (nth parts 1))
                   :params (rust-str (nth parts 4)) :source path})]
    {:engine (->> entries (reduce (fn [m e] (if (contains? m (:id e)) m (assoc m (:id e) e))) {})
                  vals (sort-by :id) vec)
     :unexpanded (fe/unexpanded files)}))

(defn sound-mcp
  "`tool(name, description, props, required)` definitions of crates/automation/src/mcp.rs
   (served by `soundcraft-cli mcp`, docs/mcp.md); a multi-line call's trailing comma is dropped."
  [root]
  (let [path "crates/automation/src/mcp.rs"]
    (vec (for [body (rust-calls (slurp (io/file root path)) "tool(")
               :let [parts (cond-> (rust-fields body) (= "" (peek (rust-fields body))) pop)]
               :when (and (= 4 (count parts)) (str/starts-with? (first parts) "\""))]
           {:name (rust-str (nth parts 0)) :description (rust-str (nth parts 1))
            :input-schema-source (nth parts 2) :required-source (nth parts 3) :source path}))))

(def control-method-re
  "A control method: dotted (`ui.inspect`) or the bare `ping` both apps serve."
  #"^[a-z][A-Za-z]*(?:\.[A-Za-z]+)*$")

(defn cells
  "Cells of a markdown table row, splitting on `|` but not on an escaped `\\|`."
  [row]
  (mapv #(str/replace % "\\|" "|") (str/split row #"(?<!\\)\|")))

(defn control-where
  "MaterialCraft: methods of the `| Method | Where | What |` table of docs/control-protocol.md
   whose Where is `all` or `app` (the running app's channel; `headless` rows are only served by
   `materialcraft-cli serve`/`mcp`). A cell's backtick values are `method {params}`: the method is
   the first word."
  [root]
  (let [lines (str/split-lines (slurp (io/file root "docs/control-protocol.md")))
        rows (->> lines (drop-while #(not (str/starts-with? % "| Method | Where |")))
                  (drop 2) (take-while #(str/starts-with? % "|")))]
    (->> rows
         (mapcat (fn [row]
                   (let [[_ cell where] (cells row)]
                     (when (#{"all" "app"} (str/trim where))
                       (->> (x/backtick-values cell)
                            (map #(first (str/split % #"[\s{]")))
                            (filter #(re-matches control-method-re %)))))))
         distinct sort vec)))

(defn control-first-cell
  "Methods in the first cell of every `| Method |` table of docs/control-protocol.md before the
   first heading matching `stop`, bare `ping` included (TextureCraft, SoundCraft)."
  [root stop]
  (let [lines (take-while #(not (re-find stop %))
                          (str/split-lines (slurp (io/file root "docs/control-protocol.md"))))]
    (loop [ls lines table? false out []]
      (if-let [l (first ls)]
        (cond
          (str/starts-with? l "| Method |") (recur (drop 2 ls) true out)
          (and table? (str/starts-with? l "|"))
          (recur (rest ls) true (into out (filter #(re-matches control-method-re %)
                                                  (x/backtick-values (second (cells l))))))
          :else (recur (rest ls) false out))
        (vec (sort (distinct out)))))))
