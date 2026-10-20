package dev.bluevista.craftq3.botlib.weapon;

import dev.bluevista.craftq3.botlib.script.ScriptException;
import dev.bluevista.craftq3.botlib.script.ScriptSources;
import dev.bluevista.craftq3.botlib.script.ScriptToken;
import dev.bluevista.craftq3.core.math.Vec3;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Original named weapon/projectile declarations, parsed independently through ScriptSources. */
public record WeaponConfig(
    List<WeaponInfo> weapons, List<ProjectileInfo> projectiles, List<String> diagnostics) {
  public static final int MAX_WEAPONS = 32, MAX_PROJECTILES = 32;

  public WeaponConfig {
    weapons = List.copyOf(weapons);
    projectiles = List.copyOf(projectiles);
    diagnostics = List.copyOf(diagnostics);
  }

  public static WeaponConfig load(ScriptSources sources, String path) throws IOException {
    int handle = sources.load(path);
    try {
      return new Parser(sources, handle).parse();
    } finally {
      sources.free(handle);
    }
  }

  private static final class Fields extends HashMap<String, Object> {
    private static final long serialVersionUID = 1L;

    String text(String key) {
      return (String) getOrDefault(key, "");
    }

    int integer(String key) {
      return (int) getOrDefault(key, 0);
    }

    float scalar(String key) {
      return (float) getOrDefault(key, 0f);
    }

    Vec3 vector(String key) {
      return (Vec3) getOrDefault(key, new Vec3(0, 0, 0));
    }
  }

  private enum Kind {
    INTEGER,
    FLOAT,
    STRING,
    VECTOR
  }

  private static final Map<String, Kind> WEAPON_FIELDS =
      kinds(
          "number level weaponindex flags numprojectiles ammoamount ammoindex",
          "hspread vspread speed acceleration extrazvelocity activate reload spinup spindown",
          "name model projectile",
          "recoil offset angleoffset");
  private static final Map<String, Kind> PROJECTILE_FIELDS =
      kinds(
          "flags damage visdamage damagetype healthinc",
              "gravity radius push detonation bounce bouncefric bouncestop",
          "name model", "");

  private static Map<String, Kind> kinds(
      String integers, String floats, String strings, String vectors) {
    var result = new HashMap<String, Kind>();
    String[] groups = {integers, floats, strings, vectors};
    for (int i = 0; i < groups.length; i++)
      for (String name : groups[i].split(" "))
        if (!name.isEmpty()) result.put(name, Kind.values()[i]);
    return Map.copyOf(result);
  }

  private static final class Parser {
    private final ScriptSources sources;
    private final int source;
    private final List<String> diagnostics = new ArrayList<>();
    private ScriptToken next;
    private boolean fetched;

    Parser(ScriptSources sources, int source) {
      this.sources = sources;
      this.source = source;
    }

    WeaponConfig parse() throws ScriptException {
      var weaponFields = new LinkedHashMap<Integer, Fields>();
      var projectiles = new ArrayList<ProjectileInfo>();
      var projectileNames = new HashMap<String, ProjectileInfo>();
      int declarations = 0;
      while (peek() != null) {
        if (++declarations > 256) throw error("Weapon declaration budget exceeded");
        ScriptToken type = take();
        if (type.type() != ScriptToken.NAME) throw error("Expected weaponinfo or projectileinfo");
        switch (type.text()) {
          case "weaponinfo" -> {
            Fields fields = fields(WEAPON_FIELDS);
            int number = fields.integer("number");
            if (number < 0 || number >= MAX_WEAPONS) throw error("Weapon number outside [0, 31]");
            if (weaponFields.put(number, fields) != null)
              diagnostics.add("Duplicate weapon slot " + number + " retains last declaration");
          }
          case "projectileinfo" -> {
            if (projectiles.size() == MAX_PROJECTILES)
              throw error("Projectile declaration budget exceeded");
            Fields fields = fields(PROJECTILE_FIELDS);
            var projectile =
                new ProjectileInfo(
                    fields.text("name"),
                    fields.text("model"),
                    fields.integer("flags"),
                    fields.scalar("gravity"),
                    fields.integer("damage"),
                    fields.scalar("radius"),
                    fields.integer("visdamage"),
                    fields.integer("damagetype"),
                    fields.integer("healthinc"),
                    fields.scalar("push"),
                    fields.scalar("detonation"),
                    fields.scalar("bounce"),
                    fields.scalar("bouncefric"),
                    fields.scalar("bouncestop"));
            projectiles.add(projectile);
            if (projectileNames.putIfAbsent(projectile.name(), projectile) != null)
              diagnostics.add(
                  "Duplicate projectile name retains first declaration: " + projectile.name());
          }
          default -> throw error("Unknown weapon configuration declaration " + type.text());
        }
      }
      var weapons =
          new ArrayList<WeaponInfo>(java.util.Collections.nCopies(MAX_WEAPONS, WeaponInfo.EMPTY));
      for (var entry : weaponFields.entrySet()) {
        Fields f = entry.getValue();
        ProjectileInfo projectile = projectileNames.get(f.text("projectile"));
        if (projectile == null)
          throw error(
              "Weapon "
                  + f.text("name")
                  + " references undefined projectile "
                  + f.text("projectile"));
        weapons.set(
            entry.getKey(),
            new WeaponInfo(
                true,
                entry.getKey(),
                f.text("name"),
                f.text("model"),
                f.integer("level"),
                f.integer("weaponindex"),
                f.integer("flags"),
                f.text("projectile"),
                f.integer("numprojectiles"),
                f.scalar("hspread"),
                f.scalar("vspread"),
                f.scalar("speed"),
                f.scalar("acceleration"),
                f.vector("recoil"),
                f.vector("offset"),
                f.vector("angleoffset"),
                f.scalar("extrazvelocity"),
                f.integer("ammoamount"),
                f.integer("ammoindex"),
                f.scalar("activate"),
                f.scalar("reload"),
                f.scalar("spinup"),
                f.scalar("spindown"),
                projectile));
      }
      return new WeaponConfig(weapons, projectiles, diagnostics);
    }

    private Fields fields(Map<String, Kind> kinds) throws ScriptException {
      require("{");
      var values = new Fields();
      int count = 0;
      while (!accept("}")) {
        if (++count > 128) throw error("Weapon field budget exceeded");
        ScriptToken field = take();
        Kind kind = field.type() == ScriptToken.NAME ? kinds.get(field.text()) : null;
        if (kind == null) throw error("Unknown weapon/projectile field " + field.text());
        Object value =
            switch (kind) {
              case INTEGER -> integer();
              case FLOAT -> scalar();
              case STRING -> text();
              case VECTOR -> vector();
            };
        values.put(field.text(), value);
      }
      return values;
    }

    private int integer() throws ScriptException {
      boolean negative = accept("-");
      ScriptToken token = take();
      if (token.type() != ScriptToken.NUMBER || (token.subtype() & ScriptToken.INTEGER) == 0)
        throw error("Expected integer weapon field");
      if (negative && token.intValue() == Integer.MIN_VALUE)
        throw error("Integer weapon field overflow");
      return negative ? -token.intValue() : token.intValue();
    }

    private float scalar() throws ScriptException {
      boolean negative = accept("-");
      ScriptToken token = take();
      if (token.type() != ScriptToken.NUMBER) throw error("Expected numeric weapon field");
      return negative ? -token.floatValue() : token.floatValue();
    }

    private String text() throws ScriptException {
      ScriptToken token = take();
      if (token.type() != ScriptToken.STRING) throw error("Expected quoted weapon string");
      String text = token.text();
      if (text.length() > 79) {
        diagnostics.add("Weapon string truncated to 79 bytes");
        text = text.substring(0, 79);
      }
      return text;
    }

    private Vec3 vector() throws ScriptException {
      require("{");
      float[] values = new float[3];
      int count = 0;
      if (!accept("}")) {
        do {
          if (count == 3) throw error("Weapon vector contains more than three components");
          values[count++] = scalar();
        } while (accept(","));
        require("}");
      }
      return new Vec3(values[0], values[1], values[2]);
    }

    private ScriptToken peek() {
      if (!fetched) {
        next = sources.read(source).orElse(null);
        fetched = true;
      }
      return next;
    }

    private ScriptToken take() throws ScriptException {
      ScriptToken token = peek();
      if (token == null) throw error("Unexpected end of weapon configuration");
      fetched = false;
      return token;
    }

    private boolean accept(String text) throws ScriptException {
      if (peek() == null || peek().type() != ScriptToken.PUNCTUATION || !peek().text().equals(text))
        return false;
      take();
      return true;
    }

    private void require(String text) throws ScriptException {
      if (!accept(text)) throw error("Expected '" + text + "'");
    }

    private ScriptException error(String text) {
      return new ScriptException(sources.location(source), text);
    }
  }
}
