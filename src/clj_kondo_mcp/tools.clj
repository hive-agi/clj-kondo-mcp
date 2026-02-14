(ns clj-kondo-mcp.tools
  "MCP tool handlers for clj-kondo analysis.

   Exposes handle-kondo for hive-mcp IAddon integration (like lsp-mcp.tools/handle-lsp)
   and tool-def for MCP schema registration."
  (:require [clj-kondo-mcp.core :as core]
            [clj-kondo-mcp.log :as log]))

;; =============================================================================
;; Command Handlers
;; =============================================================================

(def ^:private command-handlers
  {"analyze"         (fn [{:keys [path]}]
                       (let [{:keys [analysis] :as stats} (core/analyze path)]
                         (dissoc stats :analysis)))

   "lint"            (fn [{:keys [path level]}]
                       (let [level-kw (keyword (or level "warning"))
                             findings (core/lint path :level level-kw)]
                         {:findings (vec findings)
                          :count    (count findings)
                          :level    (name level-kw)}))

   "callers"         (fn [{:keys [path ns var_name]}]
                       (let [callers (core/find-callers path ns var_name)]
                         {:target  {:ns ns :var var_name}
                          :callers (vec callers)
                          :count   (count callers)}))

   "calls"           (fn [{:keys [path ns var_name]}]
                       (let [calls (core/find-calls path ns var_name)]
                         {:source {:ns ns :var var_name}
                          :calls  (vec calls)
                          :count  (count calls)}))

   "find_var"        (fn [{:keys [path var_name ns]}]
                       (if ns
                         (core/find-var path var_name ns)
                         (core/find-var path var_name)))

   "namespace_graph" (fn [{:keys [path]}]
                       (let [{:keys [nodes edges]} (core/namespace-graph path)]
                         {:nodes      nodes
                          :edges      edges
                          :node-count (count nodes)
                          :edge-count (count edges)}))

   "unused_vars"     (fn [{:keys [path]}]
                       (let [unused (core/unused-vars path)]
                         {:unused (vec unused)
                          :count  (count unused)}))})

;; =============================================================================
;; MCP Interface (IAddon integration)
;; =============================================================================

(defn handle-kondo
  "MCP tool handler for clj-kondo commands. Dispatches on :command key.
   Returns MCP-compatible response map with :content vector.

   Used by hive-mcp IAddon integration (lazy-resolved like lsp-mcp.tools/handle-lsp)."
  [{:keys [command] :as params}]
  (if-let [handler (get command-handlers command)]
    (try
      (let [result (handler params)]
        {:content [{:type "text" :text (pr-str result)}]})
      (catch Exception e
        (log/error "clj-kondo command failed:" command (ex-message e))
        {:content [{:type "text" :text (pr-str {:error   "Failed to handle command"
                                                :command command
                                                :details (ex-message e)})}]
         :isError true}))
    {:content [{:type "text" :text (pr-str {:error     "Unknown command"
                                            :command   command
                                            :available (sort (keys command-handlers))})}]
     :isError true}))

(defn tool-def
  "MCP tool definition for the clj-kondo tool."
  []
  {:name        "kondo"
   :description "clj-kondo static analysis: lint, analyze, callers, calls, find_var, namespace_graph, unused_vars"
   :inputSchema {:type       "object"
                 :properties {:command  {:type "string"
                                         :enum (sort (keys command-handlers))}
                              :path     {:type        "string"
                                         :description "Path to file or directory to analyze"}
                              :ns       {:type        "string"
                                         :description "Namespace of the target/source function"}
                              :var_name {:type        "string"
                                         :description "Name of the var/function"}
                              :level    {:type        "string"
                                         :description "Minimum severity level for lint"
                                         :enum        ["error" "warning" "info"]}}
                 :required   ["command"]}})

(defn invalidate-cache!
  "Placeholder for cache invalidation (clj-kondo doesn't cache, but IAddon expects it)."
  []
  nil)
