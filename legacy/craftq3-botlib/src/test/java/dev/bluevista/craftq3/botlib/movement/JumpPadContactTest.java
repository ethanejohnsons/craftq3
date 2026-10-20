package dev.bluevista.craftq3.botlib.movement;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.assets.aas.AasMap;
import dev.bluevista.craftq3.core.math.Vec3;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

final class JumpPadContactTest {
  private static final Vec3 ZERO = new Vec3(0, 0, 0);

  @Test
  void tracesBackAlongVelocityAndChoosesFirstAreaThenLastTaggedJumpLink() {
    var contact = new JumpPadContact(map(2, 1, 2, 128, 128));
    assertEquals(
        new JumpPadContact.Contact(1, 2),
        contact.find(new Vec3(5, 0, 0), new Vec3(50, 0, 0)).orElseThrow());
    assertEquals(
        new JumpPadContact.Contact(2, 4),
        contact.find(new Vec3(-5, 0, 0), new Vec3(-50, 0, 0)).orElseThrow());
    assertEquals(
        new JumpPadContact.Contact(1, 2), contact.find(new Vec3(5, 0, 0), ZERO).orElseThrow());
  }

  @Test
  void padWithoutMatchingLinkDoesNotHideLaterContact() {
    var contact = new JumpPadContact(map(2, 1, 1, 128, 128));
    assertEquals(
        new JumpPadContact.Contact(2, 4),
        contact.find(new Vec3(5, 0, 0), new Vec3(50, 0, 0)).orElseThrow());
    assertTrue(contact.find(new Vec3(5, 0, 0), ZERO).isEmpty());
    contact = new JumpPadContact(map(2, 1, 2, 0, 128));
    assertEquals(
        new JumpPadContact.Contact(2, 4),
        contact.find(new Vec3(5, 0, 0), new Vec3(50, 0, 0)).orElseThrow());
  }

  @Test
  void preservesNonzeroFirstIndexForZeroCountAndZeroIndexSentinel() {
    var contact = new JumpPadContact(map(2, 2, 0, 128, 0));
    assertEquals(
        new JumpPadContact.Contact(1, 2), contact.find(new Vec3(5, 0, 0), ZERO).orElseThrow());
    assertTrue(new JumpPadContact(map(2, 0, 0, 128, 0)).find(new Vec3(5, 0, 0), ZERO).isEmpty());
    assertTrue(new JumpPadContact(map(2, 5, 0, 128, 0)).find(new Vec3(5, 0, 0), ZERO).isEmpty());
  }

  @Test
  void stopsAtSixteenRawEntries() {
    var map = map(17, 1, 2, 0, 0);
    var settings = new ArrayList<>(map.areaSettings());
    settings.set(17, new AasMap.AreaSettings(128, 0, 6, 0, 0, 2, 3));
    map = copy(map, settings);
    var contact = new JumpPadContact(map);
    assertTrue(contact.find(new Vec3(17, 0, 0), new Vec3(85, 0, 0)).isEmpty());
    assertEquals(
        new JumpPadContact.Contact(17, 4), contact.find(new Vec3(.5, 0, 0), ZERO).orElseThrow());
  }

  @Test
  void budgetsAndInvalidInputsFailExplicitly() {
    var map = map(2, 1, 2, 128, 128);
    assertThrows(
        IllegalStateException.class,
        () -> new JumpPadContact(map, 1, 10).find(new Vec3(5, 0, 0), ZERO));
    assertThrows(
        IllegalStateException.class,
        () -> new JumpPadContact(map, 100, 1).find(new Vec3(5, 0, 0), ZERO));
    assertThrows(IllegalArgumentException.class, () -> new JumpPadContact(map, 0, 1));
    assertThrows(
        IllegalArgumentException.class,
        () -> new JumpPadContact(map).find(new Vec3(Double.MAX_VALUE, 0, 0), ZERO));
  }

  @Test
  void backwardRayUsesFloatConstantAndSeparateMultiplyBeforeSubtract() {
    var m = map(2, 1, 2, 128, 0);
    var withPlane =
        new AasMap(
            m.version(),
            m.bspChecksum(),
            m.lumps(),
            m.boundingBoxes(),
            m.vertices(),
            List.of(new AasMap.Plane(new Vec3(1, 0, 0), -3631.593017578125f, 0)),
            m.edges(),
            m.edgeIndices(),
            m.faces(),
            m.faceIndices(),
            m.areas(),
            m.areaSettings(),
            m.reachabilities(),
            m.nodes(),
            m.portals(),
            m.portalIndices(),
            m.clusters());
    assertEquals(
        new JumpPadContact.Contact(1, 2),
        new JumpPadContact(withPlane)
            .find(new Vec3(-4671.9697265625, 0, 0), new Vec3(-5201.8837890625, 0, 0))
            .orElseThrow());
  }

  private static AasMap copy(AasMap m, List<AasMap.AreaSettings> settings) {
    return new AasMap(
        m.version(),
        m.bspChecksum(),
        m.lumps(),
        m.boundingBoxes(),
        m.vertices(),
        m.planes(),
        m.edges(),
        m.edgeIndices(),
        m.faces(),
        m.faceIndices(),
        m.areas(),
        settings,
        m.reachabilities(),
        m.nodes(),
        m.portals(),
        m.portalIndices(),
        m.clusters());
  }

  private static AasMap map(
      int count, int first, int reachCount, int frontContents, int backContents) {
    var areas = new ArrayList<AasMap.Area>();
    var settings = new ArrayList<AasMap.AreaSettings>();
    for (int i = 0; i <= count; i++) {
      areas.add(new AasMap.Area(i, 0, 0, ZERO, ZERO, ZERO));
      settings.add(
          new AasMap.AreaSettings(
              i == 1 ? frontContents : backContents,
              0,
              6,
              0,
              0,
              i == 1 ? reachCount : 2,
              i == 1 ? first : 3));
    }
    var planes = new ArrayList<AasMap.Plane>();
    var nodes = new ArrayList<AasMap.Node>();
    nodes.add(new AasMap.Node(0, 0, 0));
    for (int i = 1; i < count; i++) {
      planes.add(new AasMap.Plane(new Vec3(1, 0, 0), count == 2 ? 0 : count - i, 0));
      nodes.add(new AasMap.Node(i - 1, -i, i + 1 == count ? -count : i + 1));
    }
    var reaches =
        List.of(
            new AasMap.Reachability(0, 0, 0, ZERO, ZERO, 0, 0, 0),
            new AasMap.Reachability(1, 0, 0, ZERO, ZERO, 2, 1, 0),
            new AasMap.Reachability(1, 0, 0, ZERO, ZERO, 18 | 0x1000000, 1, 0),
            new AasMap.Reachability(2, 0, 0, ZERO, ZERO, 18, 1, 0),
            new AasMap.Reachability(2, 0, 0, ZERO, ZERO, 18, 1, 0));
    var empty = new AasMap.Indices(new int[0]);
    return new AasMap(
        5, 0, List.of(), List.of(), List.of(), planes, List.of(), empty, List.of(), empty, areas,
        settings, reaches, nodes, List.of(), empty, List.of());
  }
}
