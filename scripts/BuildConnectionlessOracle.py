"""Build a capture-only OOB observer from unchanged official native translation units."""

import pathlib
import subprocess

ROOT = pathlib.Path(__file__).resolve().parents[1]
SOURCE = ROOT / ".tools/ioquake3-source"
OUTPUT = ROOT / ".tools/connectionless-oracle"
OUTPUT.mkdir(parents=True, exist_ok=True)
objects = []
for unit in ("net_chan", "msg", "huffman", "q_shared"):
    target = OUTPUT / (unit + ".o")
    subprocess.run(
        ["clang", "-std=c11", "-O2", "-ffunction-sections", "-fdata-sections",
         "-I" + str(SOURCE / "code"), "-c", str(SOURCE / "code/qcommon" / (unit + ".c")),
         "-o", str(target)], check=True)
    objects.append(str(target))
subprocess.run(
    ["clang", "-std=c11", "-O2", "-Wl,-dead_strip", "-I" + str(SOURCE / "code"),
     "-I" + str(ROOT / "scripts"), str(ROOT / "scripts/ConnectionlessOracle.c"),
     *objects, "-o", str(OUTPUT / "probe")], check=True)
print(OUTPUT / "probe")
