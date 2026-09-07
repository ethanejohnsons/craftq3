package dev.bluevista.craftq3.assets.shader;

import static dev.bluevista.craftq3.assets.shader.ShaderDefinition.*;

import dev.bluevista.craftq3.core.fs.VirtualPath;
import dev.bluevista.craftq3.core.math.Vec3;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/** Independent, bounded reader for the public Q3 shader-script grammar. */
public final class ShaderParser {
  public static final int MAX_SCRIPT_CHARS = 4 * 1024 * 1024;
  private static final int MAX_TOKENS = 500_000;
  private static final int MAX_DEFINITIONS = 16_384;
  private static final int MAX_STAGES = 64;
  private static final int MAX_MODIFIERS = 32;
  private static final int MAX_DIAGNOSTICS = 4096;
  private static final Set<String> SURFACE_PARMS =
      Set.of(
          "alphashadow",
          "areaportal",
          "botclip",
          "clusterportal",
          "detail",
          "donotenter",
          "dust",
          "flesh",
          "fog",
          "hint",
          "ladder",
          "lava",
          "lightfilter",
          "metalsteps",
          "nodamage",
          "nodlight",
          "nodraw",
          "nodrop",
          "noimpact",
          "nolightmap",
          "nomarks",
          "nomipmaps",
          "nonopaque",
          "nonsolid",
          "origin",
          "playerclip",
          "pointlight",
          "shotclip",
          "skip",
          "sky",
          "slick",
          "slime",
          "structural",
          "trans",
          "water",
          "antiportal",
          "monsterclip",
          "nosteps",
          "nofootsteps",
          "nooverbounce");

  private ShaderParser() {}

  public record Result(List<ShaderDefinition> definitions, List<ShaderDiagnostic> diagnostics) {
    public Result {
      definitions = List.copyOf(definitions);
      diagnostics = List.copyOf(diagnostics);
    }
  }

  public static Result parse(String source, String text) throws ShaderFormatException {
    return new Parser(source, tokenize(source, text)).parse();
  }

  private record Token(String text, int line) {}

  private static List<Token> tokenize(String source, String text) throws ShaderFormatException {
    if (text.length() > MAX_SCRIPT_CHARS) {
      throw new ShaderFormatException(source + ": shader script exceeds character limit");
    }
    List<Token> tokens = new ArrayList<>();
    int line = 1;
    for (int i = 0; i < text.length(); ) {
      char c = text.charAt(i);
      if (Character.isWhitespace(c) || c == '\ufeff') {
        if (c == '\n') line++;
        i++;
      } else if (c == '/' && i + 1 < text.length() && text.charAt(i + 1) == '/') {
        while (i < text.length() && text.charAt(i) != '\n') i++;
      } else if (c == '/' && i + 1 < text.length() && text.charAt(i + 1) == '*') {
        int startLine = line;
        i += 2;
        while (i + 1 < text.length() && !(text.charAt(i) == '*' && text.charAt(i + 1) == '/')) {
          if (text.charAt(i++) == '\n') line++;
        }
        if (i + 1 >= text.length()) {
          throw new ShaderFormatException(source + ":" + startLine + ": unterminated comment");
        }
        i += 2;
      } else {
        int tokenLine = line;
        String token;
        if (c == '"') {
          int start = ++i;
          while (i < text.length() && text.charAt(i) != '"') {
            if (text.charAt(i++) == '\n') line++;
          }
          if (i == text.length()) {
            throw new ShaderFormatException(source + ":" + tokenLine + ": unterminated quote");
          }
          token = text.substring(start, i++);
        } else if (c == '{' || c == '}' || c == '(' || c == ')') {
          token = String.valueOf(c);
          i++;
        } else {
          int start = i++;
          while (i < text.length()) {
            c = text.charAt(i);
            if (Character.isWhitespace(c)
                || c == '{'
                || c == '}'
                || c == '('
                || c == ')'
                || (c == '/'
                    && i + 1 < text.length()
                    && (text.charAt(i + 1) == '/' || text.charAt(i + 1) == '*'))) break;
            i++;
          }
          token = text.substring(start, i);
        }
        if (token.length() > 4096 || tokens.size() >= MAX_TOKENS) {
          throw new ShaderFormatException(source + ":" + line + ": shader token limit exceeded");
        }
        tokens.add(new Token(token, tokenLine));
      }
    }
    return tokens;
  }

