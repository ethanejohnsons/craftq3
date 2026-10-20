# CraftQ3 rewrite

The `rewrite` branch rebuilds CraftQ3 on a new architecture. `main` keeps the previous playable
build. The previous engine modules live under `legacy/` as a behavioural oracle: each is deleted
once its replacement reaches parity.

## Why

An audit of the bootstrapped code found a sound foundation (QVM interpreter, protocol-68 codecs,
asset parsers, render IR) under a fragile engine layer and integration layer:

- Any unsupported syscall ended the whole server (`legacy/craftq3-server/.../Q3Server.java`
  `invoke`), so a bot reaching a ladder, elevator, mover or CTF alternate-route call ended the match.
- About 7.9k lines of capture/smoke harness lived in the mod's production sources and changed
  production code paths.
- `QuakeSession` combined every session mode in one 1.7k-line class inside the Fabric module, and
  qagame ran on the render thread.
- Patch collision, the BSP cull margin and transformed traces diverged from Quake III.
- File stores required `SecureDirectoryStream`, so saves and demos failed on Windows; all mixins
  were client-only, so a dedicated Minecraft server could not load the mod.

Goals: faithful Quake III and the Minecraft bridge as equal priorities; Windows, Linux and macOS;
a dedicated Minecraft server; and every QVM, map and demo in the maintainer's Quake III
installation working (section 4).

## Status

| Phase | Scope | Status |
| --- | --- | --- |
| 0 | Branch, hygiene, build-logic, module graph, ArchUnit, CI matrix | Done, except: reference screenshots from `main`, Blaze3D spike, licence decision, nightly corpus/GameTest jobs |
| 1 | common, fs, formats, net, console, testkit | Not started |
| 2 | vm, vm-abi | Not started |
| 3 | collision, botlib | Not started |
| 4 | game, headless | Not started |
| 5 | client, render-ir, audio | Not started |
| 6 | session | Not started |
| 7 | Fabric pure-Q3 mode | Not started |
| 8 | Hybrid bridge and building | Not started |
| 9 | Parity sign-off | Not started |

## 1. Target architecture

Projects live under `engine/`, `mc/` and `tools/`. Build conventions move to `build-logic/` and replace the root `subprojects {}` block. The package root stays `dev.bluevista.craftq3`.

