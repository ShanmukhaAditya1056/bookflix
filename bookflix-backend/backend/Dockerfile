# syntax=docker/dockerfile:1
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY .mvn .mvn
COPY mvnw pom.xml ./
# Normalise line endings in case the repo was checked out on Windows with autocrlf.
RUN sed -i 's/\r$//' mvnw .mvn/wrapper/maven-wrapper.properties && chmod +x mvnw
RUN --mount=type=cache,target=/root/.m2 ./mvnw -q -B dependency:go-offline
COPY src src
RUN --mount=type=cache,target=/root/.m2 ./mvnw -q -B package -DskipTests && cp target/*.jar /app.jar

FROM eclipse-temurin:21-jre
RUN groupadd -r bookflix && useradd -r -g bookflix -u 10001 bookflix
WORKDIR /app
COPY --from=build /app.jar /app/app.jar
USER bookflix
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
