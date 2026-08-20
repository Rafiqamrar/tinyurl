FROM maven:3.9-eclipse-temurin-21
WORKDIR /app
RUN mvn package -DskipTests
CMD ["java", "-jar", "target/tinyurl-0.1.0.jar"]