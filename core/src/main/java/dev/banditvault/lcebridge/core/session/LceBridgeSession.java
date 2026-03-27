package dev.banditvault.lcebridge.core.session;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.banditvault.lcebridge.core.BridgeConfig;
import dev.banditvault.lcebridge.core.chunk.CachedLceChunkColumn;
import dev.banditvault.lcebridge.core.chunk.RleZlibCompressor;
import dev.banditvault.lcebridge.core.inventory.LegacyInventoryCrafting;
import dev.banditvault.lcebridge.core.network.java.JavaSession;
import dev.banditvault.lcebridge.core.network.lce.*;
import dev.banditvault.lcebridge.core.registry.MappingRegistry;
import io.netty.channel.Channel;
import net.kyori.adventure.key.Key;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtType;
import org.cloudburstmc.math.vector.Vector3d;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.protocol.packet.common.clientbound.ClientboundDisconnectPacket;
import org.geysermc.mcprotocollib.protocol.packet.common.clientbound.ClientboundKeepAlivePacket;
import org.geysermc.mcprotocollib.protocol.packet.common.clientbound.ClientboundResourcePackPopPacket;
import org.geysermc.mcprotocollib.protocol.packet.common.clientbound.ClientboundResourcePackPushPacket;
import org.geysermc.mcprotocollib.protocol.packet.common.clientbound.ClientboundServerLinksPacket;
import org.geysermc.mcprotocollib.protocol.packet.common.serverbound.ServerboundResourcePackPacket;
import org.geysermc.mcprotocollib.protocol.packet.configuration.clientbound.ClientboundRegistryDataPacket;
import org.geysermc.mcprotocollib.protocol.data.game.ClientCommand;
import org.geysermc.mcprotocollib.protocol.data.game.ResourcePackStatus;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.MetadataTypes;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.Hand;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.InteractAction;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.PlayerSpawnInfo;
import org.geysermc.mcprotocollib.protocol.data.game.entity.type.EntityType;
import org.geysermc.mcprotocollib.protocol.data.game.item.HashedStack;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ClickItemAction;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ContainerAction;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ContainerActionType;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.DropItemAction;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ShiftClickItemAction;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.*;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.*;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.*;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.*;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.*;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.scoreboard.ClientboundSetPlayerTeamPacket;
import org.geysermc.mcprotocollib.protocol.data.game.PlayerListEntry;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.*;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.inventory.*;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.*;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundSwingPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundLoginPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundBundlePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundPlayerLoadedPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundChunkBatchFinishedPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundChunkBatchStartPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundChunkBatchReceivedPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundPlayerPositionPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundAcceptTeleportationPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundDelimiterPacket;
import org.geysermc.mcprotocollib.protocol.packet.login.clientbound.ClientboundLoginFinishedPacket;
import org.geysermc.mcprotocollib.protocol.packet.configuration.clientbound.ClientboundFinishConfigurationPacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.geysermc.mcprotocollib.protocol.packet.common.serverbound.ServerboundClientInformationPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundClientTickEndPacket;
import org.geysermc.mcprotocollib.protocol.data.game.setting.ChatVisibility;
import org.geysermc.mcprotocollib.protocol.data.game.setting.ParticleStatus;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.HandPreference;
import org.geysermc.mcprotocollib.protocol.data.game.recipe.display.RecipeDisplayEntry;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class LceBridgeSession {
    private static final Logger log = LoggerFactory.getLogger(LceBridgeSession.class);
    private static final Map<String, String> CHAT_TRANSLATIONS = loadChatTranslations();
    private static final int LCE_NET_VERSION      = 560;
    private static final int LCE_PROTOCOL_VERSION = 78;
    private static final int LCE_CHAT_MAX_CHARS   = 119;
    private static final long BRIDGE_HOST_XUID    = 1L;
    private static final int LCE_SMALL_ID         = 4; // Remote clients start at XUSER_MAX_COUNT (4) in Win64 WinsockNetLayer
    private static final int LCE_ENTITY_ID        = (LCE_SMALL_ID * 100) + 1; // = 401
    private static final long TILE_UPDATE_GRACE_MS = 2000L;
    private static final long EARLY_POST_CHUNK_FALLBACK_MS = 1500L;
    private static final long TRACKED_ENTITY_FALLBACK_DELAY_MS = 3000L;
    private static final long TRACKED_ENTITY_FALLBACK_TELEPORT_SETTLE_MS = 2000L;
    private static final long TELEPORT_SETTLE_MS = 200L;
    private static final long ACTION_POSE_FLUSH_STALE_MS = 125L;
    private static final double FORWARDED_CORRECTION_HORIZONTAL_DELTA = 0.75d;
    private static final double FORWARDED_CORRECTION_VERTICAL_DELTA = 1.25d;
    private static final int LCE_ITEM_ENTITY_DATA_SLOT = 10;
    private static final int MAX_REMOTE_PLAYER_ENTITIES = 64;
    private static final int MAX_CACHED_PLAYER_NAMES = 512;
    /** Gray stained glass pane — filler for the fake crafting chest GUI. LCE block ID 160, damage 7. */
    private static final LceItemStack CRAFTING_FILLER_ITEM = new LceItemStack(160, 1, 7);
    /** LCE slot indices that correspond to the crafting grid + output in the fake chest. */
    private static final int[] CRAFTING_LCE_SLOTS = {11, 12, 13, 20, 21, 22, 29, 30, 31, 15};
    private static final org.geysermc.mcprotocollib.protocol.data.game.entity.object.Direction DEFAULT_DIG_FACE =
        org.geysermc.mcprotocollib.protocol.data.game.entity.object.Direction.DOWN;

    private enum TrackedEntityKind {
        OBJECT,
        MOB,
        ITEM,
        PLAYER
    }

    private static final class TrackedEntity {
        final int entityId;
        final TrackedEntityKind kind;
        final int lceType;
        int data;
        double x;
        double y;
        double z;
        float yaw;
        float pitch;
        float headYaw;
        double motionX;
        double motionY;
        double motionZ;
        LceItemStack itemStack;
        boolean spawnedToLce;
        // Player-specific fields
        String playerName;
        int playerIndex;
        java.util.UUID playerUuid;

        TrackedEntity(int entityId, TrackedEntityKind kind, int lceType) {
            this.entityId = entityId;
            this.kind = kind;
            this.lceType = lceType;
            this.data = -1;
        }
    }

    private final BridgeConfig  config;
    private final Channel       lceChannel;
    private final JavaSession   javaSession;
    private final AtomicBoolean loggedIn       = new AtomicBoolean(false);
    private final AtomicBoolean spawnFinished  = new AtomicBoolean(false);
    private final AtomicBoolean initialTeleportHandled = new AtomicBoolean(false);
    private final AtomicBoolean postChunkSpawnSent = new AtomicBoolean(false);
    private final AtomicBoolean postChunkReady = new AtomicBoolean(false);
    private final AtomicBoolean warnedMovementBeforePostChunkReady = new AtomicBoolean(false);
    private final AtomicBoolean clientInformationSent = new AtomicBoolean(false);
    private final AtomicBoolean playerLoadedSent = new AtomicBoolean(false);
    private final AtomicBoolean javaChunkBatchFinished = new AtomicBoolean(false);
    private final AtomicBoolean trackedEntitiesFallbackEnabled = new AtomicBoolean(false);
    private final AtomicBoolean trackedEntitiesFallbackScheduled = new AtomicBoolean(false);
    private final AtomicBoolean earlyPostChunkFallbackScheduled = new AtomicBoolean(false);
    private final AtomicBoolean firstChunkLogged = new AtomicBoolean(false);
    private final AtomicBoolean teleportAcked  = new AtomicBoolean(false);
    private ScheduledExecutorService tickEndScheduler;
    private ScheduledExecutorService posHeartbeatScheduler;
    private ScheduledExecutorService chunkSendScheduler;
    private final Queue<ClientboundLevelChunkWithLightPacket> pendingChunks = new ConcurrentLinkedQueue<>();
    private volatile double lastKnownX = 0, lastKnownY = 64, lastKnownZ = 0;
    private volatile float  lastKnownYaw = 0, lastKnownPitch = 0;
    private volatile SetTimePacket pendingSetTime;
    private volatile SetHealthPacket pendingSetHealth;
    private volatile int queuedChunkCount = 0;
    private volatile float lastHealth = 20.0f;
    private volatile long chunkLoadStartMs = 0L;
    private volatile long lastChunkNudgeMs = 0L;
    private volatile long lastDelimiterMs = 0L;
    private volatile long lastClientTickEndSentMs = 0L;
    private volatile long lastMovePacketMs = 0L;
    private volatile long lastAcceptedTeleportMs = 0L;
    private volatile long tileUpdatesReadyAtMs = Long.MAX_VALUE;
    private volatile long suppressPositionUntilMs = 0L;
    private volatile long expectedCorrectionEchoUntilMs = 0L;
    private final AtomicBoolean delimiterSeen = new AtomicBoolean(false);
    private final AtomicBoolean playerMoving  = new AtomicBoolean(false);
    private volatile boolean lastOnGround = true;
    private volatile boolean awaitingCorrectionEcho = false;
    private volatile double expectedCorrectionEchoX = 0d;
    private volatile double expectedCorrectionEchoY = 64d;
    private volatile double expectedCorrectionEchoYView = 65.62d;
    private volatile double expectedCorrectionEchoZ = 0d;
    private volatile float expectedCorrectionEchoYaw = 0f;
    private volatile float expectedCorrectionEchoPitch = 0f;
    private volatile int currentCarriedSlot = 0;
    private volatile int lastSentCarriedSlot = -1;
    private volatile int blockActionSequence  = 0;
    private volatile LceItemStack cachedCursorItem = null;
    private volatile org.cloudburstmc.math.vector.Vector3i activeDigPos = null;
    private volatile org.geysermc.mcprotocollib.protocol.data.game.entity.object.Direction activeDigFace = DEFAULT_DIG_FACE;
    private volatile org.cloudburstmc.math.vector.Vector3i recentDigLogPos = null;
    private volatile long recentDigLogUntilMs = 0L;
    private volatile byte lastJavaAbilityFlags = 0;
    private volatile float lastJavaFlySpeed = 0.05f;
    private volatile float lastJavaWalkSpeed = 0.1f;
    private volatile int activeCraftingContainerId = 0;
    private final Map<Integer, Integer> containerStateIds = new ConcurrentHashMap<>();
    private final Set<Integer> craftingContainerIds = ConcurrentHashMap.newKeySet();
    private final Map<Integer, LceItemStack> javaPlayerContainerSlots = new ConcurrentHashMap<>();
    private final Map<Key, Set<String>> javaRecipeItemSets = new ConcurrentHashMap<>();
    private final Map<Integer, RecipeDisplayEntry> javaInventoryCraftEntries = new ConcurrentHashMap<>();
    private final Map<Integer, LegacyInventoryCrafting.JavaRecipeCandidate> javaInventoryCraftRecipes = new ConcurrentHashMap<>();
    private final Map<Long, CachedLceChunkColumn> translatedChunkCache = new ConcurrentHashMap<>();
    private final Map<Integer, TrackedEntity> trackedEntities = new ConcurrentHashMap<>();
    private final Map<Integer, LceItemStack> pendingTrackedItemStacks = new ConcurrentHashMap<>();
    private final Map<Integer, List<LceItemStack>> cachedContainerItems = new ConcurrentHashMap<>();
    private final Map<java.util.UUID, String> knownPlayerNames = new ConcurrentHashMap<>();
    private final Queue<Integer> freeRemotePlayerIndices = new ConcurrentLinkedQueue<>();
    private final AtomicInteger activeRemotePlayerCount = new AtomicInteger(0);
    private volatile int currentLevelIdx = 0;
    private volatile int javaEntityId = -1; // The player's entity ID on the Java server
    private volatile PendingInventoryCraft pendingInventoryCraft = null;

    private String playerName = "Unknown";
    private long offlineXuid  = 0L, onlineXuid = 0L;
    private int spawnX = 0, spawnY = 64, spawnZ = 0;

    private record PendingInventoryCraft(int legacyRecipeIndex, int javaRecipeId) {
    }

    public LceBridgeSession(BridgeConfig config, Channel lceChannel) {
        this.config     = config;
        this.lceChannel = lceChannel;
        this.javaSession = new JavaSession(config, "player");
        this.javaSession.setPacketHandler(this::handleJavaPacket);
        this.javaSession.setDisconnectHandler(this::onJavaDisconnected);
        resetRemotePlayerIndices();
    }

    // ---- server speaks first ------------------------------------------------
    public void sendServerPreLogin() {
        log.info("Sending server PreLogin to {}", lceChannel.remoteAddress());
        sendLce(buildPreLoginResponse());
    }

    // ---- LCE → Bridge -------------------------------------------------------
    public void handleLcePacket(LcePacket pkt) {
        switch (pkt) {
            case PreLoginPacket p        -> handlePreLogin(p);
            case LoginPacket p           -> handleLogin(p);
            case AnimatePacket p         -> handleAnimate(p);
            case ClientCommandPacket p   -> handleClientCommand(p);
            case RespawnRequestPacket p  -> handleRespawnRequest(p);
            case KeepAlivePacket p       -> handleKeepAlive(p);
            case ChatPacket p            -> handleChat(p);
            case MovePlayerPacket p      -> handleMove(p);
            case PlayerActionPacket p    -> handlePlayerAction(p);
            case UseItemPacket p         -> handleUseItem(p);
            case InteractPacket p        -> handleInteract(p);
            case PlayerCommandPacket p   -> handlePlayerCommand(p);
            case SetCarriedItemPacket p  -> handleSetCarriedItem(p);
            case ContainerClosePacket p  -> handleContainerClose(p);
            case ContainerClickPacket p  -> handleContainerClick(p);
            case ContainerButtonClickPacket p -> handleContainerButtonClick(p);
            case CraftItemPacket p       -> handleCraftItem(p);
            case PlayerAbilitiesPacket p -> {}
            case DebugOptionsPacket p    -> {}
            case DisconnectPacket p      -> handleLceDisconnect(p);
            default -> {}
        }
    }

    private void handlePreLogin(PreLoginPacket p) {
        log.info("Client PreLogin: netVersion={} name='{}' xuid={}", p.netVersion, p.playerName, p.offlineXuid);
        if (p.netVersion != LCE_NET_VERSION) {
            log.warn("Wrong net version {} (expected {})", p.netVersion, LCE_NET_VERSION);
            sendLce(makeDisconnect(14)); lceChannel.close(); return;
        }
        this.playerName  = p.playerName.isEmpty() ? "LCEPlayer" : p.playerName;
        this.offlineXuid = p.offlineXuid;
        this.onlineXuid  = p.onlineXuid;
        log.info("PreLogin accepted for '{}'", playerName);
        javaSession.setUsername(playerName);
        javaSession.connect();
    }

    private void handleLogin(LoginPacket p) {
        if (p.protocolVersion != LCE_PROTOCOL_VERSION) {
            log.warn("Wrong protocol version {} (expected {})", p.protocolVersion, LCE_PROTOCOL_VERSION);
            sendLce(makeDisconnect(14)); lceChannel.close();
        }
    }

    private void handleKeepAlive(KeepAlivePacket p) {
        // Do not forward LCE keep-alives to Java.
        // Java expects keep-alive replies only when it has sent a challenge ID,
        // and unsolicited IDs trigger an immediate timeout disconnect.
    }

    private void handleClientCommand(ClientCommandPacket p) {
        if (!javaSession.isConnected()) return;
        log.info("Received LCE ClientCommand action={}", p.action);

        // LCE action=1 is PERFORM_RESPAWN. Mirror server behavior:
        // always forward to backend and let backend perform health/death checks.
        if (p.action == 1) {
            javaSession.send(new ServerboundClientCommandPacket(ClientCommand.RESPAWN));
            log.info("Forwarded LCE PERFORM_RESPAWN to Java (lastHealth={})", lastHealth);
        }
    }

    private void handleRespawnRequest(RespawnRequestPacket p) {
        if (!javaSession.isConnected()) return;
        javaSession.send(new ServerboundClientCommandPacket(ClientCommand.RESPAWN));
        log.info("Forwarded LCE RespawnPacket request to Java (lastHealth={})", lastHealth);
    }

    private void handleAnimate(AnimatePacket p) {
        if (!javaSession.isConnected()) return;
        // LCE action=1 is arm swing. Send to Java for animation visibility to other players.
        // The ATTACK InteractPacket follows in the same batched frame and handles damage.
        if (p.action == 1) {
            ensureCarriedItemSynced();
            flushLatestPoseForAction();
            javaSession.send(new ServerboundSwingPacket(Hand.MAIN_HAND));
        }
        // Some LCE builds signal respawn via Animate action=4.
        if (p.action == 4) {
            javaSession.send(new ServerboundClientCommandPacket(ClientCommand.RESPAWN));
            log.info("Forwarded LCE Animate RESPAWN action to Java (entityId={}, lastHealth={})", p.entityId, lastHealth);
        }
    }

    private void handleChat(ChatPacket p) {
        if (!javaSession.isConnected()) return;
        // Extract the message text from stringArgs[0] (e_ChatCustom format)
        String text = (!p.stringArgs.isEmpty()) ? p.stringArgs.get(0) : "";
        if (text.isEmpty()) return;
        if (text.startsWith("/")) {
            String command = text.substring(1).trim();
            if (!command.isEmpty()) {
                javaSession.send(new ServerboundChatCommandPacket(command));
                log.info("Forwarded LCE slash command to Java: /{}", command);
            }
            return;
        }
        javaSession.send(new ServerboundChatPacket(text, System.currentTimeMillis(), 0L, null, 0, new BitSet(), 0));
    }

    private void handleMove(MovePlayerPacket p) {
        if (!javaSession.isConnected()) return;
        // Ignore placeholder movement before Java has told us the real spawn position.
        if (!initialTeleportHandled.get()) return;
        long now = System.currentTimeMillis();
        boolean og = (p.flags & 0x1) != 0;
        boolean isFlying = (p.flags & 0x2) != 0;
        lastOnGround = og;
        boolean hasPosition = p.id == 11 || p.id == 13;
        boolean correctionEcho = hasPosition && matchesExpectedCorrectionEcho(p, now);
        if (hasPosition && now < suppressPositionUntilMs && !correctionEcho) {
            if (config.logPackets) {
                log.debug(
                    "LCE move id={} suppressed during teleport settle window until {} pos=({},{},{}) yView={} yaw={} pitch={}",
                    p.id,
                    suppressPositionUntilMs,
                    p.x,
                    p.y,
                    p.z,
                    p.yView,
                    p.yaw,
                    p.pitch
                );
            }
            return;
        }
        if (correctionEcho) {
            awaitingCorrectionEcho = false;
            expectedCorrectionEchoUntilMs = 0L;
            if (config.logPackets) {
                log.debug(
                    "Accepted LCE move id={} as correction echo pos=({},{},{}) yView={} yaw={} pitch={}",
                    p.id,
                    p.x,
                    p.y,
                    p.z,
                    p.yView,
                    p.yaw,
                    p.pitch
                );
            }
            return;
        }
        lastMovePacketMs = now;
        boolean readyForPosition = postChunkReady.get();
        if (!readyForPosition && hasPosition && warnedMovementBeforePostChunkReady.compareAndSet(false, true)) {
            log.warn(
                "Forwarding position movement before post-chunk spawn completed; some server software requires real coordinates before the chunk handshake fully settles"
            );
        }
        boolean movedEnough = false;
        double translatedY = p.y;
        if (hasPosition && !correctionEcho) {
            translatedY = translateLceYToJava(p);
        }
        // Keep last-known pose current for heartbeat and relative teleports, even when
        // LCE sends position-only or rotation-only variants.
        if (hasPosition) {
            movedEnough = Math.abs(p.x - lastKnownX) > 0.01d
                || Math.abs(translatedY - lastKnownY) > 0.01d
                || Math.abs(p.z - lastKnownZ) > 0.01d;
            lastKnownX = p.x;
            lastKnownY = translatedY;
            lastKnownZ = p.z;
        }
        if (p.id == 12 || p.id == 13) {
            lastKnownYaw = p.yaw;
            lastKnownPitch = p.pitch;
        }
        if (config.logPackets) {
            log.debug(
                "LCE move id={} pos=({},{},{}) yView={} yaw={} pitch={} flags=0x{} onGround={} flying={} postChunkReady={} movedEnough={}",
                p.id,
                p.x,
                p.y,
                p.z,
                p.yView,
                p.yaw,
                p.pitch,
                Integer.toHexString(p.flags & 0xFF),
                og,
                isFlying,
                readyForPosition,
                movedEnough
            );
        }
        // Kill the position heartbeat on first real movement from the LCE client.
        // Only treat packets with position payload as "real movement". LCE can send
        // rotation-only packets right after spawn; stopping heartbeat on those leaves
        // Java with no position updates and can trigger a floating kick.
        // The heartbeat sends a fixed position every 1s which vanilla's anti-fly detects
        // as the player hovering in mid-air, causing a "flying" kick after ~15 seconds.
        // Once the LCE client is sending its own movement at 20 pps we don't need it.
        if (hasPosition && movedEnough && playerMoving.compareAndSet(false, true)) {
            log.info("Player movement started - heartbeat switched to idle mode");
        }
        switch (p.id) {
            case 10 -> javaSession.send(new ServerboundMovePlayerStatusOnlyPacket(og, false));
            case 11 -> javaSession.send(new ServerboundMovePlayerPosPacket(og, false, p.x, translatedY, p.z));
            case 12 -> javaSession.send(new ServerboundMovePlayerRotPacket(og, false, p.yaw, p.pitch));
            case 13 -> javaSession.send(new ServerboundMovePlayerPosRotPacket(og, false, p.x, translatedY, p.z, p.yaw, p.pitch));
        }
    }

    private void handleSetCarriedItem(SetCarriedItemPacket p) {
        if (!javaSession.isConnected()) return;
        currentCarriedSlot = p.slot;
        ensureCarriedItemSynced();
    }

    private void handleUseItem(UseItemPacket p) {
        if (!javaSession.isConnected()) return;

        ensureCarriedItemSynced();
        flushLatestPoseForAction();
        int sequence = ++blockActionSequence;
        if (config.logPackets) {
            log.debug(
                "LCE use-item seq={} target=({}, {}, {}) face={} click=({}, {}, {}) pose=({},{},{})",
                sequence,
                p.x,
                p.y,
                p.z,
                p.face,
                p.clickX,
                p.clickY,
                p.clickZ,
                lastKnownX,
                lastKnownY,
                lastKnownZ
            );
        }
        if (p.face == 255 || p.x == -1 || p.y == 255 || p.z == -1) {
            javaSession.send(new ServerboundUseItemPacket(Hand.MAIN_HAND, sequence, lastKnownYaw, lastKnownPitch));
            return;
        }

        var pos = org.cloudburstmc.math.vector.Vector3i.from(p.x, p.y, p.z);
        var face = directionFromFaceIndex(p.face);
        javaSession.send(new ServerboundUseItemOnPacket(
            pos, face, Hand.MAIN_HAND, p.clickX, p.clickY, p.clickZ, false, false, sequence
        ));
        predictSelectedHotbarPlacement(p.item);
    }

    private void handleInteract(InteractPacket p) {
        if (!javaSession.isConnected()) return;

        ensureCarriedItemSynced();
        flushLatestPoseForAction();
        if (config.logPackets) {
            log.debug("LCE interact action={} target={} pose=({},{},{})", p.action, p.target, lastKnownX, lastKnownY, lastKnownZ);
        }
        if (p.action == 0) {
            javaSession.send(new ServerboundInteractPacket(p.target, InteractAction.INTERACT, Hand.MAIN_HAND, false));
        } else if (p.action == 1) {
            // ATTACK must be sent BEFORE SwingPacket. Player.swing() resets the
            // attackStrengthTicker to 0 on the Java server. If swing goes first,
            // the attack reads ticker=0 and deals 0 damage.
            log.info("Forwarding LCE ATTACK: target={} source={} javaEntityId={} pose=({},{},{})",
                p.target, p.source, javaEntityId, lastKnownX, lastKnownY, lastKnownZ);
            javaSession.send(new ServerboundInteractPacket(p.target, InteractAction.ATTACK, false));
            javaSession.send(new ServerboundSwingPacket(Hand.MAIN_HAND));
        }
    }

    private void handlePlayerAction(PlayerActionPacket p) {
        if (!javaSession.isConnected()) return;
        ensureCarriedItemSynced();
        // Flush position for actions that trigger server-side reach checks:
        // START_DIGGING (0), FINISH_DIGGING (2), and block interaction actions.
        if (p.action == 0 || p.action == 2) {
            flushLatestPoseForAction();
        }
        var action = switch (p.action) {
            case 0 -> org.geysermc.mcprotocollib.protocol.data.game.entity.player.PlayerAction.START_DIGGING;
            case 1 -> org.geysermc.mcprotocollib.protocol.data.game.entity.player.PlayerAction.CANCEL_DIGGING;
            case 2 -> org.geysermc.mcprotocollib.protocol.data.game.entity.player.PlayerAction.FINISH_DIGGING;
            case 3 -> org.geysermc.mcprotocollib.protocol.data.game.entity.player.PlayerAction.DROP_ITEM_STACK;
            case 4 -> org.geysermc.mcprotocollib.protocol.data.game.entity.player.PlayerAction.DROP_ITEM;
            case 5 -> org.geysermc.mcprotocollib.protocol.data.game.entity.player.PlayerAction.RELEASE_USE_ITEM;
            default -> null;
        };
        if (action == null) return;
        var rawPos = org.cloudburstmc.math.vector.Vector3i.from(p.x, p.y, p.z);
        var rawDir = directionFromFaceIndex(p.face);
        var pos = rawPos;
        var dir = rawDir;
        boolean shouldPinToActiveTarget = shouldPinDigTarget(p, rawPos);
        if (shouldPinToActiveTarget) {
            pos = activeDigPos;
            dir = currentDigFace();
            if (!activeDigPos.equals(rawPos)) {
                log.info("Pinning LCE dig action {} from ({}, {}, {}) to active target ({}, {}, {})",
                    p.action, rawPos.getX(), rawPos.getY(), rawPos.getZ(), pos.getX(), pos.getY(), pos.getZ());
            }
        }

        int seq = ++blockActionSequence;
        if (config.logPackets) {
            log.debug(
                "LCE dig action={} seq={} raw=({}, {}, {}) rawFace={} sent=({}, {}, {}) face={} pinned={} active={} pose=({},{},{})",
                p.action,
                seq,
                rawPos.getX(),
                rawPos.getY(),
                rawPos.getZ(),
                p.face,
                pos.getX(),
                pos.getY(),
                pos.getZ(),
                dir,
                shouldPinToActiveTarget,
                activeDigPos,
                lastKnownX,
                lastKnownY,
                lastKnownZ
            );
        }
        if (p.action == 0) {
            activeDigPos = pos;
            activeDigFace = dir;
        }
        if (p.action == 0 || p.action == 1 || p.action == 2) {
            rememberRecentDigLog(pos);
            log.info(
                "Forwarding LCE dig {} seq={} sent=({}, {}, {}) face={} raw=({}, {}, {}) rawFace={}",
                describeDigAction(p.action),
                seq,
                pos.getX(),
                pos.getY(),
                pos.getZ(),
                dir,
                rawPos.getX(),
                rawPos.getY(),
                rawPos.getZ(),
                p.face
            );
        }
        javaSession.send(new ServerboundPlayerActionPacket(action, pos, dir, seq));
        if (p.action == 3 || p.action == 4) {
            predictSelectedHotbarDrop(p.action == 3);
        }
        if (p.action == 1 || p.action == 2) {
            clearActiveDigState();
        }
    }

    private void handlePlayerCommand(PlayerCommandPacket p) {
        if (!javaSession.isConnected()) return;

        // LCE actions 1/2 (sneak) use ServerboundPlayerInputPacket in 1.21.2+
        if (p.action == 1 || p.action == 2) {
            boolean sneaking = (p.action == 1);
            javaSession.send(new org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundPlayerInputPacket(
                false, false, false, false, false, sneaking, false
            ));
            return;
        }

        // Actions 3-5 still use ServerboundPlayerCommandPacket
        var state = switch (p.action) {
            case 3 -> org.geysermc.mcprotocollib.protocol.data.game.entity.player.PlayerState.LEAVE_BED;
            case 4 -> org.geysermc.mcprotocollib.protocol.data.game.entity.player.PlayerState.START_SPRINTING;
            case 5 -> org.geysermc.mcprotocollib.protocol.data.game.entity.player.PlayerState.STOP_SPRINTING;
            default -> null;
        };
        if (state == null) return;
        javaSession.send(new ServerboundPlayerCommandPacket(LCE_ENTITY_ID, state, p.data));
    }

    private void handleContainerClose(ContainerClosePacket p) {
        if (!javaSession.isConnected()) return;
        containerStateIds.remove(p.containerId);
        cachedContainerItems.remove(p.containerId);
        craftingContainerIds.remove(p.containerId);
        clearActiveCraftingContainerIfMatches(p.containerId);
        javaSession.send(new ServerboundContainerClosePacket(p.containerId));
    }

    private void handleContainerClick(ContainerClickPacket p) {
        if (!javaSession.isConnected()) return;

        boolean isCrafting = craftingContainerIds.contains(p.containerId);
        if (isCrafting) {
            log.info("[CRAFT-DBG] handleContainerClick containerId={} lceSlot={} button={} clickType={} item={}",
                p.containerId, p.slotNum, p.buttonNum, p.clickType, p.item);
        }

        // For crafting containers, validate the clicked LCE slot maps to a valid Java slot
        if (isCrafting && p.slotNum >= 0) {
            int javaSlot = lceCraftSlotToJava(p.slotNum);
            if (javaSlot == -1) {
                // Clicked a filler/unmapped slot in the fake chest — ignore
                log.debug("Crafting container: ignoring click on unmapped LCE slot {}", p.slotNum);
                ContainerAckPacket ack = new ContainerAckPacket();
                ack.containerId = p.containerId;
                ack.uid = p.uid;
                ack.accepted = false;
                sendLce(ack);
                return;
            }
        }

        ContainerActionType actionType;
        ContainerAction action;
        if (p.slotNum == -999) {
            actionType = ContainerActionType.DROP_ITEM;
            action = p.buttonNum == 1
                ? DropItemAction.RIGHT_CLICK_OUTSIDE_NOT_HOLDING
                : DropItemAction.LEFT_CLICK_OUTSIDE_NOT_HOLDING;
        } else if (p.clickType == 0) {
            actionType = ContainerActionType.CLICK_ITEM;
            action = p.buttonNum == 1 ? ClickItemAction.RIGHT_CLICK : ClickItemAction.LEFT_CLICK;
        } else if (p.clickType == 1) {
            actionType = ContainerActionType.SHIFT_CLICK_ITEM;
            action = p.buttonNum == 1 ? ShiftClickItemAction.RIGHT_CLICK : ShiftClickItemAction.LEFT_CLICK;
        } else {
            log.info("Ignoring unsupported LCE container click type={} button={} slot={}",
                p.clickType, p.buttonNum, p.slotNum);
            return;
        }

        // Prediction uses LCE slot indices (cache is LCE-indexed) — do NOT translate p.slotNum yet
        PredictedContainerClick prediction = predictContainerClick(p);

        // For the Java packet, translate LCE slot → Java slot and remap changedSlots keys
        int javaSlotNum = p.slotNum;
        var changedSlots = prediction.changedSlots();
        if (isCrafting && p.slotNum >= 0) {
            javaSlotNum = lceCraftSlotToJava(p.slotNum);
            // Remap changedSlots keys from LCE indices to Java indices
            var remapped = new it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<HashedStack>();
            for (var entry : changedSlots.int2ObjectEntrySet()) {
                int javaKey = lceCraftSlotToJava(entry.getIntKey());
                if (javaKey >= 0) {
                    remapped.put(javaKey, entry.getValue());
                }
            }
            changedSlots = remapped;
            log.info("[CRAFT-DBG]   -> sending to Java: javaSlot={} changedSlots={} carriedItem={}",
                javaSlotNum, changedSlots, prediction.carriedItem());
        }

        javaSession.send(new ServerboundContainerClickPacket(
            p.containerId,
            containerStateIds.getOrDefault(p.containerId, 0),
            javaSlotNum,
            actionType,
            action,
            MappingRegistry.items().toJavaHashed(prediction.carriedItem()),
            changedSlots
        ));

        ContainerAckPacket ack = new ContainerAckPacket();
        ack.containerId = p.containerId;
        ack.uid = p.uid;
        ack.accepted = true;
        sendLce(ack);
    }

    private void handleContainerButtonClick(ContainerButtonClickPacket p) {
        if (!javaSession.isConnected()) return;
        javaSession.send(new ServerboundContainerButtonClickPacket(p.containerId, p.buttonId));
    }

    private void handleCraftItem(CraftItemPacket p) {
        if (!javaSession.isConnected()) return;

        LegacyInventoryCrafting.LegacyRecipe legacyRecipe = LegacyInventoryCrafting.recipeForIndex(p.recipe);
        if (legacyRecipe == null) {
            log.info("[CRAFT-DBG] Unsupported legacy inventory recipe index={}", p.recipe);
            return;
        }

        LegacyInventoryCrafting.JavaRecipeCandidate candidate = null;
        LegacyInventoryCrafting.JavaRecipeCandidate fallbackCandidate = null;
        for (LegacyInventoryCrafting.JavaRecipeCandidate javaRecipe : javaInventoryCraftRecipes.values()) {
            if (!LegacyInventoryCrafting.matches(legacyRecipe, javaRecipe)) {
                continue;
            }
            if (fallbackCandidate == null) {
                fallbackCandidate = javaRecipe;
            }
            List<LceItemStack> visibleInventory = cachedContainerItems.getOrDefault(0, List.of());
            if (!visibleInventory.isEmpty() && !LegacyInventoryCrafting.canCraft(javaRecipe, visibleInventory, MappingRegistry.items())) {
                continue;
            }
            candidate = javaRecipe;
            break;
        }

        if (candidate == null && fallbackCandidate != null) {
            candidate = fallbackCandidate;
            log.info("[CRAFT-DBG] Inventory craft legacyIndex={} using recipeId={} despite local ingredient uncertainty",
                p.recipe, candidate.recipeId());
        }

        if (candidate == null) {
            if (tryManualInventoryCraft(legacyRecipe, p.recipe)) {
                return;
            }
            log.info("[CRAFT-DBG] No Java inventory recipe matched legacy index={} result={}",
                p.recipe, legacyRecipe.resultKey());
            return;
        }

        pendingInventoryCraft = new PendingInventoryCraft(p.recipe, candidate.recipeId());
        log.info("[CRAFT-DBG] Inventory craft legacyIndex={} -> javaRecipeId={} result={}",
            p.recipe, candidate.recipeId(), candidate.resultKey());
        javaSession.send(new ServerboundPlaceRecipePacket(0, candidate.recipeId(), false));
    }

    private void handleLceDisconnect(DisconnectPacket p) {
        log.info("LCE client '{}' disconnected ({})", playerName, p.reason);
        stopClientTickLoop();
        pendingInventoryCraft = null;
        javaSession.disconnect("Client disconnected");
    }

    public void handleLceTransportClosed(String reason) {
        log.info("LCE transport closed for '{}': {}", playerName, reason);
        stopClientTickLoop();
        pendingInventoryCraft = null;
        javaSession.disconnect(reason);
    }

    // ---- Java → Bridge -------------------------------------------------------
    private void handleJavaPacket(Packet pkt) {
        if (config.logPackets) {
            log.debug("Java packet: {}", pkt.getClass().getSimpleName());
        }
        switch (pkt) {
            case ClientboundLoginFinishedPacket p           -> log.info("Login phase done");
            case ClientboundFinishConfigurationPacket p     -> log.info("Config phase done");
            case ClientboundRegistryDataPacket p            -> onJavaRegistryData(p);
            case ClientboundBundlePacket p                  -> onJavaBundle(p);
            case ClientboundLoginPacket p                   -> onJavaInGameLogin(p);
            case ClientboundKeepAlivePacket p               -> onJavaKeepAlive(p);
            case ClientboundSetHealthPacket p               -> onJavaSetHealth(p);
            case ClientboundSetTimePacket p                 -> onJavaSetTime(p);
            case ClientboundGameEventPacket p               -> onJavaGameEvent(p);
            case ClientboundPlayerAbilitiesPacket p         -> onJavaPlayerAbilities(p);
            case ClientboundSetDefaultSpawnPositionPacket p -> onJavaSetSpawn(p);
            case ClientboundLevelChunkWithLightPacket p     -> onJavaChunkData(p);
            case ClientboundBlockUpdatePacket p             -> onJavaBlockUpdate(p);
            case ClientboundSectionBlocksUpdatePacket p     -> onJavaSectionBlocksUpdate(p);
            case ClientboundChunkBatchStartPacket p         -> onJavaChunkBatchStart(p);
            case ClientboundChunkBatchFinishedPacket p      -> onJavaChunkBatchFinished(p);
            case ClientboundPlayerPositionPacket p          -> onJavaPlayerPosition(p);
            case ClientboundRespawnPacket p                 -> onJavaRespawn(p);
            case ClientboundAddEntityPacket p               -> onJavaAddEntity(p);
            case ClientboundRemoveEntitiesPacket p          -> onJavaRemoveEntities(p);
            case ClientboundSetEntityDataPacket p           -> onJavaSetEntityData(p);
            case ClientboundSetEntityMotionPacket p         -> onJavaSetEntityMotion(p);
            case ClientboundEntityPositionSyncPacket p      -> onJavaEntityPositionSync(p);
            case ClientboundMoveEntityPosPacket p           -> onJavaMoveEntityPos(p);
            case ClientboundMoveEntityPosRotPacket p        -> onJavaMoveEntityPosRot(p);
            case ClientboundMoveEntityRotPacket p           -> onJavaMoveEntityRot(p);
            case ClientboundRotateHeadPacket p              -> onJavaRotateHead(p);
            case ClientboundTeleportEntityPacket p          -> onJavaTeleportEntity(p);
            case ClientboundDelimiterPacket p               -> onJavaDelimiter(p);
            case ClientboundOpenScreenPacket p              -> onJavaOpenScreen(p);
            case ClientboundUpdateRecipesPacket p          -> onJavaUpdateRecipes(p);
            case ClientboundRecipeBookAddPacket p           -> onJavaRecipeBookAdd(p);
            case ClientboundContainerSetContentPacket p     -> onJavaContainerSetContent(p);
            case ClientboundContainerSetSlotPacket p        -> onJavaContainerSetSlot(p);
            case ClientboundContainerSetDataPacket p        -> onJavaContainerSetData(p);
            case ClientboundContainerClosePacket p          -> onJavaContainerClose(p);
            case ClientboundSetPlayerInventoryPacket p      -> onJavaSetPlayerInventory(p);
            case ClientboundSetCursorItemPacket p           -> onJavaSetCursorItem(p);
            case ClientboundSystemChatPacket p              -> onJavaSystemChat(p);
            case ClientboundPlayerChatPacket p              -> onJavaPlayerChat(p);
            case ClientboundPlayerInfoUpdatePacket p        -> onJavaPlayerInfoUpdate(p);
            case ClientboundPlayerInfoRemovePacket p        -> onJavaPlayerInfoRemove(p);
            case ClientboundSetPlayerTeamPacket p           -> onJavaSetPlayerTeam(p);
            case ClientboundEntityEventPacket p             -> onJavaEntityEvent(p);
            case ClientboundUpdateAttributesPacket p        -> onJavaUpdateAttributes(p);
            case ClientboundResourcePackPushPacket p        -> onJavaResourcePackPush(p);
            case ClientboundResourcePackPopPacket p         -> onJavaResourcePackPop(p);
            case ClientboundServerLinksPacket p             -> onJavaServerLinks(p);
            case ClientboundDisconnectPacket p              -> onJavaDisconnect(p);
            default -> {
                String simple = pkt.getClass().getSimpleName();
                if (!spawnFinished.get() && (simple.contains("Chunk") || simple.contains("Light") || simple.contains("Delimiter"))) {
                    log.info("Java pre-spawn packet observed: {}", simple);
                } else {
                    log.debug("Unhandled Java packet: {}", simple);
                }
            }
        }
    }

    private void onJavaDisconnect(ClientboundDisconnectPacket p) {
        log.info("Java sent disconnect for '{}': {}", playerName, p.getReason());
        stopClientTickLoop();
        pendingInventoryCraft = null;
        sendLce(makeDisconnect(2));
        lceChannel.close();
    }

    private void onJavaBundle(ClientboundBundlePacket bundle) {
        int count = bundle.getPackets() == null ? 0 : bundle.getPackets().size();
        if (!spawnFinished.get()) {
            log.info("Java bundle received ({} inner packets)", count);
        }
        if (bundle.getPackets() == null) {
            return;
        }
        for (var inner : bundle.getPackets()) {
            if (inner instanceof Packet p) {
                handleJavaPacket(p);
            } else if (inner != null) {
                log.debug("Unhandled bundled inner packet type: {}", inner.getClass().getSimpleName());
            }
        }
    }

    private void onJavaSetPlayerTeam(ClientboundSetPlayerTeamPacket p) {
        if (config.logPackets) {
            log.debug("Ignoring Java team update team='{}' action={}", p.getTeamName(), p.getAction());
        }
    }

    private void onJavaEntityEvent(ClientboundEntityEventPacket p) {
        if (config.logPackets) {
            log.debug("Ignoring Java entity event entityId={} event={}", p.getEntityId(), p.getEvent());
        }
    }

    private void onJavaUpdateAttributes(ClientboundUpdateAttributesPacket p) {
        if (config.logPackets) {
            int count = p.getAttributes() == null ? 0 : p.getAttributes().size();
            log.debug("Ignoring Java attribute update entityId={} attributes={}", p.getEntityId(), count);
        }
    }

    private void onJavaResourcePackPush(ClientboundResourcePackPushPacket p) {
        log.warn(
            "Java server requested resource pack id={} required={} url={} hash={}",
            p.getId(),
            p.isRequired(),
            p.getUrl(),
            p.getHash()
        );
        javaSession.send(new ServerboundResourcePackPacket(p.getId(), ResourcePackStatus.ACCEPTED));
        javaSession.send(new ServerboundResourcePackPacket(p.getId(), ResourcePackStatus.DOWNLOADED));
        javaSession.send(new ServerboundResourcePackPacket(p.getId(), ResourcePackStatus.SUCCESSFULLY_LOADED));
        if (spawnFinished.get()) {
            sendChatToLce("[Bridge] Server requested a modern resource pack; acknowledging without applying it.");
        }
    }

    private void onJavaResourcePackPop(ClientboundResourcePackPopPacket p) {
        log.info("Java server cleared resource pack id={}", p.getId());
    }

    private void onJavaServerLinks(ClientboundServerLinksPacket p) {
        if (config.logPackets) {
            log.debug("Ignoring Java server links packet {}", p);
        }
    }

    private void onJavaInGameLogin(ClientboundLoginPacket p) {
        log.info("Java in-game login entityId={}", p.getEntityId());
        javaEntityId = p.getEntityId();
        if (loggedIn.getAndSet(true)) return;
        updateLevelIdx(p.getCommonPlayerSpawnInfo());
        startClientTickLoop();
        startChunkSendLoop();
        // NOTE: Do NOT send ServerboundClientInformationPacket here.
        // In the working 17:03 session, MCProtocolLib auto-sent it as part of the
        // game-phase initial burst — in the same flush as AcceptTeleportation.
        // Sending it early here (before the first teleport arrives) sends it at the
        // wrong point and vanilla's chunk pipeline never starts. ClientInformation
        // is sent in onJavaPlayerPosition alongside AcceptTeleportation instead.
        //
        // ServerboundPlayerLoadedPacket is sent later in onJavaChunkBatchFinished,
        // after the initial chunks arrive. The server only starts its "waiting for
        // loaded" timer after LEVEL_CHUNKS_LOAD_START, so sending it here (before
        // that game event) would be silently ignored, causing disconnect.timeout.
        sendSpawnSequence();
    }

    private void onJavaKeepAlive(ClientboundKeepAlivePacket p) {
        // Java keep-alive replies are handled by MCProtocolLib internals.
        // Sending another reply here creates duplicate keepalive responses.
        KeepAlivePacket lk = new KeepAlivePacket();
        lk.keepAliveId = (int) p.getPingId();
        sendLce(lk);
        log.debug("Forwarded Java keep-alive id={} to LCE", p.getPingId());
    }

    private void onJavaDelimiter(ClientboundDelimiterPacket p) {
        // Each Delimiter packet marks the end of one server tick's outbound burst.
        // The correct response is exactly one ServerboundClientTickEndPacket per
        // Delimiter — no more, no less. The background scheduler must NOT also be
        // sending these or the server gets duplicate tick-ends and disconnects us.
        delimiterSeen.set(true);
        lastDelimiterMs = System.currentTimeMillis();
        sendClientTickEndIfDue();
    }

    private void onJavaSetHealth(ClientboundSetHealthPacket p) {
        float prevHealth = lastHealth;
        lastHealth = p.getHealth();
        SetHealthPacket lh = new SetHealthPacket();
        lh.health = p.getHealth(); lh.food = (short) p.getFood(); lh.saturation = p.getSaturation();
        if (!spawnFinished.get()) {
            pendingSetHealth = lh;
            return;
        }
        sendLce(lh);

        // LCE expects explicit entity events for hurt/death animations.
        if (lastHealth <= 0.0f && prevHealth > 0.0f) {
            EntityEventPacket ev = new EntityEventPacket();
            ev.entityId = LCE_ENTITY_ID;
            ev.eventId = 3; // death
            sendLce(ev);
            log.info("Forwarded LCE death event (health {} -> {})", prevHealth, lastHealth);
        } else if (lastHealth > 0.0f && prevHealth > lastHealth) {
            EntityEventPacket ev = new EntityEventPacket();
            ev.entityId = LCE_ENTITY_ID;
            ev.eventId = 2; // hurt
            sendLce(ev);
        }
    }

    private void onJavaSetTime(ClientboundSetTimePacket p) {
        SetTimePacket lt = new SetTimePacket();
        // LCE client renders sky/clock from dayTime, not gameTime.
        // Java sends:
        //   gameTime = absolute world age
        //   dayTime  = current day-cycle ticks (may be negative when time is frozen)
        long dayTicks = p.getDayTime();
        if (dayTicks < 0) dayTicks = -dayTicks;
        lt.gameTime = p.getGameTime();
        lt.dayTime  = dayTicks % 24000L;
        if (!spawnFinished.get()) {
            pendingSetTime = lt;
            return;
        }
        sendLce(lt);
    }

    private void onJavaGameEvent(ClientboundGameEventPacket p) {
        if (config.logPackets) {
            log.debug("GameEvent: {}", p.getNotification());
        }
        switch (p.getNotification().name()) {
            case "CHANGE_GAME_MODE" -> {
                // Java sends GameMode as the value (ordinal: 0=survival, 1=creative, 2=adventure, 3=spectator)
                if (p.getValue() instanceof org.geysermc.mcprotocollib.protocol.data.game.entity.player.GameMode gm) {
                    GameEventPacket ge = new GameEventPacket();
                    ge.reason = 3; // LCE CHANGE_GAME_MODE
                    ge.param = (byte) gm.ordinal();
                    sendLce(ge);
                    log.info("Forwarded gamemode change to LCE: {} (ordinal={})", gm, gm.ordinal());
                }
                return;
            }
            case "LEVEL_CHUNKS_LOAD_START" -> {
                // Record the time we saw this event so the watchdog knows to start nudging.
                chunkLoadStartMs = System.currentTimeMillis();
                lastChunkNudgeMs = chunkLoadStartMs;
                // Only send PlayerLoaded if the teleport has already been acked.
                // In the working 17:03 session, the teleport ack (AcceptTeleportation +
                // ClientInformation) always arrived BEFORE this event. Vanilla requires
                // AcceptTeleportation before it honours PlayerLoaded — if we send PlayerLoaded
                // first, vanilla silently ignores it and never starts the chunk pipeline.
                // If the teleport hasn't arrived yet, onJavaPlayerPosition will send
                // PlayerLoaded once the ack goes out (see teleportAcked flag).
                if (teleportAcked.get()) {
                    sendPlayerLoadedOnce("Sent PlayerLoaded on LEVEL_CHUNKS_LOAD_START (teleport already acked)");
                } else {
                    log.info("LEVEL_CHUNKS_LOAD_START received before teleport ack — deferring PlayerLoaded until ack");
                }
                return;
            }
            default -> {
                // LCE native client is sensitive to event packet differences.
                // Ignore Java game events for now to prevent LevelEvent crashes.
                return;
            }
        }
    }

    private void onJavaPlayerAbilities(ClientboundPlayerAbilitiesPacket p) {
        byte flags = 0;
        if (p.isInvincible()) flags |= 1;
        if (p.isFlying()) flags |= 2;
        if (p.isCanFly()) flags |= 4;
        if (p.isCreative()) flags |= 8;
        lastJavaAbilityFlags = flags;
        lastJavaFlySpeed = p.getFlySpeed();
        lastJavaWalkSpeed = p.getWalkSpeed();

        PlayerAbilitiesPacket la = new PlayerAbilitiesPacket();
        la.flags = flags;
        la.flySpeed = p.getFlySpeed();
        la.walkSpeed = p.getWalkSpeed();
        sendLce(la);
    }

    private void onJavaSetSpawn(ClientboundSetDefaultSpawnPositionPacket p) {
        spawnX = p.getGlobalPos().getX();
        spawnY = p.getGlobalPos().getY();
        spawnZ = p.getGlobalPos().getZ();
        // Only forward SetSpawnPosition before spawn sequence completes.
        // After spawn, resending it can confuse the LCE client.
        if (spawnFinished.get()) return;
        SetSpawnPositionPacket ls = new SetSpawnPositionPacket();
        ls.x = spawnX; ls.y = spawnY; ls.z = spawnZ;
        sendLce(ls);
    }

    private void onJavaChunkData(ClientboundLevelChunkWithLightPacket p) {
        pendingChunks.add(p);
        queuedChunkCount++;
        chunkLoadStartMs = 0L;
        if (firstChunkLogged.compareAndSet(false, true)) {
            log.info("First Java chunk received at ({},{}), queue={}", p.getX(), p.getZ(), pendingChunks.size());
            scheduleEarlyPostChunkFallback();
        }
    }

    private void onJavaBlockUpdate(ClientboundBlockUpdatePacket p) {
        var entry = p.getEntry();
        if (entry == null || entry.getPosition() == null) return;
        int x = entry.getPosition().getX();
        int y = entry.getPosition().getY();
        int z = entry.getPosition().getZ();
        if (shouldLogRecentDigUpdate(x, y, z)) {
            log.info("Java block update for recent dig target at ({}, {}, {}) state={}", x, y, z, entry.getBlock());
        }
        if (config.logPackets) {
            log.debug(
                "Java block-update pos=({}, {}, {}) state={} canSendTileUpdates={}",
                x,
                y,
                z,
                entry.getBlock(),
                canSendTileUpdates()
            );
        }
        if (canSendTileUpdates()) {
            sendTileUpdate(x, y, z, entry.getBlock());
            return;
        }
        CachedLceChunkColumn cached = updateCachedChunkBlock(x, y, z, entry.getBlock());
        if (cached != null) {
            resendCachedChunk(cached);
        }
    }

    private void onJavaSectionBlocksUpdate(ClientboundSectionBlocksUpdatePacket p) {
        if (p.getEntries() == null) return;
        for (var entry : p.getEntries()) {
            if (entry == null || entry.getPosition() == null) continue;
            if (shouldLogRecentDigUpdate(entry.getPosition().getX(), entry.getPosition().getY(), entry.getPosition().getZ())) {
                log.info(
                    "Java section block update for recent dig target at ({}, {}, {}) state={}",
                    entry.getPosition().getX(),
                    entry.getPosition().getY(),
                    entry.getPosition().getZ(),
                    entry.getBlock()
                );
                break;
            }
        }
        if (config.logPackets) {
            log.debug(
                "Java section-blocks-update entries={} canSendTileUpdates={}",
                p.getEntries().length,
                canSendTileUpdates()
            );
        }
        if (canSendTileUpdates()) {
            for (var entry : p.getEntries()) {
                if (entry == null || entry.getPosition() == null) continue;
                sendTileUpdate(entry.getPosition().getX(), entry.getPosition().getY(), entry.getPosition().getZ(), entry.getBlock());
            }
            return;
        }
        Map<Long, CachedLceChunkColumn> dirtyChunks = new HashMap<>();
        for (var entry : p.getEntries()) {
            if (entry == null || entry.getPosition() == null) continue;
            CachedLceChunkColumn cached = updateCachedChunkBlock(
                entry.getPosition().getX(),
                entry.getPosition().getY(),
                entry.getPosition().getZ(),
                entry.getBlock()
            );
            if (cached != null) {
                dirtyChunks.put(chunkKey(cached.chunkX(), cached.chunkZ()), cached);
            }
        }
        for (CachedLceChunkColumn cached : dirtyChunks.values()) {
            resendCachedChunk(cached);
        }
    }

    private void onJavaOpenScreen(ClientboundOpenScreenPacket p) {
        log.info("[CRAFT-DBG] onJavaOpenScreen type={} containerId={}", p.getType(), p.getContainerId());
        ContainerOpenPacket open = translateOpenScreen(p);
        if (open == null) {
            log.info("Ignoring unsupported Java container type {}", p.getType());
            return;
        }
        containerStateIds.put(p.getContainerId(), 0);
        activeCraftingContainerId = craftingContainerIds.contains(p.getContainerId()) ? p.getContainerId() : 0;
        log.info("[CRAFT-DBG] Sending LCE container open type={} size={} customName={} title='{}'",
            open.type, open.size, open.customName, open.title);
        sendLce(open);

        // For crafting containers, immediately send filler content so the player sees the grid layout
        if (craftingContainerIds.contains(p.getContainerId())) {
            sendCraftingFillerContent(p.getContainerId());
        }
    }

    private void onJavaRecipeBookAdd(ClientboundRecipeBookAddPacket p) {
        if (p.isReplace()) {
            javaInventoryCraftEntries.clear();
            javaInventoryCraftRecipes.clear();
        }

        int added = 0;
        if (p.getEntries() != null) {
            for (ClientboundRecipeBookAddPacket.Entry entry : p.getEntries()) {
                javaInventoryCraftEntries.put(entry.contents().id(), entry.contents());
                LegacyInventoryCrafting.JavaRecipeCandidate candidate =
                    LegacyInventoryCrafting.fromRecipeDisplayEntry(entry.contents(), MappingRegistry.items(), javaRecipeItemSets);
                if (candidate == null) {
                    continue;
                }
                javaInventoryCraftRecipes.put(candidate.recipeId(), candidate);
                added++;
            }
        }

        if (added > 0 || p.isReplace()) {
            log.info("[CRAFT-DBG] Cached {} Java inventory recipe-book entries (replace={})",
                javaInventoryCraftRecipes.size(), p.isReplace());
        }
    }

    private void onJavaUpdateRecipes(ClientboundUpdateRecipesPacket p) {
        javaRecipeItemSets.clear();

        if (p.getItemSets() != null) {
            for (Map.Entry<Key, int[]> entry : p.getItemSets().entrySet()) {
                List<String> itemNames = new java.util.ArrayList<>(entry.getValue().length);
                for (int protocolItemId : entry.getValue()) {
                    String javaName = MappingRegistry.items().javaName(protocolItemId);
                    if (javaName != null && !javaName.isBlank()) {
                        itemNames.add(javaName);
                    }
                }

                Set<String> acceptedKeys = LegacyInventoryCrafting.acceptedKeysForJavaNames(itemNames);
                if (!acceptedKeys.isEmpty()) {
                    javaRecipeItemSets.put(entry.getKey(), acceptedKeys);
                }
            }
        }

        if (!javaInventoryCraftEntries.isEmpty()) {
            javaInventoryCraftRecipes.clear();
            for (RecipeDisplayEntry entry : javaInventoryCraftEntries.values()) {
                LegacyInventoryCrafting.JavaRecipeCandidate candidate =
                    LegacyInventoryCrafting.fromRecipeDisplayEntry(entry, MappingRegistry.items(), javaRecipeItemSets);
                if (candidate != null) {
                    javaInventoryCraftRecipes.put(candidate.recipeId(), candidate);
                }
            }
        }

        log.info("[CRAFT-DBG] Cached {} Java recipe item sets; {} inventory recipes ready",
            javaRecipeItemSets.size(), javaInventoryCraftRecipes.size());
    }

    /** Sends an 81-slot content packet (45 chest + 36 player inv) with glass pane fillers and empty crafting/output slots. */
    private void sendCraftingFillerContent(int containerId) {
        ContainerSetContentPacket content = new ContainerSetContentPacket();
        content.containerId = containerId;
        // 45 chest slots (filler) + 36 player inv slots (null) = 81 total
        List<LceItemStack> lceSlots = new java.util.ArrayList<>(Collections.nCopies(45, CRAFTING_FILLER_ITEM));
        // Clear the 10 crafting slots (grid + output) so they appear empty/usable
        for (int s : CRAFTING_LCE_SLOTS) lceSlots.set(s, null);
        // Append 36 null player inventory slots
        for (int i = 0; i < 36; i++) lceSlots.add(null);
        List<LceItemStack> cachedItems = new java.util.ArrayList<>();
        for (LceItemStack item : lceSlots) {
            content.items.add(item);
            cachedItems.add(copyLceItem(item));
        }
        cachedContainerItems.put(containerId, cachedItems);
        sendLce(content);
    }

    private void onJavaContainerSetContent(ClientboundContainerSetContentPacket p) {
        if (p.getContainerId() == 0 && p.getItems() != null) {
            cacheJavaPlayerContainerContent(p.getItems());
            if (p.getItems().length > 0 && p.getItems()[0] != null) {
                tryCompletePendingInventoryCraft(p.getItems()[0]);
            }
            onJavaPlayerInventoryContainerSetContent(p);
            return;
        }
        if (craftingContainerIds.contains(p.getContainerId())) {
            log.info("[CRAFT-DBG] onJavaContainerSetContent containerId={} itemCount={}",
                p.getContainerId(), p.getItems() != null ? p.getItems().length : 0);
            onJavaCraftingContainerSetContent(p);
            return;
        }
        ContainerSetContentPacket content = new ContainerSetContentPacket();
        content.containerId = p.getContainerId();
        List<LceItemStack> cachedItems = new java.util.ArrayList<>();
        if (p.getItems() != null) {
            for (var item : p.getItems()) {
                LceItemStack mapped = MappingRegistry.items().toLce(item);
                content.items.add(mapped);
                cachedItems.add(copyLceItem(mapped));
            }
        }
        cachedContainerItems.put(p.getContainerId(), cachedItems);
        containerStateIds.put(p.getContainerId(), p.getStateId());
        sendLce(content);
        sendCursorItem(p.getCarriedItem());
    }

    private void onJavaPlayerInventoryContainerSetContent(ClientboundContainerSetContentPacket p) {
        ContainerSetContentPacket content = new ContainerSetContentPacket();
        content.containerId = 0;

        List<LceItemStack> lceSlots = new java.util.ArrayList<>(Collections.nCopies(45, null));
        if (p.getItems() != null) {
            for (int javaSlot = 0; javaSlot < p.getItems().length; javaSlot++) {
                int lceSlot = translateJavaPlayerContainerSlotToLce(javaSlot);
                if (lceSlot < 0 || lceSlot >= lceSlots.size()) {
                    continue;
                }
                lceSlots.set(lceSlot, MappingRegistry.items().toLce(p.getItems()[javaSlot]));
            }
        }

        List<LceItemStack> cachedItems = new java.util.ArrayList<>(lceSlots.size());
        for (LceItemStack item : lceSlots) {
            content.items.add(item);
            cachedItems.add(copyLceItem(item));
        }

        cachedContainerItems.put(0, cachedItems);
        containerStateIds.put(0, p.getStateId());
        sendLce(content);
        sendCursorItem(p.getCarriedItem());
    }

    private void onJavaCraftingContainerSetContent(ClientboundContainerSetContentPacket p) {
        // Java sends 46 slots: 0=output, 1-9=grid, 10-36=main inv, 37-45=hotbar.
        // We remap to LCE's combined view: 45 chest slots + 36 player inv = 81 total.
        // First 45 slots: glass pane filler with crafting slots cleared.
        // Remaining 36 slots: player inventory mapped from Java slots 10-45.
        ContainerSetContentPacket content = new ContainerSetContentPacket();
        content.containerId = p.getContainerId();

        // Initialize 45 chest slots with filler glass panes
        List<LceItemStack> lceSlots = new java.util.ArrayList<>(Collections.nCopies(45, CRAFTING_FILLER_ITEM));
        // Clear the 10 crafting slots (they show empty unless Java has items)
        for (int s : CRAFTING_LCE_SLOTS) lceSlots.set(s, null);

        if (p.getItems() != null) {
            // Map crafting slots (Java 0-9 → LCE positions in the chest)
            for (int javaSlot = 0; javaSlot < Math.min(p.getItems().length, 10); javaSlot++) {
                int lceSlot = javaCraftSlotToLce(javaSlot);
                if (lceSlot >= 0 && lceSlot < 45) {
                    lceSlots.set(lceSlot, MappingRegistry.items().toLce(p.getItems()[javaSlot]));
                }
            }
            // Append player inventory slots (Java 10-45 → LCE 45-80)
            for (int javaSlot = 10; javaSlot < Math.min(p.getItems().length, 46); javaSlot++) {
                lceSlots.add(MappingRegistry.items().toLce(p.getItems()[javaSlot]));
            }
        }

        List<LceItemStack> cachedItems = new java.util.ArrayList<>();
        for (LceItemStack item : lceSlots) {
            content.items.add(item);
            cachedItems.add(copyLceItem(item));
        }

        cachedContainerItems.put(p.getContainerId(), cachedItems);
        containerStateIds.put(p.getContainerId(), p.getStateId());
        sendLce(content);
        sendCursorItem(p.getCarriedItem());
    }

    private void onJavaContainerSetSlot(ClientboundContainerSetSlotPacket p) {
        if (p.getContainerId() == 0 && isHiddenPlayerCraftingSlot(p.getSlot())) {
            setJavaPlayerContainerSlot(p.getSlot(), MappingRegistry.items().toLce(p.getItem()));
            containerStateIds.put(0, p.getStateId());
            if (p.getSlot() == 0) {
                tryCompletePendingInventoryCraft(p.getItem());
            }
            return;
        }

        if (p.getContainerId() == 0) {
            setJavaPlayerContainerSlot(p.getSlot(), MappingRegistry.items().toLce(p.getItem()));
            int lceSlot = translateJavaPlayerContainerSlotToLce(p.getSlot());
            containerStateIds.put(0, p.getStateId());
            if (lceSlot < 0) {
                return;
            }

            ContainerSetSlotPacket slot = new ContainerSetSlotPacket();
            slot.containerId = 0;
            slot.slot = lceSlot;
            slot.item = MappingRegistry.items().toLce(p.getItem());
            setCachedContainerSlot(0, lceSlot, slot.item);
            sendLce(slot);
            return;
        }

        int lceSlot = p.getSlot();

        // Translate Java crafting slot to LCE fake-chest slot
        if (craftingContainerIds.contains(p.getContainerId())) {
            log.info("[CRAFT-DBG] onJavaContainerSetSlot containerId={} javaSlot={} item={}",
                p.getContainerId(), p.getSlot(), p.getItem());
            lceSlot = javaCraftSlotToLce(p.getSlot());
            log.info("[CRAFT-DBG]   -> translated to lceSlot={}", lceSlot);
            if (lceSlot < 0) return; // unmapped — shouldn't happen
        }

        ContainerSetSlotPacket slot = new ContainerSetSlotPacket();
        slot.containerId = p.getContainerId();
        slot.slot = lceSlot;
        slot.item = MappingRegistry.items().toLce(p.getItem());
        setCachedContainerSlot(p.getContainerId(), lceSlot, slot.item);
        containerStateIds.put(p.getContainerId(), p.getStateId());
        sendLce(slot);
    }

    private void onJavaContainerSetData(ClientboundContainerSetDataPacket p) {
        ContainerSetDataPacket data = new ContainerSetDataPacket();
        data.containerId = p.getContainerId();
        data.id = p.getRawProperty();
        data.value = p.getValue();
        sendLce(data);
    }

    private void onJavaContainerClose(ClientboundContainerClosePacket p) {
        ContainerClosePacket close = new ContainerClosePacket();
        close.containerId = p.getContainerId();
        containerStateIds.remove(p.getContainerId());
        cachedContainerItems.remove(p.getContainerId());
        craftingContainerIds.remove(p.getContainerId());
        clearActiveCraftingContainerIfMatches(p.getContainerId());
        pendingInventoryCraft = null;
        sendLce(close);
    }

    private void onJavaSetPlayerInventory(ClientboundSetPlayerInventoryPacket p) {
        int javaContainerSlot = translateJavaPlayerInventorySlotToContainerSlot(p.getSlot());
        if (javaContainerSlot >= 0) {
            setJavaPlayerContainerSlot(javaContainerSlot, MappingRegistry.items().toLce(p.getContents()));
        }
        int translatedSlot = translateJavaPlayerInventorySlotToLce(p.getSlot());
        if (translatedSlot < 0) {
            log.debug("Ignoring unsupported Java player inventory slot {}", p.getSlot());
            return;
        }
        ContainerSetSlotPacket slot = new ContainerSetSlotPacket();
        slot.containerId = 0;
        slot.slot = translatedSlot;
        slot.item = MappingRegistry.items().toLce(p.getContents());
        setCachedContainerSlot(0, translatedSlot, slot.item);
        sendLce(slot);
        mirrorPlayerInventoryUpdateIntoCraftingContainer(p.getSlot(), slot.item);
    }

    private void onJavaSetCursorItem(ClientboundSetCursorItemPacket p) {
        sendCursorItem(p.getContents());
    }

    private void onJavaChunkBatchStart(ClientboundChunkBatchStartPacket p) {
        if (chunkLoadStartMs == 0L) {
            chunkLoadStartMs = System.currentTimeMillis();
            lastChunkNudgeMs = chunkLoadStartMs;
        }
        log.info("Java chunk batch start received");
    }

    private void onJavaChunkBatchFinished(ClientboundChunkBatchFinishedPacket p) {
        javaChunkBatchFinished.set(true);
        javaSession.send(new ServerboundChunkBatchReceivedPacket(advertisedChunkRate()));
        log.info("Java chunk batch finished: queued={}, pending={}", queuedChunkCount, pendingChunks.size());

        if (postChunkReady.get()) {
            sendPendingTrackedEntities();
        }

        // Some servers can finish the initial batch with zero chunk payloads.
        // In that case, complete spawn immediately instead of waiting for queue drain.
        if (pendingChunks.isEmpty() && postChunkSpawnSent.compareAndSet(false, true)) {
            sendPostChunkSpawn();
        }
    }

    private void onJavaPlayerPosition(ClientboundPlayerPositionPacket p) {
        // Send ClientInformation in the same flush as AcceptTeleportation.
        // The 17:03 working session shows vanilla requires these together — MCProtocolLib
        // used to auto-send ClientInformation as part of the game-phase initial burst
        // alongside AcceptTeleportation. Sending it earlier (e.g. in onJavaInGameLogin)
        // or later causes vanilla's chunk pipeline to never start.
        if (clientInformationSent.compareAndSet(false, true)) {
            javaSession.send(new ServerboundClientInformationPacket(
                "en_gb", 8, ChatVisibility.FULL, true,
                List.of(), HandPreference.RIGHT_HAND, false, false,
                ParticleStatus.ALL
            ));
        }
        javaSession.send(new ServerboundAcceptTeleportationPacket(p.getId()));

        // The server can flag individual position components as relative (delta to add to
        // current position). If we blindly use p.getPosition() as absolute coords we may
        // store delta values, causing the heartbeat to send garbage and Paper's movement
        // watchdog to fire (disconnect.timeout).
        var rels = p.getRelatives();
        double previousX = lastKnownX;
        double previousY = lastKnownY;
        double previousZ = lastKnownZ;
        float previousYaw = lastKnownYaw;
        float previousPitch = lastKnownPitch;
        double newX     = p.getPosition().getX();
        double newY     = p.getPosition().getY();
        double newZ     = p.getPosition().getZ();
        float  newYaw   = p.getYRot();
        float  newPitch = p.getXRot();

        if (rels.contains(org.geysermc.mcprotocollib.protocol.data.game.entity.player.PositionElement.X))     newX     += lastKnownX;
        if (rels.contains(org.geysermc.mcprotocollib.protocol.data.game.entity.player.PositionElement.Y))     newY     += lastKnownY;
        if (rels.contains(org.geysermc.mcprotocollib.protocol.data.game.entity.player.PositionElement.Z))     newZ     += lastKnownZ;
        if (rels.contains(org.geysermc.mcprotocollib.protocol.data.game.entity.player.PositionElement.Y_ROT)) newYaw   += lastKnownYaw;
        if (rels.contains(org.geysermc.mcprotocollib.protocol.data.game.entity.player.PositionElement.X_ROT)) newPitch += lastKnownPitch;

        lastKnownX     = newX;
        lastKnownY     = newY;
        lastKnownZ     = newZ;
        lastKnownYaw   = newYaw;
        lastKnownPitch = newPitch;
        lastAcceptedTeleportMs = System.currentTimeMillis();
        double deltaX = newX - previousX;
        double deltaY = newY - previousY;
        double deltaZ = newZ - previousZ;
        double horizontalDelta = Math.hypot(deltaX, deltaZ);
        boolean significantHorizontalCorrection = horizontalDelta >= FORWARDED_CORRECTION_HORIZONTAL_DELTA;
        boolean significantVerticalCorrection = Math.abs(deltaY) >= FORWARDED_CORRECTION_VERTICAL_DELTA;
        boolean significantPositionCorrection = significantHorizontalCorrection || significantVerticalCorrection;

        log.info("Accepted teleport id={} abs=({},{},{}) yaw={} pitch={} relatives={}",
            p.getId(), lastKnownX, lastKnownY, lastKnownZ, lastKnownYaw, lastKnownPitch, rels);

        // AcceptTeleportation is the authoritative ack.
        teleportAcked.set(true);

        // If LEVEL_CHUNKS_LOAD_START already fired before this teleport arrived,
        // (i.e. not just a rotation correction). This prevents the teleport→heartbeat→
        // correction→teleport loop where vanilla keeps correcting our heartbeat position.

        // Mark teleport as acked. If LEVEL_CHUNKS_LOAD_START already fired before this
        // teleport arrived, send PlayerLoaded now — vanilla requires AcceptTeleportation
        // to have gone out first, and ClientInformation was already sent above.
        if (initialTeleportHandled.compareAndSet(false, true)) {
            suppressPositionUntilMs = System.currentTimeMillis() + TELEPORT_SETTLE_MS;
            // First teleport from Java — now we have the real spawn position.
            // Mirror Java's actual abilities; do not force isFlying in survival.
            PlayerAbilitiesPacket abilities = new PlayerAbilitiesPacket();
            abilities.flags = lastJavaAbilityFlags;
            abilities.flySpeed = lastJavaFlySpeed;
            abilities.walkSpeed = lastJavaWalkSpeed;
            sendLce(abilities);

            // Send the LCE MovePlayerPosRot with the actual confirmed coordinates.
            sendLce(buildTeleport(lastKnownX, lastKnownY, lastKnownZ, lastKnownYaw, lastKnownPitch));
            rememberExpectedCorrectionEcho(lastKnownX, lastKnownY, lastKnownZ, lastKnownYaw, lastKnownPitch);

            // Send health so the LCE HUD is correct from spawn.
            SetHealthPacket sh = new SetHealthPacket();
            sh.health = 20.0f; sh.food = 20; sh.saturation = 5.0f;
            sendLce(sh);

            // Keep the original session state semantics: after the first confirmed
            // teleport, the client is considered in-world for the rest of the spawn
            // pipeline and the chunk startup watchdogs.
            spawnFinished.set(true);
            log.info("Sent initial LCE teleport at actual spawn ({},{},{})", lastKnownX, lastKnownY, lastKnownZ);
            startPositionHeartbeat();

            // If LEVEL_CHUNKS_LOAD_START already fired, send PlayerLoaded now.
            if (chunkLoadStartMs > 0L) {
                sendPlayerLoadedOnce("Deferred PlayerLoaded sent after teleport ack");
            }
        } else if (significantPositionCorrection) {
            if (significantHorizontalCorrection) {
                suppressPositionUntilMs = System.currentTimeMillis() + TELEPORT_SETTLE_MS;
            }
            sendLce(buildTeleport(lastKnownX, lastKnownY, lastKnownZ, lastKnownYaw, lastKnownPitch));
            rememberExpectedCorrectionEcho(lastKnownX, lastKnownY, lastKnownZ, lastKnownYaw, lastKnownPitch);
            log.info("Forwarded correction teleport to LCE ({},{},{}) yaw={} pitch={} delta=({},{},{})",
                lastKnownX, lastKnownY, lastKnownZ, lastKnownYaw, lastKnownPitch, deltaX, deltaY, deltaZ);
        } else if (config.logPackets) {
            log.debug(
                "Kept minor correction server-side only delta=({},{},{}) yawDelta={} pitchDelta={}",
                deltaX,
                deltaY,
                deltaZ,
                newYaw - previousYaw,
                newPitch - previousPitch
            );
        }
    }

    private void onJavaRespawn(ClientboundRespawnPacket p) {
        // Match your server behavior: send LCE Respawn first, then Java will
        // naturally follow with health + teleport updates.
        pendingInventoryCraft = null;
        updateLevelIdx(p.getCommonPlayerSpawnInfo());
        containerStateIds.clear();
        cachedContainerItems.clear();
        craftingContainerIds.clear();
        javaPlayerContainerSlots.clear();
        activeCraftingContainerId = 0;
        cachedCursorItem = null;
        spawnFinished.set(false);
        postChunkReady.set(false);
        playerLoadedSent.set(false);
        javaChunkBatchFinished.set(false);
        trackedEntitiesFallbackEnabled.set(false);
        trackedEntitiesFallbackScheduled.set(false);
        earlyPostChunkFallbackScheduled.set(false);
        tileUpdatesReadyAtMs = Long.MAX_VALUE;
        initialTeleportHandled.set(false);
        postChunkSpawnSent.set(false);
        warnedMovementBeforePostChunkReady.set(false);
        teleportAcked.set(false);
        playerMoving.set(false);
        lastMovePacketMs = 0L;
        chunkLoadStartMs = 0L;
        lastChunkNudgeMs = 0L;
        suppressPositionUntilMs = 0L;
        expectedCorrectionEchoUntilMs = 0L;
        lastAcceptedTeleportMs = 0L;
        awaitingCorrectionEcho = false;
        currentCarriedSlot = 0;
        lastSentCarriedSlot = -1;
        lastOnGround = true;
        stopPositionHeartbeat();
        clearActiveDigState();
        translatedChunkCache.clear();
        trackedEntities.clear();
        pendingTrackedItemStacks.clear();
        resetRemotePlayerIndices();
        sendLce(buildRespawnPacket());
        SetHealthPacket sh = new SetHealthPacket();
        sh.health = 20.0f;
        sh.food = 20;
        sh.saturation = 5.0f;
        sendLce(sh);
        lastHealth = 20.0f;
        log.info("Forwarded Java respawn to LCE (lceEntityId={})", LCE_ENTITY_ID);
    }

    private void onJavaAddEntity(ClientboundAddEntityPacket p) {
        TrackedEntity tracked = trackedEntityFromAddPacket(p);
        if (tracked == null) {
            return;
        }
        if (tracked.kind == TrackedEntityKind.ITEM) {
            tracked.itemStack = pendingTrackedItemStacks.remove(tracked.entityId);
        }
        TrackedEntity previous = trackedEntities.put(tracked.entityId, tracked);
        releaseTrackedEntityResources(previous);
        if (canSpawnTrackedEntities()) {
            spawnTrackedEntity(tracked);
        }
    }

    private void onJavaRemoveEntities(ClientboundRemoveEntitiesPacket p) {
        if (p.getEntityIds() == null || p.getEntityIds().length == 0) {
            return;
        }

        List<Integer> idsToRemove = new java.util.ArrayList<>();
        for (int entityId : p.getEntityIds()) {
            TrackedEntity tracked = trackedEntities.remove(entityId);
            pendingTrackedItemStacks.remove(entityId);
            releaseTrackedEntityResources(tracked);
            if (tracked != null && tracked.spawnedToLce) {
                idsToRemove.add(entityId);
            }
        }

        if (idsToRemove.isEmpty()) {
            return;
        }

        for (int start = 0; start < idsToRemove.size(); start += 255) {
            RemoveEntitiesPacket remove = new RemoveEntitiesPacket();
            remove.entityIds.addAll(idsToRemove.subList(start, Math.min(start + 255, idsToRemove.size())));
            sendLce(remove);
        }
    }

    private void onJavaSetEntityData(ClientboundSetEntityDataPacket p) {
        org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack javaItemStack = extractJavaItemEntityStack(p);
        if (javaItemStack == null) {
            return;
        }

        LceItemStack mappedItem = MappingRegistry.items().toLce(javaItemStack);
        if (mappedItem == null) {
            log.debug("Unable to map Java item entity {} stack protocolId={} count={}",
                p.getEntityId(), javaItemStack.getId(), javaItemStack.getAmount());
            return;
        }

        TrackedEntity tracked = trackedEntities.get(p.getEntityId());
        if (tracked == null) {
            pendingTrackedItemStacks.put(p.getEntityId(), mappedItem);
            return;
        }
        if (tracked.kind != TrackedEntityKind.ITEM) {
            return;
        }

        tracked.itemStack = mappedItem;
        if (!tracked.spawnedToLce && canSpawnTrackedEntities()) {
            spawnTrackedEntity(tracked);
        } else if (tracked.spawnedToLce) {
            sendTrackedItemMetadata(tracked);
        }
    }

    private void onJavaSetEntityMotion(ClientboundSetEntityMotionPacket p) {
        // Check if this velocity update is for the local player (knockback)
        if (p.getEntityId() == javaEntityId) {
            SetEntityMotionPacket lce = new SetEntityMotionPacket();
            lce.entityId = LCE_ENTITY_ID;
            lce.xa = velocityToLce(p.getMovement().getX());
            lce.ya = velocityToLce(p.getMovement().getY());
            lce.za = velocityToLce(p.getMovement().getZ());
            sendLce(lce);
            if (config.logPackets) {
                log.debug("Forwarded player knockback to LCE: ({},{},{})",
                    p.getMovement().getX(), p.getMovement().getY(), p.getMovement().getZ());
            }
            return;
        }

        TrackedEntity tracked = trackedEntities.get(p.getEntityId());
        if (tracked == null) {
            return;
        }
        tracked.motionX = p.getMovement().getX();
        tracked.motionY = p.getMovement().getY();
        tracked.motionZ = p.getMovement().getZ();
    }

    private void onJavaEntityPositionSync(ClientboundEntityPositionSyncPacket p) {
        TrackedEntity tracked = trackedEntities.get(p.getId());
        if (tracked == null) {
            return;
        }
        applyTrackedEntityPose(
            tracked,
            p.getPosition().getX(),
            p.getPosition().getY(),
            p.getPosition().getZ(),
            p.getYRot(),
            p.getXRot()
        );
        tracked.motionX = p.getDeltaMovement().getX();
        tracked.motionY = p.getDeltaMovement().getY();
        tracked.motionZ = p.getDeltaMovement().getZ();
        emitTrackedEntityTransform(tracked);
    }

    private void onJavaMoveEntityPos(ClientboundMoveEntityPosPacket p) {
        TrackedEntity tracked = trackedEntities.get(p.getEntityId());
        if (tracked == null) {
            return;
        }
        applyTrackedEntityPose(
            tracked,
            tracked.x + p.getMoveX(),
            tracked.y + p.getMoveY(),
            tracked.z + p.getMoveZ(),
            tracked.yaw,
            tracked.pitch
        );
        emitTrackedEntityTransform(tracked);
    }

    private void onJavaMoveEntityPosRot(ClientboundMoveEntityPosRotPacket p) {
        TrackedEntity tracked = trackedEntities.get(p.getEntityId());
        if (tracked == null) {
            return;
        }
        applyTrackedEntityPose(
            tracked,
            tracked.x + p.getMoveX(),
            tracked.y + p.getMoveY(),
            tracked.z + p.getMoveZ(),
            p.getYaw(),
            p.getPitch()
        );
        emitTrackedEntityTransform(tracked);
    }

    private void onJavaMoveEntityRot(ClientboundMoveEntityRotPacket p) {
        TrackedEntity tracked = trackedEntities.get(p.getEntityId());
        if (tracked == null) {
            return;
        }
        applyTrackedEntityPose(tracked, tracked.x, tracked.y, tracked.z, p.getYaw(), p.getPitch());
        emitTrackedEntityTransform(tracked);
    }

    private void onJavaRotateHead(ClientboundRotateHeadPacket p) {
        TrackedEntity tracked = trackedEntities.get(p.getEntityId());
        if (tracked == null || !tracked.spawnedToLce) {
            return;
        }
        tracked.headYaw = p.getHeadYaw();
        RotateHeadPacket lce = new RotateHeadPacket();
        lce.entityId = tracked.entityId;
        lce.yHeadRot = angleToByte(tracked.headYaw);
        sendLce(lce);
    }

    private void onJavaTeleportEntity(ClientboundTeleportEntityPacket p) {
        TrackedEntity tracked = trackedEntities.get(p.getId());
        if (tracked == null) {
            return;
        }
        Vector3d pos = p.getPosition();
        applyTrackedEntityPose(tracked, pos.getX(), pos.getY(), pos.getZ(), p.getYRot(), p.getXRot());
        tracked.motionX = p.getDeltaMovement().getX();
        tracked.motionY = p.getDeltaMovement().getY();
        tracked.motionZ = p.getDeltaMovement().getZ();
        emitTrackedEntityTransform(tracked);
    }

    private void onJavaSystemChat(ClientboundSystemChatPacket p) {
        if (!config.forwardChat) return;
        if (!spawnFinished.get()) return; // don't send chat before LCE client is in-world
        if (p.isOverlay()) return;
        if (trySendNativeLceSystemChat(p.getContent())) {
            return;
        }
        sendChatToLce(componentToChatText(p.getContent()));
    }

    private void onJavaPlayerChat(ClientboundPlayerChatPacket p) {
        if (!config.forwardChat) return;
        if (!spawnFinished.get()) return; // don't send chat before LCE client is in-world
        net.kyori.adventure.text.Component body = p.getUnsignedContent() != null
            ? p.getUnsignedContent() : net.kyori.adventure.text.Component.text(p.getContent());
        String name = componentToChatText(p.getName()).trim();
        String message = componentToChatText(body).trim();
        if (message.isEmpty()) return;
        if (name.isEmpty()) {
            sendChatToLce(message);
        } else {
            sendChatToLce("<" + name + "> " + message);
        }
    }

    private boolean trySendNativeLceSystemChat(net.kyori.adventure.text.Component content) {
        if (!(content instanceof net.kyori.adventure.text.TranslatableComponent tc)) {
            return false;
        }

        return switch (tc.key()) {
            case "multiplayer.player.joined" -> sendNativeLceChat((short) 8, tc);
            case "multiplayer.player.left" -> sendNativeLceChat((short) 7, tc);
            default -> false;
        };
    }

    private boolean sendNativeLceChat(short messageType, net.kyori.adventure.text.TranslatableComponent tc) {
        java.util.List<String> args = new java.util.ArrayList<>();
        for (net.kyori.adventure.text.TranslationArgument arg : tc.arguments()) {
            String text = sanitizeLceChat(translationArgumentToChatText(arg));
            if (!text.isEmpty()) {
                args.add(text);
            }
        }
        if (args.isEmpty()) {
            return false;
        }

        ChatPacket lc = new ChatPacket();
        lc.messageType = messageType;
        lc.stringArgs.clear();
        lc.intArgs.clear();
        lc.stringArgs.addAll(args);
        sendLce(lc);
        return true;
    }

    private void sendChatToLce(String rawText) {
        String text = sanitizeLceChat(rawText);
        if (text.isEmpty()) return;

        ChatPacket lc = new ChatPacket();
        lc.setMessage(text);
        sendLce(lc);
    }

    private String sanitizeLceChat(String rawText) {
        if (rawText == null || rawText.isBlank()) {
            return "";
        }

        StringBuilder normalized = new StringBuilder(rawText.length());
        boolean lastWasSpace = true;
        for (int i = 0; i < rawText.length();) {
            int codePoint = rawText.codePointAt(i);
            i += Character.charCount(codePoint);

            if (Character.isISOControl(codePoint) || Character.isWhitespace(codePoint)) {
                if (!lastWasSpace && normalized.length() > 0) {
                    normalized.append(' ');
                    lastWasSpace = true;
                }
                continue;
            }

            normalized.appendCodePoint(Character.isBmpCodePoint(codePoint) ? codePoint : '?');
            lastWasSpace = false;
        }

        String text = normalized.toString().trim();
        if (text.length() <= LCE_CHAT_MAX_CHARS) {
            return text;
        }

        int truncatedLength = Math.max(0, LCE_CHAT_MAX_CHARS - 3);
        String truncated = text.substring(0, truncatedLength).trim() + "...";
        if (config.logPackets) {
            log.debug("Truncated Java chat for LCE from {} to {} chars: {}", text.length(), truncated.length(), truncated);
        }
        return truncated;
    }

    private void onJavaPlayerInfoUpdate(ClientboundPlayerInfoUpdatePacket p) {
        if (p.getEntries() == null) return;
        for (PlayerListEntry entry : p.getEntries()) {
            if (entry == null || entry.getProfileId() == null) continue;
            // Cache player name from the GameProfile
            if (entry.getProfile() != null && entry.getProfile().getName() != null
                    && !entry.getProfile().getName().isBlank()) {
                String name = entry.getProfile().getName();
                if (!knownPlayerNames.containsKey(entry.getProfileId()) && knownPlayerNames.size() >= MAX_CACHED_PLAYER_NAMES) {
                    continue;
                }
                knownPlayerNames.put(entry.getProfileId(), name);
                // If we already have a tracked entity for this UUID, update its name
                for (TrackedEntity tracked : trackedEntities.values()) {
                    if (tracked.kind == TrackedEntityKind.PLAYER
                            && entry.getProfileId().equals(tracked.playerUuid)) {
                        tracked.playerName = name;
                    }
                }
            }
        }
    }

    private void onJavaPlayerInfoRemove(ClientboundPlayerInfoRemovePacket p) {
        if (!postChunkReady.get()) {
            return;
        }
        if (p.getProfileIds() == null) {
            return;
        }
        for (java.util.UUID profileId : p.getProfileIds()) {
            if (profileId != null) {
                knownPlayerNames.remove(profileId);
            }
        }
    }

    private void sendCursorItem(org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack item) {
        ContainerSetSlotPacket cursor = new ContainerSetSlotPacket();
        cursor.containerId = -1;
        cursor.slot = -1;
        cursor.item = MappingRegistry.items().toLce(item);
        cachedCursorItem = copyLceItem(cursor.item);
        sendLce(cursor);
    }

    private PredictedContainerClick predictContainerClick(ContainerClickPacket p) {
        it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<HashedStack> changedSlots =
            new it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<>();

        LceItemStack carriedBefore = copyLceItem(cachedCursorItem);
        LceItemStack carriedAfter = copyLceItem(carriedBefore);
        LceItemStack slotBefore = p.slotNum >= 0 ? getCachedContainerSlot(p.containerId, p.slotNum) : null;
        if (slotBefore == null) {
            slotBefore = copyLceItem(p.item);
        }
        LceItemStack slotAfter = copyLceItem(slotBefore);

        if (p.slotNum == -999) {
            if (carriedBefore != null) {
                if (p.buttonNum == 0) {
                    carriedAfter = null;
                } else if (carriedBefore.count > 1) {
                    carriedAfter = new LceItemStack(carriedBefore.id, carriedBefore.count - 1, carriedBefore.damage);
                } else {
                    carriedAfter = null;
                }
            }
        } else if (p.clickType == 0) {
            if (carriedBefore == null) {
                if (slotBefore != null) {
                    if (p.buttonNum == 0) {
                        carriedAfter = copyLceItem(slotBefore);
                        slotAfter = null;
                    } else {
                        int take = (slotBefore.count + 1) / 2;
                        carriedAfter = new LceItemStack(slotBefore.id, take, slotBefore.damage);
                        int remain = slotBefore.count - take;
                        slotAfter = remain > 0 ? new LceItemStack(slotBefore.id, remain, slotBefore.damage) : null;
                    }
                }
            } else if (slotBefore == null) {
                int place = p.buttonNum == 0 ? carriedBefore.count : 1;
                slotAfter = new LceItemStack(carriedBefore.id, place, carriedBefore.damage);
                int remain = carriedBefore.count - place;
                carriedAfter = remain > 0 ? new LceItemStack(carriedBefore.id, remain, carriedBefore.damage) : null;
            } else if (canStackLceItems(slotBefore, carriedBefore)) {
                int room = 64 - slotBefore.count;
                int moved = Math.min(room, p.buttonNum == 0 ? carriedBefore.count : 1);
                if (moved > 0) {
                    slotAfter = new LceItemStack(slotBefore.id, slotBefore.count + moved, slotBefore.damage);
                    int remain = carriedBefore.count - moved;
                    carriedAfter = remain > 0 ? new LceItemStack(carriedBefore.id, remain, carriedBefore.damage) : null;
                }
            } else if (p.buttonNum == 0) {
                slotAfter = copyLceItem(carriedBefore);
                carriedAfter = copyLceItem(slotBefore);
            }
        } else if (p.clickType == 1) {
            if (slotBefore != null) {
                slotAfter = null;
            }
        }

        if (p.slotNum >= 0 && !sameLceItem(slotBefore, slotAfter)) {
            setCachedContainerSlot(p.containerId, p.slotNum, slotAfter);
            changedSlots.put(p.slotNum, MappingRegistry.items().toJavaHashed(slotAfter));
        }
        cachedCursorItem = copyLceItem(carriedAfter);
        return new PredictedContainerClick(carriedAfter, changedSlots);
    }

    private LceItemStack getCachedContainerSlot(int containerId, int slot) {
        List<LceItemStack> items = cachedContainerItems.get(containerId);
        if (items == null || slot < 0 || slot >= items.size()) {
            return null;
        }
        return copyLceItem(items.get(slot));
    }

    private void setCachedContainerSlot(int containerId, int slot, LceItemStack item) {
        if (slot < 0) {
            return;
        }
        List<LceItemStack> items = cachedContainerItems.computeIfAbsent(containerId, ignored -> new java.util.ArrayList<>());
        while (items.size() <= slot) {
            items.add(null);
        }
        items.set(slot, copyLceItem(item));
    }

    private boolean canStackLceItems(LceItemStack a, LceItemStack b) {
        return a != null && b != null && a.id == b.id && a.damage == b.damage && a.count < 64;
    }

    private boolean sameLceItem(LceItemStack a, LceItemStack b) {
        if (a == b) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        return a.id == b.id && a.count == b.count && a.damage == b.damage;
    }

    private LceItemStack copyLceItem(LceItemStack item) {
        return item == null ? null : new LceItemStack(item.id, item.count, item.damage);
    }

    private record PredictedContainerClick(
        LceItemStack carriedItem,
        it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<HashedStack> changedSlots
    ) {
    }

    private boolean tryManualInventoryCraft(LegacyInventoryCrafting.LegacyRecipe legacyRecipe, int legacyRecipeIndex) {
        List<ManualInventoryCraftPlacement> placements = planManualInventoryCraft(legacyRecipe);
        if (placements.isEmpty()) {
            return false;
        }

        log.info("[CRAFT-DBG] Manual inventory craft legacyIndex={} result={} placements={}",
            legacyRecipeIndex, legacyRecipe.resultKey(), placements);

        LceItemStack carried = null;
        for (int slot = 1; slot <= 4; slot++) {
            LceItemStack existing = getJavaPlayerContainerSlot(slot);
            if (existing != null && existing.count > 0) {
                carried = sendSyntheticPlayerContainerClick(slot, 0, 1, carried);
            }
        }
        if (carried != null) {
            log.info("[CRAFT-DBG] Manual inventory craft aborted while clearing hidden grid, carried={}", carried);
            return false;
        }

        pendingInventoryCraft = new PendingInventoryCraft(legacyRecipeIndex, -1);

        Map<Integer, List<Integer>> placementsBySource = new java.util.LinkedHashMap<>();
        for (ManualInventoryCraftPlacement placement : placements) {
            placementsBySource.computeIfAbsent(placement.sourceSlot(), ignored -> new java.util.ArrayList<>())
                .add(placement.targetSlot());
        }

        for (Map.Entry<Integer, List<Integer>> entry : placementsBySource.entrySet()) {
            carried = sendSyntheticPlayerContainerClick(entry.getKey(), 0, 0, carried);
            for (int targetSlot : entry.getValue()) {
                carried = sendSyntheticPlayerContainerClick(targetSlot, 1, 0, carried);
            }
            carried = sendSyntheticPlayerContainerClick(entry.getKey(), 0, 0, carried);
        }

        if (carried != null) {
            carried = sendSyntheticPlayerContainerClick(ServerboundContainerClickPacket.CLICK_OUTSIDE_NOT_HOLDING_SLOT, 0, 0, carried);
        }

        return carried == null;
    }

    private List<ManualInventoryCraftPlacement> planManualInventoryCraft(LegacyInventoryCrafting.LegacyRecipe legacyRecipe) {
        List<ManualInventoryCraftTarget> targets = manualInventoryCraftTargets(legacyRecipe);
        if (targets.isEmpty()) {
            return List.of();
        }

        List<AvailableManualInventoryStack> available = new java.util.ArrayList<>();
        for (int slot = 9; slot <= 44; slot++) {
            LceItemStack stack = getJavaPlayerContainerSlot(slot);
            if (stack == null || stack.count <= 0) {
                continue;
            }
            String javaName = MappingRegistry.items().javaName(stack);
            Set<String> acceptedKeys = LegacyInventoryCrafting.acceptedKeysForJavaName(javaName);
            if (acceptedKeys.isEmpty()) {
                continue;
            }
            available.add(new AvailableManualInventoryStack(slot, acceptedKeys, stack.count));
        }

        List<ManualInventoryCraftPlacement> placements = new java.util.ArrayList<>();
        for (ManualInventoryCraftTarget target : targets) {
            AvailableManualInventoryStack source = null;
            for (AvailableManualInventoryStack candidate : available) {
                if (candidate.remaining <= 0 || !candidate.acceptedKeys.contains(target.ingredientKey())) {
                    continue;
                }
                source = candidate;
                break;
            }
            if (source == null) {
                return List.of();
            }

            source.remaining--;
            placements.add(new ManualInventoryCraftPlacement(source.slot, target.targetSlot()));
        }
        return placements;
    }

    private List<ManualInventoryCraftTarget> manualInventoryCraftTargets(LegacyInventoryCrafting.LegacyRecipe legacyRecipe) {
        List<ManualInventoryCraftTarget> targets = new java.util.ArrayList<>();
        if (legacyRecipe.shaped()) {
            for (int row = 0; row < legacyRecipe.height(); row++) {
                for (int col = 0; col < legacyRecipe.width(); col++) {
                    int ingredientIndex = row * legacyRecipe.width() + col;
                    if (ingredientIndex >= legacyRecipe.ingredients().size()) {
                        continue;
                    }
                    String ingredientKey = legacyRecipe.ingredients().get(ingredientIndex);
                    if (ingredientKey == null) {
                        continue;
                    }
                    int targetSlot = playerCraftGridSlot(col, row);
                    if (targetSlot >= 0) {
                        targets.add(new ManualInventoryCraftTarget(targetSlot, ingredientKey));
                    }
                }
            }
            return targets;
        }

        int targetSlot = 1;
        for (String ingredientKey : legacyRecipe.ingredients()) {
            if (ingredientKey == null) {
                continue;
            }
            targets.add(new ManualInventoryCraftTarget(targetSlot++, ingredientKey));
        }
        return targets;
    }

    private int playerCraftGridSlot(int col, int row) {
        return switch (row * 2 + col) {
            case 0 -> 1;
            case 1 -> 2;
            case 2 -> 3;
            case 3 -> 4;
            default -> -1;
        };
    }

    private LceItemStack sendSyntheticPlayerContainerClick(int slotNum, int buttonNum, int clickType, LceItemStack carriedBefore) {
        ContainerActionType actionType;
        ContainerAction action;
        if (slotNum == ServerboundContainerClickPacket.CLICK_OUTSIDE_NOT_HOLDING_SLOT) {
            actionType = ContainerActionType.DROP_ITEM;
            action = buttonNum == 1
                ? DropItemAction.RIGHT_CLICK_OUTSIDE_NOT_HOLDING
                : DropItemAction.LEFT_CLICK_OUTSIDE_NOT_HOLDING;
        } else if (clickType == 0) {
            actionType = ContainerActionType.CLICK_ITEM;
            action = buttonNum == 1 ? ClickItemAction.RIGHT_CLICK : ClickItemAction.LEFT_CLICK;
        } else if (clickType == 1) {
            actionType = ContainerActionType.SHIFT_CLICK_ITEM;
            action = buttonNum == 1 ? ShiftClickItemAction.RIGHT_CLICK : ShiftClickItemAction.LEFT_CLICK;
        } else {
            return carriedBefore;
        }

        it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<HashedStack> changedSlots =
            new it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<>();
        LceItemStack carriedAfter = copyLceItem(carriedBefore);
        LceItemStack slotBefore = slotNum >= 0 ? getJavaPlayerContainerSlot(slotNum) : null;
        LceItemStack slotAfter = copyLceItem(slotBefore);

        if (slotNum == ServerboundContainerClickPacket.CLICK_OUTSIDE_NOT_HOLDING_SLOT) {
            if (carriedBefore != null) {
                if (buttonNum == 0 || carriedBefore.count <= 1) {
                    carriedAfter = null;
                } else {
                    carriedAfter = new LceItemStack(carriedBefore.id, carriedBefore.count - 1, carriedBefore.damage);
                }
            }
        } else if (clickType == 0) {
            if (carriedBefore == null) {
                if (slotBefore != null) {
                    if (buttonNum == 0) {
                        carriedAfter = copyLceItem(slotBefore);
                        slotAfter = null;
                    } else {
                        int take = (slotBefore.count + 1) / 2;
                        carriedAfter = new LceItemStack(slotBefore.id, take, slotBefore.damage);
                        int remain = slotBefore.count - take;
                        slotAfter = remain > 0 ? new LceItemStack(slotBefore.id, remain, slotBefore.damage) : null;
                    }
                }
            } else if (slotBefore == null) {
                int place = buttonNum == 0 ? carriedBefore.count : 1;
                slotAfter = new LceItemStack(carriedBefore.id, place, carriedBefore.damage);
                int remain = carriedBefore.count - place;
                carriedAfter = remain > 0 ? new LceItemStack(carriedBefore.id, remain, carriedBefore.damage) : null;
            } else if (canStackLceItems(slotBefore, carriedBefore)) {
                int room = 64 - slotBefore.count;
                int moved = Math.min(room, buttonNum == 0 ? carriedBefore.count : 1);
                if (moved > 0) {
                    slotAfter = new LceItemStack(slotBefore.id, slotBefore.count + moved, slotBefore.damage);
                    int remain = carriedBefore.count - moved;
                    carriedAfter = remain > 0 ? new LceItemStack(carriedBefore.id, remain, carriedBefore.damage) : null;
                }
            } else if (buttonNum == 0) {
                slotAfter = copyLceItem(carriedBefore);
                carriedAfter = copyLceItem(slotBefore);
            }
        } else if (clickType == 1 && slotBefore != null) {
            slotAfter = null;
        }

        if (slotNum >= 0 && !sameLceItem(slotBefore, slotAfter)) {
            setJavaPlayerContainerSlot(slotNum, slotAfter);
            changedSlots.put(slotNum, MappingRegistry.items().toJavaHashed(slotAfter));
        }

        javaSession.send(new ServerboundContainerClickPacket(
            0,
            containerStateIds.getOrDefault(0, 0),
            slotNum,
            actionType,
            action,
            MappingRegistry.items().toJavaHashed(carriedAfter),
            changedSlots
        ));

        log.info("[CRAFT-DBG] synthetic player click slot={} button={} clickType={} changedSlots={} carriedBefore={} carriedAfter={}",
            slotNum, buttonNum, clickType, changedSlots, carriedBefore, carriedAfter);
        return carriedAfter;
    }

    private void cacheJavaPlayerContainerContent(org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack[] items) {
        javaPlayerContainerSlots.clear();
        for (int slot = 0; slot < items.length; slot++) {
            setJavaPlayerContainerSlot(slot, MappingRegistry.items().toLce(items[slot]));
        }
    }

    private LceItemStack getJavaPlayerContainerSlot(int slot) {
        return copyLceItem(javaPlayerContainerSlots.get(slot));
    }

    private void setJavaPlayerContainerSlot(int slot, LceItemStack item) {
        if (slot < 0) {
            return;
        }
        if (item == null) {
            javaPlayerContainerSlots.remove(slot);
        } else {
            javaPlayerContainerSlots.put(slot, copyLceItem(item));
        }
    }

    private record ManualInventoryCraftTarget(int targetSlot, String ingredientKey) {
    }

    private record ManualInventoryCraftPlacement(int sourceSlot, int targetSlot) {
    }

    private static final class AvailableManualInventoryStack {
        private final int slot;
        private final Set<String> acceptedKeys;
        private int remaining;

        private AvailableManualInventoryStack(int slot, Set<String> acceptedKeys, int remaining) {
            this.slot = slot;
            this.acceptedKeys = acceptedKeys;
            this.remaining = remaining;
        }
    }

    private void predictSelectedHotbarDrop(boolean fullStack) {
        int slot = selectedHotbarInventorySlot();
        LceItemStack existing = getCachedContainerSlot(0, slot);
        if (existing == null) {
            return;
        }

        LceItemStack updated;
        if (fullStack || existing.count <= 1) {
            updated = null;
        } else {
            updated = new LceItemStack(existing.id, existing.count - 1, existing.damage);
        }
        applyPredictedInventorySlot(0, slot, updated);
    }

    private void predictSelectedHotbarPlacement(LceItemStack usedItem) {
        if (usedItem == null || !MappingRegistry.items().isLikelyPlaceableBlock(usedItem)) {
            return;
        }

        int slot = selectedHotbarInventorySlot();
        LceItemStack existing = getCachedContainerSlot(0, slot);
        if (existing == null || existing.id != usedItem.id || existing.damage != usedItem.damage) {
            return;
        }

        LceItemStack updated = existing.count <= 1
            ? null
            : new LceItemStack(existing.id, existing.count - 1, existing.damage);
        applyPredictedInventorySlot(0, slot, updated);
    }

    private int selectedHotbarInventorySlot() {
        return 36 + Math.max(0, Math.min(currentCarriedSlot, 8));
    }

    private void applyPredictedInventorySlot(int containerId, int slot, LceItemStack item) {
        setCachedContainerSlot(containerId, slot, item);
        ContainerSetSlotPacket update = new ContainerSetSlotPacket();
        update.containerId = containerId;
        update.slot = slot;
        update.item = copyLceItem(item);
        sendLce(update);
    }

    private boolean isHiddenPlayerCraftingSlot(int javaSlot) {
        return javaSlot >= 0 && javaSlot <= 4;
    }

    private void tryCompletePendingInventoryCraft(org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack resultItem) {
        PendingInventoryCraft pending = pendingInventoryCraft;
        if (pending == null || resultItem == null || resultItem.getAmount() <= 0) {
            return;
        }

        pendingInventoryCraft = null;
        var changedSlots = new it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<HashedStack>();
        changedSlots.put(0, null);
        javaSession.send(new ServerboundContainerClickPacket(
            0,
            containerStateIds.getOrDefault(0, 0),
            0,
            ContainerActionType.SHIFT_CLICK_ITEM,
            ShiftClickItemAction.LEFT_CLICK,
            null,
            changedSlots
        ));
        log.info("[CRAFT-DBG] Auto-taking player inventory craft result for legacyIndex={} javaRecipeId={}",
            pending.legacyRecipeIndex(), pending.javaRecipeId());
    }

    private void clearActiveCraftingContainerIfMatches(int containerId) {
        if (activeCraftingContainerId == containerId) {
            activeCraftingContainerId = 0;
        }
    }

    private void mirrorPlayerInventoryUpdateIntoCraftingContainer(int javaPlayerInventorySlot, LceItemStack item) {
        int containerId = activeCraftingContainerId;
        if (containerId == 0 || !craftingContainerIds.contains(containerId)) {
            return;
        }

        int lceSlot = javaPlayerInventorySlotToCraftingLce(javaPlayerInventorySlot);
        if (lceSlot < 0) {
            return;
        }
        if (sameLceItem(getCachedContainerSlot(containerId, lceSlot), item)) {
            return;
        }

        setCachedContainerSlot(containerId, lceSlot, item);
        ContainerSetSlotPacket slot = new ContainerSetSlotPacket();
        slot.containerId = containerId;
        slot.slot = lceSlot;
        slot.item = copyLceItem(item);
        sendLce(slot);
    }

    private void sendTileUpdate(int x, int y, int z, int javaBlockState) {
        TileUpdatePacket update = new TileUpdatePacket();
        update.x = x;
        update.y = y;
        update.z = z;
        update.block = MappingRegistry.blocks().getLceId(javaBlockState);
        update.data = MappingRegistry.blocks().getLceData(javaBlockState);
        update.levelIdx = currentLevelIdx;
        sendLce(update);
    }

    private ContainerOpenPacket translateOpenScreen(ClientboundOpenScreenPacket p) {
        ContainerOpenPacket open = new ContainerOpenPacket();
        open.containerId = p.getContainerId();
        String title = p.getTitle() == null ? "" : componentToPlain(p.getTitle());

        // If the title is a vanilla translation key (e.g. "container.chest", "block.minecraft.furnace"),
        // it's not a custom name — let the LCE client use its built-in translations.
        boolean isTranslationKey = title.startsWith("container.") || title.startsWith("block.")
                || title.startsWith("entity.") || title.startsWith("item.");
        open.customName = !title.isBlank() && !isTranslationKey;
        open.title = open.customName ? title : "";

        return switch (p.getType()) {
            case GENERIC_9X1 -> configureContainer(open, ContainerOpenPacket.CONTAINER, 9);
            case GENERIC_9X2 -> configureContainer(open, ContainerOpenPacket.CONTAINER, 18);
            case GENERIC_9X3 -> configureContainer(open, ContainerOpenPacket.CONTAINER, 27);
            case GENERIC_9X4 -> configureContainer(open, ContainerOpenPacket.CONTAINER, 36);
            case GENERIC_9X5 -> configureContainer(open, ContainerOpenPacket.CONTAINER, 45);
            case GENERIC_9X6, SHULKER_BOX -> configureContainer(open, ContainerOpenPacket.LARGE_CHEST, 54);
            case CRAFTING, GENERIC_3X3, CRAFTER_3x3 -> {
                // Send a generic chest container instead of WORKBENCH to bypass LCE recipe-book UI.
                // The LCE player places items manually in a 3x3 grid layout within the chest,
                // and the bridge translates slot numbers bidirectionally.
                // Layout in the 45-slot chest:
                //   Row 0 (slots 0-8):   [■][■][■][■][■][■][■][■][■]   all filler
                //   Row 1 (slots 9-17):  [■][■][1][2][3][■][O][■][■]   grid top + output
                //   Row 2 (slots 18-26): [■][■][4][5][6][■][■][■][■]   grid middle
                //   Row 3 (slots 27-35): [■][■][7][8][9][■][■][■][■]   grid bottom
                //   Row 4 (slots 36-44): [■][■][■][■][■][■][■][■][■]   all filler
                open.customName = true;
                open.title = "Crafting Table";
                craftingContainerIds.add(open.containerId);
                yield configureContainer(open, ContainerOpenPacket.CONTAINER, 45);
            }
            case FURNACE, BLAST_FURNACE, SMOKER -> configureContainer(open, ContainerOpenPacket.FURNACE, 3);
            case BREWING_STAND -> configureContainer(open, ContainerOpenPacket.BREWING_STAND, 4);
            case ENCHANTMENT -> configureContainer(open, ContainerOpenPacket.ENCHANTMENT, 9);
            case MERCHANT -> configureContainer(open, ContainerOpenPacket.TRADER_NPC, 3);
            case BEACON -> configureContainer(open, ContainerOpenPacket.BEACON, 1);
            case HOPPER -> configureContainer(open, ContainerOpenPacket.HOPPER, 5);
            case ANVIL, SMITHING, GRINDSTONE -> configureContainer(open, ContainerOpenPacket.REPAIR_TABLE, 9);
            default -> null;
        };
    }

    private ContainerOpenPacket configureContainer(ContainerOpenPacket open, int type, int size) {
        open.type = type;
        open.size = size;
        return open;
    }

    // ---- Crafting container slot mapping ------------------------------------
    // Java crafting table layout:
    //   Slot 0: output, Slots 1-9: 3x3 grid, Slots 10-36: main inv, Slots 37-45: hotbar
    // LCE fake chest layout (45 slots = 5 rows of 9):
    //   Row 0: [■][■][■][■][■][■][■][■][■]           all filler
    //   Row 1: [■][■][g1][g2][g3][■][O][■][■]         grid top (11-13) + output (15)
    //   Row 2: [■][■][g4][g5][g6][■][■][■][■]         grid mid (20-22)
    //   Row 3: [■][■][g7][g8][g9][■][■][■][■]         grid bot (29-31)
    //   Row 4: [■][■][■][■][■][■][■][■][■]           all filler
    //
    // Java grid 1->LCE 11, 2->12, 3->13, 4->20, 5->21, 6->22, 7->29, 8->30, 9->31
    // Java output 0->LCE 15

    /** Map from a Java crafting container slot to the corresponding LCE chest slot. Returns -1 for unmapped. */
    private int javaCraftSlotToLce(int javaSlot) {
        return switch (javaSlot) {
            case 0 -> 15;  // output
            case 1 -> 11;  // grid row 0
            case 2 -> 12;
            case 3 -> 13;
            case 4 -> 20;  // grid row 1
            case 5 -> 21;
            case 6 -> 22;
            case 7 -> 29;  // grid row 2
            case 8 -> 30;
            case 9 -> 31;
            default -> {
                // Java slots 10-36 = main inv (3 rows of 9) → LCE 45-71
                // Java slots 37-45 = hotbar → LCE 72-80
                if (javaSlot >= 10 && javaSlot <= 45) {
                    yield (javaSlot - 10) + 45;
                }
                yield -1;
            }
        };
    }

    /** Map from a Java player inventory slot (0-35) to the visible LCE slot in the fake crafting chest. */
    private int javaPlayerInventorySlotToCraftingLce(int javaSlot) {
        if (javaSlot >= 0 && javaSlot <= 8) {
            return 72 + javaSlot;
        }
        if (javaSlot >= 9 && javaSlot <= 35) {
            return 45 + (javaSlot - 9);
        }
        return -1;
    }

    /** Map from an LCE chest slot (from a click) to the corresponding Java crafting container slot. Returns -1 for unmapped. */
    private int lceCraftSlotToJava(int lceSlot) {
        return switch (lceSlot) {
            case 15 -> 0;  // output
            case 11 -> 1;  // grid row 0
            case 12 -> 2;
            case 13 -> 3;
            case 20 -> 4;  // grid row 1
            case 21 -> 5;
            case 22 -> 6;
            case 29 -> 7;  // grid row 2
            case 30 -> 8;
            case 31 -> 9;
            default -> {
                // LCE 45-71 = main inv → Java 10-36
                // LCE 72-80 = hotbar → Java 37-45
                if (lceSlot >= 45 && lceSlot <= 80) {
                    yield (lceSlot - 45) + 10;
                }
                yield -1; // filler slot (0-44 range, not a crafting or inv slot)
            }
        };
    }

    private void updateLevelIdx(PlayerSpawnInfo info) {
        if (info == null || info.getWorldName() == null) {
            currentLevelIdx = 0;
            return;
        }

        String worldName = info.getWorldName().asString();
        currentLevelIdx = switch (worldName) {
            case "minecraft:the_nether" -> 1;
            case "minecraft:the_end" -> 2;
            default -> 0;
        };
    }

    /**
     * Converts an Adventure Component to a plain string without Adventure's serializer dep.
     * Uses Component.toString() but strips the class noise, falling back to key for
     * translatable components so players see something readable rather than class names.
     */
    private static String componentToPlain(net.kyori.adventure.text.Component c) {
        if (c == null) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        if (c instanceof net.kyori.adventure.text.TextComponent tc) {
            sb.append(tc.content());
        } else if (c instanceof net.kyori.adventure.text.TranslatableComponent tc) {
            if (tc.fallback() != null && !tc.fallback().isBlank()) {
                sb.append(tc.fallback());
            } else {
                sb.append(tc.key());
            }
            for (net.kyori.adventure.text.TranslationArgument arg : tc.arguments()) {
                String plainArg = translationArgumentToPlain(arg);
                if (!plainArg.isBlank()) {
                    if (sb.length() > 0) {
                        sb.append(' ');
                    }
                    sb.append(plainArg);
                }
            }
        } else if (c instanceof net.kyori.adventure.text.KeybindComponent kc) {
            sb.append(kc.keybind());
        } else if (c instanceof net.kyori.adventure.text.ScoreComponent sc) {
            sb.append(sc.name());
        } else if (c instanceof net.kyori.adventure.text.SelectorComponent sc) {
            sb.append(sc.pattern());
        } else if (c instanceof net.kyori.adventure.text.NBTComponent<?, ?> nc) {
            sb.append(nc.nbtPath());
        } else if (c instanceof net.kyori.adventure.text.ObjectComponent oc && oc.contents() != null) {
            sb.append(String.valueOf(oc.contents()));
        }

        for (net.kyori.adventure.text.Component child : c.children()) {
            sb.append(componentToPlain(child));
        }
        return sb.toString();
    }

    private static String componentToChatText(net.kyori.adventure.text.Component c) {
        if (c == null) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        if (c instanceof net.kyori.adventure.text.TextComponent tc) {
            sb.append(tc.content());
        } else if (c instanceof net.kyori.adventure.text.TranslatableComponent tc) {
            String translated = translateChatKey(tc);
            if (translated != null) {
                sb.append(translated);
            } else if (tc.fallback() != null && !tc.fallback().isBlank()) {
                sb.append(tc.fallback());
            } else {
                sb.append(tc.key());
                for (net.kyori.adventure.text.TranslationArgument arg : tc.arguments()) {
                    String plainArg = translationArgumentToChatText(arg);
                    if (!plainArg.isBlank()) {
                        sb.append(' ').append(plainArg);
                    }
                }
            }
        } else if (c instanceof net.kyori.adventure.text.KeybindComponent kc) {
            sb.append(kc.keybind());
        } else if (c instanceof net.kyori.adventure.text.ScoreComponent sc) {
            sb.append(sc.name());
        } else if (c instanceof net.kyori.adventure.text.SelectorComponent sc) {
            sb.append(sc.pattern());
        } else if (c instanceof net.kyori.adventure.text.NBTComponent<?, ?> nc) {
            sb.append(nc.nbtPath());
        } else if (c instanceof net.kyori.adventure.text.ObjectComponent oc && oc.contents() != null) {
            sb.append(String.valueOf(oc.contents()));
        }

        for (net.kyori.adventure.text.Component child : c.children()) {
            sb.append(componentToChatText(child));
        }
        return sb.toString();
    }

    private static String translateChatKey(net.kyori.adventure.text.TranslatableComponent tc) {
        java.util.List<String> args = new java.util.ArrayList<>();
        for (net.kyori.adventure.text.TranslationArgument arg : tc.arguments()) {
            args.add(translationArgumentToChatText(arg));
        }

        String template = CHAT_TRANSLATIONS.get(tc.key());
        return template == null ? null : formatChatTemplate(template, args);
    }

    private static String formatChatTemplate(String template, java.util.List<String> args) {
        if (template == null || template.isEmpty()) {
            return "";
        }

        StringBuilder formatted = new StringBuilder(template.length() + (args.size() * 16));
        int nextImplicitArg = 0;
        for (int i = 0; i < template.length(); i++) {
            char ch = template.charAt(i);
            if (ch != '%') {
                formatted.append(ch);
                continue;
            }

            if (i + 1 >= template.length()) {
                formatted.append(ch);
                continue;
            }

            char next = template.charAt(i + 1);
            if (next == '%') {
                formatted.append('%');
                i++;
                continue;
            }

            int argIndex = -1;
            int cursor = i + 1;
            int explicitIndex = 0;
            while (cursor < template.length() && Character.isDigit(template.charAt(cursor))) {
                explicitIndex = (explicitIndex * 10) + (template.charAt(cursor) - '0');
                cursor++;
            }

            if (cursor + 1 < template.length() && cursor > i + 1
                    && template.charAt(cursor) == '$' && template.charAt(cursor + 1) == 's') {
                argIndex = explicitIndex - 1;
                i = cursor + 1;
            } else if (next == 's') {
                argIndex = nextImplicitArg++;
                i++;
            } else {
                formatted.append(ch);
                continue;
            }

            if (argIndex >= 0 && argIndex < args.size()) {
                formatted.append(args.get(argIndex));
            }
        }
        return formatted.toString();
    }

    private static Map<String, String> loadChatTranslations() {
        try (InputStream stream = LceBridgeSession.class.getClassLoader().getResourceAsStream("lang/chat_en_us.json")) {
            if (stream == null) {
                log.warn("Missing chat translation bundle: lang/chat_en_us.json");
                return Collections.emptyMap();
            }

            JsonObject root = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
            Map<String, String> translations = new HashMap<>();
            for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
                if (entry.getValue().isJsonPrimitive()) {
                    translations.put(entry.getKey(), entry.getValue().getAsString());
                }
            }
            return Collections.unmodifiableMap(translations);
        } catch (IOException | RuntimeException e) {
            log.warn("Failed to load chat translation bundle", e);
            return Collections.emptyMap();
        }
    }

    private static String translationArgumentToPlain(net.kyori.adventure.text.TranslationArgument arg) {
        if (arg == null) {
            return "";
        }
        Object value = arg.value();
        if (value instanceof net.kyori.adventure.text.ComponentLike componentLike) {
            return componentToPlain(componentLike.asComponent());
        }
        return value == null ? "" : String.valueOf(value);
    }

    private static String translationArgumentToChatText(net.kyori.adventure.text.TranslationArgument arg) {
        if (arg == null) {
            return "";
        }
        Object value = arg.value();
        if (value instanceof net.kyori.adventure.text.ComponentLike componentLike) {
            return componentToChatText(componentLike.asComponent());
        }
        return value == null ? "" : String.valueOf(value);
    }

    private float advertisedChunkRate() {
        return (float) Math.max(1, config.chunksPerTick);
    }

    public void cacheTranslatedChunk(int chunkX, int chunkZ, CachedLceChunkColumn column) {
        translatedChunkCache.put(chunkKey(chunkX, chunkZ), column);
    }

    private long chunkKey(int chunkX, int chunkZ) {
        return (((long) chunkX) << 32) ^ (chunkZ & 0xffffffffL);
    }

    private CachedLceChunkColumn updateCachedChunkBlock(int x, int y, int z, int javaBlockState) {
        if (y < 0 || y > 255) {
            return null;
        }
        int chunkX = Math.floorDiv(x, 16);
        int chunkZ = Math.floorDiv(z, 16);
        CachedLceChunkColumn cached = translatedChunkCache.get(chunkKey(chunkX, chunkZ));
        if (cached == null) {
            return null;
        }
        int localX = Math.floorMod(x, 16);
        int localZ = Math.floorMod(z, 16);
        cached.setBlock(
            localX,
            y,
            localZ,
            MappingRegistry.blocks().getLceId(javaBlockState),
            MappingRegistry.blocks().getLceData(javaBlockState)
        );
        return cached;
    }

    private void resendCachedChunk(CachedLceChunkColumn cached) {
        try {
            byte[] raw = cached.buildRawData();
            byte[] comp = RleZlibCompressor.compress(raw);

            ChunkVisibilityPacket vis = new ChunkVisibilityPacket();
            vis.chunkX = cached.chunkX();
            vis.chunkZ = cached.chunkZ();
            vis.visible = true;
            sendLce(vis);

            BlockRegionUpdatePacket bru = new BlockRegionUpdatePacket();
            bru.x = cached.chunkX() << 4;
            bru.y = 0;
            bru.z = cached.chunkZ() << 4;
            bru.xs = 16;
            bru.ys = 256;
            bru.zs = 16;
            bru.levelIdx = currentLevelIdx;
            bru.isFullChunk = true;
            bru.compressedData = comp;
            sendLce(bru);
        } catch (Exception e) {
            log.warn("Failed to resend cached chunk ({}, {}) after block update", cached.chunkX(), cached.chunkZ(), e);
        }
    }

    private TrackedEntity trackedEntityFromAddPacket(ClientboundAddEntityPacket p) {
        if (p == null) {
            return null;
        }
        int entityId = p.getEntityId();
        if (!isSupportedLceEntityId(entityId) || entityId == LCE_ENTITY_ID) {
            return null;
        }

        EntityType type = p.getType();
        if (type == null) {
            return null;
        }

        TrackedEntity tracked;
        if (type == EntityType.PLAYER) {
            if (activeRemotePlayerCount.get() >= MAX_REMOTE_PLAYER_ENTITIES) {
                log.debug("Skipping remote player entity {} because the crowded-server cap ({}) was reached", entityId, MAX_REMOTE_PLAYER_ENTITIES);
                return null;
            }
            Integer playerIndex = freeRemotePlayerIndices.poll();
            if (playerIndex == null) {
                log.debug("Skipping remote player entity {} because no player index slots are available", entityId);
                return null;
            }
            tracked = new TrackedEntity(entityId, TrackedEntityKind.PLAYER, 0);
            tracked.headYaw = p.getHeadYaw();
            tracked.playerUuid = p.getUuid();
            String cachedName = p.getUuid() != null ? knownPlayerNames.get(p.getUuid()) : null;
            tracked.playerName = cachedName != null ? cachedName : "Player";
            tracked.playerIndex = playerIndex;
            activeRemotePlayerCount.incrementAndGet();
        } else {
            Integer mobType = mapJavaMobType(type);
        if (mobType != null) {
            tracked = new TrackedEntity(entityId, TrackedEntityKind.MOB, mobType);
            tracked.headYaw = p.getHeadYaw();
        } else {
            Integer objectType = mapJavaObjectType(type);
            if (objectType == null) {
                return null;
            }
            tracked = new TrackedEntity(
                entityId,
                type == EntityType.ITEM ? TrackedEntityKind.ITEM : TrackedEntityKind.OBJECT,
                objectType
            );
            tracked.data = mapJavaObjectData(type, p.getData());
        }
        }

        applyTrackedEntityPose(tracked, p.getX(), p.getY(), p.getZ(), p.getYaw(), p.getPitch());
        tracked.motionX = p.getMovement().getX();
        tracked.motionY = p.getMovement().getY();
        tracked.motionZ = p.getMovement().getZ();
        return tracked;
    }

    private void applyTrackedEntityPose(TrackedEntity tracked, double x, double y, double z, float yaw, float pitch) {
        tracked.x = x;
        tracked.y = y;
        tracked.z = z;
        tracked.yaw = yaw;
        tracked.pitch = pitch;
    }

    private void emitTrackedEntityTransform(TrackedEntity tracked) {
        if (tracked == null || !canSpawnTrackedEntities()) {
            return;
        }
        if (!tracked.spawnedToLce) {
            spawnTrackedEntity(tracked);
            return;
        }
        sendLce(buildEntityTeleport(tracked));
    }

    private void spawnTrackedEntity(TrackedEntity tracked) {
        if (tracked == null || !canSpawnTrackedEntities()) {
            return;
        }
        if (tracked.kind == TrackedEntityKind.ITEM && tracked.itemStack == null) {
            return;
        }
        switch (tracked.kind) {
            case PLAYER -> {
                AddPlayerPacket player = new AddPlayerPacket();
                player.entityId = tracked.entityId;
                player.name = tracked.playerName != null ? tracked.playerName : "Player";
                player.x = toLceFixed(tracked.x);
                player.y = toLceFixed(tracked.y);
                player.z = toLceFixed(tracked.z);
                player.yaw = angleToByte(tracked.yaw);
                player.pitch = angleToByte(tracked.pitch);
                player.headYaw = angleToByte(tracked.headYaw);
                player.carriedItem = 0;
                player.offlineXuid = tracked.entityId; // Use entity ID as fake XUID
                player.onlineXuid = 0L;
                player.playerIndex = tracked.playerIndex;
                player.skinId = 0;
                player.capeId = 0;
                player.gamePrivileges = 0;
                // Entity flags byte 0 (required — same reason as AddMobPacket)
                player.metadata.add(new SetEntityDataPacket.DataValue(
                    0,
                    SetEntityDataPacket.TYPE_BYTE,
                    0
                ));
                sendLce(player);
                log.info("Spawned player entity '{}' id={} at ({},{},{}) playerIndex={}",
                    player.name, tracked.entityId, tracked.x, tracked.y, tracked.z, tracked.playerIndex);
            }
            case MOB -> {
                AddMobPacket mob = new AddMobPacket();
                mob.entityId = tracked.entityId;
                mob.type = tracked.lceType;
                mob.x = toLceFixed(tracked.x);
                mob.y = toLceFixed(tracked.y);
                mob.z = toLceFixed(tracked.z);
                mob.yaw = angleToByte(tracked.yaw);
                mob.pitch = angleToByte(tracked.pitch);
                mob.headYaw = angleToByte(tracked.headYaw);
                mob.motionX = velocityToLce(tracked.motionX);
                mob.motionY = velocityToLce(tracked.motionY);
                mob.motionZ = velocityToLce(tracked.motionZ);
                // LCE's AddMobPacket client path crashes if the metadata payload is completely
                // empty because it later falls back to a null server-side entityData pointer.
                // Send the base entity flags entry explicitly, even when all flags are zero.
                mob.metadata.add(new SetEntityDataPacket.DataValue(
                    0,
                    SetEntityDataPacket.TYPE_BYTE,
                    0
                ));
                sendLce(mob);
            }
            case ITEM, OBJECT -> {
                AddEntityPacket entity = new AddEntityPacket();
                entity.entityId = tracked.entityId;
                entity.type = tracked.lceType;
                entity.x = toLceFixed(tracked.x);
                entity.y = toLceFixed(tracked.y);
                entity.z = toLceFixed(tracked.z);
                entity.yaw = angleToByte(tracked.yaw);
                entity.pitch = angleToByte(tracked.pitch);
                entity.data = tracked.data;
                entity.motionX = velocityToLce(tracked.motionX);
                entity.motionY = velocityToLce(tracked.motionY);
                entity.motionZ = velocityToLce(tracked.motionZ);
                sendLce(entity);
                if (tracked.kind == TrackedEntityKind.ITEM && tracked.itemStack != null) {
                    sendTrackedItemMetadata(tracked);
                }
            }
        }
        tracked.spawnedToLce = true;
    }

    private void sendPendingTrackedEntities() {
        if (!canSpawnTrackedEntities()) {
            return;
        }
        for (TrackedEntity tracked : trackedEntities.values()) {
            if (!tracked.spawnedToLce) {
                spawnTrackedEntity(tracked);
            }
        }
    }

    private void sendTrackedItemMetadata(TrackedEntity tracked) {
        if (tracked == null || tracked.kind != TrackedEntityKind.ITEM || !tracked.spawnedToLce) {
            return;
        }
        SetEntityDataPacket metadata = new SetEntityDataPacket();
        metadata.entityId = tracked.entityId;
        metadata.values.add(new SetEntityDataPacket.DataValue(
            LCE_ITEM_ENTITY_DATA_SLOT,
            SetEntityDataPacket.TYPE_ITEMINSTANCE,
            tracked.itemStack
        ));
        sendLce(metadata);
    }

    private TeleportEntityPacket buildEntityTeleport(TrackedEntity tracked) {
        TeleportEntityPacket teleport = new TeleportEntityPacket();
        teleport.entityId = tracked.entityId;
        teleport.x = toLceFixed(tracked.x);
        teleport.y = toLceFixed(tracked.y);
        teleport.z = toLceFixed(tracked.z);
        teleport.yaw = angleToByte(tracked.yaw);
        teleport.pitch = angleToByte(tracked.pitch);
        return teleport;
    }

    private boolean isSupportedLceEntityId(int entityId) {
        return entityId >= 0 && entityId <= 0x7FFF;
    }

    private void resetRemotePlayerIndices() {
        freeRemotePlayerIndices.clear();
        for (int index = 5; index <= 255; index++) {
            freeRemotePlayerIndices.offer(index);
        }
        activeRemotePlayerCount.set(0);
    }

    private void releaseTrackedEntityResources(TrackedEntity tracked) {
        if (tracked == null) {
            return;
        }
        if (tracked.kind == TrackedEntityKind.PLAYER) {
            if (tracked.playerUuid != null && !tracked.spawnedToLce) {
                knownPlayerNames.remove(tracked.playerUuid);
            }
            if (tracked.playerIndex >= 5 && tracked.playerIndex <= 255) {
                freeRemotePlayerIndices.offer(tracked.playerIndex);
            }
            activeRemotePlayerCount.updateAndGet(count -> Math.max(0, count - 1));
        }
    }

    private int toLceFixed(double value) {
        return (int) Math.floor(value * 32.0d);
    }

    private int angleToByte(float angle) {
        return ((int) (angle * 256.0f / 360.0f)) & 0xFF;
    }

    private int velocityToLce(double velocity) {
        double clamped = Math.max(-3.9d, Math.min(3.9d, velocity));
        return (int) Math.round(clamped * 8000.0d);
    }

    private org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack extractJavaItemEntityStack(ClientboundSetEntityDataPacket p) {
        if (p == null || p.getMetadata() == null) {
            return null;
        }
        for (var metadata : p.getMetadata()) {
            if (metadata == null) {
                continue;
            }
            Object value = metadata.getValue();
            if (value instanceof org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack itemStack) {
                return itemStack;
            }
            if (metadata.getType() == MetadataTypes.ITEM_STACK && value == null) {
                return null;
            }
        }
        return null;
    }

    private int translateJavaPlayerInventorySlotToLce(int javaSlot) {
        if (javaSlot >= 0 && javaSlot <= 8) {
            return 36 + javaSlot;
        }
        if (javaSlot >= 9 && javaSlot <= 35) {
            return javaSlot;
        }
        return switch (javaSlot) {
            case 36 -> 8; // boots
            case 37 -> 7; // leggings
            case 38 -> 6; // chestplate
            case 39 -> 5; // helmet
            default -> -1;
        };
    }

    private int translateJavaPlayerContainerSlotToLce(int javaSlot) {
        if (javaSlot >= 9 && javaSlot <= 44) {
            return javaSlot;
        }
        return switch (javaSlot) {
            case 5 -> 5;
            case 6 -> 6;
            case 7 -> 7;
            case 8 -> 8;
            default -> -1;
        };
    }

    private int translateJavaPlayerInventorySlotToContainerSlot(int javaSlot) {
        if (javaSlot >= 0 && javaSlot <= 8) {
            return 36 + javaSlot;
        }
        if (javaSlot >= 9 && javaSlot <= 35) {
            return javaSlot;
        }
        return switch (javaSlot) {
            case 36 -> 8;
            case 37 -> 7;
            case 38 -> 6;
            case 39 -> 5;
            default -> -1;
        };
    }

    private Integer mapJavaMobType(EntityType type) {
        return switch (type) {
            case CREEPER -> 50;
            case SKELETON -> 51;
            case SPIDER -> 52;
            case ZOMBIE -> 54;
            case ZOMBIFIED_PIGLIN -> 57;
            case CAVE_SPIDER -> 59;
            case PIG -> 90;
            case SHEEP -> 91;
            case COW -> 92;
            case CHICKEN -> 93;
            case MOOSHROOM -> 96;
            case IRON_GOLEM -> 99;
            case VILLAGER -> 120;
            default -> null;
        };
    }

    private Integer mapJavaObjectType(EntityType type) {
        return switch (type) {
            case ITEM -> AddEntityPacket.ITEM;
            case ARROW, SPECTRAL_ARROW, TRIDENT -> AddEntityPacket.ARROW;
            case SNOWBALL -> AddEntityPacket.SNOWBALL;
            case EGG -> AddEntityPacket.EGG;
            case FIREBALL -> AddEntityPacket.FIREBALL;
            case SMALL_FIREBALL -> AddEntityPacket.SMALL_FIREBALL;
            case ENDER_PEARL -> AddEntityPacket.THROWN_ENDERPEARL;
            case WITHER_SKULL -> AddEntityPacket.WITHER_SKULL;
            case FALLING_BLOCK -> AddEntityPacket.FALLING;
            case ITEM_FRAME, GLOW_ITEM_FRAME -> AddEntityPacket.ITEM_FRAME;
            case EYE_OF_ENDER -> AddEntityPacket.EYEOFENDERSIGNAL;
            case SPLASH_POTION, LINGERING_POTION -> AddEntityPacket.THROWN_POTION;
            case EXPERIENCE_BOTTLE -> AddEntityPacket.THROWN_EXPBOTTLE;
            case FIREWORK_ROCKET -> AddEntityPacket.FIREWORKS;
            case LEASH_KNOT -> AddEntityPacket.LEASH_KNOT;
            case FISHING_BOBBER -> AddEntityPacket.FISH_HOOK;
            case TNT -> AddEntityPacket.PRIMED_TNT;
            case END_CRYSTAL -> AddEntityPacket.ENDER_CRYSTAL;
            default -> null;
        };
    }

    private int mapJavaObjectData(EntityType type,
                                  org.geysermc.mcprotocollib.protocol.data.game.entity.object.ObjectData javaData) {
        if (javaData == null) {
            return -1;
        }
        return switch (type) {
            case FALLING_BLOCK -> {
                if (javaData instanceof org.geysermc.mcprotocollib.protocol.data.game.entity.object.FallingBlockData data) {
                    int legacyState = data.getId();
                    int legacyMeta = data.getMetadata() & 0xF;
                    yield (legacyState << 4) | legacyMeta;
                }
                yield -1;
            }
            default -> {
                if (javaData instanceof org.geysermc.mcprotocollib.protocol.data.game.entity.object.GenericObjectData data) {
                    yield data.getValue();
                }
                if (javaData instanceof org.geysermc.mcprotocollib.protocol.data.game.entity.object.ProjectileData data) {
                    yield data.getOwnerId();
                }
                yield -1;
            }
        };
    }

    private org.geysermc.mcprotocollib.protocol.data.game.entity.object.Direction directionFromFaceIndex(int faceIndex) {
        var dirValues = org.geysermc.mcprotocollib.protocol.data.game.entity.object.Direction.values();
        int idx = Math.max(0, Math.min(faceIndex, dirValues.length - 1));
        org.geysermc.mcprotocollib.protocol.data.game.entity.object.Direction face = dirValues[idx];
        return face != null ? face : DEFAULT_DIG_FACE;
    }

    private boolean shouldPinDigTarget(PlayerActionPacket p, org.cloudburstmc.math.vector.Vector3i rawPos) {
        if (activeDigPos == null || (p.action != 1 && p.action != 2)) {
            return false;
        }
        if (isSentinelDigTarget(rawPos, p.face)) {
            return true;
        }
        return rawPos.getX() == activeDigPos.getX()
            && rawPos.getZ() == activeDigPos.getZ()
            && Math.abs(rawPos.getY() - activeDigPos.getY()) <= 1;
    }

    private boolean isSentinelDigTarget(org.cloudburstmc.math.vector.Vector3i pos, int faceIndex) {
        return (pos.getX() == 0 && pos.getY() == 0 && pos.getZ() == 0)
            || pos.getY() == 255
            || faceIndex == 255;
    }

    private org.geysermc.mcprotocollib.protocol.data.game.entity.object.Direction currentDigFace() {
        org.geysermc.mcprotocollib.protocol.data.game.entity.object.Direction face = activeDigFace;
        return face != null ? face : DEFAULT_DIG_FACE;
    }

    private void flushLatestPoseForAction() {
        if (!initialTeleportHandled.get()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now < suppressPositionUntilMs) {
            return;
        }
        if (lastMovePacketMs != 0L && (now - lastMovePacketMs) < ACTION_POSE_FLUSH_STALE_MS) {
            return;
        }
        javaSession.send(new ServerboundMovePlayerPosRotPacket(
            lastOnGround, false, lastKnownX, lastKnownY, lastKnownZ, lastKnownYaw, lastKnownPitch
        ));
        lastMovePacketMs = now;
    }

    private void ensureCarriedItemSynced() {
        if (lastSentCarriedSlot == currentCarriedSlot) {
            return;
        }
        javaSession.send(new ServerboundSetCarriedItemPacket(currentCarriedSlot));
        lastSentCarriedSlot = currentCarriedSlot;
    }

    private void clearActiveDigState() {
        activeDigPos = null;
        activeDigFace = DEFAULT_DIG_FACE;
    }

    private void rememberExpectedCorrectionEcho(double x, double y, double z, float yaw, float pitch) {
        expectedCorrectionEchoX = x;
        expectedCorrectionEchoY = y;
        expectedCorrectionEchoYView = y + 1.62d;
        expectedCorrectionEchoZ = z;
        expectedCorrectionEchoYaw = yaw;
        expectedCorrectionEchoPitch = pitch;
        expectedCorrectionEchoUntilMs = System.currentTimeMillis() + TELEPORT_SETTLE_MS;
        awaitingCorrectionEcho = true;
    }

    private boolean matchesExpectedCorrectionEcho(MovePlayerPacket p, long now) {
        if (!awaitingCorrectionEcho || now > expectedCorrectionEchoUntilMs) {
            return false;
        }
        if (p.id != 11 && p.id != 13) {
            return false;
        }
        boolean positionMatches = Math.abs(p.x - expectedCorrectionEchoX) <= 0.125d
            && Math.abs(p.y - expectedCorrectionEchoY) <= 0.125d
            && Math.abs(p.z - expectedCorrectionEchoZ) <= 0.125d
            && Math.abs(p.yView - expectedCorrectionEchoYView) <= 0.2d;
        if (!positionMatches) {
            return false;
        }
        if (p.id == 13) {
            return angularDistanceDegrees(p.yaw, expectedCorrectionEchoYaw) <= 6.0f
                && angularDistanceDegrees(p.pitch, expectedCorrectionEchoPitch) <= 6.0f;
        }
        return true;
    }

    private double translateLceYToJava(MovePlayerPacket p) {
        double rawY = p.y;

        // Most LCE movement packets already report the player's feet position in
        // Java coordinates. Blindly preferring rawY + 1 based on lastKnownY causes
        // the bridge to "learn" its own translated position and drift upward by a
        // full block over time. Keep the startup correction narrow: only apply a
        // +1 translation before post-chunk readiness, and only when the raw value
        // is almost exactly one block below the last server-confirmed Y.
        if (postChunkReady.get()) {
            return rawY;
        }

        boolean looksLikeStandardEyeOffset = Math.abs((p.yView - p.y) - 1.62d) <= 0.2d;
        if (!looksLikeStandardEyeOffset) {
            return rawY;
        }

        double shiftedY = rawY + 1.0d;
        double rawDistance = Math.abs(rawY - lastKnownY);
        double shiftedDistance = Math.abs(shiftedY - lastKnownY);
        boolean exactlyOneBlockBelowServerY = rawDistance >= 0.85d && rawDistance <= 1.15d;
        boolean shiftedMatchesServerY = shiftedDistance <= 0.15d;
        if (exactlyOneBlockBelowServerY && shiftedMatchesServerY) {
            if (config.logPackets) {
                log.debug(
                    "Translated startup LCE Y from {} to {} for Java movement (lastKnownY={}, yView={})",
                    rawY,
                    shiftedY,
                    lastKnownY,
                    p.yView
                );
            }
            return shiftedY;
        }
        return rawY;
    }

    private float angularDistanceDegrees(float a, float b) {
        float delta = Math.abs(a - b) % 360.0f;
        return delta > 180.0f ? 360.0f - delta : delta;
    }

    private void sendPlayerLoadedOnce(String reason) {
        if (!playerLoadedSent.compareAndSet(false, true)) {
            if (config.logPackets) {
                log.debug("Suppressing duplicate PlayerLoaded send: {}", reason);
            }
            return;
        }
        javaSession.send(ServerboundPlayerLoadedPacket.INSTANCE);
        log.info(reason);
    }

    private void onJavaDisconnected() {
        stopClientTickLoop();
        pendingInventoryCraft = null;
        spawnFinished.set(false);
        postChunkReady.set(false);
        tileUpdatesReadyAtMs = Long.MAX_VALUE;
        playerMoving.set(false);
        lastMovePacketMs = 0L;
        suppressPositionUntilMs = 0L;
        expectedCorrectionEchoUntilMs = 0L;
        awaitingCorrectionEcho = false;
        lastAcceptedTeleportMs = 0L;
        currentCarriedSlot = 0;
        lastSentCarriedSlot = -1;
        lastOnGround = true;
        initialTeleportHandled.set(false);
        postChunkSpawnSent.set(false);
        warnedMovementBeforePostChunkReady.set(false);
        teleportAcked.set(false);
        playerLoadedSent.set(false);
        javaChunkBatchFinished.set(false);
        trackedEntitiesFallbackEnabled.set(false);
        trackedEntitiesFallbackScheduled.set(false);
        earlyPostChunkFallbackScheduled.set(false);
        chunkLoadStartMs = 0L;
        lastChunkNudgeMs = 0L;
        containerStateIds.clear();
        cachedContainerItems.clear();
        craftingContainerIds.clear();
        javaPlayerContainerSlots.clear();
        javaRecipeItemSets.clear();
        javaInventoryCraftEntries.clear();
        javaInventoryCraftRecipes.clear();
        activeCraftingContainerId = 0;
        cachedCursorItem = null;
        blockActionSequence = 0;
        clearActiveDigState();
        translatedChunkCache.clear();
        trackedEntities.clear();
        pendingTrackedItemStacks.clear();
        knownPlayerNames.clear();
        resetRemotePlayerIndices();
        sendLce(makeDisconnect(2));
        lceChannel.close();
    }

    private void onJavaRegistryData(ClientboundRegistryDataPacket p) {
        if (p.getRegistry() == null) {
            return;
        }
        String registryName = p.getRegistry().asString();
        if ("minecraft:block".equals(registryName)) {
            log.info("Registry packet '{}' received with {} entries", registryName, p.getEntries() == null ? 0 : p.getEntries().size());
        }
        if (!"minecraft:block".equals(registryName)) {
            return;
        }

        int applied = 0;
        int seenStates = 0;
        int entriesSeen = 0;
        for (var entry : p.getEntries()) {
            entriesSeen++;
            if (entry == null || entry.getData() == null || entry.getId() == null) continue;

            String blockName = entry.getId().asString();
            NbtMap blockData = entry.getData();
            List<NbtMap> states = blockData.getList("states", NbtType.COMPOUND);
            if (states.isEmpty()) {
                if (entriesSeen <= 3) {
                    log.warn("Block registry entry '{}' had no states list. Keys={}", blockName, blockData.keySet());
                }
                continue;
            }

            for (NbtMap stateMap : states) {
                Integer stateId = asInt(stateMap.get("id"));
                if (stateId == null) continue;
                seenStates++;

                Map<String, String> props = new HashMap<>();
                NbtMap propMap = stateMap.getCompound("properties");
                for (Map.Entry<String, Object> pe : propMap.entrySet()) {
                    if (pe.getKey() == null || pe.getValue() == null) continue;
                    props.put(pe.getKey(), nbtValueToString(pe.getValue()));
                }

                if (MappingRegistry.blocks().registerRuntimeBlockState(stateId, blockName, props)) {
                    applied++;
                }
            }
        }

        log.info("Registry '{}' entries={} states={} mapped={}", registryName, entriesSeen, seenStates, applied);
    }

    private static Integer asInt(Object v) {
        if (v instanceof Number n) return n.intValue();
        if (v == null) return null;
        try {
            return Integer.parseInt(String.valueOf(v));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String nbtValueToString(Object value) {
        if (value instanceof String s) return s;
        if (value instanceof Byte b && (b == 0 || b == 1)) {
            return b != 0 ? "true" : "false";
        }
        return String.valueOf(value);
    }

    private void startClientTickLoop() {
        stopClientTickLoop();
        delimiterSeen.set(false);
        lastDelimiterMs = 0L;
        lastClientTickEndSentMs = 0L;
        tickEndScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "LceBridgeSession-TickEnd");
            t.setDaemon(true);
            return t;
        });
        // Adaptive fallback: if delimiters are absent/stalled, send tick-end at 20 TPS.
        // Once delimiters are flowing, onJavaDelimiter sends authoritative tick-end packets.
        tickEndScheduler.scheduleAtFixedRate(() -> {
            try {
                if (!javaSession.isConnected()) return;
                long now = System.currentTimeMillis();
                if (!delimiterSeen.get() || (now - lastDelimiterMs) > 250L) {
                    sendClientTickEndIfDue();
                }
            } catch (Exception e) {
                log.error("Tick-end loop error", e);
            }
        }, 50, 50, TimeUnit.MILLISECONDS);
        log.info("Tick synchronisation: delimiter-driven with adaptive fallback");
    }

    private void stopClientTickLoop() {
        stopPositionHeartbeat();
        stopChunkSendLoop();
        if (tickEndScheduler != null) {
            tickEndScheduler.shutdownNow();
            tickEndScheduler = null;
        }
        delimiterSeen.set(false);
        lastDelimiterMs = 0L;
        lastClientTickEndSentMs = 0L;
    }

    private void sendClientTickEndIfDue() {
        long now = System.currentTimeMillis();
        if ((now - lastClientTickEndSentMs) < 40L) {
            return;
        }
        lastClientTickEndSentMs = now;
        javaSession.send(ServerboundClientTickEndPacket.INSTANCE);
    }

    private void startChunkSendLoop() {
        stopChunkSendLoop();
        playerLoadedSent.set(false);
        javaChunkBatchFinished.set(false);
        trackedEntitiesFallbackEnabled.set(false);
        trackedEntitiesFallbackScheduled.set(false);
        earlyPostChunkFallbackScheduled.set(false);
        firstChunkLogged.set(false);
        queuedChunkCount = 0;

        chunkSendScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "LceBridgeSession-ChunkSend");
            t.setDaemon(true);
            return t;
        });

        chunkSendScheduler.scheduleAtFixedRate(() -> {
            try {
                drainPendingChunks();

                // If the server announced chunk load but nothing arrives, nudge it again.
                long started = chunkLoadStartMs;
                if (!postChunkReady.get() && !playerLoadedSent.get() && started > 0L && queuedChunkCount == 0) {
                    long now = System.currentTimeMillis();
                    if (now - started >= 1500L && now - lastChunkNudgeMs >= 1000L) {
                        sendPlayerLoadedOnce("No chunks received yet; sent PlayerLoaded nudge");
                        lastChunkNudgeMs = now;
                    }
                }
            } catch (Exception e) {
                log.error("Chunk send loop error", e);
            }
        }, 0, 50, TimeUnit.MILLISECONDS);

        // Safety fallback: don't leave the LCE client on the connection screen forever.
        // If chunk batching stalls for any reason, force post-chunk spawn after a short grace period.
        chunkSendScheduler.schedule(() -> {
            if (!postChunkReady.get() && lceChannel.isActive()) {
                boolean sawChunkActivity = queuedChunkCount > 0 || firstChunkLogged.get();
                if (!sawChunkActivity) {
                    log.warn("Skipping post-chunk fallback because no chunks were received yet (queued={}, pending={}, batchFinished={})",
                        queuedChunkCount, pendingChunks.size(), javaChunkBatchFinished.get());
                    return;
                }
                log.warn("Forcing post-chunk spawn fallback (queued={}, pending={}, batchFinished={})",
                    queuedChunkCount, pendingChunks.size(), javaChunkBatchFinished.get());
                if (postChunkSpawnSent.compareAndSet(false, true)) {
                    sendPostChunkSpawn();
                }
            }
        }, 5, TimeUnit.SECONDS);

        log.info("Chunk send pacing enabled: {} chunks/tick", Math.max(1, config.chunksPerTick));
    }

    private void stopChunkSendLoop() {
        pendingChunks.clear();
        if (chunkSendScheduler != null) {
            chunkSendScheduler.shutdownNow();
            chunkSendScheduler = null;
        }
        earlyPostChunkFallbackScheduled.set(false);
    }

    private void drainPendingChunks() {
        int perTick = Math.max(1, config.chunksPerTick);
        int sent = 0;

        while (sent < perTick) {
            ClientboundLevelChunkWithLightPacket chunk = pendingChunks.poll();
            if (chunk == null) break;

            dev.banditvault.lcebridge.core.chunk.ChunkTranslator.translate(chunk, this);
            sent++;
        }

        // Complete spawn only after the server signalled initial batch completion
        // and we've actually pushed at least one chunk to the LCE client.
        if (sent > 0 && javaChunkBatchFinished.get() && postChunkSpawnSent.compareAndSet(false, true)) {
            sendPostChunkSpawn();
        }
    }

    /**
     * Sends an idle status heartbeat whenever the LCE client has gone quiet.
     * Java still expects periodic onGround updates even while the player stands still.
     */
    private void startPositionHeartbeat() {
        // Stop any existing heartbeat so we restart with the new position.
        if (posHeartbeatScheduler != null && !posHeartbeatScheduler.isShutdown()) {
            posHeartbeatScheduler.shutdownNow();
            posHeartbeatScheduler = null;
        }
        posHeartbeatScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "LceBridgeSession-PosHeartbeat");
            t.setDaemon(true);
            return t;
        });
        posHeartbeatScheduler.scheduleAtFixedRate(() -> {
            try {
                if (!javaSession.isConnected()) {
                    log.warn("Heartbeat: Java session not connected, skipping tick");
                    return;
                }
                long idleMs = System.currentTimeMillis() - lastMovePacketMs;
                if (lastMovePacketMs != 0L && idleMs < 750L) {
                    return;
                }
                // Send full PosRot every ~5s to keep Java's internal position in sync.
                // Without this, the server's position can drift during idle mining, causing
                // reach-check failures on STOP_DIGGING and "moved wrongly" corrections.
                // In between, send StatusOnly to confirm onGround without wasting bandwidth.
                if (idleMs > 5000L || lastMovePacketMs == 0L) {
                    javaSession.send(new ServerboundMovePlayerPosRotPacket(
                        lastOnGround, false, lastKnownX, lastKnownY, lastKnownZ, lastKnownYaw, lastKnownPitch
                    ));
                    log.debug("Full pos heartbeat at ({},{},{}) onGround={}", lastKnownX, lastKnownY, lastKnownZ, lastOnGround);
                } else {
                    javaSession.send(new ServerboundMovePlayerStatusOnlyPacket(lastOnGround, false));
                    log.debug("Idle heartbeat sent at abs ({},{},{}) onGround={}", lastKnownX, lastKnownY, lastKnownZ, lastOnGround);
                }
            } catch (Exception e) {
                log.error("Position heartbeat lambda threw — task will be silently cancelled without this catch!", e);
            }
        }, 1, 1, TimeUnit.SECONDS);
        log.info("Position heartbeat started at abs ({},{},{})", lastKnownX, lastKnownY, lastKnownZ);
    }

    private void stopPositionHeartbeat() {
        if (posHeartbeatScheduler != null) {
            posHeartbeatScheduler.shutdownNow();
            posHeartbeatScheduler = null;
            log.info("Position heartbeat stopped");
        }
    }

    // ---- Spawn sequence (matches LCEServer Connection::SendSpawnSequence) ----
    private void sendSpawnSequence() {
        sendLce(buildLoginResponse());

        // 1. SetSpawnPosition
        SetSpawnPositionPacket sp = new SetSpawnPositionPacket();
        sp.x = spawnX; sp.y = spawnY; sp.z = spawnZ; sendLce(sp);

        // Mirror native server spawn order: selected hotbar slot before chunk stream.
        SetCarriedItemPacket carried = new SetCarriedItemPacket();
        carried.slot = 0;
        sendLce(carried);

        // Send a default time immediately; Java SetTime will overwrite once received.
        SetTimePacket initialTime = new SetTimePacket();
        initialTime.gameTime = 0L;
        initialTime.dayTime = 0L;
        sendLce(initialTime);

        // Native server sends STOP_RAINING during spawn sequence.
        GameEventPacket ge = new GameEventPacket();
        ge.reason = 2;
        ge.param = 0;
        sendLce(ge);

        // NOTE: Do NOT send PlayerAbilities here. The Java server sends this packet
        // after initial login, and we forward it in onJavaPlayerAbilities. Sending
        // a hardcoded copy here during spawn causes packet stream corruption.

        // 2. ChunkVisibilityArea — batch visibility window before chunks
        ChunkVisibilityAreaPacket cva = new ChunkVisibilityAreaPacket();
        int r = 16, cx = spawnX >> 4, cz = spawnZ >> 4;
        cva.minCX = cx - r; cva.maxCX = cx + r; cva.minCZ = cz - r; cva.maxCZ = cz + r;
        sendLce(cva);

        // Teleport and health are sent in onJavaPlayerPosition() once we have the
        // actual confirmed Java spawn coordinates from the first PlayerPosition packet.
        log.info("Pre-spawn sent for '{}' at ({},{},{}), awaiting Java teleport...", playerName, spawnX, spawnY, spawnZ);
    }

    /** Completes the spawn sequence after the first chunk batch has been delivered. */
    private void sendPostChunkSpawn() {
        // Teleport and health were already sent in sendSpawnSequence() so LCE spawns
        // immediately without waiting for chunks. Here we just flush any pending
        // SetTime that arrived from Java while chunks were loading, and log completion.
        postChunkReady.set(true);
        tileUpdatesReadyAtMs = System.currentTimeMillis() + TILE_UPDATE_GRACE_MS;
        SetTimePacket time = pendingSetTime;
        if (time != null) {
            sendLce(time);
            pendingSetTime = null;
        }

        // Forward any health update that arrived during chunk loading.
        SetHealthPacket health = pendingSetHealth;
        if (health != null) {
            sendLce(health);
            pendingSetHealth = null;
        }

        if (javaChunkBatchFinished.get()) {
            sendPendingTrackedEntities();
        } else {
            log.warn("Deferring tracked entity spawn until Java chunk batch finish is observed");
            scheduleTrackedEntityFallback();
        }
        log.info("Post-chunk spawn complete for '{}'", playerName);
    }

    private void scheduleEarlyPostChunkFallback() {
        if (chunkSendScheduler == null || chunkSendScheduler.isShutdown()) {
            return;
        }
        if (!earlyPostChunkFallbackScheduled.compareAndSet(false, true)) {
            return;
        }
        chunkSendScheduler.schedule(() -> {
            earlyPostChunkFallbackScheduled.set(false);
            if (!lceChannel.isActive() || !javaSession.isConnected()) {
                return;
            }
            if (postChunkReady.get() || javaChunkBatchFinished.get()) {
                return;
            }
            boolean sawChunkActivity = queuedChunkCount > 0 || firstChunkLogged.get() || !pendingChunks.isEmpty();
            if (!sawChunkActivity) {
                return;
            }
            log.warn(
                "Forcing early post-chunk spawn fallback after chunk activity (queued={}, pending={}, batchFinished={})",
                queuedChunkCount,
                pendingChunks.size(),
                javaChunkBatchFinished.get()
            );
            if (postChunkSpawnSent.compareAndSet(false, true)) {
                sendPostChunkSpawn();
            }
        }, EARLY_POST_CHUNK_FALLBACK_MS, TimeUnit.MILLISECONDS);
    }

    private boolean canSpawnTrackedEntities() {
        return postChunkReady.get() && (javaChunkBatchFinished.get() || trackedEntitiesFallbackEnabled.get());
    }

    private boolean canSendTileUpdates() {
        return config.liveTileUpdates
            && postChunkReady.get()
            && javaChunkBatchFinished.get()
            && System.currentTimeMillis() >= tileUpdatesReadyAtMs;
    }

    private void scheduleTrackedEntityFallback() {
        if (chunkSendScheduler == null || chunkSendScheduler.isShutdown()) {
            return;
        }
        if (!trackedEntitiesFallbackScheduled.compareAndSet(false, true)) {
            return;
        }
        chunkSendScheduler.schedule(this::enableTrackedEntityFallbackIfStable, TRACKED_ENTITY_FALLBACK_DELAY_MS, TimeUnit.MILLISECONDS);
    }

    private void enableTrackedEntityFallbackIfStable() {
        trackedEntitiesFallbackScheduled.set(false);
        if (!lceChannel.isActive() || !javaSession.isConnected()) {
            return;
        }
        if (!postChunkReady.get() || javaChunkBatchFinished.get() || trackedEntitiesFallbackEnabled.get()) {
            return;
        }

        long sinceTeleportMs = lastAcceptedTeleportMs == 0L
            ? Long.MAX_VALUE
            : System.currentTimeMillis() - lastAcceptedTeleportMs;
        if (sinceTeleportMs < TRACKED_ENTITY_FALLBACK_TELEPORT_SETTLE_MS) {
            long delayMs = Math.max(1000L, TRACKED_ENTITY_FALLBACK_TELEPORT_SETTLE_MS - sinceTeleportMs);
            if (chunkSendScheduler != null
                && !chunkSendScheduler.isShutdown()
                && trackedEntitiesFallbackScheduled.compareAndSet(false, true)) {
                log.info("Delaying tracked entity fallback; latest teleport was {} ms ago", sinceTeleportMs);
                chunkSendScheduler.schedule(this::enableTrackedEntityFallbackIfStable, delayMs, TimeUnit.MILLISECONDS);
            }
            return;
        }

        trackedEntitiesFallbackEnabled.set(true);
        log.warn("Enabling tracked entity spawn fallback without Java chunk batch finish");
        sendPendingTrackedEntities();
    }

    private void rememberRecentDigLog(org.cloudburstmc.math.vector.Vector3i pos) {
        recentDigLogPos = pos;
        recentDigLogUntilMs = System.currentTimeMillis() + 3000L;
    }

    private boolean shouldLogRecentDigUpdate(int x, int y, int z) {
        var pos = recentDigLogPos;
        if (pos == null) return false;
        if (System.currentTimeMillis() > recentDigLogUntilMs) return false;
        return pos.getX() == x && pos.getY() == y && pos.getZ() == z;
    }

    private String describeDigAction(int action) {
        return switch (action) {
            case 0 -> "START_DIGGING";
            case 1 -> "CANCEL_DIGGING";
            case 2 -> "FINISH_DIGGING";
            case 3 -> "DROP_ITEM_STACK";
            case 4 -> "DROP_ITEM";
            case 5 -> "RELEASE_USE_ITEM";
            default -> "UNKNOWN(" + action + ")";
        };
    }

    // MovePlayerPosRot (id=13) for teleport
    private LcePacket buildTeleport(double x, double y, double z, float yaw, float pitch) {
        return new RawLcePacket(13, buf -> {
            var w = new dev.banditvault.lcebridge.core.util.LceByteWriter(buf);
            w.writeByte(13);
            // Win64 LCE MovePlayerPosRot format:
            // [double x][double y][double yView][double z][float yRot][float xRot][byte flags]
            // Retail server teleports populate y=eyeY and yView=feetY.
            // flags: bit0=onGround, bit1=isFlying
            w.writeDouble(x);
            w.writeDouble(y + 1.62d);
            w.writeDouble(y);
            w.writeDouble(z);
            w.writeFloat(yaw);
            w.writeFloat(pitch);
            w.writeByte(0x01); // onGround=bit0 only; do not force isFlying in survival
        });
    }

    // ---- Packet builders ----------------------------------------------------
    private LcePacket buildPreLoginResponse() {
        return new RawLcePacket(2, buf -> {
            var w = new dev.banditvault.lcebridge.core.util.LceByteWriter(buf);
            int start = buf.writerIndex();
            w.writeByte(2);                    // [0] packet id
            w.writeShort(LCE_NET_VERSION);     // [1..2] netcodeVersion = 560
            w.writeUtf16("-");                 // [3..6] loginKey = "-" (1 wchar = short(1)+0x00 0x2D)
            w.writeByte(0);                    // [7] friendsOnlyBits
            w.writeInt(0);                     // [8..11] ugcPlayersVersion
            // Mirror a normal hosted-session shape: one host entry + hostIndex 0.
            // Some retail clients are less stable when this roster is empty.
            w.writeByte(1);                    // [12] playerCount = 1
            w.writeLong(BRIDGE_HOST_XUID);     // [13..20] host player XUID

            byte[] saveName = "lcebridgeworld".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            for (int i = 0; i < 14; i++) {
                w.writeByte(i < saveName.length ? (saveName[i] & 0xFF) : 0);
            }
            w.writeInt(0);                     // [27..30] serverSettings
            w.writeByte(0);                    // [31] hostIndex
            w.writeInt(0);                     // [32..35] texturePackId
            int end = buf.writerIndex();
            byte[] bytes = new byte[end - start];
            buf.getBytes(start, bytes);
            StringBuilder sb = new StringBuilder("PreLoginResponse bytes (").append(end-start).append("): ");
            for (byte b : bytes) sb.append(String.format("%02x ", b));
            log.info(sb.toString().trim());
        });
    }

    private LcePacket buildLoginResponse() {
        // S→C LoginPacket wire format verified against LoginPacket::read in LCE source.
        // Field order must exactly match read(): clientVersion, userName, levelType, seed,
        // gameType, dimension, mapHeight, maxPlayers, offlineXuid(8), onlineXuid(8),
        // friendsOnlyUGC, ugcPlayersVersion, difficulty, multiplayerInstanceId, playerIndex,
        // skinId, capeId, isGuest, newSeaLevel, gamePrivileges, xzSize(short), hellScale(byte)
        return new RawLcePacket(1, buf -> {
            var w = new dev.banditvault.lcebridge.core.util.LceByteWriter(buf);
            int start = buf.writerIndex();
            w.writeByte(1);              // [0] packet id
            w.writeInt(LCE_ENTITY_ID);   // [1..4] clientVersion (entity id)
            w.writeUtf16("");            // [5..6] userName (short 0 = empty)
            w.writeUtf16("flat");        // [7..16] levelTypeName (short 4 + 8 bytes)
            w.writeLong(0L);             // [17..24] seed
            w.writeInt(0);               // [25..28] gameType
            w.writeByte(0);              // [29] dimension
            w.writeByte(0);              // [30] mapHeight
            w.writeByte(20);             // [31] maxPlayers
            w.writeLong(offlineXuid);    // [32..39] offlineXuid
            w.writeLong(onlineXuid);     // [40..47] onlineXuid
            w.writeByte(0);              // [48] friendsOnlyUGC
            w.writeInt(0);               // [49..52] ugcPlayersVersion
            w.writeByte(1);              // [53] difficulty ← MUST be 0x01
            w.writeInt(0);               // [54..57] multiplayerInstanceId
            w.writeByte(LCE_SMALL_ID);   // [58] playerIndex
            w.writeInt(0);               // [59..62] skinId
            w.writeInt(0);               // [63..66] capeId
            w.writeByte(0);              // [67] isGuest
            w.writeByte(1);              // [68] newSeaLevel
            w.writeInt(0);               // [69..72] gamePrivileges
            w.writeShort(5120);          // [73..74] xzSize (Large world — 320 chunks)
            w.writeByte(8);              // [75] hellScale (8 for Large world)
            int end = buf.writerIndex();
            int len = end - start;
            // Dump the login packet bytes so we can verify the wire format
            byte[] bytes = new byte[len];
            buf.getBytes(start, bytes);
            StringBuilder sb = new StringBuilder("LoginResponse bytes (").append(len).append("): ");
            for (byte b : bytes) sb.append(String.format("%02x ", b));
            log.info(sb.toString().trim());
        });
    }

    private LcePacket buildRespawnPacket() {
        return new RawLcePacket(9, buf -> {
            var w = new dev.banditvault.lcebridge.core.util.LceByteWriter(buf);
            w.writeByte(9);              // Respawn packet id
            w.writeByte(0);              // dimension (overworld)
            w.writeByte(0);              // gameType (survival)
            w.writeShort(256);           // mapHeight
            w.writeUtf16("default");    // levelType
            w.writeLong(0L);             // seed (must match login seed)
            w.writeByte(1);              // difficulty
            w.writeByte(1);              // newSeaLevel = true
            w.writeShort(LCE_ENTITY_ID); // newEntityId
            w.writeShort(5120);           // xzSize (Large world)
            w.writeByte(8);              // hellScale (Large world)
        });
    }

    private static DisconnectPacket makeDisconnect(int reason) {
        DisconnectPacket p = new DisconnectPacket(); p.reason = reason; return p;
    }

    // ---- Helpers ------------------------------------------------------------
    public void sendLce(LcePacket pkt) {
        if (lceChannel.isActive()) lceChannel.writeAndFlush(pkt);
    }

    public String getPlayerName()  { return playerName; }
    public long   getOfflineXuid() { return offlineXuid; }
    public long   getOnlineXuid()  { return onlineXuid; }
}
