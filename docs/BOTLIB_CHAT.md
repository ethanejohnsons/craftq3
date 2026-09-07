# Bot chat scripts and state

`craftq3-botlib` contains an original Java reader and bounded state service for the
original bot chat assets. `ChatLibrary` loads `rnd.c`, `syn.c`, `match.c`, `rchat.c`
and per-character chat files through `ScriptSources`; includes such as the original
`teamplay.h` remain virtual filesystem reads. No commercial text or native routines
are bundled.

The first milestone supports initial chat templates, eight optional variables,
nested random references, context masks and weighted synonym groups, recent-message
selection, console queues, and consuming the current chat message. Match captures,
literal alternatives, reply predicates and priorities are retained as immutable
metadata. Sequential match execution, variable extraction and priority-based reply selection are
implemented. The service does not invent team orders or other bot decisions.

## API and ownership

`BotChat(ScriptSources, DoubleSupplier, RandomGenerator [, Limits])` borrows the
source service. The `VirtualFileSystem` constructor owns an internal source service
but does not close the filesystem. `setup()` loads shared data transactionally;
`close()` releases states and owned sources. Every parser source handle is freed on
success or failure.

- `allocate()` returns a positive handle, or zero when full; `free(handle)` returns
  false for a stale/invalid handle. Other handle operations reject invalid handles.
  Freed handles are never reissued.
- `load(handle, path, chatName)` returns whether the named definition exists; failure
  retains the old definition. The native ABI maps true to its zero success code.
- `initial(handle, type, context, variables)` preserves the previous message when
  the type is missing. `initialCount` returns the available message count.
- `length` counts the current internal characters, including protection markers.
  `message` peeks at the final text; `takeMessage` removes protection markers and
  consumes the message. Native `BotGetChatMessage` consumes too.
- `queue(handle, type, text)` returns false when the global pool is full. `next`
  peeks at the oldest `ConsoleMessage(id, time, type, text)`; `remove` removes by
  message ID. The native next-message return value is the message ID, not boolean 1.
- `setName`, `setGender`, `name`, `client`, and `gender` retain delivery metadata.
  The host delivers outgoing text. Modern native `EnterChat` uses the stored
  client and unquoted `say`, `say_team`, or `tell <recipient>` arguments, then
  consumes the message. Retail client routing is an ABI concern in the host.
- `stringContains` provides ASCII case folding and index/-1 results, including
  index zero for an empty needle. `unifyWhiteSpaces` implements the observed native
  byte-string normalization, including spans skipped after whitespace removal.

Default limits are 64 states, 1,024 globally queued messages, 8 MiB of retained
per-state template characters, 32 random expansion levels and 4,096 expansion
parts. Each parsed source is capped at 100,000 consumed tokens and 4 MiB token text,
in addition to the preprocessor's limits. Queued/name/variable inputs are capped
at 255 characters; overlong constructed output fails explicitly. Clocks must be
finite and float-representable. Injected random values must lie in `[0,1)`.

Chat assets use byte strings, represented as Java characters by `ScriptSources`.
The caller supplies deterministic time/RNG; Java's seeded RNG is not presented as
an implementation of any platform C `rand()` sequence.

## Verification and current fidelity limit

Eight authored tests cover nested expansion, optional variables, consuming reads,
protection markers, 20-second recent selection, queue timestamps and capacity,
stale handles, parser metadata/immutability, duplicate resolution, transactional
loads, missing references, recursion bounds, and text helpers.

The optional read-only `scripts/AuditChats.java` audit loaded all 32 original
character chat files: **2,744 types and 10,618 messages**, plus 243 random lists,
175 synonym groups, 138 match rules and 456 reply rules. It expanded every message
with no leaked source handles. Duplicate random-list names use the first definition;
duplicate chat-type blocks use the last, both established by authored native probes.

An isolated oracle links unchanged ioquake3 `be_ai_chat`, `l_script`, `l_precomp`
and `q_shared` objects from commit
`588393618dbc82e7207c21c6ddecca229944a03a`, compiled with floating-point contraction
disabled. Our own host callbacks read the user PK3 directly via libarchive; only
unlinked temporary streams are used, and no installation mounts are changed.
The oracle overrides `rand()` with a fixed probe value and calls public chat APIs.