| Project | Responsibility | Depends on |
|---|---|---|
| `engine:common` | Float-exact `Vec3f` with the full API, replacing about 20 duplicated helpers; info strings; bounded byte IO; log-once facade; injectable clocks | — |
| `engine:fs` | Search path; PK3 and loose mounts; `PureManifest`; `DownloadPlanner`; a portable `HomeStore` with NOFOLLOW, a canonical-root check and atomic moves, so it works on Windows | common |
| `engine:formats` | BSP 46/47, AAS, MD3, shader, skin, anim, TGA/JPG (DDS optional), WAV, RoQ. Primitive arrays instead of boxed lists, named lump views. Lenient by default; strict checks only in validation tooling | common, fs |
| `engine:console` | Cvars, commands, command buffer, bindings, config exec, each with an explicit owner thread | common |
| `engine:net` | Protocol-68 codecs and netchan; connectionless packets; a demo container for protocols 66, 67 and 68 (48 is a stretch, see §4); `PacketTransport` SPI with UDP and loopback | common |
| `engine:vm` | QVM reader and interpreter: flat `int[]` code, a tight dispatch loop, reused argument and stack buffers, `stackBottom` checked against bss; `SyscallTable` | common |
| `engine:vm-abi` | One struct-layout definition per ABI profile; exhaustive import enums `QagameImport`, `CgameImport`, `UiImport`, `BotlibImport`; configstrings; host services shared by all three VMs | vm, console, fs |
| `engine:collision` | CM brushes; `CM_SubdivideGrid` patch facets; capsules; `CM_TransformedBoxTrace`; a single `TraceMerge`; Q3's `offset+1` cull margin; float math; zero-allocation traces; `CollisionSource` SPI | common, formats |
| `engine:botlib` | AAS, routing, all travel types (ladder, elevator, bobbing platforms, grapple), goals, weights, chat, characters, elementary actions, scripts. Travel-type enum. `BotWorld` SPI. No `synchronized` | collision, formats |
| `engine:game` | qagame host, sector-linked entities, snapshots, `Protocol68Host` with rcon and an optional heartbeat, a thin (under 400 lines) botlib marshalling adapter, `MapSource` SPI | vm-abi, collision, botlib, net |
| `engine:render-ir` | Ported `RenderScene`/`CgameFrame`/materials; stages compiled into `StageProgram` parameters for the GPU | formats |
| `engine:audio` | S_* semantics (channels, loops, attenuation, streams); `AudioDevice` SPI | formats |
| `engine:client` | cgame and ui hosts on the shared vm-abi services; one `SnapshotPipeline` for local, remote and demo play; demos; server browser; downloads; input; cinematics | vm-abi, net, collision, render-ir, audio |
| `engine:session` | `EngineSession` state machine (Menu, Loading, Local, Remote, Demo, Cinematic), thread ownership, loopback wiring. Replaces QuakeSession; no Minecraft code | client, game |
| `engine:headless` | Command-line dedicated Q3 server and a scripted client with null render and audio. Used for CI and the compatibility sweep | session |
| `engine:testkit` | QVM assembler; synthetic BSP and AAS builders (from `craftq3-assets/src/testFixtures`); `@RealData` locator; trace-diff tools | test only |
| `engine:benchmarks` | JMH benchmarks: VM, traces, routing, codecs | test only |
| `mc:hybrid` | Minecraft-free bridge and building logic: `TerrainCells` SPI, `GridCollisionSource`, `FaceCoverage`, `SyntheticMapSource` (replaces `ExternalWorld` and the fake hurt triggers), `LoadoutPolicy` (replaces cheat-mode `give`), `CoordinateTransform` | game, collision, formats |
| `mc:fabric` | Loom `splitEnvironmentSourceSets()`. The `main` source set holds the common and server code: initializer, payloads, dimension, server mixins, server-tick driver, config. The `client` source set holds screens, the Blaze3D backend, audio, input and client mixins | session, hybrid |
| `mc:devharness` | Separate mod that is never shipped: Fabric server GameTests and client game tests that replace every `*Smoke` class and all run configurations | fabric |
| `tools:trace-format`, `tools:legacy-oracle`, `tools:native-oracle` | Trace format shared by old and new code; recorder that drives the old modules in a separate process; one maintained C harness pinned to an ioq3 commit (opt-in) | — |

**Rules, enforced by ArchUnit tests in build-logic:**
- No Minecraft, Mojang or Fabric imports outside `mc:fabric` and `mc:devharness`.
- `game` and `collision` never reference `hybrid`.
- `client` never depends on `game`.
- No `System.getProperty` outside the config loaders.

**Threading:**
- **Render thread:** input, cgame and ui VMs, render submission, the client cvar table.
- **`cq3-sim` thread:** pure-Q3 games (qagame, botlib, host) at a fixed `sv_fps`. It talks to the client only over loopback single-producer/single-consumer queues.
- **Minecraft server thread:** bridge and building modes, one qagame frame per 50 ms tick.
- **Cvars:** each table has a single writer. Server-owned cvars are forwarded as commands.
- **Building:** per-level state is a duck interface on `ServerLevel`, replacing the global `synchronized` lookup.

**Error model:**
- **VM fault or `trap_Error`:** drop the map and return to the menu. Nothing propagates into Minecraft.
- **Exception inside a syscall handler:** caught by the dispatcher. It applies the import's DEGRADE policy, which returns a documented neutral value, and logs once with the module, call number and name.
- **Botlib failures:** scoped to the bot; the bot is marked degraded or kicked.
- **Unsupported imports:** every import enum entry is IMPLEMENTED, STUB (with a documented return) or FATAL. `vm_strictSyscalls 1`, on in tests, turns degradations into failures.
- **COMPATIBILITY.md:** its syscall table is generated from the enums.

