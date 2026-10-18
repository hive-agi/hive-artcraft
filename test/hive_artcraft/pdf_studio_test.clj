(ns hive-artcraft.pdf-studio-test
  "PdfCraft (:control-file, live through transport.control-file against an IN-PROCESS fake server)
   and ArtCraft Studio (:none, catalog-only). Never starts either app. The test token is a fake;
   every check asserts it never surfaces in a result, a hint or a printed value."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test.check.generators :as gen]
            [malli.core :as m]
            [hive-test.trifecta :refer [deftrifecta]]
            [hive-test.golden :refer [deftest-golden]]
            [hive-addon.protocol :as protocol]
            [hive-artcraft.addon :as addon]
            [hive-artcraft.registry :as registry]
            [hive-artcraft.schema :as schema]
            [hive-artcraft.service :as service]
            [hive-artcraft.transport.control-file :as cf]
            [extract-pdfcraft]
            [extract-studio])
  (:import (java.io BufferedReader InputStreamReader PrintWriter)
           (java.net ServerSocket)
           (java.nio.file Files Path)
           (java.nio.file.attribute FileAttribute PosixFilePermissions)))

(def token "fake-test-token-0123456789abcdef")

(defn- tmp-dir [] (str (Files/createTempDirectory "hive-artcraft-cf" (make-array FileAttribute 0))))

(defn write-control!
  "Write a control file like `pdfcraft --control FILE` does (mode `perms`, default rw-------)."
  ([dir name body] (write-control! dir name body "rw-------"))
  ([dir name body perms]
   (let [f (io/file dir name)]
     (spit f (if (string? body) body (json/write-str body)))
     (Files/setPosixFilePermissions (.toPath f) (PosixFilePermissions/fromString perms))
     (str f))))

(def dir (tmp-dir))
(def good-file (write-control! dir "good.json" {:port 4242 :token token :pid 1}))
(def open-file (write-control! dir "open.json" {:port 4242 :token token} "rw-r--r--"))
(def link-file (let [p (.toPath (io/file dir "link.json"))]
                 (str (Files/createSymbolicLink p (.toPath (io/file good-file)) (make-array FileAttribute 0)))))
(def bad-json (write-control! dir "bad.json" "not json"))
(def no-token (write-control! dir "notoken.json" {:port 4242}))
(def bad-port (write-control! dir "badport.json" {:port 70000 :token token}))
(def huge-file (write-control! dir "huge.json" (apply str (repeat (inc cf/control-file-max) " "))))

(defn leaks? [x] (str/includes? (pr-str x) token))

(defn fake-server!
  "Serve `n` connections like PdfCraft's control.rs: auth {token} first, then one ui.* request."
  [^ServerSocket server n seen]
  (future
    (dotimes [_ n]
      (with-open [s (.accept server)]
        (let [in (BufferedReader. (InputStreamReader. (.getInputStream s)))
              out (PrintWriter. (.getOutputStream s) true)
              auth (json/read-str (.readLine in))]
          (if (and (= "auth" (get auth "method")) (= token (get-in auth ["params" "token"])))
            (let [_ (.println out (json/write-str {:jsonrpc "2.0" :id (get auth "id") :result {:ok true}}))
                  req (json/read-str (.readLine in))]
              (swap! seen conj {:first (get auth "method") :method (get req "method") :params (get req "params")})
              (.println out (json/write-str (if (= "ui.fail" (get req "method"))
                                              {:jsonrpc "2.0" :id (get req "id") :error {:code -32000 :message "no document"}}
                                              {:jsonrpc "2.0" :id (get req "id")
                                               :result {:method (get req "method") :params (get req "params")}}))))
            (do (swap! seen conj :refused)
                (.println out (json/write-str {:jsonrpc "2.0" :id (get auth "id")
                                               :error {:code -32001 :message "authenticate first: auth {token}"}})))))))))

(defn module [id] (get-in (registry/load-registry) [:ok :by-id id]))
(def pdf (module "pdfcraft"))
(def studio (module "studio"))
(def pdf-catalog (:ok (service/load-catalog pdf)))
(def studio-catalog (:ok (service/load-catalog studio)))

(defn running [config]
  (doto (addon/addon-ctor (assoc config :getenv (constantly nil))) (protocol/initialize! {})))

