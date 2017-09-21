(ns ventas.entities.user
  (:require [clojure.spec.alpha :as spec]
            [buddy.hashers :as hashers]
            [clojure.test.check.generators :as gen]
            [com.gfredericks.test.chuck.generators :as gen']
            [ventas.database :as db]
            [ventas.database.entity :as entity]
            [ventas.util :as util]))

(spec/def :user/name string?)

(spec/def :user/password (spec/nilable string?))

(spec/def :user/description string?)

(spec/def :user/status
  #{:user.status/pending
    :user.status/active
    :user.status/inactive
    :user.status/cancelled})

(spec/def :user/roles
  (spec/coll-of #{:user.role/administrator
                  :user.role/user}
                :kind set?))

(spec/def :user/email
  (spec/with-gen (spec/and string? #(re-matches #"^.+@.+$" %))
              #(gen'/string-from-regex #"[a-z0-9]{3,6}@[a-z0-9]{3,6}\.(com|es|org)")))

(spec/def :schema.type/user
  (spec/keys :req [:user/name
                   :user/password
                   :user/email
                   :user/status]
             :opt [:user/description
                   :user/roles]))

(entity/register-type! :user
 {:filter-transact
  (fn [this]
    (util/transform
     this
     [#(update
        %
        :user/password
        (fn [v]
          (when v (hashers/derive v))))]))})