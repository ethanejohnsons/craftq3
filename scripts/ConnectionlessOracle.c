/* Authored capture-only observer over unchanged native OOB formatting and MSG APIs.
 * Sys_SendPacket is the inherited byte-capture stub; no socket is opened. */
#define main unused_netchan_main
#define Q_strncpyz Oracle_Q_strncpyz
#define ShortSwap Oracle_ShortSwap
#define va Oracle_va
#include "NetchanOracle.c"
#undef main
#undef Q_strncpyz
#undef ShortSwap
#undef va
int main(void) {
 Netchan_Init(12345);
 char operation[16],encoded[MAX_MSGLEN*2+2];byte input[MAX_MSGLEN+1];
 while(scanf("%15s %32769s",operation,encoded)==2) {
  memset(input,0,sizeof(input));int length=decode(encoded,input);
  if(!strcmp(operation,"print")) {
   netadr_t address={0};address.type=NA_IP;
   NET_OutOfBandPrint(NS_CLIENT,address,"%s",(char*)input);
  } else if(!strcmp(operation,"line")) {
   msg_t message;MSG_InitOOB(&message,input,sizeof(input));message.cursize=length;
   MSG_BeginReadingOOB(&message);int marker=MSG_ReadLong(&message);
   char *text=MSG_ReadStringLine(&message);
   printf("line %d %d %d ",marker,message.readcount,message.bit);hex((byte*)text,(int)strlen(text));
   int remaining=message.readcount>length?length:message.readcount;
   putchar(' ');hex(input+remaining,length-remaining);putchar('\n');
  } else if(!strcmp(operation,"prefix")) {
   msg_t message;MSG_InitOOB(&message,input,sizeof(input));message.cursize=length;
   MSG_BeginReadingOOB(&message);int marker=MSG_ReadLong(&message);
   printf("prefix %d %d %d\n",marker,message.readcount,message.bit);
  } else return 2;
  fflush(stdout);
 }
 return 0;
}
