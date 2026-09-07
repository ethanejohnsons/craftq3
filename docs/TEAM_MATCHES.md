# Original team matches

`AuditTeamApplication.java` exercises original team deathmatch (`g_gametype 3`,
q3dm1) and Capture the Flag (`g_gametype 4`, q3ctf1) through `QuakeSession`.
Each isolated game home starts two red and two blue skill-3 bots through ordinary
console commands. The human observer joins spectators. Original qagame and botlib
supply all movement, aiming, weapons, health, respawns, scores and flag decisions;
the audit never edits those states. The existing capture seed is enabled only
around map startup and cleared before normal configuration persistence.

The audit requires an earned five-frag team win or one flag capture, intermission,
ordinary observer-ready input, the original requested restart, reset scores,
retained two-versus-two teams and 15 seconds of subsequent original cgame world
presentation. CTF additionally observes enemy-team flag possession through the
public canonical player-state powerup array and checks possession clears on
restart. It does not assume retail publishes the later CS_FLAGSTATUS configstring.
It uses a CPU audio backend and does not establish new GPU fidelity or audio timing.

Run with Java 25:

```sh
./gradlew :craftq3-fabric:auditRemoteApplication -Pq3TeamAudit=true \
  -Pq3RemoteAuditGames=/absolute/path/to/quake-installation \
  -Pq3RemoteAuditOutput=/absolute/path/to/isolated-audit-output
```

The installation is read-only. For packaged validation, add
`-Pq3AuditRuntime=/absolute/path/to/inspected-runtime` with the mod jar and its
nested engine jars. The exact completed runs are recorded in `VALIDATION.md`.

## Registered console command dispatch

The first production audit exposed `addbot` printing `unknown cmd addbot` instead
of adding a bot. Cgame registers this command for console recognition but leaves
its execution to local qagame. The registered-command callback previously skipped
the local server console and forwarded it as a player's server command.

Registered commands now use the same scoped dispatcher as unregistered commands:
cgame first, then the source's local console handler, then the player's outgoing
command if neither handles it. Remote sources still decline local console handling.
Argument quoting and the enclosing reliable-command context survive dispatch.
A synthetic guest regression fails before the fix and covers local handling,
remote fallback, cgame handling and context restoration in both ABI profiles.
The opt-in native console smoke also types `addbot sarge 3` through the actual
screen and requires exactly one additional original bot.

## Team model user information

The modern team startup then exposed a separate missing engine registration:
`team_model` and `team_headmodel` existed as UI archive settings but were absent
from player user information. Original qagame/cgame consequently received an empty
team model and attempted `models/players//icon_default.tga`, failing strict path
validation. Both settings now register as ARCHIVE | USERINFO before UI startup and
configuration loading, matching the observed native registration flags. CraftQ3
uses its existing base-game Sarge default for both. Saved values still override
the defaults. File-path validation is unchanged.

The existing independent native initialization observation records the two flags
as 3 in `.tools/demo-lifecycle-oracle/client-defaults.log`. Its expansion-oriented
`james` / `*james` defaults are not imposed on the retail base-game installation.
The real modern team startup is the regression for the missing userinfo fields.

The initial retail CTF run reached a real capture, intermission and restart but
failed its guessed flag-configstring assertion. That failure was in the audit;
flag possession now comes from player state. Full campaign/team coverage, other
maps, team orders, flag drop/return cases and broader bot navigation remain open.
