# Fuzzy weight configurations

`dev.bluevista.craftq3.botlib.weight.WeightConfig` loads the original script-defined item/weapon
weights through `ScriptSources`. It supplies immutable configuration data, deterministic evaluation,
and original undecided evaluation with an injected random stream. It does not invent bot movement,
item-selection policy, weapon policy, or match rules.

`load(ScriptSources, path)` borrows a source service and always frees its temporary source handle,
including on parse failure. `load(VirtualFileSystem, path)` creates and closes its own source
service while retaining the borrowed VFS. An overload accepts explicit `WeightConfig.Limits`.
`weights()` and `names()` preserve source order, `find(name)` returns the first matching index or
`-1`, and `evaluate(index, inventory)` returns a float. Invalid weight indices and undersized
inventory arrays fail clearly. `requiredInventorySize()` reports the highest referenced index
plus one. Public immutable `Value`, `Decision`, and `Branch` records expose the parsed configuration.

## Grammar and observed semantics

The parser accepts `weight "name" { ... }`, inventory-index `switch` blocks, integer `case`
thresholds, `default`, nested branch blocks, numeric `return`, and
`return balance(value, minimum, maximum)`. Includes/macros and Q3 evaluation directives are
handled by the shared source service. Balance bounds and their source order are retained;
deterministic evaluation uses the stored base value.

These are fuzzy thresholds, not an ordinary integer switch. Below the first threshold, evaluation
returns the first branch. Between successive thresholds, it interpolates the two branch results,
recursively evaluating nested decisions against the same inventory. Reaching the last case
threshold changes immediately to the default. For an authored configuration with
`case 10: return 100; case 20: return 200; default: return 0;`, unchanged native botlib and Java give:

| Inventory value | Weight |
| --- | --- |
| 0 or 10 | 100 |
| 15 | 150 |
| 19 | 190 |
| 20 or larger | 0 |

The original threshold `999999` is a sentinel: interpolation toward a branch with that threshold
is bypassed, even if its source spelling was `case 999999` rather than `default`. Source order is
preserved, including descending/duplicate case values and early defaults. No sorting rewrites
their behavior. Missing defaults append the original zero fallback and produce a diagnostic.
Duplicate weight names remain in the ordered list, with first-definition lookup and a diagnostic.

Native probes also established two unusual scalar behaviors: inverted balance bounds are retained,
and a negative return value emits a warning but evaluates to its positive magnitude. Java preserves
that observed magnitude and emits an explicit diagnostic rather than silently changing it. Negative
case labels and inventory indices are rejected. Evaluated float tokens retain their numeric field,
including precision beyond the source service's two-decimal display spelling.

At or beyond the final threshold, the native result is the final separator's stored base value,
without descending into any nested decision. A separator containing a nested decision has base
zero. This edge was independently verified at inventory values 999999 and larger and is preserved
by both evaluation modes.

## Undecided evaluation and random ownership

