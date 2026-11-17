(ns extract-texturecraft
  "Derive resources/hive_artcraft/catalogs/texturecraft.edn from a TextureCraft checkout.
   Run from this repository: clojure -M:dev -m extract-texturecraft [root]
   Default root: ../clones-ref/texturecraft. Engine = the 7-field cmd! across crates/engine/src
   (registry in commands.rs; no wrapper macros, so no :unexpanded key); MCP = rmcp #[tool] fns of
   crates/automation/src/server.rs, served by `texturecraft-cli mcp`; control = the control-channel
   Methods table of docs/control-protocol.md (before `### Live bake preview`, so the headless
   `serve` table is excluded); revision = HEAD sha."
  (:require [artcraft-extract :as x]
            [film-effect-extract :as fe]
            [sound-material-texture-extract :as smt]))

(def default-root "../clones-ref/texturecraft")

(defn extract [root]
  (let [{:keys [engine unexpanded]} (fe/engine root "crates/engine/src")]
    (cond-> {:reference-revision (x/head-sha root)
             :engine engine
             :mcp (fe/rmcp-tools root "crates/automation/src/server.rs")
             :control (smt/control-first-cell root #"^### Live bake preview|^## MCP")}
      (seq unexpanded) (assoc :unexpanded unexpanded))))

(defn -main [& [root]]
  (fe/extract! "texturecraft" (extract (or root default-root)))
  (shutdown-agents))
