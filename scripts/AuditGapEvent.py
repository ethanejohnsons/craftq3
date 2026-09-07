#!/usr/bin/env python3
"""Authored black-box gap-event branch probes; no native engine implementation is reproduced.

Uses the isolated jump-travel oracle with controlled trace endpoints. This tests the
observed decision and output fields, not the physical movement predictor integration.
"""
import argparse
import math
import os
from pathlib import Path
import random
import struct
import subprocess


def f32(value):
    return struct.unpack("f", struct.pack("f", value))[0]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("pk3", type=Path)
    parser.add_argument("oracle", type=Path)
    parser.add_argument("--count", type=int, default=2000)
    args = parser.parse_args()
    if not 1 <= args.count <= 10000:
        parser.error("count must be 1..10000")
    total = 0
    for step in (0, 7, 7.1, 7.125, 19, 19.1, 50, 100000):
        rng = random.Random(150319)
        commands = ["fixture 6", "contents 0", "tag 1"]
        cases = []
        for i in range(args.count):
            z = f32(rng.uniform(-20, 20) if i % 3 == 0 else rng.uniform(-10000, 10000))
            delta = f32(step + 1 + rng.uniform(-2, 2))
            if i % 5 == 0:
                delta = float(step + 1)
            solid = int(i % 7 == 0)
            contents = (0, 1, 8, 16, 32, 56, 64)[i % 7]
            fraction = (0, .25, .5, 1)[i % 4]
            commands += [f"gaptrace {solid} {fraction} {delta}", f"gapcontents {contents}",
                         f"predict {(-1, 0, 3)[i % 3]} 0 0 {z} 2 {i % 2} 0 0 0 -400 0 0 1 1 .1 64"]
            cases.append((solid, contents, delta, z))
        env = os.environ.copy()
        env.update(CRAFTQ3_ORACLE_VAR="phys_maxstep", CRAFTQ3_ORACLE_VALUE=str(step))
        run = subprocess.run([str(args.oracle.resolve()), str(args.pk3.resolve()), "q3dm1"],
                             input="\n".join(commands) + "\n", capture_output=True, text=True,
                             timeout=30, env=env, check=True)
        lines = run.stderr[run.stderr.index("TAG 1"):].splitlines()
        traces = [(i, line) for i, line in enumerate(lines)
                  if line.startswith("AASTRACE ") and "presence4" in line]
        results = [line.split() for line in run.stdout.splitlines()
                   if line.startswith("PREDICTION ")]
        if len(traces) != args.count or len(results) != args.count:
            raise AssertionError("Unexpected native query/result count")
        for index, ((line_number, trace), result, case) in enumerate(zip(traces, results, cases)):
            solid, contents, _, origin_z = case
            trace = trace.split()
            start_z = f32(float(trace[3]))
            end_z = f32(float(lines[line_number + 1].split()[3]))
            event = 64 if not solid and end_z < f32(f32(start_z - f32(step)) - 1) and not contents & 32 else 0
            if trace[9] != "pass-1":
                raise AssertionError("Gap trace did not ignore all dynamic entities")
            if int(result[10]) != event:
                raise AssertionError(f"step={step} case={index} {case} drop={start_z-end_z} native={result}")
            if event:
                # Main movement trace survives, while endPosition is rolled back one frame.
                old_z = f32(origin_z + .25)
                if f32(float(result[4])) != old_z or result[12:14] != ["0", "0"]:
                    raise AssertionError(f"Gap output changed: step={step} case={index} {result}")
        total += args.count
        print(f"phys_maxstep={step}: {args.count} gap decisions exact")
    print(f"GAP {total} controlled native decisions exact; physical integration remains separate")


if __name__ == "__main__":
    main()
