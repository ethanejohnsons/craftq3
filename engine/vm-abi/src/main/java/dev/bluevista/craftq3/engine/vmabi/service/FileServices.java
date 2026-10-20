package dev.bluevista.craftq3.engine.vmabi.service;

import dev.bluevista.craftq3.engine.common.log.Log;
import dev.bluevista.craftq3.engine.vm.QvmInstance;
import dev.bluevista.craftq3.engine.vm.QvmMemory;
import dev.bluevista.craftq3.engine.vmabi.VmModule;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * The {@code trap_FS_*} imports over {@link VmFiles}. Missing or refused files return the original
 * "not found" results (-1, handle 0) and are not degradations; an invalid handle is a guest error
 * and degrades.
 */
public final class FileServices {
  /** The special directory that lists game directories instead of files. */
  public static final String MOD_LIST = "$modlist";

  private static final Log LOG = Log.get(FileServices.class);

  private final VmModule module;
  private final VmFiles files;

  public FileServices(VmModule module, VmFiles files) {
    this.module = Objects.requireNonNull(module);
    this.files = Objects.requireNonNull(files);
  }

  public VmFiles files() {
    return files;
  }

  /**
   * {@code trap_FS_FOpenFile(path, *handle, mode)}: returns the length (0 for writes) and stores
   * the handle, or returns -1 and stores 0. A null handle pointer probes a read-mode length.
   */
  public int open(QvmInstance vm, int[] a) {
    QvmMemory memory = vm.memory();
    String path = GuestText.readPath(memory, a[0]);
    if (a[2] < 0 || a[2] >= VmFiles.Mode.values().length)
      throw new IllegalArgumentException("Invalid file mode " + a[2]);
    VmFiles.Mode mode = VmFiles.Mode.values()[a[2]];
    try {
      if (a[1] == 0) return mode == VmFiles.Mode.READ ? files.length(path) : -1;
      memory.writeInt(a[1], 0);
      VmFiles.Opened opened = files.open(path, mode);
      memory.writeInt(a[1], opened.handle());
      return opened.length();
    } catch (IOException missing) {
      LOG.debug(module.moduleName() + " cannot open " + path + " (" + mode + "): " + missing);
      return -1;
    }
  }

  /** {@code trap_FS_Read(buffer, length, handle)}: returns the bytes read. */
  public int read(QvmInstance vm, int[] a) {
    QvmMemory memory = vm.memory();
    memory.resolve(a[0], a[1]);
    try {
      byte[] data = files.read(a[2], a[1]);
      memory.writeBytes(a[0], data);
      return data.length;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** {@code trap_FS_Write(buffer, length, handle)}: returns the bytes written. */
  public int write(QvmInstance vm, int[] a) {
    byte[] data = vm.memory().readBytes(a[0], a[1]);
    try {
      return files.write(a[2], data);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** {@code trap_FS_FCloseFile(handle)}. */
  public int close(QvmInstance vm, int[] a) {
    try {
      files.close(a[0]);
      return 0;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** {@code trap_FS_Seek(handle, offset, origin)}: 0 on success, -1 outside the file. */
  public int seek(QvmInstance vm, int[] a) {
    if (a[2] < 0 || a[2] >= VmFiles.Origin.values().length)
      throw new IllegalArgumentException("Invalid seek origin " + a[2]);
    try {
      return files.seek(a[0], a[1], VmFiles.Origin.values()[a[2]]) ? 0 : -1;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * {@code trap_FS_GetFileList(path, extension, buffer, size)}: NUL-separated names, or for {@link
   * #MOD_LIST} name/description pairs; returns the number of entries that fit.
   */
  public int list(QvmInstance vm, int[] a) {
    QvmMemory memory = vm.memory();
    String directory = GuestText.readPath(memory, a[0]);
    String extension = GuestText.readPath(memory, a[1]);
    int size = a[3];
    if (size < 0) throw new IllegalArgumentException("Negative file list size " + size);
    memory.resolve(a[2], size);
    try {
      if (directory.toLowerCase(Locale.ROOT).equals(MOD_LIST))
        return encodeMods(memory, a[2], size, files.mods());
      return encodeNames(memory, a[2], size, files.list(directory, extension));
    } catch (IOException e) {
      LOG.debug(module.moduleName() + " cannot list " + directory + ": " + e);
      if (size > 0) memory.writeByte(a[2], 0);
      return 0;
    }
  }

  /**
   * The original packing: a name is copied while {@code used + length + 1 < size} (one byte stays
   * free), so the buffer always ends with a spare NUL; the count of copied names is returned.
   */
  public static int encodeNames(QvmMemory memory, int address, int size, List<String> names) {
    if (size > 0) memory.writeByte(address, 0);
    int used = 0, count = 0;
    for (String name : names) {
      byte[] bytes = name.getBytes(StandardCharsets.ISO_8859_1);
      int length = bytes.length + 1;
      if ((long) used + length + 1 >= size) break;
      memory.writeBytes(address + used, bytes);
      memory.writeByte(address + used + bytes.length, 0);
      used += length;
      count++;
    }
    if (used < size) memory.writeByte(address + used, 0);
    return count;
  }

  /**
   * {@code FS_GetModList} packing: {@code name\0description\0} per mod while {@code used + both
   * strings + 2 < size}, as the original's bound reads.
   */
  public static int encodeMods(
      QvmMemory memory, int address, int size, List<VmFiles.ModInfo> mods) {
    if (size > 0) memory.writeByte(address, 0);
    int used = 0, count = 0;
    for (VmFiles.ModInfo mod : mods) {
      byte[] name = mod.directory().getBytes(StandardCharsets.ISO_8859_1);
      byte[] description = mod.description().getBytes(StandardCharsets.ISO_8859_1);
      long need = (long) used + name.length + 1 + description.length + 1;
      if (need + 2 >= size) break;
      memory.writeBytes(address + used, name);
      memory.writeByte(address + used + name.length, 0);
      used += name.length + 1;
      memory.writeBytes(address + used, description);
      memory.writeByte(address + used + description.length, 0);
      used += description.length + 1;
      count++;
    }
    if (used < size) memory.writeByte(address + used, 0);
    return count;
  }
}
