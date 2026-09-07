import dev.bluevista.craftq3.core.net.*;
import dev.bluevista.craftq3.core.net.ServerMessageCodec.Download;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Compares streaming transfer operations with unchanged native server writer/client parser. */
class AuditDownloadProtocol {
 static int comparisons;
 static class Oracle implements AutoCloseable {
  final Process process;final BufferedReader input;final BufferedWriter output;
  Oracle(Path path)throws IOException{process=new ProcessBuilder(path.toString()).redirectError(ProcessBuilder.Redirect.INHERIT).start();input=process.inputReader();output=process.outputWriter();}
  List<String> call(String command)throws IOException {
   output.write(command);output.newLine();output.flush();var lines=new ArrayList<String>();
   for(String line;(line=input.readLine())!=null;){if(line.equals("END"))return lines;lines.add(line);}
   throw new IOException("Native observer ended during "+command);
  }
  public void close()throws IOException{try{output.close();}finally{try{input.close();}finally{process.destroy();}}}
 }
 public static void main(String[] args)throws Exception {
  Path root=Path.of(args[0]);
  try(var server=new Oracle(root.resolve(".tools/download-server-oracle/probe"));var client=new Oracle(root.resolve(".tools/download-client-oracle/probe"))) {
   for(int size:new int[]{1,1023,1024,1025,2500,48*1024,48*1024+7}) {
    byte[] source=pattern(size);server.call("seed "+size+" 1 3");server.call("download baseq3/test.pk3");client.call("seed 0 0 0 1 1");
    var sink=new ByteArrayOutputStream();
    try(var sender=new DownloadSender(new ByteArrayInputStream(source),size);var receiver=new DownloadReceiver(sink)) {
     int expected=0;
     while(!sender.complete()) {
      var packet=sender.next(1000+expected).orElseThrow();
      compareServer(server.call("write "+(1000+expected)),packet,expected);
      compareClient(client,packet,expected);
      var progress=receiver.accept(packet);check(progress.acknowledge()==expected,"Java receiver sequence");
      server.call("nextdl "+expected);sender.acknowledge(expected,1000+expected);expected++;
     }
     check(Arrays.equals(source,sink.toByteArray()),"Stream bytes changed");
     var done=server.call("donedl");check(done.stream().anyMatch(x->x.startsWith("STATE ")&&x.endsWith(" 1")),"Native done did not request gamestate");
    }
   }
   server.call("seed 50000 1 3");server.call("download baseq3/test.pk3");
   try(var sender=new DownloadSender(new ByteArrayInputStream(pattern(50000)),50000)) {
    for(int i=0;i<48;i++)compareServer(server.call("write 1000"),sender.next(1000).orElseThrow(),i);
    for(int now:new int[]{1001,1999,2000}){check(sender.next(now).isEmpty(),"Premature resend");check(server.call("write "+now).stream().anyMatch(x->x.startsWith("MESSAGE 0 0")),"Native resend boundary");comparisons++;}
    compareServer(server.call("write 2001"),sender.next(2001).orElseThrow(),0);
    server.call("stopdl");
   }
   client.call("seed 65535 67108867 67107840 1 1");
   compareClient(client,new Download(65535,null,pattern(1024),null),65535);
   compareClient(client,new Download(0,null,pattern(3),null),65536);
   compareClient(client,new Download(1,null,new byte[0],null),65537);
   client.call("seed 0 0 0 1 1");compareClient(client,new Download(0,0,new byte[0],null),0);
   var error=new Download(0,-1,new byte[0],"Not allowed");client.call("seed 0 0 0 1 1");
   var writer=new MessageWriter();DownloadMessageCodec.write(writer,error);
   check(client.call("packet "+HexFormat.of().formatHex(writer.bytes())).stream().anyMatch(x->x.equals("ERROR 1 Not allowed")),"Native error operation");comparisons++;
  }
  System.out.println("PASS "+comparisons+" native download writer/parser comparisons; window48, timeout, EOF, errors and 16-bit wrap");
 }
 static void compareServer(List<String> lines,Download packet,int expected) {
  String[] message=lines.stream().filter(x->x.startsWith("MESSAGE ")).findFirst().orElseThrow().split(" ");
  check(message[1].equals("1"),"Native writer omitted block");int bits=Integer.parseInt(message[2]);byte[] bytes=HexFormat.of().parseHex(message[3]);
  var writer=new MessageWriter();writer.byteValue(6);DownloadMessageCodec.write(writer,packet);
  check(bits==writer.bitPosition(),"Native writer bit count");byte[] java=writer.bytes();
  for(int i=0;i<bits;i++)check(((bytes[i>>3]>>(i&7))&1)==((java[i>>3]>>(i&7))&1),"Native writer bits differ");
  var reader=new MessageReader(bytes,bits);check(reader.byteValue()==6,"Native operation code");var parsed=DownloadMessageCodec.read(reader,expected);
  check(parsed.block()==packet.block()&&Objects.equals(parsed.fileSize(),packet.fileSize())&&Arrays.equals(parsed.data(),packet.data()),"Native download decode");comparisons++;
 }
 static void compareClient(Oracle client,Download packet,int expected)throws IOException {
  var writer=new MessageWriter();DownloadMessageCodec.write(writer,packet);var lines=client.call("packet "+HexFormat.of().formatHex(writer.bytes()));
  check(lines.contains("COMMAND 0 nextdl "+expected),"Native acknowledgement: "+lines);
  check(lines.contains("CURSOR "+writer.bitPosition()),"Native cursor differs");
  if(packet.data().length>0)check(lines.contains("WRITE "+packet.data().length+" "+HexFormat.of().formatHex(packet.data())),"Native write differs");
  else check(lines.stream().anyMatch(x->x.startsWith("RENAME ")),"Native EOF did not finalize");comparisons++;
 }
 static byte[] pattern(int count){byte[] bytes=new byte[count];for(int i=0;i<count;i++)bytes[i]=(byte)i;return bytes;}
 static void check(boolean value,String text){if(!value)throw new AssertionError(text);}
}
