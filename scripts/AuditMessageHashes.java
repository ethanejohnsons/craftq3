import dev.bluevista.craftq3.core.net.MessageHash;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

/** Public MSG_HashKey comparisons over byte alphabets, bounded inputs and overflowing sums. */
class AuditMessageHashes {
  public static void main(String[]args)throws Exception {
    var process=new ProcessBuilder(Path.of(".tools/netchan-oracle/hash-oracle").toAbsolutePath().toString()).redirectError(ProcessBuilder.Redirect.INHERIT).start();
    var rng=new Random(0x68322526);int checked=0;
    try(var input=process.outputWriter(StandardCharsets.US_ASCII);var output=process.inputReader(StandardCharsets.US_ASCII)) {
      for(int trial=0;trial<10_256;trial++) {
        byte[] bytes;
        if(trial<256)bytes=new byte[]{(byte)trial};
        else {bytes=new byte[trial%100==0?16384:rng.nextInt(1025)];rng.nextBytes(bytes);if(trial%5==0)Arrays.fill(bytes,(byte)127);}
        int max=switch(trial%4){case 0->32;case 1->bytes.length;case 2->0;default->rng.nextInt(16385);};
        if(trial>=256&&trial%100==0)max=16384;
        input.write(max+" "+(bytes.length==0?"-":HexFormat.of().formatHex(bytes))+"\n");input.flush();
        String expected=Integer.toUnsignedString(MessageHash.key(new String(bytes,StandardCharsets.ISO_8859_1),max)),actual=output.readLine();
        if(!expected.equals(actual))throw new AssertionError("Hash query="+trial+" max="+max+" expected="+expected+" actual="+actual);
        checked++;
      }
    }finally{process.destroy();}
    System.out.printf("Message hashes PASS: %,d exact results, including all byte values, NUL/capacity boundaries and wrapping weighted sums%n",checked);
  }
}
