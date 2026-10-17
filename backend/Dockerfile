FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY . .
RUN mvn -B -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/collector/target/collector-*.jar /app/app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-Xmx512m", "-jar", "/app/app.jar"]
