(ns ventas.entities.currency
  (:require
   [clojure.spec.alpha :as spec]
   [ventas.database.entity :as entity]
   [ventas.database.generators :as generators]
   [ventas.entities.i18n :as entities.i18n]))

(spec/def :currency/name ::entities.i18n/ref)
(spec/def :currency/plural-name ::entities.i18n/ref)
(spec/def :currency/keyword ::generators/keyword)
(spec/def :currency/symbol ::generators/string)
(spec/def :currency/culture
  (spec/with-gen ::entity/ref #(entity/ref-generator :i18n.culture)))

(spec/def :schema.type/currency
  (spec/keys :req [:currency/name
                   :currency/culture]
             :opt [:currency/keyword
                   :currency/plural-name
                   :currency/symbol]))

(entity/register-type!
 :currency
 {:migrations
  [[:base [{:db/ident :currency/name
            :db/valueType :db.type/ref
            :db/cardinality :db.cardinality/one
            :db/isComponent true
            :ventas/refEntityType :i18n}
           {:db/ident :currency/plural-name
            :db/valueType :db.type/ref
            :db/cardinality :db.cardinality/one
            :db/isComponent true
            :ventas/refEntityType :i18n}
           {:db/ident :currency/symbol
            :db/valueType :db.type/string
            :db/cardinality :db.cardinality/one}
           {:db/ident :currency/keyword
            :db/valueType :db.type/keyword
            :db/cardinality :db.cardinality/one
            :db/unique :db.unique/identity}]]
   [:culture [{:db/ident :currency/culture
               :db/valueType :db.type/ref
               :ventas/refEntityType :i18n.culture
               :db/cardinality :db.cardinality/one}]]]

  :seed-number 0

  :autoresolve? true

  :dependencies
  #{:i18n}

  :fixtures
  ;; @TODO Import from CLDR?
  (fn []
    [{:currency/name (entities.i18n/->entity {:en_US "euro"
                                              :es_ES "euro"})
      :currency/plural-name (entities.i18n/->entity {:en_US "euros"
                                                     :es_ES "euros"})
      :currency/keyword :eur
      :currency/symbol "€"
      :currency/culture [:i18n.culture/keyword :es_ES]}
     {:currency/name (entities.i18n/->entity {:en_US "dollar"
                                              :es_ES "dólar"})
      :currency/plural-name (entities.i18n/->entity {:en_US "dollars"
                                                     :es_ES "dólares"})
      :currency/keyword :usd
      :currency/culture [:i18n.culture/keyword :en_US]
      :currency/symbol "$"}])})
