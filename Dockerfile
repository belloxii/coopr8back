# syntax=docker/dockerfile:1

# ==============================================================================
# COOPR8 backend image
#
# Two stages: build the jar with a full JDK, run it on a JRE. The runtime stage
# carries no compiler, no Maven, and no source.
#
# The project targets Java 17 (<java.version>17</java.version> in pom.xml), so
# both stages are Temurin 17. They used to be Temurin 22, which meant the image
# ran the application on a JVM no test or CI job had ever exercised -- CI pins
# Temurin 17.
# ==============================================================================

FROM eclipse-temurin:17-jdk AS build

WORKDIR /app

# The wrapper first, so the build uses the same Maven version everything else does.
COPY mvnw mvnw
COPY .mvn .mvn
RUN chmod +x mvnw

COPY pom.xml .
COPY src src

# Tests are NOT run here. This image builds a jar; proving the jar correct is the
# test gate's job (./mvnw test -Dcoopr8.test.require-docker=true), which needs a
# Docker daemon of its own for Testcontainers and so cannot run inside a build.
# Build the image from a commit whose gate is green.
RUN ./mvnw --batch-mode --no-transfer-progress clean package -DskipTests

# The jar is coopr8-<version>.jar, from <artifactId>coopr8</artifactId> -- at this
# version, coopr8-0.0.1-SNAPSHOT.jar. This copy named coop-0.0.1-SNAPSHOT.jar and
# so matched nothing, which meant the image could never be built at all. The glob
# tracks <version> so a release bump cannot reintroduce that. It matches exactly
# one file: Boot's repackage leaves the pre-repackage jar as *.jar.original.
RUN cp target/coopr8-*.jar /app/app.jar


# ------------------------------------------------------------------ runtime

FROM eclipse-temurin:17-jre

# curl is here only for HEALTHCHECK below. Installed explicitly rather than
# assumed: the Temurin images carry no fixed guarantee of it.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# An unprivileged fixed uid/gid. A container process that does not need root
# should not have it: root in the container is root on the host kernel for
# anything that escapes the namespace, and this process only needs to read one
# jar and open one socket.
RUN groupadd --system --gid 10001 coopr8 \
    && useradd --system --uid 10001 --gid coopr8 --no-create-home coopr8

WORKDIR /app

COPY --from=build --chown=10001:10001 /app/app.jar app.jar

USER 10001:10001

# Documentation only; the application binds ${PORT:8080} (see application.properties).
EXPOSE 8080

# MaxRAMPercentage because the JVM's default max heap is a fraction of the
# machine's memory, which in a container is the *host's* -- so a memory-limited
# container either wastes its allowance or gets OOM-killed for exceeding it.
# 75% leaves room for metaspace, thread stacks and direct buffers.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0"

# Liveness, not /actuator/health: plain health includes the datasource, so a brief
# database outage would mark the container unhealthy and get it restarted -- which
# does not fix a database and drops every in-flight request. Liveness answers "is
# this JVM still working", which is the question a restart is the right answer to.
# Readiness (/actuator/health/readiness) is what a load balancer should poll; it
# does consider the datasource, deliberately.
# start-period covers Flyway: on a fresh database V1..V12 run before the port opens.
HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=3 \
    CMD curl -fsS http://localhost:${PORT:-8080}/actuator/health/liveness || exit 1

# Exec form via `sh -c` so ${JAVA_OPTS} expands, with `exec` so the JVM replaces
# the shell and becomes PID 1 -- otherwise SIGTERM from `docker stop` reaches the
# shell and the JVM is killed outright, which is precisely what graceful shutdown
# (server.shutdown=graceful) exists to avoid.
ENTRYPOINT ["sh", "-c", "exec java ${JAVA_OPTS} -jar /app/app.jar"]