  private static final class Parser {
    private final String source;
    private final List<Token> tokens;
    private final List<ShaderDiagnostic> diagnostics = new ArrayList<>();
    private int cursor;
    private int directiveLine;

    Parser(String source, List<Token> tokens) {
      this.source = source;
      this.tokens = tokens;
    }

    Result parse() throws ShaderFormatException {
      List<ShaderDefinition> definitions = new ArrayList<>();
      while (cursor < tokens.size()) {
        if (definitions.size() >= MAX_DEFINITIONS) throw malformed("too many shader definitions");
        Token name = tokens.get(cursor++);
        MaterialBuilder material;
        try {
          material = new MaterialBuilder(canonicalName(name.text));
        } catch (IllegalArgumentException ex) {
          throw malformed("invalid shader name at line " + name.line + ": " + name.text);
        }
        expect("{");
        while (!accept("}")) {
          if (cursor == tokens.size()) throw malformed("unterminated shader " + name.text);
          if (accept("{")) {
            if (material.stages.size() >= MAX_STAGES) throw malformed("too many shader stages");
            material.stages.add(stage());
            continue;
          }
          Token directive = tokens.get(cursor++);
          directiveLine = directive.line;
          try {
            materialDirective(material, directive.text.toLowerCase(Locale.ROOT));
          } catch (IllegalArgumentException ex) {
            warn(directive.line, directive.text + ": " + ex.getMessage());
            skipLine();
          }
        }
        definitions.add(material.build());
      }
      return new Result(definitions, diagnostics);
    }

