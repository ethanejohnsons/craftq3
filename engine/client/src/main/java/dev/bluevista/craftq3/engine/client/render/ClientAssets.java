package dev.bluevista.craftq3.engine.client.render;

import dev.bluevista.craftq3.engine.common.log.Log;
import dev.bluevista.craftq3.engine.formats.AssetLookup;
import dev.bluevista.craftq3.engine.formats.QPath;
import dev.bluevista.craftq3.engine.formats.bsp.BspMap;
import dev.bluevista.craftq3.engine.formats.image.ImageLoader;
import dev.bluevista.craftq3.engine.formats.image.LoadedImage;
import dev.bluevista.craftq3.engine.formats.image.Q3Image;
import dev.bluevista.craftq3.engine.formats.md3.Md3Model;
import dev.bluevista.craftq3.engine.formats.md3.Md3Reader;
import dev.bluevista.craftq3.engine.formats.md3.SkinParser;
import dev.bluevista.craftq3.engine.formats.shader.ShaderDefinition;
import dev.bluevista.craftq3.engine.formats.shader.ShaderDefinition.AlphaGen;
import dev.bluevista.craftq3.engine.formats.shader.ShaderDefinition.AlphaGenType;
import dev.bluevista.craftq3.engine.formats.shader.ShaderDefinition.RgbGen;
import dev.bluevista.craftq3.engine.formats.shader.ShaderDefinition.RgbGenType;
import dev.bluevista.craftq3.engine.formats.shader.ShaderDefinition.Stage;
import dev.bluevista.craftq3.engine.formats.shader.ShaderLibrary;
import dev.bluevista.craftq3.engine.render.frame.SceneAssets;
import dev.bluevista.craftq3.engine.render.material.MaterialRegistry;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The renderer's registration tables shared by the cgame and ui ({@code RE_Register*}): models,
 * skins and shaders by handle, the world map for inline models, and the decoded materials and
 * images handed to the platform with every frame ({@link #snapshot()}).
 *
 * <ul>
 *   <li><b>Shaders</b> are indices of a {@link MaterialRegistry}. Index 0 is the default shader: a
 *       name with neither a script definition nor an image registers as 0, as the original returns
 *       the default shader's handle 0 that guests test for. Shaders registered by the cgame/ui
 *       without a script get the 2D implicit material (vertex colour and alpha, alpha blended);
 *       model surface shaders get diffuse lighting.
 *   <li><b>Models</b>: MD3 files, or {@code *N} inline models of the loaded world. Handle 0 is "no
 *       model"; a missing or unreadable file registers as 0.
 *   <li><b>Skins</b>: {@code .skin} files; handle 0 is "no skin".
 * </ul>
 *
 * Failures never throw into a VM: they log once and return 0. Single owner thread.
 */
public final class ClientAssets {
  /** Handles per table. */
  public static final int MAX_HANDLES = 8192;

  /** Decoded image bytes kept at most. */
  public static final long MAX_IMAGE_BYTES = SceneAssets.MAX_IMAGE_BYTES;

  /** The default shader's name (handle 0). */
  public static final String DEFAULT_SHADER = "*default";

  /** How a shader is used, which decides its implicit material. */
  public enum Usage {
    /** {@code trap_R_RegisterShader}/{@code NoMip}: 2D and effects (LIGHTMAP_2D). */
    TWO_D,
    /** Model surfaces and skins (LIGHTMAP_NONE). */
    MODEL,
    /** World and inline model surfaces. */
    LIGHTMAPPED
  }

  /** A registered model. {@code inline} is the BSP submodel index or -1. */
  public record Model(String name, Md3Model md3, int inline) {}

  private static final Log LOG = Log.get(ClientAssets.class);

  private final AssetLookup assets;
  private final boolean decodeImages;
  private final ShaderLibrary library;
  private final MaterialRegistry materials;
  private final Map<String, Integer> shaderHandles = new HashMap<>();
  private final Map<String, ShaderDefinition> shaderDefinitions = new LinkedHashMap<>();
  private final Map<String, Q3Image> images = new LinkedHashMap<>();
  private final Map<String, Integer> modelHandles = new HashMap<>();
  private final List<Model> models = new ArrayList<>();
  private final Map<String, Integer> skinHandles = new HashMap<>();
  private final List<SkinParser.Skin> skins = new ArrayList<>();
  private final Map<String, String> remaps = new HashMap<>();
  private long imageBytes;
  private SceneAssets snapshot;
  private BspMap world;
  private String worldName = "";

  /**
   * @param decodeImages decode stage images into the snapshot (false for headless runs that only
   *     need handles)
   */
  public ClientAssets(AssetLookup assets, boolean decodeImages) {
    this.assets = Objects.requireNonNull(assets);
    this.decodeImages = decodeImages;
    this.library = ShaderLibrary.load(assets);
    ShaderDefinition fallback = ShaderDefinition.implicit("default", false);
    this.materials = new MaterialRegistry((name, lightmapped) -> fallback, List.of(fallback));
    shaderDefinitions.put(DEFAULT_SHADER, fallback);
    models.add(new Model("", null, -1));
    skins.add(new SkinParser.Skin(Map.of(), List.of()));
  }

  public AssetLookup lookup() {
    return assets;
  }

  public ShaderLibrary library() {
    return library;
  }

  public MaterialRegistry materials() {
    return materials;
  }

  // ---------------------------------------------------------------- world

  /** {@code RE_LoadWorldMap}; repeated loads of the same map are free. */
  public Optional<BspMap> loadWorld(String name, BspMap map) {
    world = map;
    worldName = QPath.normalize(name);
    return Optional.ofNullable(world);
  }

  public Optional<BspMap> world() {
    return Optional.ofNullable(world);
  }

  public String worldName() {
    return worldName;
  }

  // ---------------------------------------------------------------- shaders

  /** {@code RE_RegisterShader*}: the handle of {@code name}, 0 for the default shader. */
  public int shader(String name, Usage usage) {
    if (name == null || name.isBlank()) return 0;
    String key;
    try {
      key = ShaderDefinition.canonicalName(name);
    } catch (IllegalArgumentException invalid) {
      return 0;
    }
    String handleKey = usage.ordinal() + ":" + key;
    Integer known = shaderHandles.get(handleKey);
    if (known != null) return known;
    if (materials.size() >= MAX_HANDLES) {
      LOG.warnOnce("shader-full", "Shader table full; " + name + " uses the default shader");
      return 0;
    }
    Optional<ShaderDefinition> scripted = library.find(key);
    ShaderDefinition definition;
    if (scripted.isPresent()) definition = scripted.get();
    else {
      if (ImageLoader.find(assets, key).isEmpty()) {
        shaderHandles.put(handleKey, 0);
        return 0;
      }
      definition = implicit(key, usage);
    }
    int handle;
    try {
      handle = materials.add(definition);
    } catch (RuntimeException invalid) {
      LOG.warnOnce("shader:" + key, "Cannot compile shader " + key + ": " + invalid.getMessage());
      handle = 0;
    }
    shaderHandles.put(handleKey, handle);
    if (handle != 0 && !shaderDefinitions.containsKey(key)) {
      shaderDefinitions.put(key, definition);
      decode(definition);
      snapshot = null;
    }
    return handle;
  }

  /** The canonical name the frame uses for shader {@code handle} (after remapping). */
  public String shaderName(int handle) {
    if (handle <= 0 || handle >= materials.size()) return DEFAULT_SHADER;
    String name = materials.definition(handle).name();
    return remaps.getOrDefault(name, name);
  }

  /** {@code RE_RemapShader(old, new, offsetTime)}: frames name {@code newName} for {@code old}. */
  public void remap(String oldName, String newName) {
    String from = ShaderDefinition.canonicalName(oldName);
    String to = ShaderDefinition.canonicalName(newName);
    shader(to, Usage.LIGHTMAPPED);
    if (from.equals(to)) remaps.remove(from);
    else remaps.put(from, to);
  }

  /** A name the frame uses for a shader referenced by name (model surfaces, skins). */
  public String surfaceShader(String name) {
    String key = ShaderDefinition.canonicalName(name);
    return remaps.getOrDefault(key, key);
  }

  private static ShaderDefinition implicit(String key, Usage usage) {
    ShaderDefinition base = ShaderDefinition.implicit(key, usage == Usage.LIGHTMAPPED);
    if (usage == Usage.LIGHTMAPPED) return base;
    Stage stage = base.stages().getFirst();
    Stage replaced =
        usage == Usage.TWO_D
            ? new Stage(
                stage.texture(),
                ShaderDefinition.Blend.ALPHA,
                stage.alphaFunc(),
                RgbGen.of(RgbGenType.VERTEX),
                AlphaGen.of(AlphaGenType.VERTEX),
                stage.tcGen(),
                stage.tcMods(),
                stage.depthFunc(),
                false,
                stage.detail())
            : new Stage(
                stage.texture(),
                stage.blend(),
                stage.alphaFunc(),
                RgbGen.of(RgbGenType.LIGHTING_DIFFUSE),
                stage.alphaGen(),
                stage.tcGen(),
                stage.tcMods(),
                stage.depthFunc(),
                stage.depthWrite(),
                stage.detail());
    return new ShaderDefinition(
        base.name(),
        List.of(replaced),
        base.cull(),
        base.sort(),
        base.polygonOffset(),
        base.noMipmaps(),
        base.noPicmip(),
        base.surfaceParms(),
        base.sky(),
        base.fog(),
        base.deforms(),
        base.portal(),
        base.clampTime());
  }

  private void decode(ShaderDefinition definition) {
    if (!decodeImages) return;
    for (Stage stage : definition.stages())
      for (String frame : stage.texture().frames()) {
        if (frame.startsWith("$") || images.containsKey(frame)) continue;
        LoadedImage image = ImageLoader.load(assets, frame);
        long bytes = (long) image.image().width() * image.image().height() * 4;
        if (imageBytes + bytes > MAX_IMAGE_BYTES) {
          LOG.warnOnce("image-budget", "Decoded image budget exceeded; " + frame + " not loaded");
          continue;
        }
        imageBytes += bytes;
        images.put(frame, image.image());
      }
  }

  // ---------------------------------------------------------------- models and skins

  /** {@code RE_RegisterModel}. */
  public int model(String name) {
    if (name == null || name.isBlank()) return 0;
    String path = name.startsWith("*") ? name : QPath.normalize(name);
    Integer known = modelHandles.get(path);
    if (known != null) return known;
    if (models.size() >= MAX_HANDLES) {
      LOG.warnOnce("model-full", "Model table full; cannot register " + name);
      return 0;
    }
    Model model = path.startsWith("*") ? inline(path) : md3(path);
    if (model == null) {
      modelHandles.put(path, 0);
      return 0;
    }
    int handle = models.size();
    models.add(model);
    modelHandles.put(path, handle);
    return handle;
  }

  private Model inline(String path) {
    if (world == null) return null;
    int index;
    try {
      index = Integer.parseInt(path.substring(1));
    } catch (NumberFormatException invalid) {
      return null;
    }
    if (index < 1 || index >= world.models().size()) return null;
    BspMap.Model model = world.models().get(index);
    for (int face = model.firstFace(); face < model.firstFace() + model.faceCount(); face++) {
      int texture = world.faces().texture(face);
      if (texture >= 0 && texture < world.textures().size())
        shader(world.textures().get(texture).name(), Usage.LIGHTMAPPED);
    }
    return new Model(path, null, index);
  }

  private Model md3(String path) {
    String file = QPath.extension(path).isEmpty() ? path + ".md3" : path;
    Optional<byte[]> bytes = assets.read(file);
    if (bytes.isEmpty()) {
      LOG.debug("Missing model " + file);
      return null;
    }
    Md3Model md3;
    try {
      md3 = Md3Reader.read(bytes.get(), file);
    } catch (RuntimeException invalid) {
      LOG.warnOnce("md3:" + file, "Cannot read model " + file + ": " + invalid.getMessage());
      return null;
    }
    for (Md3Model.Surface surface : md3.surfaces())
      for (Md3Model.Shader shader : surface.shaders())
        if (!shader.name().isEmpty()) shader(shader.name(), Usage.MODEL);
    return new Model(path, md3, -1);
  }

  /** The model of {@code handle}; handle 0 and invalid handles are "no model". */
  public Model model(int handle) {
    return handle > 0 && handle < models.size() ? models.get(handle) : models.getFirst();
  }

  /** {@code RE_RegisterSkin}. */
  public int skin(String name) {
    if (name == null || name.isBlank()) return 0;
    String path = QPath.normalize(name);
    Integer known = skinHandles.get(path);
    if (known != null) return known;
    if (skins.size() >= MAX_HANDLES) return 0;
    Optional<byte[]> bytes = assets.read(path);
    if (bytes.isEmpty()) {
      skinHandles.put(path, 0);
      return 0;
    }
    SkinParser.Skin skin;
    try {
      skin = SkinParser.parse(new String(bytes.get(), StandardCharsets.ISO_8859_1));
    } catch (RuntimeException invalid) {
      LOG.warnOnce("skin:" + path, "Cannot read skin " + path + ": " + invalid.getMessage());
      skinHandles.put(path, 0);
      return 0;
    }
    for (String shader : skin.surfaces().values()) shader(shader, Usage.MODEL);
    int handle = skins.size();
    skins.add(skin);
    skinHandles.put(path, handle);
    return handle;
  }

  public Optional<SkinParser.Skin> skin(int handle) {
    return handle > 0 && handle < skins.size() ? Optional.of(skins.get(handle)) : Optional.empty();
  }

  // ---------------------------------------------------------------- frames

  /** The registered materials and images, shared by every frame until something new registers. */
  public SceneAssets snapshot() {
    if (snapshot == null) snapshot = new SceneAssets(shaderDefinitions, images);
    return snapshot;
  }

  public int modelCount() {
    return models.size() - 1;
  }

  public int shaderCount() {
    return materials.size() - 1;
  }

  public int skinCount() {
    return skins.size() - 1;
  }
}
