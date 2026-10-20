package dev.bluevista.craftq3.engine.client.host;

import static dev.bluevista.craftq3.engine.vmabi.UiImport.*;

import dev.bluevista.craftq3.engine.client.input.KeyTarget;
import dev.bluevista.craftq3.engine.client.net.UiBrowser;
import dev.bluevista.craftq3.engine.client.render.ClientAssets;
import dev.bluevista.craftq3.engine.console.CommandArgs;
import dev.bluevista.craftq3.engine.vm.QvmInstance;
import dev.bluevista.craftq3.engine.vm.QvmMemory;
import dev.bluevista.craftq3.engine.vmabi.AbiProfile;
import dev.bluevista.craftq3.engine.vmabi.Bindings;
import dev.bluevista.craftq3.engine.vmabi.RetailImport;
import dev.bluevista.craftq3.engine.vmabi.VmExports;
import dev.bluevista.craftq3.engine.vmabi.VmModule;
import dev.bluevista.craftq3.engine.vmabi.layout.UiClientStateLayout;
import dev.bluevista.craftq3.engine.vmabi.service.GuestText;
import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

/**
 * The ui VM host: binds every {@code uiImport_t} host import (renderer, sound, keys, client state,
 * configstrings, the server browser through {@link UiBrowser}, CD key, fonts, scripts, cinematics)
 * and wraps the {@code uiExport_t} entry points.
 *
 * <p>The CD key: {@code trap_GetCDKey} reports the key from {@code baseq3/q3key}, {@code
 * trap_SetCDKey} keeps a new one for this run, {@code trap_VerifyCDKey} is the vm-abi STUB (always
 * valid).
 */
public final class UiHost extends GuestVm implements KeyTarget {
  /** {@code uiMenuCommand_t}. */
  public enum Menu {
    NONE,
    MAIN,
    INGAME,
    NEED_CD,
    BAD_CD_KEY,
    TEAM,
    POSTGAME
  }

  /** What the ui reads about the connection. */
  public interface Link {
    UiClientStateLayout.UiClientState clientState();

    /** The configstring of the current gamestate, or empty. */
    default String configString(int index) {
      return "";
    }

    default UiBrowser browser() {
      return UiBrowser.EMPTY;
    }

    /** A link for a client with no connection. */
    Link DISCONNECTED = UiClientStateLayout.UiClientState::disconnected;
  }

  private final Link link;
  private int apiVersion;
  private int lastTime;

  public UiHost(HostServices services, Link link) {
    super(services, VmModule.UI);
    this.link = Objects.requireNonNull(link);
  }

  public int apiVersion() {
    return apiVersion;
  }

  /**
   * Loads {@code vm/ui.qvm}, checks {@code UI_GETAPIVERSION} and runs {@code UI_INIT}.
   *
   * @throws IOException when the module is missing
   * @throws IllegalStateException for an unsupported api version
   */
  public void start() throws IOException {
    instantiate();
    apiVersion = call(VmExports.Ui.UI_GETAPIVERSION.command());
    if (Arrays.stream(VmExports.UI_API_VERSIONS).noneMatch(v -> v == apiVersion)) {
      shutdown();
      throw new IllegalStateException("Unsupported ui api version " + apiVersion);
    }
    int connected = link.clientState().connState() >= UiClientStateLayout.CA_AUTHORIZING ? 1 : 0;
    call(VmExports.Ui.UI_INIT.command(), connected);
  }

  /** Restarts the ui (video restart, or after a fault), reopening {@code menu}. */
  public void restart(Menu menu) throws IOException {
    shutdown();
    start();
    setActiveMenu(menu);
  }

  @Override
  protected int shutdownCommand() {
    return VmExports.Ui.UI_SHUTDOWN.command();
  }

  // ---------------------------------------------------------------- exports

  /** {@code UI_SET_ACTIVE_MENU}. */
  public void setActiveMenu(Menu menu) {
    call(VmExports.Ui.UI_SET_ACTIVE_MENU.command(), menu.ordinal());
  }

  /** {@code UI_REFRESH(realtime)}: draws the menus into the current frame. */
  public void refresh(int realTime) {
    lastTime = realTime;
    call(VmExports.Ui.UI_REFRESH.command(), realTime);
  }

