(ns agentlang.util.runtime
  (:require
    clojure.main
    clojure.test
   [clojure.java.io :as io]
   [clojure.string :as s]
   [agentlang.util :as u]
   [agentlang.util.seq :as su]
   [agentlang.util.logger :as log]
   [agentlang.util.runtime :as ur]
   [agentlang.store :as store]
   [agentlang.store.util :as sfu]
   [agentlang.store :as as]
   [agentlang.store.db-common :as dbc]
   [agentlang.resolver.timer :as timer]
   [agentlang.resolver.registry :as rr]
   [agentlang.component :as cn]
   [agentlang.interpreter :as ev]
   [agentlang.global-state :as gs]
   [agentlang.lang :as ln]
   [agentlang.lang.rbac :as lr]
   [agentlang.lang.tools.loader :as loader]
   [agentlang.lang.tools.build :as build]
   [agentlang.auth :as auth]
   [agentlang.connections.client :as cc]
   [agentlang.inference.embeddings.core :as ec]
   [agentlang.inference.service.core :as isc]
   [fractl-config-secrets-reader.core :as sr]))

(def ^:private repl-mode-key :-*-repl-mode-*-)
(def ^:private repl-mode? repl-mode-key)
(def config-data-key :-*-config-data-*-)
(def resource-cache (atom nil))

(defn complete-model-paths [model current-model-paths config]
  (let [mpkey :model-paths
        mp (or (mpkey model)
               (mpkey config)
               ".")]
    (set
     (concat
      current-model-paths
      (if (vector? mp)
        mp
        [mp])))))

(defn store-from-config [store-or-store-config]
  (cond
    (or (nil? store-or-store-config)
        (map? store-or-store-config))
    (or (:store-handle store-or-store-config)
        (store/open-default-store store-or-store-config))

    (and (keyword? store-or-store-config)
         (= store-or-store-config :none))
    nil

    :else
    store-or-store-config))

(defn load-components [components model-root config]
  (loader/load-components components model-root))

(defn load-components-from-model [model model-root config]
  (loader/load-components-from-model
   model model-root
   (:load-model-from-resource config)))

(defn load-model [model model-root model-paths config]
  (loader/load-model
   model model-root
   (complete-model-paths model model-paths config)
   (:load-model-from-resource config)))

(defn log-seq! [prefix xs]
  (loop [xs xs, sep "", s (str prefix " - ")]
    (when-let [c (first xs)]
      (let [s (str s sep c)]
        (if-let [cs (seq (rest xs))]
          (recur cs " " s)
          (log/info s))))))

(defn register-resolvers! [config evaluator]
  (when-let [resolver-specs (:resolvers config)]
    (when-let [rns (rr/register-resolvers resolver-specs)]
      (log-seq! "Resolvers" rns)))
  (when-let [auth-config (:authentication config)]
    (when (auth/setup-resolver auth-config evaluator)
      (log/info "authentication resolver inited"))))

(defn model-name-from-args [args]
  (and (seq (su/nonils args))
       (= (count args) 1)
       (let [f (first args)]
         (and (s/ends-with? f u/model-script-name)
              f))))

(defn maybe-read-model [args]
  (when-let [n (and args (model-name-from-args args))]
    
    (loader/read-model n)))

(defn log-app-init-result! [result]
  (log/info (str "app-init: " (u/pretty-str result))))

(defn- run-standalone-patterns! [evaluator]
  (when-let [pats (seq @gs/standalone-patterns)]
    (let [event-name :Agentlang.Kernel.Lang/PreAppInit]
      (when (and (cn/intern-event event-name {})
                 (cn/register-dataflow event-name pats))
        (try
          (evaluator {event-name {}})
          (catch Exception ex
            (log/error ex))
          (finally
            (gs/uninstall-standalone-patterns!)
            (cn/remove-event event-name)))))))

