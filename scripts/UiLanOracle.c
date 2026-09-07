/* Authored CPU call observer. Included native operations remain unchanged. */
#include "client/cl_ui.c"
#include <setjmp.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>

clientStatic_t cls;
static jmp_buf failure;
extern void UiLanPrepareAddressParser(void);
extern int UiLanOriginalStringToAdr(const char*,netadr_t*,netadrtype_t);
int NET_StringToAdr(const char *text,netadr_t *address,netadrtype_t family) {
  if(!strcmp(text,"!fixture-failure!")){memset(address,0,sizeof(*address));return 0;}
  return UiLanOriginalStringToAdr(text,address,family);
}

void QDECL Com_Printf(const char *format, ...) { (void)format; }
void QDECL Com_DPrintf(const char *format, ...) { (void)format; }
void QDECL Com_Error(int code, const char *format, ...) {
  va_list args; va_start(args,format); char message[2048]; vsnprintf(message,sizeof(message),format,args); va_end(args);
  printf("ERROR %d ",code);
  for(const unsigned char*p=(const unsigned char*)message;*p;p++)printf("%02x",*p);
  putchar('\n'); longjmp(failure,1);
}
static void decode(const char *hex,char *target,int capacity) {
  if(!strcmp(hex,"-")){target[0]=0;return;}
  size_t size=strlen(hex);if((size&1)||size/2>=(size_t)capacity)exit(2);
  for(size_t i=0;i<size;i+=2){unsigned n;if(sscanf(hex+i,"%2x",&n)!=1)exit(2);target[i/2]=(char)n;}
  target[size/2]=0;
}
static void bytes(const void *data,int size) {
  if(size==0)putchar('-');
  for(int i=0;i<size;i++)printf("%02x",((const unsigned char*)data)[i]);
}
static serverInfo_t *record(int source,int index) {
  if(index<0)return NULL;
  if(source==AS_LOCAL && index<MAX_OTHER_SERVERS)return &cls.localServers[index];
  if((source==AS_GLOBAL || source==AS_MPLAYER) && index<MAX_GLOBAL_SERVERS)return &cls.globalServers[index];
  if(source==AS_FAVORITES && index<MAX_OTHER_SERVERS)return &cls.favoriteServers[index];
  return NULL;
}
static void counts(void) {printf("COUNTS %d %d %d\n",cls.numlocalservers,cls.numglobalservers,cls.numfavoriteservers);}
int main(void) {
  UiLanPrepareAddressParser();
  char op[24],a[8193],b[8193],c[8193],d[8193],address[4097],host[4097],map[4097],game[4097];
  while(scanf("%23s",op)==1) {
    if(setjmp(failure)){puts("END");fflush(stdout);continue;}
    if(!strcmp(op,"reset")){memset(&cls,0,sizeof(cls));puts("RESET");}
    else if(!strcmp(op,"counts")){
      if(scanf("%d %d %d",&cls.numlocalservers,&cls.numglobalservers,&cls.numfavoriteservers)!=3)return 2;counts();
    }else if(!strcmp(op,"seed")){
      int source,index;if(scanf("%d %d %8192s %8192s %8192s %8192s",&source,&index,a,b,c,d)!=6)return 2;
      serverInfo_t *server=record(source,index);if(!server)return 2;
      decode(a,address,sizeof(address));decode(b,host,sizeof(host));decode(c,map,sizeof(map));decode(d,game,sizeof(game));
      memset(server,0,sizeof(*server));NET_StringToAdr(address,&server->adr,NA_UNSPEC);
      Q_strncpyz(server->hostName,host,sizeof(server->hostName));Q_strncpyz(server->mapName,map,sizeof(server->mapName));Q_strncpyz(server->game,game,sizeof(server->game));
      if(scanf("%d %d %d %d %d %d %d %d %d %d %d",&server->netType,&server->gameType,&server->clients,&server->maxClients,&server->minPing,&server->maxPing,&server->ping,(int*)&server->visible,&server->punkbuster,&server->g_humanplayers,&server->g_needpass)!=11)return 2;
      puts("SEEDED");
    }else if(!strcmp(op,"add")){
      int source;if(scanf("%d %8192s %8192s",&source,a,b)!=3)return 2;decode(a,host,sizeof(host));decode(b,address,sizeof(address));
      printf("ADDED %d\n",LAN_AddServer(source,host,address));counts();
    }else if(!strcmp(op,"remove")){
      int source;if(scanf("%d %8192s",&source,a)!=2)return 2;decode(a,address,sizeof(address));LAN_RemoveServer(source,address);counts();
    }else if(!strcmp(op,"count")){
      int source;if(scanf("%d",&source)!=1)return 2;printf("COUNT %d\n",LAN_GetServerCount(source));
    }else if(!strcmp(op,"address")||!strcmp(op,"info")){
      int source,index,size;if(scanf("%d %d %d",&source,&index,&size)!=3||size>4096||size< -1)return 2;
      unsigned char buffer[4112];memset(buffer,0x7f,sizeof(buffer));
      if(!strcmp(op,"address"))LAN_GetServerAddressString(source,index,(char*)buffer,size);
      else LAN_GetServerInfo(source,index,(char*)buffer,size);
      int limit=(size>0?size:0)+8;printf("BUFFER ");bytes(buffer,limit);putchar('\n');
    }else if(!strcmp(op,"kind")){
      int source,index;if(scanf("%d %d",&source,&index)!=2)return 2;serverInfo_t *server=record(source,index);printf("KIND %d\n",server?(int)server->adr.type:-1);
    }else if(!strcmp(op,"ping")||!strcmp(op,"visible")){
      int source,index;if(scanf("%d %d",&source,&index)!=2)return 2;
      printf("VALUE %d\n",!strcmp(op,"ping")?LAN_GetServerPing(source,index):LAN_ServerIsVisible(source,index));
    }else if(!strcmp(op,"mark")){
      int source,index,value;if(scanf("%d %d %d",&source,&index,&value)!=3)return 2;LAN_MarkServerVisible(source,index,value);puts("MARKED");
    }else if(!strcmp(op,"resetpings")){
      int source;if(scanf("%d",&source)!=1)return 2;LAN_ResetPings(source);puts("RESET_PINGS");
    }else if(!strcmp(op,"compare")){
      int source,key,direction,first,second;if(scanf("%d %d %d %d %d",&source,&key,&direction,&first,&second)!=5)return 2;
      printf("COMPARE %d\n",LAN_CompareServers(source,key,direction,first,second));
    }else return 2;
    puts("END");fflush(stdout);
  }
  return 0;
}
