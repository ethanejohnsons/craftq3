package dev.bluevista.craftq3.server;

import dev.bluevista.craftq3.assets.aas.AasReader;
import dev.bluevista.craftq3.assets.bsp.BspMap;
import dev.bluevista.craftq3.botlib.aas.AasMovementRoutes;
import dev.bluevista.craftq3.botlib.aas.AasNavigation;
import dev.bluevista.craftq3.botlib.aas.AasReachabilityArea;
import dev.bluevista.craftq3.botlib.aas.AasRoutePredictor;
import dev.bluevista.craftq3.botlib.aas.AasRouteTimes;
import dev.bluevista.craftq3.botlib.aas.TravelPolicy;
import dev.bluevista.craftq3.botlib.character.BotCharacters;
import dev.bluevista.craftq3.botlib.chat.BotChat;
import dev.bluevista.craftq3.botlib.chat.ChatMatcher;
import dev.bluevista.craftq3.botlib.ea.ActionFlags;
import dev.bluevista.craftq3.botlib.ea.BotInput;
import dev.bluevista.craftq3.botlib.ea.ElementaryActions;
import dev.bluevista.craftq3.botlib.goal.BotGoals;
import dev.bluevista.craftq3.botlib.goal.BotItemSelector;
import dev.bluevista.craftq3.botlib.goal.BotMapGoals;
import dev.bluevista.craftq3.botlib.goal.Goal;
import dev.bluevista.craftq3.botlib.goal.GoalQueries;
import dev.bluevista.craftq3.botlib.item.ItemConfig;
import dev.bluevista.craftq3.botlib.item.ItemPlacement;
import dev.bluevista.craftq3.botlib.item.ItemRegistry;
import dev.bluevista.craftq3.botlib.item.JumpPadItemAreas;
import dev.bluevista.craftq3.botlib.movement.AasMovementPredictor;
import dev.bluevista.craftq3.botlib.movement.AasMovementWorld;
import dev.bluevista.craftq3.botlib.movement.BarrierReachMovement;
import dev.bluevista.craftq3.botlib.movement.BobbingPlatformMovement;
import dev.bluevista.craftq3.botlib.movement.BotDirectionalMovement;
import dev.bluevista.craftq3.botlib.movement.BotMovement;
import dev.bluevista.craftq3.botlib.movement.BotMovementView;
import dev.bluevista.craftq3.botlib.movement.BotVisiblePosition;
import dev.bluevista.craftq3.botlib.movement.GroundMoveToGoal;
import dev.bluevista.craftq3.botlib.movement.GroundReachMovement;
import dev.bluevista.craftq3.botlib.movement.JumpPadMovement;
import dev.bluevista.craftq3.botlib.movement.JumpReachMovement;
import dev.bluevista.craftq3.botlib.movement.JumpRunStart;
import dev.bluevista.craftq3.botlib.movement.LedgeReachMovement;
import dev.bluevista.craftq3.botlib.movement.LiquidReachMovement;
import dev.bluevista.craftq3.botlib.movement.MovementInit;
import dev.bluevista.craftq3.botlib.movement.MovementObstacles;
import dev.bluevista.craftq3.botlib.movement.MovementObstruction;
import dev.bluevista.craftq3.botlib.movement.MovementResult;
import dev.bluevista.craftq3.botlib.movement.MoverQueries;
import dev.bluevista.craftq3.botlib.movement.TeleportReachMovement;
import dev.bluevista.craftq3.botlib.movement.WeaponJumpMovement;
import dev.bluevista.craftq3.botlib.script.ScriptSources;
import dev.bluevista.craftq3.botlib.weapon.BotWeapons;
import dev.bluevista.craftq3.botlib.weapon.WeaponInfo;
import dev.bluevista.craftq3.botlib.weight.WeightConfig;
import dev.bluevista.craftq3.collision.TraceRequest;
import dev.bluevista.craftq3.collision.TraceResult;
import dev.bluevista.craftq3.collision.TraceWorld;
import dev.bluevista.craftq3.core.fs.VirtualFileSystem;
import dev.bluevista.craftq3.core.fs.VirtualPath;
import dev.bluevista.craftq3.core.math.Vec3;
import dev.bluevista.craftq3.vm.QvmMemory;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.NoSuchFileException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** Bounded engine-side bot services; original qagame retains AI decisions and usercmd assembly. */
public final class BotlibHost implements AutoCloseable {
  public interface Host {
    int maxClients();

    void clientCommand(int client, String command);

    void userCommand(int client, UserCommand command);

    int snapshotEntity(int client, int index);

    String consoleMessage(int client);

    TraceResult trace(TraceRequest request);

    TraceResult entityTrace(int entity, TraceRequest request);

    int pointContents(Vec3 point);
  }

  public record Status(
      boolean setup,
      boolean mapLoaded,
      String mapName,
      float time,
      int validEntities,
      int openSources,
      Map<Integer, Long> calls) {
    public Status {
      calls = Map.copyOf(calls);
    }
  }

  private record Entity(
      byte[] bytes,
      float updated,
      float interval,
      int frame,
      Vec3 lastVisibleOrigin,
      boolean linked) {
    Entity unlinked() {
      return new Entity(bytes, updated, interval, frame, lastVisibleOrigin, false);
    }
  }

  private static final Vec3 ZERO = new Vec3(0, 0, 0);
  private final VirtualFileSystem fs;
  private final String mapName;
  private final BspMap bsp;
  private final GameAbi abi;
  private final Host host;
  private final TraceWorld traceWorld;
  private final Consumer<String> output;
  private final Map<String, String> variables = new LinkedHashMap<>();
  private final Map<Integer, Long> calls = new LinkedHashMap<>();
  private final Entity[] entities = new Entity[1024];
  private final Set<Integer> disabledAreas = new HashSet<>();
  private final Set<Integer> sourceHandles = new HashSet<>();
  private final ScriptSources scripts;
  private final java.util.Random random = new java.util.Random(0);
  private ElementaryActions actions;
  private BotCharacters characters;
  private BotGoals goals;
  private BotWeapons weapons;
  private BotMovement movement;
  private BotChat chat;
  private ItemRegistry items;
  private ItemPlacement itemPlacement;
  private BotMapGoals mapGoals;
  private AasNavigation navigation;
  private AasRouteTimes routeTimes;
  private AasMovementRoutes movementRoutes;
  private BotMovementView movementView;
  private GroundMoveToGoal groundMoveToGoal;
  private AasMovementWorld movementWorld;
  private AasReachabilityArea reachabilityArea;
  private BotItemSelector itemSelector;
  private boolean setup, closed;
  private float time;
  private int frame;

  public BotlibHost(
      VirtualFileSystem fs,
      String mapName,
      BspMap bsp,
      GameAbi abi,
      Host host,
      Consumer<String> output) {
    this.fs = fs;
    this.mapName = mapBase(mapName);
    this.bsp = bsp;
    this.abi = abi;
    this.host = host;
    traceWorld =
        new TraceWorld() {
          @Override
          public TraceResult trace(TraceRequest request) {
            return host.trace(request);
          }

          @Override
          public int pointContents(Vec3 point, int contentsMask, int ignoreEntity) {
            if (ignoreEntity != -1)
              throw new UnsupportedOperationException("Filtered bot point contents");
            return host.pointContents(point) & contentsMask;
          }
        };
    this.output = output;
    scripts = new ScriptSources(fs);
  }

  /** Seeded host stream is reproducible; native engine random-stream parity is not claimed. */
  public void seed(int seed) {
    if (closed || setup) throw new IllegalStateException("Botlib seed must precede setup");
    random.setSeed(seed);
  }

  /** Releases per-client EA state when an engine bot slot is freed or reused. */
  public void clearClient(int client) {
    if (actions != null) actions.clearClient(client);
  }

  public Status status() {
    int valid = 0;
    for (var entity : entities) if (entity != null && entity.frame() == frame) valid++;
    return new Status(
        setup, navigation != null, mapName, time, valid, closed ? 0 : scripts.openCount(), calls);
  }

