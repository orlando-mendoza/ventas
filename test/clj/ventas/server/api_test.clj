(ns ventas.server.api-test
  (:require
   [clojure.set :as set]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [ventas.auth :as auth]
   [ventas.core]
   [ventas.database :as db]
   [ventas.database.entity :as entity]
   [ventas.database.seed :as seed]
   [ventas.entities.configuration :as entities.configuration]
   [ventas.entities.i18n :as entities.i18n]
   [ventas.search :as search]
   [ventas.server.api :as sut]
   [ventas.server.ws :as server.ws]
   [ventas.test-tools :as test-tools]
   [ventas.entities.user :as user])
  (:import [org.elasticsearch.client RestClient]))

(use-fixtures :once #(test-tools/with-test-context
                       (seed/seed :minimal? true)
                       (%)))

(defn- create-test-category! [& [kw]]
  (entity/create :category
                 {:keyword (or kw :test-category)
                  :name (entities.i18n/->entity {:en_US "Test category"})}))

(deftest categories-get
  (let [category (create-test-category!)]
    (is (= (entity/serialize category {:culture [:i18n.culture/keyword :en_US]})
           (-> (server.ws/call-request-handler {:name ::sut/categories.get
                                                :params {:id :test-category}}
                                               {})
               :data)))
    (is (= ::sut/invalid-ref
           (-> (server.ws/call-request-handler {:name ::sut/categories.get
                                                :params {:id :DOES-NOT-EXIST}}
                                               {})
               :data
               :type)))
    (is (= ::sut/category-not-found
           (-> (server.ws/call-request-handler {:name ::sut/categories.get
                                                :params {:id 1112984712}}
                                               {})
               :data
               :type)))))

(deftest categories-list
  (doseq [entity (entity/query :category)]
    (entity/delete (:db/id entity)))
  (let [categories (mapv create-test-category! [:test-1 :test-2])]
    (is (= (->> categories
                (map #(entity/serialize % {:culture [:i18n.culture/keyword :en_US]}))
                (map #(dissoc % :id))
                (sort-by :keyword))
           (->> (server.ws/call-request-handler {:name ::sut/categories.list}
                                               {})
                :data
                (map #(dissoc % :id))
                (sort-by :keyword))))))

(deftest configuration-get
  (testing "sets the configuration"
    (let [data {:stripe.publishable-key "TEST-KEY"
                :site.title "TEST-TITLE"}]
      (doseq [[k v] data]
        (entities.configuration/set! k v))
      (is (= data
             (-> (server.ws/call-request-handler {:name ::sut/configuration.get
                                                  :params #{:stripe.publishable-key
                                                            :site.title}}
                                                 {})
                 :data)))))
  (testing "overwrites the configuration if exists; creates a new one otherwise"
    (let [data {:site.title "Another site title"
                :made.up "Completely made up"}]
      (doseq [[k v] data]
        (entities.configuration/set! k v))
      (is (= data
             (-> (server.ws/call-request-handler {:name ::sut/configuration.get
                                                  :params #{:site.title
                                                            :made.up}}
                                                 {})
                 :data))))))

(deftest entities-find
  (let [category (create-test-category!)]
    (testing "by eid"
      (is (= (entity/serialize category {:culture [:i18n.culture/keyword :en_US]})
             (-> (server.ws/call-request-handler {:name ::sut/entities.find
                                                  :params {:id (:db/id category)}})
                 :data))))
    (testing "by lookup-ref"
      (is (= (entity/serialize category {:culture [:i18n.culture/keyword :en_US]})
             (-> (server.ws/call-request-handler {:name ::sut/entities.find
                                                  :params {:id [:category/keyword (:category/keyword category)]}})
                 :data))))
    (testing "by slug"
      (let [slug (entity/serialize (entity/find (:ventas/slug category))
                                   {:culture [:i18n.culture/keyword :en_US]})]
        (is (= (entity/serialize category {:culture [:i18n.culture/keyword :en_US]})
               (-> (server.ws/call-request-handler {:name ::sut/entities.find
                                                    :params {:id slug}})
                   :data)))))
    (testing "unexistent id"
      (is (= ::sut/entity-not-found
             (-> (server.ws/call-request-handler {:name ::sut/entities.find
                                                  :params {:id 1}})
                 :data
                 :type))))))

(deftest enums-get
  (is (= #{:ident :id :name}
         (-> (server.ws/call-request-handler {:name ::sut/enums.get
                                              :params {:type :order.status}})
             :data
             first
             keys
             set))))

(deftest i18n-cultures-list
  (let [fixtures (->> (ventas.database.entity/fixtures :i18n.culture)
                      (map #(dissoc % :schema/type))
                      (set))]
    (is (= fixtures
           (->> (server.ws/call-request-handler {:name ::sut/i18n.cultures.list})
                :data
                (map #(dissoc % :id))
                (map #(set/rename-keys % {:keyword :i18n.culture/keyword
                                          :name :i18n.culture/name}))
                (set))))))

(deftest image-sizes-list
  (doseq [entity (entity/query :image-size)]
    (entity/delete (:db/id entity)))
  (let [image-size (entity/create :image-size {:algorithm :image-size.algorithm/always-resize
                                               :entities #{:schema.type/product
                                                           :schema.type/category}
                                               :keyword :test-size
                                               :height 65
                                               :width 40})]
    (is (= {:test-size (-> (entity/serialize image-size {:culture [:i18n.culture/keyword :en_US]})
                           (dissoc :keyword))}
           (:data (server.ws/call-request-handler {:name ::sut/image-sizes.list}))))))

