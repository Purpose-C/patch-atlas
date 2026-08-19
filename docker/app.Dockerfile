FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY mvnw pom.xml ./
COPY .mvn .mvn
COPY src src
RUN chmod +x mvnw && ./mvnw -B -DskipTests package \
    && mv target/patch-atlas-0.1.0-SNAPSHOT.jar /tmp/app.jar

FROM eclipse-temurin:21-jre
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /tmp/app.jar app.jar
EXPOSE 8080
HEALTHCHECK --interval=5s --timeout=5s --start-period=40s --retries=24 \
    CMD curl -sf http://127.0.0.1:8080/api/v1/health || exit 1
ENTRYPOINT ["java", "-jar", "app.jar"]
