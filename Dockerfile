# syntax=docker/dockerfile:1

###############################################################################
# One Dockerfile, five images.
#
# Phase 20 turned the repository into a multi-module build with five deployable
# services. The obvious move is five Dockerfiles; this is one, parameterised by
# a build argument, and compose.yaml passes SERVICE per service.
#
#   docker build --build-arg SERVICE=catalog-service .
#
# Why one file rather than five: every property worth pinning here - non-root,
# layered copies, the jarmode, the heap percentage - is identical for all five,
# and five copies means four of them drift. ContainerConfigurationTest also has
# one file to check rather than five, and a rule that holds for one image now
# holds for all of them by construction.
#
# The cost is that the build context is the whole repository rather than one
# module, so every image's build stage compiles every module. BuildKit shares
# that stage across the five builds when they run together, so it happens once -
# but a change to shared-kernel does invalidate all five, which is a fair
# reflection of reality: they all depend on it.
###############################################################################

###############################################################################
# Stage 1 - build
#
# A full JDK plus the project's sources. Nothing from this stage reaches the
# final image: it exists to turn source into jars and is then discarded.
###############################################################################
FROM eclipse-temurin:21-jdk AS build

WORKDIR /build

# The wrapper and every POM first, on their own.
#
# Docker caches each instruction's result and reuses it while the inputs are
# unchanged. Copying only the build files before downloading dependencies means
# editing a Java file does not re-resolve them: the cache breaks at the COPY of
# the sources below instead.
#
# Note that all seven POMs are needed, not just the root one - Maven cannot read
# the reactor without them, and `go-offline` on the aggregator resolves what
# every module needs in one pass.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY shared-kernel/pom.xml shared-kernel/
COPY services/customer-service/pom.xml services/customer-service/
COPY services/catalog-service/pom.xml services/catalog-service/
COPY services/inventory-service/pom.xml services/inventory-service/
COPY services/order-service/pom.xml services/order-service/
COPY services/notification-service/pom.xml services/notification-service/

# Resolve dependencies into the image. -B is batch mode: no ANSI progress bars
# in build logs that nobody will read interactively.
RUN ./mvnw -B dependency:go-offline

COPY shared-kernel/src shared-kernel/src
COPY services/ services/

# Tests are deliberately not run here. They need Docker themselves (Testcontainers),
# and running them inside the image build would mean Docker inside Docker for no
# benefit - `./mvnw clean verify` already ran on the host, and CI runs it again.
RUN ./mvnw -B clean package -DskipTests

###############################################################################
# Stage 2 - explode the layered jar
#
# Spring Boot can split its fat jar into layers ordered by how often they change.
# Extracting them here lets the runtime stage COPY each one separately, so Docker
# can cache them separately.
#
# Measured on this project: dependencies ~56 MB, spring-boot-loader ~600 KB,
# application ~300 KB. Without layers, changing one line of Java pushes a new
# 59 MB layer. With them, it pushes 300 KB and the 56 MB layer is reused - and
# with five images sharing the same dependency set, the saving is now fivefold.
#
# Note the jarmode. Spring Boot 3.3 replaced `-Djarmode=layertools` with
# `-Djarmode=tools extract`; the old form is gone in Boot 4 and every older
# tutorial still shows it.
###############################################################################
FROM eclipse-temurin:21-jdk AS extract

# Which service this image is for. Declared again in this stage because an ARG
# is scoped to the stage it appears in - a value declared before the first FROM
# is global but unavailable inside a stage unless redeclared, which is one of
# the more surprising things about Dockerfiles.
ARG SERVICE

# The jar is deliberately kept outside the destination directory: extract refuses
# to write into a directory that is not empty, and the jar itself would make it so.
COPY --from=build /build/services/${SERVICE}/target/*.jar /tmp/application.jar

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
# the isolation is namespaces, not a virtual machine. -S makes a system user with
# no password and no login shell.
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
# on. compose.yaml is what actually publishes it, and each service overrides the
# number - customer-service 8081 through notification-service 8085.
EXPOSE 8080

# -XX:MaxRAMPercentage=75.0
#
# A modern JVM reads the container's memory limit rather than the host's, but its
# default heap ceiling is 25% of it - so a container limited to 512 MB would cap
# the heap near 128 MB and spend its life in GC while three quarters of its
# allowance sat unused. 75% leaves room for metaspace, thread stacks and direct
# buffers, which live outside the heap and are what an over-eager setting like
# 90% gets killed by: the OOM killer counts all of it.
#
# It matters much more than it did. Phase 17 already found this Docker VM at
# 4.1 GB with four JVMs on it; there are now six, and every one of them sizing
# its heap against the whole machine would be a stack that thrashes rather than
# one that runs.
ENTRYPOINT ["java", \
            "-XX:MaxRAMPercentage=75.0", \
            "org.springframework.boot.loader.launch.JarLauncher"]
