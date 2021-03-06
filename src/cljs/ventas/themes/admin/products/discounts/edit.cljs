(ns ventas.themes.admin.products.discounts.edit
  (:require
   [re-frame.core :as rf]
   [ventas.components.base :as base]
   [ventas.components.form :as form]
   [ventas.components.notificator :as notificator]
   [ventas.events :as events]
   [ventas.server.api.admin :as api.admin]
   [ventas.i18n :refer [i18n]]
   [ventas.themes.admin.common :as admin.common]
   [ventas.themes.admin.skeleton :as admin.skeleton]
   [ventas.routes :as routes]
   [ventas.utils.ui :as utils.ui])
  (:require-macros
   [ventas.utils :refer [ns-kw]]))

(def state-key ::state)

(rf/reg-event-fx
 ::submit
 (fn [{:keys [db]} _]
   {:dispatch [::api.admin/admin.entities.save
               {:params (get-in db [state-key :form])
                :success ::submit.next}]}))

(rf/reg-event-fx
 ::submit.next
 (fn [_ _]
   {:dispatch [::notificator/notify-saved]
    :go-to [:admin.products.discounts]}))

(rf/reg-event-fx
 ::init
 (fn [_ _]
   {:dispatch-n [[::events/enums.get :discount.amount.kind]
                 (let [id (routes/ref-from-param :id)]
                   (if-not (pos? id)
                     [::form/populate [state-key] {:schema/type :schema.type/discount}]
                     [::api.admin/admin.entities.pull
                      {:params {:id id}
                       :success ::init.next}]))]}))

(rf/reg-event-fx
 ::init.next
 (fn [_ [_ data]]
   {:dispatch-n [[::form/populate [state-key] data]
                 (when-let [product (get-in data [:discount/product :db/id])]
                   [::api.admin/admin.entities.find-serialize
                    {:params {:id product}
                     :success [:db [state-key :product]]}])]}))

(defn- field [{:keys [key] :as args}]
  [form/field (merge args
                     {:db-path [state-key]
                      :label (i18n (ns-kw (if (sequential? key)
                                            (first key)
                                            key)))})])

(defn- content []
  [form/form [state-key]

   [base/form {:on-submit (utils.ui/with-handler #(rf/dispatch [::submit]))}

    [base/segment {:color "orange"
                   :title (i18n ::page)}

     [field {:key :discount/name
             :type :i18n}]

     [field {:key :discount/code
             :type :text}]

     [field {:key :discount/active?
             :type :toggle}]

     [field {:key :discount/max-uses-per-customer
             :type :number}]

     [field {:key :discount/max-uses
             :type :number}]

     [field {:key :discount/free-shipping?
             :type :toggle}]

     [admin.common/entity-search-field
      {:label (i18n ::product)
       :db-path [state-key]
       :key [:discount/product :db/id]
       :attrs #{:product/name}
       :selected-option @(rf/subscribe [:db [state-key :product]])}]

     [field {:key :discount/amount
             :type :amount}]

     [field {:key :discount/amount.tax-included?
             :type :toggle}]

     [field {:key [:discount/amount.kind :db/id]
             :type :combobox
             :options @(rf/subscribe [:db [:enums :discount.amount.kind]])}]

     [base/form-button {:type "submit"} (i18n ::submit)]]]])

(defn page []
  [admin.skeleton/skeleton
   [:div.admin__default-content.admin-discounts-edit__page
    [content]]])

(routes/define-route!
 :admin.products.discounts.edit
 {:name ::page
  :url [:id "/edit"]
  :component page
  :init-fx [::init]})
