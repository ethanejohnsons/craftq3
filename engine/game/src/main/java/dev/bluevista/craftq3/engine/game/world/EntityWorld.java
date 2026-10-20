package dev.bluevista.craftq3.engine.game.world;

import dev.bluevista.craftq3.engine.common.log.Log;
import dev.bluevista.craftq3.engine.formats.bsp.BspBoxLeaves;
import dev.bluevista.craftq3.engine.vmabi.layout.TraceLayout;
import java.util.Arrays;
import java.util.Objects;

/**
 * The server's view of linked game entities ({@code sv_world.c}): the area-node sector tree,
 * per-entity cluster and area membership, and the world queries the game and botlib use (traces,
 * point contents, entity lists, PVS, area portals).
 *
 * <p>Linking follows {@code SV_LinkEntity}: it encodes {@code s.solid}, computes {@code
 * r.absmin}/{@code r.absmax} (radius-expanded for rotated brush models, then one unit of slack),
 * records up to {@link #MAX_ENT_CLUSTERS} clusters (with a last-cluster overflow marker) and up to
 * two areas from at most {@link #MAX_TOTAL_ENT_LEAFS} leaves, bumps {@code r.linkcount}, inserts
 * the entity at the head of the deepest sector node its box does not cross and sets {@code
 * r.linked}. Traces compose the world model, inline models of brush entities and temporary box or
 * capsule models of everything else, with the pass-entity and owner exclusions of {@code
 * SV_ClipMoveToEntities}.
 *
 * <p>Owned by the simulation thread; all scratch buffers are fields.
 */
public final class EntityWorld {
  /** {@code AREA_DEPTH}: the sector tree's depth. */
  public static final int AREA_DEPTH = 4;

  /** {@code AREA_NODES}. */
  public static final int AREA_NODES = 64;

  /** {@code MAX_ENT_CLUSTERS}. */
  public static final int MAX_ENT_CLUSTERS = 16;

  /** {@code MAX_TOTAL_ENT_LEAFS}. */
  public static final int MAX_TOTAL_ENT_LEAFS = 128;

  private static final Log LOG = Log.get(EntityWorld.class);
  private static final int N = GameEntities.MAX_GENTITIES;
  private static final float[] ZERO = new float[3];

  private final MapVisibility visibility;
  private final WorldCollision collision;
  private GameEntities entities;

  // Sector tree.
  private final int[] nodeAxis = new int[AREA_NODES];
  private final float[] nodeDist = new float[AREA_NODES];
  private final int[] nodeFront = new int[AREA_NODES];
  private final int[] nodeBack = new int[AREA_NODES];
  private final int[] nodeHead = new int[AREA_NODES];
  private int nodeCount;

  // Per-entity server state (svEntity_t).
  private final int[] sector = new int[N];
  private final int[] nextInSector = new int[N];
  private final int[] areaNum = new int[N];
  private final int[] areaNum2 = new int[N];
  private final int[] numClusters = new int[N];
  private final int[] lastCluster = new int[N];
  private final int[] clusters = new int[N * MAX_ENT_CLUSTERS];

  // Scratch.
  private final int[] touch = new int[N];
  private final float[] a = new float[3];
  private final float[] b = new float[3];
  private final float[] c = new float[3];
  private final float[] d = new float[3];
  private final float[] boxMins = new float[3];
  private final float[] boxMaxs = new float[3];
  private final float[] origin = new float[3];
  private final float[] angles = new float[3];
  private final float[] entityOrigin = new float[3];
  private final float[] entityAngles = new float[3];
  private final float[] entityMins = new float[3];
  private final float[] entityMaxs = new float[3];
  private final GameTrace scratch = new GameTrace();
  private int areaQueryCount;
  private boolean areaOverflowWarned;

  public EntityWorld(MapVisibility visibility, WorldCollision collision) {
    this.visibility = Objects.requireNonNull(visibility);
    this.collision = Objects.requireNonNull(collision);
    clear();
  }

  public MapVisibility visibility() {
    return visibility;
  }

  public WorldCollision collision() {
    return collision;
  }

  /** Attaches the game's located entity arrays. */
  public void attach(GameEntities entities) {
    this.entities = Objects.requireNonNull(entities);
  }

