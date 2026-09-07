/* Authored in-memory I/O observer. No filesystem, network, renderer or native routine recipes. */
#include "qcommon/q_shared.h"
#include "qcommon/qcommon.h"
#include "client/client.h"
#include <setjmp.h>
#include <stdio.h>
#include <stdlib.h>
#include <stdarg.h>

extern void CL_Record_f(void);
extern void CL_WriteDemoMessage(msg_t *message, int headerBytes);
extern void CL_ParseSnapshot(msg_t *message);
extern void CL_PacketEvent(netadr_t from,msg_t *message);
static byte input[65536], output[1048576];
static int inputLength, cursor, outputLength, argcValue=2;
static int parseWaiting=-1;
static jmp_buf escape;
static cvar_t zero;
static cvar_t modernProtocol={.integer=71},legacyProtocol={.integer=68};
cvar_t *cl_paused=&zero, *com_protocol=&modernProtocol, *com_legacyprotocol=&legacyProtocol;

static void hex(const byte *bytes,int length) {
  if(!length) putchar('-');
  for(int i=0;i<length;i++) printf("%02x",bytes[i]);
}
static int unhex(const char *text,byte *bytes,int capacity) {
  if(!strcmp(text,"-"))return 0;
  int length=(int)strlen(text);
  if(length%2||length/2>capacity)exit(2);
  for(int i=0;i<length/2;i++){unsigned value;if(sscanf(text+2*i,"%2x",&value)!=1)exit(2);bytes[i]=(byte)value;}
  return length/2;
}
void QDECL Com_Printf(const char *format,...) {
  char text[4096];va_list args;va_start(args,format);vsnprintf(text,sizeof(text),format,args);va_end(args);
  fputs("PRINT ",stdout);hex((byte*)text,(int)strlen(text));putchar('\n');
}
void QDECL Com_DPrintf(const char *format,...) {(void)format;}
void QDECL Com_Error(int code,const char *format,...) {
  char text[4096];va_list args;va_start(args,format);vsnprintf(text,sizeof(text),format,args);va_end(args);
  printf("ERROR %d ",code);hex((byte*)text,(int)strlen(text));putchar('\n');longjmp(escape,1);
}
void CL_DemoCompleted(void) {puts("COMPLETED");longjmp(escape,2);}
void CL_ParseServerMessage(msg_t *message) {
  printf("PARSE %d %d %d %d ",clc.serverMessageSequence,message->cursize,message->readcount,message->bit);
  hex(message->data,message->cursize);putchar('\n');
  if(parseWaiting>=0)clc.demowaiting=parseWaiting;
}
void CL_ConnectionlessPacket(netadr_t from,msg_t *message) {(void)from;(void)message;puts("CONNECTIONLESS");}
qboolean CL_Netchan_Process(netchan_t *channel,msg_t *message) {(void)channel;message->readcount=4;puts("CHANNEL_ACCEPT");return qtrue;}
qboolean NET_CompareAdr(netadr_t first,netadr_t second) {(void)first;(void)second;return qtrue;}
const char *NET_AdrToString(netadr_t address) {(void)address;return "fixture";}
const char *NET_AdrToStringwPort(netadr_t address) {(void)address;return "fixture:27960";}
int FS_Read(void *target,int length,fileHandle_t file) {
  printf("READ %d %d %d\n",file,length,cursor);
  if(length<0||length>MAX_MSGLEN){puts("UNSAFE_READ_REQUEST");longjmp(escape,3);}
  int available=inputLength-cursor;if(length>available)length=available;
  memcpy(target,input+cursor,(size_t)length);cursor+=length;return length;
}
int FS_Write(const void *data,int length,fileHandle_t file) {
  printf("WRITE %d %d ",file,length);
  if(length<0||length>MAX_MSGLEN||outputLength+length>(int)sizeof(output)){puts("UNSAFE_WRITE_REQUEST");longjmp(escape,3);}
  hex(data,length);putchar('\n');memcpy(output+outputLength,data,(size_t)length);outputLength+=length;return length;
}
void FS_FCloseFile(fileHandle_t file) {printf("CLOSE %d\n",file);}
fileHandle_t FS_FOpenFileWrite_HomeData(const char *name) {printf("OPEN %s\n",name);return 17;}
qboolean FS_FileExists(const char *name) {printf("EXISTS %s\n",name);return qfalse;}
qboolean FS_FileExists_HomeData(const char *name) {printf("EXISTS %s\n",name);return qfalse;}
qboolean NET_IsLocalAddress(netadr_t address) {(void)address;return qfalse;}
int Cmd_Argc(void) {return argcValue;}
char *Cmd_Argv(int index) {return index==0?"record":index==1?"fixture":"";}
void Cvar_Set(const char *name,const char *value) {printf("SET %s %s\n",name,value);}
void Cvar_SetValue(const char *name,float value) {printf("SETVALUE %s %.9g\n",name,value);}
int Cvar_VariableIntegerValue(const char *name) {(void)name;return 0;}
float Cvar_VariableValue(const char *name) {printf("CVAR %s\n",name);return 0;}
cvar_t *Cvar_Get(const char *name,const char *value,int flags) {(void)name;(void)value;(void)flags;return &zero;}
void *S_MallocDebug(int size,char *label,char *file,int line) {(void)label;(void)file;(void)line;return calloc(1,(size_t)size);}
void Z_Free(void *memory) {free(memory);}
int Sys_Milliseconds(void) {return 1234;}

