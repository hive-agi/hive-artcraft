(ns hive-artcraft.fixtures
  "Test modules for the three transport shapes plus stub/recording ports; never opens a socket."
  (:require [hive-artcraft.port :as port]))

(def entries {:engine [{:id "shape.rectangle" :label "Rectangle"}]
              :mcp [{:name "run_command"}]
              :control ["engine.execute" "document.inspect" "ui.render"]
              :reference-revision "test"})

(def port-app
  {:app/id "lightcraft" :app/label "LightCraft" :app/summary "fixture :port app"
   :app/kind :native :app/transport :port :app/default-port 7980
   :app/port-env "LIGHTCRAFT_CONTROL_PORT"
   :app/catalog-resource "hive_artcraft/fixtures/catalog.edn"})

(def file-app
  {:app/id "pdfcraft" :app/label "PdfCraft" :app/summary "fixture :control-file app"
   :app/kind :native :app/transport :control-file
   :app/catalog-resource "hive_artcraft/fixtures/catalog.edn"})

(def none-app
  {:app/id "studio" :app/label "ArtCraft Studio" :app/summary "fixture :none app"
   :app/kind :native :app/transport :none
   :app/catalog-resource "hive_artcraft/fixtures/catalog.edn"})

(defrecord StubTransport [response]
  port/ControlTransport
  (send-request [_ _] response))

(defn stub [response] (->StubTransport response))

(defrecord RecordingTransport [delegate calls]
  port/ControlTransport
  (send-request [_ request]
    (swap! calls conj request)
    (port/send-request delegate request)))

(defn recording [delegate]
  (let [calls (atom [])] [(->RecordingTransport delegate calls) calls]))

(defn no-env [_] nil)
