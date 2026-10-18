(ns extract-filmcraft
  "Derive resources/hive_artcraft/catalogs/filmcraft.edn from a FilmCraft checkout.
   Run from this repository: clojure -M:dev -m extract-filmcraft [root]
   Default root: ../clones-ref/filmcraft. Engine = cmd!/query! across
   crates/engine/src (wrapper macros and spec() helpers listed under :unexpanded);
   MCP = rmcp #[tool] fns of crates/automation/src/server.rs; reference revision = HEAD sha."
  (:require [artcraft-extract :as x]
            [film-effect-extract :as fe]))

(def default-root "../clones-ref/filmcraft")

(defn extract [root]
  (let [{:keys [engine unexpanded]} (fe/engine root "crates/engine/src")]
    {:reference-revision (x/head-sha root)
     :engine engine
     :unexpanded unexpanded
     :mcp (fe/rmcp-tools root "crates/automation/src/server.rs")
     :control (fe/control root #"^## (MCP|Panel docking)")}))

(defn -main [& [root]]
  (fe/extract! "filmcraft" (extract (or root default-root)))
  (shutdown-agents))
