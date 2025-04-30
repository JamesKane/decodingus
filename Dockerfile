# Runtime image
FROM eclipse-temurin:21-jre-jammy

# Set working directory
WORKDIR /app

# Copy the pre-built application
COPY target/universal/stage /app

# Expose the port the app runs on
EXPOSE 9000

# Command to run the application
CMD ["bin/decodingus"]