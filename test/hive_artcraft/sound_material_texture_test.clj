(ns hive-artcraft.sound-material-texture-test
  "SoundCraft (7987), MaterialCraft (7988) and TextureCraft (7989): :native :port modules served
   from catalogs that dev/extract_<app>.clj derives from the reference clones. SoundCraft needs
   the char-literal-aware scanner of sound-material-texture-extract; its wrapper macros are
   listed under :unexpanded. MaterialCraft and TextureCraft ports require a first-line auth
   handshake no transport sends yet, so their modules carry no :app/token-ref. Never starts an app."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [malli.core :as m]
            [hive-test.golden :refer [deftest-golden]]
            [hive-addon.protocol :as protocol]
            [hive-artcraft.addon :as addon]
            [hive-artcraft.registry :as registry]
            [hive-artcraft.schema :as schema]
            [hive-artcraft.service :as service]
            [artcraft-extract :as x]
            [sound-material-texture-extract :as smt]
            [extract-soundcraft :as sound]
            [extract-materialcraft :as material]
            [extract-texturecraft :as texture]))

(def apps
  {"soundcraft" {:port 7987 :env "SOUNDCRAFT_CONTROL_PORT"
                 :revision "b47a58653c291f320179d20db3eafb40e70b88a7"
                 :counts {:engine 388 :mcp 17 :control 17 :unexpanded 95}
                 :clone "../clones-ref/soundcraft" :extract sound/extract
                 :wrappers #{"toggle!" "flag!" "radio!" "option!"}
                 :execute "engine.execute"}
   "materialcraft" {:port 7988 :env "MATERIALCRAFT_CONTROL_PORT"
                    :revision "60cb2a13a481a490904a90f61d8583d354d83d54"
                    :counts {:engine 121 :mcp 20 :control 12 :unexpanded 0}
                    :clone "../clones-ref/materialcraft" :extract material/extract
                    :wrappers #{} :execute "command.run"}
   "texturecraft" {:port 7989 :env "TEXTURECRAFT_CONTROL_PORT"
                   :revision "9c1b9cbc7b1e5541cffa03b80a8fa6bd1366a54d"
                   :counts {:engine 178 :mcp 22 :control 15 :unexpanded 0}
                   :clone "../clones-ref/texturecraft" :extract texture/extract
                   :wrappers #{} :execute "command.run"}})

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
               ((juxt :app/kind :app/transport :app/default-port :app/port-env :app/catalog-resource) mod)))
        (testing "no :app/token-ref: a top-level token is the wrong wire shape for an auth handshake"
          (is (not (contains? mod :app/token-ref))))))))

(deftest catalogs-are-valid-pinned-and-honest
  (doseq [[id {:keys [revision counts wrappers execute]}] apps]
    (testing id
      (let [c (catalog-of id)]
        (is (m/validate schema/Catalog c))
        (is (= revision (:reference-revision c)))
        (is (= counts (-> (update-vals (select-keys c [:engine :mcp :control]) count)
                          (assoc :unexpanded (reduce + (map :count (:unexpanded c)))))))
        (is (apply distinct? (map :id (:engine c))))
        (is (every? string? (map :params (:engine c))))
        (is (every? string? (map :name (:mcp c))))
        (is (some #{execute} (:control c)))
        (testing "named wrapper macros are reported, never silently dropped"
          (is (every? (set (map :macro (:unexpanded c))) wrappers)))))))

(deftest control-vocabulary-is-the-running-apps
  (testing "materialcraft: headless-only rows (command.batch, package.open, render...) are excluded"
    (let [ctl (set (:control (catalog-of "materialcraft")))]
      (is (contains? ctl "ui.input"))
      (is (not-any? ctl ["command.batch" "package.open" "render" "material.export"]))))
  (testing "texturecraft: the headless serve table (project.open, mesh.import...) is excluded"
    (let [ctl (set (:control (catalog-of "texturecraft")))]
      (is (contains? ctl "viewport.screenshot"))
      (is (not-any? ctl ["project.open" "mesh.import" "render" "command.batch"])))))

(deftest char-literals-do-not-break-the-scanner
  (let [t "cmd!(noundo \"a.b\", \"A\", [], None, \"{}\", always, |e, p| f(p.trim_matches('\"'), 'x', '\\'')), cmd!(\"c.d\", \"C\", [], None, \"{}\", always, g::<'a>)"]
    (testing "the shared scanner reads '\"' as a string opener"
      (is (thrown-with-msg? Exception #"Unclosed Rust invocation" (x/calls t "cmd!("))))
    (is (= ["a.b" "c.d"] (map #(x/literal (str/replace-first (first (smt/rust-fields %)) smt/sound-arm ""))
                              (smt/rust-calls t "cmd!("))))
    (is (= 7 (count (smt/rust-fields (second (smt/rust-calls t "cmd!("))))))))

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

(deftest-golden sound-material-texture-catalog-overview "test/golden/sound-material-texture-catalog.edn"
  (into (sorted-map) (for [id (keys apps)] [id (ask {"app" id "command" "catalog"})])))

(deftest-golden sound-material-texture-unexpanded "test/golden/sound-material-texture-unexpanded.edn"
  (into (sorted-map) (for [id (keys apps)] [id (ask {"app" id "command" "catalog" "query" "unexpanded"})])))

(deftest-golden sound-material-texture-control-vocabulary "test/golden/sound-material-texture-control.edn"
  (into (sorted-map) (for [id (keys apps)] [id (:control (catalog-of id))])))

(deftest-golden sound-material-texture-doctor "test/golden/sound-material-texture-doctor.edn"
  (into (sorted-map) (for [id (keys apps)] [id (ask {"app" id "command" "doctor"})])))
