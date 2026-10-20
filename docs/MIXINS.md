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
| `SoundEngineMixin` (client) | `SoundEngine.play(SoundInstance)`: `@WrapMethod` | Returns `NOT_STARTED` while a Quake view holds a `MinecraftAudio.HostAudioClaim`. `play` is the single funnel: `SoundManager.play`, `playDelayed` and `queueTickingSound` all start their sounds through it on a later tick, so one wrapper replaces the old three cancels and nothing is queued and lost. Fabric API has no sound-playback event (`fabric-sound-api-v1` only supplies custom streams). On claim, sounds already playing are stopped per `SoundSource` (`SoundManager.stop(null, source)`), never with `stopAll`, which would restart the executor. |
| `SoundEngineMixin` (client) | `SoundEngine.stopAll`: `@Inject` at `INVOKE SoundEngineExecutor.startUp()V` | The only quiescent point: the old executor thread is joined and the new one not yet started, so `MinecraftAudioDevice` applies its pending commands and releases its pooled sources (and, inside `destroy`, its buffers) directly. |
| `SoundEngineMixin` (client) | `destroy` HEAD, `loadLibrary` RETURN, `emergencyShutdown` HEAD (plain `@Inject`s) | Bracket the OpenAL context: while it is going down the device issues no OpenAL calls and reports new voices as ended; after a reload it re-uploads buffers lazily from the PCM it keeps. |
| `SoundManagerAccessor`, `SoundEngineAccessor`, `ChannelAccessor` (client) | `SoundManager.soundEngine`; `SoundEngine.loaded`/`executor`/`library`; `Channel.source` | The device takes sources from the engine's `Library` channel pools and runs every OpenAL call on the engine's `SoundEngineExecutor`; it never opens a device or context. `Channel.source` is needed for the start offset (`AL_SAMPLE_OFFSET`) and stream buffer queueing, which `Channel` does not expose. The old `channelAccess` and `listener` accessors are gone: device channels are not registered with `ChannelAccess` (whose tick would release a stream that ran dry), and Q3 voices are played source-relative in listener space, so Minecraft's listener (and `updateSource`) is never touched. |

## Old mixins and their replacements

Flags: **L** marks a synthetic lambda target and **O** marks an ordinal or index local. Both must
be removed. No old mixin used `@Redirect`. Every target method was checked against 26.2 and still
exists.

### Phase 7: pure-Q3 mode (client)

| Old mixin | What it did | Replacement |
|---|---|---|
| GameRendererMixin | Turned the world off with `@ModifyVariable` on `boolean` ordinal 0 of `extract`/`render`. Its other injections: `prepareFrame` at `extract` HEAD (bridge: at `update` HEAD); Q3 draw at `INVOKE GuiRenderer.render()V`; `endFrame` at `render` RETURN; cancelled `bobHurt`/`bobView` under the bridge; `close` HEAD. | **Done** (see the Implemented table): the arg is now selected by name and the draw goes through `Q3FullscreenView`. Still to port: the `prepareFrame`/`endFrame` hooks, as plain `@Inject`s on `extract` HEAD and `render` RETURN. The bob cancels move to Phase 8 with the bridge. |
| GuiMixin | Cancelled `Gui.extractRenderState(DeltaTracker, boolean, boolean)` at HEAD and extracted only the Q3 screen. This skipped the HUD, background blur, toasts, debug overlay and subtitles. | **Drop.** The level flag already hides the HUD. Q3 screens override `extractBackground` as a no-op (see `Q3SpikeScreen`). If toasts or the F3 overlay must be hidden, use `@WrapWithCondition` on `ToastManager.extractRenderState` / `Hud.extractDebugOverlay`. |
| MouseHandlerMixin | Cancelled `MouseHandler.handleAccumulatedMovement` at HEAD and passed raw deltas (shadowed `accumulatedDX/DY`) to the Q3 view. | Keep in client. `ScreenMouseEvents` only covers buttons and scrolling. Swap the shadows for `@ModifyExpressionValue` on the field reads if the method body allows it. |
| SoundManagerAccessor, SoundEngineAccessor | `@Accessor`s for `soundEngine`, `loaded`, `channelAccess`, `executor` and `listener`. | **Done** (see Implemented): `soundEngine`, `loaded`, `executor` and `library`, plus a `Channel.source` accessor. |
| SoundEngineMixin | Muted Minecraft audio while Q3 audio ran. It hooked `play` HEAD (returned NOT_STARTED); cancelled `playDelayed`/`queueTickingSound`; hooked `stopAll`, `destroy`, `loadLibrary` and `emergencyShutdown` for backend suspend/resume; and cancelled `updateSource` plus **L** `lambda$updateSource$0`. | **Done** (see Implemented): one `@WrapMethod` on `play`; the lifecycle `@Inject`s; no `updateSource` hook and no lambda target, because the device no longer owns Minecraft's listener. |
| BridgeHandMixin | Cancelled `ItemInHandRenderer.submitHandsWithItems` at HEAD under a Quake view. | In pure-Q3 mode the level is skipped, so hands never render. Keep it for the bridge in Phase 8 (client). |

