;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.data.workspace.hpd
  (:require
   ["@penpot/hpd-client-tools" :refer [createPenpotClientToolsProvider]]
   ["json5" :as json5]
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.changes-builder :as pcb]
   [app.common.files.helpers :as cfh]
   [app.common.geom.align :as gal]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes :as gsh]
   [app.common.logging :as log]
   [app.common.types.color :as color]
   [app.common.types.fills :as fills]
   [app.common.types.path :as path]
   [app.common.types.shape :as cts]
   [app.common.types.shape-tree :as shape-tree]
   [app.common.types.shape.interactions :as interactions]
   [app.common.types.text :as txt]
   [app.common.types.tokens-lib :as tokens]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.main.data.changes :as dch]
   [app.main.data.comments :as comments]
   [app.main.data.common :as dcm]
   [app.main.data.exports.assets :as exports]
   [app.main.data.helpers :as dsh]
   [app.main.data.workspace.bool :as dwb]
   [app.main.data.workspace.colors :as dwc]
   [app.main.data.workspace.groups :as dwg]
   [app.main.data.workspace.interactions :as dwi]
   [app.main.data.workspace.libraries :as dwl]
   [app.main.data.workspace.media :as dwm]
   [app.main.data.workspace.pages :as dwpg]
   [app.main.data.workspace.path.shapes-to-path :as dwp]
   [app.main.data.workspace.selection :as dws]
   [app.main.data.workspace.shape-layout :as dwsl]
   [app.main.data.workspace.shapes :as dwsh]
   [app.main.data.workspace.texts :as dwtxt]
   [app.main.data.workspace.tokens.application :as dwta]
   [app.main.data.workspace.tokens.library-edit :as dwtl]
   [app.main.data.workspace.tokens.propagation :as dwtp]
   [app.main.data.workspace.tokens.remapping :as dwtr]
   [app.main.data.workspace.transforms :as dwt]
   [app.main.data.workspace.undo :as dwu]
   [app.main.data.workspace.variants :as dwv]
   [app.main.features :as features]
   [app.main.repo :as rp]
   [app.main.store :as st]
   [app.util.object :as obj]
   [app.util.websocket :as ws]
   [beicon.v2.core :as rx]
   [clojure.set :as set]
   [clojure.string :as str]
   [potok.v2.core :as ptk]))

(def ^:private max-depth 8)
(def ^:private operation-timeout-ms 3000)

(defonce ^:private provider
  (atom nil))

(defn- shape-summary
  [objects shape depth]
  (let [child-ids (:shapes shape)
        result    {:id       (dm/str (:id shape))
                   :name     (:name shape)
                   :type     (some-> (:type shape) name)
                   :parentId (some-> (:parent-id shape) dm/str)
                   :x        (:x shape)
                   :y        (:y shape)
                   :width    (:width shape)
                   :height   (:height shape)}]
    (cond
      (empty? child-ids)
      result

      (pos? depth)
      (assoc result
             :children
             (into []
                   (keep (fn [id]
                           (when-let [child (get objects id)]
                             (shape-summary objects child (dec depth)))))
                   child-ids))

      :else
      (assoc result :childrenTruncated true))))

