import dev.bluevista.craftq3.core.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

/** Ciphertext and receive comparisons against unchanged client/server protocol-68 wrappers. */
class AuditLegacyXor {
  static final HexFormat HEX=HexFormat.of();
  static String hex(byte[]data){return data.length==0?"-":HEX.formatHex(data);}
  public static void main(String[]args)throws Exception {
    var client=new ProcessBuilder(Path.of(".tools/server-message-oracle/client-xor-oracle").toAbsolutePath().toString()).redirectError(ProcessBuilder.Redirect.INHERIT).start();
    var server=new ProcessBuilder(Path.of(".tools/server-message-oracle/server-xor-oracle").toAbsolutePath().toString()).redirectError(ProcessBuilder.Redirect.INHERIT).start();
    var rng=new Random(0x68252677);int sent=0,received=0;long bytes=0;
    try(var clientIn=client.outputWriter(StandardCharsets.US_ASCII);var clientOut=client.inputReader(StandardCharsets.US_ASCII);var serverIn=server.outputWriter(StandardCharsets.US_ASCII);var serverOut=server.inputReader(StandardCharsets.US_ASCII)) {
      for(int trial=0;trial<6_000;trial++)for(boolean clientSide:new boolean[]{true,false}) {
        int sequence=1+rng.nextInt(1_000_000),challenge=rng.nextInt(),serverId=rng.nextInt(),ack=rng.nextInt(),commandAck=trial%64;
        byte[] text=new byte[trial%100==0?1023:trial%80];rng.nextBytes(text);if(trial%10==0)Arrays.fill(text,(byte)(trial%256));
        String command=new String(text,StandardCharsets.ISO_8859_1),textHex=hex(text);
        var writer=new MessageWriter();writer.intValue(clientSide?serverId:commandAck);
        if(clientSide){writer.intValue(ack);writer.intValue(commandAck);}
        int length=trial%1600;for(int i=0;i<length;i++)writer.byteValue(rng.nextInt(256));
        String role=clientSide?"client":trial%7==0?"queued":"server";
        var input=clientSide?clientIn:serverIn;var output=clientSide?clientOut:serverOut;
        input.write(role+" "+sequence+" "+challenge+" "+writer.bitPosition()+" "+hex(writer.bytes())+" "+textHex+"\n");input.flush();
        writer.byteValue(clientSide?5:8);byte[] plain=writer.bytes();
        byte[] encoded=clientSide?LegacyPayloadXor.client(plain,challenge,serverId,ack,command):LegacyPayloadXor.server(plain,challenge,sequence,command);
        String expected="wire "+writer.bitPosition()+" "+hex(encoded),actual=output.readLine();
        if(!expected.equals(actual))throw new AssertionError("Encode "+role+" query="+trial+" server="+serverId+" ack="+ack+" challenge="+challenge+" command="+textHex+" expected="+expected+" actual="+actual);
        sent++;bytes+=encoded.length;
        var receiverIn=clientSide?serverIn:clientIn;var receiverOut=clientSide?serverOut:clientOut;
        receiverIn.write((clientSide?"receive-server":"receive-client")+" "+sequence+" "+challenge+" "+writer.bitPosition()+" "+hex(encoded)+" "+textHex+"\n");receiverIn.flush();
        expected="decoded "+hex(plain);actual=receiverOut.readLine();
        if(!expected.equals(actual))throw new AssertionError("Decode "+role+" query="+trial+" expected="+expected+" actual="+actual);
        byte[] restored=clientSide?LegacyPayloadXor.client(encoded,challenge,serverId,ack,command):LegacyPayloadXor.server(encoded,challenge,sequence,command);
        if(!Arrays.equals(plain,restored))throw new AssertionError("Java inverse query="+trial);
        received++;
      }
    }finally{client.destroy();server.destroy();}
    System.out.printf("Legacy XOR PASS: %,d encoded payloads / %,d exact bytes and %,d native decoded payloads; both directions and queued server sends%n",sent,bytes,received);
  }
}
