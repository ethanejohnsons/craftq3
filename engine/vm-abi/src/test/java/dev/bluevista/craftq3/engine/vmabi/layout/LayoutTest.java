package dev.bluevista.craftq3.engine.vmabi.layout;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.engine.common.math.Vec3f;
import dev.bluevista.craftq3.engine.common.thread.ThreadOwner;
import dev.bluevista.craftq3.engine.console.CvarTable;
import dev.bluevista.craftq3.engine.vm.QvmException;
import dev.bluevista.craftq3.engine.vm.QvmMemory;
import dev.bluevista.craftq3.engine.vmabi.AbiProfile;
import dev.bluevista.craftq3.engine.vmabi.ConfigStrings;
import dev.bluevista.craftq3.engine.vmabi.TestVm;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

final class LayoutTest {
  private static final int AT = 0x100;
  private final QvmMemory memory = TestVm.instance().memory();

  private static Vec3f v(float x, float y, float z) {
    return new Vec3f(x, y, z);
  }

  @Test
  void structSizesMatchTheOriginalHeaders() {
    assertEquals(272, VmCvarLayout.BYTES);
    assertEquals(56, TraceLayout.BYTES);
    assertEquals(20, TraceLayout.PLANE_BYTES);
    assertEquals(24, UserCmdLayout.BYTES);
    assertEquals(208, EntityStateLayout.Q3_132.size());
    assertEquals(204, EntityStateLayout.RETAIL_1999.size());
    assertEquals(468, PlayerStateLayout.Q3_132.size());
    assertEquals(444, PlayerStateLayout.RETAIL_1999.size());
    assertEquals(516, SharedEntityLayout.Q3_132.size());
    assertEquals(504, SharedEntityLayout.RETAIL_1999.size());
    assertEquals(44 + 468 + 4 + 256 * 208 + 8, SnapshotLayout.Q3_132.size());
    assertEquals(44 + 444 + 4 + 256 * 204 + 8, SnapshotLayout.RETAIL_1999.size());
    assertEquals(20100, GameStateLayout.BYTES);
    assertEquals(11332, GlConfigLayout.Q3_132.size());
    assertEquals(4164, GlConfigLayout.RETAIL_1999.size());
    assertEquals(140, RefEntityLayout.BYTES);
    assertEquals(368, RefdefLayout.BYTES);
    assertEquals(24, PolyVertLayout.BYTES);
    assertEquals(48, OrientationLayout.BYTES);
    assertEquals(80, GlyphInfoLayout.BYTES);
    assertEquals(20548, FontInfoLayout.BYTES);
    assertEquals(3084, UiClientStateLayout.BYTES);
    assertEquals(36, QTimeLayout.BYTES);
    assertEquals(8, MarkFragmentLayout.BYTES);
    for (AbiProfile profile : AbiProfile.values()) {
      assertEquals(SharedEntityLayout.of(profile).size(), profile.sharedEntityBytes());
      assertEquals(GlConfigLayout.of(profile).size(), profile.glConfigBytes());
      assertEquals(SnapshotLayout.of(profile).size(), profile.snapshotBytes());
    }
  }

