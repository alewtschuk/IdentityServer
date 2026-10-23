import java.util.UUID;
import java.util.regex.Pattern;

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
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.Random;
import java.util.Scanner;

import redis.clients.jedis.*;

public class IdServer implements ServerInterface {

    // internal server variables
    // private HashSet<IdAccount> accounts; CAN MAKE THIS A CACHE IN THE FUTURE
    private static boolean verbosity = false;
    static int port = 5128;
    static int redisPort = 6379;
    JedisPool pool = new JedisPool("localhost", redisPort);

    // server coordination variables
    Object lock = new Object();
    Object electionLock = new Object(); // guards state/coordinator transitions
    // random "pid" used by the bully algorithm, assigned on startup (see whitepaper)
    private final int serverNum = new Random().nextInt(10000) + 3;
    private volatile int state;
    private volatile int coordinator;
    private int electionTicks = 0; // heartbeats spent in the election state
    private int leaderlessTicks = 0; // heartbeats spent as a pleb with no known coordinator

    private static String serverFile;
    private static ArrayList<String> serverHosts = new ArrayList<String>();
    private ArrayList<ServerListen> serverListeners = new ArrayList<ServerListen>();

    private int heartrate = 2000;
    private int numServers = 0;
    private int serverPort = 5126;

    private ServerSocket servSock;
    private volatile Inet4Address coordinatorAddy;
    private String myAddress;
    private String myHostName;
    private HashSet<String> connections; // for debugging

    private final int election = 0;
    private final int imPleb = 1;
    private final int imBoss = 2;

    final static String RESET = "\u001B[0m";
    final static String ANSI_BLACK = "\u001B[30m";
    final static String ERRORC = "\u001B[31m";
    final static String COMMANDC = "\u001B[32m";
    final static String ANSI_YELLOW = "\u001B[33m";
    final static String ANSI_PURPLE = "\u001B[35m";
    final static String ACCOUNTC = "\u001B[36m";
    final static String ANSI_WHITE = "\u001B[37m";

    final static String SYSTEMFLAG = "[" + COMMANDC + "System" + RESET + "]";
    final static String ERRORFLAG = "[" + ERRORC + "ERROR" + RESET + "]";
    final static String IP_PATTERN = "^(25[0-5]|2[0-4]\\d|1\\d{2}|[1-9]?\\d)\\." +
            "(25[0-5]|2[0-4]\\d|1\\d{2}|[1-9]?\\d)\\." +
            "(25[0-5]|2[0-4]\\d|1\\d{2}|[1-9]?\\d)\\." +
            "(25[0-5]|2[0-4]\\d|1\\d{2}|[1-9]?\\d)$";

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
        // we can uncomment this if we want to implement a cache or something
        // this.accounts = new HashSet<IdAccount>();
        Runtime.getRuntime().addShutdownHook(new DeathToServer());
        System.out.println(SYSTEMFLAG + " Server " + serverNum + " established:");

        // a new server starts as a pleb and subordinates itself to an established
        // coordinator. If none is heard from, the heartbeat starts an election.
        this.state = imPleb;
        this.coordinator = 0;

        // handling all listening and sending threads
        this.connections = new HashSet<String>();
        this.servSock = new ServerSocket(serverPort);
        this.myAddress = InetAddress.getLocalHost().getHostAddress();
        this.myHostName = InetAddress.getLocalHost().getHostName();
        if (verbosity)
            System.out.println(SYSTEMFLAG + "My address is " + myAddress);
        setupServerConnections();

