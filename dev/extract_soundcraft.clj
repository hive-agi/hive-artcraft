(ns extract-soundcraft
  "Derive resources/hive_artcraft/catalogs/soundcraft.edn from a SoundCraft checkout.
   Run from this repository: clojure -M:dev -m extract-soundcraft [root]
   Default root: ../clones-ref/soundcraft. Engine = cmd! (plain, query, noundo, view arms) under
   crates/engine/src/cmd, read with the char-literal-aware scanner of
   sound-material-texture-extract (wrapper macros listed under :unexpanded); MCP = the tool(..)
   definitions of crates/automation/src/mcp.rs, served by `soundcraft-cli mcp` (docs/mcp.md), not
   by the app; control = the docs/control-protocol.md Methods table; revision = HEAD sha."
  (:require [artcraft-extract :as x]
            [film-effect-extract :as fe]
            [sound-material-texture-extract :as smt]))

(def default-root "../clones-ref/soundcraft")

(defn extract [root]
  (let [{:keys [engine unexpanded]} (smt/sound-engine root)]
    {:reference-revision (x/head-sha root)
     :engine engine
     :unexpanded unexpanded
     :mcp (smt/sound-mcp root)
     :control (fe/control root #"^## (Positions|Panel docking)")}))

(defn -main [& [root]]
  (fe/extract! "soundcraft" (extract (or root default-root)))
  (shutdown-agents))
