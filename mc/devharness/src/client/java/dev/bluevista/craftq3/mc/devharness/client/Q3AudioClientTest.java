package dev.bluevista.craftq3.mc.devharness.client;

import dev.bluevista.craftq3.engine.audio.Attenuation;
import dev.bluevista.craftq3.engine.audio.Listener;
import dev.bluevista.craftq3.engine.audio.PcmBuffer;
import dev.bluevista.craftq3.engine.audio.VoiceParams;
import dev.bluevista.craftq3.mc.fabric.client.CraftQ3ClientMod;
import dev.bluevista.craftq3.mc.fabric.client.audio.MinecraftAudio;
import dev.bluevista.craftq3.mc.fabric.client.audio.MinecraftAudioDevice;
import dev.bluevista.craftq3.mc.fabric.client.mixin.SoundEngineAccessor;
import dev.bluevista.craftq3.mc.fabric.client.mixin.SoundManagerAccessor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.sounds.SoundEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link MinecraftAudioDevice} on the real OpenAL device behind Minecraft's sound engine.
 *
 * <ol>
 *   <li>A Quake view claims audio: vanilla {@code play} starts nothing while the claim is held.
 *   <li>A one-shot voice, a looping spatialised voice and a mono stream play a synthetic 440 Hz
 *       tone. The one-shot ends by itself, the loop keeps playing, the stream's queued frames fall
 *       as it plays, it runs dry and resumes when more data arrives.
 *   <li>{@code SoundManager.stop()} (vanilla {@code stopAll}, which restarts the sound executor)
 *       ends the device's voices and releases their sources; the device keeps working afterwards.
 *   <li>Everything stops and the device closes: its source and buffer counts and the engine's pool
 *       usage return to the baseline, and every pooled stream chunk is back in the pool.
 * </ol>
 *
 * Every device call runs on the render thread, the sound system's owner in the client.
 */
public final class Q3AudioClientTest implements FabricClientGameTest {
  private static final int RATE = 22050;
  private static final Logger LOGGER = LoggerFactory.getLogger(Q3AudioClientTest.class);
  private static final Pattern POOL = Pattern.compile("Sounds: (\\d+)/(\\d+) \\+ (\\d+)/(\\d+)");