Five fixed native random samples (`0`, `8192`, `16384`, `24576`, `32766`, divided
by `32767.0f` in Java) now match **all 53,090 expanded messages, pre-consumption
lengths and type counts exactly**. The former 1,102 midpoint mismatches are resolved.
The audit exercises source-order synonym groups, ASCII case folding, the original
token-walk spacing rules, protection for existing selected phrases, and breadth-first
random expansion with substitutions after each pass. Native C RNG endpoint/sequence
identity remains outside the supplied Java RNG contract.

The implementation is bounded, but universal synonym parity is not claimed. A wider
100,000-case authored direct `StringReplaceWords` differential leaves **six differences**
for artificial single-character self-overlap aliases. For example, replacing `a` with
`a` in `a  A,!a` retains the uppercase `A` natively while the current helper lowercases
it. These cases remain recorded in the audit and are not counted as passes. None occurs
in the five complete original initial-chat comparisons above. See
[BOTLIB_CHAT_SYNONYMS.md](BOTLIB_CHAT_SYNONYMS.md) for the tested rules, expansion limit,
and reproduction details.

Run the audit with the built core/assets/botlib class directories on the classpath:

```sh
java -cp "$CHAT_AUDIT_CP" scripts/AuditChats.java run/craftq3/games
java -cp "$CHAT_AUDIT_CP" scripts/AuditChats.java run/craftq3/games \
  .tools/chat-oracle/probe run/craftq3/games/baseq3/pak0.pk3 0
java -cp "$CHAT_AUDIT_CP" scripts/AuditChats.java run/craftq3/games \
  .tools/chat-oracle/probe run/craftq3/games/baseq3/pak0.pk3 16384
```

The ignored oracle executable, its authored protocol harness, upstream source and
commercial assets are development inputs and are not distributed.

## Match records and capture queries

`BotChat.findMatch(text, context, buffer, offset)` uses `ChatMatcher` over the original immutable
match rules. Matching follows source order and uses ASCII case folding. Literal-only patterns must
consume the whole text. A capture searches forward for the first matching literal alternative in
source alternative order; there is no backtracking if a subsequent piece fails. Empty literals keep
a pending capture open. Repeated variable indices retain the final capture. Patterns with adjacent
variables, including an optional empty separator, are rejected when parsed.

The published `bot_match_t` is 328 bytes: a 256-byte NUL-terminated byte string, type and subtype
integers, then eight eight-byte capture records. Each capture has a one-byte offset, three padding
bytes and a four-byte length. Offset 255 means absent. Finding a match always replaces the text and
clears its unused tail. Each attempted eligible rule resets capture offsets only; failed attempts
can retain partial lengths, and failure retains the prior type/subtype. Padding and unused lengths
remain untouched. The caller must therefore supply the existing guest record and write it back on
both success and failure. Buffer position/order are preserved, and range/budget failures publish
no partial changes.

`ChatMatcher.variable` bounds the capture and output capacity, returning the original captured
bytes. Inputs truncate at 255 bytes or the first NUL. The default work limit is one million character
comparisons/part visits. Offsets 128–254 are interpreted as bounded unsigned byte indices: the native
reference's signed-char implementation can assert or read outside its record there. The Java query
supports those positions safely; the native differential corpus limits capture positions to 127.
Malformed capture ranges are rejected rather than used as pointers.

Six authored matcher tests and a parser regression cover anchoring, alternative precedence, failed
capture writes, repeated/empty captures, capacity truncation, high offsets, record ownership and
work budgets. `ChatMatchOracle.c` links unchanged native chat, script/preprocessor and `q_shared`
objects, including the real bounded-string helper so trailing record bytes are compared correctly.
`AuditChatMatches.java` generated **6,713 queries** from all 138 original rules, with **3,716 matches
and 29,728 capture comparisons**. Match booleans and the complete 328-byte output records agree,
including unchanged padding and partial writes. This verifies matching independently of reply
selection; the separate reply corpus below checks that integration.


## Reply selection

`reply(handle, text, messageContext, variableContext, variables)` evaluates original reply rules
in reverse source order. Higher priorities replace earlier candidates; equal priorities keep the
later source rule. Required predicates must match, forbidden predicates must not match, and at least
one ordinary predicate must match. Required/forbidden predicates alone cannot select a reply.
Gender keys use 0 for `it`, 1 for `female` and 2 for `male`; the name key searches a case-insensitive
substring of the incoming text. Word predicates use the observed space/dot/comma/exclamation
boundaries and preserve the scanner's unusual behavior around leading and repeated delimiters.
Incoming text is neither whitespace-normalized nor synonym-normalized before matching.

