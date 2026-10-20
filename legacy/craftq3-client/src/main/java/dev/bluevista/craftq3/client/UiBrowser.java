package dev.bluevista.craftq3.client;

import java.io.IOException;
import java.util.Optional;

/** Borrowed multiplayer browser services; original UI bytecode owns menu state and selection. */
public interface UiBrowser {
  UiBrowser EMPTY = new UiBrowser() {};

  record Ping(String address, int milliseconds) {}

  /** Native getters address the full fixed array, including slots beyond the active count. */
  default int capacity(int source) {
    return 0;
  }

  default int count(int source) {
    return 0;
  }

  default String address(int source, int index) {
    return "";
  }

  default String info(int source, int index) {
    return "";
  }

  default int serverPing(int source, int index) {
    return -1;
  }

  default int visible(int source, int index) {
    return 0;
  }

  default void markVisible(int source, int index, int value) {}

  default void resetPings(int source) {}

  default int compare(int source, int key, int direction, int first, int second) {
    return 0;
  }

  default int add(int source, String name, String address) {
    return -1;
  }

  default void remove(int source, String address) {}

  default void loadCache() throws IOException {}

  default void saveCache() throws IOException {}

  default int pingCount() {
    return 0;
  }

  default void clearPing(int index) {}

  /** Side-effect-free classification for native output-buffer semantics. */
  default boolean pingOccupied(int index) {
    return false;
  }

  default Ping ping(int index) {
    return new Ping("", 0);
  }

  default String pingInfo(int index) {
    return "";
  }

  default boolean updatePings(int source) {
    return false;
  }

  /** An absent result leaves the guest's existing output buffer untouched. */
  default Optional<String> status(String address, int capacity) {
    return Optional.empty();
  }

  /** Null resets all status requests; a nonnull address resets that endpoint's request. */
  default void resetStatus(String address) {}
}
