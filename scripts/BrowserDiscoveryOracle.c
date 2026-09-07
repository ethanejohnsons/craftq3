/* Authored public-state/call observer for master discovery and status queries.
 * Uses client.h metadata and unchanged exported operations only. Outbound packets
 * are printed, and address resolution accepts numeric literals only; no sockets
 * or external DNS requests are created. See NETWORK_BROWSER_DISCOVERY.md.
 */
#include "client/client.h"
#include <arpa/inet.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
extern void CL_ServersResponsePacket(const netadr_t *, msg_t *, qboolean);
extern void CL_ServerStatusResponse(netadr_t, msg_t *);
extern cvar_t *cl_serverStatusResendTime;
static cvar_t resend;
static int now;
static int argumentCount;
static char *arguments[32];
static char masterName[1024]="127.0.0.1:27950";
static void hex(const char *s);
static cvar_t masterVar;
static cvar_t gameName = {.string="Quake3Arena"};
cvar_t *com_gamename=&gameName;
char *Cmd_ArgsFrom(int first){static char value[4096];value[0]=0;for(int i=first;i<argumentCount;i++){if(i>first)strcat(value," ");strcat(value,arguments[i]);}return value;}
void Cbuf_AddText(const char *text){printf("CBUF ");hex(text);puts("");}
int Cvar_VariableIntegerValue(const char *name){printf("INTEGER ");hex(name);puts("");return 3;}
char *Cvar_VariableString(const char *name){printf("STRING ");hex(name);puts("");return !strcmp(name,"sv_master1")?masterName:"";}

int Cmd_Argc(void){return argumentCount;}
char *Cmd_Argv(int i){return i>=0&&i<argumentCount?arguments[i]:"";}
static void hex(const char *s);
cvar_t *Cvar_Get(const char *name,const char *value,int flags){printf("CVAR ");hex(name);puts("");masterVar.string=masterName;return &masterVar;}

