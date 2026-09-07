# CraftQ3 roadmap

The complete tier plan and associated interoperability, coordinate, rendering, security, performance and test requirements below are retained from the project brief. These are **targets**, not claims of implementation. The current host target is explicitly **Minecraft 26.2 / Java 25**, with Vulkan support through Blaze3D, per the follow-up instruction.

Current increment: a visible local gameplay session joins original qagame/cgame VMs, independent collision, model/HUD rendering, sound and input. Movement, firing and death/respawn are exercised in local audits; a real Vulkan screen-input test verifies the visible world, weapon and HUD. The diagnostic viewer remains separate. Original main/in-game menus and map lifecycle now run through ui.qvm. Original bots now run by default, swimming prediction has native/map coverage, and tutorial victory/Next/saved progress pass. A protocol-68 client backend passes actual private native-server movement, packet-loss, reliable-command and map-transition checks; direct remote cgame/UI play, network clock/ping and local/remote transitions now have CPU application coverage. Pure PK3 verification, restricted pack reads and complete CPU application transitions now pass native-server checks. Original retail and 1.32 multiplayer browser services now cover lists, pings, filters, favorites, status and menu-generated remote joins. Protocol-68 recording/playback now covers local and remote matches, original Demos menus, native restart/map transitions, freeze/timedemo and failure recovery. Server hosting, legacy demo protocols, broader campaign/team coverage, remaining services and fidelity work are incomplete; the user now prioritizes the Minecraft gameplay bridge while standalone compatibility work remains open. Limits are recorded in `COMPATIBILITY.md`; the original staged target below remains the full roadmap.

The full objective includes playable pure Quake III, Q3 player mechanics in Minecraft worlds and Minecraft building/player mechanics over an unchanged Q3 BSP. The user now prioritizes the gameplay bridge; both local crossover directions have playable slices while remaining standalone work stays on the roadmap. No Java substitute for QVM weapon/game rules is an acceptable shortcut.

---

# Tier 0 — repository foundation

Start here.

Create the project structure and documentation before implementing large systems.

Deliver:

* Fabric mod project that launches successfully
* pinned Minecraft version
* pinned Fabric Loader/API versions
* Java version documented
* development run configuration
* basic `/q3` command
* CraftQ3 configuration directory
* configurable path to Quake III installation/game directory
* logging infrastructure
* architecture documentation
* compatibility roadmap
* test infrastructure

Create at minimum:

`README.md`

`ARCHITECTURE.md`

`ROADMAP.md`

`COMPATIBILITY.md`

`LICENSE-NOTES.md`

`docs/formats/`

Include the complete tier roadmap from this prompt in `ROADMAP.md`.

Do not silently couple the project to whichever Minecraft version happens to be current. Pick a known supported version and pin it.

Prefer supported Fabric/Minecraft rendering APIs over direct assumptions about raw OpenGL unless low-level access is genuinely necessary.

---

# Tier 1 — Quake virtual filesystem

Implement the Quake III filesystem/search-path layer.

Support:

* `.pk3` ZIP reading
* directories in addition to PK3s where Q3 semantics permit
* normalized virtual paths
* case handling compatible enough for real Q3 content
* PK3 ordering
* override semantics
* base game directory
* mod directory equivalent to `fs_game`
* enumeration
* reading binary/text files
* caching where appropriate

Suggested commands:

`/q3 fs status`

`/q3 fs list <path>`

`/q3 fs which <virtual-path>`

`/q3 game <mod-directory>`

The system should eventually support layouts conceptually like:

`.minecraft/craftq3/games/baseq3/`

or alternatively pointing to an external Quake III installation.

Do not require users to copy files unnecessarily if they can configure an existing installation path.

Add automated tests for search-path and override ordering.

---

# Tier 2 — BSP map loading and static rendering

Goal:

> Minecraft launches, `/q3 map q3dm17` is executed, and actual geometry from the user's original BSP is rendered.

Implement Quake III BSP v46 support.

Important BSP systems include:

* header/lumps
* entities
* planes
* nodes
* leaves
* leaf surfaces
* leaf brushes
* models
* brushes
* brush sides
* vertices
* meshverts
* effects
* faces/surfaces
* lightmaps
* light volumes
* visibility data

Support Q3 surface types including:

* planar polygon surfaces
* triangle meshes
* curved patch surfaces
* flares when appropriate

Patch tessellation must preserve curved Q3 geometry. Do not voxelize patches.

Implement a scene/world representation that is independent from Minecraft's block renderer.

