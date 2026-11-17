(ns extract-gridcraft
  "Derive resources/hive_artcraft/catalogs/gridcraft.edn from a GridCraft checkout.
   Run from this repository: clojure -M:dev -m extract-gridcraft [root]
   Default root: ../clones-ref/gridcraft. GridCraft has the vectorcraft shape (cmd! registry
   under crates/engine/src/cmd, tool( declarations, a `| Method |` table), so the shared
   extractor reads it unchanged; the reference revision is the checkout's HEAD sha."
  (:require [artcraft-extract :as x]))

(def default-root "../clones-ref/gridcraft")

(defn -main [& [root]]
  (x/extract! "gridcraft" (or root default-root))
  (shutdown-agents))