**ABI detection** is structural: struct-size sanity checks at `LocateGameData` and the import usage profile, plus a `vm_abi` override. It doesn't rely on SHA-256 alone, because the zip contains about 50 distinct QVM builds (§4).

## 2. Phases

Each phase ends with CI green and its exit criteria met.

**Phase 0: Branch and hygiene**
- Run the old smoke harness on `main` one last time to capture reference screenshots.
- On `rewrite`, move the old engine modules to `legacy/` and delete the old `craftq3-fabric`.
  `craftq3-platform` stays in `legacy/` because the legacy client depends on it.
- Delete `scripts/`, keeping the Gradle-wired engine audits in `legacy/audits/` until their
  `@RealData` replacements exist.
- Delete `docs/`, keeping ARCHITECTURE, COMPATIBILITY, LICENSE-NOTES, `docs/formats/` and this file.
- Replace `.editorconfig` with about 20 lines (utf-8, lf, 2-space indent) and add `.gitattributes`.
- Set up build-logic and the ArchUnit rules.
- CI: an engine check on Windows, Linux and macOS; a mod build on Linux; nightly GameTests under xvfb; a nightly corpus job using OpenArena data and ioq3-built QVMs, downloaded with pinned SHAs and never committed.
- Spike: confirm Blaze3D supports shader-side stage evaluation.
- Decision (see §5): licence provenance for ported ioq3 logic.

**Phase 1: common, fs, formats, net, console, testkit**
- Port the codecs from `craftq3-core/.../core/net/` with their hex-capture tests.
- Port the parsers from `craftq3-assets/.../assets/{bsp,aas,md3,shader,image,audio,video}`.
- Split `assets/fs/Pk3FileSystem.java` into its mount, pure and download parts.
- Make the file stores portable.
- Apply the PK3 tolerance rules from §4.
- Exit: codec tests byte-identical; home and demo writes pass on Windows CI; zero parse rejections across the corpus.

**Phase 2: vm and vm-abi**
- Port `craftq3-vm/.../QvmInterpreter.java` and `QvmReader.java`.
- Merge `server/GameAbi`, `VmAbi`, `VmIntrinsics` and `UserCommand` into vm-abi, removing the five copies of the 468-byte playerState size.
- Exit: opcode conformance suite passes; every distinct QVM in the zip reaches `*_INIT` against stub hosts; syscall streams match the old interpreter; JMH shows at least 2× the old interpreter's speed.

**Phase 3: collision and botlib**
- Port `collision/ConvexTrace.java` and `BspTraceWorld.java` with the fixes listed in §1.
- Port botlib from `craftq3-botlib/...`.
- Wire in `ElevatorMovement` and `BobbingPlatformMovement`, which exist but aren't used.
- Add ladders, `sv_step`, moving platforms, and imports 545, 564–566, 575 and 577.
- Collapse the `GroundMoveToGoal` constructors into a parameter record.
- Exit: trace sets match the old output, apart from entries in a checked-in divergence ledger confirmed by the native oracle; a headless bot sweep (4 bots, 10 minutes per map, FFA and CTF) finishes with zero server deaths.

**Phase 4: game and headless**
- Write fresh, using `Q3Server.java`, `EntityWorld`, `SnapshotVisibility` and `net/Protocol68Host.java` as references.
- Implement every qagame import, including 39/40, 43/44, 107–109, 112 and 113, plus rcon.
- Exit: the headless dedicated server runs every qagame in the zip with bots; snapshot streams match the old server; an ioq3 client can connect (opt-in check).

