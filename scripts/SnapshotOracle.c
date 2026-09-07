/* Authored observer: unchanged native parser, supplied states and packet bytes only. */
#define main framing_oracle_main
#include "NetchanOracle.c"
#undef main
#include "client/client.h"

clientActive_t cl;
clientConnection_t clc;
clientStatic_t cls;
cvar_t *cl_paused = &zero, *cl_autoRecordDemo = &zero;
void QDECL Com_DPrintf(const char *format, ...) { (void)format; }
extern void CL_ParseSnapshot(msg_t *msg);

static void readState(void *state, int size) {
  char text[1024];
  if (scanf("%1023s", text) != 1) exit(2);
  memset(state, 0, (size_t)size);
  if (strcmp(text, "-") && (strlen(text) != (size_t)size * 2 || decode(text, state) != size)) exit(2);
}

#ifndef SNAPSHOT_ORACLE_NO_MAIN
int main(void) {
  char operation[16], data[MAX_MSGLEN * 2 + 2];
  byte bytes[MAX_MSGLEN + 64];
  while (scanf("%15s", operation) == 1) {
    if (!strcmp(operation, "reset")) {
      memset(&cl, 0, sizeof(cl)); memset(&clc, 0, sizeof(clc)); memset(&cls, 0, sizeof(cls));
      clc.state = CA_ACTIVE; clc.netchan.compat = qtrue; clc.compat = qtrue;
      puts("reset");
    } else if (!strcmp(operation, "baseline")) {
      entityState_t entity; readState(&entity, sizeof(entity));
      if (entity.number < 0 || entity.number >= ENTITYNUM_NONE) return 2;
      cl.entityBaselines[entity.number] = entity;
      puts("baseline");
    } else if (!strcmp(operation, "previous")) {
      int number, count;
      if (scanf("%d %d", &number, &count) != 2 || number < 1 || count < 0 || count > MAX_SNAPSHOT_ENTITIES) return 2;
      clSnapshot_t *snapshot = &cl.snapshots[number & PACKET_MASK];
      snapshot->valid = qtrue; snapshot->messageNum = number;
      snapshot->numEntities = count; snapshot->parseEntitiesNum = cl.parseEntitiesNum;
      readState(&snapshot->ps, sizeof(snapshot->ps));
      for (int i = 0; i < count; i++) readState(&cl.parseEntities[cl.parseEntitiesNum++ & (MAX_PARSE_ENTITIES - 1)], sizeof(entityState_t));
      puts("previous");
    } else if (!strcmp(operation, "parse")) {
      int sequence, commands, prefix;
      if (scanf("%d %d %d %32769s", &sequence, &commands, &prefix, data) != 4 || sequence < 1 || prefix < 0 || prefix > 7) return 2;
      clc.serverMessageSequence = sequence; clc.serverCommandSequence = commands;
      msg_t message; memset(bytes, 0, sizeof(bytes)); MSG_Init(&message, bytes, MAX_MSGLEN);
      message.cursize = decode(data, bytes); MSG_BeginReading(&message);
      if (prefix) MSG_ReadBits(&message, prefix);
      CL_ParseSnapshot(&message);
      printf("snapshot %d %d %d %d %d %d %d %d ", cl.snap.valid, message.bit, cl.snap.messageNum,
          cl.snap.deltaNum, cl.snap.serverTime, cl.snap.snapFlags, cl.snap.serverCommandNum, cl.snap.numEntities);
      hex(cl.snap.areamask, sizeof(cl.snap.areamask)); putchar(' '); hex((byte*)&cl.snap.ps, sizeof(cl.snap.ps)); putchar('\n');
      for (int i = 0; i < cl.snap.numEntities; i++) {
        hex((byte*)&cl.parseEntities[(cl.snap.parseEntitiesNum + i) & (MAX_PARSE_ENTITIES - 1)], sizeof(entityState_t)); putchar('\n');
      }
    } else return 2;
    fflush(stdout);
  }
  return 0;
}
#endif
