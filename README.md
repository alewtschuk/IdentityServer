# Identity Server

* Author(s): Alex Lewtschuk and Kai Sorensen

---

## Overview

Identity Server is a collection of two programs: `IdServer` and `IdClient`. 

The `IdServer` program is the server side implementation of the protocol. It sets up the RMI instance and initializes the registry so that the client can use remote method calls to perform actions and change state on the server. This is essentially a modified and stripped down simple implementation of a Kerberos inspired protocol.

The `IdClient` program is the client side implementation that connects to an available server and allows the user to interface with the protocol via the command line.

This demo implements a multi-server (three) environment where, upon cluster start-up, server instances elect a coordinator using a bully algorithm that dictates seniority based on a randomly assigned "PID" an individual server generates at each start up. An election is triggered under two circumstances:
1. The previously mentioned startup.
2. The current coordinator goes down and a new coordinator is needed.

If another server or servers join the cluster later on the newly connected server will automatically subordinate itself to the existing coordinator, even if the new server's PID is higher than the current coordinator. This method of peer connection allows the system to dynamically scale the number of servers while avoiding unnecesary coordinator selection and election overhead and under conditions where the cluster coordinator is fully functional.

The coordinator is the only server that can interact and fulfill the commands sent by the client but, the coordinator is not always the server that the client directly connects to. Upon client connection to a server the client will request the server type from the connected instance. In the case that the client connects to a non-coordinator replica server the replica will tell the client who the coordinator server is and the client will then re-connect to the coordinator and save the coordinator address so all further client calls bypass redirection and connect to the current coordinator directly. This functionally serves as a once-per-coordinator redirection for the client. The cleint will also auto-update the saved coordinator value upon coordinator connection failure which allows for accurate client redirect after elections. The system is also designed for the database, redis in this instance, to be replicated to the non-coordinator servers, providing not just replication transparency but, eventual consistency and failover data protection. 

---

## Running the Demo

The demo is designed to run as a multi-server cluster using Docker and Docker Compose. This is the recommended way to run the project as it encapsulates the entire environment (Java, Redis, and networking).

### 1. Run the Server Cluster

**Requirements:**
* JDK (only needed on host for make build)
* Make
* Docker
* Docker Compose

Launch the 3-node server cluster with a single command:

```bash
make up
```

This will build the image that the instances use, and start three `IdServer` containers. The servers will communicate with each other, elect a coordinator, and handle client requests.

To see the log output of all servers and observe the election process, state changes, and replication, you can run:

```bash
make logs
```
If you want to see the output of a specific server you can also run:

```bash
docker compose logs -f id-server-1
# Or id-server-2, id-server-3
```

### 2. Run the Client

To run the client, you can use `docker compose exec`, or the wrapper script `./idclient` in a new terminal. This runs the command inside the already running `client` container, which is on the same network as the servers.

Here are some example commands:

*   **Create an account:**
    With wrapper script:
    ```bash
    ./idclient -c myuser -p mypassword "My Real Name"
    ```
    With `docker compose exec`:
    ```bash
    docker compose exec client java -cp '.:lib/*' IdClient -s docker.server -c myuser -p mypassword "My Real Name"
    ```

*   **Look up a user:**
    With wrapper script:
    ```bash
    ./idclient -l myuser
    ```
    With `docker compose exec`:
    ```bash
    docker compose exec client java -cp '.:lib/*' IdClient -s docker.server -l myuser
    ```

*   **Get a list of all users:**
    With wrapper script:
    ```bash
    ./idclient -g users
    ```
    With `docker compose exec`:
    ```bash
    docker compose exec client java -cp '.:lib/*' IdClient -s docker.server -g users
    ```

*   **Delete an account:**
    With wrapper script:
    ```bash
    ./idclient -d myuser -p mypassword
    ```
    With `docker compose exec`:
    ```bash
    docker compose exec client java -cp '.:lib/*' IdClient -s docker.server -d myuser -p mypassword
    ```

## Running automated behavior testing
### Client interaction tests
You can run the full test script showing all client behavior using:
```bash
make test
```
### Server failover and election testing
```bash
make failover
```

## TLS Certificates

The client talks to the servers over RMI with SSL. Normally that would require the following files: `Server_Keystore`, `server.cer` and `Client_Truststore`. These are not included in the repo and are not directly needed for the demo as the docker image handles this internally.

The necessary self-signed TLS information is generated by `gen-certs.sh`, per image so no keys are uploaded to the repo, and since each container shares the same image only one certificate is needed for the server instances `id-server-1` to `id-server-3`. To regenerate the certificate (for example, after adding a server to `docker.server` and `docker compose.yml`) you can re-initialize the image itself and restart the cluster using:
```bash
make restart
```
---

## All make commands
Build the program by compiling source files: 
```bash
make build
```
Build the container image and start server cluster in background:
```bash
make up
```
Follow logs from all servers:
```bash
make logs
```
Build container image, start server cluster, and drop into log monitoring after start:
```bash
make monitor 
```
Run the test suite to show cluster and client behavior:
```bash
make test
```
Run the failover test (stops coordinator server and checks if a new coordinator is elected):
```bash
make failover
```
Stop the cluster, and remove the containers and their data:
```bash
make down
```
Restart the server cluster by cleanly taking down the running containers, rebuilding the image, and bringing them back up:
```bash
make restart
```
Restart the server cluster and drop into monitoring immediately upon start:
```bash
make m-restart
```
Clean the project removing any compiled classes, taking down the server cluster, and removing the image:
```bash
make clean
```