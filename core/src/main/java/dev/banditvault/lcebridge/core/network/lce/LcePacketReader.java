package dev.banditvault.lcebridge.core.network.lce;

import dev.banditvault.lcebridge.core.util.LceByteReader;
import io.netty.buffer.ByteBuf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Decodes a raw payload ByteBuf into a typed LcePacket.
 * The first byte of the payload is always the packet ID.
 */
public class LcePacketReader {
    private static final Logger log = LoggerFactory.getLogger(LcePacketReader.class);

    public static LcePacket read(ByteBuf payload) {
        if (payload.readableBytes() < 1) {
            return null;
        }

        int id = payload.readUnsignedByte();
        LceByteReader r = new LceByteReader(payload);

        return switch (id) {
            case KeepAlivePacket.ID -> readKeepAlive(r);
            case LoginPacket.ID -> readLogin(r);
            case PreLoginPacket.ID -> readPreLogin(r);
            case ChatPacket.ID -> readChat(r);
            case InteractPacket.ID -> readInteract(r);
            case AnimatePacket.ID -> readAnimate(r);
            case RespawnRequestPacket.ID -> readRespawnRequest(r);
            case ClientCommandPacket.ID -> readClientCommand(r);
            case 10, 11, 12, 13 -> readMovePlayer(id, r);
            case PlayerActionPacket.ID -> readPlayerAction(r);
            case UseItemPacket.ID -> readUseItem(r);
            case PlayerCommandPacket.ID -> readPlayerCommand(r);
            case SetCarriedItemPacket.ID -> readSetCarriedItem(r);
            case PlayerAbilitiesPacket.ID -> readPlayerAbilities(r);
            case DebugOptionsPacket.ID -> readDebugOptions(r);
            case ContainerClosePacket.ID -> readContainerClose(r);
            case ContainerClickPacket.ID -> readContainerClick(r);
            case ContainerButtonClickPacket.ID -> readContainerButtonClick(r);
            case DisconnectPacket.ID -> readDisconnect(r);
            case 150 -> {
                /* CraftItemPacket - not translated yet. */
                yield null;
            }
            default -> {
                log.info("Unhandled LCE packet id={} ({} bytes remaining)", id, payload.readableBytes());
                yield null;
            }
        };
    }

    private static KeepAlivePacket readKeepAlive(LceByteReader r) {
        KeepAlivePacket p = new KeepAlivePacket();
        p.keepAliveId = r.readInt();
        return p;
    }

    private static PreLoginPacket readPreLogin(LceByteReader r) {
        PreLoginPacket p = new PreLoginPacket();
        p.netVersion = r.readShort();
        p.playerName = r.readUtf16(32);
        p.offlineXuid = r.readByte();
        r.readInt();
        int playerCount = r.readByte() & 0xFF;
        for (int i = 0; i < playerCount; i++) {
            long offline = r.readLong();
            long online = r.readLong();
            if (i == 0) {
                p.offlineXuid = offline;
                p.onlineXuid = online;
            }
        }
        for (int i = 0; i < 14; i++) {
            r.readByte();
        }
        r.readInt();
        r.readByte();
        r.readInt();
        return p;
    }

    private static LoginPacket readLogin(LceByteReader r) {
        LoginPacket p = new LoginPacket();
        p.protocolVersion = r.readInt();
        p.username = r.readUtf16(16);
        p.mapSeed = r.readLong();
        p.gameType = r.readByte();
        p.worldName = r.readUtf16(64);
        p.dimension = r.readByte();
        p.difficulty = r.readByte();
        p.maxPlayers = r.readByte();
        p.worldWidth = r.readShort();
        p.worldLength = r.readShort();
        return p;
    }

    private static ChatPacket readChat(LceByteReader r) {
        ChatPacket p = new ChatPacket();
        p.messageType = r.readShort();
        short packed = r.readShort();
        int stringCount = (packed >> 4) & 0xF;
        int intCount = packed & 0xF;
        for (int i = 0; i < stringCount; i++) {
            p.stringArgs.add(r.readUtf16(119));
        }
        for (int i = 0; i < intCount; i++) {
            p.intArgs.add(r.readInt());
        }
        return p;
    }

