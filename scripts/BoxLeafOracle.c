/* Authored QA host. Calls public native collision APIs after unchanged map loading. */
#include "qcommon/q_shared.h"
#include "qcommon/qcommon.h"
#include "qcommon/cm_local.h"
#include <math.h>
#include <stdio.h>
#include <stdlib.h>

extern void ObservedBoxLeafLoadMap(const char *name, qboolean clientload, int *checksum);

static void fixtures(void) {
    const char *path = getenv("CRAFTQ3_BOX_FIXTURES");
    if (!path) return;
    FILE *input = fopen(path, "r");
    if (!input) exit(2);
    int id, cap, front, back;
    vec3_t mins, maxs;
    cplane_t plane = {0};
    while (fscanf(input, "%d %d %f %f %f %f %f %f %f %f %f %f %d %d",
                  &id, &cap, &plane.normal[0], &plane.normal[1], &plane.normal[2],
                  &plane.dist, &mins[0], &mins[1], &mins[2], &maxs[0], &maxs[1], &maxs[2],
                  &front, &back) == 14) {
        if (cap < 0 || cap > 2) exit(3);
        plane.type = PlaneTypeForNormal(plane.normal);
        plane.signbits = 0;
        for (int axis = 0; axis < 3; axis++)
            if (plane.normal[axis] < 0) plane.signbits |= (1 << axis);
        cNode_t node = {&plane, {-1, -2}};
        cLeaf_t leaves[2] = {{0}};
        leaves[0].cluster = front; leaves[1].cluster = back;
        leaves[0].area = 0; leaves[1].area = 1;
        clipMap_t saved = cm;
        cm.nodes = &node; cm.numNodes = 1;
        cm.leafs = leaves; cm.numLeafs = 2;
        int result[2] = {-1, -1}, last = -777777;
        int count = CM_BoxLeafnums(mins, maxs, result, cap, &last);
        printf("CMFIX %d %d %d", id, count, last);
        for (int i = 0; i < count; i++) printf(" %d", result[i]);
        putchar('\n');
        cm = saved;
    }
    if (!feof(input)) exit(3);
    fclose(input);
}

void CM_LoadMap(const char *name, qboolean clientload, int *checksum) {
    ObservedBoxLeafLoadMap(name, clientload, checksum);
    const char *path = getenv("CRAFTQ3_BOX_QUERIES");
    if (!path) return;
    FILE *input = fopen(path, "r");
    if (!input) { perror("Box query input"); exit(2); }
    unsigned char *seen = calloc((size_t)cm.numLeafs, 1);
    if (!seen) exit(2);
    printf("CMMAP %s %d %d\n", name, cm.numLeafs, *checksum);
    int id, cap;
    vec3_t mins, maxs;
    while (fscanf(input, "%d %d %f %f %f %f %f %f", &id, &cap,
                  &mins[0], &mins[1], &mins[2], &maxs[0], &maxs[1], &maxs[2]) == 8) {
        if (id < 0 || cap < 0 || cap > 65536) exit(3);
        for (int axis = 0; axis < 3; axis++)
            if (!isfinite(mins[axis]) || !isfinite(maxs[axis]) || mins[axis] > maxs[axis]) exit(3);
        int *leaves = calloc((size_t)(cap ? cap : 1), sizeof(int));
        if (!leaves) exit(2);
        int last = -777777;
        int count = CM_BoxLeafnums(mins, maxs, leaves, cap, &last);
        printf("CMBOX %d %d %d", id, count, last);
        for (int i = 0; i < count; i++) {
            printf(" %d", leaves[i]);
            if (leaves[i] >= 0 && leaves[i] < cm.numLeafs) seen[leaves[i]] = 1;
        }
        putchar('\n');
        if (last >= 0 && last < cm.numLeafs) seen[last] = 1;
        free(leaves);
    }
    if (!feof(input)) exit(3);
    fclose(input);
    for (int i = 0; i < cm.numLeafs; i++)
        if (seen[i]) printf("CMLEAF %d %d %d\n", i, CM_LeafCluster(i), CM_LeafArea(i));
    free(seen);
    fixtures();
    fflush(stdout);
    exit(0);
}
