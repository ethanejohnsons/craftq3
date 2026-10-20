package dev.bluevista.craftq3.botlib.weapon;

import dev.bluevista.craftq3.botlib.script.ScriptSources;
import dev.bluevista.craftq3.botlib.weight.WeightConfig;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Config-driven weapon ranking and per-bot weight state; no Java weapon availability/physics rules.
 */
public final class BotWeapons implements AutoCloseable {
  public static final int MAX_STATES = 64, INVENTORY_SIZE = 256;
  public static final int SUCCESS = 0, CANNOT_LOAD_WEIGHTS = 11, CANNOT_LOAD_CONFIG = 12;
  private final ScriptSources sources;
  private final Consumer<String> diagnostics;
  private final Map<Integer, State> states = new HashMap<>();
  private WeaponConfig config;
  private int nextHandle = 1;
  private boolean closed;

  public BotWeapons(ScriptSources sources) {
    this(sources, unused -> {});
  }

  public BotWeapons(ScriptSources sources, Consumer<String> diagnostics) {
    this.sources = Objects.requireNonNull(sources);
    this.diagnostics = Objects.requireNonNull(diagnostics);
  }

  public synchronized int setup(String path) {
    open();
    config = null;
    for (State state : states.values()) state.weights = null;
    try {
      config = WeaponConfig.load(sources, path);
      config.diagnostics().forEach(diagnostics);
      return SUCCESS;
    } catch (IOException | IllegalArgumentException | IllegalStateException failure) {
      diagnostics.accept("Cannot load weapon config " + path + ": " + failure.getMessage());
      return CANNOT_LOAD_CONFIG;
    }
  }

  public synchronized int allocate() {
    open();
    if (states.size() == MAX_STATES || nextHandle <= 0) {
      diagnostics.accept("Weapon state budget exhausted");
      return 0;
    }
    int handle = nextHandle++;
    states.put(handle, new State());
    return handle;
  }

  public synchronized void free(int handle) {
    open();
    if (states.remove(handle) == null) diagnostics.accept("Invalid weapon state " + handle);
  }

  /** Native reset retains loaded configuration and has no ranking-state effect. */
  public synchronized void reset(int handle) {
    state(handle);
  }

  public synchronized int stateCount() {
    open();
    return states.size();
  }

  public synchronized int loadWeaponWeights(int handle, String path) {
    State state = state(handle);
    if (state == null) return CANNOT_LOAD_WEIGHTS;
    state.weights = null;
    if (config == null) {
      diagnostics.accept("Weapon configuration is unavailable");
      return CANNOT_LOAD_WEIGHTS;
    }
    try {
      WeightConfig weights = WeightConfig.load(sources, path);
      int[] indices = new int[config.weapons().size()];
      java.util.Arrays.fill(indices, -1);
      for (int number = 1; number < indices.length; number++) {
        WeaponInfo weapon = config.weapons().get(number);
        if (weapon.valid()) indices[number] = weights.find(weapon.name());
      }
      state.weights = weights;
      state.indices = indices;
      return SUCCESS;
    } catch (IOException | IllegalArgumentException | IllegalStateException failure) {
      diagnostics.accept("Cannot load weapon weights " + path + ": " + failure.getMessage());
      return CANNOT_LOAD_WEIGHTS;
    }
  }

  public synchronized int chooseBestFightWeapon(int handle, int[] inventory) {
    State state = state(handle);
    if (state == null || state.weights == null || config == null) return 0;
    if (inventory == null || inventory.length != INVENTORY_SIZE) {
      diagnostics.accept("Weapon inventory must contain exactly 256 integers");
      return 0;
    }
    int chosen = 0;
    float best = 0;
    for (int number = 1; number < state.indices.length; number++) {
      int weight = state.indices[number];
      if (weight < 0) continue;
      float value = state.weights.evaluate(weight, inventory);
      if (Float.isFinite(value) && value > best) {
        chosen = number;
        best = value;
      }
    }
    return chosen;
  }

  public synchronized WeaponInfo weaponInfo(int handle, int weapon) {
    if (state(handle) == null || config == null) return WeaponInfo.EMPTY;
    if (weapon < 1 || weapon >= config.weapons().size()) {
      diagnostics.accept("Invalid weapon number " + weapon);
      return WeaponInfo.EMPTY;
    }
    return config.weapons().get(weapon);
  }

  private State state(int handle) {
    open();
    State state = states.get(handle);
    if (state == null) diagnostics.accept("Invalid weapon state " + handle);
    return state;
  }

  private void open() {
    if (closed) throw new IllegalStateException("Weapon service is closed");
  }

  @Override
  public synchronized void close() {
    states.clear();
    config = null;
    closed = true;
  }

  private static final class State {
    private WeightConfig weights;
    private int[] indices;
  }
}
