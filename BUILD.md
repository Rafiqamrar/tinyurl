# Containerization — TinyURL server

Three Dockerfiles document the progression from a naive build to a
production-shaped image.

## Image comparison

| Version | Base image (final stage) | Build tools in final image | Source in final image | Measured size |
|---|---|---|---|---|
| v1 (`Dockerfile`) | `maven:3.9-eclipse-temurin-21` | Yes (Maven + full JDK) | Yes (`COPY . .`, no multi-stage) | 869MB |
| v2 (`Dockerfile.v2`) | `eclipse-temurin:21-jre` | No | No | 466MB |
| v3 (`Dockerfile.v3`) | `eclipse-temurin:21-jre-alpine` | No | No | 298MB |

Sizes are from `docker images` on this machine, JDK 21 / Docker 29.7.2.

## Why multi-stage build reduces size (v1 → v2)

v1 builds and runs in the same image: the final container still carries the
full Maven distribution, the JDK compiler, dependency `.m2` cache, and the
copied source tree — none of which are needed to *run* the app, only to
*build* it. A multi-stage build splits this into a `builder` stage (Maven +
JDK, does `mvn package`) and a separate runtime stage that only `COPY
--from=builder` the resulting fat jar. Everything used solely for compiling
is discarded when the builder stage isn't part of the final image, cutting
size roughly in half here (869MB → 466MB) and shrinking the attack surface
(fewer packages = fewer CVEs, see scan results below).

## Why Alpine + non-root user + healthcheck matter for production (v2 → v3)

- **Alpine base**: `eclipse-temurin:21-jre-alpine` swaps glibc/Debian
  userland for musl + a minimal package set, dropping the runtime image
  further (466MB → 298MB) and reducing the number of OS packages that can
  carry vulnerabilities.
- **Non-root user**: v3 creates and switches to an unprivileged `app` user
  (`USER app`) instead of running as root. If the JVM process or a
  dependency is ever compromised, a non-root process is contained by normal
  Linux permissions instead of having full control of the container.
- **HEALTHCHECK**: v3 adds a `HEALTHCHECK` hitting `GET /health` on an
  interval. This lets Docker/Compose/orchestrators (Kubernetes liveness
  probes work the same way) detect a hung or unresponsive process and
  restart it automatically, rather than routing traffic to a dead
  container indefinitely.

## Why copying `pom.xml` before `src/` optimizes the build cache

Docker caches each layer and only invalidates it (and everything after it)
if its inputs change. `Dockerfile.v2`/`v3` do:

```dockerfile
COPY pom.xml .
RUN mvn dependency:go-offline
COPY src src
RUN mvn package -DskipTests
```

`pom.xml` changes far less often than application source. By copying it
first and resolving dependencies before copying `src/`, a source-only
change (the common case) reuses the cached `dependency:go-offline` layer
instead of re-downloading every Maven dependency on every build. Only
`mvn package` re-runs. v1 does a single `COPY . .` before `mvn package`, so
*any* file change — including a one-line source edit — invalidates the
cache and forces a full dependency re-resolve.

## Vulnerability scan results (Trivy)

`docker run --rm -v /var/run/docker.sock:/var/run/docker.sock aquasec/trivy image <tag>`

| Image | CRITICAL | HIGH | MEDIUM | LOW | Total |
|---|---|---|---|---|---|
| tinyurl:v1 | 0 | 12 | 82 | 23 | 117 |
| tinyurl:v3 | 0 | 6 | 18 | 0 | 24 |

v3 cuts total findings by ~80% (117 → 24) purely from having a smaller OS
base and no build toolchain (Maven, compiler plugins, and their transitive
dependencies) baked into the runtime image. The remaining findings in both
images are in the application's own dependencies (Jackson, commons-io,
etc. pulled in at build time) rather than the OS layer, and would need a
dependency version bump to clear.

## Running locally

```bash
docker compose up --build -d
curl http://localhost:8080/health
# {"status":"UP"}
docker compose down
```

`docker-compose.yml` builds from `Dockerfile.v3`, maps `8080:8080`, and
runs the same healthcheck defined in the Dockerfile.
