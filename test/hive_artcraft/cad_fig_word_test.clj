(ns hive-artcraft.cad-fig-word-test
  "CADCraft (7982), FigCraft (7984) and WordCraft (7986): :native :port modules served from catalogs
   that dev/extract_<app>.clj derives from the reference clones through cad-fig-word-extract.
   CADCraft's control port needs its session token (top-level \"token\", default
   :app/token-ref CADCRAFT_CONTROL_TOKEN); FigCraft's is unauthenticated; WordCraft's needs a
   per-window key the transports do not speak yet. Never starts any app."
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
            [cad-fig-word-extract :as cfw]
            [extract-cadcraft :as cad]
            [extract-figcraft :as fig]
            [extract-wordcraft :as word]))

(def apps
  {"cadcraft" {:port 7982 :env "CADCRAFT_CONTROL_PORT"
               :revision "16341b665fa358d6fa1847e4f2325b5a5b0ec037"
               :counts {:engine 439 :mcp 15 :control 26 :unexpanded 18}
               :clone "../clones-ref/cadcraft" :extract cad/extract
               :wrappers #{"gc!" "dc!"}
               :token-ref {:scheme :env :name "CADCRAFT_CONTROL_TOKEN"}}
   "figcraft" {:port 7984 :env "FIGCRAFT_CONTROL_PORT"
               :revision "da2792cb4aaa4d14ecd84929297e804eeb67669f"
               :counts {:engine 649 :mcp 19 :control 18 :unexpanded 0}
               :clone "../clones-ref/figcraft" :extract fig/extract
               :wrappers #{}}
   "wordcraft" {:port 7986 :env "WORDCRAFT_CONTROL_PORT"
                :revision "1a56d041defe28f0d35de501ba7e40268a549d26"
                :counts {:engine 674 :mcp 18 :control 19 :unexpanded 0}
                :clone "../clones-ref/wordcraft" :extract word/extract
                :wrappers #{}}})

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
  (doseq [[id {:keys [port env token-ref]}] apps]
    (testing id
      (let [mod (module id)]
        (is (= {:ok mod} (schema/validate-module mod)))
        (is (= [:native :port port env (str "hive_artcraft/catalogs/" id ".edn")]
               ((juxt :app/kind :app/transport :app/default-port :app/port-env :app/catalog-resource) mod)))
        (is (= token-ref (:app/token-ref mod)) "only CADCraft's port takes a top-level token")))))

(deftest catalogs-are-valid-pinned-and-honest
  (doseq [[id {:keys [revision counts wrappers]}] apps]
    (testing id
      (let [c (catalog-of id)]
        (is (m/validate schema/Catalog c))
        (is (= revision (:reference-revision c)))
        (is (= counts (-> (update-vals (select-keys c [:engine :mcp :control]) count)
                          (assoc :unexpanded (reduce + (map :count (:unexpanded c)))))))
        (is (vector? (:unexpanded c)) "the gap list is always present, empty when nothing was skipped")
        (is (apply distinct? (map :id (:engine c))))
        (is (every? string? (map :params (:engine c))))
        (is (some #{"engine.execute"} (:control c)))
        (is (every? string? (map :name (:mcp c))))
        (testing "named wrapper macros are reported, never silently dropped"
          (is (every? (set (map :macro (:unexpanded c))) wrappers)))))))

(deftest registry-shapes-are-extracted
  (testing "CADCraft builder chain: id, label, menu, aliases, params"
    (let [t "CommandSpec::new(\"color\", \"Color...\", run_color).menu(&[\"Format\", \"Color...\"]).alias(&[\"col\"]).params(\"{color}\"),"]
      (is (= [{:id "color" :label "Color..." :params "{color}" :source "x.rs" :menu ["Format" "Color..."] :aliases ["col"]}]
             (:engine (with-redefs [cfw/rs-files (constantly [["x.rs" t]])] (cfw/builder-engine "." "." 3)))))))
  (testing "WordCraft: const ids resolve, raw-string params decode"
    (let [t (cfw/normalize "const T: &str = \"view.md\";\nCommandSpec::new(T, \"Md\", \"View\", run).params(r#\"{\"value\"?: bool}\"#).key(\"Mod+M\")")]
      (is (= [{:id "view.md" :label "Md" :params "{\"value\"?: bool}" :source "x.rs" :location "View" :shortcut "Mod+M"}]
             (:engine (with-redefs [cfw/rs-files (constantly [["x.rs" t]])] (cfw/builder-engine "." "." 4)))))))
  (testing "FigCraft spec! macro"
    (is (= [{:id "align.left" :label "Align left" :params "{ids?}" :source "x.rs"}]
           (:engine (with-redefs [cfw/rs-files (constantly [["x.rs" "spec!(\"align.left\", \"Align left\", \"{ids?}\", true, |s, p| left(s, p))"]])]
                      (cfw/spec-macro-engine "." ".")))))))

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

(deftest cadcraft-without-its-token-refuses-before-sending
  (let [r ((handler (running)) {"app" "cadcraft" "command" "control" "method" "engine.commands"})]
    (is (:isError r))
    (is (re-find #"token-missing" (-> r :content first :text)))
    (is (re-find #"CADCRAFT_CONTROL_TOKEN" (-> r :content first :text)))))

(deftest-golden cad-fig-word-catalog-overview "test/golden/cad-fig-word-catalog.edn"
  (into (sorted-map) (for [id (keys apps)] [id (ask {"app" id "command" "catalog"})])))

(deftest-golden cad-fig-word-unexpanded "test/golden/cad-fig-word-unexpanded.edn"
  (into (sorted-map) (for [id (keys apps)] [id (ask {"app" id "command" "catalog" "query" "unexpanded"})])))

(deftest-golden cad-fig-word-control-vocabulary "test/golden/cad-fig-word-control.edn"
  (into (sorted-map) (for [id (keys apps)] [id (:control (catalog-of id))])))

(deftest-golden cad-fig-word-doctor "test/golden/cad-fig-word-doctor.edn"
  (into (sorted-map) (for [id (keys apps)] [id (ask {"app" id "command" "doctor"})])))
