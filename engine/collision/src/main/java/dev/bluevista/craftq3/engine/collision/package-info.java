/**
 * Quake III collision (the "CM" layer): {@link
 * dev.bluevista.craftq3.engine.collision.CollisionModel} is built once per map and answers box,
 * capsule and transformed traces, point contents, leaf, cluster and area queries and PVS tests;
 * {@link dev.bluevista.craftq3.engine.collision.Trace} mirrors {@code trace_t}; {@link
 * dev.bluevista.craftq3.engine.collision.TraceWork} holds the per-thread scratch that keeps queries
 * allocation-free; {@link dev.bluevista.craftq3.engine.collision.TraceMerge} combines traces
 * against several solids; {@link dev.bluevista.craftq3.engine.collision.AreaPortalState} tracks
 * open doors; {@link dev.bluevista.craftq3.engine.collision.CollisionSource} composes foreign
 * geometry into world traces. Patches collide through facets generated from their adaptively
 * refined control grids.
 */
package dev.bluevista.craftq3.engine.collision;
