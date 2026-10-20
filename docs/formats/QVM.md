# QVM bytecode and the interpreter

The game modules (`vm/qagame.qvm`, `vm/cgame.qvm`, `vm/ui.qvm`) are QVM bytecode produced by
`q3lcc`/`q3asm`. CraftQ3 reads them from the user's installation and runs them in a Java
interpreter; native modules (`.so`, `.dll`) are never loaded. Imports (syscalls), their policies
and the struct layouts of the two ABI profiles are listed in [SYSCALLS.md](../SYSCALLS.md), which
is generated from the `engine/vm-abi` tables.

Code: `engine/vm`
[`QvmImage`](../../engine/vm/src/main/java/dev/bluevista/craftq3/engine/vm/QvmImage.java) (parse,
validate, decode),
[`QvmInstance`](../../engine/vm/src/main/java/dev/bluevista/craftq3/engine/vm/QvmInstance.java)
(interpreter),
[`QvmMemory`](../../engine/vm/src/main/java/dev/bluevista/craftq3/engine/vm/QvmMemory.java),
[`Opcode`](../../engine/vm/src/main/java/dev/bluevista/craftq3/engine/vm/Opcode.java),
[`Syscalls`](../../engine/vm/src/main/java/dev/bluevista/craftq3/engine/vm/Syscalls.java),
[`QvmException`](../../engine/vm/src/main/java/dev/bluevista/craftq3/engine/vm/QvmException.java),
[`TraceRecorder`](../../engine/vm/src/main/java/dev/bluevista/craftq3/engine/vm/TraceRecorder.java);
`engine/vm-abi`
[`AbiDetector`](../../engine/vm-abi/src/main/java/dev/bluevista/craftq3/engine/vmabi/AbiDetector.java),
[`AbiProfile`](../../engine/vm-abi/src/main/java/dev/bluevista/craftq3/engine/vmabi/AbiProfile.java),
[`ImportTable`](../../engine/vm-abi/src/main/java/dev/bluevista/craftq3/engine/vmabi/ImportTable.java),
[`Policy`](../../engine/vm-abi/src/main/java/dev/bluevista/craftq3/engine/vmabi/Policy.java).
References: id Software's public
[qfiles.h](https://github.com/id-Software/Quake-III-Arena/blob/master/code/qcommon/qfiles.h) and
ioquake3's [qfiles.h](https://github.com/ioquake/ioq3/blob/main/code/qcommon/qfiles.h) (version 2
header).

## File layout

All header fields are little-endian 32-bit words.

| Offset | Field |
| ---: | --- |
| 0 | magic: `0x12721444` (version 1) or `0x12721445` (version 2) |
| 4 | instruction count |
| 8 | code offset |
| 12 | code length (bytes) |
| 16 | data offset |
| 20 | data length (multiple of 4) |
| 24 | literal length |
| 28 | BSS length |
| 32 | version 2 only: jump-table length in bytes (multiple of 4) |

The header is 32 bytes (version 1) or 36 (version 2). Section offsets are word-aligned and may not
overlap the header or each other; literals follow the data; the version 2 jump table follows the
literals and lists instruction indices (metadata, not guest data).

Each instruction is one opcode byte (0 to 59) followed by an immediate: 4 bytes for `ENTER`,
`LEAVE`, `CONST`, `LOCAL`, the conditional branches and `BLOCK_COPY`, 1 byte for `ARG`, none
otherwise. Code addresses count instructions, not bytes. Exactly the declared count is decoded;
up to three zero padding bytes may follow.

## Validation

`QvmImage.parse` checks every range in wide arithmetic and rejects, with `QvmFormatException`:
files over 64 MiB, more than 2,000,000 instructions, a data segment over 64 MiB, unknown opcodes,
truncated immediates, invalid frame sizes (over 1 MiB), argument slots, local offsets of overflow
size (negative ones occur in shipped code and are allowed), block-copy lengths, branch targets
outside the code or crossing a procedure boundary, code that does not begin with `ENTER`, and
version 2 jump-table entries outside the code. The decoded image is immutable and shared by all
instances of the module.

## Memory and stack

