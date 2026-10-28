import java.util.UUID;

import javax.rmi.ssl.SslRMIClientSocketFactory;
import javax.rmi.ssl.SslRMIServerSocketFactory;

import java.rmi.server.RMIClientSocketFactory;
import java.rmi.server.RMIServerSocketFactory;

import org.apache.commons.cli.*;

import java.io.File;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.rmi.NotBoundException;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.regex.Pattern;
import java.util.Map;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.Random;
import java.util.Scanner;
import java.util.concurrent.ConcurrentLinkedQueue;

import redis.clients.jedis.*;

public class IdServer implements ServerInterface {

    // internal server variables
    private static boolean verbosity = false;
    static int port = 5128;
    static int redisPort = 6379;
    static final String RMI_NAME = "IdServer"; // name the server is bound under in the RMI registry
    JedisPool pool = new JedisPool("localhost", redisPort);

    // server coordination variables
    Object lock = new Object();
    Object electionLock = new Object(); // guards state/coordinator transitions
    // random "pid" used by the bully algorithm, assigned on startup (see whitepaper)
    private volatile int serverNum = new Random().nextInt(10000) + 3;
    private volatile int state;
    private volatile int coordinatorPid; // pid of the current coordinator, 0 if unknown
    private int electionTicks = 0; // heartbeats spent in the election state
    private int leaderlessTicks = 0; // heartbeats spent as a replica with no known coordinator
    private int deferredTo = 0; // highest electing pid this server has stood down for

    private static String serverFile;
    private static ArrayList<String> serverHosts = new ArrayList<String>();

    private int heartrate = 2000;
    private static final int MAX_PEER_FAILURES = 5; // runtime failures before a peer link is abandoned
    private int serverPort = 5126;

    private ServerSocket servSock;
    private volatile Inet4Address coordinatorAddr;
    private String myAddress;
    private String myHostName;

    // writes the coordinator is waiting to see on every replica (eventual consistency)
    private final ConcurrentLinkedQueue<PendingWrite> pendingWrites = new ConcurrentLinkedQueue<PendingWrite>();
    private volatile boolean replicaSynced = false; // replica: initial sync with coordinator reported

    private final int election = 0;
    private final int replica = 1;
    private final int coordinator = 2;

    final static String RESET = "\u001B[0m";
    final static String ERRORC = "\u001B[31m";
    final static String COMMANDC = "\u001B[32m";
    final static String REPLICAC = "\u001B[33m";
    final static String ELECTIONC = "\u001B[34m";
    final static String COORDINATORC = "\u001B[35m";
    final static String ACCOUNTC = "\u001B[36m";
    final static String CLIENTC = "\u001B[1;37m"; // bold white

    // Log flags: [SYSTEM] generic server work, [ELECTION] bully algorithm,
    // [COORDINATOR]/[REPLICA] role specific work, [CLIENT] client RMI calls
    final static String SYSTEMFLAG = "[" + COMMANDC + "SYSTEM" + RESET + "]";
    final static String ELECTIONFLAG = "[" + ELECTIONC + "ELECTION" + RESET + "]";
    final static String COORDINATORFLAG = "[" + COORDINATORC + "COORDINATOR" + RESET + "]";
    final static String REPLICAFLAG = "[" + REPLICAC + "REPLICA" + RESET + "]";
    final static String ERRORFLAG = "[" + ERRORC + "ERROR" + RESET + "]";

    /**
     * CONSTRUCTOR
     * The constructor establishes the server's bare running state, initiates
     * connections to other servers, and opens communication to clients.
     * The constructor needs a filepath that contains a list of server IPs.
     * 
     * @param serverFile
     */
    public IdServer(String serverFile) throws RemoteException, IOException {
        processServerFile(serverFile);
        try (Jedis jedis = pool.getResource()) {
            jedis.configSet("protected-mode", "no");
        }
        Runtime.getRuntime().addShutdownHook(new DeathToServer());
        System.out.println(SYSTEMFLAG + " Server established with \"PID\": " + serverNum);

        // a new server starts as a replica and subordinates itself to an established
        // coordinator. If none is heard from, the heartbeat starts an election.
        this.state = replica;
        this.coordinatorPid = 0;

        // handling all listening and sending threads
        this.servSock = new ServerSocket(serverPort);
        this.myAddress = InetAddress.getLocalHost().getHostAddress();
        this.myHostName = InetAddress.getLocalHost().getHostName();
        if (verbosity)
            System.out.println(SYSTEMFLAG + " Server address is " + myAddress);
        setupServerConnections();
        new ReplicationListener().start();

        // this lock is used for the heartbeat and delays
        // all ObjectOutputStream writes. Each heartbeat also advances the election.
        Timer timer = new Timer();
        TimerTask tt = new TimerTask() {
            public void run() {
                try {
                    electionHeartbeat();
                } catch (Exception e) {
                    e.printStackTrace(); // keep the heartbeat alive
                }
                synchronized (lock) {
                    lock.notifyAll();
                }
            }
        };
        timer.scheduleAtFixedRate(tt, 0, heartrate);

        // checks replication progress more often than the heartbeat so the logs show
        // when replicas catch up with the coordinator
        timer.scheduleAtFixedRate(new TimerTask() {
            public void run() {
                try {
                    checkReplication();
                } catch (Exception e) {
                    e.printStackTrace(); // keep the timer alive
                }
            }
        }, heartrate, heartrate / 4);
    }

