/* Authored original-script match observer; native chat routines are linked unchanged. */
#define main weapon_oracle_unused_main
#define Q_strncpyz oracle_unused_strncpy
#define Q_strcat oracle_unused_strcat
#define Q_stricmp oracle_unused_stricmp
#define Com_sprintf oracle_unused_sprintf
#include "WeaponOracle.c"
#undef main
#undef Q_strncpyz
#undef Q_strcat
#undef Q_stricmp
#undef Com_sprintf
#include "botlib/be_ai_chat.h"

int botDeveloper;
float AAS_Time(void){return 0;}
FILE *Log_FilePointer(void){return stderr;}
void EA_Command(int client,char *command){fprintf(stderr,"CHAT %d %s\n",client,command);}
void Com_Printf(const char *format,...) {va_list args;va_start(args,format);vfprintf(stderr,format,args);va_end(args);}
static int unhex(const char *hex,char *text,int capacity) {
  if(!strcmp(hex,"-")){text[0]=0;return 0;}size_t count=strlen(hex);if(count%2||count/2>=(size_t)capacity)return -1;
  for(size_t i=0;i<count;i+=2){unsigned v;if(sscanf(hex+i,"%2x",&v)!=1)return -1;text[i/2]=(char)v;}text[count/2]=0;return (int)(count/2);
}
#ifndef CHAT_ORACLE_ENTRY
#define CHAT_ORACLE_ENTRY main
#endif
int CHAT_ORACLE_ENTRY(int argc,char **argv) {
  if(argc!=2)return 2;root=argv[1];botimport.Print=print;botimport.FS_FOpenFile=fsopen;botimport.FS_Read=fsread;botimport.FS_FCloseFile=fsclose;
  if(BotSetupChatAI())return 3;puts("READY");fflush(stdout);
  char command[32],hex[8193],text[4097];unsigned long context;
  while(scanf("%31s",command)==1) {
    if(!strcmp(command,"find")) {
      if(scanf("%lu %8192s",&context,hex)!=2||unhex(hex,text,sizeof(text))<0)return 2;
      bot_match_t match;memset(&match,0xab,sizeof(match));int result=BotFindMatch(text,&match,context);
      printf("MATCH %d ",result);for(size_t i=0;i<sizeof(match);i++)printf("%02x",((unsigned char*)&match)[i]);putchar('\n');
      for(int variable=0;result && variable<8;variable++) {
        char value[256];memset(value,0xab,sizeof(value));BotMatchVariable(&match,variable,value,sizeof(value));
        printf("VAR %d ",variable);for(unsigned char *p=(unsigned char*)value;*p;p++)printf("%02x",*p);putchar('\n');
      }
    } else return 2;
    fflush(stdout);
  }
  return 0;
}
