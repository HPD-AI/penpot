;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns frontend-tests.data.workspace-hpd-test
  (:require
   [app.common.data :as d]
   [app.common.types.tokens-lib :as tokens]
   [app.common.uuid :as uuid]
   [app.main.data.workspace.hpd :as hpd]
   [app.main.data.workspace.tokens.propagation :as propagation]
   [app.main.repo :as rp]
   [app.main.store :as st]
   [app.util.object :as obj]
   [app.util.websocket :as ws]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
   [potok.v2.core :as ptk]))

(def ^:private emitted-events (atom []))
(def ^:private export-call (atom nil))
(def ^:private export-resource (atom nil))
(def ^:private propagation-options (atom nil))
(def ^:private propagation-event ::propagation)

(defn- capture-emit!
  ([] nil)
  ([event]
   (swap! emitted-events conj event)
   (when (= propagation-event event)
     ((:on-complete @propagation-options))))
  ([event & events]
   (swap! emitted-events into (cons event events))))

(defn- export-command
  ([command params]
   (reset! export-call {:command command :params params})
   (rx/of @export-resource))
  ([command params _options]
   (export-command command params)))

(defn- propagate-tokens
  [token-names on-complete on-error]
  (reset! propagation-options
          {:token-names token-names
           :on-complete on-complete
           :on-error on-error})
  propagation-event)

(defn- workspace-state
  ([]
   (workspace-state true))
  ([can-edit?]
   (let [file-id  (uuid/next)
         page-id  (uuid/next)
         shape-id (uuid/next)
         shape    {:id        shape-id
                   :type      :rect
                   :name      "Card"
                   :parent-id uuid/zero
                   :frame-id  uuid/zero
                   :x         10
                   :y         20
                   :width     100
                   :height    80}
         root     {:id uuid/zero :type :root :name "Root" :shapes [shape-id]}
         page     {:id page-id
                   :name "Page 1"
                   :objects {uuid/zero root shape-id shape}}]
     {:current-file-id file-id
      :current-page-id page-id
      :permissions {:can-edit can-edit?}
      :workspace-global {:read-only? (not can-edit?)}
      :workspace-local {:selected (d/ordered-set shape-id)}
      :workspace-undo {:index 0 :items []}
      :files {file-id {:id file-id
                       :revn 7
                       :data {:pages [page-id]
                              :pages-index {page-id page}
                              :tokens-lib (tokens/make-tokens-lib)
                              :colors {}
                              :typographies {}
                              :components {}
                              :media {}}}}})))

