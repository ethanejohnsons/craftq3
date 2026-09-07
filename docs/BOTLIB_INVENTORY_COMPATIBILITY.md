# Bot inventory data compatibility

The bot inventory layout belongs to the qagame module and its bot scripts. A newer
qagame QVM paired with the retail `pak0.pk3` bot files can run successfully while
making incorrect weapon and item choices. CraftQ3 must preserve the supplied
inventory and the native botlib result; silently remapping slots in the Host would
change the behavior of the original modules and their data.

## Observed version mismatch

The unchanged retail QVM supplies machinegun bullets in slot 16 and health in slot
24. The source-built modern QVM supplies bullets in slot 19 and health in slot 29.
The user's VM-only `pak8.pk3` qagame, dated September 30, 2002, also supplies slots
19 and 29. Its archive supplies replacement QVMs without a replacement
`botfiles/inv.h`; the retail header therefore still controls the weight scripts.

A modern q3dm17 request captured at 1.75 seconds contains a gauntlet and machinegun,
100 bullets in slot 19, health 115 in slot 29, and enemy horizontal distance 2018.
The retail weight script sees zero bullets in its slot 16 and selects the gauntlet.
The bot's snapshots include the stationary target and static BSP traces to it are
clear. The observed weapon selection, not missing target visibility, explains the
zero-ammunition result in this scenario.

`BotChooseBestFightWeapon` from unchanged native ioquake3 makes the same choice.
The same captured inventory returns weapon 1 with the retail header and weapon 2
with matching public inventory declarations. This is a data-version compatibility
problem reproduced by the native service, not an engine inventory-adapter defect.

The 19 shared inventory constants whose values changed are:

| Constant suffix (`INVENTORY_`) | Retail | Modern |
| --- | ---: | ---: |
| SHELLS | 15 | 18 |
| BULLETS | 16 | 19 |
| GRENADES | 17 | 20 |
| CELLS | 18 | 21 |
| LIGHTNINGAMMO | 20 | 22 |
| ROCKETS | 21 | 23 |
| SLUGS | 22 | 24 |
| BFGAMMO | 23 | 25 |
| HEALTH | 24 | 29 |
| TELEPORTER | 25 | 30 |
| MEDKIT | 26 | 31 |
| QUAD | 27 | 35 |
| ENVIRONMENTSUIT | 28 | 36 |
| HASTE | 29 | 37 |
| INVISIBILITY | 30 | 38 |
| REGEN | 31 | 39 |
| FLIGHT | 32 | 40 |
| REDFLAG | 33 | 45 |
| BLUEFLAG | 34 | 46 |

These differences also affect item weights. Shared weapon-ownership, enemy-field
and item-model constants matched. The other shared header comparisons found no
changed values: 49 definitions in `chars.h`, 81 in `match.h`, and seven in `syn.h`.
This check establishes these declaration boundaries; it does not establish that
every modern gameplay asset or every mod is compatible with retail data.

## Native and runtime verification

The authored observation script records the original trap 558 inventory and the
Host's delegated result. It preserves all arguments and results. Unchanged native
weapon selection matched every captured request:

| Capture set | Requests | Differences |
| --- | ---: | ---: |
| Source-built modern plus retail, retail header | 39 | 0 |
| User's original pak8 QVM, retail header | 24 | 0 |
| Source-built modern plus original pak8, matching header | 205 | 0 |

For the corrected QA runs only, the virtual filesystem supplied the unchanged
public `code/game/inv.h` from the official local ioquake3 checkout when bot scripts
requested `botfiles/inv.h`. The override stayed in the authored test process; no
PK3 was changed, no original asset was extracted, and no replacement header is
bundled with CraftQ3.

Both corrected q3dm17 runs completed 1,200 samples over 60 seconds and met the
stationary-target movement and combat assertions:

| Metric | Source-built modern QVM | Original user's pak8 QVM |
| --- | ---: | ---: |
| Moving frames | 1,179 | 1,163 |
| Horizontal distance | 16,323.321768655002 | 14,281.966319497626 |
| Final bot health | 82 | 88 |
| Final score | 1 | 1 |
| Ammunition spent | 90 | 110 |
| Target health, initial → final | 125 → −286 | 125 → −10 |
| Target death server time | 53,450 ms | 57,650 ms |

