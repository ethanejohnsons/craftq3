import dev.bluevista.craftq3.core.net.*;
import dev.bluevista.craftq3.core.net.ServerMessageCodec.*;
import dev.bluevista.craftq3.core.net.SnapshotDeltaCodec.*;
import dev.bluevista.craftq3.core.demo.*;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

/** Native server-message parsing of authored levels, reliable commands and snapshot chains. */
class AuditServerMessages {
  static final HexFormat HEX=HexFormat.of();
  static byte[] entity(int id,Random rng) {
    byte[] bytes=new byte[208];var b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);b.putInt(0,id);b.putInt(4,rng.nextInt(16));b.putInt(164,rng.nextInt(64));
    b.putFloat(24,rng.nextInt(4096)-2048);b.putFloat(28,rng.nextFloat()*300);return bytes;
  }
  static String hex(String text){return text.isEmpty()?"-":HEX.formatHex(text.getBytes(StandardCharsets.ISO_8859_1));}
  static void require(String expected,String actual,int trial) {
    if(!expected.equals(actual))throw new AssertionError("query="+trial+" expected="+expected+" actual="+actual);
  }
  public static void main(String[]args)throws Exception {
    var process=new ProcessBuilder(Path.of(".tools/server-message-oracle/message-oracle").toAbsolutePath().toString()).redirectError(ProcessBuilder.Redirect.INHERIT).start();
    int trials=args.length==0?1_500:Integer.parseInt(args[0]);long wireBytes=0;int entityCount=0,commandCount=0;var rng=new Random(0x68252602);
    Baselines baseline=Baselines.EMPTY;var history=new TreeMap<Integer,Snapshot>();GameState state=null;
    var commands=new TreeMap<Integer,String>();int commandSequence=0,lastSnapshotCommands=0;
    Snapshot latest=null;int lastDelta=0;
    var demoBytes=new ByteArrayOutputStream();var demoWriter=new DemoWriter(demoBytes);
    var expectedFrames=new ArrayList<Snapshot>();var expectedLevels=new ArrayList<GameState>();
    try(var input=process.outputWriter(StandardCharsets.US_ASCII);var output=process.inputReader(StandardCharsets.US_ASCII)) {
      for(int trial=0;trial<trials;trial++) {
        boolean reset=trial%30==0,restart=trial%30==15;
        if(reset) {input.write("reset\n");input.flush();require("reset",output.readLine(),trial);history.clear();commands.clear();commandSequence=0;latest=null;lastDelta=0;baseline=Baselines.EMPTY;}
        int sequence=100+trial,ack=trial%63;
        var operations=new ArrayList<Operation>();operations.add(NoOp.INSTANCE);
        if(reset||restart) {
          var strings=new TreeMap<Integer,String>();strings.put(0,"\\mapname\\oracle");strings.put(1,"\\sv_serverid\\17\\sv_pure\\0\\sv_cheats\\1");
          for(int i=0;i<12;i++)strings.put(2+i*79,"value "+i+" "+trial+" %\u0080\u00ff");
          var entities=new ArrayList<byte[]>();for(int i=1;i<30;i++)entities.add(entity(i,rng));
          operations.add(new GameState(commandSequence,strings,new Baselines(entities),trial%64,rng.nextInt()));
        }
        for(int i=0;i<trial%4;i++)operations.add(new Command(commandSequence+i+1,"print \"message "+trial+" "+i+" %\u0081\""));
        Snapshot from=reset||restart||trial%7==0||history.isEmpty()?null:history.lastEntry().getValue();
        var entities=new ArrayList<byte[]>();for(int i=1;i<30;i++)if(rng.nextBoolean())entities.add(entity(i,rng));
        byte[] player=from==null?new byte[468]:from.player();var ps=ByteBuffer.wrap(player).order(ByteOrder.LITTLE_ENDIAN);ps.putInt(0,trial*50);ps.putInt(184,rng.nextInt(200));ps.putFloat(20,rng.nextFloat()*200);
        byte[] area=new byte[trial%33];rng.nextBytes(area);
        operations.add(new Frame(new Snapshot(sequence,trial*50,trial%8,area,player,entities),from));
        if(trial%5==0)operations.add(new Command(commandSequence+trial%4+1,"post snapshot "+trial));
        var writer=new MessageWriter();ServerMessageCodec.write(writer,new Message(ack,operations),baseline);
        var reader=new MessageReader(writer.bytes());var decoded=ServerMessageCodec.read(reader,sequence,baseline,history::get);
        for(var op:decoded.operations()) {
          if(op instanceof GameState gs) {state=gs;baseline=gs.baselines();history.clear();latest=null;commandSequence=gs.commandSequence();}
          else if(op instanceof Command cmd) {if(cmd.sequence()>commandSequence){commandSequence=cmd.sequence();commands.put(cmd.sequence()&63,cmd.text());}commandCount++;}
          else if(op instanceof Frame frame) {latest=frame.current();lastSnapshotCommands=commandSequence;lastDelta=frame.previous()==null?-1:frame.previous().sequence();history.put(sequence,latest);while(history.size()>32)history.pollFirstEntry();}
        }
        input.write("parse "+sequence+" "+HEX.formatHex(writer.bytes())+"\n");input.flush();
        int size=1;for(String text:state.configstrings().values())size+=text.length()+1;
        require("message "+reader.bitPosition()+" "+ack+" "+commandSequence+" "+state.clientNumber()+" "+state.checksumFeed()+" "+size,output.readLine(),trial);
        for(var entry:new TreeMap<>(state.configstrings()).entrySet())require("string "+entry.getKey()+" "+hex(entry.getValue()),output.readLine(),trial);
        for(var e:baseline.entities())require("baseline "+ByteBuffer.wrap(e).order(ByteOrder.LITTLE_ENDIAN).getInt()+" "+HEX.formatHex(e),output.readLine(),trial);
        for(var entry:commands.entrySet())require("command "+entry.getKey()+" "+hex(entry.getValue()),output.readLine(),trial);
        require("snapshot 1 "+latest.sequence()+" "+lastDelta+" "+latest.time()+" "+latest.flags()+" "+lastSnapshotCommands+" "+latest.entities().size()+" "+HEX.formatHex(Arrays.copyOf(latest.areaMask(),32))+" "+HEX.formatHex(latest.player()),output.readLine(),trial);
        for(var e:latest.entities())require(HEX.formatHex(e),output.readLine(),trial);
        require("end",output.readLine(),trial);wireBytes+=writer.bytes().length;entityCount+=latest.entities().size();
        demoWriter.append(new DemoRecord(sequence,writer.bytes()));expectedFrames.add(latest);expectedLevels.add(state);
      }
    }finally{process.destroy();demoWriter.close();}
    System.out.printf("Server messages PASS: %,d complete payloads / %,d wire bytes accepted by native parser; %,d entities and %,d reliable commands match with gamestate/restart/baseline/snapshot history%n",trials,wireBytes,entityCount,commandCount);
    try(var demo=new Protocol68DemoReader(new ByteArrayInputStream(demoBytes.toByteArray()))) {
      for(int i=0;i<trials;i++) {
        var record=demo.next().orElseThrow();var frame=demo.latestSnapshot().orElseThrow();var expected=expectedFrames.get(i);
        if(record.sequence()!=100+i||frame.sequence()!=expected.sequence()||frame.time()!=expected.time()||frame.flags()!=expected.flags()||!Arrays.equals(frame.areaMask(),expected.areaMask())||!Arrays.equals(frame.player(),expected.player())||frame.entities().size()!=expected.entities().size())throw new AssertionError("Demo snapshot query="+i);
        for(int j=0;j<frame.entities().size();j++)if(!Arrays.equals(frame.entities().get(j),expected.entities().get(j)))throw new AssertionError("Demo entity query="+i+" entity="+j);
        if(!demo.gameState().orElseThrow().configstrings().equals(expectedLevels.get(i).configstrings())||demo.gameState().orElseThrow().checksumFeed()!=expectedLevels.get(i).checksumFeed())throw new AssertionError("Demo level query="+i);
      }
      if(demo.next().isPresent()||demo.end()!=DemoReader.End.MARKER)throw new AssertionError("Demo termination");
      System.out.printf("Protocol-68 demo stream PASS: %,d records / %,d bytes retain all native-checked snapshots and level resets%n",trials,demoBytes.size());
    }
  }
}
