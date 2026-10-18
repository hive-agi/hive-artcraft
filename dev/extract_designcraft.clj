(ns extract-designcraft
  "Derive resources/hive_artcraft/catalogs/designcraft.edn from a DesignCraft checkout.
   Run from this repository: clojure -M:dev -m extract-designcraft [root]
   Default root: /home/klein/PP/hive/clones-ref/designcraft. DesignCraft's `cmd!` has a plain,
   a `query ` and a `noundo ` arm (artcraft-extract/strip-arm accepts both prefixes); the
   reference revision is the checkout's HEAD sha."
  (:require [artcraft-extract :as x]))

(def default-root "/home/klein/PP/hive/clones-ref/designcraft")

(defn -main [& [root]]
  (x/extract! "designcraft" (or root default-root))
  (shutdown-agents))