  public GameEntities entities() {
    if (entities == null) throw new IllegalStateException("Game data is not located");
    return entities;
  }

  /**
   * {@code SV_ClearWorld}: unlinks everything and rebuilds the sector tree over the world model's
   * bounds.
   */
  public void clear() {
    Arrays.fill(sector, -1);
    Arrays.fill(nextInSector, -1);
    Arrays.fill(areaNum, -1);
    Arrays.fill(areaNum2, -1);
    Arrays.fill(numClusters, 0);
    Arrays.fill(lastCluster, 0);
    nodeCount = 0;
    float[] mins = new float[3], maxs = new float[3];
    if (collision.modelCount() > 0) collision.modelBounds(0, mins, maxs);
    createSector(0, mins, maxs);
  }

  private int createSector(int depth, float[] mins, float[] maxs) {
    int node = nodeCount++;
    nodeHead[node] = -1;
    if (depth == AREA_DEPTH) {
      nodeAxis[node] = -1;
      nodeFront[node] = nodeBack[node] = -1;
      return node;
    }
    int axis = maxs[0] - mins[0] > maxs[1] - mins[1] ? 0 : 1;
    float dist = 0.5f * (maxs[axis] + mins[axis]);
    nodeAxis[node] = axis;
    nodeDist[node] = dist;
    float[] upperMins = mins.clone(), lowerMaxs = maxs.clone();
    upperMins[axis] = dist;
    lowerMaxs[axis] = dist;
    nodeFront[node] = createSector(depth + 1, upperMins, maxs);
    nodeBack[node] = createSector(depth + 1, mins, lowerMaxs);
    return node;
  }

  // ---- linking ---------------------------------------------------------------------------------

  /** {@code SV_UnlinkEntity}. */
  public void unlink(int e) {
    GameEntities g = entities();
    g.setLinked(e, false);
    removeFromSector(e);
  }

  private void removeFromSector(int e) {
    int node = sector[e];
    if (node < 0) return;
    sector[e] = -1;
    if (nodeHead[node] == e) {
      nodeHead[node] = nextInSector[e];
      nextInSector[e] = -1;
      return;
    }
    for (int scan = nodeHead[node]; scan >= 0; scan = nextInSector[scan]) {
      if (nextInSector[scan] == e) {
        nextInSector[scan] = nextInSector[e];
        nextInSector[e] = -1;
        return;
      }
    }
    LOG.warnOnce("unlink-not-found", "SV_UnlinkEntity: entity " + e + " not found in its sector");
  }

  /** Whether the server has the entity in a sector (independent of the guest's flag). */
  public boolean inSector(int e) {
    return sector[e] >= 0;
  }

