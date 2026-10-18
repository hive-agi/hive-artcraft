(ns extract-lightcraft
  "Derive resources/hive_artcraft/catalogs/lightcraft.edn from a LightCraft checkout.
   Run from this repository: clojure -M:dev -m extract-lightcraft [root]
   Default root: ../clones-ref/lightcraft. LightCraft's `cmd!` has a plain
   and a `query ` arm; the reference revision is the checkout's HEAD sha."
  (:require [artcraft-extract :as x]))

(def default-root "../clones-ref/lightcraft")

(defn -main [& [root]]
  (x/extract! "lightcraft" (or root default-root))
  (shutdown-agents))
