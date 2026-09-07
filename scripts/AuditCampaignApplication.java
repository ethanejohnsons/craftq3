import dev.bluevista.craftq3.assets.audio.PcmSound;
import dev.bluevista.craftq3.assets.fs.Pk3FileSystem;
import dev.bluevista.craftq3.client.Q3Ui;
import dev.bluevista.craftq3.collision.BspTraceWorld;
import dev.bluevista.craftq3.collision.TraceRequest;
import dev.bluevista.craftq3.core.cvar.CvarSystem;
import dev.bluevista.craftq3.core.fs.GameFileStore;
import dev.bluevista.craftq3.core.math.Vec3;
import dev.bluevista.craftq3.fabric.game.QuakeSession;
import dev.bluevista.craftq3.platform.audio.*;
import dev.bluevista.craftq3.render.CgameFrame;
import java.nio.*;
import java.nio.file.*;
import java.util.*;

/** Earned campaign transition through the production application, never injected scores/progress. */
class AuditCampaignApplication {
  static final class Audio implements AudioBackend {
    int sounds; long voices, samples;
    final Map<Long,PcmStream> streams=new HashMap<>();
    final Set<Long> started=new HashSet<>();
    public int register(String name,PcmSound sound){return ++sounds;}
    public long play(Playback playback){return ++voices;}
    public long stream(PcmStream stream,float gain){streams.put(++voices,stream);return voices;}
    public void updateEntity(int entity,Vec3 origin){}
    public void beginFrame(){}
    public void submitLoop(Loop loop){}
    public void endFrame(Listener listener){
      for(var entry:List.copyOf(streams.entrySet())) {
        var stream=entry.getValue();
        if(stream.ready()&&started.add(entry.getKey()))stream.started(System.nanoTime());
        try {for(int i=0;i<64&&stream.ready();i++)samples+=stream.read(65536).length/stream.format().frameBytes();}
        catch(java.io.IOException failure){throw new java.io.UncheckedIOException(failure);}
        if(stream.exhausted())stop(entry.getKey());
      }
    }
    public void clearLoops(){}
    public void stop(long voice){var stream=streams.remove(voice);if(stream!=null)stream.close();started.remove(voice);}
    public void stopAll(){for(long id:List.copyOf(streams.keySet()))stop(id);}
    public void volume(float gain){}
    public Diagnostics diagnostics(){return new Diagnostics(true,sounds,streams.size(),0,0,voices,0,"CPU campaign");}
    public void close(){stopAll();}
  }

  public static void main(String[] args)throws Exception {
    Path games=Path.of(args[1]), out=Path.of(args[2]);Files.createDirectories(out);
    Path home=Files.createTempDirectory(out,"earned-");
    Map<String,String> earned;
    Set<String> movies=new LinkedHashSet<>();
    var audio=new Audio();
    try(var fs=Pk3FileSystem.mount(games,"baseq3");var saved=new GameFileStore(home);
        var session=new QuakeSession(fs,saved,audio,null,null,"CampaignAudit",640,480)) {
      session.advance(50,640,480);key(session,27);session.ui().setMenu(Q3Ui.Menu.MAIN);session.advance(50,640,480);
      key(session,13);var arena=session.advance(50,640,480);
      requireShader(arena,"levelshots/q3dm0");click(session,arena,"menu/art/fight_0");
      key(session,13);
      System.setProperty("craftq3.lifecycleCapture","true");
      try {session.advance(50,640,480);}finally{System.clearProperty("craftq3.lifecycleCapture");}
      check(session.playing()&&session.world().mapName().equals("maps/q3dm0.bsp"),"Original menu did not start tutorial");
      check(session.cvars().integer("fraglimit")==5&&session.cvars().integer("g_spSkill")==2&&session.cvars().integer("bot_enable")==1,"Original campaign defaults changed");
      var actor=new Actor(new BspTraceWorld(session.world().bsp()));
      long deadline=System.nanoTime()+120_000_000_000L;
      int elapsed=0, postgame=-1, score=-1, movieFrames=0, lastMovieFrame=-1;boolean clickedNext=false, selectedNextArena=false;
      while(System.nanoTime()<deadline&&elapsed<600000) {
        if(session.playing()&&session.world().mapName().equals("maps/q3dm1.bsp"))break;
        if(session.cinematicPlaying()) {
          if(movies.add(session.cvars().string("cl_cinematic")))System.out.println("MOVIE "+session.cvars().string("cl_cinematic")+" nextmap="+session.cvars().string("nextmap"));movieFrames++;
          lastMovieFrame=Math.max(lastMovieFrame,session.cinematicInfo().orElseThrow().frame());
          Thread.sleep(16);
        } else if(session.playing()) {
          int current=player(session,0).getInt(248);
          if(current!=score){score=current;System.out.println("SCORE time="+session.time()+" human="+score+" crash="+player(session,1).getInt(248));}
          if(!session.menuVisible())actor.input(session);
          else session.input().releaseAll(session.inputTime());
        }
        var frame=session.advance(50,640,480);elapsed+=50;
        var menu=session.uiFrame()!=null?session.uiFrame():!session.playing()?frame:null;
        if(menu!=null&&!session.playing()&&!session.cinematicPlaying()&&!movies.isEmpty()
            &&hasShader(menu,"menu/art/fight_0")) {
          requireShader(menu,"levelshots/q3dm1");
          click(session,menu,"menu/art/fight_0");key(session,13);selectedNextArena=true;
          System.out.println("ORIGINAL_NEXT_ARENA q3dm1");
        }
        if(menu!=null&&hasShader(menu,"menu/art/next_0")) {
          if(postgame<0){postgame=elapsed;System.out.println("POSTGAME "+progress(session.cvars()));}
          if(elapsed-postgame>15000&&!clickedNext) {
            check(score==5,"Next available without original five-frag win");
            click(session,menu,"menu/art/next_0");clickedNext=true;
          }
        }
      }
      check(score==5&&(clickedNext||selectedNextArena)&&session.playing()&&session.world().mapName().equals("maps/q3dm1.bsp"),
          "Campaign did not reach q3dm1: movies="+movies+" console="+session.consoleLines());
      earned=progress(session.cvars());
      check(!earned.getOrDefault("g_spScores2","").isEmpty(),"Original progress missing");
      check(movies.contains("video/tier1.roq")&&movieFrames>0&&lastMovieFrame==365&&audio.samples==273792,"Earned movie was incomplete: "+movies+" frame="+lastMovieFrame+" samples="+audio.samples);
      for(int i=0;i<100;i++)session.advance(50,640,480);
      check(session.server().isBot(1),"Next campaign match has no original bot");
      System.out.println("EARNED "+earned);
      System.out.println("PASS campaign uiApi="+session.ui().apiVersion()+" score=5 movies="+movies+" presentations="+movieFrames+" lastMovieFrame="+lastMovieFrame+" samples="+audio.samples+" next=q3dm1 home="+home);
    }
    try(var fs=Pk3FileSystem.mount(games,"baseq3");var saved=new GameFileStore(home);
        var session=new QuakeSession(fs,saved,new Audio(),null,null,"CampaignAudit",640,480)) {
      check(earned.equals(progress(session.cvars())),"Earned campaign state did not survive application close/reload");
      session.advance(50,640,480);key(session,27);session.ui().setMenu(Q3Ui.Menu.MAIN);session.advance(50,640,480);key(session,13);
      requireShader(session.advance(50,640,480),"levelshots/q3dm1");
      System.out.println("PASS campaign-reload=true original-level-menu=true");
    }
  }

