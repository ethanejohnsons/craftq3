# VM syscalls

Generated from the `engine:vm-abi` import enums by `SyscallDocsTest`; do not edit.

A guest `CALL` to address `-1 - n` is trap `n`. Every import has a policy: `HOST` (the VM host must bind a handler), `SHARED` (implemented by `engine.vmabi.service`), `STUB` (a documented constant, no handler needed) or `FATAL`. When a handler throws, a degradable import returns its neutral value, is counted and logged once per module and number; `vm_strictSyscalls 1` makes that a VM fault. Critical imports always fault. Unknown numbers return 0 (logged once) unless strict.

## ABI profiles

| | 1.32 | retail |
| --- | --- | --- |
| `entityState_t` | 208 | 204 |
| `playerState_t` | 468 | 444 |
| `sharedEntity_t` | 516 | 504 |
| `usercmd_t` | 24 | 24 |
| `trace_t` | 56 | 56 |
| `glconfig_t` | 11332 | 4164 |
| `snapshot_t` | 53772 | 52724 |
| `bot_consolemessage_t` | 276 | 172 |
| `bot_match_t` | 328 | 224 |

Detection (`AbiDetector`): the verified retail SHA-256 of pak0's qagame, cgame and ui; otherwise the import usage profile (the trap numbers called through constant addresses) against both tables; otherwise 1.32. `vm_abi` overrides it.

## qagame

197 imports in the 1.32 numbering, 160 HOST, 34 SHARED, 3 STUB.

