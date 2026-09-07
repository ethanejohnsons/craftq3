/* Authored retained-slot observer: unchanged engine EntityTrace, SV_ClipToEntity and SV_GentityNum; controlled CM callbacks only. */
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
qboolean SV_inPVS(const vec3_t a,const vec3_t b) { abort(); }
int SV_PointContents(const vec3_t point,int pass) { abort(); }
void SV_Trace(trace_t *result,const vec3_t start,vec3_t mins,vec3_t maxs,const vec3_t end,int pass,int mask,int capsule) { abort(); }
char *CM_EntityString(void) { return ""; }
clipHandle_t CM_InlineModel(int index) { return index; }
void CM_ModelBounds(clipHandle_t model,vec3_t mins,vec3_t maxs) { memcpy(mins,modelMins,sizeof(vec3_t));memcpy(maxs,modelMaxs,sizeof(vec3_t)); }


server_t sv;
static int clipCalls,tempCalls;
clipHandle_t CM_TempBoxModel(const vec3_t min,const vec3_t max,int capsule){tempCalls++;printf("TEMP %.9g %.9g %.9g %.9g %.9g %.9g %d\n",min[0],min[1],min[2],max[0],max[1],max[2],capsule);return 17;}
void CM_TransformedBoxTrace(trace_t *result,const vec3_t start,const vec3_t end,vec3_t min,vec3_t max,clipHandle_t model,int mask,const vec3_t origin,const vec3_t angles,int capsule){clipCalls++;printf("CLIP model=%d mask=%d origin=%.9g,%.9g,%.9g angles=%.9g,%.9g,%.9g capsule=%d\n",model,mask,origin[0],origin[1],origin[2],angles[0],angles[1],angles[2],capsule);memset(result,0,sizeof(*result));result->fraction=.25f;result->contents=1;result->entityNum=999;result->plane.normal[2]=1;for(int k=0;k<3;k++)result->endpos[k]=start[k]+.25f*(end[k]-start[k]);}
int main(void){
 SV_BotInitBotLib();if(!imports.EntityTrace)return 2;
 sharedEntity_t *entities=calloc(MAX_GENTITIES,sizeof(*entities));sv.gentities=entities;sv.gentitySize=sizeof(*entities);
 int count,number,contents,linked,bmodel;
 while(scanf("%d %d %d %d %d",&count,&number,&contents,&linked,&bmodel)==5){
  if(count<0||count>MAX_GENTITIES||number<0||number>=MAX_GENTITIES)return 3;
  sv.num_entities=count;memset(entities,0,MAX_GENTITIES*sizeof(*entities));
  sharedEntity_t *entity=entities+number;entity->r.contents=contents;entity->r.linked=linked;entity->r.bmodel=bmodel;entity->s.modelindex=12;
  VectorSet(entity->r.mins,-15,-15,-15);VectorSet(entity->r.maxs,15,15,15);VectorSet(entity->r.currentOrigin,1,2,3);VectorSet(entity->r.currentAngles,4,5,6);
  printf("POINTER %d\n",SV_GentityNum(number)==entity);clipCalls=tempCalls=0;
  vec3_t start={0,0,40},end={0,0,-40},min={-15,-15,-24},max={15,15,8};bsp_trace_t result;memset(&result,0xa5,sizeof(result));
  imports.EntityTrace(&result,start,min,max,end,number,65537);
  printf("RESULT %d %d %.9g %.9g %.9g %.9g %d %d clips=%d temps=%d\n",result.allsolid,result.startsolid,result.fraction,result.endpos[0],result.endpos[1],result.endpos[2],result.ent,result.contents,clipCalls,tempCalls);fflush(stdout);
 }
 free(entities);return 0;
}
