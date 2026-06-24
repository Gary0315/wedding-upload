# syntax=docker/dockerfile:1.7

FROM eclipse-temurin:17-jdk AS build
WORKDIR /workspace

COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -q -B dependency:go-offline

COPY src/ src/
RUN ./mvnw -q -B clean package -DskipTests \
 && mv target/*.jar app.jar

FROM eclipse-temurin:17-jre
WORKDIR /app

RUN useradd -r -u 1001 spring
USER spring

COPY --from=build /workspace/app.jar app.jar

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseSerialGC -XX:TieredStopAtLevel=1"
ENV PORT=8080
EXPOSE 8080

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
