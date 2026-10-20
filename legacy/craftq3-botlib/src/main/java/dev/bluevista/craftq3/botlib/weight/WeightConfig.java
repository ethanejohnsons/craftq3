package dev.bluevista.craftq3.botlib.weight;

import dev.bluevista.craftq3.botlib.script.ScriptException;
import dev.bluevista.craftq3.botlib.script.ScriptSources;
import dev.bluevista.craftq3.botlib.script.ScriptToken;
import dev.bluevista.craftq3.botlib.script.SourceLocation;
import dev.bluevista.craftq3.core.fs.VirtualFileSystem;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.IntSupplier;

/** Immutable original-script fuzzy weights. Evaluation makes no game or weapon decisions itself. */
public final class WeightConfig {
  public static final int DEFAULT_THRESHOLD = 999999;

  public record Limits(int maxWeights, int maxNodes, int maxDepth, int maxInventory) {
    public static final Limits DEFAULT = new Limits(128, 16384, 64, 256);

    public Limits {
      if (maxWeights < 1
          || maxWeights > 128
          || maxNodes < 1
          || maxDepth < 1
          || maxDepth > 128
          || maxInventory < 1
          || maxInventory > 65536) throw new IllegalArgumentException("Invalid weight limits");
    }
  }

  public sealed interface Node permits Value, Decision {}

  /** Balance bounds are retained for future explicit mutation services; evaluation uses value. */
  public record Value(float value, float minimum, float maximum, boolean balanced) implements Node {
    public Value {
      if (!Float.isFinite(value) || !Float.isFinite(minimum) || !Float.isFinite(maximum))
        throw new IllegalArgumentException("Non-finite fuzzy weight");
    }
  }

  public record Branch(int threshold, Node node, boolean defaultBranch) {
    public Branch {
      Objects.requireNonNull(node);
    }
  }

  /** Source branch order is significant, including duplicate or descending thresholds. */
  public record Decision(int inventoryIndex, List<Branch> branches) implements Node {
    public Decision {
      branches = List.copyOf(branches);
      if (inventoryIndex < 0 || branches.isEmpty())
        throw new IllegalArgumentException("Invalid fuzzy decision");
    }
  }

  public record Weight(String name, Node root, SourceLocation location) {
    public Weight {
      Objects.requireNonNull(name);
      Objects.requireNonNull(root);
      Objects.requireNonNull(location);
    }
  }

  public record Diagnostic(SourceLocation location, String message) {}

  private final List<Weight> weights;
  private final List<Diagnostic> diagnostics;
  private final Map<String, Integer> indices;
  private final int inventorySize;

  private WeightConfig(List<Weight> weights, List<Diagnostic> diagnostics, int inventorySize) {
    this.weights = List.copyOf(weights);
    this.diagnostics = List.copyOf(diagnostics);
    this.inventorySize = inventorySize;
    var names = new HashMap<String, Integer>();
    for (int i = 0; i < weights.size(); i++) names.putIfAbsent(weights.get(i).name(), i);
    indices = Map.copyOf(names);
  }

  public static WeightConfig load(VirtualFileSystem fs, String path) throws IOException {
    try (var sources = new ScriptSources(fs)) {
      return load(sources, path);
    }
  }

  public static WeightConfig load(ScriptSources sources, String path) throws IOException {
    return load(sources, path, Limits.DEFAULT);
  }

  public static WeightConfig load(ScriptSources sources, String path, Limits limits)
      throws IOException {
    int handle = sources.load(path);
    try {
      return new Parser(sources, handle, limits).parse();
    } finally {
      sources.free(handle);
    }
  }

  public List<Weight> weights() {
    return weights;
  }

  public List<String> names() {
    return weights.stream().map(Weight::name).toList();
  }

  public List<Diagnostic> diagnostics() {
    return diagnostics;
  }

  public int requiredInventorySize() {
    return inventorySize;
  }

