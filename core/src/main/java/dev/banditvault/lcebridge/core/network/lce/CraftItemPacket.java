package dev.banditvault.lcebridge.core.network.lce;

/**
 * LCE id=150 -> legacy recipe-book craft action.
 * Wire: [short uid][int recipe]
 */
public class CraftItemPacket implements LcePacket {
    public static final int ID = 150;

    public int uid;
    public int recipe;

    @Override
    public int getId() {
        return ID;
    }
}
