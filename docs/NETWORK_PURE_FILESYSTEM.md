# Native pure-filesystem observations

The filesystem observer executes unchanged ioquake3 filesystem operations against private, authored ZIP fixtures. It does not load a map, execute a VM, bind a socket, extract an installed archive, or change the user's game files. These observations establish pack eligibility and the reference transcript needed by a protocol-68 client; they do not implement downloading or server policy.

## Provenance and reproduction

The reference is official ioquake3 commit `588393618dbc82e7207c21c6ddecca229944a03a`, built with the existing standalone/legacy-protocol dedicated configuration in `.tools/network-server-oracle-build`. The public declarations and reference-bit constants come from `code/qcommon/qcommon.h`. No native filesystem routine bodies, disassembly, or intermediate representation were inspected.

`BuildPureFilesystemOracle.py` recompiles unchanged `qcommon/files.c` with only its `FS_InitFilesystem` symbol renamed, then relinks the original dedicated objects with `PureFilesystemOracle.c`. The authored wrapper delegates initialization and pauses at its return boundary. Its input loop calls the published FS APIs directly. It restores blocking stdin for the observer and supplies the otherwise later-initialized public protocol cvar pointers with native values 71 and legacy 68, needed by demo-extension checks. It exits at this boundary instead of continuing engine initialization.

```sh
python3 scripts/BuildNetworkServerOracle.py
python3 scripts/BuildPureFilesystemOracle.py
python3 scripts/AuditPureFilesystem.py --output .tools/pure-filesystem-oracle/native-corpus.json
python3 scripts/AuditPureFilesystemBoundaries.py --output .tools/pure-filesystem-oracle/boundaries.json
```

`PureFilesystemHarness.py` owns private base/home directories and the subprocess. `PureFilesystemJavaOracle.java` is an authored line adapter over production `Pk3FileSystem` APIs; the Python audit accepts `--java-classpath` to compare both providers. Directory lists are compared as canonical file sets because Java deliberately publishes canonical sorted file paths; native ZIP directory records ending in `/` are excluded from that comparison. The native NULL-handle existence operation is documented separately and is not equated with Java `which`, whose result describes the eligible read view.

## Pack checksums and ordering

- Normal and pure checksums consume central-directory CRC values for entries whose uncompressed size is nonzero, in central-directory order. Names and zero-length entries do not affect the checksum. A directory entry with nonzero data **does** contribute its CRC.
- Renaming entries and adding empty files/directories leave checksums unchanged. Reversing two nonempty entries changes both checksums. The feed affects only the pure checksum.
- A pack containing only empty entries has normal checksum `-1737972033` (`0x9868a6bf`). Its pure checksum is `1290185885` with feed 0 and `-1864024385` with feed 42. A genuinely zero-entry ZIP is not mounted by this native build.
- Loaded names preserve basename case and omit the game-directory prefix and `.pk3`; loaded checksum strings contain signed decimal values, each followed by a space. Loaded lists include ineligible packs as well as eligible packs.
- In the measured default search order, loose files precede packs in the same native game directory, and pack filenames are searched in descending order. A selected mod precedes baseq3.
- `FS_PureServerSetLoadedPaks` immediately filters reads by **normal checksum**. Supplied pack names do not determine eligibility. It does not immediately reorder the search path or clear references.
- `FS_Restart(feed)` resets references and reconstructs the search path. Each checksum occurrence in the server list moves the first remaining matching pack to the next prefix position. Missing checksums consume no pack. Repeated checksum values can therefore move multiple distinct packs with identical CRC sequences; they do not duplicate one pack.
- The remaining packs retain their ordinary order after the allowed prefix. Setting an empty pure list after a restart that selected a matching pack triggers native restoration of the default order and clears references, even if the pack-only order happened to stay unchanged. Setting/clearing a list without that restart preserves references.

The production view-restart operation is deliberately bounded to already indexed archives. A native full `FS_Restart` rescans the installation; production requires a fresh mount to observe archive replacement or new files.

## Read, lookup, and list eligibility

A nonempty pure allowlist excludes all nonmatching packs, including their `.cfg` entries. Loose files have a separate read exemption: case-insensitive final suffix `.cfg`, `.menu`, `.game`, or `.dat`, and compatible demo extensions.

For the observed native protocol configuration, a demo extension uses the **last dot anywhere in the supplied path**, followed by case-insensitive `dm_` and an `atoi`-style decimal prefix. Values 66, 67, 68 and 71 are accepted; 43, 48, 69 and 70 are denied. Thus `x.dm_68a`, `x.dm_ 68`, `x.dm_+68`, `x.dm_0068`, and `x.dm_4294967364` are accepted; the last value wraps to 68 in the native integer result. `x.dm_68.bak`, `x.dm_-68`, `x.dm_0x44`, and a dotless `dm_68` are denied. `folder.dm_68/file` is accepted by the observed extension test. These results describe native eligibility, not permission to bypass the Java path validator.

