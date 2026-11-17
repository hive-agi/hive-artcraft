(ns hive-artcraft.transport.control-file
  "JVM client for a :control-file-shaped app (PdfCraft `--control <FILE>`). The app listens on a
   RANDOM loopback port and writes {port, token} to a user-owned file. Each request reads that
   file again, connects, sends the token first (JSON-RPC `auth`), then one ui.* request.
   The token is held in a Redacted box that prints as <redacted>; no error, log line or return
   value of this namespace ever carries its value (memory axiom 20260504113116-48076a09)."
  (:require [clojure.data.json :as json]
            [hive-artcraft.core :as core]
            [hive-artcraft.port :as port]
            [malli.core :as m])
  (:import (java.io BufferedReader InputStreamReader OutputStreamWriter)
           (java.net InetSocketAddress Socket)
           (java.nio ByteBuffer)
           (java.nio.channels SeekableByteChannel)
           (java.nio.charset StandardCharsets)
           (java.nio.file Files LinkOption OpenOption Path Paths StandardOpenOption)
           (java.nio.file.attribute PosixFileAttributes PosixFilePermissions)))

(def default-config
  "Loopback host and per-request connect and read bounds; :control-file comes from the app config."
  {:host "127.0.0.1" :connect-ms 2000 :timeout-ms 40000})

(def control-file-max
  "Most a control file may hold (the app writes under 100 bytes)."
  (* 64 1024))

(def advice
  "How to start PdfCraft so its control file is trustworthy."
  "Start the app with a control file in a folder only you can write, e.g. `pdfcraft --control ~/.pdfcraft-control.json`.")

(deftype Redacted [value]
  Object
  (toString [_] "<redacted>"))

(defmethod print-method Redacted [_ ^java.io.Writer w] (.write w "#<redacted>"))