  @Test
  void playerStateFieldsFollowTheStructOrder() {
    int[] ordered = {
      PlayerStateLayout.COMMAND_TIME,
      PlayerStateLayout.PM_TYPE,
      PlayerStateLayout.BOB_CYCLE,
      PlayerStateLayout.PM_FLAGS,
      PlayerStateLayout.PM_TIME,
      PlayerStateLayout.ORIGIN,
      PlayerStateLayout.VELOCITY,
      PlayerStateLayout.WEAPON_TIME,
      PlayerStateLayout.GRAVITY,
      PlayerStateLayout.SPEED,
      PlayerStateLayout.DELTA_ANGLES,
      PlayerStateLayout.GROUND_ENTITY_NUM,
      PlayerStateLayout.LEGS_TIMER,
      PlayerStateLayout.LEGS_ANIM,
      PlayerStateLayout.TORSO_TIMER,
      PlayerStateLayout.TORSO_ANIM,
      PlayerStateLayout.MOVEMENT_DIR,
      PlayerStateLayout.GRAPPLE_POINT,
      PlayerStateLayout.E_FLAGS,
      PlayerStateLayout.EVENT_SEQUENCE,
      PlayerStateLayout.EVENTS,
      PlayerStateLayout.EVENT_PARMS,
      PlayerStateLayout.EXTERNAL_EVENT,
      PlayerStateLayout.EXTERNAL_EVENT_PARM,
      PlayerStateLayout.EXTERNAL_EVENT_TIME,
      PlayerStateLayout.CLIENT_NUM,
      PlayerStateLayout.WEAPON,
      PlayerStateLayout.WEAPONSTATE,
      PlayerStateLayout.VIEWANGLES,
      PlayerStateLayout.VIEWHEIGHT,
      PlayerStateLayout.DAMAGE_EVENT,
      PlayerStateLayout.DAMAGE_YAW,
      PlayerStateLayout.DAMAGE_PITCH,
      PlayerStateLayout.DAMAGE_COUNT,
      PlayerStateLayout.STATS,
      PlayerStateLayout.PERSISTANT,
      PlayerStateLayout.POWERUPS,
      PlayerStateLayout.AMMO,
      PlayerStateLayout.GENERIC1_132,
      PlayerStateLayout.LOOP_SOUND_132,
      PlayerStateLayout.JUMPPAD_ENT_132,
      PlayerStateLayout.PING_132,
      PlayerStateLayout.PMOVE_FRAMECOUNT_132,
      PlayerStateLayout.JUMPPAD_FRAME_132,
      PlayerStateLayout.ENTITY_EVENT_SEQUENCE_132
    };
    // Widths in words: vec3 = 3, int[2] = 2, int[3] = 3, int[16] = 16.
    int[] words = {
      1, 1, 1, 1, 1, 3, 3, 1, 1, 1, 3, 1, 1, 1, 1, 1, 1, 3, 1, 1, 2, 2, 1, 1, 1, 1, 1, 1, 3, 1, 1,
      1, 1, 1, 16, 16, 16, 16, 1, 1, 1, 1, 1, 1, 1
    };
    int offset = 0;
    for (int i = 0; i < ordered.length; i++) {
      assertEquals(offset, ordered[i], "field " + i);
      offset += 4 * words[i];
    }
    assertEquals(PlayerStateLayout.CANONICAL_BYTES, offset);
    assertEquals(PlayerStateLayout.SHARED_BYTES, PlayerStateLayout.GENERIC1_132);
    assertEquals(440, PlayerStateLayout.RETAIL_1999.ping());
    assertEquals(452, PlayerStateLayout.Q3_132.ping());
    assertEquals(-1, PlayerStateLayout.RETAIL_1999.loopSound());
    assertEquals(464, PlayerStateLayout.Q3_132.entityEventSequence());
  }

  @Test
  void entityStateFieldsFollowTheStructOrder() {
    assertEquals(84, EntityStateLayout.APOS + EntityStateLayout.TrajectoryLayout.BYTES);
    assertEquals(EntityStateLayout.TIME, EntityStateLayout.APOS + 36);
    assertEquals(EntityStateLayout.TORSO_ANIM + 4, EntityStateLayout.RETAIL_1999.size());
    assertEquals(EntityStateLayout.GENERIC1 + 4, EntityStateLayout.Q3_132.size());
    assertEquals(-1, EntityStateLayout.RETAIL_1999.generic1());
    assertEquals(204, EntityStateLayout.Q3_132.generic1());
  }

