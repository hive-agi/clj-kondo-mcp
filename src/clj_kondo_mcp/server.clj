(ns clj-kondo-mcp.server
  "Standalone babashka MCP server for clj-kondo analysis.

   Uses modex-bb framework for stdio JSON-RPC.
   Start: bb --config bb.edn server"
  (:require [modex-bb.mcp.server :as mcp-server]
            [modex-bb.mcp.tools :refer [tools]]
            [clj-kondo-mcp.core :as core]
            [clj-kondo-mcp.log :as log]))

;; =============================================================================
;; Tool Definitions (modex-bb DSL)
;; =============================================================================

(def kondo-tools
  (tools
   (analyze "Analyze Clojure code — var definitions, usages, namespaces, findings summary"
            [{:keys [path]
              :type {:path :string}
              :doc  {:path "Path to file or directory to analyze"}}]
            (let [{:keys [analysis] :as stats} (core/analyze path)]
              [(dissoc stats :analysis)]))

   (lint "Lint Clojure code — findings filtered by severity level"
         [{:keys [path level]
           :type {:path :string :level :string}
           :doc  {:path "Path to file or directory to lint"
                  :level "Filter level: error, warning (default), or info"}
           :or   {level "warning"}}]
         (let [findings (core/lint path :level (keyword level))]
           [{:findings (vec findings)
             :count    (count findings)
             :level    level}]))

   (find_callers "Find all call sites of a specific var"
                 [{:keys [path ns var_name]
                   :type {:path :string :ns :string :var_name :string}
                   :doc  {:path     "Path to file or directory to search"
                          :ns       "Namespace of the target var"
                          :var_name "Name of the var to find callers for"}}]
                 (let [callers (core/find-callers path ns var_name)]
                   [{:target  {:ns ns :var var_name}
                     :callers (vec callers)
                     :count   (count callers)}]))

   (find_calls "Find all vars that a function calls"
               [{:keys [path ns var_name]
                 :type {:path :string :ns :string :var_name :string}
                 :doc  {:path     "Path to file or directory to search"
                        :ns       "Namespace of the source function"
                        :var_name "Name of the function to analyze"}}]
               (let [calls (core/find-calls path ns var_name)]
                 [{:source {:ns ns :var var_name}
                   :calls  (vec calls)
                   :count  (count calls)}]))

   (find_var "Find definition(s) of a var by name"
             [{:keys [path var_name ns]
               :type {:path :string :var_name :string :ns :string}
               :doc  {:path     "Path to file or directory to search"
                      :var_name "Name of the var to find"
                      :ns       "Optional namespace to filter results"}
               :or   {ns nil}}]
             [(if ns
                (core/find-var path var_name ns)
                (core/find-var path var_name))])

   (namespace_graph "Namespace dependency graph — nodes and edges"
                    [{:keys [path]
                      :type {:path :string}
                      :doc  {:path "Path to file or directory to analyze"}}]
                    (let [{:keys [nodes edges]} (core/namespace-graph path)]
                      [{:nodes      nodes
                        :edges      edges
                        :node-count (count nodes)
                        :edge-count (count edges)}]))

   (unused_vars "Find unused private vars — dead code detection"
                [{:keys [path]
                  :type {:path :string}
                  :doc  {:path "Path to file or directory to analyze"}}]
                (let [unused (core/unused-vars path)]
                  [{:unused (vec unused)
                    :count  (count unused)}]))))

;; =============================================================================
;; Server
;; =============================================================================

(def mcp-server
  (mcp-server/->server
   {:name    "clj-kondo-mcp"
    :version "0.1.0"
    :tools   kondo-tools}))

(defn -main
  "Start the clj-kondo MCP server.
   Reads JSON-RPC from stdin, writes responses to stdout."
  [& _args]
  (log/info "Starting clj-kondo-mcp server v0.1.0")
  (mcp-server/start-server! mcp-server)
  ;; Allow in-flight response futures to flush before exit.
  (Thread/sleep 500))
