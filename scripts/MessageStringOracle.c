/* Authored byte-string observer over unchanged public native MSG APIs. */
#define main netchan_oracle_unused_main
#include "NetchanOracle.c"
#undef main
int main(void) {
  char operation[16],encoded[MAX_MSGLEN*2+2];byte input[MAX_MSGLEN+1],bytes[MAX_MSGLEN+64];int kind,prefix;
  while(scanf("%15s %d %d %32769s",operation,&kind,&prefix,encoded)==4) {
    int length=0;memset(input,0,sizeof(input));if(strcmp(encoded,"~"))length=decode(encoded,input);
    memset(bytes,0,sizeof(bytes));msg_t message;MSG_Init(&message,bytes,MAX_MSGLEN);
    if(prefix)MSG_WriteBits(&message,(1<<prefix)-1,prefix);
    if(!strcmp(operation,"write")) {
      char *text=!strcmp(encoded,"~")?NULL:(char*)input;
      if(kind)MSG_WriteBigString(&message,text);else MSG_WriteString(&message,text);
      printf("WIRE %d %d ",message.bit,message.cursize);hex(bytes,message.cursize);putchar('\n');
    }else if(!strcmp(operation,"read")) {
      for(int i=0;i<length;i++)MSG_WriteByte(&message,input[i]);MSG_WriteByte(&message,0);
      MSG_BeginReading(&message);if(prefix)MSG_ReadBits(&message,prefix);
      char *result=kind==2?MSG_ReadStringLine(&message):kind==1?MSG_ReadBigString(&message):MSG_ReadString(&message);
      printf("TEXT %d ",message.bit);hex((byte*)result,(int)strlen(result));putchar('\n');
    }else return 2;
    fflush(stdout);
  }
  return 0;
}
