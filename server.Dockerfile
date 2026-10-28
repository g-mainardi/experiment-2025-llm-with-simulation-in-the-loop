# Stage 1: Build stage
FROM eclipse-temurin:21-jdk AS builder

WORKDIR /app

# Build files first: the dependency layer is reused until build.mill or the Mill version change
COPY mill .mill-version build.mill ./
RUN ./mill mcp_server.prepareOffline

COPY mcp_server ./mcp_server
RUN ./mill mcp_server.assembly

# Stage 2: Runtime stage
FROM eclipse-temurin:21-jre

WORKDIR /app

COPY --from=builder /app/out/mcp_server/assembly.dest/out.jar /app/mcp-server.jar

EXPOSE 8080

ENV JAVA_OPTS="-Dsun.java2d.opengl=false"

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/mcp-server.jar"]
