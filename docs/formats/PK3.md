# PK3 and virtual paths

PK3 files are ZIP archives. CraftQ3 uses the JDK ZIP reader, retains archive handles in a mounted snapshot, and validates names and metadata before publishing that snapshot. It never extracts archive members. Reads are bounded and verify expanded size and CRC.

Lookup order is selected mod, then baseq3. Within each directory, PK3s sort descending by case-insensitive filename, then loose files. Later names override earlier ones. `/q3 fs which` reports the winning source; `/q3 fs status` lists actual order. Fixtures test `pak0`, `pak9`, `pak10`, mod loose-file overrides and fallback. Root loose files outside game directories are not mounted.

Virtual file paths use forward slashes and locale-independent lowercasing. Backslashes normalize to slashes. Absolute/drive paths, control characters, dot/dot-dot/empty components and trailing separators are rejected. Maximum length is 255 characters. Directory enumeration accepts an optional trailing slash and returns sorted, deduplicated recursive filenames. Ambiguous normalized names fail to avoid platform-dependent results. No QVM write capability exists.

Loose files are indexed without following symlinks and revalidated before opening. Rebuild mounts to see new/deleted loose files or modified archives; do not edit archives while mounted. The system accepts a configured installation root, not an arbitrary collection of host search paths. Security limits and deliberate compatibility differences are in `ARCHITECTURE.md` and `COMPATIBILITY.md`.

Behavioral reference: [ioquake3 filesystem](https://github.com/ioquake/ioq3/blob/master/code/qcommon/files.c). This describes the implemented scope, not a claim that all engine filesystem syscalls exist.

ZIP32 footer preflight bounds the central directory before JDK indexing. ZIP64 and split archives are rejected explicitly. Record layout reference: [PKWARE APPNOTE](https://pkware.cachefly.net/webdocs/casestudies/APPNOTE.TXT).
