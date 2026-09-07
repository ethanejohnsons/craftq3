/* Authored item-config observer, sharing only the authored filesystem/stub harness. */
#define main weapon_oracle_unused_main
#define LibVarValue default_oracle_LibVarValue
#include "WeaponOracle.c"
#undef main
#undef LibVarValue
#include "botlib/l_libvar.h"
#include "botlib/be_ai_goal.h"
#include "botlib/be_aas.h"

/* Data layout declared by upstream's item metadata, not a translated engine routine. */
typedef struct {
  char classname[32], name[80], model[80];
  int modelindex, type, index;
  float respawntime;
  vec3_t mins, maxs;
  int number;
} observed_item_t;
typedef struct { int count; observed_item_t *items; } observed_config_t;
extern observed_config_t *LoadItemConfig(char *path);
extern observed_config_t *itemconfig;

typedef struct observed_level_s {
  int number, iteminfo, flags; float weight;
  vec3_t origin; int area; vec3_t goalorigin; int entity; float timeout;
  struct observed_level_s *previous, *next;
} observed_level_t;
extern observed_level_t *levelitems;
static struct { int count; char *keys[64], *values[64]; } epairs[8192];
static int entity_count, game_type, placement_mode;
static float now=5;
static aas_entityinfo_t live_entities[1024];
int AAS_NextEntity(int previous) { for(int i=previous+1;i<1024;i++)if(live_entities[i].valid)return i;return 0; }
int AAS_EntityType(int number) { return live_entities[number].type; }
int AAS_EntityModelindex(int number) { return live_entities[number].modelindex; }
void AAS_EntityInfo(int number,aas_entityinfo_t *info) { *info=live_entities[number]; }
libvar_t *botDeveloper;
float LibVarValue(const char *name, const char *value) {
  return !strcmp(name,"g_gametype") ? (float)game_type : default_oracle_LibVarValue(name,value);
}
libvar_t *LibVar(const char *name, const char *value) {
  static libvar_t vars[64]; static int count;
  if(count == 64) abort(); libvar_t *v=&vars[count++];
  v->name=(char*)name;v->string=(char*)value;v->value=LibVarValue(name,value);return v;
}
int AAS_Loaded(void) { return 1; }
int AAS_NextBSPEntity(int ent) { return ent < entity_count ? ent+1 : 0; }
int AAS_ValueForBSPEpairKey(int ent,char *key,char *value,int size) {
  if(ent<1 || ent>entity_count) return 0;
  for(int i=0;i<epairs[ent-1].count;i++) if(!strcmp(key,epairs[ent-1].keys[i])) {
    Q_strncpyz(value,epairs[ent-1].values[i],size);return 1;
  }
  if(size>0)value[0]=0;return 0;
}
int AAS_VectorForBSPEpairKey(int ent,char *key,vec3_t value) {
  char text[4096];memset(value,0,sizeof(vec3_t));
  if(!AAS_ValueForBSPEpairKey(ent,key,text,sizeof(text)))return 0;
  return sscanf(text,"%f %f %f",&value[0],&value[1],&value[2])==3;
}
int AAS_IntForBSPEpairKey(int ent,char *key,int *value) {
  char text[4096];int exists=AAS_ValueForBSPEpairKey(ent,key,text,sizeof(text));*value=atoi(text);return exists;
}
int AAS_FloatForBSPEpairKey(int ent,char *key,float *value) {
  char text[4096];int exists=AAS_ValueForBSPEpairKey(ent,key,text,sizeof(text));*value=atof(text);return exists;
}
int AAS_DropToFloor(vec3_t origin,vec3_t mins,vec3_t maxs) { if(placement_mode&1)return 0;origin[2]-=3;return 1; }
int AAS_BestReachableArea(vec3_t origin,vec3_t mins,vec3_t maxs,vec3_t goal) {
  memcpy(goal,origin,sizeof(vec3_t));goal[2]+=0.5f;return placement_mode&2?0:7;
}
int AAS_PointAreaNum(vec3_t origin) { return 2; }
int AAS_AreaJumpPad(int area) { return area==9; }
float AAS_Time(void) { return now; }
int AAS_PointContents(vec3_t origin) { return placement_mode&8?32:0; }
int AAS_BestReachableFromJumpPadArea(vec3_t origin,vec3_t mins,vec3_t maxs) { return placement_mode&4?0:9; }
bsp_trace_t AAS_Trace(vec3_t start,vec3_t mins,vec3_t maxs,vec3_t end,int passent,int mask) {
  bsp_trace_t trace;memset(&trace,0,sizeof(trace));trace.fraction=1;trace.startsolid=(placement_mode&16)!=0;memcpy(trace.endpos,end,sizeof(vec3_t));return trace;
}
void AAS_PresenceTypeBoundingBox(int presence,vec3_t mins,vec3_t maxs) {
  mins[0]=mins[1]=-15;mins[2]=-24;maxs[0]=maxs[1]=15;maxs[2]=32;
}
static char *readhex(void) {
  char text[8193];if(scanf("%8192s",text)!=1)abort();
  if(!strcmp(text,"-"))return strdup("");size_t n=strlen(text);if(n&1)abort();
  char *result=calloc(1,n/2+1);
  for(size_t i=0;i<n;i+=2){unsigned value;if(sscanf(text+i,"%2x",&value)!=1)abort();result[i/2]=(char)value;}
  return result;
}

