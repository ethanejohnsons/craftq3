# Browser ping queue and info replies

`client.net.BrowserPings` implements the measured ping queue and server-info updates behind the original UI browser. `LanServerList` owns the three fixed server arrays. The host owns DNS, UDP sockets, connectionless framing, polling and the admission window for unsolicited LAN responses. The ping helper performs no network I/O, waits or background work.

## Reference and reproduction

The native reference is official ioquake3 commit `588393618dbc82e7207c21c6ddecca229944a03a`. Public `client.h` supplies `ping_t`, `serverInfo_t`, `clientStatic_t` and the 32-slot capacity; `qcommon.h` supplies network types and packet signatures. `BrowserPingOracle.c` calls unchanged native ping, reply and visible-list operations with authored clock, cvar, argument and packet-send callbacks. Its numerical address boundary uses `inet_pton`; address comparison, reply decoding and queue logic remain native. Captured sends never reach a socket. No original assets are used by these probes.

An early overbroad symbol-discovery search emitted native implementation matching lines. Those excerpts were not used, copied or translated. Subsequent discovery was restricted to public headers, symbol tables and anchored definition signatures. Every behavior below was established through authored calls and output/state comparisons.

```sh
python3 scripts/BuildBrowserPingOracle.py
./gradlew :craftq3-client:classes
python3 scripts/AuditBrowserPings.py --cases 1000
python3 scripts/BuildBrowserPingDefaultsOracle.py
.tools/browser-init-oracle/probe
```

Use Java 25; the audit accepts `--java` for its executable. `BrowserPingJavaOracle.java` is an authored adapter to the production class. Reflection only seeds its private slot metadata for controlled capacity cases; there are no production test hooks. `AuditBrowserPings.py` compares captured packets, queue count, every occupied slot, returned strings/times, and the resulting server records after each operation.

The initialization observer interposes only the exact `CL_InitRef` definition header in generated compiler IR, using an authored no-op to suppress renderer/device startup. It leaves `CL_Init` instructions unchanged, stops at the requested cvar callback, and aborts if a filesystem callback is reached. Native registration returns:

| Cvar | Default string | Flags |
| --- | --- | ---: |
| `cl_maxPing` | `800` | `1` (`CVAR_ARCHIVE`) |
| `cl_serverStatusResendTime` | `750` | `0` |

## Public host boundary

```java
BrowserPings(LanServerList servers, IntSupplier clock, IntSupplier maxPing,
             String gameName, int protocol, int legacyProtocol,
             Consumer<Request> outbound)
record Request(InetSocketAddress address, String command)
record Ping(InetSocketAddress address, int milliseconds)
```

`request(address)` returns the selected slot and emits one `Request`, whose command is exactly `getinfo xxx`, without the four marker bytes or a NUL. `count`, `clear`, `get`, `info`, `occupied` and `expects` expose bounded queue operations. Addresses must already be resolved and have a nonzero port. `expects(address)` checks occupied pending slots without reading the clock, changing a timeout, or modifying server records; it is suitable for host packet-source admission. `occupied(index)` also has no side effects and supports the VM output-buffer rules below.

`receiveInfo(source, bytes)` consumes bytes after the `infoResponse` command line and returns whether a pending ping or new local discovery record accepted them. It bounds input at 16 KiB. `discoverySource` controls unsolicited local acceptance. The host must limit that acceptance to its own active LAN discovery window. `updateVisible(source)` fills available queue slots, then drains completed/expired slots. `setGlobalOverflow` takes an owned immutable list of resolved master results; replacement consumes addresses from its end.

## Queue lifetime

The native queue has exactly 32 slots. A direct request takes the first unused slot, pending slot aged at least 500 ms, or completed slot whose measured latency is at least 500 ms. This scan can reuse an earlier eligible slot before a later empty one. If none qualify, it replaces the oldest start time, with the first slot winning a tie. A fast completed result stays occupied regardless of its subsequent age. Duplicate addresses are allowed by direct requests.

Starting a request resets start time and latency, retains old info, sends one packet, and sets matching server-record pings to zero without clearing their other metadata. `clear` only marks the slot unused; old start/time/info remain hidden until reuse. Invalid clears do nothing.

For a pending slot, `get` returns zero while elapsed time is below `max(100, cl_maxPing)` and returns the current elapsed time at or above the boundary. This does not turn the slot into a completed response or clear it. A late reply is still accepted. A completed slot returns its measured latency. Native signed negative elapsed results are retained; any nonzero result is drained by `updateVisible`.