(defn- context-data
  ([]
   (context-data @st/state))
  ([state]
   (let [file-id      (:current-file-id state)
         page-id      (:current-page-id state)
         file         (dsh/lookup-file state file-id)
         selected     (dsh/get-selected-ids state)
         undo-index   (dm/get-in state [:workspace-undo :index] -1)
         undo-items   (dm/get-in state [:workspace-undo :items])
         undo-count   (count undo-items)
         undo-version (some-> undo-items last :undo-group dm/str)
         read-only?   (true? (dm/get-in state [:workspace-global :read-only?]))
         can-edit?    (true? (dm/get-in state [:permissions :can-edit]))]
     (cond-> {:metadata {:selectionIds  (mapv #(dm/str %) selected)
                         :canEdit       (and can-edit? (not read-only?))
                         :readOnly      read-only?
                         :penpotVersion (dm/str cf/version)}}
       (some? file-id)
       (assoc :documentId (dm/str file-id)
              :fileId (dm/str file-id))

       (some? page-id)
       (assoc :pageId (dm/str page-id))

       (some? (:revn file))
       (assoc :appStateVersion
              (str (:revn file) ":" undo-index ":" undo-count ":"
                   (or undo-version "none")))))))

(defn- context-snapshot
  []
  (clj->js (context-data)))

(defn- require-file
  [state]
  (or (dsh/lookup-file state)
      (throw (js/Error. "No Penpot file is open."))))

(defn- find-page
  [state page-id]
  (let [file  (require-file state)
        pages (dm/get-in file [:data :pages-index])
        id    (if (some? page-id)
                (uuid/coerce page-id)
                (:current-page-id state))]
    (or (get pages id)
        (throw (js/Error. (str "Page '" page-id "' was not found."))))))

(defn- find-shape
  [state shape-id]
  (let [id    (uuid/coerce shape-id)
        pages (-> (require-file state) :data :pages-index vals)]
    (or (some (fn [page]
                (when-let [shape (get (:objects page) id)]
                  [page shape]))
              pages)
        (throw (js/Error. (str "Shape '" shape-id "' was not found."))))))

(defn- request-depth
  [request fallback]
  (let [depth (obj/get request "depth" fallback)]
    (-> depth (max 0) (min max-depth))))

(defn- request-id
  [request field]
  (uuid/coerce (obj/get request field)))

(defn- request-ids
  [request field]
  (->> (obj/get request field)
       (map uuid/coerce)
       (into #{})))

(defn- request-ordered-ids
  [request field]
  (->> (obj/get request field)
       (map uuid/coerce)
       (into (d/ordered-set))))

(defn- wait-until
  [predicate message]
  (js/Promise.
   (fn [resolve reject]
     (let [deadline (+ (js/Date.now) operation-timeout-ms)]
       (letfn [(check []
                 (try
                   (if-let [value (predicate)]
                     (resolve value)
                     (if (< (js/Date.now) deadline)
                       (js/setTimeout check 20)
                       (reject (js/Error. message))))
                   (catch :default error
                     (reject error))))]
         (check))))))

(defn- observable->promise
  [observable]
  (js/Promise.
   (fn [resolve reject]
     (let [last-value (atom nil)
           emitted?   (atom false)]
       (rx/sub! observable
                (fn [value]
                  (reset! emitted? true)
                  (reset! last-value value))
                reject
                (fn []
                  (resolve (when @emitted? @last-value))))))))

(defn- operation-result
  [data]
  (clj->js {:data data
            :context (context-data)}))

(defn- emit-in-undo-transaction!
  [emit-events!]
  (let [undo-id (js/Symbol)]
    (st/emit! (dwu/start-undo-transaction undo-id))
    (try
      (emit-events!)
      (finally
        (st/emit! (dwu/commit-undo-transaction undo-id))))))

(defn- run-in-undo-transaction
  [operation]
  (let [undo-id (js/Symbol)
        commit! #(st/emit! (dwu/commit-undo-transaction undo-id))]
    (st/emit! (dwu/start-undo-transaction undo-id))
    (try
      (.finally (js/Promise.resolve (operation)) commit!)
      (catch :default error
        (commit!)
        (throw error)))))

(defn- require-editable!
  []
  (let [state      @st/state
        can-edit?  (true? (dm/get-in state [:permissions :can-edit]))
        read-only? (true? (dm/get-in state [:workspace-global :read-only?]))]
    (when-not (and can-edit? (not read-only?))
      (throw (js/Error. "The active Penpot file is read-only.")))))

(defn- page-exists?
  [page-id]
  (some? (dsh/lookup-page @st/state page-id)))

(defn- current-shape
  [shape-id]
  (dsh/lookup-shape @st/state shape-id))

(defn- shape-exists?
  [shape-id]
  (some? (current-shape shape-id)))

(defn- document-operation
  [request]
  (let [action (obj/get request "action")]
    (case action
      "createPage"
      (let [state   @st/state
            file-id (:current-file-id state)
            page-id (uuid/next)
            name    (obj/get request "name")]
        (require-editable!)
        (run-in-undo-transaction
         (fn []
           (st/emit! (dwpg/create-page {:file-id file-id :page-id page-id}))
           (-> (wait-until #(page-exists? page-id)
                           "Created page was not observed.")
               (.then
                (fn [_]
                  (if (and (string? name) (not-empty name))
                    (do
                      (st/emit! (dwpg/rename-page page-id name))
                      (wait-until
                       #(= name (:name (dsh/lookup-page @st/state page-id)))
                       "Created page name was not observed."))
                    (js/Promise.resolve true))))
               (.then (fn [_]
                        (operation-result
                         {:action action
                          :affectedIds [(dm/str page-id)]})))))))

      "openPage"
      (let [page-id (request-id request "pageId")]
        (when-not (page-exists? page-id)
          (throw (js/Error. "The requested page was not found.")))
        (st/emit! (dcm/go-to-workspace :page-id page-id))
        (-> (wait-until #(= page-id (:current-page-id @st/state))
                        "Active page change was not observed.")
            (.then (fn [_]
                     (operation-result
                      {:action action
                       :affectedIds [(dm/str page-id)]})))))

      "duplicatePage"
      (let [page-id   (request-id request "pageId")
            before    (set (-> @st/state dsh/lookup-file-data :pages))]
        (require-editable!)
        (when-not (page-exists? page-id)
          (throw (js/Error. "The requested page was not found.")))
        (st/emit! (dwpg/duplicate-page page-id))
        (-> (wait-until
             (fn []
               (let [after (set (-> @st/state dsh/lookup-file-data :pages))]
                 (first (set/difference after before))))
             "Duplicated page was not observed.")
            (.then (fn [new-page-id]
                     (operation-result
                      {:action action
                       :affectedIds [(dm/str new-page-id)]})))))

      "renamePage"
      (let [page-id (request-id request "pageId")
            name    (obj/get request "name")]
        (require-editable!)
        (when-not (page-exists? page-id)
          (throw (js/Error. "The requested page was not found.")))
        (st/emit! (dwpg/rename-page page-id name))
        (-> (wait-until #(= name (:name (dsh/lookup-page @st/state page-id)))
                        "Renamed page was not observed.")
            (.then (fn [_]
                     (operation-result
                      {:action action
                       :affectedIds [(dm/str page-id)]})))))

      "reorderPage"
      (let [page-id    (request-id request "pageId")
            new-index  (obj/get request "index")
            pages      (-> @st/state dsh/lookup-file-data :pages)
            prev-index (d/index-of pages page-id)
            changes    (-> (pcb/empty-changes nil)
                           (pcb/move-page page-id new-index prev-index))]
        (require-editable!)
        (when (nil? prev-index)
          (throw (js/Error. "The requested page was not found.")))
        (st/emit! (dch/commit-changes changes))
        (-> (wait-until #(= new-index
                            (d/index-of (-> @st/state dsh/lookup-file-data :pages)
                                        page-id))
                        "Reordered page was not observed.")
            (.then (fn [_]
                     (operation-result
                      {:action action
                       :affectedIds [(dm/str page-id)]})))))

      "deletePage"
      (let [page-id (request-id request "pageId")]
        (require-editable!)
        (when-not (page-exists? page-id)
          (throw (js/Error. "The requested page was not found.")))
        (st/emit! (dwpg/delete-page page-id))
        (-> (wait-until #(not (page-exists? page-id))
                        "Deleted page was still present.")
            (.then (fn [_]
                     (operation-result
                      {:action action
                       :affectedIds [(dm/str page-id)]})))))

      (throw (js/Error. (str "Unsupported document action '" action "'."))))))

(defn- shape-frame-id
  [objects parent-id]
  (let [parent (get objects parent-id)]
    (cond
      (= parent-id uuid/zero) uuid/zero
      (= :frame (:type parent)) parent-id
      :else (:frame-id parent))))

(declare require-shapes! text-attributes)

(defn- create-shape
  [request type]
  (require-editable!)
  (let [state     @st/state
        page-id   (:current-page-id state)
        objects   (dsh/lookup-page-objects state page-id)
        draft     (obj/get request "shape")
        parent-id (if (obj/contains? draft "parentId")
                    (request-id draft "parentId")
                    uuid/zero)
        shape-id  (uuid/next)
        content   (when (= type :text)
                    (let [content (dwtxt/create-root-from-string
                                   (obj/get request "text" ""))
                          attrs   (when (obj/contains? request "attributes")
                                    (text-attributes
                                     (obj/get request "attributes")))]
                      (if (seq attrs)
                        (:content
                         (txt/update-text-content
                          {:content content}
                          (some-fn txt/is-text-node?
                                   txt/is-paragraph-node?)
                          merge
                          attrs))
                        content)))
        shape     (cts/setup-shape
                   (cond-> {:id        shape-id
                            :type      type
                            :name      (obj/get draft "name")
                            :x         (obj/get draft "x")
                            :y         (obj/get draft "y")
                            :width     (obj/get draft "width")
                            :height    (obj/get draft "height")
                            :parent-id parent-id
                            :frame-id  (shape-frame-id objects parent-id)}
                     (= type :text)
                     (assoc :content content)))]
    (when-not (contains? objects parent-id)
      (throw (js/Error. "The requested parent shape was not found.")))
    (st/emit! (dwsh/add-shape shape {:skip-edition? true}))
    (-> (wait-until
         #(let [created (current-shape shape-id)]
            (and created
                 (= (select-keys
                     shape
                     [:type :name :x :y :width :height :parent-id :content])
                    (select-keys
                     created
                     [:type :name :x :y :width :height :parent-id :content]))))
         "Created shape properties were not observed.")
        (.then (fn [_]
                 (operation-result
                  {:action (obj/get request "action")
                   :affectedIds [(dm/str shape-id)]
                   :shapes [(shape-summary
                             (dsh/lookup-page-objects @st/state)
                             (current-shape shape-id)
                             1)]}))))))

(defn- patch-shape
  [shape patch]
  (cond-> shape
    (obj/contains? patch "name")
    (assoc :name (obj/get patch "name"))

    (obj/contains? patch "hidden")
    (assoc :hidden (obj/get patch "hidden"))

    (obj/contains? patch "blocked")
    (assoc :blocked (obj/get patch "blocked"))

    (obj/contains? patch "opacity")
    (assoc :opacity (obj/get patch "opacity"))

    (obj/contains? patch "rotation")
    (assoc :rotation (obj/get patch "rotation"))

    (obj/contains? patch "blendMode")
    (assoc :blend-mode (keyword (obj/get patch "blendMode")))

    (obj/contains? patch "showContent")
    (assoc :show-content (obj/get patch "showContent"))

    (obj/contains? patch "constraintsH")
    (assoc :constraints-h (keyword (obj/get patch "constraintsH")))

    (obj/contains? patch "constraintsV")
    (assoc :constraints-v (keyword (obj/get patch "constraintsV")))

    (obj/contains? patch "fixedScroll")
    (assoc :fixed-scroll (obj/get patch "fixedScroll"))

    (obj/contains? patch "radii")
    (assoc :r1 (obj/get (obj/get patch "radii") "topLeft")
           :r2 (obj/get (obj/get patch "radii") "topRight")
           :r3 (obj/get (obj/get patch "radii") "bottomRight")
           :r4 (obj/get (obj/get patch "radii") "bottomLeft"))))

(defn- shape-patch-keys
  [patch]
  (cond-> []
    (obj/contains? patch "name") (conj :name)
    (obj/contains? patch "hidden") (conj :hidden)
    (obj/contains? patch "blocked") (conj :blocked)
    (obj/contains? patch "opacity") (conj :opacity)
    (obj/contains? patch "rotation") (conj :rotation)
    (obj/contains? patch "blendMode") (conj :blend-mode)
    (obj/contains? patch "showContent") (conj :show-content)
    (obj/contains? patch "constraintsH") (conj :constraints-h)
    (obj/contains? patch "constraintsV") (conj :constraints-v)
    (obj/contains? patch "fixedScroll") (conj :fixed-scroll)
    (obj/contains? patch "radii") (into [:r1 :r2 :r3 :r4])))

(defn- shapes-operation
  [request]
  (let [action (obj/get request "action")]
    (case action
      "createRectangle" (create-shape request :rect)
      "createBoard" (create-shape request :frame)
      "createEllipse" (create-shape request :circle)
      "createText" (create-shape request :text)

      "patch"
      (let [ids      (request-ids request "shapeIds")
            patch    (obj/get request "patch")
            keys     (shape-patch-keys patch)]
        (require-editable!)
        (require-shapes! ids)
        (let [expected (into {}
                             (map (fn [id]
                                    [id (select-keys
                                         (patch-shape (current-shape id) patch)
                                         keys)]))
                             ids)]
          (st/emit! (dwsh/update-shapes ids #(patch-shape % patch)))
          (-> (wait-until
               #(every?
                 (fn [[id values]]
                   (= values (select-keys (current-shape id) keys)))
                 expected)
               "Patched shape properties were not observed.")
              (.then (fn [_]
                       (operation-result
                        {:action action
                         :affectedIds (mapv #(dm/str %) ids)}))))))

      "clone"
      (let [ids    (request-ids request "shapeIds")
            before (set (keys (dsh/lookup-page-objects @st/state)))
            delta  (obj/get request "delta")]
        (require-editable!)
        (run-in-undo-transaction
         (fn []
           (st/emit! (dws/duplicate-shapes ids :change-selection? true))
           (-> (wait-until
                (fn []
                  (let [selected (dsh/get-selected-ids @st/state)]
                    (when (and (seq selected)
                               (every? #(not (contains? before %)) selected))
                      selected)))
                "Cloned shapes were not observed.")
               (.then
                (fn [new-ids]
                  (if (some? delta)
                    (let [positions
                          (into {}
                                (map
                                 (fn [id]
                                   (let [shape (current-shape id)]
                                     [id {:x (+ (:x shape) (obj/get delta "x"))
                                          :y (+ (:y shape) (obj/get delta "y"))}]))
                                 new-ids))]
                      (doseq [[id position] positions]
                        (st/emit! (dwt/update-position id position)))
                      (-> (wait-until
                           #(every?
                             (fn [[id position]]
                               (let [shape (current-shape id)]
                                 (and (= (:x position) (:x shape))
                                      (= (:y position) (:y shape)))))
                             positions)
                           "Cloned shape positions were not observed.")
                          (.then (fn [_] new-ids))))
                    (js/Promise.resolve new-ids))))
               (.then
                (fn [new-ids]
                  (operation-result
                   {:action action
                    :affectedIds (mapv #(dm/str %) new-ids)})))))))

      "delete"
      (let [ids (request-ids request "shapeIds")]
        (require-editable!)
        (st/emit! (dwsh/delete-shapes ids))
        (-> (wait-until #(not-any? shape-exists? ids)
                        "Deleted shapes were still present.")
            (.then (fn [_]
                     (operation-result
                      {:action action
                       :affectedIds (mapv #(dm/str %) ids)})))))

      "reparent"
      (let [ids       (request-ids request "shapeIds")
            parent-id (request-id request "parentId")
            index     (obj/get request "index" 0)]
        (require-editable!)
        (require-shapes! ids)
        (when-not (or (= parent-id uuid/zero)
                      (shape-exists? parent-id))
          (throw (js/Error. "The requested parent shape was not found.")))
        (st/emit! (dwsh/relocate-shapes ids parent-id index))
        (-> (wait-until #(every? (fn [id]
                                   (= parent-id (:parent-id (current-shape id))))
                                 ids)
                        "Reparented shapes were not observed.")
            (.then (fn [_]
                     (operation-result
                      {:action action
                       :affectedIds (mapv #(dm/str %) ids)})))))

      "setSelection"
      (let [ids (request-ordered-ids request "shapeIds")]
        (require-shapes! ids)
        (st/emit! (dws/select-shapes ids))
        (-> (wait-until #(= (set ids)
                            (set (dsh/get-selected-ids @st/state)))
                        "Selected shapes were not observed.")
            (.then
             (fn [_]
               (operation-result
                {:action action
                 :affectedIds (mapv #(dm/str %) ids)})))))

      "clearSelection"
      (do
        (st/emit! (dws/deselect-all))
        (-> (wait-until #(empty? (dsh/get-selected-ids @st/state))
                        "Cleared selection was not observed.")
            (.then
             (fn [_]
               (operation-result {:action action :affectedIds []})))))

      (throw (js/Error. (str "Unsupported shapes action '" action "'."))))))

(defn- require-shapes!
  [ids]
  (doseq [id ids]
    (when-not (shape-exists? id)
      (throw (js/Error. (str "Shape '" id "' was not found."))))))

(defn- current-shape-values
  [ids keys]
  (into {}
        (map (fn [id]
               [id (select-keys (current-shape id) keys)]))
        ids))

(defn- align-axis
  [alignment]
  (case alignment
    "left" :hleft
    "center" :hcenter
    "right" :hright
    "top" :vtop
    "middle" :vcenter
    "bottom" :vbottom
    (throw (js/Error. (str "Unsupported alignment '" alignment "'.")))))

(defn- aligned-shapes
  [objects ids axis]
  (let [shapes (map #(get objects %) ids)]
    (if (= 1 (count ids))
      (let [shape  (first shapes)
            parent (get objects (:parent-id shape))]
        (when (= uuid/zero (:parent-id shape))
          (throw (js/Error. "A root-level shape cannot be aligned by itself.")))
        [(gal/align-to-parent shape parent axis)])
      (let [rect (gsh/shapes->rect shapes)]
        (map #(gal/align-to-rect % rect axis) shapes)))))

(defn- transform-operation
  [request]
  (require-editable!)
  (let [action (obj/get request "action")]
    (case action
      "move"
      (let [ids    (request-ids request "shapeIds")
            delta  (obj/get request "delta")
            before (current-shape-values ids [:x :y])]
        (require-shapes! ids)
        (emit-in-undo-transaction!
         (fn []
           (doseq [id ids]
             (let [shape (current-shape id)]
               (st/emit!
                (dwt/update-position
                 id
                 {:x (+ (:x shape) (obj/get delta "x"))
                  :y (+ (:y shape) (obj/get delta "y"))}))))))
        (-> (wait-until
             #(every?
               (fn [id]
                 (let [shape (current-shape id)
                       old   (get before id)]
                   (and (= (:x shape) (+ (:x old) (obj/get delta "x")))
                        (= (:y shape) (+ (:y old) (obj/get delta "y"))))))
               ids)
             "Moved shape positions were not observed.")
            (.then (fn [_]
                     (operation-result
                      {:action action
                       :affectedIds (mapv #(dm/str %) ids)})))))

      "setPosition"
      (let [id (request-id request "shapeId")
            x  (obj/get request "x")
            y  (obj/get request "y")]
        (require-shapes! [id])
        (st/emit! (dwt/update-position id {:x x :y y}))
        (-> (wait-until
             #(let [shape (current-shape id)]
                (and (= x (:x shape)) (= y (:y shape))))
             "Updated shape position was not observed.")
            (.then (fn [_]
                     (operation-result
                      {:action action :affectedIds [(dm/str id)]})))))

      "resize"
      (let [ids    (request-ids request "shapeIds")
            width  (obj/get request "width")
            height (obj/get request "height")]
        (require-shapes! ids)
        (emit-in-undo-transaction!
         (fn []
           (st/emit! (dwt/update-dimensions ids :width width))
           (st/emit! (dwt/update-dimensions ids :height height))))
        (-> (wait-until
             #(every? (fn [id]
                        (let [shape (current-shape id)]
                          (and (= width (:width shape))
                               (= height (:height shape)))))
                      ids)
             "Resized shape dimensions were not observed.")
            (.then (fn [_]
                     (operation-result
                      {:action action
                       :affectedIds (mapv #(dm/str %) ids)})))))

      "rotate"
      (let [ids     (request-ids request "shapeIds")
            degrees (obj/get request "degrees")
            before  (current-shape-values ids [:rotation])]
        (require-shapes! ids)
        (st/emit! (dwt/increase-rotation ids degrees {:delta? true}))
        (-> (if (zero? (mod degrees 360))
              (js/Promise.resolve true)
              (wait-until
               #(every?
                 (fn [id]
                   (not= (:rotation (get before id))
                         (:rotation (current-shape id))))
                 ids)
               "Rotated shapes were not observed."))
            (.then (fn [_]
                     (operation-result
                      {:action action
                       :affectedIds (mapv #(dm/str %) ids)})))))

      "flipHorizontal"
      (let [ids    (request-ids request "shapeIds")
            before (current-shape-values
                    ids [:points :transform :transform-inverse])]
        (require-shapes! ids)
        (st/emit! (dwt/flip-horizontal-selected ids))
        (-> (wait-until
             #(some (fn [id]
                      (not= (get before id)
                            (select-keys
                             (current-shape id)
                             [:points :transform :transform-inverse])))
                    ids)
             "Horizontally flipped shapes were not observed.")
            (.then (fn [_]
                     (operation-result
                      {:action action
                       :affectedIds (mapv #(dm/str %) ids)})))))

      "flipVertical"
      (let [ids    (request-ids request "shapeIds")
            before (current-shape-values
                    ids [:points :transform :transform-inverse])]
        (require-shapes! ids)
        (st/emit! (dwt/flip-vertical-selected ids))
        (-> (wait-until
             #(some (fn [id]
                      (not= (get before id)
                            (select-keys
                             (current-shape id)
                             [:points :transform :transform-inverse])))
                    ids)
             "Vertically flipped shapes were not observed.")
            (.then (fn [_]
                     (operation-result
                      {:action action
                       :affectedIds (mapv #(dm/str %) ids)})))))

      "align"
      (let [ids     (request-ids request "shapeIds")
            objects (dsh/lookup-page-objects @st/state)
            moved   (aligned-shapes objects ids
                                    (align-axis
                                     (obj/get request "alignment")))
            targets (into {} (map (juxt :id #(select-keys % [:x :y]))) moved)]
        (require-shapes! ids)
        (st/emit! (dwt/position-shapes moved))
        (-> (wait-until
             #(every? (fn [id]
                        (= (get targets id)
                           (select-keys (current-shape id) [:x :y])))
                      ids)
             "Aligned shape positions were not observed.")
            (.then (fn [_]
                     (operation-result
                      {:action action
                       :affectedIds (mapv #(dm/str %) ids)})))))

      "distribute"
      (let [ids     (request-ids request "shapeIds")
            axis    (keyword (obj/get request "axis"))
            objects (dsh/lookup-page-objects @st/state)
            moved   (gal/distribute-space (map #(get objects %) ids) axis)
            targets (into {} (map (juxt :id #(select-keys % [:x :y]))) moved)]
        (require-shapes! ids)
        (when (< (count ids) 3)
          (throw (js/Error. "At least three shapes are required for distribution.")))
        (st/emit! (dwt/position-shapes moved))
        (-> (wait-until
             #(every? (fn [id]
                        (= (get targets id)
                           (select-keys (current-shape id) [:x :y])))
                      ids)
             "Distributed shape positions were not observed.")
            (.then (fn [_]
                     (operation-result
                      {:action action
                       :affectedIds (mapv #(dm/str %) ids)})))))

      "fitContent"
      (let [ids       (request-ordered-ids request "shapeIds")
            selection (dsh/get-selected-ids @st/state)
            objects   (dsh/lookup-page-objects @st/state)
            before    (current-shape-values ids [:width :height])
            modifiers
            (reduce
             (fn [result id]
               (let [frame (get objects id)
                     value (if (some? (:layout frame))
                             (dwt/fit-layout-modifiers objects frame)
                             (when-let [modifier
                                        (gsh/fit-frame-modifiers objects frame)]
                               {id {:modifiers modifier}}))]
                 (merge result value)))
             {}
             ids)]
        (require-shapes! ids)
        (doseq [id ids]
          (when-not (= :frame (:type (current-shape id)))
            (throw (js/Error. "Only boards can be fitted to content."))))
        (st/emit! (dws/select-shapes ids))
        (st/emit! (dwt/selected-fit-content))
        (st/emit! (dws/select-shapes selection))
        (-> (if (empty? modifiers)
              (js/Promise.resolve true)
              (wait-until
               #(some (fn [id]
                        (not= (get before id)
                              (select-keys (current-shape id)
                                           [:width :height])))
                      ids)
               "Fit-to-content dimensions were not observed."))
            (.then (fn [_]
                     (operation-result
                      {:action action
                       :affectedIds (mapv #(dm/str %) ids)})))))

      (throw (js/Error. (str "Unsupported transform action '" action "'."))))))

(defn- parent-orders
  [ids]
  (let [objects (dsh/lookup-page-objects @st/state)]
    (into {}
          (comp
           (map #(get objects %))
           (keep :parent-id)
           distinct
           (map (fn [parent-id]
                  [parent-id (:shapes (get objects parent-id))])))
          ids)))

(defn- reorder-shapes
  [ids location]
  (let [page-id         (:current-page-id @st/state)
        objects         (dsh/lookup-page-objects @st/state page-id)
        selected-shapes (map #(get objects %) ids)
        move-shape
        (fn [changes shape]
          (let [parent        (get objects (:parent-id shape))
                sibling-ids   (:shapes parent)
                current-index (d/index-of sibling-ids (:id shape))
                selected-index (d/index-of ids (:id shape))
                new-index     (case location
                                :top (count sibling-ids)
                                :down (max 0 (dec current-index))
                                :up (min (count sibling-ids)
                                         (+ current-index 2))
                                :bottom selected-index)]
            (pcb/change-parent changes
                               (:id parent)
                               [shape]
                               new-index)))
        changes (reduce move-shape
                        (-> (pcb/empty-changes nil page-id)
                            (pcb/with-objects objects))
                        selected-shapes)]
    (st/emit! (dch/commit-changes changes))))

(defn- reorder-would-change?
  [orders ids location]
  (let [selected? (set ids)]
    (boolean
     (some
      (fn [[_ siblings]]
        (let [selected (filterv selected? siblings)
              count-selected (count selected)]
          (case location
            :top
            (not= selected
                  (subvec (vec siblings)
                          (- (count siblings) count-selected)))

            :bottom
            (not= selected
                  (subvec (vec siblings) 0 count-selected))

            :up
            (some (fn [id]
                    (let [index (d/index-of siblings id)]
                      (and (< index (dec (count siblings)))
                           (not (selected? (nth siblings (inc index)))))))
                  selected)

            :down
            (some (fn [id]
                    (let [index (d/index-of siblings id)]
                      (and (pos? index)
                           (not (selected? (nth siblings (dec index)))))))
                  selected))))
      orders))))

(defn- hierarchy-operation
  [request]
  (require-editable!)
  (let [action (obj/get request "action")]
    (case action
      "group"
      (let [ids      (request-ordered-ids request "shapeIds")
            group-id (uuid/next)
            name     (obj/get request "name")]
        (require-shapes! ids)
        (run-in-undo-transaction
         (fn []
           (st/emit! (dwg/group-shapes group-id ids :change-selection? true))
           (-> (wait-until #(shape-exists? group-id)
                           "Created group was not observed.")
               (.then
                (fn [_]
                  (when (and (string? name) (not-empty name))
                    (st/emit! (dwsh/update-shapes
                               [group-id]
                               #(assoc % :name name))))
                  (wait-until
                   #(let [group (current-shape group-id)]
                      (and group
                           (= (set ids) (set (:shapes group)))
                           (or (not (and (string? name) (not-empty name)))
                               (= name (:name group)))))
                   "Created group properties were not observed.")))
               (.then (fn [_]
                        (operation-result
                         {:action action
                          :affectedIds [(dm/str group-id)]})))))))

      "ungroup"
      (let [ids      (request-ordered-ids request "shapeIds")
            children (into []
                           (mapcat #(dm/get-in
                                     (dsh/lookup-page-objects @st/state)
                                     [% :shapes]))
                           ids)]
        (require-shapes! ids)
        (st/emit! (dwg/ungroup-shapes ids :change-selection? true))
        (-> (wait-until #(not-any? shape-exists? ids)
                        "Ungrouped containers were still present.")
            (.then (fn [_]
                     (operation-result
                      {:action action
                       :affectedIds (mapv #(dm/str %) children)})))))

      "mask"
      (let [ids    (request-ordered-ids request "shapeIds")
            before (set (keys (dsh/lookup-page-objects @st/state)))]
        (require-shapes! ids)
        (st/emit! (dwg/mask-group ids))
        (-> (wait-until
             (fn []
               (let [objects (dsh/lookup-page-objects @st/state)
                     added   (set/difference (set (keys objects)) before)
                     masked  (or (some #(when (:masked-group (get objects %)) %)
                                       added)
                                 (some #(when (:masked-group (get objects %)) %)
                                       ids))]
                 masked))
             "Created mask was not observed.")
            (.then (fn [mask-id]
                     (operation-result
                      {:action action
                       :affectedIds [(dm/str mask-id)]})))))

      "unmask"
      (let [ids (request-ordered-ids request "shapeIds")]
        (require-shapes! ids)
        (st/emit! (dwg/unmask-group ids))
        (-> (wait-until
             #(every? (fn [id]
                        (not (:masked-group (current-shape id))))
                      ids)
             "Unmasked groups were not observed.")
            (.then (fn [_]
                     (operation-result
                      {:action action
                       :affectedIds (mapv #(dm/str %) ids)})))))

      ("bringForward" "bringToFront" "sendBackward" "sendToBack")
      (let [ids      (request-ordered-ids request "shapeIds")
            before   (parent-orders ids)
            location (case action
                       "bringForward" :up
                       "bringToFront" :top
                       "sendBackward" :down
                       "sendToBack" :bottom)]
        (require-shapes! ids)
        (reorder-shapes ids location)
        (-> (if (reorder-would-change? before ids location)
              (wait-until #(not= before (parent-orders ids))
                          "Reordered shape stacking was not observed.")
              (js/Promise.resolve true))
            (.then (fn [_]
                     (operation-result
                      {:action action
                       :affectedIds (mapv #(dm/str %) ids)})))))

      "boardFromSelection"
      (let [ids       (request-ordered-ids request "shapeIds")
            objects   (dsh/lookup-page-objects @st/state)
            shapes    (mapv #(get objects %) ids)
            parents   (set (map :parent-id shapes))
            parent-id (first parents)
            parent    (get objects parent-id)
            index     (apply min (map #(d/index-of (:shapes parent) %) ids))
            board-id  (uuid/next)
            name      (obj/get request "name" "Board")]
        (require-shapes! ids)
        (when (not= 1 (count parents))
          (throw (js/Error.
                  "Shapes must share a parent to create a board.")))
        (st/emit! (dwsh/create-artboard-from-shapes
                   shapes board-id parent-id index name
                   (gpt/point 0 0)))
        (-> (wait-until
             #(let [board (current-shape board-id)]
                (and (= :frame (:type board))
                     (= name (:name board))
                     (= (set ids) (set (:shapes board)))))
             "Created board properties were not observed.")
            (.then (fn [_]
                     (operation-result
                      {:action action
                       :affectedIds [(dm/str board-id)]})))))

      (throw (js/Error. (str "Unsupported hierarchy action '" action "'."))))))

(defn- path-command
  [command]
  (let [name   (str/lower-case (obj/get command "command"))
        params (vec (obj/get command "params"))]
    (case name
      ("m" "move" "move-to")
      (do
        (when-not (= 2 (count params))
          (throw (js/Error. "A move command requires [x, y].")))
        {:command :move-to
         :params {:x (nth params 0) :y (nth params 1)}})

      ("l" "line" "line-to")
      (do
        (when-not (= 2 (count params))
          (throw (js/Error. "A line command requires [x, y].")))
        {:command :line-to
         :params {:x (nth params 0) :y (nth params 1)}})

      ("c" "curve" "curve-to")
      (do
        (when-not (= 6 (count params))
          (throw
           (js/Error.
            "A curve command requires [c1x, c1y, c2x, c2y, x, y].")))
        {:command :curve-to
         :params {:c1x (nth params 0)
                  :c1y (nth params 1)
                  :c2x (nth params 2)
                  :c2y (nth params 3)
                  :x   (nth params 4)
                  :y   (nth params 5)}})

      ("z" "close" "close-path")
      (do
        (when-not (empty? params)
          (throw (js/Error. "A close command does not accept parameters.")))
        {:command :close-path :params {}})

      (throw (js/Error. (str "Unsupported path command '" name "'."))))))

(defn- request-path-content
  [request]
  (->> (obj/get request "commands")
       (mapv path-command)
       path/content))

(defn- vector-operation
  [request]
  (require-editable!)
  (let [action (obj/get request "action")]
    (case action
      "createPath"
      (let [shape-id (uuid/next)
            content  (request-path-content request)
            shape    (cts/setup-shape
                      {:id        shape-id
                       :type      :path
                       :name      (obj/get request "name" "Path")
                       :parent-id uuid/zero
                       :frame-id  uuid/zero
                       :content   content})]
        (st/emit! (dwsh/add-shape shape {:skip-edition? true}))
        (-> (wait-until
             #(let [current (current-shape shape-id)]
                (and current
                     (= :path (:type current))
                     (= content (:content current))))
             "Created path was not observed.")
            (.then (fn [_]
                     (operation-result
                      {:action action
                       :affectedIds [(dm/str shape-id)]})))))

      "patchPath"
      (let [shape-id (request-id request "shapeId")
            content  (request-path-content request)]
        (require-shapes! [shape-id])
        (when-not (= :path (:type (current-shape shape-id)))
          (throw (js/Error. "The requested shape is not a path.")))
        (st/emit! (dwsh/update-shapes
                   [shape-id]
                   #(path/update-geometry % content)))
        (-> (wait-until
             #(= content (:content (current-shape shape-id)))
             "Updated path content was not observed.")
            (.then (fn [_]
                     (operation-result
                      {:action action
                       :affectedIds [(dm/str shape-id)]})))))

      "convertToPath"
      (let [ids (request-ids request "shapeIds")]
        (require-shapes! ids)
        (st/emit! (dwp/convert-selected-to-path ids))
        (-> (wait-until
             #(every? (fn [id] (= :path (:type (current-shape id)))) ids)
             "Converted paths were not observed.")
            (.then (fn [_]
                     (operation-result
                      {:action action
                       :affectedIds (mapv #(dm/str %) ids)})))))

      "strokesToPath"
      (let [ids    (request-ids request "shapeIds")
            objects (dsh/lookup-page-objects @st/state)
            before (set (keys objects))]
        (require-shapes! ids)
        (when-not (features/active-feature? @st/state "render-wasm/v1")
          (throw
           (js/Error.
            "Stroke-to-path conversion requires the render-wasm/v1 feature.")))
        (when-not
         (some (comp seq :strokes)
               (mapcat #(cfh/get-children-with-self objects %) ids))
          (throw
           (js/Error.
            "None of the requested shapes contain convertible strokes.")))
        (st/emit! (dwp/convert-selected-strokes-to-path ids))
        (-> (wait-until
             (fn []
               (let [objects (dsh/lookup-page-objects @st/state)
                     added   (set/difference (set (keys objects)) before)]
                 (when (seq added) added)))
             "Converted stroke paths were not observed.")
            (.then (fn [added]
                     (operation-result
                      {:action action
                       :affectedIds (mapv #(dm/str %) added)})))))

      "createBoolean"
      (let [ids      (request-ids request "shapeIds")
            shape-id (uuid/next)
            type     (keyword (obj/get request "booleanType"))]
        (require-shapes! ids)
        (st/emit! (dwb/create-bool type :ids ids :force-shape-id shape-id))
        (-> (wait-until
             #(let [shape (current-shape shape-id)]
                (and (= :bool (:type shape))
                     (= type (:bool-type shape))))
             "Created boolean shape was not observed.")
            (.then (fn [_]
                     (operation-result
                      {:action action
                       :affectedIds [(dm/str shape-id)]})))))

      "setBooleanType"
      (let [shape-id (request-id request "shapeId")
            type     (keyword (obj/get request "booleanType"))]
        (require-shapes! [shape-id])
        (when-not (= :bool (:type (current-shape shape-id)))
          (throw (js/Error. "The requested shape is not a boolean.")))
        (st/emit! (dwb/change-bool-type shape-id type))
        (-> (wait-until #(= type (:bool-type (current-shape shape-id)))
                        "Updated boolean type was not observed.")
            (.then (fn [_]
                     (operation-result
                      {:action action
                       :affectedIds [(dm/str shape-id)]})))))

      "booleanToGroup"
      (let [shape-id (request-id request "shapeId")]
        (require-shapes! [shape-id])
        (when-not (= :bool (:type (current-shape shape-id)))
          (throw (js/Error. "The requested shape is not a boolean.")))
        (st/emit! (dwb/bool-to-group shape-id))
        (-> (wait-until #(= :group (:type (current-shape shape-id)))
                        "Converted boolean group was not observed.")
            (.then (fn [_]
                     (operation-result
                      {:action action
                       :affectedIds [(dm/str shape-id)]})))))

      (throw (js/Error. (str "Unsupported vector action '" action "'."))))))

(def ^:private text-attribute-keys
  {"fontFamily"    :font-family
   "fontId"        :font-id
   "fontVariantId" :font-variant-id
   "fontSize"      :font-size
   "fontWeight"    :font-weight
   "fontStyle"     :font-style
   "lineHeight"    :line-height
   "letterSpacing" :letter-spacing
   "textAlign"     :text-align
   "textDirection" :text-direction
   "textTransform" :text-transform
   "textDecoration" :text-decoration
   "growType"      :grow-type})

(defn- text-attributes
  [value]
  (reduce-kv
   (fn [result external internal]
     (if (obj/contains? value external)
       (assoc result internal (obj/get value external))
       result))
   {}
   text-attribute-keys))

(defn- require-text-shapes!
  [ids]
  (require-shapes! ids)
  (doseq [id ids]
    (when-not (= :text (:type (current-shape id)))
      (throw (js/Error. (str "Shape '" id "' is not a text shape."))))))

(defn- text-operation
  [request]
  (let [action (obj/get request "action")]
    (case action
      "getContent"
      (let [shape-id (request-id request "shapeId")]
        (require-text-shapes! [shape-id])
        (let [shape (current-shape shape-id)]
          (clj->js
           {:data {:shapeId (dm/str shape-id)
                   :text (txt/content->text (:content shape))
                   :content (:content shape)}
            :context (context-data)})))

      "replaceContent"
      (let [shape-id (request-id request "shapeId")
            value    (obj/get request "text")
            content  (dwtxt/create-root-from-string value)]
        (require-editable!)
        (require-text-shapes! [shape-id])
        (st/emit! (dwsh/update-shapes
                   [shape-id]
                   #(assoc % :content content
                           :name (txt/generate-shape-name value))))
        (-> (wait-until
             #(= value (txt/content->text (:content (current-shape shape-id))))
             "Replaced text content was not observed.")
            (.then (fn [_]
                     (operation-result
                      {:action action
                       :affectedIds [(dm/str shape-id)]})))))

      "replaceText"
      (let [ids         (request-ids request "shapeIds")
            search      (obj/get request "search")
            replacement (obj/get request "replacement")
            before      (into {}
                              (map (fn [id]
                                     [id (:content (current-shape id))]))
                              ids)]
        (require-editable!)
        (require-text-shapes! ids)
        (st/emit! (dwtxt/replace-text-in-shapes ids search replacement))
        (-> (wait-until
             #(every?
               (fn [id]
                 (= (txt/replace-text-in-content
                     (get before id) search replacement)
                    (:content (current-shape id))))
               ids)
             "Replaced text values were not observed.")
            (.then (fn [_]
                     (operation-result
                      {:action action
                       :affectedIds (mapv #(dm/str %) ids)})))))

      "patchAttributes"
      (let [ids   (request-ids request "shapeIds")
            attrs (text-attributes (obj/get request "attributes"))]
        (require-editable!)
        (require-text-shapes! ids)
        (when (empty? attrs)
          (throw (js/Error. "At least one text attribute is required.")))
        (st/emit! (dwtxt/update-all-attrs ids attrs))
        (-> (wait-until
             #(every?
               (fn [id]
                 (let [nodes (txt/node-seq txt/is-text-node?
                                           (:content (current-shape id)))]
                   (every?
                    (fn [node]
                      (every? (fn [[key value]]
                                (= value (get node key)))
                              attrs))
                    nodes)))
               ids)
             "Updated text attributes were not observed.")
            (.then (fn [_]
                     (operation-result
                      {:action action
                       :affectedIds (mapv #(dm/str %) ids)})))))

      "patchRange"
      (let [shape-id (request-id request "shapeId")
            start    (obj/get request "start")
            end      (obj/get request "end")
            attrs    (text-attributes (obj/get request "attributes"))
            before   (:content (current-shape shape-id))]
        (require-editable!)
        (require-text-shapes! [shape-id])
        (when (> start end)
          (throw (js/Error. "Text range start must not exceed end.")))
        (when (= start end)
          (throw (js/Error. "Text range must not be empty.")))
        (when (empty? attrs)
          (throw (js/Error. "At least one text attribute is required.")))
        (st/emit! (dwtxt/update-text-range shape-id start end attrs))
        (-> (wait-until
             #(not= before (:content (current-shape shape-id)))
             "Updated text range was not observed.")
            (.then (fn [_]
                     (operation-result
                      {:action action
                       :affectedIds [(dm/str shape-id)]})))))

      "applyTypography"
      (let [ids           (request-ids request "shapeIds")
            typography-id (request-id request "typographyId")
            library-id    (if (obj/contains? request "libraryId")
                            (request-id request "libraryId")
                            (:current-file-id @st/state))
            library-data  (dsh/lookup-file-data @st/state library-id)
            typography    (dm/get-in library-data
                                     [:typographies typography-id])]
        (require-editable!)
        (require-text-shapes! ids)
        (when-not typography
          (throw (js/Error. "The requested typography was not found.")))
        (st/emit! (dwtxt/apply-typography ids typography library-id))
        (-> (wait-until
             #(every?
               (fn [id]
                 (some
                  (fn [node]
                    (= typography-id (:typography-ref-id node)))
                  (txt/node-seq txt/is-text-node?
                                (:content (current-shape id)))))
               ids)
             "Applied typography was not observed.")
            (.then (fn [_]
                     (operation-result
                      {:action action
                       :affectedIds (mapv #(dm/str %) ids)})))))

      (throw (js/Error. (str "Unsupported text action '" action "'."))))))

(defn- layout-container-patch
  [patch]
  (cond-> {}
    (obj/contains? patch "direction")
    (assoc :layout-flex-dir (keyword (obj/get patch "direction")))

    (obj/contains? patch "wrap")
    (assoc :layout-wrap-type (keyword (obj/get patch "wrap")))

    (obj/contains? patch "gap")
    (assoc :layout-gap
           {:row-gap (obj/get (obj/get patch "gap") "row")
            :column-gap (obj/get (obj/get patch "gap") "column")})

    (obj/contains? patch "padding")
    (assoc :layout-padding
           {:p1 (obj/get (obj/get patch "padding") "top")
            :p2 (obj/get (obj/get patch "padding") "right")
            :p3 (obj/get (obj/get patch "padding") "bottom")
            :p4 (obj/get (obj/get patch "padding") "left")})

    (obj/contains? patch "alignItems")
    (assoc :layout-align-items (keyword (obj/get patch "alignItems")))

    (obj/contains? patch "justifyContent")
    (assoc :layout-justify-content
           (keyword (obj/get patch "justifyContent")))

    (obj/contains? patch "alignContent")
    (assoc :layout-align-content (keyword (obj/get patch "alignContent")))

    (obj/contains? patch "justifyItems")
    (assoc :layout-justify-items (keyword (obj/get patch "justifyItems")))))

(defn- layout-child-patch
  [patch]
  (cond-> {}
    (obj/contains? patch "horizontalSizing")
    (assoc :layout-item-h-sizing
           (keyword (obj/get patch "horizontalSizing")))

    (obj/contains? patch "verticalSizing")
    (assoc :layout-item-v-sizing
           (keyword (obj/get patch "verticalSizing")))

    (obj/contains? patch "alignSelf")
    (assoc :layout-item-align-self (keyword (obj/get patch "alignSelf")))

    (obj/contains? patch "absolute")
    (assoc :layout-item-absolute (obj/get patch "absolute"))

    (obj/contains? patch "zIndex")
    (assoc :layout-item-z-index (obj/get patch "zIndex"))

    (obj/contains? patch "margin")
    (assoc :layout-item-margin
           {:m1 (obj/get (obj/get patch "margin") "top")
            :m2 (obj/get (obj/get patch "margin") "right")
            :m3 (obj/get (obj/get patch "margin") "bottom")
            :m4 (obj/get (obj/get patch "margin") "left")})))

(defn- layout-track-value
  [value]
  (cond-> {:type (keyword (obj/get value "type"))}
    (obj/contains? value "value")
    (assoc :value (obj/get value "value"))))

(defn- grid-track-key
  [type]
  (case type
    :row :layout-grid-rows
    :column :layout-grid-columns))

(defn- require-grid!
  [shape-id]
  (require-shapes! [shape-id])
  (when-not (= :grid (:layout (current-shape shape-id)))
    (throw (js/Error. "The requested shape is not a grid layout."))))

(defn- require-grid-track-index!
  [shape-id property index]
  (when-not (< index (count (get (current-shape shape-id) property)))
    (throw (js/Error. "The requested grid track was not found."))))

(defn- require-grid-cells!
  [shape-id cell-ids]
  (let [cells (:layout-grid-cells (current-shape shape-id))]
    (doseq [cell-id cell-ids]
      (when-not (contains? cells cell-id)
        (throw (js/Error. "The requested grid cell was not found."))))))

(defn- layout-operation
  [request]
  (require-editable!)
  (let [action (obj/get request "action")]
    (case action
      ("createFlex" "createGrid")
      (let [shape-id (request-id request "shapeId")
            type     (if (= action "createFlex") :flex :grid)]
        (require-shapes! [shape-id])
        (when-not (contains? (current-shape shape-id) :shapes)
          (throw (js/Error. "Only container shapes can receive a layout.")))
        (st/emit! (dwsl/create-layout-from-id shape-id type))
        (-> (wait-until #(= type (:layout (current-shape shape-id)))
                        "Created layout was not observed.")
            (.then (fn [_]
                     (operation-result
                      {:action action
                       :affectedIds [(dm/str shape-id)]})))))

      "remove"
      (let [ids (request-ids request "shapeIds")]
        (require-shapes! ids)
        (st/emit! (dwsl/remove-layout ids))
        (-> (wait-until #(every? (fn [id]
                                   (nil? (:layout (current-shape id))))
                                 ids)
                        "Removed layouts were still present.")
            (.then (fn [_]
                     (operation-result
                      {:action action
                       :affectedIds (mapv #(dm/str %) ids)})))))

      "patchContainer"
      (let [ids     (request-ids request "shapeIds")
            changes (layout-container-patch (obj/get request "patch"))]
        (require-shapes! ids)
        (doseq [id ids]
          (when-not (some? (:layout (current-shape id)))
            (throw (js/Error. "The requested shape is not a layout container."))))
        (when (empty? changes)
          (throw (js/Error. "At least one layout property is required.")))
        (st/emit! (dwsl/update-layout ids changes))
        (-> (wait-until
             #(every? (fn [id]
                        (= changes
                           (select-keys (current-shape id)
                                        (keys changes))))
                      ids)
             "Updated layout container properties were not observed.")
            (.then (fn [_]
                     (operation-result
                      {:action action
                       :affectedIds (mapv #(dm/str %) ids)})))))

      "patchChild"
      (let [ids     (request-ids request "shapeIds")
            changes (layout-child-patch (obj/get request "patch"))]
        (require-shapes! ids)
        (doseq [id ids]
          (let [parent (current-shape (:parent-id (current-shape id)))]
            (when-not (some? (:layout parent))
              (throw (js/Error. "The requested shape is not a layout child.")))))
        (when (empty? changes)
          (throw (js/Error. "At least one layout child property is required.")))
        (st/emit! (dwsl/update-layout-child ids changes))
        (-> (wait-until
             #(every? (fn [id]
                        (= changes
                           (select-keys (current-shape id)
                                        (keys changes))))
                      ids)
             "Updated layout child properties were not observed.")
            (.then (fn [_]
                     (operation-result
                      {:action action
                       :affectedIds (mapv #(dm/str %) ids)})))))

      "addTrack"
      (let [shape-id (request-id request "shapeId")
            type     (keyword (obj/get request "trackType"))
            property (grid-track-key type)
            index    (obj/get request "index")
            value    (layout-track-value (obj/get request "value"))
            before   (count (get (current-shape shape-id) property))]
        (require-grid! shape-id)
        (when (and (some? index)
                   (> index (count (get (current-shape shape-id) property))))
          (throw (js/Error. "The grid track insertion index is out of range.")))
        (st/emit! (dwsl/add-layout-track [shape-id] type value index))
        (-> (wait-until
             #(= (inc before) (count (get (current-shape shape-id) property)))
             "Added grid track was not observed.")
            (.then (fn [_]
                     (operation-result
                      {:action action
                       :affectedIds [(dm/str shape-id)]})))))

      "removeTrack"
      (let [shape-id (request-id request "shapeId")
            type     (keyword (obj/get request "trackType"))
            property (grid-track-key type)
            index    (obj/get request "index")
            before   (count (get (current-shape shape-id) property))]
        (require-grid! shape-id)
        (require-grid-track-index! shape-id property index)
        (st/emit! (dwsl/remove-layout-track
                   [shape-id] type index
                   :with-shapes? (obj/get request "deleteShapes" false)))
        (-> (wait-until
             #(= (dec before) (count (get (current-shape shape-id) property)))
             "Removed grid track was still present.")
            (.then (fn [_]
                     (operation-result
                      {:action action
                       :affectedIds [(dm/str shape-id)]})))))

      "duplicateTrack"
      (let [shape-id (request-id request "shapeId")
            type     (keyword (obj/get request "trackType"))
            property (grid-track-key type)
            index    (obj/get request "index")
            before   (count (get (current-shape shape-id) property))]
        (require-grid! shape-id)
        (require-grid-track-index! shape-id property index)
        (st/emit! (dwsl/duplicate-layout-track [shape-id] type index))
        (-> (wait-until
             #(= (inc before) (count (get (current-shape shape-id) property)))
             "Duplicated grid track was not observed.")
            (.then (fn [_]
                     (operation-result
                      {:action action
                       :affectedIds [(dm/str shape-id)]})))))

      "reorderTrack"
      (let [shape-id  (request-id request "shapeId")
            type      (keyword (obj/get request "trackType"))
            property  (grid-track-key type)
            from      (obj/get request "fromIndex")
            to        (obj/get request "toIndex")
            before    (get (current-shape shape-id) property)]
        (require-grid! shape-id)
        (require-grid-track-index! shape-id property from)
        (require-grid-track-index! shape-id property to)
        (st/emit! (dwsl/reorder-layout-track
                   [shape-id] type from to
                   (obj/get request "moveContent" false)))
        (-> (if (= from to)
              (js/Promise.resolve true)
              (wait-until
               #(not= before (get (current-shape shape-id) property))
               "Reordered grid track was not observed."))
            (.then (fn [_]
                     (operation-result
                      {:action action
                       :affectedIds [(dm/str shape-id)]})))))

      "patchTrack"
      (let [shape-id (request-id request "shapeId")
            type     (keyword (obj/get request "trackType"))
            property (grid-track-key type)
            index    (obj/get request "index")
            value    (layout-track-value (obj/get request "value"))]
        (require-grid! shape-id)
        (require-grid-track-index! shape-id property index)
        (st/emit! (dwsl/change-layout-track
                   [shape-id] type index value))
        (-> (wait-until
             #(= value (get-in (current-shape shape-id)
                               [property index]))
             "Updated grid track was not observed.")
            (.then (fn [_]
                     (operation-result
                      {:action action
                       :affectedIds [(dm/str shape-id)]})))))

      "patchCells"
      (let [shape-id (request-id request "shapeId")
            cell-ids (request-ids request "cellIds")
            patch    (obj/get request "patch")
            props    (cond-> {}
                       (obj/contains? patch "alignSelf")
                       (assoc :align-self
                              (keyword (obj/get patch "alignSelf")))

                       (obj/contains? patch "justifySelf")
                       (assoc :justify-self
                              (keyword (obj/get patch "justifySelf")))

                       (obj/contains? patch "areaName")
                       (assoc :area-name (obj/get patch "areaName")))
            position (when (obj/contains? patch "position")
                       (let [value (obj/get patch "position")]
                         {:row (obj/get value "row")
                          :column (obj/get value "column")
                          :row-span (obj/get value "rowSpan")
                          :column-span (obj/get value "columnSpan")}))]
        (require-grid! shape-id)
        (require-grid-cells! shape-id cell-ids)
        (when (and (empty? props) (nil? position))
          (throw (js/Error. "At least one grid-cell property is required.")))
        (emit-in-undo-transaction!
         (fn []
           (when (seq props)
             (st/emit! (dwsl/update-grid-cells shape-id cell-ids props)))
           (when position
             (doseq [cell-id cell-ids]
               (st/emit! (dwsl/update-grid-cell-position
                          shape-id cell-id position))))))
        (-> (wait-until
             #(every?
               (fn [cell-id]
                 (let [cell (get-in (current-shape shape-id)
                                    [:layout-grid-cells cell-id])]
                   (and (= props (select-keys cell (keys props)))
                        (or (nil? position)
                            (= position
                               (select-keys
                                cell
                                [:row :column :row-span :column-span]))))))
               cell-ids)
             "Updated grid cells were not observed.")
            (.then (fn [_]
                     (operation-result
                      {:action action
                       :affectedIds (mapv #(dm/str %) cell-ids)})))))

      "mergeCells"
      (let [shape-id (request-id request "shapeId")
            cell-ids (request-ids request "cellIds")
            before   (get-in (current-shape shape-id)
                             [:layout-grid-cells])]
        (require-grid! shape-id)
        (require-grid-cells! shape-id cell-ids)
        (st/emit! (dwsl/merge-cells shape-id cell-ids))
        (-> (wait-until
             #(not= before
                    (get-in (current-shape shape-id)
                            [:layout-grid-cells]))
             "Merged grid cells were not observed.")
            (.then (fn [_]
                     (operation-result
                      {:action action
                       :affectedIds (mapv #(dm/str %) cell-ids)})))))

      "createCellBoard"
      (let [shape-id (request-id request "shapeId")
            cell-ids (request-ids request "cellIds")
            before   (set (keys (dsh/lookup-page-objects @st/state)))]
        (require-grid! shape-id)
        (require-grid-cells! shape-id cell-ids)
        (st/emit! (dwsl/create-cell-board shape-id cell-ids))
        (-> (wait-until
             (fn []
               (let [objects (dsh/lookup-page-objects @st/state)
                     added   (set/difference (set (keys objects)) before)]
                 (some #(when (= :frame (:type (get objects %))) %) added)))
             "Created grid-cell board was not observed.")
            (.then (fn [board-id]
                     (operation-result
                      {:action action
                       :affectedIds [(dm/str board-id)]})))))

      (throw (js/Error. (str "Unsupported layout action '" action "'."))))))

(defn- external-color
  [value]
  (let [file-data (dsh/lookup-file-data @st/state)]
    (cond-> {}
      (obj/contains? value "color")
      (assoc :color (obj/get value "color"))

      (obj/contains? value "opacity")
      (assoc :opacity (obj/get value "opacity"))

      (obj/contains? value "gradient")
      (assoc :gradient
             (let [gradient (obj/get value "gradient")]
               {:type    (keyword (obj/get gradient "type"))
                :start-x (obj/get gradient "startX")
                :start-y (obj/get gradient "startY")
                :end-x   (obj/get gradient "endX")
                :end-y   (obj/get gradient "endY")
                :width   (obj/get gradient "width")
                :stops   (js->clj (obj/get gradient "stops")
                                  :keywordize-keys true)}))

      (obj/contains? value "imageId")
      (assoc :image
             (or (dm/get-in file-data
                            [:media (request-id value "imageId")])
                 (throw (js/Error. "The requested media asset was not found.")))))))

(defn- fill-value
  [value]
  (let [value (external-color value)]
    (d/without-nils
     {:fill-color (:color value)
      :fill-opacity (:opacity value)
      :fill-color-gradient (:gradient value)
      :fill-image (:image value)
      :fill-color-ref-id (:ref-id value)
      :fill-color-ref-file (:ref-file value)})))

(defn- stroke-value
  [value]
  (let [color-value (external-color (obj/get value "color"))]
    (d/without-nils
     {:stroke-color (:color color-value)
      :stroke-opacity (:opacity color-value)
      :stroke-color-gradient (:gradient color-value)
      :stroke-image (:image color-value)
      :stroke-width (obj/get value "width")
      :stroke-style (some-> (obj/get value "style") keyword)
      :stroke-alignment (some-> (obj/get value "alignment") keyword)
      :stroke-color-ref-id (:ref-id color-value)
      :stroke-color-ref-file (:ref-file color-value)})))

(defn- shadow-value
  [value]
  {:id       (uuid/next)
   :style    (keyword (obj/get value "style"))
   :offset-x (obj/get value "x")
   :offset-y (obj/get value "y")
   :blur     (obj/get value "blur")
   :spread   (obj/get value "spread")
   :hidden   (obj/get value "hidden" false)
   :color    (external-color (obj/get value "color"))})

(defn- shadow-patch
  [value]
  (cond-> {}
    (obj/contains? value "x")
    (assoc :offset-x (obj/get value "x"))

    (obj/contains? value "y")
    (assoc :offset-y (obj/get value "y"))

    (obj/contains? value "blur")
    (assoc :blur (obj/get value "blur"))

    (obj/contains? value "spread")
    (assoc :spread (obj/get value "spread"))

    (obj/contains? value "hidden")
    (assoc :hidden (obj/get value "hidden"))

    (obj/contains? value "color")
    (assoc :color (external-color (obj/get value "color")))))

(defn- set-shape-and-text-fills!
  [ids values]
  (let [objects              (dsh/lookup-page-objects @st/state)
        [text-ids shape-ids] (dsh/split-text-shapes objects ids)
        values               (apply fills/create values)
        undo-id              (js/Symbol)]
    (st/emit! (dwu/start-undo-transaction undo-id))
    (when (seq shape-ids)
      (st/emit! (dwsh/update-shapes
                 shape-ids
                 #(assoc % :fills values)
                 {:attrs [:fills]})))
    (doseq [id text-ids]
      (st/emit! (dwtxt/update-text-with-function
                 id
                 #(assoc % :fills values))))
    (st/emit! (dwu/commit-undo-transaction undo-id))))

(defn- shape-fills
  [shape]
  (if (= :text (:type shape))
    (some->> (:content shape)
             (txt/node-seq txt/is-text-node?)
             first
             :fills)
    (:fills shape)))

(defn- styles-operation
  [request]
  (require-editable!)
  (let [action (obj/get request "action")]
    (case action
      "setFills"
      (let [ids    (request-ids request "shapeIds")
            values (mapv fill-value (obj/get request "fills"))]
        (require-shapes! ids)
        (set-shape-and-text-fills! ids values)
        (-> (wait-until
             #(every? (fn [id]
                        (= values (vec (shape-fills (current-shape id)))))
                      ids)
             "Updated fills were not observed.")
            (.then (fn [_]
                     (operation-result
                      {:action action
                       :affectedIds (mapv #(dm/str %) ids)})))))

      "addFill"
      (let [ids   (request-ids request "shapeIds")
            value (external-color (obj/get request "fill"))
            before (into {}
                         (map (fn [id]
                                [id (count (shape-fills
                                            (current-shape id)))]))
                         ids)]
        (require-shapes! ids)
        (st/emit! (dwc/add-fill ids value))
        (-> (wait-until
             #(every? (fn [id]
                        (= (inc (get before id))
                           (count (shape-fills (current-shape id)))))
                      ids)
             "Added fills were not observed.")
            (.then (fn [_]
                     (operation-result
                      {:action action
                       :affectedIds (mapv #(dm/str %) ids)})))))

      "removeFill"
      (let [ids    (request-ids request "shapeIds")
            index  (obj/get request "index")
            before (into {}
                         (map (fn [id]
                                [id (count (shape-fills
                                            (current-shape id)))]))
                         ids)]
        (require-shapes! ids)
        (doseq [id ids]
          (when-not (get (vec (shape-fills (current-shape id))) index)
            (throw (js/Error. "The requested fill was not found."))))
        (st/emit! (dwc/remove-fill ids index))
        (-> (wait-until
             #(every? (fn [id]
                        (= (dec (get before id))
                           (count (shape-fills (current-shape id)))))
                      ids)
             "Removed fills were still present.")
            (.then (fn [_]
                     (operation-result
                      {:action action
                       :affectedIds (mapv #(dm/str %) ids)})))))

      "setStrokes"
      (let [ids    (request-ids request "shapeIds")
            values (mapv stroke-value (obj/get request "strokes"))]
        (require-shapes! ids)
        (st/emit! (dwsh/update-shapes ids #(assoc % :strokes values)
                                      {:attrs [:strokes]}))
        (-> (wait-until
             #(every? (fn [id] (= values (:strokes (current-shape id)))) ids)
             "Updated strokes were not observed.")
            (.then (fn [_]
                     (operation-result
                      {:action action
                       :affectedIds (mapv #(dm/str %) ids)})))))

      "addStroke"
      (let [ids    (request-ids request "shapeIds")
            value  (stroke-value (obj/get request "stroke"))
            before (into {}
                         (map (fn [id]
                                [id (count (:strokes
                                            (current-shape id)))]))
                         ids)]
        (require-shapes! ids)
        (st/emit! (dwc/add-stroke ids value))
        (-> (wait-until
             #(every? (fn [id]
                        (= (inc (get before id))
                           (count (:strokes (current-shape id)))))
                      ids)
             "Added strokes were not observed.")
            (.then (fn [_]
                     (operation-result
                      {:action action
                       :affectedIds (mapv #(dm/str %) ids)})))))

      "removeStroke"
      (let [ids    (request-ids request "shapeIds")
            index  (obj/get request "index")
            before (into {}
                         (map (fn [id]
                                [id (count (:strokes
                                            (current-shape id)))]))
                         ids)]
        (require-shapes! ids)
        (doseq [id ids]
          (when-not (get (:strokes (current-shape id)) index)
            (throw (js/Error. "The requested stroke was not found."))))
        (st/emit! (dwc/remove-stroke ids index))
        (-> (wait-until
             #(every? (fn [id]
                        (= (dec (get before id))
                           (count (:strokes (current-shape id)))))
                      ids)
             "Removed strokes were still present.")
            (.then (fn [_]
                     (operation-result
                      {:action action
                       :affectedIds (mapv #(dm/str %) ids)})))))

      "addShadow"
      (let [ids    (request-ids request "shapeIds")
            value  (shadow-value (obj/get request "shadow"))
            before (into {}
                         (map (fn [id]
                                [id (count (:shadow
                                            (current-shape id)))]))
                         ids)]
        (require-shapes! ids)
        (st/emit! (dwc/add-shadow ids value))
        (-> (wait-until
             #(every? (fn [id]
                        (= (inc (get before id))
                           (count (:shadow (current-shape id)))))
                      ids)
             "Added shadows were not observed.")
            (.then (fn [_]
                     (operation-result
                      {:action action
                       :affectedIds (mapv #(dm/str %) ids)})))))

      "patchShadow"
      (let [ids    (request-ids request "shapeIds")
            index  (obj/get request "index")
            patch  (shadow-patch (obj/get request "shadow"))]
        (require-shapes! ids)
        (when (empty? patch)
          (throw (js/Error. "At least one shadow property is required.")))
        (doseq [id ids]
          (when-not (get-in (current-shape id) [:shadow index])
            (throw (js/Error. "The requested shadow was not found."))))
        (st/emit! (dwsh/update-shapes
                   ids
                   #(update-in % [:shadow index] merge patch)))
        (-> (wait-until
             #(every?
               (fn [id]
                 (= patch
                    (select-keys
                     (get-in (current-shape id) [:shadow index])
                     (keys patch))))
               ids)
             "Updated shadows were not observed.")
            (.then (fn [_]
                     (operation-result
                      {:action action
                       :affectedIds (mapv #(dm/str %) ids)})))))

      "removeShadow"
      (let [ids    (request-ids request "shapeIds")
            index  (obj/get request "index")
            before (into {}
                         (map (fn [id]
                                [id (count (:shadow
                                            (current-shape id)))]))
                         ids)]
        (require-shapes! ids)
        (doseq [id ids]
          (when-not (get-in (current-shape id) [:shadow index])
            (throw (js/Error. "The requested shadow was not found."))))
        (st/emit! (dwsh/update-shapes
                   ids
                   #(update % :shadow
                            (fn [values]
                              (into []
                                    (keep-indexed
                                     (fn [position value]
                                       (when (not= position index)
                                         value)))
                                    values)))))
        (-> (wait-until
             #(every? (fn [id]
                        (= (dec (get before id))
                           (count (:shadow (current-shape id)))))
                      ids)
             "Removed shadows were still present.")
            (.then (fn [_]
                     (operation-result
                      {:action action
                       :affectedIds (mapv #(dm/str %) ids)})))))

      "setOpacity"
      (let [ids     (request-ids request "shapeIds")
            opacity (obj/get request "opacity")]
        (require-shapes! ids)
        (st/emit! (dwsh/update-shapes ids #(assoc % :opacity opacity)))
        (-> (wait-until
             #(every? (fn [id] (= opacity (:opacity (current-shape id)))) ids)
             "Updated opacity was not observed.")
            (.then (fn [_]
                     (operation-result
                      {:action action
                       :affectedIds (mapv #(dm/str %) ids)})))))

      "setRadii"
      (let [ids   (request-ids request "shapeIds")
            value (obj/get request "radii")
            radii {:r1 (obj/get value "topLeft")
                   :r2 (obj/get value "topRight")
                   :r3 (obj/get value "bottomRight")
                   :r4 (obj/get value "bottomLeft")}]
        (require-shapes! ids)
        (st/emit! (dwsh/update-shapes ids #(merge % radii)))
        (-> (wait-until
             #(every? (fn [id]
                        (= radii
                           (select-keys (current-shape id)
                                        [:r1 :r2 :r3 :r4])))
                      ids)
             "Updated corner radii were not observed.")
            (.then (fn [_]
                     (operation-result
                      {:action action
                       :affectedIds (mapv #(dm/str %) ids)})))))

      "applyColorAsset"
      (let [ids        (request-ids request "shapeIds")
            color-id   (request-id request "colorId")
            library-id (if (obj/contains? request "libraryId")
                         (request-id request "libraryId")
                         (:current-file-id @st/state))
            asset      (dm/get-in
                        (dsh/lookup-file-data @st/state library-id)
                        [:colors color-id])
            target     (obj/get request "target")]
        (require-shapes! ids)
        (when-not asset
          (throw (js/Error. "The requested color asset was not found.")))
        (let [value (color/library-color->color asset library-id)]
          (case target
            ("fill" "text")
            (st/emit! (dwc/change-fill ids value 0))

            "stroke"
            (st/emit! (dwc/change-stroke-color ids value 0))))
        (-> (wait-until
             #(every?
               (fn [id]
                 (let [shape (current-shape id)
                       applied
                       (if (= target "stroke")
                         (first (:strokes shape))
                         (first (shape-fills shape)))
                       id-key (if (= target "stroke")
                                :stroke-color-ref-id
                                :fill-color-ref-id)
                       file-key (if (= target "stroke")
                                  :stroke-color-ref-file
                                  :fill-color-ref-file)]
                   (and (= color-id (get applied id-key))
                        (= library-id (get applied file-key)))))
               ids)
             "Applied color asset references were not observed.")
            (.then (fn [_]
                     (operation-result
                      {:action action
                       :affectedIds (mapv #(dm/str %) ids)})))))

      (throw (js/Error. (str "Unsupported styles action '" action "'."))))))

(defn- current-components
  []
  (dm/get-in (dsh/lookup-file-data @st/state) [:components]))

(defn- find-component!
  [component-id]
  (or (get (current-components) component-id)
      (throw (js/Error. "The requested component was not found."))))

(defn- components-operation
  [request]
  (require-editable!)
  (let [action (obj/get request "action")]
    (case action
      "create"
      (let [ids          (request-ordered-ids request "shapeIds")
            name         (obj/get request "name")
            component-id (atom nil)]
        (require-shapes! ids)
        (run-in-undo-transaction
         (fn []
           (st/emit! (dwl/add-component component-id ids))
           (-> (wait-until
                #(when-let [id @component-id]
                   (get (current-components) id))
                "Created component was not observed.")
               (.then
                (fn [component]
                  (if (and (string? name) (not-empty name))
                    (do
                      (st/emit! (dwl/rename-component-and-main-instance
                                 (:id component) name))
                      (wait-until
                       #(= name (:name (get (current-components)
                                            (:id component))))
                       "Created component name was not observed."))
                    (js/Promise.resolve component))))
               (.then (fn [_]
                        (operation-result
                         {:action action
                          :affectedIds [(dm/str @component-id)]})))))))

      "createMultiple"
      (let [ids    (request-ordered-ids request "shapeIds")
            before (set (keys (current-components)))
            undo-id (js/Symbol)]
        (require-shapes! ids)
        (st/emit! (dwu/start-undo-transaction undo-id))
        (doseq [id ids]
          (st/emit! (dwl/add-component nil [id])))
        (st/emit! (dwu/commit-undo-transaction undo-id))
        (-> (wait-until
             (fn []
               (let [added (set/difference
                            (set (keys (current-components)))
                            before)]
                 (when (= (count ids) (count added)) added)))
             "Created components were not observed.")
            (.then (fn [added]
                     (operation-result
                      {:action action
                       :affectedIds (mapv #(dm/str %) added)})))))

      "instantiate"
      (let [component-id (request-id request "componentId")
            library-id   (if (obj/contains? request "libraryId")
                           (request-id request "libraryId")
                           (:current-file-id @st/state))
            position     (obj/get request "position")
            shape-id     (atom nil)]
        (when-not (dm/get-in (dsh/lookup-file-data @st/state library-id)
                             [:components component-id])
          (throw (js/Error. "The requested component was not found.")))
        (st/emit! (dwl/instantiate-component
                   library-id component-id
                   (gpt/point (obj/get position "x")
                              (obj/get position "y"))
                   {:id-ref shape-id
                    :origin "hpd-client-tools"}))
        (-> (wait-until #(when-let [id @shape-id]
                           (shape-exists? id))
                        "Instantiated component was not observed.")
            (.then (fn [_]
                     (operation-result
                      {:action action
                       :affectedIds [(dm/str @shape-id)]})))))

      "detach"
      (let [ids (request-ordered-ids request "shapeIds")]
        (require-shapes! ids)
        (st/emit! (dwl/detach-components ids))
        (-> (wait-until
             #(every? (fn [id]
                        (nil? (:component-id (current-shape id))))
                      ids)
             "Detached component references were still present.")
            (.then (fn [_]
                     (operation-result
                      {:action action
                       :affectedIds (mapv #(dm/str %) ids)})))))

      "reset"
      (let [ids (request-ordered-ids request "shapeIds")]
        (require-shapes! ids)
        (doseq [id ids]
          (when-not (:component-id (current-shape id))
            (throw (js/Error. "Only component instances can be reset."))))
        (st/emit! (dwl/reset-components ids))
        (-> (wait-until
             #(let [objects (dsh/lookup-page-objects @st/state)]
                (every?
                 (fn [id]
                   (every? (comp empty? :touched)
                           (cfh/get-children-with-self objects id)))
                 ids))
             "Reset component overrides were still present.")
            (.then (fn [_]
                     (operation-result
                      {:action action
                       :affectedIds (mapv #(dm/str %) ids)})))))

      "swap"
      (let [ids          (request-ordered-ids request "shapeIds")
            component-id (request-id request "componentId")
            library-id   (if (obj/contains? request "libraryId")
                           (request-id request "libraryId")
                           (:current-file-id @st/state))
            shapes       (mapv current-shape ids)]
        (require-shapes! ids)
        (when-not (dm/get-in (dsh/lookup-file-data @st/state library-id)
                             [:components component-id])
          (throw (js/Error. "The requested component was not found.")))
        (st/emit! (dwl/component-multi-swap
                   shapes library-id component-id))
        (-> (wait-until
             #(every? (fn [id]
                        (= component-id
                           (:component-id (current-shape id))))
                      ids)
             "Swapped component instances were not observed.")
            (.then (fn [_]
                     (operation-result
                      {:action action
                       :affectedIds (mapv #(dm/str %) ids)})))))

      "duplicate"
      (let [component-id     (request-id request "componentId")
            library-id       (if (obj/contains? request "libraryId")
                               (request-id request "libraryId")
                               (:current-file-id @st/state))
            new-component-id (uuid/next)]
        (when-not (dm/get-in (dsh/lookup-file-data @st/state library-id)
                             [:components component-id])
          (throw (js/Error. "The requested component was not found.")))
        (st/emit! (dwl/duplicate-component
                   library-id component-id new-component-id))
        (-> (wait-until #(get (current-components) new-component-id)
                        "Duplicated component was not observed.")
            (.then (fn [_]
                     (operation-result
                      {:action action
                       :affectedIds [(dm/str new-component-id)]})))))

      "rename"
      (let [component-id (request-id request "componentId")
            name         (obj/get request "name")]
        (find-component! component-id)
        (st/emit! (dwl/rename-component-and-main-instance
                   component-id name))
        (-> (wait-until
             #(= name (:name (get (current-components) component-id)))
             "Renamed component was not observed.")
            (.then (fn [_]
                     (operation-result
                      {:action action
                       :affectedIds [(dm/str component-id)]})))))

      "delete"
      (let [component-id (request-id request "componentId")
            component    (find-component! component-id)]
        (when (:deleted component)
          (throw (js/Error. "The requested component is already deleted.")))
        (st/emit! (dwl/delete-component component))
        (-> (wait-until
             #(true? (:deleted (get (current-components) component-id)))
             "Deleted component was still active.")
            (.then (fn [_]
                     (operation-result
                      {:action action
                       :affectedIds [(dm/str component-id)]})))))

      "restore"
      (let [component-id (request-id request "componentId")
            library-id   (:current-file-id @st/state)
            component    (find-component! component-id)]
        (when-not (:deleted component)
          (throw (js/Error. "The requested component is not deleted.")))
        (st/emit! (dwl/restore-component library-id component-id))
        (-> (wait-until
             #(false? (boolean
                       (:deleted
                        (get (current-components) component-id))))
             "Restored component was not observed.")
            (.then (fn [_]
                     (operation-result
                      {:action action
                       :affectedIds [(dm/str component-id)]})))))

      "combineVariants"
      (let [ids        (request-ordered-ids request "shapeIds")
            variant-id (uuid/next)]
        (require-shapes! ids)
        (st/emit! (dwv/combine-as-variants
                   ids {:variant-id variant-id
                        :trigger "hpd-client-tools"}))
        (-> (wait-until #(shape-exists? variant-id)
                        "Combined variant container was not observed.")
            (.then (fn [_]
                     (operation-result
                      {:action action
                       :affectedIds [(dm/str variant-id)]})))))

      "addVariant"
      (let [shape-id (request-id request "shapeId")
            before   (set (keys (current-components)))]
        (require-shapes! [shape-id])
        (st/emit! (dwv/add-new-variant shape-id))
        (-> (wait-until
             (fn []
               (let [added (set/difference
                            (set (keys (current-components)))
                            before)]
                 (first added)))
             "Added variant was not observed.")
            (.then (fn [component-id]
                     (operation-result
                      {:action action
                       :affectedIds [(dm/str component-id)]})))))

      "renameVariant"
      (let [shape-id (request-id request "shapeId")
            name     (obj/get request "name")]
        (require-shapes! [shape-id])
        (st/emit! (dwv/rename-variant shape-id name))
        (-> (wait-until #(= name (:name (current-shape shape-id)))
                        "Renamed variant was not observed.")
            (.then (fn [_]
                     (operation-result
                      {:action action
                       :affectedIds [(dm/str shape-id)]})))))

      "reorderVariantProperties"
      (let [variant-id (request-id request "variantId")
            from       (obj/get request "fromIndex")
            to         (obj/get request "toIndex")
            before     (mapv :variant-properties
                             (vals (current-components)))]
        (st/emit! (dwv/reorder-variant-poperties variant-id from to))
        (-> (if (= from to)
              (js/Promise.resolve true)
              (wait-until
               #(not= before
                      (mapv :variant-properties
                            (vals (current-components))))
               "Reordered variant properties were not observed."))
            (.then (fn [_]
                     (operation-result
                      {:action action
                       :affectedIds [(dm/str variant-id)]})))))

      "switchVariant"
      (let [ids          (request-ordered-ids request "shapeIds")
            component-id (request-id request "componentId")
            library-id   (:current-file-id @st/state)
            shapes       (mapv current-shape ids)]
        (require-shapes! ids)
        (find-component! component-id)
        (st/emit! (dwl/component-multi-swap
                   shapes library-id component-id))
        (-> (wait-until
             #(every? (fn [id]
                        (= component-id
                           (:component-id (current-shape id))))
                      ids)
             "Switched variants were not observed.")
            (.then (fn [_]
                     (operation-result
                      {:action action
                       :affectedIds (mapv #(dm/str %) ids)})))))

      (throw
       (js/Error. (str "Unsupported components action '" action "'."))))))

(defn- current-tokens-lib
  []
  (some-> (dsh/lookup-file-data @st/state)
          :tokens-lib
          tokens/ensure-tokens-lib))

(defn- token-entry-by-name
  [token-name]
  (let [library (current-tokens-lib)]
    (some
     (fn [token-set]
       (some (fn [[_ token]]
               (when (= token-name (:name token))
                 {:set token-set :token token}))
             (tokens/get-tokens library (tokens/get-id token-set))))
     (tokens/get-sets library))))

(defn- require-token-entry!
  [token-name]
  (or (token-entry-by-name token-name)
      (throw
       (js/Error. (str "Token '" token-name "' was not found.")))))

(defn- token-result
  [token-set token]
  {:id          (dm/str (:id token))
   :name        (:name token)
   :type        (some-> (:type token) name)
   :value       (:value token)
   :description (:description token)
   :set         {:id (dm/str (tokens/get-id token-set))
                 :name (tokens/get-name token-set)}})

(defn- tokens-list-result
  []
  (let [library (current-tokens-lib)]
    {:sets
     (mapv
      (fn [token-set]
        {:id     (dm/str (tokens/get-id token-set))
         :name   (tokens/get-name token-set)
         :tokens (mapv
                  #(token-result token-set %)
                  (vals (tokens/get-tokens
                         library
                         (tokens/get-id token-set))))})
      (tokens/get-sets library))
     :themes
     (mapv
      (fn [theme]
        {:id (dm/str (tokens/get-id theme))
         :name (tokens/get-name theme)
         :group (:group theme)
         :sets (vec (:sets theme))})
      (tokens/get-themes library))
     :activeThemes
     (vec (tokens/get-active-themes library))}))

(defn- tokens-operation
  [request]
  (let [action (obj/get request "action")]
    (case action
      "list"
      (clj->js {:data (tokens-list-result)
                :context (context-data)})

      "create"
      (let [name       (obj/get request "name")
            token-id   (uuid/next)
            set-name   (obj/get request "setName")
            library    (current-tokens-lib)
            token-set  (when (and (string? set-name)
                                  (not-empty set-name))
                         (tokens/get-set-by-name library set-name))
            token      (tokens/make-token
                        :id token-id
                        :name name
                        :type (keyword (obj/get request "tokenType"))
                        :value (js->clj (obj/get request "value")
                                        :keywordize-keys true))]
        (require-editable!)
        (when (token-entry-by-name name)
          (throw (js/Error. "A token with this name already exists.")))
        (when (and (string? set-name)
                   (not-empty set-name)
                   (nil? token-set))
          (throw (js/Error. "The requested token set was not found.")))
        (st/emit! (dwtl/create-token
                   (some-> token-set tokens/get-id)
                   token))
        (-> (wait-until
             #(when-let [created (:token (token-entry-by-name name))]
                (= token-id (:id created)))
             "Created token was not observed.")
            (.then (fn [_]
                     (operation-result
                      {:action action
                       :affectedIds [(dm/str token-id)]
                       :tokenName name})))))

      "update"
      (let [name        (obj/get request "name")
            new-name    (obj/get request "newName")
            entry       (require-token-entry! name)
            token       (:token entry)
            token-set   (:set entry)
            params      (cond-> {}
                          (obj/contains? request "newName")
                          (assoc :name new-name)

                          (obj/contains? request "value")
                          (assoc :value
                                 (js->clj (obj/get request "value")
                                          :keywordize-keys true))

                          (obj/contains? request "description")
                          (assoc :description
                                 (obj/get request "description")))
            final-name  (or new-name name)]
        (require-editable!)
        (when (empty? params)
          (throw (js/Error. "At least one token property is required.")))
        (when (and new-name
                   (not= new-name name)
                   (token-entry-by-name new-name))
          (throw (js/Error. "A token with the new name already exists.")))
        (emit-in-undo-transaction!
         (fn []
           (st/emit! (dwtl/update-token
                      (tokens/get-id token-set)
                      (:id token)
                      params))
           (when (and new-name (not= new-name name))
             (st/emit! (dwtr/remap-tokens name new-name)))))
        (-> (wait-until
             #(when-let [updated (:token
                                  (token-entry-by-name final-name))]
                (every? (fn [[key value]]
                          (= value (get updated key)))
                        params))
             "Updated token properties were not observed.")
            (.then (fn [_]
                     (operation-result
                      {:action action
                       :affectedIds [(dm/str (:id token))]
                       :tokenName final-name})))))

      "delete"
      (let [name      (obj/get request "name")
            entry     (require-token-entry! name)
            token     (:token entry)
            token-set (:set entry)]
        (require-editable!)
        (st/emit! (dwtl/delete-token
                   (tokens/get-id token-set)
                   (:id token)))
        (-> (wait-until #(nil? (token-entry-by-name name))
                        "Deleted token was still present.")
            (.then (fn [_]
                     (operation-result
                      {:action action
                       :affectedIds [(dm/str (:id token))]
                       :tokenName name})))))

      "apply"
      (let [ids        (request-ids request "shapeIds")
            names      (vec (obj/get request "tokenNames"))
            attributes (into #{} (map keyword)
                             (obj/get request "attributes"))
            entries    (mapv require-token-entry! names)]
        (require-editable!)
        (require-shapes! ids)
        (emit-in-undo-transaction!
         (fn []
           (doseq [{:keys [token]} entries]
             (let [{:keys [on-update-shape]}
                   (dwta/get-token-properties token)]
               (st/emit! (dwta/apply-token
                          {:attributes attributes
                           :token token
                           :shape-ids ids
                           :on-update-shape on-update-shape}))))))
        (-> (wait-until
             #(every?
               (fn [id]
                 (let [applied (set (vals
                                     (:applied-tokens
                                      (current-shape id))))]
                   (every? applied names)))
               ids)
             "Applied token references were not observed.")
            (.then (fn [_]
                     (operation-result
                      {:action action
                       :affectedIds (mapv #(dm/str %) ids)
                       :tokenNames names})))))

      "unapply"
      (let [ids        (request-ids request "shapeIds")
            names      (vec (obj/get request "tokenNames"))
            attributes (into #{} (map keyword)
                             (obj/get request "attributes"))]
        (require-editable!)
        (require-shapes! ids)
        (emit-in-undo-transaction!
         (fn []
           (doseq [name names]
             (st/emit! (dwta/unapply-token
                        {:attributes attributes
                         :token-name name
                         :shape-ids ids})))))
        (-> (wait-until
             #(every?
               (fn [id]
                 (let [applied (set (vals
                                     (:applied-tokens
                                      (current-shape id))))]
                   (not-any? applied names)))
               ids)
             "Removed token references were still present.")
            (.then (fn [_]
                     (operation-result
                      {:action action
                       :affectedIds (mapv #(dm/str %) ids)
                       :tokenNames names})))))

      "import"
      (let [data    (obj/get request "data")
            format  (obj/get request "format")
            decoded (try
                      (if (= format "json")
                        (js/JSON.parse data)
                        (json5/parse data))
                      (catch :default error
                        (throw
                         (js/Error.
                          (str "Token data could not be parsed: "
                               (.-message error))))))
            library (tokens/parse-decoded-json
                     (js->clj decoded)
                     "Imported")]
        (require-editable!)
        (st/emit! (dwtl/import-tokens-lib library))
        (-> (wait-until
             #(= (tokens/export-dtcg-json library)
                 (tokens/export-dtcg-json
                  (current-tokens-lib)))
             "Imported token library was not observed.")
            (.then (fn [_]
                     (operation-result
                      {:action action
                       :format format})))))

      "export"
      (let [format (obj/get request "format")
            data   (tokens/export-dtcg-json
                    (current-tokens-lib))]
        (clj->js
         {:data {:action action
                 :format format
                 :content
                 (if (= format "json5")
                   (json5/stringify (clj->js data) nil 2)
                   (js/JSON.stringify (clj->js data) nil 2))}
          :context (context-data)}))

      "remap"
      (let [mappings (obj/get request "mappings")
            entries  (mapv
                      (fn [mapping]
                        [(require-token-entry!
                          (obj/get mapping "from"))
                         mapping])
                      mappings)
            undo-id  (js/Symbol)]
        (require-editable!)
        (st/emit! (dwu/start-undo-transaction undo-id))
        (doseq [[entry mapping] entries]
          (let [from      (obj/get mapping "from")
                to        (obj/get mapping "to")
                token     (:token entry)
                token-set (:set entry)]
            (when-not (= from to)
              (st/emit! (dwtl/update-token
                         (tokens/get-id token-set)
                         (:id token)
                         {:name to}))
              (st/emit! (dwtr/remap-tokens from to)))))
        (st/emit! (dwu/commit-undo-transaction undo-id))
        (-> (wait-until
             #(every?
               (fn [mapping]
                 (let [from (obj/get mapping "from")
                       to   (obj/get mapping "to")]
                   (or (= from to)
                       (and (nil? (token-entry-by-name from))
                            (some? (token-entry-by-name to))))))
               mappings)
             "Remapped token references were not observed.")
            (.then (fn [_]
                     (operation-result
                      {:action action
                       :mappings (js->clj mappings
                                          :keywordize-keys true)})))))

      "propagate"
      (let [names (vec (obj/get request "tokenNames"))]
        (require-editable!)
        (doseq [name names]
          (require-token-entry! name))
        (-> (js/Promise.
             (fn [resolve reject]
               (st/emit!
                (dwtp/propagate-selected-workspace-tokens
                 names
                 resolve
                 reject))))
            (.then
             (fn [_]
               (operation-result
                {:action action
                 :tokenNames names})))))

      (throw (js/Error. (str "Unsupported tokens action '" action "'."))))))

(defn- current-flows
  []
  (:flows (dsh/lookup-page @st/state)))

(defn- external-interaction
  [value shape-id]
  (let [interaction
        (cond-> {:event-type (keyword (obj/get value "eventType"))
                 :action-type (keyword (obj/get value "actionType"))
                 :position-relative-to shape-id}
          (obj/contains? value "destinationId")
          (assoc :destination (request-id value "destinationId"))

          (obj/contains? value "delay")
          (assoc :delay (obj/get value "delay"))

          (obj/contains? value "preserveScrollPosition")
          (assoc :preserve-scroll
                 (obj/get value "preserveScrollPosition"))

          (obj/contains? value "overlayPositionType")
          (assoc :overlay-pos-type
                 (keyword (obj/get value "overlayPositionType")))

          (obj/contains? value "closeOnClickOutside")
          (assoc :close-click-outside
                 (obj/get value "closeOnClickOutside"))

          (obj/contains? value "backgroundOverlay")
          (assoc :background-overlay
                 (obj/get value "backgroundOverlay")))]
    (when-not (interactions/check-interaction interaction)
      (throw (js/Error. "The prototype interaction is not valid.")))
    interaction))

(defn- prototype-operation
  [request]
  (let [action (obj/get request "action")]
    (case action
      "listFlows"
      (clj->js
       {:data
        (mapv (fn [flow]
                {:id (dm/str (:id flow))
                 :name (:name flow)
                 :boardId (dm/str (:starting-frame flow))})
              (vals (current-flows)))
        :context (context-data)})

      "addFlow"
      (let [board-id (request-id request "boardId")
            flow-id  (uuid/next)
            name     (obj/get request "name")]
        (require-editable!)
        (require-shapes! [board-id])
        (when-not (= :frame (:type (current-shape board-id)))
          (throw (js/Error. "A prototype flow must start at a board.")))
        (st/emit! (dwi/add-flow flow-id nil name board-id))
        (-> (wait-until #(get (current-flows) flow-id)
                        "Created prototype flow was not observed.")
            (.then (fn [_]
                     (operation-result
                      {:action action
                       :affectedIds [(dm/str flow-id)]})))))

      "updateFlow"
      (let [flow-id (request-id request "flowId")
            name    (obj/get request "name")
            board-id (when (obj/contains? request "boardId")
                       (request-id request "boardId"))
            page-id (:current-page-id @st/state)]
        (require-editable!)
        (when-not (get (current-flows) flow-id)
          (throw (js/Error. "The requested prototype flow was not found.")))
        (when board-id
          (require-shapes! [board-id]))
        (st/emit! (dwi/update-flow
                   page-id flow-id
                   (fn [flow]
                     (cond-> flow
                       (some? name) (assoc :name name)
                       (some? board-id) (assoc :starting-frame board-id)))))
        (-> (wait-until
             #(let [flow (get (current-flows) flow-id)]
                (and flow
                     (or (nil? name) (= name (:name flow)))
                     (or (nil? board-id)
                         (= board-id (:starting-frame flow)))))
             "Updated prototype flow was not observed.")
            (.then (fn [_]
                     (operation-result
                      {:action action
                       :affectedIds [(dm/str flow-id)]})))))

      "removeFlow"
      (let [flow-id (request-id request "flowId")]
        (require-editable!)
        (when-not (get (current-flows) flow-id)
          (throw (js/Error. "The requested prototype flow was not found.")))
        (st/emit! (dwi/remove-flow flow-id))
        (-> (wait-until #(nil? (get (current-flows) flow-id))
                        "Removed prototype flow was still present.")
            (.then (fn [_]
                     (operation-result
                      {:action action
                       :affectedIds [(dm/str flow-id)]})))))

      "addInteraction"
      (let [shape-id    (request-id request "shapeId")
            interaction (external-interaction
                         (obj/get request "interaction")
                         shape-id)
            before      (count (:interactions
                                (current-shape shape-id)))]
        (require-editable!)
        (require-shapes! [shape-id])
        (st/emit! (dwi/add-interaction
                   nil shape-id interaction))
        (-> (wait-until
             #(= (inc before)
                 (count (:interactions (current-shape shape-id))))
             "Added prototype interaction was not observed.")
            (.then (fn [_]
                     (operation-result
                      {:action action
                       :affectedIds [(dm/str shape-id)]
                       :index before})))))

      "updateInteraction"
      (let [shape-id    (request-id request "shapeId")
            index       (obj/get request "index")
            shape       (current-shape shape-id)
            interaction (external-interaction
                         (obj/get request "interaction")
                         shape-id)]
        (require-editable!)
        (require-shapes! [shape-id])
        (when-not (get (:interactions shape) index)
          (throw (js/Error. "The requested interaction was not found.")))
        (st/emit! (dwi/update-interaction
                   shape index (constantly interaction)))
        (-> (wait-until
             #(= interaction
                 (get (:interactions (current-shape shape-id))
                      index))
             "Updated prototype interaction was not observed.")
            (.then (fn [_]
                     (operation-result
                      {:action action
                       :affectedIds [(dm/str shape-id)]
                       :index index})))))

      "removeInteraction"
      (let [shape-id (request-id request "shapeId")
            index    (obj/get request "index")
            shape    (current-shape shape-id)
            before   (count (:interactions shape))]
        (require-editable!)
        (require-shapes! [shape-id])
        (when-not (get (:interactions shape) index)
          (throw (js/Error. "The requested interaction was not found.")))
        (st/emit! (dwi/remove-interaction shape index))
        (-> (wait-until
             #(= (dec before)
                 (count (:interactions (current-shape shape-id))))
             "Removed prototype interaction was still present.")
            (.then (fn [_]
                     (operation-result
                      {:action action
                       :affectedIds [(dm/str shape-id)]
                       :index index})))))

      (throw
       (js/Error. (str "Unsupported prototype action '" action "'."))))))

(defn- comment-thread-summary
  [thread]
  {:id          (dm/str (:id thread))
   :pageId      (dm/str (:page-id thread))
   :fileId      (dm/str (:file-id thread))
   :content     (:content thread)
   :resolved    (boolean (:is-resolved thread))
   :position    (:position thread)
   :owner       {:id (some-> (:owner-id thread) dm/str)
                 :name (:owner-fullname thread)
                 :email (:owner-email thread)}
   :commentCount (:count-comments thread)
   :unreadCount (:count-unread-comments thread)
   :createdAt   (:created-at thread)
   :modifiedAt  (:modified-at thread)})

(defn- collaboration-operation
  [request]
  (let [action  (obj/get request "action")
        file-id (:current-file-id @st/state)]
    (case action
      "listComments"
      (let [page-id  (when (obj/contains? request "pageId")
                       (request-id request "pageId"))
            resolved (when (obj/contains? request "resolved")
                       (obj/get request "resolved"))]
        (-> (observable->promise
             (rp/cmd! :get-comment-threads {:file-id file-id}))
            (.then
             (fn [threads]
               (clj->js
                {:data
                 (->> threads
                      (filter
                       (fn [thread]
                         (and
                          (or (nil? page-id)
                              (= page-id (:page-id thread)))
                          (or (nil? resolved)
                              (= resolved
                                 (boolean (:is-resolved thread)))))))
                      (mapv comment-thread-summary))
                 :context (context-data)})))))

      "createComment"
      (let [page-id  (if (obj/contains? request "pageId")
                       (request-id request "pageId")
                       (:current-page-id @st/state))
            position (obj/get request "position")
            position (gpt/point (obj/get position "x")
                                (obj/get position "y"))
            objects   (dsh/lookup-page-objects @st/state page-id)
            frame-id  (shape-tree/get-frame-id-by-position
                       objects position)
            params    (comments/update-mentions
                       {:page-id page-id
                        :file-id file-id
                        :frame-id frame-id
                        :position position
                        :content (obj/get request "content")})]
        (require-editable!)
        (-> (observable->promise
             (rp/cmd! :create-comment-thread params))
            (.then
             (fn [created]
               (observable->promise
                (rp/cmd! :get-comment-thread
                         {:file-id file-id
                          :id (:id created)}))))
            (.then
             (fn [thread]
               (st/emit! (comments/retrieve-comment-threads file-id))
               (clj->js
                {:data {:action action
                        :affectedIds [(dm/str (:id thread))]
                        :thread (comment-thread-summary thread)}
                 :context (context-data)})))))

      "reply"
      (let [thread-id (request-id request "threadId")
            params    (comments/update-mentions
                       {:thread-id thread-id
                        :content (obj/get request "content")})]
        (require-editable!)
        (-> (observable->promise (rp/cmd! :create-comment params))
            (.then
             (fn [comment]
               (st/emit! (comments/retrieve-comments thread-id))
               (clj->js
                {:data {:action action
                        :affectedIds [(dm/str (:id comment))]
                        :threadId (dm/str thread-id)
                        :comment comment}
                 :context (context-data)})))))

      "updateComment"
      (let [comment-id (request-id request "commentId")
            params     (comments/update-mentions
                        {:id comment-id
                         :content (obj/get request "content")})]
        (require-editable!)
        (-> (observable->promise (rp/cmd! :update-comment params))
            (.then
             (fn [_]
               (operation-result
                {:action action
                 :affectedIds [(dm/str comment-id)]})))))

      "resolveThread"
      (let [thread-id (request-id request "threadId")
            resolved  (obj/get request "resolved")]
        (require-editable!)
        (-> (observable->promise
             (rp/cmd! :update-comment-thread
                      {:id thread-id
                       :is-resolved resolved}))
            (.then
             (fn [_]
               (st/emit! (comments/retrieve-comment-threads file-id))
               (operation-result
                {:action action
                 :affectedIds [(dm/str thread-id)]
                 :resolved resolved})))))

      "deleteComment"
      (let [comment-id (request-id request "commentId")]
        (require-editable!)
        (-> (observable->promise
             (rp/cmd! :delete-comment {:id comment-id}))
            (.then
             (fn [_]
               (operation-result
                {:action action
                 :affectedIds [(dm/str comment-id)]})))))

      (throw
       (js/Error.
        (str "Unsupported collaboration action '" action "'."))))))

(defn- export-value
  [value]
  (d/without-nils
   {:type (keyword (obj/get value "type"))
    :scale (obj/get value "scale" 1)
    :suffix (obj/get value "suffix")}))

(defn- await-export-completion
  [event-stream resource-id]
  (->> event-stream
       (rx/filter ws/message-event?)
       (rx/map :payload)
       (rx/filter #(and (= :export-update (:type %))
                        (= resource-id (:resource-id %))
                        (#{"ended" "error"} (:status %))))
       (rx/take 1)
       (rx/map
        (fn [{:keys [status cause filename mtype resource-uri]}]
          (when (= status "error")
            (throw
             (js/Error.
              (or cause "Penpot export failed."))))
          (let [resource-url (js/URL.
                              resource-uri
                              (if (exists? js/location)
                                (.-origin js/location)
                                "https://invalid.local"))]
            {:filename filename
             :mediaType mtype
             :path (str (.-pathname resource-url)
                        (.-search resource-url))})))
       (observable->promise)))

(defn- create-media-shape!
  ([media position name]
   (create-media-shape! media position name true))
  ([media position name add-media?]
   (let [shape-id (uuid/next)
         width    (:width media)
         height   (:height media)
         shape    (cts/setup-shape
                   {:id        shape-id
                    :type      :rect
                    :name      (or name (:name media) "Media")
                    :x         (- (:x position) (/ width 2))
                    :y         (- (:y position) (/ height 2))
                    :width     width
                    :height    height
                    :parent-id uuid/zero
                    :frame-id  uuid/zero
                    :fills
                    (fills/create
                     {:fill-opacity 1
                      :fill-image
                      {:width width
                       :height height
                       :mtype (:mtype media)
                       :id (:id media)
                       :name (:name media)
                       :keep-aspect-ratio true}})})]
     (emit-in-undo-transaction!
      (fn []
        (when add-media?
          (st/emit! (dwl/add-media media)))
        (st/emit! (dwsh/add-shape shape {:skip-edition? true}))))
     shape-id)))

(defn- asset-summary
  [type value]
  (cond-> {:id (some-> (:id value) dm/str)
           :type type
           :name (:name value)}
    (some? (:path value))
    (assoc :path (:path value))

    (= type "media")
    (assoc :width (:width value)
           :height (:height value)
           :mediaType (:mtype value))))

(defn- assets-output-operation
  [request]
  (let [action (obj/get request "action")]
    (case action
      "listAssets"
      (let [asset-type (obj/get request "assetType" "all")
            data       (dsh/lookup-file-data @st/state)
            include?   #(or (= asset-type "all")
                            (= asset-type %))
            result     (cond-> []
                         (include? "colors")
                         (into (map #(asset-summary "color" %)
                                    (vals (:colors data))))

                         (include? "typographies")
                         (into (map #(asset-summary "typography" %)
                                    (vals (:typographies data))))

                         (include? "components")
                         (into (comp
                                (remove :deleted)
                                (map #(asset-summary "component" %)))
                               (vals (:components data)))

                         (include? "media")
                         (into (map #(asset-summary "media" %)
                                    (vals (:media data)))))]
        (clj->js {:data result
                  :context (context-data)}))

      "exportShapes"
      (let [ids       (request-ids request "shapeIds")
            specs     (mapv export-value (obj/get request "exports"))
            state     @st/state
            page-id   (:current-page-id state)
            file-id   (:current-file-id state)
            profile-id (:profile-id state)
            objects   (dsh/lookup-page-objects state)
            events    (ws/get-rcv-stream (:ws-conn state))
            payloads
            (into []
                  (mapcat
                   (fn [id]
                     (let [shape (get objects id)]
                       (map
                        (fn [spec]
                          (merge spec
                                 {:page-id page-id
                                  :file-id file-id
                                  :object-id id
                                  :name (str (:name shape)
                                             (or (:suffix spec) ""))}))
                        specs))))
                  ids)]
        (require-editable!)
        (require-shapes! ids)
        (-> (observable->promise
             (rp/cmd! :export
                      {:exports payloads
                       :profile-id profile-id
                       :cmd :export-shapes
                       :force-multiple true}))
            (.then
             (fn [resource]
               (let [resource-id (:id resource)]
                 (-> (await-export-completion events resource-id)
                     (.then
                      (fn [completed]
                        (clj->js
                         {:data {:action action
                                 :affectedIds (mapv #(dm/str %) ids)
                                 :resourceId (dm/str resource-id)
                                 :resource completed}
                          :context (context-data)})))))))))

      "setExports"
      (let [ids   (request-ids request "shapeIds")
            specs (mapv export-value (obj/get request "exports"))]
        (require-editable!)
        (require-shapes! ids)
        (st/emit! (dwsh/update-shapes ids #(assoc % :exports specs)))
        (-> (wait-until
             #(every? (fn [id]
                        (= specs (:exports (current-shape id))))
                      ids)
             "Updated export presets were not observed.")
            (.then (fn [_]
                     (operation-result
                      {:action action
                       :affectedIds (mapv #(dm/str %) ids)})))))

      "uploadMediaUrl"
      (let [url      (obj/get request "url")
            name     (obj/get request "name" "Media")
            point    (obj/get request "position")
            position (if point
                       (gpt/point (obj/get point "x")
                                  (obj/get point "y"))
                       (gpt/point 0 0))
            file-id  (:current-file-id @st/state)]
        (require-editable!)
        (-> (observable->promise
             (dwm/upload-media-url name file-id url))
            (.then
             (fn [media]
               (let [shape-id (create-media-shape!
                               media position name)]
                 (-> (wait-until #(shape-exists? shape-id)
                                 "Uploaded media shape was not observed.")
                     (.then
                      (fn [_]
                        (clj->js
                         {:data {:action action
                                 :affectedIds [(dm/str shape-id)]
                                 :mediaId (dm/str (:id media))}
                          :context (context-data)})))))))))

      "createSvg"
      (let [svg      (obj/get request "svg")
            name     (obj/get request "name" "SVG")
            point    (obj/get request "position")
            position (if point
                       (gpt/point (obj/get point "x")
                                  (obj/get point "y"))
                       (gpt/point 0 0))
            shape-id (uuid/next)
            file-id  (:current-file-id @st/state)]
        (require-editable!)
        (js/Promise.
         (fn [resolve reject]
           (st/emit! (dwm/create-svg-shape-with-images
                      file-id shape-id name svg position
                      (fn [_]
                        (-> (wait-until
                             #(shape-exists? shape-id)
                             "Created SVG shape was not observed.")
                            (.then
                             (fn [_]
                               (resolve
                                (operation-result
                                 {:action action
                                  :affectedIds
                                  [(dm/str shape-id)]}))))
                            (.catch reject)))
                      reject)))))

      "createMediaComponent"
      (let [media-id  (request-id request "mediaId")
            data      (dsh/lookup-file-data @st/state)
            media     (dm/get-in data [:media media-id])
            name      (obj/get request "name")
            point     (obj/get request "position")
            position  (if point
                        (gpt/point (obj/get point "x")
                                   (obj/get point "y"))
                        (gpt/point 0 0))
            component-id (atom nil)]
        (require-editable!)
        (when-not media
          (throw (js/Error. "The requested media asset was not found.")))
        (run-in-undo-transaction
         (fn []
           (let [shape-id (create-media-shape!
                           media position (or name (:name media)) false)]
             (-> (wait-until #(shape-exists? shape-id)
                             "Media component source shape was not observed.")
                 (.then
                  (fn [_]
                    (st/emit! (dwl/add-component
                               component-id [shape-id]))
                    (wait-until
                     #(when-let [id @component-id]
                        (get (current-components) id))
                     "Created media component was not observed.")))
                 (.then
                  (fn [_]
                    (if (and (string? name) (not-empty name))
                      (do
                        (st/emit! (dwl/rename-component-and-main-instance
                                   @component-id name))
                        (wait-until
                         #(= name (:name (get (current-components)
                                              @component-id)))
                         "Created media component name was not observed."))
                      (js/Promise.resolve true))))
                 (.then
                  (fn [_]
                    (operation-result
                     {:action action
                      :affectedIds [(dm/str @component-id)]
                      :mediaId (dm/str media-id)}))))))))

      (throw
       (js/Error.
        (str "Unsupported assets_output action '" action "'."))))))

(defn- inspect
  [request]
  (let [state  @st/state
        action (obj/get request "action")
        result
        (case action
          "getContext"
          (context-data)

          "getSelection"
          (let [page    (find-page state nil)
                objects (:objects page)
                depth   (request-depth request 1)]
            (into []
                  (keep (fn [id]
                          (when-let [shape (get objects id)]
                            (shape-summary objects shape depth))))
                  (dsh/get-selected-ids state)))

          "listPages"
          (->> (require-file state)
               :data
               :pages
               (mapv (fn [page-id]
                       (let [page (dsh/lookup-page state page-id)]
                         {:id   (dm/str page-id)
                          :name (:name page)}))))

          "getPageTree"
          (let [page    (find-page state (obj/get request "pageId"))
                objects (:objects page)
                root    (get objects uuid/zero)
                depth   (request-depth request 3)]
            {:page {:id   (dm/str (:id page))
                    :name (:name page)}
             :root (shape-summary objects root depth)})

          "getShape"
          (let [[page shape] (find-shape state (obj/get request "shapeId"))
                depth        (request-depth request 1)]
            (shape-summary (:objects page) shape depth))

          "queryShapes"
          (let [page      (find-page state (obj/get request "pageId"))
                objects   (:objects page)
                name-part (some-> (obj/get request "name") str/lower-case)
                types     (some->> (obj/get request "types")
                                   (map keyword)
                                   set)
                limit     (obj/get request "limit" 100)]
            (->> (vals objects)
                 (remove #(= uuid/zero (:id %)))
                 (filter
                  (fn [shape]
                    (and
                     (or (empty? name-part)
                         (str/includes?
                          (str/lower-case (or (:name shape) ""))
                          name-part))
                     (or (empty? types)
                         (contains? types (:type shape))))))
                 (take limit)
                 (mapv #(shape-summary objects % 0))))

          "getRecentChanges"
          (let [limit (obj/get request "limit" 20)
                items (dm/get-in state [:workspace-undo :items])]
            (->> items
                 reverse
                 (take limit)
                 (mapv
                  (fn [entry]
                    {:timestamp (:timestamp entry)
                     :by        (:by entry)
                     :tags      (mapv name (:tags entry))
                     :changes
                     (mapv
                      (fn [change]
                        (cond-> {:type (some-> (:type change) name)}
                          (some? (:id change))
                          (assoc :id (dm/str (:id change)))

                          (some? (:page-id change))
                          (assoc :pageId (dm/str (:page-id change)))))
                      (:redo-changes entry))}))))

          (throw (js/Error. (str "Unsupported inspect action '" action "'."))))]
    (clj->js
     {:data    result
      :context (context-data)})))

(defn- invoke
  [tool request]
  (case tool
    "inspect" (inspect request)
    "document" (document-operation request)
    "shapes" (shapes-operation request)
    "transform" (transform-operation request)
    "hierarchy" (hierarchy-operation request)
    "vector" (vector-operation request)
    "text" (text-operation request)
    "layout" (layout-operation request)
    "styles" (styles-operation request)
    "components" (components-operation request)
    "tokens" (tokens-operation request)
    "prototype" (prototype-operation request)
    "collaboration" (collaboration-operation request)
    "assets_output" (assets-output-operation request)
    (throw (js/Error. (str "Unsupported Penpot tool '" tool "'.")))))

(defn- create-adapter
  []
  #js {:getContext context-snapshot
       :subscribeContextChanges
       (fn [listener]
         (let [watch-key (js/Symbol "hpd-context")]
           (add-watch st/state watch-key
                      (fn [_ _ old-state new-state]
                        (when (not= (context-data old-state)
                                    (context-data new-state))
                          (listener))))
           #(remove-watch st/state watch-key)))
       :invoke     invoke})

(defn init
  []
  (ptk/reify ::init
    ptk/EffectEvent
    (effect [_ _ _]
      (when (and (or (cf/hpd-client-tools-launch-attached?)
                     (some? cf/hpd-client-tools-uri))
                 (nil? @provider))
        (let [instance (createPenpotClientToolsProvider
                        #js {:url        cf/hpd-client-tools-uri
                             :resolveInitialLaunchBootstrap
                             (when (cf/hpd-client-tools-launch-attached?)
                               (fn []
                                 (let [launch-session-id
                                       (.getItem js/sessionStorage
                                                 "hpdos.browserLaunchSessionId")]
                                   (when-not (and (string? launch-session-id)
                                                  (re-matches #"bls_[A-Za-z0-9_-]+"
                                                              launch-session-id))
                                     (throw
                                      (js/Error.
                                       "HPD-OS browser launch session identity is unavailable.")))
                                   (-> (js/fetch
                                        (str "/_hpd/client-tools/bootstrap/"
                                             launch-session-id)
                                        #js {:method      "GET"
                                             :credentials "include"
                                             :cache       "no-store"
                                             :redirect    "error"
                                             :headers     #js {:accept "application/json"}})
                                       (.then (fn [response]
                                                (if (.-ok response)
                                                  (.json response)
                                                  (throw
                                                   (js/Error.
                                                    (str "HPD-OS client-tool bootstrap failed with status "
                                                         (.-status response)))))))))))
                             :instanceId (dm/str cf/session-id)
                             :version    (dm/str cf/version)
                             :adapter    (create-adapter)
                             :onConnectionStateChange
                             (fn [change]
                               (let [status (unchecked-get change "current")
                                     reason (unchecked-get change "reason")
                                     data   {:hint "HPD client-tools connection state changed"
                                             :status status
                                             :attempt (unchecked-get change "attempt")
                                             :reason reason}]
                                 (if (contains? #{"backing_off"
                                                  "closed"
                                                  "revoked"
                                                  "unsupported"}
                                                status)
                                   (log/wrn :hint (:hint data)
                                            :status (:status data)
                                            :attempt (:attempt data)
                                            :reason (:reason data))
                                   (log/dbg :hint (:hint data)
                                            :status (:status data)
                                            :attempt (:attempt data)
                                            :reason (:reason data)))))})]
          (reset! provider instance)
          (-> (.connect instance)
              (.catch (fn [error]
                        (reset! provider nil)
                        (log/err :hint "unable to connect HPD client tools"
                                 :cause error)))))))))

(defn finalize
  []
  (ptk/reify ::finalize
    ptk/EffectEvent
    (effect [_ _ _]
      (when-let [instance @provider]
        (reset! provider nil)
        (-> (.disconnect instance "Penpot workspace finalized.")
            (.catch (fn [error]
                      (log/wrn :hint "unable to disconnect HPD client tools"
                               :cause error))))))))
