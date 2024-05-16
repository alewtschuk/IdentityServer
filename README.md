# Identity Server

* Author: Alex Lewtschuk and Kai Sorrenson
* Class: CS455 [Distributed Systems] 

## Overview

This project consists of two main programs: IdServer and IdClient. IdServer sets up an RMI instance and initializes a registry so that IdClient can use remote method calls to perform actions on the server. This is essentially a modified simple implementation of the Kerberos protocol.

There are two branches in this repo. Each branch has different features. This branch is the original implementation. The second is modifed for the servers to elect a coordinator using a bully algorithm. The coordinator is the only server that intracts with the client connections. The system is also desined for the database be replicated to backup servers providing replication transparency. 

## Manifest

IdServer: implements an RMI server
IdClient: implements a client that will call remote methods on the server
IdAccount: has the utility methods and stores the information for the account objects

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
