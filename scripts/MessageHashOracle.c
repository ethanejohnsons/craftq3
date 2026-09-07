/* Authored observer for unchanged public MSG_HashKey. */
#define main framing_oracle_main
#include "NetchanOracle.c"
#undef main
int main(void) {
  int maximum; char input[MAX_MSGLEN*2+2]; byte text[MAX_MSGLEN+1];
  while(scanf("%d %32769s", &maximum, input)==2) {
    if(maximum<0 || maximum>MAX_MSGLEN) return 2;
    memset(text,0,sizeof(text));decode(input,text);
    printf("%u\n",(unsigned)MSG_HashKey((char*)text,maximum));fflush(stdout);
  }
  return 0;
}
