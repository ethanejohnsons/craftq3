import dev.bluevista.craftq3.assets.audio.PcmSound;
import dev.bluevista.craftq3.assets.fs.Pk3FileSystem;
import dev.bluevista.craftq3.client.Q3Ui;
import dev.bluevista.craftq3.client.UiHost;
import dev.bluevista.craftq3.core.command.CommandSystem;
import dev.bluevista.craftq3.core.command.KeyBindings;
import dev.bluevista.craftq3.core.cvar.CvarSystem;
import dev.bluevista.craftq3.core.fs.VirtualFileSystem;
import dev.bluevista.craftq3.core.fs.VirtualPath;
import dev.bluevista.craftq3.core.math.Vec3;
import dev.bluevista.craftq3.platform.audio.AudioBackend;
import dev.bluevista.craftq3.render.CgameFrame;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Opt-in execution of user-supplied original menu code; no keys or game assets are embedded. */
class AuditUi {
  private record Result(int api, String digest, int models, int shaders, int sounds, long voices) {}
  public static void main(String[] args) throws Exception {
    if(args.length<1||args.length>2)throw new IllegalArgumentException("Usage: AuditUi <games directory> [ui.qvm]");
    Result first=run(args),second=run(args);
    if(!first.equals(second))throw new AssertionError("UI replay differs: "+first+" / "+second);
    System.out.println("Original UI initialization, prompt, main/setup/player menus, input and resize: PASS");
    System.out.println(first);
  }
  private static Result run(String[] args)throws Exception{
    var digest=MessageDigest.getInstance("SHA-256");
    try(var disk=Pk3FileSystem.mount(Path.of(args[0]),"baseq3")){
      VirtualFileSystem fs=new VirtualFileSystem(){
        public Optional<Origin> which(VirtualPath path){return disk.which(path);}
        public List<VirtualPath> list(String directory){return disk.list(directory);}
        public List<Origin> searchOrder(){return disk.searchOrder();}
        public byte[] read(VirtualPath path)throws IOException{
          return args.length>1&&path.value().equals("vm/ui.qvm")?Files.readAllBytes(Path.of(args[1])):disk.read(path);
        }
        public void close(){}
      };
      var cvars=new CvarSystem();cvars.register("sv_cheats","0",CvarSystem.ROM);
      cvars.register("name","UIAudit",CvarSystem.USERINFO);cvars.register("model","sarge/default",CvarSystem.USERINFO);
      cvars.register("headmodel","sarge/default",CvarSystem.USERINFO);
      var commands=new CommandSystem(cvars,fs,System.out::print);
      commands.unknownHandler(command->{throw new UnsupportedOperationException("Unexpected menu command: "+command.argument(0));});
      var bindings=new KeyBindings();bindings.bind('w',"+forward");bindings.bind(178,"+attack");
      var audio=new Audio();
      try(var ui=new Q3Ui(fs,cvars,commands,bindings,audio,frame->{},System.out::print,UiHost.disconnected())){
        try{
          ui.initialize(1280,720);ui.setMenu(Q3Ui.Menu.MAIN);
          hash(digest,ui.frame(1000,1280,720));
          // Native UI may present its empty-key prompt first. Escape and a new main-menu request
          // exercise ordinary disconnected-menu behavior; no key or checked cvar is fabricated.
          key(ui,27,1001);ui.setMenu(Q3Ui.Menu.MAIN);
          var main=ui.frame(1100,1280,720);hash(digest,main);
          if(main.commands().stream().noneMatch(CgameFrame.View.class::isInstance))throw new AssertionError("Missing original main-menu banner model");
          key(ui,133,1200);key(ui,133,1201);key(ui,13,1202);hash(digest,ui.frame(1250,1280,720));
          key(ui,13,1300);hash(digest,ui.frame(1400,1280,720));hash(digest,ui.frame(1550,1280,720));
          key(ui,27,1600);key(ui,27,1601);ui.mouse(60,20,1602);hash(digest,ui.frame(1650,1280,720));
          hash(digest,ui.frame(1700,1024,768));
          if(ui.registeredModels()<4||audio.voices==0)throw new AssertionError("Missing player model or navigation sound requests");
          return new Result(ui.apiVersion(),HexFormat.of().formatHex(digest.digest()),ui.registeredModels(),ui.registeredShaders(),ui.registeredSounds(),audio.voices);
        }catch(RuntimeException failure){System.err.println(ui.vmStats());System.err.println(ui.syscallCounts());throw failure;}
      }
    }
  }
  private static void key(Q3Ui ui,int key,int time){ui.key(key,true,time);ui.key(key,false,time);}
  private static void hash(MessageDigest digest,CgameFrame frame){
    if(frame.commands().isEmpty())throw new AssertionError("Original menu submitted no draw commands");
    for(var command:frame.commands()){
      if(command instanceof CgameFrame.Quad quad)text(digest,quad.toString());
      else if(command instanceof CgameFrame.View view){
        var ref=view.refdef();text(digest,ref.x()+":"+ref.y()+":"+ref.width()+":"+ref.height()+":"+ref.fovX()+":"+ref.fovY()
            +":"+ref.origin()+":"+ref.axisX()+":"+ref.axisY()+":"+ref.axisZ()+":"+ref.timeMillis()+":"+ref.rdflags());
        digest.update(ref.areaMask().copy());
        for(var entity:view.entities())text(digest,entity.type()+":"+(entity.model()==null?entity.inlineModel():entity.model().name())
            +":"+entity.transform()+":"+entity.frame()+":"+entity.oldFrame()+":"+entity.backlerp()+":"+entity.rgba());
      }
    }
  }
  private static void text(MessageDigest digest,String text){digest.update(text.getBytes(StandardCharsets.UTF_8));}
  private static final class Audio implements AudioBackend{
    int sounds;long voices;
    public int register(String name,PcmSound sound){return ++sounds;}
    public long play(Playback playback){return ++voices;}
    public void updateEntity(int entity,Vec3 origin){}
    public void beginFrame(){}
    public void submitLoop(Loop loop){}
    public void endFrame(Listener listener){}
    public void clearLoops(){}
    public void stop(long voice){}
    public void stopAll(){}
    public void volume(float gain){}
    public Diagnostics diagnostics(){return new Diagnostics(true,sounds,0,0,0,voices,0,"CPU audit");}
    public void close(){}
  }
}
