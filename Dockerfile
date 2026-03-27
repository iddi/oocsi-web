
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

# Expose the application port
EXPOSE 9000

# Switch to DF directory
WORKDIR /app/oocsi
CMD ["bin/oocsi-web", "-Dconfig.file=/app/oocsi/conf/application.conf", "-Dlogger.file=/app/oocsi/conf/logback.xml"]

## ---------------------------------------------------------------------------

# use for testing
# CMD ["/bin/bash"]

## to run the dockerfile in production mode:
## docker build --tag oocsidocker:production --target production . && docker run -it -rm -p 9000:9000 -p 4444:4444 oocsidocker:production
