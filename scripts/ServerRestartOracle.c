/* Authored development call observer. Only public VM and game-export metadata is used. */
#include "qcommon/q_shared.h"
#include "qcommon/qcommon.h"
#include "server/server.h"
#include <stdarg.h>
#include <stdio.h>
extern intptr_t QDECL ObservedNativeVmCall(vm_t *vm, int callNum, ...);
intptr_t QDECL VM_Call(vm_t *vm, int callNum, ...) {
    static const int counts[] = {3,1,3,1,1,1,1,1,1,0,1};
    int a[12] = {0};
    if (callNum < 0 || callNum >= (int)(sizeof(counts)/sizeof(counts[0]))) {
        fprintf(stderr,"Unexpected non-game export %d\n",callNum); abort();
    }
    va_list ap; va_start(ap,callNum);
    for(int i=0;i<counts[callNum];i++) a[i]=va_arg(ap,int);
    va_end(ap);
    fprintf(stderr,"OBSERVED_GAME_CALL %d %d %d %d SVTIME %d SVSTATE %d\n",callNum,a[0],a[1],a[2],sv.time,sv.state);
    fflush(stderr);
    if(callNum==0||callNum==1)fprintf(stderr,"BEFORE_CONFIG %d restart=%d cs14=%s cs22=%s cs1019=%s\n",callNum,callNum==0?a[2]:a[0],sv.configstrings[14],sv.configstrings[22],sv.configstrings[1019]);
    intptr_t result=ObservedNativeVmCall(vm,callNum,a[0],a[1],a[2],a[3],a[4],a[5],a[6],a[7],a[8],a[9],a[10],a[11]);
    if(callNum==1&&a[0]){SV_SetConfigstring(14,"QA_RETAIN_14");SV_SetConfigstring(22,"QA_RETAIN_22");SV_SetConfigstring(1019,"QA_RETAIN_1019");}
    if(callNum==0||callNum==1)fprintf(stderr,"AFTER_CONFIG %d restart=%d cs14=%s cs22=%s cs1019=%s\n",callNum,callNum==0?a[2]:a[0],sv.configstrings[14],sv.configstrings[22],sv.configstrings[1019]);
    return result;
}
