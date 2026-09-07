/* Authored parser host. Filesystem/download/cvar effects are isolated from the real installation. */
#define SNAPSHOT_ORACLE_NO_MAIN
#define Q_strncpyz oracle_strncpyz
#define va oracle_va
#define ShortSwap oracle_ShortSwap
#include "SnapshotOracle.c"
#undef main
#undef Q_strncpyz
#undef va
#undef ShortSwap

char cl_oldGame[MAX_QPATH];
qboolean cl_oldGameSet;
static int effectCount;
void CL_ClearState(void) { memset(&cl, 0, sizeof(cl)); }
void CL_InitDownloads(void) { effectCount++; }
void CL_NextDownload(void) { abort(); }
void CL_AddReliableCommand(const char *command, qboolean disconnect) { abort(); }
void CL_StopRecord_f(void) { abort(); }
void CL_WritePacket(void) { abort(); }
void Con_Close(void) { effectCount++; }
int Cvar_Flags(const char *name) { return CVAR_SYSTEMINFO; }
void Cvar_Set(const char *name, const char *value) { effectCount++; }
void Cvar_SetSafe(const char *name, const char *value) { effectCount++; }
void Cvar_SetValue(const char *name, float value) { effectCount++; }
void Cvar_SetCheatState(void) { effectCount++; }
float Cvar_VariableValue(const char *name) { return 0; }
char *Cvar_VariableString(const char *name) { return ""; }
void Cvar_VariableStringBuffer(const char *name, char *buffer, int size) { if (size > 0) buffer[0] = 0; }
qboolean FS_ConditionalRestart(int feed, qboolean disconnect) { effectCount++; return qfalse; }
qboolean FS_InvalidGameDir(const char *name) { return qfalse; }
void FS_PureServerSetLoadedPaks(const char *sums, const char *names) { effectCount++; }
void FS_PureServerSetReferencedPaks(const char *sums, const char *names) { effectCount++; }
fileHandle_t FS_BaseDir_FOpenFileWrite_HomeData(const char *filename) { abort(); }
void FS_BaseDir_Rename_HomeData(const char *from, const char *to, qboolean safe) { abort(); }
int FS_Write(const void *data, int length, fileHandle_t file) { abort(); }
void FS_FCloseFile(fileHandle_t file) { abort(); }

int main(void) {
  char operation[16], data[MAX_MSGLEN * 2 + 2];
  byte bytes[MAX_MSGLEN + 64];
  while (scanf("%15s", operation) == 1) {
    if (!strcmp(operation, "reset")) {
      memset(&cl, 0, sizeof(cl)); memset(&clc, 0, sizeof(clc)); memset(&cls, 0, sizeof(cls));
      memset(cl_oldGame, 0, sizeof(cl_oldGame)); cl_oldGameSet = qfalse;
      clc.state = CA_ACTIVE; clc.netchan.compat = qtrue; clc.compat = qtrue; puts("reset");
    } else if (!strcmp(operation, "parse")) {
      int sequence;
      if (scanf("%d %32769s", &sequence, data) != 2 || sequence < 1) return 2;
      clc.serverMessageSequence = sequence;
      msg_t message; memset(bytes, 0, sizeof(bytes)); MSG_Init(&message, bytes, MAX_MSGLEN);
      message.cursize = decode(data, bytes); MSG_BeginReading(&message);
      CL_ParseServerMessage(&message);
      printf("message %d %d %d %d %d %d\n", message.bit, clc.reliableAcknowledge,
          clc.serverCommandSequence, clc.clientNum, clc.checksumFeed, cl.gameState.dataCount);
      for (int i = 0; i < MAX_CONFIGSTRINGS; i++) if (cl.gameState.stringOffsets[i]) {
        printf("string %d ", i);
        const char *value = cl.gameState.stringData + cl.gameState.stringOffsets[i]; hex((byte*)value, (int)strlen(value)); putchar('\n');
      }
      for (int i = 0; i < ENTITYNUM_NONE; i++) {
        entityState_t empty; memset(&empty, 0, sizeof(empty));
        if (memcmp(&empty, &cl.entityBaselines[i], sizeof(empty))) {
          printf("baseline %d ", i); hex((byte*)&cl.entityBaselines[i], sizeof(entityState_t)); putchar('\n');
        }
      }
      for (int i = 0; i < MAX_RELIABLE_COMMANDS; i++) if (clc.serverCommands[i][0]) {
        printf("command %d ", i); hex((byte*)clc.serverCommands[i], (int)strlen(clc.serverCommands[i])); putchar('\n');
      }
      printf("snapshot %d %d %d %d %d %d %d ", cl.snap.valid, cl.snap.messageNum, cl.snap.deltaNum,
          cl.snap.serverTime, cl.snap.snapFlags, cl.snap.serverCommandNum, cl.snap.numEntities);
      hex(cl.snap.areamask, sizeof(cl.snap.areamask)); putchar(' '); hex((byte*)&cl.snap.ps, sizeof(cl.snap.ps)); putchar('\n');
      for (int i = 0; i < cl.snap.numEntities; i++) { hex((byte*)&cl.parseEntities[(cl.snap.parseEntitiesNum + i) & (MAX_PARSE_ENTITIES - 1)], sizeof(entityState_t)); putchar('\n'); }
      puts("end");
    } else return 2;
    fflush(stdout);
  }
  return 0;
}
