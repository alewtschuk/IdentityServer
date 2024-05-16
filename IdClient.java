import java.io.File;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.rmi.*;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
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
import java.util.regex.Pattern;

import org.apache.commons.cli.*;

public class IdClient {
    static String host = null;
    static int port = 5128;
    static String coordAddy = "none";
    static Registry reg;
    static ServerInterface severus;
    static boolean debug = false;
    final static String IP_PATTERN = "^(25[0-5]|2[0-4]\\d|1\\d{2}|[1-9]?\\d)\\." +
                                     "(25[0-5]|2[0-4]\\d|1\\d{2}|[1-9]?\\d)\\." +
                                     "(25[0-5]|2[0-4]\\d|1\\d{2}|[1-9]?\\d)\\." +
                                     "(25[0-5]|2[0-4]\\d|1\\d{2}|[1-9]?\\d)$";

    final static String RESET = "\u001B[0m";
    final static String ANSI_BLACK = "\u001B[30m";
    final static String ERRORC = "\u001B[31m";
    final static String INFOC = "\u001B[32m";
    final static String DEBUGC = "\u001B[33m";
    final static String ANSI_PURPLE = "\u001B[35m";
    final static String ACCOUNTC = "\u001B[36m";
    final static String ANSI_WHITE = "\u001B[37m";
    final static String DEBUGFLAG = "[" + DEBUGC + "DEBUG" + RESET + "] ";
    final static String ERRORFLAG = "[" + ERRORC + "ERROR" + RESET + "] ";
    final static String INFOFLAG = "[" + INFOC + "INFO" + RESET + "]";

    static List<String> serverHostList = new ArrayList<>(); //List to hold serverHost addresses

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
                System.out.println("Connecting to IdSever at host: " + host + " Port: " + port + "\n");
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
        }catch (Exception e) {
            if(debug){e.printStackTrace();}
            if(debug){System.err.println(ERRORC + "[ERROR]: " + RESET + "Client ran into" + ERRORC + " EXCEPTION" + RESET + " error in main");}
            if(debug){System.err.println(ERRORC + "[ERROR]: " + RESET + "The problem lies in initServerConnection()");}
        }
        //catch ( Exception e ) {
        //     if(debug){e.printStackTrace();}
        //     System.err.println(ERRORC + "[ERROR]: " + RESET + "Client ran into" + ERRORC + " LAST RESORT CATCH " + RESET + " error in main");
        // }

 
    } //end main

    /**
     * Sets up the SSL system properties
     */
    private static void setupSSL() {
        System.setProperty("javax.net.ssl.trustStore", "Client_Truststore");
        System.setProperty("java.security.policy", "mysecurity.policy");
        System.setProperty("javax.net.ssl.trustStorePassword", "123456");
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
            if(severus == null){
                host = serverCommandArgument; // Set the host directly if it's not a file
                reg = LocateRegistry.getRegistry(host, port);
                severus = (ServerInterface) reg.lookup("severus");
            }
            if (debug && severus != null) {System.out.println(DEBUGFLAG + "Serverus has been set");}
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
                if (Pattern.matches(IP_PATTERN, next.trim())) {
                    serverHostList.add(next);
                }
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
        String serverHost;
        for(int i = 0; i < serverHostList.size(); i++) {
            
            try {
                if(!coordAddy.equals("none")) serverHost = coordAddy;
                else serverHost = serverHostList.get(i);

                if (debug) System.out.println(DEBUGFLAG + "trying this host: " + serverHost);
                host = serverHost.trim();

                reg = LocateRegistry.getRegistry(host, port);
                severus = (ServerInterface) reg.lookup("severus");
                String[] boundNames = reg.list();
                if(debug){System.out.println(DEBUGFLAG + "Bound names: " + String.join(", ", boundNames));}
                isConnected = true;
                System.out.println(DEBUGFLAG + "Connected to " + host);


                int state = severus.getState();
                System.out.println(DEBUGFLAG + "State: " + state);

                switch (state) {
                    case 0:
                        System.out.println(ERRORFLAG + "ELECTION IN PROGRESS: TRYING AGAIN LATER." + host);
                        System.exit(0);
                    case 1:
                        Inet4Address temp = severus.getCoordinator();
                        if (temp == null) {
                            System.out.println(ERRORFLAG + "ELECTION IN PROGRESS: TRYING AGAIN LATER." + host);
                            System.exit(0);
                        }

                        coordAddy = temp.toString();
                        if(coordAddy.startsWith("/")) coordAddy = coordAddy.substring(1);
                        System.out.println("Found Coordinator: " + coordAddy);
                        i = serverHostList.size() - 2;
                        break;
                    case 2:
                        i = serverHostList.size() - 1;
                }


            } catch (RemoteException | NotBoundException e) {
                System.out.println(ERRORFLAG + "Failed to connect to " + host + ". Attempt " + (attempts + 1));
                if (++attempts == serverHostList.size()) {
                    System.out.println(ERRORFLAG + "All server attempts failed.");
                    break;
                }
                try {
                    // Exponential backoff
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
                        createOut = severus.createAccount(loginname, realname, null, ipAddress);
                        System.out.print(ACCOUNTC + "[FROM SERVER]: ");
                        System.out.println(createOut);
                        System.out.print(RESET);
                    } else {
                        createOut = severus.createAccount(loginname, realname, encrypt(password), ipAddress);
                        System.out.print(ACCOUNTC + "[FROM SERVER]: ");
                        System.out.println(createOut);
                        System.out.print(RESET);
                    }

                    break;
            
                case "lookup":
                    System.out.println("Command " + whichArg + " called with value: " + command.getOptionValue("lookup"));
                    String lookupOut = severus.lookupUser(command.getOptionValue("lookup"), ipAddress);
                    System.out.print(ACCOUNTC + "[FROM SERVER]: ");
                    System.out.println(lookupOut);
                    System.out.print(RESET);
                    break;

                case "reverse-lookup":
                    System.out.println("Command " + whichArg + " called with value: " + command.getOptionValue("reverse"));
                    String revLookupOut = severus.lookupUUID(command.getOptionValue("reverse-lookup"), ipAddress);
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
                    String modifyOut = severus.changeUsername(oldname, newname, encrypt(password), ipAddress);
                    System.out.print(ACCOUNTC + "[FROM SERVER]: ");
                    System.out.println(modifyOut);
                    System.out.print(RESET);
                    break;

                case "delete":
                    System.out.println("Command " + whichArg + " called with value: " + command.getOptionValue("delete"));

                    loginname = command.getOptionValue("delete");
                    password = command.getOptionValue("password");
                    String deleteOut = severus.deleteAccount((loginname), encrypt(password));
                    System.out.print(ACCOUNTC + "[FROM SERVER]: ");
                    System.out.println(deleteOut);
                    System.out.print(RESET);
                    break;

                case "get":
                    System.out.println("Command " + whichArg + " called with value: " + command.getOptionValue("get"));

                    String getOpt = command.getOptionValue("get");
                    String getOut = severus.getAccounts(getOpt);
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