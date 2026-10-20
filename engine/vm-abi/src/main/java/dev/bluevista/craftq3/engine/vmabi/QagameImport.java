package dev.bluevista.craftq3.engine.vmabi;

import static dev.bluevista.craftq3.engine.vmabi.Policy.HOST;
import static dev.bluevista.craftq3.engine.vmabi.Policy.SHARED;

import dev.bluevista.craftq3.engine.vmabi.layout.TraceLayout;
import dev.bluevista.craftq3.engine.vmabi.layout.UserCmdLayout;

/**
 * The qagame engine imports of the 1.32 interface ({@code gameImport_t} numbers 0 to 45 and the
 * shared intrinsics 100 to 113). Botlib traps (200 and up) are {@link BotlibImport}.
 */
public enum QagameImport implements VmImport {
  G_PRINT(0, 1, SHARED),
  G_ERROR(1, 1, Policy.sharedCritical("trap_Error ends the game; the host drops the map")),
  G_MILLISECONDS(2, 0, SHARED),
  G_CVAR_REGISTER(3, 4, SHARED),
  G_CVAR_UPDATE(4, 1, SHARED),
  G_CVAR_SET(5, 2, SHARED),
  G_CVAR_VARIABLE_INTEGER_VALUE(6, 1, SHARED),
  G_CVAR_VARIABLE_STRING_BUFFER(7, 3, Policy.shared(0).clearingString(1)),
  G_ARGC(8, 0, SHARED),
  G_ARGV(9, 3, Policy.shared(0).clearingString(1)),
  G_FS_FOPEN_FILE(10, 3, Policy.shared(-1)),
  G_FS_READ(11, 3, SHARED),
  G_FS_WRITE(12, 3, SHARED),
  G_FS_FCLOSE_FILE(13, 1, SHARED),
  G_SEND_CONSOLE_COMMAND(14, 2, SHARED),
  G_LOCATE_GAME_DATA(
      15, 5, Policy.hostCritical("without the entity and client arrays the server cannot run")),
  G_DROP_CLIENT(16, 2, HOST),
  G_SEND_SERVER_COMMAND(17, 2, HOST),
  G_SET_CONFIGSTRING(18, 2, HOST),
  G_GET_CONFIGSTRING(19, 3, Policy.host(0).clearingString(1)),
  G_GET_USERINFO(20, 3, Policy.host(0).clearingString(1)),
  G_SET_USERINFO(21, 2, HOST),
  G_GET_SERVERINFO(22, 2, Policy.shared(0).clearingString(0)),
  G_SET_BRUSH_MODEL(23, 2, HOST),
  G_TRACE(24, 7, Policy.host(0).clearing(0, TraceLayout.BYTES)),
  G_POINT_CONTENTS(25, 2, HOST),
  G_IN_PVS(26, 2, HOST),
  G_IN_PVS_IGNORE_PORTALS(27, 2, HOST),
  G_ADJUST_AREA_PORTAL_STATE(28, 2, HOST),
  G_AREAS_CONNECTED(29, 2, HOST),
  G_LINKENTITY(30, 1, HOST),
  G_UNLINKENTITY(31, 1, HOST),
  G_ENTITIES_IN_BOX(32, 4, HOST),
  G_ENTITY_CONTACT(33, 3, HOST),
  G_BOT_ALLOCATE_CLIENT(34, 0, Policy.host(-1)),
  G_BOT_FREE_CLIENT(35, 1, HOST),
  G_GET_USERCMD(36, 2, Policy.host(0).clearing(1, UserCmdLayout.BYTES)),
  G_GET_ENTITY_TOKEN(37, 2, Policy.host(0).clearingString(0)),
  G_FS_GETFILELIST(38, 4, SHARED),
  G_DEBUG_POLYGON_CREATE(
      39, 3, Policy.stub(0, "debug polygons are an editor visualisation; no renderer is attached")),
  G_DEBUG_POLYGON_DELETE(40, 1, Policy.stub(0, "no debug polygons are ever created")),
  G_REAL_TIME(41, 1, SHARED),
  G_SNAPVECTOR(42, 1, SHARED),
  G_TRACECAPSULE(43, 7, Policy.host(0).clearing(0, TraceLayout.BYTES)),
  G_ENTITY_CONTACTCAPSULE(44, 3, HOST),
  G_FS_SEEK(45, 3, Policy.shared(-1)),

  TRAP_MEMSET(100, 3, SHARED),
  TRAP_MEMCPY(101, 3, SHARED),
  TRAP_STRNCPY(102, 3, SHARED),
  TRAP_SIN(103, 1, SHARED),
  TRAP_COS(104, 1, SHARED),
  TRAP_ATAN2(105, 2, SHARED),
  TRAP_SQRT(106, 1, SHARED),
  TRAP_MATRIXMULTIPLY(107, 3, SHARED),
  TRAP_ANGLEVECTORS(108, 4, SHARED),
  TRAP_PERPENDICULARVECTOR(109, 2, SHARED),
  TRAP_FLOOR(110, 1, SHARED),
  TRAP_CEIL(111, 1, SHARED),
  TRAP_TESTPRINTINT(112, 2, SHARED),
  TRAP_TESTPRINTFLOAT(113, 2, SHARED);

  private final int number;
  private final int argCount;
  private final Policy policy;

  QagameImport(int number, int argCount, Policy policy) {
    this.number = number;
    this.argCount = argCount;
    this.policy = policy;
  }

  @Override
  public VmModule module() {
    return VmModule.QAGAME;
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
