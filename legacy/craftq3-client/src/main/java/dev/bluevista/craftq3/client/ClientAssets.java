package dev.bluevista.craftq3.client;

import dev.bluevista.craftq3.assets.audio.WavReader;
import dev.bluevista.craftq3.assets.bsp.BspMap;
import dev.bluevista.craftq3.assets.bsp.BspReader;
import dev.bluevista.craftq3.assets.image.ImageLoader;
import dev.bluevista.craftq3.assets.image.Q3Image;
import dev.bluevista.craftq3.assets.md3.Md3Model;
import dev.bluevista.craftq3.assets.md3.Md3Reader;
import dev.bluevista.craftq3.assets.md3.SkinParser;
import dev.bluevista.craftq3.assets.shader.ShaderDefinition;
import dev.bluevista.craftq3.assets.shader.ShaderLibrary;
import dev.bluevista.craftq3.core.fs.VirtualFileSystem;
import dev.bluevista.craftq3.core.fs.VirtualPath;
import dev.bluevista.craftq3.platform.audio.AudioBackend;
import dev.bluevista.craftq3.render.SceneAssets;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/** Per-cgame opaque registration handles. Decodes owned assets without GPU access. */
final class ClientAssets {
  record Model(String name, Md3Model md3, int inline) {}

  private static final int MAX_HANDLES = 8192;
  private final VirtualFileSystem fs;
  private final AudioBackend audio;
  private final Consumer<String> output;
  private final ShaderLibrary library;
  private final Map<String, Integer> modelHandles = new LinkedHashMap<>(),
      skinHandles = new LinkedHashMap<>(),
      shaderHandles = new LinkedHashMap<>(),
      soundHandles = new LinkedHashMap<>();
  private final List<Model> models = new ArrayList<>();
  private final List<SkinParser.Skin> skins = new ArrayList<>();
  private final List<String> shaderNames = new ArrayList<>();
  private final Map<String, ShaderDefinition> shaders = new LinkedHashMap<>();
  private final Map<String, Q3Image> images = new LinkedHashMap<>();
  private final Map<Integer, Double> soundDurations = new LinkedHashMap<>();
  private long imageBytes, modelBytes, soundBytes;
  private SceneAssets snapshot;
  private BspMap world;
  private BspMap externalWorld;
  private String worldName;

  ClientAssets(VirtualFileSystem fs, AudioBackend audio, Consumer<String> output)
      throws IOException {
    this.fs = fs;
    this.audio = audio;
    this.output = output;
    library = ShaderLibrary.load(fs);
    models.add(new Model("", null, -1));
    skins.add(new SkinParser.Skin(Map.of()));
    shaderNames.add("white");
    shader("white", false, false);
  }

  void externalWorld(BspMap metadata) {
    if (world != null) throw new IllegalStateException("World already loaded");
    externalWorld = metadata;
  }

  BspMap loadWorld(String name) throws IOException {
    String path = new VirtualPath(name).value();
    if (world != null) {
      if (!path.equals(worldName))
        throw new IllegalStateException("cgame attempted to change world without restart");
      return world;
    }
    world = externalWorld == null ? BspReader.read(fs.read(new VirtualPath(path))) : externalWorld;
    worldName = path;
    return world;
  }

  BspMap world() {
    if (world == null) throw new IllegalStateException("No cgame world loaded");
    return world;
  }

  int model(String name) throws IOException {
    if (name.isEmpty()) return 0;
    String path = name.startsWith("*") ? name : new VirtualPath(name).value();
    Integer existing = modelHandles.get(path);
    if (existing != null) return existing;
    budget(modelHandles.size());
    Model value;
    if (path.startsWith("*")) {
      int index = Integer.parseInt(path.substring(1));
      if (index < 1 || index >= world().models().size())
        throw new IllegalArgumentException("Invalid inline render model");
      var model = world.models().get(index);
      for (int face = model.firstFace(); face < model.firstFace() + model.faceCount(); face++) {
        var surface = world.faces().get(face);
        shader(world.textures().get(surface.texture()).name(), surface.lightmap() >= 0, false);
      }
      value = new Model(path, null, index);
    } else {
      if (fs.which(new VirtualPath(path)).isEmpty()) {
        output.accept("Missing cgame model: " + path + "\n");
        modelHandles.put(path, 0);
        return 0;
      }
      if (!path.endsWith(".md3"))
        throw new UnsupportedOperationException("Unsupported model format: " + path);
      byte[] encoded = fs.read(new VirtualPath(path));
      if ((modelBytes += encoded.length) > 64L * 1024 * 1024)
        throw new IllegalStateException("Cgame encoded model budget exceeded");
      var model = Md3Reader.read(encoded);
      for (var surface : model.surfaces())
        for (var entry : surface.shaders())
          if (!entry.name().isEmpty()) shader(entry.name(), false, true);
      value = new Model(path, model, -1);
    }
    int handle = models.size();
    models.add(value);
    modelHandles.put(path, handle);
    return handle;
  }

  Model model(int handle) {
    if (handle < 0 || handle >= models.size())
      throw new IllegalArgumentException("Invalid cgame model handle");
    return models.get(handle);
  }

  int skin(String name) throws IOException {
    if (name.isEmpty()) return 0;
    String path = new VirtualPath(name).value();
    Integer existing = skinHandles.get(path);
    if (existing != null) return existing;
    budget(skinHandles.size());
    if (fs.which(new VirtualPath(path)).isEmpty()) {
      output.accept("Missing cgame skin: " + path + "\n");
      skinHandles.put(path, 0);
      return 0;
    }
    var value =
        SkinParser.parse(new String(fs.read(new VirtualPath(path)), StandardCharsets.ISO_8859_1));
    for (String shader : value.surfaces().values()) shader(shader, false, true);
    int handle = skins.size();
    skins.add(value);
    skinHandles.put(path, handle);
    return handle;
  }

