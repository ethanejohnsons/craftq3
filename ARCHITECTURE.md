# Architecture

CraftQ3 is an independent Quake III Arena–compatible runtime written in Java 25 and hosted by a
Minecraft Fabric mod. It runs the original, user-supplied `qagame`, `cgame` and `ui` QVM bytecode
and supplies every engine service those modules call. No original engine code runs, and no game
data ships with the mod.

## Modules

Dependency edges are declared in `build-logic/src/main/groovy/craftq3/ModuleGraph.groovy` and
enforced at configuration time; ArchUnit rules in `tools/arch-check` enforce the rest (no
Minecraft outside `mc:fabric`/`mc:devharness`, no hybrid code in the engine, no system properties
outside config loaders, no `synchronized` in botlib).

```text
engine:common      float vectors, log facade, clocks, thread ownership, range checks, MD4, info strings
engine:fs          search path (PK3 + loose, original 1.32 precedence), pure manifest, downloads, home store
engine:formats     BSP 46/47, AAS, MD3, skins, animation configs, shaders, TGA/JPEG, WAV, RoQ, entities
engine:console     cvars, commands, command buffer, key bindings, config read/write
engine:net         protocol-68 Huffman/message/delta codecs, netchan, sessions, demos, UDP/loopback transports
engine:vm          QVM reader, flat-code interpreter and QVM-to-JVM bytecode compiler
engine:vm-abi      struct layouts per ABI profile, import tables and policies, shared VM host services
engine:collision   CM layer: brush/patch/capsule traces, inline models, PVS, area portals
engine:botlib      AAS routing, movement, goals, weights, chat, characters, elementary actions, scripts
engine:game        qagame host, entity world, snapshots, protocol-68 server, botlib adapter
engine:render-ir   render scene, GPU-ready material programs, visibility, cgame frame submissions
engine:audio       Quake III sound semantics over an audio device SPI; software mixer
engine:client      cgame/ui hosts, connection, snapshot pipeline, demos, browser, input, cinematics
engine:session     EngineSession state machine, threads, loopback wiring, cvar mirroring
engine:headless    dedicated server, scripted client, compatibility sweep
engine:testkit     @RealData support for tests against a user-owned installation
mc:hybrid          Minecraft-free bridge and building logic in Quake coordinates behind SPIs
mc:fabric          the Fabric mod: common/server and client source sets
mc:devharness      non-shipped mod with client and server game tests
```

## Threads

| Thread | Owns |
| --- | --- |
| Client (Minecraft render thread, or the headless main thread) | ui and cgame VMs, client cvars and console, input, sound, render submission |
| `cq3-sim` | a pure-Quake local or hosted server: qagame, botlib, entity world, `Protocol68Host`, server cvars |
| Minecraft server thread | the bridge and building modes: qagame runs once per 50 ms tick |

Each single-threaded structure carries a `ThreadOwner` and checks it at entry points. The client
and a local server share only a `LoopbackTransport` pair (the normal protocol-68 handshake, so
local, remote and demo play use one snapshot path), a console command inbox, and immutable status
snapshots. Client cvars that belong to the server are forwarded as commands.

## Error model

- A VM fault or `trap_Error` drops the level (ERR_DROP): the map is torn down, the error is shown
  in the original UI, and the session returns to the main menu. Nothing propagates into Minecraft.
- Every import is listed in an exhaustive table with a policy (host-implemented, shared, stub with
  a documented result, or fatal). An exception inside an import handler returns the documented
  neutral value and logs once with the module, number and name. `vm_strictSyscalls 1` turns these
  degradations into failures; tests and the compatibility sweep run that way.
- Botlib failures degrade only the bot involved; `bot_failurePolicy` can kick it.
- Unreadable PK3s, missing images and damaged shaders are skipped with a warning, as the original
  engine skips them.

`docs/SYSCALLS.md` is generated from the import tables.

## ABI profiles

The original modules come in two struct layouts: the 1999 retail release (`pak0.pk3`) and the
1.32 layout used by later releases and mods. `AbiDetector` picks the profile from a table of
verified retail hashes and, structurally, from the set of imports the bytecode calls; `vm_abi`
overrides it. All struct access goes through the `vmabi.layout` classes.

## Rendering

Quake assets and cgame submissions → `RenderScene` (static world geometry packed once per map) +
`MaterialProgram`s (shader stages as data) → `Q3RenderBackend` in `mc:fabric` → Blaze3D, which
drives Minecraft's selected Vulkan or OpenGL device. Shader stage evaluation (rgbGen, alphaGen,
tcGen, tcMod, vertex deforms), fog and dynamic lights run in GLSL; only deforms that need the
camera (autosprite, text) are evaluated on the CPU. Pipelines for a map are compiled at load.
No raw OpenGL or Vulkan calls are made.

## Coordinates and time

Engine coordinates are Quake III coordinates: right-handed, +Z up, angles in degrees.
`mc:hybrid`'s `CoordinateTransform` is the only mapping to Minecraft (`MC = origin + (x, z, −y) /
unitsPerBlock`, 32 units per block by default). Engine code never reads wall time directly; clocks
are injected so runs can be replayed with a `ManualClock`.

## Untrusted data

PK3 entries are read in place and never extracted. Paths are normalised (`\` → `/`,
case-insensitive) and reject absolute paths, `..`, drive letters and control characters. Parsers
check every offset and count with overflow-safe arithmetic and cap allocations. QVM memory access
is masked and bounds-checked, with instruction, syscall and time budgets. VM file writes go to a
per-game home store with quotas; packs, QVMs and native libraries can never be written. Native
modules (`*.so`, `*.dll`, `quake3.exe`) are never loaded.

## Verification

- Synthetic unit tests in every module run in CI on Windows, Linux and macOS (`portableCheck`).
- `@RealData` tests run against a user-owned installation named by `CRAFTQ3_Q3ROOT`.
- `./gradlew :engine:headless:compatSweep` checks every game directory of an installation end to
  end (see [COMPATIBILITY.md](COMPATIBILITY.md)).
- `mc:devharness` client game tests render real maps and play through the mod on OpenGL and
  Vulkan; its server game tests run on a dedicated Minecraft server.
- Deliberate differences from the original engine are listed in
  [docs/DIVERGENCES.md](docs/DIVERGENCES.md).
