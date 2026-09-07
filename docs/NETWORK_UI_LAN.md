# Original UI server-list records

`client.net.LanServerList` implements the bounded server-list storage and presentation callbacks used by the original UI. It does not discover servers, resolve hostnames, send packets, persist favorites, or own the asynchronous ping queue. The host and the separate browser services own those operations.

## Native reference

The reference is official ioquake3 commit `588393618dbc82e7207c21c6ddecca229944a03a`. Public `client.h` declares `serverInfo_t` and the three arrays in `clientStatic_t`; `q_shared.h` declares source numbers and capacities; `ui_public.h` declares sort keys. The LAN operations are static definitions in `client/cl_ui.c`, so the authored `UiLanOracle.c` includes that unchanged translation unit and calls them directly. No native routine body or disassembly was read, copied, or translated.

`UiLanAddressOracle.c` includes unchanged `qcommon/net_ip.c` and initializes only its declared address-parser enablement cvar. It does not initialize the network subsystem or open a socket. The observer also links unchanged `q_shared.c` and `net_chan.c`. All ordinary address inputs are authored numeric IPv4/IPv6 literals. A named fixture callback returns resolution failure with a zeroed address to observe LAN failure handling without making a DNS request; otherwise it delegates to the unchanged native parser.

```sh
python3 scripts/BuildUiLanOracle.py
./gradlew :craftq3-client:classes
mkdir -p .tools/ui-lan-oracle/classes
javac -cp craftq3-client/build/classes/java/main \
  -d .tools/ui-lan-oracle/classes scripts/UiLanJavaOracle.java
python3 scripts/AuditUiLan.py \
  --classpath craftq3-client/build/classes/java/main:.tools/ui-lan-oracle/classes \
  --output .tools/ui-lan-oracle/production-corpus.json
```

Use Java 25. The native host, executable, records and captures remain development artifacts; no native runtime is embedded in the mod.

## Storage and mutation contract

| Source | Native storage | Capacity |
| --- | --- | ---: |
| 0, LOCAL | Local servers | 128 |
| 1, MPLAYER | Alias of global servers | 4,096 |
| 2, GLOBAL | Global servers | 4,096 |
| 3, FAVORITES | Favorite servers | 128 |

The alias applies to the LAN storage callbacks; it does not imply that the separate native asynchronous ping updater accepts source 1.

`count(source)` returns the active count, or zero for an invalid source. Getters and comparison use **array capacity**, independently of the active count. A retained slot can therefore return metadata when the active count is zero. Invalid indices/sources produce an empty address/info string, ping -1, visibility 0, and comparison 0. A valid unused slot has zero numeric fields, so its info string is not empty.

`markVisible(source, -1, value)` updates every slot in that source's capacity. Other valid indices update one slot. The raw qboolean integer is retained: values such as 2 and -7 are not normalized. `resetPings(source)` sets every slot's ping to -1 and preserves other fields and counts.

`add` returns 1 for addition, 0 for an existing equal address, and -1 for an invalid or full source. Capacity is checked before duplicate detection, so even a duplicate returns -1 when the list is full. A new entry overwrites only address, hostname and visibility=1. Previously retained map/game/client/ping fields in that array slot survive. A duplicate does not rename or otherwise update the existing record.

`remove` shifts complete records to fill the removed position and decrements the active count. The previous final slot is not cleared. An addition that reuses that slot therefore retains its old metadata.

The production `setCount` accepts 0 through capacity, plus the pending-discovery sentinel -1 for global sources 1/2. Invalid counts are rejected. Add returns -1 and remove does nothing while that count is pending; unsafe native indexing with malformed counts is not emulated.

## Immutable records and network coupling

The public record is:

```java
Entry(InetSocketAddress address,
      String hostName, String mapName, String game,
      int netType, int gameType, int clients, int maxClients,
      int minPing, int maxPing, int ping, int visible,
      int punkbuster, int humanPlayers, int needPass)
```

Addresses are already resolved; null represents an inert failed/pending address. Strings are native byte strings, bounded on input and truncated to the native stored lengths: hostname 79, map 31, game 31. Stored semicolons, quotes, newlines and Latin-1 bytes are preserved. Embedded NUL and non-byte Java characters are rejected.