| # | retail # | Import | Args | Policy | Detail |
| ---: | ---: | --- | ---: | --- | --- |
| 0 | 0 | `G_PRINT` | 1 | SHARED | degrades to 0 |
| 1 | 1 | `G_ERROR` | 1 | SHARED | critical: trap_Error ends the game; the host drops the map |
| 2 | 2 | `G_MILLISECONDS` | 0 | SHARED | degrades to 0 |
| 3 | 3 | `G_CVAR_REGISTER` | 4 | SHARED | degrades to 0 |
| 4 | 4 | `G_CVAR_UPDATE` | 1 | SHARED | degrades to 0 |
| 5 | 5 | `G_CVAR_SET` | 2 | SHARED | degrades to 0 |
| 6 | 6 | `G_CVAR_VARIABLE_INTEGER_VALUE` | 1 | SHARED | degrades to 0 |
| 7 | 7 | `G_CVAR_VARIABLE_STRING_BUFFER` | 3 | SHARED | degrades to 0, empty string at arg 1 |
| 8 | 8 | `G_ARGC` | 0 | SHARED | degrades to 0 |
| 9 | 9 | `G_ARGV` | 3 | SHARED | degrades to 0, empty string at arg 1 |
| 10 | 10 | `G_FS_FOPEN_FILE` | 3 | SHARED | degrades to -1 |
| 11 | 11 | `G_FS_READ` | 3 | SHARED | degrades to 0 |
| 12 | 12 | `G_FS_WRITE` | 3 | SHARED | degrades to 0 |
| 13 | 13 | `G_FS_FCLOSE_FILE` | 1 | SHARED | degrades to 0 |
| 14 | 14 | `G_SEND_CONSOLE_COMMAND` | 2 | SHARED | degrades to 0 |
| 15 | 15 | `G_LOCATE_GAME_DATA` | 5 | HOST | critical: without the entity and client arrays the server cannot run |
| 16 | 16 | `G_DROP_CLIENT` | 2 | HOST | degrades to 0 |
| 17 | 17 | `G_SEND_SERVER_COMMAND` | 2 | HOST | degrades to 0 |
| 18 | 18 | `G_SET_CONFIGSTRING` | 2 | HOST | degrades to 0 |
| 19 | 19 | `G_GET_CONFIGSTRING` | 3 | HOST | degrades to 0, empty string at arg 1 |
| 20 | 20 | `G_GET_USERINFO` | 3 | HOST | degrades to 0, empty string at arg 1 |
| 21 | 21 | `G_SET_USERINFO` | 2 | HOST | degrades to 0 |
| 22 | 22 | `G_GET_SERVERINFO` | 2 | SHARED | degrades to 0, empty string at arg 0 |
| 23 | 23 | `G_SET_BRUSH_MODEL` | 2 | HOST | degrades to 0 |
| 24 | 24 | `G_TRACE` | 7 | HOST | degrades to 0, zeroed 56 bytes at arg 0 |
| 25 | 25 | `G_POINT_CONTENTS` | 2 | HOST | degrades to 0 |
| 26 | 26 | `G_IN_PVS` | 2 | HOST | degrades to 0 |
| 27 | 27 | `G_IN_PVS_IGNORE_PORTALS` | 2 | HOST | degrades to 0 |
| 28 | 28 | `G_ADJUST_AREA_PORTAL_STATE` | 2 | HOST | degrades to 0 |
| 29 | 29 | `G_AREAS_CONNECTED` | 2 | HOST | degrades to 0 |
| 30 | 30 | `G_LINKENTITY` | 1 | HOST | degrades to 0 |
| 31 | 31 | `G_UNLINKENTITY` | 1 | HOST | degrades to 0 |
| 32 | 32 | `G_ENTITIES_IN_BOX` | 4 | HOST | degrades to 0 |
| 33 | 33 | `G_ENTITY_CONTACT` | 3 | HOST | degrades to 0 |
| 34 | 34 | `G_BOT_ALLOCATE_CLIENT` | 0 | HOST | degrades to -1 |
| 35 | 35 | `G_BOT_FREE_CLIENT` | 1 | HOST | degrades to 0 |
| 36 | 36 | `G_GET_USERCMD` | 2 | HOST | degrades to 0, zeroed 24 bytes at arg 1 |
| 37 | 37 | `G_GET_ENTITY_TOKEN` | 2 | HOST | degrades to 0, empty string at arg 0 |
| 38 | 38 | `G_FS_GETFILELIST` | 4 | SHARED | degrades to 0 |
| 39 | 39 | `G_DEBUG_POLYGON_CREATE` | 3 | STUB | returns 0: debug polygons are an editor visualisation; no renderer is attached |
| 40 | 40 | `G_DEBUG_POLYGON_DELETE` | 1 | STUB | returns 0: no debug polygons are ever created |
| 41 | 41 | `G_REAL_TIME` | 1 | SHARED | degrades to 0 |
| 42 | 42 | `G_SNAPVECTOR` | 1 | SHARED | degrades to 0 |
| 43 | - | `G_TRACECAPSULE` | 7 | HOST | degrades to 0, zeroed 56 bytes at arg 0 |
| 44 | - | `G_ENTITY_CONTACTCAPSULE` | 3 | HOST | degrades to 0 |
| 45 | - | `G_FS_SEEK` | 3 | SHARED | degrades to -1 |
| 100 | 100 | `TRAP_MEMSET` | 3 | SHARED | degrades to 0 |
| 101 | 101 | `TRAP_MEMCPY` | 3 | SHARED | degrades to 0 |
| 102 | 102 | `TRAP_STRNCPY` | 3 | SHARED | degrades to 0 |
| 103 | 103 | `TRAP_SIN` | 1 | SHARED | degrades to 0 |
| 104 | 104 | `TRAP_COS` | 1 | SHARED | degrades to 0 |
| 105 | 105 | `TRAP_ATAN2` | 2 | SHARED | degrades to 0 |
| 106 | 106 | `TRAP_SQRT` | 1 | SHARED | degrades to 0 |
| 107 | 107 | `TRAP_MATRIXMULTIPLY` | 3 | SHARED | degrades to 0 |
| 108 | 108 | `TRAP_ANGLEVECTORS` | 4 | SHARED | degrades to 0 |
| 109 | 109 | `TRAP_PERPENDICULARVECTOR` | 2 | SHARED | degrades to 0 |
| 110 | 110 | `TRAP_FLOOR` | 1 | SHARED | degrades to 0 |
| 111 | 111 | `TRAP_CEIL` | 1 | SHARED | degrades to 0 |
| 112 | 112 | `TRAP_TESTPRINTINT` | 2 | SHARED | degrades to 0 |
| 113 | 113 | `TRAP_TESTPRINTFLOAT` | 2 | SHARED | degrades to 0 |
| 200 | 200 | `BOTLIB_SETUP` | 0 | HOST | degrades to 1 |
| 201 | 201 | `BOTLIB_SHUTDOWN` | 0 | HOST | degrades to 0 |
| 202 | 202 | `BOTLIB_LIBVAR_SET` | 2 | HOST | degrades to 0 |
| 203 | 203 | `BOTLIB_LIBVAR_GET` | 3 | HOST | degrades to 0, empty string at arg 1 |
| 204 | 204 | `BOTLIB_PC_ADD_GLOBAL_DEFINE` | 1 | HOST | degrades to 0 |
| 205 | 205 | `BOTLIB_START_FRAME` | 1 | HOST | degrades to 0 |
| 206 | 206 | `BOTLIB_LOAD_MAP` | 1 | HOST | degrades to 1 |
| 207 | 207 | `BOTLIB_UPDATENTITY` | 2 | HOST | degrades to 0 |
| 208 | 208 | `BOTLIB_TEST` | 4 | STUB | returns 0: botlib's interactive debug test hook does nothing |
| 209 | 209 | `BOTLIB_GET_SNAPSHOT_ENTITY` | 2 | HOST | degrades to -1 |
| 210 | 210 | `BOTLIB_GET_CONSOLE_MESSAGE` | 3 | HOST | degrades to 0, empty string at arg 1 |
| 211 | 211 | `BOTLIB_USER_COMMAND` | 2 | HOST | degrades to 0 |
| 300 | - | `BOTLIB_AAS_ENABLE_ROUTING_AREA` | 2 | HOST | degrades to 0 |
| 301 | - | `BOTLIB_AAS_BBOX_AREAS` | 4 | HOST | degrades to 0 |
| 302 | - | `BOTLIB_AAS_AREA_INFO` | 2 | HOST | degrades to 0 |
| 303 | 303 | `BOTLIB_AAS_ENTITY_INFO` | 2 | HOST | degrades to 0 |
| 304 | 304 | `BOTLIB_AAS_INITIALIZED` | 0 | HOST | degrades to 0 |
| 305 | 305 | `BOTLIB_AAS_PRESENCE_TYPE_BOUNDING_BOX` | 3 | HOST | degrades to 0 |
| 306 | 306 | `BOTLIB_AAS_TIME` | 0 | HOST | degrades to 0 |
| 307 | 307 | `BOTLIB_AAS_POINT_AREA_NUM` | 1 | HOST | degrades to 0 |
| 308 | 308 | `BOTLIB_AAS_TRACE_AREAS` | 5 | HOST | degrades to 0 |
| 309 | 309 | `BOTLIB_AAS_POINT_CONTENTS` | 1 | HOST | degrades to 0 |
| 310 | 310 | `BOTLIB_AAS_NEXT_BSP_ENTITY` | 1 | HOST | degrades to 0 |
| 311 | 311 | `BOTLIB_AAS_VALUE_FOR_BSP_EPAIR_KEY` | 4 | HOST | degrades to 0, empty string at arg 2 |
| 312 | 312 | `BOTLIB_AAS_VECTOR_FOR_BSP_EPAIR_KEY` | 3 | HOST | degrades to 0, zeroed 12 bytes at arg 2 |
| 313 | 313 | `BOTLIB_AAS_FLOAT_FOR_BSP_EPAIR_KEY` | 3 | HOST | degrades to 0, zeroed 4 bytes at arg 2 |
| 314 | 314 | `BOTLIB_AAS_INT_FOR_BSP_EPAIR_KEY` | 3 | HOST | degrades to 0, zeroed 4 bytes at arg 2 |
| 315 | 315 | `BOTLIB_AAS_AREA_REACHABILITY` | 1 | HOST | degrades to 0 |
| 316 | 316 | `BOTLIB_AAS_AREA_TRAVELTIME_TO_GOAL_AREA` | 4 | HOST | degrades to 0 |
| 317 | 317 | `BOTLIB_AAS_SWIMMING` | 1 | HOST | degrades to 0 |
| 318 | 318 | `BOTLIB_AAS_PREDICT_CLIENT_MOVEMENT` | 13 | HOST | degrades to 0 |
| 400 | 400 | `BOTLIB_EA_SAY` | 2 | HOST | degrades to 0 |
| 401 | 401 | `BOTLIB_EA_SAY_TEAM` | 2 | HOST | degrades to 0 |
| 402 | 407 | `BOTLIB_EA_COMMAND` | 2 | HOST | degrades to 0 |
| 403 | - | `BOTLIB_EA_ACTION` | 2 | HOST | degrades to 0 |
| 404 | 406 | `BOTLIB_EA_GESTURE` | 1 | HOST | degrades to 0 |
| 405 | 409 | `BOTLIB_EA_TALK` | 1 | HOST | degrades to 0 |
| 406 | 410 | `BOTLIB_EA_ATTACK` | 1 | HOST | degrades to 0 |
| 407 | 411 | `BOTLIB_EA_USE` | 1 | HOST | degrades to 0 |
| 408 | 412 | `BOTLIB_EA_RESPAWN` | 1 | HOST | degrades to 0 |
| 409 | 415 | `BOTLIB_EA_CROUCH` | 1 | HOST | degrades to 0 |
| 410 | 416 | `BOTLIB_EA_MOVE_UP` | 1 | HOST | degrades to 0 |
| 411 | 417 | `BOTLIB_EA_MOVE_DOWN` | 1 | HOST | degrades to 0 |
| 412 | 418 | `BOTLIB_EA_MOVE_FORWARD` | 1 | HOST | degrades to 0 |
| 413 | 419 | `BOTLIB_EA_MOVE_BACK` | 1 | HOST | degrades to 0 |
| 414 | 420 | `BOTLIB_EA_MOVE_LEFT` | 1 | HOST | degrades to 0 |
| 415 | 421 | `BOTLIB_EA_MOVE_RIGHT` | 1 | HOST | degrades to 0 |
| 416 | 408 | `BOTLIB_EA_SELECT_WEAPON` | 2 | HOST | degrades to 0 |
| 417 | 413 | `BOTLIB_EA_JUMP` | 1 | HOST | degrades to 0 |
| 418 | 414 | `BOTLIB_EA_DELAYED_JUMP` | 1 | HOST | degrades to 0 |
| 419 | 422 | `BOTLIB_EA_MOVE` | 3 | HOST | degrades to 0 |
| 420 | 423 | `BOTLIB_EA_VIEW` | 2 | HOST | degrades to 0 |
| 421 | 424 | `BOTLIB_EA_END_REGULAR` | 2 | HOST | degrades to 0 |
| 422 | 425 | `BOTLIB_EA_GET_INPUT` | 3 | HOST | degrades to 0, zeroed 40 bytes at arg 2 |
| 423 | 426 | `BOTLIB_EA_RESET_INPUT` | 1 | HOST | degrades to 0 |
| 500 | 500 | `BOTLIB_AI_LOAD_CHARACTER` | 2 | HOST | degrades to 0 |
| 501 | 501 | `BOTLIB_AI_FREE_CHARACTER` | 1 | HOST | degrades to 0 |
| 502 | 502 | `BOTLIB_AI_CHARACTERISTIC_FLOAT` | 2 | HOST | degrades to 0 |
| 503 | 503 | `BOTLIB_AI_CHARACTERISTIC_BFLOAT` | 4 | HOST | degrades to 0 |
| 504 | 504 | `BOTLIB_AI_CHARACTERISTIC_INTEGER` | 2 | HOST | degrades to 0 |
| 505 | 505 | `BOTLIB_AI_CHARACTERISTIC_BINTEGER` | 4 | HOST | degrades to 0 |
| 506 | 506 | `BOTLIB_AI_CHARACTERISTIC_STRING` | 4 | HOST | degrades to 0, empty string at arg 2 |
| 507 | 507 | `BOTLIB_AI_ALLOC_CHAT_STATE` | 0 | HOST | degrades to 0 |
| 508 | 508 | `BOTLIB_AI_FREE_CHAT_STATE` | 1 | HOST | degrades to 0 |
| 509 | 509 | `BOTLIB_AI_QUEUE_CONSOLE_MESSAGE` | 3 | HOST | degrades to 0 |
| 510 | 510 | `BOTLIB_AI_REMOVE_CONSOLE_MESSAGE` | 2 | HOST | degrades to 0 |
| 511 | 511 | `BOTLIB_AI_NEXT_CONSOLE_MESSAGE` | 2 | HOST | degrades to 0 |
| 512 | 512 | `BOTLIB_AI_NUM_CONSOLE_MESSAGE` | 1 | HOST | degrades to 0 |
| 513 | 513 | `BOTLIB_AI_INITIAL_CHAT` | 11 | HOST | degrades to 0 |
| 514 | 514 | `BOTLIB_AI_REPLY_CHAT` | 12 | HOST | degrades to 0 |
| 515 | 515 | `BOTLIB_AI_CHAT_LENGTH` | 1 | HOST | degrades to 0 |
| 516 | 516 | `BOTLIB_AI_ENTER_CHAT` | 3 | HOST | degrades to 0 |
| 517 | 517 | `BOTLIB_AI_STRING_CONTAINS` | 3 | HOST | degrades to -1 |
| 518 | 518 | `BOTLIB_AI_FIND_MATCH` | 3 | HOST | degrades to 0 |
| 519 | 519 | `BOTLIB_AI_MATCH_VARIABLE` | 4 | HOST | degrades to 0, empty string at arg 2 |
| 520 | 520 | `BOTLIB_AI_UNIFY_WHITE_SPACES` | 1 | HOST | degrades to 0 |
| 521 | 521 | `BOTLIB_AI_REPLACE_SYNONYMS` | 2 | HOST | degrades to 0 |
| 522 | 522 | `BOTLIB_AI_LOAD_CHAT_FILE` | 3 | HOST | degrades to 8 |
| 523 | 523 | `BOTLIB_AI_SET_CHAT_GENDER` | 2 | HOST | degrades to 0 |
| 524 | 524 | `BOTLIB_AI_SET_CHAT_NAME` | 3 | HOST | degrades to 0 |
| 525 | 525 | `BOTLIB_AI_RESET_GOAL_STATE` | 1 | HOST | degrades to 0 |
| 526 | 526 | `BOTLIB_AI_RESET_AVOID_GOALS` | 1 | HOST | degrades to 0 |
| 527 | 527 | `BOTLIB_AI_PUSH_GOAL` | 2 | HOST | degrades to 0 |
| 528 | 528 | `BOTLIB_AI_POP_GOAL` | 1 | HOST | degrades to 0 |
| 529 | 529 | `BOTLIB_AI_EMPTY_GOAL_STACK` | 1 | HOST | degrades to 0 |
| 530 | 530 | `BOTLIB_AI_DUMP_AVOID_GOALS` | 1 | HOST | degrades to 0 |
| 531 | 531 | `BOTLIB_AI_DUMP_GOAL_STACK` | 1 | HOST | degrades to 0 |
| 532 | 532 | `BOTLIB_AI_GOAL_NAME` | 3 | HOST | degrades to 0, empty string at arg 1 |
| 533 | 533 | `BOTLIB_AI_GET_TOP_GOAL` | 2 | HOST | degrades to 0 |
| 534 | 534 | `BOTLIB_AI_GET_SECOND_GOAL` | 2 | HOST | degrades to 0 |
| 535 | 535 | `BOTLIB_AI_CHOOSE_LTG_ITEM` | 4 | HOST | degrades to 0 |
| 536 | 536 | `BOTLIB_AI_CHOOSE_NBG_ITEM` | 6 | HOST | degrades to 0 |
| 537 | 537 | `BOTLIB_AI_TOUCHING_GOAL` | 2 | HOST | degrades to 0 |
| 538 | 538 | `BOTLIB_AI_ITEM_GOAL_IN_VIS_BUT_NOT_VISIBLE` | 4 | HOST | degrades to 0 |
| 539 | 539 | `BOTLIB_AI_GET_LEVEL_ITEM_GOAL` | 3 | HOST | degrades to -1 |
| 540 | 540 | `BOTLIB_AI_AVOID_GOAL_TIME` | 2 | HOST | degrades to 0 |
| 541 | 541 | `BOTLIB_AI_INIT_LEVEL_ITEMS` | 0 | HOST | degrades to 0 |
| 542 | 542 | `BOTLIB_AI_UPDATE_ENTITY_ITEMS` | 0 | HOST | degrades to 0 |
| 543 | 543 | `BOTLIB_AI_LOAD_ITEM_WEIGHTS` | 2 | HOST | degrades to 9 |
| 544 | 544 | `BOTLIB_AI_FREE_ITEM_WEIGHTS` | 1 | HOST | degrades to 0 |
| 545 | 545 | `BOTLIB_AI_SAVE_GOAL_FUZZY_LOGIC` | 2 | HOST | degrades to 0 |
| 546 | 546 | `BOTLIB_AI_ALLOC_GOAL_STATE` | 1 | HOST | degrades to 0 |
| 547 | 547 | `BOTLIB_AI_FREE_GOAL_STATE` | 1 | HOST | degrades to 0 |
| 548 | 548 | `BOTLIB_AI_RESET_MOVE_STATE` | 1 | HOST | degrades to 0 |
| 549 | 549 | `BOTLIB_AI_MOVE_TO_GOAL` | 4 | HOST | degrades to 0, zeroed 52 bytes at arg 0 |
| 550 | 550 | `BOTLIB_AI_MOVE_IN_DIRECTION` | 4 | HOST | degrades to 0 |
| 551 | 551 | `BOTLIB_AI_RESET_AVOID_REACH` | 1 | HOST | degrades to 0 |
| 552 | 552 | `BOTLIB_AI_RESET_LAST_AVOID_REACH` | 1 | HOST | degrades to 0 |
| 553 | 553 | `BOTLIB_AI_REACHABILITY_AREA` | 2 | HOST | degrades to 0 |
| 554 | 554 | `BOTLIB_AI_MOVEMENT_VIEW_TARGET` | 5 | HOST | degrades to 0 |
| 555 | 555 | `BOTLIB_AI_ALLOC_MOVE_STATE` | 0 | HOST | degrades to 0 |
| 556 | 556 | `BOTLIB_AI_FREE_MOVE_STATE` | 1 | HOST | degrades to 0 |
| 557 | 557 | `BOTLIB_AI_INIT_MOVE_STATE` | 2 | HOST | degrades to 0 |
| 558 | 558 | `BOTLIB_AI_CHOOSE_BEST_FIGHT_WEAPON` | 2 | HOST | degrades to 0 |
| 559 | 559 | `BOTLIB_AI_GET_WEAPON_INFO` | 3 | HOST | degrades to 0 |
| 560 | 560 | `BOTLIB_AI_LOAD_WEAPON_WEIGHTS` | 2 | HOST | degrades to 11 |
| 561 | 561 | `BOTLIB_AI_ALLOC_WEAPON_STATE` | 0 | HOST | degrades to 0 |
| 562 | 562 | `BOTLIB_AI_FREE_WEAPON_STATE` | 1 | HOST | degrades to 0 |
| 563 | 563 | `BOTLIB_AI_RESET_WEAPON_STATE` | 1 | HOST | degrades to 0 |
| 564 | 564 | `BOTLIB_AI_GENETIC_PARENTS_AND_CHILD_SELECTION` | 5 | HOST | degrades to 0 |
| 565 | 565 | `BOTLIB_AI_INTERBREED_GOAL_FUZZY_LOGIC` | 3 | HOST | degrades to 0 |
| 566 | 566 | `BOTLIB_AI_MUTATE_GOAL_FUZZY_LOGIC` | 2 | HOST | degrades to 0 |
| 567 | 567 | `BOTLIB_AI_GET_NEXT_CAMP_SPOT_GOAL` | 2 | HOST | degrades to 0 |
| 568 | 568 | `BOTLIB_AI_GET_MAP_LOCATION_GOAL` | 2 | HOST | degrades to 0 |
| 569 | 569 | `BOTLIB_AI_NUM_INITIAL_CHATS` | 2 | HOST | degrades to 0 |
| 570 | 570 | `BOTLIB_AI_GET_CHAT_MESSAGE` | 3 | HOST | degrades to 0, empty string at arg 1 |
| 571 | 571 | `BOTLIB_AI_REMOVE_FROM_AVOID_GOALS` | 2 | HOST | degrades to 0 |
| 572 | 572 | `BOTLIB_AI_PREDICT_VISIBLE_POSITION` | 5 | HOST | degrades to 0 |
| 573 | - | `BOTLIB_AI_SET_AVOID_GOAL_TIME` | 3 | HOST | degrades to 0 |
| 574 | - | `BOTLIB_AI_ADD_AVOID_SPOT` | 4 | HOST | degrades to 0 |
| 575 | - | `BOTLIB_AAS_ALTERNATIVE_ROUTE_GOAL` | 8 | HOST | degrades to 0 |
| 576 | - | `BOTLIB_AAS_PREDICT_ROUTE` | 11 | HOST | degrades to 0 |
| 577 | - | `BOTLIB_AAS_POINT_REACHABILITY_AREA_INDEX` | 1 | HOST | degrades to 0 |
| 578 | - | `BOTLIB_PC_LOAD_SOURCE` | 1 | HOST | degrades to 0 |
| 579 | - | `BOTLIB_PC_FREE_SOURCE` | 1 | HOST | degrades to 0 |
| 580 | - | `BOTLIB_PC_READ_TOKEN` | 2 | HOST | degrades to 0 |
| 581 | - | `BOTLIB_PC_SOURCE_FILE_AND_LINE` | 3 | HOST | degrades to 0, empty string at arg 1 |

