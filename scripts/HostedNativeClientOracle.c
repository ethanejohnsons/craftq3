/* Authored frame/FS controller; unchanged native netchan/XOR, writer, parser and config commands. */
#include "client/client.h"
#include <stdio.h>
#include <stdlib.h>
extern qboolean CL_GetServerCommand(int number);
static cvar_t zero;
cvar_t *cl_packetdup=&zero,*cl_showSend=&zero,*cl_nodelta=&zero,*com_sv_running=&zero;
static int now,loaded,executed;
int Sys_Milliseconds(void) { return now; }
void CL_InitDownloads(void) { loaded++; }
void CL_AddReliableCommand(const char *text,qboolean disconnect) {
 (void)disconnect;if(clc.reliableSequence-clc.reliableAcknowledge>=MAX_RELIABLE_COMMANDS)abort();
 Q_strncpyz(clc.reliableCommands[++clc.reliableSequence&(MAX_RELIABLE_COMMANDS-1)],text,MAX_STRING_CHARS);
}
void Con_ClearNotify(void) {}
void S_ClearSoundBuffer(void) {}
void Cbuf_AddText(const char *text) { printf("QUEUED %s\n",text); }
static int unhex(char *text,byte *bytes,int capacity) {
 size_t n=strcspn(text,"\r\n");if(n%2||n/2>=(size_t)capacity)exit(2);
 for(size_t i=0;i<n;i+=2){unsigned v;if(sscanf(text+i,"%2x",&v)!=1)exit(2);bytes[i/2]=(byte)v;}bytes[n/2]=0;return (int)n/2;
}
int main(void) {
 char line[40000];byte bytes[MAX_MSGLEN+64];
 while(fgets(line,sizeof(line),stdin)) {
  if(!strncmp(line,"seed ",5)) {
   int challenge,qport;if(sscanf(line+5,"%d %d",&challenge,&qport)!=2)return 2;
   memset(&cl,0,sizeof(cl));memset(&clc,0,sizeof(clc));memset(&cls,0,sizeof(cls));now=loaded=executed=0;
   clc.compat=qtrue;clc.challenge=challenge;clc.state=CA_CONNECTED;
   netadr_t address={0};address.type=NA_IP;address.ip[0]=127;address.ip[3]=1;
   Netchan_Init(qport);Netchan_Setup(NS_CLIENT,&clc.netchan,address,qport,challenge,qtrue);
  } else if(!strncmp(line,"receive ",8)) {
   msg_t msg;MSG_Init(&msg,bytes,MAX_MSGLEN);msg.cursize=unhex(line+8,bytes,sizeof(bytes));int before=loaded;
   if(CL_Netchan_Process(&clc.netchan,&msg)) {
    clc.serverMessageSequence=clc.netchan.incomingSequence;
    CL_ParseServerMessage(&msg);
    if(loaded!=before){executed=clc.serverCommandSequence;clc.state=CA_PRIMED;}
    if(cl.snap.valid) {
     for(int seq=executed+1;seq<=cl.snap.serverCommandNum;seq++){CL_GetServerCommand(seq);executed=seq;}
     clc.state=CA_ACTIVE;
    }
   }
  } else if(!strncmp(line,"command ",8)) {unhex(line+8,bytes,sizeof(bytes));CL_AddReliableCommand((char*)bytes,qfalse);}
  else if(!strncmp(line,"step ",5)) {
   int serverTime,forward,yaw;if(sscanf(line+5,"%d %d %d %d",&now,&serverTime,&forward,&yaw)!=4)return 2;
   cls.realtime=now;
   if(loaded){usercmd_t *cmd=&cl.cmds[++cl.cmdNumber&CMD_MASK];memset(cmd,0,sizeof(*cmd));cmd->serverTime=serverTime;cmd->forwardmove=(signed char)forward;cmd->angles[YAW]=yaw;}
   CL_WritePacket();
  } else return 2;
  printf("STATE %d %d %d %d %d %d %d %.6f %.6f\n",loaded,cl.serverId,clc.checksumFeed,cl.snap.valid,cl.snap.messageNum,cl.snap.serverTime,clc.serverCommandSequence,cl.snap.ps.origin[0],cl.snap.ps.origin[1]);
  puts("END");fflush(stdout);
 }
}
