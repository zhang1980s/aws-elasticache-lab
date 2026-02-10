FROM amazoncorretto:17 AS builder

WORKDIR /app

# Install Maven
RUN yum install -y maven && yum clean all

# Copy pom.xml
COPY pom.xml .

# Download dependencies (cached layer)
RUN mvn dependency:go-offline -B

# Copy source code
COPY src src

# Build the application
RUN mvn package -DskipTests -B

FROM amazoncorretto:17

WORKDIR /app

# Install curl for health checks
RUN yum install -y curl && yum clean all

# Create non-root user
RUN groupadd -g 1000 appgroup && useradd -u 1000 -g appgroup appuser
USER appuser

# Copy the built JAR
COPY --from=builder /app/target/*.jar app.jar

# Expose application port
EXPOSE 8080

# JVM options for containers
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:+UseG1GC"

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