## cgame

102 imports in the 1.32 numbering, 69 HOST, 32 SHARED, 1 STUB.

| # | retail # | Import | Args | Policy | Detail |
| ---: | ---: | --- | ---: | --- | --- |
| 0 | 0 | `CG_PRINT` | 1 | SHARED | degrades to 0 |
| 1 | 1 | `CG_ERROR` | 1 | SHARED | critical: trap_Error ends the cgame; the client disconnects |
| 2 | 2 | `CG_MILLISECONDS` | 0 | SHARED | degrades to 0 |
| 3 | 3 | `CG_CVAR_REGISTER` | 4 | SHARED | degrades to 0 |
| 4 | 4 | `CG_CVAR_UPDATE` | 1 | SHARED | degrades to 0 |
| 5 | 5 | `CG_CVAR_SET` | 2 | SHARED | degrades to 0 |
| 6 | 6 | `CG_CVAR_VARIABLESTRINGBUFFER` | 3 | SHARED | degrades to 0, empty string at arg 1 |
| 7 | 7 | `CG_ARGC` | 0 | SHARED | degrades to 0 |
| 8 | 8 | `CG_ARGV` | 3 | SHARED | degrades to 0, empty string at arg 1 |
| 9 | 9 | `CG_ARGS` | 2 | SHARED | degrades to 0, empty string at arg 0 |
| 10 | 10 | `CG_FS_FOPENFILE` | 3 | SHARED | degrades to -1 |
| 11 | 11 | `CG_FS_READ` | 3 | SHARED | degrades to 0 |
| 12 | 12 | `CG_FS_WRITE` | 3 | SHARED | degrades to 0 |
| 13 | 13 | `CG_FS_FCLOSEFILE` | 1 | SHARED | degrades to 0 |
| 14 | 14 | `CG_SENDCONSOLECOMMAND` | 1 | SHARED | degrades to 0 |
| 15 | 15 | `CG_ADDCOMMAND` | 1 | SHARED | degrades to 0 |
| 16 | 16 | `CG_SENDCLIENTCOMMAND` | 1 | HOST | degrades to 0 |
| 17 | 17 | `CG_UPDATESCREEN` | 0 | HOST | degrades to 0 |
| 18 | 18 | `CG_CM_LOADMAP` | 1 | HOST | critical: prediction and marks need the collision map |
| 19 | 19 | `CG_CM_NUMINLINEMODELS` | 0 | HOST | degrades to 0 |
| 20 | 20 | `CG_CM_INLINEMODEL` | 1 | HOST | degrades to 0 |
| 21 | 21 | `CG_CM_LOADMODEL` | 1 | HOST | degrades to 0 |
| 22 | 22 | `CG_CM_TEMPBOXMODEL` | 2 | HOST | degrades to 0 |
| 23 | 23 | `CG_CM_POINTCONTENTS` | 2 | HOST | degrades to 0 |
| 24 | 24 | `CG_CM_TRANSFORMEDPOINTCONTENTS` | 4 | HOST | degrades to 0 |
| 25 | 25 | `CG_CM_BOXTRACE` | 7 | HOST | degrades to 0, zeroed 56 bytes at arg 0 |
| 26 | 26 | `CG_CM_TRANSFORMEDBOXTRACE` | 9 | HOST | degrades to 0, zeroed 56 bytes at arg 0 |
| 27 | 27 | `CG_CM_MARKFRAGMENTS` | 7 | HOST | degrades to 0 |
| 28 | 28 | `CG_S_STARTSOUND` | 4 | HOST | degrades to 0 |
| 29 | 29 | `CG_S_STARTLOCALSOUND` | 2 | HOST | degrades to 0 |
| 30 | 30 | `CG_S_CLEARLOOPINGSOUNDS` | 1 | HOST | degrades to 0 |
| 31 | 31 | `CG_S_ADDLOOPINGSOUND` | 4 | HOST | degrades to 0 |
| 32 | 32 | `CG_S_UPDATEENTITYPOSITION` | 2 | HOST | degrades to 0 |
| 33 | 33 | `CG_S_RESPATIALIZE` | 4 | HOST | degrades to 0 |
| 34 | 34 | `CG_S_REGISTERSOUND` | 2 | HOST | degrades to 0 |
| 35 | 35 | `CG_S_STARTBACKGROUNDTRACK` | 2 | HOST | degrades to 0 |
| 36 | 36 | `CG_R_LOADWORLDMAP` | 1 | HOST | degrades to 0 |
| 37 | 37 | `CG_R_REGISTERMODEL` | 1 | HOST | degrades to 0 |
| 38 | 38 | `CG_R_REGISTERSKIN` | 1 | HOST | degrades to 0 |
| 39 | 39 | `CG_R_REGISTERSHADER` | 1 | HOST | degrades to 0 |
| 40 | 40 | `CG_R_CLEARSCENE` | 0 | HOST | degrades to 0 |
| 41 | 41 | `CG_R_ADDREFENTITYTOSCENE` | 1 | HOST | degrades to 0 |
| 42 | 42 | `CG_R_ADDPOLYTOSCENE` | 3 | HOST | degrades to 0 |
| 43 | 43 | `CG_R_ADDLIGHTTOSCENE` | 5 | HOST | degrades to 0 |
| 44 | 44 | `CG_R_RENDERSCENE` | 1 | HOST | degrades to 0 |
| 45 | 45 | `CG_R_SETCOLOR` | 1 | HOST | degrades to 0 |
| 46 | 46 | `CG_R_DRAWSTRETCHPIC` | 9 | HOST | degrades to 0 |
| 47 | 47 | `CG_R_MODELBOUNDS` | 3 | HOST | degrades to 0 |
| 48 | 48 | `CG_R_LERPTAG` | 6 | HOST | degrades to 0, zeroed 48 bytes at arg 0 |
| 49 | 49 | `CG_GETGLCONFIG` | 1 | HOST | critical: screen scaling divides by the video size |
| 50 | 50 | `CG_GETGAMESTATE` | 1 | HOST | critical: the cgame cannot start without configstrings |
| 51 | 51 | `CG_GETCURRENTSNAPSHOTNUMBER` | 2 | HOST | degrades to 0 |
| 52 | 52 | `CG_GETSNAPSHOT` | 2 | HOST | degrades to 0 |
| 53 | 53 | `CG_GETSERVERCOMMAND` | 1 | HOST | degrades to 0 |
| 54 | 54 | `CG_GETCURRENTCMDNUMBER` | 0 | HOST | degrades to 0 |
| 55 | 55 | `CG_GETUSERCMD` | 2 | HOST | degrades to 0, zeroed 24 bytes at arg 1 |
| 56 | 56 | `CG_SETUSERCMDVALUE` | 2 | HOST | degrades to 0 |
| 57 | 57 | `CG_R_REGISTERSHADERNOMIP` | 1 | HOST | degrades to 0 |
| 58 | 58 | `CG_MEMORY_REMAINING` | 0 | STUB | returns 33554432: the JVM has no hunk; a constant large value keeps low-memory paths off |
| 59 | - | `CG_R_REGISTERFONT` | 3 | HOST | degrades to 0 |
| 60 | - | `CG_KEY_ISDOWN` | 1 | HOST | degrades to 0 |
| 61 | - | `CG_KEY_GETCATCHER` | 0 | HOST | degrades to 0 |
| 62 | - | `CG_KEY_SETCATCHER` | 1 | HOST | degrades to 0 |
| 63 | - | `CG_KEY_GETKEY` | 1 | HOST | degrades to -1 |
| 64 | - | `CG_PC_ADD_GLOBAL_DEFINE` | 1 | HOST | degrades to 0 |
| 65 | - | `CG_PC_LOAD_SOURCE` | 1 | HOST | degrades to 0 |
| 66 | - | `CG_PC_FREE_SOURCE` | 1 | HOST | degrades to 0 |
| 67 | - | `CG_PC_READ_TOKEN` | 2 | HOST | degrades to 0 |
| 68 | - | `CG_PC_SOURCE_FILE_AND_LINE` | 3 | HOST | degrades to 0, empty string at arg 1 |
| 69 | - | `CG_S_STOPBACKGROUNDTRACK` | 0 | HOST | degrades to 0 |
| 70 | - | `CG_REAL_TIME` | 1 | SHARED | degrades to 0 |
| 71 | - | `CG_SNAPVECTOR` | 1 | SHARED | degrades to 0 |
| 72 | - | `CG_REMOVECOMMAND` | 1 | SHARED | degrades to 0 |
| 73 | - | `CG_R_LIGHTFORPOINT` | 4 | HOST | degrades to 0 |
| 74 | - | `CG_CIN_PLAYCINEMATIC` | 6 | HOST | degrades to -1 |
| 75 | - | `CG_CIN_STOPCINEMATIC` | 1 | HOST | degrades to 0 |
| 76 | - | `CG_CIN_RUNCINEMATIC` | 1 | HOST | degrades to 0 |
| 77 | - | `CG_CIN_DRAWCINEMATIC` | 1 | HOST | degrades to 0 |
| 78 | - | `CG_CIN_SETEXTENTS` | 5 | HOST | degrades to 0 |
| 79 | - | `CG_R_REMAP_SHADER` | 3 | HOST | degrades to 0 |
| 80 | - | `CG_S_ADDREALLOOPINGSOUND` | 4 | HOST | degrades to 0 |
| 81 | - | `CG_S_STOPLOOPINGSOUND` | 1 | HOST | degrades to 0 |
| 82 | - | `CG_CM_TEMPCAPSULEMODEL` | 2 | HOST | degrades to 0 |
| 83 | - | `CG_CM_CAPSULETRACE` | 7 | HOST | degrades to 0, zeroed 56 bytes at arg 0 |
| 84 | - | `CG_CM_TRANSFORMEDCAPSULETRACE` | 9 | HOST | degrades to 0, zeroed 56 bytes at arg 0 |
| 85 | - | `CG_R_ADDADDITIVELIGHTTOSCENE` | 5 | HOST | degrades to 0 |
| 86 | - | `CG_GET_ENTITY_TOKEN` | 2 | HOST | degrades to 0, empty string at arg 0 |
| 87 | - | `CG_R_ADDPOLYSTOSCENE` | 4 | HOST | degrades to 0 |
| 88 | - | `CG_R_INPVS` | 2 | HOST | degrades to 0 |
| 89 | - | `CG_FS_SEEK` | 3 | SHARED | degrades to -1 |
| 100 | 100 | `CG_MEMSET` | 3 | SHARED | degrades to 0 |
| 101 | 101 | `CG_MEMCPY` | 3 | SHARED | degrades to 0 |
| 102 | 102 | `CG_STRNCPY` | 3 | SHARED | degrades to 0 |
| 103 | 103 | `CG_SIN` | 1 | SHARED | degrades to 0 |
| 104 | 104 | `CG_COS` | 1 | SHARED | degrades to 0 |
| 105 | 105 | `CG_ATAN2` | 2 | SHARED | degrades to 0 |
| 106 | 106 | `CG_SQRT` | 1 | SHARED | degrades to 0 |
| 107 | - | `CG_FLOOR` | 1 | SHARED | degrades to 0 |
| 108 | - | `CG_CEIL` | 1 | SHARED | degrades to 0 |
| 109 | - | `CG_TESTPRINTINT` | 2 | SHARED | degrades to 0 |
| 110 | - | `CG_TESTPRINTFLOAT` | 2 | SHARED | degrades to 0 |
| 111 | - | `CG_ACOS` | 1 | SHARED | degrades to 0 |