  public int find(String name) {
    return indices.getOrDefault(name, -1);
  }

  public float evaluate(int weightIndex, int[] inventory) {
    Objects.requireNonNull(inventory);
    if (weightIndex < 0 || weightIndex >= weights.size())
      throw new IllegalArgumentException("Invalid fuzzy weight index " + weightIndex);
    if (inventory.length < inventorySize)
      throw new IllegalArgumentException("Inventory needs " + inventorySize + " values");
    return evaluate(weights.get(weightIndex).root(), inventory, null);
  }

  /**
   * Native undecided evaluation samples eligible leaves' retained balance intervals. The borrowed
   * supplier provides random bits; the low 15 bits are normalized by 32767, including both
   * endpoints. Sampled constant/equal-bound leaves still consume one sample. Upper nested decisions
   * and nodes beyond their final threshold retain the observed deterministic behavior. Evaluation
   * never mutates this configuration.
   */
  public float evaluateUndecided(int weightIndex, int[] inventory, IntSupplier randomBits) {
    Objects.requireNonNull(inventory);
    Objects.requireNonNull(randomBits);
    if (weightIndex < 0 || weightIndex >= weights.size())
      throw new IllegalArgumentException("Invalid fuzzy weight index " + weightIndex);
    if (inventory.length < inventorySize)
      throw new IllegalArgumentException("Inventory needs " + inventorySize + " values");
    Node root = weights.get(weightIndex).root();
    if (root instanceof Value value && inventory.length != 0 && inventory[0] >= DEFAULT_THRESHOLD)
      return value.value();
    return evaluate(root, inventory, randomBits);
  }

  private static float evaluate(Node node, int[] inventory, IntSupplier randomBits) {
    if (node instanceof Value value) {
      if (randomBits == null) return value.value();
      float sample = (randomBits.getAsInt() & 32767) / 32767f;
      return value.minimum() + sample * (value.maximum() - value.minimum());
    }
    var decision = (Decision) node;
    int input = inventory[decision.inventoryIndex()];
    var branches = decision.branches();
    Branch lower = branches.getFirst();
    if (input < lower.threshold()) return evaluate(lower.node(), inventory, randomBits);
    for (int i = 1; i < branches.size(); i++) {
      Branch upper = branches.get(i);
      if (input < upper.threshold()) {
        if (upper.threshold() == DEFAULT_THRESHOLD && randomBits == null)
          return evaluate(upper.node(), inventory, null);
        float fraction =
            upper.threshold() == DEFAULT_THRESHOLD
                ? 1
                : (float) ((long) input - lower.threshold())
                    / (float) ((long) upper.threshold() - lower.threshold());
        float low = evaluate(lower.node(), inventory, randomBits);
        // The observed upper nested branch uses deterministic evaluation, even in undecided mode.
        float high =
            evaluate(upper.node(), inventory, upper.node() instanceof Decision ? null : randomBits);
        return (1 - fraction) * low + fraction * high;
      }
      lower = upper;
    }
    // Beyond the final threshold, native separators expose their base value, without descending.
    return lower.node() instanceof Value value ? value.value() : 0;
  }

  private static final class Parser {
    private final ScriptSources sources;
    private final int handle;
    private final Limits limits;
    private final List<Diagnostic> diagnostics = new ArrayList<>();
    private ScriptToken next;
    private boolean fetched;
    private int nodes, inventorySize;

    Parser(ScriptSources sources, int handle, Limits limits) {
      this.sources = sources;
      this.handle = handle;
      this.limits = Objects.requireNonNull(limits);
    }

