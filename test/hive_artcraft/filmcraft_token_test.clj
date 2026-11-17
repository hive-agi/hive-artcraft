(ns hive-artcraft.filmcraft-token-test
  "FilmCraft's control port authenticates every JSON-lines request with a top-level \"token\"
   (craft-libs control-protocol parse_line). The token reaches the socket transport through an
   injected getenv or a trusted file, is read per request, and never surfaces in a result, an
   error, a doctor report or a printed transport. Apps without a token-ref send the request
   exactly as built. Never starts the app; the token below is a fake."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test.check.generators :as gen]
            [hive-test.trifecta :refer [deftrifecta]]
            [hive-addon.protocol :as protocol]
            [hive-artcraft.registry :as registry]
            [hive-artcraft.schema :as schema]
            [hive-artcraft.service :as service]
            [hive-artcraft.addon :as addon]
            [hive-artcraft.mount :as mount]
            [hive-artcraft.transport.socket :as socket]
            [hive-artcraft.transport.control-file :as cf]
            [hive-artcraft.fixtures :as fx])
  (:import (java.io BufferedReader InputStreamReader PrintWriter)
           (java.net ServerSocket)
           (java.nio.file Files)
           (java.nio.file.attribute FileAttribute PosixFilePermissions)))

(def token (apply str (repeat 16 "c0de")))
(def env-ref {:scheme :env :name "FILMCRAFT_CONTROL_TOKEN"})
(def film (get-in (registry/load-registry) [:ok :by-id "filmcraft"]))
(def vector-style (assoc fx/port-app :app/id "vectorcraft" :app/port-env "VECTORCRAFT_CONTROL_PORT"))
(def film-fixture (assoc fx/port-app :app/id "filmcraft" :app/label "FilmCraft" :app/default-port 9876
                         :app/port-env "FILMCRAFT_CONTROL_PORT" :app/token-ref env-ref))

(defn env-of [m] (fn [k] (get m k)))
(def with-token (env-of {"FILMCRAFT_CONTROL_TOKEN" token}))

(defn leaks? [x] (str/includes? (pr-str x) token))

(defn- tmp-file [body perms]
  (let [d (str (Files/createTempDirectory "hive-artcraft-token" (make-array FileAttribute 0)))
        f (io/file d "film.token")]
    (spit f body)
    (Files/setPosixFilePermissions (.toPath f) (PosixFilePermissions/fromString perms))
    (str f)))

(def good-file (tmp-file (str token "\n") "rw-------"))
(def open-file (tmp-file token "rw-r--r--"))

