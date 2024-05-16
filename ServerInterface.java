import java.util.UUID;

public interface ServerInterface extends java.rmi.Remote {

    //--create
    String createAccount(String username, String realname, byte[] password, String originalIP) throws java.rmi.RemoteException;

    //--lookup
    String lookupUser(String username, String recentIp) throws java.rmi.RemoteException;

    //--reverse-lookup
    String lookupUUID(String uuid, String recentIp) throws java.rmi.RemoteException;

    //--modify
    String changeUsername(String oldName, String newName, byte[] password, String recentIp) throws java.rmi.RemoteException;

    //--delete
    String deleteAccount(String username, byte[] password) throws java.rmi.RemoteException;

    //--get
    String getAccounts(String attribute) throws java.rmi.RemoteException;
}
