# hive-artcraft

One hive addon, `hive.artcraft`, with **one tool, `artcraft`**, for every ArtCraft "Crafting App"
(PhotoCraft, VectorCraft, LightCraft, DesignCraft, FilmCraft, EffectCraft, PdfCraft, ArtCraft Studio).
Each app is a **module**: a piece of data in a registry list. The tool's `app` argument is an enum
built from the apps that mounted.

This release mounts **photocraft** and **vectorcraft**. It depends on the published
`io.github.hive-agi/hive-photocraft` and `io.github.hive-agi/hive-vectorcraft` (0.1.1) and forwards
calls to their own tool handlers. Their code is not copied.

**lightcraft** (port 7980, `LIGHTCRAFT_CONTROL_PORT`) and **designcraft** (port 7981,
`DESIGNCRAFT_CONTROL_PORT`) are `:native :port` modules. hive-artcraft serves them from
`resources/hive_artcraft/catalogs/<app>.edn`. `dev/extract_<app>.clj` derives each catalog from a
reference clone: `clojure -M:dev -m extract-lightcraft [root]`. It uses the shared parser in
`dev/artcraft_extract.clj`, which is ported from hive-vectorcraft and also reads DesignCraft's
`noundo` arm. `:reference-revision` is the clone's HEAD sha.

## Using it

```json
{"command":"apps"}
{"app":"vectorcraft","command":"catalog"}
{"app":"vectorcraft","command":"catalog","query":"control"}
{"app":"vectorcraft","command":"doctor"}
{"app":"vectorcraft","command":"call","engine_command":"shape.rectangle","params":{"x":0,"y":0,"w":10,"h":10}}
{"app":"photocraft","command":"control","method":"ui.inspect"}
```

| arg | meaning |
|---|---|
| `app` | mounted app id (`command=apps` lists them) |
| `command` | `apps` \| `catalog` \| `doctor` \| `call` \| `control` |
| `query` | catalog section (`engine`, `mcp`, `control`) or one exact command id |
| `engine_command`, `method`, `params`, `id` | passed through to the app |

`app=vectorcraft command=catalog` returns exactly what hive-vectorcraft's own `vectorcraft` tool
returns (519 engine commands, 25 MCP tools, 24 control methods at revision 65c5953). A test checks this.

### Configuration

```clojure
;; hive-mcp config.edn
:addons {"hive.artcraft" {:apps {"vectorcraft" {:control-port 7979}
                                 "photocraft"  {:control-port 7878
                                                :token-ref {:scheme :env :name "PHOTOCRAFT_CONTROL_TOKEN"}}}}}
```

Each app's control port is chosen in this order:

1. `:apps {"<app>" {:control-port N}}`
2. the `<APP>_CONTROL_PORT` environment variable
3. the module's default

The default ports are 7878 (photocraft), 7979 (vectorcraft), 7980 (lightcraft), 7981 (designcraft),
9876 (filmcraft) and 9877 (effectcraft). They never collide.

PhotoCraft authenticates its control channel, so it only gets a port when a `:token-ref` is also
configured. Without one, its calls refuse with the fix instead of failing on the socket. Keep every
control port on loopback.

## Adding a Crafting App

An app is **one module map** appended to `resources/hive_artcraft/apps.edn`. A `:native` app also
needs **one catalog resource**. Neither the shared code nor the tool schema changes: the `app` enum,
`apps`, `doctor` and the routing all come from the registry. Every classpath copy of
`hive_artcraft/apps.edn` is concatenated, so another jar can contribute apps as well.

### The contract (`hive-artcraft.schema/AppModule`, Malli-validated at load)

| key | required | meaning |
|---|---|---|
| `:app/id` | yes | lowercase id, the value of `app` (`"lightcraft"`) |
| `:app/label`, `:app/summary` | yes | shown by `command=apps` |
| `:app/kind` | yes | `:mount` (forward to an existing hive addon) or `:native` (served here) |
| `:app/transport` | yes | transport shape, see below |
| `:app/default-port` | for `:port` | default control port |
| `:app/port-env` | for `:port` | env override, `<APP>_CONTROL_PORT` |
| `:app/catalog-resource` | for `:native` | classpath EDN satisfying `schema/Catalog` |
| `:app/mount` | for `:mount` | `{:ctor ns/addon-ctor :addon-id "hive.x" :port-requires [:token-ref]}` |
| `:app/upstream` | no | upstream repository URL |