(t/deftest native-context-subscription-emits-only-semantic-context-changes
  (let [state     (atom (workspace-state))
        adapter   (with-redefs [st/state state]
                    (#'hpd/create-adapter))
        changes   (atom 0)
        subscribe (obj/get adapter "subscribeContextChanges")
        dispose   (with-redefs [st/state state]
                    (subscribe #(swap! changes inc)))]
    (swap! state assoc :unrelated-state true)
    (t/is (zero? @changes))

    (swap! state assoc :current-page-id (uuid/next))
    (t/is (= 1 @changes))

    (with-redefs [st/state state]
      (dispose))
    (swap! state assoc-in [:permissions :can-edit] false)
    (t/is (= 1 @changes))))

(t/deftest native-inspection-returns-live-selection-and-context
  (let [state  (atom (workspace-state))
        result (with-redefs [st/state state]
                 (#'hpd/invoke "inspect"
                               #js {:action "getSelection" :depth 0}))
        result (js->clj result :keywordize-keys true)]
    (t/is (= "Card" (get-in result [:data 0 :name])))
    (t/is (= (str (:current-page-id @state))
             (get-in result [:context :pageId])))
    (t/is (= "7:0:0:none"
             (get-in result [:context :appStateVersion])))))

(t/deftest native-undo-transaction-closes-when-operation-throws
  (reset! emitted-events [])
  (with-redefs [st/emit! capture-emit!]
    (t/is (thrown-with-msg?
           js/Error
           #"mutation failed"
           (#'hpd/emit-in-undo-transaction!
            #(throw (js/Error. "mutation failed"))))))
  (t/is (= [:app.main.data.workspace.undo/start-undo-transaction
            :app.main.data.workspace.undo/commit-undo-transaction]
           (mapv ptk/type @emitted-events))))

(t/deftest native-destructive-mutation-honors-read-only-state
  (let [state    (atom (workspace-state false))
        shape-id (-> @state :workspace-local :selected first)]
    (with-redefs [st/state state]
      (t/is (thrown-with-msg?
             js/Error
             #"read-only"
             (#'hpd/invoke "shapes"
                           #js {:action "delete"
                                :shapeIds #js [(str shape-id)]}))))))

(t/deftest native-token-and-component-preconditions-use-live-document-data
  (let [state        (atom (workspace-state))
        shape-id     (-> @state :workspace-local :selected first)
        token-result (with-redefs [st/state state]
                       (#'hpd/invoke "tokens" #js {:action "list"}))
        token-result (js->clj token-result :keywordize-keys true)]
    (t/is (= [] (get-in token-result [:data :sets])))
    (with-redefs [st/state state]
      (t/is (thrown-with-msg?
             js/Error
             #"Only component instances"
             (#'hpd/invoke "components"
                           #js {:action "reset"
                                :shapeIds #js [(str shape-id)]}))))))

(t/deftest native-token-propagation-forwards-the-bounded-token-set
  (t/async
    done
    (let [state    (workspace-state)
          file-id  (:current-file-id state)
          set-id   (uuid/next)
          token    (tokens/make-token
                    :name "spacing.md"
                    :type :spacing
                    :value 16)
          library  (-> (tokens/make-tokens-lib)
                       (tokens/add-set
                        (tokens/make-token-set :id set-id :name "Core"))
                       (tokens/add-token set-id token))
          state    (atom (assoc-in state
                                   [:files file-id :data :tokens-lib]
                                   library))]
      (reset! emitted-events [])
      (reset! propagation-options nil)
      (with-redefs [st/state state
                    st/emit! capture-emit!
                    propagation/propagate-selected-workspace-tokens propagate-tokens]
        (-> (#'hpd/invoke
             "tokens"
             #js {:action "propagate"
                  :tokenNames #js ["spacing.md"]})
            (.then
             (fn [result]
               (let [result (js->clj result :keywordize-keys true)]
                 (t/is (= ["spacing.md"]
                          (:token-names @propagation-options)))
                 (t/is (= ["spacing.md"]
                          (get-in result [:data :tokenNames])))
                 (t/is (= [propagation-event] @emitted-events)))
               (done)))
            (.catch
             (fn [error]
               (t/is false (str "unexpected propagation error: " error))
               (done))))))))

(t/deftest native-background-export-returns-the-runtime-resource
  (t/async
    done
    (let [state       (atom (workspace-state))
          shape-id    (-> @state :workspace-local :selected first)
          resource-id (uuid/next)]
      (reset! export-resource {:id resource-id :filename "card.png"})
      (reset! export-call nil)
      (with-redefs [st/state state
                    rp/cmd! export-command
                    ws/get-rcv-stream
                    (fn [_]
                      (rx/of
                       {:type :message
                        :payload
                        {:type :export-update
                         :resource-id resource-id
                         :status "ended"
                         :filename "card.png"
                         :mtype "image/png"
                         :resource-uri
                         "http://guest-private.internal/export/card.png"}}))]
        (-> (#'hpd/invoke
             "assets_output"
             #js {:action "exportShapes"
                  :shapeIds #js [(str shape-id)]
                  :exports #js [#js {:type "png" :scale 2}]})
            (.then
             (fn [result]
               (let [result (js->clj result :keywordize-keys true)]
                 (t/is (= :export (:command @export-call)))
                 (t/is (= shape-id
                          (get-in @export-call
                                  [:params :exports 0 :object-id])))
                 (t/is (= (str resource-id)
                          (get-in result [:data :resourceId])))
                 (t/is (= "card.png"
                          (get-in result [:data :resource :filename])))
                 (t/is (= "/export/card.png"
                          (get-in result [:data :resource :path]))))
               (done)))
            (.catch
             (fn [error]
               (t/is false (str "unexpected export error: " error))
               (done))))))))
