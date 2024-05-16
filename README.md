# Project 3 Id Server Part 3

* Author: Alex Lewtschuk and  Kai Sorensen
* Class: CS455 [Distributed Systems] 

## Overview

PROJECT VIDEO: https://youtu.be/Ljfhd653CXw

This version implements the election algorithm, coordinator and replication.

## Manifest

IdServer: implements an RMI server
IdClient: implements a client that will call remote methods on the server
IdAccount: has the utility methods and stores the information for the account objects
ServerInterface: interface for the redis server
onyx.server: server input files with ip addresses

## Building the project

A makefile is included in the directory. It will compile the necessary files, preparing them for execution.
There are several ways to use our makefile.

Execute the makefile from the project directory as follows:

To compile all files use:
```
make
```

NOTE: THE FOLLOWING METHOD WILL KILL THE SERVER IF RUNNING!!!

To clean and reset the project(including the redis database) from the project directory, use:

```
make clean
```

To clear the redis database use:

```
make resetdb
```

## Features and usage

To use the program follow the following steps:

Start by launching the server. You can use the sh script that we designed. There are several ways to run the script.

First please ensure you allow the script execute permissions by running `chmod +x run-server.sh`.

To run the server in default mode (no optional commands used) use:
```
./run-server.sh
```

To run the server and specify the port number use:
```
./run-server.sh numport
```

To run the server with detailed verbose output use:
```
./run-server.sh verbose
```

To run the server with both optional commands enabled use:
```
./run-server.sh both
```

To run the server with the setup for Onyx use:
```
./run-server.sh onyx 5128
```

NOTE: Our program is configured to take .server files as input. Each of those files contains a list of IP addresses that are parsed by the program and iterated through till a connection is sucessfull. As the IPs in the provided onyx.server are configured for Boise State's Onyx system if you wish to run multiple server instances it is recommended to provide your own list of IP addresses of machines running server insances.

### Known Bugs

Cannot be run with localhost anyore due to how server handles setup.

However, the code is flexible and will work on any network with unique IP addresses, virtual or not. 
