/* Authored differential driver. Links unchanged reference msg/huffman objects only for testing. */
#define main framing_oracle_main
#include "NetchanOracle.c"
#undef main
#include <stddef.h>

typedef struct { const char *name; int offset, bits; } observed_field;
extern observed_field entityStateFields[], playerStateFields[];

static void state(const char *text, void *target, int size) {
  memset(target, 0, size);
  if (strcmp(text, "-")) {
    if (strlen(text) != (size_t)size * 2 || decode(text, target) != size) exit(2);
  }
}

int main(void) {
  char operation[16], fromText[1024], toText[1024];
  byte bytes[MAX_MSGLEN + 64];
  while (scanf("%15s", operation) == 1) {
    if (!strcmp(operation, "fields")) {
      for (int i = 0; i < 51; i++) printf("entity %s %d %d\n", entityStateFields[i].name, entityStateFields[i].offset, entityStateFields[i].bits);
      for (int i = 0; i < 48; i++) printf("player %s %d %d\n", playerStateFields[i].name, playerStateFields[i].offset, playerStateFields[i].bits);
      puts("fields-end");
    } else if (!strcmp(operation, "entity") || !strcmp(operation, "player")) {
      int force, prefixBits; unsigned prefix;
      if (scanf("%1023s %1023s %d %d %u", fromText, toText, &force, &prefixBits, &prefix) != 5 || prefixBits < 0 || prefixBits > 7) return 2;
      msg_t message; memset(bytes, 0, sizeof(bytes)); MSG_Init(&message, bytes, MAX_MSGLEN);
      if (prefixBits) MSG_WriteBits(&message, prefix, prefixBits);
      if (!strcmp(operation, "entity")) {
        entityState_t from, to, result;
        state(fromText, &from, sizeof(from)); state(toText, &to, sizeof(to));
        /* A missing baseline is normalized to a zero structure for native entity updates. */
        MSG_WriteDeltaEntity(&message, !strcmp(fromText,"-") && !strcmp(toText,"-") ? NULL : &from, !strcmp(toText,"-") ? NULL : &to, force);
        int bits = message.bit;
        printf("encoded %d ", bits); hex(bytes, message.cursize); putchar('\n');
        MSG_BeginReading(&message); if (prefixBits) MSG_ReadBits(&message, prefixBits);
        if (bits != prefixBits) {
          int number = MSG_ReadBits(&message, GENTITYNUM_BITS);
          memset(&result, 0xa5, sizeof(result));
          MSG_ReadDeltaEntity(&message, &from, &result, number);
          printf("decoded %d %d ", number, message.bit); hex((byte*)&result, sizeof(result)); putchar('\n');
        } else puts("decoded -");
      } else {
        playerState_t from, to, result;
        state(fromText, &from, sizeof(from)); state(toText, &to, sizeof(to));
        MSG_WriteDeltaPlayerstate(&message, !strcmp(fromText,"-") ? NULL : &from, &to);
        printf("encoded %d ", message.bit); hex(bytes, message.cursize); putchar('\n');
        MSG_BeginReading(&message); if (prefixBits) MSG_ReadBits(&message, prefixBits);
        memset(&result, 0xa5, sizeof(result));
        MSG_ReadDeltaPlayerstate(&message, !strcmp(fromText,"-") ? NULL : &from, &result);
        printf("decoded %d ", message.bit); hex((byte*)&result, sizeof(result)); putchar('\n');
      }
    } else return 2;
    fflush(stdout);
  }
  return 0;
}
