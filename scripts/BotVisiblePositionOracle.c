/* Authored development host. Links unchanged upstream native objects; no engine routines reproduced. */
#include "qcommon/q_shared.h"
#include "botlib/botlib.h"
#include "botlib/be_aas.h"
#include "botlib/aasfile.h"
#include "botlib/be_aas_def.h"
#include "botlib/be_ai_goal.h"
#include "botlib/be_ai_move.h"
#include "BotMoveStateMetadata.h"
extern int BotGetReachabilityToGoal(vec3_t,int,int,int,int*,float*,int*,bot_goal_t*,int,bot_avoidspot_t*,int,int*);
#define AASINTERN
#include "botlib/be_aas_sample.h"
extern unsigned short AAS_AreaTravelTime(int,vec3_t,vec3_t);
extern int AAS_AreaRouteToGoalArea(int,vec3_t,int,int,int*,int*);
#include <archive.h>
#include <archive_entry.h>
#include <stdio.h>
#include <stdlib.h>
#include <stdarg.h>
#include <math.h>
#include <stdint.h>
#define abort() do { fprintf(stderr,"HOST ABORT %s:%d\n",__func__,__LINE__); __builtin_trap(); } while(0)
extern unsigned Com_BlockChecksum(const void*,int);
extern int AAS_BestReachableArea(vec3_t,vec3_t,vec3_t,vec3_t);
extern int AAS_BestReachableFromJumpPadArea(vec3_t,vec3_t,vec3_t);
static const char *root;
static const char *overlay;
static FILE *files[128];
static unsigned char *bsp;
static int bspLength;
static char *entities;
static int collisionMode, pointContents;
static int collisionEntity=ENTITYNUM_WORLD;
static int splitUpperReach;
static float floorHeight;
static unsigned long traceCount;
static uint32_t traceHash;
static int dumpTraces, pvsResult=1, pvsCount, clearAt;
static float responseFraction=1;
static int responseStartSolid,responseAllSolid,responseEntity;
static void *hunks[262144];
static int hunkCount;
void Com_Error(int level,const char *fmt,...) { (void)level;va_list v;va_start(v,fmt);vfprintf(stderr,fmt,v);va_end(v);abort(); }
void Com_Printf(const char *fmt,...) {va_list v;va_start(v,fmt);vfprintf(stderr,fmt,v);va_end(v);}
char *Cvar_VariableString(const char *name) { (void)name;return ""; }
char *FS_BuildOSPath(const char *base,const char *game,const char *path) { (void)base;(void)game;(void)path;return "/nonexistent/craftq3-oracle-disabled"; }
static void print(int type,char *fmt,...) { (void)type;va_list v;va_start(v,fmt);vfprintf(stderr,fmt,v);va_end(v); }
static int fsopen(const char *path,fileHandle_t *handle,fsMode_t mode) {
 if(mode!=FS_READ) { for(int i=1;i<128;i++)if(!files[i]){ files[i]=tmpfile(); *handle=i; return 0; } abort(); }char actual[4096];snprintf(actual,sizeof(actual),"%s/%s",root,path);
 FILE *file=NULL;
 if(overlay) {char pathbuf[4096];snprintf(pathbuf,sizeof(pathbuf),"%s/%s",overlay,path);file=fopen(pathbuf,"rb");}
 if(!file && strstr(root,".pk3")) {
  struct archive *zip=archive_read_new();archive_read_support_format_zip(zip);archive_read_support_filter_all(zip);
  if(archive_read_open_filename(zip,root,10240)!=ARCHIVE_OK)abort();
  struct archive_entry *entry;
  while(archive_read_next_header(zip,&entry)==ARCHIVE_OK) {
   if(!strcmp(archive_entry_pathname(entry),path)) {
    file=tmpfile();if(!file)abort();char buffer[8192];long n;
    while((n=archive_read_data(zip,buffer,sizeof(buffer)))>0)fwrite(buffer,1,n,file);
    if(n<0)abort();fflush(file);break;
   }
   archive_read_data_skip(zip);
  }
  archive_read_free(zip);
 } else if(!file) file=fopen(actual,"rb");
 if(!file){*handle=0;return -1;}
 fseek(file,0,SEEK_END);long n=ftell(file);rewind(file);
 for(int i=1;i<128;i++)if(!files[i]){files[i]=file;*handle=i;return (int)n;}
 abort();
}
static int fsread(void *buffer,int n,fileHandle_t h){return (int)fread(buffer,1,n,files[h]);}
static void fsclose(fileHandle_t h){fclose(files[h]);files[h]=NULL;}

