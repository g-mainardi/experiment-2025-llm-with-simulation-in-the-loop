# Stage 1: Build stage
FROM nightscape/scala-mill:eclipse-temurin-21.0.7_6-jdk-jammy_0.12.14 AS builder

# Set working directory
WORKDIR /app

# Copy mill configuration and build files
COPY build.mill .
COPY .mill-version .

# Copy source code
COPY mcp_server ./mcp_server

# Build the assembly/uber jar
RUN mill mcp_server.assembly

# Stage 2: Runtime stage
FROM eclipse-temurin:21-jre

# Set working directory
WORKDIR /app

# Copy the built jar from builder stage
COPY --from=builder /app/out/mcp_server/assembly.dest/out.jar /app/mcp-server.jar

# Expose port 8080
EXPOSE 8080

# Set environment variables
ENV JAVA_OPTS="-Dsun.java2d.opengl=false"

# Run the server
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/mcp-server.jar"]

