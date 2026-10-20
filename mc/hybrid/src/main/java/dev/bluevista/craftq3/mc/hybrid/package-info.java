/**
 * Minecraft-free logic for the bridge and building modes, expressed in Quake coordinates behind
 * SPIs that the Fabric adapter implements. No Minecraft types appear here.
 *
 * <ul>
 *   <li>{@link dev.bluevista.craftq3.mc.hybrid.CoordinateTransform}: the one Quake/Minecraft
 *       mapping (positions, directions, velocities, normals, boxes, angles).
 *   <li>{@code terrain}: the {@code TerrainCells} SPI (block-cell shapes, fluids, climbable and
 *       slick cells), {@code GridCollisionSource} (terrain in qagame and cgame traces) and {@code
 *       TerrainSnapshot} (an immutable copy for other threads).
 *   <li>{@code building}: {@code BuildingQueries} (occupancy, movement, occlusion, fluids, floors,
 *       picking and {@code isFaceSturdy}-style support against an immutable BSP), {@code
 *       FaceCoverage}, and saved {@code BuildingRegion}s that load disabled, with a reason, when
 *       their PK3 is missing.
 *   <li>{@code bridge}: {@code SyntheticMapSource} (the bridge level), {@code BridgeHost} (incoming
 *       damage through eight binary {@code trigger_hurt}s, loadout restoration without cheats,
 *       Minecraft mobs as hidden puppet clients producing {@code MobDamage}), {@code Loadout} and
 *       its codec, and {@code PickupPlacement}s.
 *   <li>{@code map}: small BSP writers ({@code SyntheticBsp}, {@code BspAugmenter}).
 * </ul>
 */
package dev.bluevista.craftq3.mc.hybrid;
