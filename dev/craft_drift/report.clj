(ns craft-drift.report
  "Pure drift report: each Crafting App's pinned catalog against the catalog its extractor reads
   at storytold HEAD. No IO: craft-drift.boundary collects the facts, this namespace promotes them
   to per-app rows, folds the rows into a report and renders it as markdown.

   A row's :status is one of
     :current  the pin is HEAD
     :stable   HEAD moved but no catalog id was added or removed
     :drifted  ids were added or removed (information, never a failure)
     :failed   the extractor threw, a kind that had ids at the pin has none at HEAD (the silent
               break a moved upstream file causes), or HEAD could not be materialized"
  (:require [clojure.string :as str]
            [malli.core :as m]))

;; ---------------------------------------------------------------------------
;; Shapes

(def kinds [:engine :mcp :control])

(def kind-keys
  "Where each catalog shape keeps a kind. hive-photocraft's catalog names engine :commands and
   control :methods, and has no MCP tools."
  {:engine [:engine :commands] :mcp [:mcp] :control [:control :methods]})

(def KindDelta
  [:map [:pin nat-int?] [:head nat-int?]
   [:added [:vector string?]] [:removed [:vector string?]]])

(def Failure
  [:map [:stage keyword?] [:message string?] [:kinds {:optional true} [:vector keyword?]]])

(def Row
  [:map
   [:app string?]
   [:pin [:maybe string?]]
   [:head [:maybe string?]]
   [:behind [:maybe nat-int?]]
   [:status [:enum :current :stable :drifted :failed]]
   [:warnings [:vector string?]]
   [:kinds {:optional true} [:map-of keyword? KindDelta]]
   [:failure {:optional true} Failure]])

(def Report
  [:map
   [:generated-at string?]
   [:apps [:vector Row]]
   [:summary [:map
              [:apps nat-int?]
              [:by-status [:map-of keyword? [:vector string?]]]
              [:totals [:map-of keyword? [:map [:added nat-int?] [:removed nat-int?]]]]]]
   [:exit [:enum 0 1]]])

;; ---------------------------------------------------------------------------
;; Promote: catalog -> id sets

(defn entry-id
  "A catalog entry's id: engine entries carry :id, MCP tools :name, control methods are strings."
  [entry]
  (if (map? entry) (or (:id entry) (:name entry)) entry))

