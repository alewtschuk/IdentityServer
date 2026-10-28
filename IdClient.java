import java.io.File;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.rmi.*;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.RMISocketFactory;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;
import java.util.prefs.Preferences;

import org.apache.commons.cli.*;

public class IdClient {
    static Preferences prefs = Preferences.userNodeForPackage(IdClient.class);
    private final static String SAVED_COORD_ADDR = "coordinatorAddress";
    static String host = null;
    static int port = 5128;
    static String coordAddr = prefs.get(SAVED_COORD_ADDR, "none");
    static Registry reg;
    static ServerInterface server;
    static boolean debug = false;

    final static String RESET = "\u001B[0m";
    final static String ERRORC = "\u001B[31m";
    final static String INFOC = "\u001B[32m";
    final static String DEBUGC = "\u001B[33m";
    final static String ACCOUNTC = "\u001B[36m";
    final static String DEBUGFLAG = "[" + DEBUGC + "DEBUG" + RESET + "] ";
    final static String ERRORFLAG = "[" + ERRORC + "ERROR" + RESET + "] ";

    static List<String> serverHostList = new ArrayList<>(); //List to hold serverHost addresses

    /**
     * Helper method to extract and return the type of error that occured as a string.
     * @param e
     * @return Returns the simple name of the error
     */
    public static String getErrorName(Exception e) {
        String errorType = e.getClass().getSimpleName();
        return errorType;
    }

    /**
     * Prints every client command and option
     */
    public static void printUsage() {
        System.err.println(INFOC + "USAGE: " + RESET + "IdClient -s <host | file.server> [-n <port>] <command> [-dx]");
        System.err.println("Commands (one per run):");
        System.err.println("  -c, --create <loginname> [realname] [-p <password>]  Create an account (real name defaults to the system user)");
        System.err.println("  -l, --lookup <loginname>                            Show the account with this login name");
        System.err.println("  -r, --reverse-lookup <uuid>                         Show the account with this UUID");
        System.err.println("  -m, --modify <oldname> <newname> [-p <password>]    Change an account's login name");
        System.err.println("  -d, --delete <loginname> [-p <password>]            Delete an account");
        System.err.println("  -g, --get <users | uuids | all>                     List all login names, all UUIDs, or every account");
        System.err.println("Options:");
        System.err.println("  -s, --server <host | file.server>  Server hostname or IP, or a .server file listing the cluster (required)");
        System.err.println("  -n, --numport <port>               RMI registry port (default 5128)");
        System.err.println("  -p, --password <password>          Password for create, modify or delete");
        System.err.println("  -dx, --debug                       Print client debug output");
    }

    public static void main(String[] args) throws UnknownHostException{

        try {    

        CommandLine command = setupOptions(args);
        debug = command.hasOption("dx");
        if(debug){System.out.println(DEBUGFLAG + "Setting debug to " + debug);}


        setupSSL();
        setupConnectTimeout();
            
            
        String whichArg = getArg(command);
        // getArg can return a non-command option (server, numport, debug) when no command was given
        if (whichArg == null || !List.of("create", "lookup", "reverse-lookup", "modify", "delete", "get").contains(whichArg.toLowerCase())) {
            System.err.println(ERRORFLAG + "No command given.");
            printUsage();
            System.exit(1);
        }
        whichArg = whichArg.toLowerCase();

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
            System.err.println(ERRORFLAG + "Client missing needed options.");
            printUsage();
            System.exit(1);
        } catch (MissingArgumentException e){
            System.err.println(ERRORFLAG + "-s missing needed argument.");
            printUsage();
            System.exit(1);
        } catch (ParseException | java.net.UnknownHostException e) {
            if(debug){e.printStackTrace();}
            System.err.println(ERRORFLAG + "Client ran into " + ERRORC + getErrorName(e) + RESET + " error in main");
        } catch (Exception e) {
            if(debug){e.printStackTrace();}
            System.err.println(ERRORFLAG + "Client failed: " + e + " (run with -dx for details)");
            System.exit(1);
        }

 
    } //end main

    /**
     * Sets up the SSL system properties
     */
    private static void setupSSL() {
        System.setProperty("javax.net.ssl.trustStore", "Client_Truststore");
        System.setProperty("javax.net.ssl.trustStorePassword", "123456");
    }

    /**
     * Gives RMI registry connections a short connect timeout. Without it, a saved
     * coordinator address whose server went down can hang for the OS default
     * TCP timeout before the client moves on to the server list.
     */
    private static void setupConnectTimeout() throws IOException {
        RMISocketFactory.setSocketFactory(new RMISocketFactory() {
            public Socket createSocket(String host, int port) throws IOException {
                Socket socket = new Socket();
                socket.connect(new InetSocketAddress(host, port), 3000);
                return socket;
            }

            public ServerSocket createServerSocket(int port) throws IOException {
                return new ServerSocket(port);
            }
        });
    }

