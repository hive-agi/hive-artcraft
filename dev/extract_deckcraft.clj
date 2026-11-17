(ns extract-deckcraft
  "Derive resources/hive_artcraft/catalogs/deckcraft.edn from a DeckCraft checkout.
   Run from this repository: clojure -M:dev -m extract-deckcraft [root]
   Default root: ../clones-ref/deckcraft. DeckCraft has the vectorcraft shape (cmd! registry
   under crates/engine/src/cmd, tool( declarations, a `| Method |` table), so the shared
   extractor reads it unchanged; the reference revision is the checkout's HEAD sha."
  (:require [artcraft-extract :as x]))

(def default-root "../clones-ref/deckcraft")

(defn -main [& [root]]
  (x/extract! "deckcraft" (or root default-root))
  (shutdown-agents))
