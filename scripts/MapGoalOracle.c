/* Authored observer for public camp/location goal queries over controlled BSP entity inputs. */
#define ITEM_ORACLE_ENTRY item_oracle_unused_main
#define AAS_PointAreaNum item_default_point_area
#include "ItemOracle.c"
#undef AAS_PointAreaNum
extern void BotInitInfoEntities(void);
static int point_area=2;
int AAS_PointAreaNum(vec3_t origin) { (void)origin;return point_area; }

int main(int argc,char **argv) {
  if(argc<2||argc>3)return 2;root=argv[1];if(argc==3)point_area=atoi(argv[2]);botimport.Print=print;botimport.FS_FOpenFile=fsopen;botimport.FS_Read=fsread;botimport.FS_FCloseFile=fsclose;
  if(scanf("%d",&entity_count)!=1||entity_count<0||entity_count>8192)return 2;
  for(int ent=0;ent<entity_count;ent++) {
    if(scanf("%d",&epairs[ent].count)!=1||epairs[ent].count<0||epairs[ent].count>64)return 2;
    for(int k=0;k<epairs[ent].count;k++){epairs[ent].keys[k]=readhex();epairs[ent].values[k]=readhex();}
  }
  if(BotSetupGoalAI())return 3;BotInitInfoEntities();
  char command[32];int cursor,result;
  while(scanf("%31s",command)==1) {
    bot_goal_t goal;memset(&goal,0xab,sizeof(goal));
    if(!strcmp(command,"camp")){if(scanf("%d",&cursor)!=1)return 2;result=BotGetNextCampSpotGoal(cursor,&goal);}
    else if(!strcmp(command,"location")){char *name=readhex();result=BotGetMapLocationGoal(name,&goal);free(name);}
    else return 2;
    printf("GOAL %d ",result);for(int i=0;i<56;i++)printf("%02x",((unsigned char*)&goal)[i]);putchar('\n');fflush(stdout);
  }
  return 0;
}
