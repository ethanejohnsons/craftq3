# Host-only streaming demo storage

`core.fs.DemoFileStore` owns a separate, host-provisioned flat root. It is not a VM filesystem
capability and does not change `GameFileStore`'s 16 MiB buffered file limit. Default demo quotas are
512 MiB per file, 2 GiB committed bytes and 128 files; a host may configure smaller limits.
The root must not be shared with unrelated writers.

`openRead(VirtualPath)` returns an optional owned stream; `openAtomicWrite(VirtualPath)` returns an
`AtomicOutputStream`; `list()` returns bounded, sorted immutable names. Requested names must be
flat, canonical protocol-68 `.dm_68` filenames. Listings exclude temporary files and noncanonical
import names. No requested path creates a directory, and no symlink is followed. Directory-stream
providers without descriptor-relative operations are rejected.

One writer and at most 16 readers may be active. A reader is tied to its opened file descriptor and
initial byte length. Replacing the path or renaming the root cannot redirect it to another file.
Closing the store closes every reader and aborts its pending writer.

A writer creates a unique adjacent file with `CREATE_NEW` and `NOFOLLOW_LINKS`, writes incrementally,
and checks its allowance before each write. The allowance accounts for replacing the target's
existing bytes; `byteLimit()` lets a recorder reserve room for its end marker. Pending data is
excluded from committed listings/usage, so replacement may temporarily consume one additional
file and up to 512 MiB beyond the persistent byte quota. At commit, the destination identity and
metadata, root quota and file count are checked again before an atomic directory-entry move.
There is no fallback through a symlink or non-atomic copy.

Use this order with `Protocol68DemoRecorder`:

```java
recorder.finish();
stream.commit();
recorder.close();
```

`commit()` is explicit and idempotent. `close()` without a successful commit aborts and removes the
temporary file, preserving the prior target. After commit, closing the recorder/stream leaves the
committed file intact. File-channel data is forced before commit where supported; this does not
promise directory-entry durability against power loss. A process crash can leave a temporary file
for manual recovery. Such orphaned regular files count against startup quotas; they are never
silently deleted. Existing nonregular files or excess quotas reject opening the store.

Eight focused tests pass: a streamed recording beyond 16 MiB, abort and write failure, total/file
quota contention, larger/smaller replacement, pinned-root/read behavior, symlink traps, modified
target rejection, imported-length bounds and closing all resources. These are host storage
properties, separate from [native demo framing and recording observations](NETWORK68_DEMO_HOST.md).
