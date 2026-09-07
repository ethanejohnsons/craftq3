# Bot item metadata and world tracking

`botlib.item.ItemConfig` reads the original `botfiles/items.c` through `ScriptSources` and the
mounted filesystem. Its immutable records retain class/display/model names, model and inventory
indices, types, respawn times and bounds. Parsing is bounded to 256 records, checks finite bounds,
retains duplicate declarations in source order and uses the first matching class for lookup.
No game item table is copied into Java. When metadata defines `item_botroam`, discovery also
retains its roaming flag and map-authored `weight` numeric prefix; ordinary item weights are not
read from that entity property.

`ItemRegistry` discovers matching BSP entities, assigns positive item numbers and exposes them in
reverse creation order. Missing or invalid origins produce diagnostics. Game-type and `notbot`
flags are retained and applied to goal queries. The original enumeration query takes a display
name such as `Red Flag`, despite the parameter being called `classname` in an older public header.
Matching is case insensitive; negative cursors start enumeration and successful queries return the
item number for the next cursor. Invalid or exhausted cursors return no goal.

Placement is an explicit world dependency. A resolver supplies the floor-adjusted item origin,
reachable goal origin and AAS area. An empty result omits an inaccessible suspended item; area zero
retains an item known to be unreachable. Native corpus comparisons below supplied identical
controlled placement callbacks to both implementations. They validate discovery and tracking,
not collision, navigation or jump-pad physics.

Live updates process stationary item entities in ascending entity-number order. Existing entity
identity takes priority over an unbound map item of the same model within strictly 30 units.
Changing an entity's model invalidates its previous item link. Known unmatched models create drops
whose number is the static item count plus entity number. Drops expire strictly after 30 seconds;
movement does not refresh their expiry. Missing entities retain their links until subsequent
replacement or expiry. `EF_NODRAW` does not prevent association. Movement is determined from the
current and last visible origin supplied by the entity service.

Goal records retain original metadata bounds and distinguish ordinary, roaming and dropped items.
Automatic avoidance uses the metadata respawn time, with zero becoming 30 seconds and other values
clamped to at least 10 seconds. This also applies to explicitly queried dropped-item avoidance;
other item-choice timing policies are separate services. Suspended goals retain their launch area
when the live placement resolver identifies that area as a jump pad.

Registry replacement and updates build owned snapshots before publishing. Capacity is 256 items;
overflow during initialization rejects the replacement, while a full live registry diagnoses and
omits an additional drop. Duplicate entity numbers and malformed inputs are rejected.

Authored tests cover parsing, ownership, enumeration/filtering, association priority, boundary
distance, expiry, moving entities and model replacement. Independent drivers linked unchanged,
ignored ioquake3 reference objects and compared:

- All 35 original item declarations: 8,260 metadata bytes matched.
- All 31 original maps across four game types: 5,124 item records and 4,959 goal queries matched
  using identical controlled placement.
- 250 seeded live scenarios over 1,500 frames: 2,662 metadata/goal checks matched using identical
  controlled placement, including a regression where an existing entity link outranks a nearer
  unbound item.

The authored drivers are `scripts/ItemOracle.c`, `AuditItems.java`, `AuditItemDiscovery.java` and
`AuditItemUpdates.java`. Original PK3 contents and native binaries remain ignored and unmodified.
The production service contains independent Java code; it neither links the reference engine nor
extracts game data. Actual AAS placement and qagame syscall integration are tracked separately.

## Long-term and nearby item selection

`goal.BotItemSelector` ranks available items using their original script weights and injected route
queries. Items excluded by game type, unlinked non-roaming items, missing weights and zero-area or
unreachable goals are skipped. Ranking divides the adjusted weight by travel time and retains the
first candidate on ties. A dropped item receives the configured dropped-weight bonus before a
roaming multiplier. Item avoidance is compared against `travelTime * .009` with native double
comparison precision; replacing that literal with a float changes boundary decisions.

Nearby selection takes its maximum directly in AAS travel units and requires travel time strictly
below that maximum. Ordinary items must also have an onward route time no larger than the direct
route to the long-term goal. Dropped items bypass that onward restriction. A null long-term goal
omits it. Zero onward/direct times retain the original comparison behavior. On success the selector
pushes the original item goal and installs metadata-based avoidance, or 10 seconds for a drop.
Selection reports success even if the bounded goal stack reports overflow. The last reachable bot
area survives goal-state reset and is released when its state is freed.

Routing and stochastic fuzzy evaluation are explicit dependencies. This service does not substitute
the existing incomplete stored-cost Dijkstra or deterministic fuzzy evaluator for either original
contract. Those adapters must pass their own checks before qagame item-choice syscalls are enabled.
Seven authored selector tests and `GoalChoiceOracle.c`/`AuditItemChoices.java` validate policy with
identical controlled routing and weights: **4,000 seeded LTG/NBG scenarios across four game types,
52,000 goal and avoid-timer checks, zero native mismatches**. Controlled route callbacks also verify
that onward routing starts at the item's goal origin. These results do not claim autonomous bot
play or full route/weight parity.