**Phase 5: client, render-ir, audio**
- Port `client/{net,demo,video,input}` and `craftq3-render`.
- Write the cgame and ui hosts fresh, implementing every import, including file writes and reading the CD key from `q3key` (never logged).
- Add demo protocols 66 and 67.
- Exit: the headless client runs every cgame and ui in the zip; record-then-play gives an identical `CgameFrame` digest sequence; every dm_66 and dm_68 demo in the zip plays to the end.

**Phase 6: session**
- `EngineSession`, the threads, loopback and cvar mirroring.
- Exit: a scripted flow passes with thread assertions on (UI → Start Server → play → map change → record and play a demo → disconnect); a one-hour soak shows no leaks.

**Phase 7: Fabric pure-Q3 mode**
- Split common, client and server mixin lists.
- Use MixinExtras `@WrapOperation` instead of `@Local` ordinals, with no `lambda$` targets.
- Write the render backend fresh, porting only pieces of `Blaze3dRenderBackend.java`:
  - static world buffers uploaded once per map;
  - stages and deforms evaluated on the GPU;
  - ring-buffered dynamic draws;
  - portals rendered with PVS from the portal camera;
  - one shared `Q3RenderContext` across all screens.
- Audio: pooled buffers and an executor owned by the lifecycle.
- Turn the smoke classes into devharness client tests.
- Exit: screenshots within tolerance of the Phase 0 references on Vulkan and OpenGL; a dedicated Minecraft server boots with the mod; the frame-time budget is met.

**Phase 8: Hybrid bridge and building**
- Move the logic from `fabric/bridge/*` and `fabric/building/*` into `mc:hybrid`, leaving fabric as thin adapters.
- Run the bridge on the server thread, with multiplayer over a Fabric-payload `PacketTransport`.
- When PK3s are missing, disable the affected building regions with a warning instead of refusing the save.
- Exit, as server GameTests: mob pathing over BSP, explosion and sight occlusion, projectiles, fluids, two-player bridge combat on a dedicated server, and a save loading without PK3s.

**Phase 9: Parity sign-off**
- Freeze the traces, delete the remaining old modules top-down, regenerate the docs, and merge to `main`.

## 3. Verification

- **Differential testing:**
  - `tools:legacy-oracle` runs the old modules in a separate JavaExec process with seeded inputs and fixed clocks.
  - It records syscall streams, server messages, trace sets, AAS route queries and `CgameFrame` digests into `build/oracle/`.
  - New tests compare against these exactly, except for entries in the divergence ledger. Where the old code was wrong (patch collision, cull margin, transformed traces), the native ioq3 oracle is the reference.
  - Traces derived from synthetic data are committed so the old modules can be deleted.
- **CI:** the synthetic testkit plus the OpenArena and ioq3-QVM corpus, downloaded and never committed.
- **Local compatibility sweep:**
  - `./gradlew :engine:headless:compatSweep` with `CRAFTQ3_Q3ROOT` pointing at the unzipped `Quake III Arena/`.
  - It writes an HTML/JSON report to `build/compat/`. Real data is never committed and never reaches CI, because of the retail EULA.
  - This is the release gate for §4.
- **Performance:** JMH runs nightly with a 10% regression gate.
  - qagame plus 8 bots: p99 under 4 ms per frame.
  - Traces: zero allocations.
  - In-game timedemo: p99 under 8 ms at 1080p with no steady-state allocation (checked with a JFR assertion).

## 4. Compatibility corpus

The reference installation archive (`Quake III Arena.zip`, user-owned, never committed) is 2.5 GB with 278 PK3s and 29,505 entries.

**Contents to support:**
- **baseq3:** retail pak0–pak8 and 185 community PK3s, giving 215 maps and 206 AAS files.
- **9 mods:** CPMA 1.00–1.46, Jailbreak 1.0/1.27, True Combat, Combat, Q3Rally, Alternate Fire, Superheroes Arena, Q3Pinball, CorkScrew.
- **About 50 distinct qagame, cgame and ui builds:** pak0 through pak8 span engine versions 1.11 to 1.32.