  public int invoke(QvmMemory memory, int call, int[] a) throws IOException {
    if (closed) throw new IllegalStateException("Bot library is closed");
    calls.merge(call, 1L, Long::sum);
    return switch (serviceNumber(call)) {
      case 200 -> setup();
      case 201 -> {
        shutdown();
        yield 0;
      }
      case 202 -> {
        variable(text(memory, a[0]), text(memory, a[1]));
        yield 0;
      }
      case 203 -> {
        VmAbi.string(
            memory,
            a[1],
            variables.getOrDefault(text(memory, a[0]).toLowerCase(java.util.Locale.ROOT), ""),
            a[2]);
        yield 0;
      }
      case 204 -> {
        scripts.addGlobalDefine(text(memory, a[0]));
        yield 0;
      }
      case 205 -> {
        requireSetup();
        float next = f(a[0]);
        if (!Float.isFinite(next) || next < time)
          throw new IllegalArgumentException("Invalid botlib frame time");
        // A link survives the first frame without an update, then expires before invalidation.
        for (int number = 0; number < entities.length; number++) {
          var entity = entities[number];
          if (entity != null && entity.linked() && entity.frame() != frame) {
            if (movementWorld != null) movementWorld.unlink(number);
            entities[number] = entity.unlinked();
          }
        }
        time = next;
        frame++;
        yield 0;
      }
      case 206 -> loadMap(text(memory, a[0]));
      case 207 -> updateEntity(memory, a[0], a[1]);
      case 208 -> throw unsupported(call, "interactive botlib debug testing");
      case 209 -> host.snapshotEntity(a[0], a[1]);
      case 210 -> {
        VmAbi.range(memory, a[1], a[2]);
        String message = host.consoleMessage(a[0]);
        VmAbi.string(memory, a[1], message == null ? "" : message, a[2]);
        yield message == null ? 0 : 1;
      }
      case 211 -> {
        host.userCommand(a[0], readCommand(memory, a[1]));
        yield 0;
      }
      case 300 -> routingArea(a[0], a[1]);
      case 301 -> boxAreas(memory, a);
      case 302 -> areaInfo(memory, a[0], a[1]);
      case 303 -> {
        entityInfo(memory, a[0], a[1]);
        yield 0;
      }
      case 304 -> setup && navigation != null ? 1 : 0;
      case 305 -> {
        presence(memory, a[0], a[1], a[2]);
        yield 0;
      }
      case 306 -> Float.floatToRawIntBits(time);
      case 307 -> navigation().pointArea(VmAbi.vector(memory, a[0]));
      case 308 -> traceAreas(memory, a);
      case 309 -> host.pointContents(VmAbi.vector(memory, a[0]));
      case 310 -> {
        if (a[0] < 0 || a[0] > bsp.entities().size())
          throw new IllegalArgumentException("Invalid BSP entity cursor");
        yield a[0] < bsp.entities().size() ? a[0] + 1 : 0;
      }
      case 311 -> {
        String value = epair(a[0], text(memory, a[1]));
        VmAbi.string(memory, a[2], value == null ? "" : value, a[3]);
        yield value == null ? 0 : 1;
      }
      case 312 -> vectorEpair(memory, a);
      case 313 -> {
        String value = epair(a[0], text(memory, a[1]));
        float number = value == null ? 0 : Float.parseFloat(value.trim());
        if (!Float.isFinite(number)) throw new IllegalArgumentException("Nonfinite BSP epair");
        memory.writeFloat(a[2], number);
        yield value == null ? 0 : 1;
      }
      case 314 -> {
        String value = epair(a[0], text(memory, a[1]));
        memory.writeInt(a[2], value == null ? 0 : Integer.parseInt(value.trim()));
        yield value == null ? 0 : 1;
      }
      case 315 -> {
        var map = navigation().map();
        yield a[0] > 0 && a[0] < map.areaSettings().size()
            ? map.areaSettings().get(a[0]).reachabilityCount()
            : 0;
      }
      case 316 -> travelTime(memory, a);
      case 317 -> swimming(VmAbi.vector(memory, a[0])) ? 1 : 0;
      case 318 -> predictMovement(memory, a);
      case 575, 577 -> throw unsupported(call, "advanced AAS movement/route prediction");
      case 576 -> predictRoute(memory, a);
      case 400 -> {
        actions().say(a[0], text(memory, a[1]));
        yield 0;
      }
      case 401 -> {
        actions().sayTeam(a[0], text(memory, a[1]));
        yield 0;
      }
      case 402 -> {
        actions().command(a[0], text(memory, a[1]));
        yield 0;
      }
      case 403 -> {
        actions().action(a[0], a[1]);
        yield 0;
      }
      case 404 -> {
        actions().gesture(a[0]);
        yield 0;
      }
      case 405 -> {
        actions().talk(a[0]);
        yield 0;
      }
      case 406 -> {
        actions().attack(a[0]);
        yield 0;
      }
      case 407 -> {
        actions().use(a[0]);
        yield 0;
      }
      case 408 -> {
        actions().respawn(a[0]);
        yield 0;
      }
      case 409 -> {
        actions().crouch(a[0]);
        yield 0;
      }
      case 410 -> {
        actions().moveUp(a[0]);
        yield 0;
      }
      case 411 -> {
        actions().moveDown(a[0]);
        yield 0;
      }
      case 412 -> {
        actions().moveForward(a[0]);
        yield 0;
      }
      case 413 -> {
        actions().moveBack(a[0]);
        yield 0;
      }
      case 414 -> {
        actions().moveLeft(a[0]);
        yield 0;
      }
      case 415 -> {
        actions().moveRight(a[0]);
        yield 0;
      }
      case 416 -> {
        actions().selectWeapon(a[0], a[1]);
        yield 0;
      }
      case 417 -> {
        actions().jump(a[0]);
        yield 0;
      }
      case 418 -> {
        actions().delayedJump(a[0]);
        yield 0;
      }
      case 419 -> {
        actions().move(a[0], VmAbi.vector(memory, a[1]), f(a[2]));
        yield 0;
      }
      case 420 -> {
        actions().view(a[0], VmAbi.vector(memory, a[1]));
        yield 0;
      }
      case 421 -> {
        actions().endRegular(a[0], f(a[1]));
        yield 0;
      }
      case 422 -> {
        VmAbi.range(memory, a[2], 40);
        var input = actions().getInput(a[0], f(a[1]));
        var bytes = ByteBuffer.allocate(40);
        input.writeTo(bytes, 0);
        memory.writeBytes(a[2], bytes.array());
        yield 0;
      }
      case 423 -> {
        actions().resetInput(a[0]);
        yield 0;
      }
      case 500 -> characters().loadCharacter(text(memory, a[0]), f(a[1]));
      case 501 -> {
        characters().free(a[0]);
        yield 0;
      }
      case 502 -> Float.floatToRawIntBits(characters().characteristicFloat(a[0], a[1]));
      case 503 ->
          Float.floatToRawIntBits(
              characters().characteristicBoundedFloat(a[0], a[1], f(a[2]), f(a[3])));
      case 504 -> characters().characteristicInteger(a[0], a[1]);
      case 505 -> characters().characteristicBoundedInteger(a[0], a[1], a[2], a[3]);
      case 506 -> {
        VmAbi.range(memory, a[2], a[3]);
        VmAbi.string(memory, a[2], characters().characteristicString(a[0], a[1], a[3]), a[3]);
        yield 0;
      }
      case 507 -> chat().allocate();
      case 508 -> {
        chat().free(a[0]);
        yield 0;
      }
      case 509 -> {
        if (!chat().queue(a[0], a[1], chatText(text(memory, a[2]))))
          output.accept("Bot console message queue budget exceeded\n");
        yield 0;
      }
      case 510 -> {
        chat().remove(a[0], a[1]);
        yield 0;
      }
      case 511 -> {
        int size = abi == GameAbi.RETAIL_1999 ? 172 : 276;
        VmAbi.range(memory, a[1], size);
        var message = chat().next(a[0]);
        if (message.isEmpty()) yield 0;
        var value = message.get();
        memory.fill(a[1], size, 0);
        memory.writeInt(a[1], value.id());
        memory.writeFloat(a[1] + 4, value.time());
        memory.writeInt(a[1] + 8, value.type());
        VmAbi.string(memory, a[1] + 12, value.text(), chatTextBytes());
        yield value.id();
      }
      case 512 -> chat().queued(a[0]);
      case 513 -> {
        chat().initial(a[0], text(memory, a[1]), a[2], chatVariables(memory, a, 3));
        yield 0;
      }
      case 514 ->
          chat().reply(a[0], chatText(text(memory, a[1])), a[2], a[3], chatVariables(memory, a, 4))
              ? 1
              : 0;
      case 515 -> chat().length(a[0]);
      case 516 -> {
        if (a[2] < 0 || a[2] > 2)
          throw new IllegalArgumentException("Invalid bot chat destination");
        if (abi == GameAbi.RETAIL_1999 && a[2] == 2)
          throw unsupported(call, "unverified retail tell-chat destination ABI");
        String message = chat().takeMessage(a[0]);
        String prefix = a[2] == 0 ? "say " : a[2] == 1 ? "say_team " : "tell " + a[1] + " ";
        int sender = abi == GameAbi.RETAIL_1999 ? a[1] : chat.client(a[0]);
        host.clientCommand(sender, prefix + message);
        yield 0;
      }
      case 517 -> BotChat.stringContains(text(memory, a[0]), text(memory, a[1]), a[2] != 0);
      case 518 -> {
        var buffer = readChatMatch(memory, a[1]);
        boolean matched = chat().findMatch(chatText(text(memory, a[0])), a[2], buffer, 0);
        writeChatMatch(memory, a[1], buffer);
        yield matched ? 1 : 0;
      }
      case 519 -> {
        var buffer = readChatMatch(memory, a[0]);
        VmAbi.range(memory, a[2], a[3]);
        String value = ChatMatcher.variable(buffer, 0, a[1], a[3]);
        VmAbi.string(memory, a[2], value, a[3]);
        yield 0;
      }
      case 520 -> {
        String old = text(memory, a[0]);
        VmAbi.string(memory, a[0], BotChat.unifyWhiteSpaces(old), old.length() + 1);
        yield 0;
      }
      case 521 -> {
        VmAbi.string(
            memory,
            a[0],
            chat().replaceSynonyms(chatText(text(memory, a[0])), a[1]),
            chatTextBytes());
        yield 0;
      }
      case 522 -> chatFile(a[0], text(memory, a[1]), text(memory, a[2]));
      case 523 -> {
        chat().setGender(a[0], a[1]);
        yield 0;
      }
      case 524 -> {
        chat().setName(a[0], text(memory, a[1]), a[2]);
        yield 0;
      }
      case 525 -> {
        goals().reset(a[0]);
        yield 0;
      }
      case 526 -> {
        goals().resetAvoid(a[0]);
        yield 0;
      }
      case 527 -> {
        goals().push(a[0], goal(memory, a[1]));
        yield 0;
      }
      case 528 -> {
        goals().pop(a[0]);
        yield 0;
      }
      case 529 -> {
        goals().empty(a[0]);
        yield 0;
      }
      case 530 -> {
        goals()
            .snapshot(a[0])
            .ifPresent(state -> output.accept("Avoid goals: " + state.avoidGoals() + "\n"));
        yield 0;
      }
      case 531 -> {
        goals()
            .snapshot(a[0])
            .ifPresent(state -> output.accept("Goal stack: " + state.stack() + "\n"));
        yield 0;
      }
      case 532 -> {
        VmAbi.range(memory, a[1], a[2]);
        VmAbi.string(
            memory, a[1], items().byNumber(a[0]).map(item -> item.info().name()).orElse(""), a[2]);
        yield 0;
      }
      case 533, 534 -> {
        VmAbi.range(memory, a[1], Goal.BYTE_SIZE);
        var goal = call == 533 ? goals().top(a[0]) : goals().second(a[0]);
        if (goal.isEmpty()) yield 0;
        var buffer = ByteBuffer.allocate(Goal.BYTE_SIZE);
        goal.get().writeTo(buffer, 0);
        memory.writeBytes(a[1], buffer.array());
        yield 1;
      }
      case 535, 536 -> chooseItem(memory, call, a);
      case 537 -> GoalQueries.touching(VmAbi.vector(memory, a[0]), goal(memory, a[1])) ? 1 : 0;
      case 538 ->
          GoalQueries.visibleButMissing(
                  a[0],
                  VmAbi.vector(memory, a[1]),
                  VmAbi.vector(memory, a[2]),
                  goal(memory, a[3]),
                  time,
                  traceWorld,
                  this::lastEntityUpdate)
              ? 1
              : 0;
      case 539 -> {
        VmAbi.range(memory, a[2], Goal.BYTE_SIZE);
        var next =
            items()
                .nextGoal(
                    a[0],
                    text(memory, a[1]),
                    Integer.parseInt(variables.getOrDefault("g_gametype", "0")));
        if (next.isEmpty()) yield -1;
        var buffer = ByteBuffer.allocate(Goal.BYTE_SIZE);
        next.get().writeTo(buffer, 0);
        memory.writeBytes(a[2], buffer.array());
        yield next.get().number();
      }
      case 540 -> Float.floatToRawIntBits(goals().avoidTime(a[0], a[1]));
      case 541 -> {
        items().initialize(bsp.entities());
        yield 0;
      }
      case 542 -> {
        updateItems();
        yield 0;
      }
      case 543 -> itemWeights(a[0], text(memory, a[1]));
      case 544 -> {
        goals().freeWeights(a[0]);
        yield 0;
      }
      case 546 -> goals().allocate(a[0]);
      case 547 -> {
        goals().free(a[0]);
        yield 0;
      }
      case 548 -> {
        movement().reset(a[0]);
        yield 0;
      }
      case 549 -> moveToGoal(memory, a);
      case 550 -> moveInDirection(memory, a);
      case 551 -> {
        movement().resetAvoidReach(a[0]);
        yield 0;
      }
      case 552 -> {
        movement().resetLastAvoidReach(a[0]);
        yield 0;
      }
      case 553 -> reachableArea(VmAbi.vector(memory, a[0]), a[1]);
      case 554 -> movementView(memory, a);
      case 555 -> movement().allocate();
      case 556 -> {
        movement().free(a[0]);
        yield 0;
      }
      case 557 -> {
        VmAbi.range(memory, a[1], MovementInit.BYTE_SIZE);
        movement()
            .initialize(
                a[0],
                MovementInit.readFrom(
                    ByteBuffer.wrap(memory.readBytes(a[1], MovementInit.BYTE_SIZE)), 0));
        yield 0;
      }
      case 558 -> {
        VmAbi.range(memory, a[1], 1024);
        int[] inventory = new int[256];
        for (int i = 0; i < inventory.length; i++) inventory[i] = memory.readInt(a[1] + i * 4);
        yield weapons().chooseBestFightWeapon(a[0], inventory);
      }
      case 559 -> {
        VmAbi.range(memory, a[2], WeaponInfo.BYTE_SIZE);
        var info = weapons().weaponInfo(a[0], a[1]);
        var buffer = ByteBuffer.allocate(WeaponInfo.BYTE_SIZE);
        info.writeTo(buffer, 0);
        memory.writeBytes(a[2], buffer.array());
        yield 0;
      }
      case 560 -> weapons().loadWeaponWeights(a[0], text(memory, a[1]));
      case 561 -> weapons().allocate();
      case 562 -> {
        weapons().free(a[0]);
        yield 0;
      }
      case 563 -> {
        weapons().reset(a[0]);
        yield 0;
      }
      case 567 -> {
        VmAbi.range(memory, a[1], Goal.BYTE_SIZE);
        var next = mapGoals().nextCamp(a[0]);
        if (next.isEmpty()) yield 0;
        var buffer = ByteBuffer.allocate(Goal.BYTE_SIZE);
        next.get().goal().writeTo(buffer, 0);
        memory.writeBytes(a[1], buffer.array());
        yield next.get().nextCursor();
      }
      case 568 -> {
        VmAbi.range(memory, a[1], Goal.BYTE_SIZE);
        var found = mapGoals().location(text(memory, a[0]));
        if (found.isEmpty()) yield 0;
        var buffer = ByteBuffer.allocate(Goal.BYTE_SIZE);
        found.get().writeTo(buffer, 0);
        memory.writeBytes(a[1], buffer.array());
        yield 1;
      }
      case 569 -> chat().initialCount(a[0], text(memory, a[1]));
      case 570 -> {
        VmAbi.range(memory, a[1], a[2]);
        if (a[2] < 1) throw new IllegalArgumentException("Chat output capacity must be positive");
        VmAbi.string(memory, a[1], chat().takeMessage(a[0]), a[2]);
        yield 0;
      }
      case 571 -> {
        goals().removeAvoid(a[0], a[1]);
        yield 0;
      }
      case 572 -> visiblePosition(memory, a);
      case 573 -> {
        goals().setAvoidTime(a[0], a[1], f(a[2]));
        yield 0;
      }
      case 574 -> {
        if (a[3] == BotMovement.AVOID_CLEAR) movement().clearAvoidSpots(a[0]);
        else
          movement()
              .addAvoidSpot(
                  a[0], new BotMovement.AvoidSpot(VmAbi.vector(memory, a[1]), f(a[2]), a[3]));
        yield 0;
      }
      case 578 -> source(text(memory, a[0]));
      case 579 -> {
        sourceHandles.remove(a[0]);
        yield scripts.free(a[0]) ? 1 : 0;
      }
      case 580 -> token(memory, a[0], a[1]);
      case 581 -> {
        var location = scripts.location(a[0]);
        VmAbi.range(memory, a[1], location.path().length() + 1);
        VmAbi.range(memory, a[2], 4);
        VmAbi.string(memory, a[1], location.path(), location.path().length() + 1);
        memory.writeInt(a[2], location.line());
        yield 1;
      }
      default -> throw unsupported(call, "requested bot AI service");
    };
  }

