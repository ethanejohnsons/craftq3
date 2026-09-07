/* Authored codec callback observer; links unchanged snd_codec.c and q_shared.c. */
#include "client/snd_codec.h"
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>

void Com_Printf(const char *format, ...) { va_list ap; va_start(ap, format); vfprintf(stderr, format, ap); va_end(ap); }
void Com_DPrintf(const char *format, ...) { va_list ap; va_start(ap, format); vfprintf(stderr, format, ap); va_end(ap); }
void Com_Error(int code, const char *format, ...) { va_list ap; va_start(ap, format); vfprintf(stderr, format, ap); va_end(ap); exit(code + 1); }
void *Z_MallocDebug(int size, char *label, char *file, int line) { return calloc(1, size); }
void Z_Free(void *pointer) { free(pointer); }
long FS_FOpenFileRead(const char *path, fileHandle_t *file, qboolean unique) { *file = 0; return -1; }
void FS_FCloseFile(fileHandle_t file) {}
static void *load(const char *path, snd_info_t *info) { printf("WAV %s\n", path); return (void *)1; }
snd_codec_t wav_codec = {.ext = "wav", .load = load};
int main(int argc, char **argv) {
  S_CodecInit();
  for (int i = 1; i < argc; i++) { snd_info_t info = {0}; printf("INPUT %s\n", argv[i]); S_CodecLoad(argv[i], &info); }
  S_CodecShutdown();
  return 0;
}
