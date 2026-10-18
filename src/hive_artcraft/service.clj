(ns hive-artcraft.service
  "Native-app commands (catalog/doctor/call/control) driven by the app module's data:
   its catalog resource and its transport shape. :port is live through an injected or socket
   ControlTransport; :control-file and :none are contract slots that refuse with the remedy."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [malli.core :as m]
            [hive-artcraft.schema :as schema]
            [hive-artcraft.core :as core]
            [hive-artcraft.port :as port]
            [hive-artcraft.transport.socket :as socket]))

(defn load-catalog
  "Read a native app's catalog resource from the classpath and validate it."
  [module]
  (let [path (:app/catalog-resource module)
        resource (some-> path io/resource)]
    (cond
      (nil? resource)
      (core/refusal :artcraft/catalog-missing
                    (str "Package " (pr-str path) " for app " (:app/id module) "; extract it from upstream source."))
      :else (let [value (edn/read-string (slurp resource))]
              (if (m/validate schema/Catalog value)
                {:ok value}
                (core/refusal :artcraft/catalog-invalid
                              (str (pr-str path) " does not satisfy hive-artcraft.schema/Catalog.")))))))

(defn shape-remedy
  "Human remedy for reaching an app of this transport shape."
  [module port-res]
  (case (:app/transport module)
    :port (str "Start `" (:app/id module) " --control " (or (:port port-res) (:app/default-port module))
               "` and set :apps {\"" (:app/id module) "\" {:control-port N}} or " (:app/port-env module) ".")
    :control-file (str (:app/label module) " uses `--control <FILE>` (random port + token handshake, ui.* methods);"
                       " the control-file transport is not implemented yet, so calls refuse. Catalog works.")
    :none (str (:app/label module) " has no external control surface; it is catalog-only.")))

(defn resolve-transport
  "Injected :transport wins; a :port app gets a loopback socket on its resolved port; other shapes get nil."
  [module app-cfg port-res]
  (or (:transport app-cfg)
      (when (and (= :port (:app/transport module)) port-res)
        (socket/socket-transport (cond-> {:port (:port port-res)}
                                   (:control-host app-cfg) (assoc :host (:control-host app-cfg)))))))

(defn catalog
  "Describe the app's pinned vocabulary; query selects engine, mcp, control or one exact command id."
  [module entries query]
  (case query
    "engine" {:ok (:engine entries)}
    "mcp" {:ok (vec (:mcp entries))}
    "control" {:ok (:control entries)}
    nil {:ok {:app (:app/id module) :revision (:reference-revision entries)
              :engine (count (:engine entries)) :mcp (count (:mcp entries))
              :control (count (:control entries))}}
    (core/lookup entries (:app/id module) query)))

(defn doctor
  "Report catalog size, transport shape, whether a transport is live, and the remedy."
  [module entries transport port-res]
  {:ok {:app (:app/id module)
        :shape (:app/transport module)
        :catalog {:engine (count (:engine entries)) :control (count (:control entries))}
        :port port-res
        :transport (if transport :ready :absent)
        :hint (if transport "Transport ready; calls go to the running app." (shape-remedy module port-res))}})

(defn- send!
  [module transport built port-res]
  (cond
    (:error built) built
    (not= :port (:app/transport module))
    (if transport
      (core/response-line (:app/id module) (port/send-request transport (:ok built)))
      (core/refusal :artcraft/unsupported-shape (shape-remedy module port-res)))
    (nil? transport) (core/refusal :artcraft/no-transport (shape-remedy module port-res))
    :else (try (core/response-line (:app/id module) (port/send-request transport (:ok built)))
               (catch Exception e
                 (core/refusal :artcraft/transport-failed
                               (str "Check that " (:app/id module) " is running with --control: " (ex-message e)))))))

(defn call
  "Validate an engine command against the catalog, then send it through the app's transport."
  [module entries transport port-res command params request-id]
  (send! module transport (core/request entries (:app/id module) command params request-id) port-res))

(defn control
  "Validate a catalogued control method, then send it through the app's transport."
  [module entries transport port-res method params request-id]
  (send! module transport (core/control-request entries (:app/id module) method params request-id) port-res))

(m/=> load-catalog [:=> [:cat schema/AppModule] (schema/envelope schema/Catalog)])
(m/=> shape-remedy [:=> [:cat schema/AppModule [:maybe schema/PortResolution]] :string])
(m/=> resolve-transport [:=> [:cat schema/AppModule :map [:maybe schema/PortResolution]] :any])
(m/=> catalog [:=> [:cat schema/AppModule schema/Catalog [:maybe :string]] (schema/envelope :any)])
(m/=> doctor [:=> [:cat schema/AppModule schema/Catalog :any [:maybe schema/PortResolution]] (schema/envelope :map)])
(m/=> call [:=> [:cat schema/AppModule schema/Catalog :any [:maybe schema/PortResolution] :any :any :any] (schema/envelope :any)])
(m/=> control [:=> [:cat schema/AppModule schema/Catalog :any [:maybe schema/PortResolution] :any :any :any] (schema/envelope :any)])
