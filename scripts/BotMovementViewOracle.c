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
static int dumpTraces;
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
 if(dumpTraces)fprintf(stderr,"TRACE_CALL %.9g %.9g %.9g %.9g %.9g %.9g %.9g %.9g %.9g %.9g %.9g %.9g %d %d\n",start[0],start[1],start[2],mins?mins[0]:0,mins?mins[1]:0,mins?mins[2]:0,maxs?maxs[0]:0,maxs?maxs[1]:0,maxs?maxs[2]:0,end[0],end[1],end[2],pass,mask);traceCount++;memset(t,0,sizeof(*t));t->fraction=1;t->ent=ENTITYNUM_NONE;VectorCopy(end,t->endpos);
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
extern int BotFuzzyPointReachabilityArea(vec3_t origin);
extern int AAS_NextModelReachability(int,int);
static int syntheticHalf(int axis,float boundary,int reach,vec3_t origin) {
 aas_t saved=aasworld;aas_node_t nodes[2]={0};aas_plane_t planes[2]={0};aas_area_t areas[2]={0};aas_areasettings_t settings[2]={0};aas_reachability_t reaches[2]={0};
 if(axis<0||axis>2)abort();nodes[1].children[0]=-1;planes[0].normal[axis]=1;planes[0].dist=boundary;planes[0].type=axis;
 areas[1].areanum=1;for(int j=0;j<3;j++){areas[1].mins[j]=-100000;areas[1].maxs[j]=100000;}areas[1].mins[axis]=boundary;
 settings[1].presencetype=6;settings[1].numreachableareas=reach;settings[1].firstreachablearea=1;reaches[1].areanum=1;reaches[1].traveltype=2;reaches[1].traveltime=1;
 aasworld.nodes=nodes;aasworld.numnodes=2;aasworld.planes=planes;aasworld.numplanes=2;aasworld.areas=areas;aasworld.numareas=2;aasworld.areasettings=settings;aasworld.numareasettings=2;aasworld.reachability=reaches;aasworld.reachabilitysize=2;
 int result=BotFuzzyPointReachabilityArea(origin);aasworld=saved;return result;
}
static int syntheticBox(vec3_t low,vec3_t high,int reach,vec3_t origin) {
 aas_t saved=aasworld;aas_node_t nodes[7]={0};aas_plane_t planes[6]={0};aas_area_t areas[2]={0};aas_areasettings_t settings[2]={0};aas_reachability_t reaches[2]={0};
 for(int axis=0;axis<3;axis++){int n=1+axis*2;planes[n-1].normal[axis]=planes[n].normal[axis]=1;planes[n-1].dist=low[axis];planes[n].dist=high[axis];planes[n-1].type=planes[n].type=axis;nodes[n].planenum=n-1;nodes[n].children[0]=n+1;nodes[n+1].planenum=n;nodes[n+1].children[1]=axis==2?-1:n+2;}
 areas[1].areanum=1;VectorCopy(low,areas[1].mins);VectorCopy(high,areas[1].maxs);settings[1].presencetype=6;settings[1].numreachableareas=reach;settings[1].firstreachablearea=1;reaches[1].areanum=1;reaches[1].traveltype=2;reaches[1].traveltime=1;
 aasworld.nodes=nodes;aasworld.numnodes=7;aasworld.planes=planes;aasworld.numplanes=6;aasworld.areas=areas;aasworld.numareas=2;aasworld.areasettings=settings;aasworld.numareasettings=2;aasworld.reachability=reaches;aasworld.reachabilitysize=2;
 int result=BotFuzzyPointReachabilityArea(origin);aasworld=saved;return result;
}
static int syntheticSplit(float boundary,vec3_t origin) {
 aas_t saved=aasworld;aas_node_t nodes[2]={0};aas_plane_t planes[2]={0};aas_area_t areas[3]={0};aas_areasettings_t settings[3]={0};aas_reachability_t reaches[2]={0};
 nodes[1].children[0]=-1;nodes[1].children[1]=-2;planes[0].normal[2]=1;planes[0].dist=boundary;planes[0].type=2;planes[1].normal[2]=-1;planes[1].dist=-boundary;planes[1].type=2;
 for(int i=1;i<3;i++){areas[i].areanum=i;for(int j=0;j<3;j++){areas[i].mins[j]=-100000;areas[i].maxs[j]=100000;}settings[i].presencetype=6;}areas[1].mins[2]=boundary;areas[2].maxs[2]=boundary;
 settings[1].numreachableareas=splitUpperReach;settings[1].firstreachablearea=1;settings[2].numreachableareas=1;settings[2].firstreachablearea=1;reaches[1].areanum=2;reaches[1].traveltype=2;reaches[1].traveltime=1;
 aasworld.nodes=nodes;aasworld.numnodes=2;aasworld.planes=planes;aasworld.numplanes=2;aasworld.areas=areas;aasworld.numareas=3;aasworld.areasettings=settings;aasworld.numareasettings=3;aasworld.reachability=reaches;aasworld.reachabilitysize=2;
 int result=BotReachabilityArea(origin,0);aasworld=saved;return result;
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
 char line[4096],name[32];vec3_t p,lo,hi,out;int area,presence,pass,goal,flags;
 int axis,reach;float boundary;
 int moveState=api->ai.BotAllocMoveState();
 while(fgets(line,sizeof(line),stdin)) {
  traceCount=0;
  if(!strncmp(line,"avoid ",6)) {
    bot_movestate_t *state=BotMoveStateFromHandle(moveState);if(sscanf(line,"avoid %d %f %d",&state->avoidreach[0],&state->avoidreachtimes[0],&state->avoidreachtries[0])!=3)abort();puts("AVOID");
  } else if(!strncmp(line,"spot ",5)) {
    bot_movestate_t *state=BotMoveStateFromHandle(moveState);state->numavoidspots=1;bot_avoidspot_t *a=&state->avoidspots[0];if(sscanf(line,"spot %f %f %f %f %d",&a->origin[0],&a->origin[1],&a->origin[2],&a->radius,&a->type)!=5)abort();puts("SPOT");
  } else if(!strncmp(line,"reachcoords ",12)) {
    int n,a,t;vec3_t start,end;if(sscanf(line,"reachcoords %d %d %d %f %f %f %f %f %f",&n,&a,&t,&start[0],&start[1],&start[2],&end[0],&end[1],&end[2])!=9||n<1||n>=aasworld.reachabilitysize)abort();
    aas_reachability_t *r=&aasworld.reachability[n];r->areanum=a;r->traveltype=t;VectorCopy(start,r->start);VectorCopy(end,r->end);puts("REACHCOORDS");
  } else if(!strncmp(line,"history ",8)) {
    bot_movestate_t *state=BotMoveStateFromHandle(moveState);
    if(sscanf(line,"history %d %d %d %d %d",&state->areanum,&state->lastareanum,&state->lastgoalareanum,&state->lastreachnum,&state->reachareanum)!=5)abort();puts("HISTORY");
  } else if(!strncmp(line,"reachinfo ",10)) {
    int n;if(sscanf(line,"reachinfo %d",&n)!=1||n<1||n>=aasworld.reachabilitysize)abort();aas_reachability_t *r=&aasworld.reachability[n];
    printf("REACHINFO %d %d %d %.9g %.9g %.9g %.9g %.9g %.9g\n",n,r->areanum,r->traveltype,r->start[0],r->start[1],r->start[2],r->end[0],r->end[1],r->end[2]);
  } else if(!strncmp(line,"init ",5)) {
    bot_initmove_t init={0};
    if(sscanf(line,"init %f %f %f",&init.origin[0],&init.origin[1],&init.origin[2])!=3)abort();
    init.viewoffset[2]=26;init.client=0;init.entitynum=0;init.thinktime=.1f;init.presencetype=2;init.or_moveflags=2;
    api->ai.BotInitMoveState(moveState,&init);puts("INIT");
  } else if(!strncmp(line,"reset",5)){api->ai.BotResetMoveState(moveState);puts("RESET");}
  else if(!strncmp(line,"view ",5)) {
    bot_goal_t g={0};float ahead;vec3_t target={12345,23456,34567};
    if(sscanf(line,"view %d %f %f %f %d %f",&g.areanum,&g.origin[0],&g.origin[1],&g.origin[2],&flags,&ahead)!=6)abort();
    int ok=api->ai.BotMovementViewTarget(moveState,&g,flags,ahead,target);
    printf("VIEW %d %.9g %.9g %.9g\n",ok,target[0],target[1],target[2]);
  } else if(!strncmp(line,"moveto ",7)) {
    bot_goal_t g={0};bot_moveresult_t result={0};
    if(sscanf(line,"moveto %d %f %f %f %d",&g.areanum,&g.origin[0],&g.origin[1],&g.origin[2],&flags)!=5)abort();
    api->ai.BotMoveToGoal(&result,moveState,&g,flags);
    printf("MOVE %d %d %d %d %d %.9g %.9g %.9g\n",result.failure,result.type,result.blocked,result.traveltype,result.flags,result.movedir[0],result.movedir[1],result.movedir[2]);
  } else if(!strncmp(line,"visible ",8)) {
    bot_goal_t g={0};vec3_t target={12345,23456,34567};
    if(sscanf(line,"visible %f %f %f %d %d %f %f %f %d",&p[0],&p[1],&p[2],&area,&g.areanum,&g.origin[0],&g.origin[1],&g.origin[2],&flags)!=9)abort();
    int ok=api->ai.BotPredictVisiblePosition(p,area,&g,flags,target);
    printf("VISIBLE %d %.9g %.9g %.9g traces=%lu\n",ok,target[0],target[1],target[2],traceCount);
  } else if(sscanf(line,"%31s %f %f %f %f %f %f %f %f %f",name,&p[0],&p[1],&p[2],&lo[0],&lo[1],&lo[2],&hi[0],&hi[1],&hi[2])==10 && (!strcmp(name,"best")||!strcmp(name,"jump"))) {
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
  else if(sscanf(line,"splitupper %d",&splitUpperReach)==1){}
  else if(sscanf(line,"split %f %f %f %f",&boundary,&p[0],&p[1],&p[2])==4)printf("SPLIT %d\n",syntheticSplit(boundary,p));
  else if(sscanf(line,"box %f %f %f %f %f %f %d %f %f %f",&lo[0],&lo[1],&lo[2],&hi[0],&hi[1],&hi[2],&reach,&p[0],&p[1],&p[2])==10)printf("BOX %d\n",syntheticBox(lo,hi,reach,p));
  else if(sscanf(line,"half %d %f %d %f %f %f",&axis,&boundary,&reach,&p[0],&p[1],&p[2])==6)printf("HALF %d\n",syntheticHalf(axis,boundary,reach,p));
  else if(!strncmp(line,"patchreach ",11)){int n,face,edge,travel,target;if(sscanf(line,"patchreach %d %d %d %d %d",&n,&face,&edge,&travel,&target)!=5||n<1||n>=aasworld.reachabilitysize)abort();aasworld.reachability[n].facenum=face;aasworld.reachability[n].edgenum=edge;aasworld.reachability[n].traveltype=travel;aasworld.reachability[n].areanum=target;puts("PATCHED");}
  else if(sscanf(line,"modelreaches %d",&area)==1){int r=0;printf("MODELREACHES");for(int i=0;i<100000;i++){r=AAS_NextModelReachability(r,area);if(!r)break;printf(" %d:%d:%d",r,aasworld.reachability[r].areanum,aasworld.reachability[r].traveltype);}puts("");}
  else if(sscanf(line,"fuzzy %f %f %f",&p[0],&p[1],&p[2])==3)printf("FUZZY %d\n",BotFuzzyPointReachabilityArea(p));
  else if(sscanf(line,"pointreach %f %f %f",&p[0],&p[1],&p[2])==3)printf("POINTREACH %d\n",api->aas.AAS_PointReachabilityAreaIndex(p));
  else if(sscanf(line,"reachable %f %f %f %d",&p[0],&p[1],&p[2],&pass)==4){int a=api->ai.BotReachabilityArea(p,pass);printf("REACHABLE %d traces=%lu\n",a,traceCount);}
  else if(sscanf(line,"cost %d %f %f %f %f %f %f",&area,&p[0],&p[1],&p[2],&out[0],&out[1],&out[2])==7)printf("COST %u\n",AAS_AreaTravelTime(area,p,out));
  else if(sscanf(line,"enable %d %d",&area,&flags)==2)printf("ENABLED %d\n",api->aas.AAS_EnableRoutingArea(area,flags));
  else if(sscanf(line,"areas %f %f %f %f %f %f",&p[0],&p[1],&p[2],&out[0],&out[1],&out[2])==6){int a[4096];vec3_t v[4096];int n=api->aas.AAS_TraceAreas(p,out,a,v,4096);printf("AREAS");for(int i=0;i<n;i++)printf(" %d",a[i]);puts("");}
  else if(sscanf(line,"bbox %d",&presence)==1){api->aas.AAS_PresenceTypeBoundingBox(presence,lo,hi);printf("BBOX %.9g %.9g %.9g %.9g %.9g %.9g\n",lo[0],lo[1],lo[2],hi[0],hi[1],hi[2]);}
  else if(!strncmp(line,"entity ",7)) {
    bot_entitystate_t e={0};int entity;
    if(sscanf(line,"entity %d %f %f %f %f %f %f %f %f %f %d %d %d %d %d",&entity,&e.origin[0],&e.origin[1],&e.origin[2],&e.mins[0],&e.mins[1],&e.mins[2],&e.maxs[0],&e.maxs[1],&e.maxs[2],&e.type,&e.flags,&e.groundent,&e.solid,&e.modelindex)!=15)return 2;
    VectorCopy(e.origin,e.old_origin);printf("ENTITY %d\n",api->BotLibUpdateEntity(entity,&e));
  }
  else if(sscanf(line,"remove %d",&pass)==1)printf("REMOVED %d\n",api->BotLibUpdateEntity(pass,NULL));
  else if(sscanf(line,"frame %f",&p[0])==1)printf("FRAME %d\n",api->BotLibStartFrame(p[0]));
  else if(sscanf(line,"logtraces %d",&dumpTraces)==1)puts("LOGTRACES");
  else if(sscanf(line,"hitentity %d",&collisionEntity)==1){}
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