  @Test
  void playerStateConvertsBetweenProfilesLikeTheLegacyAbi() {
    byte[] canonical = new byte[PlayerStateLayout.CANONICAL_BYTES];
    new Random(7).nextBytes(canonical);
    PlayerStateLayout retail = PlayerStateLayout.RETAIL_1999;
    byte[] guest = retail.fromCanonical(canonical);
    assertEquals(444, guest.length);
    assertArrayEquals(java.util.Arrays.copyOf(canonical, 440), java.util.Arrays.copyOf(guest, 440));
    assertArrayEquals(
        java.util.Arrays.copyOfRange(canonical, 452, 456),
        java.util.Arrays.copyOfRange(guest, 440, 444));
    byte[] back = retail.toCanonical(guest);
    byte[] expected = canonical.clone();
    java.util.Arrays.fill(expected, 440, 452, (byte) 0);
    java.util.Arrays.fill(expected, 456, 468, (byte) 0);
    assertArrayEquals(expected, back);

    retail.writeCanonical(memory, AT, canonical);
    assertArrayEquals(expected, retail.readCanonical(memory, AT));
    PlayerStateLayout.Q3_132.writeCanonical(memory, AT, canonical);
    assertArrayEquals(canonical, PlayerStateLayout.Q3_132.readCanonical(memory, AT));
    assertEquals(
        java.nio.ByteBuffer.wrap(canonical, 452, 4)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .getInt(),
        memory.readInt(AT + PlayerStateLayout.Q3_132.ping()));
    assertThrows(IllegalArgumentException.class, () -> retail.toCanonical(new byte[468]));
  }

  @ParameterizedTest
  @EnumSource(AbiProfile.class)
  void entityStateRoundTripsThroughGuestMemory(AbiProfile profile) {
    EntityStateLayout layout = EntityStateLayout.of(profile);
    byte[] canonical = new byte[EntityStateLayout.CANONICAL_BYTES];
    new Random(3).nextBytes(canonical);
    memory.fill(AT, 512, 0x55);
    layout.writeCanonical(memory, AT, canonical);
    byte[] back = layout.readCanonical(memory, AT);
    byte[] expected = canonical.clone();
    if (profile == AbiProfile.RETAIL_1999) java.util.Arrays.fill(expected, 204, 208, (byte) 0);
    assertArrayEquals(expected, back);
    // Nothing is written past the guest struct.
    assertEquals(0x55, memory.readUnsignedByte(AT + layout.size()));
  }

  @ParameterizedTest
  @EnumSource(AbiProfile.class)
  void userCmdRoundTripsInEachLayout(AbiProfile profile) {
    UserCmdLayout layout = UserCmdLayout.of(profile);
    var cmd = new UserCmdLayout.UserCmd(1234, -300, 16384, 7, 0x41, 5, -127, 64, -1);
    layout.write(memory, AT, cmd);
    assertEquals(cmd, layout.read(memory, AT));
    assertEquals(cmd, UserCmdLayout.UserCmd.fromCanonical(cmd.toCanonical()));
  }

  @Test
  void retailUserCmdKeepsTheOriginalFieldOrder() {
    var cmd = new UserCmdLayout.UserCmd(99, 1, 2, 3, 0x81, 9, 10, -20, 30);
    UserCmdLayout.RETAIL_1999.write(memory, AT, cmd);
    assertEquals(99, memory.readInt(AT));
    assertEquals(0x81, memory.readUnsignedByte(AT + 4));
    assertEquals(9, memory.readUnsignedByte(AT + 5));
    assertEquals(1, memory.readInt(AT + 8));
    assertEquals(3, memory.readInt(AT + 16));
    assertEquals(10, memory.readUnsignedByte(AT + 20));
    assertEquals(-20, (byte) memory.readUnsignedByte(AT + 21));
    UserCmdLayout.Q3_132.write(memory, AT, cmd);
    assertEquals(1, memory.readInt(AT + 4));
    assertEquals(0x81, memory.readInt(AT + 16));
    assertEquals(9, memory.readUnsignedByte(AT + 20));
    assertEquals(30, memory.readUnsignedByte(AT + 23));
    assertArrayEquals(cmd.toCanonical(), memory.readBytes(AT, 24));
  }

