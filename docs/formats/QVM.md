# QVM bytecode and execution

`craftq3-vm` is an independently implemented Java 25 interpreter for Quake III
virtual machine modules. It loads bytecode supplied by the user's virtual
filesystem. It does not load native game libraries or include original game
code, SDK source, or PK3 contents.

## API

```java
QvmModule module = QvmReader.read("qagame", bytes);
QvmInterpreter vm = new QvmInterpreter(module, (memory, syscall, arguments) -> {
  // A host implements only the services and capabilities it grants this module.
  throw new UnsupportedOperationException("Unsupported syscall " + syscall);
});
int result = vm.invoke(command, argument0, argument1);
```

`QvmModule` owns immutable decoded instructions, section metadata, initialized
data, procedure ownership, and optional version 2 jump targets. Array accessors
return copies. `QvmMemory` owns a separate mutable little-endian byte region.
Host reads and writes check the complete range; addresses are offsets, never
Java references or host pointers. Offset zero is valid memory. Each syscall
host decides which of its parameters use zero to mean a null pointer.

`invoke(command, int... arguments)` enters instruction zero with at most twelve
additional arguments. A negative `CALL` destination identifies a syscall:
`-1` invokes service zero, `-2` invokes service one, and so on. The callback
receives the service number and a fresh fifteen-word argument snapshot that
excludes that number. Only the parameters declared by that service's ABI are
meaningful; unused slots may contain other guest frame bytes.

The interpreter permits synchronous reentry from a host callback. Nested entries
share the outer entry's budgets and memory while preserving distinct operand
stacks and checked program-stack frames. Invocation is synchronized; the host
must coordinate any direct access to `memory()` on other threads.

`stats()` returns cumulative instruction and syscall counts, invocation counts,
stack high-water marks, the last instruction, fault state, and an immutable
opcode histogram. Tracing is opt-in and retains at most 128 instruction records.
`reset()` restores initialized data, clears BSS and stacks, clears diagnostics
and counters, and permits reuse after a contained fault. It cannot run during
an active invocation.

## File layout

All header fields are signed 32-bit little-endian words, with nonnegative
values required for sizes and offsets. The original magic is `0x12721444`;
the version 2 magic is `0x12721445`.

| Byte offset | Meaning |
| --- | --- |
| 0 | Magic |
| 4 | Number of decoded instructions |
| 8 | Code file offset |
| 12 | Code byte length |
| 16 | Initialized data file offset |
| 20 | Data byte length, a multiple of four |
| 24 | Literal byte length |
| 28 | Zero-initialized BSS byte length |
| 32 | Version 2 only: jump-table **byte** length, a multiple of four |

The version 1 header occupies 32 bytes and version 2 occupies 36. Code and data
offsets are word-aligned and cannot overlap the header or each other. Literal
bytes follow initialized data. The version 2 jump table follows the literals
and contains little-endian instruction indices; it is metadata, not VM data.

The decoder validates all ranges in wide arithmetic before allocation or
indexing. File size and guest memory are each capped at 64 MiB, with at most
two million instructions. It decodes exactly the declared instruction count
and permits only up to three trailing zero alignment bytes inside the code
section. Truncated immediates, unknown opcode numbers, invalid frame sizes,
invalid argument slots, and invalid targets produce `QvmFormatException`.

The VM data region is the next power of two containing data, literals, and BSS.
Initialized bytes are copied without host-endian conversion because all memory
access explicitly reads and writes little-endian values. BSS and power-of-two
padding start at zero. Byte and halfword operations may use unaligned addresses.

## Instructions and call frames

Opcode numbers are the ordinal values of `Opcode`, covering original values
zero through 59. Each instruction starts with one opcode byte. `ENTER`,
`LEAVE`, `CONST`, `LOCAL`, conditional branches, and `BLOCK_COPY` have a
four-byte immediate. `ARG` has a one-byte unsigned immediate. Other
instructions have no immediate. Code addresses and branch operands count
instructions, not bytes.

The implemented operations include:

- Procedure entry/return, direct and indirect calls, jumps and all integer and
  floating-point conditional branches.
- Operand push/pop, constants, local addresses, outgoing arguments, one/two/four
  byte loads and stores, block copies, and byte/halfword sign extension.
- Signed and unsigned integer multiply/divide/remainder, wrapping arithmetic,
  bitwise operations, and shifts with a masked five-bit shift count.
- IEEE single-precision arithmetic, negation, integer-to-float conversion, and
  truncating float-to-integer conversion. Invalid float-to-integer conversions
  return `Integer.MIN_VALUE`; division by integer zero faults. Float division
  by zero follows IEEE infinity/NaN behavior.

`IGNORE` is a no-op. Executing `UNDEF` or `BREAK` produces an explicit unsupported
opcode fault. NaN payload propagation is not promised: normal float arithmetic
uses Java's canonical NaN representation. `PUSH` initializes its slot to zero.

