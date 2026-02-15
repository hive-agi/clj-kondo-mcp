# clj-kondo-mcp

Standalone [MCP](https://modelcontextprotocol.io/) server for [clj-kondo](https://github.com/clj-kondo/clj-kondo) static analysis. Runs on [Babashka](https://babashka.org/) via the [modex-bb](https://github.com/hive-agi/modex-bb) framework.

Provides 7 tools for Clojure code analysis: linting, call graph traversal, var lookup, namespace dependency graphs, and dead code detection.

## Requirements

- [Babashka](https://github.com/babashka/babashka) v1.3.0+

## Quick Start

```bash
bb --config bb.edn run server
```

### Claude Code MCP config

Add to `~/.claude/settings.json`:

```json
{
  "mcpServers": {
    "clj-kondo": {
      "command": "bb",
      "args": ["--config", "/path/to/clj-kondo-mcp/bb.edn", "run", "server"]
    }
  }
}
```

## Tools

| Tool | Description |
|------|-------------|
| `analyze` | Project analysis — var definitions, usages, namespaces, findings summary |
| `lint` | Lint with severity filtering (error, warning, info) |
| `find_callers` | Find all call sites of a specific var |
| `find_calls` | Find all vars that a function calls |
| `find_var` | Find definition(s) of a var by name |
| `namespace_graph` | Namespace dependency graph — nodes and edges |
| `unused_vars` | Dead code detection — unused private vars |

## IAddon Integration

clj-kondo-mcp implements the `IAddon` protocol for dynamic registration in [hive-mcp](https://github.com/hive-agi/hive-mcp). When loaded as an addon, its handlers override the embedded kondo handlers in the consolidated `analysis` tool via last-write-wins in the extension registry.

```clojure
(require '[clj-kondo-mcp.init :as init])
(init/init-as-addon!)
;; => {:registered ["kondo"] :total 1}
```

The addon exposes a single `kondo` supertool with command dispatch:

```clojure
;; Direct handler usage
(require '[clj-kondo-mcp.tools :as tools])
(tools/handle-kondo {:command "lint" :path "src/" :level "warning"})
```

## Project Structure

```
src/clj_kondo_mcp/
  core.clj    — clj-kondo pod wrapper (analysis, linting, call graphs)
  tools.clj   — Command handlers + MCP tool schema (IAddon interface)
  init.clj    — IAddon reify + nil-railway registration pipeline
  server.clj  — modex-bb standalone MCP server (7 tools)
  log.clj     — Logging shim (timbre on JVM, stderr on bb)
```

## Dependencies

- [modex-bb](https://github.com/hive-agi/modex-bb) — MCP server framework
- [clj-kondo](https://github.com/clj-kondo/clj-kondo) — via Babashka pod

## License

MIT
