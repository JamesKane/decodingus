# =============================================================================
# DecodingUs Application Dockerfile
# =============================================================================
# Build: sbt stage && docker build -t decodingus .
# Run:   docker run -p 9000:9000 --env-file .env decodingus
# =============================================================================

FROM eclipse-temurin:21-jre-jammy

# Labels for container metadata
LABEL org.opencontainers.image.title="DecodingUs"
LABEL org.opencontainers.image.description="Collaborative platform for genetic genealogy and population research"
LABEL org.opencontainers.image.source="https://github.com/decodingus/decodingus"

# Install curl for healthchecks
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# Create non-root user for security
RUN groupadd -r decodingus && useradd -r -g decodingus decodingus

# Set working directory
WORKDIR /app

# Copy the pre-built application
COPY --chown=decodingus:decodingus target/universal/stage /app

# Make scripts executable
RUN chmod +x /app/bin/decodingus

# Switch to non-root user
USER decodingus

# Expose the port the app runs on
EXPOSE 9000

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD curl -f http://localhost:9000/health || exit 1

# JVM tuning for container environments
ENV JAVA_OPTS="-XX:+UseContainerSupport \
    -XX:MaxRAMPercentage=75.0 \
    -XX:+UseG1GC \
    -XX:+ExitOnOutOfMemoryError \
    -Djava.security.egd=file:/dev/./urandom"

# Command to run the application
CMD ["bin/decodingus"]