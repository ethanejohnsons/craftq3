/* Authored host for unchanged CL_GetServerCommand/CL_GetGameState operations. */
#include "client/client.h"
#include <setjmp.h>
#include <stdarg.h>
#include <stdio.h>

/* Exported definition signatures, observed without inspecting their function bodies. */
extern qboolean CL_GetServerCommand(int serverCommandNumber);
extern void CL_GetGameState(gameState_t *state);
extern qboolean CL_GetSnapshot(int snapshotNumber, snapshot_t *snapshot);
extern qboolean CL_GetUserCmd(int commandNumber, usercmd_t *command);

static jmp_buf failure;
static int errorCode, soundClears, notifications;
static char errorText[8192], queued[8192];
static cvar_t localServer;
cvar_t *com_sv_running = &localServer;

void QDECL Com_Error(int code, const char *format, ...) {
    va_list args;
    va_start(args, format);
    vsnprintf(errorText, sizeof(errorText), format, args);
    va_end(args);
    errorCode = code;
    longjmp(failure, 1);
}
void Con_ClearNotify(void) { notifications++; }
void S_ClearSoundBuffer(void) { soundClears++; }
void Cbuf_AddText(const char *text) { snprintf(queued, sizeof(queued), "%s", text); }

static void bytes(const void *data, int count) {
    const byte *source = data;
    if (!count) putchar('-');
    for (int index = 0; index < count; index++) printf("%02x", source[index]);
}
static int unhex(const char *text, byte *out, int capacity) {
    if (!strcmp(text, "-")) return 0;
    int size = (int)strlen(text);
    if ((size & 1) || size / 2 >= capacity) exit(2);
    for (int index = 0; index < size; index += 2) {
        unsigned value;
        if (sscanf(text + index, "%2x", &value) != 1) exit(2);
        out[index / 2] = (byte)value;
    }
    out[size / 2] = 0;
    return size / 2;
}
static void state(void) {
    int nonzero = 0;
    for (size_t index = 0; index < sizeof(cl.cmds); index++) nonzero += ((byte *)cl.cmds)[index] != 0;
    printf("STATE latest=%d executed=%d client=%d cmdNumber=%d cmdBytes=%d sound=%d notify=%d serverId=%d queued=",
           clc.serverCommandSequence, clc.lastExecutedServerCommand, clc.state, cl.cmdNumber,
           nonzero, soundClears, notifications, cl.serverId);
    bytes(queued, (int)strlen(queued));
    putchar('\n');
}

int main(void) {
    char operation[24], text[MAX_MSGLEN * 2 + 2];
    byte input[MAX_MSGLEN + 1];
    while (scanf("%23s", operation) == 1) {
        errorText[0] = 0; errorCode = -1;
        if (setjmp(failure)) {
            printf("ERROR %d ", errorCode); bytes(errorText, (int)strlen(errorText)); putchar('\n');
            state(); puts("END"); fflush(stdout); continue;
        }
        if (!strcmp(operation, "reset")) {
            int demo;
            if (scanf("%d", &demo) != 1) return 2;
            memset(&cl, 0, sizeof(cl)); memset(&clc, 0, sizeof(clc)); memset(&cls, 0, sizeof(cls));
            clc.state = CA_ACTIVE; clc.demoplaying = demo; clc.netchan.compat = qtrue; clc.compat = qtrue;
            cl.gameState.dataCount = 1; soundClears = 0; notifications = 0; queued[0] = 0;
            Cmd_TokenizeString("fixture_initial_argument"); puts("RESET");
        } else if (!strcmp(operation, "store")) {
            int sequence;
            if (scanf("%d %32769s", &sequence, text) != 2 || sequence < 0) return 2;
            int size = unhex(text, input, sizeof(input));
            if (size >= MAX_STRING_CHARS) return 2;
            memcpy(clc.serverCommands[sequence & (MAX_RELIABLE_COMMANDS - 1)], input, (size_t)size + 1);
            if (sequence > clc.serverCommandSequence) clc.serverCommandSequence = sequence;
            puts("STORED");
        } else if (!strcmp(operation, "parse")) {
            int sequence;
            if (scanf("%d %32769s", &sequence, text) != 2) return 2;
            memset(input, 0, sizeof(input)); int size = unhex(text, input, sizeof(input));
            clc.serverMessageSequence = sequence;
            msg_t message; MSG_Init(&message, input, MAX_MSGLEN); message.cursize = size;
            MSG_BeginReading(&message); CL_ParseServerMessage(&message);
            printf("PARSED bits=%d\n", message.bit);
        } else if (!strcmp(operation, "get")) {
            int sequence;
            if (scanf("%d", &sequence) != 1) return 2;
            int accepted = CL_GetServerCommand(sequence);
            printf("RETURN %d ARGC %d RAW ", accepted, Cmd_Argc());
            bytes(Cmd_Cmd(), (int)strlen(Cmd_Cmd()));
            for (int index = 0; index < Cmd_Argc(); index++) {
                putchar(' '); bytes(Cmd_Argv(index), (int)strlen(Cmd_Argv(index)));
            }
            putchar('\n');
        } else if (!strcmp(operation, "string")) {
            int index;
            if (scanf("%d", &index) != 1 || index < 0 || index >= MAX_CONFIGSTRINGS) return 2;
            gameState_t copy; CL_GetGameState(&copy);
            const char *value = copy.stringData + copy.stringOffsets[index];
            printf("STRING %d ", index); bytes(value, (int)strlen(value)); putchar('\n');
        } else if (!strcmp(operation, "snapshot")) {
            int number, fill;
            if (scanf("%d %d", &number, &fill) != 2 || fill < 0 || fill > 255) return 2;
            snapshot_t snapshot; memset(&snapshot, fill, sizeof(snapshot));
            int accepted = CL_GetSnapshot(number, &snapshot);
            printf("SNAPSHOT %d count=%d sequence=%d time=%d entities=%d ping=%d size=%zu\n",
                   accepted, snapshot.numServerCommands, snapshot.serverCommandSequence,
                   snapshot.serverTime, snapshot.numEntities, snapshot.ping, sizeof(snapshot));
        } else if (!strcmp(operation, "usercmd")) {
            int number, fill;
            if (scanf("%d %d", &number, &fill) != 2 || fill < 0 || fill > 255) return 2;
            usercmd_t command; memset(&command, fill, sizeof(command));
            int accepted = CL_GetUserCmd(number, &command);
            printf("USERCMD %d ", accepted); bytes(&command, sizeof(command)); putchar('\n');
        } else if (!strcmp(operation, "seed")) {
            memset(cl.cmds, 0xa5, sizeof(cl.cmds)); cl.cmdNumber = 123;
            puts("SEEDED");
        } else return 2;
        state(); puts("END"); fflush(stdout);
    }
    return 0;
}
