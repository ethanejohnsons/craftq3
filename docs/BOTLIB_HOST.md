# Original QVM bot service boundary

`craftq3-server` supplies `BotlibHost`, a Java service called by the original qagame QVM through its published botlib syscall numbers. The original VM retains bot decisions. The host currently implements the engine-facing services below; autonomous bot gameplay is incomplete and `bot_enable` remains disabled by default.

`BotlibHost` borrows the mounted virtual filesystem and active BSP. Its `Host` adapter supplies client capacity, original client command/usercmd delivery, snapshot entity enumeration, reliable messages, solid collision traces, individual entity traces and world point contents. `Q3Server` owns the bridge and closes it with the server. Bot allocation and release reset per-slot inputs, reliable cursors and cached snapshots. Synchronous guest callbacks use the interpreter's bounded nested invocation support.

## Implemented calls

| Calls | Behavior |
|---|---|
| 200–207 | Setup/shutdown, bounded string variables, global script defines, monotonic frame time, active-map AAS loading and copied entity updates |
| 209–211 | Snapshot entity IDs, independent reliable-message consumption, retail/modern usercmd decoding |
| 300–318 | Runtime routing-area disable, box/point/segment area queries, area/entity information, presence boxes, time, BSP epairs, cluster/portal travel times, swimming contents and bounded movement prediction |
| Modern 400–423 / retail 400–426 | Verified elementary-action imports, including movement, view, held actions, messages and the 40-byte input record; four legacy inventory commands remain unsupported |
| 507–524, 569–570 | Chat state, bounded console queues, initial/reply expansion, string utilities, file loading, match extraction and output |
| 500–506 | Character handles, original skill-anchor lookup/interpolation, bounded numeric queries and bounded string copies |
| 525–531, 533–534, 540, 543–544, 546–547, 571, 573 | Bounded goal stacks/avoid timers, owned goal records and retained item-weight scripts |
| 532, 537–539, 541–542 | Item display names, geometric touching/missing-item queries, static discovery and live entity association |
| 548, 555–557, 574 | Movement allocation/reset/free, owned 68-byte initialization and bounded avoid-spot storage |
| 558–563 | Configuration-driven weapon state, weight scripts, inventory ranking and 552-byte weapon metadata |
| 567–568 | Map-authored camp cursors and named locations |
| 578–581 | Bounded script source handles, token reads, free and source location |

The AAS, character, elementary-action, goal, weapon, movement and script implementations live in `craftq3-botlib`. See [navigation](BOTLIB_NAVIGATION.md), [elementary actions](BOTLIB_ACTIONS.md) , [goal state](BOTLIB_GOALS.md) , [movement state](BOTLIB_MOVEMENT.md) and [script sources](BOTLIB_SCRIPTS.md) for their independent algorithms and validation.

The retail and 1.32 profiles have different elementary-action import ordering. The explicit retail table maps its 400–426 range to the verified action services; imports 402–405 retain explicit unsupported inventory operations. Retail 300–302 denote obsolete AAS visibility services and are also explicit unsupported calls. Other currently implemented botlib imports share their verified numbering. They use different 24-byte usercmd field arrangements, selected by the explicit `GameAbi` profile. Guest pointers always refer to QVM memory, never Java/native addresses.

## Structs and bounds

All numeric words are little-endian, with floats transferred by their IEEE-754 bit patterns. These are explicit wire layouts:

| Record | Bytes | Significant offsets |
|---|---:|---|
| `bot_entitystate_t` | 112 | type 0, flags 4, origin 8, angles 20, old origin 32, mins 44, maxs 56, ground entity 68, model 76, weapon 100 |
| `aas_entityinfo_t` | 140 | valid 0, type 4, flags 8, update time 12, interval 16, number 20, origin 24, last visible origin 60, mins 72, maxs 84, ground entity 96 |
| `aas_areainfo_t` | 52 | contents 0, flags 4, presence 8, cluster 12, mins 16, maxs 28, center 40 |
| `bot_input_t` | 40 | think time 0, direction 4, speed 16, view angles 20, action bits 32, weapon 36 |
| `pc_token_t` | 1040 | type 0, subtype 4, integer 8, float 12, NUL-terminated token text 16 |

Entity updates own their 112 source bytes. Entity validity expires at the next botlib frame until refreshed. All output structures and arrays are range checked before writing; an invalid token-output buffer does not consume a token. Script handles are positive, bounded and never recycled by their provider. Botlib shutdown frees its open script sources, held actions and character handles.

Item metadata is loaded lazily from `items.c` or `itemconfig`. The active BSP supplies map items; `ItemPlacement` composes actual server collision with AAS goal localization. Unsupported airborne suspended-item trajectories are diagnosed and omitted. AAS entity visibility history retains the previous supplied origin, including successive updates in one frame, allowing the registry to reject moving items. Automatic avoid timers use registered item respawn metadata.

Weapon configuration is loaded lazily on its first service call from `weapons.c` or the `weaponconfig` variable; parse failure is explicit.

