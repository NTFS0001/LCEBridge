package dev.banditvault.lcebridge.core.registry;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.banditvault.lcebridge.core.network.lce.LceItemStack;
import org.geysermc.mcprotocollib.protocol.data.game.item.HashedStack;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Maps Java 1.21.11 protocol item IDs to legacy LCE item IDs and metadata.
 *
 * The lookup is assembled from local vanilla reports and the legacy source tree:
 * - java_item_protocol_ids.json: protocol item id -> Java item name
 * - java_block_default_state_ids.json: block item name -> default block state id
 * - legacy_item_name_to_id.json: legacy source item constant name -> numeric id
 */
public class ItemMappings {
    private static final Logger log = LoggerFactory.getLogger(ItemMappings.class);

    private record LegacyRef(String legacyName, int data, boolean lossy) {
        private LegacyRef(String legacyName, int data) {
            this(legacyName, data, false);
        }
    }

    private record MappedItem(int id, int data, boolean lossy) {
    }

    private final String[] protocolIdToName;
    private final Map<String, Integer> blockDefaultStateIds;
    private final Map<String, Integer> legacyItemIds;
    private final Map<Long, Integer> legacyExactToProtocolId;
    private final Map<Integer, Integer> legacyIdToProtocolId;

    private ItemMappings(String[] protocolIdToName,
                         Map<String, Integer> blockDefaultStateIds,
                         Map<String, Integer> legacyItemIds,
                         Map<Long, Integer> legacyExactToProtocolId,
                         Map<Integer, Integer> legacyIdToProtocolId) {
        this.protocolIdToName = protocolIdToName;
        this.blockDefaultStateIds = blockDefaultStateIds;
        this.legacyItemIds = legacyItemIds;
        this.legacyExactToProtocolId = legacyExactToProtocolId;
        this.legacyIdToProtocolId = legacyIdToProtocolId;
    }

    public static ItemMappings loadFromResource() {
        String[] protocolIdToName = loadItemProtocolNames();
        Map<String, Integer> blockDefaultStateIds = loadIntMap("/mappings/java_block_default_state_ids.json");
        Map<String, Integer> legacyItemIds = loadIntMap("/mappings/legacy_item_name_to_id.json");
        Map<Long, Integer> legacyExactToProtocolId = new HashMap<>();
        Map<Integer, Integer> legacyIdToProtocolId = new HashMap<>();
        seedReverseLookups(protocolIdToName, blockDefaultStateIds, legacyItemIds, legacyExactToProtocolId, legacyIdToProtocolId);
        log.info("Loaded item lookup tables: protocolIds={}, blockDefaults={}, legacyIds={}",
            protocolIdToName.length, blockDefaultStateIds.size(), legacyItemIds.size());
        return new ItemMappings(
            protocolIdToName,
            blockDefaultStateIds,
            legacyItemIds,
            legacyExactToProtocolId,
            legacyIdToProtocolId
        );
    }

    public LceItemStack toLce(ItemStack item) {
        if (item == null || item.getAmount() <= 0) {
            return null;
        }

        MappedItem mapped = mapProtocolId(item.getId());
        if (mapped == null) {
            return null;
        }
        return new LceItemStack(mapped.id, item.getAmount(), mapped.data);
    }

    public String javaName(int protocolItemId) {
        if (protocolItemId < 0 || protocolItemId >= protocolIdToName.length) {
            return null;
        }
        return protocolIdToName[protocolItemId];
    }

    public String javaName(LceItemStack item) {
        Integer protocolId = toJavaProtocolId(item);
        if (protocolId == null) {
            return null;
        }
        return javaName(protocolId);
    }

    public Integer toJavaProtocolId(LceItemStack item) {
        if (item == null || item.count <= 0) {
            return null;
        }

        Integer exact = legacyExactToProtocolId.get(packLegacyKey(item.id, item.damage));
        if (exact != null) {
            return exact;
        }
        return legacyIdToProtocolId.get(item.id);
    }

    public HashedStack toJavaHashed(LceItemStack item) {
        Integer protocolId = toJavaProtocolId(item);
        if (protocolId == null) {
            return null;
        }
        return new HashedStack(protocolId, item.count, Map.of(), Set.of());
    }

