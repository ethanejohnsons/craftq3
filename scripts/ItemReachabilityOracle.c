/* Authored controlled calls into unchanged reachability functions. */
#include "qcommon/q_shared.h"
#include "botlib/botlib.h"
#include "botlib/be_aas.h"
#include "botlib/aasfile.h"
#include "botlib/be_aas_def.h"
#include "botlib/be_aas_reach.h"
#include <stdio.h>
#include <stdlib.h>
#include <stdarg.h>
aas_t aasworld;
botlib_import_t botimport;
static int pointArea=1,traceArea=2,traceStartSolid=0;static float traceFraction=1;
static aas_link_t links[8];static int flags=0,contents=0,pointCalls=0,pointLater=-1,firstNonzero=-1;
static void print(int type,char *fmt,...){va_list v;va_start(v,fmt);vfprintf(stderr,fmt,v);va_end(v);}
int AAS_PointAreaNum(vec3_t point){pointCalls++;int result=(pointLater>=0 && pointCalls>firstNonzero)?pointLater:pointArea;printf("POINT %.9g %.9g %.9g => %d\n",point[0],point[1],point[2],result);return result;}
aas_trace_t AAS_TraceClientBBox(vec3_t start,vec3_t end,int presence,int pass){
 printf("TRACE %.9g %.9g %.9g TO %.9g %.9g %.9g PRESENCE %d PASS %d\n",start[0],start[1],start[2],end[0],end[1],end[2],presence,pass);
 aas_trace_t r={0};r.fraction=traceFraction;r.startsolid=traceStartSolid;r.lastarea=traceArea;r.area=traceArea;
 for(int i=0;i<3;i++)r.endpos[i]=start[i]+traceFraction*(end[i]-start[i]);return r;
}
aas_link_t *AAS_LinkEntityClientBBox(vec3_t mins,vec3_t maxs,int ent,int presence){printf("LINK %.9g %.9g %.9g TO %.9g %.9g %.9g ENT %d PRESENCE %d\n",mins[0],mins[1],mins[2],maxs[0],maxs[1],maxs[2],ent,presence);return links;}
void AAS_UnlinkFromAreas(aas_link_t *link){puts("UNLINK");}
void AAS_Error(char *fmt,...){va_list v;va_start(v,fmt);vfprintf(stderr,fmt,v);va_end(v);abort();}
int main(int argc,char **argv){
 static aas_area_t areas[8];static aas_areasettings_t settings[8];static aas_reachability_t reaches[16];
 aasworld.loaded=aasworld.initialized=1;aasworld.numareas=aasworld.numareasettings=8;aasworld.areas=areas;aasworld.areasettings=settings;aasworld.reachability=reaches;aasworld.reachabilitysize=16;
 botimport.Print=print;
 for(int i=1;i<8;i++){areas[i].areanum=i;areas[i].center[0]=i;areas[i].center[1]=10+i;areas[i].center[2]=20+i;settings[i].presencetype=2;settings[i].numreachableareas=1;settings[i].firstreachablearea=i;links[i-1].areanum=i;links[i-1].next_area=i==7?NULL:&links[i];}
 if(argc>1)pointArea=atoi(argv[1]);if(argc>2)settings[1].numreachableareas=atoi(argv[2]);if(argc>3)traceFraction=atof(argv[3]);if(argc>4)traceArea=atoi(argv[4]);if(argc>5)traceStartSolid=atoi(argv[5]);
 if(argc>6)settings[1].areaflags=atoi(argv[6]);if(argc>7)settings[1].contents=atoi(argv[7]);if(argc>8)pointLater=atoi(argv[8]);if(argc>9)firstNonzero=atoi(argv[9]);
 for(int i=1;i<8;i++){char key[20];snprintf(key,sizeof(key),"AREA%d",i);char *a=getenv(key);if(a)sscanf(a,"%d,%d,%d,%d,%d,%d",&settings[i].areaflags,&settings[i].contents,&settings[i].presencetype,&settings[i].numreachableareas,&settings[i].cluster,&settings[i].clusterareanum);}
 if(getenv("LINKORDER")){int order[7],n=0;char *raw=strdup(getenv("LINKORDER")),*p=strtok(raw,",");while(p&&n<7){order[n++]=atoi(p);p=strtok(NULL,",");}for(int i=0;i<n;i++){links[i].areanum=order[i];links[i].next_area=i+1<n?&links[i+1]:NULL;}free(raw);}
 if(getenv("LINKONLY")){extern int AAS_BestReachableLinkArea(aas_link_t*);printf("LINKBEST %d\n",AAS_BestReachableLinkArea(links));return 0;}
 vec3_t origin={100,200,300},mins={-15,-15,-15},maxs={15,15,15},goal={-999,-999,-999};
 int area=AAS_BestReachableArea(origin,mins,maxs,goal);printf("BEST %d GOAL %.9g %.9g %.9g\n",area,goal[0],goal[1],goal[2]);

 return 0;
}
