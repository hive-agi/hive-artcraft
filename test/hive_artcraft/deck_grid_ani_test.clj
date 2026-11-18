(ns hive-artcraft.deck-grid-ani-test
  "DeckCraft (7983) and GridCraft (7985): :native :port modules served from catalogs that the
   shared extractor derives from the reference clones. AniCraft: :native :none, catalog-only
   (its automation is stdio-only). Never starts any of them."
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
            [artcraft-extract :as x]
            [extract-anicraft :as ani]))

(def port-apps
  {"deckcraft" {:port 7983 :env "DECKCRAFT_CONTROL_PORT"
                :revision "497582406d51498c5da4cf7e96d89e45e5bf968b"
                :counts {:engine 329 :mcp 25 :control 26}
                :clone "../clones-ref/deckcraft" :extract x/extract}
   "gridcraft" {:port 7985 :env "GRIDCRAFT_CONTROL_PORT"
                :revision "d60709a1738d4f02c7f507848441306c0aacf692"
                :counts {:engine 386 :mcp 22 :control 34}
                :clone "../clones-ref/gridcraft" :extract x/extract}})

(def anicraft
  {:revision "20a45a47f62c2458c0f424db5507e10445365d51"
   :counts {:engine 360 :mcp 5 :control 0}
   :clone ani/default-root :extract ani/extract})

(def apps (assoc port-apps "anicraft" anicraft))

(defn module [id] (get-in (registry/load-registry) [:ok :by-id id]))

(defn running []
  (doto (addon/addon-ctor {:getenv (constantly nil)}) (protocol/initialize! {})))

(defn handler [instance]
  (fn [args] (let [{:keys [mounted order]} @(:state instance)] (addon/dispatch (or mounted {}) (or order []) args))))

(defn read-text [result] (edn/read-string (-> result :content first :text)))

(defn- per-app [command]
  (let [instance (running)]
    (try (into (sorted-map)
               (for [id (sort (keys apps))]
                 [id (read-text ((handler instance) {"app" id "command" command}))]))
         (finally (protocol/shutdown! instance)))))

(deftest modules-follow-the-contract
  (doseq [[id {:keys [port env]}] port-apps]
    (testing id
      (let [mod (module id)]
        (is (= {:ok mod} (schema/validate-module mod)))
        (is (= [:native :port port env (str "hive_artcraft/catalogs/" id ".edn")]
               ((juxt :app/kind :app/transport :app/default-port :app/port-env :app/catalog-resource) mod))))))
  (let [mod (module "anicraft")]
    (is (= {:ok mod} (schema/validate-module mod)))
    (is (= [:native :none nil nil "hive_artcraft/catalogs/anicraft.edn"]
           ((juxt :app/kind :app/transport :app/default-port :app/port-env :app/catalog-resource) mod)))))

(deftest no-module-claims-a-token-ref
  ;; deckcraft's control port has no auth; gridcraft's wants a first-line auth handshake, which
  ;; is not what :app/token-ref puts on the wire (a top-level "token" per request): it declares
  ;; :app/auth {:mode :handshake} instead (socket_auth_test covers the wire).
  (doseq [id (keys apps)]
    (is (not (contains? (module id) :app/token-ref)) id))
  (is (nil? (service/auth-spec (module "deckcraft") {})))
  (is (= :handshake (:mode (service/auth-spec (module "gridcraft") {})))))

(deftest catalogs-are-valid-and-pinned
  (doseq [[id {:keys [revision counts]}] apps]
    (testing id
      (let [{:keys [ok]} (service/load-catalog (module id))]
        (is (m/validate schema/Catalog ok))
        (is (= revision (:reference-revision ok)))
        (is (= counts (update-vals (select-keys ok [:engine :mcp :control]) count)))
        (is (apply distinct? (map :id (:engine ok))))
        (is (every? string? (map :params (:engine ok))))))))

(deftest port-apps-speak-engine-execute
  (doseq [id (keys port-apps)]
    (is (some #{"engine.execute"} (:control (:ok (service/load-catalog (module id))))) id)))

(deftest anicraft-catalog-is-commands-and-stdio-tools
  (let [{:keys [ok]} (service/load-catalog (module "anicraft"))]
    (is (= [] (:control ok)))
    (is (= ["anicraft_commands" "anicraft_run" "anicraft_run_script" "anicraft_document" "anicraft_render"]
           (mapv :name (:mcp ok))))
    (is (every? #(re-matches #"[a-z_]+(\.[a-z0-9_]+)+" (:id %)) (:engine ok)))
    (is (some #(= "file.save_as" (:id %)) (:engine ok)) "multi-line CommandSpec literals are read")
    (is (some #(= "desktop.integrate" (:id %)) (:engine ok)) "4-arg c( helpers are read")))

(deftest catalogs-reproduce-from-the-clones
  (let [present (filter (fn [[_ {:keys [clone]}]] (.isDirectory (io/file clone))) apps)]
    (if (empty? present)
      (is (empty? present) "no reference clone checked out: reproduction skipped")
      (doseq [[id {:keys [clone extract]}] present]
        (testing id
          (is (= (edn/read-string (slurp (io/resource (str "hive_artcraft/catalogs/" id ".edn"))))
                 (extract clone))))))))

(deftest apps-port-resolution
  (doseq [[id {:keys [port env]}] port-apps]
    (is (= {:port port :source :default} (registry/resolve-port (module id) {} {})))
    (is (= {:port 9100 :source :env} (registry/resolve-port (module id) {} {env "9100"})))
    (is (= {:port 9200 :source :config} (registry/resolve-port (module id) {:control-port 9200} {env "9100"})))))

(deftest calls-without-a-running-app-refuse
  (let [h (handler (running))]
    (doseq [id (keys apps)]
      (is (:isError (h {"app" id "command" "call" "engine_command" "no.such.command"})))
      (is (:isError (h {"app" id "command" "control" "method" "app.format-disk"}))))))

(deftest anicraft-has-no-control-surface
  (let [h (handler (running))
        doctor (read-text (h {"app" "anicraft" "command" "doctor"}))
        r (h {"app" "anicraft" "command" "call" "engine_command" "file.save"})]
    (is (= [:none :no-control-surface] [(:shape doctor) (:status doctor)]))
    (is (re-find #"no external control surface" (:hint doctor)))
    (is (:isError r))
    (is (re-find #"unsupported-shape" (-> r :content first :text)))))

(deftest-golden deck-grid-ani-catalog-overview "test/golden/deck-grid-ani-catalog.edn"
  (per-app "catalog"))

(deftest-golden deck-grid-ani-doctor "test/golden/deck-grid-ani-doctor.edn"
  (per-app "doctor"))

(deftest-golden deck-grid-control-vocabulary "test/golden/deck-grid-control.edn"
  (into (sorted-map)
        (for [id (sort (keys port-apps))]
          [id (:control (:ok (service/load-catalog (module id))))])))