    public boolean isLikelyPlaceableBlock(LceItemStack item) {
        Integer protocolId = toJavaProtocolId(item);
        if (protocolId == null || protocolId < 0 || protocolId >= protocolIdToName.length) {
            return false;
        }
        String javaName = protocolIdToName[protocolId];
        return javaName != null && blockDefaultStateIds.containsKey(javaName);
    }

    private MappedItem mapProtocolId(int protocolItemId) {
        if (protocolItemId < 0 || protocolItemId >= protocolIdToName.length) {
            return null;
        }

        String javaName = protocolIdToName[protocolItemId];
        if (javaName == null || javaName.isBlank()) {
            return null;
        }

        String bareName = stripNamespace(javaName);

        LegacyRef direct = directOverride(bareName);
        MappedItem mapped = mapLegacyRef(direct);
        if (mapped != null) {
            return mapped;
        }

        Integer blockDefaultStateId = blockDefaultStateIds.get(javaName);
        if (blockDefaultStateId != null) {
            return new MappedItem(
                MappingRegistry.blocks().getLceId(blockDefaultStateId),
                MappingRegistry.blocks().getLceData(blockDefaultStateId),
                false
            );
        }

        Integer exactLegacyId = legacyItemIds.get(bareName);
        if (exactLegacyId != null) {
            return new MappedItem(exactLegacyId, 0, false);
        }

        String legacyName = heuristicLegacyName(bareName);
        if (legacyName != null) {
            Integer legacyId = legacyItemIds.get(legacyName);
            if (legacyId != null) {
                return new MappedItem(legacyId, 0, false);
            }
        }

        mapped = mapLegacyRef(representativeOverride(bareName));
        if (mapped != null) {
            return mapped;
        }

        Integer paperId = legacyItemIds.get("paper");
        if (paperId != null) {
            return new MappedItem(paperId, 0, true);
        }

        return null;
    }

    private static void seedReverseLookups(String[] protocolIdToName,
                                           Map<String, Integer> blockDefaultStateIds,
                                           Map<String, Integer> legacyItemIds,
                                           Map<Long, Integer> legacyExactToProtocolId,
                                           Map<Integer, Integer> legacyIdToProtocolId) {
        ItemMappings mappings = new ItemMappings(
            protocolIdToName,
            blockDefaultStateIds,
            legacyItemIds,
            Map.of(),
            Map.of()
        );

        for (int protocolId = 0; protocolId < protocolIdToName.length; protocolId++) {
            MappedItem mapped = mappings.mapProtocolId(protocolId);
            if (mapped == null) {
                continue;
            }
            if (mapped.lossy()) {
                continue;
            }
            legacyExactToProtocolId.putIfAbsent(packLegacyKey(mapped.id, mapped.data), protocolId);
            legacyIdToProtocolId.putIfAbsent(mapped.id, protocolId);
        }
    }

    private MappedItem mapLegacyRef(LegacyRef ref) {
        if (ref == null) {
            return null;
        }
        Integer legacyId = legacyItemIds.get(ref.legacyName());
        if (legacyId == null) {
            return null;
        }
        return new MappedItem(legacyId, ref.data(), ref.lossy());
    }

    private static long packLegacyKey(int id, int data) {
        return ((long) id << 32) | (data & 0xFFFFFFFFL);
    }

