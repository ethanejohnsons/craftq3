/* Authored filesystem boundary observer. Native filesystem operations remain unchanged. */
#include "qcommon/q_shared.h"
#include "qcommon/qcommon.h"
#include <stdio.h>
#include <stdlib.h>
#include <fcntl.h>
#include <unistd.h>
extern void Observed_FS_InitFilesystem(void);
static int observing;
static void text(const char *label,const char *value) {
  printf("QA_%s ",label);
  if(!*value)putchar('-');
  for(const unsigned char*p=(const unsigned char*)value;*p;p++)printf("%02x",*p);
  putchar('\n');
}
static void decode(const char *hex,char *out,int capacity) {
  if(!strcmp(hex,"-")){out[0]=0;return;}
  size_t length=strlen(hex);if((length&1)||length/2>=(size_t)capacity)exit(2);
  for(size_t i=0;i<length;i+=2){unsigned value;if(sscanf(hex+i,"%2x",&value)!=1)exit(2);out[i/2]=(char)value;}
  out[length/2]=0;
}
static void lists(void) {
  text("LOADED_NAMES",FS_LoadedPakNames());text("LOADED_CHECKSUMS",FS_LoadedPakChecksums());
  text("LOADED_PURE",FS_LoadedPakPureChecksums());text("REFERENCED_NAMES",FS_ReferencedPakNames());
  text("REFERENCED_CHECKSUMS",FS_ReferencedPakChecksums());text("REFERENCED_PURE",FS_ReferencedPakPureChecksums());
}
void FS_InitFilesystem(void) {
  Observed_FS_InitFilesystem();
  if(observing)return;
  observing=1;
  if(!com_protocol)com_protocol=Cvar_Get("protocol", "71", CVAR_ROM);
#ifdef LEGACY_PROTOCOL
  if(!com_legacyprotocol)com_legacyprotocol=Cvar_Get("com_legacyprotocol", "68", CVAR_ROM);
#endif
  int inputFlags=fcntl(STDIN_FILENO,F_GETFL);
  if(inputFlags>=0)fcntl(STDIN_FILENO,F_SETFL,inputFlags&~O_NONBLOCK);
  clearerr(stdin);puts("QA_READY");fflush(stdout);
  char operation[24],a[32769],b[32769],first[16385],second[16385];
  while(scanf("%23s",operation)==1) {
    if(!strcmp(operation,"list"))lists();
    else if(!strcmp(operation,"pure")){
      if(scanf("%32768s %32768s",a,b)!=2)exit(2);decode(a,first,sizeof(first));decode(b,second,sizeof(second));
      FS_PureServerSetLoadedPaks(first,second);puts("QA_PURE");
    }else if(!strcmp(operation,"referenced")){
      if(scanf("%32768s %32768s",a,b)!=2)exit(2);decode(a,first,sizeof(first));decode(b,second,sizeof(second));
      FS_PureServerSetReferencedPaks(first,second);puts("QA_REFERENCED");
    }else if(!strcmp(operation,"clear")){
      int flags;if(scanf("%d",&flags)!=1)exit(2);FS_ClearPakReferences(flags);puts("QA_CLEAR");
    }else if(!strcmp(operation,"feed")){
      int feed;if(scanf("%d",&feed)!=1)exit(2);FS_Restart(feed);puts("QA_FEED");
    }else if(!strcmp(operation,"open")||!strcmp(operation,"read")){
      if(scanf("%32768s",a)!=1)exit(2);decode(a,first,sizeof(first));
      fileHandle_t handle=0;long length=FS_FOpenFileRead(first,&handle,qtrue);
      printf("QA_FILE %ld %d",length,handle!=0);
      if(handle){if(!strcmp(operation,"read")){unsigned char bytes[32];int size=length>32?32:(int)length;
        if(size<0)exit(2);int n=FS_Read(bytes,size,handle);printf(" %d ",n);if(!n)putchar('-');for(int i=0;i<n;i++)printf("%02x",bytes[i]);}
        FS_FCloseFile(handle);}
      putchar('\n');
    }else if(!strcmp(operation,"exists")||!strcmp(operation,"inpack")){
      if(scanf("%32768s",a)!=1)exit(2);decode(a,first,sizeof(first));
      if(!strcmp(operation,"exists"))printf("QA_EXISTS %ld\n",FS_FOpenFileRead(first,NULL,qtrue));
      else {int checksum=0x12345678;int found=FS_FileIsInPAK(first,&checksum);printf("QA_INPACK %d %d\n",found,checksum);}
    }else if(!strcmp(operation,"files")){
      if(scanf("%32768s %32768s",a,b)!=2)exit(2);decode(a,first,sizeof(first));decode(b,second,sizeof(second));
      int count=0;char **files=FS_ListFiles(first,second,&count);printf("QA_COUNT %d\n",count);
      for(int i=0;i<count;i++)text("ENTRY",files[i]);FS_FreeFileList(files);
    }else if(!strcmp(operation,"compare")){
      int download;if(scanf("%d",&download)!=1)exit(2);char needed[16384];needed[0]=0;
      int missing=FS_ComparePaks(needed,sizeof(needed),download);printf("QA_MISSING %d\n",missing);text("NEEDED",needed);
    }else if(!strcmp(operation,"quit")){puts("QA_END");fflush(stdout);_Exit(0);}
    else exit(2);
    puts("QA_END");fflush(stdout);
  }
  _Exit(0);
}
