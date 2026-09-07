/* Authored effect observer: unchanged CL_SystemInfoChanged, controlled external services. */
#include "client/client.h"
#include <stdio.h>
#include <stdarg.h>
#include <setjmp.h>
clientActive_t cl; clientConnection_t clc; clientStatic_t cls;
char cl_oldGame[MAX_QPATH]; qboolean cl_oldGameSet;
static int targetFlags, cheatCalls; static char game[MAX_QPATH];
static jmp_buf failed;
void QDECL Com_Error(int code, const char *format, ...) {
 va_list args; va_start(args,format); printf("ERROR %d ",code); vprintf(format,args); putchar('\n'); va_end(args); longjmp(failed,1);
}
void QDECL Com_Printf(const char *format, ...) { va_list args; va_start(args,format); printf("PRINT "); vprintf(format,args); va_end(args); }
void QDECL Com_DPrintf(const char *format, ...) { (void)format; }
int Cvar_Flags(const char *name) { printf("FLAGS %s\n",name); return !strcmp(name,"probe") ? targetFlags : CVAR_SYSTEMINFO; }
void Cvar_Set(const char *name,const char *value) { printf("SET %s = %s\n",name,value); if (!strcmp(name,"fs_game")) Q_strncpyz(game,value,sizeof(game)); }
void Cvar_SetSafe(const char *name,const char *value) { printf("SAFE %s = %s\n",name,value); }
cvar_t *Cvar_Get(const char *name,const char *value,int flags) { static cvar_t var; printf("GET %s = %s FLAGS %d\n",name,value,flags); return &var; }
void Cvar_SetCheatState(void) { cheatCalls++; puts("CHEAT_RESET"); }
float Cvar_VariableValue(const char *name) { printf("VALUE %s\n",name); return 0; }
char *Cvar_VariableString(const char *name) { return !strcmp(name,"fs_game") ? game : ""; }
void Cvar_VariableStringBuffer(const char *name,char *buffer,int size) { Q_strncpyz(buffer,Cvar_VariableString(name),size); }
qboolean FS_InvalidGameDir(const char *name) { return !strcmp(name,"../bad"); }
void FS_PureServerSetLoadedPaks(const char *sums,const char *names) { printf("LOADED %s | %s\n",sums,names); }
void FS_PureServerSetReferencedPaks(const char *sums,const char *names) { printf("REFERENCED %s | %s\n",sums,names); }
int main(void) {
 char info[8192]; int demo;
 while(scanf("%d %d %8191s",&targetFlags,&demo,info)==3) {
  memset(&cl,0,sizeof(cl));memset(&clc,0,sizeof(clc));memset(&cls,0,sizeof(cls));
  clc.demoplaying=demo;cl.gameState.stringOffsets[CS_SYSTEMINFO]=1;
  Q_strncpyz(cl.gameState.stringData+1,info,sizeof(cl.gameState.stringData)-1);
  game[0]=0;cl_oldGame[0]=0;cl_oldGameSet=0;cheatCalls=0;
  if(!setjmp(failed)) CL_SystemInfoChanged();
  printf("END id=%d cheats=%d pure=%d game=%s old=%s saved=%d\n",cl.serverId,cheatCalls,cl_connectedToPureServer,game,cl_oldGame,cl_oldGameSet);fflush(stdout);
 }
 return 0;
}
