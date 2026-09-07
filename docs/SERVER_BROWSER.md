# Original multiplayer menu and server browser

CraftQ3's original `ui.qvm` owns the multiplayer menu, source selector, filters,
sorting, favorite actions, and Join button. The host supplies bounded server
storage, discovery, pings and status queries through both the retail API3 and
Q3 1.32 API4 layouts. `QuakeSession` retains this browser across local/remote
maps and pumps it while the menu or a game is running.

## Use

Open **Multiplayer** in the original menu. Local refresh sends IPv4 LAN discovery
to ports 27960–27963; Internet refresh queries configured masters. The retail
Mplayer source uses `sv_master1`; Internet uses the configured `sv_master1` through
`sv_master5`. The initial fallback is `master.quake3arena.com`; additional master
settings are empty until configured. Set a master to an empty string to disable
it. Master names resolve outside the engine/UI thread. IPv6 unicast masters and
servers are supported; IPv6 multicast LAN discovery is not implemented.

The host's `protocol` cvar is 68, matching its remote connection codec. Original
UI filter commands use that cvar. A server still needs compatible, installed
modules and media; a listing is not proof of mod or pure-pack compatibility.
Missing required PK3s are reported by the existing connection preflight.

Console commands are `localservers`,
`globalservers <master 0..5> <protocol> [filters]`, `ping <server>`, and
`serverstatus <server>`. Direct `connect <server>` remains available. Master
addresses accept an optional port (default 27950); game endpoints default to
27960. The default ping limit is `cl_maxPing 800`; status retries use
`cl_serverStatusResendTime 750`. Their registrations were observed through an
authored callback during unchanged native initialization.

Favorites and global entries use the isolated game-home file
`craftq3-browser-v1.bin`. It is a versioned CraftQ3 cache, not a native
`servercache.dat` file. It preserves resolved IPv4/IPv6 endpoints, numeric scopes,
metadata and visibility. Unresolved placeholders and an in-progress global list
are omitted. The bounded cache is validated completely, including its checksum,
before either list changes. It never writes to original PK3s.

## Boundaries and ownership

`ServerBrowser` adapts `LanServerList`, `BrowserPings`, `BrowserStatus`,
`MasterServerResponse`, `BrowserCache`, `BrowserResolver`, and the platform's
`DiscoveryTransport`. VM callbacks queue work; sockets are opened lazily and use
nonblocking datagrams. At most 128 unsent requests and 128 pending host actions
are retained. A pump receives at most 64 packets per address family. A would-block
send expires after one second; discovery responses are accepted for ten seconds.
Master responses require a requested endpoint. Info replies require a pending
ping or an explicit active LAN refresh. Status replies require a retained status
request. Received strings and addresses remain data and are never executed.

DNS permits at most 64 actual workers and retains at most 256 cache entries.
Lookups time out after 15 seconds; failed lookups can retry after 30 seconds.
Cancellation does not release a worker permit until the resolver actually
returns. Shutdown requests cancellation without waiting on platform DNS. Numeric
literals do not enter the DNS worker pool. IPv4 is preferred in mixed DNS results;
scoped and IPv4-mapped IPv6 literals preserve their address family.

A favorite added by hostname initially occupies an inert placeholder. Stable
host-only slot IDs allow its completed lookup to preserve UI changes to ping or
visibility and prevent completion from replacing a removed/reused row. IDs do
not alter native list/count/sort behavior. Native NA_BAD static-string residue is
intentionally excluded: an invalid address displays as empty instead of a stale,
unrelated endpoint. Unspecified, multicast and port-zero advertised endpoints
are never queried by this host.

Valid native list slots and occupied ping slots zero-pad the entire guest output
buffer. Missing/cleared slots write only the first NUL byte. A pending status
request leaves all existing bytes untouched; a ready result zero-pads its full
validated positive capacity. Invalid guest pointers are rejected before a request
can start or polling can consume state. Nonpositive status capacities are rejected
before side effects, even though native pending requests can ignore that invalid
capacity until completion.

These scheduling, DNS, response-window, cache and IPv4-LAN decisions are explicit
host policy. Exact native parity is claimed only for the separately documented
list, ping, status and master parser domains. Public Internet reachability,
arbitrary mod combinations, native binary cache import, downloads and automatic
mod switching are not established by the private tests.

## Evidence

The browser increment passes 58 client tests: LAN storage/identity 13, ping 12,
status 8, cache 8, resolver 7, original guest ABI 4, and host integration 6. Four
platform tests exercise IPv4/IPv6 loopback transport, multiple peers, packet
ownership, empty/oversize datagrams and lifecycle bounds. Six core tests cover
master response parsing. These counts are an increment, not the full aggregate
build count; see [the validation record](VALIDATION.md).

The unchanged native comparisons cover 99,531 LAN comparisons (with 1,108
explicit NA_BAD display exclusions), 179,794 ping operations, 15,000 status
operations and 3,000 master packets. Authored synthetic QVMs verify output memory
and malformed-pointer behavior. Original retail and source-built public UI QVM
captures verify source cycling, cvar-selected protocol, filter order and emitted
refresh commands. No native engine routine was copied or translated; original
PK3 media and QVMs remain direct read-only archive inputs.

See [LAN semantics](NETWORK_UI_LAN.md), [ping observations](NETWORK_BROWSER_PINGS.md),
[master/status observations](NETWORK_BROWSER_DISCOVERY.md),
[asynchronous resolver policy](NETWORK_BROWSER_RESOLVER.md),
[original-menu audits](NETWORK_BROWSER_UI_AUDIT.md),
[remote play](REMOTE_PLAY.md), and [validation](VALIDATION.md). Actual menu-to-match
application results are recorded in the validation checkpoint after the complete
private run. This increment adds no Minecraft window or GPU capture.

The actual CPU application audit now passes both profiles. Retail API3 displayed
one native server at 63 ms, issued exactly one original UI `connect`, and ran
90 remote frames with 270 views and 9,792 quads. API4 did the same at 70 ms with
270 views and 9,379 quads. Both returned to the original main menu. Ping values
are run observations, not fixed latency expectations. Only the LAN target list
was redirected to the private endpoint; production sockets and reply parsing
supplied the rows, and ordinary menu input generated Join.

```sh
JAVA_HOME=/path/to/java25 python3 scripts/AuditBrowserApplication.py
```

The private audit clears every master setting before opening Multiplayer and
sends no LAN broadcast or public-master traffic. Original pak0 remains a direct
read-only hard link; only public source-built QA modules are placed in its own
temporary override pack. These CPU results do not establish a new GPU capture.
