(ns hive-artcraft.addon-test
  "The `artcraft` tool over the shipped registry (photocraft + vectorcraft mounts) and over
   fixture :native apps; acceptance checks against the delegates' own catalogs."
  (:require [clojure.test :refer [deftest is]]
            [clojure.edn :as edn]
            [clojure.test.check.generators :as gen]
            [hive-test.trifecta :refer [deftrifecta]]
            [hive-addon.protocol :as protocol]
            [hive-artcraft.addon :as addon]
            [hive-artcraft.mount :as mount]
            [hive-artcraft.registry :as registry]
            [hive-artcraft.transport.socket :as socket]
            [hive-artcraft.fixtures :as fx]
            [hive-vectorcraft.addon :as vectorcraft]
            [hive-photocraft.addon :as photocraft]))

(def vc-module (get-in (registry/load-registry) [:ok :by-id "vectorcraft"]))
(def pc-module (get-in (registry/load-registry) [:ok :by-id "photocraft"]))
(def p7979 {:port 7979 :source :default})

(defn text [result] (-> result :content first :text))
(defn read-text [result] (edn/read-string (text result)))

(defn running
  "Initialized addon over the shipped registry (no env)."
  ([] (running {}))
  ([config] (doto (addon/addon-ctor (assoc config :getenv fx/no-env)) (protocol/initialize! {}))))

(defn handler [instance] (:handler (first (protocol/tools instance))))

;; ---------------------------------------------------------------- mount ns

