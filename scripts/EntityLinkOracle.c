/* Authored QA host. Public calls and header layout metadata only; no native routine copies. */
#include "qcommon/q_shared.h"
#include "qcommon/qcommon.h"
#include "qcommon/cm_local.h"
#include "server/server.h"
#include <math.h>
#include <stdio.h>
#include <stdlib.h>

extern void ObservedEntityLinkLoadMap(const char *, qboolean, int *);
extern int ObservedEntityBoxLeafnums(const vec3_t, const vec3_t, int *, int, int *);
static int linking;
int CM_BoxLeafnums(const vec3_t mins, const vec3_t maxs, int *list, int capacity, int *last) {
    int count = ObservedEntityBoxLeafnums(mins, maxs, list, capacity, last);
    if (linking) printf("LINKBOX %d %d %d\n", capacity, count, *last);
    return count;
}
void CM_LoadMap(const char *name, qboolean clientload, int *checksum) {
    ObservedEntityLinkLoadMap(name, clientload, checksum);
    const char *path = getenv("CRAFTQ3_ENTITY_LINK_QUERIES");
    if (!path) return;
    const char *mode = getenv("CRAFTQ3_ENTITY_AREA_MODE");
    if (mode && atoi(mode) != 0) {
        int fixture = atoi(mode);
        if (strcmp(name, "maps/q3tourney4.bsp") || fixture < 1 || fixture > 4 || cm.numLeafs <= 631)
            exit(3);
        // Authored metadata-only fixture in private oracle memory; source PK3 is never modified.
        for (int i = 0; i < cm.numLeafs; i++) cm.leafs[i].area = -1;
        if (fixture == 2 || fixture == 4) cm.leafs[615].area = 0;
        if (fixture == 3 || fixture == 4) cm.leafs[631].area = fixture == 4 ? 1 : 0;
    }
    FILE *input = fopen(path, "r");
    if (!input) { perror("Entity-link input"); exit(2); }
    sv.gentitySize = sizeof(sharedEntity_t);
    sv.num_entities = MAX_GENTITIES;
    sv.gentities = calloc(MAX_GENTITIES, sizeof(sharedEntity_t));
    if (!sv.gentities) exit(2);
    SV_ClearWorld();
    printf("ENTITYMAP %s %d\n", name, cm.numLeafs);
    int id; vec3_t lo, hi;
    while (fscanf(input, "%d %f %f %f %f %f %f", &id,
                  &lo[0], &lo[1], &lo[2], &hi[0], &hi[1], &hi[2]) == 7) {
        if (id < 0 || id >= MAX_GENTITIES) exit(3);
        sharedEntity_t *ent = &sv.gentities[id];
        SV_UnlinkEntity(ent);
        memset(ent, 0, sizeof(*ent));
        ent->s.number = id;
        ent->r.ownerNum = ENTITYNUM_NONE;
        ent->r.contents = 1;
        for (int a = 0; a < 3; a++) {
            if (!isfinite(lo[a]) || !isfinite(hi[a]) || hi[a] - lo[a] < 2) exit(3);
            ent->r.mins[a] = lo[a] + 1;
            ent->r.maxs[a] = hi[a] - 1;
        }
        const char *fixturePath = getenv("CRAFTQ3_ENTITY_AREA_ASSIGNMENTS");
        if (fixturePath) {
            int *raw = calloc((size_t)cm.numLeafs, sizeof(int)), last;
            if (!raw) exit(2);
            int count = ObservedEntityBoxLeafnums(lo, hi, raw, cm.numLeafs, &last);
            for (int i = 0; i < cm.numLeafs; i++) cm.leafs[i].area = -1;
            FILE *fixture = fopen(fixturePath, "r");
            if (!fixture) exit(2);
            int position, area;
            while (fscanf(fixture, "%d %d", &position, &area) == 2) {
                if (position < 0 || position >= count || area < 0 || area >= cm.numAreas) exit(3);
                cm.leafs[raw[position]].area = area;
            }
            if (!feof(fixture)) exit(3);
            fclose(fixture);
            free(raw);
        }
        linking = 1;
        SV_LinkEntity(ent);
        linking = 0;
        svEntity_t *record = SV_SvEntityForGentity(ent);
        printf("ENTITYLINK %d %d %d %d %d %.9g %.9g %.9g %.9g %.9g %.9g\n",
               id, record->areanum, record->areanum2, record->numClusters, ent->r.linked,
               ent->r.absmin[0], ent->r.absmin[1], ent->r.absmin[2],
               ent->r.absmax[0], ent->r.absmax[1], ent->r.absmax[2]);
        int leaves[1024], last;
        int count = CM_BoxLeafnums(ent->r.absmin, ent->r.absmax, leaves, 1024, &last);
        printf("TOUCHED %d %d", id, count);
        for (int i = 0; i < count; i++)
            printf(" %d:%d:%d", leaves[i], CM_LeafCluster(leaves[i]), CM_LeafArea(leaves[i]));
        putchar('\n');
        VectorSet(ent->r.absmin, 1000000, 1000000, 1000000);
        VectorSet(ent->r.absmax, 1000002, 1000002, 1000002);
        printf("ENTITYCACHE %d mutated %d %d %d\n", id,
               record->areanum, record->areanum2, ent->r.linked);
        SV_UnlinkEntity(ent);
        printf("ENTITYCACHE %d unlinked %d %d %d\n", id,
               record->areanum, record->areanum2, ent->r.linked);
        ent->r.currentOrigin[0] = 1000000;
        SV_LinkEntity(ent);
        printf("ENTITYCACHE %d relinked %d %d %d\n", id,
               record->areanum, record->areanum2, ent->r.linked);
    }
    if (!feof(input)) exit(3);
    fclose(input);
    fflush(stdout);
    exit(0);
}
