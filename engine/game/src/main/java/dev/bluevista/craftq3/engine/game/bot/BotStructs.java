package dev.bluevista.craftq3.engine.game.bot;

import dev.bluevista.craftq3.engine.botlib.BotEntityState;
import dev.bluevista.craftq3.engine.botlib.EntityInfo;
import dev.bluevista.craftq3.engine.botlib.chat.BotChat;
import dev.bluevista.craftq3.engine.botlib.chat.ChatMatcher;
import dev.bluevista.craftq3.engine.botlib.ea.BotInput;
import dev.bluevista.craftq3.engine.botlib.goal.Goal;
import dev.bluevista.craftq3.engine.botlib.movement.AasMovementPredictor;
import dev.bluevista.craftq3.engine.botlib.movement.MovementInit;
import dev.bluevista.craftq3.engine.botlib.movement.MovementResult;
import dev.bluevista.craftq3.engine.botlib.script.ScriptToken;
import dev.bluevista.craftq3.engine.botlib.weapon.ProjectileInfo;
import dev.bluevista.craftq3.engine.botlib.weapon.WeaponInfo;
import dev.bluevista.craftq3.engine.common.math.Vec3f;
import dev.bluevista.craftq3.engine.vm.QvmMemory;
import dev.bluevista.craftq3.engine.vmabi.AbiProfile;
import dev.bluevista.craftq3.engine.vmabi.layout.VmStructs;

/**
 * Guest layouts of the botlib structures ({@code botlib.h}, {@code be_aas.h}, {@code be_ai_*.h}),
 * read and written field by field. Profile-dependent sizes ({@code bot_consolemessage_t}, {@code
 * bot_match_t}) come from {@link AbiProfile}.
 */
final class BotStructs {
  /** {@code sizeof(bot_goal_t)}. */
  static final int GOAL_BYTES = 56;

  /** {@code sizeof(bot_entitystate_t)}. */
  static final int ENTITY_STATE_BYTES = 112;

  /** {@code sizeof(aas_entityinfo_t)}. */
  static final int ENTITY_INFO_BYTES = 140;

  /** {@code sizeof(bot_moveresult_t)}. */
  static final int MOVE_RESULT_BYTES = 52;

  /** The leading words a failed move writes (failure to flags). */
  static final int MOVE_RESULT_PREFIX_BYTES = 24;

  /** {@code sizeof(bot_input_t)}. */
  static final int INPUT_BYTES = 40;

  /** {@code sizeof(bot_initmove_t)}. */
  static final int INIT_MOVE_BYTES = 68;

  /** {@code sizeof(aas_areainfo_t)}. */
  static final int AREA_INFO_BYTES = 52;

  /** {@code sizeof(aas_clientmove_t)}. */
  static final int CLIENT_MOVE_BYTES = 84;

  /** {@code sizeof(aas_altroutegoal_t)}. */
  static final int ALT_ROUTE_GOAL_BYTES = 24;

  /** {@code sizeof(weaponinfo_t)} and its {@code MAX_STRINGFIELD}. */
  static final int WEAPON_INFO_BYTES = 552, STRING_FIELD = 80;

  /** {@code sizeof(pc_token_t)}: four words and {@code MAX_TOKENLENGTH}. */
  static final int TOKEN_BYTES = 16 + 1024;

  /** {@code MAX_INVENTORY} words read by the item and weapon selectors. */
  static final int INVENTORY = 256;

  private BotStructs() {}

  static Vec3f vec(QvmMemory m, int address) {
    return address == 0 ? Vec3f.ZERO : VmStructs.readVec3(m, address);
  }

  static void vec(QvmMemory m, int address, Vec3f value) {
    VmStructs.writeVec3(m, address, value);
  }

  static float f(int bits) {
    return Float.intBitsToFloat(bits);
  }

  static int bits(float value) {
    return Float.floatToRawIntBits(value);
  }

  /**
   * Reads a {@code bot_goal_t}. Guests sometimes pass goals whose bounds were never set and are
   * inverted (Q3Rally's nearby-item calls); the original library tolerates them and a box test
   * against them never succeeds, so an inverted axis is collapsed to its midpoint, where a box test
   * is just as unlikely to succeed. Botlib's goal type refuses inverted bounds.
   */
  static Goal goal(QvmMemory m, int a) {
    m.resolve(a, GOAL_BYTES);
    float[] mins = new float[3], maxs = new float[3];
    VmStructs.readVec3(m, a + 16, mins);
    VmStructs.readVec3(m, a + 28, maxs);
    for (int i = 0; i < 3; i++)
      if (!(mins[i] <= maxs[i])) {
        float mid = Float.isFinite(mins[i] + maxs[i]) ? (mins[i] + maxs[i]) * 0.5f : 0;
        mins[i] = maxs[i] = mid;
      }
    return new Goal(
        vec(m, a),
        m.readInt(a + 12),
        Vec3f.of(mins),
        Vec3f.of(maxs),
        m.readInt(a + 40),
        m.readInt(a + 44),
        m.readInt(a + 48),
        m.readInt(a + 52));
  }

