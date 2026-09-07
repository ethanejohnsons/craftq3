/* Authored geometry-only host for unchanged AAS_AreaVolume. */
#include "qcommon/q_shared.h"
#include "botlib/botlib.h"
#include "botlib/be_aas.h"
#include "botlib/aasfile.h"
#include "botlib/be_aas_def.h"
#include <stdio.h>
#include <stdlib.h>
aas_t aasworld;
extern float AAS_AreaVolume(int);
extern float AAS_FaceArea(aas_face_t *);
static void fail(void){fputs("Invalid fixture\n",stderr);exit(2);}
static void *array(int count,int stride){if(count<0||count>2097152)fail();void*p=calloc(count?count:1,stride);if(!p)fail();return p;}
static int integer(void){int v;if(scanf("%d",&v)!=1)fail();return v;}
static float real(void){float v;if(scanf("%f",&v)!=1)fail();return v;}
int main(void){char op[24];while(scanf("%23s",op)==1){
 if(!strcmp(op,"mesh")){
 free(aasworld.vertexes);free(aasworld.edges);free(aasworld.edgeindex);free(aasworld.faces);free(aasworld.faceindex);free(aasworld.areas);free(aasworld.planes);
 aasworld.numvertexes=integer();aasworld.numedges=integer();aasworld.edgeindexsize=integer();aasworld.numfaces=integer();aasworld.faceindexsize=integer();aasworld.numareas=integer();aasworld.numplanes=integer();
 aasworld.vertexes=array(aasworld.numvertexes,sizeof(aas_vertex_t));aasworld.edges=array(aasworld.numedges,sizeof(aas_edge_t));aasworld.edgeindex=array(aasworld.edgeindexsize,sizeof(aas_edgeindex_t));aasworld.faces=array(aasworld.numfaces,sizeof(aas_face_t));aasworld.faceindex=array(aasworld.faceindexsize,sizeof(aas_faceindex_t));aasworld.areas=array(aasworld.numareas,sizeof(aas_area_t));aasworld.planes=array(aasworld.numplanes,sizeof(aas_plane_t));
 for(int i=0;i<aasworld.numvertexes;i++)for(int k=0;k<3;k++)aasworld.vertexes[i][k]=real();
 for(int i=0;i<aasworld.numedges;i++)for(int k=0;k<2;k++)aasworld.edges[i].v[k]=integer();
 for(int i=0;i<aasworld.edgeindexsize;i++)aasworld.edgeindex[i]=integer();
 for(int i=0;i<aasworld.numfaces;i++){aas_face_t*f=&aasworld.faces[i];f->planenum=integer();f->faceflags=integer();f->numedges=integer();f->firstedge=integer();f->frontarea=integer();f->backarea=integer();}
 for(int i=0;i<aasworld.faceindexsize;i++)aasworld.faceindex[i]=integer();
 for(int i=0;i<aasworld.numareas;i++){aas_area_t*a=&aasworld.areas[i];a->areanum=integer();a->numfaces=integer();a->firstface=integer();for(int k=0;k<3;k++)a->mins[k]=real();for(int k=0;k<3;k++)a->maxs[k]=real();for(int k=0;k<3;k++)a->center[k]=real();}
 for(int i=0;i<aasworld.numplanes;i++){for(int k=0;k<3;k++)aasworld.planes[i].normal[k]=real();aasworld.planes[i].dist=real();aasworld.planes[i].type=integer();}
 puts("READY");
 }else if(!strcmp(op,"volume")){int a=integer();if(a<0||a>=aasworld.numareas)fail();float v=AAS_AreaVolume(a);unsigned bits;memcpy(&bits,&v,4);printf("VOLUME %d %.9g %08x\n",a,v,bits);
 }else if(!strcmp(op,"face")){int a=integer();if(a<0||a>=aasworld.numfaces)fail();float v=AAS_FaceArea(&aasworld.faces[a]);printf("FACE %d %.9g\n",a,v);
 }else fail();fflush(stdout);}}
