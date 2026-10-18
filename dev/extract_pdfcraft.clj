(ns extract-pdfcraft
  "Derive resources/hive_artcraft/catalogs/pdfcraft.edn from a PdfCraft checkout.
   Run from this repository: clojure -M:dev -m extract-pdfcraft [root]
   Default root: ../clones-ref/pdfcraft. PdfCraft has no `cmd!` registry and no
   docs/control-protocol.md, so it needs its own parsers:
   - engine:  `c(id, label, menu, shortcut, needs, icon)` / `ct(...)` rows of
              `pub const COMMANDS` in crates/engine/src/commands.rs (ct = shortcut yields to text fields)
   - mcp:     `t(name, title, description)` builder rows of crates/automation/src/tools.rs, with
              the .ro() / .destructive() / .cmd(id) modifiers of the chain that follows
   - control: the ui.* method arms of crates/ui-egui/src/control.rs (no engine.execute exists)
   Pure reads of the reference checkout; never runs the app."
  (:require [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [artcraft-extract :as x]))

(def default-root "../clones-ref/pdfcraft")

(defn- ident-char? [ch] (or (Character/isLetterOrDigit (char ch)) (= \_ ch)))

(defn- close-paren
  "Index of the paren closing the one opened just before `open`, honouring string literals."
  [^String text open]
  (loop [i open depth 1 quoted false escaped false]
    (when (= i (count text)) (throw (ex-info "Unclosed Rust invocation" {:at open})))
    (let [ch (.charAt text i)
          depth' (if quoted depth (case ch \( (inc depth) \) (dec depth) depth))
          quoted' (if (and (= ch \") (not escaped)) (not quoted) quoted)
          escaped' (and quoted (= ch \\) (not escaped))]
      (if (zero? depth') i (recur (inc i) depth' quoted' escaped')))))

(defn calls
  "Every `marker(...)` call whose marker is not the tail of a longer identifier (so `c(` never
   matches `fn spec(` or `ct(`): [{:start :end :body}]."
  [^String text marker]
  (loop [at 0 found []]
    (if-let [start (str/index-of text marker at)]
      (if (and (pos? start) (ident-char? (.charAt text (dec start))))
        (recur (inc start) found)
        (let [open (+ start (count marker)) end (close-paren text open)]
          (recur (inc end) (conj found {:start start :end end :body (subs text open end)}))))
      found)))

(defn rust-string
  "Decode a plain Rust string literal (\"...\"), or nil when the field is not one."
  [s]
  (when (and (str/starts-with? s "\"") (str/ends-with? s "\"") (< 1 (count s)))
    (-> (subs s 1 (dec (count s)))
        (str/replace #"\\u\{([0-9a-fA-F]+)\}" (fn [[_ hex]] (String. (Character/toChars (Integer/parseInt hex 16)))))
        (str/replace #"\\(.)" (fn [[_ c]] (case c "n" "\n" "t" "\t" "0" "\u0000" c))))))

(def menu-consts {"FILE" "File" "EDIT" "Edit" "VIEW" "View" "PAGES" "Pages" "HELP" "Help"})

(defn- option-string [s]
  (when-not (= "None" s)
    (or (some-> (re-matches #"Some\((\".*\")\)" s) second rust-string) (menu-consts s) s)))

(defn engine [root]
  (let [text (slurp (io/file root "crates/engine/src/commands.rs"))
        from (str/index-of text "pub const COMMANDS")
        region (subs text from (str/index-of text "];" from))]
    (->> (concat (map #(assoc % :in-text true) (calls region "c("))
                 (map #(assoc % :in-text false) (calls region "ct(")))
         (sort-by :start)
         (keep (fn [{:keys [body in-text]}]
                 (let [[id label menu shortcut needs icon :as parts] (x/fields body)]
                   (when (and (= 6 (count parts)) (rust-string id))
                     (cond-> {:id (rust-string id) :label (rust-string label) :needs needs
                              :icon (rust-string icon) :in-text in-text}
                       (option-string menu) (assoc :menu (option-string menu))
                       (not= "None" shortcut) (assoc :shortcut shortcut))))))
         vec)))

(defn mcp [root]
  (let [text (slurp (io/file root "crates/automation/src/tools.rs"))
        rows (filterv #(let [[n t d :as p] (x/fields (:body %))]
                         (and (= 3 (count p)) (rust-string n) (rust-string t) (rust-string d)))
                      (calls text "t("))]
    (mapv (fn [{:keys [body end]} next-start]
            (let [[n t d] (x/fields body)
                  chain (subs text (inc end) next-start)
                  chain (subs chain 0 (or (some #(str/index-of chain %) ["\n    ]" "\n]"]) (count chain)))
                  cmd (some-> (re-find #"\.cmd\((\"[^\"]*\")\)" chain) second rust-string)]
              (cond-> {:name (rust-string n) :title (rust-string t) :description (rust-string d)
                       :read-only (str/includes? chain ".ro()")
                       :destructive (str/includes? chain ".destructive()")
                       :source "crates/automation/src/tools.rs"}
                cmd (assoc :command cmd))))
          rows (concat (map :start (rest rows)) [(count text)]))))

(defn control [root]
  (let [text (slurp (io/file root "crates/ui-egui/src/control.rs"))]
    (->> (re-seq #"\"(ui\.[a-z][a-z.]*)\"\s*(?:=>|\||if\b)" text)
         (map second) distinct sort vec)))

(defn extract
  "The catalog of the PdfCraft checkout at `root`, pinned to its HEAD sha."
  [root]
  {:reference-revision (x/head-sha root) :engine (engine root) :mcp (mcp root) :control (control root)})

(defn -main [& [root]]
  (let [catalog (extract (or root default-root))
        counts (update-vals (dissoc catalog :reference-revision) count)]
    (when (some zero? (vals counts)) (throw (ex-info "Incomplete extraction" counts)))
    (with-open [writer (io/writer "resources/hive_artcraft/catalogs/pdfcraft.edn")]
      (binding [*out* writer] (pprint/pprint catalog)))
    (println "Extracted pdfcraft" (:reference-revision catalog) "-" (:engine counts) "engine commands,"
             (:mcp counts) "MCP tools," (:control counts) "control methods")
    (shutdown-agents)))
