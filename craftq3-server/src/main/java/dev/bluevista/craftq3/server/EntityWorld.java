package dev.bluevista.craftq3.server;

import dev.bluevista.craftq3.assets.bsp.BspBoxLeaves;
import dev.bluevista.craftq3.assets.bsp.BspMap;
import dev.bluevista.craftq3.collision.BoxTraceWorld;
import dev.bluevista.craftq3.collision.BspTraceWorld;
import dev.bluevista.craftq3.collision.CompositeTraceWorld;
import dev.bluevista.craftq3.collision.TraceRequest;
import dev.bluevista.craftq3.collision.TraceResult;
import dev.bluevista.craftq3.collision.TraceWorld;
import dev.bluevista.craftq3.core.math.Vec3;
import dev.bluevista.craftq3.vm.QvmMemory;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Linked QVM entities participate in the same collision contract as the immutable BSP. */
final class EntityWorld {
  private static final Vec3 ZERO = new Vec3(0, 0, 0);
  private final GameAbi abi;

  record Linked(int number, int pointer, BspMap.Bounds bounds, TraceWorld collision, int owner) {}

  private final BspMap map;
  private final BspTraceWorld bsp;
  private final TraceWorld terrain;
  private final QvmMemory memory;
  private final Map<Integer, Linked> linked = new LinkedHashMap<>();
  private final Map<Integer, Integer> damageModels = new java.util.HashMap<>();
  private final Map<Integer, Integer> damageEntities = new java.util.HashMap<>();
  private int armedDamage;
  private final Map<Integer, BspMap.Bounds> externalBounds = new LinkedHashMap<>();
  private final Map<Integer, List<Integer>> areaAssociations = new LinkedHashMap<>();
  private int entities, count, stride, clients, clientStride;

  EntityWorld(BspMap map, QvmMemory memory, GameAbi abi) {
    this(map, memory, abi, null);
  }

  EntityWorld(BspMap map, QvmMemory memory, GameAbi abi, TraceWorld externalTerrain) {
    this(map, memory, abi, externalTerrain, Map.of());
  }

  EntityWorld(
      BspMap map,
      QvmMemory memory,
      GameAbi abi,
      TraceWorld externalTerrain,
      Map<Integer, Integer> admissionDamage) {
    damageModels.putAll(admissionDamage);
    this.map = map;
    this.memory = memory;
    this.abi = abi;
    bsp = new BspTraceWorld(map);
    terrain = externalTerrain == null ? bsp : externalTerrain;
    if (externalTerrain != null)
      for (var entity : map.entities()) {
        if ("trigger_hurt".equals(entity.get("classname")) && entity.containsKey("craftq3_damage"))
          damageModels.put(
              Integer.parseInt(entity.get("model").substring(1)),
              Integer.parseInt(entity.get("craftq3_damage")));
      }
  }

  void locate(int address, int count, int stride, int clients, int clientStride, int maxClients) {
    if (count < 0
        || count > 1024
        || stride < abi.sharedEntityBytes()
        || clientStride < abi.playerStateBytes())
      throw new IllegalArgumentException("Invalid GAME_LOCATE_GAME_DATA layout");
    VmAbi.range(memory, address, Math.multiplyExact(count, stride));
    VmAbi.range(memory, clients, Math.multiplyExact(maxClients, clientStride));
    if (!linked.isEmpty() && (entities != address || this.stride != stride))
      throw new IllegalStateException("Game relocated linked entity memory");
    if (entities != address || this.stride != stride) areaAssociations.clear();
    this.entities = address;
    this.count = count;
    this.stride = stride;
    this.clients = clients;
    this.clientStride = clientStride;
    linked.entrySet().removeIf(entry -> entry.getKey() >= count);
    areaAssociations.keySet().removeIf(number -> number >= count);
  }

  int count() {
    return count;
  }

  void reset() {
    linked.clear();
    damageEntities.clear();
    armedDamage = 0;
    externalBounds.clear();
    areaAssociations.clear();
    entities = count = stride = clients = clientStride = 0;
  }

  int pointer(int number) {
    if (number < 0 || number >= count || stride == 0)
      throw new IllegalArgumentException("Invalid game entity " + number);
    return Math.addExact(entities, Math.multiplyExact(number, stride));
  }

  int number(int pointer) {
    if (stride == 0 || pointer < entities || (pointer - entities) % stride != 0)
      throw new IllegalArgumentException("Not a game entity pointer: " + pointer);
    int number = (pointer - entities) / stride;
    if (number >= count) throw new IllegalArgumentException("Game entity outside located range");
    return number;
  }

  int playerPointer(int number, int maxClients) {
    if (number < 0 || number >= maxClients || clientStride == 0)
      throw new IllegalArgumentException("Invalid player state index");
    return Math.addExact(clients, Math.multiplyExact(number, clientStride));
  }

  List<Linked> linked() {
    return List.copyOf(linked.values());
  }