### Phase 8: hybrid bridge and building

Common (`craftq3.mixins.json`) unless marked client.

| Old mixin | What it did | Replacement |
|---|---|---|
| BridgePlayerMixin | `Player.actuallyHurt` HEAD (cancel): sends damage to the bridge controller. `tick` TAIL: holds the player. | Keep `actuallyHurt` HEAD. `ServerLivingEntityEvents.ALLOW_DAMAGE` fires before invulnerability frames, so it is not equivalent. Use a server tick event for the hold. |
| BridgeAvatarMixin | Gave `Entity.getBoundingBox`/`getBbWidth`/`getBbHeight`/`getEyeHeight` the Q3 hull at RETURN. It referenced `LocalPlayer`/`Minecraft`. | Common `@ModifyReturnValue` backed by a side-neutral shape provider. The client registers its own supplier, so no client class appears in common code. |
| BridgeKnockbackMixin | `@WrapMethod` on `LivingEntity.knockback`: knockback becomes a controller impulse. | Keep. |
| BridgeArrowMixin | `AbstractArrow.onHitEntity`: `@WrapOperation` on `igniteForSeconds` and `hurtOrSimulate` (with `@Share`). `doKnockback`: `@WrapMethod`. | Keep. |
| BridgeEntityFireMixin | `Entity.setRemainingFireTicks` HEAD. | Keep. |
| BridgeSmallFireballMixin | `SmallFireball.onHitEntity`: `@WrapOperation` on `igniteForSeconds`/`hurtServer`. `onHitBlock` RETURN. | Keep the wraps. Drop the smoke probes (devharness replaces them). |
| BridgeHurtingProjectileMixin | `canHitEntity`: `@WrapOperation` on the `Entity.noPhysics` read. | Keep. |
| BridgeExplosionMixin | `ServerExplosion.hurtEntities`: `@WrapOperation` on `Entity.push(Vec3)` and `Map.put`. | Keep; Fabric has no explosion event. |
| BridgeServerPlayerMixin | Read/add save data RETURN (return marker); `hurtServer` HEAD returned false. | Marker: a persistent **data attachment** (fabric-data-attachment-api-v1) instead of NBT hooks. Damage filter: `ServerLivingEntityEvents.ALLOW_DAMAGE`. |
| BridgeFoodMixin | Cancelled `FoodData.tick(ServerPlayer)` at HEAD (no hunger while bridged). | Keep. |
| BridgeLocalPlayerMixin (client) | Cancelled `LocalPlayer.aiStep` at HEAD while BridgeScreen is open. | Keep (client). |
| BridgeCameraMixin (client) | `Camera`: `@ModifyReturnValue` on `calculateFov`/`getViewRotationMatrix`; RETURN on `extractRenderState`/`alignWithEntity`. | Keep (client). |
| BuildingEntityMixin | `Entity.collide`/`collideBoundingBox(Entity,…)`: `@WrapOperation` on `collectCollidersIgnoringWorldBorder` adds the BSP shapes. | Keep. Also cover the `collideBoundingBox(CollisionContext,…)` overload and `collectAllColliders` if needed. |
| BuildingLevelRendererMixin (client) | `LevelRenderer.render`: `@ModifyVariable` on the clear colour. Cancelled `addSkyPass`/`addCloudsPass`/`addWeatherPass`. Drew the BSP at RETURN. | Keep the cancels and the clear colour, selecting it by name. Draw from `LevelRenderEvents` only if the BSP draw can run inside the frame-graph main pass; otherwise keep RETURN. |
| BuildingProjectionMixin (client) | `GameRenderer.renderLevel`: `@ModifyArg` on `ProjectionMatrixBuffer.getBuffer(Matrix4f)`. | Keep. The target is unique in 26.2: the hand pass uses `getBuffer(Projection)`. |
| BuildingPickMixin (client) | `Minecraft.pick` RETURN: replaces `hitResult` with a BSP pick. | Keep. |
| BuildingBlockItemMixin | `BlockItem.canPlace` RETURN. | Keep; `UseBlockCallback` is too coarse. |
| BuildingSupportMixin | `BlockStateBase.isFaceSturdy` RETURN. | Keep. |
| BuildingProjectileMixin | `ProjectileUtil.getHitResult`: `@WrapOperation` on `Level.clipIncludingBorder`. | Keep. |
| BuildingArrowMixin | `AbstractArrow.tick`: `@WrapOperation` on `clipIncludingBorder`. `shouldFall`: `@WrapOperation` on `noCollision`. | Keep. |
| BuildingExplosionMixin | `ServerExplosion.getSeenPercent` (`@ModifyExpressionValue`) and `calculateExplodedPositions` (`@WrapOperation` on `isInWorldBounds`). **O** `@Local(ordinal = 1) Vec3` and **O** `@Local(index = 15/17/19) double`. | Keep, using `@Local(name = "from")` and `@Local(name = "xp"/"yp"/"zp")`. |
| BuildingRegionAccessor | `@Accessor` for `PathNavigationRegion.level`. | Keep. |
| BuildingWalkNodeMixin | `WalkNodeEvaluator`: path-type `@WrapMethod`/`@ModifyReturnValue` hooks. **L** `lambda$hasCollisions$0`: `@WrapOperation` on `noCollision`. | Keep the named hooks. Replace the lambda with `@ModifyReturnValue` on `hasCollisions(AABB)`, OR-ing the BSP check. |
| BuildingGroundNavigationMixin | `GroundPathNavigation.findSurfacePosition` HEAD (early return). | Keep, or use `@ModifyReturnValue` if possible. |
| BuildingPathNavigationMixin | `PathNavigation.getGroundY`/`isStableDestination`: `@ModifyReturnValue`. | Keep. |
| BuildingSightMixin | `LivingEntity.hasLineOfSight`: `@ModifyExpressionValue` on `getType` with **O** `@Local(ordinal = 0/1) Vec3`. | Keep, using `@Local(name = "from")`/`@Local(name = "to")`. |
| BuildingFluidMixin | `FlowingFluid.getNewLiquid`: `@WrapOperation` on `isSolid`. `canPassThroughWall`/`canHoldFluid` RETURN. | Keep. |
| BuildingBucketMixin | `BucketItem.use`: `@WrapOperation` on `getPlayerPOVHitResult`. `emptyContents` HEAD. | Keep. |
| BuildingHangingItemMixin | `HangingEntityItem.useOn`: `@WrapOperation` on `getClickedPos`. | Keep. |
| BuildingHangingMixin | **L** `lambda$survives$0` RETURN; `hasLevelCollision` RETURN. | Replace the lambda with `@WrapOperation` on `Stream.allMatch(Predicate)` in `survives()`, passing the predicate OR'd with BSP support. |
| BuildingItemFrameMixin | `ItemFrame.survives`: `@WrapOperation` on `isSolid`. | Keep. |
| BuildingEntityLightMixin (client) | `EntityRenderer.getPackedLightCoords` RETURN. | Keep (client). |
| BuildingLightCoordsMixin (client) | `LightCoordsUtil.getLightCoords` RETURN. | Keep (client). |

### Final split

- **Client:** GameRenderer, MouseHandler, SoundEngine and its 3 accessors (SoundManager,
  SoundEngine, Channel), BridgeLocalPlayer, BridgeHand, BridgeCamera, BuildingLevelRenderer, BuildingProjection, BuildingPick, BuildingEntityLight and
  BuildingLightCoords. GuiMixin is dropped.
- **Common:** everything else. BridgeAvatarMixin must stop referencing client classes first.
