import dev.bluevista.craftq3.assets.fs.Pk3FileSystem;
import dev.bluevista.craftq3.botlib.chat.*;
import dev.bluevista.craftq3.botlib.chat.ChatLibrary.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.random.RandomGenerator;

/** Compare generated requests over every original reply predicate with an unchanged native botlib. */
class AuditChatReplies {
  static final class Fixed implements RandomGenerator {
    final float value;final boolean sequence;int draws,state=0x13572468;
    Fixed(int bits){value=bits/32767.0f;sequence=bits<0;}
    public long nextLong(){throw new AssertionError("Unexpected random operation");}
    public float nextFloat(){if(sequence){draws++;state=state*1664525+1013904223;return ((state>>>17)%32767)/32767.0f;}return draws++==0?value:0;}
  }
  static String hex(String value){return value==null?"~":value.isEmpty()?"-":HexFormat.of().formatHex(value.getBytes(StandardCharsets.ISO_8859_1));}
  static String text(ReplyKey key,int variant) {
    if(key instanceof WordKey word)return word.text();
    if(key instanceof SpecialKey special)return special.name().equals("name")?"TestBot":"hello";
    StringBuilder value=new StringBuilder();
    for(var part:((PatternKey)key).parts()) {
      if(part instanceof Capture capture)value.append(variant%3==0?"":variant%3==1?"player"+capture.index():"two words");
      else {var alternatives=(Alternatives)part;value.append(alternatives.texts().get(variant%alternatives.texts().size()));}
    }
    return value.toString();
  }
  public static void main(String[] args)throws Exception {
    Path install=Path.of("run/craftq3/games"),oracle=Path.of(".tools/chat-oracle/reply-oracle");
    int checks=0,matches=0,totalDraws=0;
    var queries=new LinkedHashSet<String>();
    try(var fs=Pk3FileSystem.mount(install,"baseq3");var chat=new BotChat(fs,()->0,new Fixed(0))) {
      chat.setup();
      for(var rule:chat.library().replies())for(int variant=0;variant<6;variant++) {
        var combined=new StringBuilder();
        for(var condition:rule.conditions()) {
          String candidate=text(condition.key(),variant);
          for(String modified:List.of(candidate,candidate.toUpperCase(Locale.ROOT),"prefix "+candidate,candidate+" suffix"," "+candidate))
            if(modified.length()<=120)queries.add(modified);
          if(condition.constraint()!=Constraint.FORBIDDEN)combined.append(candidate).append(' ');
        }
        if(combined.length()<=120)queries.add(combined.toString().trim());
      }
    }
    boolean contexts=args.length>0 && args[0].equals("contexts");
    boolean sequence=contexts || args.length>0 && args[0].equals("sequence");
    for(int bits:sequence?new int[]{-1}:new int[]{0,16384,29490}) {
      var rng=new Fixed(bits);float[] clock={0};
      try(var fs=Pk3FileSystem.mount(install,"baseq3");var chat=new BotChat(fs,()->clock[0],rng)) {
        chat.setup();int first=chat.allocate(),other=chat.allocate();chat.setName(first,"TestBot",3);chat.setName(other,"OtherBot",4);
        var process=new ProcessBuilder(oracle.toAbsolutePath().toString(),install.resolve("baseq3/pak0.pk3").toAbsolutePath().toString()).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try(var input=process.outputWriter(StandardCharsets.US_ASCII);var output=process.inputReader(StandardCharsets.US_ASCII)) {
          if(!"READY".equals(output.readLine()))throw new AssertionError("Native setup failed");
          input.write(sequence?"rng 324478056\n":"seed "+bits+"\n");input.flush();if(!(sequence?"RNG":"SEED").equals(output.readLine()))throw new AssertionError();
          input.write("tail 0\n");input.flush();if(!"TAIL".equals(output.readLine()))throw new AssertionError();
          int queryIndex=0;
          for(String query:queries) {
            int state=contexts && queryIndex%2==1?other:first;
            int messageContext=contexts?new int[]{0,1,2,4,8,16,-1}[queryIndex%7]:0;
            int variableContext=contexts?new int[]{0,1,2,-1}[queryIndex/7%4]:0;
            if(contexts) {
              clock[0]=queryIndex*.25f;input.write("time "+clock[0]+"\n");input.flush();if(!"TIME".equals(output.readLine()))throw new AssertionError();
              input.write("state "+(state==other?1:0)+"\n");input.flush();if(!"STATE".equals(output.readLine()))throw new AssertionError();
            }
            int gender=queryIndex%3;chat.setGender(state,gender);
            input.write("gender "+gender+"\n");input.flush();if(!"GENDER".equals(output.readLine()))throw new AssertionError();
            List<String> variables=queryIndex%4==0?Arrays.asList("Override0",null,"~Caller2"):List.of();
            rng.draws=0;boolean found=chat.reply(state,query,messageContext,variableContext,variables);int length=chat.length(state);String message=chat.takeMessage(state);
            input.write("reply "+messageContext+" "+variableContext+" "+hex(query));for(int i=0;i<8;i++)input.write(" "+hex(i<variables.size()?variables.get(i):null));input.write("\n");input.flush();
            String actual=output.readLine(),actualText=output.readLine();String expected="REPLY "+(found?1:0)+" LENGTH "+length+" DRAWS "+rng.draws;
            String expectedText="TEXT "+(message.isEmpty()?"":hex(message));
            if(!expected.equals(actual)||!expectedText.equals(actualText)) {
              System.err.println("QUERY "+queryIndex+" bits="+bits+" gender="+gender+" contexts="+messageContext+","+variableContext+" time="+clock[0]+" state="+state+" text="+query+" vars="+variables);
              System.err.println("JAVA "+expected+" "+message);
              System.err.println("NATIVE "+actual+" "+(actualText==null?"terminated":new String(HexFormat.of().parseHex(actualText.substring(5)),StandardCharsets.ISO_8859_1)));
              throw new AssertionError("Reply mismatch");
            }
            checks++;queryIndex++;if(found)matches++;totalDraws+=rng.draws;
          }
        }finally{process.destroy();}
      }
    }
    System.out.printf("Chat replies PASS: %,d requests, %,d replies, %,d draws; booleans, lengths and messages exact with %s%n",checks,matches,totalDraws,contexts?"varying RNG, synonym contexts, clocks and two bot states":sequence?"an authored deterministic varying RNG stream":"three first-draw RNG samples (subsequent draws zero)");
  }
}