    private static LegacyRef directOverride(String bareName) {
        return switch (bareName) {
            case "charcoal" -> new LegacyRef("coal", 1);
            case "redstone" -> new LegacyRef("redStone", 0);
            case "wheat" -> new LegacyRef("wheat", 0);
            case "wheat_seeds" -> new LegacyRef("seeds_wheat", 0);
            case "carrot" -> new LegacyRef("carrots", 0);
            case "glowstone_dust" -> new LegacyRef("yellowDust", 0);
            case "lapis_lazuli" -> new LegacyRef("dye_powder", 4);
            case "ink_sac", "glow_ink_sac" -> new LegacyRef("dye_powder", 0);
            case "cocoa_beans" -> new LegacyRef("dye_powder", 3);
            case "porkchop" -> new LegacyRef("porkChop_raw", 0);
            case "cooked_porkchop" -> new LegacyRef("porkChop_cooked", 0);
            case "beef" -> new LegacyRef("beef_raw", 0);
            case "cooked_beef" -> new LegacyRef("beef_cooked", 0);
            case "chicken" -> new LegacyRef("chicken_raw", 0);
            case "cooked_chicken" -> new LegacyRef("chicken_cooked", 0);
            case "cod" -> new LegacyRef("fish_raw", 0);
            case "cooked_cod" -> new LegacyRef("fish_cooked", 0);
            case "salmon" -> new LegacyRef("fish_raw", 1);
            case "cooked_salmon" -> new LegacyRef("fish_cooked", 1);
            case "tropical_fish" -> new LegacyRef("fish_raw", 2);
            case "pufferfish" -> new LegacyRef("fish_raw", 3);
            case "mushroom_stew" -> new LegacyRef("mushroomStew", 0);
            case "golden_apple" -> new LegacyRef("apple_gold", 0);
            case "bucket" -> new LegacyRef("bucket_empty", 0);
            case "water_bucket" -> new LegacyRef("bucket_water", 0);
            case "lava_bucket" -> new LegacyRef("bucket_lava", 0);
            case "milk_bucket" -> new LegacyRef("bucket_milk", 0);
            case "snowball" -> new LegacyRef("snowBall", 0);
            case "oak_sign", "spruce_sign", "birch_sign", "jungle_sign", "acacia_sign", "dark_oak_sign",
                 "mangrove_sign", "cherry_sign", "pale_oak_sign", "bamboo_sign", "crimson_sign", "warped_sign",
                 "oak_hanging_sign", "spruce_hanging_sign", "birch_hanging_sign", "jungle_hanging_sign",
                 "acacia_hanging_sign", "dark_oak_hanging_sign", "mangrove_hanging_sign", "pale_oak_hanging_sign",
                 "cherry_hanging_sign", "bamboo_hanging_sign", "crimson_hanging_sign",
                 "warped_hanging_sign" -> new LegacyRef("sign", 0);
            case "oak_door", "spruce_door", "birch_door", "jungle_door", "acacia_door",
                 "dark_oak_door", "mangrove_door", "cherry_door", "pale_oak_door", "bamboo_door",
                 "crimson_door", "warped_door" -> new LegacyRef("door_wood", 0);
            case "iron_door" -> new LegacyRef("door_iron", 0);
            case "oak_boat", "spruce_boat", "birch_boat", "jungle_boat", "acacia_boat",
                 "dark_oak_boat", "mangrove_boat", "cherry_boat", "pale_oak_boat", "bamboo_raft",
                 "oak_chest_boat", "spruce_chest_boat", "birch_chest_boat", "jungle_chest_boat", "acacia_chest_boat",
                 "dark_oak_chest_boat", "mangrove_chest_boat", "cherry_chest_boat", "pale_oak_chest_boat",
                 "bamboo_chest_raft" -> new LegacyRef("boat", 0);
            case "golden_carrot" -> new LegacyRef("carrotGolden", 0);
            case "gold_nugget" -> new LegacyRef("goldNugget", 0);
            case "iron_nugget", "copper_nugget" -> new LegacyRef("goldNugget", 0, true);
            case "ender_pearl" -> new LegacyRef("enderPearl", 0);
            case "blaze_rod" -> new LegacyRef("blazeRod", 0);
            case "ghast_tear" -> new LegacyRef("ghastTear", 0);
            case "nether_wart" -> new LegacyRef("netherwart_seeds", 0);
            case "glass_bottle" -> new LegacyRef("glassBottle", 0);
            case "filled_map" -> new LegacyRef("map", 0);
            case "spider_eye" -> new LegacyRef("spiderEye", 0);
            case "fermented_spider_eye" -> new LegacyRef("fermentedSpiderEye", 0);
            case "blaze_powder" -> new LegacyRef("blazePowder", 0);
            case "magma_cream" -> new LegacyRef("magmaCream", 0);
            case "experience_bottle" -> new LegacyRef("expBottle", 0);
            case "fire_charge" -> new LegacyRef("fireball", 0);
            case "wind_charge" -> new LegacyRef("fireball", 0, true);
            case "item_frame" -> new LegacyRef("frame", 0);
            case "glow_item_frame" -> new LegacyRef("frame", 0, true);
            case "flower_pot" -> new LegacyRef("flowerPot", 0);
            case "baked_potato" -> new LegacyRef("potatoBaked", 0);
            case "poisonous_potato" -> new LegacyRef("potatoPoisonous", 0);
            case "carrot_on_a_stick" -> new LegacyRef("carrotOnAStick", 0);
            case "warped_fungus_on_a_stick" -> new LegacyRef("carrotOnAStick", 0, true);
            case "nether_star" -> new LegacyRef("netherStar", 0);
            case "pumpkin_pie" -> new LegacyRef("pumpkinPie", 0);
            case "firework_rocket" -> new LegacyRef("fireworks", 0);
            case "firework_star" -> new LegacyRef("fireworksCharge", 0);
            case "enchanted_book" -> new LegacyRef("enchantedBook", 0);
            case "nether_brick" -> new LegacyRef("netherbrick", 0);
            case "resin_brick" -> new LegacyRef("netherbrick", 0, true);
            case "quartz" -> new LegacyRef("netherQuartz", 0);
            case "prismarine_shard" -> new LegacyRef("netherQuartz", 0, true);
            case "prismarine_crystals" -> new LegacyRef("yellowDust", 0, true);
            case "chest_minecart" -> new LegacyRef("minecart_chest", 0);
            case "furnace_minecart" -> new LegacyRef("minecart_furnace", 0);
            case "tnt_minecart" -> new LegacyRef("minecart_tnt", 0);
            case "hopper_minecart" -> new LegacyRef("minecart_hopper", 0);
            case "lead" -> new LegacyRef("lead", 0);
            case "name_tag" -> new LegacyRef("nameTag", 0);
            case "sugar_cane" -> new LegacyRef("reeds", 0);
            case "clay_ball" -> new LegacyRef("clay", 0);
            case "melon_slice" -> new LegacyRef("melon", 0);
            case "pumpkin_seeds" -> new LegacyRef("seeds_pumpkin", 0);
            case "melon_seeds" -> new LegacyRef("seeds_melon", 0);
            case "ender_eye" -> new LegacyRef("eyeOfEnder", 0);
            case "glistering_melon_slice" -> new LegacyRef("speckledMelon", 0);
            case "fishing_rod" -> new LegacyRef("fishingRod", 0);
            case "flint_and_steel" -> new LegacyRef("flintAndSteel", 0);
            case "slime_ball" -> new LegacyRef("slimeBall", 0);
            case "writable_book" -> new LegacyRef("writingBook", 0);
            case "iron_horse_armor" -> new LegacyRef("horseArmorMetal", 0);
            case "golden_horse_armor" -> new LegacyRef("horseArmorGold", 0);
            case "diamond_horse_armor" -> new LegacyRef("horseArmorDiamond", 0);
            case "music_disc_13" -> new LegacyRef("record_01", 0);
            case "music_disc_cat" -> new LegacyRef("record_02", 0);
            case "music_disc_blocks" -> new LegacyRef("record_03", 0);
            case "music_disc_chirp" -> new LegacyRef("record_04", 0);
            case "music_disc_far" -> new LegacyRef("record_05", 0);
            case "music_disc_mall" -> new LegacyRef("record_06", 0);
            case "music_disc_mellohi" -> new LegacyRef("record_07", 0);
            case "music_disc_stal" -> new LegacyRef("record_09", 0);
            case "music_disc_strad" -> new LegacyRef("record_10", 0);
            case "music_disc_ward" -> new LegacyRef("record_11", 0);
            case "music_disc_11" -> new LegacyRef("record_12", 0);
            case "music_disc_wait" -> new LegacyRef("record_08", 0);
            case "black_dye" -> new LegacyRef("dye_powder", 0);
            case "red_dye" -> new LegacyRef("dye_powder", 1);
            case "green_dye" -> new LegacyRef("dye_powder", 2);
            case "brown_dye" -> new LegacyRef("dye_powder", 3);
            case "blue_dye" -> new LegacyRef("dye_powder", 4);
            case "purple_dye" -> new LegacyRef("dye_powder", 5);
            case "cyan_dye" -> new LegacyRef("dye_powder", 6);
            case "light_gray_dye" -> new LegacyRef("dye_powder", 7);
            case "gray_dye" -> new LegacyRef("dye_powder", 8);
            case "pink_dye" -> new LegacyRef("dye_powder", 9);
            case "lime_dye" -> new LegacyRef("dye_powder", 10);
            case "yellow_dye" -> new LegacyRef("dye_powder", 11);
            case "light_blue_dye" -> new LegacyRef("dye_powder", 12);
            case "magenta_dye" -> new LegacyRef("dye_powder", 13);
            case "orange_dye" -> new LegacyRef("dye_powder", 14);
            case "white_dye", "bone_meal" -> new LegacyRef("dye_powder", 15);
            default -> null;
        };
    }

