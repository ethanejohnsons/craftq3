import dev.bluevista.craftq3.assets.audio.PcmSound;
import dev.bluevista.craftq3.assets.fs.Pk3FileSystem;
import dev.bluevista.craftq3.client.Q3Ui;
import dev.bluevista.craftq3.core.cvar.CvarSystem;
import dev.bluevista.craftq3.core.math.Vec3;
import dev.bluevista.craftq3.fabric.game.QuakeSession;
import dev.bluevista.craftq3.platform.audio.*;
import dev.bluevista.craftq3.render.CgameFrame;
import java.nio.file.*;
import java.util.*;

/** Real original UI command routing plus system movie lifecycle in the production application. */
class AuditCinematicApplication {
  static final class Audio implements AudioBackend {
    int sounds; long voices; boolean closed;
    Map<Long,PcmStream> streams = new HashMap<>();
    public int register(String name,PcmSound sound){return ++sounds;}
    public long play(Playback playback){return ++voices;}
    public long stream(PcmStream stream,float gain){streams.put(++voices,stream);return voices;}
    public void updateEntity(int entity,Vec3 origin){}
    public void beginFrame(){}
    public void submitLoop(Loop loop){}
    public void endFrame(Listener listener){}
    public void clearLoops(){}
    public void stop(long voice){var stream=streams.remove(voice);if(stream!=null)stream.close();}
    public void stopAll(){for(long voice:List.copyOf(streams.keySet()))stop(voice);}
    public void volume(float value){}
    public Diagnostics diagnostics(){return new Diagnostics(true,sounds,streams.size(),0,0,voices,0,"");}
    public void close(){stopAll();closed=true;}
  }
  public static void main(String[] args)throws Exception {
    var audio=new Audio();
    try(var fs=Pk3FileSystem.mount(Path.of(args[1]),"baseq3");
        var session=new QuakeSession(fs,null,audio,null,null,"Cinematic QA",640,480)) {
      session.cvars().set("bot_enable","0",CvarSystem.Source.ENGINE);
      session.command("map q3dm17");frame(session);
      var original=session.server();
      session.command("cinematic nonexistent-craftq3");frame(session);
      check(session.server()==original&&session.playing(),"Missing movie destroyed match");
      session.command("set nextmap \"set craftq3_movie_end yes\"; cinematic demoend.roq");
      var opening=frame(session);
      check(session.cinematicPlaying()&&!session.playing()&&!session.menuVisible(),"Movie did not take screen");
      check(original.state()==dev.bluevista.craftq3.server.Q3Server.State.CLOSED,"Old match survived cinematic");
      check(opening.commands().getFirst() instanceof CgameFrame.Image,"No movie image");
      var resized=session.advance(16,1280,960);
      check(((CgameFrame.Image)resized.commands().getFirst()).width()==1280,"Movie did not resize");
      for(int i=0;i<80&&session.cinematicPlaying();i++)session.advance(200,640,480);
      frame(session);
      check(!session.cinematicPlaying()&&session.menuVisible(),"Movie EOF did not restore UI");
      check(session.cvars().string("craftq3_movie_end").equals("yes")&&session.cvars().string("nextmap").isEmpty(),"Lost/repeated nextmap");
      session.command("cinematic demoend 1");frame(session);
      for(int i=0;i<80;i++)session.advance(200,640,480);
      check(session.cinematicPlaying()&&session.cinematicInfo().orElseThrow().frame()==325,"Hold did not retain last frame");
      check(session.cinematicKey(133,true)&&session.cinematicKey(27,false),"Movie did not consume input");
      frame(session);check(session.cinematicPlaying(),"Non-exit input stopped movie");
      session.cinematicKey(27,true);frame(session);
      check(!session.cinematicPlaying()&&session.menuVisible(),"Skip did not restore UI");
      session.command("cinematic demoend 2");frame(session);
      for(int i=0;i<80;i++)session.advance(200,640,480);
      check(session.cinematicInfo().orElseThrow().cycle()>=1,"Movie failed to loop");
      session.command("cinematic missing-craftq3");frame(session);
      check(session.cinematicPlaying(),"Failed replacement destroyed movie");
      session.command("disconnect");frame(session);
      check(!session.cinematicPlaying()&&session.menuVisible(),"Disconnect retained cinematic");
      key(session,27);session.ui().setMenu(Q3Ui.Menu.MAIN);frame(session);
      for(int i=0;i<4;i++)key(session,133);
      key(session,13);
      var menu=frame(session);
      var play=menu.commands().stream().filter(CgameFrame.Quad.class::isInstance)
          .map(CgameFrame.Quad.class::cast).filter(q->q.shader().contains("play_")).findFirst();
      System.out.println("CINEMATICS MENU "+menu.commands().stream().filter(CgameFrame.Quad.class::isInstance).map(CgameFrame.Quad.class::cast).map(CgameFrame.Quad::shader).distinct().toList());
      if(play.isPresent()) {
        var q=play.orElseThrow();session.menuMouse(-10000,-10000);
        session.menuMouse(q.x()+q.width()/2,q.y()+q.height()/2);key(session,178);
      } else key(session,13);
      frame(session);
      check(session.cinematicPlaying(),"Original movie menu did not launch a cinematic: "+session.consoleLines());
      String menuMovie=session.cvars().string("cl_cinematic");
      session.cinematicKey(32,true);frame(session);
      check(!session.cinematicPlaying()&&session.menuVisible()&&audio.streams.isEmpty(),"Menu movie skip leaked audio");
      session.command("set nextmap \"map q3dm17\"; cinematic demoend");frame(session);
      for(int i=0;i<80&&session.cinematicPlaying();i++)session.advance(200,640,480);
      frame(session);check(session.playing(),"Nextmap did not enter original gameplay");
      session.command("cinematic demoend 2");frame(session);
      check(session.cinematicPlaying(),"Final close probe missing movie");
      System.out.println("PASS uiApi="+session.ui().apiVersion()+" menuMovie="+menuMovie+" eof=true hold=true loop=true skip=true nextmap=true resize=true failed-replacement-preserved=true");
    }
    check(audio.closed&&audio.streams.isEmpty(),"Session close leaked streams");
  }
  static CgameFrame frame(QuakeSession s){return s.advance(16,640,480);}
  static void key(QuakeSession s,int key){s.menuKey(key,true);s.menuKey(key,false);frame(s);}
  static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
}