  Optional<SkinParser.Skin> skin(int handle) {
    if (handle < 0 || handle >= skins.size())
      throw new IllegalArgumentException("Invalid cgame skin handle");
    return handle == 0 ? Optional.empty() : Optional.of(skins.get(handle));
  }

  int shader(String name, boolean lightmapped, boolean model) throws IOException {
    if ("".equals(name)) return 0;
    String key = ShaderDefinition.canonicalName(name);
    Integer existing = shaderHandles.get(key);
    if (existing != null) return existing;
    budget(shaderNames.size());
    var definition = library.resolve(key, lightmapped);
    if (key.equals("white") && library.find(key).isEmpty()) {
      definition = ShaderDefinition.implicit("white", false);
      images.put("white", ImageLoader.whiteTexture());
    } else if (model && library.find(key).isEmpty()) {
      var stage = definition.stages().getFirst();
      var lit =
          new ShaderDefinition.Stage(
              stage.texture(),
              stage.blend(),
              stage.alphaFunc(),
              ShaderDefinition.RgbGen.of(ShaderDefinition.RgbGenType.LIGHTING_DIFFUSE),
              stage.alphaGen(),
              stage.tcGen(),
              stage.tcMods(),
              stage.depthFunc(),
              stage.depthWrite(),
              stage.detail());
      definition =
          new ShaderDefinition(
              key,
              List.of(lit),
              definition.cull(),
              definition.sort(),
              definition.polygonOffset(),
              definition.noMipmaps(),
              definition.noPicmip(),
              definition.surfaceParms(),
              definition.sky(),
              definition.fog(),
              definition.deforms(),
              definition.portal(),
              definition.clampTime());
    }
    if (!model && !lightmapped && library.find(key).isEmpty()) {
      var stage = definition.stages().getFirst();
      var overlay =
          new ShaderDefinition.Stage(
              stage.texture(),
              ShaderDefinition.Blend.ALPHA,
              stage.alphaFunc(),
              ShaderDefinition.RgbGen.of(ShaderDefinition.RgbGenType.VERTEX),
              ShaderDefinition.AlphaGen.of(ShaderDefinition.AlphaGenType.VERTEX),
              stage.tcGen(),
              stage.tcMods(),
              stage.depthFunc(),
              false,
              stage.detail());
      definition =
          new ShaderDefinition(
              key,
              List.of(overlay),
              definition.cull(),
              definition.sort(),
              definition.polygonOffset(),
              definition.noMipmaps(),
              definition.noPicmip(),
              definition.surfaceParms(),
              definition.sky(),
              definition.fog(),
              definition.deforms(),
              definition.portal(),
              definition.clampTime());
    }
    for (var stage : definition.stages())
      for (String frame : stage.texture().frames()) {
        if (frame.startsWith("$") || images.containsKey(frame)) continue;
        if (frame.equals("*white")) {
          images.put(frame, ImageLoader.whiteTexture());
          continue;
        }
        if (frame.equals("*default")) {
          images.put(frame, ImageLoader.missingTexture());
          continue;
        }
        var image = ImageLoader.load(fs, frame);
        if ((imageBytes += (long) image.image().width() * image.image().height() * 4)
            > 256L * 1024 * 1024) throw new IllegalStateException("Cgame texture budget exceeded");
        images.put(frame, image.image());
        image.diagnostic().ifPresent(message -> output.accept(message + "\n"));
      }
    shaders.put(key, definition);
    snapshot = null;
    int handle = shaderNames.size();
    shaderNames.add(key);
    shaderHandles.put(key, handle);
    return handle;
  }

  String shader(int handle) {
    if (handle < 0 || handle >= shaderNames.size())
      throw new IllegalArgumentException("Invalid cgame shader handle");
    return shaderNames.get(handle);
  }

  int sound(String name) throws IOException {
    if (name.isEmpty()) return 0;
    String path = new VirtualPath(name).value();
    if (path.lastIndexOf('.') <= path.lastIndexOf('/')) path += ".wav";
    Integer existing = soundHandles.get(path);
    if (existing != null) return existing;
    budget(soundHandles.size());
    if (fs.which(new VirtualPath(path)).isEmpty()) {
      output.accept("Missing cgame sound: " + path + "\n");
      soundHandles.put(path, 0);
      return 0;
    }
    byte[] encoded = fs.read(new VirtualPath(path));
    if ((soundBytes += encoded.length) > 128L * 1024 * 1024)
      throw new IllegalStateException("Cgame sound budget exceeded");
    var pcm = WavReader.read(encoded);
    int handle = audio.register(path, pcm);
    soundDurations.put(handle, pcm.durationSeconds());
    soundHandles.put(path, handle);
    return handle;
  }

  double soundDuration(int handle) {
    return soundDurations.getOrDefault(handle, 0.0);
  }

  SceneAssets snapshot() {
    if (snapshot == null) snapshot = new SceneAssets(shaders, images);
    return snapshot;
  }

  int models() {
    return models.size() - 1;
  }

  int shaders() {
    return shaders.size();
  }

  int sounds() {
    return soundHandles.size();
  }

  private static void budget(int size) {
    if (size >= MAX_HANDLES) throw new IllegalStateException("Cgame asset handle budget exceeded");
  }
}
