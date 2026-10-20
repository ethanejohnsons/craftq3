package dev.bluevista.craftq3.engine.vmabi;

import static dev.bluevista.craftq3.engine.vmabi.Policy.HOST;
import static dev.bluevista.craftq3.engine.vmabi.Policy.SHARED;

import dev.bluevista.craftq3.engine.vmabi.layout.OrientationLayout;

/**
 * The ui engine imports of the 1.32 interface ({@code uiImport_t}). The retail numbering differs
 * from 46 onwards (see {@link RetailImport} and {@link ImportTable}).
 */
public enum UiImport implements VmImport {
  UI_ERROR(0, 1, Policy.sharedCritical("trap_Error ends the ui; the client restarts it")),
  UI_PRINT(1, 1, SHARED),
  UI_MILLISECONDS(2, 0, SHARED),
  UI_CVAR_SET(3, 2, SHARED),
  UI_CVAR_VARIABLEVALUE(4, 1, SHARED),
  UI_CVAR_VARIABLESTRINGBUFFER(5, 3, Policy.shared(0).clearingString(1)),
  UI_CVAR_SETVALUE(6, 2, SHARED),
  UI_CVAR_RESET(7, 1, SHARED),
  UI_CVAR_CREATE(8, 3, SHARED),
  UI_CVAR_INFOSTRINGBUFFER(9, 3, Policy.shared(0).clearingString(1)),
  UI_ARGC(10, 0, SHARED),
  UI_ARGV(11, 3, Policy.shared(0).clearingString(1)),
  UI_CMD_EXECUTETEXT(12, 2, SHARED),
  UI_FS_FOPENFILE(13, 3, Policy.shared(-1)),
  UI_FS_READ(14, 3, SHARED),
  UI_FS_WRITE(15, 3, SHARED),
  UI_FS_FCLOSEFILE(16, 1, SHARED),
  UI_FS_GETFILELIST(17, 4, SHARED),
  UI_R_REGISTERMODEL(18, 1, HOST),
  UI_R_REGISTERSKIN(19, 1, HOST),
  UI_R_REGISTERSHADERNOMIP(20, 1, HOST),
  UI_R_CLEARSCENE(21, 0, HOST),
  UI_R_ADDREFENTITYTOSCENE(22, 1, HOST),
  UI_R_ADDPOLYTOSCENE(23, 3, HOST),
  UI_R_ADDLIGHTTOSCENE(24, 5, HOST),
  UI_R_RENDERSCENE(25, 1, HOST),
  UI_R_SETCOLOR(26, 1, HOST),
  UI_R_DRAWSTRETCHPIC(27, 9, HOST),
  UI_UPDATESCREEN(28, 0, HOST),
  UI_CM_LERPTAG(29, 6, Policy.host(0).clearing(0, OrientationLayout.BYTES)),
  UI_CM_LOADMODEL(30, 1, HOST),
  UI_S_REGISTERSOUND(31, 2, HOST),
  UI_S_STARTLOCALSOUND(32, 2, HOST),
  UI_KEY_KEYNUMTOSTRINGBUF(33, 3, Policy.host(0).clearingString(1)),
  UI_KEY_GETBINDINGBUF(34, 3, Policy.host(0).clearingString(1)),
  UI_KEY_SETBINDING(35, 2, HOST),
  UI_KEY_ISDOWN(36, 1, HOST),
  UI_KEY_GETOVERSTRIKEMODE(37, 0, HOST),
  UI_KEY_SETOVERSTRIKEMODE(38, 1, HOST),
  UI_KEY_CLEARSTATES(39, 0, HOST),
  UI_KEY_GETCATCHER(40, 0, HOST),
  UI_KEY_SETCATCHER(41, 1, HOST),
  UI_GETCLIPBOARDDATA(42, 2, Policy.host(0).clearingString(0)),
  UI_GETGLCONFIG(43, 1, Policy.hostCritical("menu scaling divides by the video size")),
  UI_GETCLIENTSTATE(44, 1, HOST),
  UI_GETCONFIGSTRING(45, 3, Policy.host(0).clearingString(1)),
  UI_LAN_GETPINGQUEUECOUNT(46, 0, HOST),
  UI_LAN_CLEARPING(47, 1, HOST),
  UI_LAN_GETPING(48, 4, Policy.host(0).clearingString(1)),
  UI_LAN_GETPINGINFO(49, 3, Policy.host(0).clearingString(1)),
  UI_CVAR_REGISTER(50, 4, SHARED),
  UI_CVAR_UPDATE(51, 1, SHARED),
  UI_MEMORY_REMAINING(
      52,
      0,
      Policy.stub(
          ImportConstants.MEMORY_REMAINING,
          "the JVM has no hunk; a constant large value keeps low-memory paths off")),
  UI_GET_CDKEY(53, 2, Policy.host(0).clearingString(0)),
  UI_SET_CDKEY(54, 1, HOST),
  UI_R_REGISTERFONT(55, 3, HOST),
  UI_R_MODELBOUNDS(56, 3, HOST),
  UI_PC_ADD_GLOBAL_DEFINE(57, 1, HOST),
  UI_PC_LOAD_SOURCE(58, 1, HOST),
  UI_PC_FREE_SOURCE(59, 1, HOST),
  UI_PC_READ_TOKEN(60, 2, HOST),
  UI_PC_SOURCE_FILE_AND_LINE(61, 3, Policy.host(0).clearingString(1)),
  UI_S_STOPBACKGROUNDTRACK(62, 0, HOST),
  UI_S_STARTBACKGROUNDTRACK(63, 2, HOST),
  UI_REAL_TIME(64, 1, SHARED),
  UI_LAN_GETSERVERCOUNT(65, 1, HOST),
  UI_LAN_GETSERVERADDRESSSTRING(66, 4, Policy.host(0).clearingString(2)),
  UI_LAN_GETSERVERINFO(67, 4, Policy.host(0).clearingString(2)),
  UI_LAN_MARKSERVERVISIBLE(68, 3, HOST),
  UI_LAN_UPDATEVISIBLEPINGS(69, 1, HOST),
  UI_LAN_RESETPINGS(70, 1, HOST),
  UI_LAN_LOADCACHEDSERVERS(71, 0, HOST),
  UI_LAN_SAVECACHEDSERVERS(72, 0, HOST),
  UI_LAN_ADDSERVER(73, 3, Policy.host(-1)),
  UI_LAN_REMOVESERVER(74, 2, HOST),
  UI_CIN_PLAYCINEMATIC(75, 6, Policy.host(-1)),
  UI_CIN_STOPCINEMATIC(76, 1, HOST),
  UI_CIN_RUNCINEMATIC(77, 1, HOST),
  UI_CIN_DRAWCINEMATIC(78, 1, HOST),
  UI_CIN_SETEXTENTS(79, 5, HOST),
  UI_R_REMAP_SHADER(80, 3, HOST),
  UI_VERIFY_CDKEY(
      81,
      2,
      Policy.stub(
          ImportConstants.CD_KEY_VALID,
          "CD keys are never validated, like ioquake3 builds without key checks")),
  UI_LAN_SERVERSTATUS(82, 3, Policy.host(0).clearingString(1)),
  UI_LAN_GETSERVERPING(83, 2, HOST),
  UI_LAN_SERVERISVISIBLE(84, 2, HOST),
  UI_LAN_COMPARESERVERS(85, 5, HOST),
  UI_FS_SEEK(86, 3, Policy.shared(-1)),
  UI_SET_PBCLSTATUS(
      87, 1, Policy.stub(0, "PunkBuster is not part of CraftQ3; the requested state is ignored")),

  UI_MEMSET(100, 3, SHARED),
  UI_MEMCPY(101, 3, SHARED),
  UI_STRNCPY(102, 3, SHARED),
  UI_SIN(103, 1, SHARED),
  UI_COS(104, 1, SHARED),
  UI_ATAN2(105, 2, SHARED),
  UI_SQRT(106, 1, SHARED),
  UI_FLOOR(107, 1, SHARED),
  UI_CEIL(108, 1, SHARED);

  private final int number;
  private final int argCount;
  private final Policy policy;

  UiImport(int number, int argCount, Policy policy) {
    this.number = number;
    this.argCount = argCount;
    this.policy = policy;
  }

  @Override
  public VmModule module() {
    return VmModule.UI;
  }

  @Override
  public int number() {
    return number;
  }

  @Override
  public int argCount() {
    return argCount;
  }

  @Override
  public Policy policy() {
    return policy;
  }
}
