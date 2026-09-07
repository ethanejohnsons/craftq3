package dev.bluevista.craftq3.fabric.render;

/** Mouse deltas are delivered before host player turning or GUI scaling. */
public interface QuakeInputView extends QuakeView {
  boolean cursorCaptured();

  void mouseDelta(double dx, double dy);

  void inputFocusLost();
}