  /** {@code SV_LinkEntity}. */
  public void link(int e) {
    GameEntities g = entities();
    if (e < 0 || e >= N) throw new IllegalArgumentException("Invalid entity " + e);
    removeFromSector(e);
    boolean bmodel = g.bmodel(e);
    int contents = g.contents(e);
    float[] mins = a, maxs = b;
    g.mins(e, mins);
    g.maxs(e, maxs);
    if (bmodel) g.setSolid(e, Contents.SOLID_BMODEL);
    else if ((contents & (Contents.SOLID | Contents.BODY)) != 0) {
      int i = clampByte((int) maxs[0]);
      int j = clampByte((int) -mins[2]);
      int k = clampByte((int) (maxs[2] + 32));
      g.setSolid(e, (k << 16) | (j << 8) | i);
    } else g.setSolid(e, 0);

    g.currentOrigin(e, origin);
    g.currentAngles(e, angles);
    float[] absmin = c, absmax = d;
    if (bmodel && (angles[0] != 0 || angles[1] != 0 || angles[2] != 0)) {
      float radius = radiusFromBounds(mins, maxs);
      for (int i = 0; i < 3; i++) {
        absmin[i] = origin[i] - radius;
        absmax[i] = origin[i] + radius;
      }
    } else {
      for (int i = 0; i < 3; i++) {
        absmin[i] = origin[i] + mins[i];
        absmax[i] = origin[i] + maxs[i];
      }
    }
    for (int i = 0; i < 3; i++) {
      absmin[i] -= 1;
      absmax[i] += 1;
    }
    g.setAbsBounds(e, absmin, absmax);

    numClusters[e] = 0;
    lastCluster[e] = 0;
    areaNum[e] = -1;
    areaNum2[e] = -1;
    BspBoxLeaves.Result leaves;
    try {
      leaves = visibility.boxLeaves(absmin, absmax, MAX_TOTAL_ENT_LEAFS);
    } catch (IllegalArgumentException | IllegalStateException outside) {
      // Non-finite or inverted bounds: the original would find no usable leaf either.
      return;
    }
    int count = leaves.count();
    if (count == 0) return;
    for (int i = 0; i < count; i++) {
      int area = visibility.leafArea(leaves.leaf(i));
      if (area == -1) continue;
      if (areaNum[e] != -1 && areaNum[e] != area) areaNum2[e] = area;
      else areaNum[e] = area;
    }
    int i = 0;
    for (; i < count; i++) {
      int cluster = visibility.leafCluster(leaves.leaf(i));
      if (cluster == -1) continue;
      clusters[e * MAX_ENT_CLUSTERS + numClusters[e]++] = cluster;
      if (numClusters[e] == MAX_ENT_CLUSTERS) break;
    }
    if (i != count) lastCluster[e] = visibility.leafCluster(leaves.lastLeaf());

    g.incrementLinkCount(e);
    int node = 0;
    while (nodeAxis[node] != -1) {
      int axis = nodeAxis[node];
      if (absmin[axis] > nodeDist[node]) node = nodeFront[node];
      else if (absmax[axis] < nodeDist[node]) node = nodeBack[node];
      else break;
    }
    sector[e] = node;
    nextInSector[e] = nodeHead[node];
    nodeHead[node] = e;
    g.setLinked(e, true);
  }

  private static int clampByte(int value) {
    return value < 1 ? 1 : Math.min(value, 255);
  }

  /** {@code RadiusFromBounds}. */
  static float radiusFromBounds(float[] mins, float[] maxs) {
    float x = Math.max(Math.abs(mins[0]), Math.abs(maxs[0]));
    float y = Math.max(Math.abs(mins[1]), Math.abs(maxs[1]));
    float z = Math.max(Math.abs(mins[2]), Math.abs(maxs[2]));
    return (float) Math.sqrt(x * x + y * y + z * z);
  }

  public int areaNum(int e) {
    return areaNum[e];
  }

  public int areaNum2(int e) {
    return areaNum2[e];
  }

  public int clusterCount(int e) {
    return numClusters[e];
  }

  public int cluster(int e, int index) {
    return clusters[e * MAX_ENT_CLUSTERS + index];
  }

  public int lastCluster(int e) {
    return lastCluster[e];
  }

  // ---- queries ---------------------------------------------------------------------------------

  /**
   * {@code SV_AreaEntities}: entities whose absolute box touches {@code [mins, maxs]}, in sector
   * order, at most {@code max}.
   */
  public int entitiesInBox(float[] mins, float[] maxs, int[] out, int max) {
    GameEntities g = entities();
    areaQueryCount = 0;
    int limit = Math.min(max, out.length);
    areaEntities(g, 0, mins, maxs, out, limit);
    return areaQueryCount;
  }

  private void areaEntities(
      GameEntities g, int node, float[] mins, float[] maxs, int[] out, int max) {
    for (int e = nodeHead[node]; e >= 0; e = nextInSector[e]) {
      if (g.absmin(e, 0) > maxs[0]
          || g.absmin(e, 1) > maxs[1]
          || g.absmin(e, 2) > maxs[2]
          || g.absmax(e, 0) < mins[0]
          || g.absmax(e, 1) < mins[1]
          || g.absmax(e, 2) < mins[2]) continue;
      if (areaQueryCount == max) {
        if (!areaOverflowWarned) LOG.warn("SV_AreaEntities: MAXCOUNT");
        areaOverflowWarned = true;
        return;
      }
      out[areaQueryCount++] = e;
    }
    int axis = nodeAxis[node];
    if (axis == -1) return;
    if (maxs[axis] > nodeDist[node]) areaEntities(g, nodeFront[node], mins, maxs, out, max);
    if (mins[axis] < nodeDist[node]) areaEntities(g, nodeBack[node], mins, maxs, out, max);
  }

