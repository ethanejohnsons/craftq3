# Architecture

## Ownership and dependency direction

Minecraft supplies the process, window, lifecycle, GPU/audio access, input, network transport and integration hooks. CraftQ3 owns Quake assets, spatial geometry, simulation, clocks and engine behavior. Local game/client VMs share one engine cvar table. Original qagame owns movement, weapons, entities and game rules; original cgame owns prediction and presentation through its checked host ABI.

Implemented Gradle modules:

```text
craftq3-core       Java only: VFS/config, commands/cvars/bindings, files, math, protocol-68 codecs
craftq3-assets     → core: PK3, BSP46, shaders, images, MD3, skins, animations and PCM WAV
craftq3-render     → core + assets: scene/materials, visibility, lighting, fog, deforms, portals
craftq3-collision  → core + assets: brush/patch/box traces, inline models, composition
craftq3-botlib     → core + assets + collision: AAS spatial queries/routing and bot script services (in progress)
craftq3-vm         → core: checked QVM reader, memory, interpreter and syscall boundary
craftq3-server     → core + assets + collision + vm + botlib: qagame host, linked entities,
                                                   configstrings and local clients
craftq3-client     → engine modules: usercmd input, local snapshots and original cgame execution
craftq3-platform   → core + render: render/audio contracts, coordinate conversion
craftq3-fabric     → all above: Fabric lifecycle, commands, worker orchestration,
                              screen/input adapter, Blaze3D GPU backend
```

No module other than `craftq3-fabric` may import Minecraft, Fabric, Blaze3D, GLFW, or GPU APIs. The parser never uploads or renders; the filesystem never interprets game rules. `RenderScene` retains the BSP plus flattened vertex attributes and original surface identities; `CgameFrame` preserves ordered refdefs, models, effects and HUD quads with engine-owned asset handles.

Client services compose these modules without importing Minecraft. AAS/botlib stays separate from Minecraft navigation. Platform implementations flow inward via contracts; the VM must not depend on Fabric. Core's protocol-68 netchan and message codec own bounded binary framing independently of Fabric packet schemas. A future transport/session supplies addresses, pacing, connection lifecycle and reliable/gameplay messages; codecs never open sockets. Native differential scope is recorded in `docs/formats/NETWORK68.md`.

## Asset lifecycle and state

`CraftQ3Runtime` is the current application composition root. One background executor loads configuration, mounts files, parses maps and builds meshes. Operations are serialized; repeated commands are rejected while busy. Read-only VFS indexes are built once; archive handles remain open until replacement/shutdown. Per-read bytes are owned by the caller. Loose-file contents are refreshed on read; directory membership is refreshed on reload. This avoids a second unbounded decompressed-asset cache.

Mount replacement first validates all new sources and persists configuration, then publishes the new mount. Failure leaves the old state intact. A successful game switch/reload clears old map state. Map replacement parses and builds completely before replacing the previous map. GPU data belongs to the viewer/backend, not the VFS. Closing a viewer releases GPU resources; shutdown closes archives.

The title-screen launcher, `/q3 menu` and `/q3 map` create a `QuakeSession` with an independently owned PK3 mount, optional writable home store, shared cvars/command buffer/input/audio, original UI and an optional qagame/cgame pair. Initial CPU loading and VM initialization run on the serialized worker; ownership transfers to the client thread before presentation. An active game keeps its mount even if the administrative runtime reloads. Late worker completion checks shutdown/player context and loading-screen cancellation before opening a screen and closes abandoned sessions. Subsequent map commands yield the command buffer, then parse the next world and replace game/client VMs outside guest calls. This work currently runs synchronously at the client frame boundary and can pause presentation while loading. The shared UI, input bindings, cvar handles, audio device and archive mount survive. Failed parsing preserves the running map.

Fresh `Q3Server.initialize` runs four measured 100 ms settling frames before admitting clients, with game frames preceding enabled bot frames. Its returned clock advances by 400 ms; `QuakeSession` adopts that clock before client/input initialization. Pending console commands stay queued. Original item setup and startup validation are documented in `docs/SERVER_STARTUP.md`.