  /** Retail imports retain the pre-Team-Arena EA ordering and obsolete AAS visibility slots. */
  private int serviceNumber(int call) {
    if (abi != GameAbi.RETAIL_1999) return call;
    if (call >= 300 && call <= 302) throw unsupported(call, "legacy AAS visibility query");
    if (call < 400 || call > 426) return call;
    return switch (call) {
      case 400, 401 -> call;
      case 402, 403, 404, 405 -> throw unsupported(call, "legacy item/inventory command");
      case 406 -> 404; // gesture
      case 407 -> 402; // command
      case 408 -> 416; // select weapon
      case 409, 410, 411, 412 -> call - 4; // talk, attack, use, respawn
      case 413, 414 -> call + 4; // jump, delayed jump
      case 415, 416, 417, 418, 419, 420, 421 -> call - 6; // crouch and directional actions
      case 422, 423, 424, 425, 426 -> call - 3; // move, view, end, get, reset
      default -> throw new AssertionError("Unreachable retail EA import");
    };
  }

  private int setup() {
    if (setup) throw new IllegalStateException("Bot library is already set up");
    int count = host.maxClients();
    if (count < 1 || count > 64) throw new IllegalArgumentException("Invalid bot client capacity");
    actions = new ElementaryActions(count, host::clientCommand);
    characters = new BotCharacters(scripts, output);
    goals =
        new BotGoals(
            () -> time,
            number ->
                items == null
                    ? java.util.OptionalDouble.empty()
                    : items.automaticAvoidDuration(number),
            output);
    movement = new BotMovement(output);
    characters.setReloadCharacters(
        !variables.getOrDefault("bot_reloadcharacters", "0").equals("0"));
    setup = true;
    time = 0;
    frame = 0;
    Arrays.fill(entities, null);
    return 0;
  }

