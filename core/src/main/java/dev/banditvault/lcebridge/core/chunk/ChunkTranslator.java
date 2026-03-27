package dev.banditvault.lcebridge.core.chunk;

import dev.banditvault.lcebridge.core.network.lce.*;
import dev.banditvault.lcebridge.core.registry.MappingRegistry;
import dev.banditvault.lcebridge.core.session.LceBridgeSession;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.geysermc.mcprotocollib.protocol.codec.MinecraftTypes;
import org.geysermc.mcprotocollib.protocol.data.game.chunk.ChunkSection;
import org.geysermc.mcprotocollib.protocol.data.game.chunk.DataPalette;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundLevelChunkWithLightPacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Translates Java ClientboundLevelChunkWithLightPacket → LCE chunk packets.
 *
 * Java 1.18+ stores chunk data as a raw byte array (network-encoded sections).
 * We wrap it in a ByteBuf and read ChunkSection[] using the MCProtocolLib codec helpers.
 */
public class ChunkTranslator {
    private static final Logger log = LoggerFactory.getLogger(ChunkTranslator.class);
    private static volatile boolean firstChunkMappingSampleLogged = false;

    /** Number of chunk sections in a Java 1.18+ world (-64 to 319 = 24 sections). */
    private static final int SECTION_COUNT = 24;

    public static void translate(ClientboundLevelChunkWithLightPacket javaChunk,
                                  LceBridgeSession session) {
        int cx = javaChunk.getX();
        int cz = javaChunk.getZ();
        try {
            LceChunkBuilder builder = new LceChunkBuilder();
            int sampledBlocks = 0;
            int fallbackBlocks = 0;
            int sampleUniqueStateCount = 0;
            java.util.Set<Integer> sampleStateIds = firstChunkMappingSampleLogged
                ? java.util.Collections.emptySet()
                : new java.util.LinkedHashSet<>();

            byte[] rawData = javaChunk.getChunkData();
            ByteBuf buf = Unpooled.wrappedBuffer(rawData);

            // Track whether we've sampled biomes yet (prefer surface-level section at Y=64)
            boolean biomeSampled = false;

            // Sections 0-3 = Y -64 to -1 (below LCE world), 4-19 = Y 0-255, 20-23 = Y 256-319
            for (int si = 0; si < SECTION_COUNT && buf.isReadable(); si++) {
                ChunkSection section = MinecraftTypes.readChunkSection(buf, 15, 3);
                if (section == null) continue;

                int baseY = (si - 4) * 16; // LCE Y base (negative for si < 4)

                // Sample biomes from a surface-level section. Java stores biomes at 4×4×4
                // resolution per section; LCE uses a flat 16×16 byte grid per column.
                // We sample biome at local y=0 of the chosen section and expand each 4×4
                // biome cell to fill the 16×16 grid. Prefer section at Y=64 (si=8).
                // LCE client only has Biome::biomes[] entries for IDs 0-22; anything
                // outside that range will null-deref crash in the client.
                if (!biomeSampled && baseY >= 0 && baseY <= 240) {
                    DataPalette biomes = section.getBiomeData();
                    if (biomes != null) {
                        for (int bx = 0; bx < 4; bx++) {
                            for (int bz = 0; bz < 4; bz++) {
                                int biomeId = biomes.get(bx, 0, bz);
                                int lceBiome = (biomeId >= 0 && biomeId <= 22) ? biomeId : 1;
                                for (int dx = 0; dx < 4; dx++) {
                                    for (int dz = 0; dz < 4; dz++) {
                                        builder.setBiome(bx * 4 + dx, bz * 4 + dz, lceBiome);
                                    }
                                }
                            }
                        }
                        biomeSampled = true;
                    }
                }

                if (baseY < 0 || baseY > 240) continue; // outside LCE 0-255

                DataPalette blocks = section.getBlockData();
                for (int ly = 0; ly < 16; ly++) {
                    int lceY = baseY + ly;
                    if (lceY > 255) break;
                    for (int lz = 0; lz < 16; lz++) {
                        for (int lx = 0; lx < 16; lx++) {
                            int stateId = blocks.get(lx, ly, lz);
                            int lceId   = MappingRegistry.blocks().getLceId(stateId);
                            int lceData = MappingRegistry.blocks().getLceData(stateId);
                            if (!firstChunkMappingSampleLogged && sampledBlocks < 4096) {
                                sampledBlocks++;
                                if (lceId == 1 && lceData == 0 && stateId != 1) {
                                    fallbackBlocks++;
                                }
                                sampleStateIds.add(stateId);
                            }
                            builder.setBlock(lx, lceY, lz, lceId, lceData);
                        }
                    }
                }
            }

            if (!firstChunkMappingSampleLogged) {
                sampleUniqueStateCount = sampleStateIds.size();
                firstChunkMappingSampleLogged = true;
                log.info("First chunk mapping sample: chunk=({},{}), sampledBlocks={}, fallbackBlocks={}, uniqueStateIds={}, firstStateIds={}",
                    cx, cz, sampledBlocks, fallbackBlocks, sampleUniqueStateCount, sampleStateIds);
            }

            // Full skylight
            for (int lx = 0; lx < 16; lx++)
                for (int lz = 0; lz < 16; lz++)
                    for (int ly = 0; ly < 256; ly++)
                        builder.setSkyLight(lx, ly, lz, 15);

            session.cacheTranslatedChunk(cx, cz, builder.snapshot(cx, cz));

            byte[] raw  = builder.buildRawData();
            byte[] comp = RleZlibCompressor.compress(raw);

            ChunkVisibilityPacket vis = new ChunkVisibilityPacket();
            vis.chunkX = cx; vis.chunkZ = cz; vis.visible = true;
            session.sendLce(vis);

            BlockRegionUpdatePacket bru = new BlockRegionUpdatePacket();
            bru.x = cx << 4;   // chunk x to world x
            bru.y = 0;
            bru.z = cz << 4;   // chunk z to world z
            bru.xs = 16;
            bru.ys = 256;      // full height column
            bru.zs = 16;
            bru.levelIdx = 0;  // overworld
            bru.isFullChunk = true;
            bru.compressedData = comp;
            log.debug("Translated chunk ({},{}) → {} bytes compressed", cx, cz, comp.length);
            session.sendLce(bru);

        } catch (Exception e) {
            log.warn("Chunk translation failed ({},{}): ", cx, cz, e);
            // Send an empty air chunk so the client doesn't hang
            sendAirChunk(cx, cz, session);
        }
    }

    private static void sendAirChunk(int cx, int cz, LceBridgeSession session) {
        try {
            byte[] raw  = new LceChunkBuilder().buildRawData(); // all air
            byte[] comp = RleZlibCompressor.compress(raw);
            ChunkVisibilityPacket vis = new ChunkVisibilityPacket();
            vis.chunkX = cx; vis.chunkZ = cz; vis.visible = true;
            session.sendLce(vis);
            BlockRegionUpdatePacket bru = new BlockRegionUpdatePacket();
            bru.x = cx << 4;
            bru.y = 0;
            bru.z = cz << 4;
            bru.xs = 16;
            bru.ys = 256;
            bru.zs = 16;
            bru.levelIdx = 0;
            bru.isFullChunk = true;
            bru.compressedData = comp;
            session.sendLce(bru);
        } catch (Exception ignored) {}
    }
}
