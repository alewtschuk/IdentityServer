#!/bin/bash
# Starts one IdServer node inside its Docker container (see docker-compose.yml).
# Usage: ./run-server.sh [registryPort]
registryPort=${1:-5128}

# Each node has its own local Redis. It listens on all interfaces so that the
# other nodes can replicate from it when this node is the coordinator.
echo "Starting redis"
redis-server --protected-mode no --daemonize yes > /dev/null
until redis-cli ping > /dev/null 2>&1; do
    echo "Waiting for Redis to start..."
    sleep 1
done
echo "Redis started."

echo "Starting rmiregistry on port $registryPort"
export CLASSPATH=$(pwd):$CLASSPATH
rmiregistry $registryPort &
sleep 2

# java.rmi.server.hostname makes the RMI stub advertise this container's hostname
# (id-server-N), which is what the TLS certificate is issued for.
echo "Starting server"
# The DNS cache TTLs are lowered so a restarted container's new IP is picked up.
exec java -Djava.rmi.server.hostname=$(hostname) -Dsun.net.inetaddr.ttl=1 -Dsun.net.inetaddr.negative.ttl=1 -cp ".:lib/*" IdServer -s docker.server -n $registryPort -v
