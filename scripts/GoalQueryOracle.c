/* Authored public goal-query observer. All called engine routines are unchanged native objects. */
#define ITEM_ORACLE_ENTRY item_oracle_unused_main
#define AAS_Trace item_default_trace
#define AAS_EntityInfo item_default_entity_info
#define AAS_PresenceTypeBoundingBox item_default_presence_bounds
#include "ItemOracle.c"
#undef AAS_Trace
#undef AAS_EntityInfo
#undef AAS_PresenceTypeBoundingBox

static float trace_fraction=1;
static int trace_entity, trace_startsolid, verbose;
static aas_entityinfo_t queried_entity;
bsp_trace_t AAS_Trace(vec3_t start,vec3_t mins,vec3_t maxs,vec3_t end,int passent,int mask) {
  vec3_t zero={0,0,0};if(!mins)mins=zero;if(!maxs)maxs=zero;
  if(verbose)fprintf(stderr,"TRACE start=%g,%g,%g mins=%g,%g,%g maxs=%g,%g,%g end=%g,%g,%g pass=%d mask=%d\n",start[0],start[1],start[2],mins[0],mins[1],mins[2],maxs[0],maxs[1],maxs[2],end[0],end[1],end[2],passent,mask);
  bsp_trace_t result;memset(&result,0,sizeof(result));result.fraction=trace_fraction;
  result.ent=trace_entity;result.startsolid=trace_startsolid;memcpy(result.endpos,end,sizeof(vec3_t));return result;
}
void AAS_EntityInfo(int number,aas_entityinfo_t *info) {
  if(verbose)fprintf(stderr,"ENTITY %d\n",number);*info=queried_entity;
}
void AAS_PresenceTypeBoundingBox(int presence,vec3_t mins,vec3_t maxs) {
  if(verbose)fprintf(stderr,"PRESENCE %d\n",presence);
  item_default_presence_bounds(presence,mins,maxs);
}

int main(int argc,char **argv) {
  verbose=argc>1;
  botimport.Print=print;
  char command[32];
  while(scanf("%31s",command)==1) {
    if(!strcmp(command,"touch")) {
      vec3_t origin;bot_goal_t goal;memset(&goal,0,sizeof(goal));
      for(int i=0;i<3;i++)if(scanf("%f",&origin[i])!=1)return 2;
      for(int i=0;i<3;i++)if(scanf("%f",&goal.origin[i])!=1)return 2;
      for(int i=0;i<3;i++)if(scanf("%f",&goal.mins[i])!=1)return 2;
      for(int i=0;i<3;i++)if(scanf("%f",&goal.maxs[i])!=1)return 2;
      if(scanf("%d",&goal.flags)!=1)return 2;
      printf("TOUCH %d\n",BotTouchingGoal(origin,&goal));
    } else if(!strcmp(command,"vis")) {
      int viewer;vec3_t eye,angles;bot_goal_t goal;memset(&goal,0,sizeof(goal));
      if(scanf("%d",&viewer)!=1)return 2;
      for(int i=0;i<3;i++)if(scanf("%f",&eye[i])!=1)return 2;
      for(int i=0;i<3;i++)if(scanf("%f",&angles[i])!=1)return 2;
      for(int i=0;i<3;i++)if(scanf("%f",&goal.origin[i])!=1)return 2;
      for(int i=0;i<3;i++)if(scanf("%f",&goal.mins[i])!=1)return 2;
      for(int i=0;i<3;i++)if(scanf("%f",&goal.maxs[i])!=1)return 2;
      if(scanf("%d %d %f %d %d %d %f %f",&goal.flags,&goal.entitynum,&trace_fraction,&trace_entity,&trace_startsolid,&queried_entity.valid,&queried_entity.ltime,&queried_entity.update_time)!=8)return 2;
      printf("VIS %d\n",BotItemGoalInVisButNotVisible(viewer,eye,angles,&goal));
    } else return 2;
    fflush(stdout);
  }
  return 0;
}