The bridge caps entity state at 1024 entries, client actions at 64, variables at 1024 entries, variable names at 255 characters, values at 8191 characters and AAS query outputs at 65536 entries. Providers also bound parsing, source expansion, graph search and spatial-query work. Invalid memory, nonfinite geometry/time, malformed records and unsupported calls fail with the VM module, syscall and instruction context.

## Current limits

Route timing uses `AasRouteTimes`, including origin-dependent local costs and native-observed cluster/portal cache semantics. Cache keys include immutable area-disable policy snapshots, so disabling or re-enabling an area cannot reuse a stale result. Default bounds are 256 entries, 64 MiB and 20 million work units; budget exhaustion or an unsupported accumulated cost above 65535 fails explicitly. Enabling an area marked disabled in the file needs a future routing-topology update and fails explicitly. The loaded AAS is bound to the active map name; its retained BSP checksum is not yet checked against the BSP bytes by this bridge.

BSP numeric epairs use strict Java numeric parsing. Movement prediction supports the measured dry axis-aligned domain through 318; unverified slopes, ceilings, fluids, low-clearance transitions and additional stop events fail explicitly. Alternate-route searches, debug drawing and movement behavior remain incomplete and fail by named syscall. Item selection calls 535/536 decode bounded 256-word inventories, optional long-term goals and finite nearby travel limits, and connect original item/weight data to `BotItemSelector` and exact route timing. `AasReachabilityArea` supplies the native-observed ground/mover and fuzzy-area lookup, preserving retained entity model metadata through unlinking and frame invalidation. Its independent 167,000-query audit covers all 30 original AAS maps and 17 mover models; see the provider documentation for the float-contraction policy. A successful map initialization does not establish working autonomous bots.

## Validation and provenance

`BotlibHostTest` uses only authored memory images, BSP/AAS geometry and scripts. It checks both usercmd profiles, action flags and float bits, state ownership/expiry, all major struct offsets, sentinel bytes around outputs, token lifetimes, lifecycle resets, malformed pointers/capacities, map binding, area/segment output order and routing disable/enable behavior.

The opt-in `:craftq3-server:auditBots` task loads locally supplied original game assets and QVMs. It enables bots only in that process, runs `GAME_INIT`, connects a local client and asks original qagame to `addbot sarge 3`. It prints the next missing service as a **checkpoint**, not a passing gameplay result. `-Pq3Installation=...`, `-Pq3Map=...` and optional `-Pq3AuditVm=...` select local audit inputs. Retail qagame now loads character, item/weapon weights, chat and movement state, performs item association, and executes bot frames and usercmd callbacks. The q3dm1 audit reaches time 1.35 seconds and stops at query 535 because its `BotReachabilityArea` dependency remains unverified. This remains a checkpoint, not autonomous bot gameplay.

This is an independent Java implementation from public ABI declarations, format documentation and bounded behavior probes. No upstream engine function bodies were copied or mechanically translated. Original game files and native reference harnesses are ignored development inputs and are not packaged.

Primary declarations: [id Software game imports](https://github.com/id-Software/Quake-III-Arena/blob/master/code/game/g_public.h), [public botlib interface](https://github.com/ioquake/ioq3/blob/main/code/botlib/botlib.h), [AAS query records](https://github.com/ioquake/ioq3/blob/main/code/botlib/be_aas.h), and [AAS navigation file records](https://github.com/ioquake/ioq3/blob/main/code/botlib/be_aas_def.h).

Retail import numbering is documented by the [Lilium Arena Classic compatibility header](https://github.com/zturtleman/lilium-arena-classic/blob/master/code/game/g_public.h) and corroborated by actual original qagame import calls. Only its public declarations are used; no compatibility implementation bodies are incorporated. The scoped guest tests cover legacy reset/get/move/view/weapon/action dispatch, item association/reset, and exact goal-visibility time boundaries. See [chat](BOTLIB_CHAT.md), [weapons](BOTLIB_WEAPONS.md) and [item placement](BOTLIB_ITEM_PLACEMENT.md) for provider limitations.

Retail chat ABI uses 150-byte text, a 224-byte match record (type152, subtype156, eight capture records at160) and a 172-byte console record. Modern chat uses 256-byte text, a 328-byte match and a 276-byte console record. The retail layout is verified from original QVM argument/frame offsets and live execution; packing preserves the retail text-alignment padding and per-capture padding. Failed matches publish their documented partial writes. Retail implicit text capacities are applied before matching, queueing and in-place synonym replacement. Explicit caller capacities remain range checked.

Native entity-history probes verify prior-origin retention, initial update interval from time zero, and unlink-only null updates preserving information until frame validity expires. Runtime presence boxes use normal maxZ32 and crouch maxZ8, independently of the compiler expansion boxes stored in the AAS file. Dynamic linkage persists through frame invalidation and is removed only by explicit null updates. Solid-dependent bounds and the engine rotated-model radius policy are described in [the movement contract](BOTLIB_MOVEMENT.md).

The shared host random stream supplies undecided fuzzy weights and chat services. It is seeded before initial setup and retained across fast restart; Java seed reproducibility is a policy, not a claim of native engine RNG stream identity. Reply variable pointer zero preserves a captured value, while a nonzero pointer to an empty string explicitly replaces it. Guest tests cover both behaviors and both chat ABI profiles.
