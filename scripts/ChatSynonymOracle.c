/* Authored black-box chat synonym observer. No engine routine implementations are included.
 * Link unchanged be_ai_chat/q_shared and unfused l_script/l_precomp objects from the
 * official ignored source checkout; see docs/BOTLIB_CHAT_SYNONYMS.md.
 * Inputs are zero-padded so token scans cannot consume stale prior-command text.
 */
#include "qcommon/q_shared.h"
#include "botlib/botlib.h"
#include "botlib/be_ai_chat.h"
#include <stdlib.h>
#include <stdio.h>
#include <stdarg.h>
#include <strings.h>
#include <archive.h>
#include <archive_entry.h>
botlib_import_t botimport;
static const char *root;
static FILE *files[128];
static float reload;
void *GetMemory(unsigned long n) { return malloc(n); }
void *GetClearedMemory(unsigned long n) { return calloc(1,n); }
void FreeMemory(void *p) { free(p); }
float LibVarGetValue(const char *name) { return strcmp(name,"bot_reloadcharacters")==0?reload:0; }
void Log_Write(char *format, ...) { (void)format; }
void Com_Printf(const char *fmt,...) {va_list v;va_start(v,fmt);vfprintf(stderr,fmt,v);va_end(v);}
void Com_Error(int level,const char *fmt,...) { (void)level;va_list v;va_start(v,fmt);vfprintf(stderr,fmt,v);va_end(v);abort(); }
static void print(int level,char *fmt,...) {(void)level;va_list v;va_start(v,fmt);vfprintf(stderr,fmt,v);va_end(v);}
static int fsopen(const char *path,fileHandle_t *handle,fsMode_t mode) {
 if(mode!=FS_READ)abort();char actual[4096];snprintf(actual,sizeof(actual),"%s/%s",root,path);
 FILE *file=NULL;
 if(strstr(root,".pk3")) {
  struct archive *zip=archive_read_new();archive_read_support_format_zip(zip);archive_read_support_filter_all(zip);
  if(archive_read_open_filename(zip,root,10240)!=ARCHIVE_OK)abort();
  struct archive_entry *entry;
  while(archive_read_next_header(zip,&entry)==ARCHIVE_OK) {
   if(!strcmp(archive_entry_pathname(entry),path)) {
    file=tmpfile();if(!file)abort();char buffer[8192];long n;
    while((n=archive_read_data(zip,buffer,sizeof(buffer)))>0)fwrite(buffer,1,n,file);
    if(n<0)abort();fflush(file);break;
   }
   archive_read_data_skip(zip);
  }
  archive_read_free(zip);
 } else file=fopen(actual,"rb");
 if(!file){*handle=0;return -1;}
 fseek(file,0,SEEK_END);long n=ftell(file);rewind(file);
 for(int i=1;i<128;i++)if(!files[i]){files[i]=file;*handle=i;return (int)n;}
 abort();
}
static int fsread(void *buffer,int n,fileHandle_t h){return (int)fread(buffer,1,n,files[h]);}
static void fsclose(fileHandle_t h){fclose(files[h]);files[h]=NULL;}
extern char *StringContainsWord(char*,char*,int);
extern int IsWhiteSpace(char);
extern void StringReplaceWords(char*,char*,char*);
int botDeveloper;
static float now;
static int randomValue, randomCalls;
extern void BotReplaceWeightedSynonyms(char*,unsigned long);
int rand(void) { randomCalls++;return randomValue; }
float AAS_Time(void) { return now; }
void *GetClearedHunkMemory(unsigned long n) { return calloc(1,n); }
float LibVarValue(const char *name,const char *value) { return atof(value); }
char *LibVarString(const char *name,const char *value) { fprintf(stderr,"VAR %s %s\n",name,value);return (char*)value; }
FILE *Log_FilePointer(void) { return stderr; }
void EA_Command(int client,char *command) { printf("COMMAND %d %s\n",client,command); }
int main(int argc,char **argv) {
 if(argc!=4)return 2;root=argv[1];botimport.Print=print;botimport.FS_FOpenFile=fsopen;botimport.FS_Read=fsread;botimport.FS_FCloseFile=fsclose;
 printf("EMPTY %d %d\n",StringContains("abc","",0),StringContains("","",0));srand(1);printf("SETUP %d\n",BotSetupChatAI());int cs=BotAllocChatState();printf("STATE %d LOAD %d\n",cs,BotLoadChatFile(cs,argv[2],argv[3]));BotSetChatName(cs,"TestBot",3);
 char line[4096],word[1024],text[1024],b[1024];int a;bot_consolemessage_t msg;
 while(fgets(line,sizeof(line),stdin)) {
  memset(word,0,sizeof(word));memset(text,0,sizeof(text));memset(b,0,sizeof(b));
  if(sscanf(line,"initial %1023s %d",word,&a)==2) {printf("COUNT %d\n",BotNumInitialChats(cs,word));BotInitialChat(cs,word,a,"ZERO","ONE","TWO",NULL,NULL,NULL,NULL,NULL);printf("LENGTH %d\n",BotChatLength(cs));}
  else if(!strncmp(line,"get",3)){BotGetChatMessage(cs,b,sizeof(b));printf("MESSAGEHEX ");for(unsigned char *p=(unsigned char*)b;*p;p++)printf("%02x",*p);printf(" LENGTH %d\n",BotChatLength(cs));}
  else if(sscanf(line,"queue %d %1023[^\n]",&a,text)==2){BotQueueConsoleMessage(cs,a,text);printf("QUEUED %d\n",BotNumConsoleMessages(cs));}
  else if(!strncmp(line,"next",4)){int result=BotNextConsoleMessage(cs,&msg);printf("NEXT %d %d %.9g %d [%s]\n",result,msg.handle,msg.time,msg.type,msg.message);}
  else if(sscanf(line,"remove %d",&a)==1){BotRemoveConsoleMessage(cs,a);}
  else if(sscanf(line,"time %f",&now)==1){}
  else if(sscanf(line,"weighted %d %1023[^\n]",&a,text)==2){randomCalls=0;BotReplaceWeightedSynonyms(text,a);printf("WEIGHTED [%s] CALLS %d\n",text,randomCalls);}
  else if(sscanf(line,"replace %d %1023[^\n]",&a,text)==2){BotReplaceSynonyms(text,a);printf("REPLACED [%s]\n",text);}
  else if(sscanf(line,"seed %d",&a)==1){randomValue=a;randomCalls=0;}
  else if(!strncmp(line,"stats",5)){printf("CALLS %d\n",randomCalls);}
  else if(sscanf(line,"enter %d",&a)==1){BotEnterChat(cs,5,a);printf("LENGTH %d\n",BotChatLength(cs));}
  else if(!strncmp(line,"unify ",6)){strcpy(text,line+6);size_t n=strlen(text);if(n&&text[n-1]=='\n')text[n-1]=0;UnifyWhiteSpaces(text);printf("UNIFIED [%s]\n",text);}
  else if(sscanf(line,"contains %d %1023s %1023s",&a,text,word)==3){printf("CONTAINS %d\n",StringContains(text,word,a));}
  else if(sscanf(line,"word %d %1023s %1023[^\n]",&a,word,text)==3){char *found=StringContainsWord(text,word,a);printf("WORD %td\n",found?found-text:-1);}
  else if(!strncmp(line,"spaces",6)){for(int k=1;k<127;k++)if(IsWhiteSpace((char)k))printf("SPACE %d\n",k);}
  else if(sscanf(line,"findhex %1023s %1023s",word,text)==2){char *parts[]={word,text};for(int k=0;k<2;k++){size_t n=strlen(parts[k])/2;for(size_t i=0;i<n;i++){unsigned v;if(sscanf(parts[k]+2*i,"%2x",&v)!=1)abort();parts[k][i]=(char)v;}memset(parts[k]+n,0,1024-n);}char *found=StringContainsWord(text,word,0);printf("FIND %td\n",found?found-text:-1);}
  else if(sscanf(line,"wordsallhex %1023s %1023s %1023s",word,b,text)==3){char *parts[]={word,b,text};for(int k=0;k<3;k++){size_t n=strlen(parts[k])/2;for(size_t i=0;i<n;i++){unsigned v;if(sscanf(parts[k]+2*i,"%2x",&v)!=1)abort();parts[k][i]=(char)v;}memset(parts[k]+n,0,1024-n);}StringReplaceWords(text,word,b);printf("WORDSHEX ");for(unsigned char *p=(unsigned char*)text;*p;p++)printf("%02x",*p);printf("\n");}
  else if(sscanf(line,"wordshex %1023s %1023s %1023s",word,b,text)==3){size_t n=strlen(text)/2;for(size_t i=0;i<n;i++){unsigned v;if(sscanf(text+2*i,"%2x",&v)!=1)abort();text[i]=(char)v;}memset(text+n,0,sizeof(text)-n);StringReplaceWords(text,word,b);printf("WORDSHEX ");for(unsigned char *p=(unsigned char*)text;*p;p++)printf("%02x",*p);printf("\n");}
  else if(sscanf(line,"words %1023s %1023s %1023[^\n]",word,b,text)==3){StringReplaceWords(text,word,b);printf("WORDS [%s]\n",text);}
  else {printf("UNKNOWN\n");}
  fflush(stdout);
 }
 BotFreeChatState(cs);BotShutdownChatAI();return 0;
}
