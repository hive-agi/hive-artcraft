(ns hive-artcraft.light-design-test
  "LightCraft (7980) and DesignCraft (7981): :native :port modules served from catalogs that
   dev/extract_<app>.clj derives from the reference clones. Never starts either app."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [malli.core :as m]
            [hive-test.golden :refer [deftest-golden]]
            [hive-addon.protocol :as protocol]
            [hive-artcraft.addon :as addon]
            [hive-artcraft.registry :as registry]
            [hive-artcraft.schema :as schema]
            [hive-artcraft.service :as service]
            [artcraft-extract :as x]))

(def apps
  {"lightcraft" {:port 7980 :env "LIGHTCRAFT_CONTROL_PORT"
                 :revision "7bb9165c6bc46652db41ef5e19054a2bb017435f"
                 :counts {:engine 391 :mcp 28 :control 28}
                 :clone "../clones-ref/lightcraft"}
   "designcraft" {:port 7981 :env "DESIGNCRAFT_CONTROL_PORT"
                  :revision "47ef1ec411d53e98a31c576d80a1d0e20ef5eb04"
                  :counts {:engine 567 :mcp 26 :control 30}
                  :clone "../clones-ref/designcraft"}})

(defn module [id] (get-in (registry/load-registry) [:ok :by-id id]))

(defn running []
  (doto (addon/addon-ctor {:getenv (constantly nil)}) (protocol/initialize! {})))

(defn handler [instance]
  (fn [args] (let [{:keys [mounted order]} @(:state instance)] (addon/dispatch (or mounted {}) (or order []) args))))

(defn read-text [result] (edn/read-string (-> result :content first :text)))

(defn overview [id]
  (let [instance (running)]
    (try (read-text ((handler instance) {"app" id "command" "catalog"}))
         (finally (protocol/shutdown! instance)))))

(deftest modules-follow-the-contract
  (doseq [[id {:keys [port env]}] apps]
    (testing id
      (let [mod (module id)]
        (is (= {:ok mod} (schema/validate-module mod)))
        (is (= [:native :port port env (str "hive_artcraft/catalogs/" id ".edn")]
               ((juxt :app/kind :app/transport :app/default-port :app/port-env :app/catalog-resource) mod)))))))

(deftest catalogs-are-valid-and-pinned
  (doseq [[id {:keys [revision counts]}] apps]
    (testing id
      (let [{:keys [ok]} (service/load-catalog (module id))]
        (is (m/validate schema/Catalog ok))
        (is (= revision (:reference-revision ok)))
        (is (= counts (update-vals (select-keys ok [:engine :mcp :control]) count)))
        (is (apply distinct? (map :id (:engine ok))))
        (is (every? string? (map :params (:engine ok))))
        (is (some #{"engine.execute"} (:control ok)))))))

(deftest designcraft-noundo-arm-is-extracted
  (let [engine (:engine (:ok (service/load-catalog (module "designcraft"))))]
    (is (= 567 (count engine)))
    ;; rulers.rs declares its commands only through the multi-line `cmd!(noundo` arm.
    (is (seq (filter #(= "crates/engine/src/cmd/rulers.rs" (:source %)) engine)))
    (is (= "\"a.b\"" (x/strip-arm "query \"a.b\"") (x/strip-arm "noundo \"a.b\"")
           (x/strip-arm "noundo\n  \"a.b\"") (x/strip-arm "\"a.b\"")))))

(deftest catalogs-reproduce-from-the-clones
  (doseq [[id {:keys [clone]}] apps
          :when (.isDirectory (io/file clone))]
    (testing id
      (is (= (edn/read-string (slurp (io/resource (str "hive_artcraft/catalogs/" id ".edn"))))
             (x/extract clone))))))

(deftest apps-port-resolution
  (doseq [[id {:keys [port env]}] apps]
    (is (= {:port port :source :default} (registry/resolve-port (module id) {} {})))
    (is (= {:port 9100 :source :env} (registry/resolve-port (module id) {} {env "9100"})))
    (is (= {:port 9200 :source :config} (registry/resolve-port (module id) {:control-port 9200} {env "9100"})))))

(deftest calls-without-a-running-app-refuse
  (let [h (handler (running))]
    (doseq [id (keys apps)]
      (is (:isError (h {"app" id "command" "call" "engine_command" "no.such.command"})))
      (is (:isError (h {"app" id "command" "control" "method" "app.format-disk"}))))))

(deftest-golden lightcraft-catalog-overview "test/golden/lightcraft-catalog.edn"
  (overview "lightcraft"))

(deftest-golden designcraft-catalog-overview "test/golden/designcraft-catalog.edn"
  (overview "designcraft"))

(deftest-golden light-design-control-vocabulary "test/golden/light-design-control.edn"
  (into (sorted-map)
        (for [id (keys apps)]
          [id (:control (:ok (service/load-catalog (module id))))])))

(deftest-golden light-design-doctor "test/golden/light-design-doctor.edn"
  (let [instance (running)]
    (try (into (sorted-map)
               (for [id (keys apps)]
                 [id (read-text ((handler instance) {"app" id "command" "doctor"}))]))
         (finally (protocol/shutdown! instance)))))
