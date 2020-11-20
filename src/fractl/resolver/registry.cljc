(ns fractl.resolver.registry
  (:require [fractl.util :as u]))

(def ^:private resolver-db (u/make-cell {}))

(defn resolver-for-path [path]
  (get @resolver-db path))

(defn make-crud-resolver [name upsert-fn delete-fn]
  {:name name :upsert upsert-fn :delete delete-fn})

(def resolver-name :name)
(def resolver-upsert :upsert)
(def resolver-delete :delete)

(defn override-resolver [path resolver]
  (u/safe-set resolver-db (assoc @resolver-db path resolver)))

(defn compose-resolver [path resolver]
  (let [resolvers (get @resolver-db path [])]
    (u/safe-set resolver-db
                (assoc @resolver-db path
                       (conj resolvers resolver)))))
