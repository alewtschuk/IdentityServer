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

    //internal server variables
    //private HashSet<IdAccount> accounts; CAN MAKE THIS A CACHE IN THE FUTURE
    private static boolean verbosity = false;
    static int port = 5128;
    static int redisPort = 6379;
    JedisPool pool = new JedisPool("localhost", redisPort);

    //server coordination variables
    Object lock = new Object();
    private final int serverNum = new Random().nextInt(10000) + 3;
    private int state;
    private int coordinator;

    private static String serverFile;
    private static ArrayList<String> serverHosts = new ArrayList<String>();
    private ArrayList<ServerListen> serverListeners = new ArrayList<ServerListen>();    

    private int heartrate = 2000;
    private int numServers = 0;
    private int serverPort = 5126;
    
    private ServerSocket servSock;
    private Inet4Address coordinatorAddy;
    private String myAddress;
    private HashSet<String> connections; //for debugging

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
     * The constructor establishes the server's bare running state, initiates connections to other servers, and opens communication to clients.
     * The constructor needs a filepath that contains a list of server IPs.
     * @param serverFile
     */
    public IdServer(String serverFile) throws RemoteException, IOException{
        processServerFile(serverFile);
        pool.getResource().configSet("protected-mode", "no");
        //we can uncomment this if we want to implement a cache or something
        //this.accounts = new HashSet<IdAccount>();
        Runtime.getRuntime().addShutdownHook(new DeathToServer());
        System.out.println(SYSTEMFLAG + " Server " + serverNum + " established:");

        //whenever a server is turned on, it triggers an election
        this.state = imPleb;
        this.coordinator = 0;
        
        //taking inspiration from hw2, this lock is used for the heartbeat and delays all ObjectOutputStream writes
        Timer timer = new Timer();
        TimerTask tt = new TimerTask() {
            public void run() {
                synchronized(lock) {
                    lock.notifyAll();
                }
            }
        };
        timer.scheduleAtFixedRate(tt, 0, heartrate);

        //handling all listening and sending threads
        this.connections = new HashSet<String>();
        this.servSock = new ServerSocket(serverPort);
        this.myAddress= InetAddress.getLocalHost().getHostAddress();
        if(verbosity) System.out.println(SYSTEMFLAG + "My address is " + myAddress);
        setupServerConnections();
    }

    /**
     * Establishes connections to all other servers that are alive, or sets up a listener for when they come alive.
     */
    public void setupServerConnections() {
            //connect to every server besides myself
            for(int i = 0; i < serverHosts.size(); i++) {
                serverListeners.add(new ServerListen(serverHosts.get(i), serverPort));
                if (!serverHosts.get(i).equals(myAddress)) serverListeners.get(i).start();
            }
    }
    /**
     * Parses the file to pull IP addresses into a List<String>
     * @param serverFilePath
     */
    public void processServerFile(String serverFilePath) {
        File inputFile = new File(serverFilePath);
        try (Scanner scanner = new Scanner(inputFile)) {
            scanner.useDelimiter(",");
            while (scanner.hasNext()) {
                String next = scanner.next();
                if (Pattern.matches(IP_PATTERN, next.trim())) {
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
    //INTERNAL METHODS FOR SERVER//             
    ///////////////////////////////

    /**
     * Writes an IdAccount account to the server's database. This method should only be called by a coordinator.
     * @param acc
     */
    private void writeAccount(IdAccount acc) {
        synchronized(this){
            try(Jedis jedis = pool.getResource()) {
                //creates an account object and writes
                Map <String, String> accMap = new HashMap<String, String>();
                accMap.put("username", acc.getUsername());
                accMap.put("realName", acc.getRealname());
                accMap.put("uuid", acc.getUuid().toString());
                accMap.put("dateCreated", acc.getDateCreated().toString());
                accMap.put("dateModified", acc.getDateModified().toString());
                accMap.put("original ip", acc.getOriginalIP());
                accMap.put("recent ip", acc.getRecentIp());
                jedis.hset(acc.getUsername(), accMap);
                jedis.hset(acc.getUuid().toString(), accMap);
                System.out.println("Account stored under \""+ acc.getUsername() + "\"");
                System.out.println("Account stored under \""+ acc.getUuid().toString() + "\"");

                System.out.println("Storing password under " + acc.getUuid().toString() + "p: " + acc.getPassword());
                //writes the password separately
                if (acc.getPassword() == null) jedis.set((acc.getUuid().toString() + "p").getBytes(), "null".getBytes());
                else jedis.set((acc.getUuid().toString() + "p").getBytes(), acc.getPassword());

                jedis.wait(numServers, heartrate);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        //debug prints
        if(verbosity) System.out.println( SYSTEMFLAG + " Account: "+ acc.getUsername() +" to database.");
    }

    //searches redis for entry of account by username
    /**
     * This method account reaches into this server's redis database and searches for an account with specified username.
     * This method should only be called by the coordinator.
     * @param username
     */
    private IdAccount readAccount(String username) {
        System.out.println("readAccount called for \"" + username + "\"");
        IdAccount skeeyyah;
        synchronized(this){
            try(Jedis jedis = pool.getResource()) {
                if(!jedis.exists(username)) return null;

                Map <String, String> accMap = jedis.hgetAll(username);
                System.out.println(accMap.get("username"));
                skeeyyah = new IdAccount(
                    accMap.get("username"),
                    accMap.get("realName"),
                    UUID.fromString(accMap.get("uuid")),
                    LocalDateTime.parse(accMap.get("dateCreated")),
                    LocalDateTime.parse(accMap.get("dateModified")),
                    null,
                    accMap.get("original ip"),
                    accMap.get("recent ip")
                );

                //This line is so hard to read -- if the password is not null, then get the its bytes from jedis
                if(!(new String(jedis.get((skeeyyah.getUuid().toString() + "p").getBytes())).equals("null"))) skeeyyah.setPassword(jedis.get((skeeyyah.getUuid().toString() + "p").getBytes()));
            }//end try()
        }//end sync
        return skeeyyah;
    }

    //checks redis for entry of accoutn with uuid
    /**
     * This method account reaches into this server's redis database and searches for an account with specified UUID.
     * This method should only be called by the coordinator.
     * @param uuid
     */
    private IdAccount readAccount(UUID uuid) {
        System.out.println("readAccount called for \"" + uuid.toString() + "\"");

        String uuidString = uuid.toString();
        IdAccount skeeyyah;
        synchronized(this){
            try(Jedis jedis = pool.getResource()) {
                if(!jedis.exists(uuidString)) return null;

                Map <String, String> accMap = jedis.hgetAll(uuidString);
                System.out.println(accMap.get("uuid"));
                skeeyyah = new IdAccount(
                    accMap.get("username"),
                    accMap.get("realName"),
                    UUID.fromString(accMap.get("uuid")),
                    LocalDateTime.parse(accMap.get("dateCreated")),
                    LocalDateTime.parse(accMap.get("dateModified")),
                    null,
                    accMap.get("original ip"),
                    accMap.get("recent ip")
                );

                //This line is so hard to read -- if the password is not null, then get the its bytes from jedis
                if(!(new String(jedis.get((skeeyyah.getUuid().toString() + "p").getBytes())).equals("null"))) skeeyyah.setPassword(jedis.get((uuidString + "p").getBytes()));
            }//end try()
        }//end sync
        return skeeyyah;
    }

    /**
     * This method returns an ArrayList of all IdAccounts in the redis database.
     * This method should only be called by the coordinator.
     * @return ArrayList of IdAccount
     */
    private ArrayList<IdAccount> getAllAccounts() {
        synchronized(this){
            try(Jedis jedis = pool.getResource()) {
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
            }//end try()
        }//end sync
    }

    /**
     * This method is called by the modify() method to delete the enture account under a specific username key.
     * Note that the modify method will rewrite the account under a new username key.
     * @param username
     */
    private void deleteUsernameEntry(String username) {
        synchronized(this){
            try(Jedis jedis = pool.getResource()) {
                jedis.del(username); //delete username entry
                jedis.wait(numServers, heartrate);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        if (verbosity) System.out.println(SYSTEMFLAG + "Account \"" + username + " removed");
    }

    /**
     * This method is called deleteAccount(username, password) that is called by the client. It permanently deletes an account and all its information.
     * @param username
     */
    private void deleteAccount(String username) { 
        synchronized(this){
            try(Jedis jedis = pool.getResource()) {
                IdAccount acc = readAccount(username);
                jedis.del(acc.getUsername()); //delete username entry
                jedis.del(acc.getUuid().toString()); //delete uuid entry
                jedis.del(acc.getUuid().toString() + "p"); //delete password entry
            }
        }
        if (verbosity) System.out.println(SYSTEMFLAG + "Account \"" + username + " removed");
    }

    /**
     * Checks the passwords to look for match
     * @param p1  password 1
     * @param p2 password 2
     * @return boolean true/false
     */
    private boolean checkPassword(byte[] p1, byte[] p2) {
        if (p1.length != p2.length) {
            return false;
        };
        //for(int i = 0; i < p1.length; i++) if(p1[i] != p2[i]) System.out.println("actual password: " + p1[i] + "\n given password:  " + p2[i]); //Debug line
        for(int i = 0; i < p1.length; i++) if(p1[i] != p2[i]) return false; //Java is cool too
        return true;
    }

    ////////////////////////
    //METHODS FOR CLIENT USE
    ////////////////////////


    /**
     * Getter for the server state [election || subordinate || coordinator]
     * @return state
     */
    public int getState() {
        return state;
    }
    
    /**
     * This method is called by the client for creating an account.
     * @param username
     * @param realname
     * @param password
     * @param ipAddress
     * @return String for client debugging
     */
    public String createAccount(String username, String realname, byte[] password, String ipAddress) {
        if(verbosity){System.out.println(SYSTEMFLAG + COMMANDC + " called --create" + RESET);}

        IdAccount acc = readAccount(username);
        if(acc != null){
            if(verbosity){System.out.println(ERRORFLAG + " Account with name " + ACCOUNTC + username + RESET + " already exists. Please choose another name.");}
            return ERRORC + " Account with name " + ACCOUNTC + username + RESET + " already exists. Please choose another name." + RESET;
        }
        

        IdAccount newAcc = new IdAccount(ipAddress);
        newAcc.setUsername(username);
        newAcc.setRealname(realname);
        newAcc.setPassword(password);

        if(realname == null){
            newAcc.setRealname(System.getProperty("user.name"));
        }

        if(verbosity){
            System.out.println(ACCOUNTC + " == ACCOUNT CREATED ==" + RESET);
            System.out.println(ACCOUNTC + newAcc.toString() + "\n" + RESET);
        }
        newAcc.setDateModified(java.time.LocalDateTime.now());
        newAcc.setRecentIp(ipAddress);

        //accounts.add(newAcc);
        writeAccount(newAcc);
        return ACCOUNTC + "Account " + username + " created on server." + RESET;
    }

    /**
     * Returns a string if the account is found or not based on username
     * @param username
     * @param recentIp
     * @return String for client debugging
     */
    public String lookupUser(String username, String recentIp) {
        if(verbosity){System.out.println(SYSTEMFLAG + COMMANDC + " called --lookup" + RESET);}

        IdAccount acc = readAccount(username);
        if(acc != null) {
            if(verbosity){System.out.println(SYSTEMFLAG + ACCOUNTC + "Account found: " + username + "\n" + RESET);}
            acc.setRecentIp(recentIp);
            return acc.toString();
        }
        
        if(verbosity){System.out.println(ERRORFLAG + ACCOUNTC + "Account not found: " + username + "\n" + RESET);}
        return ERRORC + "[ERROR] user " + username + " not found" + RESET;
    }

    /**
     * Looks up user and returns string based on UUID
     * @param uuid
     * @param recentIp
     * @return String for client debugging
     */
    public String lookupUUID(String uuid, String recentIp) {
        if(verbosity){System.out.println(SYSTEMFLAG + COMMANDC + " called --reverse-lookup" + RESET);}

        //the reason I turn this into a UUID and back to a string is because I haven't checked if it changes
        UUID temp = UUID.fromString(uuid);
        IdAccount acc = readAccount(temp);

        if(acc != null) {
            if(verbosity){System.out.println(SYSTEMFLAG + ACCOUNTC + "Account found: " + uuid + "\n" + RESET);}
            acc.setDateModified(java.time.LocalDateTime.now());
            return acc.toString();
        }
        
        if(verbosity){System.out.println(ERRORFLAG + ACCOUNTC + "Account not found: " + uuid + "\n" + RESET);}
        return ERRORC + "[ERROR] user " + uuid + "not found" + RESET;
    }

    /**
     * Modifies the username, checks passord if needed
     * @param oldName
     * @param newName
     * @param password
     * @param recentIp
     * @returns String for client debugging
     */
    public String changeUsername(String oldName, String newName, byte[] password, String recentIp) {
        if(verbosity){System.out.println(SYSTEMFLAG + COMMANDC + " called --modify" + RESET);}

        IdAccount acc = readAccount(newName);
        if(acc != null){
            if(verbosity){System.out.println(ERRORFLAG + " Account with name " + newName + " already exists. Please choose another name." + RESET);}
            acc.setDateModified(java.time.LocalDateTime.now());
            return ERRORC + "[ERROR] Account with name " + newName + " already exists. Please choose another name." + RESET;
        }
        
        acc = readAccount(oldName);
        System.out.println("password for " + acc.getUsername() + ": " + acc.getPassword());
        {
            if(verbosity){System.out.println(ERRORFLAG + ACCOUNTC + "Account found: " + oldName + "\n" + RESET);}
            if(password == null && acc.getPassword() != null){
                return ERRORC + "[ERROR] PASSWORD ASSOCIATED WITH ACCOUNT WAS NOT ENTERED" + RESET;
            } else if (acc.getPassword() == null){ 
                acc.setDateModified(java.time.LocalDateTime.now());
                acc.setUsername(newName); //NO PASSWORD
                writeAccount(acc);
                deleteUsernameEntry(oldName);
                if(verbosity){System.out.println(SYSTEMFLAG + ACCOUNTC + "No password associated with account." + RESET);}
                return ACCOUNTC + "Account " + oldName + " found and login name updated to " + newName;
            } else if (checkPassword(acc.getPassword(), password)){
                acc.setDateModified(java.time.LocalDateTime.now());
                acc.setUsername(newName);
                writeAccount(acc);
                deleteUsernameEntry(oldName);
                if(verbosity){System.out.println(SYSTEMFLAG + ACCOUNTC + "Password associated with account is correct." + RESET);}
                return ACCOUNTC + "Account " + oldName + " found and login name updated to " + newName;
            } else {
                acc.setDateModified(java.time.LocalDateTime.now());
                writeAccount(acc);
                return ERRORC + "[ERROR] PASSWORD DENIED" + RESET;
            }
        }
    }

    /**
     * Deletes user, checks if password is the same before delting, fails if no match
     * @param username
     * @param password
     * @return String for client debugging
     */
    public String deleteAccount(String username, byte[] password) {
        if(verbosity){System.out.println(SYSTEMFLAG + COMMANDC + " called --delete" + RESET);}

        IdAccount acc = readAccount(username);
        if(acc != null) {
            if(verbosity){System.out.println(SYSTEMFLAG+ACCOUNTC + "Account found: " + username + "\n" + RESET);}
            if(password == null && acc.getPassword() != null){
                return ERRORC + "[ERROR] PASSWORD ASSOCIATED WITH ACCOUNT WAS NOT ENTERED" + RESET;
            }else if(acc.getPassword() == null){
                //accounts.remove(acc);
                deleteAccount(username);
                return ACCOUNTC + "Account \"" + username + "\" removed." + RESET;
            }else if (checkPassword(acc.getPassword(), password)) {
                //accounts.remove(acc);
                deleteAccount(username);
                return ACCOUNTC + "Account \"" + username + "\" removed." + RESET;
            }else return ERRORC + "[ERROR] PASSWORD DENIED" + RESET;
        } 
        
        return ERRORC + "[ERROR] NO ACCOUNT FOUND OR ACCOUNT NOT CREATED" + RESET;
    }

    /**
     * Gets the accounts in the database on the server if with output depending on what you want to be returned
     * @param attribute
     * @return formatted String of info for client printing
     */
    public String getAccounts(String attribute) {
        if(verbosity == true){System.out.println(SYSTEMFLAG+COMMANDC + " called --get" + RESET);}
        String out = null;
        int i = 0;

        ArrayList<IdAccount> allAccounts = getAllAccounts();
        if (allAccounts.size() == 0) return ERRORC + "[ERROR] There are no accounts, yet..." + RESET;

        switch (attribute.toLowerCase()) {
            case "users":
                out = "Registered USERS:\n";
                for (IdAccount acc : allAccounts) out += ++i + ":   " + acc.getUsername() + "\n";
                break;
            case "uuids":
                out = "Registered UUIDS:\n";
                for (IdAccount acc : allAccounts) out += ++i + ":   " +  acc.getUuid() + "\n";
                break;
            case "all":
                out = "Registered Accounts:\n";
                for (IdAccount acc : allAccounts) out += ++i + ":   " + acc.toString() + "\n\n";
                break;
            default:
                out += ERRORC + "[ERROR] INVALID ARG WITH -get: " + attribute + RESET;
                break;
        }

        return out;
    }

    /**
     * gets the address of the coordinator, for client use
     * @return Inet4Address of coordinator
     */
    public Inet4Address getCoordinator() throws RemoteException {
        return coordinatorAddy;
    }

    /**
     * Sets verbosity of server
     * @param verbosity
     */
    public void setVerbose(boolean verbosity){
        verbosity = true;
        System.out.println(SYSTEMFLAG+"Verbsoity set to " + COMMANDC + verbosity + RESET);
    }

    /**
     * Shutdown hook handler
     */
    private class DeathToServer extends Thread {

        /**
         * Shutdown hook thread run method
         */
        public void run () {
            deathToRMI();
            System.out.println("\n\n" + SYSTEMFLAG + ERRORC + "!!!!SERVER SHUTTING DOWN!!!!" + RESET);
        }

        /**	
         * Unbinds the registry object	
         */	
        public void deathToRMI(){	
            try {	
                Registry rg = LocateRegistry.getRegistry(port);	
                rg.unbind("severus");	
                } catch (RemoteException e) {	
                    System.out.println(ERRORFLAG + "Error in deathToRMI(). Rmi failed to unbind due to RemoteException" + RESET);	
                } catch (NotBoundException e) {	
                    System.out.println(ERRORFLAG + "Error in deathToRMI(). Rmi failed to unbind due to NotBoundException" + RESET);	
                }	
        }
    }

    /**
     * A thread that either connects to a specific server IP or lists for that IP if it's not intially running
     */
    private class ServerListen extends Thread {
        private String connectionHost; //IP of connection
        private int connectionPort; //port of connection
        private int conNum;  //the fake PID for the servers
        private ServerSend toServer; //output stream thread
        private boolean replicateData; //only replicate the data when necessary

        /**
         * CONSTRUCTOR
         * @param host
         * @param servport
         */
        public ServerListen(String host, int servport) {
            this.connectionHost = host;
            this.connectionPort = servport;
            this.toServer = null;
            this.replicateData = true;
        }

        /**
         * Setter for connection IP
         * @param host
         */
        private void setConnectionHost(String host) {
            this.connectionHost = host;
            if(connectionHost.startsWith("/")) connectionHost = connectionHost.substring(1); 
        }

        /**
         * Setter for the port of the connection.
         * This was used for localhost running, but is no longer necessary when scalaing to multiple machines.
         */
        private void setConnectionPort(int portNum) {
            this.connectionPort = portNum;
        }

        public void run() {
            Socket sock;            
            while (true) {
                try {
                    //If the server is already running, connect to it. If it's not already running, wait for it to connect.
                    //Try-catch within a try-catch. This is what's wrong with society.
                    try {
                        if(connections.contains(connectionHost) || connectionHost.equals(myAddress) ) {
                            System.out.println(ERRORC + "TRYING TO RECONNECT TO " + connectionHost + RESET);
                            throw new Exception();
                        } 
                        if(verbosity) System.out.println("\n"+ SYSTEMFLAG + " trying " + connectionHost + ":" + connectionPort + " ....");
                        sock = new Socket(connectionHost, connectionPort);
                        if(verbosity) System.out.println(COMMANDC + " FOUND: " + connectionHost + ":" + connectionPort + RESET);
                        numServers++;
                    } catch (Exception e) {
                        if(verbosity) System.out.println(ERRORFLAG + " server " + connectionHost + ":" + connectionPort + " not found, listening for future connection...");
                        Thread.sleep(100);
                        if(numServers == 0) {
                            state = imBoss;
                            coordinator = serverNum;
                            pool.getResource().replicaofNoOne();
                            System.out.println(SYSTEMFLAG + " I'M THE BOSS (" + serverNum + ")");
                        }
                        sock = servSock.accept();
                        numServers++;
                        System.out.println(COMMANDC + "CONNECTED TO " + sock.getInetAddress() + " port: " + sock.getPort() + RESET);
                    }

                    setConnectionHost(sock.getInetAddress().toString());
    
                    ObjectOutputStream out = new ObjectOutputStream(sock.getOutputStream());
                    ObjectInputStream in = new ObjectInputStream(sock.getInputStream());
                    
                    //automatically sets up output stream
                    toServer = new ServerSend(out);
                    toServer.start();
    
                    //the rest of the method deals with the input stream
                    int[] read = ((int[]) in.readObject());
                    int readState = read[0];
                    conNum = read[1];
                    toServer.setConNum(conNum);
                    connections.add(sock.getInetAddress().toString());
                    while(true) {
                        read = ((int[]) in.readObject());
                        
                        readState = read[0];
                        conNum = read[1];
                        setConnectionPort(read[2]);
                        // System.out.println("received fsrom " + read[1] + ":  " + readState);
                        if(readState == imBoss){
                            coordinator = conNum;
                            coordinatorAddy = (Inet4Address) InetAddress.getByName(connectionHost);

                            if(replicateData) {
                                String redisHost = coordinatorAddy.toString().startsWith("/") ? coordinatorAddy.toString().substring(1) : coordinatorAddy.toString();
                                pool.getResource().replicaof(redisHost, redisPort);
                                if(verbosity) System.out.println(SYSTEMFLAG + "my redis is now a replica of " + redisHost + ":" + redisPort);
                            }
                            this.replicateData = false;
                            // System.out.println("Right after replicaOf");
                        }
                        //part of the bully algorithm
                        else if(readState == election) {
                            state = election;
                            this.replicateData = true;
                            if(conNum > serverNum) {
                                state = imPleb;
                                if(conNum > coordinator) coordinator = conNum;
                                System.out.println(SYSTEMFLAG + " I'm a pleb (" + serverNum + " < " + conNum + ")");
                            }
                        }

                        //for debugging
                        // if(state == imPleb && replicateData == false) {
                        //     try (Jedis jedis = pool.getResource()) {
                        //     // Execute the INFO command specifically for replication
                        //     // String replicationInfo = jedis.info("replication");
                
                        //     // Output the replication information
                        //     System.out.println(replicationInfo);
                        //     }
                        // }



                    }
    
                } catch (IOException e) {
                    System.out.println(ERRORFLAG + " Server " + connectionHost + ":" + connectionPort + " disconnected");
                    // System.out.println("conNum that died: " + conNum + "  coordinator: " + coordinator);
                    numServers--;
                 
                    if(conNum == coordinator) {
                        if(verbosity) System.out.println(SYSTEMFLAG + " Election triggered!");
                        state = election;
                    }
                    connections.remove(connectionHost);
                } catch (ClassNotFoundException e) {
                    System.out.println(ERRORC + "Object read was not an array of integers" + RESET);
                    e.printStackTrace();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }

        
    }
    /**
     * A thread that handles the output stream of a ServerListen thread
     */
    private class ServerSend extends Thread {
        private ObjectOutputStream oos;
        private int sentElection; //flag to make sure that a new coordinator waits for everyone to get their votes in
        private int conNum; //the fake PID for the servers

        /**
         * CONSTRUCTOR
         */
        public ServerSend(ObjectOutputStream oos) {
            this.oos = oos;
            this.sentElection = 0;
            // this.running = true;
        }
        /**
         * Updates the fake PID of this connection
         */
        public void setConNum(int newNum) {
            this.conNum = newNum;
        }
        // public void kill() {
        //     this.running = false;
        // }

        public void run() {
            try {
                //shuold be every heartbeat
                while(true) {
                    synchronized(lock) {
                        lock.wait();
                    }
                    
                    //part of the bully algorithm
                    if (sentElection == 2 && state == election){
                        state = imBoss;
                        coordinator = serverNum;
                        pool.getResource().replicaofNoOne();
                        System.out.println(SYSTEMFLAG + " I'M THE BOSS (" + serverNum + ")");
                        System.out.println(SYSTEMFLAG + " My redis is now independenta");
                    }
                    sentElection %= 2;

                    int[] toSend = {state, serverNum, serverPort};
                    oos.writeObject(toSend);
                    oos.flush();
                    // System.out.println("SENT TO " + conNum + ":  " + state);
                    if(state == election) sentElection++;
                }
            } catch (IOException e) {
                // this.running = false;
                System.out.println(ERRORFLAG + " shutting down outputstream for " + conNum);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }
    
    

    /**
     * Basically runs the server. It's outside of the main method because we need to call nonstatic methods (I think?)
     * Sets bind of the registry
     */
    public void bind() {
        try {
            RMIClientSocketFactory rmiClientSocketFactory = new SslRMIClientSocketFactory();
            RMIServerSocketFactory rmiServerSocketFactory = new SslRMIServerSocketFactory();
            ServerInterface severus = (ServerInterface) UnicastRemoteObject.exportObject(this, 0, rmiClientSocketFactory, rmiServerSocketFactory);
            
            Registry reg = LocateRegistry.getRegistry(port);
            if(verbosity){System.out.println(SYSTEMFLAG + "Registry " + COMMANDC + "SET\n" + RESET);}
            
            reg.rebind("severus", severus);
            if(verbosity){System.out.println(SYSTEMFLAG + "Registy Rebind " + COMMANDC + "SET\n" + RESET);}
        } catch (Exception e) {
            e.printStackTrace();
        }
    }


    //////////
    // MAIN //
    //////////
    /**
     * Main server method
     * @param args
     */
    public static void main(String[] args) {
        try {
                        
            handleServerArgs(args);
            if(verbosity){System.out.println("\n\n" + SYSTEMFLAG + " Starting IdSever at Host: localhost " + "Port: " + port);}

            System.setProperty("javax.net.ssl.keyStore", "Server_Keystore");
            System.setProperty("javax.net.ssl.keyStorePassword", "123456");
            System.setProperty("java.security.policy", "rmisslex2/resources/mysecurity.policy");

            // SSLServerSocketFactory sslSrvFact = (SSLServerSocketFactory) SSLServerSocketFactory.getDefault();
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
     * @param args
     */
    public static void handleServerArgs(String[] args){
        try {
            Options serverOptions = new Options();

            serverOptions.addOption(new Option("s", "serverHosts", true, "Server addresses"));
            serverOptions.addOption(new Option("n", "numport", true, "Server port #"));
            serverOptions.addOption(new Option("v", "verbose", false, "Sets vebosity of server"));

            CommandLineParser serverParser = new DefaultParser();
            CommandLine serverCommand = serverParser.parse(serverOptions, args);

            String arg = null;
            for(Option serverOption : serverCommand.getOptions()){
            
                //Handles the numport option for port specification
                if(serverOption.getOpt().equals("n")){
                    arg = serverOption.getOpt();
                    port = Integer.parseInt(serverCommand.getOptionValue(arg));
                    System.out.println(SYSTEMFLAG + " Setting server port to " + serverCommand.getOptionValue(arg));
                }

                //Handles the numport option for port specification
                if(serverOption.getOpt().equals("s")){
                    arg = serverOption.getOpt();
                    serverFile = serverCommand.getOptionValue(arg);
                    System.out.println(SYSTEMFLAG + " Setting server port to " + serverCommand.getOptionValue(arg));
                }
                
                //Handles the verbosity specification
                if(serverOption.getOpt().equals("v")){
                    verbosity = true;
                    System.out.println(SYSTEMFLAG + " Verbosity set to " + COMMANDC + verbosity + RESET);
                }
            }
        } catch (ParseException e) {
            e.printStackTrace();
        }
    }

}

//hello