    private LegacyRef representativeOverride(String bareName) {
        LegacyRef explicit = switch (bareName) {
            case "enchanted_golden_apple" -> new LegacyRef("apple_gold", 0, true);
            case "powder_snow_bucket", "pufferfish_bucket", "salmon_bucket", "cod_bucket",
                 "tropical_fish_bucket", "axolotl_bucket", "tadpole_bucket" -> new LegacyRef("bucket_water", 0, true);
            case "blue_egg", "brown_egg" -> new LegacyRef("egg", 0, true);
            case "recovery_compass", "spyglass" -> new LegacyRef("compass", 0, true);
            case "phantom_membrane" -> new LegacyRef("leather", 0, true);
            case "elytra" -> new LegacyRef("chestplate_diamond", 0, true);
            case "turtle_helmet" -> new LegacyRef("helmet_leather", 0, true);
            case "turtle_scute", "armadillo_scute" -> new LegacyRef("leather", 0, true);
            case "wolf_armor" -> new LegacyRef("chestplate_iron", 0, true);
            case "raw_iron" -> new LegacyRef("ironIngot", 0, true);
            case "raw_copper", "copper_ingot" -> new LegacyRef("ironIngot", 0, true);
            case "raw_gold" -> new LegacyRef("goldIngot", 0, true);
            case "netherite_ingot" -> new LegacyRef("diamond", 0, true);
            case "netherite_scrap", "amethyst_shard", "echo_shard" -> new LegacyRef("netherQuartz", 0, true);
            case "bundle" -> new LegacyRef("leather", 0, true);
            case "shield" -> new LegacyRef("door_wood", 0, true);
            case "crossbow" -> new LegacyRef("bow", 0, true);
            case "breeze_rod" -> new LegacyRef("blazeRod", 0, true);
            case "mace" -> new LegacyRef("sword_iron", 0, true);
            case "beetroot" -> new LegacyRef("carrots", 0, true);
            case "beetroot_seeds", "torchflower_seeds", "pitcher_pod" -> new LegacyRef("seeds_wheat", 0, true);
            case "beetroot_soup", "suspicious_stew" -> new LegacyRef("mushroomStew", 0, true);
            case "rabbit" -> new LegacyRef("chicken_raw", 0, true);
            case "cooked_rabbit" -> new LegacyRef("chicken_cooked", 0, true);
            case "rabbit_stew" -> new LegacyRef("mushroomStew", 0, true);
            case "rabbit_foot" -> new LegacyRef("feather", 0, true);
            case "rabbit_hide" -> new LegacyRef("leather", 0, true);
            case "armor_stand" -> new LegacyRef("stick", 0, true);
            case "copper_horse_armor" -> new LegacyRef("horseArmorMetal", 0, true);
            case "netherite_horse_armor" -> new LegacyRef("horseArmorDiamond", 0, true);
            case "leather_horse_armor" -> new LegacyRef("saddle", 0, true);
            case "mutton" -> new LegacyRef("porkChop_raw", 0, true);
            case "cooked_mutton" -> new LegacyRef("porkChop_cooked", 0, true);
            case "chorus_fruit" -> new LegacyRef("apple", 0, true);
            case "popped_chorus_fruit" -> new LegacyRef("netherQuartz", 0, true);
            case "dragon_breath", "splash_potion", "lingering_potion" -> new LegacyRef("potion", 0, true);
            case "spectral_arrow", "tipped_arrow" -> new LegacyRef("arrow", 0, true);
            case "totem_of_undying" -> new LegacyRef("apple_gold", 0, true);
            case "shulker_shell" -> new LegacyRef("slimeBall", 0, true);
            case "knowledge_book" -> new LegacyRef("book", 0, true);
            case "debug_stick" -> new LegacyRef("stick", 0, true);
            case "nautilus_shell" -> new LegacyRef("ghastTear", 0, true);
            case "heart_of_the_sea" -> new LegacyRef("diamond", 0, true);
            case "goat_horn" -> new LegacyRef("blazeRod", 0, true);
            case "ominous_bottle" -> new LegacyRef("potion", 0, true);
            case "trial_key", "ominous_trial_key" -> new LegacyRef("nameTag", 0, true);
            default -> null;
        };
        if (explicit != null) {
            return explicit;
        }

        if (bareName.startsWith("music_disc_")) {
            return new LegacyRef("record_08", 0, true);
        }
        if (bareName.endsWith("_bundle")) {
            return new LegacyRef("leather", 0, true);
        }
        if (bareName.endsWith("_harness")) {
            return new LegacyRef("saddle", 0, true);
        }
        if (bareName.endsWith("_smithing_template")) {
            return new LegacyRef("paper", 0, true);
        }
        if (bareName.endsWith("_pottery_sherd")) {
            return new LegacyRef("brick", 0, true);
        }
        if (bareName.endsWith("_nautilus_armor")) {
            return bareName.startsWith("diamond_") || bareName.startsWith("netherite_")
                ? new LegacyRef("horseArmorDiamond", 0, true)
                : bareName.startsWith("golden_")
                    ? new LegacyRef("horseArmorGold", 0, true)
                    : new LegacyRef("horseArmorMetal", 0, true);
        }

        String substituted = substituteModernBareName(bareName);
        if (substituted != null && !substituted.equals(bareName)) {
            LegacyRef aliasedDirect = directOverride(substituted);
            if (aliasedDirect != null) {
                return new LegacyRef(aliasedDirect.legacyName(), aliasedDirect.data(), true);
            }

            String exactLegacy = legacyExactOrHeuristic(substituted);
            if (exactLegacy != null) {
                return new LegacyRef(exactLegacy, 0, true);
            }
        }

        return null;
    }