(def test-taxonomies
  [{:schema/type :schema.type/product.taxonomy
    :product.taxonomy/keyword :test-term-a
    :product.taxonomy/name (entities.i18n/->entity {:en_US "test-taxonomy-a"})}
   {:schema/type :schema.type/product.taxonomy
    :product.taxonomy/keyword :test-term-b
    :product.taxonomy/name (entities.i18n/->entity {:en_US "test-taxonomy-b"})}])

(def test-terms
  [{:schema/type :schema.type/product.term
    :product.term/keyword :test-term-a-1
    :product.term/name (entities.i18n/->entity {:en_US "test-term-a-1"})
    :product.term/taxonomy [:product.taxonomy/keyword :test-term-a]}

   {:schema/type :schema.type/product.term
    :product.term/keyword :test-term-a-2
    :product.term/name (entities.i18n/->entity {:en_US "test-term-a-2"})
    :product.term/taxonomy [:product.taxonomy/keyword :test-term-a]}

   {:schema/type :schema.type/product.term
    :product.term/keyword :test-term-b-1
    :product.term/name (entities.i18n/->entity {:en_US "test-term-b-1"})
    :product.term/taxonomy [:product.taxonomy/keyword :test-term-b]}

   {:schema/type :schema.type/product.term
    :product.term/keyword :test-term-b-2
    :product.term/name (entities.i18n/->entity {:en_US "test-term-b-2"})
    :product.term/taxonomy [:product.taxonomy/keyword :test-term-b]}])

(def test-products
  [{:schema/type :schema.type/product
    :product/keyword :server-api-product
    :product/name (entities.i18n/->entity {:en_US "Example product"})
    :product/variation-terms #{[:product.term/keyword :test-term-a-1]
                               [:product.term/keyword :test-term-a-2]
                               [:product.term/keyword :test-term-b-1]
                               [:product.term/keyword :test-term-b-2]}
    :product/price {:schema/type :schema.type/amount
                    :amount/currency [:currency/keyword :eur]
                    :amount/value 15.6M}}])

(def test-product-variations
  [{:schema/type :schema.type/product.variation
    :product.variation/parent [:product/keyword :server-api-product]
    :product.variation/default? true
    :product.variation/terms #{[:product.term/keyword :test-term-a-1]
                               [:product.term/keyword :test-term-b-1]}}
   {:schema/type :schema.type/product.variation
    :product.variation/parent [:product/keyword :server-api-product]
    :product.variation/default? false
    :product.variation/terms #{[:product.term/keyword :test-term-a-2]
                               [:product.term/keyword :test-term-b-2]}}])

