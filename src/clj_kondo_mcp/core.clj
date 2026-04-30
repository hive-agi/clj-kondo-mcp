(ns clj-kondo-mcp.core
  "Core analysis functions using clj-kondo.

   Runtime dispatch:
   - Babashka: loads clj-kondo pod (from bb.edn :pods)
   - JVM: uses clj-kondo.core via requiring-resolve"
  (:require [clj-kondo-mcp.log :as log]))

(def ^:private bb? (some? (System/getProperty "babashka.version")))

;; Load analysis backend
(when bb?
  (require 'babashka.pods)
  ((resolve 'babashka.pods/load-pod) 'clj-kondo/clj-kondo "2026.01.12"))

(def ^:private kondo-run!
  "Resolved clj-kondo run! function — pod on bb, JVM API on Clojure."
  (if bb?
    (do (require '[pod.borkdude.clj-kondo])
        (resolve 'pod.borkdude.clj-kondo/run!))
    (requiring-resolve 'clj-kondo.core/run!)))

;; =============================================================================
;; TTL Cache
;; =============================================================================

(def ^:private analysis-cache
  "Atom holding {:path str :result map :timestamp-ms long}.
   Single-entry cache — only the most recent path is cached."
  (atom nil))

(def ^:private cache-ttl-ms
  "TTL for memoized analysis results: 30 seconds."
  30000)

(defn invalidate-cache!
  "Evict the TTL analysis cache, forcing a fresh kondo-run! on next call."
  []
  (reset! analysis-cache nil))

;; =============================================================================
;; Core Analysis
;; =============================================================================

(defn run-analysis
  "Run clj-kondo analysis on a path.
   Returns {:findings [...] :analysis {...}}"
  [path & {:keys [config] :or {config {}}}]
  (kondo-run!
   {:lint [path]
    :config (merge {:output {:format :edn}
                    :analysis {:var-usages true
                               :var-definitions true
                               :namespace-definitions true
                               :namespace-usages true
                               :arglists true
                               :keywords true
                               :protocol-impls true}}
                   config)}))

(defn- ^:private max-mtime
  "Walk `path` and return the max last-modified ms across Clojure source files
   (.clj/.cljc/.cljs/.edn). Used as a content-hash proxy in the analysis cache
   so any file edit invalidates the entry — was a 30s blind TTL before, which
   surfaced post-edit stale findings (kanban 20260424115059)."
  [^String path]
  (try
    (let [f (java.io.File. path)]
      (cond
        (not (.exists f)) 0
        (.isFile f)       (.lastModified f)
        :else             (->> (file-seq f)
                               (filter (fn [^java.io.File ff]
                                         (and (.isFile ff)
                                              (let [n (.getName ff)]
                                                (or (.endsWith n ".clj")
                                                    (.endsWith n ".cljc")
                                                    (.endsWith n ".cljs")
                                                    (.endsWith n ".edn"))))))
                               (map (fn [^java.io.File ff] (.lastModified ff)))
                               (reduce max 0))))
    (catch Throwable _ 0)))

(defn cached-run-analysis
  "Run analysis with TTL+mtime memoization. Returns raw kondo result map.

   Cache is keyed by (path, max-mtime); any file edit under `path`
   invalidates the entry, so post-edit lint calls always see fresh
   findings (vs. the prior path-only key, which returned stale cached
   results for up to 30s after an edit — kanban 20260424115059).

   The 30s TTL is retained as a secondary ceiling for the rare case
   where files mutate without changing mtime (e.g. atomic rewrite
   preserving timestamps via `touch -r`)."
  [path & opts]
  (let [now    (System/currentTimeMillis)
        mtime  (max-mtime path)
        cached @analysis-cache]
    (if (and cached
             (= path (:path cached))
             (= mtime (:max-mtime cached))
             (< (- now (:timestamp-ms cached)) cache-ttl-ms))
      (do
        (log/debug "Analysis cache hit for" path)
        (:result cached))
      (let [result (apply run-analysis path opts)]
        (log/debug "Analysis cache miss for" path
                   "mtime" mtime "(prev" (:max-mtime cached) ")"
                   ", caching result")
        (reset! analysis-cache {:path         path
                                :max-mtime    mtime
                                :result       result
                                :timestamp-ms now})
        result))))

(defn analyze
  "Analyze a path and return structured analysis data."
  [path]
  (let [{:keys [analysis findings]} (cached-run-analysis path)]
    {:var-definitions (count (:var-definitions analysis))
     :var-usages (count (:var-usages analysis))
     :namespaces (count (:namespace-definitions analysis))
     :findings (count findings)
     :analysis analysis}))

(defn find-callers
  "Find all call sites of a specific var.
   Returns list of {:filename :row :col :from :from-var :arity}"
  [path ns-name var-name]
  (let [{:keys [analysis]} (cached-run-analysis path)
        var-usages (:var-usages analysis)
        target-ns (symbol ns-name)
        target-var (symbol var-name)]
    (->> var-usages
         (filter #(and (= (:to %) target-ns)
                       (= (:name %) target-var)))
         (map #(select-keys % [:filename :row :col :from :from-var :arity]))
         (sort-by (juxt :filename :row)))))

(defn find-calls
  "Find all vars that a function calls.
   Returns list of {:filename :row :col :to :name :arity}"
  [path ns-name var-name]
  (let [{:keys [analysis]} (cached-run-analysis path)
        var-usages (:var-usages analysis)
        source-ns (symbol ns-name)
        source-var (symbol var-name)]
    (->> var-usages
         (filter #(and (= (:from %) source-ns)
                       (= (:from-var %) source-var)))
         (map #(select-keys % [:filename :row :col :to :name :arity]))
         (sort-by (juxt :to :name)))))

(defn find-var
  "Find definition(s) of a var.
   Returns list of {:filename :row :col :ns :name :doc :arglists}"
  [path var-name & [ns-name]]
  (let [{:keys [analysis]} (cached-run-analysis path)
        var-defs (:var-definitions analysis)
        target-var (symbol var-name)
        target-ns (when ns-name (symbol ns-name))]
    (->> var-defs
         (filter #(and (= (:name %) target-var)
                       (or (nil? target-ns)
                           (= (:ns %) target-ns))))
         (map #(select-keys % [:filename :row :col :end-row :end-col
                               :ns :name :doc :arglist-strs :private :macro])))))

(defn namespace-graph
  "Get namespace dependency graph.
   Returns {:nodes [...] :edges [...]} suitable for visualization."
  [path]
  (let [{:keys [analysis]} (cached-run-analysis path)
        ns-defs (:namespace-definitions analysis)
        ns-usages (:namespace-usages analysis)]
    {:nodes (mapv #(select-keys % [:name :filename :doc]) ns-defs)
     :edges (mapv #(hash-map :from (:from %)
                             :to (:to %)
                             :alias (:alias %))
                  ns-usages)}))

(defn lint
  "Lint a path and return findings.
   Level can be :error, :warning, or :info"
  [path & {:keys [level] :or {level :warning}}]
  (let [{:keys [findings]} (cached-run-analysis path)]
    (->> findings
         (filter #(case level
                    :error (= (:level %) :error)
                    :warning (#{:error :warning} (:level %))
                    :info true
                    true))
         (map #(select-keys % [:filename :row :col :level :type :message])))))

(defn unused-vars
  "Find unused private vars in a codebase."
  [path]
  (let [{:keys [analysis]} (cached-run-analysis path)
        var-defs (:var-definitions analysis)
        var-usages (:var-usages analysis)
        used-vars (into #{}
                        (map (fn [{:keys [to name]}]
                               [(symbol (str to)) name])
                             var-usages))
        private-defs (filter :private var-defs)]
    (->> private-defs
         (remove #(contains? used-vars [(:ns %) (:name %)]))
         (map #(select-keys % [:filename :row :col :ns :name])))))