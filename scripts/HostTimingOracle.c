/* Authored public-struct inputs for unchanged SV_RateMsec and SV_CalcPings. */
#include "server/server.h"
#include <stdio.h>
#include <stdlib.h>
#include <strings.h>
extern void SV_CalcPings(void);
static cvar_t minimum,maximum,scale,maxclients,dedicated,fps,lanRate;
cvar_t *com_timescale=&scale,*com_dedicated=&dedicated;
static int lan;
const char *NET_AdrToString(netadr_t address) {(void)address;return "127.0.0.1";}
qboolean NET_IsLocalAddress(netadr_t address) {(void)address;return qfalse;}
void SV_DropClient(client_t *cl,const char *reason) {(void)cl;fprintf(stderr,"DROP %s\n",reason);exit(3);}
qboolean Sys_IsLANAddress(netadr_t address) {(void)address;return lan;}
void QDECL Com_DPrintf(const char *format,...) {(void)format;}
void QDECL Com_Printf(const char *format,...) {(void)format;}
void QDECL Com_Error(int code,const char *format,...) {(void)format;exit(code+20);}
static client_t client;
static sharedEntity_t entity;
static playerState_t player;
static int now;
int Sys_Milliseconds(void) {return now;}
void Cvar_Set(const char *name,const char *value) {
 if(!strcasecmp(name,"sv_minRate"))minimum.integer=atoi(value);
 else if(!strcasecmp(name,"sv_maxRate"))maximum.integer=atoi(value);
 else {fprintf(stderr,"Unexpected cvar %s=%s\n",name,value);abort();}
}
playerState_t *SV_GameClientNum(int number) {if(number)abort();return &player;}
int main(void) {
 char line[8192];sv_minRate=&minimum;sv_maxRate=&maximum;sv_maxclients=&maxclients;maxclients.integer=1;svs.clients=&client;sv_fps=&fps;sv_lanForceRate=&lanRate;
 while(fgets(line,sizeof(line),stdin)) {
  memset(&client,0,sizeof(client));memset(&entity,0,sizeof(entity));memset(&player,0,sizeof(player));
  if(!strncmp(line,"rate ",5)) {
   int family;if(sscanf(line+5,"%d %d %d %d %d %d %f %d",&client.rate,&minimum.integer,&maximum.integer,&client.netchan.lastSentSize,&client.netchan.lastSentTime,&now,&scale.value,&family)!=8)return 2;
   client.netchan.remoteAddress.type=family==6?NA_IP6:family==4?NA_IP:NA_LOOPBACK;
   printf("RATE %d %d %d\n",SV_RateMsec(&client),minimum.integer,maximum.integer);
  } else if(!strncmp(line,"ping ",5)) {
   int state,hasEntity,bot,count,used;if(sscanf(line+5,"%d %d %d %d %n",&state,&hasEntity,&bot,&count,&used)!=4||count<0||count>PACKET_BACKUP)return 2;
   client.state=state;client.gentity=hasEntity?&entity:NULL;entity.r.svFlags=bot?SVF_BOT:0;
   for(int i=0;i<PACKET_BACKUP;i++)client.frames[i].messageAcked=-1;
   char *p=line+5+used;
   for(int i=0;i<count;i++){if(sscanf(p,"%d %d %n",&client.frames[i].messageSent,&client.frames[i].messageAcked,&used)!=2)return 2;p+=used;}
   SV_CalcPings();printf("PING %d %d\n",client.ping,player.ping);
  } else if(!strncmp(line,"info ",5)) {
   int used;if(sscanf(line+5,"%d %d %d %d %n",&dedicated.integer,&lan,&lanRate.integer,&fps.integer,&used)!=4)return 2;
   Q_strncpyz(client.userinfo,line+5+used,sizeof(client.userinfo));client.userinfo[strcspn(client.userinfo,"\r\n")]=0;
   client.netchan.remoteAddress.type=NA_IP;SV_UserinfoChanged(&client);printf("INFO %d %d\n",client.rate,client.snapshotMsec);
  } else return 2;
  fflush(stdout);
 }
}
