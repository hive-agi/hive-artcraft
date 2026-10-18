(ns hive-artcraft.core
  "Portable, app-parameterised control vocabulary for :port-shaped Crafting Apps:
   request values, JSON-lines frames and reply checks. No I/O, no host conditionals."
  (:require [clojure.string :as str]))

(defn refusal
  "A portable error value with an actionable next step."
  [kind hint]
  {:error {:kind kind :hint hint}})

(defn- request-id?
  [x]
  (or (string? x) (and (integer? x) (<= 0 x))))

(defn lookup
  "Find an engine command of `app` by exact id in the injected catalog, or refuse."
  [catalog app id]
  (if-let [entry (and (string? id) (first (filter #(= id (:id %)) (:engine catalog))))]
    {:ok entry}
    (refusal :artcraft/unknown-command
             (str "Unknown " app " engine command " (pr-str id)
                  "; run artcraft app=" app " command=catalog query=engine to list valid ids."))))

(defn request
  "Validate an engine command and build the unencoded engine.execute request."
  [catalog app id params request-id]
  (let [found (lookup catalog app id)]
    (cond
      (:error found) found
      (not (map? params)) (refusal :artcraft/invalid-params "Pass params as an object/map (use {} for no params).")
      (not (request-id? request-id)) (refusal :artcraft/invalid-id "Pass a nonnegative integer or string request id.")
      :else {:ok {:id request-id :method "engine.execute" :params {:command id :params params}}})))

(defn control-request
  "Build a request for a catalogued control method of `app` (not an engine command)."
  [catalog app method params request-id]
  (cond
    (not (and (string? method) (some #(= method %) (:control catalog))))
    (refusal :artcraft/unknown-method
             (str "Unknown " app " control method " (pr-str method)
                  "; run artcraft app=" app " command=catalog query=control."))
    (not (map? params)) (refusal :artcraft/invalid-params "Pass params as an object/map.")
    (not (request-id? request-id)) (refusal :artcraft/invalid-id "Pass a nonnegative integer or string request id.")
    :else {:ok {:id request-id :method method :params params}}))

(defn frame
  "JSON-lines framing: accept exactly one single-line JSON object; the caller supplies the encoder."
  [json-text]
  (if (and (string? json-text) (str/starts-with? json-text "{") (str/ends-with? json-text "}")
           (not (str/includes? json-text "\n")) (not (str/includes? json-text "\r")))
    {:ok (str json-text "\n")}
    (refusal :artcraft/invalid-frame "Encode one JSON object as a single line; remove line breaks.")))

(defn response-line
  "Check a decoded reply {ok, result|error} from `app`; preserves false and null results."
  [app response]
  (let [status (cond (not (map? response)) ::absent
                     (contains? response :ok) (:ok response)
                     (contains? response "ok") (get response "ok")
                     :else ::absent)]
    (cond
      (not (map? response)) (refusal :artcraft/invalid-response "Decode one JSON response object.")
      (= ::absent status) (refusal :artcraft/invalid-response "Response must contain boolean ok.")
      (not (boolean? status)) (refusal :artcraft/invalid-response "Response ok must be boolean.")
      status {:ok (if (contains? response :result) (:result response) (get response "result"))}
      :else (refusal :artcraft/upstream-error
                     (str app " rejected the request: "
                          (or (:error response) (get response "error") "unknown error"))))))