The suspended-item warnings remain in both corrected runs, so they do not explain
this particular absence of shooting. They still identify a separate limitation in
jump-pad item placement. These results validate the specified stationary-target
scenario, not broad autonomous match or navigation completeness.

## Reproduction and provenance

- `scripts/ObserveBotInventory.java` runs the authored stationary-target setup and
  logs positions, snapshot membership, static world visibility and inventories.
  Its optional `craftq3.audit.inventoryHeader` property affects only that process.
  The optional third argument accepts a QVM or reads `vm/qagame.qvm` directly from
  a PK3 into memory. The transparent syscall observer is installed only on its
  development-owned server instance.
- `scripts/BotInventoryOracle.c` calls the unchanged native weapon exports. Its
  optional third argument supplies the public matching header for that process.
- `scripts/BuildBotInventoryOracle.py` compiles the unchanged native dependencies
  with `-ffp-contract=off` into ignored `.tools/bot-inventory-oracle` files.
- `scripts/AuditBotInventory.py` compares complete captured inventories and choices
  with those native calls.

The reference checkout is official ioquake3 commit
`588393618dbc82e7207c21c6ddecca229944a03a`. The matching development header used here
is `/Users/ethan/IdeaProjects/craftq3/.tools/ioquake3-source/code/game/inv.h`.
Only public declarations, data/header comparisons and authored call observations
were used; no engine routine bodies were read or translated. Native dependencies
and user assets remain outside distributable modules.

```sh
python3 scripts/BuildBotInventoryOracle.py
java -Dcraftq3.audit.botMilliseconds=60000 \
  -Dcraftq3.audit.inventoryHeader=.tools/ioquake3-source/code/game/inv.h \
  -cp craftq3-core/build/classes/java/main:craftq3-assets/build/classes/java/main:craftq3-collision/build/classes/java/main:craftq3-botlib/build/classes/java/main:craftq3-vm/build/classes/java/main:craftq3-server/build/classes/java/main \
  scripts/ObserveBotInventory.java .tools/pak0-audit/games q3dm17 path/to/qagame.qvm \
  > /tmp/craftq3-inventory-observation.log
python3 scripts/AuditBotInventory.py \
  --oracle .tools/bot-inventory-oracle/probe --pk3 path/to/pak0.pk3 \
  --header .tools/ioquake3-source/code/game/inv.h /tmp/craftq3-inventory-observation.log
```

Omit both explicit header options to reproduce the supplied data's native behavior.
Use matching game modules and bot data for a paired-profile audit; an ABI profile
alone cannot establish the script inventory layout.

## Explicit general audit overlay

`AuditBots` accepts `-Dcraftq3.audit.botInventoryHeader=/path/to/inv.h`; the Gradle
`auditBots` task exposes it as `-Pq3BotInventoryHeader=...`. This QA-only override
is read into memory, limited to 64 KiB and logged explicitly. It replaces only the
virtual `botfiles/inv.h` read for that audit. It does not alter mounted archives,
loose files, engine inventory values or production game sessions.

The matching official declaration file used here is
`.tools/ioquake3-source/code/game/inv.h` at the recorded reference commit. The
source-built modern VM can be selected separately with `-Pq3AuditVm=...`.
Omitting the header option preserves the supplied installation's data exactly.

A 789-test snapshot then ran all 30 original maps with the source-built modern
qagame and this matching header. Twenty-eight complete their minute and pass all
requested assertions, including q3dm1 and q3dm17 stationary-target combat. q3dm19
stops on platform travel19; q3tourney3 stops on a tilted step at 14,550ms. These
are a separately labeled matching-data corpus, not a relabeling of the earlier
mixed-data results. Logs are under `/tmp/craftq3-matched-modern-sweep/`.

The inspected 819-test snapshot retries matching-modern q3tourney3 after the
positive-unit step fix and completes all 1,200 samples: 1,194 moving frames,
15,843.49 horizontal units, score 1, ammunition spent 46 and target death at
19.25 seconds. The retry asserts movement; the incidental frag is additional
observed evidence. The prior 28/30 sweep plus this focused retry now has 29
completed matching-data map scenarios across checkpoints. The 826-test q3dm19 platform retry also completes its full minute, bringing
the matching-data cumulative coverage to all 30 maps. This combines the original
28-map result with two later focused retries. The retry log is
`/tmp/craftq3-modern-tourney3-tilted-step-retry.log`.
