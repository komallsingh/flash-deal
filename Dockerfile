FROM eclipse-temurin:21-jre

WORKDIR /app

COPY build/install/flash-deal/ ./

EXPOSE 8080

CMD ["./bin/flash-deal"]