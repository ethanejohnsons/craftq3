# Mixin plan

This file covers how `mc:fabric` hooks into Minecraft 26.2 (Fabric API 0.156.0+26.2). Read
[REWRITE.md](REWRITE.md) §1 and Phase 7 first.

## Rules

- **Prefer Fabric API.** Use a Fabric API event or callback where one fires at the same moment as
  the old hook. Otherwise target a stable, named method.
- **Injector choice.**
  - Prefer `@WrapOperation`, `@ModifyExpressionValue`, `@ModifyReturnValue` or `@WrapMethod`
    (MixinExtras, bundled with Fabric Loader) over `@Redirect` or cancelling `@Inject`.
  - A plain `@Inject` at a named `INVOKE` is fine.
- **Forbidden targets.**
  - Never target synthetic `lambda$…` methods.
  - Never select locals by ordinal or index. Use `@Local(name = "…")` or `name = "…"`: 26.2 ships
    unobfuscated with local variable tables.
- **Configs by side.** There are two configs, both registered in `fabric.mod.json`:
  - `craftq3.mixins.json` (package `dev.bluevista.craftq3.mc.fabric.mixin`, in the `main` source
    set) loads on both sides. It must never reference client classes.
  - `craftq3.client.mixins.json` (package `dev.bluevista.craftq3.mc.fabric.client.mixin`, in the
    `client` source set) is registered with `"environment": "client"`.

  `:mc:devharness:runGameTest` checks that a dedicated server boots with the mod.
- **Mixins on main.** On `main`, every mixin was in the `client` list. Most of them target logical
  server code, so a dedicated server could not load the mod.

## Implemented