At first it is acceptable to render using simplified materials while validating geometry.

Add debug render modes:

* wireframe
* BSP nodes/leaves
* surfaces
* patches
* normals
* clusters/PVS
* lightmap indices
* collision brushes

---

# Tier 3 — Quake III material/shader system

Implement Q3 shader-script parsing and rendering.

Support the important features progressively, including:

* shader lookup
* implicit textures
* multiple stages
* `$lightmap`
* blend functions
* alpha functions
* rgbGen
* alphaGen
* tcGen
* tcMod
* scroll
* scale
* rotate
* stretch/turbulence where required
* animated maps
* clamp maps
* depth write/test behavior
* culling
* polygon offset
* surface flags
* content flags
* sky shaders
* fog
* deformVertexes
* autosprite/billboarding
* portals/mirrors if supported by Q3 content
* dynamic lighting interaction where applicable

Support original texture formats needed by Q3 assets, including TGA/JPEG.

Implement lightmaps correctly.

The goal for this tier is visual comparison against ioquake3.

Add screenshot-based regression testing where practical.

---

# Tier 4 — collision and world tracing

Implement Q3 BSP collision independently from Minecraft collision.

Required capabilities include:

* point contents
* ray/segment traces
* swept bounding-box traces
* brush collision
* BSP traversal
* start-solid/all-solid handling
* surface/content metadata
* stepping/sliding primitives as needed later

Expose a clean collision API suitable for:

* player movement
* projectiles
* hitscan weapons
* cgame syscalls
* qagame syscalls
* future Minecraft/Q3 interop

Do not use invisible Minecraft blocks as collision proxies.

---

# Tier 5 — MD3 models and Q3 scene entities

Implement MD3 loading/rendering.

Support:

* surfaces
* frames
* interpolated vertex animation
* skins
* shader assignment
* tags
* tag interpolation
* multipart player models
* weapon attachment
* model bounds

Implement the Quake scene concept needed by cgame:

* ref entities
* models
* sprites
* beams if needed
* dynamic lights
* render definitions/camera
* multiple scene submissions where necessary

Support player model structure such as:

* lower
* upper
* head
* weapon

Implement animation configuration parsing.

---

# Tier 6 — sound

Implement Quake sound support through Minecraft's audio backend.

Support:

* registering sounds
* positional sounds
* local sounds
* looping sounds
* entity-attached sounds
* attenuation
* channel semantics where relevant
* music/background tracks if required

The Quake-side sound API should not know about Minecraft SoundEvents.

Use a backend translation layer.

---

# Tier 7 — QVM runtime

This is a major compatibility milestone.

Implement a sandboxed Quake VM interpreter in Java.

Support standard QVM bytecode and memory behavior sufficiently to execute original Q3 VM modules.

Eventually load:

`vm/qagame.qvm`

`vm/cgame.qvm`

`vm/ui.qvm`

Implement VM:

* bytecode loading
* data sections
* VM memory
* stack behavior
* instruction interpreter
* integer operations
* float operations
* calls/returns
* memory access
* bounds checking
* syscall dispatch
* deterministic behavior where Q3 expects it
* debugging/tracing facilities

Do not load arbitrary native `.dll`/`.so` game modules.

QVM execution is intentionally a security/sandbox boundary.

Provide optional VM diagnostics:

* instruction counts
* syscall counts
* currently loaded module
* unsupported syscall reporting
* VM restart
* VM memory inspection in development mode

---

# Tier 8 — Quake engine syscall ABI

Implement the engine contracts expected by:

* qagame
* cgame
* ui

This is one of the most important architectural layers.

Syscalls should translate Q3 engine requests into CraftQ3 subsystems.

Major syscall families include:

* filesystem
* cvars
* command execution
* config strings
* game state
* snapshots
* user commands
* collision traces
* entity linking/world queries
* renderer
* model registration
* shader registration
* skin registration
* scene submission
* sound
* input/key handling
* timing
* console output
* memory where required

Use ioquake3/original Q3 as a compatibility oracle for syscall semantics.

Unsupported syscalls must fail clearly and appear in diagnostics rather than silently doing the wrong thing.

---

# Tier 9 — pure Quake gameplay

At this point the goal becomes:

> Vanilla Q3 gameplay should run primarily because original qagame/cgame QVMs are executing, not because CraftQ3 has hard-coded the weapons and rules in Java.

Avoid duplicating gameplay rules in Java when the QVM is meant to own them.

For example, Java should ideally not contain logic such as:

`rocket launcher damage = 100`

unless required as part of an engine-level contract.

The QVM should determine game behavior.

Implement the systems necessary for:

* spawning
* player state
* entity state
* input/usercmd
* snapshots
* local prediction
* weapon presentation
* pickups
* damage
* scoring
* game types
* respawning
* match state

Implement Q3 movement faithfully.

Do not rely on Minecraft movement physics.

Movement compatibility should include concepts such as:

* acceleration
* friction
* air acceleration
* jumping
* sliding
* stepping
* knockback
* movement clipping
* jump pads

Movement should eventually feel correct enough for strafe jumping and rocket jumping.

---

# Tier 10 — console, commands, cvars, config files

Implement a Q3-style console environment.

Eventually pressing the configured Quake console key in Q3 mode should display the Q3 console rather than Minecraft chat.

Support:

* cvar registration
* cvar modification
* flags where relevant
* command registration
* command buffer
* `exec`
* config files
* key binds
* aliases if appropriate
* common Q3 console commands
* mod-controlled commands
* command completion if practical

Examples that should eventually work conceptually:

`map q3dm17`

`g_gametype 4`

`fraglimit 20`

`timelimit 10`

`cg_fov 110`

`bind mouse2 +zoom`

`exec autoexec.cfg`

CraftQ3-specific administrative commands can remain under `/q3`.

---

# Tier 11 — UI VM

Support `ui.qvm`.

Long-term goal:

Original Quake III menus can render and function inside Minecraft.

Support:

* menu drawing
* fonts/text
* input
* cvars
* server listing APIs when networking is implemented
* game/mod selection as appropriate

Pure Q3 mode should eventually hide:

* Minecraft HUD
* Minecraft menus
* Minecraft crosshair
* Minecraft hand rendering
* normal world rendering
* Minecraft chat unless intentionally exposed

---

# Tier 12 — demos and fidelity testing

Protocol-68 recording and playback are implemented, including original cgame presentation, local/remote recordings, original menu access and native-server restart/map-transition checks. Protocol-43 payload playback and broader demo/video fidelity coverage remain incomplete. See [demo usage](docs/DEMO_PLAY.md).

Demo playback is a major regression tool.

Create a compatibility test harness capable of:

* loading known demos
* advancing to deterministic timestamps/frames
* capturing screenshots
* comparing against reference ioquake3 screenshots
* reporting visual differences

Track fidelity in `COMPATIBILITY.md`.

Useful categories include:

* BSP rendering
* patch rendering
* lightmaps
* shader stages
* effects
* models
* animation
* player movement
* QVM behavior
* audio
* demos

A developer overlay should expose data such as:

* map
* BSP leaf
* PVS cluster
* visible surfaces
* active shader
* Q3 time
* VM state
* snapshot number
* player coordinates/velocity

---

# Tier 13 — Quake III networking

Implement enough of the Quake III network protocol to connect to compatible Q3/ioquake3 servers.

This is a stretch goal, but architecture must not make it impossible.

Potential features:

* connectionless packets
* challenge/connect flow
* reliable commands
* netchan
* snapshots
* configstrings/game state
* server commands
* usercmd transmission
* downloads where appropriate
* pure/pak verification semantics
* protocol compatibility
* server browser
* master-server queries

Major milestone:

> CraftQ3 running inside Minecraft connects as a client to a real Quake III/ioquake3 server.

Do not compromise security to achieve compatibility.

---

# Tier 14 — CraftQ3 server

Very long-term stretch goal:

Implement a server mode capable of hosting Q3 gameplay from a Minecraft/Fabric server process.

The server should execute qagame QVM logic and eventually speak a Q3-compatible network protocol.

Ultimate demonstration:

> A normal Quake III/ioquake3 client connects to a server process that is actually Minecraft + Fabric + CraftQ3.

Minecraft clients and Q3 clients sharing a compatible server is an aspirational future feature, not an early requirement.

---

# Tier 15 — AAS and bots

Implement Quake III AAS support and relevant botlib behavior.

Targets:

* AAS file parsing
* navigation areas
* reachability
* routing
* bot movement
* bot AI interfaces required by Q3

Eventually commands such as:

`addbot sarge`

should work because the original Q3 bot systems are functioning, not because CraftQ3 substitutes Minecraft mobs.

---

# Tier 16 — Team Arena and Q3 mods

After vanilla Q3 is stable, target:

1. Quake III Team Arena
2. standard QVM-based Quake III mods
3. ioquake3-compatible extensions where sensible

