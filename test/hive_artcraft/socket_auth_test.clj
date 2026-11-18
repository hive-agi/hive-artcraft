(ns hive-artcraft.socket-auth-test
  "The socket transport's AuthSpec (:app/auth): GridCraft, MaterialCraft and TextureCraft open
   every connection with {\"id\":0,\"method\":\"auth\",\"params\":{\"token\":...}}; WordCraft sends
   its per-window key as a top-level \"key\" on every request. The secret comes from the :ref or
   the file the app writes, is resolved per connection, and never surfaces in a result, an error,
   a doctor report or a rendering. Connections go through port/LineConnector: these tests pass a
   scripted stub adapter (no socket, no app, no with-redefs). The secrets below are fakes."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test.check.generators :as gen]
            [hive-test.trifecta :refer [deftrifecta]]
            [hive-artcraft.port :as port]
            [hive-artcraft.registry :as registry]
            [hive-artcraft.schema :as schema]
            [hive-artcraft.service :as service]
            [hive-artcraft.mount :as mount]
            [hive-artcraft.transport.socket :as socket]
            [hive-artcraft.fixtures :as fx])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute PosixFilePermissions)))

(def secret "gridcraft-fake-secret-0123456789")
(def key-hex (apply str (repeat 8 "beefcafe")))

(defn module [id] (get-in (registry/load-registry) [:ok :by-id id]))
(defn leaks? [x] (or (str/includes? (pr-str x) secret) (str/includes? (pr-str x) key-hex)))
(defn env-of [m] (fn [k] (get m k)))

(def handshake {:mode :handshake :field "token" :ref {:scheme :env :name "GRIDCRAFT_CONTROL_TOKEN"}
                :file-pattern ["${XDG_RUNTIME_DIR}/gridcraft/control-{port}.token"]})
(def per-key {:mode :per-request :field "key" :ref {:scheme :env :name "WORDCRAFT_CONTROL_KEY"}
              :file-pattern ["${HOME}/.config/wordcraft/control-key.local-{port}"]})

(defn- tmp-dir [] (str (Files/createTempDirectory "hive-artcraft-auth" (make-array FileAttribute 0))))

(defn- secret-file! [dir rel body perms]
  (let [f (io/file dir rel)]
    (io/make-parents f)
    (spit f body)
    (Files/setPosixFilePermissions (.toPath f) (PosixFilePermissions/fromString perms))
    (str f)))

;; --- the stub adapter -------------------------------------------------------------------

(defrecord ScriptedSession [app sent closed]
  port/LineSession
  (send-line! [_ line] (swap! sent conj line))
  (recv-line! [_] (when-let [line (peek @sent)] (json/write-str (app (json/read-str line) (count @sent)))))
  (close-lines! [_] (reset! closed true)))

(defrecord ScriptedConnector [app connections]
  port/LineConnector
  (open-lines [_ endpoint]
    (let [s (->ScriptedSession app (atom []) (atom false))]
      (swap! connections conj (assoc endpoint :session s))
      s)))

(defn connector
  "A LineConnector that answers each line with (app decoded-line n-th-line-on-connection) and
   records every connection's endpoint and lines."
  [app]
  (->ScriptedConnector app (atom [])))

