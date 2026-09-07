/* Authored out-packet metadata fixtures around unchanged CL_ParseSnapshot. */
#define SNAPSHOT_ORACLE_NO_MAIN
#include "SnapshotOracle.c"

cvar_t *cl_packetdup=&zero, *cl_showSend=&zero, *cl_nodelta=&zero;
void Cvar_Set(const char *name,const char *value) { abort(); }
void CL_Netchan_Transmit(netchan_t *channel,msg_t *message) {
  int slot=channel->outgoingSequence & PACKET_MASK;
  printf("TRANSMIT slot%d command%d time%d realtime%d bytes%d\n",slot,cl.outPackets[slot].p_cmdNumber,cl.outPackets[slot].p_serverTime,cl.outPackets[slot].p_realtime,message->cursize);
  channel->outgoingSequence++;
}

int main(void) {
  char operation[24];
  while (scanf("%23s", operation) == 1) {
    if (!strcmp(operation,"reset")) {
      memset(&cl,0,sizeof(cl));memset(&clc,0,sizeof(clc));memset(&cls,0,sizeof(cls));
      clc.state=CA_ACTIVE;clc.netchan.compat=qtrue;clc.compat=qtrue;puts("RESET");
    } else if (!strcmp(operation,"packet")) {
      int index,command,time,realtime;
      if(scanf("%d %d %d %d",&index,&command,&time,&realtime)!=4||index<0||index>=PACKET_BACKUP)return 2;
      cl.outPackets[index].p_cmdNumber=command;
      cl.outPackets[index].p_serverTime=time;
      cl.outPackets[index].p_realtime=realtime;
      puts("PACKET");
    } else if (!strcmp(operation,"parse")) {
      int outgoing,realtime,commandTime,serverTime,number;
      if(scanf("%d %d %d %d %d",&outgoing,&realtime,&commandTime,&serverTime,&number)!=5)return 2;
      clc.netchan.outgoingSequence=outgoing;cls.realtime=realtime;clc.serverMessageSequence=number;
      byte buffer[MAX_MSGLEN]={0};msg_t message;playerState_t player={0};player.commandTime=commandTime;
      MSG_Init(&message,buffer,MAX_MSGLEN);
      MSG_WriteLong(&message,serverTime);MSG_WriteByte(&message,0);MSG_WriteByte(&message,0);MSG_WriteByte(&message,0);
      MSG_WriteDeltaPlayerstate(&message,NULL,&player);MSG_WriteBits(&message,ENTITYNUM_NONE,GENTITYNUM_BITS);
      MSG_BeginReading(&message);CL_ParseSnapshot(&message);
      printf("PING %d valid%d number%d time%d command%d\n",cl.snap.ping,cl.snap.valid,cl.snap.messageNum,cl.snap.serverTime,cl.snap.ps.commandTime);
    } else if (!strcmp(operation,"write")) {
      int outgoing,realtime,number,count,time;
      if(scanf("%d %d %d %d %d",&outgoing,&realtime,&number,&count,&time)!=5||count<0||count>32||number<count)return 2;
      clc.netchan.outgoingSequence=outgoing;cls.realtime=realtime;cl.cmdNumber=number;
      cl.outPackets[(outgoing-1)&PACKET_MASK].p_cmdNumber=number-count;
      for(int i=0;i<count;i++){cl.cmds[(number-i)&CMD_MASK].serverTime=time-i;}
      CL_WritePacket();
      printf("WRITTEN next%d\n",clc.netchan.outgoingSequence);
    } else return 2;
    fflush(stdout);
  }
}
