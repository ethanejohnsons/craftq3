/* Authored CPU initialization observer; stop at the requested native cvar
 * registration. */
#include "client/client.h"
#include <setjmp.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
static jmp_buf done;
static cvar_t running, dedicated, vars[512];
static int used;
cvar_t *com_cl_running = &running, *com_dedicated = &dedicated;
void QDECL Com_Printf(const char *format, ...) { (void)format; }
void QDECL Com_DPrintf(const char *format, ...) { (void)format; }
void QDECL Com_Error(int code, const char *format, ...) {
  va_list a;
  va_start(a, format);
  vfprintf(stderr, format, a);
  va_end(a);
  exit(2);
}
cvar_t *Cvar_Get(const char *name, const char *value, int flags) {
  printf("GET %s %s %d\n", name, value, flags);
  fflush(stdout);
  if (!strcmp(name, "cl_maxPing"))
    longjmp(done, 1);
  if (used >= 512)
    exit(3);
  cvar_t *v = &vars[used++];
  v->name = (char *)name;
  v->string = (char *)value;
  v->resetString = (char *)value;
  v->integer = atoi(value);
  v->value = atof(value);
  v->flags = flags;
  return v;
}
void Cmd_AddCommand(const char *name, xcommand_t function) {
  (void)function;
  printf("COMMAND %s\n", name);
}
void Cvar_Set(const char *name, const char *value) {
  (void)name;
  (void)value;
}
void Cvar_SetValue(const char *name, float value) {
  (void)name;
  (void)value;
}
void CL_InitInput(void) { puts("INPUT"); }
void Con_Init(void) { puts("CONSOLE"); }
void SCR_Init(void) { puts("SCREEN"); }
int main(void) {
  if (!setjmp(done))
    CL_Init();
  puts("END");
}
cvar_t *cl_anglespeedkey, *cl_pitchspeed, *cl_run, *cl_yawspeed;
qboolean com_fullyInitialized;
void CL_InitRef(void) { puts("RENDERER_SUPPRESSED"); }
void Cmd_SetCommandCompletionFunc(const char *command,
                                  completionFunc_t completion) {
  (void)command;
  (void)completion;
}
void Cvar_CheckRange(cvar_t *var, float min, float max, qboolean integral) {
  (void)var;
  (void)min;
  (void)max;
  (void)integral;
}
char *Com_MD5File(const char *file, int length, const char *prefix,
                  int prefixLength) {
  (void)file;
  (void)length;
  (void)prefix;
  (void)prefixLength;
  abort();
}
void Com_RandomBytes(byte *string, int len) {
  (void)string;
  (void)len;
  abort();
}
long FS_BaseDir_FOpenFileRead(const char *filename, fileHandle_t *file) {
  (void)filename;
  (void)file;
  abort();
}
fileHandle_t FS_BaseDir_FOpenFileWrite_HomeState(const char *filename) {
  (void)filename;
  abort();
}
void FS_FCloseFile(fileHandle_t file) {
  (void)file;
  abort();
}
int FS_Write(const void *buffer, int len, fileHandle_t file) {
  (void)buffer;
  (void)len;
  (void)file;
  abort();
}
