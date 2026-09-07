/* Authored goal-policy observer with controlled weights/routing; native goal code is unchanged. */
#define ITEM_ORACLE_ENTRY item_oracle_unused_main
#include "ItemOracle.c"

static weightconfig_t controlled_config;
static float weights[256];
static int reachable_area=100, point_area=100, verbose;
static int route_cost[1024][1024];
static observed_level_t records[256];
weightconfig_t *ReadWeightConfig(char *filename) { (void)filename;return &controlled_config; }
void FreeWeightConfig(weightconfig_t *config) { (void)config; }
int FindFuzzyWeight(weightconfig_t *config,char *name) {
  (void)config;for(int i=0;i<itemconfig->count;i++)if(!strcmp(itemconfig->items[i].classname,name))return i;return -1;
}
float FuzzyWeightUndecided(int *inventory,weightconfig_t *config,int number) {
  (void)inventory;(void)config;if(verbose)fprintf(stderr,"WEIGHT %d=%g\n",number,weights[number]);return weights[number];
}
int AAS_AreaReachability(int area) { if(verbose)fprintf(stderr,"REACHABILITY %d\n",area);return area>0; }
int BotReachabilityArea(vec3_t origin,int client) {
  if(verbose)fprintf(stderr,"BOTAREA %g %g %g client=%d =>%d\n",origin[0],origin[1],origin[2],client,reachable_area);return reachable_area;
}
int AAS_AreaTravelTimeToGoalArea(int area,vec3_t origin,int goal,int flags) {
  if(area<0||area>=1024||goal<0||goal>=1024)abort();
  if(verbose)fprintf(stderr,"ROUTE %d %g %g %g %d flags=%d =>%d\n",area,origin[0],origin[1],origin[2],goal,flags,route_cost[area][goal]);
  return route_cost[area][goal];
}

int main(int argc,char **argv) {
  if(argc<2||argc>4)return 2;root=argv[1];game_type=argc>2?atoi(argv[2]):0;verbose=argc>3;
  botimport.Print=print;botimport.FS_FOpenFile=fsopen;botimport.FS_Read=fsread;botimport.FS_FCloseFile=fsclose;
  if(BotSetupGoalAI())return 3;int state=BotAllocGoalState(13);if(BotLoadItemWeights(state,"controlled"))return 3;
  puts("READY");fflush(stdout);
  int inventory[256]={0},a,b,value,count;float duration;char command[32];vec3_t origin;
  while(scanf("%31s",command)==1) {
    if(!strcmp(command,"items")) {
      if(scanf("%d",&count)!=1||count<0||count>256)return 2;memset(records,0,sizeof(records));
      for(int i=0;i<count;i++) {
        observed_level_t *r=&records[i];
        if(scanf("%d %d %d %f %f %f %f %d %f %f %f %d %f",&r->number,&r->iteminfo,&r->flags,&r->weight,&r->origin[0],&r->origin[1],&r->origin[2],&r->area,&r->goalorigin[0],&r->goalorigin[1],&r->goalorigin[2],&r->entity,&r->timeout)!=13)return 2;
        if(r->iteminfo<0||r->iteminfo>=itemconfig->count)return 2;
        r->previous=i?&records[i-1]:NULL;r->next=i+1<count?&records[i+1]:NULL;
      }
      levelitems=count?records:NULL;puts("ITEMS");
    } else if(!strcmp(command,"weight")) {if(scanf("%d %f",&a,&duration)!=2||a<0||a>=256)return 2;weights[a]=duration;puts("WEIGHT");}
    else if(!strcmp(command,"route")){if(scanf("%d %d %d",&a,&b,&value)!=3||a<0||a>=1024||b<0||b>=1024)return 2;route_cost[a][b]=value;puts("ROUTE");}
    else if(!strcmp(command,"area")){if(scanf("%d",&reachable_area)!=1)return 2;puts("AREA");}
    else if(!strcmp(command,"time")){if(scanf("%f",&now)!=1)return 2;puts("TIME");}
    else if(!strcmp(command,"avoid")){if(scanf("%d %f",&a,&duration)!=2)return 2;BotSetAvoidGoalTime(state,a,duration);puts("AVOID");}
    else if(!strcmp(command,"reset")){BotResetGoalState(state);puts("RESET");}
    else if(!strcmp(command,"ltg")||!strcmp(command,"nbg")) {
      if(scanf("%f %f %f %d",&origin[0],&origin[1],&origin[2],&value)!=4)return 2;
      if(!strcmp(command,"ltg"))a=BotChooseLTGItem(state,origin,inventory,value);
      else {
        bot_goal_t target;memset(&target,0,sizeof(target));
        if(scanf("%d %f %f %f %f",&target.areanum,&target.origin[0],&target.origin[1],&target.origin[2],&duration)!=5)return 2;
        a=BotChooseNBGItem(state,origin,inventory,value,target.areanum<0?NULL:&target,duration);
      }
      bot_goal_t goal;memset(&goal,0,sizeof(goal));b=BotGetTopGoal(state,&goal);
      printf("CHOOSE %d TOP %d ",a,b);for(int i=0;i<56;i++)printf("%02x",((unsigned char*)&goal)[i]);putchar('\n');
      for(observed_level_t *r=levelitems;r;r=r->next)printf("AVOIDTIME %d %.9g\n",r->number,BotAvoidGoalTime(state,r->number));
    } else return 2;
    fflush(stdout);
  }
  return 0;
}