(defn trigger-appinit-event! [evaluator data]
  (try
    (let [result
          (:result
           (evaluator
            (cn/make-instance
             {:Agentlang.Kernel.Lang/AppInit
              {:Data (or data {})}})))]
      (log-app-init-result! result))
    (catch Exception ex
      (log/error ex))))

(defn- run-configuration-patterns! [evaluator config]
  (try
    (doseq [[llm-name llm-attrs] (:llms config)]
      (let [event (cn/make-instance
                   {:Agentlang.Core/Create_LLM
                    {:Instance
                     (ln/preprocess-standalone-pattern
                      {:Agentlang.Core/LLM
                       (merge {:Name llm-name} llm-attrs)})}})
            r (:result (evaluator event))]
        (when-not (cn/instance-of? :Agentlang.Core/LLM r)
          (log/error (str "failed to initialize LLM - " llm-name)))))
    (catch Exception ex
      (log/error ex))))

(defn- run-pending-timers! []
  (when (:timer-manager (gs/get-app-config))
    (future
      (loop []
        (doseq [timer (seq (timer/restart-all-runnable))]
          (when (= "running" (:Status timer))
            (log/info (str "Started timer " (:Name timer)))))
        (try
          (Thread/sleep 15000)
          (catch InterruptedException _ nil))
        (recur)))))

(defn- maybe-delete-model-config-instance [entity-name]
  (gs/evaluate-dataflow-internal
   [[:delete entity-name :*]
    [:delete entity-name :purge]]))

(defn save-model-config-instance [app-config model-name]
  (when-let [ent (cn/model-config-entity model-name)]
    (when-let [attrs (ent app-config)]
      (maybe-delete-model-config-instance ent)
      (let [evt-name (cn/crud-event-name ent :Create)]
        (gs/evaluate-dataflow-internal {evt-name {:Instance {ent attrs}}})))))

(defn save-model-config-instances []
  (when-let [app-config (gs/get-app-config)]
    (mapv (partial save-model-config-instance app-config) (cn/model-names))))

(defn- fetch-model-config-declaration [entity-name]
  (when-let [app-config (gs/get-app-config)]
    (when-let [rec (entity-name app-config)]
      (cn/make-instance entity-name rec))))

(defn fetch-model-config-instance [model-name]
  (let [model-name (if (vector? model-name)
                     (second model-name)
                     model-name)]
    (when-let [ent (cn/model-config-entity model-name)]
      (let [evt-name (cn/crud-event-name ent :LookupAll)]
        (or (first (:result (gs/evaluate-dataflow-internal {evt-name {}})))
            (fetch-model-config-declaration ent))))))

(defn run-appinit-tasks! [evaluator init-data]
  (save-model-config-instances)
  (run-configuration-patterns! evaluator (gs/get-app-config))
  (run-standalone-patterns! evaluator)
  (trigger-appinit-event! evaluator init-data)
  (run-pending-timers!))

(defn merge-resolver-configs [app-config resolver-configs]
  (let [app-resolvers (:resolvers app-config)]
    (mapv
     #(let [n (:name %)]
        (if-let [ac (first
                     (filter
                      (fn [x] (= (:name x) n))
                      app-resolvers))]
          (assoc % :config (merge (:config ac) (:config %)))
          %))
     resolver-configs)))

