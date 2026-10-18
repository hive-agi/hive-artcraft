(ns hive-artcraft.contracts
  "JVM-only Malli declarations for the portable core; the .cljc core does not load Malli."
  (:require [malli.core :as m]
            [hive-artcraft.core]
            [hive-artcraft.schema :as schema]))

(m/=> hive-artcraft.core/refusal [:=> [:cat :keyword :string] [:map [:error schema/ErrorValue]]])
(m/=> hive-artcraft.core/lookup [:=> [:cat schema/Catalog :string :any] (schema/envelope schema/CatalogEntry)])
(m/=> hive-artcraft.core/request [:=> [:cat schema/Catalog :string :any :any :any] (schema/envelope schema/Request)])
(m/=> hive-artcraft.core/control-request [:=> [:cat schema/Catalog :string :any :any :any] (schema/envelope schema/Request)])
(m/=> hive-artcraft.core/frame [:=> [:cat :any] (schema/envelope :string)])
(m/=> hive-artcraft.core/response-line [:=> [:cat :string :any] (schema/envelope :any)])
