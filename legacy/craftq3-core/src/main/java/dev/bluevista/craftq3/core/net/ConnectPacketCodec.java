package dev.bluevista.craftq3.core.net;

import java.util.Arrays;
import java.util.Objects;

/** Fresh adaptive Huffman coding for the bytes after an OOB {@code connect } prefix. */
public final class ConnectPacketCodec {
  public static final int PREFIX_LENGTH = 12;
  private static final byte[] PREFIX = {-1, -1, -1, -1, 'c', 'o', 'n', 'n', 'e', 'c', 't', ' '};

  private ConnectPacketCodec() {}

  /**
   * Compresses an owned copy of a complete raw connect datagram. No quoting, sanitation or NUL is
   * added. Inputs which would overflow the native encoder's transmit bound are rejected instead of
   * returning its truncated, non-roundtripping output.
   */
  public static byte[] compress(byte[] rawDatagram) {
    validate(rawDatagram);
    int count = rawDatagram.length - PREFIX_LENGTH;
    if (count == 0) return rawDatagram.clone();
    // A final first-occurrence literal can extend past the native tree-code limit by eight bits.
    byte[] encoded = new byte[Protocol68Channel.MAX_MESSAGE + 3];
    System.arraycopy(PREFIX, 0, encoded, 0, PREFIX_LENGTH);
    encoded[PREFIX_LENGTH] = (byte) (count >>> 8);
    encoded[PREFIX_LENGTH + 1] = (byte) count;
    Bits bits = new Bits(encoded, PREFIX_LENGTH * 8 + 16);
    AdaptiveHuffmanTree tree = new AdaptiveHuffmanTree();
    boolean[] path = new boolean[256];
    int treeCodeLimit = PREFIX_LENGTH * 8 + count * 8;
    for (int index = PREFIX_LENGTH; index < rawDatagram.length; index++) {
      int symbol = rawDatagram[index] & 255;
      AdaptiveHuffmanTree.Node node = tree.symbol(symbol);
      boolean literal = node == null;
      if (literal) node = tree.unseen();
      int length = 0;
      while (node.parent != null) {
        path[length++] = node.parent.right == node;
        node = node.parent;
      }
      if (length > 0 && bits.position + length > treeCodeLimit)
        throw new IllegalArgumentException(
            "Native connect encoder would exceed its transmit bound");
      while (length > 0) bits.write(path[--length] ? 1 : 0);
      if (literal) for (int shift = 7; shift >= 0; shift--) bits.write(symbol >>> shift & 1);
      tree.observe(symbol);
    }
    // Native framing retains a padding byte even when the final bit is exactly byte-aligned.
    int size = bits.position / 8 + 1;
    if (size > Protocol68Channel.MAX_MESSAGE)
      throw new IllegalArgumentException("Compressed connect datagram exceeds the message limit");
    return Arrays.copyOf(encoded, size);
  }

  /**
   * Returns the complete raw datagram in a new array. Advertised lengths and every consumed bit are
   * checked before access; unused trailing bytes are allowed, as in the native decoder.
   */
  public static byte[] decompress(byte[] compressedDatagram) {
    validate(compressedDatagram);
    if (compressedDatagram.length == PREFIX_LENGTH) return compressedDatagram.clone();
    if (compressedDatagram.length < PREFIX_LENGTH + 2)
      throw new IllegalArgumentException("Truncated adaptive connect length");
    int count =
        (compressedDatagram[PREFIX_LENGTH] & 255) << 8
            | compressedDatagram[PREFIX_LENGTH + 1] & 255;
    if (count > Protocol68Channel.MAX_MESSAGE - PREFIX_LENGTH)
      throw new IllegalArgumentException("Advertised connect length exceeds the message limit");
    byte[] decoded = new byte[PREFIX_LENGTH + count];
    System.arraycopy(PREFIX, 0, decoded, 0, PREFIX_LENGTH);
    Bits bits = new Bits(compressedDatagram, PREFIX_LENGTH * 8 + 16);
    AdaptiveHuffmanTree tree = new AdaptiveHuffmanTree();
    for (int index = PREFIX_LENGTH; index < decoded.length; index++) {
      AdaptiveHuffmanTree.Node node = tree.root();
      while (node.left != null) node = bits.read() == 0 ? node.left : node.right;
      int symbol = node.symbol;
      if (node == tree.unseen()) {
        symbol = 0;
        for (int bit = 0; bit < 8; bit++) symbol = symbol << 1 | bits.read();
      }
      decoded[index] = (byte) symbol;
      tree.observe(symbol);
    }
    return decoded;
  }

  private static void validate(byte[] packet) {
    Objects.requireNonNull(packet, "packet");
    if (packet.length < PREFIX_LENGTH || packet.length > Protocol68Channel.MAX_MESSAGE)
      throw new IllegalArgumentException("Invalid connect datagram size");
    for (int index = 0; index < PREFIX_LENGTH; index++)
      if (packet[index] != PREFIX[index])
        throw new IllegalArgumentException("Invalid connectionless connect prefix");
  }

  private static final class Bits {
    private final byte[] bytes;
    private int position;

    Bits(byte[] bytes, int position) {
      this.bytes = bytes;
      this.position = position;
    }

    void write(int value) {
      bytes[position >>> 3] |= (byte) (value << (position & 7));
      position++;
    }

    int read() {
      if (position >= bytes.length * 8)
        throw new IllegalArgumentException("Truncated adaptive connect bit stream");
      int result = bytes[position >>> 3] >>> (position & 7) & 1;
      position++;
      return result;
    }
  }
}