    private static String heuristicLegacyName(String bareName) {
        if (bareName.endsWith("_spawn_egg")) {
            return "spawnEgg";
        }
        if (bareName.endsWith("_bed")) {
            return "bed";
        }
        if (bareName.endsWith("_boat")) {
            return "boat";
        }
        if (bareName.endsWith("_sign") || bareName.endsWith("_hanging_sign")) {
            return "sign";
        }
        if (bareName.endsWith("_door")) {
            return bareName.equals("iron_door") ? "door_iron" : "door_wood";
        }

        String tool = legacyToolName(bareName);
        if (tool != null) {
            return tool;
        }

        String armor = legacyArmorName(bareName);
        if (armor != null) {
            return armor;
        }

        return snakeToCamel(bareName);
    }

    private String legacyExactOrHeuristic(String bareName) {
        if (legacyItemIds.containsKey(bareName)) {
            return bareName;
        }
        String heuristic = heuristicLegacyName(bareName);
        if (heuristic != null && legacyItemIds.containsKey(heuristic)) {
            return heuristic;
        }
        return null;
    }

    private static String substituteModernBareName(String bareName) {
        if (bareName.startsWith("copper_")) {
            return "iron_" + bareName.substring("copper_".length());
        }
        if (bareName.startsWith("netherite_")) {
            return "diamond_" + bareName.substring("netherite_".length());
        }
        if (bareName.startsWith("pale_oak_")) {
            return "dark_oak_" + bareName.substring("pale_oak_".length());
        }
        if (bareName.startsWith("cherry_")) {
            return "birch_" + bareName.substring("cherry_".length());
        }
        if (bareName.startsWith("mangrove_")) {
            return "jungle_" + bareName.substring("mangrove_".length());
        }
        if (bareName.startsWith("bamboo_") && (bareName.endsWith("_boat") || bareName.endsWith("_raft"))) {
            return "oak_boat";
        }
        return switch (bareName) {
            case "crossbow" -> "bow";
            case "shield" -> "door_wood";
            default -> bareName;
        };
    }

