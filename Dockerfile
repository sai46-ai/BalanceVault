# 1) Build the React client.
FROM node:24-alpine3.24 AS client
WORKDIR /client
COPY client/package.json client/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY client/index.html client/vite.config.js ./
COPY client/src ./src
RUN npm run build && test -f dist/index.html

# 2) Build and test the Spring Boot jar with the client bundled as static resources.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml ./
RUN mvn -B --no-transfer-progress dependency:go-offline
COPY src ./src
COPY --from=client /client/dist ./src/main/resources/static
# `package` runs the unit tests; a failing test or a missing artifact fails the image build.
RUN mvn -B package && test -f target/balancevault.jar

# 3) Minimal runtime as a non-root user.
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY --from=build /build/target/balancevault.jar ./balancevault.jar
USER app
EXPOSE 8080
CMD ["java", "-jar", "/app/balancevault.jar"]
