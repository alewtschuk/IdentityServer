#!/bin/bash
# for kai: sed -i 's/\r$//' run-server.sh

# Check the first argument provided to the script
if [ "$1" == "numport" ]; then
    make
    echo
    echo
    # Commands to run if the input is "option1"
    echo "Starting server with optional specified port command -n"
    registryPort=$2

    echo
    echo "Killing existing rmiregistry"
    killall -9 rmiregistry >& /dev/null
    pkill rmiregistry

    echo
    echo "Starting new rmiregistry from $(pwd)"
    export CLASSPATH=$(pwd):$CLASSPATH

    rmiregistry $registryPort &

    # wait for rmiregistry to start
    sleep 2

    echo
    echo "Starting server"
    echo
    echo
    java -cp .:lib/* IdServer -n $registryPort
    echo

elif [ "$1" == "verbose" ]; then
    make
    echo
    echo
    echo "Starting server with optional verbose command -v"
    registryPort=5128

    echo
    echo "Killing existing rmiregistry"
    killall -9 rmiregistry >& /dev/null
    pkill rmiregistry

    echo
    echo "Starting new rmiregistry from $(pwd)"
    export CLASSPATH=$(pwd):$CLASSPATH

    rmiregistry $registryPort &

    # wait for rmiregistry to start
    sleep 2

    echo
    echo "Starting server"
    echo
    echo
    java -cp .:lib/* IdServer $registryPort -v
    echo

elif [ "$1" == "both" ]; then
    make
    echo
    echo
    echo "Starting server with ALL optional commands"
    echo "Enter the port for the server: "
    read port
    registryPort=$2

    echo
    echo "Killing existing rmiregistry"
    killall -9 rmiregistry >& /dev/null
    pkill rmiregistry

    echo
    echo "Starting new rmiregistry from $(pwd)"
    export CLASSPATH=$(pwd):$CLASSPATH

    rmiregistry $registryPort &

    # wait for rmiregistry to start
    sleep 2

    echo
    echo "Starting server"
    echo
    echo
    java -cp .:lib/* IdServer -n $registryPort -v
    echo

elif [ "$1" == "onyx" ]; then
    echo
    echo
    echo "Building server(s) for onyx"
    echo "All optional commands enabled"
    echo "Enter the port for the server: "
    registryPort=$2

    make
    echo
    echo "Initializing redis database"
    echo
    echo "Killing existing rmiregistry"
    killall -9 rmiregistry >& /dev/null
    pkill rmiregistry

    echo
    echo "Starting new rmiregistry from $(pwd)"
    export CLASSPATH=$(pwd):$CLASSPATH
    
    rmiregistry $registryPort &
    sleep 2
    echo
    echo "Starting server"
    echo
    echo
    java -cp .:lib/* IdServer -s onyx.server -n $registryPort -v
    echo


elif [ "$1" == "docker" ]; then
    echo
    echo
    echo "Building server(s) for docker"
    echo "All optional commands enabled"
    registryPort=$2

    make
    echo
    echo "Initializing redis database"
    echo
    echo "Killing existing rmiregistry"
    killall -9 rmiregistry >& /dev/null
    pkill rmiregistry

    echo
    echo "Starting new rmiregistry from $(pwd)"
    export CLASSPATH=$(pwd):$CLASSPATH
    
    rmiregistry $registryPort &
    sleep 2
    echo
    echo "Starting server"
    echo
    echo
    java -cp .:lib/* IdServer -s docker.server -n $registryPort -v
    echo


else
    # Default commands to run if no recognized input is provided
    make
    echo
    echo
    echo "Starting server with default commands."
    registryPort=5128

    echo
    echo "Killing existing rmiregistry"
    killall -9 rmiregistry >& /dev/null
    pkill rmiregistry

    echo
    echo "Starting new rmiregistry from $(pwd)"
    export CLASSPATH=$(pwd):$CLASSPATH

    rmiregistry $registryPort &

    # wait for rmiregistry to start
    sleep 2

    echo
    echo "Starting server"
    echo
    echo
    java -cp .:lib/* IdServer -s onyx.server $registryPort
    echo
fi