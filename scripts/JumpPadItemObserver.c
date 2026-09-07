/* Authored transparent observation of public calls; operation implementations are unchanged. */
#include "qcommon/q_shared.h"
#include "botlib/botlib.h"
#include "botlib/be_aas.h"
#include "botlib/aasfile.h"
#include "botlib/be_aas_def.h"
#include <stdio.h>
#include <stdlib.h>
int observeActive;
extern int Observed_AAS_GetJumpPadInfo(int,vec3_t,vec3_t,vec3_t,vec3_t);
int AAS_GetJumpPadInfo(int ent,vec3_t start,vec3_t lo,vec3_t hi,vec3_t velocity){
 int result=Observed_AAS_GetJumpPadInfo(ent,start,lo,hi,velocity);
 if(observeActive)fprintf(stderr,"PADINFO ent%d ok%d start %.9g %.9g %.9g low %.9g %.9g %.9g high %.9g %.9g %.9g vel %.9g %.9g %.9g\n",ent,result,start[0],start[1],start[2],lo[0],lo[1],lo[2],hi[0],hi[1],hi[2],velocity[0],velocity[1],velocity[2]);return result;
}
extern int Observed_AAS_ClientMovementHitBBox(aas_clientmove_t*,int,vec3_t,int,int,vec3_t,vec3_t,int,int,float,vec3_t,vec3_t,int);
int AAS_ClientMovementHitBBox(aas_clientmove_t *m,int ent,vec3_t o,int pres,int ground,vec3_t v,vec3_t c,int cf,int mf,float dt,vec3_t lo,vec3_t hi,int vis){
 if(observeActive)fprintf(stderr,"HITBOX ent%d origin %.9g %.9g %.9g p%d ground%d vel %.9g %.9g %.9g cmd %.9g %.9g %.9g cf%d mf%d dt%.9g lo %.9g %.9g %.9g hi %.9g %.9g %.9g vis%d\n",ent,o[0],o[1],o[2],pres,ground,v[0],v[1],v[2],c[0],c[1],c[2],cf,mf,dt,lo[0],lo[1],lo[2],hi[0],hi[1],hi[2],vis);
 int r=Observed_AAS_ClientMovementHitBBox(m,ent,o,pres,ground,v,c,cf,mf,dt,lo,hi,vis);
 if(observeActive)fprintf(stderr,"HITOUT ok%d event%d end %.9g %.9g %.9g area%d vel %.9g %.9g %.9g time%.9g frame%d\n",r,m->stopevent,m->endpos[0],m->endpos[1],m->endpos[2],m->endarea,m->velocity[0],m->velocity[1],m->velocity[2],m->time,m->frames);return r;
}
extern int Observed_AAS_PointAreaNum(vec3_t);
int AAS_PointAreaNum(vec3_t p){int r=Observed_AAS_PointAreaNum(p);if(observeActive)fprintf(stderr,"POINT %.9g %.9g %.9g -> %d\n",p[0],p[1],p[2],r);return r;}
extern aas_link_t *Observed_AAS_LinkEntityClientBBox(vec3_t,vec3_t,int,int);
aas_link_t *AAS_LinkEntityClientBBox(vec3_t lo,vec3_t hi,int ent,int pres){aas_link_t*r=Observed_AAS_LinkEntityClientBBox(lo,hi,ent,pres);if(observeActive){fprintf(stderr,"LINK low %.9g %.9g %.9g high %.9g %.9g %.9g ent%d p%d:",lo[0],lo[1],lo[2],hi[0],hi[1],hi[2],ent,pres);for(aas_link_t*p=r;p;p=p->next_area)fprintf(stderr," %d",p->areanum);fputs("\n",stderr);}return r;}

extern aas_trace_t Observed_AAS_TraceClientBBox(vec3_t,vec3_t,int,int);
aas_trace_t AAS_TraceClientBBox(vec3_t s,vec3_t e,int pres,int pass){aas_trace_t t;
 if(observeActive&&getenv("PAD_TRACE")){memset(&t,0,sizeof(t));int solid;float fraction;if(sscanf(getenv("PAD_TRACE"),"%d %f",&solid,&fraction)!=2)abort();t.startsolid=solid;t.fraction=fraction;for(int k=0;k<3;k++)t.endpos[k]=s[k]+fraction*(e[k]-s[k]);}
 else t=Observed_AAS_TraceClientBBox(s,e,pres,pass);if(observeActive)fprintf(stderr,"AASTRACE p%d pass%d start %.9g %.9g %.9g end %.9g %.9g %.9g -> solid%d frac%.9g end %.9g %.9g %.9g area%d plane%d\n",pres,pass,s[0],s[1],s[2],e[0],e[1],e[2],t.startsolid,t.fraction,t.endpos[0],t.endpos[1],t.endpos[2],t.area,t.planenum);return t;}

extern float Observed_AAS_AreaVolume(int);
float AAS_AreaVolume(int area){float result=Observed_AAS_AreaVolume(area);if(observeActive)fprintf(stderr,"VOLUME %d %.9g\n",area,result);return result;}

extern vec_t Observed_VectorNormalize(vec3_t);
vec_t VectorNormalize(vec3_t v){vec3_t original;VectorCopy(v,original);vec_t result=Observed_VectorNormalize(v);if(observeActive)fprintf(stderr,"NORMALIZE %.9g %.9g %.9g -> %.9g %.9g %.9g length%.9g\n",original[0],original[1],original[2],v[0],v[1],v[2],result);return result;}
extern vec_t Observed_VectorNormalize2(const vec3_t,vec3_t);
vec_t VectorNormalize2(const vec3_t v,vec3_t out){vec_t result=Observed_VectorNormalize2(v,out);if(observeActive)fprintf(stderr,"NORMALIZE2 %.9g %.9g %.9g -> %.9g %.9g %.9g length%.9g\n",v[0],v[1],v[2],out[0],out[1],out[2],result);return result;}
