# Porting guide for rewrite modules

How a legacy module becomes a rewrite module. Read [REWRITE.md](REWRITE.md) first.

## Rules

- **Packages**: `dev.bluevista.craftq3.engine.<module>` (`vm-abi` → `engine.vmabi`, `render-ir` →
  `engine.render`), `dev.bluevista.craftq3.mc.hybrid`, `dev.bluevista.craftq3.mc.fabric`.
- **Port, then fix.** Algorithms the audit judged faithful are copied from `legacy/` and then
  reviewed: rename packages, replace duplicated helpers with `engine:common`, remove dead code and
  telescoping constructors, name magic numbers, use primitive arrays on hot paths. Apply every fix
  listed for the module in REWRITE.md. Do not translate code from ioquake3 or other id-derived
  sources; LICENSE-NOTES.md keeps the independent-implementation posture.
- **Dependencies**: only the edges in `build-logic/src/main/groovy/craftq3/ModuleGraph.groovy`. Do
  not edit that file, `settings.gradle` or another module. If you need a new edge, stop and report.
- **Common primitives** (`engine:common`): `math.Vec3f` / `math.VecMath` (float, like `vec3_t`),
  `log.Log` (`warnOnce` for degraded paths), `time.Clock` / `ManualClock`, `thread.ThreadOwner`,
  `io.Ranges`, `info.InfoString`, `hash.Md4`.
- **Forbidden**: Minecraft/Fabric/LWJGL imports, `System.getProperty` (outside `..config..`
  packages), `synchronized` in botlib, mutable static state, swallowing exceptions, test or capture
  hooks in `src/main`.
- **Leniency**: accept what the original 1.32 engine accepts. Reject only what would be unsafe
  (out-of-range offsets, resource exhaustion) and say why in the exception message.
- **Error model**: parsers throw a module-specific `*FormatException` (unchecked) with context;
  runtime services degrade instead of killing a session (see REWRITE.md §1, error model).

## Tests

- Port the legacy tests for what you port, adapting them; keep them synthetic and fast.
- Replace brittle golden numbers with named expectations where the reason is known; keep native
  oracle captures (hex dumps, recorded values) verbatim and say where they came from.
- Real data: annotate with `@dev.bluevista.craftq3.engine.testkit.RealData` (add
  `testImplementation project(':engine:testkit')`) and get the installation from
  `RealDataRoot.require()`. It contains `baseq3/` plus mods (`cpma`, `jailbreak`, `truecombat`,
  `combat`, `q3rally`, `alternatefire`, `sha`, `q3pinball`, `CorkScrew`). The maintainer's copy is
  at `/home/ethan/IdeaProjects/craftq3/run/corpus/Quake III Arena`; run real-data tests with
  `CRAFTQ3_Q3ROOT` set to it. Never commit real data or values copied out of it.

## Build

```bash
./gradlew :engine:<module>:spotlessApply :engine:<module>:build
```

Warnings are errors (`-Xlint:all -Werror`). `./gradlew portableCheck` must stay green.
