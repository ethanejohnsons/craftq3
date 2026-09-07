package dev.bluevista.craftq3.client;

import dev.bluevista.craftq3.assets.bsp.BspMap;
import dev.bluevista.craftq3.assets.md3.Md3Animation;
import dev.bluevista.craftq3.assets.md3.Md3Model;
import dev.bluevista.craftq3.core.math.Vec3;
import dev.bluevista.craftq3.render.CgameFrame;
import dev.bluevista.craftq3.render.RenderScene;
import dev.bluevista.craftq3.server.VmAbi;
import dev.bluevista.craftq3.vm.QvmMemory;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Copies guest renderer calls into an ordered, immutable frame with bounded submissions. */
final class ClientScene {
  private static final Vec3 ZERO = new Vec3(0, 0, 0),
      X = new Vec3(1, 0, 0),
      Y = new Vec3(0, 1, 0),
      Z = new Vec3(0, 0, 1);
  private final ClientAssets assets;
  private final List<CgameFrame.Command> commands = new ArrayList<>();
  private final List<CgameFrame.RefEntity> entities = new ArrayList<>();
  private final List<CgameFrame.Poly> polygons = new ArrayList<>();
  private final List<RenderScene.DynamicLight> lights = new ArrayList<>();
  private int color = -1;
  private int vertices;

  ClientScene(ClientAssets assets) {
    this.assets = assets;
  }

  void image(CgameFrame.Image image) {
    if (commands.size() >= 16384) throw new IllegalStateException("Cgame command budget exceeded");
    commands.add(image);
  }

  void beginFrame() {
    commands.clear();
    clearScene();
    color = -1;
  }

  void clearScene() {
    entities.clear();
    polygons.clear();
    lights.clear();
    vertices = 0;
  }

  CgameFrame frame() {
    return new CgameFrame(commands, assets.snapshot());
  }

  CgameFrame frame(int milliseconds) {
    return new CgameFrame(commands, assets.snapshot(), milliseconds);
  }

  void entity(QvmMemory memory, int pointer) {
    if (entities.size() >= 1024) throw new IllegalStateException("Cgame entity budget exceeded");
    memory.checkRange(pointer, 140);
    int type = memory.readInt(pointer);
    if (type < 0 || type >= CgameFrame.EntityType.values().length)
      throw new IllegalArgumentException("Invalid cgame entity type");
    var model = assets.model(memory.readInt(pointer + 8));
    int shader = memory.readInt(pointer + 112);
    entities.add(
        new CgameFrame.RefEntity(
            CgameFrame.EntityType.values()[type],
            memory.readInt(pointer + 4),
            model.md3(),
            model.inline(),
            new Md3Model.Tag(
                "entity",
                VmAbi.vector(memory, pointer + 68),
                VmAbi.vector(memory, pointer + 28),
                VmAbi.vector(memory, pointer + 40),
                VmAbi.vector(memory, pointer + 52)),
            VmAbi.vector(memory, pointer + 84),
            memory.readInt(pointer + 80),
            memory.readInt(pointer + 96),
            memory.readFloat(pointer + 100),
            VmAbi.vector(memory, pointer + 12),
            memory.readFloat(pointer + 24),
            memory.readInt(pointer + 104),
            assets.skin(memory.readInt(pointer + 108)),
            shader == 0 ? Optional.empty() : Optional.of(assets.shader(shader)),
            rgba(memory, pointer + 116),
            new BspMap.Uv(memory.readFloat(pointer + 120), memory.readFloat(pointer + 124)),
            memory.readFloat(pointer + 128),
            memory.readFloat(pointer + 132),
            memory.readFloat(pointer + 136)));
  }

  void poly(QvmMemory memory, int shader, int count, int pointer) {
    if (count < 3
        || count > 65536
        || polygons.size() >= 4096
        || (long) vertices + count > 1_000_000)
      throw new IllegalArgumentException("Cgame polygon budget exceeded");
    memory.checkRange(pointer, Math.multiplyExact(count, 24));
    var list = new ArrayList<CgameFrame.PolyVertex>(count);
    for (int i = 0; i < count; i++) {
      int p = pointer + i * 24;
      list.add(
          new CgameFrame.PolyVertex(
              VmAbi.vector(memory, p),
              new BspMap.Uv(memory.readFloat(p + 12), memory.readFloat(p + 16)),
              rgba(memory, p + 20)));
    }
    polygons.add(new CgameFrame.Poly(assets.shader(shader), list));
    vertices += count;
  }