Clock zero is valid for the first frame. A response in the same millisecond updates info but leaves latency zero, so the slot remains pending and can accept another response. There is no required first-frame clock offset.

`updateVisible` accepts sources 0, 2 and 3. Source 1 changes the discovery source but does not enqueue or drain, despite being a global-array alias in LAN storage. Out-of-range source values preserve the previous discovery source and do nothing. Valid calls drain pending queue work even if their selected list has no rows.

Only counted rows with nonzero visibility and ping exactly -1 enqueue. Existing active addresses are not duplicated. Enqueueing precedes draining, so slots released during this call are reusable on the next call. A call returns true if queue work was active, even when it clears the final slot. Null unresolved DNS placeholders are explicitly skipped by the host-facing implementation until a real endpoint is assigned; it does not attempt native NA_BAD sends.

A visible global row with ping zero can be replaced from the last overflow address, including while an older request is still pending. Replacement resets its metadata and ping to -1, preserves visibility, and does not send until a later call. Overflow replacement by itself returns false.

## Reply filtering and record updates

The pending match is IP bytes plus port, preserving IPv4/IPv6 family. Native comparison ignores IPv6 scope IDs. The first matching pending slot consumes the reply; subsequent duplicates do not change already completed slots.

Protocol must match the configured protocol or a nonzero configured legacy protocol. Native decimal-prefix parsing accepts values such as `071x`. Missing game name is accepted; a supplied game name must match case-sensitively. The `challenge` field is not checked by the native info handler.

Native message decoding stops at NUL or 1,023 bytes, changes percent signs and bytes above 127 to periods, and preserves newlines. Info lookup is case-insensitive and uses the first duplicate key, tolerates a missing leading backslash, and ignores incomplete pairs. Stored hostname/map/game strings are truncated by `LanServerList` to 79/31/31 bytes.

The accepted queue info removes the first case-insensitive `nettype` pair and prepends `nettype=1` for IPv4 or 2 for IPv6 if it fits. Later duplicate nettype fields remain. At reception, matching server records are updated from the decoded packet info, including its supplied nettype. A subsequent `get` updates them from the augmented queue info. This observable distinction is intentional.

Updates scan all local/global/favorite array capacity, including records outside active counts. Addresses and visibility are preserved; missing info fields become empty strings or zero integers. Pending `get` also publishes retained old queue info with ping zero.

An unmatched accepted response only adds a local record while discovery source is 0. It scans for the first empty address before checking later duplicates, creates a blank record with ping -1, preserves the slot's visibility and sets count to index+1. Packet hostname and other metadata are not copied into this newly discovered row until it is pinged.

## VM buffers and local broadcast

Native `CL_GetPing` and `CL_GetPingInfo` zero-fill the full positive output capacity for an occupied slot, then truncate to capacity minus one. Invalid or cleared slots write only the first NUL; remaining caller bytes are preserved. Invalid `CL_GetPing` returns time zero. The byte immediately beyond capacity is untouched. `occupied` allows the VM bridge to distinguish these cases without accidentally polling the clock.

The authored `local` command calls unchanged `CL_LocalServers_f`. It clears all 128 local addresses and metadata/pings to zero while preserving each visibility field, sets count and discovery source to zero, and leaves the existing ping queue intact. It immediately performs two passes over ports 27960–27963. For each port it emits IPv4 broadcast then IPv6 multicast, each exactly 15 bytes: four `ff` marker bytes followed by `getinfo xxx`, without newline or NUL. Repeating the command at the same clock sends the sequence again. Public `PORT_SERVER` and `NUM_SERVER_PORTS` declarations confirm the range. The current IPv4-only host discovery scope therefore emits eight broadcast packets and does not claim IPv6 multicast support.

## Validation

The production/native audit passed **179,794 operation and full-state comparisons, zero differences**: 1,000 seeded 32-slot capacity cases, 1,000 request/reply/clear/timeout lifetimes, and 100 saturated visible-list/overflow lifetimes. Separate focused probes cover protocol/game/challenge/source handling, all-capacity metadata, duplicate keys, native byte conversion, IPv6 scope comparison, exact 100/500 ms boundaries, time zero and output-buffer sentinels. Twelve authored unit tests cover these boundaries plus side-effect-free host admission and inert DNS placeholders.

Ignored captures and machine-readable results are under `.tools/browser-ping-oracle/` and `.tools/browser-init-oracle/`. Native binaries and observer objects are development artifacts and are not bundled with the mod. This verifies the queue/provider contract; actual menu-to-transport integration is a separate host audit.
