(ns fractl.store.sql
  "Support for translating dataflow-query patterns to generic SQL."
  (:require [clojure.string :as str]
            [clojure.walk :as w]
            [honeysql.core :as hsql]
            [fractl.util :as u]
            [fractl.store.util :as su]
            [fractl.lang.internal :as li]
            [fractl.lang.kernel :as k]))

(defn- attach-column-name-prefixes [where-clause]
  (w/prewalk
   #(if (and (keyword? %) (not (li/operator? %)))
      (keyword (su/attribute-column-name %))
      %)
   where-clause))

(defn- make-wildcard [query]
  (if (su/aggregate-query? query)
    (mapv
     #(let [c (get query %)]
        (keyword (str "%" (name %) "." (when (not= c :*) "_") (name (get query %)))))
     (keys (dissoc query :where)))
    [:*]))

(defn- maybe-remove-where [qpat]
  (if (:where qpat) qpat (dissoc qpat :where)))

(defn- with-deleted-flag [flag where]
  (let [clause [:= su/deleted-flag-col-kw flag]]
    (if where
      [:and clause where]
      clause)))

(def ^:private with-not-deleted-clause (partial with-deleted-flag false))
(def ^:private with-deleted-clause (partial with-deleted-flag true))

(defn- parse-join-table [entity-table-name jquery]
  [(entity-table-name (li/record-name jquery))
   (li/record-attributes jquery)])

(defn normalize-jattr-name [a]
  (let [n (str a)]
    (if (str/ends-with? n "?")
      (subs n 1 (dec (count n)))
      a)))

(defn- jattrs-as-on-clause [attribute-column-name main-table jtable jattrs]
  (let [ss (mapv (fn [[k v]]
                   (let [p (li/path-parts v)]
                     (str jtable "." (attribute-column-name
                                      (normalize-jattr-name k))
                          " = " main-table "." (attribute-column-name (first (:refs p))))))
                 jattrs)]
    (str/join " AND " ss)))

(defn- with-join-attributes [entity-table-name attribute-column-name attrs]
  (reduce (fn [s [n k]]
            (let [[t c] (str/split (str k) #"\.")]
              (str s (if (seq s) ", " "")
                   (entity-table-name t) "." (attribute-column-name c)
                   " AS " (name n) " ")))
          "" attrs))

(defn format-join-sql
  ([entity-table-name attribute-column-name check-is-deleted table-name query]
   (let [wa (:with-attributes query)]
     (when-not wa
       (u/throw-ex (str "join requires with-attributes list - " query)))
     (let [j (:join query)
           lj (:left-join query)
           jinfo (mapv (partial parse-join-table entity-table-name) (or j lj))
           d (name su/deleted-flag-col-kw)
           q (str "SELECT " (with-join-attributes entity-table-name attribute-column-name wa)
                  " FROM " table-name
                  (if j " INNER JOIN " " LEFT JOIN ")
                  (reduce (fn [s [jtable jattrs]]
                            (str s jtable " ON " (jattrs-as-on-clause attribute-column-name table-name jtable jattrs) " "))
                          "" jinfo)
                  (when check-is-deleted (str " WHERE " table-name "." d " = FALSE")))]
       {:join true :with-attributes (mapv first wa) :query q})))
  ([table-name query] (format-join-sql su/entity-table-name su/attribute-column-name true table-name query)))

(defn format-sql [table-name query]
  (let [qmap (map? query)]
    (if (and qmap (or (:join query) (:left-join query)))
      (format-join-sql table-name query)
      (let [query (if qmap (dissoc query :join :left-join :with-attributes) query)
            group-by (when qmap (:group-by query))
            query (if group-by (dissoc query :group-by) query)
            [deleted query] (if qmap
                              [(:deleted query)
                               (dissoc query :deleted)]
                              [nil query])
            with-deleted-flag (if deleted
                                with-deleted-clause
                                with-not-deleted-clause)
            wildcard (make-wildcard query)
            interim-pattern
            (maybe-remove-where
             (if qmap
               (merge
                {:select wildcard :from [(keyword table-name)]}
                (let [clause (attach-column-name-prefixes query)
                      where (:where clause)]
                  (assoc clause :where (with-deleted-flag where))))
               (let [where-clause (attach-column-name-prefixes query)
                     p {:select wildcard
                        :from [(keyword table-name)]}]
                 (assoc p :where
                        (with-deleted-flag
                          (when where-clause
                            (let [f (first where-clause)]
                              (cond
                                (string? f)
                                [(keyword f) (keyword (second where-clause)) (nth where-clause 2)]
                                (seqable? f) f
                                :else where-clause))))))))
            final-pattern (if group-by
                            (assoc interim-pattern :group-by (mapv #(keyword (str "_" (name %))) group-by))
                            interim-pattern)]
        (hsql/format final-pattern)))))

(defn- concat-where-clauses [clauses]
  (if (> (count clauses) 1)
    (reduce conj [:and] clauses)
    (first clauses)))

(defn compile-to-direct-query
  ([table-name col-names log-opr-tag]
   (let [sql (str "SELECT * FROM " table-name)
         logopr (if (= log-opr-tag :and) "AND " "OR ")]
     (if (= :* col-names)
       (str sql " WHERE _" su/deleted-flag-col " = FALSE")
       (str sql " WHERE _" su/deleted-flag-col " = FALSE AND "
            (loop [cs col-names, s ""]
              (if-let [c (first cs)]
                (recur (rest cs)
                       (str s "_" c " = ? "
                            (when (seq (rest cs))
                              logopr)))
                s))))))
  ([table-name col-names]
   (compile-to-direct-query table-name col-names :and)))

(def default-max-varchar-length "10485760")
(def ^:private default-boolean-type "BOOLEAN")

(defn as-sql-type
  ([max-varchar-length bool-type attr-type]
   (let [parts (li/split-path attr-type)
         tp (if (= (count parts) 1) (first parts) (second parts))]
     (case tp
       (:String
        :Keyword :Email
        :Password :DateTime :Date :Time :List :Edn :Any
        :Map :Path) (str "VARCHAR(" max-varchar-length ")")
       (:UUID :Identity) "UUID"
       :Int "INT"
       (:Int64 :Integer :BigInteger) "BIGINT"
       :Float "REAL"
       :Double "DOUBLE PRECISION"
       :Decimal "DECIMAL"
       :Boolean bool-type
       nil)))
  ([attr-type] (as-sql-type default-max-varchar-length default-boolean-type attr-type)))

(defn attribute-to-sql-type
  ([max-varchar-length bool-type attribute-type]
   (if-let [root-type (k/find-root-attribute-type attribute-type)]
     (if-let [tp (as-sql-type root-type)]
       tp
       (u/throw-ex (str "SQL type mapping failed for " attribute-type
                        ", root type is " root-type)))
     (str "VARCHAR(" max-varchar-length ")")))
  ([attribute-type]
   #?(:clj
      ;; For postgres
      (attribute-to-sql-type default-max-varchar-length default-boolean-type attribute-type)
      ;(attribute-to-sql-type Integer/MAX_VALUE "BOOLEAN" "DATE" attribute-type)
      :cljs (attribute-to-sql-type (.-MAX_SAFE_INTEGER js/Number) "BOOLEAN" "DATE" attribute-type))
   ))
