import dev.bluevista.craftq3.core.net.*;
import dev.bluevista.craftq3.core.net.SnapshotDeltaCodec.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

/** Sends authored Java snapshot bodies through the unchanged native client parser. */
class AuditSnapshots {
  static final HexFormat HEX=HexFormat.of();
  static byte[] entity(int id,Random rng) {
    byte[] bytes=new byte[208];var buffer=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);buffer.putInt(0,id);
    for(int i=0;i<8;i++)buffer.putInt(4+rng.nextInt(51)*4,rng.nextInt(256));
    return bytes;
  }
  static byte[] player(Random rng) {
    byte[] bytes=new byte[468];var buffer=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
    for(int i=0;i<12;i++)buffer.putInt(rng.nextInt(117)*4,rng.nextInt(1000));return bytes;
  }
  static void require(String expected,String actual,int trial) {
    if(!expected.equals(actual))throw new AssertionError("query="+trial+" expected="+expected+" actual="+actual);
  }
  public static void main(String[]args)throws Exception {
    var process=new ProcessBuilder(Path.of(".tools/server-message-oracle/snapshot-oracle").toAbsolutePath().toString()).redirectError(ProcessBuilder.Redirect.INHERIT).start();
    int trials=args.length==0?3_000:Integer.parseInt(args[0]);int entities=0;long bytes=0;var rng=new Random(0x68260225);
    try(var input=process.outputWriter(StandardCharsets.US_ASCII);var output=process.inputReader(StandardCharsets.US_ASCII)) {
      for(int trial=0;trial<trials;trial++) {
        input.write("reset\n");input.flush();require("reset",output.readLine(),trial);
        int slots=trial==0?0:trial%100==0?256:rng.nextInt(50);
        var baselineList=new ArrayList<byte[]>();var beforeList=new ArrayList<byte[]>();var afterList=new ArrayList<byte[]>();
        for(int id=0;id<slots;id++) {
          int number=id==slots-1?1022:id*3;
          var baseline=entity(number,rng);baselineList.add(baseline);
          input.write("baseline "+HEX.formatHex(baseline)+"\n");input.flush();require("baseline",output.readLine(),trial);
          if(rng.nextInt(3)!=0||slots==256)beforeList.add(rng.nextBoolean()?baseline:entity(number,rng));
          if(rng.nextInt(4)!=0||slots==256)afterList.add(rng.nextInt(4)==0?baseline:entity(number,rng));
        }
        boolean full=trial%4==0;int distance=1+rng.nextInt(255),sequence=300+trial;
        var from=full?null:new Snapshot(sequence-distance,trial-1,0,new byte[0],player(rng),beforeList);
        var p=from==null?new byte[468]:from.player();
        if(trial%3==1)p=player(rng);else if(trial%3==2)ByteBuffer.wrap(p).order(ByteOrder.LITTLE_ENDIAN).putInt(0,rng.nextInt());
        var area=new byte[trial%33];rng.nextBytes(area);int time=trial==0?0:rng.nextInt(),flags=trial%256;
        var to=new Snapshot(sequence,time,flags,area,p,afterList);var baselines=new Baselines(baselineList);
        if(from!=null) {
          input.write("previous "+from.sequence()+" "+from.entities().size()+" "+HEX.formatHex(from.player())+"\n");
          for(var e:from.entities())input.write(HEX.formatHex(e)+"\n");input.flush();require("previous",output.readLine(),trial);
        }
        int prefix=trial%8;var writer=new MessageWriter();if(prefix!=0)writer.bits(127,prefix);
        SnapshotDeltaCodec.write(writer,from,to,baselines);
        var reader=new MessageReader(writer.bytes());if(prefix!=0)reader.bits(prefix);
        var decoded=SnapshotDeltaCodec.read(reader,sequence,from,baselines);
        int commands=trial*3;
        input.write("parse "+sequence+" "+commands+" "+prefix+" "+HEX.formatHex(writer.bytes())+"\n");input.flush();
        String expected="snapshot 1 "+writer.bitPosition()+" "+sequence+" "+(full?-1:from.sequence())+" "+time+" "+flags+" "+commands+" "+decoded.entities().size()+" "+HEX.formatHex(Arrays.copyOf(decoded.areaMask(),32))+" "+HEX.formatHex(decoded.player());
        require(expected,output.readLine(),trial);
        for(var e:decoded.entities())require(HEX.formatHex(e),output.readLine(),trial);
        if(reader.bitPosition()!=writer.bitPosition())throw new AssertionError("Java cursor query="+trial);
        if(trial==0)System.out.println("Empty full snapshot: bits="+writer.bitPosition()+" hex="+HEX.formatHex(writer.bytes()));
        entities+=decoded.entities().size();bytes+=writer.bytes().length;
      }
    }finally{process.destroy();}
    System.out.printf("Snapshots PASS: %,d bodies / %,d bytes accepted by native parser; %,d entities, complete player/area states and bit cursors match%n",trials,bytes,entities);
  }
}
