package dev.banditvault.lcebridge.core.registry;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Maps Java Edition global block-state IDs to LCE numeric ID + metadata.
 *
 * Primary source:
 *   mappings/block_state_mappings.json
 *     Exact Java 1.21.11 stateId -> packed LCE id/meta table generated from the
 *     local vanilla server reports plus block_rules.json.
 *
 * Fallback source:
 *   mappings/block_rules.json
 *     Name/property rules still used for runtime registry overrides or when a
 *     state is missing from the precomputed table.
 *
 * Unknown states fall back to stone (1:0).
 */
public class BlockMappings {
    private static final Logger log = LoggerFactory.getLogger(BlockMappings.class);

    private static final int MAX_STATES = 262144;
    private static final int FALLBACK_LCE = (1 << 8);
    /**
     * LCE world tiles must correspond to real Tile::tiles[] entries on the client.
     * The legacy client constructs ids 1..160 and 170..173; 161..169 and 174+ are unsafe.
     */
    private static final int MAX_SAFE_LCE_BLOCK_ID = 173;
    private static final Set<Integer> warnedUnsafeLceIds = Collections.synchronizedSet(new HashSet<>());

    private final int[] stateToLce;
    private final List<MappingRule> rules;

    private static final class MappingRule {
        private final String blockName;
        private final Map<String, String> requiredProps;
        private final int packedLce;

        private MappingRule(String blockName, Map<String, String> requiredProps, int packedLce) {
            this.blockName = normalizeValue(blockName);
            Map<String, String> normalizedProps = new HashMap<>();
            for (Map.Entry<String, String> e : requiredProps.entrySet()) {
                if (e.getKey() == null || e.getValue() == null) {
                    continue;
                }
                normalizedProps.put(normalizeValue(e.getKey()), normalizeValue(e.getValue()));
            }
            this.requiredProps = normalizedProps;
            this.packedLce = packedLce;
        }

        private boolean matches(String javaBlockName, Map<String, String> props) {
            if (!blockName.equals(javaBlockName)) {
                return false;
            }
            for (Map.Entry<String, String> req : requiredProps.entrySet()) {
                String actual = props.get(req.getKey());
                if (actual == null || !actual.equals(req.getValue())) {
                    return false;
                }
            }
            return true;
        }
    }

    private BlockMappings(int[] stateToLce, List<MappingRule> rules) {
        this.stateToLce = stateToLce;
        this.rules = rules;
    }

    public int getLceId(int javaStateId) {
        int packed = safePackedForState(javaStateId);
        return (packed >> 8) & 0xFF;
    }

    public int getLceData(int javaStateId) {
        return safePackedForState(javaStateId) & 0xFF;
    }

    /**
     * Register a runtime Java global block state using block name + properties from the Java registry.
     * Returns true when a rule matched and this state now has a non-fallback mapping.
     */
    public boolean registerRuntimeBlockState(int javaStateId, String javaBlockName, Map<String, String> props) {
        if (javaStateId < 0 || javaStateId >= stateToLce.length || javaBlockName == null) {
            return false;
        }

        String normalizedBlock = normalizeValue(javaBlockName);
        Map<String, String> normalizedProps = new HashMap<>();
        if (props != null) {
            for (Map.Entry<String, String> e : props.entrySet()) {
                if (e.getKey() == null || e.getValue() == null) {
                    continue;
                }
                normalizedProps.put(normalizeValue(e.getKey()), normalizeValue(e.getValue()));
            }
        }

        for (MappingRule rule : rules) {
            if (rule.matches(normalizedBlock, normalizedProps)) {
                stateToLce[javaStateId] = sanitizePackedLce(rule.packedLce, "runtime block mapping");
                return true;
            }
        }
        return false;
    }

    public static BlockMappings loadFromResource() {
        int[] table = new int[MAX_STATES];
        for (int i = 0; i < MAX_STATES; i++) {
            table[i] = FALLBACK_LCE;
        }

        loadExactStateTable(table);
        List<MappingRule> rules = loadJsonRules();
        return new BlockMappings(table, rules);
    }