(defn kind-ids
  "Sorted id set of `kind` in `catalog`, or nil when the catalog shape has no such kind."
  [catalog kind]
  (when-let [k (some #(when (contains? catalog %) %) (kind-keys kind))]
    (into (sorted-set) (comp (map entry-id) (filter string?)) (get catalog k))))

(defn catalog-ids [catalog]
  (into {} (map (juxt identity #(kind-ids catalog %))) kinds))

;; ---------------------------------------------------------------------------
;; Pipeline: ids -> deltas -> row

(defn kind-delta [pin head]
  {:pin (count pin) :head (count head)
   :added (vec (remove (set pin) head)) :removed (vec (remove (set head) pin))})

(defn emptied-kinds
  "Kinds that had ids at the pin and have none at HEAD."
  [pin-ids head-ids]
  (filterv #(and (seq (pin-ids %)) (empty? (head-ids %))) kinds))

(defn same-revision?
  "Pins may be short shas (hive-vectorcraft writes 7 chars); HEAD is a full sha."
  [pin head]
  (boolean (and (seq pin) (seq head)
                (or (str/starts-with? head pin) (str/starts-with? pin head)))))

(defn moved? [deltas]
  (boolean (some (fn [[_ {:keys [added removed]}]] (or (seq added) (seq removed))) deltas)))

(defn kind-deltas [pin-ids head-ids]
  (into (sorted-map)
        (for [k kinds :when (or (pin-ids k) (head-ids k))]
          [k (kind-delta (pin-ids k) (head-ids k))])))

(defn app-row
  "Promote one app's collected facts
     {:app :pin-catalog :head-sha :behind :head-catalog :warnings}
   (with :error {:stage :message} in place of :head-catalog when HEAD could not be extracted)
   to its drift row."
  [{:keys [app pin-catalog head-sha behind head-catalog error warnings]}]
  (let [pin (:reference-revision pin-catalog)
        base {:app app :pin pin :head head-sha :behind behind :warnings (vec warnings)}]
    (if error
      (assoc base :status :failed :failure (select-keys error [:stage :message]))
      (let [pin-ids (catalog-ids pin-catalog)
            head-ids (catalog-ids head-catalog)
            deltas (kind-deltas pin-ids head-ids)
            emptied (emptied-kinds pin-ids head-ids)]
        (cond-> (assoc base :kinds deltas
                       :status (cond (seq emptied) :failed
                                     (moved? deltas) :drifted
                                     (same-revision? pin head-sha) :current
                                     :else :stable))
          (seq emptied)
          (assoc :failure {:stage :extract :kinds emptied
                           :message (str "no " (str/join ", " (map name emptied))
                                         " ids at HEAD where the pin had them (upstream layout moved?)")}))))))

(defn exit-code
  "1 when any app failed to extract, else 0. Drift alone never fails the run."
  [rows]
  (if (some #(= :failed (:status %)) rows) 1 0))

(defn summary [rows]
  {:apps (count rows)
   :by-status (into (sorted-map) (update-vals (group-by :status rows) #(mapv :app %)))
   :totals (into (sorted-map)
                 (for [k kinds]
                   [k {:added (reduce + (map #(count (get-in % [:kinds k :added])) rows))
                       :removed (reduce + (map #(count (get-in % [:kinds k :removed])) rows))}]))})

(defn report
  "The drift report of every app's collected facts."
  [generated-at facts]
  (let [rows (mapv app-row facts)]
    {:generated-at generated-at :apps rows :summary (summary rows) :exit (exit-code rows)}))

(defn valid? [r] (m/validate Report r))

;; ---------------------------------------------------------------------------
;; Render: report -> markdown

(def max-listed 40)

(defn short-sha [sha] (when sha (subs sha 0 (min 8 (count sha)))))

(defn delta-cell [{:keys [pin head added removed]}]
  (if (and (empty? added) (empty? removed))
    (str head)
    (str pin " → " head " (+" (count added) "/−" (count removed) ")")))

(defn id-list [label ids]
  (when (seq ids)
    (let [shown (take max-listed ids) more (- (count ids) (count shown))]
      (str "- " label ": " (str/join ", " (map #(str "`" % "`") shown))
           (when (pos? more) (str " … and " more " more")) "\n"))))

(defn row-line [{:keys [app pin head behind status] deltas :kinds}]
  (str "| " app " | " (or (short-sha pin) "unrecorded") " | " (or (short-sha head) "?")
       " | " (if behind behind "?") " | "
       (str/join " | " (for [k kinds] (if-let [d (get deltas k)] (delta-cell d) "–")))
       " | " (name status) " |"))

(defn status-line [{:keys [apps summary exit]}]
  (str "Exit " exit ". " (count apps) " apps: "
       (str/join ", " (for [[s as] (:by-status summary)] (str (count as) " " (name s))))
       ". Ids added/removed across apps: "
       (str/join ", " (for [[k {:keys [added removed]}] (:totals summary)]
                        (str (name k) " +" added "/−" removed)))
       "."))

(defn markdown [{:keys [generated-at apps] :as r}]
  (str "# Craft catalog drift, " generated-at "\n\n"
       (status-line r) "\n\n"
       "Cells read `pin → HEAD (+added/−removed)`; a lone number means the id set is unchanged.\n\n"
       "| app | pin | HEAD | behind | engine | MCP | control | status |\n"
       "|---|---|---|---|---|---|---|---|\n"
       (str/join "\n" (map row-line apps)) "\n"
       (when-let [failed (seq (filter :failure apps))]
         (str "\n## Failures\n\n"
              (apply str (for [{:keys [app failure]} failed]
                           (str "- **" app "** (" (name (:stage failure)) "): " (:message failure) "\n")))))
       (when-let [warned (seq (filter #(seq (:warnings %)) apps))]
         (str "\n## Warnings\n\n"
              (apply str (for [{:keys [app warnings]} warned, w warnings]
                           (str "- **" app "**: " w "\n")))))
       (when-let [drifted (seq (filter #(= :drifted (:status %)) apps))]
         (str "\n## Drift\n"
              (apply str (for [{:keys [app] deltas :kinds} drifted]
                           (str "\n### " app "\n\n"
                                (apply str (for [[k {:keys [added removed]}] deltas]
                                             (str (id-list (str (name k) " added") added)
                                                  (id-list (str (name k) " removed") removed)))))))))))
