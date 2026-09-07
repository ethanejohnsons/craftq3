import dev.bluevista.craftq3.core.net.*;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;

/** Bounded authored messages passed to the unchanged native server message dispatcher. */
class AuditServerSessionPolicy {
  static final List<String> transcript=new ArrayList<>();
  static Process process; static BufferedReader reader; static BufferedWriter writer;
  static int cases;
  public static void main(String[] args)throws Exception {
    process=new ProcessBuilder(".tools/server-session-oracle/probe").redirectError(ProcessBuilder.Redirect.INHERIT).start();
    reader=process.inputReader();writer=process.outputWriter();
    try {
      for(int ack:new int[]{-1,0,1,2,9,10,100})run("messageAck "+ack,100,4,64,5,1000,0,0,0,100,ack,64,List.of(),true,1100);
      for(int ack:new int[]{-1,0,1,63,64,65,100})run("reliableAck "+ack,100,4,64,5,1000,0,0,0,100,9,ack,List.of(),true,1100);
      for(int id:new int[]{0,89,90,99,100,101})run("serverId "+id,100,4,64,5,1000,0,0,0,id,9,64,List.of(new ClientMessageCodec.Command(6,"say hi")),true,1100);
      for(int state:new int[]{1,2,3,4})run("state "+state,100,state,64,5,1000,0,0,0,100,9,64,List.of(new ClientMessageCodec.Command(6,"say hi")),true,1100,1200);
      for(int seq:new int[]{3,5,6,7})run("command "+seq,100,4,64,5,1000,0,0,0,100,9,64,List.of(new ClientMessageCodec.Command(seq,"say hi")),true,1100);
      run("command gap after valid",100,4,64,5,1000,0,0,0,100,9,64,List.of(new ClientMessageCodec.Command(6,"say first"),new ClientMessageCodec.Command(8,"say gap")),true,1100);
      for(int[] times:List.of(new int[]{900,1000,1100},new int[]{1200,1100},new int[]{1100,1050,1200},new int[]{1100,1100},new int[]{999999}))
        run("times "+Arrays.toString(times),100,4,64,5,1000,0,0,0,100,9,64,List.of(),true,times);
      run("no delta",100,4,64,5,1000,0,0,0,100,9,64,List.of(),false,1100);
      for(int got:new int[]{0,1})for(int auth:new int[]{0,1})for(int state:new int[]{3,4})
        run("pure "+got+" "+auth+" state"+state,100,state,64,5,1000,1,got,auth,100,9,64,List.of(),true,1100);
      Files.write(Path.of(".tools/server-session-oracle/policy.log"),transcript);
      System.out.println("Observed "+cases+" native policy cases");
    }finally{writer.close();process.waitFor();reader.close();}
  }
  static void run(String name,int server,int state,int rel,int last,int lastTime,int pure,int got,int auth,
                  int id,int ack,int rack,List<ClientMessageCodec.Command> commands,boolean delta,int... times)throws Exception {
    String seed="seed "+server+" 90 "+state+" "+rel+" 10 2 "+last+" "+lastTime+" "+pure+" "+got+" "+auth;
    writer.write(seed+"\n");writer.flush();if(!"SEEDED".equals(reader.readLine()))throw new IOException("Seed failed");
    var movement=times.length==0?null:new ClientMessageCodec.Movement(delta,Arrays.stream(times).mapToObj(time->{byte[] b=new byte[24];ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).putInt(time);return b;}).toList());
    var encoded=new MessageWriter();ClientMessageCodec.write(encoded,new ClientMessageCodec.Message(id,ack,rack,commands,movement),12345,"key"+(rack&63));
    writer.write("packet "+HexFormat.of().formatHex(encoded.bytes())+"\n");writer.flush();
    transcript.add("CASE "+name);for(String line;(line=reader.readLine())!=null;){transcript.add(line);if(line.startsWith("STATE "))break;}
    cases++;
  }
}
