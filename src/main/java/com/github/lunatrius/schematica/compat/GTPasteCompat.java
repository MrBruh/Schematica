package com.github.lunatrius.schematica.compat;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.common.util.Constants;

import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.registry.GameRegistry;

/**
 * What /schematicaPaste needs to know about GregTech blocks. It reads only registry names and tile entity NBT, so it
 * never loads a GregTech class, and the paster consults it only when GregTech is loaded.
 * <p>
 * The numbers are GT5-Unofficial 5.09.54.133's:
 * <ul>
 * <li>{@code BlockFrameBox}: a frame's world metadata is its material id ({@code MATERIAL_MASK}, 0xFFF), plus
 * {@code MTE_BIT} (0x1000) while it carries a tile entity, and {@code hasTileEntity} is that bit. The tile entity only
 * exists once a cover is put on the frame ({@code spawnFrameEntity}), as a {@code BaseMetaPipeEntity} with
 * {@code mID = 4096 + material}.</li>
 * <li>{@code BaseMetaTileEntity.setInitialValuesAsNBT}: the owner is read from {@code mOwnerName} and
 * {@code mOwnerUuid}, which a machine placed by hand gets from the player who placed it.</li>
 * <li>{@code GTValues.NBT.COVERS}: covers are stored as the {@code gt.covers} list.</li>
 * </ul>
 */
public final class GTPasteCompat {

    public static final String MOD_ID = "gregtech";

    private static final String FRAME_BLOCK = "gt.blockframes";
    private static final String MACHINE_BLOCK = "gt.blockmachines";

    private static final int FRAME_MATERIAL_MASK = 0xFFF;
    private static final int FRAME_MTE_BIT = 0x1000;
    private static final int FRAME_MID_BASE = 4096;

    private static final String TILE_ID = "id";
    private static final String TILE_ID_META_TILE_ENTITY = "BaseMetaTileEntity";
    private static final String TILE_ID_META_PIPE_ENTITY = "BaseMetaPipeEntity";
    private static final String NBT_MID = "mID";
    private static final String NBT_COVERS = "gt.covers";
    private static final String NBT_OWNER_NAME = "mOwnerName";
    private static final String NBT_OWNER_UUID = "mOwnerUuid";

    private final Block frameBlock;
    private final Block machineBlock;

    private GTPasteCompat(final Block frameBlock, final Block machineBlock) {
        this.frameBlock = frameBlock;
        this.machineBlock = machineBlock;
    }

    /**
     * @return the GregTech rules for one paste, or null when GregTech is not loaded
     */
    public static GTPasteCompat create() {
        if (!Loader.isModLoaded(MOD_ID)) {
            return null;
        }

        return new GTPasteCompat(
            GameRegistry.findBlock(MOD_ID, FRAME_BLOCK),
            GameRegistry.findBlock(MOD_ID, MACHINE_BLOCK));
    }

    public boolean isFrame(final Block block) {
        return block != null && block == this.frameBlock;
    }

    public boolean isMachine(final Block block) {
        return block != null && block == this.machineBlock;
    }

    /**
     * The world metadata of a frame whose tile entity NBT is {@code tile}: the material from {@code mID}, plus
     * {@code MTE_BIT} when the frame has covers and so keeps its tile entity.
     *
     * @return the metadata, or -1 when {@code tile} is not a frame's tile entity
     */
    public static int frameMetadata(final NBTTagCompound tile) {
        if (tile == null || !TILE_ID_META_PIPE_ENTITY.equals(tile.getString(TILE_ID))) {
            return -1;
        }

        final int mID = tile.getInteger(NBT_MID);
        if (mID < FRAME_MID_BASE || mID > FRAME_MID_BASE + FRAME_MATERIAL_MASK) {
            return -1;
        }

        final int material = mID - FRAME_MID_BASE;
        final boolean hasCovers = tile.getTagList(NBT_COVERS, Constants.NBT.TAG_COMPOUND)
            .tagCount() > 0;
        return hasCovers ? material | FRAME_MTE_BIT : material;
    }

    /**
     * @return whether a frame with this world metadata keeps a tile entity
     */
    public static boolean frameKeepsTileEntity(final int metadata) {
        return (metadata & FRAME_MTE_BIT) != 0;
    }

    /**
     * Makes {@code player} the owner of a GregTech machine, as placing it by hand would, unless the NBT already names
     * one. Each tag is filled only when it is absent or empty.
     */
    public static void fillOwner(final NBTTagCompound tile, final EntityPlayer player) {
        if (!TILE_ID_META_TILE_ENTITY.equals(tile.getString(TILE_ID))) {
            return;
        }

        if (tile.getString(NBT_OWNER_NAME)
            .isEmpty()) {
            tile.setString(NBT_OWNER_NAME, player.getDisplayName());
        }

        if (tile.getString(NBT_OWNER_UUID)
            .isEmpty()) {
            tile.setString(
                NBT_OWNER_UUID,
                player.getUniqueID()
                    .toString());
        }
    }
}
