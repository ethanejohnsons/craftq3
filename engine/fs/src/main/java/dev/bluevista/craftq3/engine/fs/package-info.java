/**
 * The Quake III file system.
 *
 * <ul>
 *   <li>{@link dev.bluevista.craftq3.engine.fs.VirtualPath}: normalised, case-insensitive paths.
 *   <li>{@link dev.bluevista.craftq3.engine.fs.SearchPath}: the immutable, shareable mount of a
 *       game's PK3s and loose directories (with {@code baseq3} fallback), ordered by {@link
 *       dev.bluevista.craftq3.engine.fs.SearchOrder}; {@link
 *       dev.bluevista.craftq3.engine.fs.Pk3Archive} is one indexed archive.
 *   <li>{@link dev.bluevista.craftq3.engine.fs.PureManifest}: pure-server checksums, ordering and
 *       reference flags, publishing {@link dev.bluevista.craftq3.engine.fs.FileView}s.
 *   <li>{@link dev.bluevista.craftq3.engine.fs.DownloadPlanner} and {@link
 *       dev.bluevista.craftq3.engine.fs.DownloadCache}: server pack downloads.
 *   <li>{@link dev.bluevista.craftq3.engine.fs.HomeStore}: the portable writable store; {@link
 *       dev.bluevista.craftq3.engine.fs.GameFileHandles}: a VM's handle table over both.
 * </ul>
 */
package dev.bluevista.craftq3.engine.fs;
