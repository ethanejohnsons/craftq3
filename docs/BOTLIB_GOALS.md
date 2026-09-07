# Goal state and avoidance services

`craftq3-botlib` supplies `goal.Goal` and `goal.BotGoals`. These implement owned goal records, handle lifetimes, bounded stacks, avoid timers and retained immutable item-weight scripts. Route selection, item discovery and decisions about which goal to pursue remain separate services. Item metadata/discovery/tracking are documented in [BOTLIB_ITEMS.md](BOTLIB_ITEMS.md).

`Goal` is the published 56-byte `bot_goal_t`: origin at 0, area at 12, mins at 16, maxs at 28, entity number at 40, goal number at 44, flags at 48 and item information at 52. Coordinates are finite 32-bit floats in Q3 units. Game identifiers and flag bits remain opaque integers. Reading or writing a `ByteBuffer` preserves its position and byte order, checks the entire range and rejects inverted bounds.

`BotGoals` takes a clock, diagnostics sink and an optional resolver for automatic avoid durations. It allocates 64 positive handles, choosing the lowest free slot. Freeing a state releases its weights, stack and avoid records; later allocation can reuse that integer handle. An invalid handle produces a diagnostic and an empty/no-op result. Closing the service invalidates all state.

Each handle has seven usable stack entries. Although the public header names an eight-entry array, an isolated native probe verified that the eighth push fails and preserves the previous stack. Empty top/second queries return no record; the QVM bridge leaves the caller's output bytes unchanged in that case. Snapshots own immutable list copies. Reset clears both stack and avoidance while retaining loaded weights; reset-avoid and empty-stack affect only their named state.

Avoidance has 256 entries. Stored expiry values are 32-bit floats. Queries clamp expired durations to zero without removing the record. Updating an existing number takes precedence over recycling an expired slot. New slots require an expiry strictly below the current time, preserving the native zero-time behavior. Full tables ignore new unmatched entries. Negative durations ask the injected item-time resolver; an unknown item leaves state unchanged and emits a diagnostic. The host does not invent item respawn times while item discovery is pending.

The server bridge supports reset/avoid-reset, push/pop/empty, diagnostic dumps, top/second retrieval, avoid-time query/set/remove, allocation/free and item-weight load/free. Original weight scripts are parsed into immutable `WeightConfig` objects and retained by their goal state. Item-name-to-item-information association, item discovery, automatic respawn durations and item selection are still incomplete and fail explicitly when called. Genetic mutation, interbreeding and saving weights are not implemented.

Seven authored goal tests cover record bytes, sentinel buffers, handle exhaustion/reuse, snapshots, overflow behavior, zero/expired/full avoid tables, reset behavior, injected durations and weight lifetime. A separate authored bridge test checks the corresponding guest pointers, owned records and empty outputs. An ignored native harness linked unchanged upstream code and compared **18,527 seeded operations** with the Java service; allocation, stack, avoidance, reset and free results matched. The harness and its native inputs are never packaged.

The local host seeds one Java random generator before initial `GAME_INIT`; it retains that stream across fast restarts and botlib setup/shutdown. This is a reproducibility policy for forthcoming stochastic services, not a claim of native random-stream parity. Goal storage itself does not draw random values.

Implementation was authored independently from [public goal records and declarations](https://github.com/ioquake/ioq3/blob/main/code/botlib/be_ai_goal.h), the [game syscall enumeration](https://github.com/id-Software/Quake-III-Arena/blob/master/code/game/g_public.h) and black-box behavior probes. No upstream engine function bodies were copied or translated.

`GoalQueries` implements contact with the normal standing-presence hull and the original missing-item
visibility predicate. Contact includes closed boundaries and expands local goal bounds before adding
the world origin in float arithmetic. This ordering matters at single-ULP boundaries. Visibility
checks one solid-only point ray to the lower goal corner, ignores the viewer entity, and tests whether
a linked item's last update is strictly older than half a second. The native predicate ignores view
angles, start-solid status, trace hit entity and the entity validity flag; it does not apply a field
of view or range cutoff. The host supplies real collision and entity update times.

Four authored query tests cover boundaries, rounding, trace arguments and short-circuit behavior.
The authored `GoalQueryOracle.c`/`AuditGoalQueries.java` pair compared 12,000 contact cases and 6,000
visibility cases against unchanged native goal routines: **18,000 exact predicate matches**. The
visibility corpus uses controlled traces/entity ages and a five-second clock, and does not validate
world collision itself. All flags and full guest goal records remain owned by their respective
services; these queries do not decide what an item does when collected.

`BotMapGoals` supplies map-authored `info_camp` and `target_location` records. It uses an injected
point-area query, fixed eight-unit goal bounds and zeroed game identifiers. Camp spots in solid
are diagnosed and omitted; named locations retain area zero. Enumeration is reverse map order.
Nonpositive camp cursors start at the first record and successful calls return the next ordinal;
exhaustion leaves guest output unchanged. Location lookup is case insensitive and selects the last
map declaration of a duplicate name. Names retain the native 127-byte limit. Missing/partial origins
retain zero for unread coordinates, while nonfinite values fail validation. Replacement is owned,
transactional and capped at 4,096 combined records.

Three authored map-goal tests cover cursors, ordering, solid handling, truncation, duplicate names,
partial origins and capacity rollback. `MapGoalOracle.c`/`AuditMapGoals.java` compare all 31 original
maps under both solid and non-solid controlled point-area callbacks: **1,253 camp/location record
and cursor checks matched**. Area classification itself has separate AAS validation.
