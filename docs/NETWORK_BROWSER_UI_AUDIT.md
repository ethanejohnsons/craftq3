# Original multiplayer menu and application audit

Two independent CPU observers cover the original UI's browser commands and the actual application path from discovery through Join. They execute user-supplied original bytecode with the existing asset/renderer/VM pipeline. They do not inspect native engine routine bodies, alter guest memory, fabricate server-list records, or inject a connect command.

## Original UI commands

`scripts/AuditMultiplayerMenu.java` initializes the retail UI or a supplied QA UI, enters Multiplayer through normal key events, and records the engine commands and browser callbacks the guest emits. Its empty borrowed browser captures commands without opening any socket. Menu labels come from the guest's submitted `gfx/2d/bigchars` quads, decoded from the 16-by-16 atlas coordinates; no menu implementation data is copied.

Use Java 25 and the compiled nine-module class directories on the classpath. Example actions after entering Multiplayer are `t10000,m330:88,178,178,178,178`: advance the UI clock beyond the initial refresh, move to the source selector, then click four times. `t` advances the authored clock, `mX:Y` moves the mouse, and an integer emits a normal key press/release. The optional second argument is a public QA `ui.qvm`, or `-` to keep the mounted original. `-Daudit.protocol=68` and `-Daudit.debugProtocol=68` select host cvar inputs for the protocol observations.

| UI | Initial source and refresh | Observed source cycle |
| --- | --- | --- |
| Original retail, API 3 | Local; `localservers` | Local, Mplayer, Internet, Favorites |
| Public QA UI, API 4 | Internet; `globalservers 0 71 empty full` with protocol 71 | Internet, Favorites, Local, when additional master cvars are unset |

The retail Mplayer option emits `globalservers 1 <protocol>`; Internet emits `globalservers 0 <protocol>`. Both use the legacy global-count callback, which accesses the global server array. Favorites emits no new master or LAN discovery command. The API 4 UI records browser source choice in its own cvar (`ui_browserMaster` observed as 1 for Internet, 7 for Favorites, 0 for Local).

Both versions use the supplied `protocol` cvar. With `protocol=68`, the emitted master filter is 68. A nonempty `debug_protocol=68` overrides protocol 71 in the newer UI; the retail UI continues to use 71. These are captured guest decisions, not command rewriting by the host. The production connection host registers protocol 68 for the protocol it implements.

Retail defaults show-full and show-empty off. The newer UI defaults both on, producing the `empty full` suffix. Selecting Free For All and enabling both retail toggles through mouse input yields `globalservers 0 71 ffa empty full`. Selecting Free For All and disabling both newer toggles yields `globalservers 0 71 ffa`. Filter order is therefore the guest's game filter, then `empty`, then `full` when present.

Raw captures are the ignored `/tmp/craftq3-{retail,modern}-browser-*.log` development logs. `AuditMultiplayerMenu.java` preserves the reusable observer; it does not claim that its empty browser is a network integration test.

## Actual application Join

`scripts/AuditBrowserApplication.py` starts an unchanged native server bound to private IPv4 loopback, creates a disposable game mount, and runs `AuditBrowserApplication.java` through the actual Fabric `QuakeSession` CPU entry point. The local original `pak0.pk3` is hardlinked and read in place. A separate temporary pack contains only the source-built public QA qagame/cgame matching the native server. The newer-profile run also includes the public QA UI; the retail-profile run keeps the original UI from pak0. Original archives are not extracted or modified.

Before opening Multiplayer, the observer empties all five master cvars. It substitutes only the host's LAN target selection with `ServerBrowser.discoverLocal([private server])`, avoiding broadcasts and public master traffic. The real browser sends and parses real UDP packets and serves ordinary original-UI callbacks. Reflection only obtains that existing private browser and wraps the existing connect handler to record and delegate calls; it never installs a synthetic row or connection result.

The original UI opens Local discovery, toggles the retail empty-server filter through ordinary input, refreshes, displays the native server's hostname and positive measured ping, and selects Join through its existing button. The observer requires exactly one guest-generated `connect 127.0.0.1:<port>`, delegates it unchanged to the actual session handler, and then requires 90 remote cgame frames with view submissions. It also verifies that there is no local server, `sv_running=0`, and ordinary disconnect returns to the original main menu.

```sh
python3 scripts/AuditBrowserApplication.py --profile both
```

The script uses the existing opt-in `:craftq3-fabric:auditRemoteApplication` task with `-Pq3RemoteAuditBrowser=true`. Native server and public QA modules must already be built as required by `NativeNetworkServer.py`. Each profile has a 90-second process bound. There is no GUI, public host, DNS-dependent master or framebuffer requirement.

| Profile | Original refresh commands | Real rows | Observed ping | Guest connect commands | Remote frames | View submissions | Quad submissions | Returned to menu |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| Retail API 3 | 2 | 1 | 63 ms | 1 | 90 | 270 | 9,792 | Yes |
| QA API 4 | 2 | 1 | 70 ms | 1 | 90 | 270 | 9,379 | Yes |

Both runs passed. Latency and render-submission counts are observations from these runs, not fixed expectations for a scheduler or remote server. The assertions require positive latency, the correct address/hostname, exactly one original connect command, successful remote presentation and menu recovery.

Machine-readable results and complete logs are under `.tools/browser-application/{retail,modern}/`. This checkpoint covers CPU presentation, original menu input, real loopback discovery/ping, actual connection/cgame presentation, and cleanup. Master response parsing, status replies, favorite persistence, rendered GPU output and broader remote lifecycle have separate provider/application audits; this specific Join replay uses Local discovery.