(defn control-file-problem
  "Why user `me` cannot trust a control file with this `owner` and rwx `perms` string, or nil."
  [owner me perms]
  (cond
    (not= owner me) (str "owned by another user (" owner "; you are " me "), who may be the one listening for your commands")
    (not (re-matches #"[r-][w-][x-]------" perms))
    (str "other users have access to it (mode " perms "; the app writes it rw-------)")))

(defn expand-home
  "Expand a leading ~/ to the user's home directory; other paths pass through."
  [path]
  (if (or (= "~" path) (.startsWith ^String path "~/"))
    (str (System/getProperty "user.home") (subs path 1))
    path))

(defn- refuse [path problem]
  (core/refusal :artcraft/control-file-refused (str path ": " problem ". " advice)))

(defn- read-capped [^Path path]
  (with-open [^SeekableByteChannel ch (Files/newByteChannel path (into-array OpenOption [StandardOpenOption/READ LinkOption/NOFOLLOW_LINKS]))]
    (let [buf (ByteBuffer/allocate (inc control-file-max))]
      (while (and (.hasRemaining buf) (pos? (.read ch buf))))
      (when (<= (.position buf) control-file-max)
        (String. (.array buf) 0 (.position buf) StandardCharsets/UTF_8)))))

(defn- parse-endpoint [path text]
  (let [info (try (json/read-str text) (catch Exception _ ::bad))
        port (when (map? info) (get info "port"))
        token (when (map? info) (get info "token"))]
    (cond
      (not (map? info)) (refuse path "is not a JSON object")
      (not (and (integer? port) (<= 1 port 65535))) (refuse path "names no valid port")
      (not (and (string? token) (seq token))) (refuse path "holds no token")
      :else {:ok {:port port :token (->Redacted token)}})))

(defn read-trusted-file
  "Read a small secret file as text: refuse a symlink, a non-regular file, a file another user owns
   or can access, and one over control-file-max. Every refusal names `path` and ends with `advice`;
   a missing file says `missing` (`path: missing advice`). Never returns or reports the contents
   in a refusal."
  [path advice missing]
  (let [p (Paths/get ^String (expand-home path) (make-array String 0))
        nofollow (into-array LinkOption [LinkOption/NOFOLLOW_LINKS])
        refuse (fn [problem] (core/refusal :artcraft/control-file-refused (str path ": " problem ". " advice)))]
    (try
      (let [attrs ^PosixFileAttributes (Files/readAttributes p PosixFileAttributes ^"[Ljava.nio.file.LinkOption;" nofollow)]
        (cond
          (.isSymbolicLink attrs) (refuse "is a symbolic link, which could point at another user's file")
          (not (.isRegularFile attrs)) (refuse "is not a regular file")
          :else
          (if-let [problem (control-file-problem (.getName (.owner attrs)) (System/getProperty "user.name")
                                                 (PosixFilePermissions/toString (.permissions attrs)))]
            (refuse problem)
            (if-let [text (read-capped p)]
              {:ok text}
              (refuse (str "is too large for a control file (over " (quot control-file-max 1024) " KiB)"))))))
      (catch java.nio.file.NoSuchFileException _
        (core/refusal :artcraft/control-file-missing (str path ": " missing " " advice)))
      (catch Exception e
        (core/refusal :artcraft/control-file-refused (str path ": " (.getSimpleName (class e)) ". " advice))))))

(defn read-control-file
  "Read {port, token} from the app's control file; refuse a symlink, a non-regular file, a file
   another user owns or can access, and an oversized one. The token comes back as a Redacted box."
  [path]
  (let [text (read-trusted-file path advice "no such file; is the app running?")]
    (if (:error text) text (parse-endpoint path (:ok text)))))

(defn rpc-request
  "JSON-RPC 2.0 envelope for one validated {:id :method :params} control request."
  [request]
  {:jsonrpc "2.0" :id (:id request) :method (:method request) :params (:params request)})

(defn rpc-reply
  "Translate a decoded JSON-RPC reply into the {ok, result|error} shape core/response-line checks."
  [reply]
  (cond
    (not (map? reply)) {:ok false :error "the app sent a reply that is not a JSON object"}
    (contains? reply :error) {:ok false :error (let [e (:error reply)]
                                                 (if (map? e) (str (or (:message e) "error") (when (:code e) (str " (" (:code e) ")"))) (str e)))}
    (contains? reply :result) {:ok true :result (:result reply)}
    :else {:ok false :error "the app sent a reply with neither result nor error"}))

(defn- line! [^OutputStreamWriter out value]
  (let [framed (core/frame (json/write-str value))]
    (when (:error framed) (throw (ex-info (get-in framed [:error :hint]) {})))
    (.write out ^String (:ok framed))
    (.flush out)))

(defn- reply! [^BufferedReader in]
  (json/read-str (or (.readLine in) (throw (ex-info "The app closed the connection without a reply." {})))
                 :key-fn keyword))

(defn- exchange!
  "Connect to the endpoint, authenticate with the token, send one request and read its reply.
   Throws ex-info whose message never contains the token."
  [{:keys [host connect-ms timeout-ms]} {:keys [port token]} request]
  (with-open [socket (doto (Socket.)
                       (.connect (InetSocketAddress. ^String host (int port)) (int connect-ms))
                       (.setSoTimeout (int timeout-ms)))]
    (let [out (OutputStreamWriter. (.getOutputStream socket) StandardCharsets/UTF_8)
          in (BufferedReader. (InputStreamReader. (.getInputStream socket) StandardCharsets/UTF_8))]
      (line! out {:jsonrpc "2.0" :id "hive-artcraft-auth" :method "auth" :params {:token (.-value ^Redacted token)}})
      (let [auth (rpc-reply (reply! in))]
        (when-not (:ok auth)
          (throw (ex-info (str "The app refused the control-file token: " (:error auth)) {:port port}))))
      (line! out (rpc-request request))
      (rpc-reply (reply! in)))))

(defrecord ControlFileTransport [config]
  port/ControlTransport
  (send-request [_ request]
    (let [endpoint (read-control-file (:control-file config))]
      (if (:error endpoint)
        (throw (ex-info (get-in endpoint [:error :hint]) {:kind (get-in endpoint [:error :kind])}))
        (exchange! config (:ok endpoint) request)))))

(defn control-file-transport
  "Build a transport for an app started with --control <FILE>; config overrides default-config."
  [config]
  (->ControlFileTransport (merge default-config config)))

(m/=> expand-home [:=> [:cat :string] :string])
(m/=> control-file-problem [:=> [:cat :string :string :string] [:maybe :string]])
(m/=> read-trusted-file [:=> [:cat :string :string :string]
                         [:or [:map [:ok :string]] [:map [:error [:map [:kind :keyword] [:hint :string]]]]]])
(m/=> read-control-file [:=> [:cat :string]
                         [:or [:map [:ok [:map [:port [:int {:min 1 :max 65535}]] [:token [:fn #(instance? Redacted %)]]]]]
                          [:map [:error [:map [:kind :keyword] [:hint :string]]]]]])
(m/=> rpc-request [:=> [:cat [:map [:id :any] [:method :string] [:params :map]]] [:map [:jsonrpc [:= "2.0"]] [:method :string]]])
(m/=> rpc-reply [:=> [:cat :any] [:map [:ok :boolean]]])
(m/=> control-file-transport [:=> [:cat [:map [:control-file :string]]]
                              [:fn #(satisfies? port/ControlTransport %)]])