(deftest products-get
  (doseq [entity (concat test-taxonomies test-terms test-products test-product-variations)]
    (entity/create* entity))
  (testing "terms for default variation"
    (is (= #{(db/normalize-ref [:product.term/keyword :test-term-a-1])
             (db/normalize-ref [:product.term/keyword :test-term-b-1])}
           (->> (server.ws/call-request-handler {:name ::sut/products.get
                                                 :params {:id [:product/keyword :server-api-product]}})
                :data
                :variation
                (map :selected)
                (map :id)
                (set)))))
  (testing "terms for non-default variation"
    (is (= #{(db/normalize-ref [:product.term/keyword :test-term-a-2])
             (db/normalize-ref [:product.term/keyword :test-term-b-2])}
           (->> (server.ws/call-request-handler {:name ::sut/products.get
                                                 :params {:id [:product/keyword :server-api-product]
                                                          :terms #{[:product.term/keyword :test-term-a-2]
                                                                   [:product.term/keyword :test-term-b-2]}}})
                :data
                :variation
                (map :selected)
                (map :id)
                (set)))))
  (testing "terms for nonexisting variation"
    (is (= #{(db/normalize-ref [:product.term/keyword :test-term-a-2])
             (db/normalize-ref [:product.term/keyword :test-term-b-1])}
           (->> (server.ws/call-request-handler {:name ::sut/products.get
                                                 :params {:id [:product/keyword :server-api-product]
                                                          :terms #{[:product.term/keyword :test-term-a-2]
                                                                   [:product.term/keyword :test-term-b-1]}}})
                :data
                :variation
                (map :selected)
                (map :id)
                (set))))))

(deftest products-list
  (testing "works without passing params"
    (is (:success (server.ws/call-request-handler {:name ::sut/products.list}
                                                  {})))))

(deftest products-aggregations
  (testing "spec does not fail when not passing params"
    (let [result (-> (server.ws/call-request-handler {:name ::sut/products.aggregations}
                                                     {})
                     :data
                     :type)]
      (if (= (type search/elasticsearch) RestClient)
        (is (not result))
        (is (= ::search/elasticsearch-error result)))))
  (doseq [entity (concat test-taxonomies test-terms test-products test-product-variations)]
    (entity/create* entity))
  (entity/create :category {:name (entities.i18n/->entity {:en_US "Test category"})
                            :keyword :test-category})
  (let [params (atom nil)]
    (with-redefs [search/search (fn [& args] (reset! params args))]
      (server.ws/call-request-handler {:name ::sut/products.aggregations
                                       :params {:filters {:categories #{:test-category}
                                                          :price {:min 0
                                                                  :max 10}
                                                          :terms #{:test-term-a-2}
                                                          :name "Example"}}})
      (is (= {:_source false
              :from 0
              :query {:bool {:must [{:term {:schema/type ":schema.type/product"}}
                                    {:bool {:should [{:bool {:should [{:term {:product/terms (db/normalize-ref [:product.term/keyword :test-term-a-2])}}
                                                                      {:term {:product/variation-terms (db/normalize-ref [:product.term/keyword :test-term-a-2])}}]}}]}}
                                    {:term {:product/categories (db/normalize-ref [:category/keyword :test-category])}}
                                    {:range {:product/price {:gte 0 :lte 10}}}
                                    {:match {:product/name.en_US "Example"}}]}}
              :size 10}
             (first @params))))))

(deftest users-register
  (server.ws/call-request-handler {:name ::sut/users.register
                                   :params {:email "test@test.com"
                                            :password "test"
                                            :name "Test user"}})
  (is (= {:schema/type :schema.type/user
          :user/email "test@test.com"
          :user/first-name "Test"
          :user/last-name "user"
          :user/culture (:db/id (db/entity user/default-culture))}
         (-> (entity/find [:user/email "test@test.com"])
             (dissoc :user/password)
             (dissoc :db/id)))))

(def test-user
  {:user/first-name "Test"
   :user/last-name "User"
   :user/email "test2@test.com"
   :user/status :user.status/active
   :user/password "test"
   :user/culture [:i18n.culture/keyword :en_US]
   :schema/type :schema.type/user})

