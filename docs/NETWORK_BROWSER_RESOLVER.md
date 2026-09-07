# Browser address resolution policy

`client.net.BrowserResolver` supplies bounded, nonblocking name resolution to the
browser host. This is an explicit application scheduling policy, **not a claim
of native DNS timing or native cache parity**. It borrows no VM callback and
does not send discovery, status or ping packets.

## API and ownership

```java
new BrowserResolver();
new BrowserResolver(Resolver lookup, LongSupplier clockMillis);
Optional<InetSocketAddress> resolve(String input, int defaultPort);
Status status(String input, int defaultPort);
int activeLookups();
int cachedEntries();
void close();
```

The injected `Resolver.lookup(String)` returns a bounded `InetAddress[]`; tests
provide authored arrays and controlled blocking behavior. The default uses
`InetAddress.getAllByName` on virtual threads and monotonic milliseconds.
Resolution returns immediately: an empty optional means pending, failed or
closed. `status` distinguishes UNKNOWN, PENDING, RESOLVED, FAILED and CLOSED
without starting work. Invalid syntax or a default port outside 1–65,535 throws
before lookup or cache insertion. Closed resolution returns empty.

`RemoteAddress.parse` supplies shared syntax validation. A supplied port always
wins; otherwise the caller's default is used. Numeric IPv4/IPv6 is immediate and
never enters a DNS worker. IPv6 is constructed with the explicit 16-byte factory,
preserving IPv4-mapped family and numeric scope. Named scope identifiers use
local network-interface metadata, with no DNS lookup; unavailable interfaces
produce a bounded failure. Hostname keys fold case, while IPv6 scope text retains
case for interface identity. The endpoint port is part of the cache key.

## Work and lifetime limits

At most **64 actual unresolved workers** may be active. Hostname lookups receive
virtual threads outside the VM/application frame. A result can contain 1–256
addresses and is cloned before publication; the first IPv4 result is preferred,
otherwise the first IPv6 result is selected. The browser owner polls to observe
completion and dispatch its waiting operation.

A lookup times out after **15 seconds**, measured on the next poll. Timeout
cancels its future and interrupts its worker. Platform DNS may ignore that
interrupt, so its work permit remains occupied until the actual worker exits.
This prevents cancellation from disguising an unbounded number of blocked DNS
calls. The worker wrapper always releases its permit in `finally`, including
cancellation before lookup begins. A late result cannot replace a newer retry.

Failed resolution can retry **30 seconds after failure** on a later `resolve`
call; merely asking for status never retries it. Quota failures follow the same
retry policy. Failure text is sanitized to at most 256 byte-oriented display
characters and remains inert diagnostic text.

The access-ordered cache holds at most **256 endpoint entries**. Eviction prefers
completed or failed entries over pending ones. Successful entries have no
separate TTL and persist until eviction or close; this is an intentional bounded
session cache. `close` cancels futures, requests worker shutdown and returns
without waiting for interrupt-resistant platform resolution. It is idempotent.

## Verification

Seven focused tests pass with an injected clock and authored addresses. They
cover numeric IPv4/scoped/mapped IPv6 without DNS; explicit/default ports;
nonblocking lookup and IPv4 preference; hostname cache reuse; failure text and
the exact retry boundary; timeout with an interrupt-resistant worker and ignored
late result; 64-worker pressure; nonblocking close; 256-entry eviction preserving
pending work; and invalid syntax without work or cache mutation. No test performs
external DNS, public-master discovery or real network I/O.
