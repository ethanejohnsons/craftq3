/* Authored CPU-only fixture around unchanged renderer exports and public metadata. */
#include "renderergl1/tr_local.h"
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#ifdef ORACLE_METADATA_ADAPTER
typedef struct {
    md3Surface_t surface;
    md3Triangle_t triangle;
    md3St_t coordinates[3];
    md3XyzNormal_t vertices[3];
} authored_mesh_t;

__attribute__((visibility("default"))) void ModelTransformObserve(
        const float *axes, const float *origin, int scaled) {
    trRefEntity_t entity = {0};
    viewParms_t view = {0};
    orientationr_t orientation = {0};
    authored_mesh_t mesh = {0};
    shader_t shader = {0};
    entity.e.reType = RT_MODEL;
    entity.e.nonNormalizedAxes = scaled;
    memcpy(entity.e.axis, axes, sizeof(entity.e.axis));
    memcpy(entity.e.origin, origin, sizeof(entity.e.origin));
    for (int i=0;i<4;i++) view.world.modelMatrix[i*5]=1;
    view.or.origin[0]=13;view.or.origin[1]=17;view.or.origin[2]=19;
    R_RotateForEntity(&entity, &view, &orientation);
    tr.or = orientation;
    tr.currentEntity = &entity;
    backEnd.currentEntity = &entity;
    backEnd.or = orientation;
    memset(&tess,0,sizeof(tess));
    shader.needsNormal=qtrue;
    tess.shader=&shader;
    mesh.surface.ident=SF_MD3;
    mesh.surface.numFrames=1;
    mesh.surface.numTriangles=1;
    mesh.surface.numVerts=3;
    mesh.surface.ofsTriangles=offsetof(authored_mesh_t,triangle);
    mesh.surface.ofsSt=offsetof(authored_mesh_t,coordinates);
    mesh.surface.ofsXyzNormals=offsetof(authored_mesh_t,vertices);
    mesh.surface.ofsEnd=sizeof(mesh);
    mesh.triangle.indexes[0]=0;mesh.triangle.indexes[1]=1;mesh.triangle.indexes[2]=2;
    mesh.vertices[1].xyz[0]=64;
    mesh.vertices[2].xyz[1]=64;mesh.vertices[2].xyz[2]=64;
    for(int i=0;i<FUNCTABLE_SIZE;i++) tr.sinTable[i]=sin((2.0*M_PI*i)/FUNCTABLE_SIZE);
    rb_surfaceTable[SF_MD3](&mesh.surface);
    printf("RESULT vertices=%d indexes=%d viewOrigin=%.9g,%.9g,%.9g matrix=",
        tess.numVertexes,tess.numIndexes,orientation.viewOrigin[0],orientation.viewOrigin[1],orientation.viewOrigin[2]);
    for(int i=0;i<16;i++)printf("%s%.9g",i?",":"",orientation.modelMatrix[i]);
    printf(" points=");
    for(int i=0;i<tess.numVertexes;i++){
        vec3_t world;
        R_LocalPointToWorld(tess.xyz[i],world);
        printf("%s%.9g,%.9g,%.9g",i?";":"",world[0],world[1],world[2]);
    }
    printf(" normals=");
    for(int i=0;i<tess.numVertexes;i++)
        printf("%s%.9g,%.9g,%.9g",i?";":"",tess.normal[i][0],tess.normal[i][1],tess.normal[i][2]);
    putchar('\n');fflush(stdout);
}
#else
#include <dlfcn.h>
#include <stdarg.h>
static void QDECL print(int level,const char *format,...) {
    va_list args;va_start(args,format);vfprintf(stderr,format,args);va_end(args);
}
static void QDECL Q_NO_RETURN fatal(int level,const char *format,...) {
    va_list args;va_start(args,format);vfprintf(stderr,format,args);va_end(args);exit(3);
}
int main(int argc,char **argv) {
    if(argc!=2)return 2;
    void *library=dlopen(argv[1],RTLD_NOW|RTLD_LOCAL);
    if(!library){fprintf(stderr,"%s\n",dlerror());return 3;}
    refexport_t *(*get)(int,refimport_t *)=dlsym(library,"GetRefAPI");
    void (*observe)(const float *,const float *,int)=dlsym(library,"ModelTransformObserve");
    if(!get||!observe)return 4;
    refimport_t imports={0};imports.Printf=print;imports.Error=fatal;
    if(!get(REF_API_VERSION,&imports))return 5;
    int scaled;float axes[9],origin[3];unsigned count=0;
    while(scanf("%d",&scaled)==1){
        for(int i=0;i<9;i++)if(scanf("%f",&axes[i])!=1||!isfinite(axes[i]))return 6;
        for(int i=0;i<3;i++)if(scanf("%f",&origin[i])!=1||!isfinite(origin[i]))return 6;
        if(++count>10000)return 7;
        observe(axes,origin,scaled);
    }
    return 0;
}
#endif
