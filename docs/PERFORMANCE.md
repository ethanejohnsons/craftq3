# Performance

The engine's hot paths are measured by the JMH benchmarks in `engine/benchmarks`
(`src/jmh/java`). This page explains how to run them, records one full run, and compares the
results with the budgets in [REWRITE.md](REWRITE.md) §3 (Verification → Performance).

## Running

```bash
./gradlew :engine:benchmarks:jmh
```

```bash
CRAFTQ3_Q3ROOT="/path/to/Quake III Arena" ./gradlew :engine:benchmarks:jmh
```

- `build` and `check` only compile the benchmarks. They run only when you ask for them, so CI
  never runs them.
- Synthetic benchmarks need no game data. The `Real*Benchmark` classes read maps and the pak8
  qagame from your installation. When `CRAFTQ3_Q3ROOT` is unset, the build excludes them.
- Each class sets short default iteration counts: 3–5 warm-up iterations and 5–10 measured
  iterations of 1–4 s each, in one fork. A full run takes about 3 minutes.
- Overrides:
  - `-Pjmh.includes='Net|Collision'` picks benchmarks with a regex.
  - `-Pjmh.wi=`, `-Pjmh.i=`, `-Pjmh.w=`, `-Pjmh.r=` and `-Pjmh.f=` set warm-up iterations,
    measured iterations, warm-up time, iteration time and forks.
  - `-Pjmh.prof=gc,stack` picks profilers. The default is `gc`; pass an empty value for none.
- Results go to `engine/benchmarks/build/results/jmh/`: `human.txt` for reading and
  `results.json` for tools.
- With the default `-prof gc`, `gc.alloc.rate.norm` is the number of bytes allocated per
  operation.

| Class | What one operation is |
| --- | --- |
| `VmBenchmark` | One `vmMain` running 10,000 iterations of a synthetic, lcc-shaped loop. There are three variants: a procedure call, a syscall, or global array loads and stores (`LOAD1`/`LOAD4`/`STORE4`) per iteration. The `instructions` counter gives QVM instructions per second. `@Param engine` selects the execution engine. |
| `RealQagameBenchmark` | One server frame of work in the original baseq3 pak8 qagame (1.32 ABI), 8 bots on q3dm17: `BOTAI_START_FRAME`, then `GAME_RUN_FRAME`, called directly on the running instance. Syscalls are included. |
| `CollisionBenchmark` | One trace (point, player hull, or capsule) through a generated arena: 4096² floor, walls, pillars, ramps, patch domes and a 64-leaf BSP. |
| `RealCollisionBenchmark` | One point or player-hull (`-15 -15 -24`…`15 15 32`) trace on q3dm17 or q3dm7. Starts are seeded open positions, and ends are up to 512 units away horizontally and 256 vertically. |
| `RealBotlibBenchmark` | One AAS route query (travel time and first reachability) between seeded routable areas of q3dm17. `warmCache` measures the steady state. `coldCache` clears the route caches before every batch of 256 queries. |
| `NetBenchmark` | Encode or decode of a delta-compressed 64-entity snapshot (398 bytes on the wire). For the Huffman variants, one operation is one byte through the protocol-68 fixed-Huffman message layer. |
| `RealGameFrameBenchmark` | One complete `SV_Frame` at `sv_fps 20` with 8 bots on q3dm17. It runs in JMH sample mode, which reports percentiles. |
| `RealRenderBenchmark` | `sceneBuild`: one `RenderScene.build` of q3dm17. `frame`: the CPU work for one frame from each deathmatch spawn in four headings in turn (PVS and frustum visibility, portal views, `FrameBuilder` over CPU-deformed world surfaces). Cgame entities are not included. |

## Recorded run

These results were recorded on 2026-10-20 at commit `dd99636` plus the benchmark module. The run
used the default iteration counts and `-prof gc`, with `CRAFTQ3_Q3ROOT` pointing at the
maintainer's corpus. `VmBenchmark` and `RealQagameBenchmark` were then re-run with their current,
longer warm-up. The first full run used 3 warm-up iterations, and the interpreter had not settled
(errors of ±30–40 %).

**Machine:**

- **CPU:** AMD Ryzen 7 9800X3D, 8 cores / 16 threads, frequency governor `powersave`.
- **System:** 30 GB RAM, Linux 7.2.11 (Arch).
- **JVM:** GraalVM CE 25.0.4.1.1+1 (JDK 25.0.4, `jvmci-25.4-b23`). With JVMCI enabled, Graal is the
  top-tier JIT. G1 GC, JMH 1.37.
- **Load:** the machine was not otherwise idle. Other builds were running, with a load average of
  about 2–3. Treat differences under about 10 % as noise.

### VM

