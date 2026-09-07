/* Authored development host. Links unchanged upstream native objects; no engine routines reproduced. */
#include "qcommon/q_shared.h"
#include "botlib/botlib.h"
#include "botlib/be_aas.h"
#include "botlib/aasfile.h"
#include "botlib/be_aas_def.h"
#include "botlib/be_ai_goal.h"
#include "botlib/be_ai_move.h"
#include "botlib/be_ea.h"
#include "BotMoveStateMetadata.h"
extern bot_moveresult_t BotMoveInGoalArea(bot_movestate_t*,bot_goal_t*);
extern bot_moveresult_t BotTravel_Swim(bot_movestate_t*,aas_reachability_t*);
extern bot_moveresult_t BotTravel_WaterJump(bot_movestate_t*,aas_reachability_t*);
extern bot_moveresult_t BotFinishTravel_WaterJump(bot_movestate_t*,aas_reachability_t*);
#include <archive.h>
#include <archive_entry.h>
#include <stdio.h>
#include <stdlib.h>
#include <stdarg.h>
#include <math.h>
#include <stdint.h>
#define abort() do { fprintf(stderr,"HOST ABORT %s:%d\n",__func__,__LINE__); __builtin_trap(); } while(0)
extern unsigned Com_BlockChecksum(const void*,int);
static const char *root;
static const char *overlay;
static FILE *files[128];
static unsigned char *bsp;
static int bspLength;
static char *entities;
static int collisionMode, pointContents;
static float floorHeight;
static unsigned long traceCount;
static vec3_t viewOffset, viewAngles;
static int randomValue,randomDraws;
int rand(void) {randomDraws++;return randomValue;}
static int seedMove;static vec3_t seedDirection;static float seedSpeed;
static int traceVerbose;
static int movePresence=2;static int reachAreaOverride=-1;
static float forcedFraction[2];static int forcedEntity[2],forcedSolid[2];
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
static int pvs(vec3_t a,vec3_t b) { (void)a;(void)b;return 1; }
static char *entityData(void) { return entities; }
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
 if(traceVerbose)fprintf(stderr,"BSPTRACE start %.9g %.9g %.9g mins %.9g %.9g %.9g maxs %.9g %.9g %.9g end %.9g %.9g %.9g pass %d mask %d\n",start[0],start[1],start[2],mins[0],mins[1],mins[2],maxs[0],maxs[1],maxs[2],end[0],end[1],end[2],pass,mask);traceCount++;memset(t,0,sizeof(*t));t->fraction=1;t->ent=ENTITYNUM_NONE;VectorCopy(end,t->endpos);
 if(collisionMode==3 && traceCount<=2) {int i=(int)traceCount-1;t->fraction=forcedFraction[i];t->ent=forcedEntity[i];t->startsolid=(forcedSolid[i]&1)!=0;t->allsolid=(forcedSolid[i]&2)!=0;for(int j=0;j<3;j++)t->endpos[j]=start[j]+t->fraction*(end[j]-start[j]);t->plane.normal[2]=1;t->contents=1;return;}
 if(collisionMode==2) {t->allsolid=t->startsolid=1;t->fraction=0;VectorCopy(start,t->endpos);t->contents=CONTENTS_SOLID;t->ent=ENTITYNUM_WORLD;return;}
 if(collisionMode!=1 || !(mask&CONTENTS_SOLID))return;
 float a=start[2]+(mins?mins[2]:0)-floorHeight,b=end[2]+(mins?mins[2]:0)-floorHeight;
 if(a<0){t->startsolid=1;t->allsolid=b<0;if(t->allsolid){t->fraction=0;VectorCopy(start,t->endpos);}}
 else if(b<0){t->fraction=a/(a-b);for(int i=0;i<3;i++)t->endpos[i]=start[i]+t->fraction*(end[i]-start[i]);}
 if(t->fraction<1||t->startsolid){t->contents=CONTENTS_SOLID;t->ent=ENTITYNUM_WORLD;t->plane.normal[2]=1;t->plane.dist=floorHeight;t->plane.type=2;}
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
int main(int argc,char **argv) {
 if(argc!=3&&argc!=4){fprintf(stderr,"usage: probe PK3-or-fixture-root mapname [authored-fixture-overlay]\n");return 2;}root=argv[1];if(argc==4)overlay=argv[3];loadBsp(argv[2]);
 botlib_import_t import={0};import.Print=print;import.Trace=trace;import.EntityTrace=entityTrace;import.PointContents=contents;import.inPVS=pvs;import.BSPEntityData=entityData;import.BSPModelMinsMaxsOrigin=modelBounds;import.BotClientCommand=command;
 import.GetMemory=getMemory;import.FreeMemory=free;import.AvailableMemory=available;import.HunkAlloc=hunk;import.FS_FOpenFile=fsopen;import.FS_Read=fsread;import.FS_Write=fswrite;import.FS_FCloseFile=fsclose;import.FS_Seek=fsseek;
 import.DebugLineCreate=debugLine;import.DebugLineDelete=debugDelete;import.DebugLineShow=debugShow;import.DebugPolygonCreate=debugPolygon;import.DebugPolygonDelete=debugDelete;
 botlib_export_t *api=GetBotLibAPI(BOTLIB_API_VERSION,&import);if(!api)abort();api->BotLibVarSet("maxclients","64");api->BotLibVarSet("maxentities","1024");api->BotLibVarSet("bot_nochat","1");api->BotLibVarSet("log","0");char checksum[32];snprintf(checksum,sizeof(checksum),"%d",(int)Com_BlockChecksum(bsp,bspLength));api->BotLibVarSet("sv_mapChecksum",checksum);
 const char *gravityOverride=getenv("CRAFTQ3_ORACLE_GRAVITY");if(gravityOverride)api->BotLibVarSet("phys_gravity",gravityOverride);
 const char *varName=getenv("CRAFTQ3_ORACLE_VAR"),*varValue=getenv("CRAFTQ3_ORACLE_VALUE");if(varName&&varValue)api->BotLibVarSet(varName,varValue);
 char gravityValue[128];api->BotLibVarGet("phys_gravity",gravityValue,sizeof(gravityValue));fprintf(stderr,"CONFIG_GRAVITY %s\n",gravityValue);
 int setup=api->BotLibSetup();if(setup){fprintf(stderr,"SETUP FAILED %d\n",setup);return 1;}int loaded=api->BotLibLoadMap(argv[2]);if(loaded){fprintf(stderr,"LOAD FAILED %d\n",loaded);return 1;}
 for(int i=0;i<1000&&!api->aas.AAS_Initialized();i++)api->BotLibStartFrame(i*0.1f);
 printf("READY %d %s\n",api->aas.AAS_Initialized(),argv[2]);fflush(stdout);if(!api->aas.AAS_Initialized())return 1;

 char line[4096];
 int area=0, presence=0,seedActions=0;
 while(fgets(line,sizeof(line),stdin)) {
  traceCount=0;
  if(sscanf(line,"random %d",&randomValue)==1) { if(randomValue<0||randomValue>32767)abort(); }
  else if(sscanf(line,"easeed %f %f %f %f",&seedDirection[0],&seedDirection[1],&seedDirection[2],&seedSpeed)==4)seedMove=1;
  else if(sscanf(line,"eaactions %d",&seedActions)==1){}
  else if(sscanf(line,"viewoffset %f %f %f",&viewOffset[0],&viewOffset[1],&viewOffset[2])==3){}
  else if(sscanf(line,"viewangles %f %f %f",&viewAngles[0],&viewAngles[1],&viewAngles[2])==3){}
  else if(sscanf(line,"movepresence %d",&movePresence)==1) { if(movePresence!=2&&movePresence!=4)abort(); }
  else if(sscanf(line,"reacharea %d",&reachAreaOverride)==1){}
  else if(sscanf(line,"traceverbose %d",&traceVerbose)==1){}
  else if(sscanf(line,"tag %d",&area)==1)fprintf(stderr,"TAG %d\n",area);
  else if(sscanf(line,"routing %d",&presence)==1) { if(presence<0)abort();aasworld.areasettings[1].numreachableareas=presence;puts("ROUTING"); }
  else if(sscanf(line,"fixture %d",&presence)==1) {
   static aas_node_t node[2];static aas_plane_t plane[2];static aas_area_t areas[2];static aas_areasettings_t settings[2];
   if(presence!=2&&presence!=4&&presence!=6)abort();node[1].children[0]=node[1].children[1]=-1;
   plane[0].normal[0]=1;plane[1].normal[0]=-1;settings[1].presencetype=presence;settings[1].areaflags=1;
   aasworld.nodes=node;aasworld.numnodes=2;aasworld.planes=plane;aasworld.numplanes=2;aasworld.areas=areas;aasworld.numareas=2;aasworld.areasettings=settings;aasworld.numareasettings=2;puts("FIXTURE");
  }
  else if(!strncmp(line,"travel ",7)||!strncmp(line,"finish ",7)||!strncmp(line,"ingoal ",7)) {
   bot_movestate_t ms={0};aas_reachability_t reach={0};ms.presencetype=movePresence;ms.entitynum=0;ms.client=0;
   if(sscanf(line+7,"%d %f %f %f %f %f %f %f %f %f %f %f %f %f %d",&reach.traveltype,&ms.origin[0],&ms.origin[1],&ms.origin[2],&ms.velocity[0],&ms.velocity[1],&ms.velocity[2],&reach.start[0],&reach.start[1],&reach.start[2],&reach.end[0],&reach.end[1],&reach.end[2],&ms.thinktime,&ms.moveflags)!=15)abort();
   if((reach.traveltype!=8&&reach.traveltype!=9)||(!strncmp(line,"finish ",7)&&reach.traveltype!=9))abort();
   VectorCopy(viewOffset,ms.viewoffset);VectorCopy(viewAngles,ms.viewangles);
   ms.areanum=api->aas.AAS_PointAreaNum(ms.origin);reach.areanum=reachAreaOverride>=0?reachAreaOverride:api->aas.AAS_PointAreaNum(reach.end);
   EA_ResetInput(0);EA_ResetInput(0);if(seedMove)EA_Move(0,seedDirection,seedSpeed);EA_Action(0,seedActions);randomDraws=0;
   bot_goal_t goal={0};goal.areanum=reach.areanum;VectorCopy(reach.end,goal.origin);
   bot_moveresult_t result=!strncmp(line,"ingoal ",7)?BotMoveInGoalArea(&ms,&goal):!strncmp(line,"finish ",7)?BotFinishTravel_WaterJump(&ms,&reach):reach.traveltype==8?BotTravel_Swim(&ms,&reach):BotTravel_WaterJump(&ms,&reach);
   bot_input_t in={0};EA_GetInput(0,ms.thinktime,&in);
   printf("TRAVEL %d %d %d %d %d %d %d %.9g %.9g %.9g %.9g %.9g %.9g EA %.9g %.9g %.9g %.9g %d STATE %d %d traces=%lu\n",result.failure,result.type,result.blocked,result.blockentity,result.traveltype,result.flags,result.weapon,result.movedir[0],result.movedir[1],result.movedir[2],result.ideal_viewangles[0],result.ideal_viewangles[1],result.ideal_viewangles[2],in.dir[0],in.dir[1],in.dir[2],in.speed,in.actionflags,ms.moveflags,ms.jumpreach,traceCount);
   printf("DRAWS %d\n",randomDraws);
  }
  else if(sscanf(line,"force %f %d %d %f %d %d",&forcedFraction[0],&forcedEntity[0],&forcedSolid[0],&forcedFraction[1],&forcedEntity[1],&forcedSolid[1])==6)collisionMode=3;
  else if(sscanf(line,"floor %f",&floorHeight)==1)collisionMode=1;
  else if(sscanf(line,"contents %d",&pointContents)==1){}
  else if(!strncmp(line,"clear",5))collisionMode=0;
  else if(!strncmp(line,"solid",5))collisionMode=2;
  else {fprintf(stderr,"UNKNOWN %s",line);return 2;}
  fflush(stdout);
 }
 if(aasworld.numnodes==2)return 0;api->BotLibShutdown();for(int i=0;i<hunkCount;i++)free(hunks[i]);free(entities);free(bsp);return 0;
}