Pattern keys reuse the bounded matcher. Successful captures merge in reverse key order and across
successively higher-priority selected rules. Failed conditions do not replace the selected capture
state. A non-null supplied variable overrides its capture, including an explicitly empty string.
Both captured and supplied values undergo canonical synonym replacement in `variableContext`;
constructed output undergoes weighted replacement in `messageContext`. Only the final chosen
message is expanded. A failed match preserves the current message. Signed priorities and the
unsupported unquoted `botnames` predicate are rejected by the parser; a random-list reference
named `botnames` inside a message remains valid.

Reply-variable canonicalization has its own observed scanner. It visits character positions before
source-order synonym groups, accepts a left boundary at the start or after a byte at most 32, and
accepts a right boundary at the end or before space/dot/comma/exclamation. Matching a canonical
phrase preserves its case and protects its own aliases; replacing an alias stops the remaining
groups at that position. Scanning then advances one character, including through inserted text.
Four focused tests cover these boundaries, overlapping groups, canonical protection and bounded
self-growing aliases. This differs from the ordinary weighted message synonym helper.

Recent reply history is shared by every bot using the library and lasts 20 seconds. The observed
native chooser counts eligible messages, then indexes the **unfiltered reversed message list**.
That can select a recent message again. When the eligible count is zero, index zero still selects
the last source message. Each improving candidate consumes a selection draw, including empty
message lists; only the final chosen message updates history. These details differ from initial
chat's selection policy. Float clock arithmetic retains the observed expiry boundary.

Eight focused reply tests cover priority/ties, predicate combinations, gender and name matching,
word boundaries, shared recent history, capture ownership, caller overrides, variable contexts,
parser rejection and preserving the current message. Reply selection shares a one-million-unit
comparison/part budget across rules, keys and patterns. Existing expansion limits remain in force.

`ChatReplyOracle.c` calls the unchanged native public chat API with controlled names, genders,
clocks and random samples. Its input storage is zero-padded: the native word scanner can otherwise
consult stale bytes past the first NUL when a message ends in a delimiter. Java deliberately stays
inside the supplied text. Original C signed-offset pointer behavior is likewise not reproduced.
The corpus limits capture positions to the defined native range.

`AuditChatReplies.java` generated **28,344 requests** from all **456 original reply rules**,
producing **26,718 replies and 40,454 random draws**. Results, internal lengths, consumed messages
and draw counts match exactly. The three first-draw samples are 0, 16,384 and 29,490; later draws
in each request are zero. A separate varying seeded stream matches **9,448 requests, 8,906 replies
and 13,277 draws**. Recursive random lists stop after the native ten-pass cutoff and retain encoded
unresolved references, as described in the synonym document.

A third varying-stream comparison exercises seven message contexts, four variable contexts,
advancing float clocks, alternating bot states, changing genders and supplied-variable overrides.
All **9,448 requests, 8,892 replies and 557,460 random draws** agree, including internal lengths
and consumed text. No original reply text is bundled in the tests or distributable.

```sh
java -cp "$CHAT_AUDIT_CP" scripts/AuditChatReplies.java
java -cp "$CHAT_AUDIT_CP" scripts/AuditChatReplies.java sequence
java -cp "$CHAT_AUDIT_CP" scripts/AuditChatReplies.java contexts
```


## Byte-string normalization

`BotChat.unifyWhiteSpaces` now reproduces the observed public `UnifyWhiteSpaces` behavior.
It removes leading whitespace and replaces processed internal runs with a space, but the original
scan skips following spans after removing a run. Consequently, `"  a  b"` becomes `"a  b"`, and
`" a\t\t b!c "` becomes `"a b!c"`. Skipped trailing bytes can remain unchanged too. Java computes
these spans in one forward pass without the native helper's repeated in-place moves.

The full byte alphabet was checked: whitespace includes byte values 1–38, 42, 59, 60, 62, 64,
92, 94, 96 and 123–255. NUL terminates the input. Characters outside the byte range are rejected
before publishing a result, and the one-million-character input bound remains. The unit tests
cover ordinary text, skipped spans, preserved trailing text, high bytes and input bounds.
`AuditChatText.java` compares all 255 nonzero bytes and 100,000 seeded strings with unchanged
native code: **100,255 exact results**. This replaces the earlier documented canonical
approximation; it does not change the separate reply word-scanning contract.

```sh
java -cp "$CHAT_AUDIT_CP" scripts/AuditChatText.java
```
