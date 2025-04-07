FROM gradle:jdk21 AS builder
WORKDIR /home/gradle/project
COPY . ./
RUN \
  --mount=type=cache,target=/home/gradle/.gradle \
  gradle --no-daemon --parallel distTar

FROM eclipse-temurin:21-jre-alpine
RUN mkdir /dist
COPY --from=builder /home/gradle/project/build/distributions/crawl-roster-2025.4.1.tar /dist
RUN tar -xf /dist/crawl-roster-2025.4.1.tar -C /
RUN rm -rf /dist
EXPOSE 8080
ENTRYPOINT ["/crawl-roster-2025.4.1/bin/crawl-roster"]