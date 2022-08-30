FROM maven:3.8.4-eclipse-temurin-17 as build
COPY src src
COPY pom.xml .
RUN mvn -Dmaven.artifact.threads=16  package

FROM eclipse-temurin:17.0.4.1_1-jre as final
COPY --from=build target/osu-irc-bridge-jar-with-dependencies.jar .
CMD java -jar osu-irc-bridge-jar-with-dependencies.jar