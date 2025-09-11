# Identity Server

* Author: Alex Lewtschuk and Kai Sorensen

---

## Running the Demo (Docker Recommended)

This project is configured to run as a multi-server cluster using Docker and Docker Compose. This is the recommended way to run the project as it encapsulates the entire environment (Java, Redis, and networking).

### 1. Run the Server Cluster

**Prerequisites:**
* Docker
* Docker Compose

Launch the 3-node server cluster and the Redis database with a single command:

```bash
docker-compose up --build
```

This will start three `IdServer` instances. The servers will communicate with each other, elect a coordinator, and handle client requests.

To see the log output of a specific server and observe the election process or heartbeats, you can run (in a separate terminal):

```bash
docker-compose logs -f id-server-1
# Or id-server-2, id-server-3
```

### 2. Run the Client

To run the client, use the `docker-compose run` command in a new terminal. This will start a new container on the same network as the servers, allowing it to connect.

Here are some example commands:

*   **Create an account:**

    ```bash
    docker-compose run client java -cp .:lib/* IdClient -s docker.server -c myuser -p mypassword "My Real Name"
    ```

*   **Look up a user:**

    ```bash
    docker-compose run client java -cp .:lib/* IdClient -s docker.server -l myuser
    ```

*   **Get a list of all users:**

    ```bash
    docker-compose run client java -cp .:lib/* IdClient -s docker.server -g users
    ```

*   **Delete an account:**

    ```bash
    docker-compose run client java -cp .:lib/* IdClient -s docker.server -d myuser -p mypassword
    ```

---

## Project Overview

This project consists of two main programs: `IdServer` and `IdClient`. `IdServer` sets up an RMI instance and initializes a registry so that `IdClient` can use remote method calls to perform actions on the server. This is essentially a modified simple implementation of the Kerberos protocol.

This version implements a multi-server environment where servers elect a coordinator using a bully algorithm. The coordinator is the only server that interacts with the client connections. The system is also designed for the database to be replicated to backup servers, providing replication transparency.