  /**
   * {@code SV_Trace}: the world, then every entity in the move's box except the pass entity, its
   * missiles and its owner's other missiles. {@code passEntity} may be {@code ENTITYNUM_NONE}.
   */
  public void trace(
      GameTrace out,
      float[] start,
      float[] mins,
      float[] maxs,
      float[] end,
      int passEntity,
      int contentMask,
      boolean capsule) {
    if (mins == null) mins = ZERO;
    if (maxs == null) maxs = ZERO;
    collision.traceModel(out, start, end, mins, maxs, 0, contentMask, ZERO, ZERO, capsule);
    out.entityNum = out.fraction != 1 ? TraceLayout.ENTITYNUM_WORLD : TraceLayout.ENTITYNUM_NONE;
    if (out.fraction == 0 || entities == null) return;
    float[] lo = boxMins, hi = boxMaxs;
    for (int i = 0; i < 3; i++) {
      if (end[i] > start[i]) {
        lo[i] = start[i] + mins[i] - 1;
        hi[i] = end[i] + maxs[i] + 1;
      } else {
        lo[i] = end[i] + mins[i] - 1;
        hi[i] = start[i] + maxs[i] + 1;
      }
    }
    // Collision models never call back into the world, so the scratch list stays valid.
    int count = entitiesInBox(lo, hi, touch, N);
    clipToEntities(out, start, mins, maxs, end, passEntity, contentMask, capsule, touch, count);
  }

  private void clipToEntities(
      GameTrace out,
      float[] start,
      float[] mins,
      float[] maxs,
      float[] end,
      int passEntity,
      int contentMask,
      boolean capsule,
      int[] list,
      int count) {
    GameEntities g = entities;
    int passOwner = -1;
    if (passEntity != TraceLayout.ENTITYNUM_NONE && passEntity >= 0 && passEntity < N) {
      passOwner = g.ownerNum(passEntity);
      if (passOwner == TraceLayout.ENTITYNUM_NONE) passOwner = -1;
    }
    for (int i = 0; i < count; i++) {
      if (out.allSolid) return;
      int touched = list[i];
      if (touched == passEntity) continue;
      int owner = g.ownerNum(touched);
      if (owner == passEntity) continue;
      if (passOwner != -1 && owner == passOwner) continue;
      if ((contentMask & g.contents(touched)) == 0) continue;
      clipEntity(scratch, start, end, mins, maxs, touched, contentMask, capsule);
      if (scratch.allSolid) {
        out.allSolid = true;
        scratch.entityNum = touched;
      } else if (scratch.startSolid) {
        out.startSolid = true;
        scratch.entityNum = touched;
      }
      if (scratch.fraction < out.fraction) {
        boolean oldStart = out.startSolid;
        scratch.entityNum = touched;
        out.copyFrom(scratch);
        out.startSolid |= oldStart;
      }
    }
  }

  /**
   * {@code CM_TransformedBoxTrace} against one entity's clip model ({@code
   * SV_ClipHandleForEntity}).
   */
  private void clipEntity(
      GameTrace out,
      float[] start,
      float[] end,
      float[] mins,
      float[] maxs,
      int e,
      int contentMask,
      boolean capsule) {
    GameEntities g = entities;
    float[] o = entityOrigin, ang = entityAngles, emins = entityMins, emaxs = entityMaxs;
    g.currentOrigin(e, o);
    if (g.bmodel(e)) {
      g.currentAngles(e, ang);
      collision.traceModel(
          out, start, end, mins, maxs, g.modelIndex(e), contentMask, o, ang, capsule);
    } else {
      g.mins(e, emins);
      g.maxs(e, emaxs);
      boolean entityCapsule = (g.svFlags(e) & Contents.SVF_CAPSULE) != 0;
      collision.traceTempBox(
          out, start, end, mins, maxs, emins, emaxs, entityCapsule, contentMask, o, capsule);
    }
    out.entityNum = TraceLayout.ENTITYNUM_NONE;
  }

