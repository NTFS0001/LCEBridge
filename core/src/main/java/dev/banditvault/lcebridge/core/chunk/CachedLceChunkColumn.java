package dev.banditvault.lcebridge.core.chunk;

/**
 * Mutable cached copy of an LCE full chunk column so single Java block updates can
 * be reflected by re-sending the whole chunk without using fragile tile updates.
 */
public final class CachedLceChunkColumn {

    private static final int BLOCKS = 16 * 256 * 16;
    private static final int NIBBLES = BLOCKS / 2;
    private static final int BIOMES = 16 * 16;

    private final int chunkX;
    private final int chunkZ;
    /** XZY-ordered arrays: index = x*256*16 + z*256 + y. */
    private final byte[] blockIds;
    private final byte[] blockData;
    private final byte[] skyLight;
    private final byte[] blockLight;
    private final byte[] biomes;

    CachedLceChunkColumn(int chunkX, int chunkZ, byte[] blockIds, byte[] blockData, byte[] skyLight, byte[] blockLight, byte[] biomes) {
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.blockIds = blockIds;
        this.blockData = blockData;
        this.skyLight = skyLight;
        this.blockLight = blockLight;
        this.biomes = biomes;
    }

    public int chunkX() {
        return chunkX;
    }

    public int chunkZ() {
        return chunkZ;
    }

    public void setBlock(int x, int y, int z, int id, int data) {
        if (x < 0 || x > 15 || z < 0 || z > 15 || y < 0 || y > 255) {
            return;
        }
        int idx = x * 256 * 16 + z * 256 + y;
        blockIds[idx] = (byte) id;
        blockData[idx] = (byte) data;
    }

    public byte[] buildRawData() {
        byte[] out = new byte[BLOCKS + NIBBLES + NIBBLES + NIBBLES + BIOMES];
        int pos = 0;

        for (int y = 0; y < 256; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    out[pos++] = blockIds[x * 256 * 16 + z * 256 + y];
                }
            }
        }

        pos = packHalfHeightNibbles(blockData, out, pos);
        pos = packHalfHeightNibbles(blockLight, out, pos);
        pos = packHalfHeightNibbles(skyLight, out, pos);
        System.arraycopy(biomes, 0, out, pos, BIOMES);
        return out;
    }

    private int packHalfHeightNibbles(byte[] src, byte[] dst, int dstPos) {
        dstPos = packHalf(src, dst, dstPos, 0);
        dstPos = packHalf(src, dst, dstPos, 128);
        return dstPos;
    }

    private int packHalf(byte[] src, byte[] dst, int dstPos, int yBase) {
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = yBase; y < yBase + 128; y += 2) {
                    int low = src[x * 256 * 16 + z * 256 + y] & 0x0F;
                    int high = src[x * 256 * 16 + z * 256 + (y + 1)] & 0x0F;
                    dst[dstPos++] = (byte) (low | (high << 4));
                }
            }
        }
        return dstPos;
    }
}
