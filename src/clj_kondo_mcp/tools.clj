(ns clj-kondo-mcp.tools
  "MCP tool handlers for clj-kondo analysis.

   Exposes handle-kondo for hive-mcp IAddon integration (like lsp-mcp.tools/handle-lsp)
   and tool-def for MCP schema registration."
  (:require [clj-kondo-mcp.core :as core]
            [clj-kondo-mcp.log :as log]))

;; =============================================================================
;; Param Helpers
;; =============================================================================

(def ^:private default-limit
  "Default max results to return. Prevents 100K+ char MCP responses."
  200)

(defn- resolve-path
  "Resolve path from params — accepts :path or :file_path (contributed by scc-mcp).
   The composite analysis tool merges params from all addons, so callers may
   pass file_path instead of path."
  [{:keys [path file_path]}]
  (or path file_path))

(defn- apply-limit
  "Cap a collection to limit entries. Returns {:items, :count, :truncated?}."
  [coll limit]
  (let [total (count coll)
        lim   (or limit default-limit)]
    {:items      (vec (take lim coll))
     :count      total
     :truncated? (> total lim)}))

;; =============================================================================
;; Command Handlers
;; =============================================================================

(def ^:private command-handlers
  {"analyze"         (fn [params]
                       (let [{:keys [analysis] :as stats} (core/analyze (resolve-path params))]
                         (dissoc stats :analysis)))

   "lint"            (fn [params]
                       (let [path     (resolve-path params)
                             level-kw (keyword (or (:level params) "warning"))
                             findings (core/lint path :level level-kw)
                             {:keys [items count truncated?]} (apply-limit findings (:limit params))]
                         {:findings  items
                          :count     count
                          :truncated truncated?
                          :level     (name level-kw)}))

   "callers"         (fn [params]
                       (let [path (resolve-path params)
                             callers (core/find-callers path (:ns params) (:var_name params))
                             {:keys [items count truncated?]} (apply-limit callers (:limit params))]
                         {:target    {:ns (:ns params) :var (:var_name params)}
                          :callers   items
                          :count     count
                          :truncated truncated?}))

   "calls"           (fn [params]
                       (let [path (resolve-path params)
                             calls (core/find-calls path (:ns params) (:var_name params))
                             {:keys [items count truncated?]} (apply-limit calls (:limit params))]
                         {:source    {:ns (:ns params) :var (:var_name params)}
                          :calls     items
                          :count     count
                          :truncated truncated?}))

   "find_var"        (fn [params]
                       (let [path (resolve-path params)]
                         (if (:ns params)
                           (core/find-var path (:var_name params) (:ns params))
                           (core/find-var path (:var_name params)))))

   "namespace_graph" (fn [params]
                       (let [{:keys [nodes edges]} (core/namespace-graph (resolve-path params))]
                         {:nodes      nodes
                          :edges      edges
                          :node-count (count nodes)
                          :edge-count (count edges)}))

   "unused_vars"     (fn [params]
                       (let [unused (core/unused-vars (resolve-path params))
                             {:keys [items count truncated?]} (apply-limit unused (:limit params))]
                         {:unused    items
                          :count     count
                          :truncated truncated?}))})

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
  "Evict the TTL analysis cache, forcing a fresh kondo-run! on next tool call."
  []
  (core/invalidate-cache!))