  /** {@code SV_ClipToEntity}: a trace against one entity only (botlib's {@code EntityTrace}). */
  public void clipToEntity(
      GameTrace out,
      float[] start,
      float[] mins,
      float[] maxs,
      float[] end,
      int entityNum,
      int contentMask,
      boolean capsule) {
    out.zero();
    if (entities == null || entityNum < 0 || entityNum >= N) {
      out.fraction = 1;
      return;
    }
    if ((contentMask & entities.contents(entityNum)) == 0) {
      out.fraction = 1;
      return;
    }
    clipEntity(
        out,
        start,
        end,
        mins == null ? ZERO : mins,
        maxs == null ? ZERO : maxs,
        entityNum,
        contentMask,
        capsule);
    if (out.fraction < 1) out.entityNum = entities.stateNumber(entityNum);
    else out.entityNum = 0;
  }

  /**
   * {@code SV_PointContents}: the world's contents plus every entity whose clip model contains the
   * point, except {@code passEntity}. Entities are placed at {@code s.origin} with {@code
   * s.angles}, as the original does.
   */
  public int pointContents(float[] point, int passEntity) {
    int contents = collision.pointContents(point, 0, ZERO, ZERO);
    if (entities == null) return contents;
    int count = entitiesInBox(point, point, touch, N);
    GameEntities g = entities;
    float[] o = entityOrigin, ang = entityAngles, mins = entityMins, maxs = entityMaxs;
    for (int i = 0; i < count; i++) {
      int e = touch[i];
      if (e == passEntity) continue;
      g.stateOrigin(e, o);
      if (g.bmodel(e)) {
        g.stateAngles(e, ang);
        contents |= collision.pointContents(point, g.modelIndex(e), o, ang);
      } else {
        g.mins(e, mins);
        g.maxs(e, maxs);
        boolean entityCapsule = (g.svFlags(e) & Contents.SVF_CAPSULE) != 0;
        contents |= collision.tempBoxPointContents(point, mins, maxs, entityCapsule, o);
      }
    }
    return contents;
  }

  /** {@code SV_EntityContact}: whether a box at the origin touches entity {@code e}. */
  public boolean entityContact(float[] mins, float[] maxs, int e, boolean capsule) {
    clipEntity(scratch, ZERO, ZERO, mins, maxs, e, Contents.ALL, capsule);
    return scratch.startSolid;
  }

  /**
   * {@code SV_SetBrushModel}: binds inline model {@code *n} to the entity (model index, bounds,
   * {@code bmodel}, contents -1) and links it.
   */
  public void setBrushModel(int e, String name) {
    if (name == null || name.isEmpty() || name.charAt(0) != '*')
      throw new IllegalArgumentException("SV_SetBrushModel: " + name + " isn't a brush model");
    int model = Integer.parseInt(name.substring(1));
    if (model < 1 || model >= collision.modelCount())
      throw new IllegalArgumentException("SV_SetBrushModel: bad inline model " + name);
    GameEntities g = entities();
    g.setModelIndex(e, model);
    float[] mins = new float[3], maxs = new float[3];
    collision.modelBounds(model, mins, maxs);
    g.setMins(e, mins);
    g.setMaxs(e, maxs);
    g.setBmodel(e, true);
    g.setContents(e, -1);
    link(e);
  }

  /** {@code SV_inPVS} (and, with {@code ignorePortals}, {@code SV_inPVSIgnorePortals}). */
  public boolean inPvs(float[] p1, float[] p2, boolean ignorePortals) {
    int leaf1 = visibility.pointLeaf(p1);
    int cluster1 = visibility.leafCluster(leaf1), area1 = visibility.leafArea(leaf1);
    int leaf2 = visibility.pointLeaf(p2);
    int cluster2 = visibility.leafCluster(leaf2), area2 = visibility.leafArea(leaf2);
    if (!visibility.clusterVisible(cluster1, cluster2)) return false;
    return ignorePortals || visibility.areasConnected(area1, area2);
  }

  /** {@code SV_AdjustAreaPortalState}: opens or closes the portal between the entity's areas. */
  public void adjustAreaPortalState(int e, boolean open) {
    visibility.adjustAreaPortalState(areaNum[e], areaNum2[e], open);
  }
}
