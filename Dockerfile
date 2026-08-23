# Use an official Maven image with JDK 22
FROM eclipse-temurin:22-jdk AS build

# Set work directory
WORKDIR /app

# Copy Maven wrapper files
COPY mvnw mvnw
COPY .mvn .mvn

# Copy Maven project files
COPY pom.xml .
COPY src src

# Make sure mvnw is executable
RUN chmod +x mvnw

# Build the app
RUN ./mvnw clean package -DskipTests

# ----------- Second stage to run the app (optional) -----------

# Use a slim Java 22 runtime image
FROM eclipse-temurin:22-jre

# Set work directory
WORKDIR /app

# Copy the jar from the build stage
COPY --from=build /app/target/coop-0.0.1-SNAPSHOT.jar app.jar

# Expose port (adjust if your app runs on a specific port)
EXPOSE 8080

# Run the application
ENTRYPOINT ["java", "-jar", "app.jar"]
