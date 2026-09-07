import dev.bluevista.craftq3.assets.bsp.BspReader;
import dev.bluevista.craftq3.collision.*;
import dev.bluevista.craftq3.core.fs.*;
import dev.bluevista.craftq3.core.math.Vec3;
import dev.bluevista.craftq3.server.*;
import dev.bluevista.craftq3.vm.*;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.ZipFile;

/** Opt-in production Host/native replay of AAS update/null/frame lifetime operations. */
class AuditAasEntityLifetime {
  record Action(int kind, float value) {}
  record Observation(int result, int traces, byte[] prediction, byte[] info) {}
  static final int STATE=300, ORIGIN=500, VELOCITY=520, COMMAND=540, OUTPUT=1000, INFO=2000;
  static final HexFormat HEX=HexFormat.of();

  public static void main(String[] args) throws Exception {
    if(args.length!=2)throw new IllegalArgumentException("AuditAasEntityLifetime <pak0.pk3> <native-observer>");
    var actions=new ArrayList<Action>();
    var random=new Random(55350);float time=0,origin=1109;
    for(int i=0;i<10000;i++) {
      int kind=random.nextInt(7);
      if(kind<3||i==0){origin=kind==0?1109:kind==1?1110:origin;actions.add(new Action(0,origin));}
      else if(kind==3)actions.add(new Action(1,0));
      else{time+=new float[]{0,.05f,.1f,1,10}[random.nextInt(5)];actions.add(new Action(2,time));}
    }
    var text=new StringBuilder();
    for(var action:actions){
      switch(action.kind){
        case 0 -> text.append("entityhex 209 ").append(HEX.formatHex(state(action.value))).append('\n');
        case 1 -> text.append("unentity 209\n");
        case 2 -> text.append("frame ").append(action.value).append('\n');
        default -> throw new AssertionError();
      }
      text.append("predictlife\nentityinfo 209\n");
    }
    var nativeRows=nativeRun(args[1],args[0],text.toString());
    try(var zip=new ZipFile(args[0])) {
      VirtualFileSystem fs=new VirtualFileSystem(){
        public byte[] read(VirtualPath path)throws IOException {
          var entry=zip.getEntry(path.value());if(entry==null)throw new NoSuchFileException(path.value());
          if(entry.getSize()>128*1024*1024)throw new IOException("Oversize audit asset");
          try(var input=zip.getInputStream(entry)){return input.readAllBytes();}
        }
        public Optional<Origin> which(VirtualPath path){return zip.getEntry(path.value())==null?Optional.empty():Optional.of(new Origin("audit",args[0],true));}
        public List<VirtualPath> list(String directory){return List.of();}
        public List<Origin> searchOrder(){return List.of(new Origin("audit",args[0],true));}
        public void close(){}
      };
      var bsp=BspReader.read(fs.read(new VirtualPath("maps/q3dm1.bsp")));
      for(var abi:GameAbi.values()) {
        var host=new Host();var memory=memory();
        try(var bot=new BotlibHost(fs,"q3dm1",bsp,abi,host,ignored->{})) {
          call(bot,memory,200);memory.writeCString(100,"q3dm1",32);call(bot,memory,206,100);
          call(bot,memory,205,Float.floatToRawIntBits(0)); // Native AAS initialization has completed frame1.
          vector(memory,ORIGIN,1048.3479f,1059.18762f,48.25f);
          vector(memory,VELOCITY,0,0,0);vector(memory,COMMAND,0,0,0);
          for(int i=0;i<actions.size();i++) {
            var action=actions.get(i);
            switch(action.kind){
              case 0 -> {memory.writeBytes(STATE,state(action.value));call(bot,memory,207,209,STATE);}
              case 1 -> call(bot,memory,207,209,0);
              case 2 -> call(bot,memory,205,Float.floatToRawIntBits(action.value));
              default -> throw new AssertionError();
            }
            host.traces=0;memory.fill(OUTPUT,84,127);
            int result=call(bot,memory,318,OUTPUT,1,ORIGIN,2,0,VELOCITY,COMMAND,0,1,Float.floatToRawIntBits(.1f),0,0,0);
            call(bot,memory,303,209,INFO);
            var expected=nativeRows.get(i);
            if(result!=expected.result||host.traces!=expected.traces||!Arrays.equals(memory.readBytes(OUTPUT,84),expected.prediction)||!Arrays.equals(memory.readBytes(INFO,140),expected.info)) {
              System.out.println("Mismatch "+abi+" action="+i+" "+action+" result="+result+"/"+expected.result+" traces="+host.traces+"/"+expected.traces);
              printDiff("prediction",memory.readBytes(OUTPUT,84),expected.prediction);
              printDiff("entityInfo",memory.readBytes(INFO,140),expected.info);
              throw new AssertionError("Native Host lifetime mismatch");
            }
          }
          System.out.println(abi+": "+actions.size()+" production Host sequences exact; callbacks,84-byte prediction and140-byte entity info");
        }
      }
    }
  }
  static byte[] state(float origin) {
    var bytes=ByteBuffer.allocate(112).order(ByteOrder.LITTLE_ENDIAN);
    bytes.putInt(4,32);bytes.putFloat(8,origin);bytes.putFloat(12,1175);bytes.putFloat(16,16);
    bytes.putFloat(32,origin);bytes.putFloat(36,1175);bytes.putFloat(40,16);
    for(int axis=0;axis<3;axis++){bytes.putFloat(44+axis*4,-15);bytes.putFloat(56+axis*4,15);}
    bytes.putInt(72,2);bytes.putInt(76,12);return bytes.array();
  }
  static List<Observation> nativeRun(String binary,String pak,String commands)throws Exception {
    var process=new ProcessBuilder(binary,pak,"q3dm1").redirectError(ProcessBuilder.Redirect.DISCARD).start();
    try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
      var output=executor.submit(()->new String(process.getInputStream().readNBytes(32*1024*1024),StandardCharsets.US_ASCII));
      try(var input=process.getOutputStream()){input.write(commands.getBytes(StandardCharsets.US_ASCII));}
      if(!process.waitFor(60,TimeUnit.SECONDS))throw new AssertionError("Native timeout");
      if(process.exitValue()!=0)throw new AssertionError("Native exit "+process.exitValue());
      var rows=output.get().lines().filter(l->l.startsWith("PREDICT ")||l.startsWith("INFO ")).toList();
      if(rows.size()!=20000)throw new AssertionError("Native rows "+rows.size());
      var results=new ArrayList<Observation>();
      for(int i=0;i<rows.size();i+=2){var prediction=rows.get(i).split(" ");var info=rows.get(i+1).split(" ");results.add(new Observation(Integer.parseInt(prediction[1]),Integer.parseInt(prediction[2]),HEX.parseHex(prediction[3]),HEX.parseHex(info[info.length-1])));}
      return List.copyOf(results);
    } finally {if(process.isAlive())process.destroyForcibly();}
  }
  static int call(BotlibHost bot,QvmMemory memory,int call,int...args)throws IOException{return bot.invoke(memory,call,args);}
  static void vector(QvmMemory memory,int pointer,float x,float y,float z){memory.writeFloat(pointer,x);memory.writeFloat(pointer+4,y);memory.writeFloat(pointer+8,z);}
  static void printDiff(String label,byte[] actual,byte[] expected){for(int i=0;i<actual.length;i++)if(actual[i]!=expected[i])System.out.println(label+" byte"+i+" java="+(actual[i]&255)+" native="+(expected[i]&255));}
  static QvmMemory memory()throws Exception {
    var file=ByteBuffer.allocate(48).order(ByteOrder.LITTLE_ENDIAN);
    file.putInt(QvmReader.MAGIC).putInt(3).putInt(32).putInt(15).putInt(48).putInt(0).putInt(0).putInt(65536);
    file.put((byte)Opcode.ENTER.ordinal()).putInt(8).put((byte)Opcode.CONST.ordinal()).putInt(0).put((byte)Opcode.LEAVE.ordinal()).putInt(8);
    return new QvmInterpreter(QvmReader.read("entity-lifetime-fixture",file.array()),(m,c,a)->0).memory();
  }
  static class Host implements BotlibHost.Host {
    int traces;
    public int maxClients(){return 2;}
    public void clientCommand(int client,String command){throw new AssertionError("Unexpected game command");}
    public void userCommand(int client,UserCommand command){throw new AssertionError("Unexpected usercmd");}
    public int snapshotEntity(int client,int index){return -1;}
    public String consoleMessage(int client){return null;}
    public TraceResult trace(TraceRequest request){return TraceResult.clear(request);}
    public TraceResult entityTrace(int entity,TraceRequest request){if(entity!=209)throw new AssertionError("Entity "+entity);traces++;return TraceResult.clear(request);}
    public int pointContents(Vec3 point){return 0;}
  }
}
