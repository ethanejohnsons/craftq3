# Botlib script sources

`dev.bluevista.craftq3.botlib.script.ScriptSources` supplies the virtual source/token operations
needed by the original `PC_*` ABI. It borrows a `VirtualFileSystem` and never resolves host paths.
The public operations are `addGlobalDefine`, `removeGlobalDefine`, `clearGlobalDefines`, `load`,
`read`, `location`, `free`, and `close`. `ScriptLexer.tokenize` also exposes standalone lexical
analysis. `ScriptLimits` permits explicit budgets.

`load` preprocesses a source transactionally and returns a positive handle. It throws a checked
`ScriptException` (an `IOException`) with virtual filename, physical line, and column for parse or
source errors. No handle is published on failure. `read` returns an optional immutable `ScriptToken`;
empty means EOF. Invalid/stale reads and location requests fail clearly; `free` returns false for an
invalid handle. Freeing a source releases its retained tokens. New loads can use the released
capacity, but old handle numbers never become valid again. Closing the service releases its data
without closing the borrowed filesystem. Global defines are copied into each load's context;
subsequent define changes do not alter already loaded streams.

## Tokens and preprocessing

The native `pc_token_t` layout is 1,040 bytes: four 32-bit fields (`type`, `subtype`, `intvalue`,
`floatvalue`) followed by a 1,024-byte string at offset 16. Token types are string 1, literal 2,
number 3, name 4, and punctuation 5. The lexer preserves Q3 punctuation identifiers and number
subtype bits. String text is unquoted while its subtype retains the length including quotes;
literal text retains single quotes and its subtype is the character's byte value. Numeric `U`/`L`
suffixes affect subtype but are absent from token text. Leading-zero number subtype conventions
match Q3, including its octal tag on some `0.x` floats. Fixed-point decimal tokens preserve the
original per-digit float rounding: for example, `0.45` exposes `0.45000002f` through the PC numeric
field rather than the nearest single conversion of the entire decimal string.

Supported processing includes object/function macros, nested arguments and alias rescanning,
stringification, token pasting, empty arguments, `#define`, `#undef`, `#if`, `#ifdef`, `#ifndef`,
`#elif`, `#else`, `#endif`, `#include`, `#error`, `defined`, `__LINE__`, and `__FILE__`. Line splices
are removed before lexical analysis while retaining physical source positions. Adjacent strings
are combined after expansion. Per-token suppression sets terminate direct/mutual macro recursion.
Include guards work even when headers include themselves; unguarded recursion reaches explicit
include budgets. Quoted includes try the including file's virtual directory, then the exact virtual
path, then configured roots. The default extra root is `botfiles`, supporting original character
scripts that include shared headers from their parent directory. Angled includes omit the local
directory attempt. Absolute paths, traversal components, drive prefixes, and oversized paths fail.

Q3 `$evalint(...)`, `$evalfloat(...)`, `#eval`, and `#evalfloat` support arithmetic, comparisons,
logical expressions, parentheses and conditionals. Integer evaluation uses each token's integer
field, including truncation of float operands: the original `W_LIGHTNING * 0.1` integer expression
therefore evaluates to zero. Float evaluation retains a float numeric field but formats the token
text to two decimal places, as Q3 does. Negative evaluated values emit a separate minus punctuation.
Expressions use explicit 32-bit integer arithmetic. Undefined expression names are errors;
`defined` is the way to test optional names.

## Bounds and deliberate limits

Defaults accept at most 1 MiB per file, 8 MiB of source reads per load, 250,000 raw/output tokens,
1,000,000 expansion work steps, 64 nesting levels, 128 includes, 4,096 macros, 64 open sources, and
1,023 characters per token. All retained source streams together are capped at 250,000 tokens.
Global define text is capped at 1 MiB in total and 4,096 names. Size/count failures are transactional.
The existing VFS also imposes its own archive/read bounds. The source service retains processed
tokens, not an unbounded file-content cache.

Variadic macros and directives such as `#line` and `#pragma` are explicitly rejected. Date/time
builtins and custom punctuation tables are not implemented. Multi-character literals and NUL
strings are rejected rather than passed through a truncated native string field. Scientific
notation and binary numbers are accepted lexical extensions. Logical and ternary branches are lazy,
so unevaluated division by zero is harmless; this is a deliberate difference from eager behavior
in some upstream preprocessing paths. Preprocessing errors surface during `load`, whereas the
native source service can defer them until token reads. This service supplies script syntax and
tokens; it does not implement bot goals, chat decisions, weapon selection, or genetic operations.

## Verification and provenance

Fifteen original authored tests cover token ABI fields, malformed input and physical positions,
macro rescanning, stringification/pasting and empty parameters, expression precedence/precision,
the original integer-evaluation truncation behavior, virtual includes and guards, line splices,
adjacent strings, global/source lifetimes, stale handles, and bounded recursive/retained storage.

The local original pak0 audit passes all **141 standalone bot script/header entry files**, producing
**209,501 tokens**. The remaining **two framework templates** require definitions supplied by their
callers; they are covered through **32 item-weight and 32 weapon-weight character files**. No source
handles remain open after the audit. Reproduce after compiling the module:

```sh
java -cp craftq3-core/build/classes/java/main:craftq3-assets/build/classes/java/main:craftq3-botlib/build/classes/java/main scripts/AuditBotScripts.java run/craftq3/games
```

ABI constants and selected behavior were checked against unchanged upstream ioquake3 headers and
the script/preprocessor reference at commit `588393618dbc82e7207c21c6ddecca229944a03a`, plus the
user's read-only bot scripts. Java uses an original token-queue macro expander and precedence parser;
no native routines were copied or mechanically translated. Native source, original data, and audit
outputs are not bundled.
