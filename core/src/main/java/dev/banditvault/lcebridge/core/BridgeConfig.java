package dev.banditvault.lcebridge.core;

/**
 * Bridge configuration — loaded from config.yml.
 * All fields have defaults matching the shipped config.yml.
 */
public class BridgeConfig {

    // LCE listener
    public String lceAddress = "0.0.0.0";
    public int    lcePort    = 25565;
    public String motd       = "LCEBridge Server";
    public int    maxPlayers = 0;

    // Java backend
    public String remoteAddress  = "127.0.0.1";
    public int    remotePort     = 25566;
    public String authType       = "offline"; // offline | online | floodgate

    // World
    public int    worldSizeLimit      = 0;
    public String negativeYHandling   = "clamp";
    public int    viewDistance        = 0;
    public int    chunksPerTick       = 4;

    // Performance
    public int     chunkTranslationThreads = 0;
    public boolean chunkCacheEnabled       = true;
    public int     chunkCacheSize          = 256;
    public boolean asyncCompression        = true;
    public boolean liveTileUpdates         = false;
    public boolean forwardChat             = false;

    // Logging
    public String  logLevel              = "info";
    public boolean logPackets            = false;
    public boolean logTranslationFails   = true;
    public boolean packetDumpEnabled     = false;
    public String  packetDumpDirectory   = "dumps/";
}
