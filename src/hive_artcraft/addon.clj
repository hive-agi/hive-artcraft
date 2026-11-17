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
            [hive-artcraft.service :as service]
            [hive-addon.registry.commands :as cmds]
            [clojure.data.json :as json]))

(def commands
  "The closed command vocabulary of the `artcraft` tool."
  ["apps" "catalog" "doctor" "call" "control"])

(defn- env [k] (System/getenv k))

(defn stringify-args
  "MCP args may arrive keyword- or string-keyed; delegates expect string keys. A client that
   still holds the host tool's schema from before `artcraft` was contributed sends `params`
   as JSON text, so a string that decodes to a JSON object becomes that map."
  [args]
  (let [args (into {} (map (fn [[k v]] [(if (keyword? k) (name k) (str k)) v])) args)
        p (get args "params")]
    (if (string? p)
      (let [decoded (try (json/read-str p) (catch Exception _ ::bad))]
        (if (map? decoded) (assoc args "params" decoded) args))
      args)))

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

(defn dispatch-native
  "Serve a :native app's command from its catalog and transport; call/control get the same
   id/params defaults as mounted apps (mount/with-request-defaults)."
  [{:keys [module port ok]} args]
  (let [{:keys [catalog transport]} ok
        args (mount/with-request-defaults args)]
    (case (get args "command")
      "catalog" (service/catalog module catalog (get args "query"))
      "doctor" (service/doctor module catalog transport port)
      "call" (service/call module catalog transport port (get args "engine_command") (get args "params") (get args "id"))
      "control" (service/control module catalog transport port (get args "method") (get args "params") (get args "id")))))

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

(def host-tool
  "The host tool `artcraft` is contributed into: `code command=\"artcraft <verb>\" app=...`."
  "code")

(defn contribution
  "The `artcraft` subdomain for the host tool: one verb per command, each routed through
   dispatch against whatever is mounted in STATE at call time. Params (with the `app` enum)
   come from the same descriptor `tool` builds."
  [state]
  (let [{:keys [description inputSchema]} (tool {} (vec (:order @state)))]
    {"artcraft"
     {:description description
      :params (dissoc (:properties inputSchema) "command")
      :handler (into {}
                     (map (fn [verb]
                            [(keyword verb)
                             (fn [args]
                               (let [{:keys [mounted order]} @state]
                                 (dispatch (or mounted {}) (or order [])
                                           (assoc (dissoc (stringify-args args) "command") "command" verb))))]))
                     commands)}}))

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
          (cmds/contribute! host-tool "hive.artcraft" (contribution state))
          {:success? true :errors [] :metadata {:commands {host-tool ["artcraft"]} :apps order}}))))
  (shutdown! [_]
    (cmds/retract! host-tool "hive.artcraft")
    (doseq [[_ app] (:mounted @state)] (mount/unmount! (:ok app)))
    (reset! state {})
    nil)
  (tools [_] [])
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

(m/=> contribution [:=> [:cat :any] [:map-of :string [:map [:description :string] [:params :map] [:handler [:map-of :keyword ifn?]]]]])

(m/=> stringify-args [:=> [:cat [:maybe :map]] [:map-of :string :any]])
(m/=> mount-app [:=> [:cat schema/AppModule :map ifn?] :map])
(m/=> app-row [:=> [:cat :map] :map])
(m/=> dispatch-native [:=> [:cat :map :map] (schema/envelope :any)])
(m/=> dispatch [:=> [:cat :map [:vector :string] [:maybe :map]] schema/ToolResult])
(m/=> tool [:=> [:cat :map [:vector :string]] :map])
(m/=> mount-all [:=> [:cat :map [:maybe :map] ifn?] :map])
(m/=> addon-ctor [:=> [:cat [:maybe :map]] :any])