  void light(QvmMemory memory, int[] args) {
    if (lights.size() >= 128) throw new IllegalStateException("Cgame light budget exceeded");
    lights.add(
        new RenderScene.DynamicLight(
            VmAbi.vector(memory, args[0]),
            f(args[1]),
            new Vec3(f(args[2]), f(args[3]), f(args[4]))));
  }

  void render(QvmMemory memory, int pointer) {
    commandBudget();
    memory.checkRange(pointer, 112);
    var ref =
        new CgameFrame.Refdef(
            memory.readInt(pointer),
            memory.readInt(pointer + 4),
            memory.readInt(pointer + 8),
            memory.readInt(pointer + 12),
            memory.readFloat(pointer + 16),
            memory.readFloat(pointer + 20),
            VmAbi.vector(memory, pointer + 24),
            VmAbi.vector(memory, pointer + 36),
            VmAbi.vector(memory, pointer + 48),
            VmAbi.vector(memory, pointer + 60),
            memory.readInt(pointer + 72),
            memory.readInt(pointer + 76),
            new BspMap.Bytes(memory.readBytes(pointer + 80, 32)),
            List.of());
    commands.add(new CgameFrame.View(ref, entities, polygons, lights));
  }

  void color(QvmMemory memory, int pointer) {
    if (pointer == 0) {
      color = -1;
      return;
    }
    memory.checkRange(pointer, 16);
    color = 0;
    for (int i = 0; i < 4; i++) {
      float value = memory.readFloat(pointer + i * 4);
      if (!Float.isFinite(value)) throw new IllegalArgumentException("Non-finite cgame color");
      color = (color << 8) | Math.round(Math.clamp(value, 0, 1) * 255);
    }
  }

  void quad(int[] a) {
    commandBudget();
    commands.add(
        new CgameFrame.Quad(
            f(a[0]),
            f(a[1]),
            f(a[2]),
            f(a[3]),
            f(a[4]),
            f(a[5]),
            f(a[6]),
            f(a[7]),
            assets.shader(a[8]),
            color));
  }

  void modelBounds(QvmMemory memory, int[] args) {
    var model = assets.model(args[0]);
    Vec3 lo = ZERO, hi = ZERO;
    if (model.inline() >= 0) {
      var box = assets.world().models().get(model.inline()).bounds();
      lo = box.min();
      hi = box.max();
    } else if (model.md3() != null) {
      var frame = model.md3().frames().getFirst();
      if (frame.hasBounds()) {
        lo = frame.min();
        hi = frame.max();
      }
    }
    VmAbi.vector(memory, args[1], lo);
    VmAbi.vector(memory, args[2], hi);
  }

  int lerpTag(QvmMemory memory, int[] args, String name) {
    var model = assets.model(args[1]).md3();
    Optional<Md3Model.Tag> tag = Optional.empty();
    if (model != null) {
      int from = Math.clamp(args[2], 0, model.frames().size() - 1),
          to = Math.clamp(args[3], 0, model.frames().size() - 1);
      tag = Md3Animation.interpolateTag(model, name, from, to, Math.clamp(f(args[4]), 0, 1));
    }
    var value = tag.orElseGet(() -> new Md3Model.Tag(name, ZERO, X, Y, Z));
    VmAbi.vector(memory, args[0], value.origin());
    VmAbi.vector(memory, args[0] + 12, value.axisX());
    VmAbi.vector(memory, args[0] + 24, value.axisY());
    VmAbi.vector(memory, args[0] + 36, value.axisZ());
    return tag.isPresent() ? 1 : 0;
  }

  private void commandBudget() {
    if (commands.size() >= 16384)
      throw new IllegalStateException("Cgame draw command budget exceeded");
  }

  private static int rgba(QvmMemory m, int p) {
    return (m.readUnsignedByte(p) << 24)
        | (m.readUnsignedByte(p + 1) << 16)
        | (m.readUnsignedByte(p + 2) << 8)
        | m.readUnsignedByte(p + 3);
  }

  private static float f(int value) {
    return Float.intBitsToFloat(value);
  }
}