  private void shutdown() {
    for (int handle : sourceHandles) scripts.free(handle);
    sourceHandles.clear();
    if (actions != null) actions.close();
    if (characters != null) characters.close();
    if (goals != null) goals.close();
    if (weapons != null) weapons.close();
    if (movement != null) movement.close();
    if (chat != null) chat.close();
    chat = null;
    items = null;
    itemPlacement = null;
    mapGoals = null;
    movement = null;
    weapons = null;
    goals = null;
    characters = null;
    actions = null;
    navigation = null;
    movementWorld = null;
    reachabilityArea = null;
    routeTimes = null;
    movementRoutes = null;
    movementView = null;
    groundMoveToGoal = null;
    itemSelector = null;
    disabledAreas.clear();
    Arrays.fill(entities, null);
    setup = false;
    time = 0;
    frame = 0;
  }

  private void variable(String name, String value) {
    if (!name.matches("[A-Za-z0-9_.-]{1,255}") || value.length() > 8191 || value.indexOf('\0') >= 0)
      throw new IllegalArgumentException("Invalid botlib variable");
    String key = name.toLowerCase(java.util.Locale.ROOT);
    if (!variables.containsKey(key) && variables.size() >= 1024)
      throw new IllegalStateException("Botlib variable budget exceeded");
    variables.put(key, value);
    if (key.equals("bot_reloadcharacters") && characters != null)
      characters.setReloadCharacters(!value.equals("0"));
  }

  private int loadMap(String name) throws IOException {
    requireSetup();
    String requested = mapBase(name);
    mapGoals = null;
    items = null;
    itemPlacement = null;
    itemSelector = null;
    routeTimes = null;
    movementRoutes = null;
    movementView = null;
    groundMoveToGoal = null;
    movementWorld = null;
    reachabilityArea = null;
    if (!requested.equals(mapName))
      throw new IllegalArgumentException("Botlib map differs from active world");
    try {
      var map = AasReader.read(fs.read(new VirtualPath("maps/" + requested + ".aas")));
      navigation = new AasNavigation(map);
      movementWorld = new AasMovementWorld(navigation, host::entityTrace, host::pointContents);
    } catch (NoSuchFileException | FileNotFoundException missing) {
      output.accept("No AAS navigation file for " + requested + "\n");
      navigation = null;
      return 3;
    }
    disabledAreas.clear();
    Arrays.fill(entities, null);
    return 0;
  }

  private int predictMovement(QvmMemory memory, int[] a) {
    VmAbi.range(memory, a[0], 84);
    if (a[1] >= entities.length) throw new IllegalArgumentException("Invalid prediction entity");
    var request =
        new AasMovementPredictor.Request(
            a[1],
            VmAbi.vector(memory, a[2]),
            a[3],
            a[4] != 0,
            VmAbi.vector(memory, a[5]),
            VmAbi.vector(memory, a[6]),
            a[7],
            a[8],
            f(a[9]),
            a[10]);
    if (a[12] != 0) throw new UnsupportedOperationException("AAS prediction debug visualization");
    var predictor = movementPredictor();
    var prediction = predictor.predict(request);
    if (prediction.isEmpty()) return 0;
    var result = prediction.orElseThrow();
    int pointer = a[0];
    VmAbi.vector(memory, pointer, result.endPosition());
    memory.writeInt(pointer + 12, result.endArea());
    VmAbi.vector(memory, pointer + 16, result.velocity());
    if (result.trace().isPresent()) {
      var trace = result.trace().orElseThrow();
      memory.writeInt(pointer + 28, trace.startSolid() ? 1 : 0);
      memory.writeFloat(pointer + 32, trace.fraction());
      VmAbi.vector(memory, pointer + 36, trace.endPosition());
      memory.writeInt(pointer + 48, trace.entity());
      memory.writeInt(pointer + 52, trace.lastArea());
      memory.writeInt(pointer + 56, trace.area());
      memory.writeInt(pointer + 60, trace.planeNumber());
    }
    memory.writeInt(pointer + 64, result.presence());
    memory.writeInt(pointer + 68, result.stopEvent());
    memory.writeInt(pointer + 72, result.endContents());
    memory.writeFloat(pointer + 76, result.time());
    memory.writeInt(pointer + 80, result.frames());
    return 1;
  }

  private int predictRoute(QvmMemory memory, int[] a) {
    VmAbi.range(memory, a[0], AasRoutePredictor.Prediction.BYTE_SIZE);
    var request =
        new AasRoutePredictor.Request(
            a[1],
            VmAbi.vector(memory, a[2]),
            a[3],
            new TravelPolicy(a[4], 6, TravelPolicy.Team.ANY, disabledAreas),
            a[5],
            a[6],
            a[7],
            a[8],
            a[9],
            a[10]);
    var result = new AasRoutePredictor(navigation(), routeTimes()).predict(request);
    // Preserve the unassigned numareas bytes without decoding the caller's existing record.
    var bytes = ByteBuffer.wrap(memory.readBytes(a[0], AasRoutePredictor.Prediction.BYTE_SIZE));
    result.prediction().writeTo(bytes, 0);
    memory.writeBytes(a[0], bytes.array());
    return result.success() ? 1 : 0;
  }

