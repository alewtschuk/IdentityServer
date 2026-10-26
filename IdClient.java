import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.rmi.*;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Scanner;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.commons.cli.*;

public class IdClient {
    static String host = null;
    static int port = 5128;
    static Registry reg;
    static ServerInterface server;
    static boolean debug = false;

    final static String RESET = "\u001B[0m";
    final static String ERRORC = "\u001B[31m";
    final static String INFOC = "\u001B[32m";
    final static String DEBUGC = "\u001B[33m";
    final static String ANSI_PURPLE = "\u001B[35m";
    final static String ACCOUNTC = "\u001B[36m";
    final static String DEBUGFLAG = "[" + DEBUGC + "DEBUG" + RESET + "] ";
    final static String ERRORFLAG = "[" + ERRORC + "ERROR" + RESET + "] ";
    final static String INFOFLAG = "[" + INFOC + "INFO" + RESET + "]";

    // servers from the server file in file order, hostname -> RMI registry port
    static LinkedHashMap<String, Integer> serverHostMap = new LinkedHashMap<>();

    public static void main(String[] args) throws UnknownHostException{

        try {    

        CommandLine command = settupOptions(args);
        debug = command.hasOption("dx");
        if(debug){System.out.println(DEBUGFLAG + "Setting debug to " + debug);}


        setupSSL();
            
            
        String whichArg = getArg(command).toLowerCase();

        initServerConnection(command);

            
            

            //Required arg
            if (command.hasOption("server")) {
                System.out.println("== CLIENT RUNNING ==");
                System.out.println("Connecting to IdServer at host: " + host + " Port: " + port + "\n");
            } 

            //Get system IP
            InetAddress localHost = InetAddress.getLocalHost();
            String ipAddress = localHost.getHostAddress();

            performAction(command, whichArg, ipAddress);
            
        }
        
        catch (MissingOptionException e){
            System.err.println(ERRORC + "[ERROR]: " + RESET + "Client missing needed options.");
            System.err.println(INFOC +"USAGE: " + RESET + "IdClient <-s serverhost> [other commands]");
            System.exit(1);
        } catch (MissingArgumentException e){
            System.err.println(ERRORC + "[ERROR]: " + RESET + "-s missing needed argument.");
            System.err.println(INFOC +"USAGE: " + RESET + "IdClient <-s serverhost> [other commands]");
            System.exit(1);
        } catch (ParseException e) {
            if(debug){e.printStackTrace();}
            System.err.println(ERRORC + "[ERROR]: " + RESET + "Client ran into" + ERRORC + " PARSING" + RESET + " error in main");
        } catch (java.net.UnknownHostException e) {
            if(debug){e.printStackTrace();}
            System.err.println(ERRORC + "[ERROR]: " + RESET + "Client ran into" + ERRORC + " UNKNOWN-HOST" + RESET + " error in main");
        } catch (Exception e) {
            if(debug){e.printStackTrace();}
            System.err.println(ERRORC + "[ERROR]: " + RESET + "Client failed: " + e + " (run with -dx for details)");
            System.exit(1);
        }

 
    } //end main

    /**
     * Points SSL at the client truststore, the certificate the client trusts the
     * servers with. Checked in order:
     *   1. the CLIENT_TRUSTSTORE environment variable (a file path)
     *   2. a truststore bundled inside the client jar as the resource "/Client_Truststore"
     *   3. Client_Truststore in the working directory (how the client runs in Docker)
     */
    private static void setupSSL() {
        try {
            String truststorePath = System.getenv("CLIENT_TRUSTSTORE");
            if (truststorePath == null || truststorePath.isEmpty()) {
                truststorePath = extractBundledTruststore();
            }
            if (truststorePath == null) {
                truststorePath = "Client_Truststore";
            }
            if (!new File(truststorePath).isFile()) {
                throw new IllegalStateException("truststore not found at " + truststorePath + " (set CLIENT_TRUSTSTORE)");
            }
            if (debug) {System.out.println(DEBUGFLAG + "Using truststore " + truststorePath);}
            System.setProperty("javax.net.ssl.trustStore", truststorePath);
            System.setProperty("javax.net.ssl.trustStorePassword", "123456");
        } catch (Exception e) {
            if(debug){e.printStackTrace();}
            System.err.println(ERRORC + "[ERROR]: " + RESET + "Client failed: " + e + " client truststore not loaded");
            System.exit(1);
        }
    }

