(ns fractl.store.util
  (:require [clojure.set :as set]
            [clojure.string :as s]
            [fractl.component :as cn]
            [fractl.lang.internal :as li]
            [fractl.util :as u]))

(def deleted-flag-col "FRACTL__IS_DELETED")
(def deleted-flag-col-kw (keyword (str "_" deleted-flag-col)))

(defn- sys-col? [n]
  (= (s/upper-case n) deleted-flag-col))

(defn db-ident [k]
  (if (keyword? k)
    (name k)
    k))

(defn db-schema-for-component [component-name]
  (s/replace (name component-name) #"\." "_"))

(defn escape-graphic-chars [s]
  (when (string? s)
    (reduce
     (fn [a c]
       (if a (str a (if #?(:clj (Character/isLetterOrDigit c) :cljs true) c \_)) c))
     nil s)))

(def ^:private schema-version
  (memoize
   (fn [component-name]
     (escape-graphic-chars
      (or (cn/model-version (cn/model-for-component component-name))
          "0.0.1")))))

(defn entity-table-name [entity-name]
  (let [[component-name r] (li/split-path entity-name)
        v (schema-version component-name)
        en (str (db-ident r) "_" v)]
    (if (cn/entity-schema-predefined? entity-name)
      en
      (str (db-schema-for-component component-name) "__" en))))

(defn component-meta-table-name
  ([component-name model-version]
   (let [v (or (escape-graphic-chars model-version) (schema-version component-name))]
     (str (db-ident (db-schema-for-component component-name)) "_meta_" v)))
  ([component-name] (component-meta-table-name component-name nil)))

(defn attribute-column-name [aname]
  (str "_" (name aname)))

(defn index-table-name
  "Construct the lookup table-name for the attribute, from the main entity
  table-name and attribute-name."
  [tabname attrname]
  (let [attrname (db-ident attrname)]
    (str tabname "_" attrname)))

(defn index-name
  "Given a table-name, return its relative index table name."
  [tabname]
  (s/replace (str tabname "_idx") #"\." "_"))

(defn index-table-names
  "Given an entity table-name and its indexed attributes, return a sequence of
  all index table names."
  [table-name indexed-attrs]
  (let [tabnames (map #(index-table-name table-name %) indexed-attrs)]
    (into {} (map vector indexed-attrs tabnames))))

(def create-table-prefix "CREATE TABLE IF NOT EXISTS")
#?(:clj 
   (def create-unique-index-prefix "CREATE UNIQUE INDEX IF NOT EXISTS")
   :cljs
   (def create-unique-index-prefix "CREATE UNIQUE INDEX"))
#?(:clj
   (def create-index-prefix "CREATE INDEX IF NOT EXISTS")
   :cljs
   (def create-index-prefix "CREATE INDEX"))

(defn create-index-sql
  "Given a table-name and an attribute-column-name, return the
  CREATE INDEX sql statement for that attribute."
  [table-name colname unique?]
  (str (if unique? create-unique-index-prefix create-index-prefix)
       " " (index-name table-name) " ON " table-name "(" colname ")"))

(defn create-schema-sql [schema-name]
  (str "CREATE SCHEMA IF NOT EXISTS " schema-name))

(defn drop-schema-sql [schema-name]
  (str "DROP SCHEMA IF EXISTS " schema-name))

(defn find-entity-schema [rec-name]
  (if-let [scm (cn/entity-schema rec-name)]
    scm
    (u/throw-ex (str "schema not found for record - " rec-name))))

(defn table-name->entity
  [tabname] 
  (let [tabnamestr (name tabname)
        [cnstr estr] (s/split tabnamestr #"__")]
    [(keyword (s/replace cnstr #"_" ".")) (keyword estr)]))

(defn- table-attr->entity-attr
  [table-attr]
  (let [[cne attr] (li/split-path table-attr)
        [cn e] (table-name->entity cne)]
    (keyword (s/upper-case (str (name cn) "." (name e) "/" (name attr))))))

(defn normalize-connection-info [connection-info]
  (if-let [f (:decrypt-fn connection-info)]
    (let [pswd (f (:password connection-info))]
      (assoc connection-info :password pswd))
    connection-info))

(def ^:private obj-prefix "#clj-obj")
(def ^:private obj-prefix-len (count obj-prefix))

(defn encode-clj-object [obj]
  (str obj-prefix (str obj)))

(defn decode-clj-object [s]
  (#?(:clj read-string :cljs clj->js)
   (let [s (s/replace s "\\\\\"" "\\\"")
         s (subs s obj-prefix-len)]
     #?(:clj (if (seq s) s "nil") :cljs s))))

(defn encoded-clj-object? [x]
  (and (string? x) (s/starts-with? x obj-prefix)))

(defn- serialize-obj-entry [non-serializable-attrs [k v]]
  (if (cn/meta-attribute-name? k)
    [k v]
    (when-not (nil? v)
      [k (cond
           (or
            (or (fn? v)
                (and (seqable? v) (not (string? v))))
            (some #{k} non-serializable-attrs))
           (encode-clj-object v)

           (keyword? v) (subs (str v) 1)

           :else v)])))

(defn serialize-objects [instance]
  (let [fattrs (mapv first (cn/future-attrs (cn/instance-type instance)))]
    (into {} (mapv (partial serialize-obj-entry fattrs) instance))))

(defn- normalize-result
  [result]
  (let [attrs (keys result)
        attrmap (apply assoc {} (interleave attrs (map table-attr->entity-attr attrs)))]
    (set/rename-keys result attrmap)))

(defn- remove-prefix [n]
  (let [s (str n)]
    (if (s/starts-with? s ":_")
      (subs s 2)
      (subs s 1))))

(defn- normalize-attribute [schema kw-type-attrs [k v]]
  [k
   (cond
     (some #{k} kw-type-attrs) (u/string-as-keyword v)
     (uuid? v) (str v)
     (encoded-clj-object? v) (decode-clj-object v)
     :else v)])

(defn result-as-instance
  ([entity-name entity-schema normalize-colname result]
   (let [attr-names (cn/attribute-names entity-schema)
         rp (or normalize-colname remove-prefix)]
     (loop [result-keys (keys result), obj {}]
       (if-let [rk (first result-keys)]
         (let [[_ b] (li/split-path rk)
               f (rp (or b rk))]
           (if (sys-col? f)
             (recur (rest result-keys) obj)
             (let [aname (first
                          (filter
                           #(= (s/upper-case (name %)) (s/upper-case f))
                           attr-names))]
               (if aname
                 (recur (rest result-keys) (assoc obj aname (get result rk)))
                 (u/throw-ex (str "cannot map " rk " to an attribute in " entity-name))))))
         (cn/make-instance
          entity-name
          (into {} (mapv (partial
                          normalize-attribute entity-schema
                          (cn/keyword-type-attributes entity-schema attr-names))
                         obj))
          false)))))
  ([entity-name entity-schema result]
   (result-as-instance entity-name entity-schema nil result))
  ([entity-name result]
   (result-as-instance entity-name (cn/fetch-schema entity-name) nil result)))

(defn results-as-instances
  ([entity-name normalize-colname results]
   (mapv (partial result-as-instance entity-name (cn/fetch-schema entity-name) normalize-colname) results))
  ([entity-name results]
   (results-as-instances entity-name nil results)))

(def compiled-query :compiled-query)
(def raw-query :raw-query)

(defn package-query
  ([q cq]
   {compiled-query cq
    raw-query q})
  ([cq]
   (package-query nil cq)))

(def aggregate-fns [:count :max :min :avg :sum])

(defn aggregate-query? [query]
  (and (map? query) (some (set (keys query)) aggregate-fns)))

(defn- normalize-aggregate [result]
  (into
   {}
   (mapv (fn [[k v]]
           (let [s (s/lower-case (name k))]
             [(or (first (filter #(s/starts-with? s (name %)) aggregate-fns)) k) v]))
         result)))

(defn normalize-aggregates [results]
  (mapv normalize-aggregate results))
