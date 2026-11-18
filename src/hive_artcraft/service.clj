(ns hive-artcraft.service
  "Native-app commands (catalog/doctor/call/control) driven by the app module's data:
   its catalog resource and its transport shape. :port is live through an injected or socket
   ControlTransport; :control-file is live through the control-file transport once
   :apps {\"<id>\" {:control-file PATH}} is configured (calls become ui.command {id});
   :none has no external control surface and refuses with the remedy."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
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

(defn token-ref
  "The TokenRef a :port app's requests must carry: :apps {\"<id>\" {:token-ref ...}} wins over the
   module's :app/token-ref default (or its :app/auth :ref); nil for an app whose port does not
   authenticate."
  [module app-cfg]
  (when (= :port (:app/transport module))
    (or (:token-ref app-cfg) (:app/token-ref module) (get-in module [:app/auth :ref]))))

(defn auth-spec
  "The AuthSpec a :port app's transport speaks: the module's :app/auth (its :ref replaced by an
   :apps {\"<id>\" {:token-ref ...}} override), or the :app/token-ref shorthand as per-request
   \"token\" of 64 hex; nil when the port does not authenticate."
  [module app-cfg]
  (when (= :port (:app/transport module))
    (let [override (:token-ref app-cfg)]
      (cond
        (:app/auth module) (cond-> (:app/auth module) override (assoc :ref override))
        (token-ref module app-cfg) {:mode :per-request :field "token" :ref (token-ref module app-cfg) :secret :hex64}))))

(defn token-remedy
  "How to give an authenticating :port app and the hive JVM the same control secret. An app that
   writes its own token/key file (:app/auth :file-pattern) needs nothing when hive runs as the
   same user on the same machine."
  [module]
  (let [id (:app/id module)
        auth (:app/auth module)
        field (or (:field auth) "token")
        ref (or (:app/token-ref module) (:ref auth))
        env (if (= :env (:scheme ref)) (:name ref) (str (str/upper-case id) "_CONTROL_" (str/upper-case field)))]
    (if (seq (:file-pattern auth))
      (str "Start `" id " --control " (:app/default-port module) "` as the user hive runs as: the app writes its "
           field " to " (str/join " or " (:file-pattern auth)) " ({port} = the control port) and hive reads it there"
           " per connection. Or start both with the same `" env "` (16+ printable characters), or point"
           " :apps {\"" id "\" {:token-ref {:scheme :file :path \"...\"}}} at a file only you can read.")
      (str "Start the app with a token and give hive the same one: `export " env
           "=\"$(python3 -c 'import secrets;print(secrets.token_hex(32))')\"` then `" id " --control "
           (:app/default-port module) "` from a shell whose environment the hive JVM inherits, or keep the token"
           " in a file only you can read and set :apps {\"" id "\" {:token-ref {:scheme :file :path \"~/.private/"
           id "-control.token\"}}}."))))

(defn resolve-transport
  "Injected :transport wins; a :port app gets a loopback socket on its resolved port (carrying its
   AuthSpec and `getenv` when its port authenticates); a :control-file app with a configured
   :control-file gets the control-file transport; anything else gets nil."
  ([module app-cfg port-res] (resolve-transport module app-cfg port-res nil))
  ([module app-cfg port-res getenv]
   (or (:transport app-cfg)
       (case (:app/transport module)
         :port (when port-res
                 (let [auth (auth-spec module app-cfg)]
                   (socket/socket-transport (cond-> {:port (:port port-res)}
                                              (:control-host app-cfg) (assoc :host (:control-host app-cfg))
                                              auth (assoc :auth auth)
                                              (and auth getenv) (assoc :getenv getenv)))))
         :control-file (when (:control-file app-cfg)
                         (control-file/control-file-transport
                          (cond-> {:control-file (:control-file app-cfg)}
                            (:control-host app-cfg) (assoc :host (:control-host app-cfg)))))
         nil))))

(defn catalog
  "Describe the app's pinned vocabulary; query selects engine, mcp, control, unexpanded or one
   exact command id. The overview counts unexpanded sites when the catalog declares any."
  [module entries query]
  (case query
    "engine" {:ok (:engine entries)}
    "mcp" {:ok (vec (:mcp entries))}
    "control" {:ok (:control entries)}
    "unexpanded" {:ok (vec (:unexpanded entries))}
    nil {:ok (cond-> {:app (:app/id module) :revision (:reference-revision entries)
                      :engine (count (:engine entries)) :mcp (count (:mcp entries))
                      :control (count (:control entries))}
               (contains? entries :unexpanded)
               (assoc :unexpanded (reduce + (map :count (:unexpanded entries)))))}
    (core/lookup entries (:app/id module) query)))

(defn doctor
  "Report catalog size (and any unexpanded static-extraction gap), transport shape, whether a
   transport is live, and the remedy. A :control-file transport also reports whether its file is
   readable and trusted, and an authenticating :port transport whether its secret resolves and
   from where (never the secret): with none found it says so and how to fix it. An :app/auth app
   also reports its auth :mode and :field."
  [module entries transport port-res]
  (let [file (when (= :control-file (:app/transport module)) (get-in transport [:config :control-file]))
        probe (when file (control-file/read-control-file file))
        live-auth (when (= :port (:app/transport module)) (socket/auth-of (or (:config transport) {})))
        auth (or live-auth (auth-spec module {}))
        tref (:ref auth)
        tprobe (when (and live-auth (:port port-res))
                 (socket/resolve-secret live-auth (:port port-res)
                                        (or (get-in transport [:config :getenv]) #(System/getenv ^String %))))
        token-hint (when (:error tprobe) (str (get-in tprobe [:error :hint]) " " (token-remedy module)))]
    {:ok (cond-> {:app (:app/id module)
                  :shape (:app/transport module)
                  :catalog (cond-> {:engine (count (:engine entries)) :control (count (:control entries))}
                             (contains? entries :unexpanded)
                             (assoc :unexpanded (reduce + (map :count (:unexpanded entries)))
                                    :unexpanded-hint (str "The static catalog does not expand "
                                                          (count (:unexpanded entries))
                                                          " macro/helper sites; query=unexpanded lists them, and the running"
                                                          " app's engine.commands control method returns the full registry.")))
                  :port port-res
                  :transport (if transport :ready :absent)
                  :hint (cond (:error probe) (get-in probe [:error :hint])
                              token-hint token-hint
                              transport "Transport ready; calls go to the running app."
                              :else (shape-remedy module port-res))}
           (= :none (:app/transport module)) (assoc :status :no-control-surface)
           file (assoc :control-file {:path file
                                      :status (if (:error probe) (get-in probe [:error :kind]) :trusted)
                                      :port (get-in probe [:ok :port])})
           auth (assoc :token (cond-> {:ref tref
                                       :status (cond (nil? tprobe) :unchecked
                                                     (:error tprobe) (get-in tprobe [:error :kind])
                                                     :else :configured)}
                                (:app/auth module) (assoc :mode (:mode auth) :field (:field auth))
                                (and (:app/auth module) (:source tprobe)) (assoc :source (:source tprobe))
                                token-hint (assoc :fix (token-remedy module))))
           (:app/call-method module) (assoc :call-method (:app/call-method module)))}))

(defn- send!
  [module transport built port-res]
  (cond
    (:error built) built
    (= :none (:app/transport module))
    (core/refusal :artcraft/unsupported-shape (shape-remedy module port-res))
    (nil? transport) (core/refusal :artcraft/no-transport (shape-remedy module port-res))
    :else (try (core/response-line (:app/id module) (port/send-request transport (:ok built)))
               (catch Exception e
                 (if (:refusal? (ex-data e))
                   (core/refusal (:kind (ex-data e)) (str (ex-message e) " " (token-remedy module)))
                   (core/refusal :artcraft/transport-failed
                                 (str "Check that " (:app/id module) " is running with --control: " (ex-message e))))))))

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

(defn command-run-request
  "An app whose engine commands go as command.run (MaterialCraft, TextureCraft): an engine
   command becomes command.run {id, params} (params left out when empty)."
  [built]
  (if (:error built)
    built
    (let [{:keys [id params]} (:ok built)]
      {:ok {:id id :method "command.run"
            :params (cond-> {:id (:command params)} (seq (:params params)) (assoc :params (:params params)))}})))

(defn call
  "Validate an engine command against the catalog, then send it through the app's transport.
   :control-file apps receive it as ui.command {id}; an :app/call-method \"command.run\" app as
   command.run {id, params}; every other app as engine.execute {command, params}."
  [module entries transport port-res command params request-id]
  (let [built (core/request entries (:app/id module) command params request-id)]
    (send! module transport
           (cond
             (= :control-file (:app/transport module)) (ui-command-request built)
             (= "command.run" (:app/call-method module)) (command-run-request built)
             :else built)
           port-res)))

(defn control
  "Validate a catalogued control method, then send it through the app's transport."
  [module entries transport port-res method params request-id]
  (send! module transport (core/control-request entries (:app/id module) method params request-id) port-res))

(m/=> auth-spec [:=> [:cat schema/AppModule :map] [:maybe schema/AuthSpec]])
(m/=> command-run-request [:=> [:cat (schema/envelope schema/Request)] (schema/envelope schema/Request)])
(m/=> auth-spec [:=> [:cat schema/AppModule :map] [:maybe schema/AuthSpec]])

(m/=> load-catalog [:=> [:cat schema/AppModule] (schema/envelope schema/Catalog)])
(m/=> shape-remedy [:=> [:cat schema/AppModule [:maybe schema/PortResolution]] :string])
(m/=> token-ref [:=> [:cat schema/AppModule :map] [:maybe :map]])
(m/=> token-remedy [:=> [:cat schema/AppModule] :string])
(m/=> resolve-transport [:function
                         [:=> [:cat schema/AppModule :map [:maybe schema/PortResolution]] :any]
                         [:=> [:cat schema/AppModule :map [:maybe schema/PortResolution] [:maybe ifn?]] :any]])
(m/=> catalog [:=> [:cat schema/AppModule schema/Catalog [:maybe :string]] (schema/envelope :any)])
(m/=> doctor [:=> [:cat schema/AppModule schema/Catalog :any [:maybe schema/PortResolution]] (schema/envelope :map)])
(m/=> ui-command-request [:=> [:cat (schema/envelope schema/Request)] (schema/envelope schema/Request)])
(m/=> call [:=> [:cat schema/AppModule schema/Catalog :any [:maybe schema/PortResolution] :any :any :any] (schema/envelope :any)])
(m/=> control [:=> [:cat schema/AppModule schema/Catalog :any [:maybe schema/PortResolution] :any :any :any] (schema/envelope :any)])
