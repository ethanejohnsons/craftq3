package dev.bluevista.craftq3.engine.game.bot;

import static dev.bluevista.craftq3.engine.game.bot.BotStructs.*;
import static dev.bluevista.craftq3.engine.vmabi.BotlibImport.*;

import dev.bluevista.craftq3.engine.botlib.BotLib;
import dev.bluevista.craftq3.engine.common.math.Vec3f;
import dev.bluevista.craftq3.engine.vm.QvmException;
import dev.bluevista.craftq3.engine.vm.QvmInstance;
import dev.bluevista.craftq3.engine.vm.QvmMemory;
import dev.bluevista.craftq3.engine.vmabi.AbiProfile;
import dev.bluevista.craftq3.engine.vmabi.Bindings;
import dev.bluevista.craftq3.engine.vmabi.BotlibImport;
import dev.bluevista.craftq3.engine.vmabi.ImportHandler;
import dev.bluevista.craftq3.engine.vmabi.layout.UserCmdLayout;
import dev.bluevista.craftq3.engine.vmabi.service.GuestText;
import java.util.Arrays;
import java.util.function.ToIntFunction;

/**
 * The qagame botlib imports bound one to one onto the {@code engine:botlib} {@link BotLib} facade:
 * library control (200), AAS queries (300 and 575 to 577) and elementary actions (400) here, the AI
 * services (500) in {@link BotAiBindings}. Structures are marshalled by {@link BotStructs}.
 *
 * <p>Errors: botlib runs strict, so a failure reaches the import dispatcher, which applies the
 * vm-abi policy (the documented neutral value, or a VM fault under {@code vm_strictSyscalls 1});
 * the bot the call belongs to is first reported to the server ({@link BotHost#botFailed}), which
 * marks it degraded and may kick it ({@code bot_failurePolicy kick}).
 */
public final class BotlibAdapter implements BotSupport {
  private BotLib botlib;
  private BotHost host;
  private AbiProfile profile = AbiProfile.Q3_132;

  /** Per-state owner client for failure attribution (-1: unknown). */
  final int[] goalStateClient = filled(), moveStateClient = filled(), chatStateClient = filled();

  private static int[] filled() {
    int[] values = new int[256];
    Arrays.fill(values, -1);
    return values;
  }

  BotLib botlib() {
    return botlib;
  }

  AbiProfile profile() {
    return profile;
  }

  @Override
  public void bind(Bindings b, AbiProfile abi, BotHost server) {
    profile = abi;
    if (botlib == null || host != server) {
      host = server;
      botlib = new BotLib(new ServerBotWorld(server));
      botlib.setStrict(true);
      botlib.seed(0x51A3);
    }
    bindLibrary(b);
    bindAas(b);
    bindActions(b);
    new BotAiBindings(this).bind(b);
  }

  @Override
  public void clientFreed(int client) {
    if (botlib != null && botlib.isSetup()) botlib.clearClient(client);
  }

  @Override
  public void close() {
    if (botlib != null) botlib.close();
    botlib = null;
  }

  // ---- binding helpers ------------------------------------------------------------------------

  /** Binds {@code handler}, attributing a failure to the client {@code owner} names. */
  void bind(Bindings b, BotlibImport entry, ToIntFunction<int[]> owner, ImportHandler handler) {
    b.bind(
        entry,
        (vm, a) -> {
          try {
            return handler.call(vm, a);
          } catch (QvmException nested) {
            throw nested;
          } catch (RuntimeException failure) {
            int client = -1;
            try {
              client = owner.applyAsInt(a);
            } catch (RuntimeException ignored) {
              client = -1;
            }
            host.botFailed(client, failure);
            throw failure;
          }
        });
  }

  static final ToIntFunction<int[]> NO_CLIENT = a -> -1, ARG0 = a -> a[0];

  ToIntFunction<int[]> goalOwner() {
    return a -> owner(goalStateClient, a[0]);
  }

  ToIntFunction<int[]> moveOwner() {
    return a -> owner(moveStateClient, a[0]);
  }

  ToIntFunction<int[]> chatOwner() {
    return a -> owner(chatStateClient, a[0]);
  }

  static int owner(int[] table, int state) {
    return state >= 0 && state < table.length ? table[state] : -1;
  }

  static String text(QvmInstance vm, int address) {
    return GuestText.read(vm.memory(), address);
  }

  // ---- library (200) --------------------------------------------------------------------------