`evaluateUndecided(index, inventory, IntSupplier randomBits)` implements the published
[`FuzzyWeightUndecided`](https://github.com/ioquake/ioq3/blob/588393618dbc82e7207c21c6ddecca229944a03a/code/botlib/be_ai_weight.h)
boundary. The host supplies and owns the random stream; it can pass its deterministic session
generator's `nextInt` method. The low 15 bits are normalized by **32767.0f**, including both zero
and one, matching the published native
[random macro](https://github.com/ioquake/ioq3/blob/588393618dbc82e7207c21c6ddecca229944a03a/code/qcommon/q_shared.h#L677).
The generator algorithm itself is a host choice, not an emulation of a platform's C library RNG.

An eligible sampled leaf returns `minimum + sample * (maximum - minimum)` using separate float32
operations. The stored base value does not influence this sampled value. Inverted intervals retain
their direction. Constant and equal-bound sampled leaves still consume one random value, so a
host must not remove those draws as an optimization.

Controlled native probes established these additional compatibility rules:

- Between thresholds, the lower branch is evaluated before the upper branch, even when its final
  interpolation contribution is zero.
- An upper nested decision uses **deterministic** evaluation. A lower nested decision retains
  undecided evaluation. An upper scalar leaf still samples its interval.
- The 999999 default sentinel forces the upper contribution to one but still evaluates the lower
  branch first. Its unused random draws affect the remaining caller-owned stream.
- At or beyond the final threshold, the stored base value is returned with no further draws or
  child traversal. A top-level scalar return has an implicit slot-zero threshold of 999999, so it
  likewise returns its base without a draw when `inventory[0]` reaches that threshold. Empty
  inventories use zero for this implicit slot; explicit referenced slots still enforce their bounds.

Invalid indices, undersized inventories, and null arguments fail before consuming randomness.
Exceptions from a supplied generator propagate; already-consumed random values are not rolled back.
No evaluation mutates the configuration, caches a generated value, changes balance bounds, or
performs file I/O. Callers serialize shared RNG access when reproducible interleaving matters.

## Limits and scope

Default limits are 128 weights (the public Q3 limit), 16,384 parsed nodes, 64 nesting levels, and
256 inventory slots. Configuration parsing also inherits the source service's include, token,
byte, and expansion budgets. Data is immutable; deterministic evaluation consumes no randomness.

Genetic evolution, interbreeding, mutation/scaling of retained bounds, weight persistence,
and native configuration-cache identity are not implemented by this service. There are no successful
placeholder operations for them. Callers must keep those operations explicitly unsupported until
their behavior is implemented and verified. Deterministic loaded weights can already be associated
with future goal and weapon state services.

## Verification and provenance

Six original authored tests verify threshold boundaries, nested interpolation, retained balance
metadata, sentinel/source-order behavior, missing defaults, duplicate names, scalar precision,
negative magnitudes, malformed input, inventory bounds, and source-handle cleanup on failure.
Ten additional authored tests cover sampled ranges, low-bit normalization, draw consumption,
inverted/constant intervals, nested branch order and asymmetry, discarded default-branch draws,
final-threshold behavior, native-captured seeded values, caller-owned streams, and invalid inputs.
All **16** tests pass.

The user's original **64 item/weapon character files** load successfully, defining **1,376 weights**.
Nine inventory vectors (empty through high inventory values and a mixed-index vector) exercise
**12,384 evaluations**. Every result matches the isolated native oracle's float bits exactly:
**zero mismatches, zero maximum absolute error**. Reproduce the Java-only audit after compilation:

```sh
java -cp craftq3-core/build/classes/java/main:craftq3-assets/build/classes/java/main:craftq3-botlib/build/classes/java/main scripts/AuditWeights.java run/craftq3/games
```

The optional differential invocation additionally takes a trusted native oracle executable and the
original PK3 path. The local harness is under ignored `.tools/weight-oracle`; it reads the archive
through installed libarchive into temporary streams without extracting or altering the user's
files. It links unchanged upstream `be_ai_weight`, lexer, and preprocessor objects from ioquake3
commit `588393618dbc82e7207c21c6ddecca229944a03a`. The native reference uses
`-ffp-contract=off` for comparison with Java's separate IEEE float operations; this explicit build
choice avoids platform-dependent fused multiply-add rounding. No native implementation bodies
were copied or translated into Java. Public declarations, original data syntax, and black-box
results established the behavior. Native source/objects, commercial data, and audit outputs are
not bundled.

`scripts/UndecidedWeightOracle.c` is an authored derivative of the existing weight host. It links
the same untouched reference objects, supplies a controlled integer stream through `rand`, and
reports both values and draw counts. Its directory fixture reads are confined to a canonical root;
original PK3 reads use anonymous temporary streams. The helper RNG is verification input only and
is not installed as CraftQ3's runtime generator. The completed audit checked all **64** original
weight files in **32 scenarios** each, including final-threshold and mixed-inventory cases:
**44,032 evaluations** and **70,141 random draws**, with **zero float-bit or draw-count mismatches**.
The deterministic corpus regression also still matches all **12,384** native values exactly.
The optional differential runs as follows:

```sh
java -cp craftq3-core/build/classes/java/main:craftq3-assets/build/classes/java/main:craftq3-botlib/build/classes/java/main \
  scripts/AuditUndecidedWeights.java /absolute/path/to/games /absolute/path/to/undecided-oracle /absolute/path/to/pak0.pk3
```
