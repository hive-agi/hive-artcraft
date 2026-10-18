(ns hive-artcraft.core-test
  (:require [clojure.test.check.generators :as gen]
            [hive-test.trifecta :refer [deftrifecta]]
            [hive-artcraft.core :as core]
            [hive-artcraft.schema :as schema]
            [hive-artcraft.fixtures :as fx]))

(def entries fx/entries)

(deftrifecta refusal-contract core/refusal
  {:golden-path "test/golden/refusal.edn"
   :cases {:missing [:artcraft/no-transport "Start the app"]}
   :apply? true :gen (gen/tuple gen/keyword gen/string-alphanumeric)
   :pred #(and (keyword? (get-in % [:error :kind])) (string? (get-in % [:error :hint]))) :num-tests 50
   :mutations [["drop-hint" (fn [kind _] {:error {:kind kind}})]]})

(deftrifecta lookup-contract core/lookup
  {:golden-path "test/golden/lookup.edn"
   :cases {:found [entries "lightcraft" "shape.rectangle"] :missing [entries "lightcraft" "nope"]}
   :apply? true :gen (gen/tuple (gen/return entries) (gen/return "x") (gen/elements ["shape.rectangle" "bad" nil]))
   :pred #(or (:ok %) (:error %)) :num-tests 50
   :mutations [["first-entry" (fn [c _ _] {:ok (first (:engine c))})]]})

(deftrifecta request-contract core/request
  {:golden-path "test/golden/request.edn"
   :cases {:valid [entries "lightcraft" "shape.rectangle" {:x 1} 3]
           :bad-params [entries "lightcraft" "shape.rectangle" nil 3]
           :bad-id [entries "lightcraft" "shape.rectangle" {} -1]
           :unknown [entries "lightcraft" "nope" {} 3]}
   :apply? true :gen (gen/tuple (gen/return entries) (gen/return "x") (gen/elements ["shape.rectangle" "bad"])
                                (gen/return {}) gen/nat)
   :pred #(or (:ok %) (:error %)) :num-tests 50
   :mutations [["skip-validation" (fn [_ _ id params rid] {:ok {:id rid :method "engine.execute" :params {:command id :params params}}})]]})

(deftrifecta control-request-contract core/control-request
  {:golden-path "test/golden/control-request.edn"
   :cases {:valid [entries "lightcraft" "document.inspect" {} 9]
           :unknown [entries "lightcraft" "app.format-disk" {} 9]
           :invalid [entries "lightcraft" "ui.render" [] 9]}
   :apply? true :gen (gen/tuple (gen/return entries) (gen/return "x") (gen/elements ["ui.render" "bad"]) (gen/return {}) gen/nat)
   :pred #(or (:ok %) (:error %)) :num-tests 50
   :mutations [["accept-unknown" (fn [_ _ method params id] {:ok {:id id :method method :params params}})]]})

(deftrifecta frame-contract core/frame
  {:golden-path "test/golden/frame.edn"
   :cases {:line "{\"id\":1}" :broken "{\"a\":\n1}" :not-object "[1]"}
   :gen (gen/elements ["{}" "{\"a\":1}" "x" "{\n}"])
   :pred #(or (:ok %) (:error %)) :num-tests 50
   :mutations [["no-newline" (fn [s] {:ok s})]]})

(deftrifecta response-line-contract core/response-line
  {:golden-path "test/golden/response-line.edn"
   :cases {:true ["lightcraft" {:ok true :result {:id 3}}]
           :false ["lightcraft" {"ok" true "result" false}]
           :null ["lightcraft" {:ok true :result nil}]
           :upstream ["lightcraft" {:ok false :error "no document"}]
           :invalid ["lightcraft" {:ok "true"}]
           :absent ["lightcraft" {}]}
   :apply? true :gen (gen/tuple (gen/return "x") (gen/elements [{:ok true} {:ok false} {:ok 1} nil]))
   :pred #(or (contains? % :ok) (:error %)) :num-tests 50
   :mutations [["null-to-false" (fn [_ r] (if (true? (:ok r)) {:ok false} {:error {:kind :x :hint "x"}}))]]})

(deftrifecta envelope-contract schema/envelope
  {:golden-path "test/golden/envelope.edn"
   :cases {:map :map :text :string}
   :gen (gen/elements [:string :int :map])
   :pred #(and (= :or (first %)) (= 3 (count %))) :num-tests 50
   :mutations [["drop-error" (fn [v] [:or [:map [:ok v]]])]]})

(deftrifecta validate-module-contract schema/validate-module
  {:golden-path "test/golden/validate-module.edn"
   :cases {:port fx/port-app :control-file fx/file-app :none fx/none-app
           :port-without-port (dissoc fx/port-app :app/default-port)
           :mount-without-ctor {:app/id "x" :app/label "X" :app/summary "x" :app/kind :mount :app/transport :none}
           :bad-shape (assoc fx/none-app :app/transport :carrier-pigeon)}
   :xf #(if (:ok %) {:ok (:app/id (:ok %))} {:error (get-in % [:error :kind])})
   :gen (gen/elements [fx/port-app fx/file-app {} nil])
   :pred #(or (:ok %) (:error %)) :num-tests 50
   :mutations [["accept-all" (fn [m] {:ok m})]]})
