(ns fractl.compiler
  "Compile dataflow patterns to calls into the resolver protocol."
  (:require [clojure.walk :as w]
            [fractl.util :as u]
            [fractl.util.graph :as ug]
            [fractl.util.seq :as us]
            [fractl.lang.internal :as li]
            [fractl.lang.opcode :as op]
            [fractl.compiler.context :as ctx]
            [fractl.component :as cn]
            [fractl.compiler.validation :as cv]
            [fractl.compiler.internal :as i]))

(def make-context ctx/make)

(defn- emit-load-instance-by-name [_ rec-name]
  (op/load-instance rec-name))

(defn- emit-match-event-by-name [_ event-name]
  (op/match-instance event-name))

(defn- emit-load-references [_ rec-name refs]
  (when (cv/validate-references rec-name refs)
    (op/load-references [rec-name refs])))

(defn- build-dependency-graph [attr-pats ctx schema graph]
  ;; makes parser/build-dependency-graph callable from util/apply->
  (let [result-graph (i/build-dependency-graph attr-pats ctx schema graph)]
    [ctx schema result-graph]))

(def ^:private runtime-env-var '--env--)
(def ^:private current-instance-var '--inst--)

(declare expr-with-arg-lookups)

(defn- reference-lookup-call [n]
  (let [parts (li/path-parts n)]
    (cond
      (:path parts)
      `(get ~current-instance-var ~n)

      (seq (:refs parts))
      `(fractl.env/follow-reference ~runtime-env-var ~parts)

      :else
      `(fractl.env/lookup-instance ~runtime-env-var [(:component parts) (:record parts)]))))

(defn- arg-lookup [arg]
  (cond
    (i/literal? arg) arg

    (seqable? arg)
    (expr-with-arg-lookups arg)

    (li/name? arg)
    (reference-lookup-call arg)

    (symbol? arg)
    `(fractl.env/lookup-variable ~runtime-env-var ~arg)

    :else arg))

(defn- expr-with-arg-lookups [expr]
  (cond
    (i/literal? expr) expr
    (seqable? expr)
    (let [final-args (map arg-lookup (rest expr))]
      `(~(first expr) ~@final-args))
    :else (arg-lookup expr)))

(defn- expr-as-fn [expr]
  (li/evaluate `(fn [~runtime-env-var ~current-instance-var] ~expr)))

(defn- query-param-lookup [p]
  (let [r (arg-lookup p)]
    (if (i/literal? r)
      r
      (expr-as-fn r))))

(defn- query-param-process [[k v]]
  (cond
    (i/literal? v) [k v]
    (seqable? v) (concat
                  [(first v)]
                  (concat [k] (map query-param-lookup (rest v))))
    :else [k (query-param-lookup v)]))

(defn- compile-query [ctx entity-name query]
  (let [expanded-query (i/expand-query
                        entity-name (map query-param-process query))]
    ((ctx/fetch-compile-query-fn ctx) expanded-query)))

(defn- compound-expr-as-fn
  "Compile compound expression to a function.
   Arguments are tranlated to lookup calls on
   the runtime context."
  [expr]
  (expr-as-fn (expr-with-arg-lookups expr)))

(def ^:private appl (partial u/apply-> last))

(defn- parse-attributes
  "Classify attributes in the pattern as follows:
    1. computable - values can be computed at compile-time, e.g literals.
    2. references - values are references to other attributes, that can be reached from the dataflow.
    3. compound - values has to be computed at runtime, by invoking a function.
    4. queries - a query has to be issued on this attribute.

    Any value or reference is validated against the schema, raise an exception on failure.

    A graph of dependencies is prepared for each attribute. If there is a cycle in the graph,
    raise an error. Otherwise return a map with each attribute group and their attached graphs."
  [ctx pat-name pat-attrs schema]
  (let [{computed :computed refs :refs
         compound :compound query :query
         :as cls-attrs} (i/classify-attributes ctx pat-attrs schema)
        fs (map #(partial build-dependency-graph %) [refs compound query])
        deps-graph (appl fs [ctx schema ug/EMPTY])
        compound-exprs (map (fn [[k v]] [k (compound-expr-as-fn v)]) compound)
        parsed-refs (map (fn [[k v]] [k (li/path-parts v)]) refs)
        compiled-query (when query (compile-query ctx pat-name query))
        final-attrs (if (seq compiled-query)
                      (assoc cls-attrs :query compiled-query)
                      cls-attrs)]
    {:attrs (assoc final-attrs :compound compound-exprs :refs parsed-refs)
     :deps deps-graph}))

(def ^:private set-attr-opcode-fns {:computed op/set-literal-attribute
                                    :refs op/set-ref-attribute
                                    :compound op/set-compound-attribute})

(defn- begin-build-instance [rec-name attrs]
  (if-let [q (:query attrs)]
    (op/query-instances [rec-name q])
    (op/new-instance rec-name)))

(defn- emit-build-entity-instance [ctx rec-name attrs schema alias event?]
  (concat [(begin-build-instance rec-name attrs)]
          (map #(op/set-literal-attribute %) (:computed attrs))
          (map (fn [[k v]]
                 ((k set-attr-opcode-fns) v))
               (:sorted attrs))
          [(if event?
             (op/intern-event-instance [rec-name alias])
             (op/intern-instance [rec-name alias]))]))

(defn- sort-attributes-by-dependency [attrs deps-graph]
  (let [sorted (i/sort-attributes-by-dependency attrs deps-graph)]
    (assoc attrs :sorted sorted)))

(defn- emit-realize-instance [ctx pat-name pat-attrs schema alias event?]
  (when-let [xs (cv/invalid-attributes pat-attrs schema)]
    (u/throw-ex (str "invalid attributes in pattern - " xs)))
  (let [{attrs :attrs deps-graph :deps} (parse-attributes ctx pat-name pat-attrs schema)
        sorted-attrs (sort-attributes-by-dependency attrs deps-graph)]
    (emit-build-entity-instance ctx pat-name sorted-attrs schema alias event?)))

(defn- emit-realize-entity-instance [ctx pat-name pat-attrs schema alias]
  (emit-realize-instance ctx pat-name pat-attrs schema alias false))

(def ^:private emit-realize-record-instance emit-realize-entity-instance)

(defn- emit-realize-event-instance [ctx pat-name pat-attrs schema alias]
  (emit-realize-instance ctx pat-name pat-attrs schema alias true))

(defn- emit-realize-map [ctx pat]
  )

(defn- compile-pathname [ctx pat]
  (let [{component :component record :record refs :refs
         path :path} (li/path-parts pat)
        n (or path [component record])
        opc (and (cv/find-schema n)
                 (if refs
                   (emit-load-references ctx n refs)
                   (emit-load-instance-by-name ctx n)))]
    (ctx/put-record! ctx n {})
    opc))

(defn- compile-map [ctx pat]
  (if (li/instance-pattern? pat)
    (let [full-nm (li/instance-pattern-name pat)
          {component :component record :record} (li/path-parts full-nm)
          nm [component record]
          attrs (li/instance-pattern-attrs pat)
          alias (:as pat)
          [tag scm] (cv/find-schema nm full-nm)]
      (let [c (case tag
                :entity emit-realize-entity-instance
                :record emit-realize-record-instance
                :event emit-realize-event-instance
                (u/throw-ex (str "not a valid instance pattern - " pat)))
            opc (c ctx nm attrs scm alias)]
        (ctx/put-record! ctx nm pat)
        (when alias
          (ctx/add-alias! ctx nm alias))
        opc))
    (emit-realize-map ctx pat)))

(defn- compile-command [ctx pat]
  )

(defn compile-pattern [ctx pat]
  (if-let [c (cond
               (li/pathname? pat) compile-pathname
               (map? pat) compile-map
               (vector? pat) compile-command)]
    (let [code (c ctx pat)]
      {:opcode code})
    (u/throw-ex (str "cannot compile invalid pattern - " pat))))

(defn- compile-dataflow [ctx evt-pattern df-patterns]
  (let [c (partial compile-pattern ctx)
        ec (c evt-pattern)
        pc (map c df-patterns)]
    [ec pc]))

(defn- maybe-compile-dataflow [compile-query-fn df]
  (when-not (cn/dataflow-opcode df)
    (let [ctx (make-context)]
      (ctx/bind-compile-query-fn! ctx compile-query-fn)
      (cn/set-dataflow-opcode!
       df (compile-dataflow
           ctx (cn/dataflow-event-pattern df)
           (cn/dataflow-patterns df)))))
  df)

(defn compile-dataflows-for-event [compile-query-fn event]
  (doall
   (map (partial maybe-compile-dataflow compile-query-fn)
        (cn/dataflows-for-event event))))

(defn- reference-attributes [attrs refrec]
  (when-let [result (cn/all-reference-paths attrs)]
    (let [[attr-name path] (first result)
          {refs :refs} (li/path-parts (:ref path))]
      [attr-name (first refs)])))

(defn- assert-unique! [entity-name attr-name ref-attr]
  (let [scm (cn/entity-schema entity-name)]
    (when-not (cn/unique-attribute? scm attr-name)
      (u/throw-ex (str "Reference not valid - " ref-attr " - " [entity-name attr-name]
                       " is not unique")))))

(defn- arg-lookup-fn [rec-name attrs attr-names aname arg]
  (cond
    (= aname arg)
    (u/throw-ex (str "self-reference in attribute expression - " [rec-name aname]))

    (some #{arg} attr-names)
    `(~arg ~current-instance-var)

    :else
    (let [{component :component rec :record refs :refs} (li/path-parts arg)]
      (if-let [[refattr ukattr] (reference-attributes attrs [component rec])]
        (do
          (assert-unique! [component rec] ukattr refattr)
          ;; The referenced instance is auto-loaded into the environment by the
          ;; evaluator, before the following code executes.
          ;; See evaluator/root/do-load-references
          `(~(first refs)
            (first
             (fractl.env/lookup-instances-by-attributes
              ~runtime-env-var
              ~[component rec] [[~ukattr (~refattr ~current-instance-var)]]))))
        (u/throw-ex (str "no unique reference can be traced from " [rec-name aname arg]))))))

(defn compile-attribute-expression [rec-name attrs aname aval]
  (when-not aval
    (u/throw-ex (str "attribute expression cannot be nil - " [rec-name aname])))
  (let [fexprs (map (partial arg-lookup-fn rec-name attrs (keys attrs) aname) (rest aval))
        exp `(fn [~runtime-env-var ~current-instance-var]
               (~(first aval) ~@fexprs))]
    (li/evaluate exp)))
