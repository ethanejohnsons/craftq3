/* Authored observer of unchanged protocol-68 netchan wrappers. No network transport is used. */
#ifdef ORACLE_CLIENT
#define SNAPSHOT_ORACLE_NO_MAIN
#include "SnapshotOracle.c"
#else
#define main framing_oracle_main
#include "NetchanOracle.c"
#undef main
#include "server/server.h"
void QDECL Com_DPrintf(const char *format, ...) { (void)format; }
#endif

static byte transmitted[MAX_MSGLEN];
static int transmittedLength;
static int receiveHeader,receiveSequence;
void Netchan_Transmit(netchan_t *channel,int length,const byte *data) {
  if(length<0||length>MAX_MSGLEN)abort();
  transmittedLength=length;memcpy(transmitted,data,(size_t)length);
}
void Netchan_TransmitNextFragment(netchan_t *channel) { abort(); }
qboolean Netchan_Process(netchan_t *channel,msg_t *message) {
  channel->incomingSequence=receiveSequence;message->readcount=receiveHeader;message->bit=receiveHeader*8;message->oob=qtrue;return qtrue;
}
#ifndef ORACLE_CLIENT
int SV_RateMsec(client_t *client) { return 0; }
#endif
void *Z_MallocDebug(int size,char *label,char *file,int line) { return calloc(1,(size_t)size); }

int main(void) {
  char role[16],data[MAX_MSGLEN*2+2],commandText[MAX_STRING_CHARS*2+2];
  byte bytes[MAX_MSGLEN+64],command[MAX_STRING_CHARS+1];
  int sequence,challenge,bits;
  while(scanf("%15s %d %d %d %32769s %2049s",role,&sequence,&challenge,&bits,data,commandText)==6) {
    if(sequence<1||bits<0||bits>MAX_MSGLEN*8-16)return 2;
    memset(bytes,0,sizeof(bytes));memset(command,0,sizeof(command));
    int length=decode(data,bytes),commandLength=decode(commandText,command);
    if(commandLength>=MAX_STRING_CHARS||bits>length*8)return 2;
    /* Zero-padded C-string state: an empty native key must not expose bytes after its NUL. */
    for(int i=0;i<commandLength;i++)if(!command[i]) {commandLength=i;break;}
    msg_t message;MSG_Init(&message,bytes,MAX_MSGLEN);message.cursize=length;message.bit=bits;
    transmittedLength=0;
#ifdef ORACLE_CLIENT
    if(!strcmp(role,"client")||!strcmp(role,"receive-client")) {
      memset(&clc,0,sizeof(clc));clc.challenge=challenge;clc.netchan.challenge=challenge;
      clc.netchan.outgoingSequence=sequence;clc.netchan.compat=qtrue;clc.compat=qtrue;
      for(int i=0;i<MAX_RELIABLE_COMMANDS;i++)memcpy(clc.serverCommands[i],command,(size_t)commandLength);
      if(!strcmp(role,"client")) CL_Netchan_Transmit(&clc.netchan,&message);
      else {
        for(int i=0;i<MAX_RELIABLE_COMMANDS;i++)memcpy(clc.reliableCommands[i],command,(size_t)commandLength);
        memmove(bytes+4,bytes,(size_t)length);memcpy(bytes,&sequence,4);message.cursize+=4;receiveHeader=4;receiveSequence=sequence;
        CL_Netchan_Process(&clc.netchan,&message);printf("decoded ");hex(bytes+4,length);putchar('\n');fflush(stdout);continue;
      }
    } else return 2;
#else
    if(!strcmp(role,"server")||!strcmp(role,"queued")||!strcmp(role,"receive-server")) {
      client_t client;memset(&client,0,sizeof(client));client.challenge=challenge;client.netchan.challenge=challenge;
      client.netchan.outgoingSequence=sequence;client.netchan.compat=qtrue;client.compat=qtrue;client.netchan_end_queue=&client.netchan_start_queue;
      memcpy(client.lastClientCommandString,command,(size_t)commandLength);
      if(!strcmp(role,"receive-server")) {
        for(int i=0;i<MAX_RELIABLE_COMMANDS;i++)memcpy(client.reliableCommands[i],command,(size_t)commandLength);
        memmove(bytes+6,bytes,(size_t)length);memcpy(bytes,&sequence,4);bytes[4]=bytes[5]=0;message.cursize+=6;receiveHeader=6;receiveSequence=sequence;
        SV_Netchan_Process(&client,&message);printf("decoded ");hex(bytes+6,length);putchar('\n');fflush(stdout);continue;
      }
      client.netchan.unsentFragments=!strcmp(role,"queued");
      SV_Netchan_Transmit(&client,&message);
      if(client.netchan.unsentFragments) {client.netchan.unsentFragments=qfalse;SV_Netchan_TransmitNextFragment(&client);}
    } else return 2;
#endif
    printf("wire %d ",message.bit);hex(transmitted,transmittedLength);putchar('\n');fflush(stdout);
  }
  return 0;
}
