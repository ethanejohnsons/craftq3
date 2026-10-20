package dev.bluevista.craftq3.engine.vmabi.service;

import static dev.bluevista.craftq3.engine.vmabi.CgameImport.*;
import static dev.bluevista.craftq3.engine.vmabi.QagameImport.*;
import static dev.bluevista.craftq3.engine.vmabi.UiImport.*;

import dev.bluevista.craftq3.engine.console.CommandArgs;
import dev.bluevista.craftq3.engine.console.CommandBuffer;
import dev.bluevista.craftq3.engine.console.ConsoleOutput;
import dev.bluevista.craftq3.engine.vmabi.Bindings;
import dev.bluevista.craftq3.engine.vmabi.VmExports;
import dev.bluevista.craftq3.engine.vmabi.VmModule;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * The services every VM host shares, and the installers that bind them to a module's {@link
 * dev.bluevista.craftq3.engine.vmabi.Policy.Kind#SHARED} imports. After {@link #install(Bindings)}
 * every SHARED import of the module is bound; hosts add their own handlers (and may {@link
 * Bindings#override} a shared one).
 *
 * @param print {@code trap_Print}/{@code trap_Error}
 * @param time milliseconds and real time
 * @param cvars cvar imports over the VM's cvar table
 * @param commands argument, command and command-buffer imports
 * @param files {@code trap_FS_*}
 */
public record SharedServices(
    VmModule module,
    PrintServices print,
    TimeServices time,
    CvarServices cvars,
    CommandServices commands,
    FileServices files) {
  public SharedServices {
    Objects.requireNonNull(module);
    Objects.requireNonNull(print);
    Objects.requireNonNull(time);
    Objects.requireNonNull(cvars);
    Objects.requireNonNull(commands);
    Objects.requireNonNull(files);
    if (print.module() != module)
      throw new IllegalArgumentException("Print services belong to " + print.module());
  }

  /**
   * Builds the services for one VM.
   *
   * @param output console text from the VM
   * @param buffer the owning console's command buffer (its registry and cvar table are used)
   * @param files the VM's file handles
   * @param unhandled receives commands the VM added but then declined (cgame: forward to the
   *     server); ignored for the other modules
   */
  public static SharedServices create(
      VmModule module,
      ConsoleOutput output,
      TimeServices time,
      CommandBuffer buffer,
      CvarServices.WritePolicy cvarPolicy,
      VmFiles files,
      Consumer<CommandArgs> unhandled) {
    int entry =
        switch (module) {
          case QAGAME -> VmExports.Qagame.GAME_CONSOLE_COMMAND.command();
          case CGAME -> VmExports.Cgame.CG_CONSOLE_COMMAND.command();
          case UI -> VmExports.Ui.UI_CONSOLE_COMMAND.command();
        };
    return new SharedServices(
        module,
        new PrintServices(module, output),
        time,
        new CvarServices(module, buffer.registry().cvars(), cvarPolicy),
        new CommandServices(module, buffer, entry, unhandled),
        new FileServices(module, files));
  }

  /** Binds every SHARED import of {@link #module()}. */
  public Bindings install(Bindings bindings) {
    if (bindings.module() != module)
      throw new IllegalArgumentException(
          "Services for " + module + " cannot bind " + bindings.module());
    switch (module) {
      case QAGAME -> installQagame(bindings);
      case CGAME -> installCgame(bindings);
      case UI -> installUi(bindings);
    }
    return installIntrinsics(bindings);
  }

  private void installQagame(Bindings b) {
    b.bind(G_PRINT, print::print);
    b.bind(G_ERROR, print::error);
    b.bind(G_MILLISECONDS, time::milliseconds);
    b.bind(G_CVAR_REGISTER, cvars::register);
    b.bind(G_CVAR_UPDATE, cvars::update);
    b.bind(G_CVAR_SET, cvars::set);
    b.bind(G_CVAR_VARIABLE_INTEGER_VALUE, cvars::variableIntegerValue);
    b.bind(G_CVAR_VARIABLE_STRING_BUFFER, cvars::variableStringBuffer);
    b.bind(G_ARGC, commands::argc);
    b.bind(G_ARGV, commands::argv);
    b.bind(G_FS_FOPEN_FILE, files::open);
    b.bind(G_FS_READ, files::read);
    b.bind(G_FS_WRITE, files::write);
    b.bind(G_FS_FCLOSE_FILE, files::close);
    b.bind(G_SEND_CONSOLE_COMMAND, commands::executeText);
    b.bind(G_GET_SERVERINFO, cvars::serverInfo);
    b.bind(G_FS_GETFILELIST, files::list);
    b.bind(G_REAL_TIME, time::realTime);
    b.bind(G_SNAPVECTOR, Intrinsics::snapVector);
    b.bind(G_FS_SEEK, files::seek);
    b.bind(TRAP_TESTPRINTINT, print::testPrintInt);
    b.bind(TRAP_TESTPRINTFLOAT, print::testPrintFloat);
  }

  private void installCgame(Bindings b) {
    b.bind(CG_PRINT, print::print);
    b.bind(CG_ERROR, print::error);
    b.bind(CG_MILLISECONDS, time::milliseconds);
    b.bind(CG_CVAR_REGISTER, cvars::register);
    b.bind(CG_CVAR_UPDATE, cvars::update);
    b.bind(CG_CVAR_SET, cvars::set);
    b.bind(CG_CVAR_VARIABLESTRINGBUFFER, cvars::variableStringBuffer);
    b.bind(CG_ARGC, commands::argc);
    b.bind(CG_ARGV, commands::argv);
    b.bind(CG_ARGS, commands::args);
    b.bind(CG_FS_FOPENFILE, files::open);
    b.bind(CG_FS_READ, files::read);
    b.bind(CG_FS_WRITE, files::write);
    b.bind(CG_FS_FCLOSEFILE, files::close);
    b.bind(CG_SENDCONSOLECOMMAND, commands::addText);
    b.bind(CG_ADDCOMMAND, commands::addCommand);
    b.bind(CG_REMOVECOMMAND, commands::removeCommand);
    b.bind(CG_REAL_TIME, time::realTime);
    b.bind(CG_SNAPVECTOR, Intrinsics::snapVector);
    b.bind(CG_FS_SEEK, files::seek);
    b.bind(CG_TESTPRINTINT, print::testPrintInt);
    b.bind(CG_TESTPRINTFLOAT, print::testPrintFloat);
  }

  private void installUi(Bindings b) {
    b.bind(UI_ERROR, print::error);
    b.bind(UI_PRINT, print::print);
    b.bind(UI_MILLISECONDS, time::milliseconds);
    b.bind(UI_CVAR_SET, cvars::set);
    b.bind(UI_CVAR_VARIABLEVALUE, cvars::variableValue);
    b.bind(UI_CVAR_VARIABLESTRINGBUFFER, cvars::variableStringBuffer);
    b.bind(UI_CVAR_SETVALUE, cvars::setValue);
    b.bind(UI_CVAR_RESET, cvars::reset);
    b.bind(UI_CVAR_CREATE, cvars::create);
    b.bind(UI_CVAR_INFOSTRINGBUFFER, cvars::infoStringBuffer);
    b.bind(UI_ARGC, commands::argc);
    b.bind(UI_ARGV, commands::argv);
    b.bind(UI_CMD_EXECUTETEXT, commands::executeText);
    b.bind(UI_FS_FOPENFILE, files::open);
    b.bind(UI_FS_READ, files::read);
    b.bind(UI_FS_WRITE, files::write);
    b.bind(UI_FS_FCLOSEFILE, files::close);
    b.bind(UI_FS_GETFILELIST, files::list);
    b.bind(UI_REAL_TIME, time::realTime);
    b.bind(UI_CVAR_REGISTER, cvars::register);
    b.bind(UI_CVAR_UPDATE, cvars::update);
    b.bind(UI_FS_SEEK, files::seek);
  }

  /** Binds only the libc/math intrinsics of the bindings' module (no other services needed). */
  public static Bindings installIntrinsics(Bindings b) {
    switch (b.module()) {
      case QAGAME -> {
        b.bind(TRAP_MEMSET, Intrinsics::memset);
        b.bind(TRAP_MEMCPY, Intrinsics::memcpy);
        b.bind(TRAP_STRNCPY, Intrinsics::strncpy);
        b.bind(TRAP_SIN, Intrinsics::sin);
        b.bind(TRAP_COS, Intrinsics::cos);
        b.bind(TRAP_ATAN2, Intrinsics::atan2);
        b.bind(TRAP_SQRT, Intrinsics::sqrt);
        b.bind(TRAP_MATRIXMULTIPLY, Intrinsics::matrixMultiply);
        b.bind(TRAP_ANGLEVECTORS, Intrinsics::angleVectors);
        b.bind(TRAP_PERPENDICULARVECTOR, Intrinsics::perpendicularVector);
        b.bind(TRAP_FLOOR, Intrinsics::floor);
        b.bind(TRAP_CEIL, Intrinsics::ceil);
      }
      case CGAME -> {
        b.bind(CG_MEMSET, Intrinsics::memset);
        b.bind(CG_MEMCPY, Intrinsics::memcpy);
        b.bind(CG_STRNCPY, Intrinsics::strncpy);
        b.bind(CG_SIN, Intrinsics::sin);
        b.bind(CG_COS, Intrinsics::cos);
        b.bind(CG_ATAN2, Intrinsics::atan2);
        b.bind(CG_SQRT, Intrinsics::sqrt);
        b.bind(CG_FLOOR, Intrinsics::floor);
        b.bind(CG_CEIL, Intrinsics::ceil);
        b.bind(CG_ACOS, Intrinsics::acos);
      }
      case UI -> {
        b.bind(UI_MEMSET, Intrinsics::memset);
        b.bind(UI_MEMCPY, Intrinsics::memcpy);
        b.bind(UI_STRNCPY, Intrinsics::strncpy);
        b.bind(UI_SIN, Intrinsics::sin);
        b.bind(UI_COS, Intrinsics::cos);
        b.bind(UI_ATAN2, Intrinsics::atan2);
        b.bind(UI_SQRT, Intrinsics::sqrt);
        b.bind(UI_FLOOR, Intrinsics::floor);
        b.bind(UI_CEIL, Intrinsics::ceil);
      }
    }
    return b;
  }
}