  private int updateEntity(QvmMemory memory, int number, int pointer) {
    requireSetup();
    if (number < 0 || number >= entities.length) return 2;
    if (pointer == 0) {
      if (movementWorld != null) movementWorld.unlink(number);
      var old = entities[number];
      if (old != null) entities[number] = old.unlinked();
      return 0;
    }
    VmAbi.range(memory, pointer, 112);
    for (int offset = 8; offset < 68; offset += 12) VmAbi.vector(memory, pointer + offset);
    var old = entities[number];
    Vec3 previous = ZERO;
    if (old != null) {
      var bytes = ByteBuffer.wrap(old.bytes()).order(java.nio.ByteOrder.LITTLE_ENDIAN);
      previous = new Vec3(bytes.getFloat(8), bytes.getFloat(12), bytes.getFloat(16));
    }
    byte[] owned = memory.readBytes(pointer, 112);
    var state = ByteBuffer.wrap(owned).order(java.nio.ByteOrder.LITTLE_ENDIAN);
    var before =
        old == null ? null : ByteBuffer.wrap(old.bytes()).order(java.nio.ByteOrder.LITTLE_ENDIAN);
    int solid = state.getInt(72);
    boolean relink =
        frame == 1
            || vectorChanged(state, before, 8)
            || (solid == 2
                && (vectorChanged(state, before, 44) || vectorChanged(state, before, 56)))
            || (solid == 3 && vectorChanged(state, before, 20));
    resolveEntityBounds(state, old);
    if (movementWorld != null && relink) {
      Vec3 origin = vector(state, 8);
      movementWorld.link(
          number, floatAdd(origin, vector(state, 44)), floatAdd(origin, vector(state, 56)));
    }
    entities[number] =
        new Entity(
            owned,
            time,
            time - (old == null ? 0 : old.updated()),
            frame,
            previous,
            relink || (old != null && old.linked()));
    return 0;
  }

  private void resolveEntityBounds(ByteBuffer state, Entity previous) {
    int solid = state.getInt(72);
    Vec3 mins, maxs;
    if (solid == 2) {
      mins = vector(state, 44);
      maxs = vector(state, 56);
    } else if (solid == 3) {
      int model = state.getInt(76);
      if (model < 0 || model >= bsp.models().size())
        throw new IllegalArgumentException("Invalid bot BSP model " + model);
      var bounds = bsp.models().get(model).bounds();
      mins = bounds.min();
      maxs = bounds.max();
      var angles = vector(state, 20);
      if (angles.x() != 0 || angles.y() != 0 || angles.z() != 0) {
        float x = Math.max(Math.abs((float) mins.x()), Math.abs((float) maxs.x()));
        float y = Math.max(Math.abs((float) mins.y()), Math.abs((float) maxs.y()));
        float z = Math.max(Math.abs((float) mins.z()), Math.abs((float) maxs.z()));
        float radius = (float) Math.sqrt(x * x + y * y + z * z);
        mins = new Vec3(-radius, -radius, -radius);
        maxs = new Vec3(radius, radius, radius);
      }
    } else if (solid == 0 || solid == 1) {
      var old =
          previous == null
              ? null
              : ByteBuffer.wrap(previous.bytes()).order(java.nio.ByteOrder.LITTLE_ENDIAN);
      mins = old == null ? ZERO : vector(old, 44);
      maxs = old == null ? ZERO : vector(old, 56);
      vector(state, 20, old == null ? ZERO : vector(old, 20));
    } else throw new IllegalArgumentException("Invalid bot entity solid type " + solid);
    if (mins.x() > maxs.x() || mins.y() > maxs.y() || mins.z() > maxs.z())
      throw new IllegalArgumentException("Inverted bot entity bounds");
    vector(state, 44, mins);
    vector(state, 56, maxs);
  }

  private static Vec3 vector(ByteBuffer bytes, int offset) {
    return new Vec3(bytes.getFloat(offset), bytes.getFloat(offset + 4), bytes.getFloat(offset + 8));
  }

  private static boolean vectorChanged(ByteBuffer current, ByteBuffer previous, int offset) {
    for (int i = offset; i < offset + 12; i += 4)
      if (current.getFloat(i) != (previous == null ? 0 : previous.getFloat(i))) return true;
    return false;
  }

  private static void vector(ByteBuffer bytes, int offset, Vec3 value) {
    bytes.putFloat(offset, (float) value.x());
    bytes.putFloat(offset + 4, (float) value.y());
    bytes.putFloat(offset + 8, (float) value.z());
  }

  private static Vec3 floatAdd(Vec3 a, Vec3 b) {
    return new Vec3(
        (float) a.x() + (float) b.x(),
        (float) a.y() + (float) b.y(),
        (float) a.z() + (float) b.z());
  }

  private void entityInfo(QvmMemory memory, int number, int pointer) {
    requireSetup();
    if (number < 0 || number >= entities.length)
      throw new IllegalArgumentException("Invalid AAS entity number");
    VmAbi.range(memory, pointer, 140);
    memory.fill(pointer, 140, 0);
    var entity = entities[number];
    if (entity == null) return;
    memory.writeInt(pointer, entity.frame() == frame ? 1 : 0);
    memory.writeFloat(pointer + 12, entity.updated());
    memory.writeFloat(pointer + 16, entity.interval());
    memory.writeInt(pointer + 20, number);
    memory.writeBytes(pointer + 4, Arrays.copyOfRange(entity.bytes(), 0, 8));
    memory.writeBytes(pointer + 24, Arrays.copyOfRange(entity.bytes(), 8, 44));
    VmAbi.vector(memory, pointer + 60, entity.lastVisibleOrigin());
    memory.writeBytes(pointer + 72, Arrays.copyOfRange(entity.bytes(), 44, 68));
    memory.writeBytes(pointer + 96, Arrays.copyOfRange(entity.bytes(), 68, 112));
  }

  private int areaInfo(QvmMemory memory, int area, int pointer) {
    var map = navigation().map();
    VmAbi.range(memory, pointer, 52);
    memory.fill(pointer, 52, 0);
    if (area <= 0 || area >= map.areas().size()) return 0;
    var settings = map.areaSettings().get(area);
    var geometry = map.areas().get(area);
    memory.writeInt(pointer, settings.contents());
    memory.writeInt(pointer + 4, settings.flags());
    memory.writeInt(pointer + 8, settings.presenceType());
    memory.writeInt(pointer + 12, settings.cluster());
    VmAbi.vector(memory, pointer + 16, geometry.min());
    VmAbi.vector(memory, pointer + 28, geometry.max());
    VmAbi.vector(memory, pointer + 40, geometry.center());
    return 1;
  }

  private void presence(QvmMemory memory, int type, int mins, int maxs) {
    VmAbi.range(memory, mins, 12);
    VmAbi.range(memory, maxs, 12);
    // Runtime presence dimensions are independent of the AAS compiler's stored expansion boxes.
    VmAbi.vector(memory, mins, new Vec3(-15, -15, -24));
    VmAbi.vector(memory, maxs, new Vec3(15, 15, type == 2 ? 32 : 8));
  }

  private int boxAreas(QvmMemory memory, int[] a) {
    capacity(a[3]);
    VmAbi.range(memory, a[2], a[3] * 4);
    if (a[3] == 0) return 0;
    var result =
        navigation().boxAreas(VmAbi.vector(memory, a[0]), VmAbi.vector(memory, a[1]), a[3]);
    if (!result.complete() && result.nodeVisits() >= 1_000_000)
      throw new IllegalStateException("AAS box query work budget exceeded");
    for (int i = 0; i < result.areas().size(); i++)
      memory.writeInt(a[2] + i * 4, result.areas().get(i));
    return result.areas().size();
  }

  private int traceAreas(QvmMemory memory, int[] a) {
    capacity(a[4]);
    VmAbi.range(memory, a[2], a[4] * 4);
    if (a[3] != 0) VmAbi.range(memory, a[3], a[4] * 12);
    if (a[4] == 0) return 0;
    var result =
        navigation().traceAreas(VmAbi.vector(memory, a[0]), VmAbi.vector(memory, a[1]), a[4]);
    if (!result.complete() && result.nodeVisits() >= 1_000_000)
      throw new IllegalStateException("AAS trace query work budget exceeded");
    for (int i = 0; i < result.spans().size(); i++) {
      var span = result.spans().get(i);
      memory.writeInt(a[2] + i * 4, span.area());
      if (a[3] != 0) VmAbi.vector(memory, a[3] + i * 12, span.entry());
    }
    return result.spans().size();
  }

  private int routingArea(int area, int enable) {
    var map = navigation().map();
    if (area <= 0 || area >= map.areas().size())
      throw new IllegalArgumentException("Invalid routing area");
    boolean before =
        !disabledAreas.contains(area) && (map.areaSettings().get(area).flags() & 8) == 0;
    if (enable < 0) return before ? 1 : 0;
    if (enable != 0 && (map.areaSettings().get(area).flags() & 8) != 0)
      throw new UnsupportedOperationException(
          "Enabling an AAS file-disabled area requires routing topology updates");
    if (enable == 0) disabledAreas.add(area);
    else disabledAreas.remove(area);
    return before ? 1 : 0;
  }

