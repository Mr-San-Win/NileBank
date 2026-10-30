FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
COPY src src
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp package -DskipTests
FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --system --uid 10001 nilebank
COPY --from=build /app/target/nilebank-0.1.0.jar app.jar
USER 10001
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=4 CMD curl --fail --silent http://localhost:8080/api/v1/health || exit 1
ENTRYPOINT ["java","-jar","app.jar"]

