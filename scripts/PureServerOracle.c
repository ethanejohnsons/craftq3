/* Authored filesystem/VM boundary observer; native validator remains unchanged. */
#include "server/server.h"
#include <stdio.h>
#include <stdlib.h>
#include <stdarg.h>
extern void SV_VerifyPaks_f(client_t *cl);
extern void SV_ResetPureClient_f(client_t *cl);
server_t sv;
serverStatic_t svs;
static client_t client;
static cvar_t pure;
cvar_t *sv_pure=&pure;
static int cgame=111,ui=222,drops,snapshots;
void QDECL Com_Printf(const char *format,...) { (void)format; }
void QDECL Com_DPrintf(const char *format,...) { (void)format; }
void QDECL Com_Error(int code,const char *format,...) { fprintf(stderr,"ERROR %d\n",code);exit(2); }
void SV_DropClient(client_t *cl,const char *reason) { drops++;printf("DROP %s\n",reason);cl->state=CS_ZOMBIE; }
void SV_SendClientSnapshot(client_t *cl) { (void)cl;snapshots++; }
int FS_FileIsInPAK(const char *name,int *checksum) {
 if(!strcmp(name,"vm/cgame.qvm")){*checksum=cgame;return cgame!=-1;}
 if(!strcmp(name,"vm/ui.qvm")){*checksum=ui;return ui!=-1;}
 fprintf(stderr,"Unexpected lookup %s\n",name);exit(2);
}
const char *FS_LoadedPakPureChecksums(void) { return "111 222 333 444 "; }
int main(void) {
 char line[32768];svs.clients=&client;
 while(fgets(line,sizeof(line),stdin)) {
  if(!strncmp(line,"seed ",5)) {
   memset(&client,0,sizeof(client));memset(&sv,0,sizeof(sv));drops=snapshots=0;
   if(sscanf(line+5,"%d %d %d %d %d %d %d %d",&pure.integer,&sv.serverId,&sv.checksumFeedServerId,&sv.checksumFeed,&client.gotCP,&client.pureAuthentic,&cgame,&ui)!=8)return 2;
   client.state=CS_PRIMED;puts("SEEDED");
  } else if(!strncmp(line,"cp",2)) { Cmd_TokenizeString(line);SV_VerifyPaks_f(&client); }
  else if(!strncmp(line,"vdr",3)) { SV_ResetPureClient_f(&client); }
  else return 2;
  printf("STATE %d %d %d %d %d\n",client.gotCP,client.pureAuthentic,client.state,drops,snapshots);fflush(stdout);
 }
}
