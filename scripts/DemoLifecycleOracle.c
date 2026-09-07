/* Authored lifecycle callbacks around original demo start/disconnect/completion exports. */
#define main DemoFramingUnusedMain
#define CL_ParseServerMessage DemoLifecycleUnusedParse
#define Cvar_Get DemoLifecycleUnusedGet
#define Cvar_Set DemoLifecycleUnusedSet
#define Cvar_SetValue DemoLifecycleUnusedSetValue
#define Cvar_VariableIntegerValue DemoLifecycleUnusedInteger
#define Cvar_VariableValue DemoLifecycleUnusedValue
#define Cmd_Argv DemoLifecycleUnusedArgv
#include "DemoPlaybackOracle.c"
#undef main
#undef CL_ParseServerMessage
#undef Cvar_Get
#undef Cvar_Set
#undef Cvar_SetValue
#undef Cvar_VariableIntegerValue
#undef Cvar_VariableValue
#undef Cmd_Argv

extern void CL_PlayDemo_f(void);
extern void DemoLifecycleNativeDisconnect(qboolean showMainMenu);
extern void DemoOracleOriginalCompleted(void);
extern void DemoLifecycleNativeParseServerMessage(msg_t *message);
static cvar_t variables[64];
static char names[64][128],values[64][512];
static int variableCount;
static char demoArgument[128]="fixture",commandName[32]="demo";
static cvar_t running={.integer=1},serverRunning;
cvar_t *com_cl_running=&running,*com_sv_running=&serverRunning;
int cl_connectedToPureServer;
qboolean com_errorEntered;
vm_t *uivm=(vm_t*)1;
int demo_protocols[]={68,0};
static cvar_t *variable(const char *name) {
  for(int i=0;i<variableCount;i++)if(!strcmp(names[i],name))return &variables[i];
  if(variableCount==64)abort();int i=variableCount++;
  snprintf(names[i],sizeof(names[i]),"%s",name);variables[i].name=names[i];variables[i].string=values[i];return &variables[i];
}
void Cvar_Set(const char *name,const char *value) {
  cvar_t *v=variable(name);int index=(int)(v-variables);snprintf(values[index],sizeof(values[index]),"%s",value);
  v->integer=atoi(value);v->value=(float)atof(value);printf("SET %s %s\n",name,value);
}
void Cvar_SetValue(const char *name,float value) {char text[64];snprintf(text,sizeof(text),"%.9g",value);Cvar_Set(name,text);}
cvar_t *Cvar_Set2(const char *name,const char *value,qboolean force) {printf("SET2 force%d\n",force);Cvar_Set(name,value);return variable(name);}
cvar_t *Cvar_Get(const char *name,const char *value,int flags) {
  cvar_t *v=variable(name);if(!v->string[0])Cvar_Set(name,value);v->flags|=flags;printf("GET %s %s flags%d\n",name,value,flags);return v;
}
int Cvar_VariableIntegerValue(const char *name) {printf("INTEGER %s\n",name);return variable(name)->integer;}
float Cvar_VariableValue(const char *name) {printf("VALUE %s\n",name);return variable(name)->value;}
char *Cvar_VariableString(const char *name) {printf("STRING %s\n",name);return variable(name)->string;}
int Cvar_Flags(const char *name) {printf("FLAGS %s\n",name);return variable(name)->flags;}
void Cvar_SetCheatState(void) {puts("CHEAT_STATE");}
void Cvar_SetSafe(const char *name,const char *value) {printf("SET_SAFE %s\n",name);Cvar_Set(name,value);}
void Cvar_VariableStringBuffer(const char *name,char *buffer,int size) {printf("STRING_BUFFER %s\n",name);snprintf(buffer,(size_t)size,"%s",variable(name)->string);}
char *Cmd_Argv(int index) {return index==0?commandName:index==1?demoArgument:"";}
void CL_ParseServerMessage(msg_t *message) {
  printf("PARSE_LIFECYCLE bytes%d sequence%d\n",message->cursize,clc.serverMessageSequence);
  DemoLifecycleNativeParseServerMessage(message);
}
void CL_InitDownloads(void) {puts("INIT_DOWNLOADS");clc.state=CA_PRIMED;}
void CL_NextDownload(void) {puts("NEXT_DOWNLOAD");}
void CL_Disconnect(qboolean showMainMenu) {printf("DISCONNECT %d\n",showMainMenu);DemoLifecycleNativeDisconnect(showMainMenu);}
void CL_FlushMemory(void) {puts("FLUSH_MEMORY");}
void CL_StartHunkUsers(qboolean rendererOnly) {printf("START_HUNK_USERS %d\n",rendererOnly);}
void Con_Close(void) {puts("CON_CLOSE");}
void CL_ShutdownCGame(void) {puts("SHUTDOWN_CGAME");}
void SV_Shutdown(char *message) {printf("SV_SHUTDOWN %s\n",message);}
void S_StopAllSounds(void) {puts("STOP_SOUNDS");}
void S_ClearSoundBuffer(void) {puts("CLEAR_SOUND_BUFFER");}
qboolean CL_VideoRecording(void) {puts("VIDEO_RECORDING");return qfalse;}
qboolean CL_CloseAVI(void) {puts("CLOSE_AVI");return qtrue;}
void SCR_StopCinematic(void) {puts("STOP_CINEMATIC");}
void SCR_UpdateScreen(void) {puts("UPDATE_SCREEN");}
intptr_t QDECL VM_Call(vm_t *vm,int callNum,...) {(void)vm;va_list args;va_start(args,callNum);int menu=callNum==7||callNum==3?va_arg(args,int):-1;int down=callNum==3?va_arg(args,int):-1;va_end(args);printf("VM_CALL %d arg%d down%d\n",callNum,menu,down);return 0;}
char *Com_MD5File(const char *filename,int length,const char *prefix,int prefixLength) {(void)length;(void)prefix;(void)prefixLength;printf("MD5_FILE %s\n",filename);return "";}
long FS_BaseDir_FOpenFileRead(const char *name,fileHandle_t *file) {printf("BASE_READ %s\n",name);*file=0;return -1;}
fileHandle_t FS_BaseDir_FOpenFileWrite_HomeData(const char *name) {printf("BASE_WRITE %s\n",name);return 19;}
void FS_BaseDir_Rename_HomeData(const char *from,const char *to,qboolean safe) {printf("BASE_RENAME %s %s %d\n",from,to,safe);}
qboolean FS_InvalidGameDir(const char *name) {printf("INVALID_GAME %s\n",name);return qfalse;}
void QDECL FS_Printf(fileHandle_t file,const char *format,...) {(void)format;printf("FS_PRINTF %d\n",file);}
void CL_WritePacket(void) {puts("WRITE_PACKET");}
void Console_Key(int key) {printf("CONSOLE_KEY %d\n",key);}
void Message_Key(int key) {printf("MESSAGE_KEY %d\n",key);}
void CL_ParseBinding(int key,qboolean down,unsigned time) {printf("BINDING %d %d %u\n",key,down,time);}
void Con_ToggleConsole_f(void) {puts("TOGGLE_CONSOLE");}
void FS_PureServerSetLoadedPaks(const char *sums,const char *names) {printf("PURE_LOADED %s | %s\n",sums,names);}
void FS_PureServerSetReferencedPaks(const char *sums,const char *names) {printf("PURE_REFERENCED %s | %s\n",sums,names);}
qboolean FS_ConditionalRestart(int feed,qboolean disconnect) {printf("FS_RESTART %d %d\n",feed,disconnect);return qfalse;}
long FS_FOpenFileRead(const char *name,fileHandle_t *file,qboolean unique) {printf("OPEN_READ %s unique%d\n",name,unique);*file=17;cursor=0;return inputLength;}
void Cbuf_AddText(const char *text) {printf("CBUF_ADD %s\n",text);}
void Cbuf_Execute(void) {puts("CBUF_EXECUTE");}

