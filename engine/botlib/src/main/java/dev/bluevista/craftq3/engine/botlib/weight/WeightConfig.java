package dev.bluevista.craftq3.engine.botlib.weight;

import dev.bluevista.craftq3.engine.botlib.script.ScriptException;
import dev.bluevista.craftq3.engine.botlib.script.ScriptSources;
import dev.bluevista.craftq3.engine.botlib.script.ScriptToken;
import dev.bluevista.craftq3.engine.botlib.script.SourceLocation;
import dev.bluevista.craftq3.engine.formats.AssetLookup;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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

  public static WeightConfig load(AssetLookup files, String path) throws IOException {
    try (var sources = new ScriptSources(files)) {
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
   * Interbreeds two parents into a child of this shape ({@code InterbreedWeightConfigs}): every
   * balanced leaf becomes the mean of the parents' leaves. The original's quirks are kept: nested
   * switches take both values from the second parent, and the lower bound is raised to the new
   * value whenever the value exceeds it. Empty when the three configurations differ in shape, which
   * the original reports as an error.
   */
  public Optional<WeightConfig> interbreed(WeightConfig first, WeightConfig second) {
    Objects.requireNonNull(first);
    Objects.requireNonNull(second);
    if (first.weights.size() != weights.size() || second.weights.size() != weights.size())
      return Optional.empty();
    var bred = new ArrayList<Weight>(weights.size());
    for (int i = 0; i < weights.size(); i++) {
      Node a = first.weights.get(i).root(), b = second.weights.get(i).root();
      Node out = weights.get(i).root();
      if (!sameShape(a, b) || !sameShape(a, out)) return Optional.empty();
      var weight = weights.get(i);
      bred.add(new Weight(weight.name(), interbreed(a, b, out), weight.location()));
    }
    return Optional.of(new WeightConfig(bred, diagnostics, inventorySize));
  }

  private static Node interbreed(Node a, Node b, Node out) {
    if (a instanceof Value first) {
      if (!first.balanced()) return out;
      var second = (Value) b;
      var child = (Value) out;
      if (!second.balanced() || !child.balanced()) return out;
      float value = (first.value() + second.value()) / 2;
      float maximum = Math.max(child.maximum(), value);
      float minimum = value > child.minimum() ? value : child.minimum();
      return new Value(value, minimum, maximum, true);
    }
    var left = (Decision) a;
    var right = (Decision) b;
    var target = (Decision) out;
    var branches = new ArrayList<Branch>(target.branches().size());
    for (int i = 0; i < target.branches().size(); i++) {
      Node l = left.branches().get(i).node(), r = right.branches().get(i).node();
      var branch = target.branches().get(i);
      Node bred =
          l instanceof Decision ? interbreed(r, r, branch.node()) : interbreed(l, r, branch.node());
      branches.add(new Branch(branch.threshold(), bred, branch.defaultBranch()));
    }
    return new Decision(target.inventoryIndex(), branches);
  }

  private static boolean sameShape(Node a, Node b) {
    if (a instanceof Value) return b instanceof Value;
    if (!(b instanceof Decision right)) return false;
    var left = (Decision) a;
    if (left.branches().size() != right.branches().size()) return false;
    for (int i = 0; i < left.branches().size(); i++)
      if (!sameShape(left.branches().get(i).node(), right.branches().get(i).node())) return false;
    return true;
  }

  /**
   * Mutates every balanced leaf ({@code EvolveWeightConfig}): it moves by a random fraction of its
   * range, a whole range in one case out of a hundred and half a range otherwise, and the range
   * grows to include the new value. {@code randomBits} supplies the original's {@code rand()}
   * values; leaves are visited depth first.
   */
  public WeightConfig mutate(IntSupplier randomBits) {
    Objects.requireNonNull(randomBits);
    var mutated = new ArrayList<Weight>(weights.size());
    for (var weight : weights)
      mutated.add(new Weight(weight.name(), mutate(weight.root(), randomBits), weight.location()));
    return new WeightConfig(mutated, diagnostics, inventorySize);
  }

  private static Node mutate(Node node, IntSupplier randomBits) {
    if (node instanceof Value value) {
      if (!value.balanced()) return value;
      float range = value.maximum() - value.minimum();
      float weight = value.value();
      // crandom() is a double expression in the original, so the sums are computed in double.
      if (random(randomBits) < .01) weight = (float) (weight + crandom(randomBits) * range);
      else weight = (float) (weight + crandom(randomBits) * range * .5);
      float minimum = value.minimum(), maximum = value.maximum();
      if (weight < minimum) minimum = weight;
      else if (weight > maximum) maximum = weight;
      return new Value(weight, minimum, maximum, true);
    }
    var decision = (Decision) node;
    var branches = new ArrayList<Branch>(decision.branches().size());
    for (var branch : decision.branches())
      branches.add(
          new Branch(
              branch.threshold(), mutate(branch.node(), randomBits), branch.defaultBranch()));
    return new Decision(decision.inventoryIndex(), branches);
  }

  private static float random(IntSupplier randomBits) {
    return (randomBits.getAsInt() & 0x7fff) / (float) 0x7fff;
  }

  private static double crandom(IntSupplier randomBits) {
    return 2.0 * (random(randomBits) - 0.5);
  }

  /**
   * The configuration as weight-file source ({@code WriteWeightConfig}), which {@link #load} parses
   * back to the same weights.
   */
  public String toSource() {
    var out = new StringBuilder();
    for (var weight : weights) {
      out.append("\nweight \"").append(weight.name()).append("\"\n{\n");
      write(out, weight.root(), 1);
      out.append("}\n");
    }
    return out.toString();
  }

  private static void write(StringBuilder out, Node node, int indent) {
    if (node instanceof Value value) {
      out.append("\t".repeat(indent)).append(leaf(value)).append('\n');
      return;
    }
    var decision = (Decision) node;
    out.append("\t".repeat(indent))
        .append("switch(")
        .append(decision.inventoryIndex())
        .append(")\n");
    out.append("\t".repeat(indent)).append("{\n");
    for (var branch : decision.branches()) {
      out.append("\t".repeat(indent + 1));
      if (branch.defaultBranch()) out.append("default:");
      else out.append("case ").append(branch.threshold()).append(':');
      if (branch.node() instanceof Value value) out.append(' ').append(leaf(value)).append('\n');
      else {
        out.append('\n').append("\t".repeat(indent + 1)).append("{\n");
        write(out, branch.node(), indent + 2);
        out.append("\t".repeat(indent + 1)).append("}\n");
      }
    }
    out.append("\t".repeat(indent)).append("}\n");
  }

  private static String leaf(Value value) {
    return value.balanced()
        ? "return balance("
            + number(value.value())
            + ", "
            + number(value.minimum())
            + ", "
            + number(value.maximum())
            + ");"
        : "return " + number(value.value()) + ";";
  }

  /** Plain decimal text that the script lexer reads back to (nearly) the same float. */
  private static String number(float value) {
    String text = new java.math.BigDecimal(Float.toString(Math.abs(value))).toPlainString();
    if (!text.contains(".")) text += ".0";
    return value < 0 ? "-" + text : text;
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
