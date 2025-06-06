# Use official OpenJDK runtime as a parent image
FROM eclipse-temurin:17-jdk-jammy as builder

WORKDIR /.app_platform_workspace

# Build the project (as per https://www.traccar.org/build/)
RUN ./gradlew build

# Create final runtime image
FROM eclipse-temurin:17-jre-jammy

# Set the working directory
WORKDIR /opt/traccar

# Copy built artifacts from builder stage
COPY --from=builder /.app_platform_workspace/build/libs/*.war ./traccar-web.war
COPY --from=builder /.app_platform_workspace/traccar.xml ./
COPY --from=builder /.app_platform_workspace/schema ./schema
COPY --from=builder /.app_platform_workspace/templates ./templates

# Create data directory
RUN mkdir -p /opt/traccar/data

# Expose default Traccar port
EXPOSE 8082

# Set environment variables
ENV JAVA_OPTS="-Xms512m"

# Run Traccar
CMD ["java", "-jar", "traccar-web.war"]
