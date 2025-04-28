# Use OpenJDK 24 as the base image
FROM eclipse-temurin:21-jdk

# Set working directory
WORKDIR /app

# Install SBT
RUN apt-get update && \
    apt-get install -y curl && \
    curl -L -o sbt-1.9.9.deb https://repo.scala-sbt.org/scalasbt/debian/sbt-1.9.9.deb && \
    dpkg -i sbt-1.9.9.deb && \
    apt-get update && \
    apt-get install -y sbt && \
    rm sbt-1.9.9.deb && \
    apt-get clean

# Copy the project files
COPY . /app

# Build the application
RUN sbt clean stage

# Expose the port the app runs on
EXPOSE 9000

# Command to run the application
CMD ["target/universal/stage/bin/decodingus"]