static byte packet[65536];
static void hex(const char *s) { for (const unsigned char*p=(const unsigned char*)s;*p;p++)printf("%02x",*p); }
static size_t unhex(const char *s, byte *target, size_t cap) {size_t n=strcspn(s,"\r\n ");if(n==1&&s[0]=='-')return 0;if(n%2||n/2>cap)exit(3);for(size_t i=0;i<n;i+=2){unsigned b;if(sscanf(s+i,"%2x",&b)!=1)exit(3);target[i/2]=b;}return n/2;}
void QDECL Com_Printf(const char*format,...) {(void)format;}
void QDECL Com_DPrintf(const char*format,...) {(void)format;}
void QDECL Com_Error(int code,const char*format,...) {va_list ap;va_start(ap,format);vfprintf(stderr,format,ap);va_end(ap);exit(2);}
int Sys_Milliseconds(void){return now;}
int Com_Milliseconds(void){return now;}
void QDECL NET_OutOfBandPrint(netsrc_t sock,netadr_t adr,const char*format,...){char text[65536];va_list ap;va_start(ap,format);vsnprintf(text,sizeof(text),format,ap);va_end(ap);printf("SEND %d %d %u ",sock,adr.type,ntohs(adr.port));hex(text);puts("");}
qboolean Sys_StringToAdr(const char *s,netadr_t *a,netadrtype_t family){
 memset(a,0,sizeof(*a));
 if((family==NA_UNSPEC||family==NA_IP)&&inet_pton(AF_INET,s,a->ip)==1){a->type=NA_IP;return qtrue;}
 if((family==NA_UNSPEC||family==NA_IP6)&&inet_pton(AF_INET6,s,a->ip6)==1){a->type=NA_IP6;return qtrue;}
 return qfalse;
}
static void address(netadr_t a){printf(" %d %u %lu ",a.type,ntohs(a.port),a.type==NA_IP6?a.scope_id:0UL);const byte*p=a.type==NA_IP6?a.ip6:a.ip;int n=a.type==NA_IP6?16:4;for(int i=0;i<n;i++)printf("%02x",p[i]);}
static void show(void){printf("COUNTS %d %d %d %d\n",cls.numglobalservers,cls.numGlobalServerAddresses,cls.numlocalservers,cls.numfavoriteservers);for(int i=0;i<cls.numglobalservers;i++){printf("SERVER %d",i);address(cls.globalServers[i].adr);printf(" %d %d ",cls.globalServers[i].ping,cls.globalServers[i].visible);hex(cls.globalServers[i].hostName);puts("");}for(int i=0;i<cls.numGlobalServerAddresses;i++){printf("EXTRA %d",i);address(cls.globalServerAddresses[i]);puts("");}}
static void seedrow(int index) {
 if(index < 0 || index >= MAX_GLOBAL_SERVERS) exit(3);
 serverInfo_t *row=&cls.globalServers[index];
 memset(row,0x55,sizeof(*row));
 strcpy(row->hostName,"old-host"); strcpy(row->mapName,"old-map"); strcpy(row->game,"old-game");
 row->netType=101; row->gameType=102; row->clients=103; row->maxClients=104;
 row->minPing=105; row->maxPing=106; row->ping=107; row->visible=108;
 row->punkbuster=109; row->g_humanplayers=110; row->g_needpass=111;
}
static void row(int index) {
 if(index < 0 || index >= MAX_GLOBAL_SERVERS) exit(3);
 serverInfo_t *r=&cls.globalServers[index];
 printf("ROW %d",index); address(r->adr);
 printf(" %d %d %d %d %d %d %d %d %d %d %d ",r->netType,r->gameType,r->clients,r->maxClients,r->minPing,r->maxPing,r->ping,r->visible,r->punkbuster,r->g_humanplayers,r->g_needpass);
 hex(r->hostName); printf(" "); hex(r->mapName); printf(" "); hex(r->game); puts("");
}
int main(void){char line[140000];resend.integer=750;resend.value=750;resend.string="750";cl_serverStatusResendTime=&resend;while(fgets(line,sizeof(line),stdin)){int a,b,c,d,used=0;
 if(sscanf(line,"reset %d %d %d %n",&a,&b,&c,&used)==3){if(a < -1 || a>MAX_GLOBAL_SERVERS || b<0 || b>MAX_GLOBAL_SERVERS) return 3;memset(&cls,0,sizeof(cls));cls.numglobalservers=a;cls.numGlobalServerAddresses=b;cls.realtime=c;now=c;for(int i=0;i<a;i++){cls.globalServers[i].adr.type=NA_IP;cls.globalServers[i].adr.ip[0]=10;cls.globalServers[i].adr.ip[2]=i>>8;cls.globalServers[i].adr.ip[3]=i;cls.globalServers[i].adr.port=htons(27960);cls.globalServers[i].ping=55;cls.globalServers[i].visible=1;strcpy(cls.globalServers[i].hostName,"seed");}puts("RESET");}
 else if(sscanf(line,"seedrow %d",&a)==1){seedrow(a);puts("SEEDED");}
 else if(sscanf(line,"row %d",&a)==1){row(a);}
 else if(sscanf(line,"recount %d %d",&a,&b)==2){if(a < -1 || a>MAX_GLOBAL_SERVERS || b<0 || b>MAX_GLOBAL_SERVERS)return 3;cls.numglobalservers=a;cls.numGlobalServerAddresses=b;puts("RECOUNT");}
 else if(sscanf(line,"master %d %d %d %n",&a,&b,&c,&used)==3){netadr_t from={0};from.type=b?NA_IP6:NA_IP;from.scope_id=c;from.port=htons(27950);from.ip[0]=127;from.ip[3]=1;size_t n=unhex(line+used,packet,sizeof(packet));msg_t msg;MSG_InitOOB(&msg,packet,sizeof(packet));msg.cursize=n;CL_ServersResponsePacket(&from,&msg,a);show();}
 else if(sscanf(line,"raw %d %d %d %n",&a,&b,&c,&used)==3){netadr_t from={0};from.type=b?NA_IP6:NA_IP;from.scope_id=c;cls.numglobalservers=MAX_GLOBAL_SERVERS;cls.numGlobalServerAddresses=0;size_t n=unhex(line+used,packet,sizeof(packet));msg_t msg;MSG_InitOOB(&msg,packet,sizeof(packet));msg.cursize=n;CL_ServersResponsePacket(&from,&msg,a);printf("RAW %d\n",cls.numGlobalServerAddresses);for(int i=0;i<cls.numGlobalServerAddresses;i++){printf("ENDPOINT");address(cls.globalServerAddresses[i]);puts("");}}
 else if(sscanf(line,"tick %d %d",&a,&b)==2){now=a;cls.realtime=a;resend.integer=b;resend.value=b;puts("TICK");}
 else if(sscanf(line,"status %d %n",&a,&used)==1){char addr[1024];size_t n=unhex(line+used,(byte*)addr,sizeof(addr)-1);addr[n]=0;char result[65536];if(a < -16 || a>(int)sizeof(result))return 3;memset(result,0x55,sizeof(result));int value=CL_ServerStatus(n?addr:NULL,result,a);printf("STATUS %d ",value);if(a>0){for(int i=0;i<a;i++)printf("%02x",(unsigned char)result[i]);}puts("");}
 else if(sscanf(line,"response %d %n",&a,&used)==1){netadr_t from={0};from.type=NA_IP;from.ip[0]=127;from.ip[3]=1;from.port=htons(a);size_t n=unhex(line+used,packet,sizeof(packet));msg_t msg;MSG_InitOOB(&msg,packet,sizeof(packet));msg.cursize=n;CL_ServerStatusResponse(from,&msg);puts("RESPONSE");}
 else if(!strncmp(line,"cancel ",7)){char addr[1024];size_t n=unhex(line+7,(byte*)addr,sizeof(addr)-1);addr[n]=0;printf("CANCEL %d\n",CL_ServerStatus(n?addr:NULL,NULL,0));}
 else if(!strncmp(line,"global ",7)){char *save;argumentCount=0;arguments[argumentCount++]="globalservers";for(char *word=strtok_r(line+7," \r\n",&save);word&&argumentCount<32;word=strtok_r(NULL," \r\n",&save))arguments[argumentCount++]=word;CL_GlobalServers_f();printf("GLOBAL %d %d %d\n",cls.numglobalservers,cls.numGlobalServerAddresses,cls.pingUpdateSource);}
 else if(!strncmp(line,"masteraddr ",11)){size_t n=unhex(line+11,(byte*)masterName,sizeof(masterName)-1);masterName[n]=0;puts("MASTERADDR");}
 else if(!strncmp(line,"clear",5)){CL_ServerStatus(NULL,NULL,0);puts("CLEAR");}
 else return 3;fflush(stdout);
}return 0;}
