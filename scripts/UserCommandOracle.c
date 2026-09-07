/* Authored observer for unchanged native keyed user-command delta APIs. */
#define main framing_observer_unused_main
#include "NetchanOracle.c"
#undef main
int main(void) {
  char before[49],after[49];unsigned key;int prefix;
  byte bytes[MAX_MSGLEN+64];
  while(scanf("%u %d %48s %48s",&key,&prefix,before,after)==4) {
    usercmd_t from={0},to={0},result={0};
    if(strcmp(before,"-") && decode(before,(byte*)&from)!=sizeof(from))return 2;
    if(decode(after,(byte*)&to)!=sizeof(to))return 2;
    memset(bytes,0,sizeof(bytes));msg_t message;MSG_Init(&message,bytes,MAX_MSGLEN);
    if(prefix)MSG_WriteBits(&message,(1<<prefix)-1,prefix);
    MSG_WriteDeltaUsercmdKey(&message,(int)key,&from,&to);
    printf("WIRE %d ",message.bit);hex(bytes,message.cursize);putchar('\n');
    MSG_BeginReading(&message);if(prefix)MSG_ReadBits(&message,prefix);
    MSG_ReadDeltaUsercmdKey(&message,(int)key,&from,&result);
    printf("STATE %d ",message.bit);hex((byte*)&result,sizeof(result));putchar('\n');fflush(stdout);
  }
  return 0;
}
