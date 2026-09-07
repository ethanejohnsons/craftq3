# Original Demos menu catalog

`client.demo.DemoCatalog` combines flat mounted filenames with the host's
separate recording storage. `UiHost.demoFiles()` returns physical `.dm_68`
filenames and defaults to an empty list. `Q3Ui` merges those names into
`FS_GETFILELIST` requests for the canonical `demos` directory. Directory queries
and listings elsewhere keep their existing behavior.

## Retail display aliases

The original retail/API-3 Demos menu requests extension `dm3`, uppercases the
display name, and emits an unquoted command such as `demo RECORDING.dm3`.
The source-built API-4 menu requests `.dm_68` and emits `demo recording.dm_68`
when protocol 68 is configured. These are observed unchanged guest calls through
`scripts/AuditDemosMenu.java`; its fixture names and captured commands do not
execute playback or modify original media. A separate authored 200-character
retail filename observation confirmed that the emitted name was retained intact.

The retail listing therefore exposes a `.dm3` **display alias only for a known
physical `.dm_68` file**. API-4 `.dm_68` queries receive physical names. This does
not rename, convert, open or assert support for a protocol-43 `.dm3` recording.
Physical `.dm3` files are omitted from the retail playable catalog. An existing
same-name physical `.dm3` blocks the synthetic alias to avoid redirecting that
file's name to different content.

## Helper API

```java
List<String> DemoCatalog.files(List<String> mountedNames,
                               List<String> hostNames,
                               String extension, boolean retail);
Optional<String> DemoCatalog.resolveAlias(String name,
                                         List<String> availableNames);
```

The host passes immediate filenames, without a `demos/` prefix. Mounted entries
can include other extensions so actual `.dm3` collisions remain visible to the
helper. Host entries must identify real protocol-68 files; unsupported host
extensions are omitted. The combined input is bounded at 65,536 entries.

Exact duplicate names collapse. Different case spellings of the same identity
are ambiguous and are omitted, including when they came from different input
lists. Results are immutable and sorted without case sensitivity, including
after replacing the physical suffix with its display suffix. The existing VFS
still owns canonical mounted-file identity and search-path precedence.

Because both native menus emit unquoted commands, catalog names must be flat
printable ASCII without whitespace, quotes, semicolons, colon or path separators.
Control/non-ASCII bytes, traversal components, empty protocol basenames and names
longer than 255 characters are omitted. This is an explicit menu compatibility
policy; it does not change the underlying storage validator or authorize a
filesystem path. Ordinary punctuation and known long filenames remain supported.

`resolveAlias` accepts only a safe `.dm3` name whose case-insensitive physical
`.dm_68` target has exactly one spelling. It returns the known physical spelling,
or empty for an unsafe, unknown, conflicting or actual `.dm3` name. It does not
resolve ordinary `.dm_68` requests; those remain the normal playback path.
The caller must pass the real combined availability list, including unsupported
`.dm3` filenames as collision blockers, and must open the resolved result using
its existing bounded storage/VFS API. Alias resolution never reads a file.

## Guest output and verification

The UI retains its existing 1 MiB destination bound and validates the complete
guest range before consulting the host. It returns only complete NUL-terminated
names that fit, writes an initial NUL for a nonempty destination even if no name
fits, and preserves bytes beyond the written list. A zero-capacity query writes
nothing. Counts report written entries, not the entire available catalog.

`DemoCatalogTest` contains five focused cases for merge/deduplication, supported
aliases, unknown and real legacy names, case collisions, unquoted command syntax,
post-alias sorting, long known names and work bounds. `UiDemoCatalogTest` contains
two guest tests spanning API 3 and API 4, host/mounted merges, zero/short/exact/full
capacities, trailing sentinels, default-host behavior and unrelated directory
listings. Existing `UiHostTest` retains directory-list and malformed-range coverage.

```sh
./gradlew :craftq3-client:test --tests '*DemoCatalogTest' \
  --tests '*UiDemoCatalogTest' --tests '*UiHostTest'
```

The catalog supplies menu names and resolves aliases. Demo file parsing, protocol
validation, cgame startup and returning to the menu remain playback integration
responsibilities.
