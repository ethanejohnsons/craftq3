/* Authored call-boundary observer, linked with unchanged native movement/math objects. */
#include "qcommon/q_shared.h"
#include <stdio.h>
extern int BotAddToTarget(vec3_t start, vec3_t end, float maxdist, float *dist, vec3_t target);
int main(void) {
  vec3_t start, end, target;
  float maximum, distance;
  while (scanf("%f %f %f %f %f %f %f %f", &start[0], &start[1], &start[2],
      &end[0], &end[1], &end[2], &maximum, &distance) == 8) {
    VectorSet(target, 111, 222, 333);
    int finished = BotAddToTarget(start, end, maximum, &distance, target);
    printf("%d %.9g %.9g %.9g %.9g\n", finished, distance, target[0], target[1], target[2]);
    fflush(stdout);
  }
  return 0;
}
