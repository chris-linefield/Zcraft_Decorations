package com.zcraft.decorations.registry;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import com.zcraft.decorations.ZcraftDecorationsMod;
import com.zcraft.decorations.block.CollisionProxyBlock;
import com.zcraft.decorations.block.ModelBasedFacingBlock;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModBlocks {
    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(BuiltInRegistries.BLOCK, ZcraftDecorationsMod.MODID);
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(BuiltInRegistries.ITEM, ZcraftDecorationsMod.MODID);

    /**
     * Premier bloc décoratif du fichier liste (hors {@link #COLLISION_PROXY}) : icône d’onglet créatif stable.
     */
    private static DeferredHolder<Item, BlockItem> creativeTabIconItem;

    private static final String BLOCK_LIST_RESOURCE = "test_block_list.txt";
    private static final List<String> BLOCK_NAMES = new ArrayList<>();
    private static final Set<String> NO_COLLISION_BLOCKS = Set.of(
            "cao_1",
            "dui_1",
            "dui_2",
            "dui_3",
            "dui_4",
            "dui_5",
            "dui_6",
            "dui_7",
            "dui_8",
            "dui_9",
            "sfz_changcao",
            "sfz_dishangxueye",
            "sfz_dishangxueye_2",
            "sfz_dishangxueye_4",
            "sfz_xie_ye",
            "sfz_yumi",
            "sfz_zmlaji",
            "sfz_zmlaji_1"
    );
    private static final Set<String> NO_COLLISION_KEEP_DROPS_BLOCKS = Set.of(
            "men_1",
            "men_2",
            "men_3",
            "men_4"
    );

    public static final DeferredHolder<Block, CollisionProxyBlock> COLLISION_PROXY = BLOCKS.register("collision_proxy",
            () -> new CollisionProxyBlock(BlockBehaviour.Properties.of()
                    .strength(0.5F, 1.0F)
                    .sound(SoundType.STONE)
                    .dynamicShape()
                    .noOcclusion()));

    static {
        try (InputStream in = ModBlocks.class.getClassLoader().getResourceAsStream(BLOCK_LIST_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Missing resource: " + BLOCK_LIST_RESOURCE);
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String name = line.trim();
                    if (!name.isEmpty() && name.charAt(0) == '\uFEFF') {
                        name = name.substring(1).trim();
                    }
                    int commentIndex = name.indexOf('#');
                    if (commentIndex >= 0) {
                        name = name.substring(0, commentIndex).trim();
                    }
                    if (name.isEmpty() || name.startsWith("#")) {
                        continue;
                    }
                    BLOCK_NAMES.add(name);
                    registerBlock(name);
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to load block list: " + BLOCK_LIST_RESOURCE, e);
        }
    }

    private static DeferredHolder<Block, ModelBasedFacingBlock> registerBlock(String name) {
        boolean noCollision = NO_COLLISION_BLOCKS.contains(name) || NO_COLLISION_KEEP_DROPS_BLOCKS.contains(name);
        boolean noDrops = NO_COLLISION_BLOCKS.contains(name);
        DeferredHolder<Block, ModelBasedFacingBlock> block = BLOCKS.register(name, () -> new ModelBasedFacingBlock(name, blockProperties(noCollision), noCollision, noDrops));
        DeferredHolder<Item, BlockItem> itemHolder = ITEMS.register(name, () -> new BlockItem(block.get(), new Item.Properties()));
        if (creativeTabIconItem == null) {
            creativeTabIconItem = itemHolder;
        }
        return block;
    }

    /**
     * Item utilisé pour l’icône de l’onglet créatif (premier bloc de la liste chargée).
     */
    public static Item creativeTabIconItem() {
        if (creativeTabIconItem != null) {
            return creativeTabIconItem.get();
        }
        return COLLISION_PROXY.get().asItem();
    }

    public static List<String> getBlockNames() {
        return Collections.unmodifiableList(BLOCK_NAMES);
    }

    private static BlockBehaviour.Properties blockProperties(boolean noCollision) {
        if (noCollision) {
            return BlockBehaviour.Properties.of()
                    .strength(0.1F, 1.0F)
                    .sound(SoundType.GRASS)
                    .dynamicShape()
                    .noOcclusion()
                    .noCollission();
        }
        return BlockBehaviour.Properties.of()
                .strength(1.0F, 10.0F)
                .sound(SoundType.STONE)
                .dynamicShape()
                .noOcclusion();
    }

    private ModBlocks() {
    }
}