  private void bindLibrary(Bindings b) {
    bind(b, BOTLIB_SETUP, NO_CLIENT, (vm, a) -> botlib.setup());
    bind(b, BOTLIB_SHUTDOWN, NO_CLIENT, (vm, a) -> botlib.shutdown());
    bind(
        b,
        BOTLIB_LIBVAR_SET,
        NO_CLIENT,
        (vm, a) -> botlib.libVarSet(text(vm, a[0]), text(vm, a[1])));
    bind(
        b,
        BOTLIB_LIBVAR_GET,
        NO_CLIENT,
        (vm, a) -> {
          GuestText.write(vm.memory(), a[1], botlib.libVarGet(text(vm, a[0])), a[2]);
          return 0;
        });
    bind(
        b,
        BOTLIB_PC_ADD_GLOBAL_DEFINE,
        NO_CLIENT,
        (vm, a) -> botlib.pcAddGlobalDefine(text(vm, a[0])) ? 1 : 0);
    bind(b, BOTLIB_START_FRAME, NO_CLIENT, (vm, a) -> botlib.startFrame(f(a[0])));
    bind(b, BOTLIB_LOAD_MAP, NO_CLIENT, (vm, a) -> botlib.loadMap(text(vm, a[0])));
    bind(
        b,
        BOTLIB_UPDATENTITY,
        NO_CLIENT,
        (vm, a) -> botlib.updateEntity(a[0], a[1] == 0 ? null : entityState(vm.memory(), a[1])));
    bind(
        b,
        BOTLIB_TEST,
        NO_CLIENT,
        (vm, a) ->
            botlib.test(a[0], text(vm, a[1]), vec(vm.memory(), a[2]), vec(vm.memory(), a[3])));
    bind(b, BOTLIB_GET_SNAPSHOT_ENTITY, ARG0, (vm, a) -> host.snapshotEntity(a[0], a[1]));
    bind(
        b,
        BOTLIB_GET_CONSOLE_MESSAGE,
        ARG0,
        (vm, a) -> {
          String message = host.consoleMessage(a[0]);
          if (message == null) return 0;
          GuestText.write(vm.memory(), a[1], message, a[2]);
          return 1;
        });
    bind(
        b,
        BOTLIB_USER_COMMAND,
        ARG0,
        (vm, a) -> {
          host.botUserCommand(a[0], UserCmdLayout.of(profile).read(vm.memory(), a[1]));
          return 0;
        });
  }

  // ---- AAS (300, 575-577) ---------------------------------------------------------------------

