# Use official OpenJDK runtime as a parent image
FROM eclipse-temurin:17-jdk-jammy as builder

# Set the working directory
WORKDIR /app

# Install build dependencies
RUN apt-get update && \
    apt-get install -y git npm && \
    npm install -g yarn

# Build the project (as per https://www.traccar.org/build/)
RUN ./gradlew build

# Create final runtime image
FROM eclipse-temurin:17-jre-jammy

# Set the working directory
WORKDIR /opt/traccar

# Copy built artifacts from builder stage
COPY --from=builder /app/build/libs/*.war ./traccar-web.war
COPY --from=builder /app/traccar.xml ./
COPY --from=builder /app/schema ./schema
COPY --from=builder /app/templates ./templates

# Create data directory
RUN mkdir -p /opt/traccar/data

# Expose default Traccar port
EXPOSE 8082

# Set environment variables
ENV JAVA_OPTS="-Xms512m"

# Run Traccar
CMD ["java", "-jar", "traccar-web.war"]
