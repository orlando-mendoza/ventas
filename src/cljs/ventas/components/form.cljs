(ns ventas.components.form
  "Form stuff"
  (:require
   [cljs.reader :as reader]
   [clojure.string :as str]
   [re-frame.core :as rf]
   [reagent.core :as reagent]
   [cljs-time.format :as f]
   [cljs-time.coerce :as c]
   [ventas.components.amount-input :as amount-input]
   [ventas.components.base :as base]
   [ventas.components.i18n-input :as i18n-input]
   [ventas.session :as session]
   [ventas.i18n :refer [i18n]]
   [ventas.utils.validation :as validation]
   [ventas.components.colorpicker :as colorpicker]
   [reagent.core :as r]))

(def state-key ::state)

(defn get-data [db db-path]
  (get-in db (conj db-path :form)))

(defn assoc-hash [db db-path]
  (assoc-in db
            (conj db-path :form-state :hash)
            (hash (get-data db db-path))))

(defn- normalize-field-key [field]
  (if-not (sequential? field) [field] field))

(rf/reg-event-db
 ::reset
 (fn [db [_ db-path]]
   (assoc-in db (conj db-path :form-state :hash) (hash (gensym)))))

(defn set-field [db db-path field value & {:keys [reset-hash?]}]
  {:pre [(vector? db-path)]}
  (let [form-field (normalize-field-key field)
        validators (get-in db (concat db-path [:form-state :validators]))]
    (as-> db %
          (assoc-in % (concat db-path [:form] form-field)
                    value)
          (assoc-in % (concat db-path [:form-state :validation] form-field)
                    (:infractions (validation/validate validators field value)))
          (if-not reset-hash?
            %
            (assoc-hash % db-path)))))

(rf/reg-event-db
 ::set-field
 (fn [db args]
   (apply set-field db (drop 1 args))))

(rf/reg-event-fx
 ::update-field
 (fn [{:keys [db]} [_ db-path field update-fn]]
   {:pre [(vector? db-path)]}
   (let [field (normalize-field-key field)
         new-value (update-fn (get-in db (concat db-path [:form] field)))]
     {:dispatch [::set-field db-path field new-value]})))

(rf/reg-event-db
 ::populate
 (fn [db [_ {:keys [validators db-path] :as db-path-or-config} data]]
   {:pre [(or (vector? db-path-or-config) (map? db-path-or-config))]}
   (let [db-path (if (map? db-path-or-config)
                   db-path
                   db-path-or-config)]
     (-> db
         (assoc-in (conj db-path :form) data)
         (assoc-in (conj db-path :form-state) {:validators validators
                                               :validation {}})
         (assoc-hash db-path)))))

(rf/reg-sub
 ::data
 (fn [db [_ db-path]]
   (get-data db db-path)))

(defn get-state [db db-path]
  (get-in db (conj db-path :form-state)))

(rf/reg-sub
 ::state
 (fn [db [_ db-path]]
   (get-state db db-path)))

(rf/reg-sub
 ::infractions
 (fn [[_ db-path]]
   (rf/subscribe [::state db-path]))
 (fn [{:keys [validation]}]
   validation))

(rf/reg-sub
 ::field.infractions
 (fn [[_ db-path]]
   (rf/subscribe [::infractions db-path]))
 (fn [infractions [_ db-path key]]
   (get-in infractions (normalize-field-key key))))

(rf/reg-sub
 ::valid?
 (fn [[_ db-path]]
   (rf/subscribe [::state db-path]))
 (fn [{:keys [validation]}]
   (empty? (apply concat (vals validation)))))

(defn form [db-path content]
  (let [{:keys [hash]} @(rf/subscribe [::state db-path])]
    (with-meta content {:key hash})))

