(ns hive-artcraft.registry
  "The app registry is data: every classpath copy of hive_artcraft/apps.edn is read and
   concatenated, each module is validated against schema/AppModule, and per-app config and
   control ports are resolved here. Adding an app never touches this namespace."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [malli.core :as m]
            [hive-artcraft.schema :as schema]))

(def registry-resource
  "Classpath path of the app registry data list."
  "hive_artcraft/apps.edn")

(defn read-modules
  "Read and concatenate every classpath copy of `resource` (each one EDN vector of modules)."
  [resource]
  (let [urls (enumeration-seq (.getResources (.getContextClassLoader (Thread/currentThread)) resource))]
    (if (seq urls)
      {:ok (into [] (mapcat #(edn/read-string (slurp %))) (distinct urls))}
      {:error {:kind :artcraft/registry-missing
               :hint (str "Package " resource " on the classpath; it lists the app modules.")}})))

(defn index-modules
  "Validate modules and index them by :app/id in registry order; refuse invalid or duplicate ids."
  [modules]
  (let [invalid (keep #(:error (schema/validate-module %)) modules)
        ids (map :app/id modules)
        dupes (keep (fn [[id n]] (when (< 1 n) id)) (frequencies ids))]
    (cond
      (seq invalid) {:error (first invalid)}
      (seq dupes) {:error {:kind :artcraft/duplicate-app
                           :hint (str "App ids must be unique; remove the duplicate module for " (pr-str (vec dupes)) ".")}}
      :else {:ok {:order (vec ids) :by-id (zipmap ids modules)}})))

(defn load-registry
  "Read and index the classpath registry."
  ([] (load-registry registry-resource))
  ([resource]
   (let [read (read-modules resource)]
     (if (:error read) read (index-modules (:ok read))))))

(defn app-ids
  "Mounted app ids, in registry order."
  [registry]
  (:order registry))

(defn find-app
  "Look an app up by id, or refuse naming the mounted ones."
  [registry app]
  (if-let [module (get-in registry [:by-id app])]
    {:ok module}
    {:error {:kind :artcraft/unknown-app
             :hint (str "Unknown app " (pr-str app) "; mounted apps: "
                        (str/join ", " (app-ids registry))
                        ". Run artcraft command=apps.")}}))

(defn app-config
  "Per-app override from the addon config's :apps map (string or keyword keys)."
  [config app]
  (let [apps (:apps config)]
    (or (get apps app) (get apps (keyword app)) {})))

(defn resolve-port
  "Control port for a :port app: :apps config wins, then <APP>_CONTROL_PORT, then the module default."
  [module app-cfg getenv]
  (let [from-env (some-> (:app/port-env module) getenv parse-long)]
    (cond
      (:control-port app-cfg) {:port (:control-port app-cfg) :source :config}
      from-env {:port from-env :source :env}
      (:app/default-port module) {:port (:app/default-port module) :source :default})))

(m/=> read-modules [:=> [:cat :string] (schema/envelope [:vector :map])])
(m/=> index-modules [:=> [:cat [:sequential :any]] (schema/envelope :map)])
(m/=> load-registry [:function [:=> [:cat] (schema/envelope :map)]
                     [:=> [:cat :string] (schema/envelope :map)]])
(m/=> app-ids [:=> [:cat :map] [:vector :string]])
(m/=> find-app [:=> [:cat :map :any] (schema/envelope schema/AppModule)])
(m/=> app-config [:=> [:cat [:maybe :map] :string] :map])
(m/=> resolve-port [:=> [:cat schema/AppModule :map ifn?] [:maybe schema/PortResolution]])
