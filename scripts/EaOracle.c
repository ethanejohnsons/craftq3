/* Authored verification driver. Link an untouched upstream be_ea.c object; never ship with the mod. */
#include "qcommon/q_shared.h"
#include "botlib/botlib.h"
#include "botlib/be_interface.h"
#include "botlib/be_ea.h"
#include <stdio.h>
#include <stdlib.h>
#include <stdarg.h>

botlib_globals_t botlibglobals;
botlib_import_t botimport;
void *GetClearedHunkMemory(unsigned long size) { return calloc(1, size); }
void FreeMemory(void *pointer) { free(pointer); }
char *va(char *format, ...) {
  static char text[8192];
  va_list arguments;
  va_start(arguments, format);
  vsnprintf(text, sizeof(text), format, arguments);
  va_end(arguments);
  return text;
}
static void command(int client, char *text) { fprintf(stderr, "command[%d]=<%s>\n", client, text); }

int main(void) {
  _Static_assert(sizeof(bot_input_t) == 40, "bot_input_t ABI size");
  botlibglobals.maxclients = 4;
  botimport.BotClientCommand = command;
  EA_Setup();
  int operation, client, value;
  float scalar;
  vec3_t vector;
  while (scanf("%d %d", &operation, &client) == 2) {
    if (client < 0 || client >= 4) return 2;
    switch (operation) {
      case 0:
        if (scanf("%d", &value) != 1) return 2;
        EA_Action(client, value);
        break;
      case 1: EA_Jump(client); break;
      case 2: EA_DelayedJump(client); break;
      case 3: EA_ResetInput(client); break;
      case 4:
        if (scanf("%f %f %f %f", &vector[0], &vector[1], &vector[2], &scalar) != 4) return 2;
        EA_Move(client, vector, scalar);
        break;
      case 5:
        if (scanf("%f %f %f", &vector[0], &vector[1], &vector[2]) != 3) return 2;
        EA_View(client, vector);
        break;
      case 6:
        if (scanf("%d", &value) != 1) return 2;
        EA_SelectWeapon(client, value);
        break;
      case 7:
        if (scanf("%f", &scalar) != 1) return 2;
        EA_EndRegular(client, scalar);
        break;
      case 8: {
        if (scanf("%f", &scalar) != 1) return 2;
        bot_input_t input;
        EA_GetInput(client, scalar, &input);
        if (fwrite(&input, sizeof(input), 1, stdout) != 1 || fflush(stdout) != 0) return 3;
        break;
      }
      default: return 2;
    }
  }
  EA_Shutdown();
  return 0;
}
