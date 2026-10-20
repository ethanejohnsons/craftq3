/**
 * The Quake III server running the original qagame.
 *
 * <ul>
 *   <li>{@link dev.bluevista.craftq3.engine.game.GameServer}: lifecycle ({@code spawn} with the
 *       original settling frames, {@code map_restart}, shutdown, error drops), the fixed-step frame
 *       loop driven by {@code frame(nowMillis)}, configstrings, client slots ({@link
 *       dev.bluevista.craftq3.engine.game.ServerClient}) with their reliable command windows, bots
 *       and the operator commands.
 *   <li>{@link dev.bluevista.craftq3.engine.game.QagameHost}: every {@code QagameImport} bound over
 *       the vm-abi dispatcher; {@link dev.bluevista.craftq3.engine.game.GameProgram} loads the
 *       module ({@link dev.bluevista.craftq3.engine.game.QvmGameProgram}).
 *   <li>{@code map}: the {@code MapSource} SPI (BSP maps from the game files by default).
 *   <li>{@code world}: linked entities in the area-node tree, traces composing the collision model,
 *       inline models and entity boxes or capsules, PVS and area portals; extra world geometry
 *       joins through {@code engine:collision}'s {@code CollisionSource}.
 *   <li>{@code snapshot}: per-client entity visibility.
 *   <li>{@code net}: {@code Protocol68Host}, the network endpoint (local play uses its loopback).
 *   <li>{@code bot}: the botlib marshalling.
 * </ul>
 *
 * Everything belongs to the simulation thread that owns the server's console.
 */
package dev.bluevista.craftq3.engine.game;
