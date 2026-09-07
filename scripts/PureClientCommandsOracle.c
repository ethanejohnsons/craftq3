/* Authored direct-call observer. Only exported no-argument signatures are invoked. */
#include "client/client.h"
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>

extern void CL_SendPureChecksums(void);
extern void CL_ResetPureClientAtServer(void);

int cl_connectedToPureServer;
static char transcript[32768];
static int referenced;

const char *FS_ReferencedPakPureChecksums(void) {
  referenced++;
  return transcript;
}

void CL_AddReliableCommand(const char *text, qboolean disconnect) {
  printf("RELIABLE %d ", disconnect);
  for (const unsigned char *p = (const unsigned char *)text; *p; p++)
    printf("%02x", *p);
  puts("");
}

void QDECL Com_Printf(const char *format, ...) { (void)format; }

void QDECL Com_Error(int code, const char *format, ...) {
  (void)code;
  va_list args;
  va_start(args, format);
  vfprintf(stderr, format, args);
  va_end(args);
  exit(2);
}

int main(void) {
  char line[65536];
  while (fgets(line, sizeof(line), stdin)) {
    int pure, serverId, state, demo, used;
    if (sscanf(line, "seed %d %d %d %d %n", &pure, &serverId, &state, &demo, &used) == 4) {
      cl_connectedToPureServer = pure;
      cl.serverId = serverId;
      clc.state = state;
      clc.demoplaying = demo;
      char *hex = line + used;
      size_t length = strcspn(hex, "\r\n");
      if (length % 2 || length / 2 >= sizeof(transcript)) return 2;
      for (size_t k = 0; k < length; k += 2) {
        unsigned byte;
        if (sscanf(hex + k, "%2x", &byte) != 1) return 2;
        transcript[k / 2] = (char)byte;
      }
      transcript[length / 2] = 0;
      puts("SEEDED");
    } else if (!strncmp(line, "send", 4)) {
      referenced = 0;
      CL_SendPureChecksums();
      printf("END %d\n", referenced);
    } else if (!strncmp(line, "reset", 5)) {
      referenced = 0;
      CL_ResetPureClientAtServer();
      printf("END %d\n", referenced);
    } else {
      return 2;
    }
    fflush(stdout);
  }
}