  private int travelTime(QvmMemory memory, int[] a) {
    return travelTime(a[0], VmAbi.vector(memory, a[1]), a[2], a[3]);
  }

  private int travelTime(int start, Vec3 origin, int goal, int flags) {
    return routeTimes()
        .travelTime(
            start, origin, goal, new TravelPolicy(flags, 6, TravelPolicy.Team.ANY, disabledAreas));
  }

  private AasRouteTimes routeTimes() {
    if (routeTimes == null) routeTimes = new AasRouteTimes(navigation().map());
    return routeTimes;
  }

  private AasMovementRoutes movementRoutes() {
    if (movementRoutes == null) movementRoutes = new AasMovementRoutes(routeTimes());
    return movementRoutes;
  }

  private int visiblePosition(QvmMemory memory, int[] a) {
    VmAbi.range(memory, a[4], 12);
    if (a[2] == 0) return 0;
    Vec3 origin = VmAbi.vector(memory, a[0]);
    Goal goal = goal(memory, a[2]);
    var policy = new TravelPolicy(a[3], 6, TravelPolicy.Team.ANY, disabledAreas);
    var target =
        new BotVisiblePosition(navigation().map(), movementRoutes(), traceWorld)
            .predict(origin, a[1], goal, policy);
    target.ifPresent(value -> VmAbi.vector(memory, a[4], value));
    return target.isPresent() ? 1 : 0;
  }

  private int movementView(QvmMemory memory, int[] a) {
    VmAbi.range(memory, a[4], 12);
    float lookAhead = f(a[3]);
    if (!Float.isFinite(lookAhead))
      throw new IllegalArgumentException("Nonfinite movement lookahead");
    if (a[1] == 0) return 0;
    Goal goal = goal(memory, a[1]);
    var state = movement().snapshot(a[0]);
    if (state.isEmpty() || state.get().input().isEmpty()) return 0;
    var snapshot = state.get();
    var history = snapshot.history();
    if (history.lastReachability() == 0 || lookAhead <= 0) return 0;
    if (movementView == null)
      movementView = new BotMovementView(navigation().map(), movementRoutes());
    var avoid = snapshot.reachAvoidance();
    var context =
        new AasMovementRoutes.Context(
            history.lastGoalArea(),
            history.lastArea(),
            time,
            avoid.reachability() == 0
                ? java.util.List.of()
                : java.util.List.of(
                    new AasMovementRoutes.AvoidReach(
                        avoid.reachability(), avoid.expiresAt(), avoid.tries())));
    var result =
        movementView.target(
            snapshot.input().orElseThrow().origin(),
            history.lastReachability(),
            goal,
            new TravelPolicy(a[2], 6, TravelPolicy.Team.ANY, disabledAreas),
            lookAhead,
            context);
    result.target().ifPresent(value -> VmAbi.vector(memory, a[4], value));
    return result.success() ? 1 : 0;
  }

  private int moveToGoal(QvmMemory memory, int[] a) {
    VmAbi.range(memory, a[0], MovementResult.BYTE_SIZE);
    if (a[2] == 0) throw new IllegalArgumentException("Movement goal is null");
    Goal goal = goal(memory, a[2]);
    var input =
        movement()
            .snapshot(a[1])
            .orElseThrow(() -> new IllegalArgumentException("Invalid movement state"))
            .input()
            .orElseThrow(() -> new IllegalStateException("Movement state is not initialized"));
    int client = input.client();
    if (client < 0 || client >= actions().maxClients())
      throw new IllegalArgumentException("Movement client outside action slots");
    if (Float.parseFloat(variables.getOrDefault("sv_step", "18")) != 18)
      throw new UnsupportedOperationException("Ground movement with nondefault step height");
    if (groundMoveToGoal == null) {
      var nav = navigation();
      var map = nav.map();
      var obstacles = new MovementObstacles(movementWorld);
      var ground =
          new GroundReachMovement(
              traceWorld,
              area -> map.areaSettings().get(area).presenceType(),
              area -> map.areaSettings().get(area).reachabilityCount(),
              obstacles::gapDistance);
      var obstruction =
          new MovementObstruction(
              traceWorld, area -> map.areaSettings().get(area).reachabilityCount());
      var bobbing =
          new BobbingPlatformMovement(moverQueries(), obstruction, obstacles::barrierJump);
      var jumpPad = new JumpPadMovement(obstruction);
      var teleport = new TeleportReachMovement(obstruction);
      var barrier = new BarrierReachMovement(obstruction);
      var liquid = new LiquidReachMovement(obstruction, () -> (random.nextInt() & 32767) / 32767f);
      var weaponJump = new WeaponJumpMovement();
      var runStart =
          new JumpRunStart(
              request ->
                  movementPredictor()
                      .predict(request)
                      .orElseThrow(
                          () ->
                              new UnsupportedOperationException(
                                  "Jump run-up prediction failed: " + request)));
      var jump = new JumpReachMovement(runStart::calculate, nav::pointArea);
      java.util.function.Supplier<LedgeReachMovement> ledge =
          () ->
              new LedgeReachMovement(
                  obstruction,
                  Float.parseFloat(variables.getOrDefault("phys_gravity", "800")),
                  Float.parseFloat(variables.getOrDefault("phys_maxvelocity", "320")));
      groundMoveToGoal =
          new GroundMoveToGoal(
              movement(),
              nav,
              reachabilityArea()::fuzzyArea,
              movementRoutes(),
              traceWorld,
              (origin, presence, entity) -> movementPredictor().onGround(origin, presence, entity),
              this::movingPlatform,
              ground::execute,
              ground::moveInGoalArea,
              Map.of(
                  4,
                  barrier::execute,
                  7,
                  (state, flags, area, reach) -> ledge.get().execute(state, flags, area, reach),
                  8,
                  liquid::execute,
                  9,
                  liquid::execute,
                  10,
                  teleport::execute,
                  18,
                  jumpPad::execute,
                  19,
                  bobbing::execute),
              Map.of(
                  4,
                  barrier::finish,
                  7,
                  (state, flags, area, reach) -> ledge.get().finish(state, flags, area, reach),
                  8,
                  liquid::execute,
                  9,
                  liquid::finish,
                  18,
                  jumpPad::finish,
                  19,
                  bobbing::finish),
              liquid::moveInGoalArea,
              Map.of(5, jump::execute, 12, weaponJump::execute, 13, weaponJump::execute),
              Map.of(5, jump::finish, 12, weaponJump::finish, 13, weaponJump::finish),
              this::bobbingPlatformReach);
    }
    var result =
        groundMoveToGoal.execute(
            a[1], goal, new TravelPolicy(a[3], 6, TravelPolicy.Team.ANY, disabledAreas), time);
    var bytes = ByteBuffer.allocate(MovementResult.BYTE_SIZE);
    result.writeTo(bytes, 0);
    memory.writeBytes(a[0], Arrays.copyOf(bytes.array(), result.writtenBytes()));
    actions().action(client, result.actionFlags() & ~(ActionFlags.JUMP | ActionFlags.DELAYED_JUMP));
    if ((result.actionFlags() & ActionFlags.JUMP) != 0) actions().jump(client);
    if ((result.actionFlags() & ActionFlags.DELAYED_JUMP) != 0) actions().delayedJump(client);
    result.view().ifPresent(view -> actions().view(client, view));
    result.weapon().ifPresent(weapon -> actions().selectWeapon(client, weapon));
    result
        .command()
        .ifPresent(command -> actions().move(client, command.direction(), command.speed()));
    return 0;
  }

  private AasMovementPredictor movementPredictor() {
    navigation();
    return new AasMovementPredictor(movementWorld, AasMovementPredictor.Settings.from(variables));
  }

