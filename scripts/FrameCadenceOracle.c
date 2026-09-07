/* Authored call observer. Native engine function bodies remain unchanged. */
#include "qcommon/q_shared.h"
#include "qcommon/qcommon.h"
#ifdef ORACLE_CLIENT_METADATA
#include "client/client.h"
int ObservedClientState(void);
int ObservedClientState(void) { return clc.state; }
#else
#include "server/server.h"
#include <stdarg.h>
#include <stdio.h>

extern void ObservedNativeComFrame(void);
extern void ObservedNativeServerFrame(int msec);
extern void ObservedNativeClientFrame(int msec);
extern void ObservedNativeBotFrame(int time);
extern int ObservedClientState(void);
intptr_t QDECL ObservedGameCall(vm_t *vm, int callNum, ...);

static int frameNumber;
static int readyFrames;
static const char *context = "STARTUP";

static void event(const char *name, int argument) {
    fprintf(stderr, "CADENCE frame=%d context=%s event=%s arg=%d svtime=%d svstate=%d client=%d dedicated=%d\n",
            frameNumber, context, name, argument, sv.time, sv.state, ObservedClientState(),
            com_dedicated ? com_dedicated->integer : -1);
    fflush(stderr);
}

void Com_Frame(void) {
    const char *previous = context;
    context = "COM";
    frameNumber++;
    event("COM_ENTER", 0);
    ObservedNativeComFrame();
    event("COM_LEAVE", 0);
    if (sv.state == SS_GAME && (com_dedicated->integer || ObservedClientState() == CA_ACTIVE)) {
        readyFrames++;
        if (readyFrames == 90) {
            event("FIXTURE_RESTART", 0);
            Cbuf_AddText("map_restart 0\n");
        }
        if (readyFrames == 180) {
            event("FIXTURE_QUIT", 0);
            Cbuf_AddText("quit\n");
        }
    }
    if (frameNumber == 4000) {
        event("FIXTURE_TIMEOUT", 0);
        Cbuf_AddText("quit\n");
    }
    context = previous;
}
void SV_Frame(int msec) {
    const char *previous = context;
    event("SV_ENTER", msec);
    context = "SV";
    ObservedNativeServerFrame(msec);
    context = previous;
    event("SV_LEAVE", msec);
}

void CL_Frame(int msec) {
    const char *previous = context;
    event("CL_ENTER", msec);
    context = "CL";
    ObservedNativeClientFrame(msec);
    context = previous;
    event("CL_LEAVE", msec);
}

void SV_BotFrame(int time) {
    const char *previous = context;
    event("BOT_ENTER", time);
    context = "BOT";
    ObservedNativeBotFrame(time);
    context = previous;
    event("BOT_LEAVE", time);
}

intptr_t QDECL ObservedGameCall(vm_t *vm, int callNum, ...) {
    /* Only server translation units redirect VM_Call here. UI/cgame remain untouched. */
    static const int argumentCounts[] = {3, 1, 3, 1, 1, 1, 1, 1, 1, 0, 1};
    int arguments[12] = {0};
    if (callNum < 0 || callNum >= (int)(sizeof(argumentCounts) / sizeof(argumentCounts[0]))) {
        fprintf(stderr, "Unexpected game export %d\n", callNum);
        abort();
    }
    va_list ap;
    va_start(ap, callNum);
    for (int i = 0; i < argumentCounts[callNum]; i++) arguments[i] = va_arg(ap, int);
    va_end(ap);
    char name[32];
    snprintf(name, sizeof(name), "GAME_%d", callNum);
    event(name, arguments[0]);
    if (callNum == 0) event("GAME_INIT_RESTART", arguments[2]);
    return VM_Call(vm, callNum, arguments[0], arguments[1], arguments[2], arguments[3],
                   arguments[4], arguments[5], arguments[6], arguments[7], arguments[8],
                   arguments[9], arguments[10], arguments[11]);
}
#endif
