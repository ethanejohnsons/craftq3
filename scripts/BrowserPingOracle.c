/* Authored CPU-only ping observer. Native operations are linked unchanged.
 * Types/capacities come from public client.h/q_shared.h. Clock, cvar, console
 * argument and packet-send boundaries are controlled; no socket is opened.
 */
#include "client/client.h"
#include <arpa/inet.h>
#include <setjmp.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
extern ping_t cl_pinglist[MAX_PINGREQUESTS];
extern void CL_LocalServers_f(void);
static cvar_t protocol, legacy, gamename;
cvar_t *com_protocol = &protocol, *com_legacyprotocol = &legacy,
       *com_gamename = &gamename;
static int now, maxPing = 800, argc;
static char argv[4][1024];
static jmp_buf failed;
static void hex(const char *s) {
  for (const unsigned char *p = (const unsigned char *)s; *p; p++)
    printf("%02x", *p);
}
void QDECL Com_Printf(const char *format, ...) {
  va_list a;
  va_start(a, format);
  char b[8192];
  vsnprintf(b, sizeof(b), format, a);
  va_end(a);
  printf("PRINT ");
  hex(b);
  puts("");
}
void QDECL Com_DPrintf(const char *format, ...) { (void)format; }
void QDECL Com_Error(int code, const char *format, ...) {
  va_list a;
  va_start(a, format);
  char b[8192];
  vsnprintf(b, sizeof(b), format, a);
  va_end(a);
  printf("ERROR %d ", code);
  hex(b);
  puts("");
  longjmp(failed, 1);
}
int Sys_Milliseconds(void) { return now; }
int Cvar_VariableIntegerValue(const char *name) {
  printf("CVAR %s\n", name);
  return !strcmp(name, "cl_maxPing") ? maxPing : 0;
}
int Cmd_Argc(void) { return argc; }
char *Cmd_Argv(int index) {
  return index >= 0 && index < argc ? argv[index] : "";
}
static netadr_t address(const char *input) {
  netadr_t a = {0};
  char host[1024];
  Q_strncpyz(host, input, sizeof(host));
  char *p = strrchr(host, ':');
  if (!p)
    exit(4);
  int port = atoi(p + 1);
  *p = 0;
  if (port < 1 || port > 65535)
    exit(4);
  if (host[0] == '[') {
    size_t n = strlen(host);
    if (n < 3 || host[n - 1] != ']')
      exit(4);
    host[n - 1] = 0;
    char *scope = strchr(host + 1, '%');
    if (scope) {
      *scope = 0;
      a.scope_id = strtoul(scope + 1, NULL, 10);
    }
    if (inet_pton(AF_INET6, host + 1, a.ip6) != 1)
      exit(4);
    a.type = NA_IP6;
  } else {
    if (inet_pton(AF_INET, host, a.ip) != 1)
      exit(4);
    a.type = NA_IP;
  }
  a.port = htons(port);
  return a;
}
int NET_StringToAdr(const char *text, netadr_t *a, netadrtype_t family) {
  (void)family;
  *a = address(text);
  return 1;
}
void QDECL NET_OutOfBandPrint(netsrc_t source, netadr_t a, const char *format,
                              ...) {
  va_list args;
  va_start(args, format);
  char b[8192];
  vsnprintf(b, sizeof(b), format, args);
  va_end(args);
  printf("SEND %d %s ", source, NET_AdrToStringwPort(a));
  hex(b);
  puts("");
}
void NET_SendPacket(netsrc_t source, int length, const void *data, netadr_t a) {
  printf("PACKET %d TYPE %d PORT %d LEN %d ", source, a.type, ntohs(a.port),
         length);
  for (int i = 0; i < length; i++)
    printf("%02x", ((const unsigned char *)data)[i]);
  puts("");
}
static int decode(const char *text, byte *out, int max) {
  if (!strcmp(text, "-"))
    return 0;
  int n = (int)strlen(text);
  if (n % 2 || n / 2 > max)
    exit(4);
  for (int i = 0; i < n; i += 2) {
    unsigned b;
    if (sscanf(text + i, "%2x", &b) != 1)
      exit(4);
    out[i / 2] = (byte)b;
  }
  return n / 2;
}
static serverInfo_t *servers(int source, int **count) {
  if (source == AS_LOCAL) {
    *count = &cls.numlocalservers;
    return cls.localServers;
  }
  if (source == AS_GLOBAL) {
    *count = &cls.numglobalservers;
    return cls.globalServers;
  }
  if (source == AS_FAVORITES) {
    *count = &cls.numfavoriteservers;
    return cls.favoriteServers;
  }
  exit(4);
}
static void serverstate(int source) {
  int *count;
  serverInfo_t *s = servers(source, &count);
  printf("SERVERS %d %d\n", source, *count);
  for (int i = 0; i < *count; i++) {
    printf("SERVER %d %s %d %d %d ", i, NET_AdrToStringwPort(s[i].adr),
           s[i].ping, s[i].visible, s[i].netType);
    hex(s[i].hostName);
    putchar(' ');
    hex(s[i].mapName);
    puts("");
    printf("DETAIL %d %d %d %d %d %d %d %d %d ", i, s[i].gameType, s[i].clients,
           s[i].maxClients, s[i].minPing, s[i].maxPing, s[i].punkbuster,
           s[i].g_humanplayers, s[i].g_needpass);
    hex(s[i].game);
    puts("");
  }
}
static void slots(void) {
  printf("COUNT %d\n", CL_GetPingQueueCount());
  for (int i = 0; i < MAX_PINGREQUESTS; i++) {
    ping_t *p = &cl_pinglist[i];
    if (p->adr.port) {
      printf("SLOT %d %s %d %d ", i, NET_AdrToStringwPort(p->adr), p->start,
             p->time);
      hex(p->info);
      puts("");
    }
  }
}
int main(void) {
  char line[65536], op[32], arg[32768], from[1024];
  protocol.integer = 71;
  legacy.integer = 68;
  gamename.string = "Quake3Arena";
  printf("READY slots=%d local=%d global=%d\n", MAX_PINGREQUESTS,
         MAX_OTHER_SERVERS, MAX_GLOBAL_SERVERS);
  fflush(stdout);
  while (fgets(line, sizeof(line), stdin)) {
    if (sscanf(line, "%31s", op) != 1)
      continue;
    if (setjmp(failed)) {
      puts("END");
      fflush(stdout);
      continue;
    }
    if (!strcmp(op, "reset")) {
      memset(cl_pinglist, 0, sizeof(ping_t) * MAX_PINGREQUESTS);
      memset(&cls, 0, sizeof(cls));
      now = 0;
      maxPing = 800;
      puts("RESET");
    } else if (sscanf(line, "time %d", &now) == 1) {
      cls.realtime = now;
      printf("TIME %d\n", now);
    } else if (sscanf(line, "max %d", &maxPing) == 1)
      printf("MAX %d\n", maxPing);
    else if (sscanf(line, "ping %1023s", arg) == 1) {
      argc = 2;
      strcpy(argv[0], "ping");
      strcpy(argv[1], arg);
      CL_Ping_f();
      slots();
    } else if (!strcmp(op, "slots"))
      slots();
    else if (!strcmp(op, "entry")) {
      int source, index, *count;
      sscanf(line, "entry %d %d", &source, &index);
      if (index < 0 || index >= MAX_OTHER_SERVERS)
        exit(4);
      serverInfo_t *row = &servers(source, &count)[index];
      printf("ENTRY %d TYPE %d PORT %d PING %d VISIBLE %d HOST ", index,
             row->adr.type, ntohs(row->adr.port), row->ping, row->visible);
      hex(row->hostName);
      puts("");
    } else if (!strcmp(op, "local")) {
      CL_LocalServers_f();
      printf("LOCAL %d SOURCE %d\n", cls.numlocalservers, cls.pingUpdateSource);
      slots();
    } else if (!strcmp(op, "fillget") || !strcmp(op, "fillinfo")) {
      int index, size;
      sscanf(line, "%*s %d %d", &index, &size);
      if (size < 1 || size >= 1024)
        exit(4);
      char text[1024];
      memset(text, 0x7f, sizeof(text));
      int time = -123;
      if (!strcmp(op, "fillget"))
        CL_GetPing(index, text, size, &time);
      else
        CL_GetPingInfo(index, text, size);
      printf("MEM %d %d ", index, time);
      for (int i = 0; i < size + 1; i++)
        printf("%02x", (unsigned char)text[i]);
      puts("");
    } else if (!strcmp(op, "get")) {
      int index, size = 1024;
      sscanf(line, "get %d %d", &index, &size);
      if (size < 1 || size > 1024)
        exit(4);
      char text[1024];
      memset(text, 0, sizeof(text));
      int ping = -123;
      CL_GetPing(index, text, size, &ping);
      printf("GET %d %d ", index, ping);
      hex(text);
      puts("");
      slots();
    } else if (!strcmp(op, "info")) {
      int index, size = 1024;
      sscanf(line, "info %d %d", &index, &size);
      if (size < 1 || size > 1024)
        exit(4);
      char text[1024] = {0};
      CL_GetPingInfo(index, text, size);
      printf("INFO %d ", index);
      hex(text);
      puts("");
    } else if (!strcmp(op, "clear")) {
      int index;
      sscanf(line, "clear %d", &index);
      CL_ClearPing(index);
      slots();
    } else if (sscanf(line, "reply %1023s %32767s", from, arg) == 2) {
      byte bytes[16384] = {0};
      int n = decode(arg, bytes, sizeof(bytes) - 1);
      msg_t m;
      MSG_InitOOB(&m, bytes, sizeof(bytes));
      m.cursize = n + 1;
      MSG_BeginReadingOOB(&m);
      CL_ServerInfoPacket(address(from), &m);
      slots();
    } else if (!strcmp(op, "slot")) {
      int index, start, time;
      sscanf(line, "slot %d %1023s %d %d %32767s", &index, from, &start, &time,
             arg);
      if (index < 0 || index >= MAX_PINGREQUESTS)
        exit(4);
      ping_t *p = &cl_pinglist[index];
      memset(p, 0, sizeof(*p));
      p->adr = address(from);
      p->start = start;
      p->time = time;
      decode(arg, (byte *)p->info, sizeof(p->info) - 1);
      slots();
    } else if (!strcmp(op, "raw")) {
      int index;
      sscanf(line, "raw %d", &index);
      if (index < 0 || index >= MAX_PINGREQUESTS)
        exit(4);
      ping_t *p = &cl_pinglist[index];
      printf("RAW %d %d %d %d ", index, ntohs(p->adr.port), p->start, p->time);
      hex(p->info);
      puts("");
    } else if (!strcmp(op, "server")) {
      int source, index, ping, visible;
      sscanf(line, "server %d %d %1023s %d %d", &source, &index, from, &ping,
             &visible);
      if (index < 0 || index >= MAX_OTHER_SERVERS)
        exit(4);
      int *count;
      serverInfo_t *s = servers(source, &count);
      memset(&s[index], 0, sizeof(*s));
      s[index].adr = address(from);
      s[index].ping = ping;
      s[index].visible = visible;
      if (index >= *count)
        *count = index + 1;
      serverstate(source);
    } else if (!strcmp(op, "overflow")) {
      int index;
      sscanf(line, "overflow %d %1023s", &index, from);
      if (index < 0 || index >= MAX_GLOBAL_SERVERS)
        exit(4);
      cls.globalServerAddresses[index] = address(from);
      if (index >= cls.numGlobalServerAddresses)
        cls.numGlobalServerAddresses = index + 1;
      printf("OVERFLOW %d\n", cls.numGlobalServerAddresses);
    } else if (!strcmp(op, "count")) {
      int source, n, *count;
      sscanf(line, "count %d %d", &source, &n);
      servers(source, &count);
      *count = n;
      printf("SETCOUNT %d\n", n);
    } else if (!strcmp(op, "servers")) {
      int source;
      sscanf(line, "servers %d", &source);
      serverstate(source);
    } else if (!strcmp(op, "source")) {
      int source;
      sscanf(line, "source %d", &source);
      cls.pingUpdateSource = source;
      printf("SOURCE %d\n", source);
    } else if (!strcmp(op, "update")) {
      int source;
      sscanf(line, "update %d", &source);
      printf("UPDATE %d\n", CL_UpdateVisiblePings_f(source));
      slots();
    } else {
      printf("UNKNOWN\n");
      return 3;
    }
    puts("END");
    fflush(stdout);
  }
}