Per-app runtime config (`schema/AppConfig`, under `:apps {"<id>" {...}}`) accepts:
`:control-port`, `:control-host`, `:control-file`, `:token-ref` and `:transport`. An injected
`ControlTransport` always wins.

If a module is invalid or an id is duplicated, the addon refuses to initialize and names the
module and the reason.

### Transport shapes

| shape | apps | behaviour |
|---|---|---|
| `:port` | vectorcraft, lightcraft, designcraft, filmcraft, effectcraft (photocraft adds a token) | **live**: `<app> --control PORT`, one JSON line `{id, method:"engine.execute", params:{command, params}}` per request, over loopback (`hive-artcraft.transport.socket`) |
| `:control-file` | pdfcraft | `--control <FILE>`: random port plus a token handshake, `ui.*` methods only. **Contract slot**: catalog and doctor work; `call`/`control` refuse with `:artcraft/unsupported-shape` until a control-file transport lands (or a `:transport` is injected) |
| `:none` | ArtCraft Studio (Tauri IPC) | catalog only; `call`/`control` refuse |

`doctor` always reports the app's shape, its resolved port and its source (`:config`, `:env` or
`:default`), whether a transport is ready, and the remedy.

### Example: a native `:port` app

```clojure
;; resources/hive_artcraft/apps.edn — append:
{:app/id "lightcraft"
 :app/label "LightCraft"
 :app/summary "Photo library and raw development."
 :app/kind :native
 :app/transport :port
 :app/default-port 7980
 :app/port-env "LIGHTCRAFT_CONTROL_PORT"
 :app/catalog-resource "hive_artcraft/catalogs/lightcraft.edn"
 :app/upstream "https://github.com/storytold/lightcraft"}
```

`resources/hive_artcraft/catalogs/lightcraft.edn` holds the extracted vocabulary:

```clojure
{:reference-revision "fb7ac08"
 :engine [{:id "develop.exposure" :label "Exposure"} ...]
 :mcp [{:name "..."} ...]
 :control ["engine.execute" "engine.commands" "ui.render" ...]}
```

That's it. `call` checks `engine_command` against `:engine`, `control` checks `method` against
`:control`, and both go out over the resolved port.

### Example: mounting an existing hive addon

```clojure
{:app/id "vectorcraft" :app/label "VectorCraft" :app/summary "..."
 :app/kind :mount :app/transport :port
 :app/default-port 7979 :app/port-env "VECTORCRAFT_CONTROL_PORT"
 :app/mount {:ctor hive-vectorcraft.addon/addon-ctor :addon-id "hive.vectorcraft"}}
```

The delegate's manifest ctor receives its `:apps` config with the resolved `:control-port`. It is
initialized once, and every command (minus `app`) goes to its tool handler unchanged. Add the
delegate's library to `deps.edn`.

## Layout

| ns | role |
|---|---|
| `hive-artcraft.schema` | `AppModule`, `AppConfig`, `TransportShape`, `Catalog` contracts |
| `hive-artcraft.registry` | reads and validates the app data list, resolves per-app config and ports |
| `hive-artcraft.mount` | constructs a delegate addon and forwards to its tool |
| `hive-artcraft.service` | native-app catalog/doctor/call/control for each shape |
| `hive-artcraft.core` (.cljc) | portable request/frame/reply values |
| `hive-artcraft.port`, `.transport.socket` | `ControlTransport` port and loopback JSON-lines client |
| `hive-artcraft.addon` | the IAddon and the `artcraft` tool |

## Tests

```sh
clojure -M:test
```

The suite covers every public function with a golden, property and mutation trifecta (a gate test
reads the universe from `src/`), every one has a Malli contract (also census-checked), and there are
acceptance tests against the real photocraft and vectorcraft delegates.

MIT licensed.
