(ns hive-artcraft.addon
  "Single IAddon hive.artcraft with ONE tool, `artcraft`. Every app in the registry data list
   mounts under it: :mount apps forward to their own addon's tool handler, :native apps are
   served by hive-artcraft.service from their catalog and transport shape."
  (:require [hive-addon.protocol :as addon]
            [hive-help.core :as help]
            [malli.core :as m]
            [hive-artcraft.schema :as schema]
            [hive-artcraft.registry :as registry]
            [hive-artcraft.mount :as mount]
            [hive-artcraft.service :as service]))

(def commands
  "The closed command vocabulary of the `artcraft` tool."
  ["apps" "catalog" "doctor" "call" "control"])

(defn- env [k] (System/getenv k))

(defn stringify-args
  "MCP args may arrive keyword- or string-keyed; delegates expect string keys."
  [args]
  (into {} (map (fn [[k v]] [(if (keyword? k) (name k) (str k)) v])) args))

(defn mount-app
  "Bring one module up from its config: a :mount delegate or a :native catalog + transport."
  [module app-cfg getenv]
  (let [port-res (when (= :port (:app/transport module)) (registry/resolve-port module app-cfg getenv))]
    (case (:app/kind module)
      :mount (let [mounted (mount/mount! module (mount/mount-config module app-cfg port-res))]
               (merge {:module module :port port-res} mounted))
      :native (let [entries (service/load-catalog module)]
                (if (:error entries)
                  {:module module :port port-res :error (:error entries)}
                  {:module module :port port-res
                   :ok {:catalog (:ok entries)
                        :transport (service/resolve-transport module app-cfg port-res)}})))))

(defn app-row
  "One line of `artcraft command=apps`."
  [{:keys [module port error ok]}]
  (cond-> {:app (:app/id module) :label (:app/label module) :summary (:app/summary module)
           :kind (:app/kind module) :shape (:app/transport module)
           :status (if error :unavailable :mounted)}
    (:app/default-port module) (assoc :default-port (:app/default-port module)
                                      :port-env (:app/port-env module))
    port (assoc :port port)
    (get-in module [:app/mount :addon-id]) (assoc :delegate (get-in module [:app/mount :addon-id]))
    (:transport ok) (assoc :transport :ready)
    error (assoc :hint (:hint error))))

(defn- params-of [args] (if (contains? args "params") (get args "params") {}))
(defn- id-of [args] (if (contains? args "id") (get args "id") 0))

(defn dispatch-native
  "Serve a :native app's command from its catalog and transport."
  [{:keys [module port ok]} args]
  (let [{:keys [catalog transport]} ok]
    (case (get args "command")
      "catalog" (service/catalog module catalog (get args "query"))
      "doctor" (service/doctor module catalog transport port)
      "call" (service/call module catalog transport port (get args "engine_command") (params-of args) (id-of args))
      "control" (service/control module catalog transport port (get args "method") (params-of args) (id-of args)))))

(defn dispatch
  "Route one `artcraft` call over the mounted apps; always answers an MCP tool result."
  [mounted order args]
  (let [args (stringify-args args)
        command (get args "command")
        app (get args "app")]
    (cond
      (= "apps" command)
      (mount/tool-result {:ok (mapv #(app-row (get mounted %)) order)})

      (not (some #{command} commands))
      (mount/tool-result {:error {:kind :artcraft/unknown-tool-command :hint "Choose apps, catalog, doctor, call or control."}
                          :message (help/unknown-command {:tool "artcraft" :command (str command) :valid-commands commands})})

      (not (contains? mounted app))
      (mount/tool-result {:error {:kind :artcraft/unknown-app
                                  :hint (str "Pass app = one of " (pr-str order) "; run artcraft command=apps.")}})

      (:error (get mounted app))
      (mount/tool-result {:error (:error (get mounted app))})

      (= :mount (get-in mounted [app :module :app/kind]))
      (mount/forward (:ok (get mounted app)) args)

      :else (mount/tool-result (dispatch-native (get mounted app) args)))))

(defn tool
  "The one consolidated tool descriptor; `app` is an enum of the mounted apps."
  [mounted order]
  {:name "artcraft"
   :description (str "ArtCraft Crafting Apps under one verb. command=apps lists mounted apps and their transport shape; "
                     "with app=<id>: catalog (query = engine|mcp|control|<command id>), doctor (transport shape + remedy), "
                     "call (engine_command + params), control (method + params). call/control need the app running with --control.")
   :inputSchema {:type "object" :required ["command"] :additionalProperties false
                 :properties {"app" {:type "string" :enum (vec order) :description "Crafting App id (see command=apps)."}
                              "command" {:type "string" :enum commands}
                              "query" {:type "string" :description "Catalog section or exact engine command id."}
                              "engine_command" {:type "string" :description "Exact engine command id to execute."}
                              "method" {:type "string" :description "Control method for command=control."}
                              "params" {:type "object" :description "Engine or control parameters."}
                              "id" {:type ["integer" "string"] :description "Nonnegative integer or string request id."}}}
   :handler (fn [args] (dispatch mounted order args))})

(defn mount-all
  "Mount every registry app against the merged addon config."
  [reg config getenv]
  (into {} (map (fn [id] [id (mount-app (get-in reg [:by-id id]) (registry/app-config config id) getenv)]))
        (registry/app-ids reg)))

(defrecord ArtcraftAddon [state configuration]
  addon/IAddon
  (addon-id [_] "hive.artcraft")
  (addon-type [_] :native)
  (capabilities [_] #{:tools :health-reporting})
  (initialize! [_ cfg]
    (let [config (merge configuration (select-keys (or cfg {}) [:apps]))
          reg (or (:registry configuration) (registry/load-registry))]
      (if (:error reg)
        (do (reset! state {}) {:success? false :errors [(get-in reg [:error :hint])]})
        (let [order (registry/app-ids (:ok reg))]
          (reset! state {:order order :mounted (mount-all (:ok reg) config (or (:getenv configuration) env))})
          {:success? true :errors [] :metadata {:tools ["artcraft"] :apps order}}))))
  (shutdown! [_]
    (doseq [[_ app] (:mounted @state)] (mount/unmount! (:ok app)))
    (reset! state {})
    nil)
  (tools [_] (if-let [order (:order @state)] [(tool (:mounted @state) order)] []))
  (schema-extensions [_] {})
  (excluded-tools [_] #{})
  (hooks [_] {})
  (health [_]
    (let [rows (mapv #(app-row (get (:mounted @state) %)) (:order @state))]
      {:status (if (and (seq rows) (every? #(= :mounted (:status %)) rows)) :ok :degraded)
       :details {:apps rows}})))

(defn addon-ctor
  "Host manifest entry point. Config: {:apps {\"<app>\" AppConfig}}; :registry and :getenv are test seams."
  [config]
  (->ArtcraftAddon (atom {}) (or config {})))

(m/=> stringify-args [:=> [:cat [:maybe :map]] [:map-of :string :any]])
(m/=> mount-app [:=> [:cat schema/AppModule :map ifn?] :map])
(m/=> app-row [:=> [:cat :map] :map])
(m/=> dispatch-native [:=> [:cat :map :map] (schema/envelope :any)])
(m/=> dispatch [:=> [:cat :map [:vector :string] [:maybe :map]] schema/ToolResult])
(m/=> tool [:=> [:cat :map [:vector :string]] :map])
(m/=> mount-all [:=> [:cat :map [:maybe :map] ifn?] :map])
(m/=> addon-ctor [:=> [:cat [:maybe :map]] :any])
