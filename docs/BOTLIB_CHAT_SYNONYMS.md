# Chat synonym and expansion behavior

`ChatSynonyms` is the package-local implementation used by `BotChat` for ordinary
canonical and weighted message substitution. Reply variables use their separately
verified normalizer. Synonym groups and entries remain immutable loader data; no
commercial synonym text is embedded in the implementation or tests.

Groups run in source order. Canonical mode selects the first entry without consuming
random values. Weighted mode consumes one float per eligible context group, including
groups that cannot match the text and groups whose total weight is zero. A zero
sample performs no substitutions for that group. Other samples choose an entry using
float32 weights. Each nonselected entry is replaced separately; occurrences of the
selected spelling retain their original capitalization.

Authored native probes establish ASCII case-insensitive matching and exactly four
word delimiters: space, period, comma and exclamation mark. The word cursor advances
before seeking its next delimiter. Consequently one space can skip an adjacent alias,
while two spaces can expose it. Existing selected phrases are found with their own
cursor and protected during each substitution; character protection follows insertions
and deletions. This prevents ordinary repeated expansion of a selected phrase containing
a shorter alias, such as an authored `red` / `red end` pair.

Random-list expansion proceeds one level at a time, from left to right. Each pass
substitutes synonyms across the whole intermediate message. A pass that expands a
random reference causes another pass, including one terminal pass when all references
have resolved; variables alone do not cause an additional pass. This preserves the
original random draw interleaving. The native limit is ten passes: unresolved references
are returned in the original `0x01 r name 0x01` encoding at that point. Stricter configured
depth limits still fail explicitly. Expanded text and internal placeholders remain
bounded, and a failed expansion preserves the previously published chat message.

Nine authored unit tests cover group ordering, casing, spacing, phrase overlap,
context/draw behavior, output budgets, whole-message expansion passes, reference cutoff,
and preservation after a stricter budget failure. All pass. The complete original
initial-chat audit passes at five samples (`0`, `8192`, `16384`, `24576`, `32766`):
**32 files, 2,744 types and 10,618 messages per sample; 53,090 exact comparisons**.
See `AuditChats.java` and the commands in [BOTLIB_CHAT.md](BOTLIB_CHAT.md).

There is a narrow remaining limitation. A wider authored direct-helper differential
with 100,000 strings matches 99,994; six cases involving artificial single-character
self-overlap aliases differ. For example, `a` → `a` applied to `a  A,!a` and `a b` → `a`
applied to `a   A B   a b` differ. These are explicit failures, not relaxed comparisons.
They do not occur in the complete original initial-chat comparisons above. No universal
chat equivalence claim is made.

## Independent native observation

`ChatSynonymOracle.c` is an authored observer linked against unchanged objects built
from the official [ioquake3 source](https://github.com/ioquake/ioq3/tree/588393618dbc82e7207c21c6ddecca229944a03a).
Only public services and exported helper inputs/outputs are observed. No original
routine bodies were copied or translated. Native code and its binary remain ignored
verification tools and are never loaded by the mod. Shared native objects were not
modified during these probes. The host opens the user's PK3 read-only, or uses an
authored fixture root, and zeroes its command buffers before each call.

The observer accepts `<PK3-or-fixture-root> <chat-path> <chat-name>` and line commands:
`seed N`, `weighted context text`, `replace context text`, `stats`,
`wordsallhex oldhex newhex texthex`, and `findhex wordhex texthex`. Hex commands preserve
leading whitespace. For the first remaining edge case, send:

```text
wordsallhex 61 61 612020412c2161
```

The native result is `WORDSHEX 612020412c2161`; the current Java helper yields
`612020612c2161`. The broader probe is intentionally reported as imperfect.

The fixture observer links `be_ai_chat.o`, `q_shared.o`, `l_script-unfused.o` and
`l_precomp-unfused.o` with floating-point contraction disabled and local libarchive
for PK3 reads. Author-created synthetic fixtures and detailed logs live under ignored
`.tools/synonym-oracle` and `run/chat-synonym-*.log`; no original pack contents are
committed, extracted into the repository, or modified.
