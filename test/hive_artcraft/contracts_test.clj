(ns hive-artcraft.contracts-test
  "Every public source function carries a Malli contract that is actually registered."
  (:require [clojure.test :refer [deftest is]]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.test.check.generators :as gen]
            [hive-test.trifecta :refer [deftrifecta]]
            [malli.core :as m]
            [hive-artcraft.contracts]
            [hive-artcraft.registry]
            [hive-artcraft.service]
            [hive-artcraft.mount]
            [hive-artcraft.addon]
            [hive-artcraft.schema]
            [hive-artcraft.transport.socket :as socket]))

(defn source-forms [file]
  (with-open [r (java.io.PushbackReader. (io/reader file))]
    (loop [forms []]
      (let [form (read {:eof ::eof :read-cond :allow :features #{:clj}} r)]
        (if (= ::eof form) forms (recur (conj forms form)))))))

(defn public-defs [file]
  (let [forms (source-forms file)
        ns-name (second (first forms))]
    (set (for [form forms :when (and (seq? form) (= 'defn (first form)))]
           (symbol (str ns-name) (str (second form)))))))

(defn declared-contracts [file]
  (set (for [form (source-forms file) :when (and (seq? form) (= 'm/=> (first form)))]
         (second form))))

(defn source-files []
  (->> (file-seq (io/file "src"))
       (filter #(and (.isFile %) (or (.endsWith (.getName %) ".clj") (.endsWith (.getName %) ".cljc"))))))

(deftest all-public-source-functions-have-contracts
  (let [files (source-files)
        public (set (mapcat public-defs files))
        actual (set (for [file files
                          :let [ns-name (second (first (source-forms file)))]
                          sym (declared-contracts file)]
                      (if (namespace sym) sym (symbol (str ns-name) (name sym)))))]
    (is (>= (count public) 30) "A source-tree census must not pass vacuously")
    (is (= public actual) (str "missing " (set/difference public actual) "; stale " (set/difference actual public)))
    (is (every? #(get-in (m/function-schemas) [(symbol (namespace %)) (symbol (name %))]) actual))))

(deftrifecta socket-transport-contract socket/socket-transport
  {:golden-path "test/golden/socket-transport.edn"
   :cases {:port {:port 9001} :timeout {:port 7980 :timeout-ms 500}}
   :xf :config
   :gen (gen/fmap #(hash-map :port %) (gen/choose 1 65535))
   :pred #(and (= "127.0.0.1" (:host (:config %))) (pos-int? (:connect-ms (:config %)))) :num-tests 30
   :mutations [["drop-overrides" (fn [_] (socket/->SocketTransport (assoc socket/default-config :port 1)))]]})
