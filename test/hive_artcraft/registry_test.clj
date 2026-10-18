(ns hive-artcraft.registry-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.test.check.generators :as gen]
            [hive-test.trifecta :refer [deftrifecta]]
            [hive-artcraft.registry :as registry]
            [hive-artcraft.fixtures :as fx]))

(def reg (:ok (registry/index-modules [fx/port-app fx/file-app])))

(deftrifecta read-modules-contract registry/read-modules
  {:golden-path "test/golden/read-modules.edn"
   :cases {:fixture ["hive_artcraft/fixtures/apps.edn"] :missing ["hive_artcraft/nope.edn"]}
   :apply? true :xf #(if (:ok %) {:ok (mapv :app/id (:ok %))} %)
   :gen (gen/tuple (gen/elements ["hive_artcraft/fixtures/apps.edn" "nope.edn"]))
   :pred #(or (:ok %) (:error %)) :num-tests 20
   :mutations [["empty" (fn [_] {:ok []})]]})

(deftrifecta index-modules-contract registry/index-modules
  {:golden-path "test/golden/index-modules.edn"
   :cases {:two [[fx/port-app fx/file-app]] :dupe [[fx/port-app fx/port-app]] :invalid [[{:app/id "X"}]]}
   :apply? true :xf #(if (:ok %) {:ok (:order (:ok %))} {:error (get-in % [:error :kind])})
   :gen (gen/tuple (gen/elements [[fx/port-app] [fx/port-app fx/port-app] [{}]]))
   :pred #(or (:ok %) (:error %)) :num-tests 30
   :mutations [["no-dupe-check" (fn [ms] {:ok {:order (mapv :app/id ms) :by-id (zipmap (map :app/id ms) ms)}})]]})

(deftrifecta load-registry-contract registry/load-registry
  {:golden-path "test/golden/load-registry.edn"
   :cases {:shipped [] :fixture ["hive_artcraft/fixtures/apps.edn"]}
   :apply? true :xf #(if (:ok %) {:ok (:order (:ok %))} %)
   :gen (gen/return []) :pred #(some #{"vectorcraft"} (get-in % [:ok :order])) :num-tests 5
   :mutations [["drop-apps" (fn [& _] {:ok {:order [] :by-id {}}})]]})

(deftrifecta app-ids-contract registry/app-ids
  {:golden-path "test/golden/app-ids.edn"
   :cases {:reg reg :registry-order (:ok (registry/index-modules [fx/file-app fx/port-app]))}
   :gen (gen/return reg) :pred vector? :num-tests 10
   :mutations [["sorted" (fn [r] (vec (sort (keys (:by-id r)))))]]})

(deftrifecta find-app-contract registry/find-app
  {:golden-path "test/golden/find-app.edn"
   :cases {:found [reg "lightcraft"] :unknown [reg "gimp"]}
   :apply? true :xf #(if (:ok %) {:ok (:app/id (:ok %))} %)
   :gen (gen/tuple (gen/return reg) (gen/elements ["lightcraft" "x" nil]))
   :pred #(or (:ok %) (:error %)) :num-tests 30
   :mutations [["first-app" (fn [r _] {:ok (val (first (:by-id r)))})]]})

(deftrifecta app-config-contract registry/app-config
  {:golden-path "test/golden/app-config.edn"
   :cases {:string [{:apps {"lightcraft" {:control-port 1}}} "lightcraft"]
           :keyword [{:apps {:lightcraft {:control-port 2}}} "lightcraft"]
           :absent [{} "lightcraft"]}
   :apply? true :gen (gen/tuple (gen/return {}) (gen/return "x"))
   :pred map? :num-tests 20
   :mutations [["ignore" (fn [_ _] {})]]})

(deftrifecta resolve-port-contract registry/resolve-port
  {:golden-path "test/golden/resolve-port.edn"
   :cases {:config [fx/port-app {:control-port 8001} {"LIGHTCRAFT_CONTROL_PORT" "8002"}]
           :env [fx/port-app {} {"LIGHTCRAFT_CONTROL_PORT" "8002"}]
           :default [fx/port-app {} {}]
           :no-port [fx/file-app {} {}]}
   :apply? true :gen (gen/tuple (gen/return fx/port-app) (gen/return {}) (gen/return {}))
   :pred #(= 7980 (:port %)) :num-tests 20
   :mutations [["env-ignored" (fn [m c _] (if-let [p (:control-port c)] {:port p :source :config}
                                              (when-let [p (:app/default-port m)] {:port p :source :default})))]]})

(deftest shipped-registry-lists-mounts-and-natives
  (let [{:keys [ok]} (registry/load-registry)]
    (is (= ["photocraft" "vectorcraft" "lightcraft" "designcraft" "pdfcraft" "studio" "filmcraft" "effectcraft"] (:order ok)))
    (is (= [7878 7979 7980 7981 nil nil 9876 9877] (map #(get-in ok [:by-id % :app/default-port]) (:order ok))))
    (is (= [:control-file :none :port :port] (map #(get-in ok [:by-id % :app/transport]) ["pdfcraft" "studio" "filmcraft" "effectcraft"])))))