Important test mods include:

* Alternate Fire 2.0
* Super Hero Arena
* other mods that ship standard QVM modules

Mod switching should work similarly to `fs_game`.

A mod should be able to replace:

* assets
* shaders
* sounds
* qagame
* cgame
* ui

without CraftQ3 containing mod-specific Java code.

If a mod uses native DLL/SO game modules rather than QVMs, mark it unsupported unless a safe future solution is explicitly designed.

Do not JNI-load arbitrary old native Q3 modules.

Create a mod compatibility diagnostics screen/command showing:

* detected QVM modules
* required syscalls
* unsupported syscalls
* native modules detected
* likely compatibility status

---

# Tier 17 — cinematics and remaining engine features

Implement less-central Q3 engine features needed for high compatibility, including where applicable:

* RoQ cinematics
* fonts
* localized/text assets if relevant
* server browser
* master-server communication
* downloads
* pure server semantics
* screenshots
* miscellaneous renderer behavior
* unusual shader features
* edge-case filesystem behavior

Use real Q3 content and mods to drive compatibility priorities.

---

# Minecraft interoperability mode

This is an important long-term goal, but it must remain separate from pure Quake fidelity.

CraftQ3 should ultimately have three conceptual modes:

## Pure Q3 mode

Goal: behave and look like Quake III.

Minecraft gameplay and visual elements are hidden.

## Interop mode

Quake III and Minecraft systems coexist.

## Normal Minecraft mode

Normal Minecraft is running, but selected Q3 systems can be invoked.

---

# Interop feature goals

Design APIs now so these are possible later.

## Q3 weapons in Minecraft

A Minecraft player should eventually be able to use actual Q3 weapon behavior in a Minecraft world.

Examples:

* railgun
* rocket launcher
* plasma gun
* lightning gun
* BFG
* gauntlet

Where possible, preserve Q3 timings, projectile behavior, damage rules, knockback, etc.

Translate results into Minecraft entities/blocks through an interop adapter.

Example:

Q3 weapon logic
→ Q3 trace/projectile
→ hybrid collision query
→ Minecraft entity hit
→ translated Minecraft damage/knockback

---

## Minecraft player in Q3 maps

Allow Steve/Alex or another Minecraft player representation to exist inside Q3 BSP geometry.

Possible movement modes:

* Minecraft physics
* Quake physics

Quake-physics Steve should eventually be possible.

---

## Minecraft blocks placed in Q3 maps

This is a major interop target.

Do not alter or voxelize the BSP.

Instead use two overlapping geometry layers:

* immutable Q3 BSP world
* mutable Minecraft block layer

Both should participate in rendering and collision.

A Minecraft player should eventually be able to place blocks on Q3 floors/walls and construct structures inside Q3 maps.

---

## Hybrid collision

Design collision so a query can eventually consider:

* Q3 BSP geometry
* Minecraft blocks
* Q3 entities
* Minecraft entities

A rocket or player should resolve the nearest valid collision across both worlds.

Do not hardwire Q3 collision APIs directly to BSP-only assumptions if a clean composition layer can avoid it.

---

## Minecraft mobs in Q3 maps

Eventually support Minecraft entities moving and fighting inside Q3 geometry.

Navigation can be limited initially.

Possible future work:

* generated Minecraft navigation from Q3 geometry
* navigation adapters
* AAS/Minecraft integration

---

## Q3 players/bots in Minecraft worlds

Eventually support MD3-rendered Q3 entities using Quake movement/weapons inside ordinary Minecraft terrain.

A future milestone might be:

`/q3 spawnbot xaero`

in a normal Minecraft world.

---

## Cross-world portals

A major demonstration feature:

Create portals between Minecraft terrain and a Q3 map.

Eventually:

* render the destination through the portal
* walk seamlessly through
* switch HUD/movement rules
* allow entities/projectiles to cross
* preserve shared coordinates

Example:

Normal Minecraft overworld
→ visible portal to q3dm17
→ player enters
→ Quake HUD and controls take over

---

# Coordinate systems

Create an explicit coordinate conversion abstraction.

Do not scatter Q3-to-Minecraft scale/orientation conversions throughout the code.

Support:

Q3 coordinates
↔ CraftQ3 canonical/world coordinates
↔ Minecraft coordinates

Document:

* units/scale
* handedness
* axis mapping
* rotation conventions
* bounding-box conventions

Interop will depend heavily on this being correct.

---

# Rendering architecture

Do not make Q3 rendering merely a collection of ad hoc Fabric draw callbacks.

