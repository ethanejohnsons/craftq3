/* Authored development observer linked with unchanged native movement code. */
#include "qcommon/q_shared.h"
#include "botlib/botlib.h"
#include "botlib/be_ai_goal.h"
#include "botlib/be_ai_move.h"
#include "BotMoveStateMetadata.h"
#include <stdio.h>
#include <stdlib.h>
#include <stdarg.h>

botlib_import_t botimport;
static float now;
void *GetClearedMemory(unsigned long size) { return calloc(1, size); }
void FreeMemory(void *memory) { free(memory); }
float AAS_Time(void) { return now; }
static void print(int type, char *format, ...) {
  (void)type; va_list arguments; va_start(arguments, format);
  vfprintf(stderr, format, arguments); va_end(arguments);
}
int main(void) {
  botimport.Print = print;
  int handle = BotAllocMoveState();
  bot_movestate_t *state = BotMoveStateFromHandle(handle);
  char line[256], operation[32];
  int reach, tries, number, flags;
  float expiry, duration;
  while (fgets(line, sizeof(line), stdin)) {
    if (sscanf(line, "seed %d %f %d %f", &reach, &expiry, &tries, &now) == 4) {
      state->avoidreach[0] = reach; state->avoidreachtimes[0] = expiry;
      state->avoidreachtries[0] = tries;
    } else if (sscanf(line, "clock %f", &now) == 1) {
    } else if (sscanf(line, "add %d %f", &number, &duration) == 2) {
      BotAddToAvoidReach(state, number, duration);
    } else if (sscanf(line, "initflags %d %d", &state->moveflags, &flags) == 2) {
      bot_initmove_t input = {0}; input.or_moveflags = flags;
      BotInitMoveState(handle, &input);
    } else if (sscanf(line, "%31s", operation) == 1 && !strcmp(operation, "reset")) {
      BotResetAvoidReach(handle);
    } else if (sscanf(line, "%31s", operation) == 1 && !strcmp(operation, "last")) {
      BotResetLastAvoidReach(handle);
    } else return 2;
    printf("STATE %d %.9g %d %d\n", state->avoidreach[0],
        state->avoidreachtimes[0], state->avoidreachtries[0], state->moveflags);
    fflush(stdout);
  }
  BotFreeMoveState(handle);
  return 0;
}
