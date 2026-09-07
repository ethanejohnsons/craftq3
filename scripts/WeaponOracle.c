/* Authored verification driver; original objects are ignored and never ship in the mod. */
#include "qcommon/q_shared.h"
#include "botlib/botlib.h"
#include "botlib/be_ai_weight.h"
#define MAX_STRINGFIELD 80
#include "botlib/be_ai_weap.h"
#include <stddef.h>
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
void Q_strncpyz(char *d,const char *s,int n) { if(n<=0)abort();snprintf(d,n,"%s",s); }
void Q_strcat(char *d,int n,const char *s) { size_t used=strlen(d); if(used>=(size_t)n)abort();snprintf(d+used,n-used,"%s",s); }
int Q_stricmp(const char *a,const char *b) { return strcasecmp(a,b); }
int Com_sprintf(char *d,int n,const char *fmt,...) {va_list v;va_start(v,fmt);int result=vsnprintf(d,n,fmt,v);va_end(v);return result;}
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
 } else {
  char canonical_root[4096],canonical_file[4096];
  if(realpath(root,canonical_root)&&realpath(actual,canonical_file)) {
   size_t root_size=strlen(canonical_root);
   if(!strncmp(canonical_file,canonical_root,root_size)&&canonical_file[root_size]=='/')file=fopen(canonical_file,"rb");
  }
 }
 if(!file){*handle=0;return -1;}
 fseek(file,0,SEEK_END);long n=ftell(file);rewind(file);
 for(int i=1;i<128;i++)if(!files[i]){files[i]=file;*handle=i;return (int)n;}
 abort();
}
static int fsread(void *buffer,int n,fileHandle_t h){return (int)fread(buffer,1,n,files[h]);}
static void fsclose(fileHandle_t h){fclose(files[h]);files[h]=NULL;}

static char *configfile;
void *GetClearedHunkMemory(unsigned long n){return calloc(1,n);}
float LibVarValue(const char *name,const char *value){fprintf(stderr,"LibVarValue %s=%s\n",name,value);return atof(value);}
char *LibVarString(const char *name,const char *value){fprintf(stderr,"LibVarString %s=%s\n",name,value);return strcmp(name,"weaponconfig")==0?configfile:(char*)value;}
void LibVarSet(const char *name,const char *value){fprintf(stderr,"LibVarSet %s=%s\n",name,value);}
int main(int argc,char **argv) {
 if(argc!=3)return 2;root=argv[1];configfile=argv[2];botimport.Print=print;botimport.FS_FOpenFile=fsopen;botimport.FS_Read=fsread;botimport.FS_FCloseFile=fsclose;
 printf("SETUP %d\n",BotSetupWeaponAI());fflush(stdout);
 fprintf(stderr,"SIZES weapon=%zu projectile=%zu projOffset=%zu\n",sizeof(weaponinfo_t),sizeof(projectileinfo_t),offsetof(weaponinfo_t,proj));
 int inventory[256]={0},index,value,handle;char command[20],path[1024];
 while(scanf("%19s",command)==1){
  if(!strcmp(command,"alloc"))printf("HANDLE %d\n",BotAllocWeaponState());
  else if(!strcmp(command,"load")){scanf("%d %1023s",&handle,path);printf("LOAD %d\n",BotLoadWeaponWeights(handle,path));}
  else if(!strcmp(command,"inventory")){for(index=0;index<256;index++)if(scanf("%d",&inventory[index])!=1)return 2;puts("INVENTORY");}
  else if(!strcmp(command,"set")){scanf("%d %d",&index,&value);if(index<0||index>=256)abort();inventory[index]=value;puts("SET");}
  else if(!strcmp(command,"choose")){scanf("%d",&handle);printf("CHOOSE %d\n",BotChooseBestFightWeapon(handle,inventory));}
  else if(!strcmp(command,"reset")){scanf("%d",&handle);BotResetWeaponState(handle);puts("RESET");}
  else if(!strcmp(command,"free")){scanf("%d",&handle);BotFreeWeaponState(handle);puts("FREE");}
  else if(!strcmp(command,"info")){scanf("%d %d",&handle,&index);weaponinfo_t info;memset(&info,0,sizeof(info));BotGetWeaponInfo(handle,index,&info);printf("INFO %d %d %s damage=%d projectile=%s\n",info.valid,info.number,info.name,info.proj.damage,info.projectile);}
  else if(!strcmp(command,"bytes")){scanf("%d %d",&handle,&index);weaponinfo_t info;memset(&info,0,sizeof(info));BotGetWeaponInfo(handle,index,&info);for(size_t i=0;i<sizeof(info);i++)printf("%02x",((unsigned char*)&info)[i]);puts("");}
  else return 2;
  fflush(stdout);
 }
 BotShutdownWeaponAI();BotShutdownWeights();return 0;
}