int main(void) {
 char operation[32],text[131074];
 while(scanf("%31s",operation)==1) {
  if(!strcmp(operation,"reset")) {
   memset(&cl,0,sizeof(cl));memset(&clc,0,sizeof(clc));memset(&cls,0,sizeof(cls));
   memset(variables,0,sizeof(variables));memset(values,0,sizeof(values));variableCount=0;
   clc.state=CA_DISCONNECTED;clc.compat=clc.netchan.compat=qtrue;cl.gameState.dataCount=1;
   cl_paused=&zero;cl_shownet=&zero;cl_autoRecordDemo=&zero;cl_timedemo=&zero;
   inputLength=cursor=outputLength=0;puts("RESET");
  } else if(!strcmp(operation,"input")) {if(scanf("%131073s",text)!=1)return 2;inputLength=unhex(text,input,sizeof(input));cursor=0;puts("INPUT");
  } else if(!strcmp(operation,"set")) {char name[128];if(scanf("%127s %131073s",name,text)!=2)return 2;byte value[512];int size=unhex(text,value,sizeof(value)-1);value[size]=0;Cvar_Set(name,(char*)value);
  } else if(!strcmp(operation,"play")) {if(scanf("%127s",demoArgument)!=1)return 2;strcpy(commandName,"demo");if(!setjmp(escape))CL_PlayDemo_f();
  } else if(!strcmp(operation,"disconnect")) {int show;if(scanf("%d",&show)!=1)return 2;if(!setjmp(escape))CL_Disconnect(show);
  } else if(!strcmp(operation,"complete")) {if(!setjmp(escape))DemoOracleOriginalCompleted();
  } else if(!strcmp(operation,"keys")) {printf("KEYS escape%d letter%d space%d up%d down%d left%d right%d mouse1%d f1%d tab%d enter%d\n",K_ESCAPE,'a',' ',K_UPARROW,K_DOWNARROW,K_LEFTARROW,K_RIGHTARROW,K_MOUSE1,K_F1,K_TAB,K_ENTER);
  } else if(!strcmp(operation,"key")) {int key,down;if(scanf("%d %d",&key,&down)!=2)return 2;if(!setjmp(escape))CL_KeyEvent(key,down,1234);
  } else if(!strcmp(operation,"catcher")) {int catcher;if(scanf("%d",&catcher)!=1)return 2;Key_SetCatcher(catcher);
  } else if(!strcmp(operation,"active")) {clc.state=CA_ACTIVE;puts("ACTIVE");
  } else if(!strcmp(operation,"state")) {
   printf("STATE client%d playing%d recording%d file%d compat%d serverId%d\n",clc.state,clc.demoplaying,clc.demorecording,clc.demofile,clc.compat,cl.serverId);
   printf("DEMONAME ");hex((byte*)clc.demoName,(int)strlen(clc.demoName));putchar('\n');
   for(int i=0;i<variableCount;i++)printf("VAR %s %s flags%d\n",names[i],values[i],variables[i].flags);
  } else return 2;
  puts("END");fflush(stdout);
 }
}