  /** {@code UI_IS_FULLSCREEN}. */
  public boolean isFullscreen() {
    return call(VmExports.Ui.UI_IS_FULLSCREEN.command()) != 0;
  }

  /** {@code UI_CONSOLE_COMMAND(realtime)}: true when the ui handled the current command. */
  public boolean consoleCommand(CommandArgs args, int realTime) {
    var scope = shared().commands().withArgs(args);
    try {
      return call(VmExports.Ui.UI_CONSOLE_COMMAND.command(), realTime) != 0;
    } finally {
      scope.close();
    }
  }

  /** {@code UI_DRAW_CONNECT_SCREEN(overlay)}. */
  public void drawConnectScreen(boolean overlay) {
    call(VmExports.Ui.UI_DRAW_CONNECT_SCREEN.command(), overlay ? 1 : 0);
  }

  /** {@code UI_HASUNIQUECDKEY}. */
  public boolean hasUniqueCdKey() {
    return call(VmExports.Ui.UI_HASUNIQUECDKEY.command()) != 0;
  }

  @Override
  public void keyEvent(int key, boolean down, int time) {
    if (state() != State.RUNNING) return;
    lastTime = Math.max(lastTime, time);
    // The retail module reads only the key; it never expected releases.
    if (profile() == AbiProfile.RETAIL_1999 && !down) return;
    call(VmExports.Ui.UI_KEY_EVENT.command(), key, down ? 1 : 0);
  }

  @Override
  public void mouseEvent(int dx, int dy, int time) {
    if (state() != State.RUNNING) return;
    call(VmExports.Ui.UI_MOUSE_EVENT.command(), dx, dy);
  }

  // ---------------------------------------------------------------- imports