  private int moveInDirection(QvmMemory memory, int[] a) {
    Vec3 direction = VmAbi.vector(memory, a[1]);
    if (Math.abs(direction.x()) > BotInput.MAX_DIRECTION_COMPONENT
        || Math.abs(direction.y()) > BotInput.MAX_DIRECTION_COMPONENT
        || Math.abs(direction.z()) > BotInput.MAX_DIRECTION_COMPONENT)
      throw new IllegalArgumentException("Movement direction outside action bounds");
    float speed = f(a[2]);
    if (!Float.isFinite(speed) || Math.abs(speed) > 1_000_000)
      throw new IllegalArgumentException("Invalid directional movement speed");
    var snapshot =
        movement()
            .snapshot(a[0])
            .orElseThrow(() -> new IllegalArgumentException("Invalid movement state"));
    var input =
        snapshot
            .input()
            .orElseThrow(() -> new IllegalStateException("Movement state is not initialized"));
    int client = input.client();
    if (client < 0 || client >= actions().maxClients())
      throw new IllegalArgumentException("Movement client outside action slots");
    var predictor = movementPredictor();
    var obstacles =
        new MovementObstacles(
            movementWorld,
            Float.parseFloat(variables.getOrDefault("sv_step", "18")),
            Float.parseFloat(variables.getOrDefault("sv_maxbarrier", "32")));
    var directional =
        new BotDirectionalMovement(
            new BotDirectionalMovement.Services() {
              @Override
              public boolean swimming(Vec3 origin) {
                return predictor.swimming(origin);
              }

              @Override
              public boolean onGround(Vec3 origin, int presence, int entity) {
                return predictor.onGround(origin, presence, entity);
              }

              @Override
              public boolean barrierJump(MovementInit state, Vec3 requested, float requestedSpeed) {
                return obstacles.barrierJump(state, requested, requestedSpeed);
              }

              @Override
              public float gapDistance(Vec3 origin, Vec3 requested, int entity) {
                return obstacles.gapDistance(origin, requested, entity);
              }

              @Override
              public java.util.Optional<AasMovementPredictor.Prediction> predict(
                  AasMovementPredictor.Request request) {
                return predictor.predict(request);
              }
            });
    var result = directional.move(input, snapshot.movementFlags(), direction, speed, a[3]);
    movement().updateMovementFlags(a[0], result.movementFlags());
    if (result.crouch()) actions().crouch(client);
    if (result.jump()) actions().jump(client);
    result.motion().ifPresent(motion -> actions().move(client, motion.direction(), motion.speed()));
    return result.accepted() ? 1 : 0;
  }

  private MoverQueries moverQueries() {
    return new MoverQueries(
        traceWorld,
        model -> {
          if (model < 0 || model >= bsp.models().size())
            throw new IllegalArgumentException("Invalid mover BSP model " + model);
          var bounds = bsp.models().get(model).bounds();
          return new MoverQueries.ModelBounds(bounds.min(), bounds.max());
        },
        number -> {
          if (number < 0 || number >= entities.length || entities[number] == null)
            return java.util.Optional.empty();
          // Mover lookup observes retained metadata after unlinking and frame invalidation.
          var bytes =
              ByteBuffer.wrap(entities[number].bytes()).order(java.nio.ByteOrder.LITTLE_ENDIAN);
          return java.util.Optional.of(
              new MoverQueries.Entity(bytes.getInt(0), bytes.getInt(76), vector(bytes, 8)));
        },
        entities.length);
  }

  private int bobbingPlatformReach(int entity, int previousReach) {
    if (entity < 0 || entity >= entities.length || entities[entity] == null)
      throw new IllegalArgumentException("Missing platform contact entity " + entity);
    int model =
        ByteBuffer.wrap(entities[entity].bytes())
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .getInt(76);
    boolean bobbing = false;
    for (var value : bsp.entities()) {
      if (!"func_bobbing".equalsIgnoreCase(value.get("classname"))) continue;
      if (("*" + model).equals(value.get("model"))) {
        bobbing = true;
        break;
      }
    }
    if (!bobbing)
      throw new UnsupportedOperationException("Move-to-goal while standing on an elevator");
    var reaches = navigation().map().reachabilities();
    if (previousReach > 0 && previousReach < reaches.size()) {
      var cached = reaches.get(previousReach);
      if (cached.baseTravelType() == 19 && (cached.face() & 65535) == model) return previousReach;
    }
    for (int i = 1; i < reaches.size(); i++) {
      var reach = reaches.get(i);
      if (reach.baseTravelType() == 19 && (reach.face() & 65535) == model) return i;
    }
    return 0;
  }

  private boolean movingPlatform(int entity) {
    if (entity < 0 || entity >= entities.length || entities[entity] == null) return false;
    int model =
        ByteBuffer.wrap(entities[entity].bytes())
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .getInt(76);
    if (model == 0) return false;
    for (var value : bsp.entities()) {
      if (!"func_plat".equalsIgnoreCase(value.get("classname"))
          && !"func_bobbing".equalsIgnoreCase(value.get("classname"))) continue;
      String reference = value.getOrDefault("model", "");
      if (!reference.startsWith("*")) continue;
      try {
        if (Integer.parseInt(reference.substring(1)) == model) return true;
      } catch (NumberFormatException malformed) {
        // A malformed non-model key cannot describe the updated entity's valid inline model.
      }
    }
    return false;
  }

  private boolean swimming(Vec3 origin) {
    var sample = new Vec3((float) origin.x(), (float) origin.y(), (float) origin.z() - 2);
    return (host.pointContents(sample) & 56) != 0;
  }

  private int reachableArea(Vec3 origin, int client) {
    return reachabilityArea().reachableArea(origin, client);
  }

  private AasReachabilityArea reachabilityArea() {
    if (reachabilityArea == null) {
      reachabilityArea =
          new AasReachabilityArea(
              navigation(),
              bsp.entities(),
              traceWorld,
              number -> {
                if (number < 0 || number >= entities.length || entities[number] == null)
                  return java.util.Optional.empty();
                // Native model metadata survives both frame invalidation and explicit unlinking.
                var bytes =
                    ByteBuffer.wrap(entities[number].bytes())
                        .order(java.nio.ByteOrder.LITTLE_ENDIAN);
                return java.util.Optional.of(new AasReachabilityArea.Entity(bytes.getInt(76)));
              });
    }
    return reachabilityArea;
  }

  private int chooseItem(QvmMemory memory, int call, int[] a) throws IOException {
    Vec3 origin = VmAbi.vector(memory, a[1]);
    VmAbi.range(memory, a[2], 256 * 4);
    int[] inventory = new int[256];
    for (int i = 0; i < inventory.length; i++) inventory[i] = memory.readInt(a[2] + i * 4);
    Goal longTerm = call == 536 && a[4] != 0 ? goal(memory, a[4]) : null;
    float maxTime = call == 536 ? f(a[5]) : 0;
    if (!Float.isFinite(maxTime)) throw new IllegalArgumentException("Nonfinite nearby goal time");
    if (goals().weights(a[0]).isEmpty()) return 0;
    if (itemSelector == null) {
      var registry = items();
      itemSelector =
          new BotItemSelector(
              goals(),
              registry::items,
              new BotItemSelector.Routing() {
                @Override
                public int reachableArea(Vec3 point, int client) {
                  return BotlibHost.this.reachableArea(point, client);
                }

                @Override
                public boolean hasReachability(int area) {
                  var map = navigation().map();
                  return area > 0
                      && area < map.areaSettings().size()
                      && map.areaSettings().get(area).reachabilityCount() != 0;
                }

                @Override
                public int travelTime(int start, Vec3 point, int goal, int flags) {
                  return BotlibHost.this.travelTime(start, point, goal, flags);
                }
              },
              (config, index, values) -> config.evaluateUndecided(index, values, random::nextInt),
              () -> Integer.parseInt(variables.getOrDefault("g_gametype", "0")),
              () -> Double.parseDouble(variables.getOrDefault("droppedweight", "1000")));
    }
    return (call == 535
            ? itemSelector.chooseLongTerm(a[0], origin, inventory, a[3])
            : itemSelector.chooseNearby(a[0], origin, inventory, a[3], longTerm, maxTime))
        ? 1
        : 0;
  }

  private String epair(int entity, String key) {
    if (entity < 1 || entity > bsp.entities().size()) return null;
    return bsp.entities().get(entity - 1).get(key);
  }

  private int vectorEpair(QvmMemory memory, int[] a) {
    String value = epair(a[0], text(memory, a[1]));
    Vec3 vector = ZERO;
    if (value != null) {
      String[] parts = value.trim().split("\\s+");
      if (parts.length != 3) throw new IllegalArgumentException("Malformed BSP vector epair");
      vector =
          new Vec3(
              Float.parseFloat(parts[0]), Float.parseFloat(parts[1]), Float.parseFloat(parts[2]));
    }
    VmAbi.vector(memory, a[2], vector);
    return value == null ? 0 : 1;
  }

