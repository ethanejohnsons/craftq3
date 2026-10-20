package dev.bluevista.craftq3.engine.vmabi;

import static dev.bluevista.craftq3.engine.vmabi.Policy.HOST;
import static dev.bluevista.craftq3.engine.vmabi.Policy.SHARED;

import dev.bluevista.craftq3.engine.vmabi.layout.OrientationLayout;
import dev.bluevista.craftq3.engine.vmabi.layout.TraceLayout;
import dev.bluevista.craftq3.engine.vmabi.layout.UserCmdLayout;

/**
 * The cgame engine imports of the 1.32 interface ({@code cgameImport_t}). Its intrinsics differ
 * from qagame's: 107 is floor, 108 ceil, 109/110 the test prints and 111 acos.
 *
 * <p>Retail note: {@link #CG_S_CLEARLOOPINGSOUNDS} took no argument before 1.27; the shared
 * numbering is otherwise identical up to {@link #CG_MEMORY_REMAINING}.
 */
public enum CgameImport implements VmImport {
  CG_PRINT(0, 1, SHARED),
  CG_ERROR(1, 1, Policy.sharedCritical("trap_Error ends the cgame; the client disconnects")),
  CG_MILLISECONDS(2, 0, SHARED),
  CG_CVAR_REGISTER(3, 4, SHARED),
  CG_CVAR_UPDATE(4, 1, SHARED),
  CG_CVAR_SET(5, 2, SHARED),
  CG_CVAR_VARIABLESTRINGBUFFER(6, 3, Policy.shared(0).clearingString(1)),
  CG_ARGC(7, 0, SHARED),
  CG_ARGV(8, 3, Policy.shared(0).clearingString(1)),
  CG_ARGS(9, 2, Policy.shared(0).clearingString(0)),
  CG_FS_FOPENFILE(10, 3, Policy.shared(-1)),
  CG_FS_READ(11, 3, SHARED),
  CG_FS_WRITE(12, 3, SHARED),
  CG_FS_FCLOSEFILE(13, 1, SHARED),
  CG_SENDCONSOLECOMMAND(14, 1, SHARED),
  CG_ADDCOMMAND(15, 1, SHARED),
  CG_SENDCLIENTCOMMAND(16, 1, HOST),
  CG_UPDATESCREEN(17, 0, HOST),
  CG_CM_LOADMAP(18, 1, Policy.hostCritical("prediction and marks need the collision map")),
  CG_CM_NUMINLINEMODELS(19, 0, HOST),
  CG_CM_INLINEMODEL(20, 1, HOST),
  CG_CM_LOADMODEL(21, 1, HOST),
  CG_CM_TEMPBOXMODEL(22, 2, HOST),
  CG_CM_POINTCONTENTS(23, 2, HOST),
  CG_CM_TRANSFORMEDPOINTCONTENTS(24, 4, HOST),
  CG_CM_BOXTRACE(25, 7, Policy.host(0).clearing(0, TraceLayout.BYTES)),
  CG_CM_TRANSFORMEDBOXTRACE(26, 9, Policy.host(0).clearing(0, TraceLayout.BYTES)),
  CG_CM_MARKFRAGMENTS(27, 7, HOST),
  CG_S_STARTSOUND(28, 4, HOST),
  CG_S_STARTLOCALSOUND(29, 2, HOST),
  CG_S_CLEARLOOPINGSOUNDS(30, 1, HOST),
  CG_S_ADDLOOPINGSOUND(31, 4, HOST),
  CG_S_UPDATEENTITYPOSITION(32, 2, HOST),
  CG_S_RESPATIALIZE(33, 4, HOST),
  CG_S_REGISTERSOUND(34, 2, HOST),
  CG_S_STARTBACKGROUNDTRACK(35, 2, HOST),
  CG_R_LOADWORLDMAP(36, 1, HOST),
  CG_R_REGISTERMODEL(37, 1, HOST),
  CG_R_REGISTERSKIN(38, 1, HOST),
  CG_R_REGISTERSHADER(39, 1, HOST),
  CG_R_CLEARSCENE(40, 0, HOST),
  CG_R_ADDREFENTITYTOSCENE(41, 1, HOST),
  CG_R_ADDPOLYTOSCENE(42, 3, HOST),
  CG_R_ADDLIGHTTOSCENE(43, 5, HOST),
  CG_R_RENDERSCENE(44, 1, HOST),
  CG_R_SETCOLOR(45, 1, HOST),
  CG_R_DRAWSTRETCHPIC(46, 9, HOST),
  CG_R_MODELBOUNDS(47, 3, HOST),
  CG_R_LERPTAG(48, 6, Policy.host(0).clearing(0, OrientationLayout.BYTES)),
  CG_GETGLCONFIG(49, 1, Policy.hostCritical("screen scaling divides by the video size")),
  CG_GETGAMESTATE(50, 1, Policy.hostCritical("the cgame cannot start without configstrings")),
  CG_GETCURRENTSNAPSHOTNUMBER(51, 2, HOST),
  CG_GETSNAPSHOT(52, 2, HOST),
  CG_GETSERVERCOMMAND(53, 1, HOST),
  CG_GETCURRENTCMDNUMBER(54, 0, HOST),
  CG_GETUSERCMD(55, 2, Policy.host(0).clearing(1, UserCmdLayout.BYTES)),
  CG_SETUSERCMDVALUE(56, 2, HOST),
  CG_R_REGISTERSHADERNOMIP(57, 1, HOST),
  CG_MEMORY_REMAINING(
      58,
      0,
      Policy.stub(
          ImportConstants.MEMORY_REMAINING,
          "the JVM has no hunk; a constant large value keeps low-memory paths off")),
  CG_R_REGISTERFONT(59, 3, HOST),
  CG_KEY_ISDOWN(60, 1, HOST),
  CG_KEY_GETCATCHER(61, 0, HOST),
  CG_KEY_SETCATCHER(62, 1, HOST),
  CG_KEY_GETKEY(63, 1, Policy.host(-1)),
  CG_PC_ADD_GLOBAL_DEFINE(64, 1, HOST),
  CG_PC_LOAD_SOURCE(65, 1, HOST),
  CG_PC_FREE_SOURCE(66, 1, HOST),
  CG_PC_READ_TOKEN(67, 2, HOST),
  CG_PC_SOURCE_FILE_AND_LINE(68, 3, Policy.host(0).clearingString(1)),
  CG_S_STOPBACKGROUNDTRACK(69, 0, HOST),
  CG_REAL_TIME(70, 1, SHARED),
  CG_SNAPVECTOR(71, 1, SHARED),
  CG_REMOVECOMMAND(72, 1, SHARED),
  CG_R_LIGHTFORPOINT(73, 4, HOST),
  CG_CIN_PLAYCINEMATIC(74, 6, Policy.host(-1)),
  CG_CIN_STOPCINEMATIC(75, 1, HOST),
  CG_CIN_RUNCINEMATIC(76, 1, HOST),
  CG_CIN_DRAWCINEMATIC(77, 1, HOST),
  CG_CIN_SETEXTENTS(78, 5, HOST),
  CG_R_REMAP_SHADER(79, 3, HOST),
  CG_S_ADDREALLOOPINGSOUND(80, 4, HOST),
  CG_S_STOPLOOPINGSOUND(81, 1, HOST),
  CG_CM_TEMPCAPSULEMODEL(82, 2, HOST),
  CG_CM_CAPSULETRACE(83, 7, Policy.host(0).clearing(0, TraceLayout.BYTES)),
  CG_CM_TRANSFORMEDCAPSULETRACE(84, 9, Policy.host(0).clearing(0, TraceLayout.BYTES)),
  CG_R_ADDADDITIVELIGHTTOSCENE(85, 5, HOST),
  CG_GET_ENTITY_TOKEN(86, 2, Policy.host(0).clearingString(0)),
  CG_R_ADDPOLYSTOSCENE(87, 4, HOST),
  CG_R_INPVS(88, 2, HOST),
  CG_FS_SEEK(89, 3, Policy.shared(-1)),

  CG_MEMSET(100, 3, SHARED),
  CG_MEMCPY(101, 3, SHARED),
  CG_STRNCPY(102, 3, SHARED),
  CG_SIN(103, 1, SHARED),
  CG_COS(104, 1, SHARED),
  CG_ATAN2(105, 2, SHARED),
  CG_SQRT(106, 1, SHARED),
  CG_FLOOR(107, 1, SHARED),
  CG_CEIL(108, 1, SHARED),
  CG_TESTPRINTINT(109, 2, SHARED),
  CG_TESTPRINTFLOAT(110, 2, SHARED),
  CG_ACOS(111, 1, SHARED);

  private final int number;
  private final int argCount;
  private final Policy policy;

  CgameImport(int number, int argCount, Policy policy) {
    this.number = number;
    this.argCount = argCount;
    this.policy = policy;
  }

  @Override
  public VmModule module() {
    return VmModule.CGAME;
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