    /**
     * Establishes connections to all other servers. For every pair of servers
     * exactly one side dials: a server dials the servers listed before it in the
     * server file and accepts connections from the servers listed after it.
     */
    public void setupServerConnections() {
        int me = -1;
        for (int i = 0; i < serverHosts.size(); i++) {
            if (isMe(serverHosts.get(i)))
                me = i;
        }
        if (me == -1) {
            System.out.println(ERRORFLAG + " This server (" + myHostName + ") is not listed in " + serverFile);
            System.exit(1);
        }

        for (int i = 0; i < serverHosts.size(); i++) {
            if (i == me)
                continue;
            ServerListen listener = new ServerListen(serverHosts.get(i), i < me);
            listener.start();
        }
    }

    /**
     * Checks whether a host from the server file (hostname or IP) is this server
     */
    private boolean isMe(String host) {
        if (host.equalsIgnoreCase(myHostName) || host.equals(myAddress))
            return true;
        try {
            return InetAddress.getByName(host).getHostAddress().equals(myAddress);
        } catch (IOException e) {
            return false; // peer not resolvable yet, so it is not us
        }
    }

    ////////////////////////
    // BULLY ELECTION
    ////////////////////////

    /**
     * Called once per heartbeat. A server that has been in an election for two
     * heartbeats without hearing from a higher pid becomes the coordinator. A
     * replica that has not heard from any coordinator for four heartbeats starts an
     * election. Four is long enough for a joining server to be reached by the
     * others and hear an established coordinator first.
     */
    private void electionHeartbeat() {
        synchronized (electionLock) {
            if (state == election) {
                if (++electionTicks >= 2)
                    becomeCoord();
            } else if (state == replica && coordinatorAddr == null) {
                if (++leaderlessTicks >= 4)
                    startElection();
            }
        }
    }

    private void startElection() {
        synchronized (electionLock) {
            if (verbosity)
                System.out.println(ELECTIONFLAG + " Election triggered! (" + serverNum + ")");
            state = election;
            electionTicks = 0;
            deferredTo = 0;
            coordinatorPid = 0;
            coordinatorAddr = null;
        }
    }

    private void becomeCoord() {
        synchronized (electionLock) {
            state = coordinator;
            coordinatorPid = serverNum;
            try {
                coordinatorAddr = (Inet4Address) InetAddress.getByName(myAddress);
            } catch (IOException e) {
                e.printStackTrace();
            }
            try (Jedis jedis = pool.getResource()) {
                jedis.replicaofNoOne();
            }
            pendingWrites.clear();
            System.out.println(COORDINATORFLAG + " COORDINATOR SERVER CHOSEN with PID = " + serverNum);
            if (verbosity)
                System.out.println(COORDINATORFLAG + " Coordinator redis is now independent and source of truth");
        }
    }

    /**
     * Handles a coordinator announcement. Any server that is not the coordinator
     * subordinates itself, even if its pid is larger (whitepaper). If two servers
     * both claim coordinator, the lower pid steps down.
     */
    private void followCoordinator(int coordinatorNum, String coordinatorHost) {
        synchronized (electionLock) {
            if (state == coordinator && coordinatorNum < serverNum)
                return;
            // a coordinator is identified by its pid and its address: after a pid collision two
            // coordinators can have shared a pid, so a matching pid alone is not enough
            boolean newCoordinator = coordinatorPid != coordinatorNum || coordinatorAddr == null
                    || !coordinatorAddr.getHostAddress().equals(coordinatorHost);
            state = replica;
            leaderlessTicks = 0;
            deferredTo = 0;
            coordinatorPid = coordinatorNum;
            if (!newCoordinator)
                return;
            replicaSynced = false;
            try {
                coordinatorAddr = (Inet4Address) InetAddress.getByName(coordinatorHost);
            } catch (IOException e) {
                e.printStackTrace();
                return;
            }
            System.out.println(REPLICAFLAG + " Following coordinator " + coordinatorNum + " at " + coordinatorHost);
            try (Jedis jedis = pool.getResource()) {
                jedis.replicaof(coordinatorHost, redisPort);
            }
            if (verbosity)
                System.out.println(REPLICAFLAG + " Redis is now a replica of " + coordinatorHost + ":" + redisPort);
        }
    }

    /**
     * Handles an election message: a higher pid makes this server back down, a
     * lower pid makes a leaderless server start its own election.
     */
    private void handleElection(int fromNum) {
        synchronized (electionLock) {
            if (fromNum > serverNum) {
                if (fromNum > deferredTo && verbosity) {
                    // only an electing or leaderless server defers; an established coordinator
                    // or a replica that already has one keeps its role (whitepaper)
                    if (state == coordinator)
                        System.out.println(ELECTIONFLAG + " Peer electing with higher pid " + fromNum + ", staying coordinator from prior election (" + serverNum + ")");
                    else if (state == replica && coordinatorAddr != null)
                        System.out.println(ELECTIONFLAG + " Higher pid " + fromNum + " seen, still following coordinator " + coordinatorPid);
                    else
                        System.out.println(ELECTIONFLAG + " Higher pid found, standing down (" + serverNum + " < " + fromNum + ")");
                }
                deferredTo = Math.max(deferredTo, fromNum);
                if (state == election)
                    state = replica;
                if (state == replica && coordinatorAddr == null)
                    leaderlessTicks = 0; // the higher server is still electing
            } else if (state == replica && coordinatorAddr == null) {
                startElection();
            }
        }
    }

