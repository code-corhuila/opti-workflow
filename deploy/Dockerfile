# Reproducible build: dependencies are resolved from the poms, tests run in CI (ci.yml).
FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
COPY workflow-core/pom.xml workflow-core/
COPY workflow-adapters/pom.xml workflow-adapters/
COPY workflow-app/pom.xml workflow-app/
COPY . .
RUN mvn -B -q -DskipTests package

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY --from=build /src/workflow-app/target/workflow-api.jar app.jar
USER app
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --start-period=30s --retries=6 \
  CMD wget -qO- http://localhost:8080/health || exit 1
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
