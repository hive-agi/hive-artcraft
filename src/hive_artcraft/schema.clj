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

(def AppModule
  "One Crafting App, as data. :mount delegates every command to an existing hive addon's tool
   handler; :native is served by hive-artcraft from its catalog resource and transport shape.
   :port-requires names AppConfig keys a delegate needs before a port may be handed to it
   (PhotoCraft's control channel needs :token-ref)."
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
    [:app/upstream {:optional true} :string]]
   [:fn {:error/message ":port apps declare :app/default-port and :app/port-env"}
    #(or (not= :port (:app/transport %)) (boolean (and (:app/default-port %) (:app/port-env %))))]
   [:fn {:error/message ":mount apps declare :app/mount {:ctor :addon-id}"}
    #(or (not= :mount (:app/kind %)) (contains? % :app/mount))]
   [:fn {:error/message ":native apps declare :app/catalog-resource"}
    #(or (not= :native (:app/kind %)) (contains? % :app/catalog-resource))]])

(def AppConfig
  "Per-app override under the addon config's :apps {\"<app-id>\" {...}}."
  [:map
   [:control-port {:optional true} [:int {:min 1 :max 65535}]]
   [:control-host {:optional true} :string]
   [:control-file {:optional true} :string]
   [:token-ref {:optional true} :map]
   [:transport {:optional true} :any]])

(def CatalogEntry
  [:map [:id :string] [:label :string]])

(def Catalog
  "Native-app catalog resource: the vectorcraft-shaped extraction (engine commands + control methods)."
  [:map [:engine [:vector CatalogEntry]]
   [:control [:vector :string]]
   [:mcp {:optional true} [:vector :map]]
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
