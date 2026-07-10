# BHL for Rider

A [Rider](https://www.jetbrains.com/rider/) / IntelliJ Platform plugin that provides
language support for the [BHL](https://github.com/bitdotgames/bhl) (Behaviour Highlevel
Language) scripting language by acting as a client for the BHL language server.

It is the Rider counterpart of the
[BHL VSCode extension](https://github.com/bitdotgames/BHL-VSCode): it launches
`bhl lsp` over stdio and connects it to `.bhl` files using JetBrains' built-in
[Platform LSP API](https://plugins.jetbrains.com/docs/intellij/language-server-protocol.html).
Syntax highlighting, diagnostics, completion, and navigation are all provided by the
language server via LSP semantic tokens.

## Requirements

- Rider **2024.3** (build 243) or newer. The plugin uses the built-in LSP API, which is
  available in Rider and other paid JetBrains IDEs.
- A working **BHL executable/script** (`bhl`) — either on your `PATH` or at a path you
  configure in settings. See the [BHL repository](https://github.com/bitdotgames/bhl) for
  how to obtain and build it. On startup the plugin invokes it as `bhl lsp`.
- A **`bhl.proj`** file in the opened project. The language server is started with its
  working directory set to the folder containing `bhl.proj` (matching the VSCode
  extension's behaviour).

## Installation

Build the plugin and install the resulting zip:

```bash
./gradlew buildPlugin
```

The distributable is written to `build/distributions/BHL-<version>.zip`. Install it in
Rider via **Settings ▸ Plugins ▸ ⚙ ▸ Install Plugin from Disk…**.

## Usage

1. Open a project/folder that contains a `bhl.proj` and one or more `.bhl` files.
2. Open any `.bhl` file. The plugin locates `bhl.proj`, then starts the BHL language
   server. Its status is shown in the LSP widget in the status bar.
3. If the project contains **more than one** `bhl.proj`, you are prompted to pick which one
   to use; the choice is remembered for that session. To pin a project explicitly, use
   **Tools ▸ Select BHL Project File…** — it lists the `bhl.proj` files it discovered, or,
   if none are indexed (e.g. a C# solution with BHL scripts in a separate folder), opens a
   file browser so you can point at a `bhl.proj` (or its directory). Either way it stores
   the chosen directory as the *BHL project directory* override (top priority below) and
   restarts the server against it. Clear that setting to return to automatic discovery.

### How the `bhl.proj` is discovered

The working directory for the server is resolved in this order:

1. **BHL project directory** setting — if set, it always wins (useful when the BHL
   scripts live outside the open solution, e.g. a C# solution with BHL scripts in a
   separate folder).
2. **Walk up** from the opened `.bhl` file to the nearest ancestor directory containing a
   `bhl.proj`. This works even if the file is not part of the open project's indexed
   content.
3. **Project-wide search** of the open project's indexed files for `bhl.proj` (0 → server
   doesn't start; 1 → used; many → remembered choice or a picker).

## Settings

Configure the plugin under **Settings ▸ Languages & Frameworks ▸ BHL**:

| Setting         | Default   | Description                                                                                     |
|-----------------|-----------|-------------------------------------------------------------------------------------------------|
| Executable path | *(empty)* | Path to the BHL executable/script. When empty, `bhl` is resolved on your `PATH`.               |
| Log file        | *(empty)* | Optional path passed to the server as `--log-file=PATH`.                                       |
| Force rebuild   | `false`   | When enabled, launches the server with `BHL_REBUILD=1` and `BHL_SILENT=1`, which makes the launcher rebuild before starting (for LSP development). Off by default — the rebuild can exceed the IDE's init timeout. |

These map 1:1 to the VSCode extension's `bhl.executablePath`, `bhl.logFile`, and
`bhl.forceRebuild` settings.

The project-directory override used as discovery priority #1 is set via
**Tools ▸ Select BHL Project File…** (it is not an editable settings field).

## How the server is launched

On opening a `.bhl` file the plugin builds this command line:

```
<executablePath or "bhl"> lsp [--log-file=<logFile>]
```

- **Working directory:** the directory containing the selected `bhl.proj`.
- **Environment:** inherits the IDE environment, plus `BHL_REBUILD=1` / `BHL_SILENT=1`
  when *Force rebuild* is enabled.
- **Transport:** stdio.
- On Windows, a `.bat` executable is run through `cmd.exe /c` (Java cannot exec `.bat`
  files directly), mirroring the VSCode client's `shell: true` handling.

## Highlighting

Highlighting is layered:

- A minimal built-in lexer colors **comments** (`//`, `/* */`) and **strings** — the BHL
  server sends comments to ANTLR's hidden channel, so they are never reported as semantic
  tokens.
- Everything else comes from **LSP semantic tokens**. Types/classes, functions, variables,
  and parameters use BHL-specific color keys (with colors shipped for the Default and
  Darcula scheme families); keywords, strings, numbers, properties, and operators use the
  IDE's standard colors.

## Logs

The **BHL LSP** tool window (bottom) shows a console with both client- and server-side
activity, and opens automatically on the first message:

- **Client side:** file opened, `bhl.proj` discovery result (or why the server won't
  start), the launch command line + working directory, server state transitions
  (Initializing → Running → Shutdown…), and documents opened for the server (`didOpen`).
- **Server side:** the process's **stderr** and exit code (where startup crashes show up),
  plus `window/logMessage` / `$/logTrace` output, `window/showMessage` notifications, and
  diagnostics counts.
- **Rider's platform LSP logs** (`com.intellij.platform.lsp.*`) are mirrored in too, prefixed
  `[lsp]` — server start/stop, init timeouts, the process kill, etc. INFO/WARN/errors show by
  default; enable DEBUG for `com.intellij.platform.lsp` in **Help ▸ Diagnostic Tools ▸ Debug
  Log Settings** to see the finer records here as well. (This reflects all LSP servers, which
  in Rider is normally just BHL.)

To see the **raw JSON-RPC requests/responses** (completion, hover, semantic tokens, …),
enable **Trace LSP traffic** in the settings — every frame is mirrored into the console,
prefixed `-->` (client → server) and `<--` (server → client), truncated at 10 000 chars.
The toggle is read per frame, so it applies immediately without a server restart. With
tracing off, frames are still written at DEBUG level to the plugin's log category
(`#com.bitdotgames.bhl.rider.lsp.BhlLspServerDescriptor` in Debug Log Settings).

Alternatively, set a *Log file* in the settings (passed to the server as `--log-file`) to
get the server's own view of the traffic. Everything in the console is also mirrored to
`idea.log` (**Help ▸ Show Log in Finder**), except `[lsp]` platform records and traced
frames.

## Development

```bash
# Run a sandbox Rider with the plugin loaded
./gradlew runIde

# Build the installable plugin zip
./gradlew buildPlugin

# Verify plugin compatibility
./gradlew verifyPlugin
```

Requirements for building: **JDK 21** and the bundled Gradle wrapper. The IDE to build
against is configured via `platformType`/`platformVersion` in `gradle.properties`.

## Project layout

```
src/main/
├── kotlin/com/bitdotgames/bhl/rider/
│   ├── BhlLanguage.kt / BhlFileType.kt / BhlIcons.kt   # language + .bhl file type
│   ├── lsp/
│   │   ├── BhlLspServerSupportProvider.kt              # LSP wiring + command line
│   │   └── BhlProjectFileResolver.kt                   # bhl.proj discovery
│   ├── settings/
│   │   ├── BhlSettings.kt                              # persisted settings
│   │   └── BhlSettingsConfigurable.kt                  # settings UI
│   └── actions/
│       └── SelectBhlProjectFileAction.kt              # Tools ▸ Select BHL Project File…
└── resources/META-INF/plugin.xml
```