| Mixin (config) | Target | Purpose |
|---|---|---|
| `GameRendererMixin` (client) | `GameRenderer.extract`/`render`: `@ModifyVariable(argsOnly, name = "advanceGameTime")` at HEAD | While a `Q3FullscreenView` screen is open, clears the flag that gates all level work: camera and level extraction, the HUD, the lightmap, `renderLevel`, the entity outline and post chains. Window and options extraction, the GUI and frame cleanup still run. |
| `GameRendererMixin` (client) | `GameRenderer.render`: `@Inject` at `INVOKE GuiRenderer.render()V` | Runs the view's full-screen Blaze3D pass after the depth clear and before the GUI. Fabric has no event between level and GUI rendering: `LevelRenderEvents` only fire while the level renders. |
| `MouseHandlerMixin` (client) | `MouseHandler.handleAccumulatedMovement`: plain `@Inject` at HEAD (not cancelling), `@Shadow` of `minecraft`, `accumulatedDX`, `accumulatedDY` | While a `Q3InputView` screen is open, hands the frame's accumulated cursor motion to its `Q3InputCapture` and zeroes it, so neither the screen's `mouseMoved`/`mouseDragged` nor `turnPlayer` sees it; the method then runs normally and keeps its frame-time bookkeeping. Fabric API has no motion event (`ScreenMouseEvents` covers buttons and scrolling only). The deltas are read in two methods (`handleAccumulatedMovement` and `turnPlayer`), so one HEAD hook replaces per-read `@ModifyExpressionValue`s. The cursor is grabbed by `MinecraftInputHost` with `InputConstants.grabOrReleaseMouse`, never `MouseHandler.grabMouse`, so Minecraft's own grab flag (which gates `turnPlayer`) stays clear. Covered by `Q3InputClientTest`, which fails without this mixin. |
| `SoundEngineMixin` (client) | `SoundEngine.play(SoundInstance)`: `@WrapMethod` | Returns `NOT_STARTED` while a Quake view holds a `MinecraftAudio.HostAudioClaim`. `play` is the single funnel: `SoundManager.play`, `playDelayed` and `queueTickingSound` all start their sounds through it on a later tick, so one wrapper replaces the old three cancels and nothing is queued and lost. Fabric API has no sound-playback event (`fabric-sound-api-v1` only supplies custom streams). On claim, sounds already playing are stopped per `SoundSource` (`SoundManager.stop(null, source)`), never with `stopAll`, which would restart the executor. |
| `SoundEngineMixin` (client) | `SoundEngine.stopAll`: `@Inject` at `INVOKE SoundEngineExecutor.startUp()V` | The only quiescent point: the old executor thread is joined and the new one not yet started, so `MinecraftAudioDevice` applies its pending commands and releases its pooled sources (and, inside `destroy`, its buffers) directly. |
| `SoundEngineMixin` (client) | `destroy` HEAD, `loadLibrary` RETURN, `emergencyShutdown` HEAD (plain `@Inject`s) | Bracket the OpenAL context: while it is going down the device issues no OpenAL calls and reports new voices as ended; after a reload it re-uploads buffers lazily from the PCM it keeps. |
| `SoundManagerAccessor`, `SoundEngineAccessor`, `ChannelAccessor` (client) | `SoundManager.soundEngine`; `SoundEngine.loaded`/`executor`/`library`; `Channel.source` | The device takes sources from the engine's `Library` channel pools and runs every OpenAL call on the engine's `SoundEngineExecutor`; it never opens a device or context. `Channel.source` is needed for the start offset (`AL_SAMPLE_OFFSET`) and stream buffer queueing, which `Channel` does not expose. The old `channelAccess` and `listener` accessors are gone: device channels are not registered with `ChannelAccess` (whose tick would release a stream that ran dry), and Q3 voices are played source-relative in listener space, so Minecraft's listener (and `updateSource`) is never touched. |
| `LevelOverlayGameRendererMixin` (client) | `GameRenderer.update` HEAD; `renderLevel` at `INVOKE CommandEncoder.clearDepthTexture` with `@Local(name = "projectionMatrix")` and `@Local(name = "cameraState")`; `@WrapWithCondition` on the `renderItemInHand`, `bobHurt` and `bobView` calls in `renderLevel` | `Q3LevelOverlay`s (the bridge's cgame frame, building regions' BSPs): their frame runs before `Camera.update`, so Minecraft culls and renders with the cgame's view; they draw after the level, before the hand pass clears the depth buffer, with this frame's projection (bobbing included) and the level's depth, so blocks and Quake geometry occlude each other. The hand and view bobbing are skipped while the bridge is open (replaces `BridgeHandMixin` and the old bob cancels). Fabric's `LevelRenderEvents` fire inside the level's frame graph, where the main target is not ours to draw into. |
| `BridgeCameraMixin` (client) | `Camera.alignWithEntity` RETURN (plain `@Inject`, `@Shadow` fields and `setPosition`); `calculateFov`: `@ModifyReturnValue` | While the bridge is open the camera takes the cgame's world view (position, rotation with roll, forward/up/left, yaw/pitch, first person) and its vertical field of view. Set before `Camera.update` derives its matrices, so the old `getViewRotationMatrix` and `extractRenderState` overrides are gone. |
| `BridgeLocalPlayerMixin` (client) | `LocalPlayer.aiStep` HEAD (cancelling) | The local player does not move by itself while bridged (the bridge places it at the Quake player) or while building regions it is about to stand on are loading. |
| `EntityAvatarMixin` (common) | `Entity`: duck field; `@ModifyReturnValue` on `getBoundingBox`, `getBbWidth`, `getBbHeight`, `getEyeHeight()F`, `getEyeY` | Replaces `BridgeAvatarMixin`: a bridged player's dimensions are its Quake hull (`AvatarShape`), a plain field the owning side sets (server every tick, client every frame). No client class, no lookup. |
| `LevelMixin`, `ServerPlayerMixin` (common) | `Level`, `ServerPlayer`: duck fields | `CraftQ3Level` (the level's bridge game and building regions, client levels included) and `BridgedPlayer` (the player's bridge session), replacing the global synchronized maps. |
| `BridgePlayerHurtMixin` (common) | `Player.actuallyHurt` HEAD (cancelling) | Accepted Minecraft damage to a bridged player (mob and player attacks, their projectiles, explosions) goes to the Quake player (`BridgeHost.minecraftDamage`); Minecraft health is untouched. `ALLOW_DAMAGE` fires before the invulnerability frames, so it is not the same moment. A dead Quake player is protected by `ServerLivingEntityEvents.ALLOW_DAMAGE`. |
| `BridgeKnockbackMixin` (common) | `LivingEntity.knockback(DDDLDamageSource;FZ)V`: `@WrapMethod` | Minecraft computes the knockback from the Quake player's own motion and grounded state; the velocity change becomes a Quake impulse and the body's motion is restored. |
| `BridgeExplosionMixin` (common) | `ServerExplosion.hurtEntities`: `@WrapOperation` on `Entity.push(Vec3)` and `Map.put` | An explosion's push on a bridged player becomes a Quake impulse and is not sent to its client. |
| `BridgeMovePacketMixin` (common) | `ServerGamePacketListenerImpl.handleMovePlayer`: `@Inject` after `INVOKE PacketUtils.ensureRunningOnSameThread` (cancelling) | New: the server places a bridged player's body from the Quake player state, so the client's movement packets are ignored (on the server thread, where the session lives). |
| `BridgeFoodMixin` (common) | `FoodData.tick(ServerPlayer)` HEAD (cancelling) | No hunger or regeneration while Quake owns the health. |
| `BuildingEntityMixin` (common) | `Entity.collide` and `collideBoundingBox(Entity, …)`: `@WrapOperation` on `collectCollidersIgnoringWorldBorder` | The colliders of one movement gain a `BspMovementShape` (exact Quake axis sweeps, the BSP floor as a step-up candidate). |
| `BuildingSupportMixin` (common) | `BlockStateBase.isFaceSturdy(BlockGetter, BlockPos, Direction, SupportType)`: `@ModifyReturnValue` | Block faces lying on BSP faces are sturdy (`FaceSupportIndex`). |
| `BuildingBlockItemMixin` (common) | `BlockItem.canPlace`: `@ModifyReturnValue` | No block may bury its shape in BSP solid. |
| `BuildingFallingBlockMixin`, `BuildingFallingBlockEntityMixin` (common) | `FallingBlock.tick` and `FallingBlockEntity.tick`: `@WrapOperation` on `FallingBlock.isFree` (`@Local(name = "pos")` in the entity) | New: sand and gravel rest on BSP floors and land on them as blocks. |
| `BuildingExplosionMixin` (common) | `ServerExplosion.getSeenPercent`: `@ModifyExpressionValue` on `BlockHitResult.getType` with `@Local(name = "from")`; `calculateExplodedPositions`: `@WrapOperation` on `isInWorldBounds` with `@Local(name = "xp"/"yp"/"zp")` and `@Share` | BSP shelters from blast exposure and stops propagation rays (no ordinals or indices). |
| `BuildingSightMixin` (common) | `LivingEntity.hasLineOfSight(Entity, Block, Fluid, D)`: `@ModifyExpressionValue` on `getType` with `@Local(name = "from"/"to")` | Mobs cannot see through BSP. |
| `BuildingFluidMixin` (common) | `FlowingFluid.canPassThroughWall`: `@ModifyReturnValue` | The single test behind spreading down and sideways: no flow into BSP solid or across a BSP barrier. |
| `BuildingProjectileMixin` (common) | `ProjectileUtil.getHitResult`/`getHitEntitiesAlong`: `@WrapOperation` on `Level.clipIncludingBorder` | Projectiles hit BSP surfaces. |
| `BuildingArrowMixin` (common) | `AbstractArrow.tick`: `@WrapOperation` on `clipIncludingBorder`; `shouldFall`: `@ModifyReturnValue` | Arrows stick in BSP surfaces and stay. |
| `BuildingWalkNodeMixin` (common) | `WalkNodeEvaluator.getPathTypeOfMob` and `hasCollisions(AABB)`: `@ModifyReturnValue` | Nodes overlapping BSP solid are `BLOCKED`, open nodes over a BSP floor are walkable, and straight-line shortcuts test the BSP (replaces the lambda target). |
| `BuildingPickMixin` (client) | `LocalPlayer.pick(Entity, D, D, F)`: `@WrapOperation` on `Entity.pick(DFZ)` | The crosshair picks BSP surfaces nearer than any block; the hit's block position is the free cell in front of the surface, so a block item places into it (the server's own rules check the placement). |
| `BuildingHangingMixin` (common) | `HangingEntity.survives`: `@WrapOperation` on `Stream.allMatch(Predicate)`; `hasLevelCollision`: `@ModifyReturnValue` | Paintings and frames hang on BSP walls and pop on BSP solid (replaces the lambda target). |