(defn lines [c] (mapv #(mapv json/read-str @(:sent (:session %))) @(:connections c)))

(defn handshake-app
  "Like GridCraft: the first line must be auth {token}, later lines answer {method}."
  [want]
  (fn [msg n]
    (if (= 1 n)
      (if (and (= "auth" (get msg "method")) (= want (get-in msg ["params" "token"])))
        {:id 0 :ok true :result {:authenticated true}}
        {:ok false :error "authentication required"})
      {:id (get msg "id") :ok true :result {:method (get msg "method") :params (get msg "params")}})))

(defn key-app
  "Like WordCraft: every request carries a top-level key."
  [want]
  (fn [msg _]
    (if (= want (get msg "key"))
      {:id (get msg "id") :ok true :result {:method (get msg "method")}}
      {:ok false :error "unauthorized"})))

;; --- trifectas of the new public fns ------------------------------------------------------

(def ^:private scrub #(str/replace % #"/tmp/hive-artcraft-auth\d+" "<tmp>"))

(def good-dir (tmp-dir))
(def _good (secret-file! good-dir "gridcraft/control-7985.token" (str secret "\n") "rw-------"))
(def open-dir (tmp-dir))
(def _open (secret-file! open-dir "gridcraft/control-7985.token" secret "rw-r--r--"))

(deftrifecta read-secret-contract socket/read-secret
  {:golden-path "test/golden/read-secret.edn"
   :cases {:printable [{:scheme :env :name "GRIDCRAFT_CONTROL_TOKEN"} (env-of {"GRIDCRAFT_CONTROL_TOKEN" secret}) :printable]
           :too-short [{:scheme :env :name "GRIDCRAFT_CONTROL_TOKEN"} (env-of {"GRIDCRAFT_CONTROL_TOKEN" "short"}) :printable]
           :hex-wanted [{:scheme :env :name "GRIDCRAFT_CONTROL_TOKEN"} (env-of {"GRIDCRAFT_CONTROL_TOKEN" secret}) :hex64]
           :unset [{:scheme :env :name "WORDCRAFT_CONTROL_KEY"} fx/no-env :hex64]}
   :apply? true
   :xf #(if (:ok %) {:ok (pr-str (:ok %))} %)
   :gen (gen/tuple (gen/return {:scheme :env :name "A_CONTROL_TOKEN"})
                   (gen/fmap (fn [s] (env-of {"A_CONTROL_TOKEN" s})) gen/string-ascii)
                   (gen/elements [:printable :hex64]))
   :pred #(and (or (:ok %) (:error %)) (not (leaks? %))) :num-tests 30
   :mutations [["accept-anything" (fn [_ _ _] {:ok (hive_artcraft.transport.control_file.Redacted. "x")})]
               ["leak" (fn [_ _ _] {:error {:kind :artcraft/token-refused :hint secret}})]]})

(deftrifecta expand-pattern-contract socket/expand-pattern
  {:golden-path "test/golden/expand-pattern.edn"
   :cases {:xdg ["${XDG_RUNTIME_DIR}/gridcraft/control-{port}.token" 7985 (env-of {"XDG_RUNTIME_DIR" "/run/user/1000"})]
           :unset ["${XDG_RUNTIME_DIR}/gridcraft/control-{port}.token" 7985 fx/no-env]
           :default ["${TMPDIR:-/tmp}/texturecraft-control-{port}.token" 7989 fx/no-env]
           :tmpdir ["${TMPDIR:-/tmp}/texturecraft-control-{port}.token" 7989 (env-of {"TMPDIR" "/var/t"})]}
   :apply? true
   :gen (gen/tuple (gen/return "/x/control-{port}.token") (gen/choose 1 65535) (gen/return fx/no-env))
   :pred #(and (string? %) (not (str/includes? % "{port}"))) :num-tests 30
   :mutations [["no-port" (fn [p _ _] p)] ["always-nil" (fn [_ _ _] nil)]]})

(deftrifecta resolve-secret-contract socket/resolve-secret
  {:golden-path "test/golden/resolve-secret.edn"
   :cases {:env-wins [handshake 7985 (env-of {"GRIDCRAFT_CONTROL_TOKEN" secret "XDG_RUNTIME_DIR" good-dir})]
           :file [handshake 7985 (env-of {"XDG_RUNTIME_DIR" good-dir})]
           :open-file [handshake 7985 (env-of {"XDG_RUNTIME_DIR" open-dir})]
           :nothing [handshake 7985 (env-of {"XDG_RUNTIME_DIR" "/nonexistent"})]
           :no-pattern-applies [handshake 7985 fx/no-env]}
   :apply? true
   :xf #(cond-> % (:ok %) (update :ok pr-str) (:source %) (update :source scrub) (:error %) (update-in [:error :hint] scrub))
   :gen (gen/tuple (gen/return handshake) (gen/choose 1 65535) (gen/return fx/no-env))
   :pred #(and (= :artcraft/token-missing (get-in % [:error :kind])) (not (leaks? %))) :num-tests 20
   :mutations [["ignore-files" (fn [a _ g] (socket/read-secret (:ref a) g :printable))]
               ["leak" (fn [_ _ _] {:error {:kind :artcraft/token-missing :hint secret}})]]})

(deftrifecta auth-of-contract socket/auth-of
  {:golden-path "test/golden/auth-of.edn"
   :cases {:auth [{:auth handshake}] :token-ref [{:token-ref {:scheme :env :name "FILMCRAFT_CONTROL_TOKEN"}}] :none [{}]}
   :apply? true
   :gen (gen/return [{}])
   :pred nil? :num-tests 10
   :mutations [["always-handshake" (fn [_] handshake)]]})

(deftrifecta wire-lines-contract socket/wire-lines
  {:golden-path "test/golden/wire-lines.edn"
   :cases {:handshake [handshake "S3CR3T-0123456789" {:id 1 :method "ping" :params {}}]
           :per-key [per-key "S3CR3T-0123456789" {:id 2 :method "ui.inspect" :params {}}]
           :none [nil nil {:id 3 :method "ping" :params {}}]}
   :apply? true
   :gen (gen/tuple (gen/return nil) (gen/return nil) (gen/fmap #(hash-map :id % :method "ping" :params {}) gen/nat))
   :pred #(= 1 (count %)) :num-tests 20
   :mutations [["drop-request" (fn [_ _ _] [])] ["no-handshake-dup" (fn [_ _ r] [r r])]]})

(deftrifecta auth-spec-contract service/auth-spec
  {:golden-path "test/golden/auth-spec.edn"
   :cases {:gridcraft [(module "gridcraft") {}]
           :wordcraft-override [(module "wordcraft") {:token-ref {:scheme :file :path "/k"}}]
           :filmcraft [(module "filmcraft") {}]
           :figcraft [(module "figcraft") {}]}
   :apply? true
   :gen (gen/tuple (gen/return fx/port-app) (gen/return {}))
   :pred nil? :num-tests 10
   :mutations [["always-handshake" (fn [_ _] handshake)]]})

(deftrifecta command-run-request-contract service/command-run-request
  {:golden-path "test/golden/command-run-request.edn"
   :cases {:bare {:ok {:id 1 :method "engine.execute" :params {:command "layer.add.fill" :params {}}}}
           :params {:ok {:id 2 :method "engine.execute" :params {:command "layer.add.fill" :params {:color "#336699"}}}}
           :error {:error {:kind :artcraft/unknown-command :hint "x"}}}
   :apply? true
   :gen (gen/fmap #(hash-map :ok {:id % :method "engine.execute" :params {:command "a.b" :params {}}}) gen/nat)
   :pred #(= "command.run" (get-in % [:ok :method])) :num-tests 20
   :mutations [["keep-execute" identity]]})

;; --- behaviour ---------------------------------------------------------------------------

(deftest the-four-modules-declare-their-auth
  (doseq [[id mode field call-method] [["gridcraft" :handshake "token" nil]
                                       ["materialcraft" :handshake "token" "command.run"]
                                       ["texturecraft" :handshake "token" "command.run"]
                                       ["wordcraft" :per-request "key" nil]]]
    (testing id
      (let [m (module id)]
        (is (= {:ok m} (schema/validate-module m)))
        (is (= [mode field] ((juxt :mode :field) (:app/auth m))))
        (is (= call-method (:app/call-method m)))
        (is (not (contains? m :app/token-ref))))))
  (testing "filmcraft and cadcraft keep :app/token-ref"
    (is (= {:scheme :env :name "FILMCRAFT_CONTROL_TOKEN"} (:app/token-ref (module "filmcraft"))))
    (is (= {:scheme :env :name "CADCRAFT_CONTROL_TOKEN"} (:app/token-ref (module "cadcraft")))))
  (testing "the schema refuses both forms at once, auth on a non-:port app, and an auth with no source"
    (is (:error (schema/validate-module (assoc (module "gridcraft") :app/token-ref {:scheme :env :name "GRIDCRAFT_CONTROL_TOKEN"}))))
    (is (:error (schema/validate-module (assoc fx/file-app :app/auth handshake))))
    (is (:error (schema/validate-module (assoc (module "gridcraft") :app/auth {:mode :handshake :field "token"}))))))

(deftest handshake-goes-first-on-every-connection
  (doseq [id ["gridcraft" "materialcraft" "texturecraft"]]
    (testing id
      (let [mod (module id)
            c (connector (handshake-app secret))
            t (socket/socket-transport {:port 7985 :auth (:app/auth mod) :connector c
                                        :getenv (env-of {(get-in mod [:app/auth :ref :name]) secret})})
            entries (:ok (service/load-catalog mod))
            cmd (:id (first (:engine entries)))
            res {:port 7985 :source :default}
            ctl (service/control mod entries t res (first (:control entries)) {} 1)
            run (service/call mod entries t res cmd {:x 1} 2)
            [[auth1 req1] [auth2 req2]] (lines c)]
        (is (:ok ctl))
        (is (:ok run))
        (is (= 2 (count @(:connections c))) "one connection per request")
        (is (= {"id" 0 "method" "auth" "params" {"token" secret}} auth1 auth2))
        (is (= (first (:control entries)) (get req1 "method")))
        (is (not (contains? req1 "token")) "the secret is not repeated on the request")
        (if (= "command.run" (:app/call-method mod))
          (is (= {"id" 2 "method" "command.run" "params" {"id" cmd "params" {"x" 1}}} req2))
          (is (= {"id" 2 "method" "engine.execute" "params" {"command" cmd "params" {"x" 1}}} req2)))
        (is (every? (comp deref :closed :session) @(:connections c)) "every connection is closed")
        (is (not-any? leaks? [ctl run (mount/tool-result ctl) (mount/tool-result run)]))))))

(deftest wordcraft-key-rides-on-every-request
  (let [dir (tmp-dir)
        _ (secret-file! dir ".config/wordcraft/control-key.local-7986" (str key-hex "\n") "rw-------")
        mod (module "wordcraft")
        entries (:ok (service/load-catalog mod))
        c (connector (key-app key-hex))
        t (socket/socket-transport {:port 7986 :auth (:app/auth mod) :connector c :getenv (env-of {"HOME" dir})})
        r (service/control mod entries t {:port 7986 :source :default} "ui.inspect" {} 5)
        [[req]] (lines c)]
    (is (= {:ok {:method "ui.inspect"}} r))
    (is (= key-hex (get req "key")))
    (is (= 1 (count (first (lines c)))) "no handshake line for :per-request")
    (is (not (leaks? r)))))

(deftest refused-handshake-and-missing-secret-are-values-without-the-secret
  (let [mod (module "gridcraft")
        entries (:ok (service/load-catalog mod))
        res {:port 7985 :source :default}
        wrong (connector (handshake-app "a-different-secret-xyz"))
        refused (service/control mod entries
                                 (socket/socket-transport {:port 7985 :auth (:app/auth mod) :connector wrong
                                                           :getenv (env-of {"GRIDCRAFT_CONTROL_TOKEN" secret})})
                                 res "window.list" {} 1)
        none (connector (handshake-app secret))
        missing (service/control mod entries
                                 (socket/socket-transport {:port 7985 :auth (:app/auth mod) :connector none
                                                           :getenv (env-of {"XDG_RUNTIME_DIR" "/nonexistent"})})
                                 res "window.list" {} 1)]
    (is (= :artcraft/auth-refused (get-in refused [:error :kind])))
    (is (= 1 (count (first (lines wrong)))) "the request is never sent after a refused handshake")
    (is (= :artcraft/token-missing (get-in missing [:error :kind])))
    (is (str/includes? (get-in missing [:error :hint]) "/nonexistent/gridcraft/control-7985.token"))
    (is (empty? @(:connections none)) "no connection without a secret")
    (is (not-any? leaks? (mapcat (juxt identity mount/tool-result) [refused missing])))))

(deftest an-echoing-app-cannot-leak-the-secret
  (let [mod (module "gridcraft")
        echo (connector (fn [msg n] (if (= 1 n) {:id 0 :ok true :result {}} {:ok false :error (str "bad " (pr-str msg) " " secret)})))
        r (service/control mod (:ok (service/load-catalog mod))
                           (socket/socket-transport {:port 7985 :auth (:app/auth mod) :connector echo
                                                     :getenv (env-of {"GRIDCRAFT_CONTROL_TOKEN" secret})})
                           {:port 7985 :source :default} "window.list" {} 1)]
    (is (= :artcraft/upstream-error (get-in r [:error :kind])))
    (is (str/includes? (get-in r [:error :hint]) "<redacted>"))
    (is (not (leaks? r)))))

(deftest secret-is-read-per-connection
  (let [env (atom {})
        mod (module "texturecraft")
        c (connector (handshake-app secret))
        t (socket/socket-transport {:port 7989 :auth (:app/auth mod) :connector c :getenv #(get @env %)})
        entries (:ok (service/load-catalog mod))
        res {:port 7989 :source :default}
        before (service/control mod entries t res "ping" {} 1)
        _ (swap! env assoc "TEXTURECRAFT_CONTROL_TOKEN" secret "TMPDIR" "/nonexistent")
        after (service/control mod entries t res "ping" {} 2)]
    (is (#{:artcraft/token-missing :artcraft/token-refused} (get-in before [:error :kind])))
    (is (:ok after))))

(deftest doctor-reports-auth-mode-and-source-never-the-secret
  (let [dir (tmp-dir)
        _ (secret-file! dir "gridcraft/control-7985.token" secret "rw-------")
        mod (module "gridcraft")
        entries (:ok (service/load-catalog mod))
        res {:port 7985 :source :default}
        found (:ok (service/doctor mod entries (service/resolve-transport mod {} res (env-of {"XDG_RUNTIME_DIR" dir})) res))
        lost (:ok (service/doctor mod entries (service/resolve-transport mod {} res (env-of {"XDG_RUNTIME_DIR" "/nonexistent" "HOME" "/nonexistent"})) res))]
    (is (= {:status :configured :mode :handshake :field "token"} (select-keys (:token found) [:status :mode :field])))
    (is (str/ends-with? (get-in found [:token :source]) "gridcraft/control-7985.token"))
    (is (= "Transport ready; calls go to the running app." (:hint found)))
    (is (= :artcraft/token-missing (get-in lost [:token :status])))
    (is (str/includes? (get-in lost [:token :fix]) "writes its token to"))
    (is (not-any? leaks? [found lost]))))
