# syntax=docker/dockerfile:1

###############################################################################
# Stage 1 - build
#
# A full JDK plus the project's sources. Nothing from this stage reaches the
# final image: it exists to turn source into a jar and is then discarded, which
# is the whole point of a multi-stage build. The compiler, the Maven cache and
# the ~59 MB fat jar all stay behind.
###############################################################################
FROM eclipse-temurin:21-jdk AS build

WORKDIR /build

# The wrapper and the POM first, on their own.
#
# Docker caches each instruction's result and reuses it while the inputs are
# unchanged. Copying only these two things before downloading dependencies means
# editing a Java file does not invalidate the download: the cache breaks at the
# COPY src/ below instead, and the dependency layer is reused. Copy everything
# at once and every source edit re-downloads the internet.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./

# Resolve dependencies into the image. -B is batch mode: no ANSI progress bars
# in build logs that nobody will read interactively.
RUN ./mvnw -B dependency:go-offline

COPY src/ src/

# Tests are deliberately not run here. They need Docker themselves from Phase 7
# on (Testcontainers), and running them inside the image build would mean Docker
# inside Docker for no benefit - `./mvnw clean verify` already ran on the host,
# and CI will run it again in Phase 11.
RUN ./mvnw -B clean package -DskipTests

###############################################################################
# Stage 2 - explode the layered jar
#
# Spring Boot can split its fat jar into layers ordered by how often they change.
# Extracting them here lets the runtime stage COPY each one separately, so Docker
# can cache them separately.
#
# Measured on this project: dependencies 56 MB, spring-boot-loader 604 KB,
# application 320 KB. Without layers, changing one line of Java pushes a new
# 59 MB layer. With them, it pushes 320 KB and the 56 MB layer is reused.
#
# Note the jarmode. Spring Boot 3.3 replaced `-Djarmode=layertools` with
# `-Djarmode=tools extract`; the old form is gone in Boot 4 and every older
# tutorial still shows it.
###############################################################################
FROM eclipse-temurin:21-jdk AS extract

# The jar is deliberately kept outside the destination directory: extract refuses
# to write into a directory that is not empty, and the jar itself would make it so.
COPY --from=build /build/target/*.jar /tmp/application.jar

WORKDIR /extracted
RUN java -Djarmode=tools -jar /tmp/application.jar extract --layers --launcher --destination .

###############################################################################
# Stage 3 - runtime
#
# A JRE, not a JDK: nothing here compiles anything. Alpine-based, which is the
# smaller of Temurin's images - the trade is musl rather than glibc, fine for a
# pure-JVM application and worth remembering if a glibc-only native library ever
# appears.
###############################################################################
FROM eclipse-temurin:21-jre-alpine

# A user to run as.
#
# Containers default to root, and root in a container is root on the host kernel:
# the isolation is namespaces, not a virtual machine. A process that can write
# anywhere in the image, bind privileged ports and exploit a kernel bug from
# uid 0 is a worse starting point than one that cannot - and nothing this
# application does needs any of it. -S makes a system user with no password and
# no login shell.
RUN addgroup -S ecomdemo && adduser -S -G ecomdemo -h /app ecomdemo

WORKDIR /app

# One COPY per layer, least-changing first, so Docker's cache follows the same
# order. --chown avoids a second layer that exists only to fix permissions.
COPY --from=extract --chown=ecomdemo:ecomdemo /extracted/dependencies/ ./
COPY --from=extract --chown=ecomdemo:ecomdemo /extracted/spring-boot-loader/ ./
COPY --from=extract --chown=ecomdemo:ecomdemo /extracted/snapshot-dependencies/ ./
COPY --from=extract --chown=ecomdemo:ecomdemo /extracted/application/ ./

USER ecomdemo

# Documentation, not a firewall rule: it records which port the process listens
# on. compose.yaml is what actually publishes it.
EXPOSE 8080

# -XX:MaxRAMPercentage=75.0
#
# A modern JVM reads the container's memory limit rather than the host's, but its
# default heap ceiling is 25% of it - so a container limited to 512 MB would cap
# the heap near 128 MB and spend its life in GC while three quarters of its
# allowance sat unused. 75% leaves room for metaspace, thread stacks and direct
# buffers, which live outside the heap and are what an over-eager setting like
# 90% gets killed by: the OOM killer counts all of it.
ENTRYPOINT ["java", \
            "-XX:MaxRAMPercentage=75.0", \
            "org.springframework.boot.loader.launch.JarLauncher"]