  @Test
  void sharedEntityOffsetsMatchBothProfiles() {
    var modern = SharedEntityLayout.Q3_132;
    assertEquals(208, modern.r());
    assertEquals(416, modern.linked());
    assertEquals(424, modern.svFlags());
    assertEquals(428, modern.singleClient());
    assertEquals(432, modern.bmodel());
    assertEquals(436, modern.mins());
    assertEquals(464, modern.absmin());
    assertEquals(488, modern.currentOrigin());
    assertEquals(512, modern.ownerNum());
    var retail = SharedEntityLayout.RETAIL_1999;
    assertEquals(408, retail.linked());
    assertEquals(-1, retail.singleClient());
    assertEquals(420, retail.bmodel());
    assertEquals(500, retail.ownerNum());
    assertTrue(modern.acceptsGameEntitySize(808));
    assertFalse(modern.acceptsGameEntitySize(504));
    assertTrue(retail.acceptsGameEntitySize(504));
    assertTrue(retail.acceptsGameClientSize(444));
    assertFalse(modern.acceptsGameClientSize(444));
  }

  @Test
  void traceWritesPlaneTypeAndSignBits() {
    var trace =
        new TraceLayout.VmTrace(
            false, true, 0.25f, v(1, 2, 3), v(0, -1, 0), -64, 0x20, 1, TraceLayout.ENTITYNUM_WORLD);
    memory.fill(AT, 64, 0x7f);
    TraceLayout.write(memory, AT, trace);
    assertEquals(trace, TraceLayout.read(memory, AT));
    assertEquals(TraceLayout.PLANE_NON_AXIAL, TraceLayout.planeType(memory, AT));
    assertEquals(2, TraceLayout.signBits(memory, AT));
    assertEquals(0, memory.readUnsignedShort(AT + TraceLayout.PLANE_SIGNBITS + 1));
    TraceLayout.write(memory, AT, TraceLayout.VmTrace.clear(v(4, 5, 6)));
    assertEquals(TraceLayout.ENTITYNUM_NONE, memory.readInt(AT + TraceLayout.ENTITY_NUM));
    assertEquals(TraceLayout.PLANE_Z, TraceLayout.planeType(v(0, 0, 1)));
    assertEquals(TraceLayout.PLANE_X, TraceLayout.planeType(v(1, 0, 0)));
    assertEquals(7, TraceLayout.signBits(v(-1, -1, -1)));
  }

  @Test
  void vmCvarCopiesTheCvarAndTruncatesLongValues() {
    var cvars = new CvarTable(new ThreadOwner("test"));
    var cvar = cvars.register("g_gravity", "800.5", 0);
    assertFalse(VmCvarLayout.write(memory, AT, cvar));
    assertEquals(cvar.handle(), VmCvarLayout.handle(memory, AT));
    assertEquals(1, VmCvarLayout.modificationCount(memory, AT));
    assertEquals(800.5f, memory.readFloat(AT + VmCvarLayout.VALUE));
    assertEquals(800, memory.readInt(AT + VmCvarLayout.INTEGER));
    assertEquals("800.5", VmCvarLayout.string(memory, AT));
    assertTrue(VmCvarLayout.write(memory, AT, 1, 2, 0, 0, "x".repeat(300)));
    assertEquals(255, VmCvarLayout.string(memory, AT).length());
  }

