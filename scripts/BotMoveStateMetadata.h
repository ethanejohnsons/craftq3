/* Development oracle layout metadata only; never included in the Java runtime.
 * Field layout from ioquake3 be_ai_move.c, commit
 * 588393618dbc82e7207c21c6ddecca229944a03a, lines 57-86.
 * Copyright (C) 1999-2005 Id Software, Inc.; GPL-2.0-or-later.
 * This declaration contains no engine routines or algorithm implementation.
 * Include q_shared.h and be_ai_move.h first.
 */
#ifndef CRAFTQ3_BOT_MOVE_STATE_METADATA_H
#define CRAFTQ3_BOT_MOVE_STATE_METADATA_H
typedef struct bot_movestate_s {
  vec3_t origin, velocity, viewoffset;
  int entitynum, client;
  float thinktime;
  int presencetype;
  vec3_t viewangles;
  int areanum, lastareanum, lastgoalareanum, lastreachnum;
  vec3_t lastorigin;
  int reachareanum, moveflags, jumpreach;
  float grapplevisible_time, lastgrappledist, reachability_time;
  int avoidreach[MAX_AVOIDREACH];
  float avoidreachtimes[MAX_AVOIDREACH];
  int avoidreachtries[MAX_AVOIDREACH];
  bot_avoidspot_t avoidspots[MAX_AVOIDSPOTS];
  int numavoidspots;
} bot_movestate_t;
extern bot_movestate_t *BotMoveStateFromHandle(int handle);
extern void BotAddToAvoidReach(bot_movestate_t *state, int number, float avoidtime);
#endif
