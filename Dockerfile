FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /build
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN ./mvnw -B -ntp dependency:go-offline
COPY src src
RUN ./mvnw -B -ntp package -DskipTests

FROM eclipse-temurin:21-jre-alpine
RUN apk add --no-cache curl jq
RUN addgroup -S sporty && adduser -S sporty -G sporty
WORKDIR /app
COPY --from=build --chown=sporty:sporty /build/target/sporty-street-0.0.1-SNAPSHOT.jar app.jar
USER sporty
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
