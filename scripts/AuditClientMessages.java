import dev.bluevista.craftq3.core.net.*;
import dev.bluevista.craftq3.core.net.ClientMessageCodec.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

/** Byte comparison with unchanged native CL_WritePacket, including its movement hash key. */
class AuditClientMessages {
  static final HexFormat HEX=HexFormat.of();
  static String hex(String text){return text.isEmpty()?"-":HEX.formatHex(text.getBytes(StandardCharsets.ISO_8859_1));}
  public static void main(String[]args)throws Exception {
    var process=new ProcessBuilder(Path.of(".tools/server-message-oracle/client-message-oracle").toAbsolutePath().toString()).redirectError(ProcessBuilder.Redirect.INHERIT).start();
    var rng=new Random(0x68322625);long wireBytes=0;int totalCommands=0;
    try(var input=process.outputWriter(StandardCharsets.US_ASCII);var output=process.inputReader(StandardCharsets.US_ASCII)) {
      for(int trial=0;trial<4_000;trial++) {
        int count=trial%33,reliableCount=trial%101==0?64:trial%9;
        byte[] rawText=new byte[trial%80];rng.nextBytes(rawText);String serverText=new String(rawText,StandardCharsets.ISO_8859_1);
        var reliable=new ArrayList<Command>();for(int i=1;i<=reliableCount;i++)reliable.add(new Command(i,"say \"wire "+trial+" %\u0080 "+i+"\""));
        var commands=new ArrayList<byte[]>();int time=rng.nextInt(100_000);
        for(int i=0;i<count;i++) {
          byte[] cmd=new byte[24];rng.nextBytes(cmd);var b=ByteBuffer.wrap(cmd).order(ByteOrder.LITTLE_ENDIAN);time+=rng.nextInt(256);b.putInt(0,time);
          for(int j=4;j<=16;j+=4)b.putInt(j,b.getInt(j)&65535);
          commands.add(cmd);
        }
        int server=rng.nextInt(),ack=rng.nextInt(),serverAck=rng.nextInt(),feed=rng.nextInt();boolean delta=trial%2==0;
        var message=new Message(server,ack,serverAck,reliable,count==0?null:new Movement(delta,commands));
        var writer=new MessageWriter();ClientMessageCodec.writeBody(writer,message,feed,serverText);
        input.write(server+" "+ack+" "+serverAck+" "+feed+" "+(delta?0:1)+" "+count+" "+reliableCount+" "+hex(serverText)+"\n");
        for(var command:reliable)input.write(hex(command.text())+"\n");
        for(var command:commands)input.write(HEX.formatHex(command)+"\n");input.flush();
        String expected="body "+writer.bitPosition()+" "+HEX.formatHex(writer.bytes()),actual=output.readLine();
        if(!expected.equals(actual))throw new AssertionError("Client wire query="+trial+" expected="+expected+" actual="+actual);
        int bodyBytes=writer.bytes().length;
        writer.byteValue(5);var reader=new MessageReader(writer.bytes());var decoded=ClientMessageCodec.read(reader,feed,ignored->serverText);
        if(decoded.serverId()!=server||decoded.messageAcknowledge()!=ack||decoded.serverCommandAcknowledge()!=serverAck||decoded.commands().size()!=reliableCount||reader.bitPosition()!=writer.bitPosition())throw new AssertionError("Header query="+trial);
        if(count>0) {
          if(decoded.movement().requestDelta()!=delta)throw new AssertionError("Delta flag query="+trial);
          for(int i=0;i<count;i++) {
            byte[] target=commands.get(i).clone();for(int j=21;j<24;j++)if(target[j]==-128)target[j]=-127;
            if(!Arrays.equals(target,decoded.movement().commands().get(i)))throw new AssertionError("Command query="+trial+" index="+i);
          }
        }
        wireBytes+=bodyBytes;totalCommands+=count;
      }
    }finally{process.destroy();}
    System.out.printf("Client messages PASS: 4,000 bodies / %,d normalized native wire bytes; %,d keyed user commands decode with reliable text, all batch sizes and both delta flags; only unused tail bits zeroed%n",wireBytes,totalCommands);
  }
}
