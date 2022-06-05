FROM gradle:jdk17 as builder
WORKDIR /home/gradle/project
COPY . ./
RUN \
  --mount=type=cache,target=/home/gradle/.gradle/cache \
  --mount=type=cache,target=/home/gradle/project/build/js/node_modules \
  gradle --no-daemon --parallel distTar

FROM eclipse-temurin:17-jre-alpine
RUN mkdir /dist
COPY --from=builder /home/gradle/project/build/distributions/crawl-roster-0.1.tar /dist
RUN tar -xf /dist/crawl-roster-0.1.tar -C /
EXPOSE 8080
ENTRYPOINT ["/crawl-roster-0.1/bin/crawl-roster"]