The program stack lives at the top of guest data memory and grows down; the
operand stack is separate. A vmMain entry reserves 60 bytes: return sentinel
at offset zero, a reserved word at four, command at eight, and twelve arguments
at offsets twelve through 56. `ENTER` reserves its frame. `LOCAL` adds an
offset to the current program stack; `ARG` writes within its outgoing frame.
`CALL` saves the return instruction in the caller's first word. `LEAVE` restores
the caller frame and leaves exactly one return operand.

Java shadow frames validate procedure entry, frame size, operand depth, and
saved return addresses. Guest writes cannot redirect a return. Calls must
target `ENTER`. Branches remain inside the current procedure and cannot enter
another function. Version 2 indirect jumps additionally require a declared
jump-table target; the direct `CONST target; JUMP` form must actually execute
the preceding constant to qualify as direct.

For original Q3 compatibility, guest load/store starting addresses are masked
into the power-of-two data region. A multi-byte access or block copy cannot
cross its end. This compatibility masking does not apply to host memory APIs.
Block copies have defined overlap behavior equivalent to `System.arraycopy`.

## Execution limits and host responsibilities

The default per-outer-invocation limits are 50 million instruction/work units,
200,000 syscalls, five seconds of elapsed time, 4096 operand slots, 1024 call
frames, 64 KiB of program stack, and sixteen nested vmMain entries. A block
copy charges an additional work unit per four bytes. Custom `Limits` may lower
these values or select a different bounded stack capacity.

Cancellation and deadlines are checked at entry, periodically during execution,
and immediately before and after host callbacks. A synchronous callback that
blocks indefinitely cannot be preempted by this interpreter; host services
must remain bounded and cooperate with cancellation. Instruction budgets do
not estimate arbitrary host service costs. Hosts must enforce their own file,
asset, command, allocation, and output limits.

`QvmException` reports a reason, module name, and instruction index. Stack,
memory, control-flow, arithmetic, budget, cancellation, and host callback
failures terminate the invocation and mark the VM faulted. A reset is required
before further execution. A contained fault does not roll back guest memory
or external effects already performed by an authorized host service.

The VM exposes no filesystem, process, network, rendering, or audio API by
itself. Those capabilities are supplied by the host; accepting valid bytecode
does not automatically grant them.

## Original retail compatibility and validation

Tests assemble original synthetic programs in Java; no game bytecode is checked
in. They exercise arithmetic, float corner cases, calls and arguments, memory
widths, data persistence, nested host calls, return-address protection, version
2 jump enforcement, malformed sections, a seeded mutation corpus, and resource
budgets.

A local read-only audit of the user's original `pak0.pk3` decoded qagame
(115,028 instructions), cgame (71,923), and UI (74,792). The original UI's API
version entry returned three. qagame and cgame reached their expected host
callbacks. The integrated server audit subsequently initialized original
qagame, spawned a player, ran 100 frames with movement and firing, and reproduced
the exact canonical player-state bytes with a fixed seed.

The server's `GameAbi` is separate from the QVM file version. The verified
November 21, 1999 qagame module has SHA-256
`73d07e341bd21bff3e7ec2c961ea9cafe7ff9150e0ddcc5d8055757edece0f72`.
Only this exact hash automatically selects `RETAIL_1999`; other modules default
to the published 1.32 ABI, with an explicit profile constructor for independently
verified legacy mods. No structure layout is guessed from entity stride.

Retail entity states contain 204 bytes and player states 444 bytes. Its shared
entity has no `singleClient` word: `svFlags` is at 416, `mins` at 424,
`contents` at 448, current origin at 476, and owner at 500. Canonical snapshots
insert the absent trailing entity word and later player-state fields without
copying adjacent private game memory. Retail user commands contain time at
zero, byte buttons/weapon at four/five, aligned angles at eight/twelve/sixteen,
and signed movement bytes at twenty/twenty-one/twenty-two. These facts were
confirmed from user-supplied bytecode accesses and execution probes, not by
translating game implementation routines.

## References and provenance

- [id Software's QVM file header declaration](https://github.com/id-Software/Quake-III-Arena/blob/master/code/qcommon/qfiles.h).
- [ioquake3's extended QVM header declaration](https://github.com/ioquake/ioq3/blob/main/code/qcommon/qfiles.h)
  and [the original version 2 format change announcement](https://icculus.org/pipermail/quake3-commits/2005-October/000075.html).
- [Phaethon's independent QVM format/opcode notes](https://icculus.org/~phaethon/q3mc/q3vm_specs.html).
  Its offset/length table labels are reversed at bytes eight/twelve and
  sixteen/twenty; use the primary header declaration and the table above.
  `BLOCK_COPY` also has a four-byte immediate.
- [id Software's public game service declarations](https://github.com/id-Software/Quake-III-Arena/blob/master/code/game/g_public.h)
  and [public shared structure declarations](https://github.com/id-Software/Quake-III-Arena/blob/master/code/game/q_shared.h).

These references establish file and service ABI facts. CraftQ3's decoder,
interpreter, shadow-frame checks, memory API, diagnostics, fixtures, and tests
are original implementations; upstream runtime routines were not copied or
translated.
