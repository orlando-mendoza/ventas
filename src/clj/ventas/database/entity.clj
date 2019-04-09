(ns ventas.database.entity
  (:refer-clojure :exclude [find type update])
  (:require
   [clojure.core :as clj]
   [clojure.set :as set]
   [clojure.spec.alpha :as spec]
   [clojure.test.check.generators :as gen]
   [datomic.api :as d]
   [slingshot.slingshot :refer [throw+]]
   [ventas.common.utils :refer [remove-nil-vals map-vals]]
   [ventas.database :as db]
   [ventas.database.generators :as db.generators]
   [ventas.database.schema :as schema]
   [ventas.i18n :refer [i18n]]
   [ventas.utils :as utils :refer [mapm qualify-map-keywords dequalify-keywords into-n qualify-keyword]]
   [clojure.walk :as walk]
   [clojure.string :as str]
   [ventas.database.tx-processor :as tx-processor]))

(defn entity? [entity]
  (when (map? entity)
    (spec/valid? ::entity (select-keys entity #{:schema/type}))))

(spec/def
  ::ref
  (spec/or :eid number?                ;; 17592186046479
           :ident keyword?             ;; :order.status/unpaid
           :entity entity?             ;; to be created ({:schema/type :something ...})
           :lookup-ref db/lookup-ref?  ;; [:i18n.culture/keyword :en_US]
           :pull-eid ::db/pull-eid))   ;; {:db/id 17592186046479}

(spec/def
  ::refs
  (spec/coll-of ::ref))

(spec/def :schema/type
  (spec/or :pull-eid ::db/pull-eid
           :keyword ::db.generators/keyword))

(spec/def ::entity
  (spec/keys :req [:schema/type]))

(spec/def ::attributes sequential?)

(spec/def ::entity-type
  (spec/keys :req-un [::attributes]))

(defn enum-spec [elements]
  (spec/with-gen
   (spec/or :pull-eid ::db/pull-eid
            :element elements)
   #(gen/elements elements)))

(defn kw->type [kw]
  {:pre [(keyword? kw)]}
  (keyword "schema.type" (name kw)))

(defn type->kw [type]
  {:pre [(keyword? type)]}
  (keyword (name type)))

(defn db-migrated? []
  (and (db/connected?) (db/entity :schema/type)))

(defn check-db-migrated!
  "Throws if (not (db-migrated?))"
  []
  (when-not (db-migrated?)
    (throw+ {:type ::database-not-migrated
             :message "The database needs to be migrated"})))

(defonce ^:private registered-types (atom {}))

(defn register-type!
  "Registers an entity type
   Example: (register-entity-type! :user)"
  [kw & [m]]
  {:pre [(keyword? kw) (not (namespace kw)) (or (nil? m) (map? m))]}
  (let [m (or m {})]
    (schema/register-migration!
     (keyword (name kw) "entity-type-ident")
     [{:db/ident (kw->type kw)}])
    (doseq [[key attributes] (:migrations m)]
      (schema/register-migration!
       (keyword (name kw) (name key))
       attributes))
    (swap! registered-types assoc kw m))
  :done)

(defn types
  "Returns all types"
  []
  @registered-types)

(defn type-exists?
  [type]
  {:pre [(keyword? type)]}
  (some? (get @registered-types type)))

(defn type
  "Returns the type of an entity"
  [entity]
  {:pre [(entity? entity)]}
  (let [type (:schema/type entity)]
    (cond
      (keyword? type) (keyword (name type))
      (:db/id type) (keyword (name (db/ident (:db/id type)))))))

(defn type-properties
  "Returns the properties of an entity type"
  [type]
  (get @registered-types type))

(declare default-type)

(defn type-property [entity-type property]
  (or (get (type-properties entity-type) property)
      (property default-type)))

(defn call-type-fn [property entity & args]
  (let [type-fn (type-property (type entity) property)]
    (apply type-fn entity args)))

(defn types-with-property [property]
  (->> @registered-types
       (filter (fn [[_ properties]]
                 (contains? (set (keys properties)) property)))
       (map key)
       (set)))

(defn serialize
  "Transforms an entity into a stripped map, suitable for sending to the outside"
  [entity & [options]]
  {:pre [(entity? entity)]}
  (call-type-fn :serialize entity options))

(defn deserialize
  "The inverse of serialize"
  [type data]
  (let [type-fn (type-property type :deserialize)]
    (type-fn data)))

(defn after-transact [entity]
  {:pre [(entity? entity)]}
  (call-type-fn :after-transact entity))

(defn filter-create [entity]
  {:pre [(entity? entity)]}
  (call-type-fn :filter-create entity))

(defn filter-update [entity data]
  {:pre [(entity? entity) (map? data)]}
  (call-type-fn :filter-update entity data))

(defn spec!
  "Checks that an entity complies with its spec"
  [entity]
  {:pre [(type-exists? (type entity))]}
  (let [spec (:schema/type entity)]
    (when (utils/spec-exists? spec)
      (utils/check! spec entity))))

(defn find
  "Finds an entity by ref (see ::ref spec)
   Returns nil if no entity was found"
  [eid]
  {:pre [(utils/check! ::ref eid)]}
  (let [result (db/touch-eid eid)]
    (and (entity? result) result)))

(defn find-serialize
  "Shortcut for (serialize (find eid) params)"
  [eid & [params]]
  (when-let [entity (find eid)]
    (serialize entity params)))

(defn transaction->entity
  "Returns an entity from a transaction"
  [tx tempid]
  (find (db/resolve-tempid (:tempids tx) tempid)))

(defn- prepare-creation-attrs [pre-entity & [initial-tempid]]
  (mapm (fn [[k v]]
          [k (cond
               (entity? v) (prepare-creation-attrs v)
               (and (coll? v) (entity? (first v))) (map prepare-creation-attrs v)
               :else v)])
        (-> pre-entity
            (clj/update :db/id #(or % initial-tempid (db/tempid)))
            (filter-create))))

(defn lifecycle-create [attrs transact-fn]
  (spec! attrs)
  (let [tempid (db/tempid)
        pre-entity (prepare-creation-attrs attrs tempid)
        tx (transact-fn [pre-entity
                         {:db/id (d/tempid :db.part/tx)
                          :event/kind :entity.create}])]
    (transaction->entity tx tempid)))

(defn create*
  "Creates an entity"
  [attrs]
  (check-db-migrated!)
  (lifecycle-create attrs db/transact))

(defn create
  "Creates an entity from unqualified attributes.
   Example usage:
   (create :user {:name `Joel` :email `test@test.com`})"
  [type attributes]
  (create* (-> attributes
               remove-nil-vals
               (qualify-map-keywords type)
               (assoc :schema/type (kw->type type)))))

(defn fixtures
  [type]
  (when-let [fixtures-fn (type-property type :fixtures)]
    (map (fn [fixture]
           (clj/update fixture :schema/type #(or % (kw->type type))))
         (fixtures-fn))))

(defn autoresolve?
  [type]
  (type-property type :autoresolve?))

(defn dependencies
  [type]
  (or (type-property type :dependencies) #{}))

(defn attributes-by-schema-kv
  "Returns the attributes of an entity whose schema matches the given value for the k attribute"
  [entity k v]
  (->> (keys entity)
       (map db/touch-eid)
       (filter (fn [attr]
                 (= v (get attr k))))
       (map :db/ident)
       (set)))

(defn idents-with-value-type
  "Returns the idents of an entity with the given valueType"
  [entity v]
  (attributes-by-schema-kv entity :db/valueType v))

(defn- autoresolve-ref [options ref]
  (let [ref (if (:db/id ref) (:db/id ref) ref)
        subentity (find ref)]
    (if (and subentity (autoresolve? (type->kw (:schema/type subentity))))
      (serialize subentity options)
      ref)))

(defn- resolve-refs
  "Serializes subentities, resolves references to entities that have autoresolve? enabled,
   and normalizes pull eids."
  [entity & [options]]
  (let [ref-attrs (idents-with-value-type entity :db.type/ref)]
    (mapm (fn [[attr value]]
            [attr (if (and (not (contains? ref-attrs attr))
                           (not (str/starts-with? (name attr) "_")))
                    value
                    (cond
                      (and (sequential? value) (spec/valid? entity? (first value)))
                      (mapv #(serialize % options) value)

                      (spec/valid? entity? value)
                      (serialize value options)

                      (and (sequential? value) (spec/valid? ::ref (first value)))
                      (mapv (partial autoresolve-ref options) value)

                      (spec/valid? ::ref value)
                      (autoresolve-ref options value)

                      :else value))])
          entity)))

(defn- serialize-enums
  "Transforms enum values into maps of :ident and :name"
  [entity & [{:keys [culture]}]]
  (let [enum-attrs (attributes-by-schema-kv entity :ventas/refEntityType :enum)
        culture-kw (some-> culture find :i18n.culture/keyword)
        value-fn (fn [attr value]
                   (if (or (= :schema/type attr)
                           (not (contains? enum-attrs attr)))
                     value
                     {:ident (if (keyword? value)
                               value
                               (some-> value :db/id db/touch-eid :db/ident))
                      :name (i18n culture-kw value)}))]
    (if-not culture-kw
      entity
      (mapm (fn [[attr value]]
              [attr (if (or (set? value) (sequential? value))
                      (map (partial value-fn attr) value)
                      (value-fn attr value))])
            entity))))

(defn default-serialize [entity & [{:keys [keep-type?] :as options}]]
  (let [result (-> entity
                   (serialize-enums options)
                   (resolve-refs options)
                   (dequalify-keywords))]
    (if keep-type?
      (clojure.core/update result :type #(keyword (name %)))
      (dissoc result :type))))

(defn- unresolve* [attr]
  (if (sequential? attr)
    (map unresolve* attr)
    (if (and (map? attr) (:id attr))
      (:id attr)
      attr)))

(defn- unresolve [attrs]
  (map-vals unresolve* attrs))

(defn default-deserialize
  "Inverse of serialize"
  [attributes]
  {:pre [(map? attributes)]}
  (let [id (:id attributes)
        type (:schema/type (find id))]
    (-> attributes
        (unresolve)
        (dissoc :id)
        (dissoc :type)
        (qualify-map-keywords type)
        (assoc :schema/type type)
        (assoc :db/id id))))

(def ^:private default-type
  {:attributes []
   :serialize #'default-serialize
   :deserialize #'default-deserialize
   :filter-create identity
   :filter-update (fn [_ data] data)
   :after-transact (constantly true)})

(defn default-attr [attr-name]
  (get default-type attr-name))

(defn- normalize-refs [refs]
  (when-not (map? refs)
    (map db/normalize-ref refs)))

(defn- enum-retractions-helper
  "Retracts no longer present enum values.
   Assuming this is in the db:
   {:db/id 17592186045691
    :user/favorites [17592186045648 17592186045679]}

   (enum-retractions {:db/id 17592186045691
                      :user/favorites [17592186045648]})
   => ([:db/retract 17592186045691 :user/favorites 17592186045679])"
  [new-values]
  (let [old-values (find (:db/id new-values))
        ident->values-set (fn [ident values]
                            (->> (get values ident)
                                 (normalize-refs)
                                 (set)
                                 (not-empty)))]
    (->> (set (keys new-values))
         (map db/touch-eid)
         (filter #(and (= (:db/cardinality %) :db.cardinality/many)
                       (= (:db/valueType %) :db.type/ref)))
         (map :db/ident)
         (mapcat (fn [ident]
                   (let [new-vals (ident->values-set ident new-values)
                         old-vals (ident->values-set ident old-values)
                         diff (set/difference old-vals new-vals)]
                     (map (fn [val-to-retract]
                            [:db/retract (:db/id new-values) ident val-to-retract])
                          diff)))))))

(defn- enum-retractions [new-values]
  (let [retractions (atom [])]
    (walk/prewalk (fn [x]
                    (when (:db/id x)
                      (swap! retractions into (enum-retractions-helper x)))
                    x)
                  new-values)
    @retractions))

(defn lifecycle-update [{:db/keys [id] :as attrs} {:keys [append?]} transact-fn]
  (let [entity (find id)
        attrs (filter-update entity attrs)]
    (transact-fn (into-n
                  [attrs]
                  (when-not append?
                    (enum-retractions attrs))
                  [{:db/id (d/tempid :db.part/tx)
                    :event/kind :entity.update}]))
    (find id)))

(defn update*
  "Updates an entity.
   Example usage:
   (update* {:db/id 1234567
             :user/first-name `Other name`})"
  [attrs & opts]
  {:pre [(:db/id attrs)]}
  (check-db-migrated!)
  (lifecycle-update attrs opts db/transact))

(defn update
  "Updates an entity from unqualified attributes.
   Example usage:
   (update {:id 1234567 :name `Other name`})"
  [{:keys [id] :as attrs}]
  {:pre [(map? attrs) id]}
  (let [entity (find id)]
    (update* (-> attrs
                 (dissoc :id)
                 (qualify-map-keywords (type entity))
                 (assoc :db/id (:db/id entity))))))

(defn delete [ref]
  "Deletes an entity by ref (see ::ref spec)"
  (let [entity (find ref)]
    (db/retract-entity ref)
    (db/transact [[:db.fn/retractEntity ref]
                  {:db/id (d/tempid :db.part/tx)
                   :event/kind :entity.delete}])))

(defn upsert
  [type {:keys [id] :as attributes}]
  {:pre [(keyword? type) (map? attributes)]}
  (if id
    (update attributes)
    (create type attributes)))

(defn upsert*
  [{:db/keys [id] :as attributes}]
  {:pre [(map? attributes)]}
  (if id
    (update* attributes)
    (create* attributes)))

(defn dates
  "First and last dates associated with an eid"
  [eid]
  (let [query '[:find (min ?tx-time) (max ?tx-time)
                :in $ ?e
                :where [?e _ _ ?tx _]
                       [?tx :db/txInstant ?tx-time]]
        result (first (d/q query (db/history) eid))]
    {:created-at (get result 0)
     :updated-at (get result 1)}))

(defn- filters->wheres* [type filters]
  (->> filters
       ;; autoqualify attributes, handle :any
       (map (fn [[attribute value]]
              (let [attribute (if (namespace attribute)
                                attribute
                                (qualify-keyword attribute type))
                    value (if (= value :any) '_ value)]
                ['?id attribute value])))
       (mapcat (fn [[var attribute value]]
                 (cond
                   (set? value)
                   (for [item value]
                     [var attribute item])

                   (and (sequential? value) (= :in (first value)))
                   (let [sym (symbol (str "?" (gensym)))]
                     [[var attribute sym]
                      [(list 'contains? (second value) sym)]])

                   :else  [[var attribute value]])))))

(defn filters->wheres
  "Generates `:where` clauses"
  [type filters]
  (filters->wheres*
   type
   (as-> filters filters
         (filter (fn [[k v]]
                   (or (and (not (coll? v)) v)
                       (seq v)))
                 filters)
         (if (empty? filters)
           {:schema/type (kw->type type)}
           filters))))

(defn query
  "Performs a high-level query.
   Accepts optional `wheres` clauses"
  [type & [filters]]
  (check-db-migrated!)
  (map #(find (:id %))
       (db/nice-query {:find '[?id]
                       :where (filters->wheres type filters)})))

(defn mass-delete
  "Deletes all entities of the given type"
  [type]
  (doseq [entity (query type)]
    (delete (:db/id entity))))

(defn query-one
  "(first (query))"
  [type & [filters]]
  (first (query type filters)))

(defn generate*
  "Generates one sample of a given entity type"
  [type]
  (let [db-type (kw->type type)]
    (-> (spec/gen db-type)
        (gen/generate)
        (assoc :schema/type db-type))))

(defn generate
  "Generates n samples of a given entity type"
  [type & [n]]
  (let [n (or n 1)]
    (map generate* (repeat n type))))

(defn ref-generator [type & {:keys [new?]}]
  (gen/elements
   (if new?
     (generate type)
     (let [entities (query type)]
       (if (seq entities)
         (map :db/id entities)
         (generate type))))))

(defn refs-generator [type & {:keys [new?]}]
  (gen/vector (ref-generator type :new? new?)))

(defn ref-spec [type]
  (spec/with-gen ::ref (partial ref-generator type)))

(defn refs-spec [type]
  (spec/with-gen ::refs (partial refs-generator type)))

(defn find-recursively [eid]
  "Finds the given eid or lookup ref, and all the refs inside it"
  (let [entity (find eid)
        ref-idents (idents-with-value-type entity :db.type/ref)]
    (mapm (fn [[k v]]
            [k (if (and (contains? ref-idents k) (not (keyword? v)))
                 (if (sequential? v)
                   (map find-recursively v)
                   (find-recursively v))
                 v)])
          entity)))

(defn- process-report [report]
  (let [eid->tx-type (->> (:tx-data report)
                      (map db/datom->map)
                      (group-by :e)
                      (map (fn [[eid datoms]]
                             [eid (case (set (keys (group-by :added datoms)))
                                    #{true false} :updated
                                    #{false} :deleted
                                    #{true} :added)])))]
    (doseq [[eid tx-type] eid->tx-type]
      (when (contains? #{:updated :added} tx-type)
        (when-let [entity (find eid)]
          (after-transact entity))))))

(tx-processor/add-callback! ::process-report process-report)