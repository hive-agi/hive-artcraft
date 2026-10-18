(ns extract-effectcraft
  "Derive resources/hive_artcraft/catalogs/effectcraft.edn from an EffectCraft checkout.
   Run from this repository: clojure -M:dev -m extract-effectcraft [root]
   Default root: /home/klein/PP/hive/clones-ref/effectcraft. Engine = cmd! (incl. the
   vector-shortcut arm) and query! across crates/engine/src (commands/ plus crate-root modules;
   wrapper macros listed under :unexpanded); MCP = ToolDef literals of
   crates/automation/src/tools.rs; reference revision = HEAD sha."
  (:require [artcraft-extract :as x]
            [film-effect-extract :as fe]))

(def default-root "/home/klein/PP/hive/clones-ref/effectcraft")

(defn extract [root]
  (let [{:keys [engine unexpanded]} (fe/engine root "crates/engine/src")]
    {:reference-revision (x/head-sha root)
     :engine engine
     :unexpanded unexpanded
     :mcp (fe/tooldef-tools root "crates/automation/src/tools.rs")
     :control (fe/control root nil)}))

(defn -main [& [root]]
  (fe/extract! "effectcraft" (extract (or root default-root)))
  (shutdown-agents))
