FROM gradle:8.11.1-jdk17 AS build
COPY --chown=gradle:gradle . /home/gradle/project
WORKDIR /home/gradle/project
USER gradle
RUN gradle --no-daemon installDist

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /home/gradle/project/build/install/bumpmap-api/ ./
USER 10001:10001
ENTRYPOINT ["/app/bin/bumpmap-api"]
