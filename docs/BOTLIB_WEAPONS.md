# Bot weapon configuration and selection

`botlib.weapon.BotWeapons` provides the weapon-state, configuration, and fuzzy-weight boundary
used by the original qagame VM. It borrows `ScriptSources` and uses `WeightConfig` for evaluation.
The original VM still supplies the inventory and owns gameplay rules. The Java service adds no
weapon possession, ammunition, firing, damage, or physics rules.

The public contract comes from id Software's
[botlib interface](https://github.com/id-Software/Quake-III-Arena/blob/master/code/game/botlib.h)
and the maintained
[weapon interface header](https://github.com/ioquake/ioq3/blob/main/code/botlib/be_ai_weap.h).

| Operation | Result |
| --- | --- |
| `setup("weapons.c")` | `0` on success; `12` for an unavailable or malformed configuration |
| `allocate()` | Positive opaque state handle, or `0` when the budget is exhausted |
| `free(handle)` / `reset(handle)` | Free invalidates the state; reset retains its loaded weights |
| `loadWeaponWeights(handle, path)` | `0` on success; `11` on failure |
| `chooseBestFightWeapon(handle, int[256])` | Selected weapon number, or `0` |
| `weaponInfo(handle, number)` | Immutable `WeaponInfo`; invalid queries return `WeaponInfo.EMPTY` |
| `close()` | Clears states and configuration; leaves the borrowed script service open |

An optional diagnostic consumer receives configuration, state, and bounds failures. Configuration
setup clears previous configuration and state weights before loading. Weight loading likewise
clears the state's previous weights before loading, so failed replacement cannot leave dangling
state. Calls after service closure throw `IllegalStateException`; closure itself is idempotent.

Selection visits configured weapon numbers **1–31** in increasing order and looks up each weight
by its case-sensitive weapon name. Missing names are skipped. The largest strictly positive finite
weight wins; positive ties select the smaller weapon number. Zero or negative weights select no
weapon. Exactly 256 inventory integers are required. Metadata queries require an allocated state,
but do not require that state to have loaded weights. Native reset has no ranking-state effect.

`WeaponConfig.load(ScriptSources, path)` accepts `weaponinfo { ... }` and
`projectileinfo { ... }` declarations. Fields use their published integer, float, quoted-string, or
vector types. Signed numeric values and forward projectile references are supported. Vectors have
up to three comma-separated components inside braces; missing components are zero. Unspecified
fields are zero or empty. Duplicate weapon slots retain the last declaration; duplicate projectile
names resolve to the first declaration. An undefined projectile reference rejects the configuration.
Strings are Latin-1 and truncate to 79 bytes, leaving space for the original terminating NUL.

The reader enforces 32 weapon slots, 32 projectile declarations, 256 total declarations, and
128 fields per declaration, in addition to the source and fuzzy-weight parser budgets. The 32-slot
limits match the native default configuration variables; runtime expansion of those limits is not
supported. There are at most 64 active states. Freed Java handle numbers are never reused, so stale
handles cannot target a newly allocated state. Native numeric handle reuse is deliberately not
reproduced; callers must treat handles as opaque.

`WeaponInfo.writeTo(buffer, offset)` writes exactly **552 little-endian bytes**. Its embedded
`ProjectileInfo` begins at offset **344** and occupies **208 bytes**. Both methods validate the
entire destination range and read-only status before writing, and preserve the caller's position
and byte order. All strings occupy 80 bytes; vectors occupy three 32-bit floats. Scalar integers
and floats occupy four bytes.

| Weapon field | Byte offset |
| --- | --- |
| valid, number | 0, 4 |
| name, model | 8, 88 |
| level, weapon index, flags | 168, 172, 176 |
| projectile name, projectile count | 180, 260 |
| horizontal spread, vertical spread, speed, acceleration | 264, 268, 272, 276 |
| recoil, offset, angle offset | 280, 292, 304 |
| extra Z velocity, ammunition amount, ammunition index | 316, 320, 324 |
| activate, reload, spin up, spin down | 328, 332, 336, 340 |
| embedded projectile | 344 |

| Projectile field | Relative byte offset |
| --- | --- |
| name, model | 0, 80 |
| flags, gravity, damage, radius | 160, 164, 168, 172 |
| visible damage, damage type, health increment, push | 176, 180, 184, 188 |
| detonation, bounce, bounce friction, bounce stop | 192, 196, 200, 204 |

Eight authored tests cover complete metadata encoding, weighted selection, ties, state isolation,
reset/free behavior, malformed configuration, duplicate declarations, string truncation, handle
limits, inventory bounds, source cleanup, and destination-buffer ownership. An authored complete
field fixture also checks a native-derived digest of all 552 bytes.

The read-only corpus audit loaded all **32** supplied original weapon-weight files and matched the
native reference for **2,048** inventory/selection pairs. All **31** queryable metadata slots matched
the native reference across their complete 552-byte representation. Neither the original PK3 nor
its contents were modified. No commercial bytes are included in tests or committed scripts.

No original routine body was copied or mechanically translated. The optional authored
`scripts/WeaponOracle.c` harness links untouched `be_ai_weap.c`, `be_ai_weight.c`, `l_struct.c`,
`l_script.c`, and `l_precomp.c` reference objects from the official
[ioquake3 checkout at 588393618dbc82e7207c21c6ddecca229944a03a](https://github.com/ioquake/ioq3/tree/588393618dbc82e7207c21c6ddecca229944a03a).
Only public headers, compilation interfaces, supplied configuration data, and black-box results
informed this implementation. Reference objects are compiled with `-ffp-contract=off` to match
Java's separate float operations. The native tool is confined to ignored verification files and
is neither a mod dependency nor bundled with the mod. Its optional libarchive dependency reads
the supplied PK3 directly into anonymous temporary streams; it creates no game window or device.

Native probes also exposed unsafe failure paths after failed configuration or weight replacement.
The Java service instead returns the documented error/empty result and leaves safely cleared state.
These failure-handling differences are intentional and are covered by tests.

With Java 25 and compiled classes, the corpus-only audit runs as follows:

```sh
./gradlew :craftq3-botlib:classes
java --class-path craftq3-botlib/build/classes/java/main:craftq3-assets/build/classes/java/main:craftq3-core/build/classes/java/main \
  scripts/AuditWeapons.java /absolute/path/to/games
```

For the optional differential audit, compile the five untouched reference objects above with
`clang -ffp-contract=off`, defining `BOTLIB` where required, and include the checkout's `code`
directory. Link them with `scripts/WeaponOracle.c` and libarchive. Pass the resulting executable
and the original `pak0.pk3` path as the final two arguments to `AuditWeapons.java`.