(defn- scrub [s] (str/replace s #"/tmp/\S+?/film\.token" "<tmp>/film.token"))

(defn- recorder!
  "Serve `n` connections like FilmCraft: record each request line, reply ok when its token
   matches, otherwise the upstream auth error."
  [^ServerSocket server n seen]
  (future
    (dotimes [_ n]
      (with-open [s (.accept server)]
        (let [in (BufferedReader. (InputStreamReader. (.getInputStream s)))
              out (PrintWriter. (.getOutputStream s) true)
              line (.readLine in)
              request (json/read-str line)]
          (swap! seen conj line)
          (.println out (json/write-str
                         (if (= token (get request "token"))
                           {:id (get request "id") :ok true :result {:method (get request "method")}}
                           {:ok false :error "control authentication failed; closing the connection"}))))))))

(defn- render [r] (mount/tool-result r))

;; --- the three facets of each new public fn ---------------------------------------------

(deftrifecta read-token-contract socket/read-token
  {:golden-path "test/golden/read-token.edn"
   :cases {:env [env-ref with-token] :unset [env-ref fx/no-env]
           :short [env-ref (env-of {"FILMCRAFT_CONTROL_TOKEN" "abc"})]
           :file [{:scheme :file :path good-file} fx/no-env]
           :open-file [{:scheme :file :path open-file} fx/no-env]}
   :apply? true
   :xf #(if (:ok %)
          {:ok (pr-str (:ok %)) :len (count (.-value ^hive_artcraft.transport.control_file.Redacted (:ok %)))}
          (update-in % [:error :hint] scrub))
   :gen (gen/tuple (gen/return env-ref) (gen/elements [fx/no-env with-token]))
   :pred #(and (or (:ok %) (:error %)) (not (leaks? %))) :num-tests 20
   :mutations [["accept-anything" (fn [_ _] {:ok (cf/->Redacted "x")})]
               ["leak-token" (fn [_ _] {:error {:kind :artcraft/token-refused :hint token}})]]})

(deftrifecta read-trusted-file-contract cf/read-trusted-file
  {:golden-path "test/golden/read-trusted-file.edn"
   :cases {:good [good-file "advice" "missing."] :open [open-file "advice" "missing."]
           :missing ["/nonexistent/film.token" "advice" "missing."]}
   :apply? true
   :xf #(if (:ok %) {:ok-len (count (:ok %))} (update-in % [:error :hint] scrub))
   :gen (gen/tuple (gen/return "/nonexistent/x") (gen/return "a") (gen/return "m"))
   :pred #(= :artcraft/control-file-missing (get-in % [:error :kind])) :num-tests 10
   :mutations [["trust-everything" (fn [_ _ _] {:ok token})]]})

(deftrifecta token-ref-contract service/token-ref
  {:golden-path "test/golden/token-ref.edn"
   :cases {:film-default [film-fixture {}]
           :film-override [film-fixture {:token-ref {:scheme :file :path "/x"}}]
           :vector-style [vector-style {}]
           :file-app [fx/file-app {:token-ref env-ref}]}
   :apply? true
   :gen (gen/tuple (gen/return vector-style) (gen/return {}))
   :pred nil? :num-tests 10
   :mutations [["always-film" (fn [_ _] env-ref)]]})

(deftrifecta token-remedy-contract service/token-remedy
  {:golden-path "test/golden/token-remedy.edn"
   :cases {:film film-fixture}
   :gen (gen/return film-fixture)
   :pred #(and (str/includes? % "FILMCRAFT_CONTROL_TOKEN") (str/includes? % "--control 9876")) :num-tests 5
   :mutations [["vague" (fn [_] "Configure a token.")]]})

;; --- behaviour ---------------------------------------------------------------------------

(deftest registry-filmcraft-declares-the-env-token-ref
  (is (= env-ref (:app/token-ref film)))
  (is (= {:ok film} (schema/validate-module film)))
  (is (:error (schema/validate-module (assoc fx/file-app :app/token-ref env-ref))) ":app/token-ref is for :port apps")
  (is (:error (schema/validate-module (assoc film :app/token-ref {:scheme :env :name "HOME"})))
      "an env ref must name an <APP>_CONTROL_TOKEN variable"))

(deftest token-is-injected-for-filmcraft-and-absent-for-vectorcraft-style
  (with-open [server (ServerSocket. 0)]
    (let [seen (atom [])
          _ (recorder! server 2 seen)
          res {:port (.getLocalPort server) :source :config}
          film-t (service/resolve-transport film-fixture {} res with-token)
          plain-t (service/resolve-transport vector-style {} res with-token)
          film-r (service/call film-fixture fx/entries film-t res "shape.rectangle" {} 2)
          plain-r (service/call vector-style fx/entries plain-t res "shape.rectangle" {} 3)
          [film-line plain-line] @seen]
      (testing "filmcraft: a top-level token on the request, and the call succeeds"
        (is (= {:ok {:method "engine.execute"}} film-r))
        (is (= token (get (json/read-str film-line) "token"))))
      (testing "vector-style: the bare request, byte for byte as before"
        (is (= (json/write-str {:id 3 :method "engine.execute" :params {:command "shape.rectangle" :params {}}})
               plain-line))
        (is (= :artcraft/upstream-error (get-in plain-r [:error :kind]))
            "the recorder demands a token, so the bare request is refused upstream"))
      (testing "neither result nor its rendering carries the token"
        (is (not-any? leaks? [film-r plain-r (render film-r) (render plain-r)]))))))

(deftest token-is-read-per-request
  (with-open [server (ServerSocket. 0)]
    (let [seen (atom [])
          _ (recorder! server 1 seen)
          env (atom {})
          res {:port (.getLocalPort server) :source :config}
          t (service/resolve-transport film-fixture {} res (fn [k] (get @env k)))
          before (service/control film-fixture fx/entries t res "ui.render" {} 1)
          _ (swap! env assoc "FILMCRAFT_CONTROL_TOKEN" token)
          after (service/control film-fixture fx/entries t res "ui.render" {} 2)]
      (is (= :artcraft/token-missing (get-in before [:error :kind])) "no connection without a token")
      (is (= {:ok {:method "ui.render"}} after) "a token exported later is picked up on the next request")
      (is (= 1 (count @seen))))))

(deftest token-never-appears-in-an-error
  (with-open [server (ServerSocket. 0)]
    (let [_ (future (with-open [s (.accept server)]
                      (let [in (BufferedReader. (InputStreamReader. (.getInputStream s)))
                            out (PrintWriter. (.getOutputStream s) true)
                            line (.readLine in)]
                        ;; A buggy app echoing the request back inside its error.
                        (.println out (json/write-str {:ok false :error (str "rejected " line)})))))
          res {:port (.getLocalPort server) :source :config}
          t (service/resolve-transport film-fixture {} res with-token)
          r (service/call film-fixture fx/entries t res "shape.rectangle" {} 4)]
      (is (= :artcraft/upstream-error (get-in r [:error :kind])))
      (is (str/includes? (get-in r [:error :hint]) "<redacted>"))
      (is (not (leaks? r)))
      (is (not (leaks? (render r))))))
  (testing "connection refused, bad token, untrusted token file"
    (let [res {:port 1 :source :config}
          refused (service/call film-fixture fx/entries
                                (socket/socket-transport {:port 1 :connect-ms 300 :token-ref env-ref :getenv with-token})
                                res "shape.rectangle" {} 1)
          bad (service/call film-fixture fx/entries
                            (service/resolve-transport film-fixture {} res (env-of {"FILMCRAFT_CONTROL_TOKEN" (str token "0")}))
                            res "shape.rectangle" {} 1)
          open (service/call film-fixture fx/entries
                             (service/resolve-transport film-fixture {:token-ref {:scheme :file :path open-file}} res fx/no-env)
                             res "shape.rectangle" {} 1)]
      (is (= :artcraft/transport-failed (get-in refused [:error :kind])))
      (is (= :artcraft/token-refused (get-in bad [:error :kind])))
      (is (= :artcraft/token-refused (get-in open [:error :kind])))
      (is (not-any? leaks? (mapcat (juxt identity render) [refused bad open]))))))

(deftest doctor-says-when-filmcraft-has-no-token
  (let [res {:port 9876 :source :default}
        missing (:ok (service/doctor film-fixture fx/entries (service/resolve-transport film-fixture {} res fx/no-env) res))
        present (:ok (service/doctor film-fixture fx/entries (service/resolve-transport film-fixture {} res with-token) res))
        plain (:ok (service/doctor vector-style fx/entries (service/resolve-transport vector-style {} res with-token) res))]
    (is (= :artcraft/token-missing (get-in missing [:token :status])))
    (is (str/includes? (:hint missing) "FILMCRAFT_CONTROL_TOKEN is not set"))
    (is (str/includes? (get-in missing [:token :fix]) "export FILMCRAFT_CONTROL_TOKEN="))
    (is (= :configured (get-in present [:token :status])))
    (is (= "Transport ready; calls go to the running app." (:hint present)))
    (is (not (contains? plain :token)) "an app without a token-ref reports no token")
    (is (not-any? leaks? [missing present]))))

(deftest addon-threads-its-getenv-seam-to-filmcraft
  (let [instance (addon/addon-ctor {:getenv with-token})]
    (try
      (protocol/initialize! instance {})
      (let [{:keys [mounted order]} @(:state instance)
            r (addon/dispatch mounted order {"app" "filmcraft" "command" "doctor"})]
        (is (str/includes? (-> r :content first :text) ":status :configured"))
        (is (not (leaks? r))))
      (finally (protocol/shutdown! instance)))))
