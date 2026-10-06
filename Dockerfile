
# Stage 1: Build Stage

# Base build
FROM ubuntu:latest as builder

# Set PATH
WORKDIR /app

# Install system dependencies needed for sbt
RUN apt-get update && apt-get install -y \
    curl \
    zip \
    npm \
    unzip \
    && apt-get clean

# Install SDKman
RUN curl -s "https://get.sdkman.io?ci=true&rcupdate=false" | bash

# Activate SDKman
RUN /bin/bash -c "source /root/.sdkman/bin/sdkman-init.sh \
    && sdk version \
    && sdk install java 25.0.1-graalce \
    && sdk install sbt"

ENV PATH="/root/.sdkman/candidates/java/current/bin:/root/.sdkman/candidates/sbt/current/bin:/root/.sdkman/candidates/scala/current/bin:${PATH}"

# Test java
RUN java --version

# Test sbt
RUN sbt -version

# Copy SBT project files
COPY build.sbt .
COPY app ./app
COPY oocsi ./oocsi
COPY conf ./conf
COPY project ./project
COPY public ./public
COPY lib ./lib

ARG BUILD_MODE=stage
ENV BUILD_MODE=${BUILD_MODE}

RUN sbt update

# Build the application
RUN sbt dist && ls -l /app/target/universal

# Copy zip to root for easier export
RUN cp /app/target/universal/oocsi-*.zip /oocsi-web.zip

## ---------------------------------------------------------------------------

# Stage 2: Application container

FROM ghcr.io/graalvm/graalvm-community:25 AS production

# Set working directory
WORKDIR /app

# Copy the built JAR file from the build stage
COPY --from=builder /app/target/universal/oocsi-*.zip app.zip

# Unpack application and rename
RUN microdnf install unzip && \
    unzip app.zip && \
    mv oocsi* oocsi && \
    rm app.zip && \
    microdnf remove unzip

# Expose the application ports (4444: OOCSI TCP socket, 9000: HTTP/WebSocket)
EXPOSE 4444 9000

# -------------------------------------------------------------------------------------------------
# JVM Performance & Garbage Collection Tuning (Optional / Configurable)
#
# By default in container environments, the JVM sizes heap based on container cgroups.
# The options below tune GC pause times and heap sizing for the high-concurrency, short-lived
# object allocation patterns typical of the OOCSI server (e.g. JSON messaging, NIO sockets):
#
#   -XX:+UseG1GC                     : G1 Garbage Collector provides predictable, low-latency pause times.
#   -XX:MaxRAMPercentage=65.0        : Allocates up to 65% of available container RAM to Java heap,
#                                      leaving the remaining 35% for direct byte buffers (NIO), thread stacks, and OS.
#   -XX:InitialRAMPercentage=40.0    : Avoids gradual heap expansion overhead at startup.
#   -XX:G1ReservePercent=15          : Reserves 15% spare memory in G1 regions to prevent costly evacuation failures.
#   -XX:InitiatingHeapOccupancyPercent=45 : Starts concurrent GC cycles earlier (at 45% heap occupancy)
#                                      to stay ahead of sudden message bursts.
#   -XX:+ExitOnOutOfMemoryError      : Immediately terminates on OOM so the container orchestrator (Docker/K8s)
#                                      can quickly restart the container rather than lingering in a hung state.
#
# These options can be overridden or disabled entirely at runtime by passing your own JAVA_OPTS:
# e.g.: docker run -e JAVA_OPTS="-XX:+UseSerialGC -Xmx1g" ...
# -------------------------------------------------------------------------------------------------
ENV JAVA_OPTS="-XX:+UseG1GC -XX:MaxRAMPercentage=65.0 -XX:InitialRAMPercentage=40.0 -XX:G1ReservePercent=15 -XX:InitiatingHeapOccupancyPercent=45 -XX:+ExitOnOutOfMemoryError"

# Switch to DF directory
WORKDIR /app/oocsi
CMD ["bin/oocsi-web", "-Dconfig.file=/app/oocsi/conf/application.conf", "-Dlogger.file=/app/oocsi/conf/logback.xml"]

## ---------------------------------------------------------------------------

# use for testing
# CMD ["/bin/bash"]

## to run the dockerfile in production mode:
## docker build --tag oocsidocker:production --target production . && docker run -it --rm -p 9000:9000 -p 4444:4444 oocsidocker:production
