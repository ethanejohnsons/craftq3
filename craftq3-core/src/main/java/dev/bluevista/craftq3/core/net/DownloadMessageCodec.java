package dev.bluevista.craftq3.core.net;

/** Body of svc_download, with the absolute expected block supplied by the transfer owner. */
public final class DownloadMessageCodec {
  private DownloadMessageCodec() {}

  public static void write(MessageWriter output, ServerMessageCodec.Download download) {
    output.transaction(
        writer -> {
          writer.shortValue(download.block());
          if (download.fileSize() != null) writer.intValue(download.fileSize());
          if (download.error() != null) writer.stringValue(download.error());
          else {
            byte[] bytes = download.data();
            writer.shortValue(bytes.length);
            for (byte value : bytes) writer.byteValue(Byte.toUnsignedInt(value));
          }
          return null;
        });
  }

  public static ServerMessageCodec.Download read(MessageReader input, int expectedBlock) {
    if (expectedBlock < 0) throw new IllegalArgumentException("Invalid expected download block");
    return input.transaction(
        reader -> {
          int block = reader.bits(16);
          Integer size =
              block == 0 && expectedBlock == 0 ? Integer.valueOf(reader.intValue()) : null;
          String error = size != null && size < 0 ? reader.stringValue() : null;
          byte[] data;
          if (error != null) data = new byte[0];
          else {
            int count = reader.shortValue();
            if (count < 0 || count > Protocol68Channel.MAX_MESSAGE)
              throw new IllegalArgumentException("Invalid download chunk size");
            data = new byte[count];
            for (int i = 0; i < count; i++) data[i] = (byte) reader.byteValue();
          }
          return new ServerMessageCodec.Download(block, size, data, error);
        });
  }
}
