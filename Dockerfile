# Stage 1 — Build
FROM eclipse-temurin:17-jdk-alpine AS build
WORKDIR /app
COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN chmod +x mvnw && ./mvnw dependency:go-offline -q
COPY src src
RUN ./mvnw clean package -DskipTests -q

# Stage 2 — Run
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=build /app/target/logstream-0.0.1-SNAPSHOT.jar app.jar
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
  CMD wget -qO- http://localhost:8080/actuator/health || exit 1
ENTRYPOINT java -XX:+UseG1GC -XX:MaxGCPauseMillis=100 -XX:+UseStringDeduplication \
  -Xms256m -Xmx${JVM_MAX_HEAP:-512m} -jar app.jar
