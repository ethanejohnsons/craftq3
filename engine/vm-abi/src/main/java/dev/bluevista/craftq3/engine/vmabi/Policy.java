package dev.bluevista.craftq3.engine.vmabi;

import java.util.Objects;

/**
 * How the dispatcher treats one import. Every import enum entry declares exactly one policy.
 *
 * <ul>
 *   <li>{@link Kind#IMPLEMENTED_BY_HOST}: the VM host must bind a handler; {@link VmHost} refuses
 *       to construct without one.
 *   <li>{@link Kind#SHARED}: implemented in this module by the {@code engine.vmabi.service}
 *       installers; it must be bound too, usually by installing the shared services.
 *   <li>{@link Kind#STUB}: needs no handler and returns {@link #value()}; the {@link #reason()}
 *       documents why that is the correct neutral result. A host may still bind a handler.
 *   <li>{@link Kind#FATAL}: calling it faults the VM.
 * </ul>
 *
 * When a handler throws, a {@link #degradable()} import returns {@link #value()} (after clearing
 * its declared output buffer) and the degradation is logged once and counted; otherwise the failure
 * faults the VM.
 */
public final class Policy {
  public enum Kind {
    IMPLEMENTED_BY_HOST,
    SHARED,
    STUB,
    FATAL
  }

  /** No output buffer is cleared on degradation. */
  public static final int NO_OUTPUT = -1;

  /** {@link #outputBytes()} value meaning: write a single NUL at the output address. */
  public static final int STRING_OUTPUT = -1;

  /** A host handler returning 0 on degradation. */
  public static final Policy HOST = new Policy(Kind.IMPLEMENTED_BY_HOST, 0, "", true, -1, 0);

  /** A shared service returning 0 on degradation. */
  public static final Policy SHARED = new Policy(Kind.SHARED, 0, "", true, -1, 0);

  private final Kind kind;
  private final int value;
  private final String reason;
  private final boolean degradable;
  private final int outputArg;
  private final int outputBytes;

  private Policy(
      Kind kind, int value, String reason, boolean degradable, int outputArg, int outputBytes) {
    this.kind = kind;
    this.value = value;
    this.reason = Objects.requireNonNull(reason);
    this.degradable = degradable;
    this.outputArg = outputArg;
    this.outputBytes = outputBytes;
  }

  /** A host handler that returns {@code neutral} when it fails. */
  public static Policy host(int neutral) {
    return new Policy(Kind.IMPLEMENTED_BY_HOST, neutral, "", true, -1, 0);
  }

  /** A host handler whose failure cannot be hidden (the VM faults), e.g. LocateGameData. */
  public static Policy hostCritical(String reason) {
    return new Policy(Kind.IMPLEMENTED_BY_HOST, 0, reason, false, -1, 0);
  }

  /** A shared service that returns {@code neutral} when it fails. */
  public static Policy shared(int neutral) {
    return new Policy(Kind.SHARED, neutral, "", true, -1, 0);
  }

  /** A shared service whose failure faults the VM. */
  public static Policy sharedCritical(String reason) {
    return new Policy(Kind.SHARED, 0, reason, false, -1, 0);
  }

  /** A documented neutral result that needs no handler. */
  public static Policy stub(int value, String reason) {
    if (reason == null || reason.isBlank())
      throw new IllegalArgumentException("A stub must document its reason");
    return new Policy(Kind.STUB, value, reason, true, -1, 0);
  }

  /** An import that faults the VM whenever it is called. */
  public static Policy fatal(String reason) {
    if (reason == null || reason.isBlank())
      throw new IllegalArgumentException("A fatal import must document its reason");
    return new Policy(Kind.FATAL, 0, reason, false, -1, 0);
  }

  /**
   * On degradation also zero {@code bytes} bytes at the address in argument {@code arg}, or write
   * one NUL there when {@code bytes} is {@link #STRING_OUTPUT}, so the guest never reads
   * uninitialized output.
   */
  public Policy clearing(int arg, int bytes) {
    if (arg < 0 || arg >= 16 || bytes < STRING_OUTPUT || bytes == 0)
      throw new IllegalArgumentException("Invalid degrade output " + arg + "/" + bytes);
    return new Policy(kind, value, reason, degradable, arg, bytes);
  }

  /** Clears a NUL-terminated output buffer in argument {@code arg} on degradation. */
  public Policy clearingString(int arg) {
    return clearing(arg, STRING_OUTPUT);
  }

  public Kind kind() {
    return kind;
  }

  /** The stub result, or the neutral result returned on degradation. */
  public int value() {
    return value;
  }

  /** Why a stub or fatal import behaves as it does, or why a critical one cannot degrade. */
  public String reason() {
    return reason;
  }

  public boolean degradable() {
    return degradable;
  }

  /** Whether a handler must be bound before a {@link VmHost} can be built. */
  public boolean requiresHandler() {
    return kind == Kind.IMPLEMENTED_BY_HOST || kind == Kind.SHARED;
  }

  /** The argument holding a degrade output address, or {@link #NO_OUTPUT}. */
  public int outputArg() {
    return outputArg;
  }

  /** Bytes cleared at {@link #outputArg()}, or {@link #STRING_OUTPUT}. */
  public int outputBytes() {
    return outputBytes;
  }

  /** A one-line description for generated documentation. */
  public String describe() {
    StringBuilder text = new StringBuilder();
    switch (kind) {
      case IMPLEMENTED_BY_HOST, SHARED -> {
        if (!degradable) text.append("critical: ").append(reason);
        else {
          text.append("degrades to ").append(value);
          if (outputArg >= 0)
            text.append(
                outputBytes == STRING_OUTPUT
                    ? ", empty string at arg " + outputArg
                    : ", zeroed " + outputBytes + " bytes at arg " + outputArg);
        }
      }
      case STUB -> text.append("returns ").append(value).append(": ").append(reason);
      case FATAL -> text.append(reason);
    }
    return text.toString();
  }

  @Override
  public String toString() {
    return kind + "(" + describe() + ")";
  }
}