    private Stage stage() throws ShaderFormatException {
      StageBuilder stage = new StageBuilder();
      while (!accept("}")) {
        if (cursor == tokens.size()) throw malformed("unterminated shader stage");
        if (peek("{")) throw malformed("nested shader stages are invalid");
        Token directive = tokens.get(cursor++);
        directiveLine = directive.line;
        try {
          stageDirective(stage, directive.text.toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
          warn(directive.line, directive.text + ": " + ex.getMessage());
          skipLine();
        }
      }
      if (stage.texture == null) {
        warn(directiveLine, "stage has no texture map; using $whiteimage");
        stage.texture = new TextureMap(List.of("$whiteimage"), 0, false);
      }
      return stage.build();
    }

    private void materialDirective(MaterialBuilder m, String directive)
        throws ShaderFormatException {
      switch (directive) {
        case "cull" ->
            m.cull =
                switch (lower()) {
                  case "front", "frontside" -> Cull.FRONT;
                  case "back", "backside", "backsided" -> Cull.BACK;
                  case "none", "disable", "twosided" -> Cull.NONE;
                  default -> throw bad("unknown cull mode");
                };
        case "sort" -> m.sort = sort(argument());
        case "polygonoffset" -> m.polygonOffset = true;
        case "nomipmaps" -> {
          m.noMipmaps = true;
          m.noPicmip = true;
        }
        case "nopicmip" -> m.noPicmip = true;
        case "portal" -> {
          m.portal = true;
          m.sort = 1f;
        }
        case "clamptime" -> m.clampTime = nonnegative(number(), "clampTime");
        case "surfaceparm" -> {
          String parm = lower();
          m.surfaceParms.add(parm);
          if (!SURFACE_PARMS.contains(parm)) warn(directiveLine, "unknown surfaceparm " + parm);
        }
        case "skyparms" -> {
          String far = skyBox(argument());
          String heightToken = argument();
          float height = heightToken.equals("-") ? 0 : finite(heightToken);
          m.sky = new Sky(far, height == 0 ? 512 : height, skyBox(argument()));
        }
        case "fogparms" -> m.fog = new Fog(vector(), positive(number(), "fog depth"));
        case "deformvertexes" -> {
          if (m.deforms.size() >= MAX_MODIFIERS) throw malformed("too many vertex deformations");
          m.deforms.add(deform());
        }
        // These affect compilation/editor display. BSP already contains their baked result.
        case "tesssize", "light", "entitymergable" -> skipLine();
        default -> {
          if (!directive.startsWith("q3map_") && !directive.startsWith("qer_")) {
            warn(directiveLine, "unknown shader directive " + directive);
          }
          skipLine();
        }
      }
    }

    private void stageDirective(StageBuilder s, String directive) throws ShaderFormatException {
      switch (directive) {
        case "map", "clampmap" ->
            s.texture = new TextureMap(List.of(argument()), 0, directive.equals("clampmap"));
        case "animmap", "clampanimmap" -> {
          float fps = nonnegative(number(), "animation frequency");
          List<String> frames = new ArrayList<>();
          while (hasArgument()) {
            if (frames.size() >= 64) throw malformed("too many animated map frames");
            frames.add(argument());
          }
          s.texture = new TextureMap(frames, fps, directive.equals("clampanimmap"));
        }
        case "blendfunc" -> s.blend = blend();
        case "alphafunc" ->
            s.alphaFunc =
                switch (lower()) {
                  case "gt0" -> AlphaFunc.GT0;
                  case "lt128" -> AlphaFunc.LT128;
                  case "ge128" -> AlphaFunc.GE128;
                  default -> throw bad("unknown alpha function");
                };
        case "rgbgen" -> s.rgbGen = rgbGen();
        case "alphagen" -> s.alphaGen = alphaGen();
        case "tcgen", "texgen" -> s.tcGen = tcGen();
        case "tcmod" -> {
          if (s.tcMods.size() >= MAX_MODIFIERS) throw malformed("too many texture modifiers");
          s.tcMods.add(tcMod());
        }
        case "depthfunc" ->
            s.depthFunc =
                switch (lower()) {
                  case "lequal" -> DepthFunc.LEQUAL;
                  case "equal" -> DepthFunc.EQUAL;
                  case "disable" -> DepthFunc.DISABLE;
                  default -> throw bad("unknown depth function");
                };
        case "depthwrite" -> s.explicitDepthWrite = true;
        case "detail" -> s.detail = true;
        default -> {
          warn(directiveLine, "unknown stage directive " + directive);
          skipLine();
        }
      }
    }

    private Blend blend() {
      String first = lower();
      return switch (first) {
        case "add" -> Blend.ADD;
        case "filter" -> Blend.FILTER;
        case "blend" -> Blend.ALPHA;
        default -> new Blend(blendFactor(first), blendFactor(lower()));
      };
    }

    private BlendFactor blendFactor(String token) {
      if (token.startsWith("gl_")) token = token.substring(3);
      try {
        return BlendFactor.valueOf(token.toUpperCase(Locale.ROOT));
      } catch (IllegalArgumentException ex) {
        throw bad("unknown blend factor " + token);
      }
    }

    private RgbGen rgbGen() {
      String type = lower();
      return switch (type) {
        case "identity" -> RgbGen.of(RgbGenType.IDENTITY);
        case "identitylighting" -> RgbGen.of(RgbGenType.IDENTITY_LIGHTING);
        case "vertex", "fromvertex" -> RgbGen.of(RgbGenType.VERTEX);
        case "exactvertex" -> RgbGen.of(RgbGenType.EXACT_VERTEX);
        case "oneminusvertex" -> RgbGen.of(RgbGenType.ONE_MINUS_VERTEX);
        case "entity" -> RgbGen.of(RgbGenType.ENTITY);
        case "oneminusentity" -> RgbGen.of(RgbGenType.ONE_MINUS_ENTITY);
        case "lightingdiffuse" -> RgbGen.of(RgbGenType.LIGHTING_DIFFUSE);
        case "fog" -> RgbGen.of(RgbGenType.FOG);
        case "wave" -> new RgbGen(RgbGenType.WAVE, wave(), new Vec3(1, 1, 1));
        case "const", "constant" -> new RgbGen(RgbGenType.CONSTANT, Wave.ONE, vector());
        default -> throw bad("unknown RGB generation " + type);
      };
    }

    private AlphaGen alphaGen() {
      String type = lower();
      return switch (type) {
        case "identity" -> AlphaGen.of(AlphaGenType.IDENTITY);
        case "vertex" -> AlphaGen.of(AlphaGenType.VERTEX);
        case "oneminusvertex" -> AlphaGen.of(AlphaGenType.ONE_MINUS_VERTEX);
        case "entity" -> AlphaGen.of(AlphaGenType.ENTITY);
        case "oneminusentity" -> AlphaGen.of(AlphaGenType.ONE_MINUS_ENTITY);
        case "lightingspecular" -> AlphaGen.of(AlphaGenType.LIGHTING_SPECULAR);
        case "wave" -> new AlphaGen(AlphaGenType.WAVE, wave(), 1, 256);
        case "const", "constant" -> new AlphaGen(AlphaGenType.CONSTANT, Wave.ONE, number(), 256);
        case "portal" ->
            new AlphaGen(
                AlphaGenType.PORTAL,
                Wave.ONE,
                1,
                hasArgument() ? positive(number(), "portal range") : 256);
        default -> throw bad("unknown alpha generation " + type);
      };
    }

    private TcGen tcGen() {
      String type = lower();
      return switch (type) {
        case "base", "texture" -> TcGen.of(TcGenType.BASE);
        case "lightmap" -> TcGen.of(TcGenType.LIGHTMAP);
        case "environment" -> TcGen.of(TcGenType.ENVIRONMENT);
        case "identity" -> TcGen.of(TcGenType.IDENTITY);
        case "fog" -> TcGen.of(TcGenType.FOG);
        case "vector" -> new TcGen(TcGenType.VECTOR, vector(), vector());
        default -> throw bad("unknown texture generation " + type);
      };
    }

    private TcMod tcMod() {
      String type = lower();
      return switch (type) {
        case "scroll" -> new Scroll(number(), number());
        case "scale" -> new Scale(number(), number());
        case "rotate" -> new Rotate(number());
        case "stretch" -> new Stretch(wave());
        case "transform" ->
            new Transform(number(), number(), number(), number(), number(), number());
        case "turb" -> new Turbulence(number(), number(), number(), number());
        case "entitytranslate" -> new EntityTranslate();
        default -> throw bad("unknown texture modifier " + type);
      };
    }

    private Deform deform() {
      String type = lower();
      return switch (type) {
        case "wave" -> new WaveDeform(number(), wave());
        case "normal" -> new NormalDeform(number(), number());
        case "bulge" -> new BulgeDeform(number(), number(), number());
        case "move" -> new MoveDeform(vector(), wave());
        case "autosprite" -> new AutoSprite(false);
        case "autosprite2" -> new AutoSprite(true);
        case "projectionshadow" -> new ProjectionShadow();
        default -> {
          if (type.matches("text[0-7]")) yield new TextDeform(type.charAt(4) - '0');
          throw bad("unknown vertex deformation " + type);
        }
      };
    }

    private Wave wave() {
      String function = lower();
      WaveFunction kind =
          switch (function) {
            case "sin" -> WaveFunction.SIN;
            case "triangle" -> WaveFunction.TRIANGLE;
            case "square" -> WaveFunction.SQUARE;
            case "sawtooth" -> WaveFunction.SAWTOOTH;
            case "inversesawtooth" -> WaveFunction.INVERSE_SAWTOOTH;
            case "noise" -> WaveFunction.NOISE;
            default -> throw bad("unknown waveform " + function);
          };
      return new Wave(kind, number(), number(), number(), number());
    }

    private Vec3 vector() {
      boolean parens = accept("(");
      Vec3 value = new Vec3(number(), number(), number());
      if (parens && !accept(")")) throw bad("expected ')' after vector");
      return value;
    }

    private float sort(String value) {
      return switch (value.toLowerCase(Locale.ROOT)) {
        case "portal" -> 1;
        case "sky", "environment" -> 2;
        case "opaque" -> 3;
        case "decal" -> 4;
        case "seethrough" -> 5;
        case "banner" -> 6;
        case "fog" -> 7;
        case "underwater" -> 8;
        case "additive" -> 9;
        case "nearest" -> 16;
        default -> finite(value);
      };
    }

    private String skyBox(String name) {
      return name.equals("-") ? name : new VirtualPath(name).value();
    }

    private float number() {
      return finite(argument());
    }

    private float finite(String value) {
      float result;
      try {
        result = Float.parseFloat(value);
      } catch (NumberFormatException ex) {
        throw bad("invalid number " + value);
      }
      if (!Float.isFinite(result)) throw bad("non-finite number");
      return result;
    }

    private float nonnegative(float value, String name) {
      if (value < 0) throw bad(name + " must be nonnegative");
      return value;
    }

    private float positive(float value, String name) {
      if (value <= 0) throw bad(name + " must be positive");
      return value;
    }

    private String lower() {
      return argument().toLowerCase(Locale.ROOT);
    }

    private String argument() {
      if (!hasArgument()) throw bad("missing argument");
      return tokens.get(cursor++).text;
    }

    private boolean hasArgument() {
      return cursor < tokens.size()
          && tokens.get(cursor).line == directiveLine
          && !peek("{")
          && !peek("}");
    }

    private void skipLine() {
      while (hasArgument()) cursor++;
    }

    private boolean peek(String text) {
      return cursor < tokens.size() && tokens.get(cursor).text.equals(text);
    }

    private boolean accept(String text) {
      if (!peek(text)) return false;
      cursor++;
      return true;
    }

    private void expect(String text) throws ShaderFormatException {
      if (!accept(text)) throw malformed("expected '" + text + "'");
    }

    private ShaderFormatException malformed(String reason) {
      int line = cursor < tokens.size() ? tokens.get(cursor).line : directiveLine;
      return new ShaderFormatException(source + ":" + line + ": " + reason);
    }

    private IllegalArgumentException bad(String message) {
      return new IllegalArgumentException(message);
    }

    private void warn(int line, String message) {
      if (diagnostics.size() < MAX_DIAGNOSTICS) {
        diagnostics.add(new ShaderDiagnostic(source, line, message));
      }
    }
  }

