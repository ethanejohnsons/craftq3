# Divergence ledger

Places where the rewrite deliberately behaves differently from the original Quake III 1.32
engine or from the legacy CraftQ3 code. Each entry says why. Anything not listed here is meant to
match the original; a mismatch found against the original is a bug.

## Collision (`engine:collision`)

| Behaviour | Original | Rewrite | Why |
| --- | --- | --- | --- |
| Capsule vs capsule | Pairs top-with-top and bottom-with-bottom spheres; stacked capsules can sink up to 26 units | True combined (Minkowski) capsule, vertical only | Original is a known bug; baseq3 does not use capsule traces |
| Transformed trace plane distance | Reported in model space | Reported in world space | Callers compare against world positions |
| Rotated capsule axis | Rotation error in the original | Correct rotation | Bug fix; no retail content relies on it |
| Box vs capsule bounds | Swept bounds not recomputed after swapping roles | Recomputed | Bug fix |
| Point contents of a capsule model | Tests the bounding box | Tests the capsule | Consistency with capsule traces |
| Oversized patches | Map load fails (`Com_Error`) | Patch skipped with a warning | Never kill a session over one surface |
| Patch hit plane type/signbits | Taken from facet plane as stored | Recomputed | Consistency |

## Filesystem (`engine:fs`)

| Behaviour | Legacy CraftQ3 | Rewrite | Why |
| --- | --- | --- | --- |
| Loose files vs PK3s in one game directory | Loose first | PK3s first (original 1.32); `SearchOrder.LOOSE_FIRST` available | Matches the original engine |
| Unreadable or empty PK3 | Mount fails | Archive skipped with a warning | Matches the original engine |

## Console (`engine:console`)

| Behaviour | Original | Rewrite | Why |
| --- | --- | --- | --- |
| Command buffer capacity | 16 KiB | 128 KiB | Mod configs in the corpus exceed 16 KiB |
| Writing configs with quotes or newlines in values | Written verbatim | Entry skipped and reported | Prevents command injection through archived values |

## Audio (`engine:audio`)

| Behaviour | Original | Rewrite | Why |
| --- | --- | --- | --- |
| Doppler, underwater filter, identical-loop merging | Present | Not implemented | Not observable at Minecraft's mixer quality; tracked as gaps |

## Rendering (`engine:render-ir`)

| Behaviour | Original | Rewrite | Why |
| --- | --- | --- | --- |
| `noise` wave function | Engine noise table | 32-bit GPU-reproducible hash | Must evaluate identically in GLSL |
| Patch level of detail by distance | Grid rows and columns whose error is negligible at the viewer's distance are skipped each frame (`r_lodCurveError`) | The full `r_subdivisions` grid is always drawn | Static upload once per map |
| Patch edge stitching | Samples are inserted wherever a neighbouring grid's border vertices fall on a grid's border | Samples are shared only along boundary spans whose three control points are identical | Covers the edges compilers share between patches, with bit-identical vertices; geometric matching added a few dozen vertices on id maps but cascaded on overlapping community patches (`simpsons_q3`: +23% triangles, 5× build time) |
| Patch normals | Recomputed from the tessellated grid | Interpolated from the control normals and renormalised | Keeps the compiler's smoothed normals |
| `RT_BEAM` placement | Tube offsets transformed by the entity's axes (usually left zero by cgames) | Tube from `origin` to `oldorigin` in world space | The original's result depends on unset axes; no retail cgame submits `RT_BEAM` |
| Stencil shadows (`cg_shadows 2`) | Shadow volumes through the stencil buffer | Not drawn, so `RF_NOSHADOW` has no effect | No stencil attachment in the Blaze3D targets; tracked as a gap |
| Projected shadows (`cg_shadows 3`) | Enabled by the renderer reading `cg_shadows`, drawn for `RF_SHADOW_PLANE` | Drawn for `RF_SHADOW_PLANE` (`FrameBuilder.Options.projectionShadows`) | The renderer has no cvars; cgames set the flag only for `cg_shadows 3`, so the result is the same |
| Entity light direction without a world | The last loaded map's sun, or none before any map loaded (ambient only) | The default sun `(0.45, 0.3, 0.9)` | Menu models before the first map would otherwise lose their directed light |
| Dynamic lights on entities | `16 r² / d²` bytes, doubled by hardware overbright only in full screen | Always doubled (`r_overBrightBits 1` with gamma) | Matches the overbright the light grid and lightmaps already assume |
| `skyparms` inner (near) box | Images loaded but never drawn | Not drawn by default; `Q3RenderBackend.drawSkyNearBox(true)` draws it alpha-blended over the clouds | The opt-in is an extension for maps authored for engines that draw it |
| `videoMap` timing | Advanced by the client's real-time clock, uploaded into a 256×256 scratch image | Frame `floor(shaderTime × rate)`, wrapped at the file's length, at the RoQ's own size | GPU-side shader time already drives every other stage |
| A view without `RDF_NOWORLDMODEL` while no map is loaded | `ERR_DROP` ("NULL worldmodel") | View skipped with a warning | Never kill a session over one view |
| `RDF_HYPERSPACE` | Clears the view's colour to the time's grey | Also clears the view's depth (already cleared for every view) | Blaze3D clears colour and depth regions together |

