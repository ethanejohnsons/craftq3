package dev.bluevista.craftq3.engine.game.bot;

import static dev.bluevista.craftq3.engine.game.bot.BotStructs.*;
import static dev.bluevista.craftq3.engine.game.bot.BotlibAdapter.ARG0;
import static dev.bluevista.craftq3.engine.game.bot.BotlibAdapter.NO_CLIENT;
import static dev.bluevista.craftq3.engine.game.bot.BotlibAdapter.run;
import static dev.bluevista.craftq3.engine.game.bot.BotlibAdapter.text;
import static dev.bluevista.craftq3.engine.vmabi.BotlibImport.*;

import dev.bluevista.craftq3.engine.botlib.BotLib;
import dev.bluevista.craftq3.engine.botlib.goal.Goal;
import dev.bluevista.craftq3.engine.botlib.movement.MovementResult;
import dev.bluevista.craftq3.engine.vm.QvmInstance;
import dev.bluevista.craftq3.engine.vm.QvmMemory;
import dev.bluevista.craftq3.engine.vmabi.Bindings;
import dev.bluevista.craftq3.engine.vmabi.service.GuestText;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The botlib AI imports (500 to 574 and the script sources 578 to 581): characters, chat, goals,
 * movement and weapons, each one facade call.
 */
final class BotAiBindings {
  /** {@code MAX_QPATH}: the file name buffer of {@code PC_SourceFileAndLine}. */
  private static final int SOURCE_NAME_BYTES = 64;

  private final BotlibAdapter adapter;

  BotAiBindings(BotlibAdapter adapter) {
    this.adapter = adapter;
  }

  private BotLib lib() {
    return adapter.botlib();
  }

  private static List<String> variables(QvmInstance vm, int[] a, int first) {
    List<String> values = new ArrayList<>(8);
    for (int i = 0; i < 8; i++) values.add(a[first + i] == 0 ? null : text(vm, a[first + i]));
    return values;
  }

  /** Whether {@code area} is an area of the loaded AAS file (area 0 is the solid placeholder). */
  private boolean validArea(int area) {
    return lib().navigation().map(n -> area > 0 && area < n.map().areas().size()).orElse(false);
  }

  private static int goalOut(QvmMemory m, int address, Optional<Goal> goal) {
    if (goal.isEmpty()) return 0;
    goal(m, address, goal.get());
    return 1;
  }

  void bind(Bindings b) {
    bindCharactersAndChat(b);
    bindGoals(b);
    bindMovementAndWeapons(b);
    bindSources(b);
  }