  static void goal(QvmMemory m, int a, Goal g) {
    m.resolve(a, GOAL_BYTES);
    vec(m, a, g.origin());
    m.writeInt(a + 12, g.area());
    vec(m, a + 16, g.mins());
    vec(m, a + 28, g.maxs());
    m.writeInt(a + 40, g.entity());
    m.writeInt(a + 44, g.number());
    m.writeInt(a + 48, g.flags());
    m.writeInt(a + 52, g.itemInfo());
  }

  static BotEntityState entityState(QvmMemory m, int a) {
    m.resolve(a, ENTITY_STATE_BYTES);
    return new BotEntityState(
        m.readInt(a),
        m.readInt(a + 4),
        vec(m, a + 8),
        vec(m, a + 20),
        vec(m, a + 32),
        vec(m, a + 44),
        vec(m, a + 56),
        m.readInt(a + 68),
        m.readInt(a + 72),
        m.readInt(a + 76),
        m.readInt(a + 80),
        m.readInt(a + 84),
        m.readInt(a + 88),
        m.readInt(a + 92),
        m.readInt(a + 96),
        m.readInt(a + 100),
        m.readInt(a + 104),
        m.readInt(a + 108));
  }

  static void entityInfo(QvmMemory m, int a, EntityInfo e) {
    m.resolve(a, ENTITY_INFO_BYTES);
    m.writeInt(a, e.valid() ? 1 : 0);
    m.writeInt(a + 4, e.type());
    m.writeInt(a + 8, e.flags());
    m.writeFloat(a + 12, e.lastUpdateTime());
    m.writeFloat(a + 16, e.updateInterval());
    m.writeInt(a + 20, e.number());
    vec(m, a + 24, e.origin());
    vec(m, a + 36, e.angles());
    vec(m, a + 48, e.oldOrigin());
    vec(m, a + 60, e.lastVisibleOrigin());
    vec(m, a + 72, e.mins());
    vec(m, a + 84, e.maxs());
    int[] tail = {
      e.groundEntity(),
      e.solid(),
      e.modelIndex(),
      e.modelIndex2(),
      e.frame(),
      e.event(),
      e.eventParm(),
      e.powerups(),
      e.weapon(),
      e.legsAnim(),
      e.torsoAnim()
    };
    for (int i = 0; i < tail.length; i++) m.writeInt(a + 96 + 4 * i, tail[i]);
  }

  /** Writes the first {@code bytes} bytes of a move result (the original's partial writes). */
  static void moveResult(QvmMemory m, int a, MovementResult r, int bytes) {
    m.resolve(a, MOVE_RESULT_BYTES);
    int[] head = {r.failure(), r.type(), r.blocked(), r.blockEntity(), r.travelType(), r.flags()};
    for (int i = 0; i < head.length; i++) m.writeInt(a + 4 * i, head[i]);
    if (bytes <= 24) return;
    m.writeInt(a + 24, r.weapon());
    vec(m, a + 28, r.direction());
    vec(m, a + 40, r.idealViewAngles());
  }

  static void input(QvmMemory m, int a, BotInput in) {
    m.resolve(a, INPUT_BYTES);
    m.writeFloat(a, in.thinkTime());
    vec(m, a + 4, in.direction());
    m.writeFloat(a + 16, in.speed());
    vec(m, a + 20, in.viewAngles());
    m.writeInt(a + 32, in.actionFlags());
    m.writeInt(a + 36, in.weapon());
  }

  static MovementInit initMove(QvmMemory m, int a) {
    m.resolve(a, INIT_MOVE_BYTES);
    return new MovementInit(
        vec(m, a),
        vec(m, a + 12),
        vec(m, a + 24),
        m.readInt(a + 36),
        m.readInt(a + 40),
        m.readFloat(a + 44),
        m.readInt(a + 48),
        vec(m, a + 52),
        m.readInt(a + 64));
  }

  static void clientMove(QvmMemory m, int a, AasMovementPredictor.Prediction p) {
    m.resolve(a, CLIENT_MOVE_BYTES);
    vec(m, a, p.endPosition());
    m.writeInt(a + 12, p.endArea());
    vec(m, a + 16, p.velocity());
    p.trace()
        .ifPresent(
            t -> {
              m.writeInt(a + 28, t.startSolid() ? 1 : 0);
              m.writeFloat(a + 32, t.fraction());
              vec(m, a + 36, t.endPosition());
              m.writeInt(a + 48, t.entity());
              m.writeInt(a + 52, t.lastArea());
              m.writeInt(a + 56, t.area());
              m.writeInt(a + 60, t.planeNumber());
            });
    m.writeInt(a + 64, p.presence());
    m.writeInt(a + 68, p.stopEvent());
    m.writeInt(a + 72, p.endContents());
    m.writeFloat(a + 76, p.time());
    m.writeInt(a + 80, p.frames());
  }