(def known-keys #{:value :type :db-path :key :label :width :inline-label :on-change-fx})

(defmulti input (fn [{:keys [type]}] type) :default :default)

(defn- checkbox [{:keys [value toggle db-path key inline-label] :as args}]
  [base/checkbox
   (merge (apply dissoc args known-keys)
          {:toggle toggle
           :checked (or value false)
           :label inline-label
           :on-change #(rf/dispatch [::set-field db-path key (aget %2 "checked")])})])

(defmethod input :toggle [args]
  (checkbox (assoc args :toggle true)))

(defmethod input :checkbox [args]
  (checkbox args))

(defmethod input :radio [{current-value :value :keys [db-path key options] :as args}]
  [:div
   (for [{:keys [value text]} options]
     [base/form-radio
      (merge (apply dissoc args known-keys)
             {:label text
              :value value
              :checked (= value current-value)
              :on-change #(rf/dispatch [::set-field db-path key (aget %2 "value")])})])])

(defmethod input :i18n [{:keys [value db-path key culture]}]
  [i18n-input/input
   {:entity value
    :culture (or culture @(rf/subscribe [::session/culture-id]))
    :on-change #(rf/dispatch [::set-field db-path key %])}])

(defmethod input :i18n-textarea [{:keys [value db-path key culture]}]
  [i18n-input/input
   {:entity value
    :culture (or culture @(rf/subscribe [::session/culture-id]))
    :control :textarea
    :on-change #(rf/dispatch [::set-field db-path key %])}])

(rf/reg-event-fx
 ::on-color-change
 (fn [_ [_ db-path key on-change-fx color]]
   {:dispatch-n [[::set-field db-path key color]
                 (when on-change-fx
                   (conj on-change-fx color))]}))

(defmethod input :color [{:keys [db-path key on-change-fx value]}]
  [:div
   [base/input]
   [colorpicker/colorpicker
    {:on-change [::on-color-change db-path key on-change-fx]
     :value value}]])

(defmethod input :entity [{:keys [value db-path key options on-search-change]
                           {:keys [in out] :or {in identity out identity}} :xform}]
  [base/dropdown {:placeholder (i18n ::search)
                  :selection true
                  :default-value (if value (-> value in pr-str) "")
                  :icon "search"
                  :search (fn [options _] options)
                  :options (map (fn [{:keys [text value]}]
                                  {:text text
                                   :value (pr-str value)})
                                options)
                  :on-change #(rf/dispatch [::set-field db-path key (-> (.-value %2)
                                                                        reader/read-string
                                                                        out)])
                  :on-search-change on-search-change}])

(defmethod input :combobox [{:keys [value db-path key options on-change-fx] :as args}]
  [base/dropdown
   (merge (apply dissoc args known-keys)
          {:fluid true
           :selection true
           :default-value (if value (pr-str value) "")
           :on-change #(let [new-value (reader/read-string (.-value %2))]
                         (rf/dispatch [::set-field db-path key new-value])
                         (when on-change-fx
                           (rf/dispatch (conj on-change-fx new-value))))
           :options (map (fn [{:keys [text value]}]
                           {:text text
                            :value (pr-str value)})
                         options)})])

(defmethod input :tags [{:keys [value db-path key options forbid-additions]
                         {:keys [in out] :or {in identity out identity}} :xform}]
  [base/dropdown
   {:allowAdditions (not forbid-additions)
    :multiple true
    :fluid true
    :search true
    :selection true
    :options (map (fn [{:keys [text value]}]
                    {:text text
                     :value (pr-str value)})
                  options)
    :default-value (->> value
                        (in)
                        (map pr-str)
                        (set))
    :on-change (fn [_ result]
                 (rf/dispatch [::set-field db-path key
                               (->> (.-value result)
                                    (map reader/read-string)
                                    (out)
                                    (set))]))}])

(defmethod input :enum-tags [opts]
  [input (assoc opts :type :tags
                     :xform {:in #(map :db/id %)
                             :out #(map (fn [v] {:db/id v}) %)}
                     :forbid-additions true)])

(defmethod input :amount [{:keys [value db-path key]}]
  [amount-input/input
   {:amount value
    :on-change-fx [::set-field db-path key]}])

(defn- parse-value [type value]
  (cond
    (= type :number) (js/parseInt value 10)
    :else value))

(defn- base-input [html-control {:keys [value db-path key type inline-label on-change-fx xform] :as args}]
  (let [infractions @(rf/subscribe [::field.infractions db-path key])
        {:keys [in out] :or {in identity out identity}} xform]
    [base/input
     {:label inline-label
      :icon true}
     [html-control (merge (apply dissoc args known-keys)
                          {:default-value (if value (in value) "")
                           :type (or type :text)
                           :on-change #(let [new-value (out (parse-value type (-> % .-target .-value)))]
                                         (rf/dispatch [::set-field db-path key new-value])
                                         (when on-change-fx
                                           (rf/dispatch (conj on-change-fx new-value))))})]
     (when (seq infractions)
       [base/popup
        {:content (->> infractions
                       (map #(apply i18n %))
                       (str/join "\n"))
         :trigger (reagent/as-element
                   [base/icon {:class "link"
                               :name "warning sign"}])}])]))

(defmethod input :textarea [data]
  (base-input :textarea data))

(defmethod input :default [data]
  (base-input :input data))

(defmethod input :date [data]
  (base-input :input (assoc data :xform {:in (fn [v] (f/unparse (:date f/formatters) (c/from-date v)))
                                         :out (fn [v]
                                                (and (seq v)
                                                     (c/to-date (f/parse (:date f/formatters) v))))})))

(defn field [{:keys [db-path key label inline-label description width] :as args}]
  (let [infractions @(rf/subscribe [::field.infractions db-path key])]
    [base/form-field
     {:width width
      :error (and (some? infractions) (seq infractions))}
     (when-not inline-label
       (if description
         [base/popup {:trigger (r/as-element [:label label])
                      :content description}]
         [:label label]))
     [input
      (merge args
             {:value (get-in @(rf/subscribe [::data db-path]) (normalize-field-key key))})]]))