    WeightConfig parse() throws ScriptException {
      var weights = new ArrayList<Weight>();
      while (peek() != null) {
        SourceLocation source = peek().location();
        require("weight");
        ScriptToken name = take();
        if (name.type() != ScriptToken.STRING) throw error("Expected quoted weight name");
        if (weights.size() >= limits.maxWeights()) throw error("Weight count budget exceeded");
        require("{");
        Node root = node(0);
        require("}");
        if (weights.stream().anyMatch(weight -> weight.name().equals(name.text())))
          diagnostics.add(
              new Diagnostic(source, "Duplicate weight name; lookup retains the first definition"));
        weights.add(new Weight(name.text(), root, source));
      }
      if (weights.isEmpty()) throw error("Weight configuration is empty");
      return new WeightConfig(weights, diagnostics, inventorySize);
    }

    private Node node(int depth) throws ScriptException {
      if (depth >= limits.maxDepth() || ++nodes > limits.maxNodes())
        throw error("Weight node/depth budget exceeded");
      if (accept("{")) {
        Node node = node(depth + 1);
        require("}");
        return node;
      }
      if (accept("return")) {
        boolean balanced = accept("balance");
        float value, low, high;
        if (balanced) {
          require("(");
          value = scalar();
          require(",");
          low = scalar();
          require(",");
          high = scalar();
          require(")");
        } else value = low = high = scalar();
        require(";");
        return new Value(value, low, high, balanced);
      }
      SourceLocation source = location();
      require("switch");
      require("(");
      int index = integer();
      if (index < 0 || index >= limits.maxInventory())
        throw error("Inventory index outside configured range");
      inventorySize = Math.max(inventorySize, index + 1);
      require(")");
      require("{");
      var branches = new ArrayList<Branch>();
      boolean foundDefault = false;
      while (!accept("}")) {
        boolean fallback = accept("default");
        int threshold;
        if (fallback) {
          threshold = DEFAULT_THRESHOLD;
          if (foundDefault)
            diagnostics.add(
                new Diagnostic(location(), "Multiple defaults retain their source order"));
          foundDefault = true;
        } else {
          require("case");
          threshold = integer();
        }
        require(":");
        branches.add(new Branch(threshold, node(depth + 1), fallback));
      }
      if (branches.isEmpty()) throw new ScriptException(source, "Fuzzy switch has no branches");
      if (!foundDefault) {
        if (++nodes > limits.maxNodes()) throw error("Weight node budget exceeded");
        branches.add(new Branch(DEFAULT_THRESHOLD, new Value(0, 0, 0, false), true));
        diagnostics.add(
            new Diagnostic(source, "Switch without default uses the original zero fallback"));
      }
      return new Decision(index, branches);
    }

    private int integer() throws ScriptException {
      ScriptToken value = take();
      if (value.type() != ScriptToken.NUMBER
          || (value.subtype() & ScriptToken.INTEGER) == 0
          || value.intValue() < 0)
        throw new ScriptException(value.location(), "Expected a nonnegative 32-bit integer");
      return value.intValue();
    }

    private float scalar() throws ScriptException {
      boolean negative = accept("-");
      ScriptToken token = take();
      if (token.type() != ScriptToken.NUMBER)
        throw new ScriptException(token.location(), "Expected a numeric weight");
      if (negative)
        diagnostics.add(
            new Diagnostic(
                token.location(),
                "Negative return value retains the positive magnitude observed in original botlib"));
      return token.floatValue();
    }

    private ScriptToken peek() {
      if (!fetched) {
        next = sources.read(handle).orElse(null);
        fetched = true;
      }
      return next;
    }

    private ScriptToken take() throws ScriptException {
      var result = peek();
      if (result == null) throw error("Unexpected end of weight configuration");
      fetched = false;
      return result;
    }

    private boolean accept(String text) throws ScriptException {
      if (peek() == null || !peek().text().equals(text)) return false;
      take();
      return true;
    }

    private void require(String text) throws ScriptException {
      if (!accept(text)) throw error("Expected '" + text + "' in weight configuration");
    }

    private SourceLocation location() {
      return peek() == null ? sources.location(handle) : peek().location();
    }

    private ScriptException error(String message) {
      return new ScriptException(location(), message);
    }
  }
}
