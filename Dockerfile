# ---- Stage 1: build the executable jar ----
# Uses a Maven image with JDK 17 so we don't need Maven/JDK installed to build.
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app

# Copy pom first and pre-download dependencies so this layer is cached
# (rebuilds are fast when only source code changes).
COPY pom.xml .
RUN mvn -q dependency:go-offline

# Now copy the source and build. Skip tests here — they need Docker (Testcontainers),
# which isn't available inside an image build. Tests run in CI / locally via `mvn verify`.
COPY src ./src
RUN mvn -q clean package -DskipTests

# ---- Stage 2: tiny runtime image ----
# Only a JRE + the jar. The heavy build tools from stage 1 are left behind.
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/target/rate-limiter-service-*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