(defn run-initconfig [app-config evaluator]
  (let [result (evaluator
                (cn/make-instance
                 {:Agentlang.Kernel.Lang/InitConfig {}}))
        configs (first (mapv :Data (:result (first result))))
        resolver-configs (merge-resolver-configs
                          app-config
                          (vec
                           (apply
                            concat
                            (mapv :resolvers configs))))
        other-configs (mapv #(dissoc % :resolvers) configs)]
    (merge
     (assoc
      (apply merge other-configs)
      :resolvers resolver-configs)
     (dissoc app-config :resolvers))))

(def set-on-init! u/set-on-init!)

(defn init-schema? [config]
  (if-let [[_ f] (find config :init-schema?)]
    f
    true))

(def ^:private runtime-inited (atom nil))

(defn- runtime-inited-with [value]
  (reset! runtime-inited value)
  value)

(defn get-runtime-init-result [] @runtime-inited)

(defn init-runtime [model config]
  (gs/kernel-call
   (fn []
     (let [store (store-from-config config)
           ev (partial ev/evaluate-dataflow store)
           ins (:interceptors config)
           embeddings-config (:embeddings config)]
       (when embeddings-config (ec/init embeddings-config))
       (when (or (not (init-schema? config)) (store/init-all-schema store))
         (let [resolved-config (run-initconfig config ev)]
           (if (:rbac-enabled config)
             (lr/finalize-events)
             (lr/reset-events!))
           (u/run-init-fns)
           (register-resolvers! config ev)
           (when (seq (:resolvers resolved-config))
             (register-resolvers! resolved-config ev))
           (isc/init)
           (run-appinit-tasks! ev (or (:init-data model)
                                      (:init-data config)))
           (when embeddings-config (isc/setup-agent-documents))
           (u/run-app-init-fns)
           [ev store]))))))

(defn finalize-config [model config]
  (let [final-config (merge (:config model) config)]
    (gs/merge-app-config! final-config)
    final-config))

(defn make-server-config [app-config]
  (assoc (:service app-config) :authentication
         (:authentication app-config)))

(defn prepare-runtime
  ([args [[model model-root] config :as abc]]
   (or @runtime-inited
       (let [config (finalize-config model config)
             store (store-from-config (:store config))
             config (assoc config :store-handle store)
             components (or
                         (if model
                           (load-model model model-root nil config)
                           (load-components args (:component-root config) config))
                         (cn/component-names))]
         (when (and (seq components) (every? keyword? components))
           (log-seq! "Components" components))
         (runtime-inited-with [(init-runtime model config) config]))))
  ([model-info] (prepare-runtime nil model-info)))

(defn prepare-repl-runtime [[[model model-root] config]]
  ;; TODO: Fix duplicate invocation of `prepare-runtime` and set repl-mode-key to `true`.
  (prepare-runtime [[model model-root] (assoc config repl-mode-key false)]))

(defn find-model-to-read [args config]
  (or (seq (su/nonils args))
      [(:full-model-path config)]))

(defn preproc-config [config]
  (if (:rbac-enabled config)
    (let [opt (:service config)
          serv (if-not (find opt :call-post-sign-up-event)
                 (assoc opt :call-post-sign-up-event true)
                 opt)
          auth (or (:authentication config)
                   {:service :cognito
                    :superuser-email (u/getenv "AGENTLANG_SUPERUSER_EMAIL" "superuser@superuser.com")
                    :whitelist? false})]
      (assoc config
             :service serv
             :authentication auth))
    config))

(defn load-config [options]
  (preproc-config
   (u/read-config-file (get options :config "config.edn"))))

(defn read-model-and-config
  ([args options]
   (let [config (or (config-data-key options) (load-config options))]
     (when-let [extn (:script-extn config)]
       (u/set-script-extn! extn))
     (let [[model _ :as m] (maybe-read-model (find-model-to-read args config))
           config (merge (:config model) config)]
       (try
         [m (sr/read-secret-config config)]
         (catch Exception e
           (u/throw-ex (str "error reading secret config " e)))))))
  ([options] (read-model-and-config nil options)))

(defn read-model-from-resource [component-root]
  (let [^String s (slurp
                   (io/resource
                    (str "model/" component-root "/" u/model-script-name)))]
    (if-let [model (loader/read-model-expressions (io/input-stream (.getBytes s)))]
      model
      (u/throw-ex (str "failed to load model from " component-root)))))

(defn load-model-from-resource []
  (when-let [cfgres (io/resource "config.edn")]
    (let [config (read-string (slurp cfgres))]
      (when-let [extn (:script-extn config)]
        (u/set-script-extn! extn))
      (if-let [component-root (:component-root config)]
        (let [model (read-model-from-resource component-root)
              config (merge (:config model) config)
              components (load-model
                          model component-root nil
                          (assoc config :load-model-from-resource true))]
          (when (seq components)
            (log-seq! "Components loaded from resources" components)
            (let [r [config model components]]
              (reset! resource-cache r) r)))
        (u/throw-ex "component-root not defined in config")))))

(defn merge-options-with-config [options]
  (let [basic-config (load-config options)]
    [basic-config (assoc options config-data-key basic-config)]))

(def ^:private loaded-models (u/make-cell #{}))

(defn call-after-load-model
  ([model-name f ignore-load-error]
   (gs/in-script-mode!)
   (if (some #{model-name} @loaded-models)
     (f)
     (when (try
             (when (build/load-model model-name)
               (u/safe-set loaded-models (conj @loaded-models model-name)))
             (catch Exception ex
               (if ignore-load-error true (throw ex))))
       (f))))
  ([model-name f]
   (call-after-load-model model-name f false)))

(defn- rename-entity-table [connection entity-name old-version new-version]
  (try
    (let [old-table-name (sfu/entity-table-name entity-name old-version)
          new-table-name (sfu/entity-table-name entity-name new-version)]
      (dbc/rename-db-table! connection new-table-name old-table-name)
      (println "Table renamed: " old-table-name "to" new-table-name))
    (catch Exception e
      (log/error
       (str "Table not renamed - " entity-name " - " old-version " - " new-version))
      (log/error e))))

(defn rename-db-entity-tables [new-entities old-entities old-agentlang-version config]
  (let [no-auto-migration (get config :no-auto-migration #{})
        connection (as/connection-info (store-from-config config))]
    (loop [ne (into [] old-entities)]
      (when (seq ne)
        (let [[k v] (first ne)]
          (when (and (not (contains? no-auto-migration k))
                     (not (contains? no-auto-migration (keyword (namespace k))))
                     (not (contains? no-auto-migration (cn/model-for-component (keyword (namespace k)))))
                     (nil? (rr/resolver-for-path k)))
            
            (when-let [new-version (get new-entities k)]
              (if (contains? (set (cn/internal-component-names)) (keyword (namespace k)))
                (when (and (not= old-agentlang-version "current")
                           (not= old-agentlang-version new-version))
                  (rename-entity-table connection k old-agentlang-version new-version))
                (when (not= v new-version)
                  (rename-entity-table connection k v new-version))))))
        (recur (rest ne))))))

(defn invoke-migrations-event []
  (try
    (let [r (ev/evaluate-dataflow
             (cn/make-instance
              {:Agentlang.Kernel.Lang/Migrations {}}))]
      (log/info (str "migrations result: " r))
      (:result r))
    (catch Exception ex
      (log/error (str "migrations event failed: " (.getMessage ex)))
      (throw ex))))

(defn call-after-load-model-migrate
  ([model-name type path options ignore-load-error] 
   (binding [gs/migration-mode true]
     (gs/in-script-mode!)
     (try
       (let [[_ config] (read-model-and-config options)
             [model old-entities] (build/load-model-migration model-name type path)]
         (cn/unregister-model (:name model))
         (let [[_ new-entities] (build/load-model-migration model-name nil nil)]
           (rename-db-entity-tables new-entities old-entities (:agentlang-version model) config))
         (init-runtime (:name model) config)
         (invoke-migrations-event))
       (catch Exception ex
         (if ignore-load-error true (throw ex))))))
  ([model-name type path options]
   (call-after-load-model-migrate model-name type path options false)))

(defn force-call-after-load-model [model-name f]
  (try
    (call-after-load-model model-name f)
    (catch Exception ex
      (println (str "ERROR - " (.getMessage ex)))
      (f))))

(defn run-repl-func [options model-fn]
  (fn [args]
    (let [opt (first args)
          with-logs (= opt ":with-logs")
          remaining-args (if with-logs (rest args) (do (log/log-capture! :agentlang) args))
          model-name (first remaining-args)]
      (model-fn model-name options))))
