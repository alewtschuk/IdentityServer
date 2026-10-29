# Identity Server

* Author: Alex Lewtschuk and Kai Sorrenson

## Overview

Identity Server is a collection of two programs: `IdServer` and `IdClient`. 

The `IdServer` program is the server side implementation of the protocol. It sets up the RMI instance and initializes the registry so that the client can use remote method calls to perform actions and change state on the server. This is a simplified RMI-based identity server, in the spirit of systems like Kerberos and LDAP.

The `IdClient` program is the client side implementation that connects to an available server and allows the user to interface with the protocol via the command line.

- IdServer: implements an RMI server
- IdClient: implements a client that will call remote methods on the server
- IdAccount: has the utility methods and stores the information for the account objects

If another server or servers join the cluster later on the newly connected server will automatically subordinate itself to the existing coordinator, even if the new server's PID is higher than the current coordinator. This method of peer connection allows the system to dynamically scale the number of servers while avoiding unnecessary coordinator selection and election overhead and under conditions where the cluster coordinator is fully functional.

The coordinator is the only server that can interact and fulfill the commands sent by the client but, the coordinator is not always the server that the client directly connects to. Upon client connection to a server the client will request the server type from the connected instance. In the case that the client connects to a non-coordinator replica server the replica will tell the client who the coordinator server is and the client will then re-connect to the coordinator and save the coordinator address so all further client calls bypass redirection and connect to the current coordinator directly. This functionally serves as a once-per-coordinator redirection for the client. The client will also auto-update the saved coordinator value upon coordinator connection failure which allows for accurate client redirect after elections. The system is also designed for the database, redis in this instance, to be replicated to the non-coordinator servers, providing not just replication transparency but, eventual consistency and failover data protection.

The original 2024 design document for the election and replication model is in [`Whitepaper.pdf`](Whitepaper.pdf) (written for a university course project).

---

## Running the Demo

The demo is designed to run as a multi-server cluster using Docker and Docker Compose. This is the recommended way to run the project as it encapsulates the entire environment (Java, Redis, and networking).

### 1. Run the Server Cluster

**Requirements:**
* JDK (only needed if running make build on host)
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

*   **Look up a user by UUID:**

    With wrapper script:
    ```bash
    ./idclient -r <uuid>
    ```
    With `docker compose exec`:
    ```bash
    docker compose exec client java -cp '.:lib/*' IdClient -s docker.server -r <uuid>
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

*   **Change user login name:**

    With wrapper script:
    ```bash
    ./idclient -m myuser newname -p mypassword
    ```
    With `docker compose exec`:
    ```bash
    docker compose exec client java -cp '.:lib/*' IdClient -s docker.server -m myuser newname -p mypassword
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

The necessary self-signed TLS information is generated by `gen-certs.sh`, per image so no keys are uploaded to the repo, and since each container shares the same image only one certificate is needed for the server instances `id-server-1` to `id-server-3`. To regenerate the certificate (for example, after adding a server to `docker.server` and `docker-compose.yml`) you can re-initialize the image itself and restart the cluster using:
```bash
make restart
```
---

## Demo limitations

This is a purely distributed systems demo and is not designed to be a fully hardened identity service. 

The following choices/limitations are deliberate:
* **Hardcoded keystore password** (`123456` in `gen-certs.sh`, `IdServer` and `IdClient`). Fine for the demo as keystores are generated per image and never leave the created Docker network.
* **SHA-512 password hashes.** All password hashes in the demo are unsalted. In a production system all passwords would be salted and use an alternate hash function like bcrypt or Argon2. This is demo acceptable as no real functional credentials are stored.
* **Non-authenticated Redis.** Redis instances for the cluster lack authentication. In demo no Redis ports are published outside the created Docker network making this inconsequential. In a production system authentication would be required.
* **Plain text heartbeats.** All server-to-server heartbeats on the network are in plain text. Functional for the demo as only the cluster's containers are on the network. All client request traffic however uses TLS.
* **Plain text password echo on client.** The demo client echoes all passwords in plaintext. In production this would not be done however, for the demo's purpose it serves to show the client request flow transparently.
* **Asynchronous replication can lose writes.** If a write occurs that a coordinator has acknowledged but not yet replicated that write is lost if the coordinator dies before replication can occur. This is a known trade off of the design described in the whitepaper.

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
Fully refresh the cluster from a clean slate, rebuild the image, and drop into monitoring:
```bash
make refresh
```
Clean the project removing any compiled classes, taking down the server cluster, and removing the image:
```bash
make clean
```