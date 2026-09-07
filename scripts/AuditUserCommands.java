import dev.bluevista.craftq3.core.net.*;
import dev.bluevista.craftq3.core.net.delta.UserCommandDeltaCodec;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

/** Native keyed-command wire/canonical-state differential, including nonmonotonic time probes. */
class AuditUserCommands {
  public static void main(String[] args)throws Exception {
    var process=new ProcessBuilder(Path.of(".tools/netchan-oracle/usercmd-oracle").toAbsolutePath().toString()).redirectError(ProcessBuilder.Redirect.INHERIT).start();
    var rng=new Random(0x68324025);long bytes=0;int checks=0;
    try(var input=process.outputWriter(StandardCharsets.US_ASCII);var output=process.inputReader(StandardCharsets.US_ASCII)) {
      for(int trial=0;trial<32_000;trial++) {
        byte[] from=new byte[24];rng.nextBytes(from);if(trial%31==0)Arrays.fill(from,(byte)0);
        byte[] to=from.clone();
        if(trial%5==0)rng.nextBytes(to);
        else if(trial%5==1)to[4+rng.nextInt(20)]=(byte)rng.nextInt(256);
        else if(trial%5==2)Arrays.fill(to,21,24,(byte)128);
        int oldTime=ByteBuffer.wrap(from).order(ByteOrder.LITTLE_ENDIAN).getInt(0);
        int time=switch(trial%8){case 0->oldTime;case 1->oldTime+1;case 2->oldTime+255;case 3->oldTime+256;case 4->oldTime-1;case 5->oldTime-256;case 6->rng.nextInt();default->Integer.MIN_VALUE;};
        ByteBuffer.wrap(to).order(ByteOrder.LITTLE_ENDIAN).putInt(0,time);
        int key=rng.nextInt(),prefix=trial%8;var writer=new MessageWriter();if(prefix>0)writer.bits((1<<prefix)-1,prefix);
        UserCommandDeltaCodec.write(writer,key,trial%31==0?null:from,to);
        input.write(Integer.toUnsignedString(key)+" "+prefix+" "+(trial%31==0?"-":HexFormat.of().formatHex(from))+" "+HexFormat.of().formatHex(to)+"\n");input.flush();
        String expected="WIRE "+writer.bitPosition()+" "+HexFormat.of().formatHex(writer.bytes()),actual=output.readLine();
        if(!expected.equals(actual))throw new AssertionError("Wire mismatch query="+trial+" expected="+expected+" actual="+actual+" from="+HexFormat.of().formatHex(from)+" to="+HexFormat.of().formatHex(to)+" key="+key);
        var reader=new MessageReader(writer.bytes());if(prefix>0)reader.bits(prefix);
        byte[] decoded=UserCommandDeltaCodec.read(reader,key,trial%31==0?null:from);
        expected="STATE "+reader.bitPosition()+" "+HexFormat.of().formatHex(decoded);actual=output.readLine();
        if(!expected.equals(actual))throw new AssertionError("State mismatch query="+trial+" expected="+expected+" actual="+actual+" from="+HexFormat.of().formatHex(from)+" to="+HexFormat.of().formatHex(to));
        checks++;bytes+=writer.bytes().length;
      }
    }finally{process.destroy();}
    System.out.printf("User commands PASS: %,d keyed deltas, %,d exact wire bytes and canonical states; all bit alignments/time modes%n",checks,bytes);
  }
}
