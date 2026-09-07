/* Authored CPU demo clock observer. Native clock routines are linked unchanged.
 * clientActive_t/clientConnection_t/clientStatic_t come from the public native
 * client header. Only exported signatures and observed behavior were inspected;
 * no native routine body was used to write this host. No VM, socket or GL setup.
 */
#include "client/client.h"
#include <setjmp.h>
#include <stdarg.h>
#include <stdio.h>
extern void CL_AdjustTimeDelta(void); /* Exported definition signature only. */
clientActive_t cl;
clientConnection_t clc;
clientStatic_t cls;
static cvar_t nudge, showDelta, freezeDemo, timedemo, activeAction, localServer, timeScale, serverPaused;
cvar_t *cl_timeNudge=&nudge, *cl_showTimeDelta=&showDelta, *cl_freezeDemo=&freezeDemo,
 *cl_timedemo=&timedemo, *cl_activeAction=&activeAction, *com_sv_running=&localServer,
 *com_timescale=&timeScale, *sv_paused=&serverPaused;
static int paused, pauseCalls, demoCalls, notifications, milliseconds, errorCode, error;
static char errorText[1024], queue[1024];
static jmp_buf failure;
void QDECL Com_Error(int code,const char *format,...) {
 va_list args;va_start(args,format);vsnprintf(errorText,sizeof(errorText),format,args);va_end(args);
 errorCode=code;error=1;longjmp(failure,1);
}
void QDECL Com_Printf(const char *format,...) {(void)format;}
void QDECL Com_DPrintf(const char *format,...) {(void)format;}
qboolean CL_CheckPaused(void) {pauseCalls++;return paused;}
static struct {int kind,time,flags;} steps[4096];
static int stepCount,stepCursor;
void CL_ReadDemoMessage(void) {
 demoCalls++;
 if(demoCalls>4096)Com_Error(ERR_DROP,"Authored reader work cap");
 int kind=stepCursor<stepCount?steps[stepCursor].kind:3;
 int time=stepCursor<stepCount?steps[stepCursor].time:0;
 int flags=stepCursor<stepCount?steps[stepCursor].flags:0;
 stepCursor++;
 printf("READ %d %d %d\n",kind,time,flags);
 if(kind==1){cl.snap.valid=qtrue;cl.snap.serverTime=time;cl.snap.snapFlags=flags;cl.newSnapshots=qtrue;}
 else if(kind==2){memset(&cl,0,sizeof(cl));clc.state=CA_PRIMED;}
 else if(kind==3)clc.state=CA_DISCONNECTED;
}
void Con_Close(void) {notifications++;}
void Cbuf_AddText(const char *text) {snprintf(queue,sizeof(queue),"%s",text);}
void Cvar_Set(const char *name,const char *value) {(void)name;(void)value;}
float Cvar_VariableValue(const char *name) {(void)name;return 0;}
int Sys_Milliseconds(void) {return milliseconds;}
static void state(void) {
 printf("STATE %d %d %d %d %d %d %d %d %d %d %d %d %d %d %d\n",clc.state,cls.realtime,
 cl.snap.serverTime,cl.serverTimeDelta,cl.serverTime,cl.oldServerTime,cl.oldFrameServerTime,
 cl.newSnapshots,cl.extrapolatedSnapshot,clc.timeDemoBaseTime,pauseCalls,demoCalls,notifications,
 error,errorCode);
 if(error)printf("ERROR %s\n",errorText);
 printf("DEMO %d %d %d %d %d %d %d %d\n",clc.firstDemoFrameSkipped,clc.timeDemoFrames,
 clc.timeDemoStart,clc.timeDemoBaseTime,clc.timeDemoLastFrame,clc.timeDemoMinDuration,clc.timeDemoMaxDuration,stepCursor);
 printf("DURATIONS");for(int i=0;i<16;i++)printf(" %u",clc.timeDemoDurations[i]);puts("");
 puts("END");fflush(stdout);
}
int main(void) {
 char command[32];
 activeAction.string="";timeScale.value=1;timeScale.integer=1;
 printf("CONSTANTS primed=%d active=%d notactive=%d\n",CA_PRIMED,CA_ACTIVE,SNAPFLAG_NOT_ACTIVE);fflush(stdout);
 while(scanf("%31s",command)==1){
  error=0;errorCode=0;errorText[0]=0;pauseCalls=demoCalls=notifications=0;
  if(setjmp(failure)){state();continue;}
  if(!strcmp(command,"reset")){
   memset(&cl,0,sizeof(cl));memset(&clc,0,sizeof(clc));memset(&cls,0,sizeof(cls));
   nudge.integer=0;showDelta.integer=0;freezeDemo.integer=0;timedemo.integer=0;
   localServer.integer=0;timeScale.value=1;timeScale.integer=1;serverPaused.integer=0;
   paused=0;queue[0]=0;clc.state=CA_PRIMED;clc.demoplaying=qtrue;stepCount=stepCursor=0;milliseconds=0;
  }else if(!strcmp(command,"seed")){
   int stateValue,valid,news,extrap;
   if(scanf("%d%d%d%d%d%d%d%d%d%d%d",&stateValue,&cls.realtime,&cl.snap.serverTime,&valid,
    &cl.snap.snapFlags,&news,&extrap,&cl.serverTimeDelta,&cl.serverTime,&cl.oldServerTime,
    &cl.oldFrameServerTime)!=11)return 2;
   clc.state=stateValue;cl.snap.valid=valid;cl.newSnapshots=news;cl.extrapolatedSnapshot=extrap;
  }else if(!strcmp(command,"settings")){
   int demo;
   if(scanf("%d%f%d%d%d%d%d",&nudge.integer,&timeScale.value,&localServer.integer,&paused,
    &serverPaused.integer,&freezeDemo.integer,&demo)!=7)return 2;
   clc.demoplaying=demo;
  }else if(!strcmp(command,"step")){
   if(stepCount>=4096||scanf("%d%d%d",&steps[stepCount].kind,&steps[stepCount].time,&steps[stepCount].flags)!=3)return 2;stepCount++;
  }else if(!strcmp(command,"demoseed")){
   if(scanf("%d%d%d%d%d%d%d",(int*)&clc.firstDemoFrameSkipped,&clc.timeDemoFrames,&clc.timeDemoStart,&clc.timeDemoBaseTime,&clc.timeDemoLastFrame,&clc.timeDemoMinDuration,&clc.timeDemoMaxDuration)!=7)return 2;
  }else if(!strcmp(command,"run")){
   if(scanf("%d%d%d%d%d%f",&cls.realtime,&milliseconds,&nudge.integer,&freezeDemo.integer,&timedemo.integer,&timeScale.value)!=6)return 2;
   CL_SetCGameTime();
  }else if(!strcmp(command,"snapshot")){
   if(scanf("%d%d",&cl.snap.serverTime,&cl.snap.snapFlags)!=2)return 2;
   cl.snap.valid=qtrue;cl.newSnapshots=qtrue;
  }else if(!strcmp(command,"tick")){
   if(scanf("%d%d%f",&cls.realtime,&nudge.integer,&timeScale.value)!=3)return 2;
   CL_SetCGameTime();
  }else if(!strcmp(command,"first")){CL_FirstSnapshot();
  }else if(!strcmp(command,"adjust")){CL_AdjustTimeDelta();
  }else if(!strcmp(command,"frame")){CL_SetCGameTime();
  }else return 2;
  state();
 }
 return 0;
}