  @Test
  void gameStateRoundTripsConfigStrings() {
    var strings = new ConfigStrings();
    strings.set(0, "\\sv_hostname\\test");
    strings.set(5, "q3dm17");
    strings.set(1023, "last");
    GameStateLayout.write(memory, AT, strings);
    assertEquals(
        Map.of(0, "\\sv_hostname\\test", 5, "q3dm17", 1023, "last"),
        GameStateLayout.read(memory, AT));
    assertEquals(1, memory.readInt(AT + GameStateLayout.STRING_OFFSETS));
    assertEquals(0, memory.readInt(AT + GameStateLayout.STRING_OFFSETS + 4));
    assertEquals(strings.characters(), memory.readInt(AT + GameStateLayout.DATA_COUNT));
    assertThrows(
        IllegalStateException.class,
        () -> GameStateLayout.write(memory, AT, Map.of(0, "x".repeat(16000))));
  }

  @Test
  void configStringsEnforceTheGamestateBudget() {
    var strings = new ConfigStrings();
    strings.set(2, "a".repeat(15998));
    assertEquals(16000, strings.characters());
    assertThrows(IllegalStateException.class, () -> strings.set(3, "b"));
    int generation = strings.generation();
    strings.set(2, "a".repeat(15998));
    assertEquals(generation, strings.generation());
    strings.set(2, "");
    assertEquals(1, strings.characters());
    assertThrows(IllegalArgumentException.class, () -> strings.set(1024, ""));
    assertThrows(IllegalArgumentException.class, () -> strings.set(4, "a\0b"));
  }

  @ParameterizedTest
  @EnumSource(AbiProfile.class)
  void glConfigRoundTripsInEachProfile(AbiProfile profile) {
    GlConfigLayout layout = GlConfigLayout.of(profile);
    var config = GlConfigLayout.GlConfig.standard(1920, 1080);
    layout.write(memory, AT, config);
    assertEquals(config, layout.read(memory, AT));
    int expectedWidth = 3072 + profile.glExtensionsBytes() + 40;
    assertEquals(expectedWidth, layout.vidWidth());
    assertEquals(1920, memory.readInt(AT + layout.vidWidth()));
    assertEquals(1080, memory.readInt(AT + layout.vidHeight()));
    assertEquals(1920f / 1080, memory.readFloat(AT + layout.windowAspect()));
  }

  @ParameterizedTest
  @EnumSource(AbiProfile.class)
  void snapshotPlacesStatesByProfileSize(AbiProfile profile) {
    SnapshotLayout layout = SnapshotLayout.of(profile);
    byte[] ps = new byte[PlayerStateLayout.CANONICAL_BYTES];
    ps[0] = 42;
    byte[] es = new byte[EntityStateLayout.CANONICAL_BYTES];
    es[0] = 7;
    int base = 0x400;
    layout.write(memory, base, 1, 50, 1000, new byte[] {1, 2}, ps, List.of(es, es), 3, 9);
    assertEquals(50, memory.readInt(base + SnapshotLayout.PING));
    assertEquals(42, memory.readInt(base + SnapshotLayout.PS));
    assertEquals(2, memory.readInt(base + layout.numEntities()));
    assertEquals(44 + profile.playerStateBytes(), layout.numEntities());
    assertEquals(7, memory.readInt(base + layout.entity(1)));
    assertEquals(layout.entities() + profile.entityStateBytes(), layout.entity(1));
    assertEquals(3, memory.readInt(base + layout.numServerCommands()));
    assertEquals(9, memory.readInt(base + layout.serverCommandSequence()));
  }

