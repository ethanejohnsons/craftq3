package dev.bluevista.craftq3.botlib.item;

import dev.bluevista.craftq3.botlib.script.ScriptException;
import dev.bluevista.craftq3.botlib.script.ScriptSources;
import dev.bluevista.craftq3.botlib.script.ScriptToken;
import dev.bluevista.craftq3.core.math.Vec3;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Bounded original iteminfo declarations through the shared preprocessor and virtual filesystem.
 */
public record ItemConfig(List<ItemInfo> items, List<String> diagnostics) {
  public static final int MAX_ITEMS = 256;

  public ItemConfig {
    items = List.copyOf(items);
    diagnostics = List.copyOf(diagnostics);
    if (items.size() > MAX_ITEMS)
      throw new IllegalArgumentException("Bot item count exceeds limit");
    for (int i = 0; i < items.size(); i++)
      if (items.get(i).number() != i)
        throw new IllegalArgumentException("Bot item indexes must be consecutive");
  }

  public Optional<ItemInfo> first(String classname) {
    return items.stream().filter(item -> item.classname().equals(classname)).findFirst();
  }

  public static ItemConfig load(ScriptSources sources, String path) throws IOException {
    int handle = sources.load(path);
    try {
      return new Parser(sources, handle).parse();
    } finally {
      sources.free(handle);
    }
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

    ItemConfig parse() throws ScriptException {
      var items = new ArrayList<ItemInfo>();
      while (peek() != null) {
        if (items.size() == MAX_ITEMS) throw error("Bot item declaration limit exceeded");
        ScriptToken declaration = take();
        if (declaration.type() != ScriptToken.NAME || !declaration.text().equals("iteminfo"))
          throw error("Expected iteminfo declaration");
        String classname = text(false);
        if (classname.length() > 31) throw error("Bot item classname exceeds 31 bytes");
        String name = "", model = "";
        int modelIndex = 0, type = 0, inventory = 0;
        float respawn = 0;
        Vec3 mins = new Vec3(0, 0, 0), maxs = mins;
        require("{");
        int fields = 0;
        while (!accept("}")) {
          if (++fields > 128) throw error("Bot item field limit exceeded");
          ScriptToken field = take();
          if (field.type() != ScriptToken.NAME) throw error("Expected bot item field name");
          switch (field.text()) {
            case "name" -> name = text(true);
            case "model" -> model = text(true);
            case "modelindex" -> modelIndex = integer();
            case "type" -> type = integer();
            case "index" -> inventory = integer();
            case "respawntime" -> respawn = scalar();
            case "mins" -> mins = vector();
            case "maxs" -> maxs = vector();
            default -> throw error("Unknown bot item field " + field.text());
          }
        }
        try {
          items.add(
              new ItemInfo(
                  classname,
                  name,
                  model,
                  modelIndex,
                  type,
                  inventory,
                  respawn,
                  mins,
                  maxs,
                  items.size()));
        } catch (IllegalArgumentException failure) {
          throw error(failure.getMessage());
        }
      }
      return new ItemConfig(items, diagnostics);
    }

    private String text(boolean truncate) throws ScriptException {
      ScriptToken token = take();
      if (token.type() != ScriptToken.STRING) throw error("Expected quoted bot item string");
      if (truncate && token.text().length() > 79) {
        diagnostics.add("Bot item string truncated to 79 bytes at " + token.location());
        return token.text().substring(0, 79);
      }
      return token.text();
    }

    private int integer() throws ScriptException {
      boolean negative = accept("-");
      ScriptToken token = take();
      if (token.type() != ScriptToken.NUMBER || (token.subtype() & ScriptToken.INTEGER) == 0)
        throw error("Expected integer bot item field");
      if (negative && token.intValue() == Integer.MIN_VALUE)
        throw error("Bot item integer overflow");
      return negative ? -token.intValue() : token.intValue();
    }

    private float scalar() throws ScriptException {
      boolean negative = accept("-");
      ScriptToken token = take();
      if (token.type() != ScriptToken.NUMBER) throw error("Expected numeric bot item field");
      return negative ? -token.floatValue() : token.floatValue();
    }

    private Vec3 vector() throws ScriptException {
      require("{");
      float[] values = new float[3];
      int count = 0;
      if (!accept("}")) {
        do {
          if (count == 3) throw error("Bot item vector has too many components");
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
      if (token == null) throw error("Unexpected end of bot item configuration");
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