    private static void loadExactStateTable(int[] table) {
        InputStream is = BlockMappings.class.getResourceAsStream("/mappings/block_state_mappings.json");
        if (is == null) {
            log.warn("Exact state table not found at mappings/block_state_mappings.json");
            return;
        }

        int loaded = 0;
        int nonFallback = 0;
        try (Reader r = new InputStreamReader(is, StandardCharsets.UTF_8)) {
            JsonArray arr = JsonParser.parseReader(r).getAsJsonArray();
            int limit = Math.min(arr.size(), table.length);
            for (int i = 0; i < limit; i++) {
                JsonElement el = arr.get(i);
                if (el == null || el.isJsonNull()) {
                    continue;
                }
                int packed = el.getAsInt();
                table[i] = sanitizePackedLce(packed, "exact state table");
                loaded++;
                if (table[i] != FALLBACK_LCE) {
                    nonFallback++;
                }
            }
            log.info("Loaded exact Java block state table: states={}, mapped={}, fallback={}",
                loaded, nonFallback, Math.max(0, loaded - nonFallback));
        } catch (Exception e) {
            log.warn("Failed to load exact state table: {}", e.getMessage());
        }
    }

    private static List<MappingRule> loadJsonRules() {
        InputStream is = BlockMappings.class.getResourceAsStream("/mappings/block_rules.json");
        if (is == null) {
            log.warn("JSON block mapping rules not found at mappings/block_rules.json");
            return Collections.emptyList();
        }

        List<MappingRule> parsed = new ArrayList<>();
        try (Reader r = new InputStreamReader(is, StandardCharsets.UTF_8)) {
            JsonArray arr = JsonParser.parseReader(r).getAsJsonArray();
            for (JsonElement el : arr) {
                JsonObject o = el.getAsJsonObject();
                String blockName = o.get("blockName").getAsString();
                int lceId = o.get("lceId").getAsInt();
                int lceData = o.get("lceData").getAsInt();

                Map<String, String> required = new HashMap<>();
                JsonObject props = o.getAsJsonObject("requiredProps");
                if (props != null) {
                    for (Map.Entry<String, JsonElement> e : props.entrySet()) {
                        if (e.getKey() == null || e.getValue() == null || e.getValue().isJsonNull()) {
                            continue;
                        }
                        required.put(e.getKey(), e.getValue().getAsString());
                    }
                }

                int packed = sanitizePackedLce(((lceId & 0xFF) << 8) | (lceData & 0xFF), "JSON block rule");
                parsed.add(new MappingRule(blockName, required, packed));
            }
        } catch (Exception e) {
            log.warn("Failed to load JSON block mapping rules: {}", e.getMessage());
            return Collections.emptyList();
        }

        log.info("Loaded {} JSON block mapping rules", parsed.size());
        return parsed;
    }

    private static String normalizeValue(String value) {
        if (value == null) {
            return "";
        }
        String out = value.trim();
        if (out.length() >= 2 && out.startsWith("\"") && out.endsWith("\"")) {
            out = out.substring(1, out.length() - 1);
        }
        if (out.length() > 1) {
            char last = out.charAt(out.length() - 1);
            if ((last == 'b' || last == 's' || last == 'l' || last == 'f' || last == 'd')
                && Character.isDigit(out.charAt(out.length() - 2))) {
                out = out.substring(0, out.length() - 1);
            }
        }
        return out;
    }

    private int safePackedForState(int javaStateId) {
        if (javaStateId < 0 || javaStateId >= stateToLce.length) {
            return FALLBACK_LCE;
        }
        return sanitizePackedLce(stateToLce[javaStateId], "state lookup");
    }

    private static int sanitizePackedLce(int packed, String source) {
        int id = (packed >> 8) & 0xFF;
        if (isSafeLceBlockId(id)) {
            return packed;
        }
        warnUnsafeLceId(id, source);
        return FALLBACK_LCE;
    }

    private static boolean isSafeLceBlockId(int id) {
        if (id == 0) {
            return true;
        }
        if (id < 0 || id > MAX_SAFE_LCE_BLOCK_ID) {
            return false;
        }
        return id <= 160 || id >= 170;
    }

    private static void warnUnsafeLceId(int id, String source) {
        if (warnedUnsafeLceIds.add(id)) {
            log.warn("Unsafe LCE block id {} encountered from {}; falling back to stone (1:0)", id, source);
        }
    }
}