**What the sweep checks, per game directory:**
1. Mount every PK3.
2. Load every map: BSP, AAS, and resolution of every referenced shader, texture and model.
3. Boot every distinct QVM triple by forcing each one, not just the winning pak8. Run 2 minutes with 4 bots in FFA, plus CTF/team modes where the map supports them.
4. Play every demo to the end.
5. Play every RoQ.
6. Record zero DEGRADE events under `vm_strictSyscalls 1`.

**Specific requirements found in the zip:**

| Finding | Requirement |
|---|---|
| 15 zero-byte `CorkScrew_*.pk3` files | Skip invalid or empty PK3s with a warning, as Q3 does; never fail the whole mount |
| Backslash paths (`Casdm9v1.pk3`: `env\sunset1_up.jpg`), spaces (`q36x pakky/`, `weener dog.wav`) | Normalise `\` to `/` and match case-insensitively instead of rejecting |
| ZIP comments in 13 PK3s | Accept EOCD comments; the current `ZipSafety` is too strict |
| `almostlost.bsp` is IBSP v47 (RTCW format) | Accept v47, whose lump layout is identical; flag it as an extension, since Q3 1.32 itself rejects it |
| DDS textures in `dmmq3dm3.pk3` | Fall back to the TGA/JPG files in `dmmq3dm3_tga.pk3`, as Q3 does; a DDS decoder is optional |
| Demos: `dm_68` (pak8, CPMA, moteof), `dm_66` (pak6, freefall), `dm_48` (pak4/pak5 attract loop) | 68 and 66/67 are required. 48 (the 1.27 format) is a stretch item at the end of Phase 5 |
| Q3Pinball ships only `qagame.qvm` | cgame and ui fall back to baseq3 |
| Loose `baseq3/qagame.qvm` (466,544 bytes, unknown build) | Loose files win over PK3s when the server isn't pure, so it overrides pak8. The sweep reports which QVM was chosen. This file is probably a leftover; consider removing it |
| `quake3.exe`, `*x86_64.so` | Never loaded; CraftQ3 runs QVMs only |
| `share/` (GTK and libxml docs), `games.log`, mod `q3config.cfg` | Ignored; writes go to the home store, never into the install |
| Largest entries: 49 MB `intro.RoQ`, 110 MB `jbpow_maps_v2.pk3` | Within current limits (64 MiB per file, 2 GiB per PK3) |

## 5. Risks, open decisions, scope cuts

**Licence provenance (decide in Phase 0).** LICENSE-NOTES claims an independent implementation, but ConvexTrace and the interpreter mirror ioq3. ioq3 is GPL-2.0-or-later, which is compatible with this project's GPL-3.0. Choose one:
- Allow ported ioq3 logic with attribution. This makes `CM_SubdivideGrid` and `be_aas_*` far easier to get right.
- Keep a clean-room claim.

**Risks:**
- The rewrite stalls before parity. Mitigation: a headless vertical slice by Phase 4, and time-boxed phases.
- The old oracle is wrong in places. Mitigation: the divergence ledger and the native oracle.
- Blaze3D limits. Mitigation: the Phase 0 spike, with cached CPU stage evaluation as a fallback.
- Interpreted qagame may not fit inside a 50 ms tick, especially CPMA's 830 KB qagame. Mitigation: profiling, and an optional bytecode compiler.
- Mods may use undocumented engine quirks (CPMA, True Combat).
- Minecraft updates break mixins.

**Cut order, first to last:**
1. QVM-to-JVM compiler.
2. Master heartbeat and browser polish.
3. `dm_48` demos.
4. Bridge multiplayer on a dedicated server (keep the integrated server).
5. Building extras.
6. RoQ `videoMap`.
7. GPU stage evaluation.
8. Team Arena (not in the zip).

**Never cut:**
- The error model.
- The threading model.
- The fabric source-set split.
- Windows support.
- The hybrid boundary.
- The §4 corpus sweep.
