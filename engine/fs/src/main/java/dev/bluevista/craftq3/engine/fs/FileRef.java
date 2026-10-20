package dev.bluevista.craftq3.engine.fs;

/**
 * Where a virtual path resolved: the mount that supplies it and the name stored there (the PK3
 * member name, or the loose file's on-disk relative name).
 */
public record FileRef(VirtualPath path, Mount mount, String storedName) {
  /** True when the file comes from a PK3. */
  public boolean inPack() {
    return mount instanceof PackMount;
  }
}
