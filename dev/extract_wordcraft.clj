(ns extract-wordcraft
  "Derive resources/hive_artcraft/catalogs/wordcraft.edn from a WordCraft checkout.
   Run from this repository: clojure -M:dev -m extract-wordcraft [root]
   Default root: ../clones-ref/wordcraft. Engine = `CommandSpec::new(id, label, location, run)`
   builder chains (.params/.key) across crates/engine/src, ids or labels held in a same-file
   `const` resolved; MCP = `tool(name, title, description, schema, read_only)` calls of
   crates/mcp/src/tools.rs; control = the `| Method |` table of docs/control-protocol.md;
   reference revision = HEAD sha."
  (:require [artcraft-extract :as x]
            [cad-fig-word-extract :as cfw]))

(def default-root "../clones-ref/wordcraft")

(defn extract [root]
  (let [{:keys [engine unexpanded]} (cfw/builder-engine root "crates/engine/src" 4)]
    {:reference-revision (x/head-sha root)
     :engine engine
     :unexpanded unexpanded
     :mcp (cfw/tool-fn-tools root "crates/mcp/src/tools.rs")
     :control (x/control root)}))

(defn -main [& [root]]
  (cfw/extract! "wordcraft" (extract (or root default-root)))
  (shutdown-agents))