## ui

97 imports in the 1.32 numbering, 63 HOST, 31 SHARED, 3 STUB.

| # | retail # | Import | Args | Policy | Detail |
| ---: | ---: | --- | ---: | --- | --- |
| 0 | 0 | `UI_ERROR` | 1 | SHARED | critical: trap_Error ends the ui; the client restarts it |
| 1 | 1 | `UI_PRINT` | 1 | SHARED | degrades to 0 |
| 2 | 2 | `UI_MILLISECONDS` | 0 | SHARED | degrades to 0 |
| 3 | 3 | `UI_CVAR_SET` | 2 | SHARED | degrades to 0 |
| 4 | 4 | `UI_CVAR_VARIABLEVALUE` | 1 | SHARED | degrades to 0 |
| 5 | 5 | `UI_CVAR_VARIABLESTRINGBUFFER` | 3 | SHARED | degrades to 0, empty string at arg 1 |
| 6 | 6 | `UI_CVAR_SETVALUE` | 2 | SHARED | degrades to 0 |
| 7 | 7 | `UI_CVAR_RESET` | 1 | SHARED | degrades to 0 |
| 8 | 8 | `UI_CVAR_CREATE` | 3 | SHARED | degrades to 0 |
| 9 | 9 | `UI_CVAR_INFOSTRINGBUFFER` | 3 | SHARED | degrades to 0, empty string at arg 1 |
| 10 | 10 | `UI_ARGC` | 0 | SHARED | degrades to 0 |
| 11 | 11 | `UI_ARGV` | 3 | SHARED | degrades to 0, empty string at arg 1 |
| 12 | 12 | `UI_CMD_EXECUTETEXT` | 2 | SHARED | degrades to 0 |
| 13 | 13 | `UI_FS_FOPENFILE` | 3 | SHARED | degrades to -1 |
| 14 | 14 | `UI_FS_READ` | 3 | SHARED | degrades to 0 |
| 15 | 15 | `UI_FS_WRITE` | 3 | SHARED | degrades to 0 |
| 16 | 16 | `UI_FS_FCLOSEFILE` | 1 | SHARED | degrades to 0 |
| 17 | 17 | `UI_FS_GETFILELIST` | 4 | SHARED | degrades to 0 |
| 18 | 18 | `UI_R_REGISTERMODEL` | 1 | HOST | degrades to 0 |
| 19 | 19 | `UI_R_REGISTERSKIN` | 1 | HOST | degrades to 0 |
| 20 | 20 | `UI_R_REGISTERSHADERNOMIP` | 1 | HOST | degrades to 0 |
| 21 | 21 | `UI_R_CLEARSCENE` | 0 | HOST | degrades to 0 |
| 22 | 22 | `UI_R_ADDREFENTITYTOSCENE` | 1 | HOST | degrades to 0 |
| 23 | 23 | `UI_R_ADDPOLYTOSCENE` | 3 | HOST | degrades to 0 |
| 24 | 24 | `UI_R_ADDLIGHTTOSCENE` | 5 | HOST | degrades to 0 |
| 25 | 25 | `UI_R_RENDERSCENE` | 1 | HOST | degrades to 0 |
| 26 | 26 | `UI_R_SETCOLOR` | 1 | HOST | degrades to 0 |
| 27 | 27 | `UI_R_DRAWSTRETCHPIC` | 9 | HOST | degrades to 0 |
| 28 | 28 | `UI_UPDATESCREEN` | 0 | HOST | degrades to 0 |
| 29 | 29 | `UI_CM_LERPTAG` | 6 | HOST | degrades to 0, zeroed 48 bytes at arg 0 |
| 30 | 30 | `UI_CM_LOADMODEL` | 1 | HOST | degrades to 0 |
| 31 | 31 | `UI_S_REGISTERSOUND` | 2 | HOST | degrades to 0 |
| 32 | 32 | `UI_S_STARTLOCALSOUND` | 2 | HOST | degrades to 0 |
| 33 | 33 | `UI_KEY_KEYNUMTOSTRINGBUF` | 3 | HOST | degrades to 0, empty string at arg 1 |
| 34 | 34 | `UI_KEY_GETBINDINGBUF` | 3 | HOST | degrades to 0, empty string at arg 1 |
| 35 | 35 | `UI_KEY_SETBINDING` | 2 | HOST | degrades to 0 |
| 36 | 36 | `UI_KEY_ISDOWN` | 1 | HOST | degrades to 0 |
| 37 | 37 | `UI_KEY_GETOVERSTRIKEMODE` | 0 | HOST | degrades to 0 |
| 38 | 38 | `UI_KEY_SETOVERSTRIKEMODE` | 1 | HOST | degrades to 0 |
| 39 | 39 | `UI_KEY_CLEARSTATES` | 0 | HOST | degrades to 0 |
| 40 | 40 | `UI_KEY_GETCATCHER` | 0 | HOST | degrades to 0 |
| 41 | 41 | `UI_KEY_SETCATCHER` | 1 | HOST | degrades to 0 |
| 42 | 42 | `UI_GETCLIPBOARDDATA` | 2 | HOST | degrades to 0, empty string at arg 0 |
| 43 | 43 | `UI_GETGLCONFIG` | 1 | HOST | critical: menu scaling divides by the video size |
| 44 | 44 | `UI_GETCLIENTSTATE` | 1 | HOST | degrades to 0 |
| 45 | 45 | `UI_GETCONFIGSTRING` | 3 | HOST | degrades to 0, empty string at arg 1 |
| 46 | 50 | `UI_LAN_GETPINGQUEUECOUNT` | 0 | HOST | degrades to 0 |
| 47 | 51 | `UI_LAN_CLEARPING` | 1 | HOST | degrades to 0 |
| 48 | 52 | `UI_LAN_GETPING` | 4 | HOST | degrades to 0, empty string at arg 1 |
| 49 | 53 | `UI_LAN_GETPINGINFO` | 3 | HOST | degrades to 0, empty string at arg 1 |
| 50 | 54 | `UI_CVAR_REGISTER` | 4 | SHARED | degrades to 0 |
| 51 | 55 | `UI_CVAR_UPDATE` | 1 | SHARED | degrades to 0 |
| 52 | 56 | `UI_MEMORY_REMAINING` | 0 | STUB | returns 33554432: the JVM has no hunk; a constant large value keeps low-memory paths off |
| 53 | 57 | `UI_GET_CDKEY` | 2 | HOST | degrades to 0, empty string at arg 0 |
| 54 | 58 | `UI_SET_CDKEY` | 1 | HOST | degrades to 0 |
| 55 | - | `UI_R_REGISTERFONT` | 3 | HOST | degrades to 0 |
| 56 | - | `UI_R_MODELBOUNDS` | 3 | HOST | degrades to 0 |
| 57 | - | `UI_PC_ADD_GLOBAL_DEFINE` | 1 | HOST | degrades to 0 |
| 58 | - | `UI_PC_LOAD_SOURCE` | 1 | HOST | degrades to 0 |
| 59 | - | `UI_PC_FREE_SOURCE` | 1 | HOST | degrades to 0 |
| 60 | - | `UI_PC_READ_TOKEN` | 2 | HOST | degrades to 0 |
| 61 | - | `UI_PC_SOURCE_FILE_AND_LINE` | 3 | HOST | degrades to 0, empty string at arg 1 |
| 62 | - | `UI_S_STOPBACKGROUNDTRACK` | 0 | HOST | degrades to 0 |
| 63 | - | `UI_S_STARTBACKGROUNDTRACK` | 2 | HOST | degrades to 0 |
| 64 | - | `UI_REAL_TIME` | 1 | SHARED | degrades to 0 |
| 65 | - | `UI_LAN_GETSERVERCOUNT` | 1 | HOST | degrades to 0 |
| 66 | - | `UI_LAN_GETSERVERADDRESSSTRING` | 4 | HOST | degrades to 0, empty string at arg 2 |
| 67 | - | `UI_LAN_GETSERVERINFO` | 4 | HOST | degrades to 0, empty string at arg 2 |
| 68 | - | `UI_LAN_MARKSERVERVISIBLE` | 3 | HOST | degrades to 0 |
| 69 | - | `UI_LAN_UPDATEVISIBLEPINGS` | 1 | HOST | degrades to 0 |
| 70 | - | `UI_LAN_RESETPINGS` | 1 | HOST | degrades to 0 |
| 71 | - | `UI_LAN_LOADCACHEDSERVERS` | 0 | HOST | degrades to 0 |
| 72 | - | `UI_LAN_SAVECACHEDSERVERS` | 0 | HOST | degrades to 0 |
| 73 | - | `UI_LAN_ADDSERVER` | 3 | HOST | degrades to -1 |
| 74 | - | `UI_LAN_REMOVESERVER` | 2 | HOST | degrades to 0 |
| 75 | - | `UI_CIN_PLAYCINEMATIC` | 6 | HOST | degrades to -1 |
| 76 | - | `UI_CIN_STOPCINEMATIC` | 1 | HOST | degrades to 0 |
| 77 | - | `UI_CIN_RUNCINEMATIC` | 1 | HOST | degrades to 0 |
| 78 | - | `UI_CIN_DRAWCINEMATIC` | 1 | HOST | degrades to 0 |
| 79 | - | `UI_CIN_SETEXTENTS` | 5 | HOST | degrades to 0 |
| 80 | - | `UI_R_REMAP_SHADER` | 3 | HOST | degrades to 0 |
| 81 | - | `UI_VERIFY_CDKEY` | 2 | STUB | returns 1: CD keys are never validated, like ioquake3 builds without key checks |
| 82 | - | `UI_LAN_SERVERSTATUS` | 3 | HOST | degrades to 0, empty string at arg 1 |
| 83 | - | `UI_LAN_GETSERVERPING` | 2 | HOST | degrades to 0 |
| 84 | - | `UI_LAN_SERVERISVISIBLE` | 2 | HOST | degrades to 0 |
| 85 | - | `UI_LAN_COMPARESERVERS` | 5 | HOST | degrades to 0 |
| 86 | - | `UI_FS_SEEK` | 3 | SHARED | degrades to -1 |
| 87 | - | `UI_SET_PBCLSTATUS` | 1 | STUB | returns 0: PunkBuster is not part of CraftQ3; the requested state is ignored |
| 100 | 100 | `UI_MEMSET` | 3 | SHARED | degrades to 0 |
| 101 | 101 | `UI_MEMCPY` | 3 | SHARED | degrades to 0 |
| 102 | 102 | `UI_STRNCPY` | 3 | SHARED | degrades to 0 |
| 103 | 103 | `UI_SIN` | 1 | SHARED | degrades to 0 |
| 104 | 104 | `UI_COS` | 1 | SHARED | degrades to 0 |
| 105 | 105 | `UI_ATAN2` | 2 | SHARED | degrades to 0 |
| 106 | 106 | `UI_SQRT` | 1 | SHARED | degrades to 0 |
| 107 | - | `UI_FLOOR` | 1 | SHARED | degrades to 0 |
| 108 | - | `UI_CEIL` | 1 | SHARED | degrades to 0 |

