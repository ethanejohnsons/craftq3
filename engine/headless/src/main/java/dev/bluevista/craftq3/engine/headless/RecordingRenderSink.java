package dev.bluevista.craftq3.engine.headless;

import dev.bluevista.craftq3.engine.client.RenderSink;
import dev.bluevista.craftq3.engine.render.frame.CgameFrame;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * The headless "renderer": counts every submitted frame, its 2D quads, 3D views and cinematic
 * images, and keeps the newest {@code keep} frames for inspection.
 */
public final class RecordingRenderSink implements RenderSink {
  private final int keep;
  private final ArrayDeque<CgameFrame> recent = new ArrayDeque<>();
  private long frames, quads, views, images, emptyFrames;

  /** Keeps the newest {@code keep} frames (0 keeps none). */
  public RecordingRenderSink(int keep) {
    if (keep < 0) throw new IllegalArgumentException("keep must be >= 0");
    this.keep = keep;
  }

  @Override
  public void submit(CgameFrame frame) {
    frames++;
    if (frame.commands().isEmpty()) emptyFrames++;
    for (CgameFrame.Command command : frame.commands()) {
      if (command instanceof CgameFrame.Quad) quads++;
      else if (command instanceof CgameFrame.View) views++;
      else if (command instanceof CgameFrame.Image) images++;
    }
    if (keep == 0) return;
    recent.addLast(frame);
    while (recent.size() > keep) recent.removeFirst();
  }

  public long frames() {
    return frames;
  }

  public long quads() {
    return quads;
  }

  /** 3D scenes rendered ({@code trap_R_RenderScene}). */
  public long views() {
    return views;
  }

  /** Cinematic frames drawn. */
  public long images() {
    return images;
  }

  public long emptyFrames() {
    return emptyFrames;
  }

  /** The kept frames, oldest first. */
  public List<CgameFrame> recent() {
    return new ArrayList<>(recent);
  }

  /** Forgets the counters and kept frames. */
  public void reset() {
    frames = quads = views = images = emptyFrames = 0;
    recent.clear();
  }
}
