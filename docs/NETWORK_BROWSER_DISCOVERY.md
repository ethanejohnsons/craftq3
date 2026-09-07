# Master discovery and server status

`core.net.MasterServerResponse` owns decoded, inert master-response endpoints.
`client.net.BrowserStatus` owns the bounded native-observed status request state.
Transport, accepted peer selection, global-list insertion and UI memory writes
belong to the browser host. [LAN records](NETWORK_UI_LAN.md) and
[asynchronous resolution](NETWORK_BROWSER_RESOLVER.md) are separate services.

## Reference and reproduction

The reference is official ioquake3 commit
`588393618dbc82e7207c21c6ddecca229944a03a`. Public `client.h`, `q_shared.h` and
`ui_public.h` provide structures, bounds and signatures. The authored
`scripts/BrowserDiscoveryOracle.c` invokes unchanged `CL_ServersResponsePacket`,
`CL_ServerStatus`, `CL_ServerStatusResponse` and `CL_GlobalServers_f` operations.
No native routine body or disassembly was read, copied or translated.

`BuildBrowserDiscoveryOracle.py` compiles the original client, message reader,
address-comparison and shared-string translation units. Compile-time symbol
renaming replaces only the outbound formatter and OS address lookup definitions:
the authored host prints outbound arguments and accepts numeric literals using
`inet_pton`. No socket, public-master request, external DNS lookup, renderer,
original-media extraction or native runtime embedding is involved.

With Java 25 selected through `JAVA_HOME`:

```sh
python3 scripts/BuildBrowserDiscoveryOracle.py
./gradlew :craftq3-client:classes
python3 scripts/AuditBrowserDiscovery.py
python3 scripts/AuditBrowserDiscoveryBoundaries.py
```

The published production driver, `BrowserDiscoveryJavaOracle.java`, uses the
compiled Java services. The seeded replay passes **3,000 master packets and
15,000 status operations**, with **228,274 observed output lines exactly equal**.
Six parser tests and eight status tests pass. The corpus covers malformed tails,
mixed address families, duplicate records, caps, delayed/duplicate replies,
cache reuse, capacity pressure, signed clock extremes, and byte-string decoding.
The separate boundary driver passes five positive output capacities, four
pending/ready nonpositive-capacity states, stale-row initialization and IPv4/IPv6
master request formatting through the native observer.

## Master packet records

`MasterServerResponse.parse(byte[] packet, int senderScopeId)` requires the
connectionless four-byte marker and a recognized `getserversResponse` or
`getserversExtResponse` prefix. The existing connectionless envelope caps the
whole packet at 16,384 bytes. Prefix suffix text is not interpreted: parsing
seeks the first eligible address delimiter after the recognized prefix.

