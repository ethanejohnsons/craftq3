/* Authored in-memory raw-file source and effect observer for unchanged server download operations. */
#include "server/server.h"
#include <stdio.h>
#include <stdlib.h>
#include <stdarg.h>
extern void SV_BeginDownload_f(client_t *cl);
extern void SV_NextDownload_f(client_t *cl);
extern void SV_StopDownload_f(client_t *cl);
extern void SV_DoneDownload_f(client_t *cl);
server_t sv;serverStatic_t svs;
static client_t client;static cvar_t allow,pure; cvar_t *sv_allowDownload=&allow,*sv_pure=&pure;
static int fileSize,position,closed,drops,games;
void QDECL Com_Printf(const char *format,...){(void)format;}
void QDECL Com_DPrintf(const char *format,...){(void)format;}
void QDECL Com_Error(int code,const char *format,...){fprintf(stderr,"ERROR %d\n",code);exit(2);}
void SV_DropClient(client_t *cl,const char *reason){drops++;cl->state=CS_ZOMBIE;printf("DROP %s\n",reason);}
void SV_SendClientGameState(client_t *cl){(void)cl;games++;}
long FS_BaseDir_FOpenFileRead(const char *name,fileHandle_t *handle){printf("OPEN %s\n",name);position=0;*handle=fileSize<0?0:1;return fileSize;}
int FS_Read(void *buffer,int size,fileHandle_t handle){int n=fileSize-position;if(n>size)n=size;if(n<0)n=0;for(int i=0;i<n;i++)((byte*)buffer)[i]=(byte)(position+i);position+=n;return n;}
void FS_FCloseFile(fileHandle_t handle){closed++;}
void *S_MallocDebug(int size,char *label,char *file,int line){return calloc(1,size);}
void Z_Free(void *pointer){free(pointer);}
qboolean FS_FilenameCompare(const char *a,const char *b){return strcmp(a,b)!=0;}
const char *FS_ReferencedPakNames(void){return "baseq3/test";}
qboolean FS_idPak(char *pak,char *base,int count){return qfalse;}
void *Z_MallocDebug(int size,char *label,char *file,int line){return calloc(1,size);}
int main(void){char line[8192];byte bytes[MAX_MSGLEN];svs.clients=&client;
 while(fgets(line,sizeof(line),stdin)) {
  if(!strncmp(line,"seed ",5)){memset(&client,0,sizeof(client));closed=drops=games=0;int state;if(sscanf(line+5,"%d %d %d",&fileSize,&allow.integer,&state)!=3)return 2;client.state=state;client.rate=25000;client.snapshotMsec=50;pure.integer=1;svs.time=1000;}
  else if(!strncmp(line,"write ",6)) {
   if(sscanf(line+6,"%d",&svs.time)!=1)return 2;msg_t msg;MSG_Init(&msg,bytes,sizeof(bytes));MSG_Bitstream(&msg);
   int result=SV_WriteDownloadToClient(&client,&msg);printf("MESSAGE %d %d ",result,msg.bit);for(int i=0;i<msg.cursize;i++)printf("%02x",bytes[i]);puts("");
  } else {Cmd_TokenizeString(line);if(!strncmp(line,"download ",9))SV_BeginDownload_f(&client);else if(!strncmp(line,"nextdl ",7))SV_NextDownload_f(&client);else if(!strncmp(line,"stopdl",6))SV_StopDownload_f(&client);else if(!strncmp(line,"donedl",6))SV_DoneDownload_f(&client);else return 2;}
  printf("STATE %d %d %d %d %d %d %d %d %d %d\n",client.downloadSize,client.downloadCount,client.downloadClientBlock,client.downloadCurrentBlock,client.downloadXmitBlock,client.downloadEOF,client.download,closed,drops,games);puts("END");fflush(stdout);
 }
}
