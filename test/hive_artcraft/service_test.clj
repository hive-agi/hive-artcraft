(ns hive-artcraft.service-test
  "Native-app service over the three transport shapes; :port is live, the others refuse."
  (:require [clojure.test :refer [deftest is]]
            [clojure.data.json :as json]
            [clojure.test.check.generators :as gen]
            [hive-test.trifecta :refer [deftrifecta]]
            [hive-artcraft.service :as service]
            [hive-artcraft.port :as port]
            [hive-artcraft.transport.socket :as socket]
            [hive-artcraft.fixtures :as fx])
  (:import (hive_artcraft.transport.socket SocketTransport)
           (java.io BufferedReader InputStreamReader PrintWriter)
           (java.net ServerSocket)))

(def entries fx/entries)
(def p7980 {:port 7980 :source :default})
(def ok-stub (fx/stub {:ok true :result {:layers 1}}))

(deftrifecta load-catalog-contract service/load-catalog
  {:golden-path "test/golden/load-catalog.edn"
   :cases {:fixture fx/port-app :missing (assoc fx/port-app :app/catalog-resource "nope.edn")}
   :xf #(if (:ok %) {:engine (count (:engine (:ok %)))} %)
   :gen (gen/return fx/port-app) :pred #(= 2 (count (:engine (:ok %)))) :num-tests 10
   :mutations [["empty" (fn [_] {:ok {:engine [] :control []}})]]})

(deftrifecta shape-remedy-contract service/shape-remedy
  {:golden-path "test/golden/shape-remedy.edn"
   :cases {:port [fx/port-app p7980] :control-file [fx/file-app nil] :none [fx/none-app nil]}
   :apply? true :gen (gen/tuple (gen/elements [fx/port-app fx/file-app fx/none-app]) (gen/return nil))
   :pred string? :num-tests 20
   :mutations [["generic" (fn [_ _] "Start the app.")]]})

(deftrifecta resolve-transport-contract service/resolve-transport
  {:golden-path "test/golden/resolve-transport.edn"
   :cases {:injected [fx/port-app {:transport :injected} p7980]
           :port [fx/port-app {} p7980]
           :host [fx/port-app {:control-host "localhost"} p7980]
           :control-file [fx/file-app {} nil]
           :none [fx/none-app {} nil]}
   :apply? true :xf #(if (instance? SocketTransport %) (:config %) %)
   :gen (gen/tuple (gen/return fx/port-app) (gen/return {}) (gen/fmap #(hash-map :port % :source :env) (gen/choose 1 65535)))
   :pred #(satisfies? port/ControlTransport %) :num-tests 30
   :mutations [["never" (fn [& _] nil)]]})

(deftrifecta catalog-contract service/catalog
  {:golden-path "test/golden/service-catalog.edn"
   :cases {:overview [fx/port-app entries nil] :control [fx/port-app entries "control"]
           :one [fx/port-app entries "shape.rectangle"] :unknown [fx/port-app entries "zzz"]}
   :apply? true :gen (gen/tuple (gen/return fx/port-app) (gen/return entries) (gen/elements [nil "mcp" "engine" "bad"]))
   :pred #(or (contains? % :ok) (:error %)) :num-tests 30
   :mutations [["empty" (fn [& _] {:ok []})]]})

(deftrifecta doctor-contract service/doctor
  {:golden-path "test/golden/service-doctor.edn"
   :cases {:port-absent [fx/port-app entries nil p7980] :port-ready [fx/port-app entries :t p7980]
           :control-file [fx/file-app entries nil nil] :none [fx/none-app entries nil nil]}
   :apply? true :gen (gen/tuple (gen/elements [fx/port-app fx/file-app fx/none-app]) (gen/return entries) (gen/return nil) (gen/return nil))
   :pred #(and (keyword? (get-in % [:ok :shape])) (= :absent (get-in % [:ok :transport]))) :num-tests 30
   :mutations [["hide-shape" (fn [& _] {:ok {:transport :absent}})]]})

(deftrifecta call-contract service/call
  {:golden-path "test/golden/service-call.edn"
   :cases {:valid [fx/port-app entries (fx/stub {:ok true :result {:id 3}}) p7980 "shape.rectangle" {} 4]
           :no-transport [fx/port-app entries nil p7980 "shape.rectangle" {} 4]
           :unknown [fx/port-app entries nil p7980 "bad" {} 4]
           :control-file [fx/file-app entries nil nil "shape.rectangle" {} 4]
           :none [fx/none-app entries nil nil "shape.rectangle" {} 4]}
   :apply? true :gen (gen/tuple (gen/elements [fx/port-app fx/file-app fx/none-app]) (gen/return entries) (gen/return nil)
                                (gen/return nil) (gen/elements ["shape.rectangle" "bad"]) (gen/return {}) gen/nat)
   :pred #(:error %) :num-tests 30
   :mutations [["always-ok" (fn [& _] {:ok nil})]]})

(deftrifecta control-contract service/control
  {:golden-path "test/golden/service-control.edn"
   :cases {:valid [fx/port-app entries ok-stub p7980 "document.inspect" {} 3]
           :upstream [fx/port-app entries (fx/stub {:ok false :error "no document"}) p7980 "ui.render" {} 4]
           :unknown [fx/port-app entries ok-stub p7980 "app.format-disk" {} 3]
           :control-file [fx/file-app entries nil nil "ui.render" {} 3]}
   :apply? true :gen (gen/tuple (gen/return fx/port-app) (gen/return entries) (gen/return ok-stub) (gen/return p7980)
                                (gen/elements ["document.inspect" "ui.render"]) (gen/return {}) gen/nat)
   :pred #(= {:layers 1} (:ok %)) :num-tests 30
   :mutations [["skip-catalog" (fn [_ _ _ _ m p id] {:ok {:id id :method m :params p}})]]})

(deftest recording-sends-exactly-validated-requests
  (let [[t calls] (fx/recording (fx/stub {:ok true :result 1}))]
    (is (= {:ok 1} (service/call fx/port-app entries t p7980 "shape.rectangle" {:x 2} 6)))
    (is (:error (service/call fx/port-app entries t p7980 "nope" {} 7)))
    (is (= [{:id 6 :method "engine.execute" :params {:command "shape.rectangle" :params {:x 2}}}] @calls))))

(defn- serve-lines! [^ServerSocket server n seen]
  (future
    (dotimes [_ n]
      (with-open [s (.accept server)]
        (let [in (BufferedReader. (InputStreamReader. (.getInputStream s)))
              out (PrintWriter. (.getOutputStream s) true)
              request (json/read-str (.readLine in))]
          (swap! seen conj request)
          (.println out (json/write-str {:id (get request "id") :ok true :result {:method (get request "method")}})))))))

(deftest port-shape-loopback-round-trip
  (with-open [server (ServerSocket. 0)]
    (let [seen (atom [])
          _ (serve-lines! server 1 seen)
          res {:port (.getLocalPort server) :source :config}
          t (service/resolve-transport fx/port-app {} res)]
      (is (= {:ok {:method "engine.execute"}} (service/call fx/port-app entries t res "shape.rectangle" {} 2)))
      (is (= ["engine.execute"] (mapv #(get % "method") @seen))))))

(deftest refused-connection-is-a-value
  (let [res {:port 1 :source :config}
        t (socket/socket-transport {:port 1 :connect-ms 300})]
    (is (= :artcraft/transport-failed (get-in (service/control fx/port-app entries t res "ui.render" {} 1) [:error :kind])))))