    /**
     * Handles a peer heartbeat carrying this server's own pid. Pids must be unique,
     * so the coordinator keeps its pid and any other server draws a new one. No
     * election is forced: a replica keeps following the current coordinator and a
     * server mid-election carries on with its new pid.
     * 
     * The one exception is two coordinators with the same pid (e.g. after a network
     * split heals). Neither can defer to the other, and following each other would
     * leave the cluster with no coordinator, so both re-roll and run a new election.
     * 
     * @return true if the heartbeat came from a coordinator sharing this coordinator's
     *         pid and must not be acted on
     */
    private boolean handlePidCollision(int peerNum, int peerState) {
        synchronized (electionLock) {
            if (peerNum != serverNum)
                return false;
            boolean bothCoordinators = state == coordinator && peerState == coordinator;
            if (state == coordinator && !bothCoordinators)
                return false;
            int oldNum = serverNum;
            while (serverNum == oldNum)
                serverNum = new Random().nextInt(10000) + 3;
            System.out.println(ELECTIONFLAG + " Pid " + oldNum + " already in use, re-rolled to " + serverNum);
            if (bothCoordinators)
                startElection();
            return bothCoordinators;
        }
    }

    /**
     * Handles a lost connection to another server
     */
    private void handleDisconnect(int lostNum) {
        synchronized (electionLock) {
            if (lostNum == coordinatorPid && state != coordinator)
                startElection();
        }
    }

    /**
     * Parses the file to pull IP addresses into a List<String>
     * 
     * @param serverFilePath
     */
    public void processServerFile(String serverFilePath) {
        File inputFile = new File(serverFilePath);
        try (Scanner scanner = new Scanner(inputFile)) {
            scanner.useDelimiter(",");
            while (scanner.hasNext()) {
                String next = scanner.next();
                serverHosts.add(next.trim());
            }

            if (serverHosts.isEmpty()) {
                System.out.println(ERRORFLAG + " No valid server addresses found in file.");
                System.exit(1);
            }

        } catch (Exception e) {
            System.out.println(ERRORFLAG + " Problem reading server file: " + e.getMessage());
            System.exit(1);
        }
    }

    ////////////////////////
    // REPLICATION LOGGING
    ////////////////////////

    /**
     * A client write the coordinator has applied, and the replication offset its
     * replicas must reach before they hold it too
     */
    private static class PendingWrite {
        final String description;
        final long offset;
        final long writtenAt = System.currentTimeMillis();

        PendingWrite(String description, long offset) {
            this.description = description;
            this.offset = offset;
        }
    }

    /**
     * Called by the coordinator after a client write. Replication itself is done
     * by redis asynchronously; this only records the write so checkReplication()
     * can log when every replica has it.
     */
    private void trackReplication(String description) {
        try (Jedis jedis = pool.getResource()) {
            Map<String, String> info = replicationInfo(jedis);
            pendingWrites.add(new PendingWrite(description, Long.parseLong(info.get("master_repl_offset"))));
        }
    }

    /**
     * Logs replication progress. On the coordinator: when every connected replica
     * has acknowledged a tracked write. On a replica: when its initial sync with
     * the coordinator has finished.
     */
    private void checkReplication() {
        if (state == coordinator && !pendingWrites.isEmpty()) {
            Map<String, String> info;
            try (Jedis jedis = pool.getResource()) {
                info = replicationInfo(jedis);
            }
            int replicas = Integer.parseInt(info.get("connected_slaves"));
            for (PendingWrite write = pendingWrites.peek(); write != null; write = pendingWrites.peek()) {
                if (replicas == 0) {
                    System.out.println(COORDINATORFLAG + " " + write.description
                            + " stored on coordinator only (no replicas connected)");
                    pendingWrites.poll();
                    continue;
                }
                int caughtUp = 0;
                for (int i = 0; i < replicas; i++) {
                    String replica = info.get("slave" + i); // ip=..,port=..,state=online,offset=..,lag=..
                    if (replica != null && replicaOffset(replica) >= write.offset)
                        caughtUp++;
                }
                if (caughtUp == replicas) {
                    System.out.println(COORDINATORFLAG + " " + write.description + " replicated to " + caughtUp
                            + "/" + replicas + " replicas");
                } else if (System.currentTimeMillis() - write.writtenAt > heartrate * 5) {
                    System.out.println(COORDINATORFLAG + ERRORFLAG + " " + write.description + " reached only " + caughtUp + "/"
                            + replicas + " replicas so far, no longer tracking it");
                } else {
                    break; // later writes cannot be caught up before this one
                }
                pendingWrites.poll();
            }
        } else if (state == replica && coordinatorAddr != null && !replicaSynced) {
            try (Jedis jedis = pool.getResource()) {
                Map<String, String> info = replicationInfo(jedis);
                if ("up".equals(info.get("master_link_status")) && "0".equals(info.get("master_sync_in_progress"))) {
                    replicaSynced = true;
                    System.out.println(REPLICAFLAG + " Synced with coordinator " + coordinatorPid + ", holding "
                            + jedis.dbSize() + " keys");
                }
            }
        }
    }

    /**
     * Parses redis "INFO replication" into key/value pairs
     */
    private static Map<String, String> replicationInfo(Jedis jedis) {
        Map<String, String> info = new HashMap<String, String>();
        for (String line : jedis.info("replication").split("\r?\n")) {
            int colon = line.indexOf(':');
            if (colon > 0)
                info.put(line.substring(0, colon), line.substring(colon + 1).trim());
        }
        return info;
    }

    /**
     * Pulls the acknowledged offset out of a "slaveN" INFO replication entry
     */
    private static long replicaOffset(String replica) {
        for (String field : replica.split(",")) {
            if (field.startsWith("offset="))
                return Long.parseLong(field.substring("offset=".length()));
        }
        return -1;
    }

    /**
     * Listens to this server's redis for writes. On a replica every write comes
     * from the coordinator, so each one is logged as a replicated change.
     */
    private class ReplicationListener extends Thread {
        private final Pattern uuidKey = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}p?");

