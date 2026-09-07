"""Build an outbound configstring observer without startup or routine-body inspection."""

from pathlib import Path
import subprocess

root = Path(__file__).resolve().parent.parent
source = root / ".tools/ioquake3-source/code"
out = root / ".tools/configstring-send-oracle"
out.mkdir(parents=True, exist_ok=True)
flags = [
    "-std=c11",
    "-O2",
    "-fno-inline",
    "-ffunction-sections",
    "-fdata-sections",
    "-DSTANDALONE",
    "-DLEGACY_PROTOCOL",
    "-DUSE_INTERNAL_SDL_HEADERS",
    "-I" + str(source),
    "-I" + str(source / "thirdparty/SDL2-2.32.8/include"),
]
subprocess.run(
    [
        "clang", *flags, "-c", str(source / "qcommon/q_shared.c"),
        "-o", str(out / "q-shared.o"),
    ],
    check=True,
    capture_output=True,
)
subprocess.run(
    [
        "clang", *flags, "-Wl,-dead_strip",
        str(root / "scripts/ConfigstringSendOracle.c"),
        str(out / "q-shared.o"), "-o", str(out / "probe"),
    ],
    check=True,
)
print(out / "probe")
