/* Authored driver for unchanged native net_chan.c/msg.c/huffman.c. Never shipped in the mod. */
#include "qcommon/q_shared.h"
#include "qcommon/qcommon.h"
#include <stdio.h>
#include <stdlib.h>
#include <stdarg.h>

static cvar_t zero;
cvar_t *cl_packetdelay = &zero, *sv_packetdelay = &zero, *com_timescale = &zero, *cl_shownet = &zero;
void QDECL Com_Printf(const char *format, ...) { (void) format; }
void QDECL Com_Error(int code, const char *format, ...) {
  va_list args; va_start(args, format); vfprintf(stderr, format, args); va_end(args); exit(code + 20);
}
cvar_t *Cvar_Get(const char *name, const char *value, int flags) {
  static cvar_t port;
  if (!strcmp(name, "net_qport")) { port.integer = atoi(value); return &port; }
  return &zero;
}
char *QDECL va(char *format, ...) {
  static char buffer[4096]; va_list args; va_start(args, format);
  vsnprintf(buffer, sizeof(buffer), format, args); va_end(args); return buffer;
}
int Sys_Milliseconds(void) { return 1; }
const char *NET_AdrToString(netadr_t address) { return "oracle"; }
void *S_MallocDebug(int size, char *label, char *file, int line) { return calloc(1, size); }
void Z_Free(void *pointer) { free(pointer); }
short ShortSwap(short value) { return (short)(((unsigned short)value >> 8) | (value << 8)); }
void Q_strncpyz(char *to, const char *from, int size) {
  if (size <= 0) abort(); snprintf(to, (size_t)size, "%s", from);
}
static void hex(const byte *data, int length) {
  if (!length) fputs("-", stdout);
  for (int i = 0; i < length; i++) printf("%02x", data[i]);
}
void Sys_SendPacket(int length, const void *data, netadr_t to) {
  fputs("packet ", stdout); hex(data, length); putchar('\n');
}
static int decode(const char *source, byte *target) {
  if (!strcmp(source, "-")) return 0;
  size_t chars = strlen(source);
  if ((chars & 1) || chars / 2 > MAX_MSGLEN) exit(2);
  for (size_t i = 0; i < chars; i += 2) {
    unsigned value;
    if (sscanf(source + i, "%2x", &value) != 1) exit(2);
    target[i / 2] = (byte)value;
  }
  return (int)(chars / 2);
}
int main(void) {
  netchan_t channel;
  netadr_t address = {0}; address.type = NA_IP;
  Netchan_Init(12345);
  char operation[16], data[MAX_MSGLEN * 2 + 2];
  byte bytes[MAX_MSGLEN + 64];
  while (scanf("%15s", operation) == 1) {
    if (!strcmp(operation, "reset")) {
      int role, qport;
      if (scanf("%d %d", &role, &qport) != 2) return 2;
      Netchan_Init(qport);
      Netchan_Setup(role ? NS_SERVER : NS_CLIENT, &channel, address, qport, 42, qtrue);
      puts("reset");
    } else if (!strcmp(operation, "send")) {
      if (scanf("%32769s", data) != 1) return 2;
      int length = decode(data, bytes);
      Netchan_Transmit(&channel, length, bytes);
      while (channel.unsentFragments) Netchan_TransmitNextFragment(&channel);
      printf("sent %d\n", channel.outgoingSequence);
    } else if (!strcmp(operation, "receive")) {
      if (scanf("%32769s", data) != 1) return 2;
      msg_t message;
      MSG_InitOOB(&message, bytes, sizeof(bytes));
      message.cursize = decode(data, bytes);
      int accepted = Netchan_Process(&channel, &message);
      printf("received %d %d %d %d %d ", accepted, channel.incomingSequence,
             channel.fragmentSequence, channel.fragmentLength, channel.dropped);
      if (accepted) hex(message.data + message.readcount, message.cursize - message.readcount);
      else putchar('-');
      putchar('\n');
    } else if (!strcmp(operation, "encode")) {
      int count, width;
      unsigned value;
      if (scanf("%d", &count) != 1 || count < 0 || count > 4096) return 2;
      memset(bytes, 0, sizeof(bytes));
      msg_t message; MSG_Init(&message, bytes, MAX_MSGLEN);
      for (int i = 0; i < count; i++) {
        if (scanf("%d %u", &width, &value) != 2) return 2;
        MSG_WriteBits(&message, (int)value, width);
      }
      printf("encoded %d %d ", message.bit, message.cursize);
      hex(bytes, message.cursize); putchar('\n');
    } else if (!strcmp(operation, "decode")) {
      int count, width;
      if (scanf("%32769s %d", data, &count) != 2 || count < 0 || count > 4096) return 2;
      memset(bytes, 0, sizeof(bytes));
      msg_t message; MSG_Init(&message, bytes, MAX_MSGLEN);
      message.cursize = decode(data, bytes);
      MSG_BeginReading(&message);
      fputs("decoded", stdout);
      for (int i = 0; i < count; i++) {
        if (scanf("%d", &width) != 1) return 2;
        printf(" %d", MSG_ReadBits(&message, width));
      }
      printf(" bits %d read %d\n", message.bit, message.readcount);
    } else return 2;
    fflush(stdout);
  }
  return 0;
}
