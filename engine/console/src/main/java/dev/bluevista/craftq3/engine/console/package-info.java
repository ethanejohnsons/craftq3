/**
 * Cvars, commands, the command buffer, key bindings and config execution, with Quake III 1.32
 * semantics.
 *
 * <p>Main types: {@link dev.bluevista.craftq3.engine.console.CvarTable} ({@code Cvar_*}), {@link
 * dev.bluevista.craftq3.engine.console.CommandRegistry} ({@code Cmd_*} and {@code Cvar_Command}),
 * {@link dev.bluevista.craftq3.engine.console.CommandBuffer} ({@code Cbuf_*}), {@link
 * dev.bluevista.craftq3.engine.console.CommandParser} ({@code Cmd_TokenizeString}), {@link
 * dev.bluevista.craftq3.engine.console.KeyBindings}, {@link
 * dev.bluevista.craftq3.engine.console.ConfigWriter} and the {@link
 * dev.bluevista.craftq3.engine.console.Console} facade that wires them with the built-in commands.
 * Files come in through {@link dev.bluevista.craftq3.engine.console.ConfigSource} and go out
 * through {@link dev.bluevista.craftq3.engine.console.ConfigSink}, so this module does not depend
 * on the filesystem.
 *
 * <p>Threading: every table has a single owner thread, recorded in an {@code
 * engine.common.thread.ThreadOwner} and checked at each entry point; nothing here locks. A change
 * that originates on another thread (for example the render thread changing a server-owned cvar) is
 * forwarded as command text through {@link dev.bluevista.craftq3.engine.console.CommandInbox} and
 * executed by the owner at its next frame.
 */
package dev.bluevista.craftq3.engine.console;