  void brushModel(int pointer, String name) {
    number(pointer);
    if (!name.startsWith("*"))
      throw new IllegalArgumentException("Inline brush model must start with *");
    int index = Integer.parseInt(name.substring(1));
    if (index < 1 || index >= map.models().size())
      throw new IllegalArgumentException("Invalid inline model " + index);
    var model = map.models().get(index);
    if (damageModels.containsKey(index))
      damageEntities.put(number(pointer), damageModels.get(index));
    memory.writeInt(pointer + 160, index);
    memory.writeInt(pointer + abi.bmodel(), 1);
    VmAbi.vector(memory, pointer + abi.mins(), model.bounds().min());
    VmAbi.vector(memory, pointer + abi.maxs(), model.bounds().max());
    int contents = 0;
    for (int i = model.firstBrush(); i < model.firstBrush() + model.brushCount(); i++)
      contents |= map.textures().get(map.brushes().get(i).texture()).contents();
    memory.writeInt(pointer + abi.contents(), contents);
  }

  void externalBounds(int number, BspMap.Bounds bounds) {
    if (bounds == null) externalBounds.remove(number);
    else externalBounds.put(number, bounds);
  }

  TraceWorld externalCollision() {
    return new TraceWorld() {
      public TraceResult trace(TraceRequest request) {
        var shapes = new ArrayList<TraceWorld>();
        shapes.add(terrain);
        for (int number : externalBounds.keySet()) shapes.add(collision(pointer(number), number));
        return new CompositeTraceWorld(shapes).trace(request);
      }

      public int pointContents(Vec3 point, int mask, int ignored) {
        int result = terrain.pointContents(point, mask, ignored);
        for (int number : externalBounds.keySet())
          result |= collision(pointer(number), number).pointContents(point, mask, ignored);
        return result;
      }
    };
  }

  void link(int pointer) {
    int number = number(pointer);
    var external = externalBounds.get(number);
    if (external != null) {
      Vec3 origin = VmAbi.vector(memory, pointer + abi.origin());
      VmAbi.vector(memory, pointer + abi.mins(), external.min().add(origin.scale(-1)));
      VmAbi.vector(memory, pointer + abi.maxs(), external.max().add(origin.scale(-1)));
    }
    Vec3 mins = VmAbi.vector(memory, pointer + abi.mins()),
        maxs = VmAbi.vector(memory, pointer + abi.maxs()),
        origin = VmAbi.vector(memory, pointer + abi.origin()),
        angles = VmAbi.vector(memory, pointer + abi.angles());
    if (mins.x() > maxs.x() || mins.y() > maxs.y() || mins.z() > maxs.z())
      throw new IllegalArgumentException(
          "Entity "
              + number
              + " bounds are inverted: mins="
              + mins
              + " maxs="
              + maxs
              + " origin="
              + origin
              + " pointer="
              + pointer
              + " stride="
              + stride);
    boolean bmodel = memory.readInt(pointer + abi.bmodel()) != 0;
    Vec3 lo = mins.add(origin), hi = maxs.add(origin);
    if (bmodel && !angles.equals(ZERO)) {
      double x = Math.max(Math.abs(mins.x()), Math.abs(maxs.x())),
          y = Math.max(Math.abs(mins.y()), Math.abs(maxs.y())),
          z = Math.max(Math.abs(mins.z()), Math.abs(maxs.z()));
      double radius = Math.sqrt(x * x + y * y + z * z);
      lo = origin.add(new Vec3(-radius, -radius, -radius));
      hi = origin.add(new Vec3(radius, radius, radius));
    }
    lo = lo.add(new Vec3(-1, -1, -1));
    hi = hi.add(new Vec3(1, 1, 1));
    var bounds = new BspMap.Bounds(lo, hi);
    var association = associateAreas(bounds);
    VmAbi.vector(memory, pointer + abi.absmin(), lo);
    VmAbi.vector(memory, pointer + abi.absmax(), hi);
    int contents = memory.readInt(pointer + abi.contents()), solid = 0;
    if (bmodel) solid = 0xffffff;
    else if ((contents & (1 | 0x2000000)) != 0)
      solid =
          Math.clamp((int) maxs.x(), 1, 255)
              | (Math.clamp((int) -mins.z(), 1, 255) << 8)
              | (Math.clamp((int) (maxs.z() + 32), 1, 255) << 16);
    memory.writeInt(pointer + 176, solid);
    TraceWorld collision = collision(pointer, number);
    areaAssociations.put(number, association);
    linked.put(
        number,
        new Linked(number, pointer, bounds, collision, memory.readInt(pointer + abi.owner())));
    memory.writeInt(pointer + abi.linked(), 1);
    memory.writeInt(pointer + abi.linkCount(), memory.readInt(pointer + abi.linkCount()) + 1);
  }

