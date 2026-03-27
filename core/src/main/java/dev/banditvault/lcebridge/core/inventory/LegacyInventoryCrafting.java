package dev.banditvault.lcebridge.core.inventory;

import dev.banditvault.lcebridge.core.network.lce.LceItemStack;
import dev.banditvault.lcebridge.core.registry.ItemMappings;
import net.kyori.adventure.key.Key;
import org.geysermc.mcprotocollib.protocol.data.game.recipe.display.RecipeDisplay;
import org.geysermc.mcprotocollib.protocol.data.game.recipe.display.RecipeDisplayEntry;
import org.geysermc.mcprotocollib.protocol.data.game.recipe.display.ShapedCraftingRecipeDisplay;
import org.geysermc.mcprotocollib.protocol.data.game.recipe.display.ShapelessCraftingRecipeDisplay;
import org.geysermc.mcprotocollib.protocol.data.game.recipe.display.slot.CompositeSlotDisplay;
import org.geysermc.mcprotocollib.protocol.data.game.recipe.display.slot.EmptySlotDisplay;
import org.geysermc.mcprotocollib.protocol.data.game.recipe.display.slot.ItemSlotDisplay;
import org.geysermc.mcprotocollib.protocol.data.game.recipe.display.slot.ItemStackSlotDisplay;
import org.geysermc.mcprotocollib.protocol.data.game.recipe.display.slot.SlotDisplay;
import org.geysermc.mcprotocollib.protocol.data.game.recipe.display.slot.TagSlotDisplay;
import org.geysermc.mcprotocollib.protocol.data.game.recipe.display.slot.WithRemainderSlotDisplay;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class LegacyInventoryCrafting {
    private static final String GROUP_PLANKS = group("planks");
    private static final String GROUP_WOOL = group("wool");
    private static final String GROUP_COALS = group("coals");
    private static final String GROUP_WOODEN_BUTTON = group("wooden_button");
    private static final String GROUP_WOODEN_PRESSURE_PLATE = group("wooden_pressure_plate");

    private static final String[] DYE_COLORS = {
        "black", "red", "green", "brown", "blue", "purple", "cyan", "light_gray",
        "gray", "pink", "lime", "yellow", "light_blue", "magenta", "orange", "white"
    };

    private static final String[] WOOL_ITEM_COLORS = {
        "white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
        "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black"
    };

    private static final Map<Integer, LegacyRecipe> LEGACY_RECIPES = buildCatalog();

    private LegacyInventoryCrafting() {
    }

    public static LegacyRecipe recipeForIndex(int legacyIndex) {
        return LEGACY_RECIPES.get(legacyIndex);
    }

    public static JavaRecipeCandidate fromRecipeDisplayEntry(RecipeDisplayEntry entry, ItemMappings itemMappings,
                                                             Map<Key, Set<String>> tagItemKeys) {
        RecipeDisplay display = entry.display();
        if (display instanceof ShapedCraftingRecipeDisplay shaped) {
            if (shaped.width() > 2 || shaped.height() > 2) {
                return null;
            }

            String resultKey = resultKey(shaped.result(), itemMappings);
            if (resultKey == null) {
                return null;
            }

            Integer resultCount = resultCount(shaped.result());
            if (resultCount == null) {
                return null;
            }

            List<Set<String>> ingredients = new ArrayList<>();
            for (SlotDisplay ingredient : shaped.ingredients()) {
                Set<String> keys = acceptedKeys(ingredient, itemMappings, tagItemKeys);
                if (keys == null) {
                    return null;
                }
                ingredients.add(keys);
            }
            return new JavaRecipeCandidate(entry.id(), resultKey, resultCount, true, shaped.width(), shaped.height(), ingredients);
        }

        if (display instanceof ShapelessCraftingRecipeDisplay shapeless) {
            if (shapeless.ingredients().size() > 4) {
                return null;
            }

            String resultKey = resultKey(shapeless.result(), itemMappings);
            if (resultKey == null) {
                return null;
            }

            Integer resultCount = resultCount(shapeless.result());
            if (resultCount == null) {
                return null;
            }

            List<Set<String>> ingredients = new ArrayList<>();
            for (SlotDisplay ingredient : shapeless.ingredients()) {
                Set<String> keys = acceptedKeys(ingredient, itemMappings, tagItemKeys);
                if (keys == null || keys.isEmpty()) {
                    return null;
                }
                ingredients.add(keys);
            }
            return new JavaRecipeCandidate(entry.id(), resultKey, resultCount, false, 0, 0, ingredients);
        }

        return null;
    }

    public static boolean matches(LegacyRecipe legacy, JavaRecipeCandidate candidate) {
        if (!legacy.resultKey().equals(candidate.resultKey()) || legacy.resultCount() != candidate.resultCount()) {
            return false;
        }

        if (matchesSingleIngredientRecipe(legacy, candidate)) {
            return true;
        }

        if (legacy.shaped() != candidate.shaped()) {
            return false;
        }

        if (legacy.shaped()) {
            if (legacy.width() != candidate.width() || legacy.height() != candidate.height()) {
                return false;
            }
            if (legacy.ingredients().size() != candidate.ingredients().size()) {
                return false;
            }
            for (int i = 0; i < legacy.ingredients().size(); i++) {
                String legacyKey = legacy.ingredients().get(i);
                Set<String> candidateKeys = candidate.ingredients().get(i);
                if (legacyKey == null) {
                    if (!candidateKeys.isEmpty()) {
                        return false;
                    }
                } else if (candidateKeys.isEmpty() || !candidateKeys.contains(legacyKey)) {
                    return false;
                }
            }
            return true;
        }

        if (legacy.ingredients().size() != candidate.ingredients().size()) {
            return false;
        }
        return matchIngredientOrderless(new ArrayList<>(legacy.ingredients()), candidate.ingredients(), 0, new boolean[candidate.ingredients().size()]);
    }

    private static boolean matchesSingleIngredientRecipe(LegacyRecipe legacy, JavaRecipeCandidate candidate) {
        List<String> legacyIngredients = nonEmptyLegacyIngredients(legacy.ingredients());
        List<Set<String>> candidateIngredients = nonEmptyCandidateIngredients(candidate.ingredients());
        if (legacyIngredients.size() != 1 || candidateIngredients.size() != 1) {
            return false;
        }

        if (legacy.width() > 1 || legacy.height() > 1) {
            return false;
        }
        if (candidate.shaped() && (candidate.width() > 1 || candidate.height() > 1)) {
            return false;
        }

        return candidateIngredients.get(0).contains(legacyIngredients.get(0));
    }

    public static boolean canCraft(JavaRecipeCandidate candidate, List<LceItemStack> visibleInventory, ItemMappings itemMappings) {
        if (visibleInventory == null || visibleInventory.isEmpty()) {
            return false;
        }

        List<InventoryStack> stacks = new ArrayList<>();
        for (LceItemStack item : visibleInventory) {
            if (item == null || item.count <= 0) {
                continue;
            }
            Set<String> keys = acceptedKeys(itemMappings.javaName(item));
            if (keys.isEmpty()) {
                continue;
            }
            stacks.add(new InventoryStack(keys, item.count));
        }

        if (stacks.isEmpty()) {
            return false;
        }

        List<Set<String>> ingredients = new ArrayList<>();
        for (Set<String> keys : candidate.ingredients()) {
            if (!keys.isEmpty()) {
                ingredients.add(keys);
            }
        }
        return canCraftIngredients(ingredients, stacks, 0);
    }

    public static Set<String> acceptedKeysForJavaName(String javaName) {
        return acceptedKeys(javaName);
    }

    public static Set<String> acceptedKeysForJavaNames(Iterable<String> javaNames) {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        if (javaNames == null) {
            return Set.of();
        }

        for (String javaName : javaNames) {
            keys.addAll(acceptedKeys(javaName));
        }
        return keys.isEmpty() ? Set.of() : Set.copyOf(keys);
    }

    private static boolean matchIngredientOrderless(List<String> legacyIngredients, List<Set<String>> candidateIngredients,
                                                    int ingredientIndex, boolean[] usedCandidates) {
        if (ingredientIndex >= legacyIngredients.size()) {
            return true;
        }

        String legacyKey = legacyIngredients.get(ingredientIndex);
        for (int i = 0; i < candidateIngredients.size(); i++) {
            if (usedCandidates[i]) {
                continue;
            }
            Set<String> candidateKeys = candidateIngredients.get(i);
            if (!candidateKeys.contains(legacyKey)) {
                continue;
            }
            usedCandidates[i] = true;
            if (matchIngredientOrderless(legacyIngredients, candidateIngredients, ingredientIndex + 1, usedCandidates)) {
                return true;
            }
            usedCandidates[i] = false;
        }
        return false;
    }

    private static boolean canCraftIngredients(List<Set<String>> ingredients, List<InventoryStack> stacks, int ingredientIndex) {
        if (ingredientIndex >= ingredients.size()) {
            return true;
        }

        Set<String> requiredKeys = ingredients.get(ingredientIndex);
        for (InventoryStack stack : stacks) {
            if (stack.remaining <= 0 || Collections.disjoint(requiredKeys, stack.keys)) {
                continue;
            }
            stack.remaining--;
            if (canCraftIngredients(ingredients, stacks, ingredientIndex + 1)) {
                return true;
            }
            stack.remaining++;
        }
        return false;
    }

    private static List<String> nonEmptyLegacyIngredients(List<String> ingredients) {
        List<String> filtered = new ArrayList<>();
        for (String ingredient : ingredients) {
            if (ingredient != null) {
                filtered.add(ingredient);
            }
        }
        return filtered;
    }

    private static List<Set<String>> nonEmptyCandidateIngredients(List<Set<String>> ingredients) {
        List<Set<String>> filtered = new ArrayList<>();
        for (Set<String> ingredient : ingredients) {
            if (ingredient != null && !ingredient.isEmpty()) {
                filtered.add(ingredient);
            }
        }
        return filtered;
    }

    private static String resultKey(SlotDisplay display, ItemMappings itemMappings) {
        String javaName = javaName(display, itemMappings);
        return javaName == null ? null : normalizeResultKey(javaName);
    }

    private static Integer resultCount(SlotDisplay display) {
        if (display instanceof ItemStackSlotDisplay itemStackDisplay && itemStackDisplay.itemStack() != null) {
            return itemStackDisplay.itemStack().getAmount();
        }
        if (display instanceof ItemSlotDisplay) {
            return 1;
        }
        return null;
    }

    private static String javaName(SlotDisplay display, ItemMappings itemMappings) {
        if (display instanceof ItemSlotDisplay itemDisplay) {
            return itemMappings.javaName(itemDisplay.item());
        }
        if (display instanceof ItemStackSlotDisplay itemStackDisplay && itemStackDisplay.itemStack() != null) {
            return itemMappings.javaName(itemStackDisplay.itemStack().getId());
        }
        if (display instanceof WithRemainderSlotDisplay withRemainder) {
            return javaName(withRemainder.input(), itemMappings);
        }
        return null;
    }

    private static Set<String> acceptedKeys(SlotDisplay display, ItemMappings itemMappings,
                                            Map<Key, Set<String>> tagItemKeys) {
        if (display instanceof EmptySlotDisplay) {
            return Set.of();
        }
        if (display instanceof ItemSlotDisplay itemDisplay) {
            return acceptedKeys(itemMappings.javaName(itemDisplay.item()));
        }
        if (display instanceof ItemStackSlotDisplay itemStackDisplay && itemStackDisplay.itemStack() != null) {
            return acceptedKeys(itemMappings.javaName(itemStackDisplay.itemStack().getId()));
        }
        if (display instanceof TagSlotDisplay tagDisplay) {
            return acceptedKeys(tagDisplay.tag(), tagItemKeys);
        }
        if (display instanceof WithRemainderSlotDisplay withRemainder) {
            return acceptedKeys(withRemainder.input(), itemMappings, tagItemKeys);
        }
        if (display instanceof CompositeSlotDisplay composite) {
            LinkedHashSet<String> keys = new LinkedHashSet<>();
            for (SlotDisplay child : composite.contents()) {
                Set<String> childKeys = acceptedKeys(child, itemMappings, tagItemKeys);
                if (childKeys == null) {
                    return null;
                }
                keys.addAll(childKeys);
            }
            return keys.isEmpty() ? null : Set.copyOf(keys);
        }
        return null;
    }

    private static Set<String> acceptedKeys(Key tag, Map<Key, Set<String>> tagItemKeys) {
        if (tag != null && tagItemKeys != null) {
            Set<String> dynamic = tagItemKeys.get(tag);
            if (dynamic != null && !dynamic.isEmpty()) {
                return dynamic;
            }
        }

        String value = tag == null ? null : tag.asString();
        if (value == null || value.isBlank()) {
            return null;
        }
        return switch (value) {
            case "minecraft:planks" -> Set.of(GROUP_PLANKS);
            case "minecraft:wool" -> Set.of(GROUP_WOOL);
            case "minecraft:coals" -> Set.of(GROUP_COALS);
            case "minecraft:wooden_buttons" -> Set.of(GROUP_WOODEN_BUTTON);
            case "minecraft:wooden_pressure_plates" -> Set.of(GROUP_WOODEN_PRESSURE_PLATE);
            default -> null;
        };
    }

    private static Set<String> acceptedKeys(String javaName) {
        if (javaName == null || javaName.isBlank()) {
            return Set.of();
        }

        LinkedHashSet<String> keys = new LinkedHashSet<>();
        keys.add(javaName);

        if (javaName.endsWith("_planks")) {
            keys.add(GROUP_PLANKS);
        }
        if (javaName.endsWith("_wool")) {
            keys.add(GROUP_WOOL);
        }
        if (javaName.equals("minecraft:coal") || javaName.equals("minecraft:charcoal")) {
            keys.add(GROUP_COALS);
        }
        if (isWoodenButton(javaName)) {
            keys.add(GROUP_WOODEN_BUTTON);
        }
        if (isWoodenPressurePlate(javaName)) {
            keys.add(GROUP_WOODEN_PRESSURE_PLATE);
        }

        return Set.copyOf(keys);
    }

    private static boolean isWoodenButton(String javaName) {
        return javaName.endsWith("_button") && !javaName.equals("minecraft:stone_button");
    }

    private static boolean isWoodenPressurePlate(String javaName) {
        return javaName.endsWith("_pressure_plate")
            && !javaName.equals("minecraft:stone_pressure_plate")
            && !javaName.equals("minecraft:heavy_weighted_pressure_plate")
            && !javaName.equals("minecraft:light_weighted_pressure_plate");
    }

    private static String normalizeResultKey(String javaName) {
        if (isWoodenButton(javaName)) {
            return GROUP_WOODEN_BUTTON;
        }
        if (isWoodenPressurePlate(javaName)) {
            return GROUP_WOODEN_PRESSURE_PLATE;
        }
        return javaName;
    }

    private static String group(String key) {
        return "group:" + key;
    }

    private static LegacyRecipe shaped(String resultKey, int resultCount, int width, int height, String... ingredients) {
        return new LegacyRecipe(-1, resultKey, resultCount, true, width, height, Arrays.asList(ingredients));
    }

    private static LegacyRecipe shapeless(String resultKey, int resultCount, String... ingredients) {
        return new LegacyRecipe(-1, resultKey, resultCount, false, 0, 0, Arrays.asList(ingredients));
    }

    private static void put(Map<Integer, LegacyRecipe> recipes, int legacyIndex, LegacyRecipe recipe) {
        recipes.put(legacyIndex, recipe.withLegacyIndex(legacyIndex));
    }

    private static Map<Integer, LegacyRecipe> buildCatalog() {
        Map<Integer, LegacyRecipe> recipes = new HashMap<>();
        int index = 0;

        put(recipes, index++, shaped("minecraft:oak_planks", 4, 1, 1, "minecraft:oak_log"));
        put(recipes, index++, shaped("minecraft:birch_planks", 4, 1, 1, "minecraft:birch_log"));
        put(recipes, index++, shaped("minecraft:spruce_planks", 4, 1, 1, "minecraft:spruce_log"));
        put(recipes, index++, shaped("minecraft:jungle_planks", 4, 1, 1, "minecraft:jungle_log"));
        put(recipes, index++, shaped("minecraft:stick", 4, 1, 2, GROUP_PLANKS, GROUP_PLANKS));

        index += 20;
        put(recipes, index++, shaped("minecraft:shears", 1, 2, 2,
            null, "minecraft:iron_ingot",
            "minecraft:iron_ingot", null));

        index = addFoodRecipes(recipes, index);
        index = addStructureRecipes(recipes, index);

        index += 23;
        index += 16;

        index = addClothDyeRecipes(recipes, index);
        index = addRecipesAfterCloth(recipes, index);

        index += 5;
        index = addRecipesAfterWeapon(recipes, index);
        index = addOreRecipes(recipes, index);
        addTailRecipes(recipes, index);

        return Map.copyOf(recipes);
    }

    private static int addFoodRecipes(Map<Integer, LegacyRecipe> recipes, int index) {
        index += 3;
        put(recipes, index++, shapeless("minecraft:mushroom_stew", 1,
            "minecraft:brown_mushroom", "minecraft:red_mushroom", "minecraft:bowl"));
        index += 2;
        put(recipes, index++, shaped("minecraft:melon_seeds", 1, 1, 1, "minecraft:melon_slice"));
        put(recipes, index++, shaped("minecraft:pumpkin_seeds", 4, 1, 1, "minecraft:pumpkin"));
        put(recipes, index++, shapeless("minecraft:pumpkin_pie", 1,
            "minecraft:pumpkin", "minecraft:sugar", "minecraft:egg"));
        index++;
        put(recipes, index++, shapeless("minecraft:fermented_spider_eye", 1,
            "minecraft:spider_eye", "minecraft:brown_mushroom", "minecraft:sugar"));
        put(recipes, index++, shapeless("minecraft:blaze_powder", 2, "minecraft:blaze_rod"));
        put(recipes, index++, shapeless("minecraft:magma_cream", 1, "minecraft:blaze_powder", "minecraft:slime_ball"));
        return index;
    }

    private static int addStructureRecipes(Map<Integer, LegacyRecipe> recipes, int index) {
        put(recipes, index++, shaped("minecraft:sandstone", 1, 2, 2,
            "minecraft:sand", "minecraft:sand",
            "minecraft:sand", "minecraft:sand"));
        put(recipes, index++, shaped("minecraft:cut_sandstone", 4, 2, 2,
            "minecraft:sandstone", "minecraft:sandstone",
            "minecraft:sandstone", "minecraft:sandstone"));
        put(recipes, index++, shaped("minecraft:chiseled_sandstone", 1, 1, 2,
            "minecraft:sandstone_slab", "minecraft:sandstone_slab"));
        put(recipes, index++, shaped("minecraft:chiseled_quartz_block", 1, 1, 2,
            "minecraft:quartz_slab", "minecraft:quartz_slab"));
        put(recipes, index++, shaped("minecraft:quartz_pillar", 2, 1, 2,
            "minecraft:quartz_block", "minecraft:quartz_block"));
        put(recipes, index++, shaped("minecraft:crafting_table", 1, 2, 2,
            GROUP_PLANKS, GROUP_PLANKS,
            GROUP_PLANKS, GROUP_PLANKS));
        index += 2;
        put(recipes, index++, shaped("minecraft:trapped_chest", 1, 2, 1, "minecraft:chest", "minecraft:tripwire_hook"));
        index++;
        put(recipes, index++, shaped("minecraft:stone_bricks", 4, 2, 2,
            "minecraft:stone", "minecraft:stone",
            "minecraft:stone", "minecraft:stone"));
        index++;
        put(recipes, index++, shaped("minecraft:nether_bricks", 1, 2, 2,
            "minecraft:nether_brick", "minecraft:nether_brick",
            "minecraft:nether_brick", "minecraft:nether_brick"));
        index += 2;
        return index;
    }

    private static int addClothDyeRecipes(Map<Integer, LegacyRecipe> recipes, int index) {
        for (String color : DYE_COLORS) {
            put(recipes, index++, shapeless("minecraft:" + color + "_wool", 1,
                "minecraft:" + color + "_dye", "minecraft:white_wool"));
        }

        index += 16;

        put(recipes, index++, shapeless("minecraft:yellow_dye", 2, "minecraft:dandelion"));
        put(recipes, index++, shapeless("minecraft:red_dye", 2, "minecraft:poppy"));
        put(recipes, index++, shapeless("minecraft:white_dye", 3, "minecraft:bone"));
        put(recipes, index++, shapeless("minecraft:pink_dye", 2, "minecraft:red_dye", "minecraft:white_dye"));
        put(recipes, index++, shapeless("minecraft:orange_dye", 2, "minecraft:red_dye", "minecraft:yellow_dye"));
        put(recipes, index++, shapeless("minecraft:lime_dye", 2, "minecraft:green_dye", "minecraft:white_dye"));
        put(recipes, index++, shapeless("minecraft:gray_dye", 2, "minecraft:black_dye", "minecraft:white_dye"));
        put(recipes, index++, shapeless("minecraft:light_gray_dye", 2, "minecraft:gray_dye", "minecraft:white_dye"));
        put(recipes, index++, shapeless("minecraft:light_gray_dye", 3,
            "minecraft:black_dye", "minecraft:white_dye", "minecraft:white_dye"));
        put(recipes, index++, shapeless("minecraft:light_blue_dye", 2, "minecraft:blue_dye", "minecraft:white_dye"));
        put(recipes, index++, shapeless("minecraft:cyan_dye", 2, "minecraft:blue_dye", "minecraft:green_dye"));
        put(recipes, index++, shapeless("minecraft:purple_dye", 2, "minecraft:blue_dye", "minecraft:red_dye"));
        put(recipes, index++, shapeless("minecraft:magenta_dye", 2, "minecraft:purple_dye", "minecraft:pink_dye"));
        put(recipes, index++, shapeless("minecraft:magenta_dye", 3,
            "minecraft:blue_dye", "minecraft:red_dye", "minecraft:pink_dye"));
        put(recipes, index++, shapeless("minecraft:magenta_dye", 4,
            "minecraft:blue_dye", "minecraft:red_dye", "minecraft:red_dye", "minecraft:white_dye"));

        for (String color : WOOL_ITEM_COLORS) {
            put(recipes, index++, shaped("minecraft:" + color + "_carpet", 3, 2, 1,
                "minecraft:" + color + "_wool", "minecraft:" + color + "_wool"));
        }

        return index;
    }

    private static int addRecipesAfterCloth(Map<Integer, LegacyRecipe> recipes, int index) {
        put(recipes, index++, shaped("minecraft:snow_block", 1, 2, 2,
            "minecraft:snowball", "minecraft:snowball",
            "minecraft:snowball", "minecraft:snowball"));
        index++;
        put(recipes, index++, shaped("minecraft:clay", 1, 2, 2,
            "minecraft:clay_ball", "minecraft:clay_ball",
            "minecraft:clay_ball", "minecraft:clay_ball"));
        put(recipes, index++, shaped("minecraft:bricks", 1, 2, 2,
            "minecraft:brick", "minecraft:brick",
            "minecraft:brick", "minecraft:brick"));
        put(recipes, index++, shaped("minecraft:white_wool", 1, 2, 2,
            "minecraft:string", "minecraft:string",
            "minecraft:string", "minecraft:string"));
        index += 13;
        put(recipes, index++, shaped("minecraft:sugar", 1, 1, 1, "minecraft:sugar_cane"));
        index += 5;
        put(recipes, index++, shaped("minecraft:chest_minecart", 1, 1, 2, "minecraft:chest", "minecraft:minecart"));
        put(recipes, index++, shaped("minecraft:furnace_minecart", 1, 1, 2, "minecraft:furnace", "minecraft:minecart"));
        put(recipes, index++, shaped("minecraft:tnt_minecart", 1, 1, 2, "minecraft:tnt", "minecraft:minecart"));
        put(recipes, index++, shaped("minecraft:hopper_minecart", 1, 1, 2, "minecraft:hopper", "minecraft:minecart"));
        index += 2;
        put(recipes, index++, shaped("minecraft:carrot_on_a_stick", 1, 2, 2,
            "minecraft:fishing_rod", null,
            null, "minecraft:carrot"));
        put(recipes, index++, shaped("minecraft:flint_and_steel", 1, 2, 2,
            "minecraft:iron_ingot", null,
            null, "minecraft:flint"));
        index += 3;
        return index;
    }

    private static int addRecipesAfterWeapon(Map<Integer, LegacyRecipe> recipes, int index) {
        index += 4;
        put(recipes, index++, shaped("minecraft:torch", 4, 1, 2, "minecraft:charcoal", "minecraft:stick"));
        put(recipes, index++, shaped("minecraft:torch", 4, 1, 2, "minecraft:coal", "minecraft:stick"));
        put(recipes, index++, shaped("minecraft:glowstone", 1, 2, 2,
            "minecraft:glowstone_dust", "minecraft:glowstone_dust",
            "minecraft:glowstone_dust", "minecraft:glowstone_dust"));
        put(recipes, index++, shaped("minecraft:quartz_block", 1, 2, 2,
            "minecraft:quartz", "minecraft:quartz",
            "minecraft:quartz", "minecraft:quartz"));
        put(recipes, index++, shaped("minecraft:lever", 1, 1, 2, "minecraft:stick", "minecraft:cobblestone"));
        index++;
        put(recipes, index++, shaped("minecraft:redstone_torch", 1, 1, 2, "minecraft:redstone", "minecraft:stick"));
        index += 5;
        put(recipes, index++, shapeless("minecraft:ender_eye", 1, "minecraft:ender_pearl", "minecraft:blaze_powder"));
        put(recipes, index++, shapeless("minecraft:fire_charge", 3, "minecraft:gunpowder", "minecraft:blaze_powder", "minecraft:coal"));
        put(recipes, index++, shapeless("minecraft:fire_charge", 3, "minecraft:gunpowder", "minecraft:blaze_powder", "minecraft:charcoal"));
        index += 4;
        put(recipes, index++, shaped("minecraft:stone_button", 1, 1, 1, "minecraft:stone"));
        put(recipes, index++, shaped(GROUP_WOODEN_BUTTON, 1, 1, 1, GROUP_PLANKS));
        put(recipes, index++, shaped(GROUP_WOODEN_PRESSURE_PLATE, 1, 2, 1, GROUP_PLANKS, GROUP_PLANKS));
        put(recipes, index++, shaped("minecraft:stone_pressure_plate", 1, 2, 1, "minecraft:stone", "minecraft:stone"));
        put(recipes, index++, shaped("minecraft:heavy_weighted_pressure_plate", 1, 2, 1,
            "minecraft:iron_ingot", "minecraft:iron_ingot"));
        put(recipes, index++, shaped("minecraft:light_weighted_pressure_plate", 1, 2, 1,
            "minecraft:gold_ingot", "minecraft:gold_ingot"));
        index += 5;
        put(recipes, index++, shaped("minecraft:jack_o_lantern", 1, 1, 2, "minecraft:pumpkin", "minecraft:torch"));
        index++;
        index++;
        put(recipes, index++, shapeless("minecraft:book", 1,
            "minecraft:paper", "minecraft:paper", "minecraft:paper", "minecraft:leather"));
        index += 4;
        return index;
    }

    private static int addOreRecipes(Map<Integer, LegacyRecipe> recipes, int index) {
        String[][] pairs = {
            {"minecraft:gold_block", "minecraft:gold_ingot", "9"},
            {"minecraft:iron_block", "minecraft:iron_ingot", "9"},
            {"minecraft:diamond_block", "minecraft:diamond", "9"},
            {"minecraft:emerald_block", "minecraft:emerald", "9"},
            {"minecraft:lapis_block", "minecraft:lapis_lazuli", "9"},
            {"minecraft:redstone_block", "minecraft:redstone", "9"},
            {"minecraft:coal_block", "minecraft:coal", "9"},
            {"minecraft:hay_block", "minecraft:wheat", "9"}
        };

        for (String[] pair : pairs) {
            index++;
            put(recipes, index++, shaped(pair[1], Integer.parseInt(pair[2]), 1, 1, pair[0]));
        }
        return index;
    }

    private static void addTailRecipes(Map<Integer, LegacyRecipe> recipes, int index) {
        index++;
        put(recipes, index++, shaped("minecraft:gold_nugget", 9, 1, 1, "minecraft:gold_ingot"));
        index += 2;
        put(recipes, index++, shaped("minecraft:sticky_piston", 1, 1, 2, "minecraft:slime_ball", "minecraft:piston"));
    }

    public record LegacyRecipe(int legacyIndex, String resultKey, int resultCount, boolean shaped,
                               int width, int height, List<String> ingredients) {
        public LegacyRecipe withLegacyIndex(int value) {
            return new LegacyRecipe(value, resultKey, resultCount, shaped, width, height, ingredients);
        }
    }

    public record JavaRecipeCandidate(int recipeId, String resultKey, int resultCount, boolean shaped,
                                      int width, int height, List<Set<String>> ingredients) {
    }

    private static final class InventoryStack {
        private final Set<String> keys;
        private int remaining;

        private InventoryStack(Set<String> keys, int remaining) {
            this.keys = keys;
            this.remaining = remaining;
        }
    }
}