## Client (`engine:client`)

| Behaviour | Original | Rewrite | Why |
| --- | --- | --- | --- |
| Server-sent systeminfo cvars | Any cvar may be set | Only SYSTEMINFO-flagged or user-created cvars | A malicious server could otherwise rewrite arbitrary client cvars |
| `trap_SetCDKey` | Writes `q3key` | Kept in memory only | Never write key material to disk |
| `trap_UpdateScreen` outside `CG_INIT` (ui calls, other cgame calls) | Re-enters the cgame draw | Hands over the frame drawn so far | Avoids re-entrant VM calls; inside `CG_INIT` it re-enters `CG_DRAW_ACTIVE_FRAME` as the original does (the loading pacifier), with the ui's connect screen as overlay |
| Fonts (`trap_R_RegisterFont`) | Pre-rendered `.dat` or FreeType | Pre-rendered `fonts/fontImage_N.dat` only | Retail content ships pre-rendered fonts |
| Protocol 43 (1.09 `.dm3`) and 46/48 (1.25–1.27 `.dm_48`) demos | Not playable by 1.32 | Played through the same snapshot path | Extension; the layouts were measured from the demos (see `DemoProtocol`) |

| Downloaded PK3s | Written into the game directory and mounted from then on | Kept in a host-only cache (`<home>/.downloads`), mounted only while connected to a server that references them; reconnecting takes them from the cache | A server's packs never change local or other servers' games |
| `trap_UpdateScreen` frames | Swapped to the screen inside the VM call | Handed to `RenderSink.submitImmediate`; whether they show before the call returns is the host's choice (the Fabric screen shows them, at most one per 15 ms) | The host owns presentation |

## Session (`engine:session`)

