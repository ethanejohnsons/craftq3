/* Authored command/backend observer; unchanged snd_main.c is compiled separately.
 * Only public headers, callback signatures, and observed command dispatch are used. */
#include "client/snd_local.h"
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static int argumentCount;
static char **arguments;
static xcommand_t playCommand;
static cvar_t variables[64], empty;
static int variableCount, handle = 41;
cvar_t *com_minimized = &empty, *com_unfocused = &empty;

void Com_Printf(const char *format, ...) {
  va_list ap; va_start(ap, format); vfprintf(stderr, format, ap); va_end(ap);
}
void Com_Error(int code, const char *format, ...) {
  va_list ap; va_start(ap, format); vfprintf(stderr, format, ap); va_end(ap); exit(code + 1);
}
void Cmd_AddCommand(const char *name, xcommand_t callback) {
  printf("ADD %s\n", name);
  if (!strcmp(name, "play")) playCommand = callback;
}
void Cmd_RemoveCommand(const char *name) { printf("REMOVE %s\n", name); }
int Cmd_Argc(void) { return argumentCount; }
char *Cmd_Argv(int index) { return index >= 0 && index < argumentCount ? arguments[index] : ""; }
cvar_t *Cvar_Get(const char *name, const char *value, int flags) {
  for (int i = 0; i < variableCount; i++) if (!strcmp(variables[i].name, name)) return &variables[i];
  if (variableCount == 64) abort();
  cvar_t *result = &variables[variableCount++];
  result->name = strdup(name); result->string = strdup(value); result->integer = atoi(value);
  result->value = atof(value); result->flags = flags;
  return result;
}
void Cvar_Set(const char *name, const char *value) {
  cvar_t *variable = Cvar_Get(name, value, 0);
  free(variable->string); variable->string = strdup(value);
  variable->integer = atoi(value); variable->value = atof(value);
}
void S_CodecInit(void) {}
void S_CodecShutdown(void) {}
static void nothing(void) {}
static void local(sfxHandle_t sound, int channel) { printf("LOCAL %d %d\n", sound, channel); }
static sfxHandle_t registerSound(const char *name, qboolean compressed) {
  printf("REGISTER %zu %d %s\n", strlen(name), compressed, name);
  return handle;
}
static void start(vec3_t origin, int entity, int channel, sfxHandle_t sound) {}
static void background(const char *intro, const char *loop) {}
static void raw(int stream, int samples, int rate, int width, int channels, const byte *data, float gain, int entity) {}
static void clear(qboolean all) {}
static void loop(int entity, const vec3_t origin, const vec3_t velocity, sfxHandle_t sound) {}
static void stop(int entity) {}
static void spatial(int entity, const vec3_t origin, vec3_t axis[3], int inwater) {}
static void position(int entity, const vec3_t origin) {}
qboolean S_AL_Init(soundInterface_t *backend) { return qfalse; }
qboolean S_Base_Init(soundInterface_t *backend) {
  *backend = (soundInterface_t){
      .Shutdown = nothing, .StartSound = start, .StartLocalSound = local,
      .StartBackgroundTrack = background, .StopBackgroundTrack = nothing, .RawSamples = raw,
      .StopAllSounds = nothing, .ClearLoopingSounds = clear, .AddLoopingSound = loop,
      .AddRealLoopingSound = loop, .StopLoopingSound = stop, .Respatialize = spatial,
      .UpdateEntityPosition = position, .Update = nothing, .DisableSounds = nothing,
      .BeginRegistration = nothing, .RegisterSound = registerSound, .ClearSoundBuffer = nothing,
      .SoundInfo = nothing, .SoundList = nothing};
  return qtrue;
}
int main(int argc, char **argv) {
  argumentCount = argc; arguments = argv; arguments[0] = "play";
  if (getenv("CRAFTQ3_ORACLE_SOUND_HANDLE")) handle = atoi(getenv("CRAFTQ3_ORACLE_SOUND_HANDLE"));
  S_Init();
  if (!playCommand) return 2;
  puts("DISPATCH"); playCommand(); puts("DONE");
  S_Shutdown();
  return 0;
}