  private TraceWorld collision(int pointer, int number) {
    var external = externalBounds.get(number);
    if (external != null)
      return new BoxTraceWorld(external.min(), external.max(), 0x2000000, 0, number);
    Vec3 origin = VmAbi.vector(memory, pointer + abi.origin());
    if (memory.readInt(pointer + abi.bmodel()) != 0)
      return bsp.model(
          memory.readInt(pointer + 160),
          number,
          origin,
          VmAbi.vector(memory, pointer + abi.angles()));
    return new BoxTraceWorld(
        VmAbi.vector(memory, pointer + abi.mins()).add(origin),
        VmAbi.vector(memory, pointer + abi.maxs()).add(origin),
        memory.readInt(pointer + abi.contents()),
        0,
        number);
  }

  void unlink(int pointer) {
    linked.remove(number(pointer));
    memory.writeInt(pointer + abi.linked(), 0);
  }

  /** Botlib has already selected this entity through AAS linkage and pass-entity filtering. */
  TraceResult traceEntity(int number, TraceRequest request) {
    // Native bot traces can address retained slots beyond the current active-entity count.
    // Keep all accesses inside the QVM allocation and the protocol's 1024 entity slots.
    if (number < 0 || number >= 1024 || stride == 0)
      throw new IllegalArgumentException("Invalid bot trace entity " + number);
    int pointer = Math.addExact(entities, Math.multiplyExact(number, stride));
    VmAbi.range(memory, pointer, abi.sharedEntityBytes());
    if ((memory.readInt(pointer + abi.contents()) & request.contentsMask()) == 0)
      return TraceResult.clear(request);
    return collision(pointer, number).trace(request.ignoring(-1));
  }

  TraceResult trace(TraceRequest request) {
    List<TraceWorld> providers = new ArrayList<>();
    providers.add(terrain);
    for (Linked entity : linked.values()) {
      if (skipForTrace(entity, request.ignoreEntity())) continue;
      if ((memory.readInt(entity.pointer() + abi.contents()) & request.contentsMask()) == 0)
        continue;
      providers.add(entity.collision());
    }
    return new CompositeTraceWorld(providers).trace(request);
  }

  private boolean skipForTrace(Linked entity, int ignored) {
    if (ignored < 0 || ignored == 1023) return false;
    if (entity.number() == ignored || entity.owner() == ignored) return true;
    if (ignored < count) {
      int ignoredOwner = memory.readInt(pointer(ignored) + abi.owner());
      if (ignoredOwner >= 0 && ignoredOwner < 1022) return entity.owner() == ignoredOwner;
    }
    return false;
  }

  int pointContents(Vec3 point, int ignored) {
    int contents = terrain.pointContents(point, -1, ignored);
    for (Linked entity : linked.values())
      if (entity.number() != ignored)
        contents |= entity.collision().pointContents(point, -1, ignored);
    return contents;
  }

  void armDamage(int amount) {
    if (amount != 0 && !damageModels.containsValue(amount))
      throw new IllegalStateException("No original hurt adapter for damage " + amount);
    armedDamage = amount;
  }

  List<Integer> entitiesInBox(Vec3 mins, Vec3 maxs, int maximum) {
    if (maximum < 0 || maximum > 1024)
      throw new IllegalArgumentException("Invalid entity list capacity");
    return linked.values().stream()
        .filter(
            entity ->
                damageEntities.containsKey(entity.number())
                    ? armedDamage != 0 && damageEntities.get(entity.number()) == armedDamage
                    : intersects(entity.bounds(), mins, maxs))
        .map(Linked::number)
        .limit(maximum)
        .toList();
  }

  boolean contact(Vec3 mins, Vec3 maxs, int pointer) {
    var damage = damageEntities.get(number(pointer));
    if (damage != null) return armedDamage != 0 && damage == armedDamage;
    TraceWorld shape = collision(pointer, number(pointer));
    return shape.trace(new TraceRequest(ZERO, ZERO, mins, maxs, -1, -1)).startSolid();
  }

  /** The private server association survives unlink and guest bound changes until relink. */
  List<Integer> areas(int pointer) {
    return areaAssociations.getOrDefault(number(pointer), List.of());
  }

  private List<Integer> associateAreas(BspMap.Bounds bounds) {
    int first = -1, second = -1;
    // Native SV_LinkEntity requests 128 ordered leaves, independently of visibility clusters.
    for (int leaf : BspBoxLeaves.query(map, bounds, 128).leaves()) {
      int area = map.leaves().get(leaf).area();
      if (area < 0 || area == first) continue;
      if (first < 0) first = area;
      else second = area; // Keep the first valid area and the last different one.
    }
    return first < 0 ? List.of() : second < 0 ? List.of(first) : List.of(first, second);
  }

  private static boolean intersects(BspMap.Bounds bounds, Vec3 lo, Vec3 hi) {
    return bounds.max().x() >= lo.x()
        && bounds.min().x() <= hi.x()
        && bounds.max().y() >= lo.y()
        && bounds.min().y() <= hi.y()
        && bounds.max().z() >= lo.z()
        && bounds.min().z() <= hi.z();
  }
}
