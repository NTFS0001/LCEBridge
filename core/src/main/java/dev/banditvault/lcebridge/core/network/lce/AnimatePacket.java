package dev.banditvault.lcebridge.core.network.lce;

/**
 * ID 18 — Animate (C→S).
 * action: 1=SWING, 2=HURT, 3=WAKE_UP, 4=RESPAWN
 */
public class AnimatePacket implements LcePacket {
    public static final int ID = 18;
    public int entityId;
    public int action;

    @Override
    public int getId() {
        return ID;
    }
}
