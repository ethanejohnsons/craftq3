import dev.bluevista.craftq3.assets.fs.Pk3FileSystem;
import dev.bluevista.craftq3.assets.bsp.BspReader;
import dev.bluevista.craftq3.botlib.goal.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

/** Native map camp/location goal records and cursors with identical controlled area queries. */
class AuditMapGoals {
  public static void main(String[] args)throws Exception {
    Path install=Path.of("run/craftq3/games"),oracle=Path.of(".tools/item-oracle/map-goal-oracle");
    int maps=0,queries=0;
    try(var fs=Pk3FileSystem.mount(install,"baseq3")) {
      for(var path:fs.list("maps")) {
        if(!path.value().endsWith(".bsp")||!fs.which(path).orElseThrow().archive())continue;
        var entities=BspReader.read(fs.read(path)).entities();
        for(int area:new int[]{0,2}) {
          var goals=new BotMapGoals(p->area,s->{});goals.initialize(entities);
          var process=new ProcessBuilder(oracle.toAbsolutePath().toString(),install.resolve("baseq3/pak0.pk3").toAbsolutePath().toString(),Integer.toString(area)).redirectError(ProcessBuilder.Redirect.DISCARD).start();
          try(var input=process.outputWriter(StandardCharsets.US_ASCII);var output=process.inputReader(StandardCharsets.US_ASCII)) {
            input.write(entities.size()+"\n");
            for(var entity:entities) {
              input.write(entity.size()+"\n");for(var entry:entity.entrySet())input.write(hex(entry.getKey())+" "+hex(entry.getValue())+"\n");
            }
            for(int cursor=-2;cursor<=goals.camps().size()+2;cursor++) {
              var camp=goals.nextCamp(cursor);
              query(input,output,"camp "+cursor,camp.map(BotMapGoals.Camp::nextCursor).orElse(0),camp.map(BotMapGoals.Camp::goal),path.value());queries++;
            }
            var names=new LinkedHashSet<String>();for(var location:goals.locations()) {names.add(location.name());names.add(location.name().toUpperCase(Locale.ROOT));}
            names.add("");names.add("authored_missing_location");
            for(String name:names) {
              var result=goals.location(name);query(input,output,"location "+hex(name),result.isPresent()?1:0,result,path.value());queries++;
            }
          }
          if(process.waitFor()!=0)throw new AssertionError("Native map goal failure");
        }
        maps++;
      }
    }
    System.out.printf("Map goals PASS: %d original maps x2 area modes, %,d camp/location record and cursor checks; zero native mismatches with controlled point-area queries%n",maps,queries);
  }
  static void query(java.io.Writer input,java.io.BufferedReader output,String command,int result,Optional<Goal> goal,String map)throws Exception {
    var bytes=ByteBuffer.allocate(56);Arrays.fill(bytes.array(),(byte)0xab);goal.ifPresent(g->g.writeTo(bytes,0));
    input.write(command+"\n");input.flush();String actual=output.readLine();String expected="GOAL "+result+" "+HexFormat.of().formatHex(bytes.array());
    if(!expected.equals(actual))throw new AssertionError(map+" "+command+" expected "+expected+" actual "+actual);
  }
  static String hex(String text){return text.isEmpty()?"-":HexFormat.of().formatHex(text.getBytes(StandardCharsets.ISO_8859_1));}
}