The data segment holds data, literals and BSS, rounded up to a power of two. Initialized bytes are
copied as is (all accesses are explicitly little-endian); BSS and padding start at zero. Every
guest load, store and block copy masks its start address into the segment (as Quake III does) and
then faults if the access would run past the end; host accessors check the full range.

The program stack starts at the top of the segment and grows down. `q3asm` reserves 64 KiB
(`PROGRAM_STACK_SIZE`) at the end of BSS; an image is accepted when its BSS contains that
reservation or all of data, literals and BSS lie below the stack. The hosts parse with
`StackPolicy.NATIVE_EXTENT`: like the original interpreter, which has no stack-bottom check, the
stack may use everything down to the end of the guest's globals (the reservation plus the
power-of-two slack), never less than 64 KiB. Q3Rally needs more than the reservation; see
[DIVERGENCES.md](../DIVERGENCES.md).

## Execution

- `vmMain(command, args...)` takes at most 12 arguments. It reserves a 60-byte frame below the
  current program stack: return sentinel at 0, the syscall slot at 4, the command at 8 and the
  arguments from 12.
- `CALL` to a non-negative address must land on an `ENTER`; `LEAVE` checks the frame size, that
  exactly one operand is returned and that the saved return address was not modified. Branches and
  `JUMP` stay inside the current procedure; in version 2 modules an indirect `JUMP` must target a
  jump-table entry (a `CONST`/`JUMP` pair reached only by falling through counts as direct).
- `CALL` to a negative address `-1 - n` is syscall `n`. The host receives 16 argument words read
  from the outgoing argument area (words past the end of memory read as 0) and may re-enter
  `vmMain` synchronously; nested entries share the outer budgets.
- Integer division or remainder by zero faults; float division follows IEEE. Float-to-int
  conversion truncates, and NaN, infinities and out-of-range values give `Integer.MIN_VALUE`.
  Shift counts use the low five bits. `UNDEF` and `BREAK` fault when executed; `IGNORE` is a no-op.
  NaN payloads are not preserved.

Limits apply per outermost `vmMain`; instructions are charged per straight-line run, block copies
one unit per 4 bytes:

| Limit | Default | qagame host | cgame/ui host |
| --- | ---: | ---: | ---: |
| Instructions | 50,000,000 | 2,000,000,000 | 4,000,000,000 |
| Syscalls | 200,000 | 20,000,000 | 4,000,000 |
| Wall time | 5 s | none | 120 s |
| Operand stack slots | 4,096 | 65,536 | 4,096 |
| Call depth | 1,024 | 4,096 | 1,024 |
| Nested `vmMain` | 16 | 32 | 16 |

A fault (`QvmException` with a reason, module, instruction and syscall number) leaves the instance
faulted until `reset()`, which restores the initial data. Hosts turn a fault or `trap_Error` into a
level drop (ERR_DROP); see [ARCHITECTURE.md](../../ARCHITECTURE.md). An exception inside an import
handler returns the import's documented neutral value and is logged once; `vm_strictSyscalls 1`
makes it a fault instead. `TraceRecorder` keeps an opcode histogram and the most recent
instructions for diagnostics.

## ABI profiles

The 1999 retail release (`pak0.pk3`) and 1.32 use different import numbering and struct layouts
(for example `entityState_t` 204 vs 208 bytes, `playerState_t` 444 vs 468). `AbiDetector` picks the
profile from the verified retail SHA-256 hashes, otherwise from the set of trap numbers the bytecode
calls through constant addresses, otherwise 1.32; the `vm_abi` cvar overrides it. The full table
of sizes and imports is in [SYSCALLS.md](../SYSCALLS.md).

Independent implementation; see [LICENSE-NOTES.md](../../LICENSE-NOTES.md). Tests:
`QvmImageTest`, `QvmInstanceTest`, `OpcodeConformanceTest`, `QvmMemoryTest`, `QvmSecurityTest`
(synthetic bytecode), `AbiDetectorTest`, `ImportTableTest`, `SyscallDocsTest`, and the
`@RealData` `RealQvmCorpusTest`, which parses every QVM build of an installation.