    private static String legacyToolName(String bareName) {
        String[] split = bareName.split("_");
        if (split.length != 2) {
            return null;
        }

        String material = switch (split[0]) {
            case "wooden" -> "wood";
            case "stone" -> "stone";
            case "iron" -> "iron";
            case "diamond" -> "diamond";
            case "golden" -> "gold";
            default -> null;
        };
        if (material == null) {
            return null;
        }

        return switch (split[1]) {
            case "sword" -> "sword_" + material;
            case "shovel" -> "shovel_" + material;
            case "pickaxe" -> "pickAxe_" + material;
            case "axe" -> "hatchet_" + material;
            case "hoe" -> "hoe_" + material;
            default -> null;
        };
    }

    private static String legacyArmorName(String bareName) {
        String[] split = bareName.split("_");
        if (split.length != 2) {
            return null;
        }

        String material = switch (split[0]) {
            case "leather" -> "leather";
            case "chainmail" -> "chain";
            case "iron" -> "iron";
            case "diamond" -> "diamond";
            case "golden" -> "gold";
            default -> null;
        };
        if (material == null) {
            return null;
        }

        return switch (split[1]) {
            case "helmet" -> "helmet_" + material;
            case "chestplate" -> "chestplate_" + material;
            case "leggings" -> "leggings_" + material;
            case "boots" -> "boots_" + material;
            default -> null;
        };
    }

