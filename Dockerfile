
# Build stage
FROM eclipse-temurin:21-jdk AS build

WORKDIR /app

COPY . .

RUN chmod +x gradlew
RUN ./gradlew installDist --no-daemon

# Runtime stage
FROM eclipse-temurin:21-jre

WORKDIR /app

COPY --from=build /app/build/install/flash-deal/ ./

EXPOSE 8080

CMD ["./bin/flash-deal"]
