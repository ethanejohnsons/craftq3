package dev.bluevista.craftq3.core.net.delta;

import dev.bluevista.craftq3.core.net.MessageReader;
import dev.bluevista.craftq3.core.net.MessageWriter;
import java.util.List;

/** Shared wire primitives, derived from field observations rather than native routine structure. */
final class DeltaFields {
  private DeltaFields() {}

  static int extent(byte[] from, byte[] to, List<StateFields.Field> fields) {
    for (int i = fields.size() - 1; i >= 0; i--) {
      int offset = fields.get(i).byteOffset();
      if (StateBytes.word(from, offset) != StateBytes.word(to, offset)) return i + 1;
    }
    return 0;
  }

  static void write(
      MessageWriter writer,
      byte[] from,
      byte[] to,
      List<StateFields.Field> fields,
      int count,
      boolean zeroShortcut) {
    writer.byteValue(count);
    for (StateFields.Field field : fields.subList(0, count)) {
      int value = StateBytes.word(to, field.byteOffset());
      boolean changed = value != StateBytes.word(from, field.byteOffset());
      writer.bits(changed ? 1 : 0, 1);
      if (!changed) continue;
      boolean floating = field.bitWidth() == 0;
      boolean zero = floating ? Float.intBitsToFloat(value) == 0 : value == 0;
      if (zeroShortcut) writer.bits(zero ? 0 : 1, 1);
      if (zeroShortcut && zero) continue;
      if (floating) {
        float number = Float.intBitsToFloat(value);
        boolean compact = number >= -4096 && number <= 4095 && number == (int) number;
        writer.bits(compact ? 0 : 1, 1);
        if (compact) writer.bits((int) number + 4096, 13);
        else writer.intValue(value);
      } else writer.field(value, field.bitWidth());
    }
  }

  static void read(
      MessageReader reader, byte[] result, List<StateFields.Field> fields, boolean zeroShortcut) {
    int count = reader.byteValue();
    if (count > fields.size())
      throw new IllegalArgumentException("Delta field count exceeds protocol table");
    for (StateFields.Field field : fields.subList(0, count)) {
      if (reader.bits(1) == 0) continue;
      int value;
      if (zeroShortcut && reader.bits(1) == 0) value = 0;
      else if (field.bitWidth() != 0) value = reader.field(field.bitWidth());
      else if (reader.bits(1) != 0) value = reader.intValue();
      else value = Float.floatToRawIntBits((float) (reader.bits(13) - 4096));
      StateBytes.word(result, field.byteOffset(), value);
    }
  }
}
