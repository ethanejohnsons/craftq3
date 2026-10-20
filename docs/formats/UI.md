# ui host

The original `vm/ui.qvm` draws every menu: the main menu, setup, the server browser, the in-game
menu, the connect screen. The host supplies the services it imports; menu logic, layout, sounds and
commands all come from the module. The ui and the cgame share one renderer frame (cgame first, then
ui, then the console).

Code (`engine/client`):
[`UiHost`](../../engine/client/src/main/java/dev/bluevista/craftq3/engine/client/host/UiHost.java)
(ui imports and exports),
[`GuestVm`](../../engine/client/src/main/java/dev/bluevista/craftq3/engine/client/host/GuestVm.java)
(shared lifecycle, renderer, sound, keys, fonts, scripts, cinematics),
[`UiBrowser`](../../engine/client/src/main/java/dev/bluevista/craftq3/engine/client/net/UiBrowser.java)
and [`ServerBrowser`](../../engine/client/src/main/java/dev/bluevista/craftq3/engine/client/net/ServerBrowser.java)
(server lists),
[`UiClientStateLayout`](../../engine/vm-abi/src/main/java/dev/bluevista/craftq3/engine/vmabi/layout/UiClientStateLayout.java),
[`RetailImport`](../../engine/vm-abi/src/main/java/dev/bluevista/craftq3/engine/vmabi/RetailImport.java),
[`FileServices`](../../engine/vm-abi/src/main/java/dev/bluevista/craftq3/engine/vmabi/service/FileServices.java),
[`ModList`](../../engine/vm-abi/src/main/java/dev/bluevista/craftq3/engine/vmabi/service/ModList.java).
Every import, with its 1.32 and retail number and policy, is listed in
[SYSCALLS.md](../SYSCALLS.md). Public reference: id Software's
[ui_public.h](https://github.com/id-Software/Quake-III-Arena/blob/master/code/ui/ui_public.h).

## Lifecycle

`start()` loads the module (ABI detected as in [QVM.md](QVM.md)), calls `UI_GETAPIVERSION` and
accepts API 3 (retail), 4 (1.32) and 6 (Team Arena layout; Team Arena itself is not supported),
then calls `UI_INIT(connected)`. Exports are wrapped for `UI_SET_ACTIVE_MENU` (none, main, in-game,
need CD, bad CD key, team, postgame), `UI_REFRESH(realtime)`, `UI_KEY_EVENT`, `UI_MOUSE_EVENT`,
`UI_IS_FULLSCREEN`, `UI_CONSOLE_COMMAND` and `UI_DRAW_CONNECT_SCREEN(overlay)`. A fault or
`trap_Error` restarts the ui; a video restart restarts it and reopens the current menu.

`host_ui [0|1]` is a CraftQ3 extension that shows the platform's own interface, also used when the
ui cannot run ([DIVERGENCES.md](../DIVERGENCES.md)).

## Retail differences

- Import numbering: retail 46 to 49 are local/global server count and address imports that 1.32
  replaced (`RetailImport`); retail 50 to 58 are 1.32's 46 to 54 (pings, cvar register/update,
  memory remaining, get/set CD key). Imports after 54 (fonts, scripts, cinematics, the 1.32 server
  browser) exist only in the 1.32 numbering.
- `UI_KEY_EVENT`: the retail module reads only the key and never expected releases, so releases
  are not sent to it; the 1.32 module receives key and down state.
- `glconfig_t` is 4,164 bytes in retail and 11,332 in 1.32 ([CGAME.md](CGAME.md#struct-layouts)).

`uiClientState_t` is 3,084 bytes in both: connection state 0, connect packet count 4, client number
8, then server name, update info and message strings of 1,024 bytes each at 12, 1,036 and 2,060.

## Services

- **Renderer, sound, keys, fonts, cinematics**: as for the cgame ([CGAME.md](CGAME.md),
  [ROQ.md](ROQ.md)), including key bindings, overstrike mode, key catchers and the clipboard.
- **Client state and configstrings** of the current connection, for the connect screen and the
  in-game menus.
- **Server browser**: local (LAN broadcast), global (masters), favorites and pings, with
  persistent favorites and a server cache, filtering and sorting, server status queries
  ([CONNECTIONLESS68.md](CONNECTIONLESS68.md)). Each frame's network work is bounded.
- **Files**: `trap_FS_GetFileList(directory, extension)` lists names directly in a directory, from
  the home store first and then the search path, case-insensitively deduplicated. Extension `/`
  lists subdirectories. Directory `$modlist` lists game directories (home store first, then the
  installation; not `baseq3` or dot-directories) that directly contain a `.pk3`, with the first
  line of their `description.txt` as description, sorted case-insensitively. Files the guest
  writes go to the home store ([PK3.md](PK3.md)). Handle 0 (left by a failed open) reads and writes
  nothing.
- **CD key**: `trap_GetCDKey` reports the key from `baseq3/q3key`; `trap_SetCDKey` keeps a new key
  for this run only, never writing it ([DIVERGENCES.md](../DIVERGENCES.md)); `trap_VerifyCDKey`
  always succeeds.
- **Scripts**: the `trap_PC_*` preprocessor imports used by script-driven menus.
- `trap_MemoryRemaining` reports a constant 32 MiB.

Independent implementation; see [LICENSE-NOTES.md](../../LICENSE-NOTES.md).