    private static String stripNamespace(String name) {
        int idx = name.indexOf(':');
        return idx >= 0 ? name.substring(idx + 1) : name;
    }

    private static String snakeToCamel(String value) {
        StringBuilder out = new StringBuilder();
        boolean upperNext = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '_') {
                upperNext = true;
                continue;
            }
            if (upperNext) {
                out.append(Character.toUpperCase(c));
                upperNext = false;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static String[] loadItemProtocolNames() {
        InputStream is = ItemMappings.class.getResourceAsStream("/mappings/java_item_protocol_ids.json");
        if (is == null) {
            log.warn("Item protocol lookup not found at mappings/java_item_protocol_ids.json");
            return new String[0];
        }

        try (Reader r = new InputStreamReader(is, StandardCharsets.UTF_8)) {
            JsonArray arr = JsonParser.parseReader(r).getAsJsonArray();
            String[] names = new String[arr.size()];
            for (int i = 0; i < arr.size(); i++) {
                JsonElement el = arr.get(i);
                if (el != null && !el.isJsonNull()) {
                    names[i] = el.getAsString();
                }
            }
            return names;
        } catch (Exception e) {
            log.warn("Failed to load item protocol lookup: {}", e.getMessage());
            return new String[0];
        }
    }

    private static Map<String, Integer> loadIntMap(String resourcePath) {
        InputStream is = ItemMappings.class.getResourceAsStream(resourcePath);
        if (is == null) {
            log.warn("Item lookup resource not found at {}", resourcePath);
            return Map.of();
        }

        try (Reader r = new InputStreamReader(is, StandardCharsets.UTF_8)) {
            JsonObject obj = JsonParser.parseReader(r).getAsJsonObject();
            Map<String, Integer> result = new HashMap<>();
            for (Map.Entry<String, JsonElement> entry : obj.entrySet()) {
                if (entry.getValue() == null || entry.getValue().isJsonNull()) {
                    continue;
                }
                result.put(entry.getKey(), entry.getValue().getAsInt());
            }
            return result;
        } catch (Exception e) {
            log.warn("Failed to load item lookup resource {}: {}", resourcePath, e.getMessage());
            return Map.of();
        }
    }
}