`Q3Screen` owns the GPU backend, cursor capture and session lifetime. The original VMs provide player physics, world state, prediction, weapon presentation and HUD; the host translates screen callbacks into timestamped Q3 commands. The underlying Minecraft world is hidden and a local integrated world pauses. Minecraft SoundInstances are stopped by category on entry and further host playback is suppressed while a Quake view is active. The Q3 backend continues through the shared audio source pool. Before VM startup, the host loads archived `q3config.cfg` then `autoexec.cfg` through the normal bounded command buffer. Saved home files take precedence for `exec`; registered input bindings exist before config execution, and latched server values apply at map initialization. On clean game exit the host writes archived cvars and bindings transactionally. Closing releases input, restores the cursor/listener and closes GPU, VM, audio, home-store and archive resources. `/q3 view` retains the independent noclip diagnostic viewer. Only the loading/error surface and optional diagnostics use Minecraft widgets; the engine console submits original charset/material quads through the same renderer; Quake main/in-game menus are original ui.qvm scene submissions. Escape opens the in-game menu and pauses the Quake clock; Shift+Escape exits. Fullscreen UI replaces the game view; non-fullscreen UI renders after cgame with preserved color contents. Frames retain presentation time even when they contain only UI quads, so shader animation does not depend on a preceding world view. Changing maps releases the previous GPU backend after the new scene becomes active. Fast map restarts retain cgame, server clients/configstrings/reliable sequence and GPU assets, invoke qagame shutdown/init with restart=true, reconnect with firstTime=false, toggle SNAPFLAG_SERVERCOUNT and deliver the map_restart reliable command. Changed game type or client capacity requires a complete map spawn.

## Rendering and Vulkan

Q3 assets/cgame → immutable `RenderScene` → `RenderBackend` → Fabric Blaze3D backend → selected Minecraft GPU device.

The current mesh builder follows stored relative mesh indices for planar/mesh faces and evaluates quadratic Bernstein surfaces on overlapping 3×3 patch grids. It preserves curved geometry without blocks. A bounded subdivision setting caps tessellation work. Model zero is the static world; cgame inline-model submissions retain translation and rotation independently of world geometry. Flares are retained by the parser but not rendered.

The screen extracts immutable camera/scene snapshots. Version-pinned mixins disable the world-enabled argument to `GameRenderer.extract` and `render` while Q3 owns the view, retaining host GUI extraction, target resize and frame cleanup. The Q3 pass runs immediately before `GuiRenderer.render`, so its own diagnostics remain visible. GUI extraction omits vanilla HUD, toasts and subtitles. The dedicated Q3 depth texture is explicitly cleared before each pass; Vulkan/MoltenVK capture testing exposed blank output with attachment-load depth clears. The backend uses `RenderPipeline`, `GpuBuffer`, uniforms, and `RenderPass`, with a depth projection selected from the device's clip-depth convention. Custom GLSL is handed to Minecraft for backend compilation. No raw GL state, Vulkan handles or implicit global model-view state are used. Resizing changes projection from framebuffer dimensions; closing frees buffers.

The material IR carries texture coordinates, lightmaps, ordered shader stages, fog, sky, deformation and sort order. Scene lights and portal cameras already use host-independent types. Interpolated MD3 frames/tags, skin overrides, inline models, sprites, beams, polygons and multiple refdefs share this contract. HUD quads remain ordered with scene submissions, including model portraits. GPU handles never escape the backend. Fixed cameras and a Vulkan/OpenGL screenshot matrix test parity.

## Coordinates and time

Canonical engine/world coordinates are **Q3 coordinates**, with no lossy unit conversion: right-handed, +Z up. Q3 yaw 0 points +X, yaw +90 points +Y; positive pitch looks down. Angles are degrees in scene contracts. Surface normals and BSP plane distances remain in Q3 units.

`CoordinateTransform` is the single interop mapping: MC position = origin + `(q.x, q.z, -q.y) / quakeUnitsPerBlock`. The inverse is explicit and tested. This is an orientation-preserving rotation with uniform scale; a suggested interop scale is 32 Q3 units per block, but pure-Q3 rendering uses Q3 units directly. Direction/displacement conversion excludes translation. Unit normals require rotation only and renormalization; do not use scaled displacements as normals. Axis-aligned boxes use componentwise min/max and must reorder endpoints on the sign-flipped axis. Collision traces return a segment fraction [0,1], inclusive bounds and caller-specified content masks. A 0.125-unit contact epsilon is tested against Q3 behavior.