  static final class Actor {
    final BspTraceWorld geometry;int waypoint, selected=-1;
    final double[][] route={{-1000,-1230},{-1000,-1460},{-1152,-1640},{-1152,-1850}};
    Actor(BspTraceWorld geometry){this.geometry=geometry;}
    void input(QuakeSession session) {
      var human=player(session,0);double pitch=human.getFloat(152),yaw=human.getFloat(156);
      boolean forward=false,fire=false;
      if(waypoint<route.length) {
        double dx=route[waypoint][0]-human.getFloat(20),dy=route[waypoint][1]-human.getFloat(24);
        if(Math.hypot(dx,dy)<20||human.getFloat(20)>-500)waypoint++;
        else {yaw=Math.toDegrees(Math.atan2(dy,dx));forward=true;}
      } else if(session.server().isBot(1)&&human.getInt(4)!=5) {
        var crash=player(session,1);
        if(human.getInt(184)<=0)fire=true;
        else if(crash.getInt(184)>0) {
          Vec3 start=position(human).add(new Vec3(0,0,26)),target=position(crash).add(new Vec3(0,0,12));
          var d=new Vec3(target.x()-start.x(),target.y()-start.y(),target.z()-start.z());yaw=Math.toDegrees(Math.atan2(d.y(),d.x()));
          pitch=-Math.toDegrees(Math.atan2(d.z(),Math.hypot(d.x(),d.y())));
          fire=geometry.trace(TraceRequest.ray(start,target,1)).fraction()==1;
        }
      }
      int weapon=human.getInt(388)>0?3:human.getInt(384)>0?2:1;
      if(weapon!=selected){session.command("weapon "+weapon);selected=weapon;}
      session.input().angles(pitch-human.getInt(56)*(360.0/65536),yaw-human.getInt(60)*(360.0/65536),human.getFloat(160)-human.getInt(64)*(360.0/65536));
      session.input().key('w',forward,session.inputTime());session.input().key(178,fire,session.inputTime());
    }
  }
  static Map<String,String> progress(CvarSystem cvars){var result=new TreeMap<String,String>();for(var v:cvars.all())if(v.name().startsWith("g_spScores")||v.name().equals("g_spAwards")||v.name().equals("g_spVideos"))result.put(v.name(),v.value());return Map.copyOf(result);}
  static ByteBuffer player(QuakeSession s,int slot){return ByteBuffer.wrap(s.server().playerState(slot)).order(ByteOrder.LITTLE_ENDIAN);}
  static Vec3 position(ByteBuffer p){return new Vec3(p.getFloat(20),p.getFloat(24),p.getFloat(28));}
  static void key(QuakeSession s,int key){s.menuKey(key,true);s.menuKey(key,false);}
  static boolean hasShader(CgameFrame f,String shader){return f.commands().stream().anyMatch(c->c instanceof CgameFrame.Quad q&&q.shader().equals(shader));}
  static void requireShader(CgameFrame f,String shader){check(hasShader(f,shader),"Missing menu shader "+shader+" in "+f.commands().stream().filter(CgameFrame.Quad.class::isInstance).map(CgameFrame.Quad.class::cast).map(CgameFrame.Quad::shader).distinct().toList());}
  static void click(QuakeSession s,CgameFrame f,String shader){var q=f.commands().stream().filter(c->c instanceof CgameFrame.Quad quad&&quad.shader().equals(shader)).map(CgameFrame.Quad.class::cast).findFirst().orElseThrow();s.menuMouse(-10000,-10000);s.menuMouse(q.x()+q.width()/2,q.y()+q.height()/2);key(s,178);}
  static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
}
