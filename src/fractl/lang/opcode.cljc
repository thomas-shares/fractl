(ns fractl.lang.opcode
  "Abstractions to deal with opcodes generated by the compiler."
  #?(:clj
     (:require [net.cgrand.macrovich :as macros])
     :cljs
     (:require-macros [net.cgrand.macrovich :as macros]
                      [fractl.lang.opcode :refer [defopcode defvm]])))

(def arg :arg)
(def op :op)

(defn make-opcode [tag x]
  {op tag
   arg x})

(defn op? [tag opc]
  (= (op opc) tag))

(macros/deftime
  ;; anything inside a deftime block will only appear at the macro compilation stage.
  (defmacro defopcode [opc]
    (let [kw (keyword (name opc))]
      `(do (def ~opc (partial make-opcode ~kw))
           (def ~(symbol (str (name opc) "?")) (partial op? ~kw))))))

(defn- dispatcher [n]
  (symbol (str "do-" (name n))))

(macros/deftime
  ;; anything inside a deftime block will only appear at the macro compilation stage.
  (defmacro defvm [opc-names-args]
    (let [nargs (map (fn [[n args docstring]]
                       `(~(dispatcher n) ~(vec (concat ['--self-- '--env--] args)) docstring))
                     opc-names-args)
          opcdefs (map (fn [[n _ _]]
                         `(defopcode ~n))
                       opc-names-args)
          dispatch-table (into {} (map (fn [[n _ _ ]]
                                         [(keyword (name n)) (dispatcher n)])
                                       opc-names-args))]
      `(do (defprotocol VM ~@nargs)
           ~@opcdefs
           (def dispatch-table ~dispatch-table)))))

;; The virtual machine for evaluating dataflows.
;; Declares the instruction-set (opcodes), defines a protocol for
;; handling these instructions and initializes a dispatch table for
;; the handler methods. Each method corresponds to an opcode.
;;
;; The argument list of the new protocol will be extended to receive
;; a reference to `this` and the runtime environment. Also the `do-` prefix
;; will be added to the method names. For example, the method
;; `(match-instance [pattern instance])` will become `(do-match-instance [self env pattern instance])`.
;; A resolver is an implementation of this newly defined protocol.
;;
;; The `do-`methods defined by resolvers should return a map with status information and result.
;; The general forms of this map are:
;;  - {:status :ok :result <optional-result> :env <optional-updated-environment>}, call success.
;;  - {:status :not-found}, a query or lookup returned no-data.
;;  - {:status :declined}, the resolver refuses to service the call.
;;  - {:status :no-op}, no operation needs to be performed, for instance, no dataflows attached to an event.
;;  - {:status :error :message <error-message>}, there was an unxpected error."
;;
;; The dispatch table will have the structure {:match-instance match-instance ...}.
;; If someone has an opcode, its keyword mnemonic can be fetched with a call to the
;; `op` function. The corresponding handler can be looked-up in the dispatch table
;; using this mnemonic and called with an appropriate resolver implementation.
(macros/usetime
 ;; anything inside a usetime block will not appear at the macro compilation stage.
 (defvm [(match-instance
          [[pattern instance]]
          "If the instance matches the pattern, update env with the instance. Return {:result true}.
           If there is no match, return {:result false}.")
         (load-instance
          [[record-name alias]]
          "Load an instance from the environment. The resolver may extend the search to a database backend, then
          env must be updated with the loaded instance.")
         (load-references
          [[[record-name alias] refs]]
          "Update env with referenced instances.")
         (load-literal
          [[obj]]
          "Load a literal object (number, string) to some VM specific location, like a register.")
         (new-instance
          [record-name]
          "Start initializing a record/entity/event instance.")
         (query-instances
          [[entity-name query-attrs]]
          "Start initializing entity instances in env by first querying it from a persistent store.")
         (set-literal-attribute
          [[attr-name attr-value]]
          "Set the attribute in the instance that is being inited to the given value.")
         (set-ref-attribute
          [[attr-name attr-ref]]
          "Set the attribute in the instance that is being inited by fetching a value from
           the reference.")
         (set-compound-attribute
          [[attr-name f]]
          "Set the attribute in the instance that is being inited by invoking the function.")
         (set-list-attribute
          [[attr-name elements-opcodes quoted?]]
          "Construct a list by evaluating each set of opcodes, set the result as an attribute of the current instance.")
         (intern-instance
          [[record-name alias]]
          "Finish the instance initialization by inserting that in env.")
         (intern-event-instance
          [[record-name alias]]
          "Finish the instance initialization of an event, evaluate attached dataflows.")
         (delete-instance
          [[record-name id-pattern-code]]
          "Remove an instance of the given type and id from the store and caches. The value of
           the id is resolved by evaluating id-pattern-code.")
         (call-function
          [fnobj]
          "Call the function with the current environment and instance being inited as arguments. Return the result.")
         (match
          [[match-pattern-code cases-code alternative-code result-alias]]
          "Execute code for each part of conditional evaluation based on the :match construct.")
         (for-each
          [[bind-pattern-code body-code result-alias]]
           "Execute code for the binding pattern and the iteration.")]))
