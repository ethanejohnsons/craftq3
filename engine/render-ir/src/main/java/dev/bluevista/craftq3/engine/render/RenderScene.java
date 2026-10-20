package dev.bluevista.craftq3.engine.render;

import dev.bluevista.craftq3.engine.common.math.Vec3f;
import dev.bluevista.craftq3.engine.formats.bsp.BspMap;
import dev.bluevista.craftq3.engine.formats.entity.BspEntity;
import dev.bluevista.craftq3.engine.formats.shader.ShaderDefinition;
import dev.bluevista.craftq3.engine.render.material.MaterialProgram;
import dev.bluevista.craftq3.engine.render.material.MaterialRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Everything the renderer keeps per map, independent of the host graphics API: the static {@link
 * WorldGeometry} (uploaded once), the compiled world materials, visibility, light grid, fog volumes
 * and portal surfaces. Immutable; per-view state lives in {@link VisibleSet} and frame buffers.
 */
public final class RenderScene {
  private static final float SPAWN_EYE_HEIGHT = 26;
  private static final Camera FALLBACK_CAMERA = new Camera(new Vec3f(0, 0, 64), 0, 0, 90);

  /** Build options. {@code patchSubdivisions} steps per patch block side (1..16). */
  public record Options(int patchSubdivisions) {
    public static final Options DEFAULT = new Options(8);

    public Options {
      if (patchSubdivisions < 1 || patchSubdivisions > 16)
        throw new IllegalArgumentException("Patch subdivisions must be 1..16");
    }
  }

  private final String name;
  private final BspMap bsp;
  private final WorldGeometry world;
  private final List<MaterialProgram> materials;
  private final List<ShaderDefinition> definitions;
  private final MaterialRegistry.Source shaders;
  private final Visibility visibility;
  private final LightGrid lightGrid;
  private final FogVolumes fog;
  private final Camera spawn;
  private final int[] portalSurfaces;
  private final java.util.Map<Integer, PortalView> staticPortals;

  private RenderScene(
      String name,
      BspMap bsp,
      WorldGeometry world,
      List<MaterialProgram> materials,
      List<ShaderDefinition> definitions,
      MaterialRegistry.Source shaders) {
    this.name = name;
    this.bsp = bsp;
    this.world = world;
    this.materials = List.copyOf(materials);
    this.definitions = List.copyOf(definitions);
    this.shaders = shaders;
    this.visibility = new Visibility(bsp, world, this.materials);
    this.lightGrid = LightGrid.of(bsp);
    this.fog = FogVolumes.from(bsp, effect -> fogShader(shaders, effect));
    this.spawn = spawn(bsp);
    List<Integer> portals = new ArrayList<>();
    for (int s = 0; s < world.surfaceCount(); s++)
      if (world.surfaceBatch(s) >= 0 && this.materials.get(world.surfaceMaterial(s)).portal())
        portals.add(s);
    this.portalSurfaces = portals.stream().mapToInt(Integer::intValue).toArray();
    java.util.Map<Integer, PortalView> linked = new java.util.HashMap<>();
    for (int surface : portalSurfaces)
      PortalView.find(bsp, world, surface).ifPresent(view -> linked.put(surface, view));
    this.staticPortals = java.util.Map.copyOf(linked);
  }

  public static RenderScene build(String name, BspMap map, MaterialRegistry.Source shaders) {
    return build(name, map, shaders, Options.DEFAULT);
  }

  /**
   * Resolves every face's shader ({@code shaders.resolve(texture, lightmapped)}), compiles the
   * materials and tessellates every BSP model into static buffers.
   */
  public static RenderScene build(
      String name, BspMap map, MaterialRegistry.Source shaders, Options options) {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(map, "map");
    Objects.requireNonNull(shaders, "shaders");
    MaterialRegistry registry = new MaterialRegistry(shaders);
    BspMap.Faces faces = map.faces();
    int[] faceMaterial = new int[faces.count()];
    for (int face = 0; face < faces.count(); face++) {
      int texture = faces.texture(face);
      String shader =
          texture >= 0 && texture < map.textures().size()
              ? map.textures().get(texture).name()
              : "$missing";
      faceMaterial[face] = registry.index(shader, faces.lightmap(face) >= 0);
    }
    List<MaterialProgram> programs = registry.programs();
    List<ShaderDefinition> definitions = new ArrayList<>(registry.size());
    for (int i = 0; i < registry.size(); i++) definitions.add(registry.definition(i));
    WorldGeometry world =
        WorldBuilder.build(name, map, options.patchSubdivisions(), faceMaterial, programs);
    return new RenderScene(name, map, world, programs, definitions, shaders);
  }

  public String name() {
    return name;
  }

  public BspMap bsp() {
    return bsp;
  }

  public WorldGeometry world() {
    return world;
  }

  /** World materials, indexed by {@link WorldGeometry#surfaceMaterial} and {@link DrawBatch}. */
  public List<MaterialProgram> materials() {
    return materials;
  }

  public MaterialProgram material(int index) {
    return materials.get(index);
  }

  /** The shader definitions the world materials were compiled from, in the same order. */
  public List<ShaderDefinition> definitions() {
    return definitions;
  }

  /**
   * A registry seeded with the world materials at their indices, resolving further names (cgame
   * registrations) through the scene's shader source.
   */
  public MaterialRegistry newMaterialRegistry() {
    return new MaterialRegistry(shaders, definitions);
  }

  public Visibility visibility() {
    return visibility;
  }

  public LightGrid lightGrid() {
    return lightGrid;
  }

  public FogVolumes fog() {
    return fog;
  }

  /** The first deathmatch or player spawn at eye height, for viewers without a cgame. */
  public Camera spawnCamera() {
    return spawn;
  }

  /** Drawn world surfaces whose material is a portal or mirror. */
  public int[] portalSurfaces() {
    return portalSurfaces.clone();
  }

  /**
   * The portal or mirror a portal surface shows according to the map's {@code misc_portal_*}
   * entities, for views without cgame portal entities.
   */
  public Optional<PortalView> staticPortal(int surface) {
    return Optional.ofNullable(staticPortals.get(surface));
  }

  private static Optional<ShaderDefinition> fogShader(
      MaterialRegistry.Source shaders, String name) {
    ShaderDefinition shader = shaders.resolve(name, false);
    return shader.fog().isPresent() ? Optional.of(shader) : Optional.empty();
  }

  private static Camera spawn(BspMap map) {
    for (BspEntity entity : map.entities()) {
      String kind = entity.getOrDefault("classname", "");
      if (!kind.equals("info_player_deathmatch") && !kind.equals("info_player_start")) continue;
      String[] xyz = entity.getOrDefault("origin", "").trim().split("\\s+");
      if (xyz.length != 3) continue;
      try {
        Vec3f origin =
            new Vec3f(
                Float.parseFloat(xyz[0]),
                Float.parseFloat(xyz[1]),
                Float.parseFloat(xyz[2]) + SPAWN_EYE_HEIGHT);
        return new Camera(origin, Float.parseFloat(entity.getOrDefault("angle", "0")), 0, 90);
      } catch (IllegalArgumentException malformed) {
        // Bad spawn metadata only affects the diagnostic viewpoint; try the next spawn.
        continue;
      }
    }
    return FALLBACK_CAMERA;
  }
}
