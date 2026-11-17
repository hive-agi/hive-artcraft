(ns hive-artcraft.transport.socket
  "JVM loopback client for a :port-shaped app's --control channel: one JSON line out, one back.
   An app whose port authenticates every request (FilmCraft) gets :token-ref in the config: the
   token is read from it per request, sent as a top-level \"token\" field, and never logged,
   returned or put in an error (memory axiom 20260504113116-48076a09). Without :token-ref the
   request goes out exactly as built."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [hive-artcraft.core :as core]
            [hive-artcraft.port :as port]
            [hive-artcraft.schema :as schema]
            [hive-artcraft.transport.control-file :as control-file]
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

(def token-file-advice
  "How to keep a control-token file trustworthy."
  "Keep the token in a regular file only you can read, e.g. `umask 077; python3 -c 'import secrets;print(secrets.token_hex(32))' > ~/.private/filmcraft-control.token`.")

(defn- token-source [token-ref]
  (case (:scheme token-ref)
    :env (str "environment variable " (:name token-ref))
    :file (str "token file " (:path token-ref))
    "the :token-ref"))

(defn read-token
  "Resolve a TokenRef at send time with the injected `getenv`: {:ok Redacted} holding a token of
   64 hex characters (the craft control protocol's form), or a refusal naming the source and the
   fix. No hint ever carries the value."
  [token-ref getenv]
  (let [raw (case (:scheme token-ref)
              :env (if-let [v (getenv (:name token-ref))]
                     {:ok v}
                     (core/refusal :artcraft/token-missing
                                   (str (:name token-ref) " is not set in the hive JVM's environment; export the token the app was started with before starting hive, or use :token-ref {:scheme :file :path ...}.")))
              :file (let [r (control-file/read-trusted-file (:path token-ref) token-file-advice "no such token file.")]
                      (if (= :artcraft/control-file-missing (get-in r [:error :kind]))
                        (core/refusal :artcraft/token-missing (get-in r [:error :hint]))
                        (if (:error r) (core/refusal :artcraft/token-refused (get-in r [:error :hint])) r)))
              (core/refusal :artcraft/token-refused
                            "Configure :token-ref as {:scheme :env :name \"<APP>_CONTROL_TOKEN\"} or {:scheme :file :path \"...\"}."))
        value (some-> (:ok raw) str/trim)]
    (cond
      (:error raw) raw
      (re-matches #"[0-9A-Fa-f]{64}" value) {:ok (control-file/->Redacted value)}
      :else (core/refusal :artcraft/token-refused
                          (str "The " (token-source token-ref) " does not hold 64 hex characters, which the app's control port requires.")))))

(defn- authenticated
  "The request to put on the wire: unchanged without a :token-ref, else with a top-level \"token\"
   read now (per request). Returns [request token-or-nil]; throws a refusal ex-info without the value."
  [{:keys [token-ref getenv]} request]
  (if-not token-ref
    [request nil]
    (let [r (read-token token-ref (or getenv #(System/getenv ^String %)))]
      (if (:error r)
        (throw (ex-info (get-in r [:error :hint]) {:kind (get-in r [:error :kind]) :refusal? true}))
        (let [token (.-value ^hive_artcraft.transport.control_file.Redacted (:ok r))]
          [(assoc request :token token) token])))))

(defrecord SocketTransport [config]
  port/ControlTransport
  (send-request [_ request]
    (let [[wire token] (authenticated config request)
          framed (core/frame (json/write-str wire))]
      (if (:error framed)
        (throw (ex-info (get-in framed [:error :hint]) (dissoc framed :ok)))
        (let [line (exchange! config (:ok framed))]
          (json/read-str (if token (str/replace line token "<redacted>") line) :key-fn keyword))))))

(defn socket-transport
  "Build a transport for an app listening on --control <port>; config overrides default-config."
  [config]
  (->SocketTransport (merge default-config config)))

(m/=> read-token [:=> [:cat schema/TokenRef ifn?]
                  [:or [:map [:ok [:fn #(instance? hive_artcraft.transport.control_file.Redacted %)]]]
                   [:map [:error schema/ErrorValue]]]])
(m/=> socket-transport [:=> [:cat [:map [:port [:int {:min 1 :max 65535}]] [:token-ref {:optional true} schema/TokenRef]]]
                        [:fn #(satisfies? port/ControlTransport %)]])
