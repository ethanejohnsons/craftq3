# Bot character configurations

`botlib.character.BotCharacters` provides the character-loading and characteristic-getter boundary
used by the original qagame VM. It borrows `ScriptSources`, which supplies bounded virtual-file
loading, includes, macros, and native token types. It does not evaluate bot goals or simulate AI.

The public ABI is documented in id Software's
[botlib.h](https://github.com/id-Software/Quake-III-Arena/blob/master/code/game/botlib.h) and the
maintained [character interface header](https://github.com/ioquake/ioq3/blob/main/code/botlib/be_ai_char.h).
The service offers `loadCharacter(path, skill)`, `free(handle)`, float/integer/string getters, and
bounded float/integer getters. The optional string-buffer overload reserves one byte for the
terminating NUL expected by the VM bridge. The bridge remains responsible for writing VM memory.

`loadCharacter` returns a positive handle or zero with a diagnostic. Indices are 0–79. Numeric
getters accept either integer or float values; float-to-integer conversion truncates toward zero
within the representable integer range. Invalid handles, missing values, and incompatible types
produce zero or an empty string with a diagnostic. Bounded getters clamp the numeric result;
inverted bounds, or non-finite float bounds, return zero with a diagnostic.

The independent parser accepts `skill integer { index value ... }`, where each value is an integer,
float, or quoted string. The first matching skill block wins. It skips unselected block bodies with
bounded brace nesting, correctly distinguishing string contents from punctuation. Duplicate indices,
bad selected-block syntax, and indices outside 0–79 reject that selection. Temporary script handles
are freed on success and failure.

Native reference probes established these selection rules:

- Requested skill clamps to 1–5 and uses fixed anchors **1, 4, and 5**, even if other blocks exist.
- Each anchor first receives missing values from `bots/default_c.c` at that same anchor.
- Missing files, missing anchor blocks, and invalid selected blocks fall back to the default anchor.
- Between anchors, a value interpolates only when both endpoints have float type. Lower-endpoint
  integers and strings remain unchanged. A lower float with an absent/non-float upper value becomes
  uninitialized; absent lower values remain absent.

Profiles are immutable and retain the selected source path and skill. A normal duplicate load
reuses a cached handle, and normal `free` calls retain cached characters. `setReloadCharacters(true)`
allows `free` to invalidate the requested handle and bypasses anchor-cache reuse for subsequent
explicit anchor loads; already-cached interpolated profiles remain reusable. Default anchor loads
remain cached. An interpolated fallback inherits the default source path, so repeatedly requesting
a missing non-anchor character can allocate additional profiles, as observed in the native service.
`close` clears all profiles without closing the borrowed script service.

There are at most **64 retained profiles**, including defaults and anchors. Script limits apply to
each parse; the character grammar further limits skill-block count to 64 and skipped brace nesting
to 32. Source paths pass through `VirtualPath`; full `botfiles/...` and botlib-relative paths share
canonical cache identity. This intentionally avoids the native cache's case/path alias duplication.
Freed Java handle numbers are never reused, preventing stale handles from becoming valid again.
These handle/path choices are explicit host differences; numeric handle identity is opaque.

The native reader was observed accepting index 80 during parsing while refusing it during getter
access. The bounded Java reader rejects it consistently rather than reproducing that unsafe edge
case. Java also rejects non-finite skill requests and defines safe zero-length string-buffer behavior.
Because `ScriptSources` preprocesses a complete source transactionally, lexical/preprocessor errors
in a later, unselected block can reject a load earlier than the native lazy token reader would.

No original routine body was copied or mechanically translated. An authored host harness links
untouched `be_ai_char.c`, `l_script.c`, and `l_precomp.c` reference objects from the official
[ioquake3 checkout at 588393618dbc82e7207c21c6ddecca229944a03a](https://github.com/ioquake/ioq3/tree/588393618dbc82e7207c21c6ddecca229944a03a).
Only public headers, compilation interfaces, supplied configuration data, and black-box results
informed this implementation. The native code is confined to ignored verification tools and is
neither a runtime dependency nor bundled with the mod.

The numeric audit compiles the reference with **`-ffp-contract=off`** so it uses separate float
operations, matching Java. The default arm64 compiler fused multiply-add in character interpolation
and differed by up to four float steps in some results. This build condition is part of the oracle
definition, not a claim of bit-for-bit parity with every native compiler configuration. Separately,
the corpus audit identified the native decimal token accumulation behavior, which is now implemented
centrally by `ScriptLexer` and verified by parser regression tests.

Eight authored tests cover defaults, fixed anchors, mixed types, malformed selections, cache/free
semantics, bounded getters, strings, immutable ownership, handle budgets, and source cleanup. The
read-only corpus audit loaded all **33** supplied character files at **nine** skills each: **297
profiles**. All **71,280** float, integer, and string getter comparisons matched the specified native
reference exactly, with zero differences. The original PK3 was unchanged; native corpus staging
remains ignored and no commercial bytes are included in tests or committed scripts.

The corpus-only audit needs Java 25 and compiled classes:

```sh
./gradlew :craftq3-botlib:classes
java --class-path craftq3-botlib/build/classes/java/main:craftq3-assets/build/classes/java/main:craftq3-core/build/classes/java/main \
  scripts/AuditCharacters.java /absolute/path/to/games
```

To reproduce the optional differential audit, compile the three untouched reference objects above
with `clang -ffp-contract=off -DBOTLIB`, include the official checkout's `code` directory, and link
them with the authored `scripts/CharacterOracle.c`. Pass the resulting executable and an isolated
native asset root containing `botfiles/chars.h` and the supplied character files as the final two
arguments to `AuditCharacters.java`. The native harness permits reads only beneath that root and
opens no game window, sound device, or network connection.
