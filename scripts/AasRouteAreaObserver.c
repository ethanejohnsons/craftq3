/* Authored call-boundary observer. The separately compiled callee is unchanged. */
#include "qcommon/q_shared.h"
#include <stdio.h>
extern int ObservedOriginalTraceAreas(vec3_t,vec3_t,int*,vec3_t*,int);
int AAS_TraceAreas(vec3_t start,vec3_t end,int *areas,vec3_t *points,int max){
 int n=ObservedOriginalTraceAreas(start,end,areas,points,max);
 fprintf(stderr,"OBSERVE TraceAreas max=%d start=%.9g,%.9g,%.9g end=%.9g,%.9g,%.9g points=%d result=%d areas=",max,start[0],start[1],start[2],end[0],end[1],end[2],points!=NULL,n);
 for(int i=0;i<n;i++)fprintf(stderr,"%d,",areas[i]);fputc('\n',stderr);return n;
}
