# Quake III in Minecraft worlds

Two modes join Quake III and Minecraft. Both need your own Quake III installation, configured with
`/q3 path <dir>` (see the README). Both work in singleplayer and on a dedicated server; on a server
the installation is configured on the server, and every player who uses the bridge or sees a
building region needs CraftQ3 and the same game data on their client too.

| Mode | What it is | Start | Leave |
| --- | --- | --- | --- |
| Bridge | Quake movement, weapons, HUD and rules over Minecraft terrain and mobs | `/q3 bridge` or `/q3 bridge fresh` | Shift+Escape, `/q3 leave`, `quit` or leaving the game from Quake's menu |
| Building | A Quake map placed in its own dimension; you build in it with Minecraft blocks | `/q3 build <map>` | `/q3 leave` |

## Bridge

`/q3 bridge` opens the bridge where you stand. You see Minecraft's world as usual, with the
original Quake cgame on top: your weapon, the HUD, effects, other bridged players and items.
Everything Quake (movement, weapons, health, armor, pickups, death and respawn) is run by the
original game module on the Minecraft server, one Quake frame per server tick.

- **Controls**: Quake's own bindings (`q3config.cfg` in `craftq3/home/<game>/`, shared with pure
  Quake). `~` opens the Quake console; Escape opens Quake's menu; Shift+Escape returns to
  Minecraft. Minecraft's hotbar, bars, crosshair and hand are hidden; chat stays.
- **Terrain**: every block's collision shape is Quake collision (slabs, stairs and fences too).
  Water and lava are Quake water and lava, ladders and scaffolding are climbable, ice is slick.
  Unloaded chunks are walls. Block changes take effect at once.
- **Mobs**: the 31 living entities nearest the bridged players (48 blocks around each) are Quake
  targets. Quake damage hurts them through Minecraft (`craftq3:quake` damage, credited to you:
  loot, experience and advancements work), with Quake's knockback. A mob's full health is 125
  Quake points; Minecraft armor still reduces the damage, so a kill message can come before a
  well-armored mob actually dies. Other players outside the bridge are targets when PvP is on.
- **Getting hurt**: mob and player attacks, their arrows and fireballs, and explosions become
  Quake damage (one Minecraft point is five Quake points) through Quake's armor rules; their
  knockback pushes you through Quake movement. Falling, drowning, lava and the like are Quake's
  own. Your Minecraft health and hunger do not change while bridged. Killed players respawn (fire)
  where they entered the bridge.
- **Your Minecraft body** stands where your Quake player is, with the Quake hull, so mobs aim at
  it and other players see you move. You are in Adventure mode while bridged; your game mode and
  abilities are restored when you leave, also after a crash or server stop (on your next join).
- **Several players**: everyone bridged in the same dimension plays in one Quake game and can
  fight each other with Quake weapons. Players more than 900 blocks from where the dimension's
  bridge started cannot join it until everyone has left.

### Loadouts

Leaving saves your Quake loadout (health, armor, weapons, the selected weapon, ammo, the holdable
item and the time left on powerups) with your player, per game directory. `/q3 bridge` restores it
with the game's own rules; `/q3 bridge fresh` starts from the game's defaults (and the next save
replaces the old loadout). Leaving while dead clears it. A loadout only applies to the qagame
module it was saved with.

### Moving between a Quake match and the bridge (singleplayer)

- In the bridge, `quake <map>` in the Quake console starts a local match of that map with your
  weapons, ammo, armor, holdable item and powerups (not your health).
- In a local match opened over a singleplayer world (`/q3 map <map>`), `minecraft` in the Quake
  console returns to the bridge at your Minecraft position with the match's loadout.

### Pickups

`/q3 pickup add <classname>` (for example `weapon_rocketlauncher`, `item_armor_combat`,
`ammo_rockets`; Tab completes the base game's items) places an original Quake item where you
stand. `/q3 pickup list` shows the dimension's placements, `/q3 pickup remove <id>` removes one.
Placements are saved with the world, per dimension and game directory (up to 256), and appear the
next time a bridge starts in that dimension, within 256 blocks of where it starts. The game
handles picking up and respawning. Placing pickups needs game-master permission on a server (the
owner of a singleplayer world can always).

## Building

`/q3 build <map>` (for example `/q3 build q3dm17`) places that map's BSP in the `craftq3:building`
dimension and takes you to its first spawn point. The map is solid, immutable scenery you build in
with Minecraft blocks:

- You, mobs, items and falling blocks collide with the map exactly as Quake would.
- The crosshair picks map surfaces: right-click a map floor or wall with a block to place it in
  the cell in front of that surface.
- Blocks need support as usual: map floors and walls support torches, rails, carpets and the like
  where a map surface lies on the block grid. Blocks cannot be placed inside the map's walls.
- Sand and gravel rest on map floors; water and lava stop at map surfaces.
- Map walls block mobs' paths and sight, shelter from explosions (Minecraft's minimum of one
  point still applies) and stop arrows and other projectiles.
- The map renders with its original textures and lighting inside Minecraft's view; your blocks
  render normally among it.

Each map gets its own region of the dimension; building the same map again returns to the same
region and blocks. `/q3 leave` takes you back to where you came from.

Regions are saved with the world by reference (game, map path and the BSP's SHA-256), never the
map itself. If the PK3 is missing or the map changed, the world still loads: the region is
disabled (its blocks stay, the map's rules and rendering are off) and players entering the
dimension are told why. Restoring the PK3 enables it again.

## Known limits

- The bridge has no bots (no navigation data for Minecraft terrain).
- Kills by Minecraft damage show the world's obituary, not the mob's name.
- Arrow knockback, attributed burning and potion effects do not reach the Quake player.
- In building regions buckets and item frames cannot be used directly against a map surface;
  they need a block to aim at.
- Destination snapping of mob navigation to map floors that are off the block grid is not
  implemented; mobs still path over map floors.
- A carried loadout's health is not restored when entering a local match from the bridge.
