/* Authored fixture: public structs/signatures and unchanged SV_ExecuteClientMessage only. */
#include "server/server.h"
#include <stdio.h>
#include <stdlib.h>
#include <stdarg.h>
#include <setjmp.h>
server_t sv;
serverStatic_t svs;
static client_t client;
static cvar_t pure, flood, maxclients, dedicated, pause, running;
cvar_t *sv_pure=&pure,*sv_floodProtect=&flood,*sv_maxclients=&maxclients;
cvar_t *com_dedicated=&dedicated,*cl_paused=&pause,*com_cl_running=&running;
static jmp_buf failure;
void QDECL Com_Printf(const char *format,...) { (void)format; }
void QDECL Com_DPrintf(const char *format,...) { (void)format; }
void QDECL Com_Error(int code,const char *format,...) { printf("ERROR %d\n",code);longjmp(failure,1); }
void SV_DropClient(client_t *cl,const char *reason) { printf("DROP %s\n",reason);cl->state=CS_ZOMBIE; }
void SV_SendClientGameState(client_t *cl) { (void)cl;puts("GAMESTATE"); }
void SV_ClientEnterWorld(client_t *cl,usercmd_t *cmd) { printf("ENTER %d\n",cmd->serverTime);cl->state=CS_ACTIVE;cl->lastUsercmd=*cmd; }
void SV_ClientThink(client_t *cl,usercmd_t *cmd) { printf("THINK %d\n",cmd->serverTime);cl->lastUsercmd=*cmd; }
void SV_ExecuteClientCommand(client_t *cl,const char *text,qboolean allowed) { (void)cl;printf("COMMAND %d %s\n",allowed,text); }
void SV_SendClientSnapshot(client_t *cl) { (void)cl;puts("SNAPSHOT"); }
int Cvar_VariableIntegerValue(const char *name) { (void)name;return 0; }
int main(void) {
 char line[40000];byte bytes[MAX_MSGLEN];svs.clients=&client;maxclients.integer=1;
 while(fgets(line,sizeof(line),stdin)) {
  if(!strncmp(line,"seed ",5)) {
   memset(&client,0,sizeof(client));memset(&sv,0,sizeof(sv));svs.time=1000;
   if(sscanf(line+5,"%d %d %d %d %d %d %d %d %d %d %d",&sv.serverId,&sv.restartedServerId,(int*)&client.state,&client.reliableSequence,&client.netchan.outgoingSequence,&client.gamestateMessageNum,&client.lastClientCommand,&client.lastUsercmd.serverTime,&pure.integer,&client.gotCP,&client.pureAuthentic)!=11)return 2;
   sv.checksumFeed=12345;client.compat=qtrue;client.reliableSent=client.reliableSequence;client.deltaMessage=-1;
   for(int i=0;i<MAX_RELIABLE_COMMANDS;i++)snprintf(client.reliableCommands[i],MAX_STRING_CHARS,"key%d",i);
   puts("SEEDED");
  } else if(!strncmp(line,"packet ",7)) {
   char *hex=line+7;size_t length=strcspn(hex,"\r\n");if(length%2||length/2>sizeof(bytes))return 2;
   for(size_t i=0;i<length;i+=2){unsigned value;if(sscanf(hex+i,"%2x",&value)!=1)return 2;bytes[i/2]=(byte)value;}
   msg_t msg;MSG_Init(&msg,bytes,sizeof(bytes));msg.cursize=(int)length/2;MSG_BeginReading(&msg);
   if(!setjmp(failure))SV_ExecuteClientMessage(&client,&msg);
   printf("STATE state%d msgAck%d reliableAck%d clientCmd%d lastTime%d delta%d bit%d\n",client.state,client.messageAcknowledge,client.reliableAcknowledge,client.lastClientCommand,client.lastUsercmd.serverTime,client.deltaMessage,msg.bit);
  } else return 2;
  fflush(stdout);
 }
}