  @Override
  public void runTest(ClientGameTestContext context) {
    context.waitForScreen(TitleScreen.class);
    boolean loaded =
        context.computeOnClient(c -> ((SoundEngineAccessor) engine(c)).craftq3$loaded());
    if (!loaded)
      throw new AssertionError("Minecraft's sound engine did not start (no OpenAL device?)");
    // Let title-screen sounds settle so the pool baseline is stable.
    context.waitTicks(10);
    int[] baseline = context.computeOnClient(Q3AudioClientTest::poolUsage);

    MinecraftAudio audio = context.computeOnClient(c -> clientMod().audio());
    MinecraftAudioDevice device = context.computeOnClient(c -> audio.openDevice());
    MinecraftAudio.HostAudioClaim claim = context.computeOnClient(c -> audio.claimHostAudio());

    SoundEngine.PlayResult muted =
        context.computeOnClient(
            c ->
                c.getSoundManager()
                    .play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1f)));
    if (muted != SoundEngine.PlayResult.NOT_STARTED)
      throw new AssertionError("A vanilla sound started while Quake audio was claimed: " + muted);

    PcmBuffer shot = new PcmBuffer(RATE, 1, tone(RATE / 4)); // 250 ms
    PcmBuffer loop = new PcmBuffer(RATE, 1, tone(RATE / 10)); // 100 ms, whole periods
    int[] ids = new int[5];
    run(
        context,
        c -> {
          ids[0] = device.createBuffer(shot);
          ids[1] = device.createBuffer(loop);
          device.setListener(Listener.DEFAULT);
          VoiceParams local = new VoiceParams();
          ids[2] = device.startVoice(ids[0], false, 0, local);
          VoiceParams spatial = new VoiceParams();
          Attenuation.spatialise(Listener.DEFAULT, 200, 100, 0, 0.8f, spatial);
          ids[3] = device.startVoice(ids[1], true, 441, spatial);
          ids[4] = device.openStream(RATE, 1);
          short[] block = tone(RATE / 2); // 500 ms
          device.queueStream(ids[4], block, 0, block.length);
          device.commit();
        });
    for (int i = 0; i < ids.length; i++)
      if (ids[i] == 0) throw new AssertionError("Device returned no identifier for request " + i);
    int shotVoice = ids[2], loopVoice = ids[3], stream = ids[4];

    // Sound system cadence: one commit per tick.
    int firstQueued = tick(context, device, 2, () -> device.streamQueuedFrames(stream));
    expect(computeVoice(context, device, shotVoice), "the one-shot voice is not playing");
    expect(computeVoice(context, device, loopVoice), "the looping voice is not playing");
    expect(firstQueued > 0, "the stream has nothing queued right after queueing");
    expect(
        device.diagnostics().sources() == 3,
        "expected 3 sources (voice, loop, stream), got " + device.diagnostics());
    MinecraftAudioDevice.Diagnostics playing = device.diagnostics();
    int laterQueued = tick(context, device, 4, () -> device.streamQueuedFrames(stream));
    expect(
        laterQueued < firstQueued,
        "stream did not progress: " + firstQueued + " then " + laterQueued + " frames queued");

    // The one-shot (250 ms) has ended by itself by now; the loop goes on.
    tick(context, device, 4, () -> 0);
    expect(!computeVoice(context, device, shotVoice), "the one-shot voice never ended");
    expect(computeVoice(context, device, loopVoice), "the looping voice stopped by itself");
    run(context, c -> device.stopVoice(shotVoice));

    // The 500 ms stream runs dry, then resumes when more frames arrive.
    tick(context, device, 6, () -> 0);
    int dry = context.computeOnClient(c -> device.streamQueuedFrames(stream));
    expect(dry == 0, "the stream should have run dry, " + dry + " frames still queued");
    run(
        context,
        c -> {
          short[] block = tone(RATE); // 1 s
          device.queueStream(stream, block, 0, block.length);
          device.commit();
        });
    int resumed = tick(context, device, 2, () -> device.streamQueuedFrames(stream));
    expect(resumed > 0 && resumed < RATE, "the stream did not resume: " + resumed + " queued");

    // Vanilla stopAll restarts the executor: the device's voices end and their sources go back.
    run(context, c -> c.getSoundManager().stop());
    tick(context, device, 1, () -> 0);
    expect(!computeVoice(context, device, loopVoice), "the loop survived SoundManager.stop()");
    expect(
        device.diagnostics().sources() == 0,
        "sources still held after stopAll: " + device.diagnostics());
    run(context, c -> device.stopVoice(loopVoice));
    int again =
        context.computeOnClient(
            c -> {
              int voice = device.startVoice(ids[1], true, 0, new VoiceParams());
              device.commit();
              return voice;
            });
    tick(context, device, 2, () -> 0);
    expect(
        again != 0 && computeVoice(context, device, again),
        "no voice plays after SoundManager.stop()");

    // Stop everything and close.
    run(
        context,
        c -> {
          device.stopVoice(again);
          device.closeStream(stream);
          device.destroyBuffer(ids[0]);
          device.destroyBuffer(ids[1]);
          device.commit();
          device.close();
        });
    try {
      device.released().get(5, TimeUnit.SECONDS);
    } catch (Exception e) {
      throw new AssertionError("The device did not release its native resources", e);
    }
    MinecraftAudioDevice.Diagnostics after = device.diagnostics();
    expect(after.sources() == 0 && after.buffers() == 0, "native resources leaked: " + after);
    expect(
        after.pooledChunks() == after.allocatedChunks(),
        "stream chunks not returned to the pool: " + after);
    expect(
        context.computeOnClient(c -> audio.openDevices()) == 0,
        "the closed device is still registered");
    int[] end = context.computeOnClient(Q3AudioClientTest::poolUsage);
    expect(
        end[0] <= baseline[0] && end[1] <= baseline[1],
        "pool usage "
            + end[0]
            + "+"
            + end[1]
            + " did not return to baseline "
            + baseline[0]
            + "+"
            + baseline[1]);

    run(context, c -> claim.close());
    SoundEngine.PlayResult unmuted =
        context.computeOnClient(
            c ->
                c.getSoundManager()
                    .play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1f)));
    expect(
        unmuted != SoundEngine.PlayResult.NOT_STARTED,
        "vanilla sounds still suppressed after the claim was released");
    LOGGER.info(
        "Q3AudioClientTest passed: playing {}; stream queued {} -> {} frames, dry {}, resumed {};"
            + " closed {}; pool static+streaming {}+{} -> {}+{}",
        playing,
        firstQueued,
        laterQueued,
        dry,
        resumed,
        after,
        baseline[0],
        baseline[1],
        end[0],
        end[1]);
  }

  /** Commits once per tick for {@code ticks} ticks, then samples {@code probe} on the client. */
  private static int tick(
      ClientGameTestContext context, MinecraftAudioDevice device, int ticks, IntProbe probe) {
    for (int i = 0; i < ticks; i++) {
      context.waitTick();
      run(context, c -> device.commit());
    }
    // One more tick so the executor has drained the last commit.
    context.waitTick();
    return context.computeOnClient(c -> probe.sample());
  }

  private static boolean computeVoice(
      ClientGameTestContext context, MinecraftAudioDevice device, int voice) {
    return context.computeOnClient(c -> device.voicePlaying(voice));
  }

  private static void run(ClientGameTestContext context, Consumer<Minecraft> action) {
    context.runOnClient(action::accept);
  }

  private interface IntProbe {
    int sample();
  }

  private static void expect(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }

  /** A 440 Hz sine at a quarter of full scale. */
  private static short[] tone(int frames) {
    short[] samples = new short[frames];
    for (int i = 0; i < frames; i++)
      samples[i] = (short) (Math.sin(2 * Math.PI * 440 * i / RATE) * 8192);
    return samples;
  }

  private static SoundEngine engine(Minecraft client) {
    return ((SoundManagerAccessor) client.getSoundManager()).craftq3$soundEngine();
  }

  /** Used static and streaming sources in the engine's pools. */
  private static int[] poolUsage(Minecraft client) {
    String debug = client.getSoundManager().getChannelDebugString();
    Matcher m = POOL.matcher(debug);
    if (!m.find()) throw new AssertionError("Unrecognised channel debug string: " + debug);
    return new int[] {Integer.parseInt(m.group(1)), Integer.parseInt(m.group(3))};
  }

  private static CraftQ3ClientMod clientMod() {
    return FabricLoader.getInstance().getEntrypoints("client", ClientModInitializer.class).stream()
        .filter(CraftQ3ClientMod.class::isInstance)
        .map(CraftQ3ClientMod.class::cast)
        .findFirst()
        .orElseThrow(() -> new AssertionError("CraftQ3ClientMod entrypoint not found"));
  }
}
