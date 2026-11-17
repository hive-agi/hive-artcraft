(ns extract-cadcraft
  "Derive resources/hive_artcraft/catalogs/cadcraft.edn from a CADCraft checkout.
   Run from this repository: clojure -M:dev -m extract-cadcraft [root]
   Default root: ../clones-ref/cadcraft. Engine = `CommandSpec::new(id, label, run)` builder chains
   (.menu/.alias/.params/.key) across crates/engine/src, with the gc!/dc! wrapper macros of
   cmd/constraints.rs counted under :unexpanded; MCP = the json! tool objects of
   crates/mcp/src/tools.rs; control = the `| Method |` table of docs/control-protocol.md;
   reference revision = HEAD sha."
  (:require [artcraft-extract :as x]
            [cad-fig-word-extract :as cfw]))

(def default-root "../clones-ref/cadcraft")

(defn extract [root]
  (let [{:keys [engine unexpanded]} (cfw/builder-engine root "crates/engine/src" 3)]
    {:reference-revision (x/head-sha root)
     :engine engine
     :unexpanded unexpanded
     :mcp (cfw/json-object-tools root "crates/mcp/src/tools.rs")
     :control (x/control root)}))

(defn -main [& [root]]
  (cfw/extract! "cadcraft" (extract (or root default-root)))
  (shutdown-agents))
