package dev.bluevista.craftq3.engine.fs;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/** Writes ZIP bytes field by field, so tests can build archives the JDK writer cannot. */
final class TestZip {
  private record Member(byte[] name, byte[] data, int method, int flags, int declaredMethod) {}

  private final List<Member> members = new ArrayList<>();
  private byte[] prefix = new byte[0];
  private byte[] comment = new byte[0];
  private int method = Pk3Archive.DEFLATED;

  static TestZip create() {
    return new TestZip();
  }

  TestZip stored() {
    method = Pk3Archive.STORED;
    return this;
  }

  TestZip add(String name, String text) {
    return add(name, text.getBytes(StandardCharsets.ISO_8859_1));
  }

  TestZip add(String name, byte[] data) {
    members.add(new Member(latin1(name), data, method, 0, method));
    return this;
  }

  /** A member with raw general-purpose flags (bit 0 marks encryption). */
  TestZip addFlagged(String name, String text, int flags) {
    members.add(new Member(latin1(name), latin1(text), method, flags, method));
    return this;
  }

  /** A stored member whose header claims another compression method. */
  TestZip addWithMethod(String name, String text, int declaredMethod) {
    members.add(new Member(latin1(name), latin1(text), Pk3Archive.STORED, 0, declaredMethod));
    return this;
  }

  TestZip directory(String name) {
    members.add(new Member(latin1(name), new byte[0], Pk3Archive.STORED, 0, Pk3Archive.STORED));
    return this;
  }

  TestZip prefix(byte[] bytes) {
    prefix = bytes.clone();
    return this;
  }

  TestZip comment(String text) {
    comment = latin1(text);
    return this;
  }

  private static byte[] latin1(String text) {
    return text.getBytes(StandardCharsets.ISO_8859_1);
  }

  byte[] bytes() {
    var body = new ByteArrayOutputStream();
    var directory = new ByteArrayOutputStream();
    for (Member member : members) {
      var crc = new CRC32();
      crc.update(member.data);
      byte[] payload = member.method == Pk3Archive.DEFLATED ? deflate(member.data) : member.data;
      int offset = body.size();
      ByteBuffer local = little(30);
      local.putInt(0x04034b50).putShort((short) 20).putShort((short) member.flags);
      local.putShort((short) member.declaredMethod).putInt(0).putInt((int) crc.getValue());
      local.putInt(payload.length).putInt(member.data.length);
      local.putShort((short) member.name.length).putShort((short) 0);
      body.writeBytes(local.array());
      body.writeBytes(member.name);
      body.writeBytes(payload);
      ByteBuffer central = little(46);
      central.putInt(0x02014b50).putShort((short) 20).putShort((short) 20);
      central.putShort((short) member.flags).putShort((short) member.declaredMethod).putInt(0);
      central.putInt((int) crc.getValue()).putInt(payload.length).putInt(member.data.length);
      central.putShort((short) member.name.length).putShort((short) 0).putShort((short) 0);
      central.putShort((short) 0).putShort((short) 0).putInt(0).putInt(offset);
      directory.writeBytes(central.array());
      directory.writeBytes(member.name);
    }
    ByteBuffer end = little(22);
    end.putInt(0x06054b50).putShort((short) 0).putShort((short) 0);
    end.putShort((short) members.size()).putShort((short) members.size());
    end.putInt(directory.size()).putInt(body.size()).putShort((short) comment.length);
    var out = new ByteArrayOutputStream();
    out.writeBytes(prefix);
    out.writeBytes(body.toByteArray());
    out.writeBytes(directory.toByteArray());
    out.writeBytes(end.array());
    out.writeBytes(comment);
    return out.toByteArray();
  }

  Path write(Path file) throws IOException {
    Files.createDirectories(file.getParent());
    return Files.write(file, bytes());
  }

  private static ByteBuffer little(int size) {
    return ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
  }

  private static byte[] deflate(byte[] data) {
    var deflater = new Deflater(Deflater.DEFAULT_COMPRESSION, true);
    deflater.setInput(data);
    deflater.finish();
    var out = new ByteArrayOutputStream();
    byte[] buffer = new byte[4096];
    while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer));
    deflater.end();
    return out.toByteArray();
  }
}