  @Test
  void rendererStructsRoundTrip() {
    Vec3f[] axis = {v(1, 0, 0), v(0, 1, 0), v(0, 0, 1)};
    var entity =
        new RefEntityLayout.RefEntity(
            2,
            0x40,
            17,
            v(1, 2, 3),
            0.5f,
            axis,
            true,
            v(4, 5, 6),
            3,
            v(7, 8, 9),
            2,
            0.25f,
            1,
            5,
            6,
            0x80ff00ff,
            0.5f,
            0.75f,
            1.5f,
            32,
            90);
    RefEntityLayout.write(memory, AT, entity);
    var back = RefEntityLayout.read(memory, AT);
    assertArrayEquals(axis, back.axis());
    assertEquals(entity.origin(), back.origin());
    assertEquals(entity.shaderRgba(), back.shaderRgba());
    assertEquals(90f, back.rotation());
    assertEquals(17, memory.readInt(AT + RefEntityLayout.H_MODEL));

    String[] text = {"a", "b", "", "", "", "", "", "c"};
    var refdef =
        new RefdefLayout.Refdef(
            0, 0, 640, 480, 90, 73.74f, v(1, 1, 1), axis, 500, 1, new byte[32], text);
    RefdefLayout.write(memory, 0x800, refdef);
    var refdefBack = RefdefLayout.read(memory, 0x800);
    assertArrayEquals(text, refdefBack.text());
    assertEquals(640, refdefBack.width());
    assertEquals(500, memory.readInt(0x800 + RefdefLayout.TIME));

    var vertex = new PolyVertLayout.PolyVert(v(1, 2, 3), 0.5f, 0.25f, 0xff00ff00);
    PolyVertLayout.write(memory, AT, vertex);
    assertEquals(vertex, PolyVertLayout.read(memory, AT));

    OrientationLayout.write(memory, AT, v(9, 8, 7), axis);
    assertEquals(v(9, 8, 7), OrientationLayout.origin(memory, AT));
    assertArrayEquals(axis, OrientationLayout.axis(memory, AT));

    MarkFragmentLayout.write(memory, AT, 3, 4);
    assertEquals(3, MarkFragmentLayout.firstPoint(memory, AT));
    assertEquals(4, MarkFragmentLayout.numPoints(memory, AT));
  }

  @Test
  void fontAndGlyphsRoundTrip() {
    var glyph =
        new GlyphInfoLayout.GlyphInfo(16, 12, 4, 8, 9, 16, 16, 0, 0, 0.5f, 0.5f, 3, "fonts/a");
    GlyphInfoLayout.write(memory, AT, glyph);
    assertEquals(glyph, GlyphInfoLayout.read(memory, AT));
    int font = 0x1000;
    FontInfoLayout.write(memory, font, List.of(glyph, glyph), 0.5f, "fonts/font1_prop.dat");
    assertEquals(glyph, GlyphInfoLayout.read(memory, font + FontInfoLayout.glyph(1)));
    assertEquals(0.5f, FontInfoLayout.glyphScale(memory, font));
    assertEquals("fonts/font1_prop.dat", FontInfoLayout.name(memory, font));
  }

  @Test
  void uiClientStateAndQTime() {
    var state =
        new UiClientStateLayout.UiClientState(
            UiClientStateLayout.CA_CONNECTING, 3, 1, "localhost", "", "Awaiting challenge");
    UiClientStateLayout.write(memory, AT, state);
    assertEquals(state, UiClientStateLayout.read(memory, AT));

    var time = ZonedDateTime.of(2001, 12, 31, 23, 59, 58, 0, ZoneId.of("UTC"));
    QTimeLayout.write(memory, AT, time);
    assertEquals(58, memory.readInt(AT + QTimeLayout.TM_SEC));
    assertEquals(11, memory.readInt(AT + QTimeLayout.TM_MON));
    assertEquals(101, memory.readInt(AT + QTimeLayout.TM_YEAR));
    assertEquals(1, memory.readInt(AT + QTimeLayout.TM_WDAY));
    assertEquals(364, memory.readInt(AT + QTimeLayout.TM_YDAY));
    assertEquals(0, memory.readInt(AT + QTimeLayout.TM_ISDST));
  }

  @Test
  void accessesOutsideMemoryAreRejected() {
    int last = memory.size() - 10;
    assertThrows(QvmException.class, () -> TraceLayout.read(memory, last));
    assertThrows(QvmException.class, () -> VmStructs.readVec3(memory, memory.size() - 8));
  }
}
