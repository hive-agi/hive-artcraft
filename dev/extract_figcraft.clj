(ns extract-figcraft
  "Derive resources/hive_artcraft/catalogs/figcraft.edn from a FigCraft checkout.
   Run from this repository: clojure -M:dev -m extract-figcraft [root]
   Default root: ../clones-ref/figcraft. Engine = `spec!(id, label, params, history, run)` across
   crates/engine/src; MCP = `tool(name, title, description, schema, read_only)` calls of
   crates/mcp/src/tools.rs; control = the `### `method`` headings under `## Methods` of
   docs/control-protocol.md (it has no method table); reference revision = HEAD sha."
  (:require [artcraft-extract :as x]
            [cad-fig-word-extract :as cfw]))

(def default-root "../clones-ref/figcraft")

(defn extract [root]
  (let [{:keys [engine unexpanded]} (cfw/spec-macro-engine root "crates/engine/src")]
    {:reference-revision (x/head-sha root)
     :engine engine
     :unexpanded unexpanded
     :mcp (cfw/tool-fn-tools root "crates/mcp/src/tools.rs")
     :control (cfw/heading-methods root)}))

(defn -main [& [root]]
  (cfw/extract! "figcraft" (extract (or root default-root)))
  (shutdown-agents))
