(ns ventas.common.utils
  #?(:clj
     (:require
      [clojure.string :as str]))
  #?(:cljs
     (:require
      [clojure.string :as str]
      [cljs.reader :as reader]
      [cognitect.transit :as transit])))

(defn map-keys [f m]
  (->> m
       (map (fn [[k v]]
              [(f k) v]))
       (into {})))

(defn map-vals [f m]
  (->> m
       (map (fn [[k v]]
              [k (f v)]))
       (into {})))

(defn deep-merge
  "Like merge, but merges maps recursively.
   See: https://dev.clojure.org/jira/browse/CLJ-1468"
  [& maps]
  (if (every? map? maps)
    (apply merge-with deep-merge maps)
    (last maps)))

(defn read-keyword [str]
  (keyword (str/replace str #"\:" "")))

(def ^:private set-identifier "__set")

(defn str->bigdec [v]
  #?(:clj (bigdec v))
  #?(:cljs (transit/bigdec v)))

(defn bigdec->str [v]
  #?(:clj (str v))
  #?(:cljs (reader/read-string (.-rep v))))

(defn process-input-message
  "Properly decode keywords and sets"
  [message]
  (cond
    (map? message)
      (map-vals process-input-message message)
    (string? message)
      (cond
        (str/starts-with? message ":")
          (read-keyword message)
        :else message)
    (vector? message)
      (if (= (first message) set-identifier)
        (set (map process-input-message (rest message)))
        (map process-input-message message))
    :else message))

(defn process-output-message
  "Properly encode keywords and sets"
  [message]
  (cond
    (map? message) (map-vals process-output-message message)
    (vector? message) (map process-output-message message)
    (keyword? message) (str message)
    (set? message) (vec (concat [set-identifier] (map process-output-message message)))
    :else message))