Pure `FS_ListFiles` excludes **all loose entries**, including otherwise readable loose cfg files. It enumerates matching pack entries independently of which loose file wins a read. Lookup with `FS_FOpenFileRead(path, NULL, ...)` and `FS_FileIsInPAK` does not add references. The former returns zero for absence, a nonempty file's length, and one for an existing empty file, and **bypasses pure filtering**. It can report existence while an ordinary open is denied. `FS_FileIsInPAK` returns the containing pack's **pure** checksum and respects the allowlist. Java `which` intentionally describes its filtered readable view, so the native NULL-handle operation is not its pure-eligibility oracle.

## Reference accounting

An ordinary successful pack open sets GENERAL=1, except for:

- Case-insensitive final suffixes `.cfg`, `.txt`, `.shader`, `.arena`, `.bot`, `.menu`, `.config`.
- The exact case-insensitive supplied path `vm/qagame.qvm`.
- Any case-sensitive supplied-path substring `levelshots`.

A case-sensitive substring `cgame.qvm` sets CGAME=4, and `ui.qvm` sets UI=2. These bits are in addition to GENERAL when that rule applies. The substrings need not be basenames or suffixes: `nested/cgame.qvm.foo` qualifies. Empty files still record references when opened. Open alone is sufficient; reading the bytes does not add a different reference class.

These checks use the **caller's spelling**, while pack lookup is case-insensitive. Opening stored `vm/cgame.qvm` as `VM/CGAME.QVM` finds the bytes but sets no CGAME bit. Similarly `Levelshots/a.tga` receives GENERAL while `levelshots/a.tga` does not. Backslashes are not normalized before reference checks: `vm\qagame.qvm` resolves the stored file but receives GENERAL, unlike `vm/qagame.qvm`.

`FS_ClearPakReferences(mask)` clears the selected bits; mask zero clears all bits. Clearing GENERAL alone retains cgame/UI references. Referenced name/normal-checksum exports include every pack with any reference bit, in effective search order, and also include selected-mod packs even with no reference bits. Referenced names include the game directory. Applying a new nonempty allowlist does not erase previous references to now-ineligible packs.

## Pure reference transcript

`FS_ReferencedPakPureChecksums` emits:

1. The first effective-order pack with CGAME, if any.
2. The first effective-order pack with UI, if any.
3. `@`.
4. Every pack with GENERAL, in effective search order.
5. `checksumFeed XOR each GENERAL pure checksum XOR generalPackCount`.

Every token, including the last, is followed by one space. Missing special references contribute no placeholder. A pack may occur once in each of these classes. With no references, the result is `@ <feed> `. Non-base mod packs without reference flags are not added to this pure transcript despite their inclusion in referenced name/checksum exports. Controlled loose reads (`.jpg` outside pure, and exempt `.dat`/`.game`/`.cfg` under pure) did not insert a fake checksum in this reference build.

## Missing-pack reporting

`FS_PureServerSetReferencedPaks` and `FS_ComparePaks` were also observed without performing downloads. A locally present checksum satisfies a reference regardless of the supplied name. A missing `baseq3/missing` produces `baseq3/missing.pk3\n` in display mode and `@baseq3/missing.pk3@baseq3/missing.pk3` in download-list mode. Mismatched checksum/name counts truncate to paired entries; tested parent-traversal names are ignored. These captures do not establish a complete download-name validation policy and are not authorization to write remote files.

## Production differential checkpoint

The production filesystem passed **12,597 native operation comparisons** across three authored installations, including a selected mod. The corpus covers exact file bytes, all pack checksums and ordering, normal referenced exports derived from the public pack metadata, the exact pure transcript, and canonical file lists. It includes a zero-entry ZIP, empty entries, CRC-bearing directory metadata, mixed-case mod pack names, equal-checksum packs, raw caller case/backslashes, immediate filter changes, repeated/missing allowlist sums, reference clears, and randomized signed checksum feeds. There were no remaining differences within these declared API comparisons. The two focused Java filesystem test classes passed 19 tests, and `AuditPureFilesystemBoundaries.py` passed 57 additional native boundary assertions.

```sh
./gradlew :craftq3-assets:classes
mkdir -p .tools/pure-filesystem-oracle/classes
javac -cp craftq3-assets/build/classes/java/main:craftq3-core/build/classes/java/main \
  -d .tools/pure-filesystem-oracle/classes scripts/PureFilesystemJavaOracle.java
python3 scripts/AuditPureFilesystem.py \
  --java-classpath .tools/pure-filesystem-oracle/classes:craftq3-assets/build/classes/java/main:craftq3-core/build/classes/java/main \
  --output .tools/pure-filesystem-oracle/production-corpus.json
```

The authored native fixtures also record missing-pack reporting and name/case boundary observations beyond the production comparison. Native scanning of changed files, directory-list records, permissive invalid paths, and malformed archive reads are not claimed as production parity. The production path validator, CRC checks, fixed archive inventory, and work limits remain enforced.