| Behaviour | Original | Rewrite | Why |
| --- | --- | --- | --- |
| Server cvars seen by the client in a local game | Client and server share one cvar table | Separate tables; the local server's serverinfo configstring is mirrored into client cvars (client-owned cvars never overwritten, reset on disconnect); server-only cvars without `SERVERINFO` are not visible | The server runs on its own thread with its own console |
| `rcon` while playing a local game with no `rconAddress` | An `rcon` packet to the listen server over loopback, checked against its `rconPassword` | Runs on the local server's console directly, without the network (still requires `rconPassword` to be set) | Same effect without opening a socket |
| `host_ui [0\|1]` | Not present | Shows or hides the platform's own user interface (also shown when the ui VM cannot run) | CraftQ3 extension: the user is never left on an empty screen |
| Cgame start after a gamestate | Same frame (`CL_InitCGame` from the gamestate) | One or more client frames later, once the map was parsed and its collision built on a worker (same frame for demos and synchronous runs) | Keeps the render thread responsive while the map loads |
| Host map hooks | Maps come from the game files only | `ClientHostServices.mapOverride` may supply a map, `worldSources` adds collision geometry to client world traces (as the server's map sources do); `GameLocalServer.factory` decorates the local server's map source (server-only additions, checksum kept) | CraftQ3 extension for levels the Minecraft bridge synthesizes and for the host triggers of carried loadouts |

## Game server (`engine:game`)

| Behaviour | Original | Rewrite | Why |
| --- | --- | --- | --- |
| `map_restart` entity links and portal counts | Stale links kept | Cleared | Avoids ghost links after restart |
| Level drop (ERR_DROP) | Remote clients time out | Clients receive `disconnect` | Clear feedback |
| View cluster -1 | PVS row 0 | Sees everything | Avoids invisible entities when the viewer is outside the map |
| Inverted `bot_goal_t` bounds (Q3Rally) | Undefined | Collapsed to the midpoint | Robustness |
| Bot move goal outside the AAS (Q3Rally) | Botlib call | Original failed-move result returned directly | Same observable result |
| QVM program stack | No bottom check | May extend down to the end of the guest's globals | Q3Rally needs more than 64 KiB, as on the original |
| File handle 0 to FS_Read/Write/FCloseFile | Returns 0 | Returns 0 (not a degradation) | CPMA relies on it |
| Host-driven puppet clients (`connectPuppet`) | No such client kind | Local non-bot slots whose server commands are discarded, without the server's connect/drop prints; freed at once when dropped and at every new level | Extension for `mc:hybrid`'s Minecraft mobs; qagame sees an ordinary local human (`isBot` 0, no `SVF_BOT`), and puppets do not count as players for `cl_paused` |

## Hybrid (`mc:hybrid`)

| Behaviour | Original | Rewrite | Why |
| --- | --- | --- | --- |
| Obituary of a player killed by Minecraft damage | n/a | "was in the wrong place" (`MOD_TRIGGER_HURT`, killer `<world>`, the player loses a point as for any world death); the mob is not named | Incoming damage goes through the game's own `trigger_hurt` so armor, pain, death and respawn stay qagame's, and `hurt_touch` always passes the trigger itself as attacker. Every map entity that can name another attacker (`target_laser`'s activator) uses a means of death the cgame reports as a single-client message without the killer, and making the mob's puppet fire a weapon cannot deliver exact amounts. Naming the mob would need a modified qagame, or rewriting the `EV_OBITUARY` event's killer and means of death in game memory, which is display-only (scores and `PERS_ATTACKER` would still say world) and depends on each mod's `meansOfDeath_t` and event numbering |

## Minecraft adapter (`mc:fabric`)

| Behaviour | Original | Rewrite | Why |
| --- | --- | --- | --- |
| Quake hits on Minecraft mobs | n/a (Minecraft: hits within the 10-tick hurt cooldown only deal their excess) | The mob's hurt cooldown is reset before each frame's Quake damage, then Minecraft armor, resistance and death apply as usual | Quake has its own fire rates; a machinegun would otherwise lose most bullets. The Quake frag (from the puppet's health) can therefore precede the Minecraft death of an armored mob |
| Knockback on a bridged player | Minecraft halves the body's motion and adds the push | The same, computed from the Quake player's velocity, applied as a Quake impulse | Repeated hits saturate as in Minecraft instead of adding up; Quake movement then applies gravity, friction and collision |
| A bridged player's movement packets | Minecraft trusts the client and checks moves | Ignored; the server places the body from the Quake player state every tick | The Quake player state is authoritative, on the integrated and a dedicated server alike |
| Bridge game settings | `fraglimit 20`, `sv_pure 1` | `fraglimit 0`, `timelimit 0`, `g_doWarmup 0`, `sv_pure 0`, `bot_enable 0` | A bridge has no end and no AAS; clients use their own installation's PK3s |
| `quake <map>` from the bridge | n/a | The whole loadout carries into the match; the match's maps carry the host triggers (server-only, `HostTriggerMapSource`), so health is reached with the game's own mega-health and hurt rules | Setting health without cheats; on a map with no room for the trigger models only the inventory carries |
| Burning on a bridged player | Minecraft burns the body (`on_fire` every second) | The same burn is Quake damage; Minecraft health is untouched and a dead Quake player is put out | Quake owns health; lava's own damage stays Quake's lava |
| Pushes on a bridged player (Punch arrows, explosions, melee) | Minecraft's air drag slows the pushed body | The velocity change is a Quake impulse, so a pushed player slides on in the air as Quake players do (a Punch II arrow throws one about 25 blocks) | Quake movement owns the body |
| Explosion damage to a bridged player | Minecraft deals at least one point within range, even behind full cover | Unchanged, also behind BSP walls in building regions; the push is a Quake impulse | Minecraft's own explosion rules |