`entry(source,index)` returns an optional immutable snapshot. `updateEntry(source,index,entry)` replaces one record without changing its count. `Entry.empty()`, `withPing`, and `withVisible` support controlled updates. These APIs allow the separately observed ping/reply service to update the original record fields without exposing mutable arrays. Global overflow addresses remain outside this class.

The separate host-only `identity(source,index)` tracks asynchronous address resolution without relying on immutable record object identity. Additions receive fresh monotonic IDs; removal shifts IDs with their records and clears the inactive tail ID. Visibility, ping and same-address metadata updates preserve the ID. An endpoint change or renaming a null-address placeholder replaces it. Zero denotes an invalid or unallocated slot. These IDs are not native fields or part of the cache format.

An authored failure callback established that native `LAN_AddServer` ignores parser failure: it adds an NA_BAD record and returns 1. Repeated failures create multiple entries because NA_BAD does not compare equal. Production accepts null for this inert placeholder, never deduplicates it, and treats removal of null as a no-op. The host may reserve a placeholder while resolving a name, but no transport may use it as a peer.

## Sorting and strings

Keys 0–4 compare hostname, map name, clients, game type and ping respectively. Equal client counts are ordered by maximum client capacity. Other keys, including the published PunkBuster key 5 in this native build, return zero. Any nonzero sort direction reverses the result. Comparisons return -1, 0 or 1 and avoid signed-integer subtraction overflow.

Hostname/map comparisons retain color escapes and leading whitespace. Only ASCII letters fold case; Latin-1 bytes compare with the signed-char ordering observed in the native build. Thus `^1a` sorts after `b`, and byte `0x80` sorts before `a`. There is no general hostname or ping tie-break applied to other equal keys.

`info` emits native fields in this exact order, omitting empty or rejected string fields:

```text
g_humanplayers, g_needpass, punkbuster, addr, nettype, gametype, game,
maxping, minping, ping, sv_maxclients, clients, mapname, hostname
```

Each key/value pair uses the usual leading backslashes. A string containing a backslash, semicolon or quote is omitted from the generated info string, while its stored record value remains intact. Newlines and high bytes are retained in direct LAN metadata; the network message reader may already have applied its separately observed byte conversion before an update reaches this class.

The provider returns a full string. Native guest-buffer writes were observed separately: a valid index uses `Q_strncpyz`, truncates at capacity minus one and zero-fills the entire capacity. Even an empty valid address zero-fills the buffer. Valid capacity zero or negative invokes the native fatal-error path. An invalid source/index writes only the first NUL and leaves the rest untouched, including when the caller supplied capacity zero. The VM bridge owns these bounded memory-write conventions.

Numeric address formatting preserves native IPv4 ports and IPv6 compression, mapped/compatible IPv4 tails and observed local scope display. Local interface metadata supplies scope names for link-local and node/link-local multicast addresses; other scopes are omitted as in the observed native formatter. This performs no name resolution or packet I/O.

## Explicit invalid-address display difference

The native address formatter has a retained static-buffer artifact: after formatting a valid endpoint, formatting NA_BAD returns that previous endpoint's text, even after the server arrays are cleared. Native LAN info can consequently attach another server's `addr` field to an unused or failed record.

Production keeps a null address empty and omits its `addr` field. This preserves the inert placeholder boundary instead of publishing an unrelated connect address. The differential records these NA_BAD display cases as explicit exclusions; addition, counts, removal, visibility and other stored metadata remain covered. No native parse-failure request is represented as a valid resolved endpoint.

## Validation checkpoint

The direct corpus passed **99,531 native/production operation and record comparisons**, using 10,000 independently seeded record pairs and 10,000 state transitions. It covers all sort keys/directions, signed scalar extrema, byte-string cases and truncation, full-capacity indices, aliases, address formatting, exact valid-endpoint info strings, visibility/reset operations, duplicates, removals and retained-slot reuse. It reports **1,108 NA_BAD display exclusions** explicitly, rather than weakening comparison of valid endpoints.

All 13 focused LAN tests pass, covering count bounds, pending discovery, raw visibility, full-array resets, reuse, null placeholders, sort semantics, info omission, immutable snapshots, IPv6 formatting and host-only identity lifetimes. The native fixture's bounded buffer observer verifies sentinel preservation and zero-fill separately from the Java provider's full-string API. These results establish the list service, not server discovery, favorite-file persistence, ping scheduling, DNS timing or complete original-browser behavior.
