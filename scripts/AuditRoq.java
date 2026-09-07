import dev.bluevista.craftq3.assets.video.RoqDecoder;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.zip.ZipFile;

/** Reads original media directly from the user-owned PK3; emits only decoded hashes. */
class AuditRoq {
 public static void main(String[] args)throws Exception {
  try(var zip=new ZipFile(args[0]);var decoder=new RoqDecoder(zip.getInputStream(zip.getEntry(args[1])))) {
   var audio=MessageDigest.getInstance("SHA-256");int frames=0,blocks=0;long samples=0;
   for(var next=decoder.next();next.isPresent();next=decoder.next()) {
    if(next.get() instanceof RoqDecoder.Video video) {
     System.out.println("V "+video.number()+" "+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(video.yuv())));frames++;
    } else if(next.get() instanceof RoqDecoder.Audio sound) {
     if(sound.firstSample()!=samples)throw new AssertionError("Discontinuous audio");audio.update(sound.sound().pcm());samples+=sound.sound().frames();blocks++;
    }
   }
   System.out.println("A "+HexFormat.of().formatHex(audio.digest()));System.out.println("PASS frames="+frames+" audioBlocks="+blocks+" samples="+samples+" dimensions="+decoder.width()+"x"+decoder.height()+" rate="+decoder.frameRate());
  }
 }
}
