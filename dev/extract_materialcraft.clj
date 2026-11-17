(ns extract-materialcraft
  "Derive resources/hive_artcraft/catalogs/materialcraft.edn from a MaterialCraft checkout.
   Run from this repository: clojure -M:dev -m extract-materialcraft [root]
   Default root: ../clones-ref/materialcraft. Engine = the 7-field cmd! across crates/engine/src
   (no wrapper macros: every specs() module calls cmd! directly, so no :unexpanded key);
   MCP = rmcp #[tool] fns of crates/automation/src/server/material_craft_mcp.rs, served by
   `materialcraft-cli mcp`; control = rows of the docs/control-protocol.md Methods table whose
   Where is `all` or `app` (what `materialcraft --control PORT` answers, crates/ui-egui/src/control.rs);
   revision = HEAD sha."
  (:require [artcraft-extract :as x]
            [film-effect-extract :as fe]
            [sound-material-texture-extract :as smt]))

(def default-root "../clones-ref/materialcraft")

(defn extract [root]
  (let [{:keys [engine unexpanded]} (fe/engine root "crates/engine/src")]
    (cond-> {:reference-revision (x/head-sha root)
             :engine engine
             :mcp (fe/rmcp-tools root "crates/automation/src/server/material_craft_mcp.rs")
             :control (smt/control-where root)}
      (seq unexpanded) (assoc :unexpanded unexpanded))))

(defn -main [& [root]]
  (fe/extract! "materialcraft" (extract (or root default-root)))
  (shutdown-agents))
