/* Authored observer of unchanged CL_WritePacket, before transport and legacy XOR. */
#define SNAPSHOT_ORACLE_NO_MAIN
#include "SnapshotOracle.c"

cvar_t *cl_packetdup=&zero, *cl_showSend=&zero;
static cvar_t noDelta;
cvar_t *cl_nodelta=&noDelta;
void Cvar_Set(const char *name,const char *value) { abort(); }
void CL_Netchan_Transmit(netchan_t *channel,msg_t *message) {
  /* The native stack buffer can leave the trailing, wholly unused byte uninitialized. */
  byte captured[MAX_MSGLEN];memcpy(captured,message->data,(size_t)message->cursize);
  captured[message->bit>>3]&=(byte)((1u<<(message->bit&7))-1u);
  printf("body %d ",message->bit);hex(captured,message->cursize);putchar('\n');
}

int main(void) {
  int serverId,messageAck,serverAck,feed,noDeltaValue,count,commandCount;
  char text[MAX_MSGLEN*2+2];byte decoded[MAX_MSGLEN+1];
  while(scanf("%d %d %d %d %d %d %d %32769s",&serverId,&messageAck,&serverAck,&feed,&noDeltaValue,&count,&commandCount,text)==8) {
    if(count<0||count>32||commandCount<0||commandCount>64) return 2;
    memset(&cl,0,sizeof(cl));memset(&clc,0,sizeof(clc));memset(&cls,0,sizeof(cls));
    memset(decoded,0,sizeof(decoded));int length=decode(text,decoded);if(length>=MAX_STRING_CHARS)return 2;
    memcpy(clc.serverCommands[serverAck&(MAX_RELIABLE_COMMANDS-1)],decoded,(size_t)length);
    cl.serverId=serverId;clc.serverMessageSequence=messageAck;clc.serverCommandSequence=serverAck;clc.checksumFeed=feed;
    cl.snap.valid=qtrue;cl.snap.messageNum=messageAck;clc.state=CA_ACTIVE;noDelta.integer=noDeltaValue;
    cl.cmdNumber=count;clc.netchan.outgoingSequence=1;clc.netchan.compat=qtrue;clc.compat=qtrue;
    for(int i=1;i<=commandCount;i++) {
      if(scanf("%32769s",text)!=1)return 2;memset(decoded,0,sizeof(decoded));length=decode(text,decoded);if(length>=MAX_STRING_CHARS)return 2;
      memcpy(clc.reliableCommands[i&(MAX_RELIABLE_COMMANDS-1)],decoded,(size_t)length);
    }
    clc.reliableSequence=commandCount;
    for(int i=1;i<=count;i++)readState(&cl.cmds[i&CMD_MASK],sizeof(usercmd_t));
    CL_WritePacket();fflush(stdout);
  }
  return 0;
}
