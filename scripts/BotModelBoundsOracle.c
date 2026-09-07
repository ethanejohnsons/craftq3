/* Authored host captures the unchanged engine's botlib import and probes supplied model bounds. */
#define main framing_oracle_main
#include "NetchanOracle.c"
#undef main
#include "server/server.h"
#include "botlib/botlib.h"

botlib_export_t *botlib_export;
static botlib_export_t exports;
static botlib_import_t imports;
static vec3_t modelMins,modelMaxs;
serverStatic_t svs;
vm_t *gvm;
cvar_t *com_basegame=&zero,*sv_maxclients=&zero;
botlib_export_t *GetBotLibAPI(int version,botlib_import_t *value) { imports=*value;return &exports; }
int Cvar_VariableIntegerValue(const char *name) { return 0; }
int FS_FOpenFileByMode(const char *path,fileHandle_t *file,fsMode_t mode) { abort(); }
void FS_FCloseFile(fileHandle_t file) { abort(); }
int FS_Read(void *data,int length,fileHandle_t file) { abort(); }
int FS_Write(const void *data,int length,fileHandle_t file) { abort(); }
int FS_Seek(fileHandle_t file,long offset,int origin) { abort(); }
void *Hunk_AllocDebug(int size,ha_pref preference,char *label,char *file,int line) { return calloc(1,(size_t)size); }
qboolean Hunk_CheckMark(void) { return qfalse; }
void *Z_MallocDebug(int size,char *label,char *file,int line) { return calloc(1,(size_t)size); }
void *Z_TagMallocDebug(int size,int tag,char *label,char *file,int line) { return calloc(1,(size_t)size); }
int Z_AvailableMemory(void) { return 1000000; }
intptr_t QDECL VM_Call(vm_t *vm,int call,...) { abort(); }
void SV_ExecuteClientCommand(client_t *client,const char *text,qboolean ok) { abort(); }
sharedEntity_t *SV_GentityNum(int index) { abort(); }
qboolean SV_inPVS(const vec3_t a,const vec3_t b) { abort(); }
int SV_PointContents(const vec3_t point,int pass) { abort(); }
void SV_Trace(trace_t *result,const vec3_t start,vec3_t mins,vec3_t maxs,const vec3_t end,int pass,int mask,int capsule) { abort(); }
void SV_ClipToEntity(trace_t *result,const vec3_t start,const vec3_t mins,const vec3_t maxs,const vec3_t end,int entity,int mask,int capsule) { abort(); }
char *CM_EntityString(void) { return ""; }
clipHandle_t CM_InlineModel(int index) { return index; }
void CM_ModelBounds(clipHandle_t model,vec3_t mins,vec3_t maxs) { memcpy(mins,modelMins,sizeof(vec3_t));memcpy(maxs,modelMaxs,sizeof(vec3_t)); }

int main(void) {
  SV_BotInitBotLib();
  if(!imports.BSPModelMinsMaxsOrigin)return 2;
  vec3_t angles,mins,maxs,origin;
  while(scanf("%f %f %f %f %f %f %f %f %f",modelMins,modelMins+1,modelMins+2,modelMaxs,modelMaxs+1,modelMaxs+2,angles,angles+1,angles+2)==9) {
    memset(mins,0xa5,sizeof(mins));memset(maxs,0xa5,sizeof(maxs));memset(origin,0xa5,sizeof(origin));
    imports.BSPModelMinsMaxsOrigin(1,angles,mins,maxs,origin);
    for(int i=0;i<3;i++)printf("%.9g ",mins[i]);for(int i=0;i<3;i++)printf("%.9g ",maxs[i]);for(int i=0;i<3;i++)printf("%.9g%c",origin[i],i==2?'\n':' ');
    fflush(stdout);
  }
  return 0;
}
