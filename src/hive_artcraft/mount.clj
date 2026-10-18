(ns hive-artcraft.mount
  "Mount an existing hive craft addon (hive-photocraft, hive-vectorcraft, ...) behind `artcraft`:
   construct it through its own manifest ctor, keep its one tool handler, and forward commands.
   No delegate code is copied; only the config handed to the ctor is derived here."
  (:require [hive-addon.protocol :as addon]
            [malli.core :as m]
            [hive-artcraft.schema :as schema]))

(defn mount-config
  "Config for the delegate ctor: the app's :apps override, plus the resolved control port only
   when every key the delegate's live port requires (:port-requires) is present."
  [module app-cfg port-res]
  (let [required (get-in module [:app/mount :port-requires] [])
        ready? (and port-res (every? #(contains? app-cfg %) required))]
    (cond-> (dissoc app-cfg :control-port)
      ready? (assoc :control-port (:port port-res)))))

(defn mount!
  "Resolve the module's ctor, construct and initialize the delegate addon, and return its tool."
  [module delegate-config]
  (let [{:keys [ctor addon-id]} (:app/mount module)]
    (try
      (let [instance ((requiring-resolve ctor) delegate-config)
            init (addon/initialize! instance delegate-config)
            tool (first (addon/tools instance))]
        (cond
          (not (:success? init))
          {:error {:kind :artcraft/mount-failed
                   :hint (str addon-id " refused to initialize: " (pr-str (:errors init)))}}
          (not (fn? (:handler tool)))
          {:error {:kind :artcraft/mount-failed
                   :hint (str addon-id " exposes no tool handler; check its version.")}}
          :else {:ok {:addon instance :tool tool}}))
      (catch Exception e
        {:error {:kind :artcraft/mount-failed
                 :hint (str "Put " addon-id " (" ctor ") on the classpath: " (ex-message e))}}))))

(defn tool-result
  "Normalise a delegate's reply into an MCP tool result: MCP content passes through,
   an {:ok ..}/{:error ..} envelope is rendered the way hive-vectorcraft renders it."
  [reply]
  (cond
    (and (map? reply) (vector? (:content reply))) reply
    (and (map? reply) (:error reply))
    {:isError true :content [{:type "text"
                              :text (str (get-in reply [:error :kind]) ": " (get-in reply [:error :hint])
                                         (when-let [msg (:message reply)] (str "\n" msg)))}]}
    (and (map? reply) (contains? reply :ok)) {:content [{:type "text" :text (pr-str (:ok reply))}]}
    :else {:content [{:type "text" :text (pr-str reply)}]}))

(defn forward
  "Send string-keyed tool args (minus \"app\") to the mounted tool's own handler."
  [mounted args]
  (try
    (tool-result ((get-in mounted [:tool :handler]) (dissoc args "app")))
    (catch Exception e
      (tool-result {:error {:kind :artcraft/delegate-failed
                            :hint (str "The mounted tool threw: " (ex-message e))}}))))

(defn unmount!
  "Shut the delegate addon down."
  [mounted]
  (when-let [instance (:addon mounted)] (addon/shutdown! instance))
  nil)

(m/=> mount-config [:=> [:cat schema/AppModule :map [:maybe schema/PortResolution]] :map])
(m/=> mount! [:=> [:cat schema/AppModule :map] (schema/envelope [:map [:addon :any] [:tool :map]])])
(m/=> tool-result [:=> [:cat :any] schema/ToolResult])
(m/=> forward [:=> [:cat :map :map] schema/ToolResult])
(m/=> unmount! [:=> [:cat [:maybe :map]] :nil])