    /**
     * Adds the cli options and sets up the cliParser
     * @param args
     * @return
     * @throws ParseException
     */
    public static CommandLine setupOptions(String[] args) throws ParseException{
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
    
        //If the server input is a .server file process the file
        if (serverCommandArgument.endsWith(".server")) {
            processServerFile(serverCommandArgument);
        } else { //Otherwise it is a single hostname or IP, connected to like a one-entry server file
            if (debug) {System.out.println(DEBUGFLAG + "No file input, setting host to " + serverCommandArgument);}
            serverHostList.add(serverCommandArgument);
            connectToServer();
        }
    }
    
    
    /**
     * Parses the file to pull IP addresses into a List<String>
     * @param serverFilePath
     */
    public static void processServerFile(String serverFilePath) {
        File inputFile = new File(serverFilePath);
        try (Scanner scanner = new Scanner(inputFile)) {
            scanner.useDelimiter(",");
            while (scanner.hasNext()) {
                String next = scanner.next();
                serverHostList.add(next);
            }
    
            if (serverHostList.isEmpty()) {
                System.out.println(ERRORFLAG + "No valid server addresses found in file.");
                System.exit(1);
            }
            if (debug){System.out.println(DEBUGFLAG + "Addresses in serverHostList: " + serverHostList);}
            connectToServer(); // Attempt to connect to servers from the list

        } catch (Exception e) {
            System.out.println(ERRORFLAG + "Problem reading server file: " + e.getMessage());
            System.exit(1);
        }
    }
    

    /**
     * Attempts server connection with retry mechanism
     */
    public static void connectToServer() {
        boolean isConnected = false;
        int attempts = 0;
        boolean staleRetried = false; // the free retry below is only for an address saved by a previous run
        String serverHost;
        for(int i = 0; i < serverHostList.size(); i++) {
            
            try {
                if (debug) System.out.println(DEBUGFLAG + "coordinator address currently set to: " + coordAddr);
                if(!coordAddr.equals("none")) serverHost = coordAddr;
                else serverHost = serverHostList.get(i);

                if (debug) System.out.println(DEBUGFLAG + "trying this host: " + serverHost);
                host = serverHost.trim();

                reg = LocateRegistry.getRegistry(host, port);
                server = (ServerInterface) reg.lookup("IdServer");
                String[] boundNames = reg.list();
                if(debug){System.out.println(DEBUGFLAG + "Bound names: " + String.join(", ", boundNames));}
                isConnected = true;
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
                        Inet4Address temp = server.getCoordinator();
                        if (temp == null) {
                            // brief retry for coordinator resolution
                            for (int retry = 0; retry < 10 && temp == null; retry++) {
                                try { Thread.sleep(500); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                                temp = server.getCoordinator();
                            }
                            if (temp == null) {
                                System.out.println(ERRORFLAG + "ELECTION IN PROGRESS: TRYING AGAIN LATER." + host);
                                System.exit(0);
                            }
                        }

                        coordAddr = temp.toString();
                        if(coordAddr.startsWith("/")) coordAddr = coordAddr.substring(1);
                        System.out.println("Found Coordinator Address: " + coordAddr);
                        i = serverHostList.size() - 2;
                        if (!coordAddr.equals(temp.toString())) {
                            prefs.put(SAVED_COORD_ADDR, coordAddr);
                            if (debug) System.out.println(DEBUGFLAG + "coordinator address saved: " + coordAddr);
                        } else {
                            break;
                        }
                        break;
                    case 2: // coordinator
                        i = serverHostList.size() - 1;
                }


            } catch (Exception e) {
                // If there is an exception caught make sure the first thing done is to remove the saved coordinator address.
                if (!coordAddr.equals("none")) {
                    if (debug) System.out.println(DEBUGFLAG + "coordinator address reset due to connection failure") ;
                    coordAddr = "none";
                    prefs.remove(SAVED_COORD_ADDR);
                    // a stale saved address is not a failed attempt: retry this slot from the list once
                    if (!staleRetried) {
                        staleRetried = true;
                        i--;
                        continue;
                    }
                }

                Throwable cause = e;
                while (cause.getCause() != null) cause = cause.getCause();
                System.out.println(ERRORFLAG + "Failed to connect to " + host + ". Attempt " + (attempts + 1) + " (" + cause.getMessage() + ")");
                if (++attempts == serverHostList.size()) {
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

        if (!isConnected) {
            System.out.println(ERRORFLAG + "No available server found.");
            System.exit(1);
        }
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
                    System.out.print(ACCOUNTC + "[FROM SERVER]: ");
                    if (revLookupOut.startsWith("FOR USER:")) System.out.print("Account info found ");
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

                default:
                    System.out.println("[DEFAULT]: No arg to be parsed");
                    break;
            }
        } catch (RemoteException e) {
            if(debug){e.printStackTrace();}
            System.err.println(ERRORFLAG + "Client ran into " + ERRORC + getErrorName(e) + RESET + " error in performAction()");
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
