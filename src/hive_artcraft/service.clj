(ns hive-artcraft.service
  "Native-app commands (catalog/doctor/call/control) driven by the app module's data:
   its catalog resource and its transport shape. :port is live through an injected or socket
   ControlTransport; :control-file is live through the control-file transport once
   :apps {\"<id>\" {:control-file PATH}} is configured (calls become ui.command {id});
   :none has no external control surface and refuses with the remedy."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [malli.core :as m]
            [hive-artcraft.schema :as schema]
            [hive-artcraft.core :as core]
            [hive-artcraft.port :as port]
            [hive-artcraft.transport.socket :as socket]
            [hive-artcraft.transport.control-file :as control-file]))

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
    :control-file (str "Start `" (:app/id module) " --control ~/." (:app/id module) "-control.json` (random port + token,"
                       " ui.* methods) and set :apps {\"" (:app/id module) "\" {:control-file \"~/." (:app/id module)
                       "-control.json\"}}. The file must be yours, mode 600 and not a symlink.")
    :none (str (:app/label module) " has no external control surface (its commands are in-process IPC);"
               " it is catalog-only. Drive it through its own UI, or ask upstream for an automation surface.")))

(defn resolve-transport
  "Injected :transport wins; a :port app gets a loopback socket on its resolved port; a :control-file
   app with a configured :control-file gets the control-file transport; anything else gets nil."
  [module app-cfg port-res]
  (or (:transport app-cfg)
      (case (:app/transport module)
        :port (when port-res
                (socket/socket-transport (cond-> {:port (:port port-res)}
                                           (:control-host app-cfg) (assoc :host (:control-host app-cfg)))))
        :control-file (when (:control-file app-cfg)
                        (control-file/control-file-transport
                         (cond-> {:control-file (:control-file app-cfg)}
                           (:control-host app-cfg) (assoc :host (:control-host app-cfg)))))
        nil)))

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
  "Report catalog size, transport shape, whether a transport is live, and the remedy. A :control-file
   transport also reports whether its file is readable and trusted (never the token)."
  [module entries transport port-res]
  (let [file (when (= :control-file (:app/transport module)) (get-in transport [:config :control-file]))
        probe (when file (control-file/read-control-file file))]
    {:ok (cond-> {:app (:app/id module)
                  :shape (:app/transport module)
                  :catalog {:engine (count (:engine entries)) :control (count (:control entries))}
                  :port port-res
                  :transport (if transport :ready :absent)
                  :hint (cond (:error probe) (get-in probe [:error :hint])
                              transport "Transport ready; calls go to the running app."
                              :else (shape-remedy module port-res))}
           (= :none (:app/transport module)) (assoc :status :no-control-surface)
           file (assoc :control-file {:path file
                                      :status (if (:error probe) (get-in probe [:error :kind]) :trusted)
                                      :port (get-in probe [:ok :port])}))}))

(defn- send!
  [module transport built port-res]
  (cond
    (:error built) built
    (= :none (:app/transport module))
    (core/refusal :artcraft/unsupported-shape (shape-remedy module port-res))
    (nil? transport) (core/refusal :artcraft/no-transport (shape-remedy module port-res))
    :else (try (core/response-line (:app/id module) (port/send-request transport (:ok built)))
               (catch Exception e
                 (core/refusal :artcraft/transport-failed
                               (str "Check that " (:app/id module) " is running with --control: " (ex-message e)))))))

(defn ui-command-request
  "A :control-file app has no engine.execute: an engine command becomes ui.command {id}
   (non-empty params ride along as {params})."
  [built]
  (if (:error built)
    built
    (let [{:keys [id params]} (:ok built)
          command (:command params)
          extra (:params params)]
      {:ok {:id id :method "ui.command"
            :params (cond-> {:id command} (seq extra) (assoc :params extra))}})))

(defn call
  "Validate an engine command against the catalog, then send it through the app's transport.
   :control-file apps receive it as ui.command {id}."
  [module entries transport port-res command params request-id]
  (let [built (core/request entries (:app/id module) command params request-id)]
    (send! module transport
           (if (= :control-file (:app/transport module)) (ui-command-request built) built)
           port-res)))

(defn control
  "Validate a catalogued control method, then send it through the app's transport."
  [module entries transport port-res method params request-id]
  (send! module transport (core/control-request entries (:app/id module) method params request-id) port-res))

(m/=> load-catalog [:=> [:cat schema/AppModule] (schema/envelope schema/Catalog)])
(m/=> shape-remedy [:=> [:cat schema/AppModule [:maybe schema/PortResolution]] :string])
(m/=> resolve-transport [:=> [:cat schema/AppModule :map [:maybe schema/PortResolution]] :any])
(m/=> catalog [:=> [:cat schema/AppModule schema/Catalog [:maybe :string]] (schema/envelope :any)])
(m/=> doctor [:=> [:cat schema/AppModule schema/Catalog :any [:maybe schema/PortResolution]] (schema/envelope :map)])
(m/=> ui-command-request [:=> [:cat (schema/envelope schema/Request)] (schema/envelope schema/Request)])
(m/=> call [:=> [:cat schema/AppModule schema/Catalog :any [:maybe schema/PortResolution] :any :any :any] (schema/envelope :any)])
(m/=> control [:=> [:cat schema/AppModule schema/Catalog :any [:maybe schema/PortResolution] :any :any :any] (schema/envelope :any)])
