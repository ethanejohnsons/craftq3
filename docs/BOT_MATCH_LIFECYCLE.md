# Original bot match lifecycle audit

`scripts/AuditBotMatch.java` runs original qagame decisions with three varied bots
(Sarge, Major and Visor), skill 3, seed 42, free-for-all and fraglimit 3. It does
not inject damage, deaths, scores, bot movement or bot readiness. The source
map and assets are read from the isolated original-pak0 installation.

The first checkpoint uses the inspected 830-test runtime snapshot at
`/tmp/craftq3-platform-parity-runtime`: 614 Java 25 classes. Source changes made
after that snapshot cannot affect these results. The modern profile uses the
unchanged locally built original qagame QVM and a QA-only in-memory replacement
for its matching inventory declarations, as described in
[BOTLIB_INVENTORY_COMPATIBILITY.md](BOTLIB_INVENTORY_COMPATIBILITY.md).

## What the script observes

The script records canonical player scores and movement modes, low game
configstrings, reliable scoreboards and restart messages, and the command
requested by the original guest after intermission. It preserves reliable
sequence numbers across restart and checks score reset, normal player mode,
the server-count snapshot flag and continued movement in the next match.

The configured standard `nextmap` command is `map_restart 0`. An authored host
callback records that command, stops command-buffer dispatch at its boundary,
and calls the existing `Q3Server.restart` after the VM invocation unwinds. No
restart is injected when the guest has not requested one. This checks the
headless guest/server boundary; it does not replace the separate rendered
`QuakeSession` lifecycle smoke.

An optional observer is a passive spectator throughout combat. In that mode
the script sends one attack-button press/release five seconds after all bots
enter intermission, representing a human selecting ready. It also requests the
original scoreboard. This is explicitly separate from the bot-only scenario.

## Inspected-snapshot results

Times below are guest server milliseconds, including initialization. Each run
starts at time 1,000 and completes its startup settling at 1,400.

| Profile and scenario | Fraglimit reached | All bots in intermission | Guest restart request | Restart completed | Outcome |
| --- | ---: | ---: | ---: | ---: | --- |
| Modern, matching inventory, bots only | 27,550 | 28,550 | 43,600 | 44,000 | Pass through 59,000; 767 moving samples and two new scored kills |
| Modern, matching inventory, passive observer | 55,200 | 56,200 | 61,300 | 61,700 | Pass through 76,700; 844 moving samples and a new scored kill |
| Retail, bots only | 73,150 | 74,100 | None by 301,400 | — | Match end proven; automatic advance not observed |
| Retail, passive observer | 48,550 | 49,500 | 54,600 | 55,000 | Restart boundary fails at 55,350; see below |

Modern configstring 22 changes to `1` at the fraglimit and is cleared by the
restart. The retail guest instead changes configstring 14; this index was
observed directly rather than inferred from the modern public header. Both
profiles publish canonical movement mode 5 for intermission. The modern
bot-only run preserves three reliable restart messages. The observer run
preserves four and delivers three original scoreboard messages. Their post-
restart scores and normal movement modes reset before further combat.

Retail bot-only behavior is reported as the observed original-QVM result in
this host, not a claim of native dedicated-server policy. Adding a passive
spectator and a single ready input causes the guest to request the configured
restart, establishing that the intermission exit path itself is reachable.

## Concrete retail restart checkpoint

On the 830 snapshot, retail restart returns `RUNNING`, increments restart count
to one, toggles snapshot flag 4, sends four reliable restart commands and resets
the three bots to score zero and normal movement mode. Two observations required
independent native investigation:

- Retail intermission configstring 14 remains `1` after the fast restart.
- At time 55,350, original syscall 549 fails with `Invalid game entity 209` from
  the AAS movement world's entity-trace callback into `EntityWorld`. The
  original guest has not called botlib map loading again; old entity-area links
  remain while the host has reset the game entity allocation.

No invalid-entity exception is ignored by the script, and no stale state is
silently cleared for the audit. The original failure is preserved in
`/tmp/craftq3-match-retail-observer-dm1-v2.log`. It led to the native-observed AAS
entity-link lifetime correction described in
[BOTLIB_ENTITY_LIFETIME.md](BOTLIB_ENTITY_LIFETIME.md), rather than an inferred
blanket reset during map restart.

