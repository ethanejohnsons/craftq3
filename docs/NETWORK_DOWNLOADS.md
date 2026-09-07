# Protocol-68 downloads

Protocol-68 UDP downloads now connect the original loading UI to a verified,
isolated PK3 cache and automatic connection resumption. Enable client downloads
with `set cl_allowDownload 1`; the default is disabled. The server must also enable
`sv_allowDownload 1`. The mounted game directory must match the server.

## Client cache and loading

Missing referenced packs are selected by the server's advertised checksums and
bounded `game/archive.pk3` identities, for both pure and non-pure servers. Existing
matching packs are reused. Multiple files download in sequence; the original UI
reads `cl_downloadName`, `cl_downloadSize`, `cl_downloadCount` and `cl_downloadTime`.
Verification runs on a virtual thread while networking and the UI continue. After
all files pass, `donedl` requests a fresh gamestate; the client rebuilds its content
view and original cgame/UI registrations before sending the pure response.

Downloaded files live in `craftq3/downloads`, beside the configured default games,
home and demos roots. Flat encoded cache names include the remote identity and
checksum. The installation's original PK3s are never replaced or extracted.
Successful entries persist across sessions; a matching older cached version can
be reactivated for another server. Only one cached version of a logical pack name
is active at a time. This overlay does not implement every native home/base/CD
search-path rule.

Staging uses descriptor-relative exclusive creation, an exclusive cache lock,
regular-file checks and descriptor-relative rename after validation. ZIP layout,
normalized member paths, duplicate names, member lengths, every member CRC and
the advertised Quake PK3 checksum are checked. Limits are 512 MiB per downloaded
archive, 128 cache files, 2 GiB total cache storage, 64 MiB per unpacked member and
2 GiB unpacked per archive. Verification checks cancellation between read chunks.
Path/file identities are checked around Java's path-based ZIP reader; this is not
a claim of descriptor-pinned ZIP verification against hostile local filesystem
races. Quake checksums establish protocol identity, not cryptographic authenticity.

Disconnect cancels staging and clears the loading UI. Verification failure returns
to the menu without publishing the failed pack. Completed packs remain cached.
Crash-left temporary files count against quotas; automatic eviction is not yet
implemented. Filesystems without secure directory streams disable this storage.
Starting a requested download ends demo recording; recording cannot begin while
the connection is loading. Original official packs still require local installation.
HTTP redirects, automatic mod-directory switching and cache-management UI remain
unsupported.

## Hosted server

`set sv_allowDownload 1` enables UDP delivery of referenced, already-indexed PK3s.
The default is zero (disabled); the native `DLF_NO_UDP` bit (4) also disables it.
A request must name exactly `game/archive.pk3` from the mounted pack catalog and
its advertised reference set. Official `baseq3/pak0` through `pak8` and
`missionpack/pak0` through `pak3` require local installation. File names from the
network never become arbitrary host paths. The archive stream is read-only,
rejects a substituted final symlink, and closes on cancellation, disconnect,
map replacement or host shutdown. Loose files are not served.

`download`, ordered `nextdl`, `stopdl` and `donedl` commands now drive the transfer.
A completed sequence requests a fresh gamestate before original-qagame entry.
Pending players cannot think while downloading. Existing per-peer byte pacing
also applies to download messages and their netchan fragments. Disabled,
unreferenced and official-pack requests produce a protocol download error.
HTTP redirects and download transport negotiation are not implemented.

## Streaming and format

`DownloadMessageCodec` implements the body of `svc_download`; `ServerMessageCodec`
and established sessions retain it alongside reliable commands. The first block
carries a signed total length, or a negative value followed by an error string.
Subsequent blocks carry a 16-bit block number, signed short payload length and
bytes. The size header is present only at the start of the transfer: block zero
after the 65,536-block wrap has no size header. The session's absolute expected
block therefore participates in decoding. A new requested file resets that
counter while retaining connection reliability.

`DownloadSender` owns a stream and retains at most 48 chunks of 1024 bytes. It
fills the window, transmits one block at a time, waits for ordered acknowledgements
and retransmits after more than 1000 ms without progress. A separate empty block
marks EOF, including when the file ends at an exact block boundary. The transfer
budget is 512 MiB. Source length changes fail instead of producing a successful
EOF. Acknowledgements must refer to an actually transmitted block.

`DownloadReceiver` writes to a caller-supplied staging sink. It ignores out-of-order
blocks, returns the absolute `nextdl` acknowledgement number, supports block wrap,
and enforces both the budget and advertised length. A complete stream is not an
installed or verified PK3: `Pk3Downloads` and `DownloadCache` validate, commit and
mount the result. Demo playback can consume a download operation without granting
filesystem write capability; recordings of long transfer traffic remain unverified.

## Evidence and limits

`BuildDownloadServerOracle.py` and `BuildDownloadClientOracle.py` link unchanged
native server download operations and `CL_ParseDownload`. Only function-definition
linkage is adjusted; filesystem/clock/command effects are authored callbacks. No
native routine body was read or translated into Java. `AuditDownloadProtocol.java`
passes **281 comparisons** of native wire bits, parsed bytes/cursors, acknowledgement
commands, the 48-block window, resend timing, EOF, errors and 16-bit wrap.

Seven core tests cover owned/transactional codec data, normal and wrapped headers,
ordered/sent acknowledgements, timeout and window bounds, receiver ordering and
length limits, changed input streams, empty transfers, session file reset and an
actual streaming transfer across 64 MiB without buffering the complete file. One
asset test covers raw indexed-archive bytes, forged catalog identities and a
replacement symlink.

`AuditHostedDownload.py` runs original retail and public source-built modern
gameplay QVMs against the inspected packaged engine, using ephemeral loopback and
an authored 65,729-byte PK3. Both runs deliver 66 blocks despite 70 deliberately
lost outgoing packets, verify every transferred byte, obtain a fresh gamestate,
complete pure validation and enter original qagame. An official-pak request then
returns a download error. The original pak0 is linked into a private fixture and
read as a ZIP; it is neither extracted nor transmitted. Only the authored fixture
is written/transferred. The audit receiver uses memory and a pre-mounted fixture
filesystem for the subsequent pure check; it does not prove automatic client
installation or content-view refresh. Logs live under
`.tools/hosted-download/{retail,modern}/audit.log`.

Deliberate protections exceed the observed native behavior on malformed inputs:
Java rejects a wrong advertised length and acknowledgements of unsent data. The
native client observer can finalize an early EOF or write beyond the advertised
size; that behavior is not reproduced. No native filesystem installation behavior,
HTTP support, full native client application or new Vulkan capture is claimed.

`AuditDownloadApplication.py --runtime <inspected-runtime>` additionally runs actual
Fabric CPU host/client sessions with different inventories through both original
UI/cgame profiles. Two authored missing packs download and verify, pure gameplay
resumes, a fresh client reuses the cache with downloads disabled, cancellation
removes staging and a corrupted stored ZIP member returns to the original menu
without publishing a pack. The audit asserts that the application/controller/cache
classes load from the inspected runtime. Logs:
`.tools/download-application/{retail,modern}/application.log`. Fifteen new tests
cover cache persistence/version selection, CRC/checksum/path rejection, quotas,
symlinks, locking, cancellation, unsolicited packets and the fresh-gamestate gate.
No new GPU capture or full native-client application coverage is claimed.
