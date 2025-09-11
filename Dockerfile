FROM openjdk:11-jdk

# Install make, redis-tools, and redis-server
RUN apt-get update && apt-get install -y make redis-tools redis-server

WORKDIR /app

COPY . .

RUN make

# No CMD here
