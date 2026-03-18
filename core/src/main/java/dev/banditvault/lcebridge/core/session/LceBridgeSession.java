package dev.banditvault.lcebridge.core.session;

import dev.banditvault.lcebridge.core.BridgeConfig;
import dev.banditvault.lcebridge.core.chunk.CachedLceChunkColumn;
import dev.banditvault.lcebridge.core.chunk.RleZlibCompressor;
import dev.banditvault.lcebridge.core.network.java.JavaSession;
import dev.banditvault.lcebridge.core.network.lce.*;
import dev.banditvault.lcebridge.core.registry.MappingRegistry;
import io.netty.channel.Channel;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtType;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.protocol.packet.common.clientbound.ClientboundDisconnectPacket;
import org.geysermc.mcprotocollib.protocol.packet.common.clientbound.ClientboundKeepAlivePacket;
import org.geysermc.mcprotocollib.protocol.packet.configuration.clientbound.ClientboundRegistryDataPacket;
import org.geysermc.mcprotocollib.protocol.data.game.ClientCommand;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.Hand;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.InteractAction;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.PlayerSpawnInfo;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ClickItemAction;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ContainerAction;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ContainerActionType;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.DropItemAction;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ShiftClickItemAction;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.*;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.*;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.*;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.*;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.*;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.inventory.*;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.*;
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
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class LceBridgeSession {
    private static final Logger log = LoggerFactory.getLogger(LceBridgeSession.class);
    private static final int LCE_NET_VERSION      = 560;
    private static final int LCE_PROTOCOL_VERSION = 78;
    private static final long BRIDGE_HOST_XUID    = 1L;
    private static final int LCE_SMALL_ID         = 4; // Remote clients start at XUSER_MAX_COUNT (4) in Win64 WinsockNetLayer
    private static final int LCE_ENTITY_ID        = (LCE_SMALL_ID * 100) + 1; // = 401
    private static final long TILE_UPDATE_GRACE_MS = 2000L;
    private static final org.geysermc.mcprotocollib.protocol.data.game.entity.object.Direction DEFAULT_DIG_FACE =
        org.geysermc.mcprotocollib.protocol.data.game.entity.object.Direction.DOWN;

    private final BridgeConfig  config;
    private final Channel       lceChannel;
    private final JavaSession   javaSession;
    private final AtomicBoolean loggedIn       = new AtomicBoolean(false);
    private final AtomicBoolean spawnFinished  = new AtomicBoolean(false);
    private final AtomicBoolean initialTeleportHandled = new AtomicBoolean(false);
    private final AtomicBoolean postChunkSpawnSent = new AtomicBoolean(false);
    private final AtomicBoolean postChunkReady = new AtomicBoolean(false);
    private final AtomicBoolean clientInformationSent = new AtomicBoolean(false);
    private final AtomicBoolean javaChunkBatchFinished = new AtomicBoolean(false);
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
    private volatile long lastMovePacketMs = 0L;
    private volatile long tileUpdatesReadyAtMs = Long.MAX_VALUE;
    private final AtomicBoolean delimiterSeen = new AtomicBoolean(false);
    private final AtomicBoolean playerMoving  = new AtomicBoolean(false);
    private volatile boolean lastOnGround = true;
    private volatile int blockActionSequence  = 0;
    private volatile Integer activeDigSequence = null;
    private volatile org.cloudburstmc.math.vector.Vector3i activeDigPos = null;
    private volatile org.geysermc.mcprotocollib.protocol.data.game.entity.object.Direction activeDigFace = DEFAULT_DIG_FACE;
    private volatile byte lastJavaAbilityFlags = 0;
    private volatile float lastJavaFlySpeed = 0.05f;
    private volatile float lastJavaWalkSpeed = 0.1f;
    private final Map<Integer, Integer> containerStateIds = new ConcurrentHashMap<>();
    private final Map<Long, CachedLceChunkColumn> translatedChunkCache = new ConcurrentHashMap<>();
    private volatile int currentLevelIdx = 0;

    private String playerName = "Unknown";
    private long offlineXuid  = 0L, onlineXuid = 0L;
    private int spawnX = 0, spawnY = 64, spawnZ = 0;

    public LceBridgeSession(BridgeConfig config, Channel lceChannel) {
        this.config     = config;
        this.lceChannel = lceChannel;
        this.javaSession = new JavaSession(config, "player");
        this.javaSession.setPacketHandler(this::handleJavaPacket);
        this.javaSession.setDisconnectHandler(this::onJavaDisconnected);
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
        javaSession.send(new ServerboundChatPacket(text, System.currentTimeMillis(), 0L, null, 0, new BitSet(), 0));
    }

    private void handleMove(MovePlayerPacket p) {
        if (!javaSession.isConnected()) return;
        // Ignore placeholder movement before Java has told us the real spawn position.
        if (!initialTeleportHandled.get()) return;
        boolean og = (p.flags & 0x1) != 0;
        lastOnGround = og;
        lastMovePacketMs = System.currentTimeMillis();
        boolean hasPosition = p.id == 11 || p.id == 13;
        boolean readyForPosition = postChunkReady.get();
        if (!readyForPosition && hasPosition) {
            if (p.id == 13) {
                javaSession.send(new ServerboundMovePlayerRotPacket(og, false, p.yaw, p.pitch));
            } else {
                javaSession.send(new ServerboundMovePlayerStatusOnlyPacket(og, false));
            }
            return;
        }
        boolean movedEnough = false;
        // Keep last-known pose current for heartbeat and relative teleports, even when
        // LCE sends position-only or rotation-only variants.
        if (hasPosition) {
            movedEnough = Math.abs(p.x - lastKnownX) > 0.01d
                || Math.abs(p.y - lastKnownY) > 0.01d
                || Math.abs(p.z - lastKnownZ) > 0.01d;
            lastKnownX = p.x;
            lastKnownY = p.y;
            lastKnownZ = p.z;
        }
        if (p.id == 12 || p.id == 13) {
            lastKnownYaw = p.yaw;
            lastKnownPitch = p.pitch;
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
            case 11 -> javaSession.send(new ServerboundMovePlayerPosPacket(og, false, p.x, p.y, p.z));
            case 12 -> javaSession.send(new ServerboundMovePlayerRotPacket(og, false, p.yaw, p.pitch));
            case 13 -> javaSession.send(new ServerboundMovePlayerPosRotPacket(og, false, p.x, p.y, p.z, p.yaw, p.pitch));
        }
    }

    private void handleSetCarriedItem(SetCarriedItemPacket p) {
        if (!javaSession.isConnected()) return;
        javaSession.send(new ServerboundSetCarriedItemPacket(p.slot));
    }

    private void handleUseItem(UseItemPacket p) {
        if (!javaSession.isConnected()) return;

        int sequence = ++blockActionSequence;
        if (p.face == 255 || p.x == -1 || p.y == 255 || p.z == -1) {
            javaSession.send(new ServerboundUseItemPacket(Hand.MAIN_HAND, sequence, lastKnownYaw, lastKnownPitch));
            return;
        }

        var pos = org.cloudburstmc.math.vector.Vector3i.from(p.x, p.y, p.z);
        var face = directionFromFaceIndex(p.face);
        javaSession.send(new ServerboundUseItemOnPacket(
            pos, face, Hand.MAIN_HAND, p.clickX, p.clickY, p.clickZ, false, false, sequence
        ));
    }

    private void handleInteract(InteractPacket p) {
        if (!javaSession.isConnected()) return;

        if (p.action == 0) {
            javaSession.send(new ServerboundInteractPacket(p.target, InteractAction.INTERACT, Hand.MAIN_HAND, false));
        } else if (p.action == 1) {
            javaSession.send(new ServerboundInteractPacket(p.target, InteractAction.ATTACK, false));
        }
    }

    private void handlePlayerAction(PlayerActionPacket p) {
        if (!javaSession.isConnected()) return;
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
        boolean blankDigPos = rawPos.getX() == 0 && rawPos.getY() == 0 && rawPos.getZ() == 0;

        // ABORT should stay tied to the original target. LCE can also emit a blank
        // STOP target; keep that tied to the active dig target too.
        boolean shouldPinToActiveTarget =
            activeDigPos != null && (p.action == 1 || (p.action == 2 && blankDigPos));
        if (shouldPinToActiveTarget) {
            pos = activeDigPos;
            dir = currentDigFace();
            if (!activeDigPos.equals(rawPos) || blankDigPos) {
                log.info("Pinning LCE dig action {} from ({}, {}, {}) to active target ({}, {}, {})",
                    p.action, rawPos.getX(), rawPos.getY(), rawPos.getZ(), pos.getX(), pos.getY(), pos.getZ());
            }
        }

        int seq;
        if (p.action == 0) {
            seq = ++blockActionSequence;
            activeDigSequence = seq;
            activeDigPos = pos;
            activeDigFace = dir;
        } else if (p.action == 1 || p.action == 2) {
            if (activeDigSequence != null && activeDigPos != null && activeDigPos.equals(pos)) {
                seq = activeDigSequence;
            } else {
                seq = ++blockActionSequence;
            }
            // Keep the active dig target alive across ABORT packets; some LCE clients
            // follow with a later FINISH for the same block.
            if (p.action == 2) {
                activeDigSequence = null;
                activeDigPos = null;
                activeDigFace = DEFAULT_DIG_FACE;
            }
        } else {
            seq = ++blockActionSequence;
        }
        javaSession.send(new ServerboundPlayerActionPacket(action, pos, dir, seq));
    }

    private void handlePlayerCommand(PlayerCommandPacket p) {
        if (!javaSession.isConnected()) return;
        var state = switch (p.action) {
            case 1 -> null; // no matching serverbound command in this MCProtocolLib snapshot
            case 2 -> null;
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
        javaSession.send(new ServerboundContainerClosePacket(p.containerId));
    }

    private void handleContainerClick(ContainerClickPacket p) {
        if (!javaSession.isConnected()) return;

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

        javaSession.send(new ServerboundContainerClickPacket(
            p.containerId,
            containerStateIds.getOrDefault(p.containerId, 0),
            p.slotNum,
            actionType,
            action,
            null,
            new it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<>()
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

    private void handleLceDisconnect(DisconnectPacket p) {
        log.info("LCE client '{}' disconnected ({})", playerName, p.reason);
        stopClientTickLoop();
        javaSession.disconnect("Client disconnected");
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
            case ClientboundDelimiterPacket p               -> onJavaDelimiter(p);
            case ClientboundOpenScreenPacket p              -> onJavaOpenScreen(p);
            case ClientboundContainerSetContentPacket p     -> onJavaContainerSetContent(p);
            case ClientboundContainerSetSlotPacket p        -> onJavaContainerSetSlot(p);
            case ClientboundContainerSetDataPacket p        -> onJavaContainerSetData(p);
            case ClientboundContainerClosePacket p          -> onJavaContainerClose(p);
            case ClientboundSetPlayerInventoryPacket p      -> onJavaSetPlayerInventory(p);
            case ClientboundSetCursorItemPacket p           -> onJavaSetCursorItem(p);
            case ClientboundSystemChatPacket p              -> onJavaSystemChat(p);
            case ClientboundPlayerChatPacket p              -> onJavaPlayerChat(p);
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

    private void onJavaInGameLogin(ClientboundLoginPacket p) {
        log.info("Java in-game login entityId={}", p.getEntityId());
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
        javaSession.send(ServerboundClientTickEndPacket.INSTANCE);
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
                    javaSession.send(ServerboundPlayerLoadedPacket.INSTANCE);
                    javaSession.send(new ServerboundChunkBatchReceivedPacket(advertisedChunkRate()));
                    log.info("Sent PlayerLoaded + ChunkBatchReceived on LEVEL_CHUNKS_LOAD_START (teleport already acked)");
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
        }
    }

    private void onJavaBlockUpdate(ClientboundBlockUpdatePacket p) {
        var entry = p.getEntry();
        if (entry == null || entry.getPosition() == null) return;
        int x = entry.getPosition().getX();
        int y = entry.getPosition().getY();
        int z = entry.getPosition().getZ();
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
        ContainerOpenPacket open = translateOpenScreen(p);
        if (open == null) {
            log.info("Ignoring unsupported Java container type {}", p.getType());
            return;
        }
        containerStateIds.put(p.getContainerId(), 0);
        sendLce(open);
    }

    private void onJavaContainerSetContent(ClientboundContainerSetContentPacket p) {
        ContainerSetContentPacket content = new ContainerSetContentPacket();
        content.containerId = p.getContainerId();
        if (p.getItems() != null) {
            for (var item : p.getItems()) {
                content.items.add(MappingRegistry.items().toLce(item));
            }
        }
        containerStateIds.put(p.getContainerId(), p.getStateId());
        sendLce(content);
        sendCursorItem(p.getCarriedItem());
    }

    private void onJavaContainerSetSlot(ClientboundContainerSetSlotPacket p) {
        ContainerSetSlotPacket slot = new ContainerSetSlotPacket();
        slot.containerId = p.getContainerId();
        slot.slot = p.getSlot();
        slot.item = MappingRegistry.items().toLce(p.getItem());
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
        sendLce(close);
    }

    private void onJavaSetPlayerInventory(ClientboundSetPlayerInventoryPacket p) {
        ContainerSetSlotPacket slot = new ContainerSetSlotPacket();
        slot.containerId = 0;
        slot.slot = p.getSlot();
        slot.item = MappingRegistry.items().toLce(p.getContents());
        sendLce(slot);
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
        javaSession.send(new ServerboundChunkBatchReceivedPacket(advertisedChunkRate()));
        javaChunkBatchFinished.set(true);
        log.info("Java chunk batch finished: queued={}, pending={}", queuedChunkCount, pendingChunks.size());

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
            // First teleport from Java — now we have the real spawn position.
            // Mirror Java's actual abilities; do not force isFlying in survival.
            PlayerAbilitiesPacket abilities = new PlayerAbilitiesPacket();
            abilities.flags = lastJavaAbilityFlags;
            abilities.flySpeed = lastJavaFlySpeed;
            abilities.walkSpeed = lastJavaWalkSpeed;
            sendLce(abilities);

            // Send the LCE MovePlayerPosRot with the actual confirmed coordinates.
            sendLce(buildTeleport(lastKnownX, lastKnownY, lastKnownZ, lastKnownYaw, lastKnownPitch));

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
                javaSession.send(ServerboundPlayerLoadedPacket.INSTANCE);
                javaSession.send(new ServerboundChunkBatchReceivedPacket(advertisedChunkRate()));
                log.info("Deferred PlayerLoaded + ChunkBatchReceived sent after teleport ack");
            }
        }
    }

    private void onJavaRespawn(ClientboundRespawnPacket p) {
        // Match your server behavior: send LCE Respawn first, then Java will
        // naturally follow with health + teleport updates.
        updateLevelIdx(p.getCommonPlayerSpawnInfo());
        containerStateIds.clear();
        spawnFinished.set(false);
        postChunkReady.set(false);
        tileUpdatesReadyAtMs = Long.MAX_VALUE;
        initialTeleportHandled.set(false);
        postChunkSpawnSent.set(false);
        teleportAcked.set(false);
        playerMoving.set(false);
        lastMovePacketMs = 0L;
        lastOnGround = true;
        stopPositionHeartbeat();
        activeDigSequence = null;
        activeDigPos = null;
        activeDigFace = DEFAULT_DIG_FACE;
        translatedChunkCache.clear();
        sendLce(buildRespawnPacket());
        SetHealthPacket sh = new SetHealthPacket();
        sh.health = 20.0f;
        sh.food = 20;
        sh.saturation = 5.0f;
        sendLce(sh);
        lastHealth = 20.0f;
        log.info("Forwarded Java respawn to LCE (lceEntityId={})", LCE_ENTITY_ID);
    }

    private void onJavaSystemChat(ClientboundSystemChatPacket p) {
        if (!config.forwardChat) return;
        if (!spawnFinished.get()) return; // don't send chat before LCE client is in-world
        String plain = componentToPlain(p.getContent()).trim();
        if (plain.isEmpty()) return;
        ChatPacket lc = new ChatPacket();
        lc.setMessage(plain);
        sendLce(lc);
    }

    private void onJavaPlayerChat(ClientboundPlayerChatPacket p) {
        if (!config.forwardChat) return;
        if (!spawnFinished.get()) return; // don't send chat before LCE client is in-world
        ChatPacket lc = new ChatPacket();
        net.kyori.adventure.text.Component body = p.getUnsignedContent() != null
            ? p.getUnsignedContent() : net.kyori.adventure.text.Component.text(p.getContent());
        String name = componentToPlain(p.getName()).trim();
        String message = componentToPlain(body).trim();
        if (message.isEmpty()) return;
        if (name.isEmpty()) {
            lc.setMessage(message);
        } else {
            lc.setMessage("<" + name + "> " + message);
        }
        sendLce(lc);
    }

    private void sendCursorItem(org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack item) {
        ContainerSetSlotPacket cursor = new ContainerSetSlotPacket();
        cursor.containerId = -1;
        cursor.slot = -1;
        cursor.item = MappingRegistry.items().toLce(item);
        sendLce(cursor);
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
        open.customName = !title.isBlank();
        open.title = title;

        return switch (p.getType()) {
            case GENERIC_9X1 -> configureContainer(open, ContainerOpenPacket.CONTAINER, 9);
            case GENERIC_9X2 -> configureContainer(open, ContainerOpenPacket.CONTAINER, 18);
            case GENERIC_9X3 -> configureContainer(open, ContainerOpenPacket.CONTAINER, 27);
            case GENERIC_9X4 -> configureContainer(open, ContainerOpenPacket.CONTAINER, 36);
            case GENERIC_9X5 -> configureContainer(open, ContainerOpenPacket.CONTAINER, 45);
            case GENERIC_9X6, SHULKER_BOX -> configureContainer(open, ContainerOpenPacket.LARGE_CHEST, 54);
            case CRAFTING, GENERIC_3X3, CRAFTER_3x3 -> configureContainer(open, ContainerOpenPacket.WORKBENCH, 9);
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
        if (c instanceof net.kyori.adventure.text.TextComponent tc) {
            // Collect plain text from this node and its children recursively
            StringBuilder sb = new StringBuilder(tc.content());
            for (net.kyori.adventure.text.Component child : tc.children()) {
                sb.append(componentToPlain(child));
            }
            return sb.toString();
        }
        if (c instanceof net.kyori.adventure.text.TranslatableComponent tc) {
            StringBuilder sb = new StringBuilder();
            if (tc.fallback() != null && !tc.fallback().isBlank()) {
                sb.append(tc.fallback());
            } else {
                sb.append(tc.key());
            }
            for (net.kyori.adventure.text.TranslationArgument arg : tc.arguments()) {
                String plainArg = translationArgumentToPlain(arg);
                if (!plainArg.isBlank()) {
                    if (!sb.isEmpty()) sb.append(' ');
                    sb.append(plainArg);
                }
            }
            for (net.kyori.adventure.text.Component child : tc.children()) {
                sb.append(componentToPlain(child));
            }
            return sb.toString();
        }
        // Any other component type: use toString but it may be noisy
        return c.toString();
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

    private org.geysermc.mcprotocollib.protocol.data.game.entity.object.Direction directionFromFaceIndex(int faceIndex) {
        var dirValues = org.geysermc.mcprotocollib.protocol.data.game.entity.object.Direction.values();
        int idx = Math.max(0, Math.min(faceIndex, dirValues.length - 1));
        org.geysermc.mcprotocollib.protocol.data.game.entity.object.Direction face = dirValues[idx];
        return face != null ? face : DEFAULT_DIG_FACE;
    }

    private org.geysermc.mcprotocollib.protocol.data.game.entity.object.Direction currentDigFace() {
        org.geysermc.mcprotocollib.protocol.data.game.entity.object.Direction face = activeDigFace;
        return face != null ? face : DEFAULT_DIG_FACE;
    }

    private void onJavaDisconnected() {
        stopClientTickLoop();
        spawnFinished.set(false);
        postChunkReady.set(false);
        tileUpdatesReadyAtMs = Long.MAX_VALUE;
        playerMoving.set(false);
        lastMovePacketMs = 0L;
        lastOnGround = true;
        initialTeleportHandled.set(false);
        postChunkSpawnSent.set(false);
        teleportAcked.set(false);
        containerStateIds.clear();
        blockActionSequence = 0;
        activeDigSequence = null;
        activeDigPos = null;
        activeDigFace = DEFAULT_DIG_FACE;
        translatedChunkCache.clear();
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
                    javaSession.send(ServerboundClientTickEndPacket.INSTANCE);
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
    }

    private void startChunkSendLoop() {
        stopChunkSendLoop();
        javaChunkBatchFinished.set(false);
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
                if (!spawnFinished.get() && started > 0L && queuedChunkCount == 0) {
                    long now = System.currentTimeMillis();
                    if (now - started >= 1500L && now - lastChunkNudgeMs >= 1000L) {
                        javaSession.send(ServerboundPlayerLoadedPacket.INSTANCE);
                        lastChunkNudgeMs = now;
                        log.warn("No chunks received yet; re-sent PlayerLoaded nudge (ChunkBatchReceived withheld — only valid after batch finish)");
                    }
                }
            } catch (Exception e) {
                log.error("Chunk send loop error", e);
            }
        }, 0, 50, TimeUnit.MILLISECONDS);

        // Safety fallback: don't leave the LCE client on the connection screen forever.
        // If chunk batching stalls for any reason, force post-chunk spawn after a short grace period.
        chunkSendScheduler.schedule(() -> {
            if (!spawnFinished.get() && lceChannel.isActive()) {
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
                javaSession.send(new ServerboundMovePlayerStatusOnlyPacket(lastOnGround, false));
                log.debug("Idle heartbeat sent at abs ({},{},{}) onGround={}", lastKnownX, lastKnownY, lastKnownZ, lastOnGround);
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
        int r = 8, cx = spawnX >> 4, cz = spawnZ >> 4;
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

        log.info("Post-chunk spawn complete for '{}'", playerName);
    }

    private boolean canSendTileUpdates() {
        return config.liveTileUpdates
            && postChunkReady.get()
            && System.currentTimeMillis() >= tileUpdatesReadyAtMs;
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
            w.writeShort(864);           // [73..74] xzSize
            w.writeByte(3);              // [75] hellScale
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
            w.writeShort(864);           // xzSize
            w.writeByte(3);              // hellScale
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