Do not map Q3 frame time to Minecraft's 20 Hz tick. The diagnostic camera uses bounded monotonic elapsed time. The local session advances a 20 Hz Q3 server with an explicit millisecond clock while sampling usercmds and running cgame at presentation cadence. Development captures use a fixed seed and 16 ms presentation step; input replay and headless audits check determinism. Demo time will use the same explicit clock boundary.

## VM, collision, network and interop contracts

QVM modules are bytecode only. Validate all instruction, stack, memory and syscall access; impose execution budgets and clear faults. Separate qagame/cgame/ui syscall tables translate engine services. No native module loading, arbitrary host filesystem access, or reflection capabilities are exposed. Unsupported syscalls must identify module, call number and context. QVM ownership of weapon damage, match rules, movement state and prediction must survive later Java optimizations.

Collision queries use `TraceWorld`, with BSP brush/patch, transformed inline-model and box providers. `CompositeTraceWorld` chooses the nearest fraction with stable ties and explicit start/all-solid merging. The server composes static map and linked entity queries, preserving contents masks and owner exclusions. Hybrid queries later add Minecraft blocks/entities. Translators map damage/knockback to Minecraft only in interop. Immutable BSP and mutable blocks remain overlapping layers; BSP is never voxelized.

`AudioBackend` accepts decoded PCM, entity/channel/spatial playback, frame-submitted loops and a Q3 listener. The Fabric implementation uses Minecraft’s sound executor, source pool and listener without opening another device. `Q3Input` tracks multiple physical keys per action, short press/release impulses, mouse angles and command timestamps without Minecraft imports. `UdpTransport` now provides a caller-driven, pinned nonblocking UDP endpoint in the platform module. Core protocol-68 handshake/session services own framing, keyed payloads, reliable history and snapshots without sockets or VMs; the client presentation adapter is the next integration boundary. Quake-side audio never refers to Minecraft SoundEvents. Q3 networking must preserve protocol framing, reliable commands, snapshots, configstrings, usercmds and pure/pak verification semantics. Fabric hosting must not imply that the peer is Minecraft.

## Untrusted data and limits

Paths reject absolute paths, dot components, empty components, drive separators and control characters. PK3 entries are read directly and never extracted. Ambiguous case-normalized duplicates fail explicitly. Symlinks are not traversed in mounted game directories; loose files are rechecked on read. Mount roots are user-configured host capabilities, never VM-supplied. The configured installation root itself can resolve through a symlink.

Current limits: 64 MiB per expanded file/BSP, 2 GiB per PK3, 1,024 archives, 400,000 indexed filesystem entries, 32 loose-directory levels, two million typed BSP records, eight million face-index checks, four MiB entity text, 65,536 entities, and one million diagnostic triangles. Long arithmetic checks offsets/ranges before slicing or allocation. BSP node cycles are detected iteratively. Visibility dimensions and face-local indices are validated before use. Readers are suitable for mutation fuzzing.

A ZIP32 footer preflight caps central-directory metadata at 32 MiB before the JDK allocates its ZIP index. Multi-volume and ZIP64 archives are explicitly rejected. This is not a separate OS sandbox. Concurrent hostile mutation of host directories is outside the current read-only asset threat model. Original archives are never modified; network downloads and pure verification remain unavailable.

`GameFileHandles` exposes bounded opaque VM handles. Optional writes go through a separately owned `GameFileStore`: its host-selected root stays pinned by a secure directory descriptor, including if its visible path is renamed or replaced. Reads, temporary writes and replacement traverse descriptor-relative paths without following symlinks. Quotas limit each file to 16 MiB and the store to 256 MiB/4096 files/256 directories/16 levels. The host must provision nested directories, because Java provides no secure relative mkdir operation. Unsupported filesystem providers fail closed. Executable, QVM and pack replacement paths are rejected. Closing a VM's handle table does not close its borrowed store.

The qagame bridge selects the published 1.32 ABI or a verified 1999 profile for the original pak0 bytecode. Entity/player/usercmd fields differ between these versions; normalization occurs at the host boundary. Cvar handles are zero-based, with `sv_cheats` registered first, matching zero-initialized VM cvar updates in the original bot diagnostics. The VM memory boundary still checks every field and pointer. Unknown bytecode defaults to the published ABI, with explicit profiles available after independent verification; entity stride is not used to guess a version.

## Testing and evolution

