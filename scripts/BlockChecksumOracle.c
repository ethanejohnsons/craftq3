/* Authored buffer observer; links unchanged Com_BlockChecksum, public qcommon.h
 * signature only. No native checksum function body was inspected or copied. */
#include "qcommon/q_shared.h"
#include "qcommon/qcommon.h"
#include <inttypes.h>
#include <stdio.h>
#include <stdlib.h>

static int nibble(char c) {
  if (c >= '0' && c <= '9') return c - '0';
  if (c >= 'a' && c <= 'f') return c - 'a' + 10;
  if (c >= 'A' && c <= 'F') return c - 'A' + 10;
  return -1;
}

int main(void) {
  char *line = NULL;
  size_t capacity = 0;
  ssize_t length;
  while ((length = getline(&line, &capacity, stdin)) >= 0) {
    while (length && (line[length - 1] == '\n' || line[length - 1] == '\r')) length--;
    if (length < 4 || strncmp(line, "hex ", 4) || (length - 4) % 2 || length > 8388612) return 2;
    size_t bytes = (size_t)(length - 4) / 2;
    unsigned char *input = calloc(bytes + 1, 1);
    if (!input) return 3;
    for (size_t i = 0; i < bytes; i++) {
      int a = nibble(line[4 + 2*i]), b = nibble(line[5 + 2*i]);
      if (a < 0 || b < 0) return 2;
      input[i] = (unsigned char)((a << 4) | b);
    }
    printf("BLOCK %08" PRIx32 "\n", (uint32_t)Com_BlockChecksum(input, (int)bytes));
    fflush(stdout);
    free(input);
  }
  free(line);
  return 0;
}
