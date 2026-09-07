import dev.bluevista.craftq3.core.net.*;
import java.nio.*;
import java.util.*;

/** Fixed private-loopback packet, captured before the first gamestate; no asset bytes. */
class DecodePurePreGamePacket {
  public static void main(String[] args) {
    int challenge = -2038325531;
    byte[] packet = HexFormat.of().parseHex("01000000aa7fa748f1561183cb6d4da9e78c56e6");
    int sequence = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN).getInt();
    byte[] decoded =
        LegacyPayloadXor.server(
            Arrays.copyOfRange(packet, 4, packet.length), challenge, sequence, "");
    var message =
        ServerMessageCodec.read(
            new MessageReader(decoded),
            sequence,
            SnapshotDeltaCodec.Baselines.EMPTY,
            ignored -> null);
    System.out.println(
        "ACK " + message.reliableAcknowledge() + " operations=" + message.operations().size());
    for (var operation : message.operations()) {
      if (operation instanceof ServerMessageCodec.Frame frame) {
        var snapshot = frame.current();
        System.out.println(
            "SNAPSHOT sequence="
                + snapshot.sequence()
                + " time="
                + snapshot.time()
                + " flags="
                + snapshot.flags()
                + " delta="
                + (frame.previous() != null)
                + " areaBytes="
                + snapshot.areaMask().length
                + " entities="
                + snapshot.entities().size()
                + " zeroPlayer="
                + Arrays.equals(snapshot.player(), new byte[snapshot.player().length]));
      } else System.out.println(operation);
    }
  }
}