Synthetic fixtures exercise every BSP lump and patch math; precedence tests define the current VFS contract. Unit tests execute without launching Minecraft. Fabric runtime tests cover application transactions. CI builds on Java 25 with formatting and warnings-as-errors. Real Q3 golden fixtures remain local and ignored. Add parser fuzzing, trace oracles, cgame scenes, demo timestamps and image comparisons incrementally. Never label flat-color output visually Q3-compatible.

## Shader rendering increment

Asset workers resolve scripts, textures and lightmaps into an immutable `MaterialLibrary` before handing the scene to the client. `RenderScene` owns flattened vertex attributes, original BSP face ranges, camera and bounded dynamic lights. `BspVisibility`, `LightGrid`, `FogVolumes`, `StageEvaluator`, `VertexDeformer`, `SkyGeometry` and `PortalView` remain independent of Minecraft.

The Fabric backend owns GPU buffers, texture views, samplers and Q3 depth. It caches static stage vertices, updates time/view-dependent attributes, sorts stages/surfaces, renders sky coverage and a bounded portal view, then draws to the host target. Minecraft GUI extraction is restricted to the active `QuakeView`; the GPU map pass precedes GUI drawing so diagnostics stay visible. Closing or replacing the screen closes GPU resources. The frame meter measures presentation intervals independently of clamped camera deltas.

## Saved Minecraft building regions

The Fabric building adapter owns a world-local hash-to-region index and a bounded
source manifest containing game, virtual BSP path and vertical origin. During
`SERVER_STARTING`, `BuildingWorldLoader` verifies every saved identity through
fresh read-only mounts and prepares immutable BSP collision. `ServerLevelEvents.LOAD`
installs those providers for the build dimension before chunks can tick. Missing
identities fail startup before level construction. Legacy hash-only regions are
located in the selected game/baseq3 hierarchy and receive source descriptors only
after all transforms validate. Metadata contains no original asset bytes.

`Pk3FileSystem.readFrom` is a host-only explicit mounted-source lookup, sharing
normal bounded ZIP/CRC checks; it allows saved geometry to retain its identity
under later pack shadowing. Guest filesystem calls still use normal search/pure
selection. `BuildingWorlds` retains geometry per integrated server level through
leave and map changes, releasing it on server shutdown. Client rendering remains
owned by the active build session. No region eviction or multiplayer protocol is
implemented.


`BuildingProjectiles` contributes exact solid BSP ray hits to Minecraft's arrow
and standard projectile utility paths before native entity selection. It compares
all crossed registered regions against the existing native block/border hit.
Native callbacks own impact behavior and damage. The arrow's existing embedded
occupancy check additionally considers solid BSP geometry, excluding player-only
clip volumes. This does not alter general Minecraft block ray queries or replace
native states.


`BuildingOcclusion` contributes solid BSP occlusion to `ServerExplosion` entity
exposure and block propagation. The exposure hook preserves native block hits and
marks otherwise clear samples occluded by BSP. The block hook tests each exact
native step segment before resistance and block selection, terminating that ray
at immutable geometry. Per-invocation shared state resets at each native ray's
center; it avoids retracing the entire distance at every step. Version-pinned
locals select Minecraft 26.2's sample coordinates. Native explosion calculators,
resistance, damage and knockback remain unchanged. Ordinary dimensions bypass
the BSP checks; no block proxies or BSP modifications are introduced.


`BuildingNavigation` adapts on-demand BSP support and occupancy to Minecraft's
`WalkNodeEvaluator`. A region accessor resolves the server level behind native
pathfinding chunk views. Native path costs, block hazards, doors and path search
remain in place; BSP solids reject occupied cells and bodies, and supported open
cells become walkable. Ground navigation keeps valid BSP destinations and uses
actual support heights for waypoints. No BSP voxelization or substitute mob AI is
introduced. Per-cell clearance is conservative; detailed narrow-passage, slope,
large-mob and non-ground movement coverage remains open.


`BuildingOcclusion` also serves native living-entity sight. The sight hook uses
the exact native start/end ray positions, after Minecraft's dimension/range checks,
and augments only an otherwise clear block/fluid result with BSP solid occlusion.
Native sensing caches, target goals and attack logic remain untouched. A live
original-map audit verifies cover/cache behavior and ordinary zombie pursuit and
melee damage over BSP ground navigation.
