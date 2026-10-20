package dev.bluevista.craftq3.core.net;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.net.Inet6Address;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

final class MasterServerResponseTest {
  @Test
  void mixedExtendedAddressesPreserveFamilyPortsAndIpv6SenderScope() {
    byte[] mapped = HexFormat.of().parseHex("00000000000000000000ffff7f000001");
    var parsed =
        MasterServerResponse.parse(
            packet(
                true,
                entry(new byte[] {127, 0, 0, 1}, 27960),
                entry(mapped, 65535),
                ascii("\\EOT")),
            17);
    assertTrue(parsed.extended());
    assertEquals(2, parsed.endpoints().size());
    var first = parsed.endpoints().get(0);
    assertEquals(27960, first.port());
    assertEquals(0, first.scopeId());
    var second = parsed.endpoints().get(1);
    assertEquals(65535, second.port());
    assertEquals(17, second.scopeId());
    assertInstanceOf(Inet6Address.class, second.socketAddress().getAddress());
    assertArrayEquals(mapped, second.socketAddress().getAddress().getAddress());
  }

  @Test
  void finalRecordNeedsAValidFollowingDelimiterAndMalformedSuffixStopsParsing() {
    byte[] first = entry(new byte[] {1, 2, 3, 4}, 1);
    byte[] second = entry(new byte[] {5, 6, 7, 8}, 2);
    assertEquals(0, MasterServerResponse.parse(packet(false, first), 0).endpoints().size());
    assertEquals(1, MasterServerResponse.parse(packet(false, first, second), 0).endpoints().size());
    assertEquals(
        1,
        MasterServerResponse.parse(packet(false, first, second, ascii("X\\EOT")), 0)
            .endpoints()
            .size());
    assertEquals(
        1, MasterServerResponse.parse(packet(false, first, ascii("/")), 0).endpoints().size());
  }

  @Test
  void classicModeSeeksOnlyIpv4AndStopsAtAnIpv6RecordAfterAnIpv4Record() {
    byte[] ipv6 = entry(new byte[16], 27960);
    byte[] ipv4 = entry(new byte[] {127, 0, 0, 1}, 27960);
    assertEquals(
        1,
        MasterServerResponse.parse(packet(false, ipv6, ipv4, ascii("\\EOT")), 0)
            .endpoints()
            .size());
    assertEquals(
        1,
        MasterServerResponse.parse(packet(false, ipv4, ipv6, ipv4, ascii("\\EOT")), 0)
            .endpoints()
            .size());
  }

  @Test
  void recordLimitCountsDuplicatesAndAddressesRemainInertMetadata() {
    var body = new ByteArrayOutputStream();
    byte[] zero = entry(new byte[4], 0);
    for (int i = 0; i < 300; i++) body.writeBytes(zero);
    body.writeBytes(ascii("\\EOT"));
    var endpoints = MasterServerResponse.parse(packet(false, body.toByteArray()), 0).endpoints();
    assertEquals(256, endpoints.size());
    assertEquals(endpoints.getFirst(), endpoints.getLast());
    assertEquals(0, endpoints.getFirst().port());
  }

  @Test
  void eotIsATruncatedRecordRatherThanAMagicTokenInsideAPaddedRecord() {
    var endpoints = MasterServerResponse.parse(packet(false, ascii("\\EOTabc\\")), 0).endpoints();
    assertEquals(1, endpoints.size());
    assertArrayEquals(new byte[] {'E', 'O', 'T', 'a'}, endpoints.getFirst().addressBytes());
    assertEquals(0x6263, endpoints.getFirst().port());
  }

  @Test
  void ownsAddressBytesAndRejectsUnrecognizedOrUnboundedEnvelopes() {
    byte[] address = {1, 2, 3, 4};
    var endpoint = new MasterServerResponse.Endpoint(address, 1, 0);
    address[0] = 9;
    endpoint.addressBytes()[1] = 9;
    assertArrayEquals(new byte[] {1, 2, 3, 4}, endpoint.addressBytes());
    assertThrows(
        IllegalArgumentException.class,
        () -> MasterServerResponse.parse(ConnectionlessPacket.text("statusResponse\n"), 0));
    assertThrows(
        IllegalArgumentException.class,
        () -> MasterServerResponse.parse(new byte[ConnectionlessPacket.MAX_PAYLOAD + 5], 0));
    assertThrows(
        IllegalArgumentException.class, () -> MasterServerResponse.parse(packet(false), -1));
    assertThrows(
        IllegalArgumentException.class, () -> new MasterServerResponse.Endpoint(new byte[4], 1, 7));
  }

  private static byte[] packet(boolean extended, byte[]... parts) {
    var body = new ByteArrayOutputStream();
    body.writeBytes(ascii(extended ? "getserversExtResponse" : "getserversResponse"));
    for (byte[] part : parts) body.writeBytes(part);
    return ConnectionlessPacket.encode(body.toByteArray());
  }

  private static byte[] entry(byte[] address, int port) {
    var result = new ByteArrayOutputStream();
    result.write(address.length == 16 ? '/' : '\\');
    result.writeBytes(address);
    result.write(port >>> 8);
    result.write(port);
    return result.toByteArray();
  }

  private static byte[] ascii(String text) {
    return text.getBytes(StandardCharsets.ISO_8859_1);
  }
}
