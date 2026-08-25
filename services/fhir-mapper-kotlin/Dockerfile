# syntax=docker/dockerfile:1
FROM gradle:8.12-jdk21 AS build
WORKDIR /build
# Copy the build scripts first so the dependency-resolution layer is cached across source-only
# changes. `--no-daemon` because a build daemon that outlives the RUN step is a wasted process in
# a layer that is thrown away either way.
COPY settings.gradle.kts build.gradle.kts ./
RUN gradle --no-daemon dependencies --configuration runtimeClasspath > /dev/null
COPY src ./src
# installDist rather than a shaded jar: merging META-INF/services entries by hand is how a driver
# silently loses a provider, and the failure shows up at runtime rather than in the build.
RUN gradle --no-daemon installDist -x test

FROM eclipse-temurin:21-jre-alpine
# SOC 2 CC6.1: the container runs as an unprivileged user with no shell-writable app dir.
RUN addgroup -S app && adduser -S -G app -H -s /sbin/nologin app
WORKDIR /app
COPY --from=build /build/build/install/fhir-mapper /app
USER app
EXPOSE 8081
# Readiness, not liveness: /readyz also fails when the consume loop has stopped calling poll(),
# which is the way a Kafka consumer dies without the process noticing. A PID check would report
# this container healthy while it consumed nothing for an hour.
HEALTHCHECK --interval=30s --timeout=3s --start-period=20s --retries=3 \
    CMD wget -qO- http://127.0.0.1:8081/readyz || exit 1
ENTRYPOINT ["/app/bin/fhir-mapper"]
