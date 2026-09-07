# PK3 checksums

`assets.fs.Pk3Checksums.normal(int[] orderedCrcs)` and
`pure(int[] orderedCrcs, int checksumFeed)` calculate original pack checksums.
They return all 32 bits in a Java `int`; signed decimal formatting matches the
native filesystem lists. Each call accepts at most 400,000 entries and leaves
the caller's array untouched. These helpers neither read archives nor choose
which mounted pack owns a virtual path.

The caller supplies central-directory CRC32 values **in directory entry order**,
excluding entries with zero uncompressed size. Names, path case, compression
and file lookup precedence do not affect this list. CRC values themselves may
be zero, repeated or have their high bit set; the helper preserves each supplied
word. It does not sort or deduplicate.

The normal checksum hashes the list serialized as four little-endian bytes per
CRC. The pure checksum prepends the feed as another little-endian word, even
when the feed is zero. Controlled native filesystem results distinguished that
prefix from a suffix and from a big-endian feed. Independently authored `abc`
and `xyz` members gave normal `1225466421`; reversed directory order gave
`1603507484`. Renaming entries or adding empty members did not change the result.

`core.hash.Md4.digest(byte[])` independently implements the algorithm described
in [RFC 1320, sections 3.1–3.5](https://www.rfc-editor.org/rfc/rfc1320#section-3).
It returns the standard sixteen digest bytes. `blockChecksum(byte[])` supplies
the native engine's 32-bit result: nonempty input folds the four little-endian
digest words by XOR. These are compatibility checksums, not authentication.

There is a measured native empty-input exception. Standard MD4(empty) folds to
`0xc6f640b7`, while the unchanged native `Com_BlockChecksum(buffer, 0)` returns
`0x9868a6bf`. The engine-compatibility method preserves the latter explicitly;
the standard digest method remains RFC-correct. An independently loaded PK3
containing only zero-size members confirmed normal `-1737972033` (`0x9868a6bf`),
pure feed0 `1290185885` and pure feed42 `-1864024385`. Native filesystem probing
also found that a ZIP containing no directory entries is not mounted; deciding
whether to mount that archive remains filesystem policy.

## Provenance and validation

The algorithm implementation was written from the RFC's algorithm description,
not from native engine routines or its reference implementation. Public
`qcommon.h` declares `unsigned Com_BlockChecksum(const void *, int)`.
`scripts/BlockChecksumOracle.c` links that unchanged function with an authored
hex-buffer host. The native source baseline is ioquake3 commit
`588393618dbc82e7207c21c6ddecca229944a03a`. Only headers, data declarations,
definition signatures and observed outputs were inspected; no engine routine
body was read, copied or translated. A separate ignored zero-length observation
exposed private linkage at compilation solely to inspect the resulting digest;
the published comparison needs only the public checksum entry point.

Validation passed:

- All seven full-digest test vectors in RFC 1320 Appendix A.5, plus authored
  padding-boundary, storage-ownership and native empty-input regressions.
- **20,000 arbitrary buffers / 40,525,343 bytes**, exact native checksum bits.
- **40,007 ordered-CRC comparisons**, including normal/pure outputs, signed and
  mixed-byte feeds, the 400,000-entry bound and original pack metadata.
- **Eight focused tests** across core/assets, Java 25 compilation and scoped
  formatting. No native runtime dependency is introduced.

The original user-owned `pak0.pk3` was read through `ZipFile` central-directory
metadata only: **3,357 included entries and 182 excluded zero-size entries**.
No member was extracted, inflated or changed. Its independently observed native
filesystem checksums and Java results agree:

| Kind/feed | Signed checksum |
| --- | ---: |
| Normal | 1566731103 |
| Pure / 0 | 1615543659 |
| Pure / 42 | -1507839103 |
| Pure / -1 | 1800150064 |
| Pure / 305419896 (`0x12345678`) | -1277309582 |

Reproduce after compiling core/assets:

```sh
python3 scripts/BuildBlockChecksumOracle.py
java -cp craftq3-core/build/classes/java/main scripts/AuditBlockChecksums.java
java -cp craftq3-core/build/classes/java/main:craftq3-assets/build/classes/java/main scripts/AuditPackChecksums.java .tools/block-checksum-oracle/probe .tools/pak0-audit/games/baseq3/pak0.pk3
```

The final PK3 argument is optional; omit it for entirely authored data. The
native observer owns only `.tools/block-checksum-oracle` outputs. Run logs are
`/tmp/craftq3-block-checksum-audit.log`,
`/tmp/craftq3-pack-checksums-audit.log` and
`/tmp/craftq3-pack-checksums-tests.log`. The independent native original-pack
filesystem results are in `/tmp/craftq3-pure-retail-checksums.json`.