An ignored transparent callback observer captures the failing hull query from
`(1048.347900390625, 1059.1876220703125, 48.25)` down to z `24.125`, with bounds
`(-15,-15,-24)..(15,15,8)`, mask 65,537 and ignored client 1. The current game
entity count is 197. Retained botlib entity 209 was last updated at time 19.55,
frame 186, at `(1109,1175,16)` with bounds ±15, type 2 and solid type 2. The
observer delegates the unchanged host call and prints metadata only when it
fails; it does not modify production source or discard the stale query. Its
report is `/tmp/craftq3-match-retail-trace-observer.log`.

## Original cgame after the correction

The optional `craftq3.audit.matchCgame=true` mode runs the original cgame for
passive observer client 0 once per 50 ms server frame. It consumes the real
configstrings, reliable commands and snapshots through `Q3Client`, builds all
submitted views with production `SubmittedGeometry`, and keeps the same client
VM across the requested restart. Sound calls use an inert audit backend; no GPU
or audio device is opened. No intermission state is patched in either VM.

The corrected retail replay uses current server, client and render classes
prepended to the inspected 830 jars; other modules remain from that snapshot.
It reaches the same fraglimit/intermission/readiness times, completes restart
at 55,000, records a new scored kill at 63,150 and continues through 70,000.
The original cgame remains `RUNNING` with no VM or geometry exception: 1,365
frames, 1,777 views, 77,717 quads and 97,144 built surfaces. These include 103
intermission frames and 301 frames after the restart. The latter contain 301
world views, 6,141 model submissions and 11,671 surfaces. The final match has
543 moving bot samples and a new scored kill. Both cgame and match success
assertions pass in `/tmp/craftq3-match-retail-cgame-final.log`.

Native `ServerRestartOracle.c` independently seeds configstrings 14, 22 and
1,019 after shutdown, then inspects them before and after restart `GAME_INIT`.
The engine preserves all three before invoking the guest. The modern guest
clears 22 during initialization while retaining the other markers. Thus a
universal engine-side intermission-string clear would violate the measured
ownership boundary. The native transcript is
`/tmp/craftq3-native-restart-configstrings.log`; full build/provenance details
are in [BOTLIB_ENTITY_LIFETIME.md](BOTLIB_ENTITY_LIFETIME.md).

The audit retains `intermissionCleared` as an informational field, but success
requires actual player score/mode reset and continued play. Cgame mode further
requires intermission coverage, at least 300 post-restart frames, and
post-restart world views, model submissions and built surfaces. Retail retaining
configstring 14 is not treated as a failure when the original client has
resumed the measured world scene.

## Reproduction

Use Java 25 with the inspected runtime jars, independently of live source
classes:

```sh
java -cp '/tmp/craftq3-platform-parity-runtime/*' \
  scripts/AuditBotMatch.java .tools/pak0-audit/games q3dm1 \
  .tools/ioquake3-qvm-audit-build/Release/baseq3/vm/qagame.qvm \
  .tools/ioquake3-source/code/game/inv.h
```

Omit the last two paths for the retail module mounted from pak0. Add
`-Dcraftq3.audit.readyObserver=true` before the script path for the passive
observer scenario. Duration defaults to 300,000 milliseconds and is bounded
to 10,000–600,000 with `craftq3.audit.matchMilliseconds`. Bot names, skill,
seed and fraglimit are explicit bounded properties. A run fails unless it sees
match completion, a guest-requested restart, actual player reset and continued
bot movement. The optional cgame mode additionally requires the passive
observer and verifies continued world/model scene submission:

```sh
java -cp 'craftq3-server/build/classes/java/main:craftq3-client/build/classes/java/main:craftq3-render/build/classes/java/main:/tmp/craftq3-platform-parity-runtime/*' \
  -Dcraftq3.audit.readyObserver=true -Dcraftq3.audit.matchCgame=true \
  scripts/AuditBotMatch.java .tools/pak0-audit/games q3dm1
```

Reports for this checkpoint are
`/tmp/craftq3-match-modern-dm1-v2.log`,
`/tmp/craftq3-match-modern-observer-dm1.log`,
`/tmp/craftq3-match-retail-dm1.log`, and
`/tmp/craftq3-match-retail-observer-dm1-v2.log`.

These runs open no game network connection, mutate no original archives and
bundle no original data. They do not establish team/CTF match completion,
arbitrary map rotation, rendered intermission presentation or network sessions.