| Benchmark | Result | Allocation |
| --- | --- | --- |
| Interpreter, procedure-call loop | **544 M instr/s** (±0.2 %) | ~5 B per `vmMain` |
| Interpreter, syscall loop | **519 M instr/s** | ~4 B per `vmMain` |
| Interpreter, memory loads/stores loop | **593 M instr/s** | ~6 B per `vmMain` |
| pak8 qagame, 8 bots on q3dm17, `BOTAI_START_FRAME` + `GAME_RUN_FRAME` | 1,320 frames/s = **0.76 ms per frame**; **385 M instr/s** and 2.76 M syscalls/s including host time (≈292 k instructions and ≈2,100 syscalls per frame) | 117 KB per frame |

### Collision

All numbers are traces per second. Allocation is in bytes per trace.

| Map | Point | Player hull | Capsule | Allocation |
| --- | --- | --- | --- | --- |
| Synthetic arena | 14.6 M | 12.3 M | 12.9 M | 0.001 |
| q3dm17 | 13.1 M | 9.4 M | – | 0.001 |
| q3dm7 | 5.9 M | 1.36 M | – | 0.001–0.005 |

The residual fraction of a byte is JMH's own iteration bookkeeping spread over millions of
operations, so traces allocate nothing. q3dm7's hull traces are the slowest case because its
architecture is curved and detailed. Each hull trace there crosses many patch facets: about
0.7 µs per trace.

### Botlib (q3dm17 AAS)

The map has 544 routable areas. 216 of the 256 seeded pairs have a route.

| Benchmark | Result | Allocation |
| --- | --- | --- |
| Route query, warm caches | **46 M queries/s** (22 ns) | 20 B per query (the per-query work budget) |
| Route query, cold caches (builds the goal's cluster or portal cache) | **20.9 k queries/s** (48 µs) | 27.6 KB per query |

### Net

| Benchmark | Result | Allocation |
| --- | --- | --- |
| Encode a 64-entity delta snapshot | **157 k/s** (6.3 µs) | 448 B |
| Decode a 64-entity delta snapshot | **321 k/s** (3.1 µs) | 21.6 KB (the immutable `Snapshot` copies its states) |
| Fixed-Huffman encode | **390 MB/s** | 0 |
| Fixed-Huffman decode | **270 MB/s** | 0 |

### Game frame (pak8 qagame + 8 bots, q3dm17)

| p50 | p90 | p99 | p99.9 | Mean | Allocation |
| --- | --- | --- | --- | --- | --- |
| **0.79 ms** | 0.93 ms | **1.11 ms** | 1.43 ms | 0.80 ms | 266 KB per frame |

There were 24,935 sampled frames. Bots stay in the game for the whole run.

### Render IR (q3dm17)

| Benchmark | Result | Allocation |
| --- | --- | --- |
| `RenderScene` build (map load) | **7.5 ms** (single shot, ±0.9) | 8.6 MB |
| Per-frame visibility + portal views + `FrameBuilder` | p50 **12 µs**, p99 **17 µs**, p99.9 26 µs | 1.7 KB per frame |

## Comparison with the budgets

| Budget (REWRITE.md §3) | Measured | Status |
| --- | --- | --- |
| VM: at least 2× the legacy interpreter. The legacy benchmark gave 555 M instr/s on the procedure-call loop and 566 M on the syscall loop. | Current interpreter: 544 M (0.98×) and 519 M (0.92×) on the same loops. | **Not met.** The interpreter matches legacy and does not double it. The 2× target is for the QVM-to-JVM compiler, which is not on `main` yet. When it lands, `@Param engine` gets a `compiled` value in `VmBenchmark` and `RealQagameBenchmark`. |
| qagame + 8 bots: p99 under 4 ms per frame | p99 1.11 ms (p99.9 1.43 ms) | **Met**, with 3.6× headroom. |
| Traces: zero allocation | ≈0 B per trace on every map and trace kind | **Met.** |
| Render: p99 under 8 ms per frame (in-game timedemo at 1080p) | p99 17 µs for the render-ir CPU part. The scene build at map load takes 7.5 ms. | **Met for the part measured here.** The budget covers the whole frame: cgame, entity geometry and the Blaze3D backend on the GPU. Those run only in `mc:devharness` and are not covered by these benchmarks. |

The plan also asks for a 10 % regression gate on a nightly JMH run. The nightly workflow has been
removed, so for now comparisons against this page are made by hand.

## Notes

- The qagame benchmark calls `vmMain` directly, the way `SV_Frame` does, without the server's
  console, snapshot and timeout work. `RealGameFrameBenchmark` measures the whole frame.
  qagame accounts for roughly 95 % of a server frame (0.76 ms of 0.80 ms).
- Steady-state allocation outside collision and Huffman coding is not zero:
  - 266 KB per server frame.
  - 117 KB per qagame frame, allocated by host syscalls and botlib.
  - 21.6 KB per decoded snapshot.

  These are candidates for the "no steady-state allocation" goal.