(deftrifecta mount-config-contract mount/mount-config
  {:golden-path "test/golden/mount-config.edn"
   :cases {:vector-port [vc-module {} p7979]
           :photo-no-token [pc-module {} {:port 7878 :source :default}]
           :photo-token [pc-module {:token-ref {:scheme :env :name "PHOTOCRAFT_CONTROL_TOKEN"}} {:port 7878 :source :default}]
           :no-port [vc-module {} nil]}
   :apply? true :gen (gen/tuple (gen/return vc-module) (gen/return {}) (gen/fmap #(hash-map :port % :source :env) (gen/choose 1 65535)))
   :pred #(pos-int? (:control-port %)) :num-tests 30
   :mutations [["always-port" (fn [_ c r] (assoc c :control-port (:port r 1)))]]})

(deftrifecta mount-contract mount/mount!
  {:golden-path "test/golden/mount.edn"
   :cases {:vectorcraft [vc-module {}] :photocraft [pc-module {}]
           :missing [(assoc-in vc-module [:app/mount :ctor] 'nope.addon/addon-ctor) {}]}
   :apply? true :xf #(if (:ok %) {:tool (get-in % [:ok :tool :name])} {:error (get-in % [:error :kind])})
   :gen (gen/tuple (gen/elements [vc-module pc-module]) (gen/return {}))
   :pred #(fn? (get-in % [:ok :tool :handler])) :num-tests 5
   :mutations [["no-tool" (fn [_ _] {:ok {:addon nil :tool {}}})]]})

(deftrifecta tool-result-contract mount/tool-result
  {:golden-path "test/golden/tool-result.edn"
   :cases {:content {:content [{:type "text" :text "x"}]} :ok {:ok {:a 1}}
           :error {:error {:kind :k :hint "h"}} :other 42}
   :gen (gen/elements [{:ok 1} {:error {:kind :x :hint "y"}} nil])
   :pred #(vector? (:content %)) :num-tests 30
   :mutations [["lose-error" (fn [r] {:content [{:type "text" :text (pr-str r)}]})]]})

(deftrifecta forward-contract mount/forward
  {:golden-path "test/golden/forward.edn"
   :cases {:echo [{:tool {:handler (fn [a] {:ok a})}} {"app" "x" "command" "doctor"}]
           :throws [{:tool {:handler (fn [_] (throw (ex-info "boom" {})))}} {"command" "doctor"}]}
   :apply? true :gen (gen/tuple (gen/return {:tool {:handler (fn [a] {:ok a})}}) (gen/return {"app" "x"}))
   :pred #(= "{}" (text %)) :num-tests 20
   :mutations [["keep-app" (fn [m a] (mount/tool-result ((get-in m [:tool :handler]) a)))]]})

(deftrifecta unmount-contract mount/unmount!
  {:golden-path "test/golden/unmount.edn"
   :cases {:nil nil :empty {}}
   :gen (gen/return nil) :pred nil? :num-tests 10
   :mutations [["returns-value" (fn [m] (or m :done))]]})

;; ---------------------------------------------------------------- addon ns

(deftrifecta stringify-args-contract addon/stringify-args
  {:golden-path "test/golden/stringify-args.edn"
   :cases {:kw {:command "apps" :app "vectorcraft"} :str {"command" "apps"} :nil nil}
   :gen (gen/map gen/keyword gen/small-integer) :pred #(every? string? (keys %)) :num-tests 30
   :mutations [["identity" identity]]})

(deftrifecta mount-app-contract addon/mount-app
  {:golden-path "test/golden/mount-app.edn"
   :cases {:native-port [fx/port-app {} fx/no-env] :native-file [fx/file-app {} fx/no-env]
           :mount [vc-module {} fx/no-env]}
   :apply? true :xf #(-> % (select-keys [:port :error]) (assoc :ready? (boolean (or (get-in % [:ok :tool]) (get-in % [:ok :transport])))))
   :gen (gen/tuple (gen/return fx/file-app) (gen/return {}) (gen/return fx/no-env))
   :pred #(get-in % [:ok :catalog]) :num-tests 10
   :mutations [["no-port" (fn [m _ _] {:module m})]]})

(def fixture-mounted (addon/mount-all (:ok (registry/load-registry "hive_artcraft/fixtures/apps.edn")) {} fx/no-env))

(deftrifecta mount-all-contract addon/mount-all
  {:golden-path "test/golden/mount-all.edn"
   :cases {:fixture [(:ok (registry/load-registry "hive_artcraft/fixtures/apps.edn")) {:apps {"lightcraft" {:control-port 9000}}} fx/no-env]}
   :apply? true :xf #(into (sorted-map) (map (fn [[k v]] [k (:port v)])) %)
   :gen (gen/return [(:ok (registry/load-registry "hive_artcraft/fixtures/apps.edn")) {} fx/no-env])
   :pred #(= #{"lightcraft" "pdfcraft"} (set (keys %))) :num-tests 5
   :mutations [["drop-config" (fn [r _ e] (addon/mount-all r {} e))]]})

(deftrifecta app-row-contract addon/app-row
  {:golden-path "test/golden/app-row.edn"
   :cases {:lightcraft (get fixture-mounted "lightcraft") :pdfcraft (get fixture-mounted "pdfcraft")}
   :gen (gen/elements (vals fixture-mounted)) :pred #(= :mounted (:status %)) :num-tests 10
   :mutations [["no-shape" (fn [m] {:app (get-in m [:module :app/id]) :status :mounted})]]})

(deftrifecta dispatch-native-contract addon/dispatch-native
  {:golden-path "test/golden/dispatch-native.edn"
   :cases {:catalog [(get fixture-mounted "lightcraft") {"command" "catalog"}]
           :doctor [(get fixture-mounted "pdfcraft") {"command" "doctor"}]
           :call [(get fixture-mounted "pdfcraft") {"command" "call" "engine_command" "shape.rectangle"}]}
   :apply? true :gen (gen/tuple (gen/return (get fixture-mounted "pdfcraft")) (gen/return {"command" "call" "engine_command" "x"}))
   :pred #(:error %) :num-tests 10
   :mutations [["always-catalog" (fn [m _] (addon/dispatch-native m {"command" "catalog"}))]]})

(deftrifecta dispatch-contract addon/dispatch
  {:golden-path "test/golden/dispatch.edn"
   :cases {:apps [fixture-mounted ["lightcraft" "pdfcraft"] {"command" "apps"}]
           :unknown-command [fixture-mounted ["lightcraft" "pdfcraft"] {"command" "draw"}]
           :unknown-app [fixture-mounted ["lightcraft" "pdfcraft"] {"command" "catalog" "app" "gimp"}]
           :native [fixture-mounted ["lightcraft" "pdfcraft"] {:command "catalog" :app "lightcraft"}]}
   :apply? true :gen (gen/tuple (gen/return fixture-mounted) (gen/return ["lightcraft" "pdfcraft"]) (gen/return {"command" "apps"}))
   :pred #(not (:isError %)) :num-tests 10
   :mutations [["no-routing" (fn [& _] {:content [{:type "text" :text "[]"}]})]]})

(deftrifecta tool-contract addon/tool
  {:golden-path "test/golden/addon-tool.edn"
   :cases {:fixture [fixture-mounted ["lightcraft" "pdfcraft"]]}
   :apply? true :xf #(select-keys % [:name :inputSchema])
   :gen (gen/return [fixture-mounted ["lightcraft"]])
   :pred #(and (= "artcraft" (:name %)) (fn? (:handler %))) :num-tests 10
   :mutations [["drop-handler" (fn [_ _] {:name "artcraft" :inputSchema {}})]]})

(deftrifecta addon-ctor-contract addon/addon-ctor
  {:golden-path "test/golden/addon-ctor.edn"
   :cases {:default [{}] :nil [nil]}
   :apply? true :xf #(select-keys % [:configuration])
   :gen (gen/return [{}]) :pred #(= "hive.artcraft" (protocol/addon-id %)) :num-tests 10
   :mutations [["lose-config" (fn [_] (addon/->ArtcraftAddon (atom {}) {:lost true}))]]})

;; ---------------------------------------------------------------- acceptance

(deftest apps-lists-mounts-and-natives
  (let [instance (running)
        rows (read-text ((handler instance) {"command" "apps"}))
        shipped ["photocraft" "vectorcraft" "lightcraft" "designcraft" "pdfcraft" "studio"]]
    (is (= shipped (mapv :app rows)))
    (is (every? #(= :mounted (:status %)) rows))
    (is (= {"photocraft" "hive.photocraft" "vectorcraft" "hive.vectorcraft"}
           (into {} (comp (filter :delegate) (map (juxt :app :delegate))) rows)))
    (is (= shipped (get-in (first (protocol/tools instance)) [:inputSchema :properties "app" :enum])))
    (is (= 1 (count (protocol/tools instance))))
    (protocol/shutdown! instance)))

(deftest vectorcraft-catalog-matches-hive-vectorcraft
  (let [instance (running)
        direct (doto (vectorcraft/addon-ctor {}) (protocol/initialize! {}))
        theirs ((:handler (first (protocol/tools direct))) {"command" "catalog"})
        ours ((handler instance) {"app" "vectorcraft" "command" "catalog"})]
    (is (= theirs ours))
    (is (= {:engine 519 :mcp 25 :control 24} (select-keys (read-text ours) [:engine :mcp :control])))
    (is (= ((:handler (first (protocol/tools direct))) {"command" "catalog" "query" "control"})
           ((handler instance) {:app "vectorcraft" :command "catalog" :query "control"})))
    (protocol/shutdown! instance)))

(deftest photocraft-catalog-matches-hive-photocraft
  (let [ours (read-text ((handler (running)) {"app" "photocraft" "command" "catalog"}))
        theirs (:ok ((:handler (first (protocol/tools (photocraft/addon-ctor {})))) {"command" "catalog"}))]
    (is (= theirs ours))
    (is (= 271 (count (:commands ours))))))

(deftest mounted-calls-refuse-without-a-running-app
  (let [h (handler (running))]
    (is (:isError (h {"app" "vectorcraft" "command" "call" "engine_command" "shape.rectangle"})))
    (is (:isError (h {"app" "photocraft" "command" "control" "method" "ui.inspect"})))
    (is (:isError (h {"app" "vectorcraft" "command" "call" "engine_command" "no.such"})))))

(deftest per-app-port-reaches-the-delegate
  (let [instance (running {:apps {"vectorcraft" {:control-port 1}}})
        reply ((handler instance) {"app" "vectorcraft" "command" "control" "method" "document.inspect"})]
    (is (:isError reply))
    (is (re-find #"transport-failed" (text reply)))))

(deftest adding-an-app-is-data-only
  (let [instance (doto (addon/addon-ctor {:getenv fx/no-env
                                          :registry (registry/index-modules [vc-module fx/port-app fx/file-app fx/none-app])})
                   (protocol/initialize! {}))
        h (handler instance)
        rows (read-text (h {"command" "apps"}))]
    (is (= ["vectorcraft" "lightcraft" "pdfcraft" "studio"] (mapv :app rows)))
    (is (= [:port :port :control-file :none] (mapv :shape rows)))
    (is (= :port (:shape (read-text (h {"app" "lightcraft" "command" "doctor"})))))
    (is (re-find #"--control ~/.pdfcraft-control.json" (text (h {"app" "pdfcraft" "command" "doctor"}))))
    (is (re-find #"no-transport" (text (h {"app" "pdfcraft" "command" "call" "engine_command" "shape.rectangle"}))))
    (is (re-find #"unsupported-shape" (text (h {"app" "studio" "command" "call" "engine_command" "shape.rectangle"}))))
    (is (= :ok (:status (protocol/health instance))))))

(deftest broken-registry-refuses-to-initialize
  (let [instance (addon/addon-ctor {:registry (registry/index-modules [{:app/id "Bad"}])})]
    (is (false? (:success? (protocol/initialize! instance {}))))
    (is (= [] (protocol/tools instance)))))

(deftest socket-transport-defaults
  (is (= {:host "127.0.0.1" :port 7980 :connect-ms 2000 :timeout-ms 30000}
         (:config (socket/socket-transport {:port 7980})))))