static int fswrite(const void *p,int n,fileHandle_t h) { return (int)fwrite(p,1,n,files[h]); }
static int fsseek(fileHandle_t h,long offset,int origin) { return fseek(files[h],offset,origin==FS_SEEK_SET?SEEK_SET:origin==FS_SEEK_CUR?SEEK_CUR:SEEK_END); }
static void *getMemory(int n) { if(n<0)abort();void *p=malloc(n?n:1);if(!p)abort();return p; }
static void *hunk(int n) { if(hunkCount==262144)abort();return hunks[hunkCount++]=getMemory(n); }
static int available(void) { return 1024*1024*1024; }
static int contents(vec3_t p) { (void)p;return pointContents; }
static int pvs(vec3_t a,vec3_t b) {
 pvsCount++;if(dumpTraces)fprintf(stderr,"PVS_CALL %.9g %.9g %.9g %.9g %.9g %.9g\n",a[0],a[1],a[2],b[0],b[1],b[2]);return pvsResult;
}
static char *entityData(void) {
 static char *changed;const char *value=getenv("CQ3_PROBE_BOBCLASS");if(!value)return entities;if(changed)return changed;
 size_t len=strlen(value);if(len>64||strchr(value,'"')||strchr(value,'\n'))abort();
 const char *old="func_bobbing";size_t count=0;for(const char *p=entities;(p=strstr(p,old));p+=strlen(old))count++;
 changed=malloc(strlen(entities)+count*len+1);if(!changed)abort();char *out=changed;const char *p=entities,*next;
 while((next=strstr(p,old))){size_t n=(size_t)(next-p);memcpy(out,p,n);out+=n;memcpy(out,value,len);out+=len;p=next+strlen(old);}strcpy(out,p);return changed;
}
static int u32(int offset) { if(offset<0||offset>bspLength-4)abort();return (int)((uint32_t)bsp[offset]|(uint32_t)bsp[offset+1]<<8|(uint32_t)bsp[offset+2]<<16|(uint32_t)bsp[offset+3]<<24); }
static float f32(int offset) { uint32_t n=(uint32_t)u32(offset);float value;memcpy(&value,&n,4);return value; }
static void modelBounds(int model,vec3_t angles,vec3_t mins,vec3_t maxs,vec3_t origin) {
 int offset=u32(8+7*8),size=u32(12+7*8); if(model<0||model>=size/40)abort();offset+=model*40;
 vec3_t low,high;for(int i=0;i<3;i++){low[i]=f32(offset+i*4);high[i]=f32(offset+12+i*4);origin[i]=0;mins[i]=1e30f;maxs[i]=-1e30f;}
 vec3_t axes[3];AnglesToAxis(angles,axes);
 for(int bits=0;bits<8;bits++) {vec3_t p;for(int i=0;i<3;i++)p[i]=(bits&(1<<i))?high[i]:low[i];
  for(int j=0;j<3;j++){float v=0;for(int i=0;i<3;i++)v+=p[i]*axes[i][j];if(v<mins[j])mins[j]=v;if(v>maxs[j])maxs[j]=v;}}
}
static void trace(bsp_trace_t *t,vec3_t start,vec3_t mins,vec3_t maxs,vec3_t end,int pass,int mask) {
 for(int group=0;group<4;group++)for(int axis=0;axis<3;axis++) {
  float value=group==0?start[axis]:group==1?(mins?mins[axis]:0):group==2?(maxs?maxs[axis]:0):end[axis];
  uint32_t bits;memcpy(&bits,&value,4);traceHash=traceHash*31+bits;
 }
 traceHash=traceHash*31+(uint32_t)pass;traceHash=traceHash*31+(uint32_t)mask;
 if(dumpTraces)fprintf(stderr,"TRACE_CALL %.9g %.9g %.9g %.9g %.9g %.9g %.9g %.9g %.9g %.9g %.9g %.9g %d %d\n",start[0],start[1],start[2],mins?mins[0]:0,mins?mins[1]:0,mins?mins[2]:0,maxs?maxs[0]:0,maxs?maxs[1]:0,maxs?maxs[2]:0,end[0],end[1],end[2],pass,mask);traceCount++;memset(t,0,sizeof(*t));t->fraction=1;t->ent=ENTITYNUM_NONE;VectorCopy(end,t->endpos);
 if(collisionMode==4) {t->fraction=responseFraction;t->startsolid=responseStartSolid;t->allsolid=responseAllSolid;t->ent=responseEntity;for(int j=0;j<3;j++)t->endpos[j]=start[j]+responseFraction*(end[j]-start[j]);return;}
 if(collisionMode==2 || (collisionMode==3 && traceCount!=(unsigned long)clearAt)) {t->allsolid=t->startsolid=1;t->fraction=0;VectorCopy(start,t->endpos);t->contents=CONTENTS_SOLID;t->ent=collisionEntity;return;}
 if(collisionMode!=1 || !(mask&CONTENTS_SOLID))return;
 float a=start[2]+(mins?mins[2]:0)-floorHeight,b=end[2]+(mins?mins[2]:0)-floorHeight;
 if(a<0){t->startsolid=1;t->allsolid=b<0;if(t->allsolid){t->fraction=0;VectorCopy(start,t->endpos);}}
 else if(b<0){t->fraction=a/(a-b);for(int i=0;i<3;i++)t->endpos[i]=start[i]+t->fraction*(end[i]-start[i]);}
 if(t->fraction<1||t->startsolid){t->contents=CONTENTS_SOLID;t->ent=collisionEntity;t->plane.normal[2]=1;t->plane.dist=floorHeight;t->plane.type=2;}
}
static void entityTrace(bsp_trace_t *t,vec3_t s,vec3_t lo,vec3_t hi,vec3_t e,int entity,int mask){trace(t,s,lo,hi,e,entity,mask);}
static void command(int client,char *s){fprintf(stderr,"CLIENT %d %s\n",client,s);}
static int debugLine(void){return 0;} static void debugDelete(int id){(void)id;}
static void debugShow(int id,vec3_t a,vec3_t b,int color){(void)id;(void)a;(void)b;(void)color;}
static int debugPolygon(int color,int count,vec3_t *points){(void)color;(void)count;(void)points;return 0;}
static void loadBsp(const char *map) {
 char path[1024];snprintf(path,sizeof(path),"maps/%s.bsp",map);fileHandle_t h;
 bspLength=fsopen(path,&h,FS_READ);if(bspLength<144||bspLength>256*1024*1024)abort();bsp=getMemory(bspLength);if(fsread(bsp,bspLength,h)!=bspLength)abort();fsclose(h);
 if(memcmp(bsp,"IBSP",4)||u32(4)!=46)abort();int off=u32(8),len=u32(12);if(off<0||len<0||off>bspLength-len)abort();entities=getMemory(len+1);memcpy(entities,bsp+off,len);entities[len]=0;
}
extern int AAS_NextAreaReachability(int,int);
extern int AAS_AreaTravelTimeToGoalArea(int,vec3_t,int,int);
int ObservedNextAreaReachability(int area,int previous) {
 int result=AAS_NextAreaReachability(area,previous);
 if(dumpTraces)fprintf(stderr,"NEXT_CALL %d %d %d\n",area,previous,result);return result;
}
int ObservedTravelTime(int area,vec3_t origin,int goal,int flags) {
 int result=AAS_AreaTravelTimeToGoalArea(area,origin,goal,flags);
 if(dumpTraces)fprintf(stderr,"TIME_CALL %d %.9g %.9g %.9g %d %d %d\n",area,origin[0],origin[1],origin[2],goal,flags,result);return result;
}
int main(int argc,char **argv) {
 if(argc!=3&&argc!=4){fprintf(stderr,"usage: probe PK3-or-fixture-root mapname [authored-fixture-overlay]\n");return 2;}root=argv[1];if(argc==4)overlay=argv[3];loadBsp(argv[2]);
 botlib_import_t import={0};import.Print=print;import.Trace=trace;import.EntityTrace=entityTrace;import.PointContents=contents;import.inPVS=pvs;import.BSPEntityData=entityData;import.BSPModelMinsMaxsOrigin=modelBounds;import.BotClientCommand=command;
 import.GetMemory=getMemory;import.FreeMemory=free;import.AvailableMemory=available;import.HunkAlloc=hunk;import.FS_FOpenFile=fsopen;import.FS_Read=fsread;import.FS_Write=fswrite;import.FS_FCloseFile=fsclose;import.FS_Seek=fsseek;
 import.DebugLineCreate=debugLine;import.DebugLineDelete=debugDelete;import.DebugLineShow=debugShow;import.DebugPolygonCreate=debugPolygon;import.DebugPolygonDelete=debugDelete;
 botlib_export_t *api=GetBotLibAPI(BOTLIB_API_VERSION,&import);if(!api)abort();api->BotLibVarSet("maxclients","64");api->BotLibVarSet("maxentities","1024");api->BotLibVarSet("bot_nochat","1");api->BotLibVarSet("log","0");char checksum[32];snprintf(checksum,sizeof(checksum),"%d",(int)Com_BlockChecksum(bsp,bspLength));api->BotLibVarSet("sv_mapChecksum",checksum);
 int setup=api->BotLibSetup();if(setup){fprintf(stderr,"SETUP FAILED %d\n",setup);return 1;}int loaded=api->BotLibLoadMap(argv[2]);if(loaded){fprintf(stderr,"LOAD FAILED %d\n",loaded);return 1;}
 for(int i=0;i<1000&&!api->aas.AAS_Initialized();i++)api->BotLibStartFrame(i*0.1f);
 printf("READY %d %s\n",api->aas.AAS_Initialized(),argv[2]);fflush(stdout);if(!api->aas.AAS_Initialized())return 1;

 char line[4096];
 while(fgets(line,sizeof(line),stdin)) {
  if(!strncmp(line,"world ",6)) {
   if(sscanf(line,"world %d %f %d",&collisionMode,&floorHeight,&clearAt)!=3)abort();puts("WORLD");
  } else if(!strncmp(line,"response ",9)) {
   if(sscanf(line,"response %f %d %d %d",&responseFraction,&responseStartSolid,&responseAllSolid,&responseEntity)!=4)abort();collisionMode=4;puts("RESPONSE");
  } else if(!strncmp(line,"pvs ",4)) {
   if(sscanf(line,"pvs %d",&pvsResult)!=1)abort();puts("PVS");
  } else if(!strncmp(line,"dump ",5)) {
   if(sscanf(line,"dump %d",&dumpTraces)!=1)abort();puts("DUMP");
  } else if(!strncmp(line,"nullgoal",8)) {
   vec3_t origin={1099,2233,52},target={12345,23456,34567};traceCount=0;traceHash=1;pvsCount=0;
   int result=api->ai.BotPredictVisiblePosition(origin,4,NULL,18616254,target);
   printf("VISIBLE %d %.9g %.9g %.9g %lu %d %u\n",result,target[0],target[1],target[2],traceCount,pvsCount,traceHash);
  } else if(!strncmp(line,"visible ",8)) {
   vec3_t origin,target={12345,23456,34567};int area,flags;bot_goal_t goal={0};
   if(sscanf(line,"visible %f %f %f %d %d %f %f %f %d %d",&origin[0],&origin[1],&origin[2],&area,&goal.areanum,&goal.origin[0],&goal.origin[1],&goal.origin[2],&flags,&goal.entitynum)<9)abort();
   traceCount=0;traceHash=1;pvsCount=0;int result=api->ai.BotPredictVisiblePosition(origin,area,&goal,flags,target);
   printf("VISIBLE %d %.9g %.9g %.9g %lu %d %u\n",result,target[0],target[1],target[2],traceCount,pvsCount,traceHash);
  } else if(!strncmp(line,"areas",5)) {
   for(int n=1;n<aasworld.numareas;n++)if(aasworld.areasettings[n].numreachableareas) {
    aas_area_t *a=&aasworld.areas[n];aas_areasettings_t *s=&aasworld.areasettings[n];
    printf("AREA %d %.9g %.9g %.9g %d %d\n",n,a->center[0],a->center[1],a->center[2],s->firstreachablearea,s->numreachableareas);
   }
  } else if(!strncmp(line,"area ",5)) {
   int area;if(sscanf(line,"area %d",&area)!=1||area<1||area>=aasworld.numareas)abort();
   aas_area_t *a=&aasworld.areas[area];aas_areasettings_t *s=&aasworld.areasettings[area];
   printf("AREA %d %.9g %.9g %.9g %d %d\n",area,a->center[0],a->center[1],a->center[2],s->firstreachablearea,s->numreachableareas);
  } else if(!strncmp(line,"reachinfo ",10)) {
   int n;if(sscanf(line,"reachinfo %d",&n)!=1||n<1||n>=aasworld.reachabilitysize)abort();aas_reachability_t *r=&aasworld.reachability[n];
   printf("REACHINFO %d %d %d %.9g %.9g %.9g %.9g %.9g %.9g\n",n,r->areanum,r->traveltype,r->start[0],r->start[1],r->start[2],r->end[0],r->end[1],r->end[2]);
  } else if(!strncmp(line,"route ",6)) {
   vec3_t origin;int area,goal,flags,t=0,reach=0;
   if(sscanf(line,"route %d %f %f %f %d %d",&area,&origin[0],&origin[1],&origin[2],&goal,&flags)!=6)abort();
   int ok=AAS_AreaRouteToGoalArea(area,origin,goal,flags,&t,&reach);
   printf("ROUTE %d %d %d\n",ok,t,reach);
  } else if(!strncmp(line,"quit",4))break;
  else {fprintf(stderr,"Unknown request %s",line);abort();}
  fflush(stdout);
 }
 api->BotLibShutdown();for(int i=0;i<hunkCount;i++)free(hunks[i]);free(bsp);free(entities);return 0;
}
