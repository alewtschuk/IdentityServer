import java.net.InetAddress;
import java.rmi.*;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;


import org.apache.commons.cli.*;

public class IdClient {
    static String host = "localhost";
    static int port = 5128;
    static ServerInterface severus;

    public static void main(String[] args) throws UnknownHostException{

        final String RESET = "\u001B[0m";
        final String ANSI_BLACK = "\u001B[30m";
        final String ERRORC = "\u001B[31m";
        final String ANSI_GREEN = "\u001B[32m";
        final String ANSI_YELLOW = "\u001B[33m";
        final String ANSI_PURPLE = "\u001B[35m";
        final String ACCOUNTC = "\u001B[36m";
        final String ANSI_WHITE = "\u001B[37m";

        

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

        //Command Line Parser
        CommandLineParser cliParser = new DefaultParser();


        try {
            System.setProperty("javax.net.ssl.trustStore", "Client_Truststore");
            System.setProperty("java.security.policy", "mysecurity.policy");
            System.setProperty("javax.net.ssl.trustStorePassword", "123456");
            //Parse commands first
            CommandLine command = cliParser.parse(options, args);
            String whichArg = getArg(command).toLowerCase();

            //Setup registry
            Registry reg = LocateRegistry.getRegistry(host, port);
            severus = (ServerInterface) reg.lookup("severus");

            //Get system IP
            InetAddress localHost = InetAddress.getLocalHost();
            String ipAddress = localHost.getHostAddress();


            //Required arg
            if (command.hasOption("server")) {
                System.out.println("== CLIENT RUNNING ==");
                System.out.println("Connecting to IdSever at host: " + command.getOptionValue("server") + " Port: " + port + "\n");
            } 

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
            
        }
        
        catch (ParseException e) {
            e.printStackTrace();
            System.err.println(ERRORC + "[ERROR]: " + RESET + "Client ran into" + ERRORC + " PARSING" + RESET + " error in main");
        } catch (RemoteException e) {
            e.printStackTrace();
            System.err.println(ERRORC + "[ERROR]: " + RESET + "Client ran into" + ERRORC + " REMOTE EXCEPTION" + RESET + " error in main");
            if(port != 5128){System.err.println(ERRORC + "[ERROR_INFO]: REMOTE EXCEPTION" + RESET + " no server on port");}
        } catch (NotBoundException e) {
            e.printStackTrace();
            System.err.println(ERRORC + "[ERROR]: " + RESET + "Client ran into" + ERRORC + " NOT BOUND EXCEPTION" + RESET + " error in main");
        }catch ( Exception e ) {
            e.printStackTrace();
            System.err.println(ERRORC + "[ERROR]: " + RESET + "Client ran into" + ERRORC + " LAST RESORT CATCH " + RESET + " error in main");
        }
    } //end main

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
            if(option.getOpt().equals("s")){
                arg = option.getLongOpt();
                System.out.println("Setting host to " + command.getOptionValue(arg));
                host = command.getOptionValue(arg);
            }


            //If the option is the required s do not pass to switch
            if(!option.getOpt().equals("s")){
                arg = option.getLongOpt();
                numarg++;
                System.out.println("Current arg is: " + arg);
            }

            //Handles the optional n arg for port number specification if not defaults to 5128
            if(option.getOpt().equals("n")){
                System.out.println("Setting port to " + command.getOptionValue(arg));
                port = Integer.parseInt(command.getOptionValue(arg));
                numarg = 0;
            }

            //If the num of needed args is 1 and the second option does not equal numport or verbose break and return
            if(numarg >= 1 && (!option.getOpt().equals("n") || !option.getOpt().equals("v"))) {
                System.out.println(numarg);
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
