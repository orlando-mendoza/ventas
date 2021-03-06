(ns ventas.database.entity-test
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [ventas.database.entity :as sut]
   [ventas.test-tools :as test-tools]
   [ventas.entities.user :as user]
   [ventas.database :as db]))

(use-fixtures :each #(test-tools/with-test-context
                       (%)))

(deftest entity?
  (is (= (sut/entity? {:schema/type :any-kw}) true))
  (is (= (sut/entity? {}) false))
  (is (= (sut/entity? {:type :any-kw}) false)))

(def test-user-attrs
  {:first-name "Test user"
   :password ""
   :email "email@email.com"
   :status :user.status/active})

(deftest entity-create
  (let [user (sut/create :user test-user-attrs)]
    (is (= (-> user
               (dissoc :db/id)
               (dissoc :user/password))
           {:schema/type :schema.type/user
            :user/first-name "Test user"
            :user/email "email@email.com"
            :user/status :user.status/active
            :user/culture (:db/id (db/entity user/default-culture))}))
    (sut/delete (:db/id user))))

(deftest register-type!
  (sut/register-type! :new-type {:attributes []})
  (is (= (:new-type (sut/types)) {:attributes []}))
  (let [properties {:filter-json (fn [])
                    :attributes []}]
    (sut/register-type! :new-type properties)
    (is (= (:new-type (sut/types)) properties))))

(deftest entities-remove
  (let [{id :db/id} (sut/create :user test-user-attrs)]
    (sut/delete id)
    (is (not (sut/find id)))))

(deftest entities-find
  (let [{id :db/id :as user} (sut/create :user test-user-attrs)]
    (is (= user (sut/find id)))
    (sut/delete id)))

(deftest enum-retractions
  (let [{:db/keys [id]} (sut/create :user {:favorites [17592186045648 17592186045679 17592186045691]})]
    (is (= [[:db/retract id :user/favorites 17592186045691]
            [:db/retract id :user/favorites 17592186045679]]
           (#'sut/get-retractions
            {:db/id id
             :user/favorites [17592186045648]})))))
