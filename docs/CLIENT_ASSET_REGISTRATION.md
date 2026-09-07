# Empty original-UI shader registration

The original retail Single Player arena menu requests an empty bitmap shader.
Previously, `ClientAssets.shader` passed that request to `VirtualPath`, which
correctly rejects empty file paths but consequently stopped the UI at trap 20.
The fresh reproduction is UI initialization, the ordinary main menu, Enter, then
the next rendered menu frame. It requires no configuration edits or fabricated
CD key.

An empty shader-registration name now returns handle zero before any path lookup,
asset registration or snapshot invalidation. Handle zero already maps to the
renderer’s white/default drawing slot. The separately registered `white` name
keeps its existing positive handle. Null Java requests and malformed virtual
paths still fail; missing named images retain their existing checker texture and
diagnostic, while malformed encoded images still raise their decoding error.
A guest zero string pointer follows the existing bounded VM string convention
and therefore yields an empty registration request.

The authored `ShaderRegistrationOracle.c` calls the official renderer’s public
`RegisterShader` and `RegisterShaderNoMip` exports. The build script relinks the
existing unchanged renderer objects with a separate header-only metadata fixture
for `tr.defaultShader` (index zero, default-shader flag). It does not replace any
routine, initialize a graphics device or access original assets. Both exports
return zero for the empty name. A null C pointer is invalid and triggers the
observer’s signal handler; it is not treated as an empty C string. Names of
length 64, 255, 256 and 1024 also return zero with the native maximum-path
diagnostic; this broader length behavior is observed but is not changed by this
narrow empty-name fix. Existing Java virtual-path bounds remain intact.

The reference is unchanged ioquake3 commit
`588393618dbc82e7207c21c6ddecca229944a03a`. Only public export declarations and
shader/global structure metadata were inspected in
[`tr_public.h`](https://github.com/ioquake/ioq3/blob/588393618dbc82e7207c21c6ddecca229944a03a/code/renderercommon/tr_public.h)
and [`tr_local.h`](https://github.com/ioquake/ioq3/blob/588393618dbc82e7207c21c6ddecca229944a03a/code/renderergl1/tr_local.h).
No renderer function bodies were copied or translated.

Four focused tests cover empty registration without allocation, invalid/null
requests, missing-versus-malformed image diagnostics and both guest UI ABIs.
All 31 client-module tests pass with zero failures, errors or skips.
`AuditSinglePlayerMenu.java` exercises the original UI twice and checks its arena
levelshot and Skirmish/Fight controls, in addition to matching frame digests.
The original pack remains mounted read-only. The development renderer and
observer are not included in the mod.

## Original deferred player models

The 897-test Vulkan q3dm12 bot capture labels an apparent Sarge model as Visor.
This is expected temporary presentation from the original retail cgame, not a
model-handle mapping defect. The smoke adds bots after cgame initialization.
Original defaults are `cg_forceModel=0` and `cg_deferPlayers=1`; qagame publishes
the correct player configstrings (`Visor` / `model=visor`, and Anarki / `anarki`).
An original-VM CPU replay with the captured engine jars initially registers and
submits only `models/players/sarge/{head,lower,upper}.md3`. With the ordinary
`cg_deferPlayers=0` setting in a separate QA run, it immediately registers the
distinct players and submits Visor's own model paths.

The retail guest actually registers the console command **`loaddefered`** (one
`r`), as observed from its `ADDCOMMAND` requests. Calling that registered spelling
at elapsed 2,000 ms loads Visor and Anarki in the same frame. Opening the original
scoreboard with `+scores` also loads them, in the next observed frame at 2,016 ms.
A control run with neither command loads the models after the human dies and
the scoreboard appears. The guessed spelling `loaddeferred` is not recognized
by this retail guest; subsequent death-triggered loading must not be attributed
to that rejected command. Production settings and model mapping remain unchanged.

The authored observer is `.tools/player-model-audit/ObservePlayerModels.java`;
it reads Java registration paths and immutable submitted scenes, without opening
a graphics device or inspecting native routine bodies. Local evidence is in
`/tmp/craftq3-player-model-{passive,immediate,registered,scores}.log`. These runs
use the read-only original pack and `/tmp/craftq3-progression-network-runtime/*`
engine snapshot. `registered` derives the deferred command spelling from actual
guest registrations before dispatching it; `passive` leaves loading to the guest.

```sh
python3 scripts/BuildShaderRegistrationOracle.py
.tools/shader-registration-oracle/probe \
  .tools/shader-registration-oracle/renderer_observed.dylib empty
```

Run the UI reproduction with Java 25 and the built engine jars or module classes:

```sh
java -cp '<engine classpath>' scripts/AuditSinglePlayerMenu.java \
  .tools/pak0-audit/games
```

Local proof logs are `/tmp/craftq3-empty-shader-tests.log`,
`/tmp/craftq3-client-empty-shader-tests.log`, `/tmp/craftq3-single-player-menu.log`
and the individual input reports under
`.tools/shader-registration-oracle/`. The native build script uses the existing
macOS renderer build and SDL headers; it is a development tool only.
