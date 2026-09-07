# AAS entity lifetime and native fast restart

The retail match restart exposed a retained botlib collision link to entity 209 after the guest reduced its active entity count to 197. Independent observers identified normal AAS frame expiration, allocated-slot tracing and restart timing as distinct contracts. No engine routine bodies were used to derive these rules.

## Collision links and retained metadata

A successful entity update makes its information valid for the current botlib frame. On the next `BotLibStartFrame`, the link remains available but the information becomes invalid. A second frame without another update removes the collision link. Stored origin, model and other information remain available afterward.

An explicit null entity update removes the collision link immediately, while preserving the information's existing validity and contents. In particular, removing an entity updated this frame does not change its information's valid bit. Refreshing unchanged information after expiration does not normally recreate the link.

The public interface separately exposes invalidation, unlinking invalid entities, resetting links and reading retained information. Native observations distinguish their effects rather than treating invalid metadata as an absent entity. [AAS entity interface](https://raw.githubusercontent.com/ioquake/ioq3/master/code/botlib/be_aas_entity.h).

Relinking follows float value comparisons:

- A changed origin triggers relinking for every solid type.
- For a bounding box (`solid=2`), changed local mins/maxs also trigger relinking. Angles are copied but angle-only changes do not trigger relinking.
- For a BSP model (`solid=3`), changed raw angles also trigger relinking. This remains true for angle 1→2 when derived bounds are identical. Conversely, a model change that alters derived bounds without changing origin/angles updates the retained bounds but does not trigger relinking.
- Solid types 0 and 1 retain previous bounds and angles. Other state fields do not independently trigger relinking.
- At exposed native AAS frame number 1, updates force relinking, including an all-zero slot and an identical update after explicit unlink. At frame 2 and later, the ordinary comparisons apply. Same-map loading preserves the AAS frame counter.

Positive and negative zero compare equal; a representable adjacent float counts as a change. These rules concern link updates, not a license to discard retained information or silently ignore invalid entity identifiers.

[`AasEntityLifetimeOracle.c`](../scripts/AasEntityLifetimeOracle.c) and [`BuildAasEntityLifetimeOracle.py`](../scripts/BuildAasEntityLifetimeOracle.py) call unchanged native botlib operations with controlled collision callbacks. [`AuditAasEntityLifetime.py`](../scripts/AuditAasEntityLifetime.py) verifies a 10,000-step mixed update/null/frame history against the observed state transitions, including callback counts, validity and retained model identity. Additional field, first-frame and angle/model probes are recorded in `/tmp/craftq3-aas-relink-fields-summary.log`, `/tmp/craftq3-aas-relink-angle-model.log` and `/tmp/craftq3-aas-first-frame-relink.log`.

[`AuditAasEntityLifetime.java`](../scripts/AuditAasEntityLifetime.java) separately replays a seeded 10,000-step history through public `BotlibHost.invoke` calls for each of the retail and modern game ABIs. It constructs bounded synthetic QVM memory, loads the original q3dm1 AAS directly from the archive, updates entity 209 through trap 207, advances frames through trap 205, and observes prediction through trap 318 and entity information through trap 303. All **20,000 production-host comparisons** match the native observer exactly: return value, per-target collision callback count, every byte of the 84-byte prediction and every byte of the 140-byte entity-information record. Collision callbacks are deliberately clear in both hosts; this audit verifies link lifetime and record semantics rather than dynamic collision geometry. The result is recorded in `/tmp/craftq3-aas-entity-lifetime-production.log`.

To repeat the production comparison with Java 25 after building the engine modules:

```sh
python3 scripts/BuildAasEntityLifetimeOracle.py
audit_classpath=$(python3 -c 'from pathlib import Path; print(":".join(str(p) for p in Path(".").glob("craftq3-*/build/classes/java/main")))')
java -cp "$audit_classpath" scripts/AuditAasEntityLifetime.java \
  .tools/pak0-audit/games/baseq3/pak0.pk3 .tools/aas-entity-lifetime-oracle/probe
```

The native build requires the existing ignored official development checkout and its compiled botlib objects. The archive argument can point to another user-owned original pak0; it is read directly.

## Allocated server slots

The native server observer captures the actual `EntityTrace` botlib import and calls unchanged `SV_GentityNum` and `SV_ClipToEntity`. Its authored game allocation has all 1024 slots while `sv.num_entities` can be smaller.

With active count 197, a request for allocated slot 209 resolves that slot. An unlinked slot is still traced when its current contents match the query mask. Zero or mismatched contents skip the collision-model query. Box collision uses the current local bounds and origin, with zero angles; BSP collision uses the current inline model, origin and angles. Count zero with allocated slot 1023 behaves the same way. Out-of-allocation identifiers were not tested and must remain rejected in the Java host.

This callback identifies a target entity and has no ignored-entity argument. The Java per-target shape query therefore does not apply the original AAS caller's ignored-entity filter a second time; target selection and exclusions already occur before the callback.

The observation is about dispatch and fields passed to collision, using an authored collision-model callback; it is not a replacement proof for the separately verified collision implementation. `scripts/BotEntityTraceOracle.c` and `BuildBotEntityTraceOracle.py` reproduce it. Exact records are in `/tmp/craftq3-entity-trace-slot-probe.log`.

## Restart frames and configstrings

The full native dedicated-server observer shows `GAME_INIT(T, seed, restart=1)` followed by game frames at T, T+100, T+200 and T+300. Bot-AI frame exports do not occur inside those four settling frames. The next regular bot-AI call occurs at T+400. Initial map loading has a different sequence that includes bot-AI calls during settling; the two paths must not be conflated.

The engine does not universally clear configstrings during fast restart. The observer writes distinct markers to indices 14, 22 and 1019 after guest shutdown. All three remain present immediately before the next guest `GAME_INIT`. The unchanged modern guest clears index 22 during initialization; indices 14 and 1019 remain. This does not assign modern meaning to retail index 14 and does not justify engine-side clearing of retail intermission state.

`scripts/ServerRestartOracle.c` extends the authored export observer with these markers; `BuildServerRestartOracle.py` builds it against the unchanged local development engine. The probe uses a source-built modern game VM and a private development home directory, without modifying the original assets. The export and marker evidence is in `/tmp/craftq3-native-fast-restart-exports.log` and `/tmp/craftq3-native-restart-configstrings.log`.

## Independence and runtime scope

These observer programs use public headers, attributed structure-layout metadata and exported operations. Native source is compiled unchanged; no native algorithm is copied or translated into Java. Callback stubs are explicitly controlled test inputs. Original archives are read directly and are never packaged in the mod.

Production `BotlibHost` applies the measured expiration and relink rules while retaining entity information. `EntityWorld` traces allocated guest slots using their current contents and shape, and `Q3Server` uses the observed fast-restart frame arguments. Focused server regressions cover both ABIs, allocated-slot bounds, target-self collision, first-frame relinking and restart scheduling. The retail cgame and match replay, including retained retail configstrings, is documented separately in [match lifecycle validation](BOT_MATCH_LIFECYCLE.md).