  @Override
  protected void bind(Bindings b) {
    b.bind(UI_R_REGISTERMODEL, this::registerModel);
    b.bind(UI_R_REGISTERSKIN, this::registerSkin);
    b.bind(UI_R_REGISTERSHADERNOMIP, this::registerShader);
    b.bind(UI_R_CLEARSCENE, this::clearScene);
    b.bind(UI_R_ADDREFENTITYTOSCENE, this::addRefEntity);
    b.bind(UI_R_ADDPOLYTOSCENE, this::addPoly);
    b.bind(UI_R_ADDLIGHTTOSCENE, this::addLight);
    b.bind(UI_R_RENDERSCENE, this::renderScene);
    b.bind(UI_R_SETCOLOR, this::setColor);
    b.bind(UI_R_DRAWSTRETCHPIC, this::drawStretchPic);
    b.bind(UI_UPDATESCREEN, this::updateScreen);
    b.bind(UI_CM_LERPTAG, this::lerpTag);
    b.bind(UI_CM_LOADMODEL, this::registerModel);
    b.bind(UI_S_REGISTERSOUND, this::registerSound);
    b.bind(UI_S_STARTLOCALSOUND, this::startLocalSound);
    b.bind(UI_KEY_KEYNUMTOSTRINGBUF, this::keyNumToString);
    b.bind(UI_KEY_GETBINDINGBUF, this::getBinding);
    b.bind(UI_KEY_SETBINDING, this::setBinding);
    b.bind(UI_KEY_ISDOWN, this::keyIsDown);
    b.bind(UI_KEY_GETOVERSTRIKEMODE, (vm, a) -> services.input().overstrike() ? 1 : 0);
    b.bind(
        UI_KEY_SETOVERSTRIKEMODE,
        (vm, a) -> {
          services.input().setOverstrike(a[0] != 0);
          return 0;
        });
    b.bind(
        UI_KEY_CLEARSTATES,
        (vm, a) -> {
          services.input().clearStates();
          return 0;
        });
    b.bind(UI_KEY_GETCATCHER, this::keyGetCatcher);
    b.bind(UI_KEY_SETCATCHER, this::keySetCatcher);
    b.bind(
        UI_GETCLIPBOARDDATA,
        (vm, a) -> {
          GuestText.write(vm.memory(), a[0], services.platform().clipboardText(), a[1]);
          return 0;
        });
    b.bind(UI_GETGLCONFIG, this::glconfig);
    b.bind(
        UI_GETCLIENTSTATE,
        (vm, a) -> {
          UiClientStateLayout.write(vm.memory(), a[0], link.clientState());
          return 0;
        });
    b.bind(UI_GETCONFIGSTRING, this::getConfigString);
    b.bind(UI_LAN_GETPINGQUEUECOUNT, (vm, a) -> browser().pingCount());
    b.bind(
        UI_LAN_CLEARPING,
        (vm, a) -> {
          browser().clearPing(a[0]);
          return 0;
        });
    b.bind(UI_LAN_GETPING, this::lanGetPing);
    b.bind(UI_LAN_GETPINGINFO, this::lanGetPingInfo);
    b.bind(UI_GET_CDKEY, this::getCdKey);
    b.bind(
        UI_SET_CDKEY,
        (vm, a) -> {
          services.setCdKey(GuestText.read(vm.memory(), a[0]));
          return 0;
        });
    b.bind(UI_R_REGISTERFONT, this::registerFont);
    b.bind(UI_R_MODELBOUNDS, this::modelBounds);
    b.bind(UI_PC_ADD_GLOBAL_DEFINE, this::pcAddGlobalDefine);
    b.bind(UI_PC_LOAD_SOURCE, this::pcLoadSource);
    b.bind(UI_PC_FREE_SOURCE, this::pcFreeSource);
    b.bind(UI_PC_READ_TOKEN, this::pcReadToken);
    b.bind(UI_PC_SOURCE_FILE_AND_LINE, this::pcSourceFileAndLine);
    b.bind(UI_S_STOPBACKGROUNDTRACK, this::stopBackgroundTrack);
    b.bind(UI_S_STARTBACKGROUNDTRACK, this::startBackgroundTrack);
    b.bind(UI_LAN_GETSERVERCOUNT, (vm, a) -> browser().count(a[0]));
    b.bind(UI_LAN_GETSERVERADDRESSSTRING, (vm, a) -> serverText(vm, a[0], a[1], a[2], a[3], false));
    b.bind(UI_LAN_GETSERVERINFO, (vm, a) -> serverText(vm, a[0], a[1], a[2], a[3], true));
    b.bind(
        UI_LAN_MARKSERVERVISIBLE,
        (vm, a) -> {
          browser().markVisible(a[0], a[1], a[2]);
          return 0;
        });
    b.bind(UI_LAN_UPDATEVISIBLEPINGS, (vm, a) -> browser().updatePings(a[0]) ? 1 : 0);
    b.bind(
        UI_LAN_RESETPINGS,
        (vm, a) -> {
          browser().resetPings(a[0]);
          return 0;
        });
    b.bind(
        UI_LAN_LOADCACHEDSERVERS,
        (vm, a) -> {
          browser().loadCache();
          return 0;
        });
    b.bind(
        UI_LAN_SAVECACHEDSERVERS,
        (vm, a) -> {
          browser().saveCache();
          return 0;
        });
    b.bind(UI_LAN_ADDSERVER, (vm, a) -> browser().add(a[0], text(vm, a[1]), text(vm, a[2])));
    b.bind(
        UI_LAN_REMOVESERVER,
        (vm, a) -> {
          browser().remove(a[0], text(vm, a[1]));
          return 0;
        });
    b.bind(UI_CIN_PLAYCINEMATIC, this::cinPlay);
    b.bind(UI_CIN_STOPCINEMATIC, this::cinStop);
    b.bind(UI_CIN_RUNCINEMATIC, this::cinRun);
    b.bind(UI_CIN_DRAWCINEMATIC, this::cinDraw);
    b.bind(UI_CIN_SETEXTENTS, this::cinSetExtents);
    b.bind(UI_R_REMAP_SHADER, this::remapShader);
    b.bind(UI_LAN_SERVERSTATUS, this::lanServerStatus);
    b.bind(UI_LAN_GETSERVERPING, (vm, a) -> browser().serverPing(a[0], a[1]));
    b.bind(UI_LAN_SERVERISVISIBLE, (vm, a) -> browser().visible(a[0], a[1]));
    b.bind(UI_LAN_COMPARESERVERS, (vm, a) -> browser().compare(a[0], a[1], a[2], a[3], a[4]));
    // Retail server browser.
    b.bind(RetailImport.RETAIL_UI_LAN_GETLOCALSERVERCOUNT, (vm, a) -> browser().count(0));
    b.bind(
        RetailImport.RETAIL_UI_LAN_GETLOCALSERVERADDRESSSTRING,
        (vm, a) -> serverText(vm, 0, a[0], a[1], a[2], false));
    b.bind(RetailImport.RETAIL_UI_LAN_GETGLOBALSERVERCOUNT, (vm, a) -> browser().count(2));
    b.bind(
        RetailImport.RETAIL_UI_LAN_GETGLOBALSERVERADDRESSSTRING,
        (vm, a) -> serverText(vm, 2, a[0], a[1], a[2], false));
  }

