/**
 * The VM ABI layer shared by the qagame, cgame and ui hosts.
 *
 * <ul>
 *   <li>{@link dev.bluevista.craftq3.engine.vmabi.AbiProfile} (1.32 and retail) and {@link
 *       dev.bluevista.craftq3.engine.vmabi.AbiDetector} (hash plus import-usage detection).
 *   <li>Exhaustive import enums: {@link dev.bluevista.craftq3.engine.vmabi.QagameImport}, {@link
 *       dev.bluevista.craftq3.engine.vmabi.BotlibImport}, {@link
 *       dev.bluevista.craftq3.engine.vmabi.CgameImport}, {@link
 *       dev.bluevista.craftq3.engine.vmabi.UiImport}, {@link
 *       dev.bluevista.craftq3.engine.vmabi.RetailImport}, each entry with an argument count and a
 *       {@link dev.bluevista.craftq3.engine.vmabi.Policy}; {@link
 *       dev.bluevista.craftq3.engine.vmabi.ImportTable} numbers them per profile.
 *   <li>{@link dev.bluevista.craftq3.engine.vmabi.VmHost}: the syscall dispatcher with the error
 *       model; handlers are collected in {@link dev.bluevista.craftq3.engine.vmabi.Bindings}.
 *   <li>{@link dev.bluevista.craftq3.engine.vmabi.ConfigStrings} and the struct layouts in {@code
 *       layout}; shared services in {@code service}.
 * </ul>
 */
package dev.bluevista.craftq3.engine.vmabi;
