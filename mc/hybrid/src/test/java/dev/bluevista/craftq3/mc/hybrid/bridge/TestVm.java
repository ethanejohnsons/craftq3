package dev.bluevista.craftq3.mc.hybrid.bridge;

import dev.bluevista.craftq3.engine.vm.QvmImage;
import dev.bluevista.craftq3.engine.vm.QvmInstance;
import dev.bluevista.craftq3.engine.vm.QvmMemory;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** A do-nothing QVM ({@code ENTER 8; LEAVE 8}) whose memory tests write structs into. */
final class TestVm {
  private TestVm() {}

  static QvmMemory memory() {
    byte[] code = {3, 8, 0, 0, 0, 4, 8, 0, 0, 0, 0, 0};
    ByteBuffer file = ByteBuffer.allocate(32 + code.length).order(ByteOrder.LITTLE_ENDIAN);
    file.putInt(QvmImage.MAGIC_V1).putInt(2).putInt(32).putInt(10);
    file.putInt(32 + code.length).putInt(0).putInt(0).putInt(64 * 1024);
    file.put(code);
    QvmImage image = QvmImage.parse("test.qvm", file.array());
    return new QvmInstance(image, (vm, number, args) -> 0).memory();
  }
}
