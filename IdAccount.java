import java.net.InetAddress;
import java.time.LocalDateTime;
import java.util.UUID;

public class IdAccount {

    //data
    final private UUID uuid;
    final private LocalDateTime dateCreated;
    final private String originalIp;
    private String username;
    private String realname;
    private byte[] password;
    private LocalDateTime dateModified;
    private String recentIp;

    /**
     * Constructor for account object
     * @param originalIp
     */
    public IdAccount(String originalIp) {
        this.uuid = java.util.UUID.randomUUID();
        this.dateCreated = java.time.LocalDateTime.now();
        this.dateModified = dateCreated;
        this.originalIp = originalIp;
        this.recentIp = originalIp;
    }

    /**
     * Secondary constructor for account object
     * @param username account username
     * @param realname real name associated with the acount, if not specified will be the username of the machine
     * @param uuid 
     * @param dateCreated date of account creation
     * @param dateModified date of most recent action on account 
     * @param password
     * @param originalIp Ip Address of the original machine that made the account
     * @param recentIp Ip Address of the machine that most recently did something with the account
     */
    public IdAccount(String username, String realname, UUID uuid, LocalDateTime dateCreated, LocalDateTime dateModified, byte[] password, String originalIp, String recentIp) {
        this.username = username;
        this.realname = realname;
        this.uuid = uuid;
        this.dateCreated = dateCreated;
        this.dateModified = dateModified;
        this.password = password;
        this.originalIp = originalIp;
        this.recentIp = recentIp;
    }


    //getters for permanent data
    /**
     * Gets uuid
     * @return uuid
     */
    public UUID getUuid() {
        return uuid;
    }

    /**
     * Gets dataCreated
     * @return dateCreated
     */
    public LocalDateTime getDateCreated() {
        return dateCreated;
    }

    //getters and setters for variable data
    /**
     * Gets username
     * @return username
     */
    public String getUsername() {
        return username;
    }

    /**
     * Sets username
     * @param username
     */
    public void setUsername(String username) {
        this.username = username;
        this.dateModified = java.time.LocalDateTime.now();
    }

    /**
     * Gets password
     * @return password
     */
    public byte[] getPassword() {
        return password;
    }

    /**
     * Sets password
     * @param password
     */
    public void setPassword(byte[] password) {
        this.password = password;
    }

    /**
     * Gets dateModified
     * @return dateModified
     */
    public LocalDateTime getDateModified() {
        return dateModified;
    }

    /**
     * Sets date modified
     * @param dateModified
     */
    public void setDateModified(LocalDateTime dateModified) {
        this.dateModified = dateModified;
    }

    /**
     * Gets real name
     * @return realname
     */
    public String getRealname() {
        return realname;
    }

    /**
     * Sets realname
     * @param realname
     */
    public void setRealname(String realname) {
        this.realname = realname;
    }

    /**
     * Gets original Ip
     * @return originalIp
     */
    public String getOriginalIP(){
        return originalIp;
    }

    /**
     * Gets recentIp
     * @return recentIp
     */
    public String getRecentIp(){
        return recentIp;
    }

    /**
     * Sets recent Ip
     * @param recentIp
     */
    public void setRecentIp(String recentIp){
        this.recentIp = recentIp;
    }

    /**
     * To string method
     */
    public String toString() {
        String out = "";

        out += "FOR USER: "+ getUsername();
        out += "\nUUID: "+ getUuid() +"\n";
        if(realname != null) out += "Name: " + getRealname() +"\n";
        out += "Original Ip: " + getOriginalIP() + "\n";
        out += "Joined: " + getDateCreated() +"\n";
        out += "Recent Activity: " + getDateModified() +"\n";
        out += "Recent Ip: " + getRecentIp() + "\n";

        return out;
    }
    
}


