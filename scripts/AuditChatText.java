import dev.bluevista.craftq3.botlib.chat.BotChat;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

/** Deterministic native differential for every byte and whitespace-run normalization behavior. */
class AuditChatText {
  public static void main(String[] args)throws Exception {
    var process=new ProcessBuilder(Path.of(".tools/chat-oracle/reply-oracle").toAbsolutePath().toString(),Path.of("run/craftq3/games/baseq3/pak0.pk3").toAbsolutePath().toString()).redirectError(ProcessBuilder.Redirect.DISCARD).start();
    int checks=0;var random=new Random(0x73105);
    try(var input=process.outputWriter(StandardCharsets.US_ASCII);var output=process.inputReader(StandardCharsets.US_ASCII)) {
      if(!"READY".equals(output.readLine()))throw new AssertionError("Native setup failed");
      for(int trial=0;trial<100_255;trial++) {
        String text;
        if(trial<255)text="a"+(char)(trial+1)+"b";
        else {
          var generated=new StringBuilder();String alphabet="abc  !?\t\n;.,()";
          for(int i=0,length=random.nextInt(256);i<length;i++)generated.append(trial%2==0?alphabet.charAt(random.nextInt(alphabet.length())):(char)(1+random.nextInt(255)));
          text=generated.toString();
        }
        String hex=HexFormat.of().formatHex(text.getBytes(StandardCharsets.ISO_8859_1));input.write("unify "+(hex.isEmpty()?"-":hex)+"\n");input.flush();
        String actual=output.readLine(),expected="UNIFIED "+HexFormat.of().formatHex(BotChat.unifyWhiteSpaces(text).getBytes(StandardCharsets.ISO_8859_1));
        if(!expected.equals(actual))throw new AssertionError("Normalization mismatch at query "+trial+" input="+hex+" expected="+expected+" actual="+actual);
        checks++;
      }
    }finally {process.destroy();}
    System.out.printf("Chat text PASS: %,d requests; all nonzero byte values and 100,000 seeded strings match unchanged native normalization%n",checks);
  }
}