        // taking inspiration from hw2, this lock is used for the heartbeat and delays
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
            System.out.println(ERRORFLAG + "This server (" + myHostName + ") is not listed in " + serverFile);
            System.exit(1);
        }

        for (int i = 0; i < serverHosts.size(); i++) {
            if (i == me)
                continue;
            ServerListen listener = new ServerListen(serverHosts.get(i), i < me);
            serverListeners.add(listener);
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
     * heartbeats without hearing from a higher pid becomes the coordinator. A pleb
     * that has not heard from any coordinator for four heartbeats starts an
     * election. Four is long enough for a joining server to be reached by the
     * others and hear an established coordinator first.
     */
    private void electionHeartbeat() {
        synchronized (electionLock) {
            if (state == election) {
                if (++electionTicks >= 2)
                    becomeBoss();
            } else if (state == imPleb && coordinatorAddy == null) {
                if (++leaderlessTicks >= 4)
                    startElection();
            }
        }
    }

    private void startElection() {
        synchronized (electionLock) {
            if (verbosity)
                System.out.println(SYSTEMFLAG + " Election triggered! (" + serverNum + ")");
            state = election;
            electionTicks = 0;
            coordinator = 0;
            coordinatorAddy = null;
        }
    }

    private void becomeBoss() {
        synchronized (electionLock) {
            state = imBoss;
            coordinator = serverNum;
            try {
                coordinatorAddy = (Inet4Address) InetAddress.getByName(myAddress);
            } catch (IOException e) {
                e.printStackTrace();
            }
            try (Jedis jedis = pool.getResource()) {
                jedis.replicaofNoOne();
            }
            System.out.println(SYSTEMFLAG + " I'M THE BOSS (" + serverNum + ")");
            if (verbosity)
                System.out.println(SYSTEMFLAG + " My redis is now independent");
        }
    }

    /**
     * Handles a boss announcement. Any server that is not the boss subordinates
     * itself, even if its pid is larger (whitepaper). If two servers both claim
     * boss, the lower pid steps down.
     */
    private void followBoss(int bossNum, String bossHost) {
        synchronized (electionLock) {
            if (state == imBoss && bossNum < serverNum)
                return;
            boolean newBoss = coordinator != bossNum || coordinatorAddy == null;
            state = imPleb;
            leaderlessTicks = 0;
            coordinator = bossNum;
            if (!newBoss)
                return;
            try {
                coordinatorAddy = (Inet4Address) InetAddress.getByName(bossHost);
            } catch (IOException e) {
                e.printStackTrace();
                return;
            }
            System.out.println(SYSTEMFLAG + " Following coordinator " + bossNum + " at " + bossHost);
            try (Jedis jedis = pool.getResource()) {
                jedis.replicaof(bossHost, redisPort);
            }
            if (verbosity)
                System.out.println(SYSTEMFLAG + "my redis is now a replica of " + bossHost + ":" + redisPort);
        }
    }

    /**
     * Handles an election message: a higher pid makes this server back down, a
     * lower pid makes a leaderless server start its own election.
     */
    private void handleElection(int fromNum) {
        synchronized (electionLock) {
            if (fromNum > serverNum) {
                if (state != imPleb && verbosity)
                    System.out.println(SYSTEMFLAG + " I'm a pleb (" + serverNum + " < " + fromNum + ")");
                if (state == election)
                    state = imPleb;
                if (state == imPleb && coordinatorAddy == null)
                    leaderlessTicks = 0; // the higher server is still electing
            } else if (state == imPleb && coordinatorAddy == null) {
                startElection();
            }
        }
    }

    /**
     * Handles a lost connection to another server
     */
    private void handleDisconnect(int lostNum) {
        synchronized (electionLock) {
            if (lostNum == coordinator && state != imBoss)
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
                if (true) { // Pattern.matches(IP_PATTERN, next.trim())) {
                    serverHosts.add(next.trim());
                }
            }

            if (serverHosts.isEmpty()) {
                System.out.println(ERRORFLAG + "No valid server addresses found in file.");
                System.exit(1);
            }

        } catch (Exception e) {
            System.out.println(ERRORFLAG + "Problem reading server file: " + e.getMessage());
            System.exit(1);
        }
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
                System.out.println("Account stored under \"" + acc.getUsername() + "\"");
                System.out.println("Account stored under \"" + acc.getUuid().toString() + "\"");

                System.out.println("Storing password under " + acc.getUuid().toString() + "p: " + acc.getPassword());
                // writes the password separately
                if (acc.getPassword() == null)
                    jedis.set((acc.getUuid().toString() + "p").getBytes(), "null".getBytes());
                else
                    jedis.set((acc.getUuid().toString() + "p").getBytes(), acc.getPassword());
                // replicas catch up asynchronously (see whitepaper: Server Synchronization)
            }
        }
        // debug prints
        if (verbosity)
            System.out.println(SYSTEMFLAG + " Account: " + acc.getUsername() + " to database.");
    }

    // searches redis for entry of account by username
    /**
     * This method account reaches into this server's redis database and searches
     * for an account with specified username.
     * This method should only be called by the coordinator.
     * 
     * @param username
     */
    private IdAccount readAccount(String username) {
        System.out.println("readAccount called for \"" + username + "\"");
        IdAccount skeeyyah;
        synchronized (this) {
            try (Jedis jedis = pool.getResource()) {
                if (!jedis.exists(username))
                    return null;

                Map<String, String> accMap = jedis.hgetAll(username);
                System.out.println(accMap.get("username"));
                skeeyyah = new IdAccount(
                        accMap.get("username"),
                        accMap.get("realName"),
                        UUID.fromString(accMap.get("uuid")),
                        LocalDateTime.parse(accMap.get("dateCreated")),
                        LocalDateTime.parse(accMap.get("dateModified")),
                        null,
                        accMap.get("original ip"),
                        accMap.get("recent ip"));

                // This line is so hard to read -- if the password is not null, then get the its
                // bytes from jedis
                if (!(new String(jedis.get((skeeyyah.getUuid().toString() + "p").getBytes())).equals("null")))
                    skeeyyah.setPassword(jedis.get((skeeyyah.getUuid().toString() + "p").getBytes()));
            } // end try()
        } // end sync
        return skeeyyah;
    }

    // checks redis for entry of accoutn with uuid
    /**
     * This method account reaches into this server's redis database and searches
     * for an account with specified UUID.
     * This method should only be called by the coordinator.
     * 
     * @param uuid
     */
    private IdAccount readAccount(UUID uuid) {
        System.out.println("readAccount called for \"" + uuid.toString() + "\"");

        String uuidString = uuid.toString();
        IdAccount skeeyyah;
        synchronized (this) {
            try (Jedis jedis = pool.getResource()) {
                if (!jedis.exists(uuidString))
                    return null;

                Map<String, String> accMap = jedis.hgetAll(uuidString);
                System.out.println(accMap.get("uuid"));
                skeeyyah = new IdAccount(
                        accMap.get("username"),
                        accMap.get("realName"),
                        UUID.fromString(accMap.get("uuid")),
                        LocalDateTime.parse(accMap.get("dateCreated")),
                        LocalDateTime.parse(accMap.get("dateModified")),
                        null,
                        accMap.get("original ip"),
                        accMap.get("recent ip"));

                // This line is so hard to read -- if the password is not null, then get the its
                // bytes from jedis
                if (!(new String(jedis.get((skeeyyah.getUuid().toString() + "p").getBytes())).equals("null")))
                    skeeyyah.setPassword(jedis.get((uuidString + "p").getBytes()));
            } // end try()
        } // end sync
        return skeeyyah;
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
                System.out.println("keys: " + keys.toString());
                for (String key : keys) {
                    System.out.println(key);
                    if (keys.contains(key + "p")) {
                        allAccounts.add(readAccount(key));
                    }
                }
                return allAccounts;
            } // end try()
        } // end sync
    }

    /**
     * This method is called by the modify() method to delete the enture account
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
                jedis.wait(numServers, heartrate);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        if (verbosity)
            System.out.println(SYSTEMFLAG + "Account \"" + username + " removed");
    }

    /**
     * This method is called deleteAccount(username, password) that is called by the
     * client. It permanently deletes an account and all its information.
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
            System.out.println(SYSTEMFLAG + "Account \"" + username + " removed");
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
        ;
        // for(int i = 0; i < p1.length; i++) if(p1[i] != p2[i])
        // System.out.println("actual password: " + p1[i] + "\n given password: " +
        // p2[i]); //Debug line
        for (int i = 0; i < p1.length; i++)
            if (p1[i] != p2[i])
                return false; // Java is cool too
        return true;
    }

    ////////////////////////
    // METHODS FOR CLIENT USE
    ////////////////////////

    /**
     * Getter for the server state [election || subordinate || coordinator]
     * 
     * @return state
     */
    public int getState() {
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
    public String createAccount(String username, String realname, byte[] password, String ipAddress) {
        if (verbosity) {
            System.out.println(SYSTEMFLAG + COMMANDC + " called --create" + RESET);
        }

        IdAccount acc = readAccount(username);
        if (acc != null) {
            if (verbosity) {
                System.out.println(ERRORFLAG + " Account with name " + ACCOUNTC + username + RESET
                        + " already exists. Please choose another name.");
            }
            return ERRORC + " Account with name " + ACCOUNTC + username + RESET
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
            System.out.println(ACCOUNTC + " == ACCOUNT CREATED ==" + RESET);
            System.out.println(ACCOUNTC + newAcc.toString() + "\n" + RESET);
        }
        newAcc.setDateModified(java.time.LocalDateTime.now());
        newAcc.setRecentIp(ipAddress);

        // accounts.add(newAcc);
        writeAccount(newAcc);
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
            System.out.println(SYSTEMFLAG + COMMANDC + " called --lookup" + RESET);
        }

        IdAccount acc = readAccount(username);
        if (acc != null) {
            if (verbosity) {
                System.out.println(SYSTEMFLAG + ACCOUNTC + "Account found: " + username + "\n" + RESET);
            }
            acc.setRecentIp(recentIp);
            return acc.toString();
        }

        if (verbosity) {
            System.out.println(ERRORFLAG + ACCOUNTC + "Account not found: " + username + "\n" + RESET);
        }
        return ERRORC + "[ERROR] user " + username + " not found" + RESET;
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
            System.out.println(SYSTEMFLAG + COMMANDC + " called --reverse-lookup" + RESET);
        }

        // the reason I turn this into a UUID and back to a string is because I haven't
        // checked if it changes
        UUID temp = UUID.fromString(uuid);
        IdAccount acc = readAccount(temp);

        if (acc != null) {
            if (verbosity) {
                System.out.println(SYSTEMFLAG + ACCOUNTC + "Account found: " + uuid + "\n" + RESET);
            }
            acc.setDateModified(java.time.LocalDateTime.now());
            return acc.toString();
        }

        if (verbosity) {
            System.out.println(ERRORFLAG + ACCOUNTC + "Account not found: " + uuid + "\n" + RESET);
        }
        return ERRORC + "[ERROR] user " + uuid + "not found" + RESET;
    }

    /**
     * Modifies the username, checks passord if needed
     * 
     * @param oldName
     * @param newName
     * @param password
     * @param recentIp
     * @returns String for client debugging
     */
    public String changeUsername(String oldName, String newName, byte[] password, String recentIp) {
        if (verbosity) {
            System.out.println(SYSTEMFLAG + COMMANDC + " called --modify" + RESET);
        }

        IdAccount acc = readAccount(newName);
        if (acc != null) {
            if (verbosity) {
                System.out.println(ERRORFLAG + " Account with name " + newName
                        + " already exists. Please choose another name." + RESET);
            }
            acc.setDateModified(java.time.LocalDateTime.now());
            return ERRORC + "[ERROR] Account with name " + newName + " already exists. Please choose another name."
                    + RESET;
        }

        acc = readAccount(oldName);
        System.out.println("password for " + acc.getUsername() + ": " + acc.getPassword());
        {
            if (verbosity) {
                System.out.println(ERRORFLAG + ACCOUNTC + "Account found: " + oldName + "\n" + RESET);
            }
            if (password == null && acc.getPassword() != null) {
                return ERRORC + "[ERROR] PASSWORD ASSOCIATED WITH ACCOUNT WAS NOT ENTERED" + RESET;
            } else if (acc.getPassword() == null) {
                acc.setDateModified(java.time.LocalDateTime.now());
                acc.setUsername(newName); // NO PASSWORD
                writeAccount(acc);
                deleteUsernameEntry(oldName);
                if (verbosity) {
                    System.out.println(SYSTEMFLAG + ACCOUNTC + "No password associated with account." + RESET);
                }
                return ACCOUNTC + "Account " + oldName + " found and login name updated to " + newName;
            } else if (checkPassword(acc.getPassword(), password)) {
                acc.setDateModified(java.time.LocalDateTime.now());
                acc.setUsername(newName);
                writeAccount(acc);
                deleteUsernameEntry(oldName);
                if (verbosity) {
                    System.out.println(SYSTEMFLAG + ACCOUNTC + "Password associated with account is correct." + RESET);
                }
                return ACCOUNTC + "Account " + oldName + " found and login name updated to " + newName;
            } else {
                acc.setDateModified(java.time.LocalDateTime.now());
                writeAccount(acc);
                return ERRORC + "[ERROR] PASSWORD DENIED" + RESET;
            }
        }
    }

    /**
     * Deletes user, checks if password is the same before delting, fails if no
     * match
     * 
     * @param username
     * @param password
     * @return String for client debugging
     */
    public String deleteAccount(String username, byte[] password) {
        if (verbosity) {
            System.out.println(SYSTEMFLAG + COMMANDC + " called --delete" + RESET);
        }

        IdAccount acc = readAccount(username);
        if (acc != null) {
            if (verbosity) {
                System.out.println(SYSTEMFLAG + ACCOUNTC + "Account found: " + username + "\n" + RESET);
            }
            if (password == null && acc.getPassword() != null) {
                return ERRORC + "[ERROR] PASSWORD ASSOCIATED WITH ACCOUNT WAS NOT ENTERED" + RESET;
            } else if (acc.getPassword() == null) {
                // accounts.remove(acc);
                deleteAccount(username);
                return ACCOUNTC + "Account \"" + username + "\" removed." + RESET;
            } else if (checkPassword(acc.getPassword(), password)) {
                // accounts.remove(acc);
                deleteAccount(username);
                return ACCOUNTC + "Account \"" + username + "\" removed." + RESET;
            } else
                return ERRORC + "[ERROR] PASSWORD DENIED" + RESET;
        }

        return ERRORC + "[ERROR] NO ACCOUNT FOUND OR ACCOUNT NOT CREATED" + RESET;
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
            System.out.println(SYSTEMFLAG + COMMANDC + " called --get" + RESET);
        }
        String out = null;
        int i = 0;

        ArrayList<IdAccount> allAccounts = getAllAccounts();
        if (allAccounts.size() == 0)
            return ERRORC + "[ERROR] There are no accounts, yet..." + RESET;

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
                out += ERRORC + "[ERROR] INVALID ARG WITH -get: " + attribute + RESET;
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
        return coordinatorAddy;
    }

    /**
     * Sets verbosity of server
     * 
     * @param verbosity
     */
    public void setVerbose(boolean verbosity) {
        verbosity = true;
        System.out.println(SYSTEMFLAG + "Verbsoity set to " + COMMANDC + verbosity + RESET);
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
            System.out.println("\n\n" + SYSTEMFLAG + ERRORC + "!!!!SERVER SHUTTING DOWN!!!!" + RESET);
        }

        /**
         * Unbinds the registry object
         */
        public void deathToRMI() {
            try {
                Registry rg = LocateRegistry.getRegistry(port);
                rg.unbind("severus");
            } catch (RemoteException e) {
                System.out.println(
                        ERRORFLAG + "Error in deathToRMI(). Rmi failed to unbind due to RemoteException" + RESET);
            } catch (NotBoundException e) {
                System.out.println(
                        ERRORFLAG + "Error in deathToRMI(). Rmi failed to unbind due to NotBoundException" + RESET);
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
                System.out.println(COMMANDC + "CONNECTED TO " + sock.getInetAddress() + " port: "
                        + sock.getPort() + RESET);
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
                        System.out.println(COMMANDC + " FOUND: " + peerHost + ":" + serverPort + RESET);
                    return sock;
                } catch (IOException e) {
                    if (verbosity && !reported)
                        System.out.println(ERRORFLAG + " server " + peerHost + ":" + serverPort
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
                        if (conNum == 0) {
                            numServers++;
                            connections.add(connectionHost);
                        }
                        conNum = read[1];
                        toServer.setConNum(conNum);

                        // part of the bully algorithm
                        if (readState == imBoss)
                            followBoss(conNum, connectionHost);
                        else if (readState == election)
                            handleElection(conNum);
                    }

                } catch (IOException e) {
                    if (conNum != 0) {
                        System.out.println(
                                ERRORFLAG + " Server " + conNum + " at " + connectionHost + " disconnected");
                        numServers--;
                        connections.remove(connectionHost);
                        handleDisconnect(conNum);
                    }
                } catch (ClassNotFoundException e) {
                    System.out.println(ERRORC + "Object read was not an array of integers" + RESET);
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
        private int conNum; // the fake PID for the servers

        /**
         * CONSTRUCTOR
         */
        public ServerSend(ObjectOutputStream oos) {
            this.oos = oos;
        }

        /**
         * Updates the fake PID of this connection
         */
        public void setConNum(int newNum) {
            this.conNum = newNum;
        }

        public void run() {
            try {
                // shuold be every heartbeat
                while (true) {
                    synchronized (lock) {
                        lock.wait();
                    }
                    int[] toSend = { state, serverNum, serverPort };
                    oos.writeObject(toSend);
                    oos.flush();
                }
            } catch (IOException e) {
                if (verbosity)
                    System.out.println(ERRORFLAG + " shutting down outputstream for " + conNum);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * Basically runs the server. It's outside of the main method because we need to
     * call nonstatic methods (I think?)
     * Sets bind of the registry
     */
    public void bind() {
        try {
            RMIClientSocketFactory rmiClientSocketFactory = new SslRMIClientSocketFactory();
            RMIServerSocketFactory rmiServerSocketFactory = new SslRMIServerSocketFactory();
            ServerInterface severus = (ServerInterface) UnicastRemoteObject.exportObject(this, 0,
                    rmiClientSocketFactory, rmiServerSocketFactory);

            Registry reg = LocateRegistry.getRegistry(port);
            if (verbosity) {
                System.out.println(SYSTEMFLAG + "Registry " + COMMANDC + "SET\n" + RESET);
            }

            reg.rebind("severus", severus);
            if (verbosity) {
                System.out.println(SYSTEMFLAG + "Registy Rebind " + COMMANDC + "SET\n" + RESET);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
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
                System.out.println("\n\n" + SYSTEMFLAG + " Starting IdSever at Host: localhost " + "Port: " + port);
            }

            System.setProperty("javax.net.ssl.keyStore", "Server_Keystore");
            System.setProperty("javax.net.ssl.keyStorePassword", "123456");
            System.setProperty("java.security.policy", "rmisslex2/resources/mysecurity.policy");

            // SSLServerSocketFactory sslSrvFact = (SSLServerSocketFactory)
            // SSLServerSocketFactory.getDefault();
            // SSLServerSocket ss = (SSLServerSocket) sslSrvFact.createServerSocket(port);

            IdServer server = new IdServer(serverFile);
            server.bind();

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
            serverOptions.addOption(new Option("v", "verbose", false, "Sets vebosity of server"));

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

                // Handles the numport option for port specification
                if (serverOption.getOpt().equals("s")) {
                    arg = serverOption.getOpt();
                    serverFile = serverCommand.getOptionValue(arg);
                    System.out.println(SYSTEMFLAG + " Setting server port to " + serverCommand.getOptionValue(arg));
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

// hello
