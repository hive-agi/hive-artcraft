(ns artcraft-extract
  "Shared catalog extraction for vectorcraft-shaped Crafting Apps (lightcraft, designcraft).
   Ported from hive-vectorcraft dev/extract_catalog.clj: the Rust `cmd!` registry under
   crates/engine/src/cmd, the `tool(` declarations of crates/mcp/src/tools.rs and the method
   table of docs/control-protocol.md. Pure reads of a reference checkout; never runs the app."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.pprint :as pprint]
            [clojure.string :as str]))

(defn calls [text marker]
  (loop [at 0 found []]
    (if-let [start (str/index-of text marker at)]
      (let [open (+ start (count marker))
            end (loop [i open depth 1 quoted false escaped false]
                  (when (= i (count text))
                    (throw (ex-info "Unclosed Rust invocation" {:marker marker :start start})))
                  (let [ch (.charAt text i)
                        depth' (if quoted depth (case ch \( (inc depth) \) (dec depth) depth))
                        quoted' (if (and (= ch \") (not escaped)) (not quoted) quoted)
                        escaped' (and quoted (= ch \\) (not escaped))]
                    (if (zero? depth') i
                        (recur (inc i) depth' quoted' escaped'))))]
        (recur (inc end) (conj found (subs text open end))))
      found)))

(defn fields [body]
  (loop [i 0 start 0 depth 0 quoted false escaped false result []]
    (if (= i (count body))
      (conj result (str/trim (subs body start)))
      (let [ch (.charAt body i)
            delim? (and (not quoted) (zero? depth) (= ch \,))
            depth' (if quoted depth (case ch \( (inc depth) \[ (inc depth) \{ (inc depth)
                                            \) (dec depth) \] (dec depth) \} (dec depth) depth))
            quoted' (if (and (= ch \") (not escaped)) (not quoted) quoted)
            escaped' (and quoted (= ch \\) (not escaped))]
        (recur (inc i) (if delim? (inc i) start) depth' quoted' escaped'
               (if delim? (conj result (str/trim (subs body start i))) result))))))

(defn literal [s]
  (try (edn/read-string s) (catch Exception _ s)))

(defn split-on [text delimiter]
  (loop [start 0 pieces []]
    (if-let [at (str/index-of text delimiter start)]
      (recur (+ at (count delimiter)) (conj pieces (subs text start at)))
      (conj pieces (subs text start)))))

(defn backtick-values [text]
  (loop [at 0 values []]
    (if-let [open (str/index-of text "`" at)]
      (if-let [close (str/index-of text "`" (inc open))]
        (recur (inc close) (conj values (subs text (inc open) close)))
        values)
      values)))

(def arm-prefix
  "The `cmd!` macro arms besides the plain one: `query ` (no journal, no undo) and
   designcraft's `noundo ` (journal, no undo). The same 7 fields follow the prefix."
  #"^(?:query|noundo)\s+")

(defn strip-arm [field]
  (str/replace-first field arm-prefix ""))

(defn engine [root]
  (let [dir (io/file root "crates/engine/src/cmd")
        base (str (.getPath dir) "/")]
    (->> (file-seq dir)
         (filter #(and (.isFile %) (str/ends-with? (.getName %) ".rs")))
         (sort-by #(.getPath %))
         (mapcat (fn [file]
                   (for [call (calls (slurp file) "cmd!(")
                         :let [parts (update (fields call) 0 strip-arm)]
                         :when (and (<= 7 (count parts)) (str/starts-with? (first parts) "\"")
                                    (string? (literal (first parts))))]
                     {:id (literal (nth parts 0)) :label (str (literal (nth parts 1)))
                      :params (literal (nth parts 4))
                      :source (str "crates/engine/src/cmd/" (str/replace-first (.getPath file) base ""))})))
         (sort-by :id) vec)))

(defn mcp [root]
  (let [file (io/file root "crates/mcp/src/tools.rs")]
    (->> (calls (slurp file) "tool(")
         (keep (fn [body]
                 (let [parts (fields body)]
                   (when (and (<= 5 (count parts)) (str/starts-with? (first parts) "\""))
                     {:name (literal (first parts)) :title (literal (second parts))
                      :description (literal (nth parts 2))
                      ;; Rust composes schema expressions dynamically; keep the exact expression.
                      :input-schema-source (nth parts 3)
                      :source "crates/mcp/src/tools.rs"}))))
         vec)))

(def method-re #"^[a-z][A-Za-z]*(?:\.[A-Za-z]+)+$")

(defn control
  "Methods of the first `| Method |` table of docs/control-protocol.md. Rows that document an
   engine command (`engine.execute {command: ...}`) are engine vocabulary and are skipped."
  [root]
  (let [lines (str/split-lines (slurp (io/file root "docs/control-protocol.md")))
        rows (->> lines (drop-while #(not (str/starts-with? % "| Method |")))
                  (drop 2) (take-while #(str/starts-with? % "|")))]
    (->> rows
         (mapcat (fn [row]
                   (let [cell (second (split-on row "|"))
                         methods (backtick-values cell)
                         prefix (first methods)]
                     (when-not (and prefix (str/starts-with? prefix "engine.execute {"))
                       (->> methods
                            (map (fn [method]
                                   (if (and (str/starts-with? method ".") (str/includes? prefix "."))
                                     (str (subs prefix 0 (inc (str/last-index-of prefix "."))) (subs method 1))
                                     method)))
                            (filter #(re-matches method-re %)))))))
         distinct sort vec)))

(defn head-sha [root]
  (let [{:keys [exit out err]} (sh/sh "git" "-C" (str root) "rev-parse" "HEAD")]
    (when-not (zero? exit) (throw (ex-info "Cannot read reference HEAD" {:root root :err err})))
    (str/trim out)))

(defn extract
  "The catalog of the reference checkout at `root`, pinned to its HEAD sha."
  [root]
  {:reference-revision (head-sha root) :engine (engine root) :mcp (mcp root) :control (control root)})

(defn extract!
  "Write `app`'s catalog, extracted from `root`, to resources/hive_artcraft/catalogs/<app>.edn."
  [app root]
  (let [catalog (extract root)
        counts (update-vals (dissoc catalog :reference-revision) count)]
    (when (some zero? (vals counts))
      (throw (ex-info "Incomplete extraction" counts)))
    (with-open [writer (io/writer (io/file "resources/hive_artcraft/catalogs" (str app ".edn")))]
      (binding [*out* writer] (pprint/pprint catalog)))
    (println "Extracted" app (:reference-revision catalog) "-" (:engine counts) "engine commands,"
             (:mcp counts) "MCP tools," (:control counts) "control methods")
    catalog))