    private static InteractPacket readInteract(LceByteReader r) {
        InteractPacket p = new InteractPacket();
        p.source = r.readInt();
        p.target = r.readInt();
        p.action = r.readUnsignedByte();
        return p;
    }

    private static ClientCommandPacket readClientCommand(LceByteReader r) {
        ClientCommandPacket p = new ClientCommandPacket();
        p.action = r.readByte() & 0xFF;
        return p;
    }

    private static RespawnRequestPacket readRespawnRequest(LceByteReader r) {
        return new RespawnRequestPacket();
    }

    private static AnimatePacket readAnimate(LceByteReader r) {
        AnimatePacket p = new AnimatePacket();
        p.entityId = r.readInt();
        p.action = r.readByte() & 0xFF;
        return p;
    }

    private static MovePlayerPacket readMovePlayer(int id, LceByteReader r) {
        MovePlayerPacket p = new MovePlayerPacket(id);
        if (id == 11 || id == 13) {
            p.x = r.readDouble();
            p.y = r.readDouble();
            p.yView = r.readDouble();
            p.z = r.readDouble();
        }
        if (id == 12 || id == 13) {
            p.yaw = r.readFloat();
            p.pitch = r.readFloat();
        }
        p.flags = r.readByte();
        return p;
    }

    private static SetCarriedItemPacket readSetCarriedItem(LceByteReader r) {
        SetCarriedItemPacket p = new SetCarriedItemPacket();
        p.slot = r.readShort();
        return p;
    }

    private static PlayerAbilitiesPacket readPlayerAbilities(LceByteReader r) {
        PlayerAbilitiesPacket p = new PlayerAbilitiesPacket();
        p.flags = r.readByte();
        p.flySpeed = r.readFloat();
        p.walkSpeed = r.readFloat();
        return p;
    }

    private static DebugOptionsPacket readDebugOptions(LceByteReader r) {
        DebugOptionsPacket p = new DebugOptionsPacket();
        p.optionsMask = r.readInt();
        return p;
    }

    private static PlayerActionPacket readPlayerAction(LceByteReader r) {
        PlayerActionPacket p = new PlayerActionPacket();
        p.action = r.readByte() & 0xFF;
        p.x = r.readInt();
        p.y = r.readByte() & 0xFF;
        p.z = r.readInt();
        p.face = r.readByte() & 0xFF;
        return p;
    }

    private static UseItemPacket readUseItem(LceByteReader r) {
        UseItemPacket p = new UseItemPacket();
        p.x = r.readInt();
        p.y = r.readUnsignedByte();
        p.z = r.readInt();
        p.face = r.readUnsignedByte();
        p.item = LceItemCodec.readItem(r);
        p.clickX = r.readUnsignedByte() / 16.0f;
        p.clickY = r.readUnsignedByte() / 16.0f;
        p.clickZ = r.readUnsignedByte() / 16.0f;
        return p;
    }

    private static PlayerCommandPacket readPlayerCommand(LceByteReader r) {
        PlayerCommandPacket p = new PlayerCommandPacket();
        p.entityId = r.readInt();
        p.action = r.readByte() & 0xFF;
        p.data = r.readInt();
        return p;
    }

    private static ContainerClosePacket readContainerClose(LceByteReader r) {
        ContainerClosePacket p = new ContainerClosePacket();
        p.containerId = r.readByte();
        return p;
    }

    private static ContainerClickPacket readContainerClick(LceByteReader r) {
        ContainerClickPacket p = new ContainerClickPacket();
        p.containerId = r.readByte();
        p.slotNum = r.readShort();
        p.buttonNum = r.readUnsignedByte();
        p.uid = r.readShort();
        p.clickType = r.readUnsignedByte();
        p.item = LceItemCodec.readItem(r);
        return p;
    }

    private static ContainerButtonClickPacket readContainerButtonClick(LceByteReader r) {
        ContainerButtonClickPacket p = new ContainerButtonClickPacket();
        p.containerId = r.readByte();
        p.buttonId = r.readUnsignedByte();
        return p;
    }

    private static DisconnectPacket readDisconnect(LceByteReader r) {
        DisconnectPacket p = new DisconnectPacket();
        p.reason = r.readInt();
        return p;
    }
}
