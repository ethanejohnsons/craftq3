/* Authored flat/open environment for unchanged native movement prediction. */
#include "qcommon/q_shared.h"
#include "botlib/botlib.h"
#include "botlib/be_aas.h"
#include "botlib/aasfile.h"
#include "botlib/be_aas_def.h"
#include "botlib/be_aas_move.h"
#include <stdio.h>
#include <stdlib.h>
#include <stddef.h>
#include <stdarg.h>
aas_t aasworld;botlib_import_t botimport;
static unsigned long long queryHash;
static void hashInt(unsigned int value){queryHash=(queryHash^value)*1099511628211ULL;}
static void hashVec(vec3_t p){for(int i=0;i<3;i++){unsigned int bits;memcpy(&bits,p+i,4);hashInt(bits);}}
static int floorEnabled, contentsValue, traceCount, presenceLeft, presenceRight, presenceQueries;static float presenceThreshold;static float planeX,planeY,planeZ=1,planeDistance;
float LibVarValue(const char *name,const char *value){if(getenv(name))return atof(getenv(name));if(getenv("TRACE_SETTINGS"))fprintf(stderr,"SETTING %s %s\n",name,value);return atof(value);}
int AAS_PointAreaNum(vec3_t p){hashInt(4);hashVec(p);return 1;}
int AAS_PointContents(vec3_t p){int out=contentsValue;if(getenv("FLUID_Z"))out=p[2]<atof(getenv("FLUID_Z"))?contentsValue:0;if(getenv("FLUID_X"))out=p[0]>atof(getenv("FLUID_X"))?contentsValue:0;if(getenv("TRACE_CONTENTS"))fprintf(stderr,"CONTENTS %.9g %.9g %.9g = %d\n",p[0],p[1],p[2],out);hashInt(3);hashVec(p);hashInt(out);return out;}
int AAS_PointPresenceType(vec3_t p){int result=p[0]<presenceThreshold?presenceLeft:presenceRight;presenceQueries++;hashInt(2);hashVec(p);hashInt(result);if(getenv("TRACE_PRESENCE"))fprintf(stderr,"PRESENCE %.9g %.9g %.9g = %d\n",p[0],p[1],p[2],result);return result;}
void AAS_PresenceTypeBoundingBox(int type,vec3_t mins,vec3_t maxs){VectorSet(mins,-15,-15,-24);VectorSet(maxs,15,15,type==2?32:8);}
aas_plane_t *AAS_PlaneFromNum(int n){static aas_plane_t p;p.normal[0]=planeX;p.normal[1]=planeY;p.normal[2]=planeZ;p.dist=planeDistance;return &p;}
aas_trace_t AAS_TraceClientBBox(vec3_t start,vec3_t end,int presence,int pass){hashInt(1);hashVec(start);hashVec(end);hashInt(presence);hashInt(pass);traceCount++;aas_trace_t t={0};t.fraction=1;t.lastarea=1;VectorCopy(end,t.endpos);float from=planeX*start[0]+planeY*start[1]+planeZ*start[2]-planeDistance,to=planeX*end[0]+planeY*end[1]+planeZ*end[2]-planeDistance;if(floorEnabled&&to<0){if(from<0){t.startsolid=1;t.fraction=0;VectorCopy(start,t.endpos);}else{t.fraction=from/(from-to);for(int i=0;i<3;i++)t.endpos[i]=start[i]+t.fraction*(end[i]-start[i]);}}if(getenv("TRACE_MOVES"))fprintf(stderr,"TRACE p%d %.9g %.9g %.9g -> %.9g %.9g %.9g = %.9g %d %.9g %.9g %.9g\n",presence,start[0],start[1],start[2],end[0],end[1],end[2],t.fraction,t.startsolid,t.endpos[0],t.endpos[1],t.endpos[2]);return t;}
bsp_trace_t AAS_Trace(vec3_t s,vec3_t mins,vec3_t maxs,vec3_t e,int pass,int mask){(void)mins;(void)maxs;(void)mask;aas_trace_t a=AAS_TraceClientBBox(s,e,2,pass);bsp_trace_t t={0};t.fraction=a.fraction;t.startsolid=a.startsolid;VectorCopy(a.endpos,t.endpos);VectorCopy(AAS_PlaneFromNum(a.planenum)->normal,t.plane.normal);return t;}

int AAS_TraceAreas(vec3_t s,vec3_t e,int *areas,vec3_t *points,int max){(void)e;if(max<1)return 0;areas[0]=1;if(points)VectorCopy(s,points[0]);return 1;}
qboolean AAS_PointInsideFace(int f,vec3_t p,float epsilon){(void)f;(void)p;(void)epsilon;return 1;}
void AAS_ClearShownDebugLines(void){}
void AAS_DebugLine(vec3_t s,vec3_t e,int color){(void)s;(void)e;(void)color;}
static void print(int type,char *fmt,...){(void)type;va_list a;va_start(a,fmt);vfprintf(stderr,fmt,a);va_end(a);}
int main(void){botimport.Print=print;static aas_areasettings_t settings[2];settings[1].presencetype=6;settings[1].contents=getenv("AREA_CONTENTS")?atoi(getenv("AREA_CONTENTS")):0;aasworld.areasettings=settings;aasworld.numareas=2;aasworld.loaded=aasworld.initialized=1;AAS_InitSettings();printf("SIZE %zu TRACE %zu\n",sizeof(aas_clientmove_t),sizeof(aas_trace_t));
 float dt;int presence,ground,cmdframes,maxframes,events;vec3_t p,v,c;while(scanf("%d %d %f %d %d %d %d %d %f %d %f %f %f %f %f %f %f %f %f %d %f %f %f %f",&presenceLeft,&presenceRight,&presenceThreshold,&floorEnabled,&presence,&ground,&cmdframes,&maxframes,&dt,&events,&p[0],&p[1],&p[2],&v[0],&v[1],&v[2],&c[0],&c[1],&c[2],&contentsValue,&planeX,&planeY,&planeZ,&planeDistance)==24){aas_clientmove_t out;memset(&out,0x7f,sizeof(out));traceCount=0;presenceQueries=0;queryHash=14695981039346656037ULL;if(getenv("SWIM_ONLY")){printf("SWIM %d\n", AAS_Swimming(p));continue;}int r=AAS_PredictClientMovement(&out,3,p,presence,ground,v,c,cmdframes,maxframes,dt,events,0,0);printf("MOVE %d %.9g %.9g %.9g %.9g %.9g %.9g %d %d %d %.9g %d trace%d %.9g count%d presence%d hash%llu\n",r,out.endpos[0],out.endpos[1],out.endpos[2],out.velocity[0],out.velocity[1],out.velocity[2],out.presencetype,out.stopevent,out.endcontents,out.time,out.frames,out.trace.startsolid,out.trace.fraction,traceCount,presenceQueries,queryHash);printf("RAW ");for(size_t b=0;b<sizeof(out);b++)printf("%02x",((unsigned char*)&out)[b]);puts("");fflush(stdout);}}