  private void bindAas(Bindings b) {
    bind(
        b,
        BOTLIB_AAS_ENABLE_ROUTING_AREA,
        NO_CLIENT,
        (vm, a) -> botlib.aasEnableRoutingArea(a[0], a[1]));
    bind(
        b,
        BOTLIB_AAS_BBOX_AREAS,
        NO_CLIENT,
        (vm, a) -> {
          QvmMemory m = vm.memory();
          var areas = botlib.aasBBoxAreas(vec(m, a[0]), vec(m, a[1]), Math.max(0, a[3]));
          if (!areas.isEmpty()) m.resolve(a[2], 4 * areas.size());
          for (int i = 0; i < areas.size(); i++) m.writeInt(a[2] + 4 * i, areas.get(i));
          return areas.size();
        });
    bind(
        b,
        BOTLIB_AAS_AREA_INFO,
        NO_CLIENT,
        (vm, a) -> {
          QvmMemory m = vm.memory();
          m.fill(a[1], AREA_INFO_BYTES, 0);
          var info = botlib.aasAreaInfo(a[0]);
          if (info.isEmpty()) return 0;
          var i = info.get();
          m.writeInt(a[1], i.contents());
          m.writeInt(a[1] + 4, i.flags());
          m.writeInt(a[1] + 8, i.presenceType());
          m.writeInt(a[1] + 12, i.cluster());
          vec(m, a[1] + 16, i.mins());
          vec(m, a[1] + 28, i.maxs());
          vec(m, a[1] + 40, i.center());
          return 1;
        });
    bind(
        b,
        BOTLIB_AAS_ENTITY_INFO,
        NO_CLIENT,
        (vm, a) -> {
          entityInfo(vm.memory(), a[1], botlib.aasEntityInfo(a[0]));
          return 0;
        });
    bind(b, BOTLIB_AAS_INITIALIZED, NO_CLIENT, (vm, a) -> botlib.aasInitialized() ? 1 : 0);
    bind(
        b,
        BOTLIB_AAS_PRESENCE_TYPE_BOUNDING_BOX,
        NO_CLIENT,
        (vm, a) -> {
          var box = botlib.aasPresenceTypeBoundingBox(a[0]);
          vec(vm.memory(), a[1], box.mins());
          vec(vm.memory(), a[2], box.maxs());
          return 0;
        });
    bind(b, BOTLIB_AAS_TIME, NO_CLIENT, (vm, a) -> bits(botlib.aasTime()));
    bind(
        b,
        BOTLIB_AAS_POINT_AREA_NUM,
        NO_CLIENT,
        (vm, a) -> botlib.aasPointAreaNum(vec(vm.memory(), a[0])));
    bind(
        b,
        BOTLIB_AAS_POINT_REACHABILITY_AREA_INDEX,
        NO_CLIENT,
        (vm, a) -> botlib.aasPointReachabilityAreaIndex(a[0] == 0 ? null : vec(vm.memory(), a[0])));
    bind(
        b,
        BOTLIB_AAS_TRACE_AREAS,
        NO_CLIENT,
        (vm, a) -> {
          QvmMemory m = vm.memory();
          var areas = botlib.aasTraceAreas(vec(m, a[0]), vec(m, a[1]), Math.max(0, a[4]));
          for (int i = 0; i < areas.size(); i++) {
            m.writeInt(a[2] + 4 * i, areas.get(i).area());
            if (a[3] != 0) vec(m, a[3] + 12 * i, areas.get(i).point());
          }
          return areas.size();
        });
    bind(
        b,
        BOTLIB_AAS_POINT_CONTENTS,
        NO_CLIENT,
        (vm, a) -> botlib.aasPointContents(vec(vm.memory(), a[0])));
    bind(b, BOTLIB_AAS_NEXT_BSP_ENTITY, NO_CLIENT, (vm, a) -> botlib.aasNextBspEntity(a[0]));
    bind(
        b,
        BOTLIB_AAS_VALUE_FOR_BSP_EPAIR_KEY,
        NO_CLIENT,
        (vm, a) -> {
          var value = botlib.aasValueForBspEpairKey(a[0], text(vm, a[1]));
          GuestText.write(vm.memory(), a[2], value.orElse(""), a[3]);
          return value.isPresent() ? 1 : 0;
        });
    bind(
        b,
        BOTLIB_AAS_VECTOR_FOR_BSP_EPAIR_KEY,
        NO_CLIENT,
        (vm, a) -> {
          var value = botlib.aasVectorForBspEpairKey(a[0], text(vm, a[1]));
          vec(vm.memory(), a[2], value.orElse(Vec3f.ZERO));
          return value.isPresent() ? 1 : 0;
        });
    bind(
        b,
        BOTLIB_AAS_FLOAT_FOR_BSP_EPAIR_KEY,
        NO_CLIENT,
        (vm, a) -> {
          var value = botlib.aasFloatForBspEpairKey(a[0], text(vm, a[1]));
          vm.memory().writeFloat(a[2], value.orElse(0f));
          return value.isPresent() ? 1 : 0;
        });
    bind(
        b,
        BOTLIB_AAS_INT_FOR_BSP_EPAIR_KEY,
        NO_CLIENT,
        (vm, a) -> {
          var value = botlib.aasIntForBspEpairKey(a[0], text(vm, a[1]));
          vm.memory().writeInt(a[2], value.orElse(0));
          return value.isPresent() ? 1 : 0;
        });
    bind(b, BOTLIB_AAS_AREA_REACHABILITY, NO_CLIENT, (vm, a) -> botlib.aasAreaReachability(a[0]));
    bind(
        b,
        BOTLIB_AAS_AREA_TRAVELTIME_TO_GOAL_AREA,
        NO_CLIENT,
        (vm, a) -> botlib.aasAreaTravelTimeToGoalArea(a[0], vec(vm.memory(), a[1]), a[2], a[3]));
    bind(
        b,
        BOTLIB_AAS_SWIMMING,
        NO_CLIENT,
        (vm, a) -> botlib.aasSwimming(vec(vm.memory(), a[0])) ? 1 : 0);
    bind(
        b,
        BOTLIB_AAS_PREDICT_CLIENT_MOVEMENT,
        NO_CLIENT,
        (vm, a) -> {
          QvmMemory m = vm.memory();
          m.resolve(a[0], CLIENT_MOVE_BYTES);
          var prediction =
              botlib.aasPredictClientMovement(
                  a[1],
                  vec(m, a[2]),
                  a[3],
                  a[4] != 0,
                  vec(m, a[5]),
                  vec(m, a[6]),
                  a[7],
                  a[8],
                  f(a[9]),
                  a[10],
                  a[11]);
          if (prediction.isEmpty()) return 0;
          clientMove(m, a[0], prediction.get());
          return 1;
        });
    bind(
        b,
        BOTLIB_AAS_ALTERNATIVE_ROUTE_GOAL,
        NO_CLIENT,
        (vm, a) -> {
          QvmMemory m = vm.memory();
          var goals =
              botlib.aasAlternativeRouteGoals(
                  vec(m, a[0]), a[1], vec(m, a[2]), a[3], a[4], Math.max(0, a[6]), a[7]);
          for (int i = 0; i < goals.size(); i++) {
            int at = a[5] + ALT_ROUTE_GOAL_BYTES * i;
            m.fill(at, ALT_ROUTE_GOAL_BYTES, 0);
            var g = goals.get(i);
            vec(m, at, g.origin());
            m.writeInt(at + 12, g.area());
            m.writeShort(at + 16, g.startTravelTime());
            m.writeShort(at + 18, g.goalTravelTime());
            m.writeShort(at + 20, g.extraTravelTime());
          }
          return goals.size();
        });
    bind(
        b,
        BOTLIB_AAS_PREDICT_ROUTE,
        NO_CLIENT,
        (vm, a) -> {
          QvmMemory m = vm.memory();
          var result =
              botlib.aasPredictRoute(
                  a[1], vec(m, a[2]), a[3], a[4], a[5], a[6], a[7], a[8], a[9], a[10]);
          var p = result.prediction();
          m.resolve(a[0], 36);
          vec(m, a[0], p.endPosition());
          m.writeInt(a[0] + 12, p.endArea());
          m.writeInt(a[0] + 16, p.stopEvent());
          m.writeInt(a[0] + 20, p.endContents());
          m.writeInt(a[0] + 24, p.endTravelFlags());
          // numareas (28) is left as the caller set it, as the original leaves it.
          m.writeInt(a[0] + 32, p.time());
          return result.success() ? 1 : 0;
        });
  }

