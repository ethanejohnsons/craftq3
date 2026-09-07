/* Authored black-box driver; link untouched official reference objects only for local validation. */
#include "qcommon/q_shared.h"
#include "botlib/botlib.h"
#include "botlib/be_ai_char.h"
#include <stdlib.h>
#include <stdio.h>
#include <stdarg.h>
#include <strings.h>
botlib_import_t botimport;
static const char *root;
static FILE *files[128];
static float reload;
void *GetMemory(unsigned long n) { return malloc(n); }
void *GetClearedMemory(unsigned long n) { return calloc(1,n); }
void FreeMemory(void *p) { free(p); }
float LibVarGetValue(const char *name) { return strcmp(name,"bot_reloadcharacters")==0?reload:0; }
void Log_Write(char *format, ...) { (void)format; }
void Q_strncpyz(char *d,const char *s,int n) { if(n<=0)abort();snprintf(d,n,"%s",s); }
void Q_strcat(char *d,int n,const char *s) { size_t used=strlen(d); if(used>=(size_t)n)abort();snprintf(d+used,n-used,"%s",s); }
int Q_stricmp(const char *a,const char *b) { return strcasecmp(a,b); }
int Com_sprintf(char *d,int n,const char *fmt,...) {va_list v;va_start(v,fmt);int result=vsnprintf(d,n,fmt,v);va_end(v);return result;}
void Com_Error(int level,const char *fmt,...) { (void)level;va_list v;va_start(v,fmt);vfprintf(stderr,fmt,v);va_end(v);abort(); }
static void print(int level,char *fmt,...) {(void)level;va_list v;va_start(v,fmt);vfprintf(stderr,fmt,v);va_end(v);}
static int fsopen(const char *path,fileHandle_t *handle,fsMode_t mode) {
 if(mode!=FS_READ)abort();char actual[4096];snprintf(actual,sizeof(actual),"%s/%s",root,path);
 char canonical_root[4096],canonical_file[4096];
 if(!realpath(root,canonical_root)||!realpath(actual,canonical_file)){*handle=0;return -1;}
 size_t root_size=strlen(canonical_root);
 if(strncmp(canonical_file,canonical_root,root_size)||canonical_file[root_size]!='/'){*handle=0;return -1;}
 FILE *file=fopen(canonical_file,"rb");if(!file){*handle=0;return -1;}
 fseek(file,0,SEEK_END);long n=ftell(file);rewind(file);
 for(int i=1;i<128;i++)if(!files[i]){files[i]=file;*handle=i;return (int)n;}
 abort();
}
static int fsread(void *buffer,int n,fileHandle_t h){return (int)fread(buffer,1,n,files[h]);}
static void fsclose(fileHandle_t h){fclose(files[h]);files[h]=NULL;}
int main(int argc,char **argv) {
 if(argc!=2)return 2;root=argv[1];botimport.Print=print;botimport.FS_FOpenFile=fsopen;botimport.FS_Read=fsread;botimport.FS_FCloseFile=fsclose;
 char command[20],path[1024],text[1024];int h,index,min,max;float skill,a,b;
 while(scanf("%19s",command)==1){
  if(!strcmp(command,"load")){scanf("%1023s %f",path,&skill);printf("H %d\n",BotLoadCharacter(path,skill));}
  else if(!strcmp(command,"f")){scanf("%d %d",&h,&index);printf("F %.9g\n",Characteristic_Float(h,index));}
  else if(!strcmp(command,"i")){scanf("%d %d",&h,&index);printf("I %d\n",Characteristic_Integer(h,index));}
  else if(!strcmp(command,"s")){scanf("%d %d",&h,&index);memset(text,0,sizeof(text));Characteristic_String(h,index,text,sizeof(text));printf("S %s\n",text);}
  else if(!strcmp(command,"bf")){scanf("%d %d %f %f",&h,&index,&a,&b);printf("F %.9g\n",Characteristic_BFloat(h,index,a,b));}
  else if(!strcmp(command,"bi")){scanf("%d %d %d %d",&h,&index,&min,&max);printf("I %d\n",Characteristic_BInteger(h,index,min,max));}
  else if(!strcmp(command,"free")){scanf("%d",&h);BotFreeCharacter(h);puts("FREE");}
  else if(!strcmp(command,"reload")){scanf("%f",&reload);puts("RELOAD");}
  else if(!strcmp(command,"shutdown")){BotShutdownCharacters();puts("SHUTDOWN");}
  else return 2;
  fflush(stdout);
 }
 BotShutdownCharacters();return 0;
}