        public void run() {
            while (true) {
                try (Jedis jedis = new Jedis("localhost", redisPort)) {
                    // keyevent notifications for hash, string and generic (del) commands
                    jedis.configSet("notify-keyspace-events", "Eh$g");
                    jedis.psubscribe(new JedisPubSub() {
                        @Override
                        public void onPMessage(String pattern, String channel, String key) {
                            // each account is stored under its username, its uuid and uuid + "p";
                            // only the username key is logged
                            if (state != replica || uuidKey.matcher(key).matches())
                                return;
                            String event = channel.substring(channel.indexOf(':') + 1);
                            if (event.equals("hset"))
                                System.out.println(REPLICAFLAG + " Replicated account \"" + key + "\" from coordinator");
                            else if (event.equals("del"))
                                System.out.println(REPLICAFLAG + " Replicated removal of account \"" + key
                                        + "\" from coordinator");
                        }
                    }, "__keyevent@0__:*");
                } catch (Exception e) {
                    try {
                        Thread.sleep(heartrate); // redis restarting, subscribe again
                    } catch (InterruptedException ie) {
                        return;
                    }
                }
            }
        }
    }

    ////////////////////////
    // LOGGING HELPERS
    ////////////////////////

    /**
     * Flag for this server's current role
     */
    private String roleFlag() {
        if (state == coordinator)
            return COORDINATORFLAG;
        if (state == election)
            return ELECTIONFLAG;
        return coordinatorAddr == null ? SYSTEMFLAG : REPLICAFLAG; // no coordinator found yet
    }

    /**
     * Flag for a log line produced while serving a client RMI call
     */
    private String rmiFlag() {
        return roleFlag() + "[" + CLIENTC + "CLIENT" + RESET + "]";
    }

    /**
     * Shortened hex form of a password hash for the logs: first and last 4 digits
     */
    private static String shortHash(byte[] bytes) {
        if (bytes == null)
            return "none";
        StringBuilder out = new StringBuilder();
        for (byte b : bytes)
            out.append(String.format("%02x", b));
        return out.substring(0, 4) + "..." + out.substring(out.length() - 4);
    }

    ///////////////////////////////
    // INTERNAL METHODS FOR SERVER//
    ///////////////////////////////

    /**
     * Writes an IdAccount account to the server's database. This method should only
     * be called by a coordinator.
     * 
     * @param acc
     */
    private void writeAccount(IdAccount acc) {
        synchronized (this) {
            try (Jedis jedis = pool.getResource()) {
                // creates an account object and writes
                Map<String, String> accMap = new HashMap<String, String>();
                accMap.put("username", acc.getUsername());
                accMap.put("realName", acc.getRealname());
                accMap.put("uuid", acc.getUuid().toString());
                accMap.put("dateCreated", acc.getDateCreated().toString());
                accMap.put("dateModified", acc.getDateModified().toString());
                accMap.put("original ip", acc.getOriginalIP());
                accMap.put("recent ip", acc.getRecentIp());
                jedis.hset(acc.getUsername(), accMap);
                jedis.hset(acc.getUuid().toString(), accMap);
                if (verbosity)
                    System.out.println(COORDINATORFLAG + " Stored account \"" + acc.getUsername()
                            + "\" in redis, password hash " + shortHash(acc.getPassword()));
                // writes the password separately
                if (acc.getPassword() == null)
                    jedis.set((acc.getUuid().toString() + "p").getBytes(), "null".getBytes());
                else
                    jedis.set((acc.getUuid().toString() + "p").getBytes(), acc.getPassword());
                // replicas catch up asynchronously (see whitepaper: Server Synchronization)
            }
        }
    }

    // searches redis for entry of account by username
    /**
     * This method reaches into this server's redis database and searches
     * for an account with specified username.
     * This method should only be called by the coordinator.
     * 
     * @param username
     */
    private IdAccount readAccount(String username) {
        if (verbosity)
            System.out.println(COORDINATORFLAG + " Reading account \"" + username + "\" from redis");
        IdAccount account;
        synchronized (this) {
            try (Jedis jedis = pool.getResource()) {
                if (!jedis.exists(username))
                    return null;

                Map<String, String> accMap = jedis.hgetAll(username);
                account = new IdAccount(
                        accMap.get("username"),
                        accMap.get("realName"),
                        UUID.fromString(accMap.get("uuid")),
                        LocalDateTime.parse(accMap.get("dateCreated")),
                        LocalDateTime.parse(accMap.get("dateModified")),
                        null,
                        accMap.get("original ip"),
                        accMap.get("recent ip"));

                // the password hash is stored separately under "<uuid>p"; "null" means no password
                byte[] password = jedis.get((account.getUuid() + "p").getBytes());
                if (password != null && !new String(password).equals("null"))
                    account.setPassword(password);
            } // end try()
        } // end sync
        return account;
    }

    // checks redis for entry of account with uuid
    /**
     * This method reaches into this server's redis database and searches
     * for an account with specified UUID.
     * This method should only be called by the coordinator.
     * 
     * @param uuid
     */
    private IdAccount readAccount(UUID uuid) {
        if (verbosity)
            System.out.println(COORDINATORFLAG + " Reading account \"" + uuid + "\" from redis");

        String uuidString = uuid.toString();
        IdAccount account;
        synchronized (this) {
            try (Jedis jedis = pool.getResource()) {
                if (!jedis.exists(uuidString))
                    return null;

                Map<String, String> accMap = jedis.hgetAll(uuidString);
                account = new IdAccount(
                        accMap.get("username"),
                        accMap.get("realName"),
                        UUID.fromString(accMap.get("uuid")),
                        LocalDateTime.parse(accMap.get("dateCreated")),
                        LocalDateTime.parse(accMap.get("dateModified")),
                        null,
                        accMap.get("original ip"),
                        accMap.get("recent ip"));

                // the password hash is stored separately under "<uuid>p"; "null" means no password
                byte[] password = jedis.get((account.getUuid() + "p").getBytes());
                if (password != null && !new String(password).equals("null"))
                    account.setPassword(password);
            } // end try()
        } // end sync
        return account;
    }

    /**
     * This method returns an ArrayList of all IdAccounts in the redis database.
     * This method should only be called by the coordinator.
     * 
     * @return ArrayList of IdAccount
     */
    private ArrayList<IdAccount> getAllAccounts() {
        synchronized (this) {
            try (Jedis jedis = pool.getResource()) {
                ArrayList<IdAccount> allAccounts = new ArrayList<IdAccount>();
                Set<String> keys = jedis.keys("*");
                if (verbosity)
                    System.out.println(COORDINATORFLAG + " Scanning " + keys.size() + " redis keys for accounts");
                for (String key : keys) {
                    if (keys.contains(key + "p")) {
                        allAccounts.add(readAccount(key));
                    }
                }
                return allAccounts;
            } // end try()
        } // end sync
    }

    /**
     * This method is called by the modify() method to delete the entire account
     * under a specific username key.
     * Note that the modify method will rewrite the account under a new username
     * key.
     * 
     * @param username
     */
    private void deleteUsernameEntry(String username) {
        synchronized (this) {
            try (Jedis jedis = pool.getResource()) {
                jedis.del(username); // delete username entry
            }
        }
        if (verbosity)
            System.out.println(COORDINATORFLAG + " Account \"" + username + "\" removed from redis");
    }

    /**
     * This method is called by deleteAccount(username, password), which is called by
     * the client. It permanently deletes an account and all its information.
     * 
     * @param username
     */
    private void deleteAccount(String username) {
        synchronized (this) {
            try (Jedis jedis = pool.getResource()) {
                IdAccount acc = readAccount(username);
                jedis.del(acc.getUsername()); // delete username entry
                jedis.del(acc.getUuid().toString()); // delete uuid entry
                jedis.del(acc.getUuid().toString() + "p"); // delete password entry
            }
        }
        if (verbosity)
            System.out.println(COORDINATORFLAG + " Account \"" + username + "\" removed from redis");
    }

    /**
     * Checks the passwords to look for match
     * 
     * @param p1 password 1
     * @param p2 password 2
     * @return boolean true/false
     */
    private boolean checkPassword(byte[] p1, byte[] p2) {
        if (p1.length != p2.length) {
            return false;
        }
        for (int i = 0; i < p1.length; i++)
            if (p1[i] != p2[i])
                return false;
        return true;
    }

    ////////////////////////
    // METHODS FOR CLIENT USE
    ////////////////////////

    /**
     * Getter for the server state [election (0) || replica (1) || coordinator (2)]
     * 
     * @return state
     */
    public int getState() {
        if (verbosity)
            System.out.println(rmiFlag() + " Client checking server role: "
                    + (state == coordinator ? "coordinator" : state == replica ? "replica" : "election in progress"));
        return state;
    }

    /**
     * This method is called by the client for creating an account.
     * 
     * @param username
     * @param realname
     * @param password
     * @param ipAddress
     * @return String for client debugging
     */
    public synchronized String createAccount(String username, String realname, byte[] password, String ipAddress) {
        if (verbosity) {
            System.out.println(rmiFlag() + " Request to create account \"" + username + "\"");
        }

        IdAccount acc = readAccount(username);
        if (acc != null) {
            if (verbosity) {
                System.out.println(rmiFlag() + ERRORC + " Account \"" + username + "\" already exists" + RESET);
            }
            return ERRORFLAG + ERRORC + " Account with name " + ACCOUNTC + username + ERRORC
                    + " already exists. Please choose another name." + RESET;
        }

        IdAccount newAcc = new IdAccount(ipAddress);
        newAcc.setUsername(username);
        newAcc.setRealname(realname);
        newAcc.setPassword(password);

        if (realname == null) {
            newAcc.setRealname(System.getProperty("user.name"));
        }

        if (verbosity) {
            System.out.println(rmiFlag() + ACCOUNTC + " Account created: \"" + newAcc.getUsername() + "\" ("
                    + newAcc.getRealname() + "), UUID " + newAcc.getUuid() + ", from " + ipAddress + RESET);
        }
        newAcc.setDateModified(java.time.LocalDateTime.now());
        newAcc.setRecentIp(ipAddress);

        writeAccount(newAcc);
        trackReplication("Create of \"" + username + "\"");
        return ACCOUNTC + "Account " + username + " created on server." + RESET;
    }

    /**
     * Returns a string if the account is found or not based on username
     * 
     * @param username
     * @param recentIp
     * @return String for client debugging
     */
    public String lookupUser(String username, String recentIp) {
        if (verbosity) {
            System.out.println(rmiFlag() + " Request to look up account \"" + username + "\"");
        }

        IdAccount acc = readAccount(username);
        if (acc != null) {
            if (verbosity) {
                System.out.println(rmiFlag() + ACCOUNTC + " Account found: " + username + RESET);
            }
            acc.setRecentIp(recentIp);
            return acc.toString();
        }

        if (verbosity) {
            System.out.println(rmiFlag() + ERRORC + " Account not found: " + username + RESET);
        }
        return ERRORFLAG + ERRORC + " user " + username + " not found" + RESET;
    }

    /**
     * Looks up user and returns string based on UUID
     * 
     * @param uuid
     * @param recentIp
     * @return String for client debugging
     */
    public String lookupUUID(String uuid, String recentIp) {
        if (verbosity) {
            System.out.println(rmiFlag() + " Request to look up UUID \"" + uuid + "\"");
        }

        // parse to validate the format and normalize case to match the stored key
        UUID temp;
        try {
            temp = UUID.fromString(uuid);
        } catch (IllegalArgumentException e) {
            if (verbosity)
                System.out.println(rmiFlag() + ERRORC + " Invalid UUID: " + uuid + RESET);
            return ERRORFLAG + ERRORC + " invalid UUID: " + uuid + RESET;
        }
        IdAccount acc = readAccount(temp);

        if (acc != null) {
            if (verbosity) {
                System.out.println(rmiFlag() + ACCOUNTC + " Account found: " + uuid + RESET);
            }
            acc.setDateModified(java.time.LocalDateTime.now());
            return acc.toString();
        }

        if (verbosity) {
            System.out.println(rmiFlag() + ERRORC + " Account not found: " + uuid + RESET);
        }
        return ERRORFLAG + ERRORC + " user " + uuid + " not found" + RESET;
    }

    /**
     * Modifies the username, checks password if needed
     * 
     * @param oldName
     * @param newName
     * @param password
     * @param recentIp
     * @returns String for client debugging
     */
    public synchronized String changeUsername(String oldName, String newName, byte[] password, String recentIp) {
        if (verbosity) {
            System.out.println(rmiFlag() + " Request to rename account \"" + oldName + "\" to \"" + newName + "\"");
        }

        IdAccount acc = readAccount(newName);
        if (acc != null) {
            if (verbosity) {
                System.out.println(rmiFlag() + ERRORC + " Account \"" + newName + "\" already exists" + RESET);
            }
            acc.setDateModified(java.time.LocalDateTime.now());
            return ERRORFLAG + ERRORC + " Account with name " + newName + " already exists. Please choose another name." + RESET;
        }

        acc = readAccount(oldName);
        if (acc == null) {
            if (verbosity)
                System.out.println(rmiFlag() + ERRORC + " Account not found: " + oldName + RESET);
            return ERRORFLAG + ERRORC + " NO ACCOUNT FOUND OR ACCOUNT NOT CREATED" + RESET;
        }
        {
            if (verbosity) {
                System.out.println(rmiFlag() + ACCOUNTC + " Account found: " + oldName + RESET);
                System.out.println(rmiFlag() + " Stored password hash: " + shortHash(acc.getPassword()));
            }
            if (password == null && acc.getPassword() != null) {
                return ERRORFLAG + ERRORC + " PASSWORD ASSOCIATED WITH ACCOUNT WAS NOT ENTERED" + RESET;
            } else if (acc.getPassword() == null) {
                acc.setDateModified(java.time.LocalDateTime.now());
                acc.setUsername(newName); // NO PASSWORD
                writeAccount(acc);
                deleteUsernameEntry(oldName);
                trackReplication("Rename of \"" + oldName + "\" to \"" + newName + "\"");
                if (verbosity) {
                    System.out.println(rmiFlag() + ACCOUNTC + " No password associated with account" + RESET);
                }
                return ACCOUNTC + "Account " + oldName + " found and login name updated to " + newName;
            } else if (checkPassword(acc.getPassword(), password)) {
                acc.setDateModified(java.time.LocalDateTime.now());
                acc.setUsername(newName);
                writeAccount(acc);
                deleteUsernameEntry(oldName);
                trackReplication("Rename of \"" + oldName + "\" to \"" + newName + "\"");
                if (verbosity) {
                    System.out.println(rmiFlag() + ACCOUNTC + " Password hash matches" + RESET);
                }
                return ACCOUNTC + "Account " + oldName + " found and login name updated to " + newName;
            } else {
                if (verbosity)
                    System.out.println(rmiFlag() + ERRORC + " Password hash does not match" + RESET);
                return ERRORFLAG + ERRORC + " PASSWORD DENIED" + RESET;
            }
        }
    }

    /**
     * Deletes user, checks if password is the same before deleting, fails if no
     * match
     * 
     * @param username
     * @param password
     * @return String for client debugging
     */
    public synchronized String deleteAccount(String username, byte[] password) {
        if (verbosity) {
            System.out.println(rmiFlag() + " Request to delete account \"" + username + "\"");
        }

        IdAccount acc = readAccount(username);
        if (acc != null) {
            if (verbosity) {
                System.out.println(rmiFlag() + ACCOUNTC + " Account found: " + username + RESET);
            }
            if (password == null && acc.getPassword() != null) {
                return ERRORFLAG + ERRORC + " PASSWORD ASSOCIATED WITH ACCOUNT WAS NOT ENTERED" + RESET;
            } else if (acc.getPassword() == null) {
                deleteAccount(username);
                trackReplication("Delete of \"" + username + "\"");
                return ACCOUNTC + "Account \"" + username + "\" removed." + RESET;
            } else if (checkPassword(acc.getPassword(), password)) {
                deleteAccount(username);
                trackReplication("Delete of \"" + username + "\"");
                return ACCOUNTC + "Account \"" + username + "\" removed." + RESET;
            } else
                return ERRORFLAG + ERRORC + " PASSWORD DENIED" + RESET;
        }

        return ERRORFLAG + ERRORC + " NO ACCOUNT FOUND OR ACCOUNT NOT CREATED" + RESET;
    }

    /**
     * Gets the accounts in the database on the server if with output depending on
     * what you want to be returned
     * 
     * @param attribute
     * @return formatted String of info for client printing
     */
    public String getAccounts(String attribute) {
        if (verbosity == true) {
            System.out.println(rmiFlag() + " Request to list accounts (" + attribute + ")");
        }
        String out = null;
        int i = 0;

        ArrayList<IdAccount> allAccounts = getAllAccounts();
        if (allAccounts.size() == 0)
            return ERRORFLAG + ERRORC + " There are no accounts, yet..." + RESET;

        switch (attribute.toLowerCase()) {
            case "users":
                out = "Registered USERS:\n";
                for (IdAccount acc : allAccounts)
                    out += ++i + ":   " + acc.getUsername() + "\n";
                break;
            case "uuids":
                out = "Registered UUIDS:\n";
                for (IdAccount acc : allAccounts)
                    out += ++i + ":   " + acc.getUuid() + "\n";
                break;
            case "all":
                out = "Registered Accounts:\n";
                for (IdAccount acc : allAccounts)
                    out += ++i + ":   " + acc.toString() + "\n\n";
                break;
            default:
                out = ERRORFLAG + ERRORC + " INVALID ARG WITH -get: " + attribute + RESET;
                break;
        }

        return out;
    }

    /**
     * gets the address of the coordinator, for client use
     * 
     * @return Inet4Address of coordinator
     */
    public Inet4Address getCoordinator() throws RemoteException {
        if (verbosity)
            System.out.println(rmiFlag() + " Client redirected to coordinator at "
                    + (coordinatorAddr == null ? "unknown" : coordinatorAddr.getHostAddress()));
        return coordinatorAddr;
    }

    /**
     * Shutdown hook handler
     */
    private class DeathToServer extends Thread {

        /**
         * Shutdown hook thread run method
         */
        public void run() {
            deathToRMI();
            System.out.println(roleFlag() + ERRORC + " !!!!SERVER SHUTTING DOWN!!!!" + RESET);
        }

        /**
         * Unbinds the registry object
         */
        public void deathToRMI() {
            try {
                Registry rg = LocateRegistry.getRegistry(port);
                rg.unbind(RMI_NAME);
            } catch (RemoteException | NotBoundException e) {
                System.out.println(
                        ERRORFLAG + " Error in deathToRMI(). Rmi failed to unbind due to " + getErrorName(e));
            }
        }
    }

    /**
     * A thread that maintains the connection to one other server. A dialer
     * connects to its server (retrying until it is up); a non-dialer accepts the
     * next incoming server connection. Either way it then reads that server's
     * heartbeats and feeds them to the election.
     */
    private class ServerListen extends Thread {
        private final String peerHost; // host from the server file, used for dialing
        private final boolean dialer; // true if this side opens the connection
        private String connectionHost; // IP of connection
        private int conNum; // the fake PID for the servers
        private ServerSend toServer; // output stream thread
        private boolean everConnected = false; // false until the first connection to this peer
        private int failures = 0; // consecutive runtime failures on this peer link

        /**
         * CONSTRUCTOR
         * 
         * @param host
         * @param dialer
         */
        public ServerListen(String host, boolean dialer) {
            this.peerHost = host;
            this.connectionHost = host;
            this.dialer = dialer;
            this.toServer = null;
        }

        /**
         * Setter for connection IP
         * 
         * @param host
         */
        private void setConnectionHost(String host) {
            this.connectionHost = host;
            if (connectionHost.startsWith("/"))
                connectionHost = connectionHost.substring(1);
        }

        /**
         * Dials the peer until it answers, or waits for any peer to dial us
         */
        private Socket connect() throws IOException, InterruptedException {
            if (!dialer) {
                Socket sock = servSock.accept();
                System.out.println(SYSTEMFLAG + " Peer server connected from " + sock.getInetAddress().getHostAddress());
                return sock;
            }
            // keep retrying so a server that comes back is reconnected, but only log
            // the first failed attempt of each outage
            boolean reported = false;
            while (true) {
                try {
                    // resolve on every attempt (a restarted server can get a new IP) and do
                    // not let a connect to a stopped server's old IP hang
                    Socket sock = new Socket();
                    sock.connect(new InetSocketAddress(peerHost, serverPort), heartrate / 2);
                    if (verbosity)
                        System.out.println(SYSTEMFLAG + " Connected to peer server " + peerHost + ":" + serverPort);
                    return sock;
                } catch (IOException e) {
                    if (verbosity && !reported && !everConnected)
                        System.out.println(SYSTEMFLAG + " Waiting for peer server " + peerHost + ":" + serverPort
                                + " to start...");
                    else if (verbosity && !reported)
                        System.out.println(roleFlag() + ERRORFLAG + " Server " + peerHost + ":" + serverPort
                                + " not reachable, retrying in the background...");
                    reported = true;
                    Thread.sleep(heartrate / 2);
                }
            }
        }

        public void run() {
            while (true) {
                Socket sock = null;
                conNum = 0;
                try {
                    sock = connect();
                    everConnected = true;
                    // a server that stops sending heartbeats is treated as dead
                    sock.setSoTimeout(heartrate * 3);
                    setConnectionHost(sock.getInetAddress().getHostAddress());

                    ObjectOutputStream out = new ObjectOutputStream(sock.getOutputStream());
                    out.flush(); // send the stream header now so the other side's input stream can open
                    ObjectInputStream in = new ObjectInputStream(sock.getInputStream());

                    // automatically sets up output stream
                    toServer = new ServerSend(out);
                    toServer.start();

                    // the rest of the method deals with the input stream
                    while (true) {
                        int[] read = ((int[]) in.readObject());
                        int readState = read[0];
                        conNum = read[1];
                        if (handlePidCollision(conNum, readState))
                            continue;

                        // part of the bully algorithm
                        if (readState == coordinator)
                            followCoordinator(conNum, connectionHost);
                        else if (readState == election)
                            handleElection(conNum);
                        failures = 0; // heartbeat handled, the link is healthy
                    }

                } catch (IOException e) {
                    if (conNum != 0) {
                        String lost = conNum == coordinatorPid ? "Coordinator " : "Server ";
                        System.out.println(roleFlag() + ERRORFLAG + " " + lost + conNum + " at " + connectionHost
                                + " disconnected");
                        handleDisconnect(conNum);
                    }
                } catch (RuntimeException e) {
                    // e.g. redis refusing replicaof; reconnect like a dropped link, but stop
                    // after repeated failures instead of retrying forever
                    System.out.println(roleFlag() + ERRORFLAG + " Connection to " + connectionHost + " failed: " + e);
                    if (conNum != 0)
                        handleDisconnect(conNum);
                    if (++failures >= MAX_PEER_FAILURES) {
                        System.out.println(roleFlag() + ERRORFLAG + ERRORC + " Giving up on peer " + peerHost + " after "
                                + failures + " consecutive failures" + RESET);
                        return;
                    }
                } catch (ClassNotFoundException e) {
                    System.out.println(roleFlag() + ERRORFLAG + ERRORC + " Object read was not an array of integers" + RESET);
                    e.printStackTrace();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                    return;
                } finally {
                    try {
                        if (sock != null)
                            sock.close();
                    } catch (IOException ignored) {
                    }
                }
            }
        }

    }

    /**
     * A thread that handles the output stream of a ServerListen thread. It sends
     * this server's state once per heartbeat.
     */
    private class ServerSend extends Thread {
        private ObjectOutputStream oos;

        /**
         * CONSTRUCTOR
         */
        public ServerSend(ObjectOutputStream oos) {
            this.oos = oos;
        }

        public void run() {
            try {
                // should be every heartbeat
                while (true) {
                    synchronized (lock) {
                        lock.wait();
                    }
                    int[] toSend = { state, serverNum, serverPort };
                    oos.writeObject(toSend);
                    oos.flush();
                }
            } catch (IOException e) {
                // the connection closed; its ServerListen thread logs the disconnect
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * Exports this server over RMI with SSL and binds it in the RMI registry so
     * clients can look it up. The registry may still be starting, so the bind is
     * retried a few times.
     * 
     * @return true if the server was bound
     */
    public boolean bind() {
        ServerInterface stub;
        try {
            RMIClientSocketFactory rmiClientSocketFactory = new SslRMIClientSocketFactory();
            RMIServerSocketFactory rmiServerSocketFactory = new SslRMIServerSocketFactory();
            stub = (ServerInterface) UnicastRemoteObject.exportObject(this, 0,
                    rmiClientSocketFactory, rmiServerSocketFactory);
        } catch (RemoteException e) {
            System.out.println(SYSTEMFLAG + ERRORFLAG + " Failed to export server: " + e);
            return false;
        }

        for (int attempt = 1; attempt <= 5; attempt++) {
            try {
                Registry reg = LocateRegistry.getRegistry(port);
                if (verbosity) {
                    System.out.println(SYSTEMFLAG + " Found RMI registry on port " + port);
                }

                reg.rebind(RMI_NAME, stub);
                if (verbosity) {
                    System.out.println(SYSTEMFLAG + " Bound \"" + RMI_NAME + "\" in RMI registry");
                }
                return true;
            } catch (RemoteException e) {
                System.out.println(SYSTEMFLAG + ERRORFLAG + " Bind attempt " + attempt + " of 5 failed: " + e);
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return false;
    }

    /**
     * Helper method to extract and return the type of error that occured as a string.
     * @param e
     * @return Returns the simple name of the error
     */
    public static String getErrorName(Exception e) {
        String errorType = e.getClass().getSimpleName();
        return errorType;
    }

    //////////
    // MAIN //
    //////////
    /**
     * Main server method
     * 
     * @param args
     */
    public static void main(String[] args) {
        try {

            handleServerArgs(args);
            if (verbosity) {
                System.out.println(SYSTEMFLAG + " Starting IdServer, RMI registry port " + port);
            }

            System.setProperty("javax.net.ssl.keyStore", "Server_Keystore");
            System.setProperty("javax.net.ssl.keyStorePassword", "123456");

            IdServer server = new IdServer(serverFile);
            if (!server.bind()) {
                System.out.println(SYSTEMFLAG + ERRORFLAG + " Could not bind to the RMI registry, shutting down");
                System.exit(1);
            }

        } catch (RemoteException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Parses args for server
     * 
     * @param args
     */
    public static void handleServerArgs(String[] args) {
        try {
            Options serverOptions = new Options();

            serverOptions.addOption(new Option("s", "serverHosts", true, "Server addresses"));
            serverOptions.addOption(new Option("n", "numport", true, "Server port #"));
            serverOptions.addOption(new Option("v", "verbose", false, "Sets verbosity of server"));

            CommandLineParser serverParser = new DefaultParser();
            CommandLine serverCommand = serverParser.parse(serverOptions, args);

            String arg = null;
            for (Option serverOption : serverCommand.getOptions()) {

                // Handles the numport option for port specification
                if (serverOption.getOpt().equals("n")) {
                    arg = serverOption.getOpt();
                    port = Integer.parseInt(serverCommand.getOptionValue(arg));
                    System.out.println(SYSTEMFLAG + " Setting server port to " + serverCommand.getOptionValue(arg));
                }

                // Handles the server list file
                if (serverOption.getOpt().equals("s")) {
                    arg = serverOption.getOpt();
                    serverFile = serverCommand.getOptionValue(arg);
                    System.out.println(SYSTEMFLAG + " Server list file: " + serverCommand.getOptionValue(arg));
                }

                // Handles the verbosity specification
                if (serverOption.getOpt().equals("v")) {
                    verbosity = true;
                    System.out.println(SYSTEMFLAG + " Verbosity set to " + COMMANDC + verbosity + RESET);
                }
            }
        } catch (ParseException e) {
            e.printStackTrace();
        }
    }

}
