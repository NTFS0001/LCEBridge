package dev.banditvault.lcebridge.core.chunk;

/**
 * Builds the raw full-chunk payload for BlockRegionUpdatePacket.
 *
 * Native LCE full-chunk payload layout is:
 *   [65536 block IDs in YZX order]
 *   [32768 block data nibbles in native XZY half-column order]
 *   [32768 block light nibbles in native XZY half-column order]
 *   [32768 sky light nibbles in native XZY half-column order]
 *   [256 biome bytes]
 *
 * Only the block ID plane is reordered for full chunks. Metadata and light
 * planes stay in the order produced by SparseDataStorage/SparseLightStorage:
 * lower 128 Y first, then upper 128 Y, iterating X/Z columns.
 */
public final class LceChunkBuilder {

    private static final int BLOCKS = 16 * 256 * 16;
    private static final int NIBBLES = BLOCKS / 2;
    private static final int BIOMES = 16 * 16;

    /** XZY-ordered arrays: index = x*256*16 + z*256 + y. */
    private final byte[] blockIds = new byte[BLOCKS];
    private final byte[] blockData = new byte[BLOCKS];
    private final byte[] skyLight = new byte[BLOCKS];
    private final byte[] blockLight = new byte[BLOCKS];
    private final byte[] biomes = new byte[BIOMES];

    public void setBlock(int x, int y, int z, int id, int data) {
        int idx = x * 256 * 16 + z * 256 + y;
        blockIds[idx] = (byte) id;
        blockData[idx] = (byte) data;
    }

    public void setSkyLight(int x, int y, int z, int level) {
        skyLight[x * 256 * 16 + z * 256 + y] = (byte) level;
    }

    public void setBlockLight(int x, int y, int z, int level) {
        blockLight[x * 256 * 16 + z * 256 + y] = (byte) level;
    }

    public void setBiome(int x, int z, int biome) {
        biomes[x * 16 + z] = (byte) biome;
    }

    public byte[] buildRawData() {
        byte[] out = new byte[BLOCKS + NIBBLES + NIBBLES + NIBBLES + BIOMES];
        int pos = 0;

        // Full chunks reorder only the block ID plane into YZX order.
        for (int y = 0; y < 256; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    out[pos++] = blockIds[x * 256 * 16 + z * 256 + y];
                }
            }
        }

        // Native plane order after IDs: metadata, block light, sky light, biomes.
        pos = packHalfHeightNibbles(blockData, out, pos);
        pos = packHalfHeightNibbles(blockLight, out, pos);
        pos = packHalfHeightNibbles(skyLight, out, pos);
        System.arraycopy(biomes, 0, out, pos, BIOMES);

        return out;
    }

    public CachedLceChunkColumn snapshot(int chunkX, int chunkZ) {
        return new CachedLceChunkColumn(
            chunkX,
            chunkZ,
            blockIds.clone(),
            blockData.clone(),
            skyLight.clone(),
            blockLight.clone(),
            biomes.clone()
        );
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