(deftest users-login
  (let [user (entity/create* test-user)]
    (let [session (atom nil)]
      (testing "unexistent user"
        (is (not (:success (server.ws/call-request-handler {:name ::sut/users.login
                                                            :params {:email "doesnotexist@test.com"
                                                                     :password "test"}}
                                                           {:session session}))))
        (is (= nil @session))))
    (let [session (atom nil)]
      (testing "valid credentials"
        (is (= {:token (auth/user->token user)
                :user {:culture (entity/find-serialize (:user/culture user))
                       :email "test2@test.com"
                       :first-name "Test"
                       :last-name "User"
                       :name "Test User"
                       :status :user.status/active}}
               (-> (server.ws/call-request-handler {:name ::sut/users.login
                                                    :params {:email "test2@test.com"
                                                             :password "test"}}
                                                   {:session session})
                   :data
                   (update :user #(dissoc % :id)))))

        (is (= {:user (db/normalize-ref [:user/email "test2@test.com"])}
               @session))))
    (let [session (atom nil)]
      (testing "invalid credentials"
        (is (= ::sut/invalid-credentials
               (-> (server.ws/call-request-handler {:name ::sut/users.login
                                                    :params {:email "test2@test.com"
                                                             :password "INVALID"}}
                                                   {:session session})
                   :data
                   :type)))
        (is (= nil @session))))))

(comment
 (register-endpoint!
  :users.session
  {:spec {(opt :token) (maybe ::string)}}
  (fn [{:keys [params]} {:keys [session]}]
    (if-let [user (get-user session)]
      {:user (entity/serialize user)}
      (if-let [user (some->> (:token params)
                             auth/token->user)]
        (do
          (set-user session user)
          {:user (entity/serialize user)})
        (let [{:keys [user token]} (create-unregistered-user)]
          (set-user session user)
          {:user (entity/serialize user)
           :token token}))))))

(defn- run-temporary-user-test [token]
  (let [session (atom nil)
        result (:data (server.ws/call-request-handler {:name ::sut/users.session
                                                       :params {:token token}}
                                                      {:session session}))]
    (is (= (:token result)
           (auth/user->token (entity/find (get-in result [:user :id])))))
    (is (= :user.status/unregistered
           (get-in result [:user :status])))
    (is (= {:user (get-in result [:user :id])}
           @session))))

(deftest users-session
  (let [user (entity/create* test-user)]
    (testing "user in session"
      (let [session (atom {:user (:db/id user)})]
        (is (= {:user (entity/serialize user)}
               (:data (server.ws/call-request-handler {:name ::sut/users.session
                                                       :params {}}
                                                      {:session session}))))
        (is (= {:user (:db/id user)}
               @session))))
    (testing "user not in session but token present"
      (let [session (atom nil)]
        (is (= {:user (entity/serialize user)}
               (:data (server.ws/call-request-handler {:name ::sut/users.session
                                                       :params {:token (auth/user->token user)}}
                                                      {:session session}))))
        (is (= {:user (:db/id user)}
               @session))))
    (testing "user not in session, token present but invalid"
      (run-temporary-user-test "WELL THIS DOES NOT LOOK LIKE A TOKEN, DOES IT?"))
    (testing "user not in session, no token"
      (run-temporary-user-test nil))))

(deftest users-logout
  (let [session (atom {:user true})]
    (server.ws/call-request-handler {:name ::sut/users.logout}
                                    {:session session})
    (is (not (:user @session)))))

(deftest states-list
  (doseq [entity (entity/query :state)]
    (entity/delete (:db/id entity)))
  (entity/create :country {:name (entities.i18n/->entity {:en_US "Test country"})
                           :keyword :test-country})
  (let [state (entity/create :state {:name (entities.i18n/->entity {:en_US "Test state"})
                                     :country [:country/keyword :test-country]})]
    (is (= "Test state"
           (->> (server.ws/call-request-handler {:name ::sut/states.list
                                                 :params {:country :test-country}})
                :data
                first
                :name)))))

(deftest search
  (let [search-params (atom nil)]
    (with-redefs [search/search (fn [& args] (reset! search-params args))]
      (server.ws/call-request-handler {:name ::sut/search
                                       :params {:search "Test"}})
      (is (= [{:_source false
               :query {:bool {:should [{:match {:brand/name.en_US "Test"}}
                                       {:match {:category/name.en_US "Test"}}
                                       {:match {:product/name.en_US "Test"}}]}}}]
             @search-params)))))