(defn handler [instance] (:handler (first (protocol/tools instance))))
(defn read-text [result] (edn/read-string (-> result :content first :text)))

;; ---------------------------------------------------------------- trifectas (new public fns)

(deftrifecta expand-home-contract cf/expand-home
  {:golden-path "test/golden/expand-home.edn"
   :cases {:absolute "/etc/x" :relative "x.json"}
   :gen (gen/fmap #(str "/" %) gen/string-alphanumeric) :pred #(str/starts-with? % "/") :num-tests 30
   :mutations [["always-home" (fn [_] (System/getProperty "user.home"))]]})

(deftest expand-home-expands-tilde
  (is (= (str (System/getProperty "user.home") "/.pdfcraft-control.json") (cf/expand-home "~/.pdfcraft-control.json"))))

(deftrifecta control-file-problem-contract cf/control-file-problem
  {:golden-path "test/golden/control-file-problem.edn"
   :cases {:mine ["me" "me" "rw-------"] :read-only ["me" "me" "r--------"]
           :other-owner ["root" "me" "rw-------"] :group-readable ["me" "me" "rw-r-----"]
           :world ["me" "me" "rw-rw-rw-"]}
   :apply? true :gen (gen/tuple (gen/return "me") (gen/return "me") (gen/elements ["rw-------" "r--------" "rwx------"]))
   :pred nil? :num-tests 30
   :mutations [["trust-all" (fn [_ _ _] nil)] ["owner-only" (fn [o me _] (when (not= o me) "other"))]]})

(defn- endpoint-view [r]
  (if (:ok r) {:port (get-in r [:ok :port]) :token (str (get-in r [:ok :token]))} {:error (get-in r [:error :kind])}))

(deftrifecta read-control-file-contract cf/read-control-file
  {:golden-path "test/golden/read-control-file.edn"
   :cases {:good good-file :open open-file :symlink link-file :missing (str dir "/absent.json")
           :bad-json bad-json :no-token no-token :bad-port bad-port :huge huge-file}
   :xf endpoint-view
   :gen (gen/elements [good-file link-file open-file]) :pred #(not (leaks? %)) :num-tests 30
   :mutations [["trust-everything" (fn [_] {:ok {:port 4242 :token (cf/->Redacted token)}})]
               ["leak-token" (fn [_] {:ok {:port 4242 :token token}})]]})

(deftrifecta rpc-request-contract cf/rpc-request
  {:golden-path "test/golden/rpc-request.edn"
   :cases {:command {:id 2 :method "ui.command" :params {:id "file.save"}} :state {:id "s" :method "ui.state" :params {}}}
   :gen (gen/fmap #(hash-map :id % :method "ui.state" :params {}) gen/nat)
   :pred #(and (= "2.0" (:jsonrpc %)) (= "ui.state" (:method %))) :num-tests 30
   :mutations [["drop-version" (fn [r] (dissoc r :jsonrpc))]]})

(deftrifecta rpc-reply-contract cf/rpc-reply
  {:golden-path "test/golden/rpc-reply.edn"
   :cases {:result {:jsonrpc "2.0" :id 1 :result {:ok true}} :null {:id 1 :result nil}
           :error {:id 1 :error {:code -32000 :message "no document"}} :empty {} :not-map 7}
   :gen (gen/elements [{:result 1} {:error "x"} {} nil]) :pred #(boolean? (:ok %)) :num-tests 30
   :mutations [["error-is-ok" (fn [r] {:ok true :result (:result r)})]]})

(deftrifecta control-file-transport-contract cf/control-file-transport
  {:golden-path "test/golden/control-file-transport.edn"
   :cases {:file {:control-file "~/.pdfcraft-control.json"} :host {:control-file "/x" :host "localhost"}}
   :xf :config
   :gen (gen/fmap #(hash-map :control-file (str "/" %)) gen/string-alphanumeric)
   :pred #(= "127.0.0.1" (get-in % [:config :host])) :num-tests 30
   :mutations [["drop-defaults" (fn [c] (cf/->ControlFileTransport c))]]})

(deftrifecta ui-command-request-contract service/ui-command-request
  {:golden-path "test/golden/ui-command-request.edn"
   :cases {:bare {:ok {:id 3 :method "engine.execute" :params {:command "file.save" :params {}}}}
           :with-params {:ok {:id 4 :method "engine.execute" :params {:command "page.rotate" :params {:angle 90}}}}
           :refused {:error {:kind :artcraft/unknown-command :hint "x"}}}
   :gen (gen/fmap #(hash-map :ok {:id % :method "engine.execute" :params {:command "file.save" :params {}}}) gen/nat)
   :pred #(= {:id "file.save"} (get-in % [:ok :params])) :num-tests 30
   :mutations [["keep-engine" identity]]})

;; ---------------------------------------------------------------- the control-file transport, live

(deftest ui-command-round-trip-sends-token-first
  (with-open [server (ServerSocket. 0)]
    (let [seen (atom [])
          _ (fake-server! server 3 seen)
          file (write-control! (tmp-dir) "ctl.json" {:port (.getLocalPort server) :token token :pid 1})
          t (service/resolve-transport pdf {:control-file file} nil)
          called (service/call pdf pdf-catalog t nil "file.save" {} 2)
          inspected (service/control pdf pdf-catalog t nil "ui.inspect" {:depth 1} 3)
          failed (service/call pdf (update pdf-catalog :engine conj {:id "x" :label "x"}) t nil "x" {:n 1} 4)]
      (is (instance? hive_artcraft.transport.control_file.ControlFileTransport t))
      (is (= {:ok {:method "ui.command" :params {:id "file.save"}}} called))
      (is (= {:ok {:method "ui.inspect" :params {:depth 1}}} inspected))
      (is (= {:ok {:method "ui.command" :params {:id "x" :params {:n 1}}}} failed))
      (is (= [{:first "auth" :method "ui.command" :params {"id" "file.save"}}
              {:first "auth" :method "ui.inspect" :params {"depth" 1}}
              {:first "auth" :method "ui.command" :params {"id" "x" "params" {"n" 1}}}] @seen))
      (is (not-any? leaks? [called inspected failed t])))))

(deftest upstream-error-and-wrong-token-are-values-without-the-token
  (with-open [server (ServerSocket. 0)]
    (let [seen (atom [])
          _ (fake-server! server 2 seen)
          d (tmp-dir)
          good (write-control! d "ctl.json" {:port (.getLocalPort server) :token token})
          wrong (write-control! d "wrong.json" {:port (.getLocalPort server) :token "not-the-token"})
          upstream (service/control pdf (update pdf-catalog :control conj "ui.fail")
                                    (cf/control-file-transport {:control-file good}) nil "ui.fail" {} 1)
          refused (service/control pdf pdf-catalog (cf/control-file-transport {:control-file wrong}) nil "ui.state" {} 1)]
      (is (= :artcraft/upstream-error (get-in upstream [:error :kind])))
      (is (re-find #"no document" (get-in upstream [:error :hint])))
      (is (= :artcraft/transport-failed (get-in refused [:error :kind])))
      (is (re-find #"refused the control-file token" (get-in refused [:error :hint])))
      (is (not (str/includes? (pr-str refused) "not-the-token")))
      (is (not-any? leaks? [upstream refused])))))

(deftest untrusted-control-files-refuse-before-connecting
  (doseq [f [open-file link-file]]
    (let [r (service/call pdf pdf-catalog (cf/control-file-transport {:control-file f}) nil "file.save" {} 1)]
      (is (= :artcraft/transport-failed (get-in r [:error :kind])))
      (is (re-find #"symbolic link|other users have access" (get-in r [:error :hint])))
      (is (not (leaks? r))))))

(deftest redacted-token-never-prints
  (let [r (cf/read-control-file good-file)]
    (is (= 4242 (get-in r [:ok :port])))
    (is (= "<redacted>" (str (get-in r [:ok :token]))))
    (is (= "#<redacted>" (pr-str (get-in r [:ok :token]))))
    (is (not (leaks? r)))))

;; ---------------------------------------------------------------- modules, catalogs, tool surface

(deftest modules-follow-the-contract
  (is (= {:ok pdf} (schema/validate-module pdf)))
  (is (= {:ok studio} (schema/validate-module studio)))
  (is (= [:native :control-file "hive_artcraft/catalogs/pdfcraft.edn"]
         ((juxt :app/kind :app/transport :app/catalog-resource) pdf)))
  (is (= [:native :none "hive_artcraft/catalogs/studio.edn"]
         ((juxt :app/kind :app/transport :app/catalog-resource) studio))))

(deftest catalogs-are-valid-and-pinned
  (is (m/validate schema/Catalog pdf-catalog))
  (is (m/validate schema/Catalog studio-catalog))
  (is (= "a8cd797a917b1007544575c7280c9a0ce6a17e8f" (:reference-revision pdf-catalog)))
  (is (= "7a2935cf9e3922e90e4ed5f3556b1cb107013148" (:reference-revision studio-catalog)))
  (is (= {:engine 201 :mcp 149 :control 17} (update-vals (select-keys pdf-catalog [:engine :mcp :control]) count)))
  (is (= {:engine 67 :mcp 0 :control 0} (update-vals (select-keys studio-catalog [:engine :mcp :control]) count)))
  (is (apply distinct? (map :id (:engine pdf-catalog))))
  (is (apply distinct? (map :id (:engine studio-catalog))))
  (is (every? #(str/starts-with? % "ui.") (:control pdf-catalog)))
  (is (not-any? #{"engine.execute"} (:control pdf-catalog)))
  (is (= #{"ui.command" "ui.commands" "ui.inspect" "ui.state"} (set (filter #{"ui.command" "ui.commands" "ui.inspect" "ui.state"} (:control pdf-catalog))))))

(deftest catalogs-reproduce-from-the-clones
  (when (.isDirectory (io/file extract-pdfcraft/default-root))
    (is (= pdf-catalog (extract-pdfcraft/extract extract-pdfcraft/default-root))))
  (when (.isDirectory (io/file extract-studio/default-root))
    (is (= studio-catalog (extract-studio/extract extract-studio/default-root)))))

(deftest studio-has-no-control-surface
  (let [h (handler (running {}))
        doctor (read-text (h {"app" "studio" "command" "doctor"}))]
    (is (= :none (:shape doctor)))
    (is (= :no-control-surface (:status doctor)))
    (is (re-find #"no external control surface" (:hint doctor)))
    (doseq [args [{"command" "call" "engine_command" (:id (first (:engine studio-catalog)))}
                  {"command" "control" "method" "ui.state"}]]
      (let [r (h (assoc args "app" "studio"))]
        (is (:isError r))
        (is (re-find #"unsupported-shape|unknown-method" (-> r :content first :text)))))))

(deftest pdfcraft-doctor-reports-the-control-file-not-the-token
  (let [h (handler (running {:apps {"pdfcraft" {:control-file good-file}}}))
        ready (read-text (h {"app" "pdfcraft" "command" "doctor"}))
        bad (read-text ((handler (running {:apps {"pdfcraft" {:control-file open-file}}})) {"app" "pdfcraft" "command" "doctor"}))
        unset (read-text ((handler (running {})) {"app" "pdfcraft" "command" "doctor"}))]
    (is (= [:control-file :ready :trusted 4242]
           [(:shape ready) (:transport ready) (get-in ready [:control-file :status]) (get-in ready [:control-file :port])]))
    (is (= :artcraft/control-file-refused (get-in bad [:control-file :status])))
    (is (= :absent (:transport unset)))
    (is (re-find #"--control ~/.pdfcraft-control.json" (:hint unset)))
    (is (not-any? leaks? [ready bad unset]))))

(deftest-golden pdf-studio-catalog-overview "test/golden/pdf-studio-catalog.edn"
  (let [h (handler (running {}))]
    (into (sorted-map) (for [id ["pdfcraft" "studio"]] [id (read-text (h {"app" id "command" "catalog"}))]))))

(deftest-golden pdf-studio-doctor "test/golden/pdf-studio-doctor.edn"
  (let [h (handler (running {}))]
    (into (sorted-map) (for [id ["pdfcraft" "studio"]] [id (read-text (h {"app" id "command" "doctor"}))]))))

(deftest-golden pdfcraft-control-vocabulary "test/golden/pdfcraft-control.edn"
  (:control pdf-catalog))