  private UiBrowser browser() {
    return link.browser();
  }

  private int getBinding(QvmInstance vm, int[] a) {
    String binding = a[0] >= 0 && a[0] < 256 ? services.input().bindings().binding(a[0]) : "";
    GuestText.write(vm.memory(), a[1], binding, a[2]);
    return 0;
  }

  private int setBinding(QvmInstance vm, int[] a) {
    if (a[0] < 0 || a[0] >= 256) return 0;
    services.input().bindings().bind(a[0], text(vm, a[1]));
    return 0;
  }

  /** {@code trap_GetConfigString(index, buf, size)}: 0 for an unset or invalid index. */
  private int getConfigString(QvmInstance vm, int[] a) {
    String value = a[0] >= 0 && a[0] < 1024 ? link.configString(a[0]) : "";
    GuestText.write(vm.memory(), a[1], value, a[2]);
    return value.isEmpty() ? 0 : 1;
  }

  private int getCdKey(QvmInstance vm, int[] a) {
    GuestText.write(vm.memory(), a[0], services.cdKey(), a[1]);
    return 0;
  }

  /** Native browser getters pad a valid slot's whole buffer and write one NUL otherwise. */
  private static void browserText(
      QvmMemory memory, int address, int capacity, boolean present, String value) {
    if (address == 0 || capacity <= 0) return;
    if (present) {
      memory.fill(address, capacity, 0);
      GuestText.write(memory, address, value, capacity);
    } else memory.writeByte(address, 0);
  }

  private int serverText(
      QvmInstance vm, int source, int index, int buffer, int size, boolean info) {
    UiBrowser browser = browser();
    boolean present = index >= 0 && index < browser.capacity(source);
    String value =
        !present ? "" : info ? browser.info(source, index) : browser.address(source, index);
    browserText(vm.memory(), buffer, size, present, value);
    return 0;
  }

  private int lanGetPing(QvmInstance vm, int[] a) {
    UiBrowser browser = browser();
    boolean occupied = browser.pingOccupied(a[0]);
    UiBrowser.Ping ping = browser.ping(a[0]);
    browserText(vm.memory(), a[1], a[2], occupied, ping.address());
    if (a[3] != 0) vm.memory().writeInt(a[3], ping.milliseconds());
    return 0;
  }

  private int lanGetPingInfo(QvmInstance vm, int[] a) {
    UiBrowser browser = browser();
    boolean occupied = browser.pingOccupied(a[0]);
    browserText(vm.memory(), a[1], a[2], occupied, browser.pingInfo(a[0]));
    return 0;
  }

  /** {@code trap_LAN_ServerStatus(address, buf, size)}: 1 once the status arrived. */
  private int lanServerStatus(QvmInstance vm, int[] a) {
    String address = a[0] == 0 ? null : text(vm, a[0]);
    if (address == null || a[1] == 0) {
      browser().resetStatus(address);
      return 0;
    }
    if (a[2] < 1) return 0;
    Optional<String> status = browser().status(address, a[2]);
    status.ifPresent(value -> browserText(vm.memory(), a[1], a[2], true, value));
    return status.isPresent() ? 1 : 0;
  }

  /** The 2D shader registration the ui uses for the console and connect screens. */
  public int registerShaderNoMip(String name) {
    return assets.shader(name, ClientAssets.Usage.TWO_D);
  }

  public int lastTime() {
    return lastTime;
  }
}
