/* Authored calls into the unchanged target-box movement operation. */
#define main authored_environment_main
#include "AasSwimmingOracle.c"
#undef main
extern int AAS_ClipToBBox(aas_trace_t*,vec3_t,vec3_t,int,vec3_t,vec3_t);
int main(void){botimport.Print=print;static aas_areasettings_t settings[2];settings[1].presencetype=6;aasworld.areasettings=settings;aasworld.numareas=2;aasworld.loaded=aasworld.initialized=1;AAS_InitSettings();
 char row[4096];while(fgets(row,sizeof(row),stdin)){
 vec3_t start,end,mins,maxs;int presence,solid;float fraction;
 if(sscanf(row,"clip %d %f %f %f %f %f %f %f %f %f %f %f %f %f %d",&presence,&start[0],&start[1],&start[2],&end[0],&end[1],&end[2],&mins[0],&mins[1],&mins[2],&maxs[0],&maxs[1],&maxs[2],&fraction,&solid)==15){
 aas_trace_t trace={0};trace.startsolid=solid;trace.fraction=fraction;VectorCopy(end,trace.endpos);trace.ent=17;trace.lastarea=19;trace.area=23;trace.planenum=29;
 int ok=AAS_ClipToBBox(&trace,start,end,presence,mins,maxs);printf("CLIP %d ",ok);for(size_t i=0;i<sizeof(trace);i++)printf("%02x",((byte*)&trace)[i]);puts("");
 }else if(!strncmp(row,"hit ",4)){
 float a[30];char *cursor=row+4;for(int i=0;i<30;i++){char*next;a[i]=strtof(cursor,&next);if(next==cursor)return 2;cursor=next;}
 presenceLeft=(int)a[0];presenceRight=(int)a[1];presenceThreshold=a[2];floorEnabled=(int)a[3];int pres=(int)a[4],ground=(int)a[5],cf=(int)a[6],mf=(int)a[7];float dt=a[8];contentsValue=(int)a[19];planeX=a[20];planeY=a[21];planeZ=a[22];planeDistance=a[23];
 vec3_t origin={a[10],a[11],a[12]},velocity={a[13],a[14],a[15]},command={a[16],a[17],a[18]},lo={a[24],a[25],a[26]},hi={a[27],a[28],a[29]};
 aas_clientmove_t result;memset(&result,0x7f,sizeof(result));traceCount=presenceQueries=0;queryHash=14695981039346656037ULL;
 int ok=AAS_ClientMovementHitBBox(&result,3,origin,pres,ground,velocity,command,cf,mf,dt,lo,hi,0);
 printf("HIT %d %d %d %llu ",ok,traceCount,presenceQueries,queryHash);for(size_t i=0;i<sizeof(result);i++)printf("%02x",((byte*)&result)[i]);puts("");
 }else{fprintf(stderr,"UNKNOWN %s",row);return 2;}
 }
}
