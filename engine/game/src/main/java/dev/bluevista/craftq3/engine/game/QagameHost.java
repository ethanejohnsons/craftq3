package dev.bluevista.craftq3.engine.game;

import static dev.bluevista.craftq3.engine.vmabi.QagameImport.*;

import dev.bluevista.craftq3.engine.game.world.EntityWorld;
import dev.bluevista.craftq3.engine.game.world.GameEntities;
import dev.bluevista.craftq3.engine.game.world.GameTrace;
import dev.bluevista.craftq3.engine.vm.QvmInstance;
import dev.bluevista.craftq3.engine.vm.QvmMemory;
import dev.bluevista.craftq3.engine.vmabi.AbiProfile;
import dev.bluevista.craftq3.engine.vmabi.Bindings;
import dev.bluevista.craftq3.engine.vmabi.ConfigStrings;
import dev.bluevista.craftq3.engine.vmabi.ImportTable;
import dev.bluevista.craftq3.engine.vmabi.QagameImport;
import dev.bluevista.craftq3.engine.vmabi.VmHost;
import dev.bluevista.craftq3.engine.vmabi.VmModule;
import dev.bluevista.craftq3.engine.vmabi.layout.UserCmdLayout;
import dev.bluevista.craftq3.engine.vmabi.layout.VmStructs;
import dev.bluevista.craftq3.engine.vmabi.service.GuestText;
import dev.bluevista.craftq3.engine.vmabi.service.SharedServices;
import java.util.Objects;

/**
 * The qagame engine imports ({@code SV_GameSystemCalls}) bound over a {@link GameServer}: every
 * {@link QagameImport} HOST entry, the shared services and intrinsics, and (through the server's
 * {@code BotSupport}) the botlib imports. Struct access goes through the profile's layouts; the
 * server's world does the work.
 */
final class QagameHost {
  /** {@code BotImport_DebugPolygonCreate} returns 0 when no debug polygon can be shown. */
  static final int NO_DEBUG_POLYGON = 0;

  private final GameServer server;
  private final AbiProfile profile;
  private final UserCmdLayout userCmdLayout;
  private final GameTrace trace = new GameTrace();
  private final float[] start = new float[3];
  private final float[] end = new float[3];
  private final float[] mins = new float[3];
  private final float[] maxs = new float[3];
  private final int[] list = new int[GameEntities.MAX_GENTITIES];

  QagameHost(GameServer server, AbiProfile profile) {
    this.server = Objects.requireNonNull(server);
    this.profile = Objects.requireNonNull(profile);
    this.userCmdLayout = UserCmdLayout.of(profile);
  }

  /** Builds the dispatcher: shared services, host imports and botlib. */
  VmHost build(SharedServices services, VmHost.Options options) {
    Bindings b = new Bindings(VmModule.QAGAME);
    services.install(b);
    bindEngine(b);
    server.botSupport().bind(b, profile, server);
    return new VmHost(ImportTable.of(VmModule.QAGAME, profile), b, options);
  }

  private EntityWorld world() {
    return server.world();
  }

  private static String text(QvmInstance vm, int address) {
    return GuestText.read(vm.memory(), address);
  }

  private static float[] vec(QvmMemory memory, int address, float[] out) {
    if (address == 0) {
      out[0] = out[1] = out[2] = 0;
      return out;
    }
    VmStructs.readVec3(memory, address, out);
    return out;
  }

  private int entity(int pointer) {
    return world().entities().number(pointer);
  }

