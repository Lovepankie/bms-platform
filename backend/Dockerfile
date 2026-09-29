# syntax=docker/dockerfile:1.7
# BMS Platform API image (ADR-010, ADR-006). One image, two commands:
#   (default)  the web application, connected as bms_app
#   migrate    Flyway migrations as bms_owner, then exit (the one-shot migrate container)
# Built once per commit in CI and promoted unchanged from staging to production.

FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /src
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn -B -q dependency:go-offline
COPY src ./src
# Tests, formatting and the architecture checks run in CI (mvn verify) before any image is built.
RUN --mount=type=cache,target=/root/.m2 mvn -B -q package -DskipTests -Dspotless.check.skip=true \
    && java -Djarmode=tools -jar target/bms-api.jar extract --layers --destination /extracted

FROM eclipse-temurin:25-jre AS runtime
ARG GIT_SHA=unknown
RUN groupadd --system --gid 10001 bms && useradd --system --uid 10001 --gid bms --no-create-home bms
WORKDIR /app
# Layers from least to most often changed, so a code-only change ships a small layer.
COPY --from=build /extracted/dependencies/ ./
COPY --from=build /extracted/spring-boot-loader/ ./
COPY --from=build /extracted/snapshot-dependencies/ ./
COPY --from=build /extracted/application/ ./
# JVM sized for a 4 GB VM (docs/sdd/09-infrastructure-design.md section 9.4): the container is
# limited to 1536 MiB, the heap takes up to 70 percent of that, the rest is metaspace, thread
# stacks, direct buffers and code cache. Override JAVA_TOOL_OPTIONS to resize.
ENV BMS_GIT_SHA=${GIT_SHA} \
    SPRING_PROFILES_ACTIVE=server \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:InitialRAMPercentage=25 -XX:MaxMetaspaceSize=256m -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/urandom"
USER 10001:10001
EXPOSE 8080 8081
ENTRYPOINT ["java", "-jar", "/app/bms-api.jar"]
