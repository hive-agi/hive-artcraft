(ns hive-artcraft.schema
  "Malli value objects. AppModule is THE contract a Crafting App satisfies to mount under `artcraft`."
  (:require [malli.core :as m]
            [malli.error :as me]))

(def TransportShape
  "How hive reaches a running app.
   :port         - JSON-lines engine.execute on 127.0.0.1:<port> (`<app> --control PORT`). Live.
   :control-file - `--control <FILE>`: random port + token handshake, ui.* methods only. Contract slot; calls refuse.
   :none         - no external control surface (e.g. Tauri IPC). Catalog only; calls refuse."
  [:enum :port :control-file :none])

(def TokenRef
  "Where a control secret lives, never the secret itself: an environment variable of the hive JVM
   named <APP>_CONTROL_TOKEN or <APP>_CONTROL_KEY (so a config cannot point the channel at an
   unrelated secret), or a file only the user can access. The value is read at send time."
  [:or
   [:map [:scheme [:= :env]] [:name [:re #"^[A-Z][A-Z0-9_]*_CONTROL_(TOKEN|KEY)$"]]]
   [:map [:scheme [:= :file]] [:path [:re #"\S"]]]])

(def AuthSpec
  "How a :port app's control channel authenticates, as data.
   :mode :per-request - the secret rides on EVERY request as a top-level <field> (FilmCraft and
                        CADCraft \"token\", WordCraft \"key\").
   :mode :handshake   - the FIRST line of every connection is {\"id\":0,\"method\":\"auth\",
                        \"params\":{<field>: secret}} and must be answered ok before the request
                        (GridCraft, MaterialCraft, TextureCraft).
   :ref           - the environment variable (or file) tried first.
   :file-pattern  - files the app itself writes, tried in order when :ref yields nothing:
                    {port} is the control port, ${VAR} an environment variable (a pattern naming
                    an unset or empty one is skipped).
   :secret        - :printable (16 to 1024 printable ASCII, the default) or :hex64.
   The secret is read per connection, never logged, returned or put in an error."
  [:and
   [:map
    [:mode [:enum :handshake :per-request]]
    [:field [:enum "token" "key"]]
    [:ref {:optional true} TokenRef]
    [:file-pattern {:optional true} [:vector [:re #"\{port\}"]]]
    [:secret {:optional true} [:enum :printable :hex64]]]
   [:fn {:error/message ":app/auth names a :ref or a :file-pattern"}
    #(or (contains? % :ref) (seq (:file-pattern %)))]])

(def CallMethod
  "How an engine command goes on the wire: engine.execute {command, params} (default) or
   command.run {id, params?} (MaterialCraft, TextureCraft)."
  [:enum "engine.execute" "command.run"])

(def AppModule
  "One Crafting App, as data. :mount delegates every command to an existing hive addon's tool
   handler; :native is served by hive-artcraft from its catalog resource and transport shape.
   :port-requires names AppConfig keys a delegate needs before a port may be handed to it
   (PhotoCraft's control channel needs :token-ref). A :native :port app whose control port
   authenticates declares either :app/token-ref (shorthand for :app/auth {:mode :per-request
   :field \"token\" :ref <it> :secret :hex64}: FilmCraft, CADCraft) or :app/auth (an AuthSpec:
   GridCraft, MaterialCraft and TextureCraft handshake, WordCraft sends a per-window key).
   :apps {\"<id>\" {:token-ref ...}} overrides the reference. :app/call-method names the method an
   engine command goes out as (default engine.execute)."
  [:and
   [:map
    [:app/id [:re #"^[a-z][a-z0-9-]*$"]]
    [:app/label :string]
    [:app/summary :string]
    [:app/kind [:enum :mount :native]]
    [:app/transport TransportShape]
    [:app/catalog-resource {:optional true} :string]
    [:app/default-port {:optional true} [:int {:min 1 :max 65535}]]
    [:app/port-env {:optional true} [:re #"^[A-Z][A-Z0-9_]*$"]]
    [:app/mount {:optional true} [:map [:ctor qualified-symbol?] [:addon-id :string]
                                  [:port-requires {:optional true} [:vector :keyword]]]]
    [:app/token-ref {:optional true} TokenRef]
    [:app/auth {:optional true} AuthSpec]
    [:app/call-method {:optional true} CallMethod]
    [:app/upstream {:optional true} :string]]
   [:fn {:error/message ":port apps declare :app/default-port and :app/port-env"}
    #(or (not= :port (:app/transport %)) (boolean (and (:app/default-port %) (:app/port-env %))))]
   [:fn {:error/message ":mount apps declare :app/mount {:ctor :addon-id}"}
    #(or (not= :mount (:app/kind %)) (contains? % :app/mount))]
   [:fn {:error/message ":native apps declare :app/catalog-resource"}
    #(or (not= :native (:app/kind %)) (contains? % :app/catalog-resource))]
   [:fn {:error/message ":app/token-ref, :app/auth and :app/call-method are for :native :port apps; a :mount delegate takes :token-ref in its :apps config"}
    #(or (not (some (partial contains? %) [:app/token-ref :app/auth :app/call-method]))
         (and (= :native (:app/kind %)) (= :port (:app/transport %))))]
   [:fn {:error/message "declare :app/token-ref or :app/auth, not both"}
    #(not (and (contains? % :app/token-ref) (contains? % :app/auth)))]])

(def AppConfig
  "Per-app override under the addon config's :apps {\"<app-id>\" {...}}. :token-ref replaces the
   reference of the app's auth (its :app/token-ref or its :app/auth :ref)."
  [:map
   [:control-port {:optional true} [:int {:min 1 :max 65535}]]
   [:control-host {:optional true} :string]
   [:control-file {:optional true} :string]
   [:token-ref {:optional true} :map]
   [:transport {:optional true} :any]])

(def CatalogEntry
  [:map [:id :string] [:label :string]])

(def Catalog
  "Native-app catalog resource: the vectorcraft-shaped extraction (engine commands + control methods).
   :unexpanded (optional) lists command-building sites a static extractor did not expand
   (wrapper macros, helper fns), one {:macro :count :source} row per macro and file, so a
   partial :engine list is never mistaken for the whole registry."
  [:map [:engine [:vector CatalogEntry]]
   [:control [:vector :string]]
   [:mcp {:optional true} [:vector :map]]
   [:unexpanded {:optional true} [:vector [:map [:macro :string] [:count pos-int?] [:source :string]]]]
   [:reference-revision {:optional true} :string]])

(def ErrorValue
  [:map [:kind :keyword] [:hint :string]])

(def Request
  [:map [:id [:or :string [:and :int [:fn #(<= 0 %)]]]]
   [:method :string] [:params :map]])

(def PortResolution
  [:map [:port [:int {:min 1 :max 65535}]] [:source [:enum :config :env :default]]])

(def ToolResult
  [:map [:content [:vector [:map [:type :string] [:text :string]]]]
   [:isError {:optional true} :boolean]])

(defn envelope
  "Success-or-refusal envelope around `value`."
  [value]
  [:or [:map [:ok value]] [:map [:error ErrorValue]]])

(defn validate-module
  "Accept a module that satisfies AppModule, or refuse with the humanised reasons."
  [module]
  (if (m/validate AppModule module)
    {:ok module}
    {:error {:kind :artcraft/invalid-module
             :hint (str "Fix the app module " (pr-str (when (map? module) (:app/id module))) ": "
                        (pr-str (me/humanize (m/explain AppModule module))))}}))

(m/=> envelope [:=> [:cat :any] [:vector :any]])
(m/=> validate-module [:=> [:cat :any] (envelope AppModule)])
