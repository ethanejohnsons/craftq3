/* Authored development host. Links unchanged upstream native objects; no engine routines reproduced. */
#include "qcommon/q_shared.h"
#include "botlib/botlib.h"
#include "botlib/be_aas.h"
#include "botlib/aasfile.h"
#include "botlib/be_aas_def.h"
#include "botlib/be_ai_goal.h"
#include "botlib/be_ai_move.h"
#include "BotMoveStateMetadata.h"
extern int BotReachabilityTime(aas_reachability_t *reach);
extern int BotAvoidSpots(vec3_t,aas_reachability_t*,bot_avoidspot_t*,int);
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
static float floorHeight;
static unsigned long traceCount;
static int traceVerbose;
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
static int contents(vec3_t p) { if(traceVerbose)fprintf(stderr,"CONTENTS %.9g %.9g %.9g\n",p[0],p[1],p[2]);return pointContents; }
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
 if(collisionMode==2) {t->allsolid=t->startsolid=1;t->fraction=0;VectorCopy(start,t->endpos);t->contents=CONTENTS_SOLID;t->ent=collisionEntity;return;}
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

extern int Reference_AAS_PointAreaNum(vec3_t);
int AAS_PointAreaNum(vec3_t p){int r=Reference_AAS_PointAreaNum(p);if(traceVerbose)fprintf(stderr,"POINTQUERY %.9g %.9g %.9g -> %d\n",p[0],p[1],p[2],r);return r;}
extern int Reference_AAS_TraceAreas(vec3_t,vec3_t,int*,vec3_t*,int);
int AAS_TraceAreas(vec3_t start,vec3_t end,int *areas,vec3_t *points,int cap){int r=Reference_AAS_TraceAreas(start,end,areas,points,cap);if(traceVerbose){fprintf(stderr,"AREAQUERY %.9g %.9g %.9g -> %.9g %.9g %.9g cap%d :",start[0],start[1],start[2],end[0],end[1],end[2],cap);for(int i=0;i<r;i++)fprintf(stderr," %d",areas[i]);fprintf(stderr,"\n");}return r;}
int main(int argc,char **argv) {
 if(argc!=3&&argc!=4){fprintf(stderr,"usage: probe PK3-or-fixture-root mapname [authored-fixture-overlay]\n");return 2;}root=argv[1];if(argc==4)overlay=argv[3];loadBsp(argv[2]);
 botlib_import_t import={0};import.Print=print;import.Trace=trace;import.EntityTrace=entityTrace;import.PointContents=contents;import.inPVS=pvs;import.BSPEntityData=entityData;import.BSPModelMinsMaxsOrigin=modelBounds;import.BotClientCommand=command;
 import.GetMemory=getMemory;import.FreeMemory=free;import.AvailableMemory=available;import.HunkAlloc=hunk;import.FS_FOpenFile=fsopen;import.FS_Read=fsread;import.FS_Write=fswrite;import.FS_FCloseFile=fsclose;import.FS_Seek=fsseek;
 import.DebugLineCreate=debugLine;import.DebugLineDelete=debugDelete;import.DebugLineShow=debugShow;import.DebugPolygonCreate=debugPolygon;import.DebugPolygonDelete=debugDelete;
 botlib_export_t *api=GetBotLibAPI(BOTLIB_API_VERSION,&import);if(!api)abort();api->BotLibVarSet("maxclients","64");api->BotLibVarSet("maxentities","1024");api->BotLibVarSet("bot_nochat","1");api->BotLibVarSet("log","0");char checksum[32];snprintf(checksum,sizeof(checksum),"%d",(int)Com_BlockChecksum(bsp,bspLength));api->BotLibVarSet("sv_mapChecksum",checksum);
 int setup=api->BotLibSetup();if(setup){fprintf(stderr,"SETUP FAILED %d\n",setup);return 1;}int loaded=api->BotLibLoadMap(argv[2]);if(loaded){fprintf(stderr,"LOAD FAILED %d\n",loaded);return 1;}
 for(int i=0;i<1000&&!api->aas.AAS_Initialized();i++)api->BotLibStartFrame(i*0.1f);
 printf("READY %d %s\n",api->aas.AAS_Initialized(),argv[2]);fflush(stdout);if(!api->aas.AAS_Initialized())return 1;
 int mh=api->ai.BotAllocMoveState(); bot_movestate_t *ms=BotMoveStateFromHandle(mh);
 char line[4096],name[32];vec3_t p,lo,hi,out;int area,presence,pass,goal,flags,lastGoal,lastArea,avoidReach,avoidTries;float avoidTime;
 while(fgets(line,sizeof(line),stdin)) {
  traceCount=0;
  if(!strncmp(line,"contact ",8)){bot_initmove_t in={0};if(sscanf(line,"contact %f %f %f %f %f %f",&in.origin[0],&in.origin[1],&in.origin[2],&in.velocity[0],&in.velocity[1],&in.velocity[2])!=6)abort();api->ai.BotResetMoveState(mh);in.entitynum=in.client=1;in.thinktime=.1f;in.presencetype=2;api->ai.BotInitMoveState(mh,&in);api->ea.EA_ResetInput(1);api->ea.EA_ResetInput(1);bot_goal_t g={0};bot_moveresult_t r={0};api->ai.BotMoveToGoal(&r,mh,&g,0);printf("CONTACT %d %d %d",ms->moveflags,ms->lastareanum,ms->lastreachnum);puts("");continue;}
  if(!strncmp(line,"halfworld ",10)){static aas_node_t n[2];static aas_plane_t p[2];float d;int a,b;if(sscanf(line,"halfworld %f %d %d",&d,&a,&b)!=3||a<1||a>=aasworld.numareas||b<1||b>=aasworld.numareas)abort();memset(n,0,sizeof(n));memset(p,0,sizeof(p));p[0].normal[0]=1;p[0].dist=d;p[1].normal[0]=-1;p[1].dist=-d;n[1].children[0]=-a;n[1].children[1]=-b;aasworld.nodes=n;aasworld.numnodes=2;aasworld.planes=p;aasworld.numplanes=2;puts("HALFWORLD");continue;}
  if(!strncmp(line,"reaches ",8)){int area;if(sscanf(line,"reaches %d",&area)!=1||area<1||area>=aasworld.numareas)abort();aas_areasettings_t *as=&aasworld.areasettings[area];printf("REACHES %d contents%d",area,as->contents);for(int i=as->firstreachablearea;i<as->firstreachablearea+as->numreachableareas;i++){aas_reachability_t *r=&aasworld.reachability[i];printf(" [%d:%d:%d:start%.9g,%.9g,%.9g:end%.9g,%.9g,%.9g]",i,r->areanum,r->traveltype,r->start[0],r->start[1],r->start[2],r->end[0],r->end[1],r->end[2]);}puts("");continue;}
  if(!strncmp(line,"patcharea ",10)){int a,c,n,f;if(sscanf(line,"patcharea %d %d %d %d",&a,&c,&n,&f)!=4||a<1||a>=aasworld.numareas||n<0||f<0||f>aasworld.reachabilitysize-n)abort();aasworld.areasettings[a].contents=c;aasworld.areasettings[a].numreachableareas=n;aasworld.areasettings[a].firstreachablearea=f;puts("PATCHAREA");continue;}
  if(!strncmp(line,"reachdata ",10)){int n,a,t;vec3_t st,en;if(sscanf(line,"reachdata %d %d %d %f %f %f %f %f %f",&n,&a,&t,&st[0],&st[1],&st[2],&en[0],&en[1],&en[2])!=9||n<1||n>=aasworld.reachabilitysize||a<0||a>=aasworld.numareas)abort();aas_reachability_t *r=&aasworld.reachability[n];r->areanum=a;r->traveltype=t;VectorCopy(st,r->start);VectorCopy(en,r->end);puts("REACHDATA");continue;}
  if(!strncmp(line,"eareset",7)){api->ea.EA_ResetInput(ms->client);api->ea.EA_ResetInput(ms->client);continue;}
  if(!strncmp(line,"spot ",5)){ms->numavoidspots=1;bot_avoidspot_t *s=&ms->avoidspots[0];if(sscanf(line,"spot %f %f %f %f %d",&s->origin[0],&s->origin[1],&s->origin[2],&s->radius,&s->type)!=5)abort();puts("SPOT");continue;}
  if(sscanf(line,"hitentity %d",&collisionEntity)==1){puts("HITENTITY");continue;}
  if(!strncmp(line,"patchreach ",11)){int n,type;if(sscanf(line,"patchreach %d %d",&n,&type)!=2||n<1||n>=aasworld.reachabilitysize)abort();aasworld.reachability[n].traveltype=type;puts("PATCHREACH");continue;}
  if(!strncmp(line,"timeout ",8)) {int type,stored; if(sscanf(line,"timeout %d %d",&type,&stored)!=2)abort();aas_reachability_t r={0};r.traveltype=type;r.traveltime=stored;printf("TIMEOUT %d\n",BotReachabilityTime(&r));fflush(stdout);continue;}

  if(!strncmp(line,"init ",5)) {
   bot_initmove_t init={0};
   if(sscanf(line,"init %f %f %f %f %f %f %f %f %f %d %d %f %d %f %f %f %d",&init.origin[0],&init.origin[1],&init.origin[2],&init.velocity[0],&init.velocity[1],&init.velocity[2],&init.viewoffset[0],&init.viewoffset[1],&init.viewoffset[2],&init.entitynum,&init.client,&init.thinktime,&init.presencetype,&init.viewangles[0],&init.viewangles[1],&init.viewangles[2],&init.or_moveflags)!=17)abort();
   api->ai.BotInitMoveState(mh,&init);puts("INIT");fflush(stdout);continue;
  }
  if(!strncmp(line,"history ",8)) {
   if(sscanf(line,"history %d %d %d %d %d %d %d %f %f %f %f",&ms->areanum,&ms->lastareanum,&ms->lastgoalareanum,&ms->lastreachnum,&ms->reachareanum,&ms->moveflags,&ms->jumpreach,&ms->reachability_time,&ms->lastorigin[0],&ms->lastorigin[1],&ms->lastorigin[2])!=11)abort();
   puts("HISTORY");fflush(stdout);continue;
  }
  if(!strncmp(line,"avoid ",6)) {
   if(sscanf(line,"avoid %d %f %d",&ms->avoidreach[0],&ms->avoidreachtimes[0],&ms->avoidreachtries[0])!=3)abort();
   puts("AVOID");fflush(stdout);continue;
  }
  if(!strncmp(line,"reset",5)) {api->ai.BotResetMoveState(mh);puts("RESET");fflush(stdout);continue;}
  if(!strncmp(line,"goal ",5)) {
   bot_goal_t g={0};int flags;bot_moveresult_t result;memset(&result,0x7f,sizeof(result));
   if(sscanf(line,"goal %f %f %f %d %d",&g.origin[0],&g.origin[1],&g.origin[2],&g.areanum,&flags)!=5)abort();
   VectorSet(g.mins,-15,-15,-15);VectorSet(g.maxs,15,15,15);g.entitynum=149;g.number=9;g.flags=1;g.iteminfo=11;
   api->ea.EA_ResetInput(ms->client);api->ai.BotMoveToGoal(&result,mh,&g,flags);
   printf("RESULT %d %d %d %d %d %d %d %.9g %.9g %.9g %.9g %.9g %.9g traces=%lu\n",result.failure,result.type,result.blocked,result.blockentity,result.traveltype,result.flags,result.weapon,result.movedir[0],result.movedir[1],result.movedir[2],result.ideal_viewangles[0],result.ideal_viewangles[1],result.ideal_viewangles[2],traceCount);
   printf("STATE %d %d %d %d %d %d %d %.9g %.9g %.9g %.9g avoid=%d,%.9g,%d\n",ms->areanum,ms->lastareanum,ms->lastgoalareanum,ms->lastreachnum,ms->reachareanum,ms->moveflags,ms->jumpreach,ms->reachability_time,ms->lastorigin[0],ms->lastorigin[1],ms->lastorigin[2],ms->avoidreach[0],ms->avoidreachtimes[0],ms->avoidreachtries[0]);
   bot_input_t input;api->ea.EA_GetInput(ms->client,ms->thinktime,&input);printf("EA %.9g %.9g %.9g %.9g %d\n",input.dir[0],input.dir[1],input.dir[2],input.speed,input.actionflags);
   fflush(stdout);continue;
  }

  if(sscanf(line,"%31s %f %f %f %f %f %f %f %f %f",name,&p[0],&p[1],&p[2],&lo[0],&lo[1],&lo[2],&hi[0],&hi[1],&hi[2])==10 && (!strcmp(name,"best")||!strcmp(name,"jump"))) {
   VectorClear(out);area=!strcmp(name,"best")?AAS_BestReachableArea(p,lo,hi,out):AAS_BestReachableFromJumpPadArea(p,lo,hi);
   printf("%s %d %.9g %.9g %.9g traces=%lu\n",!strcmp(name,"best")?"BEST":"JUMP",area,out[0],out[1],out[2],traceCount);
  } else if(sscanf(line,"point %f %f %f",&p[0],&p[1],&p[2])==3)printf("POINT %d\n",api->aas.AAS_PointAreaNum(p));
  else if(sscanf(line,"area %d",&area)==1){aas_areainfo_t info={0};int ok=api->aas.AAS_AreaInfo(area,&info);printf("AREA %d %d %d %d %d %d %.9g %.9g %.9g %.9g %.9g %.9g %.9g %.9g %.9g\n",area,ok,info.contents,info.flags,info.presencetype,info.cluster,info.mins[0],info.mins[1],info.mins[2],info.maxs[0],info.maxs[1],info.maxs[2],info.center[0],info.center[1],info.center[2]);printf("REACH %d\n",api->aas.AAS_AreaReachability(area));}
  else if(sscanf(line,"trace %f %f %f %f %f %f %d %d",&p[0],&p[1],&p[2],&out[0],&out[1],&out[2],&presence,&pass)==8){aas_trace_t t=AAS_TraceClientBBox(p,out,presence,pass);printf("TRACE %d %.9g %.9g %.9g %.9g %d %d %d %d\n",t.startsolid,t.fraction,t.endpos[0],t.endpos[1],t.endpos[2],t.ent,t.lastarea,t.area,t.planenum);}
  else if(sscanf(line,"links %f %f %f %f %f %f %d %d",&lo[0],&lo[1],&lo[2],&hi[0],&hi[1],&hi[2],&pass,&presence)==8) {
   aas_link_t *head=AAS_LinkEntityClientBBox(lo,hi,pass,presence);printf("LINKS");int count=0;for(aas_link_t *p=head;p;p=p->next_area){if(count++>100000)abort();printf(" %d",p->areanum);}puts("");AAS_UnlinkFromAreas(head);
  } else if(sscanf(line,"route %d %f %f %f %d %d",&area,&p[0],&p[1],&p[2],&goal,&flags)==6)printf("ROUTE %d\n",api->aas.AAS_AreaTravelTimeToGoalArea(area,p,goal,flags));
  else if(sscanf(line,"first %d %f %f %f %d %d",&area,&p[0],&p[1],&p[2],&goal,&flags)==6){int time=0,reach=0;int ok=AAS_AreaRouteToGoalArea(area,p,goal,flags,&time,&reach);printf("FIRST %d %d %d\n",ok,time,reach);}
  else if(sscanf(line,"movefirst %d %f %f %f %d %d",&area,&p[0],&p[1],&p[2],&goal,&flags)==6){int avoid[MAX_AVOIDREACH]={0},tries[MAX_AVOIDREACH]={0},result=0;float times[MAX_AVOIDREACH]={0};bot_goal_t g={0};g.areanum=goal;if(goal>0&&goal<aasworld.numareas)VectorCopy(aasworld.areas[goal].center,g.origin);int reach=BotGetReachabilityToGoal(p,area,0,0,avoid,times,tries,&g,flags,NULL,0,&result);printf("MOVEFIRST %d %d\n",reach,result);}
  else if(sscanf(line,"reachable %f %f %f %d",&p[0],&p[1],&p[2],&pass)==4){int result=api->ai.BotReachabilityArea(p,pass);printf("REACHABLE %d traces=%lu\n",result,traceCount);}
  else if(sscanf(line,"movehistory %d %f %f %f %d %d %d %d %d %f %d",&area,&p[0],&p[1],&p[2],&goal,&flags,&lastGoal,&lastArea,&avoidReach,&avoidTime,&avoidTries)==11){int avoid[MAX_AVOIDREACH]={avoidReach},tries[MAX_AVOIDREACH]={avoidTries},result=0;float times[MAX_AVOIDREACH]={avoidTime};bot_goal_t g={0};g.areanum=goal;if(goal>0&&goal<aasworld.numareas)VectorCopy(aasworld.areas[goal].center,g.origin);int reach=BotGetReachabilityToGoal(p,area,lastGoal,lastArea,avoid,times,tries,&g,flags,NULL,0,&result);printf("MOVEHISTORY %d %d %d %.9g %d\n",reach,result,avoid[0],times[0],tries[0]);}
  else if(sscanf(line,"frame %f",&avoidTime)==1){api->BotLibStartFrame(avoidTime);printf("TIME %.9g\n",api->aas.AAS_Time());}
  else if(sscanf(line,"spot %d %f %f %f %f %f %f %f %f %f %f %f %f %f %d",&area,&p[0],&p[1],&p[2],&lo[0],&lo[1],&lo[2],&hi[0],&hi[1],&hi[2],&out[0],&out[1],&out[2],&avoidTime,&flags)==15){aas_reachability_t r={0};r.traveltype=area;VectorCopy(lo,r.start);VectorCopy(hi,r.end);bot_avoidspot_t spot={0};spot.type=flags;spot.radius=avoidTime;VectorCopy(out,spot.origin);printf("SPOT %d\n",BotAvoidSpots(p,&r,&spot,1));}
  else if(sscanf(line,"movespot %d %f %f %f %d %d %f %f %f %f %d",&area,&p[0],&p[1],&p[2],&goal,&flags,&out[0],&out[1],&out[2],&avoidTime,&avoidTries)==11){int avoid[MAX_AVOIDREACH]={0},tries[MAX_AVOIDREACH]={0},result=0;float times[MAX_AVOIDREACH]={0};bot_goal_t g={0};g.areanum=goal;if(goal>0&&goal<aasworld.numareas)VectorCopy(aasworld.areas[goal].center,g.origin);bot_avoidspot_t spot={0};spot.type=avoidTries;spot.radius=avoidTime;VectorCopy(out,spot.origin);int reach=BotGetReachabilityToGoal(p,area,0,0,avoid,times,tries,&g,flags,&spot,1,&result);printf("MOVESPOT %d %d\n",reach,result);}
  else if(sscanf(line,"spots %d %d",&area,&flags)==2){VectorClear(p);aas_reachability_t r={0};r.traveltype=2;r.start[0]=10;r.end[0]=20;bot_avoidspot_t spots[2]={0};spots[0].type=area;spots[1].type=flags;for(int j=0;j<2;j++){spots[j].origin[0]=5;spots[j].radius=2;}printf("SPOTS %d\n",BotAvoidSpots(p,&r,spots,2));}
  else if(!strncmp(line,"moveavoid ",10)){int n=0,count=0;if(sscanf(line,"moveavoid %d %f %f %f %d %d %d %d %d %f %d %d %n",&area,&p[0],&p[1],&p[2],&goal,&flags,&lastGoal,&lastArea,&avoidReach,&avoidTime,&avoidTries,&count,&n)!=12||count<0||count>MAX_AVOIDSPOTS)abort();bot_avoidspot_t spots[MAX_AVOIDSPOTS]={0};for(int j=0;j<count;j++){int used=0;if(sscanf(line+n,"%f %f %f %f %d %n",&spots[j].origin[0],&spots[j].origin[1],&spots[j].origin[2],&spots[j].radius,&spots[j].type,&used)!=5)abort();n+=used;}int avoid[MAX_AVOIDREACH]={avoidReach},tries[MAX_AVOIDREACH]={avoidTries},result=0;float times[MAX_AVOIDREACH]={avoidTime};bot_goal_t g={0};g.areanum=goal;if(goal>0&&goal<aasworld.numareas)VectorCopy(aasworld.areas[goal].center,g.origin);int reach=BotGetReachabilityToGoal(p,area,lastGoal,lastArea,avoid,times,tries,&g,flags,spots,count,&result);printf("MOVEAVOID %d %d\n",reach,result);}
  else if(!strncmp(line,"spotset ",8)){int n=0,count=0;aas_reachability_t r={0};if(sscanf(line,"spotset %d %f %f %f %f %f %f %f %f %f %d %n",&r.traveltype,&p[0],&p[1],&p[2],&r.start[0],&r.start[1],&r.start[2],&r.end[0],&r.end[1],&r.end[2],&count,&n)!=11||count<0||count>MAX_AVOIDSPOTS)abort();bot_avoidspot_t spots[MAX_AVOIDSPOTS]={0};for(int j=0;j<count;j++){int used=0;if(sscanf(line+n,"%f %f %f %f %d %n",&spots[j].origin[0],&spots[j].origin[1],&spots[j].origin[2],&spots[j].radius,&spots[j].type,&used)!=5)abort();n+=used;}printf("SPOTSET %d\n",BotAvoidSpots(p,&r,spots,count));}
  else if(sscanf(line,"raw %d %f %f %f %f %f %f",&presence,&p[0],&p[1],&p[2],&out[0],&out[1],&out[2])==7){if(presence<1||presence>1024)abort();int areas[1024];vec3_t points[1024];int n=AAS_TraceAreas(p,out,areas,points,presence);printf("RAW %d",n);for(int i=0;i<n;i++)printf(" %d@%.9g,%.9g,%.9g",areas[i],points[i][0],points[i][1],points[i][2]);puts("");}
  else if(sscanf(line,"rawhalf %d %f %d %d %f %f %f %f %f %f %d",&area,&avoidTime,&goal,&flags,&p[0],&p[1],&p[2],&out[0],&out[1],&out[2],&presence)==11){if(area<0||area>2||goal<0||goal>2||flags<0||flags>2||presence<1||presence>1024)abort();aas_t saved=aasworld;aas_node_t nodes[2]={0};aas_plane_t planes[2]={0};planes[0].normal[area]=1;planes[0].dist=avoidTime;planes[0].type=area;planes[1].normal[area]=-1;planes[1].dist=-avoidTime;planes[1].type=area;nodes[1].children[0]=-goal;nodes[1].children[1]=-flags;aasworld.nodes=nodes;aasworld.numnodes=2;aasworld.planes=planes;aasworld.numplanes=2;int areas[1024];vec3_t points[1024];int n=AAS_TraceAreas(p,out,areas,points,presence);aasworld=saved;printf("RAW %d",n);for(int i=0;i<n;i++)printf(" %d@%.9g,%.9g,%.9g",areas[i],points[i][0],points[i][1],points[i][2]);puts("");}
  else if(sscanf(line,"traceverbose %d",&traceVerbose)==1){}
  else if(sscanf(line,"entity %d %f %f %f %f %f %f %f %f %f %d %d %d",&pass,&p[0],&p[1],&p[2],&lo[0],&lo[1],&lo[2],&hi[0],&hi[1],&hi[2],&presence,&area,&flags)==13){bot_entitystate_t e={0};VectorCopy(p,e.origin);VectorCopy(p,e.old_origin);VectorCopy(lo,e.mins);VectorCopy(hi,e.maxs);e.solid=presence;e.modelindex=area;e.flags=flags;printf("ENTITY %d\n",api->BotLibUpdateEntity(pass,&e));}
  else if(sscanf(line,"unentity %d",&pass)==1)printf("ENTITY %d\n",api->BotLibUpdateEntity(pass,NULL));
  else if(sscanf(line,"cost %d %f %f %f %f %f %f",&area,&p[0],&p[1],&p[2],&out[0],&out[1],&out[2])==7)printf("COST %u\n",AAS_AreaTravelTime(area,p,out));
  else if(sscanf(line,"enable %d %d",&area,&flags)==2)printf("ENABLED %d\n",api->aas.AAS_EnableRoutingArea(area,flags));
  else if(sscanf(line,"areas %f %f %f %f %f %f",&p[0],&p[1],&p[2],&out[0],&out[1],&out[2])==6){int a[4096];vec3_t v[4096];int n=api->aas.AAS_TraceAreas(p,out,a,v,4096);printf("AREAS");for(int i=0;i<n;i++)printf(" %d",a[i]);puts("");}
  else if(sscanf(line,"bbox %d",&presence)==1){api->aas.AAS_PresenceTypeBoundingBox(presence,lo,hi);printf("BBOX %.9g %.9g %.9g %.9g %.9g %.9g\n",lo[0],lo[1],lo[2],hi[0],hi[1],hi[2]);}
  else if(sscanf(line,"floor %f",&floorHeight)==1)collisionMode=1;
  else if(sscanf(line,"contents %d",&pointContents)==1){}
  else if(!strncmp(line,"caches",6)){int n=0,a=0;sscanf(line,"caches %d",&a);for(aas_routingcache_t *c=aasworld.oldestcache;c;c=c->time_next){if(n++>100000)abort();printf("CACHE %d %d %d %.9g",c->type,c->cluster,c->areanum,c->starttraveltime);if(a>0&&a<aasworld.numareas&&c->type==1){aas_areasettings_t *st=&aasworld.areasettings[a];int idx=-1;if(st->cluster==c->cluster)idx=st->clusterareanum;else if(st->cluster<0){aas_portal_t *p=&aasworld.portals[-st->cluster];if(p->frontcluster==c->cluster)idx=p->clusterareanum[0];if(p->backcluster==c->cluster)idx=p->clusterareanum[1];}if(idx>=0&&idx<aasworld.clusters[c->cluster].numreachabilityareas)printf(" area=%d time=%u reach=%u",a,c->traveltimes[idx],c->reachabilities[idx]);}puts("");}}
  else if(!strncmp(line,"clear",5))collisionMode=0;
  else if(!strncmp(line,"solid",5))collisionMode=2;
  else {fprintf(stderr,"UNKNOWN %s",line);return 2;}
  fflush(stdout);
 }
 api->BotLibShutdown();for(int i=0;i<hunkCount;i++)free(hunks[i]);free(entities);free(bsp);return 0;
}