int main(void) {
  char operation[24],data[131074];
  while(scanf("%23s",operation)==1) {
    if(!strcmp(operation,"reset")) {
      memset(&cl,0,sizeof(cl));memset(&clc,0,sizeof(clc));memset(&cls,0,sizeof(cls));
      clc.state=CA_ACTIVE;clc.compat=qtrue;clc.netchan.compat=qtrue;clc.demofile=17;
      cl.gameState.dataCount=1;cl_paused=&zero;cl_autoRecordDemo=&zero;cl_shownet=&zero;
      inputLength=cursor=outputLength=0;puts("RESET");
    } else if(!strcmp(operation,"input")) {
      if(scanf("%131073s",data)!=1)return 2;inputLength=unhex(data,input,sizeof(input));cursor=0;puts("INPUT");
    } else if(!strcmp(operation,"read")) {
      int result=setjmp(escape);if(!result)CL_ReadDemoMessage();printf("RETURN %d cursor%d sequence%d packetTime%d\n",result,cursor,clc.serverMessageSequence,clc.lastPacketTime);
    } else if(!strcmp(operation,"write")) {
      int header,sequence;if(scanf("%d %d %131073s",&header,&sequence,data)!=3)return 2;
      byte bytes[MAX_MSGLEN]={0};msg_t message;MSG_Init(&message,bytes,sizeof(bytes));message.cursize=unhex(data,bytes,sizeof(bytes));
      if(header<0||header>message.cursize)return 2;clc.serverMessageSequence=sequence;
      if(!setjmp(escape))CL_WriteDemoMessage(&message,header);
    } else if(!strcmp(operation,"packet")) {
      int waitingAfter;
      if(scanf("%d %131073s",&waitingAfter,data)!=2||waitingAfter<0||waitingAfter>1)return 2;
      byte bytes[MAX_MSGLEN]={0};msg_t message;MSG_InitOOB(&message,bytes,sizeof(bytes));message.cursize=unhex(data,bytes,sizeof(bytes));
      netadr_t from={0};from.type=NA_IP;parseWaiting=waitingAfter;
      if(!setjmp(escape))CL_PacketEvent(from,&message);parseWaiting=-1;
    } else if(!strcmp(operation,"record")) {
      int state,compat,sequence,reliable,commands,client,feed;
      if(scanf("%d %d %d %d %d %d %d",&state,&compat,&sequence,&reliable,&commands,&client,&feed)!=7)return 2;
      clc.state=state;clc.compat=compat;clc.netchan.compat=compat;clc.serverMessageSequence=sequence;
      clc.reliableSequence=reliable;clc.serverCommandSequence=commands;clc.clientNum=client;clc.checksumFeed=feed;
      if(!setjmp(escape))CL_Record_f();
    } else if(!strcmp(operation,"cs")) {
      int index;if(scanf("%d %131073s",&index,data)!=2||index<0||index>=MAX_CONFIGSTRINGS)return 2;
      byte bytes[MAX_GAMESTATE_CHARS];int length=unhex(data,bytes,sizeof(bytes));
      if(cl.gameState.dataCount+length+1>MAX_GAMESTATE_CHARS)return 2;
      cl.gameState.stringOffsets[index]=cl.gameState.dataCount;
      memcpy(cl.gameState.stringData+cl.gameState.dataCount,bytes,(size_t)length);
      cl.gameState.dataCount+=length;cl.gameState.stringData[cl.gameState.dataCount++]=0;puts("CS");
    } else if(!strcmp(operation,"baseline")) {
      int number,kind;float x;if(scanf("%d %d %f",&number,&kind,&x)!=3||number<0||number>=MAX_GENTITIES)return 2;
      cl.entityBaselines[number].number=number;cl.entityBaselines[number].eType=kind;cl.entityBaselines[number].origin[0]=x;puts("BASELINE");
    } else if(!strcmp(operation,"stop")) {
      if(!setjmp(escape))CL_StopRecord_f();
    } else if(!strcmp(operation,"clock")) {
      if(scanf("%d",&cls.realtime)!=1)return 2;puts("CLOCK");
    } else if(!strcmp(operation,"file")) {
      if(scanf("%d",&clc.demofile)!=1)return 2;puts("FILE");
    } else if(!strcmp(operation,"snapshot")) {
      int sequence,delta,flags,time,command;
      if(scanf("%d %d %d %d %d",&sequence,&delta,&flags,&time,&command)!=5||delta<0||delta>255)return 2;
      clc.serverMessageSequence=sequence;
      byte bytes[MAX_MSGLEN]={0};msg_t message;playerState_t player={0};player.commandTime=command;
      MSG_Init(&message,bytes,sizeof(bytes));MSG_WriteLong(&message,time);MSG_WriteByte(&message,delta);
      MSG_WriteByte(&message,flags);MSG_WriteByte(&message,0);MSG_WriteDeltaPlayerstate(&message,NULL,&player);
      MSG_WriteBits(&message,ENTITYNUM_NONE,GENTITYNUM_BITS);MSG_BeginReading(&message);
      if(!setjmp(escape))CL_ParseSnapshot(&message);
      printf("SNAPSHOT valid%d number%d delta%d waiting%d recording%d\n",cl.snap.valid,cl.snap.messageNum,cl.snap.deltaNum,clc.demowaiting,clc.demorecording);
    } else if(!strcmp(operation,"state")) {
      printf("STATE state%d recording%d playing%d waiting%d file%d bytes%d\n",clc.state,clc.demorecording,clc.demoplaying,clc.demowaiting,clc.demofile,outputLength);
      fputs("OUTPUT ",stdout);hex(output,outputLength);putchar('\n');
    } else return 2;
    puts("END");fflush(stdout);
  }
}
