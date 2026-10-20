package dev.bluevista.craftq3.engine.vmabi;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Renders the import tables and their policies as Markdown ({@code docs/SYSCALLS.md}). The file is
 * generated; a test in this module regenerates it and fails when the committed copy is stale.
 */
public final class SyscallDocs {
  private SyscallDocs() {}

  public static String render() {
    StringBuilder out = new StringBuilder();
    out.append("# VM syscalls\n\n");
    out.append(
        "Generated from the `engine:vm-abi` import enums by `SyscallDocsTest`; do not edit.\n\n");
    out.append(
        "A guest `CALL` to address `-1 - n` is trap `n`. Every import has a policy: `HOST` (the VM"
            + " host must bind a handler), `SHARED` (implemented by `engine.vmabi.service`), `STUB`"
            + " (a documented constant, no handler needed) or `FATAL`. When a handler throws, a"
            + " degradable import returns its neutral value, is counted and logged once per module"
            + " and number; `vm_strictSyscalls 1` makes that a VM fault. Critical imports always"
            + " fault. Unknown numbers return 0 (logged once) unless strict.\n\n");
    profiles(out);
    for (VmModule module : VmModule.values()) module(out, module);
    retailOnly(out);
    return out.toString();
  }

  private static void profiles(StringBuilder out) {
    out.append("## ABI profiles\n\n");
    out.append("| | 1.32 | retail |\n| --- | --- | --- |\n");
    row(out, "entityState_t", AbiProfile::entityStateBytes);
    row(out, "playerState_t", AbiProfile::playerStateBytes);
    row(out, "sharedEntity_t", AbiProfile::sharedEntityBytes);
    row(out, "usercmd_t", AbiProfile::userCmdBytes);
    row(out, "trace_t", AbiProfile::traceBytes);
    row(out, "glconfig_t", AbiProfile::glConfigBytes);
    row(out, "snapshot_t", AbiProfile::snapshotBytes);
    row(out, "bot_consolemessage_t", AbiProfile::botConsoleMessageBytes);
    row(out, "bot_match_t", AbiProfile::botMatchBytes);
    out.append('\n');
    out.append(
        "Detection (`AbiDetector`): the verified retail SHA-256 of pak0's qagame, cgame and ui;"
            + " otherwise the import usage profile (the trap numbers called through constant"
            + " addresses) against both tables; otherwise 1.32. `vm_abi` overrides it.\n\n");
  }

  private interface Size {
    int of(AbiProfile profile);
  }

  private static void row(StringBuilder out, String name, Size size) {
    out.append("| `")
        .append(name)
        .append("` | ")
        .append(size.of(AbiProfile.Q3_132))
        .append(" | ")
        .append(size.of(AbiProfile.RETAIL_1999))
        .append(" |\n");
  }

  private static void module(StringBuilder out, VmModule module) {
    ImportTable retail = ImportTable.of(module, AbiProfile.RETAIL_1999);
    List<VmImport> entries = ImportTable.canonicalEntries(module);
    Map<String, Integer> kinds = new TreeMap<>();
    for (VmImport entry : entries) kinds.merge(kind(entry.policy()), 1, Integer::sum);
    out.append("## ").append(module.moduleName()).append("\n\n");
    out.append(entries.size()).append(" imports in the 1.32 numbering");
    kinds.forEach((kind, count) -> out.append(", ").append(count).append(' ').append(kind));
    out.append(".\n\n");
    out.append("| # | retail # | Import | Args | Policy | Detail |\n");
    out.append("| ---: | ---: | --- | ---: | --- | --- |\n");
    for (VmImport entry : entries) {
      int retailNumber = retail.numberOf(entry);
      out.append("| ")
          .append(entry.number())
          .append(" | ")
          .append(retailNumber < 0 ? "-" : Integer.toString(retailNumber))
          .append(" | `")
          .append(entry.name())
          .append("` | ")
          .append(entry.argCount() < 0 ? "?" : Integer.toString(entry.argCount()))
          .append(" | ")
          .append(kind(entry.policy()))
          .append(" | ")
          .append(escape(entry.policy().describe()))
          .append(" |\n");
    }
    out.append('\n');
  }

  private static void retailOnly(StringBuilder out) {
    out.append("## Retail-only imports\n\n");
    out.append("| Module | retail # | Import | Args | Policy | Detail |\n");
    out.append("| --- | ---: | --- | ---: | --- | --- |\n");
    Arrays.stream(RetailImport.values())
        .forEach(
            entry ->
                out.append("| ")
                    .append(entry.module().moduleName())
                    .append(" | ")
                    .append(entry.retailNumber())
                    .append(" | `")
                    .append(entry.name())
                    .append("` | ")
                    .append(entry.argCount() < 0 ? "?" : Integer.toString(entry.argCount()))
                    .append(" | ")
                    .append(kind(entry.policy()))
                    .append(" | ")
                    .append(escape(entry.policy().describe()))
                    .append(" |\n"));
  }

  private static String kind(Policy policy) {
    return switch (policy.kind()) {
      case IMPLEMENTED_BY_HOST -> "HOST";
      case SHARED -> "SHARED";
      case STUB -> "STUB";
      case FATAL -> "FATAL";
    };
  }

  private static String escape(String text) {
    return text.replace("|", "\\|");
  }
}
