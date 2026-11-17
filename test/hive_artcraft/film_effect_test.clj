(ns hive-artcraft.film-effect-test
  "FilmCraft (9876) and EffectCraft (9877): :native :port modules served from catalogs that
   dev/extract_<app>.clj derives from the reference clones. The static extractor parses cmd!
   and query! only; every other command-building site is listed under :unexpanded and is
   surfaced by catalog and doctor. Never starts either app."
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
            [film-effect-extract :as fe]
            [extract-filmcraft :as film]
            [extract-effectcraft :as effect]))

(def apps
  {"filmcraft" {:port 9876 :env "FILMCRAFT_CONTROL_PORT"
                :revision "93a88a839aae370740b09434fdec410abb8c24ca"
                :counts {:engine 192 :mcp 17 :control 24 :unexpanded 447}
                :clone "../clones-ref/filmcraft" :extract film/extract
                :wrappers #{"align_cmd!" "arrange_cmd!" "label_cmd!" "spec()"}}
   "effectcraft" {:port 9877 :env "EFFECTCRAFT_CONTROL_PORT"
                  :revision "4c38f78307e21a07021333c8a109e6fc49d66c3b"
                  :counts {:engine 680 :mcp 36 :control 24 :unexpanded 98}
                  :clone "../clones-ref/effectcraft" :extract effect/extract
                  :wrappers #{"style_cmd!" "view_cmd!" "fe!" "res!"}}})

(defn module [id] (get-in (registry/load-registry) [:ok :by-id id]))

(defn catalog-of [id] (:ok (service/load-catalog (module id))))

(defn running []
  (doto (addon/addon-ctor {:getenv (constantly nil)}) (protocol/initialize! {})))

(defn handler [instance]
  (fn [args] (let [{:keys [mounted order]} @(:state instance)] (addon/dispatch (or mounted {}) (or order []) args))))

(defn read-text [result] (edn/read-string (-> result :content first :text)))

(defn ask [args]
  (let [instance (running)]
    (try (read-text ((handler instance) args))
         (finally (protocol/shutdown! instance)))))

(deftest modules-follow-the-contract
  (doseq [[id {:keys [port env]}] apps]
    (testing id
      (let [mod (module id)]
        (is (= {:ok mod} (schema/validate-module mod)))
        (is (= [:native :port port env (str "hive_artcraft/catalogs/" id ".edn")]
               ((juxt :app/kind :app/transport :app/default-port :app/port-env :app/catalog-resource) mod)))))))

(deftest catalogs-are-valid-pinned-and-honest
  (doseq [[id {:keys [revision counts wrappers]}] apps]
    (testing id
      (let [c (catalog-of id)]
        (is (m/validate schema/Catalog c))
        (is (= revision (:reference-revision c)))
        (is (= counts (-> (update-vals (select-keys c [:engine :mcp :control]) count)
                          (assoc :unexpanded (reduce + (map :count (:unexpanded c)))))))
        (is (apply distinct? (map :id (:engine c))))
        (is (every? string? (map :params (:engine c))))
        (is (some #{"engine.execute"} (:control c)))
        (is (every? string? (map :name (:mcp c))))
        (testing "named wrapper macros are reported, never silently dropped"
          (is (every? (set (map :macro (:unexpanded c))) wrappers)))))))

(deftest effectcraft-vector-shortcut-arm-is-extracted
  (let [t "cmd!(\"time.next\", \"Next\", [\"Edit\"], [\"PageDown\", \"Cmd+ArrowRight\"], \"{}\", has_comp, |s, p| next(s, p))"]
    (is (= [{:id "time.next" :label "Next" :params "{}" :source "x.rs"}]
           (fe/engine-entries "x.rs" t)))))

(deftest catalogs-reproduce-from-the-clones
  (let [present (filter (fn [[_ {:keys [clone]}]] (.isDirectory (io/file clone))) apps)]
    (if (empty? present)
      (is (empty? present) "no reference clone checked out: reproduction skipped")
      (doseq [[id {:keys [clone extract]}] present]
        (testing id
          (is (= (edn/read-string (slurp (io/resource (str "hive_artcraft/catalogs/" id ".edn"))))
                 (extract clone))))))))

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

(deftest-golden film-effect-catalog-overview "test/golden/film-effect-catalog.edn"
  (into (sorted-map) (for [id (keys apps)] [id (ask {"app" id "command" "catalog"})])))

(deftest-golden film-effect-unexpanded "test/golden/film-effect-unexpanded.edn"
  (into (sorted-map) (for [id (keys apps)] [id (ask {"app" id "command" "catalog" "query" "unexpanded"})])))

(deftest-golden film-effect-control-vocabulary "test/golden/film-effect-control.edn"
  (into (sorted-map) (for [id (keys apps)] [id (:control (catalog-of id))])))

(deftest-golden film-effect-doctor "test/golden/film-effect-doctor.edn"
  (into (sorted-map) (for [id (keys apps)] [id (ask {"app" id "command" "doctor"})])))
