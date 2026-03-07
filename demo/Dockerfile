FROM eclipse-temurin:21-jdk-alpine AS build

WORKDIR /project
COPY . .
RUN ./gradlew :demo:bootJar --no-daemon -x test

FROM eclipse-temurin:21-jre-alpine

LABEL maintainer="dev@egoge.com"
LABEL description="AI-ATLAS demo application"

WORKDIR /app

COPY --from=build /project/demo/build/libs/demo-*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