| Record | Bytes after delimiter | Available in |
| --- | --- | --- |
| `\` | 4 IPv4 bytes, 2 big-endian port bytes | Both responses |
| `/` | 16 IPv6 bytes, 2 big-endian port bytes | Extended response |

A complete record must be followed by another `\` or `/` delimiter. A missing
or different following byte discards that record and stops. The conventional
`\EOT` tail terminates because it is too short for another record; the letters
are not a special parser token. For example, a padded `\EOTabc\` is a complete
IPv4 record. Classic parsing seeks an initial backslash even if earlier bytes
contain a slash, but stops when a slash record follows an accepted IPv4 record.

At most 256 complete records are retained, **including duplicates**. The parser
does not resolve, deduplicate, connect to or ping them. Zero addresses and port
zero remain inert parsed metadata; transport validation is separate. The
response has no per-address protocol field. Its association with an outstanding
protocol/filter request belongs to the host.

`Endpoint` owns cloned address bytes and preserves a 16-byte IPv6 family even
for IPv4-mapped addresses. IPv6 endpoints inherit the supplied sender scope;
IPv4 endpoints use scope zero. Native IPv4's unused scope storage can retain
arbitrary bytes, so the observation driver canonicalizes only that unused field
to zero. `socketAddress()` constructs numeric addresses without DNS.

The separate native insertion observation established global-list policy:
addresses are deduplicated while the 4,096-entry primary list has room. Once
full, the 4,096-entry overflow list receives every remaining record, including
duplicates and addresses already in the primary list. When a response fills the
last primary slot, later records in that same response follow the overflow
rule. A first reply with primary count -1 resets both counts before insertion.

An accepted new row copies the address, empties hostname/map/game, zeroes
net type, game type, client/max-client counts, min/max ping, PunkBuster,
human-player and password fields, and sets ping to -1. **Its existing visibility
integer is preserved.** The authored `seedrow`, `recount`, `master` and `row`
commands expose this stale-slot behavior through public `serverInfo_t` fields.

## Master requests and UI profiles

With an authored numeric IPv4 master, native `globalservers 1 68 empty full`
emits `getservers 68 empty full`. With IPv6 it emits
`getserversExt Quake3Arena 68 empty full`. Protocol 71 and supplied filter tokens
are likewise retained. These observations use the enabled dual-stack fixture;
they do not establish every single-stack request option. Index zero enqueues one
command for each nonempty configured master slot 1–5. A valid explicit request
sets primary count -1 and update source 2; overflow is reset on its first reply.
Invalid indices or missing protocol arguments produce no send.

The public fallback master name is `master.quake3arena.com`; `Quake3Arena` is the
published default game identifier. The audit never resolves or contacts that
domain. Actual configured master cvars and asynchronous dispatch are host policy.

The original retail UI profile remains the exact-module/API-3 profile documented
in [UI ABI](formats/UI.md): imports 46/47 are local count/address, 48/49 are global
count/address, and retail 50–53 are ping queue count/clear/get/info. They must not
be dispatched as the modern import numbers. The published API-4 table uses
46–49 for ping operations, 65–74 for generalized list/cache mutations, 82 for
server status, 83 for server ping, 84 for visibility and 85 for comparison.
This table records the existing verified retail profile and public modern
declarations; it does not infer unverified retail extension imports 57–99.

## Status queue and text

`BrowserStatus` borrows an outbound callback. `query(endpoint, capacity, now,
resend)` returns an optional completed string, `receive(endpoint, body)` accepts
raw bytes after the `statusResponse` command line, and `reset`/`resetAll` manage
slots. Endpoints must be resolved and have a positive port. The caller provides
signed millisecond time and the resend cvar on every query.

There are 16 slots. A new query sends exactly the connectionless `getstatus`
command, without a newline, challenge or extra argument. Pending results leave
the guest destination untouched. Retry is the native strict signed comparison
`started < now - resend`; subtraction happens before comparison, including
integer wrap. The default `cl_serverStatusResendTime` is **750 ms, flags 0**,
captured by the unchanged initialization callback observer
`BrowserPingDefaultsOracle.c`/`BuildBrowserPingDefaultsOracle.py`.

A completed query releases its slot for reuse, resets its start time to zero and
returns the cached result until that slot is reused. Selection first finds the
same address, otherwise the first released slot, otherwise the earliest start
time with first-index ties. An unknown address cannot evict a full pool of 16
unreleased queries. Resetting an individual address marks the selected slot
released without clearing pending state or cached text; resetting an unknown
address can release the selected oldest slot. Reset-all removes address matches.
Replies to retained addresses can update even completed/released slots; unknown
senders are ignored. A reply does not itself change the released flag.

The first native message line is the info text. Output is that line followed by
two backslashes, then each nonempty subsequent line followed by one backslash.
The first empty player line ends output. Numeric fields and quoted names are not
parsed or executed. Each line consumes through newline, NUL or end of input;
the 1,023-character cap consumes one additional byte. Percent signs and bytes
above 127 become periods, while carriage returns remain. Stored text is capped
at 8,191 characters and raw input at the connectionless payload bound.

## Guest buffer boundary

For a ready result, native positive-capacity writes zero-fill the **entire**
destination, even when it is much larger than the stored text. Direct capacities
1, 8, 8,192, 8,193 and 65,536 confirm this. Pending calls preserve all destination
bytes. A ready native query with zero or negative capacity invokes the fatal
`Q_strncpyz` destination-size check; a pending query can leave that same invalid
capacity untouched.

The production provider accepts capacity 1–8,192. The UI host validates the full
positive guest range before any request, caps only the provider text query, and
zero-fills the full validated capacity on success. It rejects nonpositive guest
output capacities before network side effects. The native pending invalid-size
no-op is therefore an explicit boundary difference, not a supported way to
initiate a request. Reset through a null destination is a separate operation.
