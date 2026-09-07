"""Build the authored weapon-jump host against an existing ignored official checkout."""
from pathlib import Path
import subprocess

source = Path(".tools/ioquake3-source")
base = source / "build/CMakeFiles/ioquake3.dir/code"
output = Path(".tools/weapon-jump-travel-oracle")
output.mkdir(parents=True, exist_ok=True)
objects = [p for p in (base / "botlib").glob("*.o")
           if p.name not in {"be_aas_sample.c.o", "be_ai_move.c.o", "be_aas_move.c.o"}]
if not objects:
    raise RuntimeError("Build the official development reference first")
for relative in ("botlib/be_aas_sample.c", "botlib/be_ai_move.c", "botlib/be_aas_move.c", "qcommon/q_math.c"):
    target = output / (Path(relative).stem + "-unfused.o")
    subprocess.run(["clang", "-std=c11", "-O2", "-ffp-contract=off",
                    "-I" + str(source / "code"), "-c", str(source / "code" / relative),
                    "-o", str(target)], check=True)
    objects.append(target)
objects += [base / "qcommon" / name for name in ("q_shared.c.o", "md4.c.o")]
subprocess.run(["clang", "-std=c11", "-O2", "-ffp-contract=off", "-Wl,-dead_strip",
                "-I" + str(source / "code"), "-Iscripts",
                "-I/opt/homebrew/opt/libarchive/include", "scripts/BotWeaponJumpOracle.c",
                *map(str, objects), "-L/opt/homebrew/opt/libarchive/lib", "-larchive",
                "-o", str(output / "probe")], check=True)
