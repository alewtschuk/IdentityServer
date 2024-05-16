import java.util.UUID;

import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;
import javax.rmi.ssl.SslRMIClientSocketFactory;
import javax.rmi.ssl.SslRMIServerSocketFactory;

import java.rmi.server.RMIClientSocketFactory;
import java.rmi.server.RMIServerSocketFactory;

import org.apache.commons.cli.*;

import java.io.IOException;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

import redis.clients.jedis.*;

public class IdServer implements ServerInterface {

    private static boolean verbosity = false;
    private HashSet<IdAccount> accounts;
    static int port = 5128; //our assigned port 
    JedisPool pool = new JedisPool("localhost", 6379);

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
 
    //constructor
    public IdServer() throws RemoteException{
        this.accounts = new HashSet<IdAccount>();
        Runtime.getRuntime().addShutdownHook(new DeathToServer());
        SavePeriodically saveThread = new SavePeriodically();
        saveThread.start();
        readData();
    }

    /////////////////////////////
    //INTERNAL METHODS FOR SERVER
    /////////////////////////////

    /**
     * Writes the data to the database
     */
    private void writeData() {

        synchronized(this){
            try(Jedis jedis = pool.getResource()){
                jedis.flushAll(); //I know this isn't scalable, but I don't have all day
                int i = 0;
                for(IdAccount acc : accounts) {
                    Map <String, String> accMap = new HashMap<String, String>();
                    accMap.put("username", acc.getUsername());
                    accMap.put("realName", acc.getRealname());
                    accMap.put("uuid", acc.getUuid().toString());
                    accMap.put("dateCreated", acc.getDateCreated().toString());
                    accMap.put("dateModified", acc.getDateModified().toString());
                    accMap.put("original ip", acc.getOriginalIP());
                    accMap.put("recent ip", acc.getRecentIp());
                    jedis.hset("account" + ++i, accMap);
    
                    if (acc.getPassword() == null) jedis.set(("accountp" + i).getBytes(), "null".getBytes());
                    else jedis.set(("accountp" + i).getBytes(), acc.getPassword());
                }
            }
            if(verbosity) System.out.println(  SYSTEMFLAG + " Data written to database.");
            if(accounts.size() == 0 && verbosity) System.out.println(SYSTEMFLAG + ERRORFLAG + " No accounts currently exist, database is empty.");
            if(accounts.size() == 0 && verbosity) System.out.println(SYSTEMFLAG + " Above message is an ONLY if accounts should be present on system.");
        }
    }

