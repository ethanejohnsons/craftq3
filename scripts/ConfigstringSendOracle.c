/* Authored output observer: include unchanged source only to call its static
 * helper. */
#include "server/sv_init.c"
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
server_t sv;
serverStatic_t svs;
void QDECL SV_SendServerCommand(client_t *client, const char *format, ...) {
  (void)client;
  char text[32768];
  va_list args;
  va_start(args, format);
  vsnprintf(text, sizeof(text), format, args);
  va_end(args);
  printf("COMMAND %zu ", strlen(text));
  for (unsigned char *p = (unsigned char *)text; *p; p++)
    printf("%02x", *p);
  puts("");
}
void QDECL Com_Error(int code, const char *format, ...) {
  (void)code;
  (void)format;
  abort();
}
void QDECL Com_Printf(const char *format, ...) { (void)format; }
void QDECL Com_DPrintf(const char *format, ...) { (void)format; }
int main(void) {
  int index;
  char hex[32768], value[16384];
  client_t client = {0};
  while (scanf("%d %32767s", &index, hex) == 2) {
    if (index < 0 || index >= MAX_CONFIGSTRINGS)
      return 2;
    size_t length = !strcmp(hex, "-") ? 0 : strlen(hex) / 2;
    if (length >= sizeof(value))
      return 2;
    for (size_t i = 0; i < length; i++) {
      unsigned b;
      if (sscanf(hex + 2 * i, "%2x", &b) != 1)
        return 2;
      value[i] = (char)b;
    }
    value[length] = 0;
    sv.configstrings[index] = value;
    SV_SendConfigstring(&client, index);
    puts("END");
    fflush(stdout);
  }
}
