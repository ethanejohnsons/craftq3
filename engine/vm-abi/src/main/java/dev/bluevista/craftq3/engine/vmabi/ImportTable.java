package dev.bluevista.craftq3.engine.vmabi;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The trap numbering of one module under one ABI profile: guest call number to {@link VmImport}.
 * Immutable and shared; obtain one with {@link #of}.
 *
 * <p>The 1.32 tables are the import enums' own numbers. The retail tables are a remap: the numbers
 * the verified retail builds (and their pre-Team-Arena siblings) import, which differ from 1.32 in
 * the botlib elementary actions (an older order with inventory actions, ending at 426), the three
 * obsolete AAS visibility slots 300 to 302, and the ui server browser from 46 onwards. Imports
 * added in 1.27 and later are absent from the retail tables.
 */
public final class ImportTable {
  private static final Map<VmModule, Map<AbiProfile, ImportTable>> TABLES = build();

  private final VmModule module;
  private final AbiProfile profile;
  private final VmImport[] byNumber;
  private final Map<VmImport, Integer> numbers;
  private final List<VmImport> entries;

  private ImportTable(VmModule module, AbiProfile profile, Map<Integer, VmImport> mapping) {
    this.module = module;
    this.profile = profile;
    int max = mapping.keySet().stream().mapToInt(Integer::intValue).max().orElse(-1);
    byNumber = new VmImport[max + 1];
    Map<VmImport, Integer> reverse = new HashMap<>();
    List<VmImport> ordered = new ArrayList<>();
    for (int number = 0; number <= max; number++) {
      VmImport entry = mapping.get(number);
      if (entry == null) continue;
      if (entry.module() != module)
        throw new IllegalStateException(entry + " is not a " + module + " import");
      if (reverse.put(entry, number) != null)
        throw new IllegalStateException(entry + " is mapped twice in the " + profile + " table");
      byNumber[number] = entry;
      ordered.add(entry);
    }
    numbers = Collections.unmodifiableMap(reverse);
    entries = List.copyOf(ordered);
  }

  /** The table for {@code module} under {@code profile}. */
  public static ImportTable of(VmModule module, AbiProfile profile) {
    return TABLES.get(Objects.requireNonNull(module)).get(Objects.requireNonNull(profile));
  }

  public VmModule module() {
    return module;
  }

  public AbiProfile profile() {
    return profile;
  }

  /** The import called by guest trap {@code number}, or null when this numbering has none. */
  public VmImport get(int number) {
    return number >= 0 && number < byNumber.length ? byNumber[number] : null;
  }

  public Optional<VmImport> lookup(int number) {
    return Optional.ofNullable(get(number));
  }

  public boolean knows(int number) {
    return get(number) != null;
  }

  /** The guest number of {@code entry} in this numbering, or -1 when it is absent. */
  public int numberOf(VmImport entry) {
    Integer number = numbers.get(entry);
    return number == null ? -1 : number;
  }

  public boolean contains(VmImport entry) {
    return numbers.containsKey(entry);
  }

  /** One past the highest mapped number. */
  public int span() {
    return byNumber.length;
  }

  /** Every mapped import in ascending number order. */
  public List<VmImport> entries() {
    return entries;
  }

  @Override
  public String toString() {
    return module + " imports (" + profile + ", " + entries.size() + " entries)";
  }

  /** Every 1.32 import enum entry of a module, in declaration order. */
  public static List<VmImport> canonicalEntries(VmModule module) {
    List<VmImport> all = new ArrayList<>();
    switch (module) {
      case QAGAME -> {
        all.addAll(Arrays.asList(QagameImport.values()));
        all.addAll(Arrays.asList(BotlibImport.values()));
      }
      case CGAME -> all.addAll(Arrays.asList(CgameImport.values()));
      case UI -> all.addAll(Arrays.asList(UiImport.values()));
    }
    return List.copyOf(all);
  }

  private static Map<VmModule, Map<AbiProfile, ImportTable>> build() {
    Map<VmModule, Map<AbiProfile, ImportTable>> tables = new EnumMap<>(VmModule.class);
    for (VmModule module : VmModule.values()) {
      Map<Integer, VmImport> modern = new HashMap<>();
      for (VmImport entry : canonicalEntries(module))
        if (modern.put(entry.number(), entry) != null)
          throw new IllegalStateException("Duplicate " + module + " number " + entry.number());
      Map<AbiProfile, ImportTable> byProfile = new EnumMap<>(AbiProfile.class);
      byProfile.put(AbiProfile.Q3_132, new ImportTable(module, AbiProfile.Q3_132, modern));
      byProfile.put(
          AbiProfile.RETAIL_1999,
          new ImportTable(module, AbiProfile.RETAIL_1999, retail(module, modern)));
      tables.put(module, byProfile);
    }
    return tables;
  }

  private static Map<Integer, VmImport> retail(VmModule module, Map<Integer, VmImport> modern) {
    Map<Integer, VmImport> map = new HashMap<>();
    switch (module) {
      case QAGAME -> {
        same(map, modern, 0, 42);
        same(map, modern, 100, 113);
        same(map, modern, 200, 211);
        same(map, modern, 303, 318);
        same(map, modern, 500, 572);
        map.put(400, BotlibImport.BOTLIB_EA_SAY);
        map.put(401, BotlibImport.BOTLIB_EA_SAY_TEAM);
        map.put(406, BotlibImport.BOTLIB_EA_GESTURE);
        map.put(407, BotlibImport.BOTLIB_EA_COMMAND);
        map.put(408, BotlibImport.BOTLIB_EA_SELECT_WEAPON);
        map.put(409, BotlibImport.BOTLIB_EA_TALK);
        map.put(410, BotlibImport.BOTLIB_EA_ATTACK);
        map.put(411, BotlibImport.BOTLIB_EA_USE);
        map.put(412, BotlibImport.BOTLIB_EA_RESPAWN);
        map.put(413, BotlibImport.BOTLIB_EA_JUMP);
        map.put(414, BotlibImport.BOTLIB_EA_DELAYED_JUMP);
        map.put(415, BotlibImport.BOTLIB_EA_CROUCH);
        map.put(416, BotlibImport.BOTLIB_EA_MOVE_UP);
        map.put(417, BotlibImport.BOTLIB_EA_MOVE_DOWN);
        map.put(418, BotlibImport.BOTLIB_EA_MOVE_FORWARD);
        map.put(419, BotlibImport.BOTLIB_EA_MOVE_BACK);
        map.put(420, BotlibImport.BOTLIB_EA_MOVE_LEFT);
        map.put(421, BotlibImport.BOTLIB_EA_MOVE_RIGHT);
        map.put(422, BotlibImport.BOTLIB_EA_MOVE);
        map.put(423, BotlibImport.BOTLIB_EA_VIEW);
        map.put(424, BotlibImport.BOTLIB_EA_END_REGULAR);
        map.put(425, BotlibImport.BOTLIB_EA_GET_INPUT);
        map.put(426, BotlibImport.BOTLIB_EA_RESET_INPUT);
      }
      case CGAME -> {
        same(map, modern, 0, CgameImport.CG_MEMORY_REMAINING.number());
        same(map, modern, 100, 106);
      }
      case UI -> {
        same(map, modern, 0, UiImport.UI_GETCONFIGSTRING.number());
        UiImport[] shifted = {
          UiImport.UI_LAN_GETPINGQUEUECOUNT,
          UiImport.UI_LAN_CLEARPING,
          UiImport.UI_LAN_GETPING,
          UiImport.UI_LAN_GETPINGINFO,
          UiImport.UI_CVAR_REGISTER,
          UiImport.UI_CVAR_UPDATE,
          UiImport.UI_MEMORY_REMAINING,
          UiImport.UI_GET_CDKEY,
          UiImport.UI_SET_CDKEY
        };
        for (int i = 0; i < shifted.length; i++) map.put(50 + i, shifted[i]);
        same(map, modern, 100, 106);
      }
    }
    for (RetailImport entry : RetailImport.values())
      if (entry.module() == module) map.put(entry.retailNumber(), entry);
    return map;
  }

  private static void same(
      Map<Integer, VmImport> map, Map<Integer, VmImport> modern, int from, int to) {
    for (int number = from; number <= to; number++) {
      VmImport entry = modern.get(number);
      if (entry != null) map.put(number, entry);
    }
  }
}