  // ---- elementary actions (400) ---------------------------------------------------------------

  @FunctionalInterface
  private interface ClientAction {
    void run(int client);
  }

  private void action(Bindings b, BotlibImport entry, ClientAction action) {
    bind(
        b,
        entry,
        ARG0,
        (vm, a) -> {
          action.run(a[0]);
          return 0;
        });
  }

  private void bindActions(Bindings b) {
    bind(b, BOTLIB_EA_SAY, ARG0, (vm, a) -> run(() -> botlib.eaSay(a[0], text(vm, a[1]))));
    bind(b, BOTLIB_EA_SAY_TEAM, ARG0, (vm, a) -> run(() -> botlib.eaSayTeam(a[0], text(vm, a[1]))));
    bind(b, BOTLIB_EA_COMMAND, ARG0, (vm, a) -> run(() -> botlib.eaCommand(a[0], text(vm, a[1]))));
    bind(b, BOTLIB_EA_ACTION, ARG0, (vm, a) -> run(() -> botlib.eaAction(a[0], a[1])));
    action(b, BOTLIB_EA_GESTURE, c -> botlib.eaGesture(c));
    action(b, BOTLIB_EA_TALK, c -> botlib.eaTalk(c));
    action(b, BOTLIB_EA_ATTACK, c -> botlib.eaAttack(c));
    action(b, BOTLIB_EA_USE, c -> botlib.eaUse(c));
    action(b, BOTLIB_EA_RESPAWN, c -> botlib.eaRespawn(c));
    action(b, BOTLIB_EA_CROUCH, c -> botlib.eaCrouch(c));
    action(b, BOTLIB_EA_MOVE_UP, c -> botlib.eaMoveUp(c));
    action(b, BOTLIB_EA_MOVE_DOWN, c -> botlib.eaMoveDown(c));
    action(b, BOTLIB_EA_MOVE_FORWARD, c -> botlib.eaMoveForward(c));
    action(b, BOTLIB_EA_MOVE_BACK, c -> botlib.eaMoveBack(c));
    action(b, BOTLIB_EA_MOVE_LEFT, c -> botlib.eaMoveLeft(c));
    action(b, BOTLIB_EA_MOVE_RIGHT, c -> botlib.eaMoveRight(c));
    action(b, BOTLIB_EA_JUMP, c -> botlib.eaJump(c));
    action(b, BOTLIB_EA_DELAYED_JUMP, c -> botlib.eaDelayedJump(c));
    action(b, BOTLIB_EA_RESET_INPUT, c -> botlib.eaResetInput(c));
    bind(b, BOTLIB_EA_SELECT_WEAPON, ARG0, (vm, a) -> run(() -> botlib.eaSelectWeapon(a[0], a[1])));
    bind(
        b,
        BOTLIB_EA_MOVE,
        ARG0,
        (vm, a) -> run(() -> botlib.eaMove(a[0], vec(vm.memory(), a[1]), f(a[2]))));
    bind(
        b, BOTLIB_EA_VIEW, ARG0, (vm, a) -> run(() -> botlib.eaView(a[0], vec(vm.memory(), a[1]))));
    bind(b, BOTLIB_EA_END_REGULAR, ARG0, (vm, a) -> run(() -> botlib.eaEndRegular(a[0], f(a[1]))));
    bind(
        b,
        BOTLIB_EA_GET_INPUT,
        ARG0,
        (vm, a) -> {
          input(vm.memory(), a[2], botlib.eaGetInput(a[0], f(a[1])));
          return 0;
        });
  }

  static int run(Runnable action) {
    action.run();
    return 0;
  }
}