    /**
     * Reads data from database
     */
    private void readData() {
        HashSet<IdAccount> accountsRead = new HashSet<IdAccount>();
        try(Jedis jedis = pool.getResource()){   
            //jedis.flushAll(); //used to delete all accounts when the devs screw up the backend
            int i = 0;
            //loop through accounts, starting at 1
            while (jedis.hgetAll("account" + ++i).size() > 0) {
                Map <String, String> accMap = jedis.hgetAll("account" + i);;
                System.out.println(accMap.toString());
                IdAccount skeeyyah = new IdAccount(
                    accMap.get("username"),
                    accMap.get("realName"),
                    UUID.fromString(accMap.get("uuid")),
                    LocalDateTime.parse(accMap.get("dateCreated")),
                    LocalDateTime.parse(accMap.get("dateModified")),
                    null,
                    accMap.get("original ip"),
                    accMap.get("recent ip")
                );

                //This line is so hard to read.
                //If the password is not null, then get the its bytes from jedis
                if(!(new String(jedis.get(("accountp" + i).getBytes())).equals("null"))) skeeyyah.setPassword(jedis.get(("accountp" + i).getBytes()));

                accountsRead.add(skeeyyah);
            }
        }
        accounts = accountsRead;
        if(verbosity) System.out.println(SYSTEMFLAG + " Database read has been executed");
        if(verbosity) System.out.println(SYSTEMFLAG + " There are " + accounts.size() + "accounts.");
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
     * Creates the account
     */
    public String createAccount(String username, String realname, byte[] password, String ipAddress) {
        if(verbosity){System.out.println(SYSTEMFLAG + COMMANDC + " called --create" + RESET);}

        for (IdAccount acc : accounts){
            if(acc.getUsername().equals(username)){
                if(verbosity){System.out.println(ERRORFLAG + " Account with name " + ACCOUNTC + username + RESET + " already exists. Please choose another name.");}
                return ERRORC + " Account with name " + ACCOUNTC + username + RESET + " already exists. Please choose another name." + RESET;
            }
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

        accounts.add(newAcc);
        writeData();
        return ACCOUNTC + "Account " + username + " created on server." + RESET;
    }

    /**
     * Returns a string if the account is found or not based on username
     */
    public String lookupUser(String username, String recentIp) {
        if(verbosity){System.out.println(SYSTEMFLAG + COMMANDC + " called --lookup" + RESET);}

        for (IdAccount acc : accounts) {
            if(acc.getUsername().equals(username)) {
                if(verbosity){System.out.println(SYSTEMFLAG + ACCOUNTC + "Account found: " + username + "\n" + RESET);}
                acc.setRecentIp(recentIp);
                return acc.toString();
            }
        }
        if(verbosity){System.out.println(ERRORFLAG + ACCOUNTC + "Account not found: " + username + "\n" + RESET);}
        return ERRORC + "[ERROR] user " + username + " not found" + RESET;
    }

    /**
     * Looks up user and returns string based on UUID
     */
    public String lookupUUID(String uuid, String recentIp) {
        if(verbosity){System.out.println(SYSTEMFLAG + COMMANDC + " called --reverse-lookup" + RESET);}
        UUID temp = UUID.fromString(uuid);

        for (IdAccount acc : accounts) {
            if(acc.getUuid().equals(temp)) {
                if(verbosity){System.out.println(SYSTEMFLAG + ACCOUNTC + "Account found: " + uuid + "\n" + RESET);}
                acc.setDateModified(java.time.LocalDateTime.now());
                return acc.toString();
            } else{
                acc.setDateModified(java.time.LocalDateTime.now());
            }
        }
        if(verbosity){System.out.println(ERRORFLAG + ACCOUNTC + "Account not found: " + uuid + "\n" + RESET);}
        return ERRORC + "[ERROR] user " + uuid + "not found" + RESET;
    }

    /**
     * Modifies the username, checks passord if needed
     */
    public String changeUsername(String oldName, String newName, byte[] password, String recentIp) {
        if(verbosity){System.out.println(SYSTEMFLAG + COMMANDC + " called --modify" + RESET);}

        for (IdAccount acc : accounts){
            if(acc.getUsername().equals(newName)){
                if(verbosity){System.out.println(ERRORFLAG + " Account with name " + newName + " already exists. Please choose another name." + RESET);}
                acc.setDateModified(java.time.LocalDateTime.now());
                return ERRORC + "[ERROR] Account with name " + newName + " already exists. Please choose another name." + RESET;
            }
        }

        for (IdAccount acc : accounts) {
            if(acc.getUsername().equals(oldName)) {
                if(verbosity){System.out.println(ERRORFLAG + ACCOUNTC + "Account found: " + oldName + "\n" + RESET);}
                if(password == null && acc.getPassword() != null){
                    return ERRORC + "[ERROR] PASSWORD ASSOCIATED WITH ACCOUNT WAS NOT ENTERED" + RESET;
                } else if (acc.getPassword() == null){ 
                    acc.setDateModified(java.time.LocalDateTime.now());
                    acc.setUsername(newName); //NO PASSWORD
                    if(verbosity){System.out.println(SYSTEMFLAG + ACCOUNTC + "No password associated with account." + RESET);}
                    return ACCOUNTC + "Account " + oldName + " found and login name updated to " + newName;
                } else if (checkPassword(acc.getPassword(), password)){
                    acc.setDateModified(java.time.LocalDateTime.now());
                    acc.setUsername(newName);
                    if(verbosity){System.out.println(SYSTEMFLAG + ACCOUNTC + "Password associated with account is correct." + RESET);}
                    return ACCOUNTC + "Account " + oldName + " found and login name updated to " + newName;
                } else {
                    acc.setDateModified(java.time.LocalDateTime.now());
                    return ERRORC + "[ERROR] PASSWORD DENIED" + RESET;
                }
            } else{
                acc.setDateModified(java.time.LocalDateTime.now());
            }
        }
        writeData();
        return ERRORC + "[ERROR] NO ACCOUNT FOUND OR ACCOUNT NOT CREATED" + RESET;
    }

    /**
     * Deletes user, checks if password is the same before delting, fails if no match
     */
    public String deleteAccount(String username, byte[] password) {
        if(verbosity){System.out.println(SYSTEMFLAG + COMMANDC + " called --delete" + RESET);}
        
        for (IdAccount acc : accounts) {
            if(acc.getUsername().equals(username)) {
                if(verbosity){System.out.println(SYSTEMFLAG+ACCOUNTC + "Account found: " + username + "\n" + RESET);}
                if(password == null && acc.getPassword() != null){
                    return ERRORC + "[ERROR] PASSWORD ASSOCIATED WITH ACCOUNT WAS NOT ENTERED" + RESET;
                }else if(acc.getPassword() == null){
                    accounts.remove(acc);
                    writeData();
                    return ACCOUNTC + "Account \"" + username + "\" removed." + RESET;
                }else if (checkPassword(acc.getPassword(), password)) {
                    accounts.remove(acc);
                    writeData();
                    return ACCOUNTC + "Account \"" + username + "\" removed." + RESET;
                }else return ERRORC + "[ERROR] PASSWORD DENIED" + RESET;
            } 
        }
        return ERRORC + "[ERROR] NO ACCOUNT FOUND OR ACCOUNT NOT CREATED" + RESET;
    }

    /**
     * Gets the accounts in the database on the server if with output depending on what you want to be returned
     */
    public String getAccounts(String attribute) {
        if(verbosity == true){System.out.println(SYSTEMFLAG+COMMANDC + " called --get" + RESET);}
        if(accounts.size() == 0) return ERRORC + "[ERROR] There are no accounts, yet..." + RESET;
        String out = null;
        int i = 0;
        switch (attribute.toLowerCase()) {
            case "users":
                out = "Registered USERS:\n";
                for (IdAccount acc : accounts) out += ++i + ":   " + acc.getUsername() + "\n";
                break;
            case "uuids":
                out = "Registered UUIDS:\n";
                for (IdAccount acc : accounts) out += ++i + ":   " +  acc.getUuid() + "\n";
                break;
            case "all":
                out = "Registered Accounts:\n";
                for (IdAccount acc : accounts) out += ++i + ":   " + acc.toString() + "\n\n";
                break;
            default:
                out += ERRORC + "[ERROR] INVALID ARG WITH -get: " + attribute + RESET;
                break;
        }

        return out;
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
        public void run () {
            System.out.println("\n\n" + SYSTEMFLAG + ERRORC + "!!!!SERVER SHUTTING DOWN!!!!" + RESET);
            writeData();
        }
    }

    /**
     * Saves the database in intervals in case of shutdown
     */
    public class SavePeriodically extends Thread{
        long time = System.currentTimeMillis();

        public void run () {
            while (true) {
                if(System.currentTimeMillis() - time > 180000) {
                    writeData();
                    time = System.currentTimeMillis();
                }
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

            SSLServerSocketFactory sslSrvFact = (SSLServerSocketFactory) SSLServerSocketFactory.getDefault();
            SSLServerSocket ss = (SSLServerSocket) sslSrvFact.createServerSocket(port);

            IdServer server = new IdServer();
            server.bind();
           
        } catch (RemoteException e) {
            e.printStackTrace();
        } catch (IOException e) {
            // TODO Auto-generated catch block
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
                
                //Handles the verbosity specification
                if(serverOption.getOpt().equals("v")){
                    verbosity = true;
                    System.out.println(SYSTEMFLAG + " Verbosity set to " + COMMANDC + verbosity + RESET);
                }
            }
        } catch (ParseException e) {
            // TODO: handle exception
            e.printStackTrace();
        }
    }
}