Create a Q3-oriented intermediate representation.

Conceptually:

Q3 assets/cgame
→ CraftQ3 RenderScene
→ CraftQ3 RenderBackend
→ Fabric/Minecraft GPU APIs

RenderScene should support Q3 concepts such as:

* world surfaces
* ref entities
* models
* sprites
* shader/material references
* dynamic lights
* fog
* camera/refdef
* portals if needed

The Fabric backend translates this into the rendering facilities available in the pinned Minecraft version.

This separation is critical.

---

# Security

Treat old game data and mods as untrusted input.

Requirements:

* bounds-check binary parsing
* validate offsets/counts
* avoid ZIP-slip/path traversal
* cap unreasonable allocations
* sandbox QVM memory
* validate VM instructions
* validate syscalls
* never allow QVM filesystem calls outside permitted virtual paths
* never execute arbitrary native binaries
* fail safely on malformed content

Fuzzable parsers are preferred.

---

# Performance

Do not prematurely optimize before correctness, but design for eventual real-time Q3 workloads.

Likely important systems include:

* PK3 caching
* texture caching
* shader compilation/caching
* BSP visibility/PVS
* patch mesh caching
* lightmap atlases
* MD3 interpolation
* scene batching
* collision acceleration
* VM execution speed
* network snapshot processing

A QVM interpreter is acceptable initially. A faster execution strategy/JIT may be explored much later only if needed.

---

# Testing strategy

Testing is a first-class requirement.

Use:

## Unit tests

For:

* PK3 ordering
* path normalization
* binary readers
* BSP lump parsing
* shader parsing
* MD3 parsing
* QVM instructions
* coordinate transforms
* collision primitives
* cvars/commands

## Golden/reference tests

Use known Q3 assets supplied locally by the developer but never committed if copyrighted.

Compare:

* parsed counts
* geometry
* traces
* shaders
* demo state
* screenshots

against ioquake3/original Q3 where useful.

## Integration tests

Examples:

* load baseq3
* load q3dm17
* locate spawn entities
* trace against floor
* render fixed camera
* execute QVM startup
* run VM syscalls
* switch mods

## Diagnostics

Prefer explicit developer tooling over hidden magic.

Commands might include:

`/q3 debug bsp`

`/q3 debug collision`

`/q3 debug shaders`

`/q3 debug vm`

`/q3 debug scene`

`/q3 debug pvs`

`/q3 fs which ...`

---

# Code-quality rules

Use modern Java appropriate for the pinned Minecraft/Fabric version.

Prefer:

* immutable data structures where useful
* explicit binary format types
* bounded readers rather than unsafe index arithmetic
* small subsystem interfaces
* dependency inversion between Q3 core and Fabric
* descriptive exceptions
* structured logging
* automated formatting/linting
* tests for parsers and VM instructions

Avoid:

* giant god classes
* global Minecraft state in core code
* static mutable state everywhere
* parsing/rendering tightly coupled together
* Minecraft classes in format parsers
* hard-coded map names
* hard-coded vanilla Q3 gameplay rules
* converting maps to Minecraft blocks
* depending on resource-pack conversion
* native module loading

---

# User experience

Eventually CraftQ3 should support something conceptually like:

1. Install Fabric + CraftQ3.
2. Launch Minecraft.
3. Point CraftQ3 at a legally owned Quake III installation or game-data folder.
4. CraftQ3 discovers baseq3 PK3s.
5. Run:

`/q3 map q3dm17`

6. Minecraft rendering/UI disappears.
7. Quake III appears.

Later:

`/q3 game alternatefire`

`/q3 map q3dm6`

should launch the mod using its original QVMs/assets.

Returning to Minecraft should be clean and intentional.

---

# Definition of the first major success

Do not begin with guns, bots, networking, or interop.

The first major visual milestone is:

> Launch Minecraft, load the user's real `q3dm17.bsp` from the real Quake III PK3 hierarchy, position a camera at a spawn point, and render the map with correct geometry, textures, curved patches, and lightmaps, with no Minecraft world pixels visible.

This milestone proves the fundamental idea.

The next milestones are:

1. PK3 VFS
2. BSP geometry
3. PVS/visibility
4. Q3 shaders and lightmaps
5. collision
6. MD3 rendering
7. QVM interpreter
8. qagame/cgame syscall compatibility
9. Quake movement/gameplay
10. UI/console
11. demos
12. networking
13. bots
14. mods/Team Arena
15. Minecraft interoperability

---