  private void bindCharactersAndChat(Bindings b) {
    var chat = adapter.chatOwner();
    adapter.bind(
        b,
        BOTLIB_AI_LOAD_CHARACTER,
        NO_CLIENT,
        (vm, a) -> lib().loadCharacter(text(vm, a[0]), f(a[1])));
    adapter.bind(
        b, BOTLIB_AI_FREE_CHARACTER, NO_CLIENT, (vm, a) -> run(() -> lib().freeCharacter(a[0])));
    adapter.bind(
        b,
        BOTLIB_AI_CHARACTERISTIC_FLOAT,
        NO_CLIENT,
        (vm, a) -> bits(lib().characteristicFloat(a[0], a[1])));
    adapter.bind(
        b,
        BOTLIB_AI_CHARACTERISTIC_BFLOAT,
        NO_CLIENT,
        (vm, a) -> bits(lib().characteristicBFloat(a[0], a[1], f(a[2]), f(a[3]))));
    adapter.bind(
        b,
        BOTLIB_AI_CHARACTERISTIC_INTEGER,
        NO_CLIENT,
        (vm, a) -> lib().characteristicInteger(a[0], a[1]));
    adapter.bind(
        b,
        BOTLIB_AI_CHARACTERISTIC_BINTEGER,
        NO_CLIENT,
        (vm, a) -> lib().characteristicBInteger(a[0], a[1], a[2], a[3]));
    adapter.bind(
        b,
        BOTLIB_AI_CHARACTERISTIC_STRING,
        NO_CLIENT,
        (vm, a) -> {
          GuestText.write(vm.memory(), a[2], lib().characteristicString(a[0], a[1], a[3]), a[3]);
          return 0;
        });
    adapter.bind(b, BOTLIB_AI_ALLOC_CHAT_STATE, NO_CLIENT, (vm, a) -> lib().allocChatState());
    adapter.bind(
        b,
        BOTLIB_AI_FREE_CHAT_STATE,
        chat,
        (vm, a) -> {
          lib().freeChatState(a[0]);
          if (a[0] >= 0 && a[0] < adapter.chatStateClient.length)
            adapter.chatStateClient[a[0]] = -1;
          return 0;
        });
    adapter.bind(
        b,
        BOTLIB_AI_QUEUE_CONSOLE_MESSAGE,
        chat,
        (vm, a) -> run(() -> lib().queueConsoleMessage(a[0], a[1], text(vm, a[2]))));
    adapter.bind(
        b,
        BOTLIB_AI_REMOVE_CONSOLE_MESSAGE,
        chat,
        (vm, a) -> run(() -> lib().removeConsoleMessage(a[0], a[1])));
    adapter.bind(
        b,
        BOTLIB_AI_NEXT_CONSOLE_MESSAGE,
        chat,
        (vm, a) -> {
          var message = lib().nextConsoleMessage(a[0]);
          if (message.isEmpty()) return 0;
          consoleMessage(vm.memory(), a[1], adapter.profile(), message.get());
          return message.get().id();
        });
    adapter.bind(b, BOTLIB_AI_NUM_CONSOLE_MESSAGE, chat, (vm, a) -> lib().numConsoleMessages(a[0]));
    adapter.bind(
        b,
        BOTLIB_AI_INITIAL_CHAT,
        chat,
        (vm, a) -> run(() -> lib().initialChat(a[0], text(vm, a[1]), a[2], variables(vm, a, 3))));
    adapter.bind(
        b,
        BOTLIB_AI_NUM_INITIAL_CHATS,
        chat,
        (vm, a) -> lib().numInitialChats(a[0], text(vm, a[1])));
    adapter.bind(
        b,
        BOTLIB_AI_REPLY_CHAT,
        chat,
        (vm, a) -> lib().replyChat(a[0], text(vm, a[1]), a[2], a[3], variables(vm, a, 4)) ? 1 : 0);
    adapter.bind(b, BOTLIB_AI_CHAT_LENGTH, chat, (vm, a) -> lib().chatLength(a[0]));
    adapter.bind(
        b, BOTLIB_AI_ENTER_CHAT, chat, (vm, a) -> run(() -> lib().enterChat(a[0], a[1], a[2])));
    adapter.bind(
        b,
        BOTLIB_AI_GET_CHAT_MESSAGE,
        chat,
        (vm, a) -> {
          GuestText.write(vm.memory(), a[1], lib().getChatMessage(a[0], a[2]), a[2]);
          return 0;
        });
    adapter.bind(
        b,
        BOTLIB_AI_STRING_CONTAINS,
        NO_CLIENT,
        (vm, a) -> lib().stringContains(text(vm, a[0]), text(vm, a[1]), a[2] != 0));
    adapter.bind(
        b,
        BOTLIB_AI_FIND_MATCH,
        NO_CLIENT,
        (vm, a) -> {
          byte[] match = match(vm.memory(), a[1], adapter.profile());
          boolean found = lib().findMatch(text(vm, a[0]), match, a[2]);
          match(vm.memory(), a[1], adapter.profile(), match);
          return found ? 1 : 0;
        });
    adapter.bind(
        b,
        BOTLIB_AI_MATCH_VARIABLE,
        NO_CLIENT,
        (vm, a) -> {
          byte[] match = match(vm.memory(), a[0], adapter.profile());
          GuestText.write(vm.memory(), a[2], lib().matchVariable(match, a[1], a[3]), a[3]);
          return 0;
        });
    adapter.bind(
        b,
        BOTLIB_AI_UNIFY_WHITE_SPACES,
        NO_CLIENT,
        (vm, a) -> {
          String old = text(vm, a[0]);
          GuestText.write(vm.memory(), a[0], lib().unifyWhiteSpaces(old), old.length() + 1);
          return 0;
        });
    adapter.bind(
        b,
        BOTLIB_AI_REPLACE_SYNONYMS,
        NO_CLIENT,
        (vm, a) -> {
          String old = text(vm, a[0]);
          String replaced = lib().replaceSynonyms(old, a[1]);
          GuestText.write(vm.memory(), a[0], replaced, adapter.profile().botMessageBytes());
          return 0;
        });
    adapter.bind(
        b,
        BOTLIB_AI_LOAD_CHAT_FILE,
        chat,
        (vm, a) -> lib().loadChatFile(a[0], text(vm, a[1]), text(vm, a[2])));
    adapter.bind(
        b, BOTLIB_AI_SET_CHAT_GENDER, chat, (vm, a) -> run(() -> lib().setChatGender(a[0], a[1])));
    adapter.bind(
        b,
        BOTLIB_AI_SET_CHAT_NAME,
        a -> a[2],
        (vm, a) -> {
          lib().setChatName(a[0], text(vm, a[1]), a[2]);
          if (a[0] >= 0 && a[0] < adapter.chatStateClient.length)
            adapter.chatStateClient[a[0]] = a[2];
          return 0;
        });
  }

