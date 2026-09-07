package dev.bluevista.craftq3.assets.shader;

/** Recoverable script warning. Lines are one-based; zero denotes the script collection. */
public record ShaderDiagnostic(String source, int line, String message) {}
