package dev.bluevista.craftq3.client;

import dev.bluevista.craftq3.vm.QvmMemory;
import java.util.Arrays;

/** The shared snapshot_t guest layout, independent of local or network history ownership. */
final class CgameSnapshotWriter {
  private CgameSnapshotWriter() {}

  static void write(QvmMemory memory, ClientAbi abi, CgameSource.Snapshot snapshot, int pointer) {
    memory.checkRange(pointer, abi.snapshotBytes());
    int count = pointer + 44 + abi.game().playerStateBytes();
    int tail = count + 4 + 256 * abi.game().entityStateBytes();
    memory.fill(pointer, tail - pointer, 0);
    memory.writeInt(pointer, snapshot.flags());
    memory.writeInt(pointer + 4, snapshot.ping());
    memory.writeInt(pointer + 8, snapshot.time());
    memory.writeBytes(pointer + 12, snapshot.areaMask());
    abi.playerState(memory, pointer + 44, snapshot.player());
    var entities = snapshot.entities();
    memory.writeInt(count, entities.size());
    int index = 0;
    for (byte[] entity : entities)
      memory.writeBytes(
          count + 4 + index++ * abi.game().entityStateBytes(),
          Arrays.copyOf(entity, abi.game().entityStateBytes()));
    if (snapshot.serverCommandCount() >= 0) memory.writeInt(tail, snapshot.serverCommandCount());
    memory.writeInt(tail + 4, snapshot.serverCommandSequence());
  }
}