The pure-Q3 entry points need no mixin: the title screen button uses Fabric's
`ScreenEvents.AFTER_INIT`, `/q3 menu|map|connect|demo` are client commands, and `Q3Screen` reuses
the `Q3FullscreenView`/`Q3InputView` hooks above.

## Old mixins and their replacements

Flags: **L** marks a synthetic lambda target and **O** marks an ordinal or index local. Both must
be removed. No old mixin used `@Redirect`. Every target method was checked against 26.2 and still
exists.

### Phase 7: pure-Q3 mode (client)

| Old mixin | What it did | Replacement |
|---|---|---|
| GameRendererMixin | Turned the world off with `@ModifyVariable` on `boolean` ordinal 0 of `extract`/`render`. Its other injections: `prepareFrame` at `extract` HEAD (bridge: at `update` HEAD); Q3 draw at `INVOKE GuiRenderer.render()V`; `endFrame` at `render` RETURN; cancelled `bobHurt`/`bobView` under the bridge; `close` HEAD. | **Done** (see the Implemented table): the arg is now selected by name and the draw goes through `Q3FullscreenView`. Still to port: the `prepareFrame`/`endFrame` hooks, as plain `@Inject`s on `extract` HEAD and `render` RETURN. The bob cancels move to Phase 8 with the bridge. |
| GuiMixin | Cancelled `Gui.extractRenderState(DeltaTracker, boolean, boolean)` at HEAD and extracted only the Q3 screen. This skipped the HUD, background blur, toasts, debug overlay and subtitles. | **Drop.** The level flag already hides the HUD. Q3 screens override `extractBackground` as a no-op (see `Q3SpikeScreen`). If toasts or the F3 overlay must be hidden, use `@WrapWithCondition` on `ToastManager.extractRenderState` / `Hud.extractDebugOverlay`. |
| MouseHandlerMixin | Cancelled `MouseHandler.handleAccumulatedMovement` at HEAD and passed raw deltas (shadowed `accumulatedDX/DY`) to the Q3 view. | **Done** (see Implemented): a non-cancelling HEAD `@Inject` that consumes and zeroes the shadowed deltas for the open `Q3InputView`. Shadows kept, because the fields are also read in `turnPlayer`. |
| SoundManagerAccessor, SoundEngineAccessor | `@Accessor`s for `soundEngine`, `loaded`, `channelAccess`, `executor` and `listener`. | **Done** (see Implemented): `soundEngine`, `loaded`, `executor` and `library`, plus a `Channel.source` accessor. |
| SoundEngineMixin | Muted Minecraft audio while Q3 audio ran. It hooked `play` HEAD (returned NOT_STARTED); cancelled `playDelayed`/`queueTickingSound`; hooked `stopAll`, `destroy`, `loadLibrary` and `emergencyShutdown` for backend suspend/resume; and cancelled `updateSource` plus **L** `lambda$updateSource$0`. | **Done** (see Implemented): one `@WrapMethod` on `play`; the lifecycle `@Inject`s; no `updateSource` hook and no lambda target, because the device no longer owns Minecraft's listener. |
| BridgeHandMixin | Cancelled `ItemInHandRenderer.submitHandsWithItems` at HEAD under a Quake view. | In pure-Q3 mode the level is skipped, so hands never render. Keep it for the bridge in Phase 8 (client). |

