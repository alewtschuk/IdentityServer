import java.net.Inet4Address;

public interface ServerInterface extends java.rmi.Remote {

    /**
     * This method is called by the client for creating an account.
     * @param username
     * @param realname
     * @param password
     * @param ipAddress
     * @return String for client debugging
     */
    String createAccount(String username, String realname, byte[] password, String originalIP) throws java.rmi.RemoteException;

    /**
     * Returns a string if the account is found or not based on username
     * @param username
     * @param recentIp
     * @return String for client debugging
     */
    String lookupUser(String username, String recentIp) throws java.rmi.RemoteException;

    /**
     * Looks up user and returns string based on UUID
     * @param uuid
     * @param recentIp
     * @return String for client debugging
     */
    String lookupUUID(String uuid, String recentIp) throws java.rmi.RemoteException;

    /**
     * Modifies the username, checks passord if needed
     * @param oldName
     * @param newName
     * @param password
     * @param recentIp
     * @returns String for client debugging
     */
    String changeUsername(String oldName, String newName, byte[] password, String recentIp) throws java.rmi.RemoteException;

    /**
     * Deletes user, checks if password is the same before delting, fails if no match
     * @param username
     * @param password
     * @return String for client debugging
     */
    String deleteAccount(String username, byte[] password) throws java.rmi.RemoteException;

    /**
     * Gets the accounts in the database on the server if with output depending on what you want to be returned
     * @param attribute
     * @return formatted String of info for client printing
     */
    String getAccounts(String attribute) throws java.rmi.RemoteException;

    /**
     * Getter for the server state [election || subordinate || coordinator]
     * @return state
     */
    int getState() throws java.rmi.RemoteException;

    /**
     * gets the address of the coordinator, for client use
     * @return Inet4Address of coordinator
     */    
    Inet4Address getCoordinator() throws java.rmi.RemoteException;
}
