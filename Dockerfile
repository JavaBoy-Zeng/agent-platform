FROM maven:3.9.11-eclipse-temurin-21 AS build

WORKDIR /workspace
COPY pom.xml ./
COPY agentos-kernel/pom.xml agentos-kernel/pom.xml
COPY agentos-tool/pom.xml agentos-tool/pom.xml
COPY agentos-memory/pom.xml agentos-memory/pom.xml
COPY agentos-hitl/pom.xml agentos-hitl/pom.xml
COPY agentos-planner/pom.xml agentos-planner/pom.xml
COPY agentos-agent/pom.xml agentos-agent/pom.xml
COPY agentos-server/pom.xml agentos-server/pom.xml
COPY agentos-kernel/src agentos-kernel/src
COPY agentos-tool/src agentos-tool/src
COPY agentos-memory/src agentos-memory/src
COPY agentos-hitl/src agentos-hitl/src
COPY agentos-planner/src agentos-planner/src
COPY agentos-agent/src agentos-agent/src
COPY agentos-server/src agentos-server/src
RUN mvn -B -ntp -DskipTests package \
    && cp agentos-server/target/agentos-server-*.jar /tmp/agentos-server.jar

FROM eclipse-temurin:21-jre-alpine

RUN apk add --no-cache docker-cli \
    && addgroup -S agentos \
    && adduser -S -D -H -u 10001 -G agentos agentos \
    && mkdir -p /app /workspace /data/artifacts /data/skills \
    && chown -R agentos:agentos /app /workspace /data

WORKDIR /app
COPY --from=build --chown=agentos:agentos /tmp/agentos-server.jar /app/agentos-server.jar

USER agentos
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/agentos-server.jar"]