### Phase 8: hybrid bridge and building

Done (see Implemented) unless noted. Not ported, with the reason:

| Old mixin | Status |
|---|---|
| BridgePlayerMixin | `actuallyHurt` kept as `BridgePlayerHurtMixin`; the hold is `BridgeRules.hold` from the bridge tick. |
| BridgeAvatarMixin | `EntityAvatarMixin`. |
| BridgeKnockbackMixin, BridgeExplosionMixin, BridgeFoodMixin | Kept. |
| BridgeServerPlayerMixin | Marker: persistent data attachment `craftq3:bridge_return`; damage filter: `ALLOW_DAMAGE`. |
| BridgeLocalPlayerMixin, BridgeCameraMixin (client) | Kept. |
| BridgeHandMixin (client) | `@WrapWithCondition` on the hand pass in `LevelOverlayGameRendererMixin`. |
| BridgeArrowMixin, BridgeEntityFireMixin, BridgeSmallFireballMixin, BridgeHurtingProjectileMixin | Not ported: arrow and fireball hits already reach the Quake player through `actuallyHurt`; attributed burning, arrow pushes and fireball targeting of the `noPhysics` avatar are gaps. |
| BuildingEntityMixin, BuildingSupportMixin, BuildingBlockItemMixin, BuildingExplosionMixin, BuildingSightMixin, BuildingFluidMixin, BuildingProjectileMixin, BuildingArrowMixin, BuildingWalkNodeMixin, BuildingHangingMixin | Kept (names, not ordinals; no lambda targets). |
| BuildingRegionAccessor | Not needed: path hooks read the mob's own level. |
| BuildingGroundNavigationMixin, BuildingPathNavigationMixin | Not ported: with BSP floors walkable to the evaluator, ground navigation plans over them (GameTest); destination snapping to off-grid BSP floors is a gap. |
| BuildingBucketMixin, BuildingHangingItemMixin, BuildingItemFrameMixin | Not ported: placing fluids and hanging items against a BSP surface the player aims at needs a client pick hook (gap); placed blocks work through `BlockItem`. |
| BuildingLevelRendererMixin (client) | Not needed: the dimension type has no Minecraft sky (`"skybox": "none"`), and the BSP draws through `LevelOverlayGameRendererMixin`. |
| BuildingProjectionMixin (client) | Not needed: the BSP is drawn with Minecraft's own projection and depth. |
| BuildingPickMixin (client) | Kept, as a `@WrapOperation` on the block pick (`Entity.pick`) inside `LocalPlayer.pick` instead of a RETURN hook on `Minecraft.pick`. |
| BuildingEntityLightMixin, BuildingLightCoordsMixin (client) | Not needed: the dimension has sky light and fixed time, so blocks and entities in a region are lit. |
| New | `BridgeMovePacketMixin`, `BuildingFallingBlockMixin`, `BuildingFallingBlockEntityMixin`. |

### Final split

- **Client** (`craftq3.client.mixins.json`): GameRenderer, LevelOverlayGameRenderer, MouseHandler,
  SoundEngine and its 3 accessors (SoundManager, SoundEngine, Channel), BridgeCamera,
  BridgeLocalPlayer and BuildingPick. GuiMixin is dropped.
- **Common** (`craftq3.mixins.json`): EntityAvatar, Level, ServerPlayer, BridgePlayerHurt,
  BridgeKnockback, BridgeExplosion, BridgeMovePacket, BridgeFood and the Building mixins. None
  references a client class; `:mc:devharness:runGameTest` boots them on a dedicated server.