  private void bindGoals(Bindings b) {
    var gs = adapter.goalOwner();
    adapter.bind(
        b, BOTLIB_AI_RESET_GOAL_STATE, gs, (vm, a) -> run(() -> lib().resetGoalState(a[0])));
    adapter.bind(
        b, BOTLIB_AI_RESET_AVOID_GOALS, gs, (vm, a) -> run(() -> lib().resetAvoidGoals(a[0])));
    adapter.bind(
        b,
        BOTLIB_AI_REMOVE_FROM_AVOID_GOALS,
        gs,
        (vm, a) -> run(() -> lib().removeFromAvoidGoals(a[0], a[1])));
    adapter.bind(
        b,
        BOTLIB_AI_PUSH_GOAL,
        gs,
        (vm, a) -> run(() -> lib().pushGoal(a[0], goal(vm.memory(), a[1]))));
    adapter.bind(b, BOTLIB_AI_POP_GOAL, gs, (vm, a) -> run(() -> lib().popGoal(a[0])));
    adapter.bind(
        b, BOTLIB_AI_EMPTY_GOAL_STACK, gs, (vm, a) -> run(() -> lib().emptyGoalStack(a[0])));
    adapter.bind(
        b, BOTLIB_AI_DUMP_AVOID_GOALS, gs, (vm, a) -> run(() -> lib().dumpAvoidGoals(a[0])));
    adapter.bind(b, BOTLIB_AI_DUMP_GOAL_STACK, gs, (vm, a) -> run(() -> lib().dumpGoalStack(a[0])));
    adapter.bind(
        b,
        BOTLIB_AI_GOAL_NAME,
        NO_CLIENT,
        (vm, a) -> {
          GuestText.write(vm.memory(), a[1], lib().goalName(a[0]), a[2]);
          return 0;
        });
    adapter.bind(
        b,
        BOTLIB_AI_GET_TOP_GOAL,
        gs,
        (vm, a) -> goalOut(vm.memory(), a[1], lib().getTopGoal(a[0])));
    adapter.bind(
        b,
        BOTLIB_AI_GET_SECOND_GOAL,
        gs,
        (vm, a) -> goalOut(vm.memory(), a[1], lib().getSecondGoal(a[0])));
    adapter.bind(
        b,
        BOTLIB_AI_CHOOSE_LTG_ITEM,
        gs,
        (vm, a) ->
            lib().chooseLtgItem(a[0], vec(vm.memory(), a[1]), inventory(vm.memory(), a[2]), a[3])
                ? 1
                : 0);
    adapter.bind(
        b,
        BOTLIB_AI_CHOOSE_NBG_ITEM,
        gs,
        (vm, a) -> {
          QvmMemory m = vm.memory();
          Goal ltg = a[4] == 0 ? null : goal(m, a[4]);
          return lib().chooseNbgItem(a[0], vec(m, a[1]), inventory(m, a[2]), a[3], ltg, f(a[5]))
              ? 1
              : 0;
        });
    adapter.bind(
        b,
        BOTLIB_AI_TOUCHING_GOAL,
        NO_CLIENT,
        (vm, a) -> lib().touchingGoal(vec(vm.memory(), a[0]), goal(vm.memory(), a[1])) ? 1 : 0);
    adapter.bind(
        b,
        BOTLIB_AI_ITEM_GOAL_IN_VIS_BUT_NOT_VISIBLE,
        ARG0,
        (vm, a) -> {
          QvmMemory m = vm.memory();
          return lib().itemGoalInVisButNotVisible(a[0], vec(m, a[1]), vec(m, a[2]), goal(m, a[3]))
              ? 1
              : 0;
        });
    adapter.bind(
        b,
        BOTLIB_AI_GET_LEVEL_ITEM_GOAL,
        NO_CLIENT,
        (vm, a) -> {
          var found = lib().getLevelItemGoal(a[0], text(vm, a[1]));
          if (found.isEmpty()) return -1;
          goal(vm.memory(), a[2], found.get().goal());
          return found.get().number();
        });
    adapter.bind(
        b, BOTLIB_AI_AVOID_GOAL_TIME, gs, (vm, a) -> bits(lib().avoidGoalTime(a[0], a[1])));
    adapter.bind(
        b,
        BOTLIB_AI_SET_AVOID_GOAL_TIME,
        gs,
        (vm, a) -> run(() -> lib().setAvoidGoalTime(a[0], a[1], f(a[2]))));
    adapter.bind(
        b, BOTLIB_AI_INIT_LEVEL_ITEMS, NO_CLIENT, (vm, a) -> run(() -> lib().initLevelItems()));
    adapter.bind(
        b,
        BOTLIB_AI_UPDATE_ENTITY_ITEMS,
        NO_CLIENT,
        (vm, a) -> run(() -> lib().updateEntityItems()));
    adapter.bind(
        b, BOTLIB_AI_LOAD_ITEM_WEIGHTS, gs, (vm, a) -> lib().loadItemWeights(a[0], text(vm, a[1])));
    adapter.bind(
        b, BOTLIB_AI_FREE_ITEM_WEIGHTS, gs, (vm, a) -> run(() -> lib().freeItemWeights(a[0])));
    adapter.bind(
        b,
        BOTLIB_AI_INTERBREED_GOAL_FUZZY_LOGIC,
        NO_CLIENT,
        (vm, a) -> run(() -> lib().interbreedGoalFuzzyLogic(a[0], a[1], a[2])));
    adapter.bind(
        b,
        BOTLIB_AI_SAVE_GOAL_FUZZY_LOGIC,
        gs,
        (vm, a) -> run(() -> lib().saveGoalFuzzyLogic(a[0], text(vm, a[1]))));
    adapter.bind(
        b,
        BOTLIB_AI_MUTATE_GOAL_FUZZY_LOGIC,
        gs,
        (vm, a) -> run(() -> lib().mutateGoalFuzzyLogic(a[0], f(a[1]))));
    adapter.bind(
        b,
        BOTLIB_AI_ALLOC_GOAL_STATE,
        ARG0,
        (vm, a) -> {
          int state = lib().allocGoalState(a[0]);
          if (state >= 0 && state < adapter.goalStateClient.length)
            adapter.goalStateClient[state] = a[0];
          return state;
        });
    adapter.bind(
        b,
        BOTLIB_AI_FREE_GOAL_STATE,
        gs,
        (vm, a) -> {
          lib().freeGoalState(a[0]);
          if (a[0] >= 0 && a[0] < adapter.goalStateClient.length)
            adapter.goalStateClient[a[0]] = -1;
          return 0;
        });
    adapter.bind(
        b,
        BOTLIB_AI_GET_NEXT_CAMP_SPOT_GOAL,
        NO_CLIENT,
        (vm, a) -> {
          var found = lib().getNextCampSpotGoal(a[0]);
          if (found.isEmpty()) return 0;
          goal(vm.memory(), a[1], found.get().goal());
          return found.get().next();
        });
    adapter.bind(
        b,
        BOTLIB_AI_GET_MAP_LOCATION_GOAL,
        NO_CLIENT,
        (vm, a) -> goalOut(vm.memory(), a[1], lib().getMapLocationGoal(text(vm, a[0]))));
    adapter.bind(
        b,
        BOTLIB_AI_GENETIC_PARENTS_AND_CHILD_SELECTION,
        NO_CLIENT,
        (vm, a) -> {
          QvmMemory m = vm.memory();
          int count = Math.max(0, Math.min(a[0], 256));
          float[] ranks = new float[count];
          for (int i = 0; i < count; i++) ranks[i] = m.readFloat(a[1] + 4 * i);
          var selection = lib().geneticParentsAndChildSelection(ranks);
          if (selection.isEmpty()) return 0;
          m.writeInt(a[2], selection.get().parent1());
          m.writeInt(a[3], selection.get().parent2());
          m.writeInt(a[4], selection.get().child());
          return 1;
        });
  }

