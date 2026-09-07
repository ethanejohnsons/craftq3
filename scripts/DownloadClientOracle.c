/* Authored bounded observer. Native routines are called unchanged, never read or translated. */
#include "client/client.h"
#include <stdio.h>
#include <stdlib.h>
#include <stdarg.h>
#include <setjmp.h>
extern void CL_ParseDownload(msg_t *msg);
static jmp_buf failure;
static int writes,closed,renamed,next,packets,openResult=1;
static void hex(const void *data,int size){const byte *b=data;for(int i=0;i<size;i++)printf("%02x",b[i]);}
void QDECL Com_Error(int code,const char *format,...) {printf("ERROR %d ",code);va_list args;va_start(args,format);vprintf(format,args);va_end(args);puts("");longjmp(failure,1);}
void CL_AddReliableCommand(const char *text,qboolean disconnect){printf("COMMAND %d %s\n",disconnect,text);}
void CL_NextDownload(void){next++;}
void CL_WritePacket(void){packets++;}
void Cvar_Set(const char *name,const char *value){printf("CVAR %s %s\n",name,value);}
void Cvar_SetValue(const char *name,float value){printf("CVAR %s %.0f\n",name,value);}
fileHandle_t FS_BaseDir_FOpenFileWrite_HomeData(const char *name){printf("OPEN %s\n",name);return openResult;}
int FS_Write(const void *data,int size,fileHandle_t file){writes+=size;printf("WRITE %d ",size);hex(data,size);puts("");return size;}
void FS_FCloseFile(fileHandle_t file){closed++;}
void FS_BaseDir_Rename_HomeData(const char *from,const char *to,qboolean safe){renamed++;printf("RENAME %s %s %d\n",from,to,safe);}
int main(void){char line[40000];byte bytes[MAX_MSGLEN];
 while(fgets(line,sizeof(line),stdin)) {
  if(!strncmp(line,"seed ",5)) {
   int expected,size,count,active;if(sscanf(line+5,"%d %d %d %d %d",&expected,&size,&count,&active,&openResult)!=5)return 2;
   memset(&clc,0,sizeof(clc));clc.downloadBlock=expected;clc.downloadSize=size;clc.downloadCount=count;
   if(active){Q_strncpyz(clc.downloadName,"baseq3/test.pk3",sizeof(clc.downloadName));Q_strncpyz(clc.downloadTempName,"baseq3/test.pk3.tmp",sizeof(clc.downloadTempName));}
   clc.download=expected?1:0;writes=closed=renamed=next=packets=0;
  } else if(!strncmp(line,"packet ",7)) {
   char *text=line+7;size_t n=strcspn(text,"\r\n");if(n%2||n/2>sizeof(bytes))return 2;
   for(size_t i=0;i<n;i+=2){unsigned value;if(sscanf(text+i,"%2x",&value)!=1)return 2;bytes[i/2]=(byte)value;}
   msg_t msg;MSG_Init(&msg,bytes,sizeof(bytes));msg.cursize=(int)n/2;MSG_BeginReading(&msg);
   if(!setjmp(failure))CL_ParseDownload(&msg);
   printf("CURSOR %d\n",msg.bit);
  } else return 2;
  printf("STATE %d %d %d %d %d %d %d %d %d\n",clc.downloadBlock,clc.downloadSize,clc.downloadCount,clc.download,writes,closed,renamed,next,packets);puts("END");fflush(stdout);
 }
}
