# Concurrency Benchmark — TinyURL Server

## Setup

- **Load tool**: `hey`
- **Endpoint**: `GET /health`
- **Simulated workload**: `Thread.sleep(2000)` in each request handler
- **JDK**: OpenJDK 21
- **Hardware**: Ubuntu 22.04 (WSL2), 8 GB RAM
- **Client timeout**: 20s (hey default)

## Test 1 — Standard load (500 concurrent)

`hey -n 10000 -c 500 http://localhost:8080/health`

| Approach | Total time | Throughput | p50 | p95 | p99 | Success rate |
|---|---|---|---|---|---|---|
| **Virtual Threads** | 41.4s | 241 req/s | 2.00s | 2.14s | **2.20s** | **100%** |
| **FixedThreadPool(50)** | 400s | 25 req/s | 10.1s | 18.1s | 19.98s | 4.8% (9517 timeouts) |
| **new Thread() per request** | 42.1s | 238 req/s | 2.00s | 2.18s | **3.39s** | 100% |

## Test 2 — Stress test (5000 concurrent, virtual threads only)

`hey -n 50000 -c 5000 http://localhost:8080/health`

| Metric | Value |
|---|---|
| Total time | 68s |
| Throughput | **734 req/s** |
| Fastest / Slowest | 2.00s / 19.95s |
| p50 / p90 / p99 | 2.12s / 4.02s / **11.5s** |
| Success rate | 92.4% (3820 timeouts, 3 connection resets) |

## Analysis

### Standard load — Little's Law confirms everything

- **FixedThreadPool(50)** hits the theoretical ceiling: 50 threads / 2s = 25 req/s. Below this rate, the queue grows unbounded, and requests time out after 20s. Not a bug, a design limitation.
- **Virtual threads** achieve 99% of theoretical efficiency (10,000 / 500 / 2s = 40s theoretical, measured 41.4s).
- **`new Thread()` per request** matches virtual threads on throughput at this scale, but shows a wider p99 tail (3.4s vs 2.2s) due to OS-level context switching overhead.

### Stress test — where each model actually breaks

| Approach | Behavior at 5000 concurrent |
|---|---|
| Virtual threads | **Works** — degrades gracefully (92% success, elevated p99) |
| `new Thread()` | **Would fail** — 5000 × 1 MB stack = 5 GB, OS refuses or swaps |
| FixedThreadPool(50) | **Would time out at 99%+** — 33 minutes to complete |

The virtual thread test reveals what a real system looks like under pressure:
- **p50 stays healthy** (2.12s) — most users don't notice
- **p99 explodes** (11.5s) — the tail suffers first
- **A few percent time out** — this is what a partial outage looks like in production

This is why monitoring **p95/p99 latency** matters more than average or error rate.

### Why the stress test doesn't hit 5000/2 = 2500 req/s

Theoretical ceiling: 2500 req/s. Measured: 734 req/s. The bottleneck is no longer the concurrency model — it's the machine itself:
- **CPU saturation** from carrier thread scheduling
- **ServerSocket backlog** (default 128 on Linux) — some connections refused
- **File descriptors** limit at 1024 by default
- **Kernel network stack** overhead at high socket count

With a properly tuned production server (backlog 1024, ulimit 65536, more CPU), throughput would scale further. **Virtual threads are not the bottleneck.**

## Broken pipe exceptions (test B)

During the FixedThreadPool test, server logs were flooded with `java.net.SocketException: Broken pipe`.
This is not a bug — it's the server trying to write a response to a client that already timed out and closed the connection.
In production, this class of exception should be logged at DEBUG level, not ERROR, to avoid drowning genuine errors.

## Conclusion

- **Virtual threads are the correct default for I/O-bound servers in Java 21**: same code as `new Thread()`, better latency tail, dramatically lower memory (~KB vs ~MB per thread), and they scale past the point where OS threads collapse.
- **Bounded thread pools remain useful** for controlling load on a downstream component (e.g. limiting DB connections). Size them with Little's Law: `pool_size ≈ target_throughput × latency`.
- **`new Thread()` per request is an anti-pattern**: comparable throughput on light loads, but no scalability ceiling protection, higher memory, worse tail latency.
- **Systems don't crash — they degrade.** At 5000 concurrent, p50 stayed at 2s while p99 hit 11.5s. In production, this is invisible to average latency monitoring but very visible to affected users. Alert on percentiles, not means.

## References

- [JEP 444 — Virtual Threads](https://openjdk.org/jeps/444)
- [Little's Law](https://en.wikipedia.org/wiki/Little%27s_law)
- [Google SRE Book — Monitoring Distributed Systems](https://sre.google/sre-book/monitoring-distributed-systems/)