  private void bindEngine(Bindings b) {
    b.bind(
        G_LOCATE_GAME_DATA,
        (vm, a) -> {
          server.locateGameData(a[0], a[1], a[2], a[3], a[4]);
          return 0;
        });
    b.bind(
        G_DROP_CLIENT,
        (vm, a) -> {
          server.gameDropClient(a[0], text(vm, a[1]));
          return 0;
        });
    b.bind(
        G_SEND_SERVER_COMMAND,
        (vm, a) -> {
          server.gameSendServerCommand(a[0], text(vm, a[1]));
          return 0;
        });
    b.bind(
        G_SET_CONFIGSTRING,
        (vm, a) -> {
          server.setConfigstring(a[0], text(vm, a[1]));
          return 0;
        });
    b.bind(
        G_GET_CONFIGSTRING,
        (vm, a) -> {
          if (a[0] < 0 || a[0] >= ConfigStrings.MAX_STRINGS)
            throw new IllegalArgumentException("GetConfigstring: bad index " + a[0]);
          GuestText.write(vm.memory(), a[1], server.configstring(a[0]), a[2]);
          return 0;
        });
    b.bind(
        G_GET_USERINFO,
        (vm, a) -> {
          GuestText.write(vm.memory(), a[1], server.checkedClient(a[0]).userinfo(), a[2]);
          return 0;
        });
    b.bind(
        G_SET_USERINFO,
        (vm, a) -> {
          server.setUserinfo(a[0], text(vm, a[1]));
          return 0;
        });
    b.bind(
        G_SET_BRUSH_MODEL,
        (vm, a) -> {
          world().setBrushModel(entity(a[0]), text(vm, a[1]));
          return 0;
        });
    b.bind(G_TRACE, (vm, a) -> trace(vm, a, false));
    b.bind(G_TRACECAPSULE, (vm, a) -> trace(vm, a, true));
    b.bind(G_POINT_CONTENTS, (vm, a) -> world().pointContents(vec(vm.memory(), a[0], start), a[1]));
    b.bind(
        G_IN_PVS,
        (vm, a) ->
            world().inPvs(vec(vm.memory(), a[0], start), vec(vm.memory(), a[1], end), false)
                ? 1
                : 0);
    b.bind(
        G_IN_PVS_IGNORE_PORTALS,
        (vm, a) ->
            world().inPvs(vec(vm.memory(), a[0], start), vec(vm.memory(), a[1], end), true)
                ? 1
                : 0);
    b.bind(
        G_ADJUST_AREA_PORTAL_STATE,
        (vm, a) -> {
          world().adjustAreaPortalState(entity(a[0]), a[1] != 0);
          return 0;
        });
    b.bind(G_AREAS_CONNECTED, (vm, a) -> world().visibility().areasConnected(a[0], a[1]) ? 1 : 0);
    b.bind(
        G_LINKENTITY,
        (vm, a) -> {
          world().link(entity(a[0]));
          return 0;
        });
    b.bind(
        G_UNLINKENTITY,
        (vm, a) -> {
          world().unlink(entity(a[0]));
          return 0;
        });
    b.bind(
        G_ENTITIES_IN_BOX,
        (vm, a) -> {
          int max = Math.max(0, Math.min(a[3], list.length));
          if (max > 0) vm.memory().resolve(a[2], 4 * max);
          int count =
              world()
                  .entitiesInBox(
                      vec(vm.memory(), a[0], mins), vec(vm.memory(), a[1], maxs), list, max);
          for (int i = 0; i < count; i++) vm.memory().writeInt(a[2] + 4 * i, list[i]);
          return count;
        });
    b.bind(
        G_ENTITY_CONTACT,
        (vm, a) ->
            world()
                    .entityContact(
                        vec(vm.memory(), a[0], mins),
                        vec(vm.memory(), a[1], maxs),
                        entity(a[2]),
                        false)
                ? 1
                : 0);
    b.bind(
        G_ENTITY_CONTACTCAPSULE,
        (vm, a) ->
            world()
                    .entityContact(
                        vec(vm.memory(), a[0], mins),
                        vec(vm.memory(), a[1], maxs),
                        entity(a[2]),
                        true)
                ? 1
                : 0);
    b.bind(G_BOT_ALLOCATE_CLIENT, (vm, a) -> server.botAllocateClient());
    b.bind(
        G_BOT_FREE_CLIENT,
        (vm, a) -> {
          server.botFreeClient(a[0]);
          return 0;
        });
    b.bind(
        G_GET_USERCMD,
        (vm, a) -> {
          userCmdLayout.write(vm.memory(), a[1], server.checkedClient(a[0]).lastUsercmd());
          return 0;
        });
    b.bind(
        G_GET_ENTITY_TOKEN,
        (vm, a) -> {
          var tokens = server.entityTokens();
          String token = tokens.next();
          GuestText.write(vm.memory(), a[0], token, a[1]);
          return tokens.more() ? 1 : 0;
        });
    b.bind(
        G_DEBUG_POLYGON_CREATE,
        (vm, a) -> {
          // Read the points so a bad pointer is caught, then report that nothing was created.
          if (a[1] > 0) vm.memory().resolve(a[2], Math.multiplyExact(a[1], VmStructs.VEC3_BYTES));
          return NO_DEBUG_POLYGON;
        });
    b.bind(G_DEBUG_POLYGON_DELETE, (vm, a) -> 0);
  }

  private int trace(QvmInstance vm, int[] a, boolean capsule) {
    QvmMemory memory = vm.memory();
    vec(memory, a[1], start);
    vec(memory, a[2], mins);
    vec(memory, a[3], maxs);
    vec(memory, a[4], end);
    world().trace(trace, start, mins, maxs, end, a[5], a[6], capsule);
    trace.write(memory, a[0]);
    return 0;
  }

  AbiProfile profile() {
    return profile;
  }
}