    /**
     * Copies the truststore bundled in the client jar to a temp file, because the
     * javax.net.ssl.trustStore property only accepts a file path
     * @return the temp file's path, or null if no truststore is bundled
     */
    private static String extractBundledTruststore() throws IOException {
        try (InputStream in = IdClient.class.getResourceAsStream("/Client_Truststore")) {
            if (in == null) return null;
            File tempTS = File.createTempFile("Client_Truststore", null);
            tempTS.deleteOnExit(); // remove file on exit
            Files.copy(in, tempTS.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return tempTS.getAbsolutePath();
        }
    }

    /**
     * Adds the cli options and setus up the cliParser
     * @param args
     * @return
     * @throws ParseException
     */
    public static CommandLine settupOptions(String[] args) throws ParseException{
        //Use Apache Cli to create cli options
        Options options = new Options();
        options.addRequiredOption("s", "server", true, "RMI Server hostname");
        options.addOption(new Option("n", "numport", true, "RMI Server port #"));
        options.addOption(new Option("c", "create", true, "Contact the server and attempts to create a new login name"));
        options.addOption(new Option("l", "lookup", true, "Looks up the loginname and displays all information associated with the UUID(password excluded)"));
        options.addOption(new Option("r", "reverse-lookup", true, "Looks up the UUID and displays all information associated with the UUID"));
        options.addOption(new Option("m", "modify", true, "Requests a loginname change"));
        options.addOption(new Option("d", "delete", true, "Requests the loginname deletion"));
        options.addOption(new Option("g", "get", true, "Obtains a list of all loginnames, all UUIDs or a list of user, UUID, and string descriptions of all accounts"));
        options.addOption(new Option("p", "password", true, "Optional argument for a password value"));
        options.addOption(new Option("dx", "debug", false, "Sets client debug on/off")); //Client debug option

        //Command Line Parser
        CommandLineParser cliParser = new DefaultParser();
        return cliParser.parse(options, args);
    }

    /**
     * Initializes the server connection 
     * @param command
     * @throws Exception
     */
    public static void initServerConnection(CommandLine command) throws Exception {
        String serverCommandArgument = command.getOptionValue("server");
    
        //If the server input contains a . and is a serverfile process the file
        if (serverCommandArgument.contains(".") && serverCommandArgument.endsWith("server")) {
            processServerFile(serverCommandArgument);
        } else if (serverCommandArgument.contains(".") && !serverCommandArgument.endsWith("server")) { //If the server input contains a . and is not a serverfile exit and inform user
            System.out.println(ERRORFLAG + "File type not recognized. Please use a \".server\" file");
            System.exit(1);
        } else { //No input file specified so the server input functions as in P2
            if (debug) {System.out.println(DEBUGFLAG + "No file input, setting host to " + serverCommandArgument);}
            if(server == null){
                host = serverCommandArgument; // Set the host directly if it's not a file
                reg = LocateRegistry.getRegistry(host, port);
                server = (ServerInterface) reg.lookup("IdServer");
            }
            if (debug && server != null) {System.out.println(DEBUGFLAG + "Server stub has been set");}
        }
    }
    
    
    /**
     * Parses the server file into serverHostMap. The file is a comma separated list of
     * host or host:port entries; a host without a port uses the -n port (default 5128)
     * @param serverFilePath
     */
    public static void processServerFile(String serverFilePath) {
        File inputFile = new File(serverFilePath);
        try (Scanner scanner = new Scanner(inputFile)) {
            scanner.useDelimiter(",");
            while (scanner.hasNext()) {
                String entry = scanner.next().trim();
                if (entry.isEmpty()) continue;
                String[] parts = entry.split(":");
                int entryPort = parts.length > 1 ? Integer.parseInt(parts[1].trim()) : port;
                serverHostMap.put(parts[0].trim(), entryPort);
            }
    
            if (serverHostMap.isEmpty()) {
                System.out.println(ERRORFLAG + "No valid server addresses found in file.");
                System.exit(1);
            }
            if (debug){System.out.println(DEBUGFLAG + "Addresses in serverHostMap: " + serverHostMap);}

        } catch (Exception e) {
            System.out.println(ERRORFLAG + "Problem reading server file: " + e.getMessage());
            System.exit(1);
        }
        connectToServer(); // Attempt to connect to servers from the list
    }
    

    /**
     * Connects to the coordinator. Servers from the file are tried in order; a replica
     * is asked for the coordinator's hostname and the next pass connects to it. Exits
     * if no server can be reached
     */
    public static void connectToServer() {
        List<String> hosts = new ArrayList<>(serverHostMap.keySet());
        String coordinator = null; // set once a replica tells us which server is the coordinator
        int next = 0; // index of the next server from the file to try
        int failures = 0;
        int defaultPort = port; // the -n port, for hosts listed without one

        while (failures < hosts.size()) {
            host = coordinator != null ? coordinator : hosts.get(next);
            // the coordinator's port comes from the server file; a host not in it uses the -n port
            port = serverHostMap.getOrDefault(host, defaultPort);
            if (debug) System.out.println(DEBUGFLAG + "trying this host: " + host + " with port: " + port);

            try {
                reg = LocateRegistry.getRegistry(host, port);
                server = (ServerInterface) reg.lookup("IdServer");
                if(debug){System.out.println(DEBUGFLAG + "Connected to " + host);}

                int state = server.getState();
                if(debug){System.out.println(DEBUGFLAG + "State: " + state);}

                // If election is in progress, wait briefly and recheck a few times
                if (state == 0) {
                    for (int retry = 0; retry < 10 && state == 0; retry++) {
                        try { Thread.sleep(500); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                        state = server.getState();
                    }
                    if(debug){System.out.println(DEBUGFLAG + "State after wait: " + state);}
                }

                switch (state) {
                    case 0: // election
                        System.out.println(ERRORFLAG + "ELECTION IN PROGRESS: TRYING AGAIN LATER." + host);
                        System.exit(0);
                    case 1: // replica: ask it who the coordinator is
                        if (coordinator != null) {
                            // we were sent here as the coordinator but it is a replica, so leadership moved
                            System.out.println(ERRORFLAG + "Coordinator changed while connecting: TRYING AGAIN LATER.");
                            System.exit(0);
                        }
                        coordinator = server.getCoordinator();
                        // brief retry for coordinator resolution
                        for (int retry = 0; retry < 10 && coordinator == null; retry++) {
                            try { Thread.sleep(500); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                            coordinator = server.getCoordinator();
                        }
                        if (coordinator == null) {
                            System.out.println(ERRORFLAG + "ELECTION IN PROGRESS: TRYING AGAIN LATER." + host);
                            System.exit(0);
                        }
                        System.out.println("Found Coordinator: " + coordinator);
                        continue; // next pass of the while loop connects to the coordinator
                    case 2: // coordinator: connected to the right server
                        return;
                    default:
                        System.out.println(ERRORFLAG + "Server " + host + " reported unknown state " + state);
                        System.exit(1);
                }

            } catch (RemoteException | NotBoundException e) {
                Throwable cause = e;
                while (cause.getCause() != null) cause = cause.getCause();
                System.out.println(ERRORFLAG + "Failed to connect to " + host + ". Attempt " + (failures + 1)
                        + " (" + cause.getMessage() + ")");
                // fall back to the next server in the file
                coordinator = null;
                next = (next + 1) % hosts.size();
                if (++failures == hosts.size()) {
                    System.out.println(ERRORFLAG + "All server attempts failed.");
                    break;
                }
                try {
                    // wait a second before trying the next server
                    Thread.sleep(1000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    System.out.println(ERRORFLAG + "Thread interrupted during backoff.");
                    break;
                }
            }
        }

        System.out.println(ERRORFLAG + "No available server found.");
        System.exit(1);
    }


    
    

    /**
     * Performs the requested action by connecting to the server with RMI
     * @param command the command to be sent to the server
     * @param whichArg
     * @param ipAddress
     * @throws RemoteException
     */
    public static void performAction(CommandLine command, String whichArg, String ipAddress){
        try {
            //Other required arg can be any of the following
            switch (whichArg) {
                    
                case "create":
                    System.out.println("Command " + whichArg + " called with value: " + command.getOptionValue("create"));
                    String loginname = command.getOptionValue("create"); //Sets username to the first argument of the create option
                    String realname = null; //Sets to null as no realname has been found
                    String password = command.getOptionValue("password"); //Sets password to the first argument of the password option

                    String [] remainingArgs = command.getArgs(); //Gets an array of left over unrecognized arguments for the command option
                    if(remainingArgs.length > 0){
                        realname = remainingArgs[0]; //Sets realname to the first arg passed after the required command arg
                    }
                    
                    System.out.println("Creating account with login name: " + loginname);
                    if (realname != null && !realname.isEmpty()) {
                        System.out.println("Real name: " + realname);
                    }
                    if (password != null && !password.isEmpty()) {
                        System.out.println("Password: " + password);
                    }

                    if(realname == null){
                        realname = System.getProperty("user.name");
                        System.out.println("No real name specified using the system username " + ACCOUNTC + realname + RESET + " as real name." + RESET);
                    }

                    String createOut;
                    if(password == null){
                        createOut = server.createAccount(loginname, realname, null, ipAddress);
                        System.out.print(ACCOUNTC + "[FROM SERVER]: ");
                        System.out.println(createOut);
                        System.out.print(RESET);
                    } else {
                        createOut = server.createAccount(loginname, realname, encrypt(password), ipAddress);
                        System.out.print(ACCOUNTC + "[FROM SERVER]: ");
                        System.out.println(createOut);
                        System.out.print(RESET);
                    }

                    break;
            
                case "lookup":
                    System.out.println("Command " + whichArg + " called with value: " + command.getOptionValue("lookup"));
                    String lookupOut = server.lookupUser(command.getOptionValue("lookup"), ipAddress);
                    System.out.print(ACCOUNTC + "[FROM SERVER]: ");
                    System.out.println(lookupOut);
                    System.out.print(RESET);
                    break;

                case "reverse-lookup":
                    System.out.println("Command " + whichArg + " called with value: " + command.getOptionValue("reverse-lookup"));
                    String revLookupOut = server.lookupUUID(command.getOptionValue("reverse-lookup"), ipAddress);
                    System.out.print(ACCOUNTC + "[FROM SERVER]: Account info found: ");
                    System.out.println(revLookupOut);
                    System.out.print(RESET);
                    break;

                case "modify":
                    System.out.println("Command " + whichArg + " called with value: " + command.getOptionValue("modify"));
                    String oldname = command.getOptionValue("modify"); //Sets username to the first argument of the modify option
                    String newname = null; //Sets to null as no realname has been found
                    password = command.getOptionValue("password"); //Sets password to the first argument of the password option

                    remainingArgs = command.getArgs(); //Gets an array of left over unrecognized arguments for the command option
                    if(remainingArgs.length > 0){
                        newname = remainingArgs[0]; //Sets realname to the first arg passed after the required command arg
                    }
                    
                    System.out.println("Modifying account with new login name: " + newname);
                    if (newname != null && !newname.isEmpty()) {
                        System.out.println("New name: " + newname);
                    }
                    if (password != null && !password.isEmpty()) {
                        System.out.println("Password: " + password);
                    }
                    String modifyOut = server.changeUsername(oldname, newname, encrypt(password), ipAddress);
                    System.out.print(ACCOUNTC + "[FROM SERVER]: ");
                    System.out.println(modifyOut);
                    System.out.print(RESET);
                    break;

                case "delete":
                    System.out.println("Command " + whichArg + " called with value: " + command.getOptionValue("delete"));

                    loginname = command.getOptionValue("delete");
                    password = command.getOptionValue("password");
                    String deleteOut = server.deleteAccount((loginname), encrypt(password));
                    System.out.print(ACCOUNTC + "[FROM SERVER]: ");
                    System.out.println(deleteOut);
                    System.out.print(RESET);
                    break;

                case "get":
                    System.out.println("Command " + whichArg + " called with value: " + command.getOptionValue("get"));

                    String getOpt = command.getOptionValue("get");
                    String getOut = server.getAccounts(getOpt);
                    System.out.print(ACCOUNTC + "[FROM SERVER]: ");
                    System.out.print(getOut);
                    System.out.print(RESET);
                    break;

                // case "password":
                //     System.out.println("Command " + whichArg + " called with value: " + command.getOptionValue("password"));
                //     break;

                default:
                    System.out.println("[DEFAULT]: No arg to be parsed");
                    break;
            }
        } catch (RemoteException e) {
            if(debug){e.printStackTrace();}
            System.err.println(ERRORC + "[ERROR]: " + RESET + "Client ran into" + ERRORC + " REMOTE-EXCEPTION" + RESET + " error in performAction()");
        }
    }



    /**
     * Loops through args and returns the long option. Only parses one option
     * @param options
     * @param command
     * @return the option
     */
    public static String getArg(CommandLine command){
        String arg = null;
        for(Option option : command.getOptions()){
            int numarg = 0;

            //Sets the host using the s arg
            if(option.getOpt().equals("s") && !option.getValue().contains(".")){
                arg = option.getLongOpt();
                if(debug){System.out.println(DEBUGFLAG + "Setting host to " + command.getOptionValue(arg));}
                host = command.getOptionValue(arg);
            }

            // if((option.getOpt().equals("s") && option.getValue().contains("."))){
            //     numarg++;
            //     if(debug){System.out.println(DEBUGFLAG + "Server input is a file: " + command.getOptionValue(arg));}
            // } else if(option.getOpt().equals("s")){
            //     arg = option.getLongOpt();
            //     if(debug){System.out.println(DEBUGFLAG + "Setting host to " + command.getOptionValue(arg));}
            //     host = command.getOptionValue(arg);
            // }


            //If the option is the required s do not pass to switch
            if(!option.getOpt().equals("s")){
                arg = option.getLongOpt();
                numarg++;
                if(debug){System.out.println(DEBUGFLAG + "Current arg is: " + arg);}
                if(option.getOpt().equals("dx")){
                    numarg--;
                }
            }

            //Handles the optional n arg for port number specification if not defaults to 5128
            if(option.getOpt().equals("n")){
                try {
                    if(debug){System.out.println(DEBUGFLAG + "Setting port to " + command.getOptionValue(arg));}
                    port = Integer.parseInt(command.getOptionValue(arg));
                    numarg = 0;
                } catch (Exception e) {
                    System.out.println(ERRORFLAG + "-n command missing port number");
                }
            }

            //If the num of needed args is 1 and the second option does not equal numport or verbose break and return
            if(numarg >= 1 && (!option.getOpt().equals("n") || !option.getOpt().equals("v"))) {
                if(debug){System.out.println(DEBUGFLAG + "Numarg is: " + numarg);}
                break;
            }
        }
        return arg;
    }

    /**
     * Provided encryption function for passwords
     * @param input
     * @return
     */
    private static byte[] encrypt(String input) {
        if(input == null) return null;
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-512");
            //System.out.println(md);
            byte[] bytes = input.getBytes();
            md.reset();

            byte[] result = md.digest(bytes);
            return result;
        } catch (NoSuchAlgorithmException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
            return null;
        }
    }

} //end class