  private int source(String path) {
    try {
      int handle = scripts.load(path);
      sourceHandles.add(handle);
      return handle;
    } catch (IOException failure) {
      output.accept("Botlib source: " + failure.getMessage() + "\n");
      return 0;
    }
  }

  private int token(QvmMemory memory, int handle, int pointer) {
    VmAbi.range(memory, pointer, 1040);
    var token = scripts.read(handle);
    if (token.isEmpty()) return 0;
    memory.fill(pointer, 1040, 0);
    var value = token.get();
    memory.writeInt(pointer, value.type());
    memory.writeInt(pointer + 4, value.subtype());
    memory.writeInt(pointer + 8, value.intValue());
    memory.writeFloat(pointer + 12, value.floatValue());
    VmAbi.string(memory, pointer + 16, value.text(), 1024);
    return 1;
  }

  private UserCommand readCommand(QvmMemory memory, int pointer) {
    VmAbi.range(memory, pointer, 24);
    boolean retail = abi == GameAbi.RETAIL_1999;
    int angles = retail ? 8 : 4, movement = retail ? 20 : 21;
    return new UserCommand(
        memory.readInt(pointer),
        memory.readInt(pointer + angles),
        memory.readInt(pointer + angles + 4),
        memory.readInt(pointer + angles + 8),
        retail ? memory.readUnsignedByte(pointer + 4) : memory.readInt(pointer + 16),
        memory.readUnsignedByte(pointer + (retail ? 5 : 20)),
        (byte) memory.readUnsignedByte(pointer + movement),
        (byte) memory.readUnsignedByte(pointer + movement + 1),
        (byte) memory.readUnsignedByte(pointer + movement + 2));
  }

  private static void capacity(int capacity) {
    if (capacity < 0 || capacity > 65536)
      throw new IllegalArgumentException("AAS result capacity exceeds limit");
  }

  private void requireSetup() {
    if (!setup) throw new IllegalStateException("Bot library is not set up");
  }

  private AasNavigation navigation() {
    requireSetup();
    if (navigation == null) throw new IllegalStateException("No AAS map loaded");
    return navigation;
  }

  private int itemWeights(int handle, String path) {
    if (goals().snapshot(handle).isEmpty()) return 9;
    try {
      goals.weights(handle, WeightConfig.load(scripts, path));
      return 0;
    } catch (IOException failure) {
      output.accept("Item weights: " + failure.getMessage() + "\n");
      return 9;
    }
  }

  private static Goal goal(QvmMemory memory, int pointer) {
    VmAbi.range(memory, pointer, Goal.BYTE_SIZE);
    return Goal.readFrom(ByteBuffer.wrap(memory.readBytes(pointer, Goal.BYTE_SIZE)), 0);
  }

  private ItemRegistry items() throws IOException {
    if (items == null) {
      var config = ItemConfig.load(scripts, variables.getOrDefault("itemconfig", "items.c"));
      var placement =
          new ItemPlacement(
              navigation(),
              traceWorld,
              new JumpPadItemAreas(
                  navigation(),
                  bsp,
                  movementWorld,
                  () -> AasMovementPredictor.Settings.from(variables),
                  output),
              line -> output.accept(line + "\n"));
      var registry = new ItemRegistry(config, placement, output);
      registry.initialize(bsp.entities());
      itemPlacement = placement;
      items = registry;
    }
    return items;
  }

  private BotMapGoals mapGoals() {
    if (mapGoals == null) {
      var loaded = new BotMapGoals(navigation()::pointArea, output);
      loaded.initialize(bsp.entities());
      mapGoals = loaded;
    }
    return mapGoals;
  }

  private void updateItems() throws IOException {
    var registry = items();
    var visible = new java.util.ArrayList<ItemRegistry.WorldEntity>();
    for (int number = 1; number < entities.length; number++) {
      var entity = entities[number];
      if (entity == null || entity.frame() != frame) continue;
      var bytes = ByteBuffer.wrap(entity.bytes()).order(java.nio.ByteOrder.LITTLE_ENDIAN);
      var origin = new Vec3(bytes.getFloat(8), bytes.getFloat(12), bytes.getFloat(16));
      visible.add(
          new ItemRegistry.WorldEntity(
              number,
              bytes.getInt(0),
              bytes.getInt(4),
              bytes.getInt(76),
              origin,
              entity.lastVisibleOrigin()));
    }
    registry.update(time, visible, itemPlacement);
  }

  private double lastEntityUpdate(int entity) {
    if (entity < 0 || entity >= entities.length)
      throw new IllegalArgumentException("Invalid goal entity number");
    return entities[entity] == null ? 0 : entities[entity].updated();
  }

  private java.util.List<String> chatVariables(QvmMemory memory, int[] arguments, int first) {
    var variables = new java.util.ArrayList<String>(8);
    for (int i = 0; i < 8; i++) {
      int pointer = arguments[first + i];
      variables.add(pointer == 0 ? null : text(memory, pointer));
    }
    return java.util.Collections.unmodifiableList(variables);
  }

  private int chatFile(int handle, String path, String name) throws IOException {
    var provider = chat();
    try {
      return provider.load(handle, path, name) ? 0 : 8;
    } catch (IOException failure) {
      output.accept("Bot chat: " + failure.getMessage() + "\n");
      return 8;
    }
  }

  private int chatTextBytes() {
    return abi == GameAbi.RETAIL_1999 ? 150 : 256;
  }

  private String chatText(String value) {
    return value.substring(0, Math.min(value.length(), chatTextBytes() - 1));
  }

  private ByteBuffer readChatMatch(QvmMemory memory, int pointer) {
    boolean retail = abi == GameAbi.RETAIL_1999;
    int size = retail ? 224 : ChatMatcher.BYTE_SIZE;
    VmAbi.range(memory, pointer, size);
    byte[] guest = memory.readBytes(pointer, size);
    if (!retail) return ByteBuffer.wrap(guest);
    var canonical = ByteBuffer.allocate(ChatMatcher.BYTE_SIZE);
    canonical.put(0, guest, 0, 150);
    canonical.put(256, guest, 152, 72);
    return canonical;
  }

  private void writeChatMatch(QvmMemory memory, int pointer, ByteBuffer match) {
    if (abi == GameAbi.RETAIL_1999) {
      memory.writeBytes(pointer, Arrays.copyOfRange(match.array(), 0, 150));
      memory.writeBytes(pointer + 152, Arrays.copyOfRange(match.array(), 256, 328));
    } else memory.writeBytes(pointer, match.array());
  }

  private BotChat chat() throws IOException {
    requireSetup();
    if (chat == null) {
      var provider = new BotChat(scripts, () -> time, random);
      try {
        provider.setup();
      } catch (IOException | RuntimeException failure) {
        provider.close();
        throw failure;
      }
      chat = provider;
    }
    return chat;
  }

  private BotMovement movement() {
    requireSetup();
    return movement;
  }

  private BotWeapons weapons() {
    requireSetup();
    if (weapons == null) {
      var provider = new BotWeapons(scripts, output);
      int result = provider.setup(variables.getOrDefault("weaponconfig", "weapons.c"));
      if (result != 0) {
        provider.close();
        throw new IllegalStateException("Bot weapon setup failed with error " + result);
      }
      weapons = provider;
    }
    return weapons;
  }

  private BotGoals goals() {
    requireSetup();
    return goals;
  }

  private BotCharacters characters() {
    requireSetup();
    return characters;
  }

  private ElementaryActions actions() {
    requireSetup();
    return actions;
  }

  private static String mapBase(String path) {
    String name = new VirtualPath(path).value();
    if (name.startsWith("maps/")) name = name.substring(5);
    if (name.endsWith(".bsp") || name.endsWith(".aas")) name = name.substring(0, name.length() - 4);
    if (name.contains("/")) throw new IllegalArgumentException("Invalid botlib map name");
    return name;
  }

  private static String text(QvmMemory memory, int address) {
    return address == 0 ? "" : memory.readCString(address, 8192);
  }

  private static float f(int bits) {
    return Float.intBitsToFloat(bits);
  }

  private static UnsupportedOperationException unsupported(int call, String feature) {
    return new UnsupportedOperationException(
        "Botlib syscall " + call + ": " + feature + " is not implemented");
  }

  @Override
  public void close() {
    if (closed) return;
    shutdown();
    scripts.close();
    closed = true;
  }
}
