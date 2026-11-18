(ns hive-artcraft.transport.socket
  "JVM loopback client for a :port-shaped app's --control channel: one JSON line out, one back,
   over a connection opened through the injected port/LineConnector (TCP by default; tests pass
   a scripted stub adapter). An app whose port authenticates carries an AuthSpec (:auth, or the
   FilmCraft/CADCraft shorthand :token-ref): :per-request puts the secret on the request as a
   top-level \"token\"/\"key\"; :handshake sends {\"id\":0,\"method\":\"auth\",\"params\":{...}} as
   the first line and checks its ok before the request. The secret is resolved per connection
   (:ref, then the files the app writes) and never logged, returned or put in an error (memory
   axiom 20260504113116-48076a09). Without auth the request goes out exactly as built."
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

(defrecord TcpLineSession [^Socket socket ^BufferedReader in ^OutputStreamWriter out]
  port/LineSession
  (send-line! [_ line]
    (.write out ^String line)
    (.flush out))
  (recv-line! [_] (.readLine in))
  (close-lines! [_] (.close socket)))

(defrecord TcpConnector []
  port/LineConnector
  (open-lines [_ {:keys [host port connect-ms timeout-ms]}]
    (let [socket (doto (Socket.)
                   (.connect (InetSocketAddress. ^String host (int port)) (int connect-ms))
                   (.setSoTimeout (int timeout-ms)))]
      (->TcpLineSession socket
                        (BufferedReader. (InputStreamReader. (.getInputStream socket) StandardCharsets/UTF_8))
                        (OutputStreamWriter. (.getOutputStream socket) StandardCharsets/UTF_8)))))

(def tcp-connector
  "The production LineConnector: a fresh loopback TCP connection per request."
  (->TcpConnector))

(def token-file-advice
  "How to keep a control-token file trustworthy."
  "Keep the token in a regular file only you can read, e.g. `umask 077; python3 -c 'import secrets;print(secrets.token_hex(32))' > ~/.private/filmcraft-control.token`.")

(defn- token-source [token-ref]
  (case (:scheme token-ref)
    :env (str "environment variable " (:name token-ref))
    :file (str "token file " (:path token-ref))
    "the :token-ref"))

(def app-file-advice
  "How the app-written secret files stay trustworthy."
  "The app writes this file owner-only (mode 0600) when it binds its control port; remove a stale copy and restart the app as the user hive runs as.")

(defn- secret-problem
  "Why `value` cannot be the control secret of kind `kind`, or nil."
  [kind value]
  (case kind
    :hex64 (when-not (re-matches #"[0-9A-Fa-f]{64}" value)
             "does not hold 64 hex characters, which the app's control port requires")
    (when-not (re-matches #"[\x20-\x7E]{16,1024}" value)
      "does not hold 16 to 1024 printable ASCII characters, which the app's control port requires")))

(defn read-secret
  "Resolve a TokenRef at send time with the injected `getenv` and check it is a secret of `kind`
   (:hex64 or :printable): {:ok Redacted}, or a refusal naming the source and the fix. A :file ref
   is read only when the file is a regular file only the user can access. No hint ever carries
   the value."
  [token-ref getenv kind]
  (let [raw (case (:scheme token-ref)
              :env (if-let [v (not-empty (getenv (:name token-ref)))]
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
      (secret-problem kind value) (core/refusal :artcraft/token-refused
                                                (str "The " (token-source token-ref) " " (secret-problem kind value) "."))
      :else {:ok (control-file/->Redacted value)})))

(defn read-token
  "FilmCraft/CADCraft form of read-secret: the TokenRef must hold 64 hex characters."
  [token-ref getenv]
  (read-secret token-ref getenv :hex64))

(defn expand-pattern
  "Expand an :app/auth :file-pattern for `port`: {port} becomes the port, ${VAR} the value of VAR
   from the injected `getenv` and ${VAR:-default} that value or the default. nil when a ${VAR}
   without a default is unset or empty (the pattern does not apply on this machine)."
  [pattern port getenv]
  (let [unset (volatile! false)
        path (str/replace (str/replace pattern "{port}" (str port))
                          #"\$\{([A-Za-z_][A-Za-z0-9_]*)(?::-([^}]*))?\}"
                          (fn [[_ var default]]
                            (or (not-empty (getenv var)) default (do (vreset! unset true) ""))))]
    (when-not @unset path)))

(defn resolve-secret
  "The secret an AuthSpec names for a connection to `port`: its :ref first, then each
   :file-pattern the app writes, in order. {:ok Redacted :source <where>} or a refusal; a file
   that exists but is not trustworthy (symlink, another owner, group/other access) refuses rather
   than falling through. Never carries the value."
  [{:keys [ref file-pattern secret field] :or {secret :printable field "token"}} port getenv]
  (let [from-ref (when ref (read-secret ref getenv secret))]
    (cond
      (and from-ref (not= :artcraft/token-missing (get-in from-ref [:error :kind])))
      (cond-> from-ref (:ok from-ref) (assoc :source (token-source ref)))

      (empty? file-pattern) from-ref

      :else
      (let [paths (vec (keep #(expand-pattern % port getenv) file-pattern))]
        (loop [[path & more] paths]
          (if-not path
            (core/refusal :artcraft/token-missing
                          (str (when ref (str (token-source ref) " is not set and "))
                               "no " field " file the app writes for port " port " was found"
                               (if (seq paths) (str " (looked for " (str/join ", " paths) ")") " (no pattern applies on this machine)")
                               "."))
            (let [r (read-secret {:scheme :file :path path} getenv secret)]
              (cond
                (= :artcraft/token-missing (get-in r [:error :kind])) (recur more)
                (:error r) (core/refusal :artcraft/token-refused
                                         (str/replace (get-in r [:error :hint]) token-file-advice app-file-advice))
                :else (assoc r :source path)))))))))

(defn auth-of
  "The AuthSpec a transport config carries: :auth as given, or the FilmCraft/CADCraft shorthand
   :token-ref read as {:mode :per-request :field \"token\" :ref <it> :secret :hex64}; nil when the
   port does not authenticate."
  [config]
  (or (:auth config)
      (when-let [r (:token-ref config)]
        {:mode :per-request :field "token" :ref r :secret :hex64})))

(defn- secret!
  "Resolve the secret for one connection, or throw a refusal ex-info that does not carry it."
  [config auth]
  (let [r (resolve-secret auth (:port config) (or (:getenv config) #(System/getenv ^String %)))]
    (if (:error r)
      (throw (ex-info (get-in r [:error :hint]) {:kind (get-in r [:error :kind]) :refusal? true}))
      (.-value ^hive_artcraft.transport.control_file.Redacted (:ok r)))))

(defn wire-lines
  "The lines one request puts on the wire, in order, before a single reply is read for each:
   a :handshake auth first ({\"id\":0,\"method\":\"auth\",\"params\":{<field>: secret}}), then the
   request, which under :per-request carries the secret as a top-level <field>. Pure; the caller
   redacts the secret out of anything it reports."
  [auth secret request]
  (let [field (keyword (:field auth))]
    (case (:mode auth)
      :handshake [{:id 0 :method "auth" :params {field secret}} request]
      :per-request [(assoc request field secret)]
      [request])))

(defn- encode [value]
  (let [framed (core/frame (json/write-str value))]
    (if (:error framed)
      (throw (ex-info (get-in framed [:error :hint]) (dissoc framed :ok)))
      (:ok framed))))

(defn- reply! [session secret]
  (let [line (or (port/recv-line! session)
                 (throw (ex-info "The app closed the connection without a reply." {})))]
    (json/read-str (if secret (str/replace line secret "<redacted>") line) :key-fn keyword)))

(defn- exchange!
  "Open one connection through the injected LineConnector, send the request's wire lines (auth
   handshake first when the app wants one) and return the decoded reply to the request. A
   refused handshake throws a refusal naming the field, never the secret."
  [config auth secret request]
  (let [lines (mapv encode (wire-lines auth secret request))
        session (port/open-lines (or (:connector config) tcp-connector) (select-keys config [:host :port :connect-ms :timeout-ms]))]
    (try
      (when (= :handshake (:mode auth))
        (port/send-line! session (first lines))
        (let [ack (reply! session secret)]
          (when-not (true? (:ok ack))
            (throw (ex-info (str "The app refused the control " (:field auth) " handshake: "
                                 (or (:error ack) "no ok") ". The " (:field auth) " hive read does not match the running app's.")
                            {:kind :artcraft/auth-refused :refusal? true})))))
      (port/send-line! session (peek lines))
      (reply! session secret)
      (finally (port/close-lines! session)))))

(defrecord SocketTransport [config]
  port/ControlTransport
  (send-request [_ request]
    (let [auth (auth-of config)
          secret (when auth (secret! config auth))]
      (exchange! config auth secret request))))

(defn socket-transport
  "Build a transport for an app listening on --control <port>; config overrides default-config."
  [config]
  (->SocketTransport (merge default-config config)))

(def ^:private Secret
  [:or [:map [:ok [:fn #(instance? hive_artcraft.transport.control_file.Redacted %)]] [:source {:optional true} :string]]
   [:map [:error schema/ErrorValue]]])

(m/=> read-secret [:=> [:cat schema/TokenRef ifn? [:enum :hex64 :printable]] Secret])
(m/=> read-token [:=> [:cat schema/TokenRef ifn?] Secret])
(m/=> expand-pattern [:=> [:cat :string [:int {:min 1 :max 65535}] ifn?] [:maybe :string]])
(m/=> resolve-secret [:=> [:cat schema/AuthSpec [:int {:min 1 :max 65535}] ifn?] Secret])
(m/=> auth-of [:=> [:cat :map] [:maybe schema/AuthSpec]])
(m/=> wire-lines [:=> [:cat [:maybe schema/AuthSpec] [:maybe :string] schema/Request] [:vector :map]])
(m/=> socket-transport [:=> [:cat [:map [:port [:int {:min 1 :max 65535}]]
                                   [:token-ref {:optional true} schema/TokenRef]
                                   [:auth {:optional true} schema/AuthSpec]]]
                        [:fn #(satisfies? port/ControlTransport %)]])
