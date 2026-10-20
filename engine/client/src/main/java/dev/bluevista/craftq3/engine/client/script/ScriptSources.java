package dev.bluevista.craftq3.engine.client.script;

import dev.bluevista.craftq3.engine.common.log.Log;
import dev.bluevista.craftq3.engine.vm.QvmInstance;
import dev.bluevista.craftq3.engine.vm.QvmMemory;
import dev.bluevista.craftq3.engine.vmabi.service.GuestText;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * The {@code trap_PC_*} handle table of one VM: {@value #MAX_SOURCES} open script sources read
 * through a {@link ScriptPrecompiler}, and the global defines added to every source loaded later. A
 * script error is printed once and ends that source, as the original prints the error and its read
 * returns 0.
 *
 * <p>Guest layout of {@code pc_token_t}: {@code int type, subtype, intvalue; float floatvalue; char
 * string[1024]}.
 */
public final class ScriptSources {
  /** {@code MAX_SOURCEFILES}. */
  public static final int MAX_SOURCES = 64;

  /** Global defines kept at most. */
  public static final int MAX_GLOBAL_DEFINES = 1024;

  /** {@code sizeof(pc_token_t)}. */
  public static final int TOKEN_BYTES = 16 + ScriptToken.MAX_LENGTH;

  /** Bytes of the file name {@code trap_PC_SourceFileAndLine} writes ({@code MAX_QPATH}). */
  public static final int FILE_NAME_BYTES = 64;

  private static final Log LOG = Log.get(ScriptSources.class);

  private final Function<String, Optional<String>> files;
  private final java.util.function.Consumer<String> errors;
  private final ScriptPrecompiler[] sources = new ScriptPrecompiler[MAX_SOURCES];
  private final List<String> globals = new ArrayList<>();

  /**
   * @param files reads a script's text by game path
   * @param errors receives script error lines for the console
   */
  public ScriptSources(
      Function<String, Optional<String>> files, java.util.function.Consumer<String> errors) {
    this.files = Objects.requireNonNull(files);
    this.errors = Objects.requireNonNull(errors);
  }

  /** {@code trap_PC_AddGlobalDefine(string)}: 1 on success. */
  public int addGlobalDefine(String define) {
    try {
      ScriptPrecompiler.checkDefine(define);
    } catch (ScriptException invalid) {
      errors.accept("PC_AddGlobalDefine: " + invalid.getMessage());
      return 0;
    }
    if (globals.size() >= MAX_GLOBAL_DEFINES) return 0;
    globals.add(define);
    return 1;
  }

  /** {@code trap_PC_LoadSource(filename)}: a handle from 1, or 0 when missing or full. */
  public int load(String path) {
    int handle = 1;
    while (handle < MAX_SOURCES && sources[handle] != null) handle++;
    if (handle >= MAX_SOURCES) {
      LOG.warnOnce("pc-full", "Script source table full; cannot load " + path);
      return 0;
    }
    Optional<String> text = files.apply(path);
    if (text.isEmpty()) return 0;
    try {
      sources[handle] = new ScriptPrecompiler(path, text.get(), files, globals);
    } catch (ScriptException invalid) {
      errors.accept("Error: " + invalid.getMessage());
      return 0;
    }
    return handle;
  }

  /** {@code trap_PC_FreeSource(handle)}: 1 when the handle was open. */
  public int free(int handle) {
    if (!valid(handle)) return 0;
    sources[handle] = null;
    return 1;
  }

  /** The next token of {@code handle}, or empty at its end or after an error. */
  public Optional<ScriptToken> next(int handle) {
    if (!valid(handle)) return Optional.empty();
    ScriptPrecompiler source = sources[handle];
    try {
      return Optional.ofNullable(source.next());
    } catch (ScriptException failure) {
      errors.accept("Error: " + failure.getMessage());
      sources[handle] = new ScriptPrecompiler(source.file(), "", files, List.of());
      return Optional.empty();
    }
  }

  /** {@code trap_PC_ReadToken(handle, pc_token_t *)}: 1 with a token, 0 at the end. */
  public int readToken(QvmMemory memory, int handle, int address) {
    memory.resolve(address, TOKEN_BYTES);
    Optional<ScriptToken> token = next(handle);
    memory.fill(address, TOKEN_BYTES, 0);
    if (token.isEmpty()) return 0;
    ScriptToken value = token.get();
    memory.writeInt(address, value.type());
    memory.writeInt(address + 4, value.subtype());
    memory.writeInt(address + 8, (int) value.intValue());
    memory.writeFloat(address + 12, (float) value.floatValue());
    memory.writeString(address + 16, value.pcText(), ScriptToken.MAX_LENGTH);
    return 1;
  }

  /** {@code trap_PC_SourceFileAndLine(handle, filename, *line)}: 1 for an open handle. */
  public int sourceFileAndLine(QvmMemory memory, int handle, int fileAddress, int lineAddress) {
    if (!valid(handle)) {
      GuestText.write(memory, fileAddress, "", FILE_NAME_BYTES);
      if (lineAddress != 0) memory.writeInt(lineAddress, 0);
      return 0;
    }
    ScriptPrecompiler source = sources[handle];
    GuestText.write(memory, fileAddress, source.file(), FILE_NAME_BYTES);
    if (lineAddress != 0) memory.writeInt(lineAddress, source.line());
    return 1;
  }

  /** Open source count. */
  public int openCount() {
    int count = 0;
    for (ScriptPrecompiler source : sources) if (source != null) count++;
    return count;
  }

  /** Frees every source and global define (the VM is shutting down). */
  public void clear() {
    java.util.Arrays.fill(sources, null);
    globals.clear();
  }

  private boolean valid(int handle) {
    return handle >= 1 && handle < MAX_SOURCES && sources[handle] != null;
  }

  // ---------------------------------------------------------------- import handlers

  public int addGlobalDefine(QvmInstance vm, int[] a) {
    return addGlobalDefine(GuestText.read(vm.memory(), a[0]));
  }

  public int load(QvmInstance vm, int[] a) {
    return load(GuestText.readPath(vm.memory(), a[0]));
  }

  public int free(QvmInstance vm, int[] a) {
    return free(a[0]);
  }

  public int readToken(QvmInstance vm, int[] a) {
    return readToken(vm.memory(), a[0], a[1]);
  }

  public int sourceFileAndLine(QvmInstance vm, int[] a) {
    return sourceFileAndLine(vm.memory(), a[0], a[1], a[2]);
  }
}
