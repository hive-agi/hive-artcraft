(ns hive-artcraft.transport.socket
  "JVM loopback client for a :port-shaped app's --control channel: one JSON line out, one back."
  (:require [clojure.data.json :as json]
            [hive-artcraft.core :as core]
            [hive-artcraft.port :as port]
            [malli.core :as m])
  (:import (java.io BufferedReader InputStreamReader OutputStreamWriter)
           (java.net InetSocketAddress Socket)
           (java.nio.charset StandardCharsets)))

(def default-config
  "Loopback host and per-request connect and read bounds; :port comes from the app module."
  {:host "127.0.0.1" :connect-ms 2000 :timeout-ms 30000})

(defn- exchange!
  "Send one framed line and read one reply line over a fresh connection; throws on I/O failure."
  [{:keys [host port connect-ms timeout-ms]} line]
  (with-open [socket (doto (Socket.)
                       (.connect (InetSocketAddress. ^String host (int port)) (int connect-ms))
                       (.setSoTimeout (int timeout-ms)))]
    (let [out (OutputStreamWriter. (.getOutputStream socket) StandardCharsets/UTF_8)
          in (BufferedReader. (InputStreamReader. (.getInputStream socket) StandardCharsets/UTF_8))]
      (.write out ^String line)
      (.flush out)
      (or (.readLine in)
          (throw (ex-info "The app closed the connection without a reply." {:host host :port port}))))))

(defrecord SocketTransport [config]
  port/ControlTransport
  (send-request [_ request]
    (let [framed (core/frame (json/write-str request))]
      (if (:error framed)
        (throw (ex-info (get-in framed [:error :hint]) framed))
        (json/read-str (exchange! config (:ok framed)) :key-fn keyword)))))

(defn socket-transport
  "Build a transport for an app listening on --control <port>; config overrides default-config."
  [config]
  (->SocketTransport (merge default-config config)))

(m/=> socket-transport [:=> [:cat [:map [:port [:int {:min 1 :max 65535}]]]]
                        [:fn #(satisfies? port/ControlTransport %)]])
