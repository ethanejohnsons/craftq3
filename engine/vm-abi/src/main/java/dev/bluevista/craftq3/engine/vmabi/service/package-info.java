/**
 * Host services shared by the qagame, cgame and ui hosts: print and error routing ({@link
 * dev.bluevista.craftq3.engine.vmabi.service.PrintServices}), time ({@link
 * dev.bluevista.craftq3.engine.vmabi.service.TimeServices}), the libc/math intrinsics ({@link
 * dev.bluevista.craftq3.engine.vmabi.service.Intrinsics}), cvars ({@link
 * dev.bluevista.craftq3.engine.vmabi.service.CvarServices}), commands ({@link
 * dev.bluevista.craftq3.engine.vmabi.service.CommandServices}) and files ({@link
 * dev.bluevista.craftq3.engine.vmabi.service.FileServices} over {@link
 * dev.bluevista.craftq3.engine.vmabi.service.VmFiles}, adapted to {@code engine.fs} by {@link
 * dev.bluevista.craftq3.engine.vmabi.service.FsVmFiles}). {@link
 * dev.bluevista.craftq3.engine.vmabi.service.SharedServices#install} binds all of them to a
 * module's SHARED imports.
 *
 * <p>Services belong to the thread that runs their VM, like the cvar table they use.
 */
package dev.bluevista.craftq3.engine.vmabi.service;
