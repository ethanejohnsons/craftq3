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
| Patch tessellation | Error-based LOD (`r_subdivisions`) | Uniform 8 subdivisions | Static upload once per map; tracked as a gap |
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
| `trap_UpdateScreen` | Re-enters the cgame draw | Presents the frame drawn so far | Avoids re-entrant VM calls during loading screens |
| Fonts (`trap_R_RegisterFont`) | Pre-rendered `.dat` or FreeType | Pre-rendered `fonts/fontImage_N.dat` only | Retail content ships pre-rendered fonts |
| Protocol 48 (1.27) demos | Not playable by 1.32 either | Refused with a clear message | Layout only partly decoded (see `DemoProtocol.Q3_1_27`) |

| Downloaded PK3s | Written into the game directory and mounted from then on | Kept in a host-only cache (`<home>/.downloads`), mounted only while connected to a server that references them; reconnecting takes them from the cache | A server's packs never change local or other servers' games |
| `trap_UpdateScreen` frames | Swapped to the screen inside the VM call | Handed to `RenderSink.submitImmediate`; whether they show before the call returns is the host's choice | The host owns presentation |

## Session (`engine:session`)

| Behaviour | Original | Rewrite | Why |
| --- | --- | --- | --- |
| Server cvars seen by the client in a local game | Client and server share one cvar table | Separate tables; the local server's serverinfo configstring is mirrored into client cvars (client-owned cvars never overwritten, reset on disconnect); server-only cvars without `SERVERINFO` are not visible | The server runs on its own thread with its own console |
| `rcon` while playing a local game with no `rconAddress` | An `rcon` packet to the listen server over loopback, checked against its `rconPassword` | Runs on the local server's console directly, without the network (still requires `rconPassword` to be set) | Same effect without opening a socket |
| `host_ui [0\|1]` | Not present | Shows or hides the platform's own user interface (also shown when the ui VM cannot run) | CraftQ3 extension: the user is never left on an empty screen |
| `.dm3` demos (protocol 43, pre-1.17) | Not playable by 1.32 | Refused with a clear message | 1.32 cannot play them either; the layout is not decoded |
| Cgame start after a gamestate | Same frame (`CL_InitCGame` from the gamestate) | One or more client frames later, once the map was parsed and its collision built on a worker (same frame for demos and synchronous runs) | Keeps the render thread responsive while the map loads |
| Host map hooks | Maps come from the game files only | `ClientHostServices.mapOverride` may supply a map, `worldSources` adds collision geometry to client world traces (as the server's map sources do) | CraftQ3 extension for levels the Minecraft bridge synthesizes |

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
