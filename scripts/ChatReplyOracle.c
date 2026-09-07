/* Authored reply-selection observer with controlled random samples and caller-supplied variables. */
#define CHAT_ORACLE_ENTRY chat_match_unused_main
#define AAS_Time unused_match_time
#include "ChatMatchOracle.c"
#undef AAS_Time

static float oracle_time;
float AAS_Time(void){return oracle_time;}

static int random_bits,draws,tail_bits=-1;
static unsigned random_state;
static int sequence_mode;
int rand(void){draws++;if(sequence_mode){random_state=random_state*1664525u+1013904223u;return (int)((random_state>>17)%32767u);}return draws>1&&tail_bits>=0?tail_bits:random_bits;}
static char *read_text(char *buffer,int capacity) {
  char hex[8193];if(scanf("%8192s",hex)!=1)abort();if(!strcmp(hex,"~"))return NULL;
  memset(buffer,0,(size_t)capacity);
  if(unhex(hex,buffer,capacity)<0)abort();return buffer;
}
int main(int argc,char **argv) {
  if(argc!=2)return 2;root=argv[1];botimport.Print=print;botimport.FS_FOpenFile=fsopen;botimport.FS_Read=fsread;botimport.FS_FCloseFile=fsclose;
  if(BotSetupChatAI())return 3;int state=BotAllocChatState(),other=BotAllocChatState(),primary=state;BotSetChatName(state,"TestBot",3);BotSetChatName(other,"OtherBot",4);
  puts("READY");fflush(stdout);char command[32],message[4097],variables[8][4097],*values[8],output[256];int context,vcontext,value;
  while(scanf("%31s",command)==1) {
    if(!strcmp(command,"name")){BotSetChatName(state,read_text(message,sizeof(message)),3);puts("NAME");}
    else if(!strcmp(command,"other")){BotSetChatName(other,read_text(message,sizeof(message)),4);puts("OTHER");}
    else if(!strcmp(command,"gender")){if(scanf("%d",&value)!=1)return 2;BotSetChatGender(state,value);puts("GENDER");}
    else if(!strcmp(command,"seed")){if(scanf("%d",&random_bits)!=1)return 2;sequence_mode=0;puts("SEED");}
    else if(!strcmp(command,"rng")){if(scanf("%u",&random_state)!=1)return 2;sequence_mode=1;puts("RNG");}
    else if(!strcmp(command,"tail")){if(scanf("%d",&tail_bits)!=1)return 2;puts("TAIL");}
    else if(!strcmp(command,"time")){if(scanf("%f",&oracle_time)!=1)return 2;puts("TIME");}
    else if(!strcmp(command,"state")){if(scanf("%d",&value)!=1)return 2;state=value?other:primary;puts("STATE");}
    else if(!strcmp(command,"unify")) {
      if(!read_text(message,sizeof(message)))return 2;UnifyWhiteSpaces(message);
      fputs("UNIFIED ",stdout);for(unsigned char*p=(unsigned char*)message;*p;p++)printf("%02x",*p);putchar('\n');
    }
    else if(!strcmp(command,"reply")) {
      if(scanf("%d %d",&context,&vcontext)!=2)return 2;if(!read_text(message,sizeof(message)))return 2;
      for(int i=0;i<8;i++)values[i]=read_text(variables[i],sizeof(variables[i]));
      draws=0;int result=BotReplyChat(state,message,context,vcontext,values[0],values[1],values[2],values[3],values[4],values[5],values[6],values[7]);
      printf("REPLY %d LENGTH %d DRAWS %d\n",result,BotChatLength(state),draws);
      BotGetChatMessage(state,output,sizeof(output));fputs("TEXT ",stdout);for(unsigned char*p=(unsigned char*)output;*p;p++)printf("%02x",*p);putchar('\n');
    } else return 2;
    fflush(stdout);
  }
  return 0;
}