  private void bindMovementAndWeapons(Bindings b) {
    var ms = adapter.moveOwner();
    adapter.bind(
        b, BOTLIB_AI_RESET_MOVE_STATE, ms, (vm, a) -> run(() -> lib().resetMoveState(a[0])));
    adapter.bind(
        b,
        BOTLIB_AI_MOVE_TO_GOAL,
        a -> BotlibAdapter.owner(adapter.moveStateClient, a[1]),
        (vm, a) -> {
          QvmMemory m = vm.memory();
          Goal goal = a[2] == 0 ? null : goal(m, a[2]);
          if (goal != null && !validArea(goal.area())) {
            // The original finds no reachability towards an area outside the AAS file and fails
            // the move; Q3Rally passes such goals.
            moveResult(m, a[0], MovementResult.failed(0), MOVE_RESULT_PREFIX_BYTES);
            return 0;
          }
          var output = lib().moveToGoal(a[1], goal, a[3]);
          moveResult(m, a[0], output.result(), output.writtenBytes());
          return 0;
        });
    adapter.bind(
        b,
        BOTLIB_AI_MOVE_IN_DIRECTION,
        ms,
        (vm, a) -> lib().moveInDirection(a[0], vec(vm.memory(), a[1]), f(a[2]), a[3]) ? 1 : 0);
    adapter.bind(
        b, BOTLIB_AI_RESET_AVOID_REACH, ms, (vm, a) -> run(() -> lib().resetAvoidReach(a[0])));
    adapter.bind(
        b,
        BOTLIB_AI_RESET_LAST_AVOID_REACH,
        ms,
        (vm, a) -> run(() -> lib().resetLastAvoidReach(a[0])));
    adapter.bind(
        b,
        BOTLIB_AI_REACHABILITY_AREA,
        a -> a[1],
        (vm, a) -> lib().reachabilityArea(vec(vm.memory(), a[0]), a[1]));
    adapter.bind(
        b,
        BOTLIB_AI_MOVEMENT_VIEW_TARGET,
        ms,
        (vm, a) -> {
          QvmMemory m = vm.memory();
          var target =
              lib().movementViewTarget(a[0], a[1] == 0 ? null : goal(m, a[1]), a[2], f(a[3]));
          if (target.isEmpty()) return 0;
          vec(m, a[4], target.get());
          return 1;
        });
    adapter.bind(
        b,
        BOTLIB_AI_PREDICT_VISIBLE_POSITION,
        NO_CLIENT,
        (vm, a) -> {
          QvmMemory m = vm.memory();
          var target =
              lib()
                  .predictVisiblePosition(
                      vec(m, a[0]), a[1], a[2] == 0 ? null : goal(m, a[2]), a[3]);
          if (target.isEmpty()) return 0;
          vec(m, a[4], target.get());
          return 1;
        });
    adapter.bind(b, BOTLIB_AI_ALLOC_MOVE_STATE, NO_CLIENT, (vm, a) -> lib().allocMoveState());
    adapter.bind(
        b,
        BOTLIB_AI_FREE_MOVE_STATE,
        ms,
        (vm, a) -> {
          lib().freeMoveState(a[0]);
          if (a[0] >= 0 && a[0] < adapter.moveStateClient.length)
            adapter.moveStateClient[a[0]] = -1;
          return 0;
        });
    adapter.bind(
        b,
        BOTLIB_AI_INIT_MOVE_STATE,
        ms,
        (vm, a) -> {
          var init = initMove(vm.memory(), a[1]);
          if (a[0] >= 0 && a[0] < adapter.moveStateClient.length)
            adapter.moveStateClient[a[0]] = init.client();
          lib().initMoveState(a[0], init);
          return 0;
        });
    adapter.bind(
        b,
        BOTLIB_AI_ADD_AVOID_SPOT,
        ms,
        (vm, a) -> run(() -> lib().addAvoidSpot(a[0], vec(vm.memory(), a[1]), f(a[2]), a[3])));
    adapter.bind(
        b,
        BOTLIB_AI_CHOOSE_BEST_FIGHT_WEAPON,
        NO_CLIENT,
        (vm, a) -> lib().chooseBestFightWeapon(a[0], inventory(vm.memory(), a[1])));
    adapter.bind(
        b,
        BOTLIB_AI_GET_WEAPON_INFO,
        NO_CLIENT,
        (vm, a) -> {
          weaponInfo(vm.memory(), a[2], lib().getWeaponInfo(a[0], a[1]));
          return 0;
        });
    adapter.bind(
        b,
        BOTLIB_AI_LOAD_WEAPON_WEIGHTS,
        NO_CLIENT,
        (vm, a) -> lib().loadWeaponWeights(a[0], text(vm, a[1])));
    adapter.bind(b, BOTLIB_AI_ALLOC_WEAPON_STATE, NO_CLIENT, (vm, a) -> lib().allocWeaponState());
    adapter.bind(
        b,
        BOTLIB_AI_FREE_WEAPON_STATE,
        NO_CLIENT,
        (vm, a) -> run(() -> lib().freeWeaponState(a[0])));
    adapter.bind(
        b,
        BOTLIB_AI_RESET_WEAPON_STATE,
        NO_CLIENT,
        (vm, a) -> run(() -> lib().resetWeaponState(a[0])));
  }

  private void bindSources(Bindings b) {
    adapter.bind(
        b, BOTLIB_PC_LOAD_SOURCE, NO_CLIENT, (vm, a) -> lib().pcLoadSource(text(vm, a[0])));
    adapter.bind(b, BOTLIB_PC_FREE_SOURCE, NO_CLIENT, (vm, a) -> lib().pcFreeSource(a[0]) ? 1 : 0);
    adapter.bind(
        b,
        BOTLIB_PC_READ_TOKEN,
        NO_CLIENT,
        (vm, a) -> {
          var token = lib().pcReadToken(a[0]);
          if (token.isEmpty()) return 0;
          token(vm.memory(), a[1], token.get());
          return 1;
        });
    adapter.bind(
        b,
        BOTLIB_PC_SOURCE_FILE_AND_LINE,
        NO_CLIENT,
        (vm, a) -> {
          var location = lib().pcSourceFileAndLine(a[0]);
          if (location.isEmpty()) return 0;
          GuestText.write(vm.memory(), a[1], location.get().path(), SOURCE_NAME_BYTES);
          vm.memory().writeInt(a[2], location.get().line());
          return 1;
        });
  }
}
