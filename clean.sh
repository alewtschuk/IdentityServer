#!/bin/bash

pid=$(ps -a | grep -i "IdServer" | awk '{print $1}' | head -n 1) ; \
if [ ! -z "$pid" ]; then \
    kill $pid ; \
else \
    echo "IdServer process not found" ; \
fi
redis-cli flushall

# next, kill redis server
pid=$(pgrep redis-server)
# Check if any PIDs were found
if [ -z "$pid" ]; then
    echo "No Redis server to stop."
else
    echo "killing redis server"
    sudo kill $pid
fi