## Retail-only imports

| Module | retail # | Import | Args | Policy | Detail |
| --- | ---: | --- | ---: | --- | --- |
| qagame | 300 | `RETAIL_BOTLIB_AAS_ENTITY_VISIBLE` | ? | STUB | returns 0: obsolete pre-1.27 visibility query that no shipped build calls; 0 means not visible |
| qagame | 301 | `RETAIL_BOTLIB_AAS_IN_FIELD_OF_VISION` | ? | STUB | returns 0: obsolete pre-1.27 visibility query that no shipped build calls; 0 means not visible |
| qagame | 302 | `RETAIL_BOTLIB_AAS_VISIBLE_CLIENTS` | ? | STUB | returns 0: obsolete pre-1.27 visibility query that no shipped build calls; 0 means not visible |
| qagame | 402 | `RETAIL_BOTLIB_EA_USE_ITEM` | 2 | STUB | returns 0: pre-1.27 inventory action; Quake III has no usable inventory, so it is ignored |
| qagame | 403 | `RETAIL_BOTLIB_EA_DROP_ITEM` | 2 | STUB | returns 0: pre-1.27 inventory action; Quake III has no usable inventory, so it is ignored |
| qagame | 404 | `RETAIL_BOTLIB_EA_USE_INV` | 2 | STUB | returns 0: pre-1.27 inventory action; Quake III has no usable inventory, so it is ignored |
| qagame | 405 | `RETAIL_BOTLIB_EA_DROP_INV` | 2 | STUB | returns 0: pre-1.27 inventory action; Quake III has no usable inventory, so it is ignored |
| ui | 46 | `RETAIL_UI_LAN_GETLOCALSERVERCOUNT` | 0 | HOST | degrades to 0 |
| ui | 47 | `RETAIL_UI_LAN_GETLOCALSERVERADDRESSSTRING` | 3 | HOST | degrades to 0, empty string at arg 1 |
| ui | 48 | `RETAIL_UI_LAN_GETGLOBALSERVERCOUNT` | 0 | HOST | degrades to 0 |
| ui | 49 | `RETAIL_UI_LAN_GETGLOBALSERVERADDRESSSTRING` | 3 | HOST | degrades to 0, empty string at arg 1 |
