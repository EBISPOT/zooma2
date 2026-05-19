FROM maven:3.9.9-eclipse-temurin-21 AS build

WORKDIR /workspace

COPY pom.xml .
COPY src ./src

RUN mvn -q -DskipTests package

FROM eclipse-temurin:21-jre-jammy

RUN groupadd --system --gid 1279 zooma \
    && useradd --system --uid 5533 --gid 1279 --home-dir /app --no-create-home zooma

WORKDIR /app

COPY --chown=5533:1279 --from=build /workspace/target/zooma2-1.0-SNAPSHOT.jar /app/zooma2.jar
COPY --chown=5533:1279 config.json /app/config.json

ENV ZOOMA2_DB_URL=jdbc:sqlite:/tmp/zooma.db

USER 5533:1279

EXPOSE 8090

ENTRYPOINT ["java", "-jar", "/app/zooma2.jar"]
