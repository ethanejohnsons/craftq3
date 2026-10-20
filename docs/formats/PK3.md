# PK3 archives and virtual paths

PK3 files are ZIP archives. CraftQ3 indexes them from their central directory with its own
reader, keeps them open, and reads members in place; nothing is extracted. Game files are named
by validated virtual paths and resolved through the original 1.32 search order.

Code (`engine/fs`):
[`Pk3Archive`](../../engine/fs/src/main/java/dev/bluevista/craftq3/engine/fs/Pk3Archive.java),
[`VirtualPath`](../../engine/fs/src/main/java/dev/bluevista/craftq3/engine/fs/VirtualPath.java),
[`SearchPath`](../../engine/fs/src/main/java/dev/bluevista/craftq3/engine/fs/SearchPath.java),
[`SearchOrder`](../../engine/fs/src/main/java/dev/bluevista/craftq3/engine/fs/SearchOrder.java),
[`PathOrder`](../../engine/fs/src/main/java/dev/bluevista/craftq3/engine/fs/PathOrder.java),
[`DirectoryMount`](../../engine/fs/src/main/java/dev/bluevista/craftq3/engine/fs/DirectoryMount.java),
[`Pk3Checksums`](../../engine/fs/src/main/java/dev/bluevista/craftq3/engine/fs/Pk3Checksums.java),
[`PureManifest`](../../engine/fs/src/main/java/dev/bluevista/craftq3/engine/fs/PureManifest.java),
[`DownloadPlanner`](../../engine/fs/src/main/java/dev/bluevista/craftq3/engine/fs/DownloadPlanner.java),
[`DownloadCache`](../../engine/fs/src/main/java/dev/bluevista/craftq3/engine/fs/DownloadCache.java),
[`Pk3Verifier`](../../engine/fs/src/main/java/dev/bluevista/craftq3/engine/fs/Pk3Verifier.java),
[`HomeStore`](../../engine/fs/src/main/java/dev/bluevista/craftq3/engine/fs/HomeStore.java).
Layout reference: [PKWARE APPNOTE](https://pkware.cachefly.net/webdocs/casestudies/APPNOTE.TXT).

## Archive reading

Only ZIP32 archives are supported. The end-of-central-directory (EOCD) record is found by scanning
backwards over the last 22 + 65,535 bytes. A record whose comment length reaches exactly to the
end of the file is authoritative; otherwise the last valid one is used. Data prepended before the
archive (a self-extractor stub) is accepted: the difference between the EOCD position and the
declared directory end shifts every offset.

Tolerance follows the original engine and is applied per entry where possible:

| Condition | Result |
| --- | --- |
| Empty file, truncated, no usable EOCD, ZIP64 locator or sentinels, multi-volume | Archive refused (`Pk3FormatException`) |
| Archive > 2 GiB, central directory > 32 MiB, more than 65,534 entries | Archive refused |
| Central directory record unreadable part way | Stop there, keep the earlier entries |
| Encrypted entry, method other than stored (0) or deflate (8), ZIP64 sizes, stored size mismatch, local header out of range, unsafe name | Entry skipped and recorded |
| Entry expands past 64 MiB (512 MiB for `.roq`), or archive past 2 GiB in total | Entry skipped and recorded |
| Duplicate name | Later entry wins, recorded |
| Directory entries (name ends in `/` or `\`) | Ignored |

Entry names are raw bytes decoded as ISO-8859-1; the UTF-8 flag is ignored, and backslashes are
allowed. At most 16 problems are kept per archive for diagnostics (all are counted). When a
search path is built, an archive that cannot be opened is skipped with a one-time warning and
listed in the mount report, as the original engine skips archives it cannot open.

Reads check the local header signature and data range. Buffered reads are limited to 64 MiB;
cinematics are streamed. Every stream verifies the expanded size and CRC-32 when it reaches its
end. Archives are read with positional I/O, so concurrent reads are safe; if a channel is closed
by an interrupted reader it is reopened once after checking that the file was not replaced.

## Pack checksums

A pack checksum is an MD4 block checksum (`Md4.blockChecksum`) over the little-endian CRC-32 of
every central-directory entry with a nonzero uncompressed size, in directory order, including
entries that were skipped. Names, compression and search order do not enter it. The pure checksum
prepends the server's checksum feed as four little-endian bytes (also when it is zero).

## Virtual paths

- Backslashes become `/`. Lookup keys are ASCII lower case; the caller's spelling is kept for
  reference tracking and diagnostics. Spaces and non-ASCII characters are allowed.
- Rejected: empty, longer than 255 characters, control characters (below 32, and 127), any `:`
  (drive letters, stream names), empty components (absolute paths, `//`, trailing `/`), `.` and `..`
  components.
- Directory listings accept `""` or `/` for the root and an optional trailing slash, and return
  the names directly inside the directory (not recursive) in search order without duplicates; a
  pure view lists no loose files.

## Search order

A search path holds the mod directory (when it is not `baseq3`) followed by `baseq3`. Game
directories are found by exact spelling, then case-insensitively. Within one game directory:

- PK3s are sorted with the original path comparison (ASCII letters compared as upper case, `\` and
  `:` as `/`, other characters as signed bytes) and the last name in that order is searched first.
  Because `_` sorts after letters, `zz_x.pk3` beats `zzz.pk3`; `pak8` beats `pak0`.
- `SearchOrder.ORIGINAL_132` (default): all PK3s of the directory are searched before its loose
  files, which only supply names no PK3 has. `SearchOrder.LOOSE_FIRST` puts loose files first (the
  ioquake3 and earlier CraftQ3 behaviour); see [DIVERGENCES.md](../DIVERGENCES.md).

Loose directories are indexed once, to a depth of 32, without following symbolic links; names that
differ only in case keep the first in sorted order (with a warning), and every read rechecks that
the file is still a regular file inside the directory. Rebuild the search path to see added files.
Limits per search path: 1,024 PK3s and 400,000 indexed files.

## Pure servers

`PureManifest` applies a server's `sv_paks` list. It immediately hides every PK3 not in the list and
every loose file except `.cfg`, `.menu`, `.game`, `.dat` and demos whose `dm_` suffix parses (as C
`atoi`) to protocol 66, 67, 68 or 71. On the next restart the server's packs move to the front in
the server's order. Reads record per-pack reference flags (general, cgame, ui) for
`sv_referencedPaks`, with the original exemptions (`.cfg`, `.txt`, `.shader`, `.arena`, `.bot`,
`.menu`, `.config`, `vm/qagame.qvm`, levelshots).

## Downloads and the home store

`DownloadPlanner` compares a server's referenced packs with what is installed or cached and lists
the downloads still needed (at most 128 per connection). Remote names are `game/name.pk3`, at most
63 characters of printable ASCII without `"` or `;`; a request for an official pack (`baseq3`
`pak0`-`pak8`, `missionpack` `pak0`-`pak3`) is refused.
`DownloadCache` stores each pack under `<home>/.downloads` as
`<checksum>-<base64url(game/name.pk3)>.pk3` (512 MiB per file, 2 GiB total). A download is fully
verified first: no skipped or duplicate members, every CRC correct, pack checksum equal to the
announced one. Cached packs are mounted only for a server that references them; see
[DIVERGENCES.md](../DIVERGENCES.md).

`HomeStore` is the only writable location: configs, demos, screenshots and other VM writes.
Writes are atomic (temporary file and move), never follow links, and refuse non-portable names and
the extensions `pk3 qvm exe dll so dylib jar class`. Default quotas are 16 MiB per file, 256 MiB in
total, 4,096 files, 256 directories and 16 levels. The installation is never written.

Independent implementation; see [LICENSE-NOTES.md](../../LICENSE-NOTES.md). Tests:
`Pk3ArchiveTest`, `SearchPathTest`, `PureManifestTest`, `DownloadCacheTest`, `HomeStoreTest`,
and the `@RealData` `RealInstallationTest`.
