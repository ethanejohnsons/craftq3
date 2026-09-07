import dev.bluevista.craftq3.core.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

/** Native MSG string differential at every alignment with all byte values and capacity edges. */
class AuditMessageStrings {
  public static void main(String[] args)throws Exception {
    var process=new ProcessBuilder(Path.of(".tools/netchan-oracle/string-oracle").toAbsolutePath().toString()).redirectError(ProcessBuilder.Redirect.INHERIT).start();
    int reads=0,writes=0;long wireBytes=0;var random=new Random(0x68_5712);
    try(var input=process.outputWriter(StandardCharsets.US_ASCII);var output=process.inputReader(StandardCharsets.US_ASCII)) {
      for(int trial=0;trial<20_064;trial++) {
        int kind=trial%3,prefix=trial%8;String value;
        if(trial<256)value="a"+(char)trial+"b";
        else if(trial>=20_000) {
          int[] sizes={1022,1023,1024,1025,8190,8191,8192,8193};value="a".repeat(sizes[trial%8]);
        } else {
          var text=new StringBuilder();for(int i=0,length=random.nextInt(320);i<length;i++)text.append((char)random.nextInt(256));value=text.toString();
        }
        String hex=HexFormat.of().formatHex(value.getBytes(StandardCharsets.ISO_8859_1));if(hex.isEmpty())hex="-";
        if(kind<2) {
          var writer=new MessageWriter();if(prefix!=0)writer.bits((1<<prefix)-1,prefix);
          if(kind==1)writer.bigStringValue(value);else writer.stringValue(value);
          input.write("write "+kind+" "+prefix+" "+hex+"\n");input.flush();
          String expected="WIRE "+writer.bitPosition()+" "+writer.bytes().length+" "+HexFormat.of().formatHex(writer.bytes()),actual=output.readLine();
          if(!expected.equals(actual))throw new AssertionError("Write mismatch at "+trial+" expected="+expected+" actual="+actual);
          writes++;wireBytes+=writer.bytes().length;
        }
        var writer=new MessageWriter();if(prefix!=0)writer.bits((1<<prefix)-1,prefix);
        for(char c:value.toCharArray())writer.byteValue(c);writer.byteValue(0);
        var reader=new MessageReader(writer.bytes());if(prefix!=0)reader.bits(prefix);
        String result=kind==2?reader.stringLine():kind==1?reader.bigStringValue():reader.stringValue();
        String resultHex=HexFormat.of().formatHex(result.getBytes(StandardCharsets.ISO_8859_1));if(resultHex.isEmpty())resultHex="-";
        input.write("read "+kind+" "+prefix+" "+hex+"\n");input.flush();
        String expected="TEXT "+reader.bitPosition()+" "+resultHex,actual=output.readLine();
        if(!expected.equals(actual))throw new AssertionError("Read mismatch at "+trial+" expected="+expected+" actual="+actual);
        reads++;
      }
    }finally{process.destroy();}
    System.out.printf("MSG strings PASS: %,d writes / %,d exact wire bytes; %,d reads with exact text and bit cursors%n",writes,wireBytes,reads);
  }
}