  private static void chars(QvmMemory m, int a, String text, int capacity) {
    VmStructs.writeChars(m, a, text, capacity);
  }

  static void weaponInfo(QvmMemory m, int a, WeaponInfo w) {
    m.resolve(a, WEAPON_INFO_BYTES);
    m.writeInt(a, w.valid() ? 1 : 0);
    m.writeInt(a + 4, w.number());
    chars(m, a + 8, w.name(), STRING_FIELD);
    chars(m, a + 88, w.model(), STRING_FIELD);
    m.writeInt(a + 168, w.level());
    m.writeInt(a + 172, w.weaponIndex());
    m.writeInt(a + 176, w.flags());
    chars(m, a + 180, w.projectile(), STRING_FIELD);
    m.writeInt(a + 260, w.projectileCount());
    m.writeFloat(a + 264, w.horizontalSpread());
    m.writeFloat(a + 268, w.verticalSpread());
    m.writeFloat(a + 272, w.speed());
    m.writeFloat(a + 276, w.acceleration());
    vec(m, a + 280, w.recoil());
    vec(m, a + 292, w.offset());
    vec(m, a + 304, w.angleOffset());
    m.writeFloat(a + 316, w.extraZVelocity());
    m.writeInt(a + 320, w.ammoAmount());
    m.writeInt(a + 324, w.ammoIndex());
    m.writeFloat(a + 328, w.activate());
    m.writeFloat(a + 332, w.reload());
    m.writeFloat(a + 336, w.spinUp());
    m.writeFloat(a + 340, w.spinDown());
    ProjectileInfo p = w.projectileInfo();
    int b = a + 344;
    chars(m, b, p.name(), STRING_FIELD);
    chars(m, b + 80, p.model(), STRING_FIELD);
    m.writeInt(b + 160, p.flags());
    m.writeFloat(b + 164, p.gravity());
    m.writeInt(b + 168, p.damage());
    m.writeFloat(b + 172, p.radius());
    m.writeInt(b + 176, p.visibleDamage());
    m.writeInt(b + 180, p.damageType());
    m.writeInt(b + 184, p.healthIncrement());
    m.writeFloat(b + 188, p.push());
    m.writeFloat(b + 192, p.detonation());
    m.writeFloat(b + 196, p.bounce());
    m.writeFloat(b + 200, p.bounceFriction());
    m.writeFloat(b + 204, p.bounceStop());
  }

  static int[] inventory(QvmMemory m, int a) {
    m.resolve(a, 4 * INVENTORY);
    int[] values = new int[INVENTORY];
    for (int i = 0; i < INVENTORY; i++) values[i] = m.readInt(a + 4 * i);
    return values;
  }

  /** {@code bot_consolemessage_t}: handle, time, type and the message in the profile's size. */
  static void consoleMessage(QvmMemory m, int a, AbiProfile profile, BotChat.ConsoleMessage c) {
    m.fill(a, profile.botConsoleMessageBytes(), 0);
    m.writeInt(a, c.id());
    m.writeFloat(a + 4, c.time());
    m.writeInt(a + 8, c.type());
    chars(m, a + 12, c.text(), profile.botMessageBytes());
  }

  /**
   * Reads a guest {@code bot_match_t} into the 1.32 layout botlib uses. Retail builds have a
   * 150-byte string, so the type, subtype and variables sit at 152, 156 and 160.
   */
  static byte[] match(QvmMemory m, int a, AbiProfile profile) {
    byte[] modern = new byte[ChatMatcher.BYTE_SIZE];
    int text = profile.botMessageBytes();
    int tail = matchTail(profile);
    byte[] guest = m.readBytes(a, profile.botMatchBytes());
    System.arraycopy(guest, 0, modern, 0, Math.min(text, 256));
    System.arraycopy(guest, tail, modern, 256, ChatMatcher.BYTE_SIZE - 256);
    return modern;
  }

  static void match(QvmMemory m, int a, AbiProfile profile, byte[] modern) {
    byte[] guest = m.readBytes(a, profile.botMatchBytes());
    int text = profile.botMessageBytes();
    System.arraycopy(modern, 0, guest, 0, Math.min(text, 256));
    guest[Math.min(text, 256) - 1] = 0;
    System.arraycopy(modern, 256, guest, matchTail(profile), ChatMatcher.BYTE_SIZE - 256);
    m.writeBytes(a, guest);
  }

  private static int matchTail(AbiProfile profile) {
    return (profile.botMessageBytes() + 3) & ~3;
  }

  static void token(QvmMemory m, int a, ScriptToken t) {
    m.resolve(a, TOKEN_BYTES);
    m.writeInt(a, t.type());
    m.writeInt(a + 4, t.subtype());
    m.writeInt(a + 8, t.intValue());
    m.writeFloat(a + 12, t.floatValue());
    chars(m, a + 16, t.text(), TOKEN_BYTES - 16);
  }
}
