# el jar se construye en local (./gradlew bootJar) y se copia al contexto como app.jar
FROM eclipse-temurin:25-jre
WORKDIR /app
COPY app.jar /app/app.jar
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
