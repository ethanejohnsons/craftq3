package dev.bluevista.craftq3.engine.client.host;

import static dev.bluevista.craftq3.engine.console.CvarFlags.ARCHIVE;
import static dev.bluevista.craftq3.engine.console.CvarFlags.CHEAT;
import static dev.bluevista.craftq3.engine.console.CvarFlags.LATCH;
import static dev.bluevista.craftq3.engine.console.CvarFlags.ROM;
import static dev.bluevista.craftq3.engine.console.CvarFlags.SERVERINFO;
import static dev.bluevista.craftq3.engine.console.CvarFlags.TEMP;
import static dev.bluevista.craftq3.engine.console.CvarFlags.USERINFO;

import dev.bluevista.craftq3.engine.console.CvarTable;

/**
 * The cvars the original client, renderer and sound system create at startup with their default
 * values and flags. Menus and HUDs read many of them ({@code r_mode}, {@code model}, {@code
 * cl_maxpackets}..); creating them keeps the guest's view of a fresh installation.
 */
public final class EngineCvars {
  private EngineCvars() {}

  public static void register(CvarTable cvars) {
    // Player and connection settings (CL_Init).
    cvars.register("name", "UnnamedPlayer", USERINFO | ARCHIVE);
    cvars.register("rate", "3000", USERINFO | ARCHIVE);
    cvars.register("snaps", "20", USERINFO | ARCHIVE);
    cvars.register("model", "sarge", USERINFO | ARCHIVE);
    cvars.register("headmodel", "sarge", USERINFO | ARCHIVE);
    cvars.register("team_model", "james", USERINFO | ARCHIVE);
    cvars.register("team_headmodel", "*james", USERINFO | ARCHIVE);
    cvars.register("g_redTeam", "Stroggs", SERVERINFO | ARCHIVE);
    cvars.register("g_blueTeam", "Pagans", SERVERINFO | ARCHIVE);
    cvars.register("color1", "4", USERINFO | ARCHIVE);
    cvars.register("color2", "5", USERINFO | ARCHIVE);
    cvars.register("handicap", "100", USERINFO | ARCHIVE);
    cvars.register("teamtask", "0", USERINFO);
    cvars.register("sex", "male", USERINFO | ARCHIVE);
    cvars.register("cl_anonymous", "0", USERINFO | ARCHIVE);
    cvars.register("password", "", USERINFO);
    cvars.register("cg_predictItems", "1", USERINFO | ARCHIVE);
    cvars.register("cl_maxpackets", "30", ARCHIVE);
    cvars.register("cl_packetdup", "1", ARCHIVE);
    cvars.register("cl_timeNudge", "0", TEMP);
    cvars.register("cl_timeout", "200", 0);
    cvars.register("cl_shownet", "0", TEMP);
    cvars.register("cl_showTimeDelta", "0", TEMP);
    cvars.register("cl_freezeDemo", "0", TEMP);
    cvars.register("cl_avidemo", "0", 0);
    cvars.register("cl_forceavidemo", "0", 0);
    cvars.register("cl_allowDownload", "0", ARCHIVE);
    cvars.register("cl_inGameVideo", "1", ARCHIVE);
    cvars.register("cl_serverStatusResendTime", "750", 0);
    cvars.register("cl_maxPing", "800", ARCHIVE);
    cvars.register("cl_motd", "1", 0);
    cvars.register("cl_conXOffset", "0", 0);
    cvars.register("cl_running", "1", ROM);
    cvars.register("cl_paused", "0", ROM);
    cvars.register("activeAction", "", TEMP);
    cvars.register("timedemo", "0", 0);
    cvars.register("timescale", "1", CHEAT);
    cvars.register("fixedtime", "0", CHEAT);
    cvars.register("com_cameraMode", "0", CHEAT);
    cvars.register("com_maxfps", "85", ARCHIVE);
    cvars.register("com_introplayed", "0", ARCHIVE);
    cvars.register("dedicated", "0", LATCH);
    cvars.register("protocol", "68", ROM);
    cvars.register("cl_downloadName", "", ROM);
    cvars.register("cl_downloadSize", "0", ROM);
    cvars.register("cl_downloadCount", "0", ROM);
    cvars.register("cl_downloadTime", "0", ROM);
    // Renderer (R_Register).
    cvars.register("r_glDriver", "opengl32", ARCHIVE | LATCH);
    cvars.register("r_allowExtensions", "1", ARCHIVE | LATCH);
    cvars.register("r_ext_compressed_textures", "0", ARCHIVE | LATCH);
    cvars.register("r_ext_gamma_control", "1", ARCHIVE | LATCH);
    cvars.register("r_ext_multitexture", "1", ARCHIVE | LATCH);
    cvars.register("r_ext_compiled_vertex_array", "1", ARCHIVE | LATCH);
    cvars.register("r_ext_texture_env_add", "1", ARCHIVE | LATCH);
    cvars.register("r_picmip", "1", ARCHIVE | LATCH);
    cvars.register("r_roundImagesDown", "1", ARCHIVE | LATCH);
    cvars.register("r_colorMipLevels", "0", LATCH);
    cvars.register("r_detailtextures", "1", ARCHIVE | LATCH);
    cvars.register("r_texturebits", "0", ARCHIVE | LATCH);
    cvars.register("r_colorbits", "0", ARCHIVE | LATCH);
    cvars.register("r_stencilbits", "8", ARCHIVE | LATCH);
    cvars.register("r_depthbits", "0", ARCHIVE | LATCH);
    cvars.register("r_overBrightBits", "1", ARCHIVE | LATCH);
    cvars.register("r_ignorehwgamma", "0", ARCHIVE | LATCH);
    cvars.register("r_mode", "3", ARCHIVE | LATCH);
    cvars.register("r_fullscreen", "1", ARCHIVE | LATCH);
    cvars.register("r_customwidth", "1600", ARCHIVE | LATCH);
    cvars.register("r_customheight", "1024", ARCHIVE | LATCH);
    cvars.register("r_customaspect", "1", ARCHIVE | LATCH);
    cvars.register("r_simpleMipMaps", "1", ARCHIVE | LATCH);
    cvars.register("r_vertexLight", "0", ARCHIVE | LATCH);
    cvars.register("r_uiFullScreen", "0", 0);
    cvars.register("r_subdivisions", "4", ARCHIVE | LATCH);
    cvars.register("r_displayRefresh", "0", LATCH);
    cvars.register("r_fastsky", "0", ARCHIVE);
    cvars.register("r_inGameVideo", "1", ARCHIVE);
    cvars.register("r_drawSun", "0", ARCHIVE);
    cvars.register("r_dynamiclight", "1", ARCHIVE);
    cvars.register("r_dlightBacks", "1", ARCHIVE);
    cvars.register("r_finish", "0", ARCHIVE);
    cvars.register("r_textureMode", "GL_LINEAR_MIPMAP_NEAREST", ARCHIVE);
    cvars.register("r_swapInterval", "0", ARCHIVE);
    cvars.register("r_gamma", "1", ARCHIVE);
    cvars.register("r_facePlaneCull", "1", ARCHIVE);
    cvars.register("r_railWidth", "16", ARCHIVE);
    cvars.register("r_railCoreWidth", "6", ARCHIVE);
    cvars.register("r_railSegmentLength", "32", ARCHIVE);
    cvars.register("r_primitives", "0", ARCHIVE);
    cvars.register("r_ambientScale", "0.6", CHEAT);
    cvars.register("r_directedScale", "1", CHEAT);
    cvars.register("r_lodBias", "0", ARCHIVE);
    cvars.register("r_flares", "0", ARCHIVE);
    cvars.register("r_znear", "4", CHEAT);
    cvars.register("r_zproj", "64", ARCHIVE);
    cvars.register("r_stereoSeparation", "64", ARCHIVE);
    cvars.register("r_ignoreGLErrors", "1", ARCHIVE);
    cvars.register("r_fastsky", "0", ARCHIVE);
    cvars.register("r_lodCurveError", "250", ARCHIVE | CHEAT);
    cvars.register("r_intensity", "1", LATCH);
    cvars.register("r_singleShader", "0", CHEAT | LATCH);
    cvars.register("r_mapOverBrightBits", "2", LATCH);
    cvars.register("r_fullbright", "0", LATCH | CHEAT);
    cvars.register("r_smp", "0", ARCHIVE | LATCH);
    cvars.register("r_stereo", "0", ARCHIVE | LATCH);
    // Sound (S_Init).
    cvars.register("s_volume", "0.8", ARCHIVE);
    cvars.register("s_musicvolume", "0.25", ARCHIVE);
    cvars.register("s_separation", "0.5", ARCHIVE);
    cvars.register("s_doppler", "1", ARCHIVE);
    cvars.register("s_khz", "22", ARCHIVE);
    cvars.register("s_mixahead", "0.2", ARCHIVE);
    cvars.register("s_mixPreStep", "0.05", ARCHIVE);
    cvars.register("s_initsound", "1", 0);
    // Input.
    cvars.register("in_mouse", "1", ARCHIVE | LATCH);
    cvars.register("in_joystick", "0", ARCHIVE | LATCH);
    cvars.register("joy_threshold", "0.15", ARCHIVE);
  }
}
