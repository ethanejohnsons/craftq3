/**
 * Parsers for Quake III data formats (BSP, AAS, MD3, shaders, skins, animations, images, WAV, RoQ).
 * Lenient by default, matching what the original engine accepts; parsers take bytes and resolve
 * further files only through {@link dev.bluevista.craftq3.engine.formats.AssetLookup}.
 */
package dev.bluevista.craftq3.engine.formats;