  private static final class MaterialBuilder {
    final String name;
    final List<Stage> stages = new ArrayList<>();
    final Set<String> surfaceParms = new HashSet<>();
    final List<Deform> deforms = new ArrayList<>();
    Cull cull = Cull.FRONT;
    Float sort;
    boolean polygonOffset;
    boolean noMipmaps;
    boolean noPicmip;
    Sky sky;
    Fog fog;
    boolean portal;
    float clampTime;

    MaterialBuilder(String name) {
      this.name = name;
    }

    ShaderDefinition build() {
      if (sort == null) {
        if (sky != null || surfaceParms.contains("sky")) sort = 2f;
        else if (polygonOffset) sort = 4f;
        else if (!stages.isEmpty() && !stages.getFirst().blend().opaque()) {
          sort = stages.getFirst().depthWrite() ? 5f : 9f;
        } else if (stages.isEmpty() && fog != null) sort = 7f;
        else sort = 3f;
      }
      return new ShaderDefinition(
          name,
          stages,
          cull,
          sort,
          polygonOffset,
          noMipmaps,
          noPicmip,
          surfaceParms,
          Optional.ofNullable(sky),
          Optional.ofNullable(fog),
          deforms,
          portal || sort == 1,
          clampTime);
    }
  }

  private static final class StageBuilder {
    TextureMap texture;
    Blend blend = Blend.OPAQUE;
    AlphaFunc alphaFunc = AlphaFunc.NONE;
    RgbGen rgbGen;
    AlphaGen alphaGen;
    TcGen tcGen;
    final List<TcMod> tcMods = new ArrayList<>();
    DepthFunc depthFunc = DepthFunc.LEQUAL;
    boolean explicitDepthWrite;
    boolean detail;

    Stage build() {
      if (rgbGen == null) {
        rgbGen =
            RgbGen.of(
                blend.opaque()
                        || blend.source() == BlendFactor.ONE
                        || blend.source() == BlendFactor.SRC_ALPHA
                    ? RgbGenType.IDENTITY_LIGHTING
                    : RgbGenType.IDENTITY);
      }
      if (alphaGen == null) {
        alphaGen =
            AlphaGen.of(
                rgbGen.type() == RgbGenType.VERTEX ? AlphaGenType.VERTEX : AlphaGenType.IDENTITY);
      }
      if (tcGen == null) {
        tcGen =
            TcGen.of(
                texture.frames().getFirst().equals("$lightmap")
                    ? TcGenType.LIGHTMAP
                    : TcGenType.BASE);
      }
      return new Stage(
          texture,
          blend,
          alphaFunc,
          rgbGen,
          alphaGen,
          tcGen,
          tcMods,
          depthFunc,
          explicitDepthWrite || blend.opaque(),
          detail);
    }
  }
}
