(ns clj-kondo-mcp.init
  "IAddon implementation for clj-kondo-mcp — static analysis & linting.

   Deterministic L1 structural layer. Contributes the 'kondo' MCP tool.
   Follows the lsp-mcp exemplar: reify + nil-railway pipeline.

   Usage:
     ;; Via addon system (auto-discovered from META-INF manifest):
     (init-as-addon!)

     ;; Legacy fallback:
     (register-tools!)"
  (:require [clj-kondo-mcp.tools :as tools]
            [clj-kondo-mcp.log :as log]))

;; =============================================================================
;; Resolution Helpers
;; =============================================================================

(defn- try-resolve
  "Attempt to resolve a fully-qualified symbol. Returns var or nil."
  [sym]
  (try
    (requiring-resolve sym)
    (catch Exception _ nil)))

;; =============================================================================
;; IAddon Implementation
;; =============================================================================

(defonce ^:private addon-instance (atom nil))

(defn- make-addon
  "Create an IAddon reify for clj-kondo-mcp.
   Returns nil if protocol is not on classpath."
  []
  (when (try-resolve 'hive-mcp.addons.protocol/IAddon)
    (let [state (atom {:initialized? false})]
      (reify
        hive-mcp.addons.protocol/IAddon

        (addon-id [_] "clj-kondo.mcp")

        (addon-type [_] :native)

        (capabilities [_] #{:tools})

        (initialize! [_ _config]
          (if (:initialized? @state)
            {:success? true :already-initialized? true}
            (do
              (reset! state {:initialized? true})
              ;; Contribute commands to composite "analysis" tool
              (when-let [contribute! (try-resolve 'hive-mcp.extensions.registry/contribute-commands!)]
                (contribute! "analysis" :kondo
                             {"lint"        {:handler #(tools/handle-kondo (assoc % :command "lint"))
                                             :params {"path" {:type "string" :description "Path to file or directory to lint"}
                                                      "level" {:type "string" :enum ["error" "warning" "info"]
                                                               :description "Minimum severity level"}}
                                             :description "Run clj-kondo lint"}
                              "analyze"     {:handler #(tools/handle-kondo (assoc % :command "analyze"))
                                             :params {"path" {:type "string" :description "Path to analyze"}}
                                             :description "Analyze project structure"}
                              "callers"     {:handler #(tools/handle-kondo (assoc % :command "callers"))
                                             :params {"path" {:type "string" :description "Path to analyze"}
                                                      "ns" {:type "string" :description "Namespace of the target function"}
                                                      "var_name" {:type "string" :description "Name of the function"}}
                                             :description "Find all call sites of a var"}
                              "calls"       {:handler #(tools/handle-kondo (assoc % :command "calls"))
                                             :params {"path" {:type "string" :description "Path to analyze"}
                                                      "ns" {:type "string" :description "Namespace of the source function"}
                                                      "var_name" {:type "string" :description "Name of the function"}}
                                             :description "Find all vars called by a function"}
                              "graph"       {:handler #(tools/handle-kondo (assoc % :command "namespace_graph"))
                                             :params {"path" {:type "string" :description "Path to analyze"}}
                                             :description "Namespace dependency graph"}
                              "find_var"    {:handler #(tools/handle-kondo (assoc % :command "find_var"))
                                             :params {"path" {:type "string" :description "Path to analyze"}
                                                      "var_name" {:type "string" :description "Name of the var"}
                                                      "ns" {:type "string" :description "Namespace of the var"}}
                                             :description "Find var definition"}
                              "unused_vars" {:handler #(tools/handle-kondo (assoc % :command "unused_vars"))
                                             :params {"path" {:type "string" :description "Path to analyze"}}
                                             :description "Find unused private vars"}}))
              (log/info "clj-kondo-mcp addon initialized")
              {:success? true
               :errors []
               :metadata {:tools 0}})))

        (shutdown! [_]
          (when (:initialized? @state)
            (tools/invalidate-cache!)
            (reset! state {:initialized? false}))
          nil)

        (tools [_]
          ;; Commands contributed to composite "analysis" tool, no standalone tool
          [])

        (schema-extensions [_] {})

        (health [_]
          (if (:initialized? @state)
            {:status :ok :details {}}
            {:status :down :details {:reason "not initialized"}}))))))

;; =============================================================================
;; Dep Registry + Nil-Railway Pipeline
;; =============================================================================

(defonce ^:private dep-registry
  (atom {:register! 'hive-mcp.addons.core/register-addon!
         :init!     'hive-mcp.addons.core/init-addon!
         :addon-id  'hive-mcp.addons.protocol/addon-id}))

(defn- resolve-deps
  "Resolve all symbols in registry. Returns ctx map or nil."
  [registry]
  (reduce-kv
   (fn [ctx k sym]
     (if-let [resolved (try-resolve sym)]
       (assoc ctx k resolved)
       (do (log/debug "Dep resolution failed:" k "->" sym)
           (reduced nil))))
   {}
   registry))

(defn- step-resolve-deps [ctx]
  (when-let [deps (resolve-deps @dep-registry)]
    (merge ctx deps)))

(defn- step-register [{:keys [addon register!] :as ctx}]
  (let [result (register! addon)]
    (when (:success? result)
      (assoc ctx :reg-result result))))

(defn- step-init [{:keys [addon addon-id init!] :as ctx}]
  (let [result (init! (addon-id addon))]
    (when (:success? result)
      (assoc ctx :init-result result))))

(defn- step-store-instance [{:keys [addon] :as ctx}]
  (reset! addon-instance addon)
  ctx)

(defn- run-addon-pipeline!
  "Nil-railway: resolve-deps -> register -> init -> store"
  [initial-ctx]
  (some-> initial-ctx
          step-resolve-deps
          step-register
          step-init
          step-store-instance))

;; =============================================================================
;; Public API
;; =============================================================================

(defn register-tools!
  "Legacy tool registration (pre-IAddon). Returns tool-def seq."
  []
  [(tools/tool-def)])

(defn init-as-addon!
  "Register clj-kondo-mcp as an IAddon. Falls back to legacy register-tools!."
  []
  (if-let [result (some-> (make-addon)
                          (as-> addon (run-addon-pipeline! {:addon addon})))]
    (do
      (log/info "clj-kondo-mcp registered as IAddon")
      {:registered ["kondo"] :total 1})
    (do
      (log/debug "IAddon unavailable, falling back to legacy init")
      {:registered (mapv :name (register-tools!)) :total 1})))

(defn get-addon-instance
  "Return the current IAddon instance, or nil."
  []
  @addon-instance)