#ifndef ITEM_ORACLE_ENTRY
#define ITEM_ORACLE_ENTRY main
#endif
int ITEM_ORACLE_ENTRY(int argc, char **argv) {
  if (argc < 3 || argc > 5) return 2;
  root = argv[1];
  botimport.Print = print; botimport.FS_FOpenFile = fsopen;
  botimport.FS_Read = fsread; botimport.FS_FCloseFile = fsclose;
  if(argc>=4) {
    placement_mode=atoi(argv[3]);
    if(scanf("%d %d",&game_type,&entity_count)!=2 || entity_count<0 || entity_count>8192)return 2;
    for(int ent=0;ent<entity_count;ent++) {
      if(scanf("%d",&epairs[ent].count)!=1 || epairs[ent].count<0 || epairs[ent].count>64)return 2;
      for(int k=0;k<epairs[ent].count;k++){epairs[ent].keys[k]=readhex();epairs[ent].values[k]=readhex();}
    }
    if(BotSetupGoalAI())return 3;
    BotInitLevelItems();
    if(argc==5) {
      int count,step=0;while(scanf("%d",&count)==1) {
      if(count<0 || count>1023)return 2;
      now=5+(float)step++;
      memset(live_entities,0,sizeof(live_entities));
      for(int i=0;i<count;i++) {
        int number;if(scanf("%d",&number)!=1 || number<1 || number>=1024)return 2;
        aas_entityinfo_t *entity=&live_entities[number];entity->valid=1;entity->number=number;
        if(scanf("%d %d %d %f %f %f",&entity->type,&entity->flags,&entity->modelindex,&entity->origin[0],&entity->origin[1],&entity->origin[2])!=6)return 2;
        entity->ltime=5;entity->update_time=.05f;memcpy(entity->old_origin,entity->origin,sizeof(vec3_t));memcpy(entity->lastvisorigin,entity->origin,sizeof(vec3_t));
        for(int a=0;a<3;a++){entity->mins[a]=-15;entity->maxs[a]=15;}
      }
      BotUpdateEntityItems();
      }
    }
    int state=BotAllocGoalState(0);
    for(observed_level_t *item=levelitems;item;item=item->next) {
      const unsigned char *bytes=(const unsigned char*)item;
      fputs("LEVEL ",stdout);for(int i=0;i<52;i++)printf("%02x",bytes[i]);putchar('\n');
      BotSetAvoidGoalTime(state,item->number,-1);
      printf("AVOID %d %.9g\n",item->number,BotAvoidGoalTime(state,item->number));
    }
    for(int i=0;i<itemconfig->count;i++) {
      int cursor=-1;
      for(int count=0;count<256;count++) {
        bot_goal_t goal;memset(&goal,0,sizeof(goal));
        char search[80];Q_strncpyz(search,itemconfig->items[i].name,sizeof(search));
        if(placement_mode&128)for(int k=0;search[k];k++)if(search[k]>='a'&&search[k]<='z')search[k]-=32;
        cursor=BotGetLevelItemGoal(cursor,search,&goal);
        if(cursor<=0)break;
        printf("QUERY %s %d ",itemconfig->items[i].classname,cursor);
        const unsigned char *bytes=(const unsigned char*)&goal;
        for(size_t offset=0;offset<sizeof(goal);offset++)printf("%02x",bytes[offset]);putchar('\n');
      }
    }
    return 0;
  }
  observed_config_t *config = LoadItemConfig(argv[2]);
  if (!config) return 3;
  _Static_assert(sizeof(observed_item_t) == 236, "item metadata observation layout");
  printf("ITEMS %d\n", config->count);
  for (int index = 0; index < config->count; index++) {
    const unsigned char *bytes = (const unsigned char *)&config->items[index];
    for (size_t offset = 0; offset < sizeof(observed_item_t); offset++) printf("%02x", bytes[offset]);
    putchar('\n');
  }
  FreeMemory(config);
  return 0;
}
