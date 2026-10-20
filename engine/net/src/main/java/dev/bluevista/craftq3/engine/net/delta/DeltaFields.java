package dev.bluevista.craftq3.engine.net.delta;

import dev.bluevista.craftq3.engine.net.NetFormatException;
import dev.bluevista.craftq3.engine.net.msg.MessageReader;
import dev.bluevista.craftq3.engine.net.msg.MessageWriter;

/** Shared wire primitives, derived from field observations rather than native routine structure. */
final class DeltaFields {
  /** Floats with an integral value in [-4096, 4095] are sent in 13 bits, offset by this bias. */
  static final int FLOAT_INT_BIAS = 4096;

  static final int FLOAT_INT_BITS = 13;

  private DeltaFields() {}

  /** One past the last field index whose word differs, or 0 when the states are equal. */
  static int extent(byte[] from, byte[] to, StateFields.Table fields) {
    for (int i = fields.size() - 1; i >= 0; i--) {
      int offset = fields.offset(i);
      if (StateBytes.word(from, offset) != StateBytes.word(to, offset)) return i + 1;
    }
    return 0;
  }

  static void write(
      MessageWriter writer,
      byte[] from,
      byte[] to,
      StateFields.Table fields,
      int count,
      boolean zeroShortcut) {
    writer.byteValue(count);
    for (int i = 0; i < count; i++) {
      int offset = fields.offset(i);
      int value = StateBytes.word(to, offset);
      boolean changed = value != StateBytes.word(from, offset);
      writer.bits(changed ? 1 : 0, 1);
      if (!changed) continue;
      int width = fields.width(i);
      boolean floating = width == 0;
      boolean zero = floating ? Float.intBitsToFloat(value) == 0 : value == 0;
      if (zeroShortcut) writer.bits(zero ? 0 : 1, 1);
      if (zeroShortcut && zero) continue;
      if (floating) {
        float number = Float.intBitsToFloat(value);
        boolean compact =
            number >= -FLOAT_INT_BIAS && number <= FLOAT_INT_BIAS - 1 && number == (int) number;
        writer.bits(compact ? 0 : 1, 1);
        if (compact) writer.bits((int) number + FLOAT_INT_BIAS, FLOAT_INT_BITS);
        else writer.intValue(value);
      } else writer.field(value, width);
    }
  }

  static void read(
      MessageReader reader, byte[] result, StateFields.Table fields, boolean zeroShortcut) {
    int count = reader.byteValue();
    if (count > fields.size())
      throw new NetFormatException(
          "Delta field count " + count + " exceeds protocol table of " + fields.size());
    for (int i = 0; i < count; i++) {
      if (reader.bits(1) == 0) continue;
      int width = fields.width(i);
      int value;
      if (zeroShortcut && reader.bits(1) == 0) value = 0;
      else if (width != 0) value = reader.field(width);
      else if (reader.bits(1) != 0) value = reader.intValue();
      else value = Float.floatToRawIntBits((float) (reader.bits(FLOAT_INT_BITS) - FLOAT_INT_BIAS));
      StateBytes.word(result, fields.offset(i), value);
    }
  }
}
