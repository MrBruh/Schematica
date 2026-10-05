package com.github.lunatrius.schematica.world.paste;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import net.minecraft.block.Block;
import net.minecraft.command.CommandException;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.inventory.IInventory;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.world.World;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.common.util.Constants;

import com.github.lunatrius.schematica.compat.GTPasteCompat;
import com.github.lunatrius.schematica.handler.ConfigurationHandler;
import com.github.lunatrius.schematica.nbt.NBTHelper;
import com.github.lunatrius.schematica.reference.Names;
import com.github.lunatrius.schematica.reference.Reference;
import com.github.lunatrius.schematica.world.schematic.DecodedBlocks;
import com.github.lunatrius.schematica.world.schematic.SchematicAlpha;
import com.github.lunatrius.schematica.world.schematic.SchematicUtil;

import cpw.mods.fml.common.registry.FMLControlledNamespacedRegistry;
import cpw.mods.fml.common.registry.GameData;

/**
 * Writes a schematic file into the world together with its tile entity NBT, the way a chunk load restores a tile
 * entity, for building and testing in creative. Runs on the server thread, inside the command.
 *
 * <pre>
 *  file --read--> root compound --SchematicAlpha.decodeBlocks--> ids, 8-bit Data, size
 *                      |
 *                      +-- TileEntities, keyed by their local x/y/z
 *
 *  preflight, refusing before anything changes:
 *    size under pasteVolumeLimit, y within 0..255, every chunk loaded,
 *    every block registered, no GT machine without tile NBT,
 *    nothing solid in the way unless force
 *
 *  write:
 *    force: empty the inventories in the box, so nothing spills
 *    setBlock(flag 2) on every cell, bottom up (a GT frame's metadata comes from its tile NBT)
 *    each tile NBT: copy, move to world x/y/z, fill a GT owner, create, setTileEntity
 *    markBlockForUpdate on every tile entity cell; no neighbour notification pass
 *    force: remove the item entities this paste spilled after all
 * </pre>
 *
 * Each player's last paste is remembered for {@link #undo}, which sets that box back to air. Undo does not restore
 * what a forced paste replaced.
 */
public final class SchematicPaster {

    private static final FMLControlledNamespacedRegistry<Block> BLOCK_REGISTRY = GameData.getBlockRegistry();

    /** How many unknown block names a refusal lists before it says how many more there are. */
    private static final int MAX_LISTED_NAMES = 10;

    private static final Map<UUID, PasteBox> LAST_PASTES = new HashMap<>();

    private SchematicPaster() {}

    /**
     * Forgets every player's last paste, so an undo never reaches into a world from an earlier session.
     */
    public static void clearUndoHistory() {
        LAST_PASTES.clear();
    }

    /**
     * Pastes {@code file} with its lowest corner at the origin.
     *
     * @param extendedFormat true when the file is in the schemplus layout
     * @throws CommandException with a translatable message when the paste is refused; the world is then unchanged
     */
    public static Result paste(final EntityPlayerMP player, final World world, final File file,
        final boolean extendedFormat, final int originX, final int originY, final int originZ, final boolean force) {
        final String name = file.getName();

        final NBTTagCompound root;
        try {
            root = SchematicUtil.readTagCompoundFromFile(file);
        } catch (Exception e) {
            Reference.logger.error("Could not read {} for a paste", file, e);
            throw new CommandException(Names.Command.Paste.Message.READ_FAILED, name);
        }

        if (!Names.NBT.FORMAT_ALPHA.equals(root.getString(Names.NBT.MATERIALS))) {
            throw new CommandException(Names.Command.Paste.Message.UNSUPPORTED_FORMAT, name);
        }

        final int width = root.getShort(Names.NBT.WIDTH);
        final int height = root.getShort(Names.NBT.HEIGHT);
        final int length = root.getShort(Names.NBT.LENGTH);
        if (width <= 0 || height <= 0 || length <= 0) {
            throw new CommandException(Names.Command.Paste.Message.EMPTY, name, width, height, length);
        }

        final long volume = (long) width * height * length;
        if (volume > ConfigurationHandler.pasteVolumeLimit) {
            throw new CommandException(
                Names.Command.Paste.Message.TOO_LARGE,
                name,
                volume,
                ConfigurationHandler.pasteVolumeLimit);
        }

        final PasteBox box = new PasteBox(
            world.provider.dimensionId,
            originX,
            originY,
            originZ,
            originX + width - 1,
            originY + height - 1,
            originZ + length - 1);
        if (box.minY < 0 || box.maxY > 255) {
            throw new CommandException(Names.Command.Paste.Message.OUT_OF_HEIGHT, box.minY, box.maxY);
        }

        if (!world.checkChunksExist(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ)) {
            throw new CommandException(Names.Command.Paste.Message.NOT_LOADED);
        }

        final DecodedBlocks blocks;
        try {
            blocks = SchematicAlpha.decodeBlocks(root, extendedFormat);
        } catch (Exception e) {
            Reference.logger.error("Could not decode the blocks of {} for a paste", file, e);
            throw new CommandException(Names.Command.Paste.Message.READ_FAILED, name);
        }

        final List<String> warnings = new ArrayList<>();
        final Block[] cells = resolveBlocks(blocks);
        final Map<Integer, NBTTagCompound> tiles = readTileEntities(root, blocks, box, warnings);
        final int[] metadata = blocks.metadata.clone();

        final GTPasteCompat gregtech = GTPasteCompat.create();
        if (gregtech != null) {
            refuseMachinesWithoutData(gregtech, blocks, box, cells, tiles);
            planFrames(gregtech, blocks, box, cells, tiles, metadata, warnings);
        }

        if (!force) {
            refuseOccupiedTarget(world, box);
        }

        // Nothing has changed in the world until here.
        if (force) {
            emptyInventories(world, box, warnings);
        }

        int placedBlocks = 0;
        for (int y = 0; y < blocks.height; y++) {
            for (int z = 0; z < blocks.length; z++) {
                for (int x = 0; x < blocks.width; x++) {
                    final int index = blocks.index(x, y, z);
                    final Block block = cells[index];
                    final int wx = box.minX + x;
                    final int wy = box.minY + y;
                    final int wz = box.minZ + z;

                    world.setBlock(wx, wy, wz, block, metadata[index], 2);
                    if (block != Blocks.air) {
                        placedBlocks++;
                    }

                    if (gregtech != null && gregtech.isFrame(block)) {
                        final int stored = world.getBlockMetadata(wx, wy, wz);
                        if (stored != metadata[index]) {
                            warnings.add(
                                String.format(
                                    "GT frame at %d %d %d holds metadata %d instead of %d, so this world cannot store"
                                        + " its material (is EndlessIDs installed?); it gets no tile entity",
                                    wx,
                                    wy,
                                    wz,
                                    stored,
                                    metadata[index]));
                            tiles.remove(index);
                        }
                    }
                }
            }
        }

        final List<int[]> tileCells = new ArrayList<>();
        for (Map.Entry<Integer, NBTTagCompound> entry : tiles.entrySet()) {
            final int[] cell = worldPosition(blocks, box, entry.getKey());
            final NBTTagCompound tag = (NBTTagCompound) entry.getValue()
                .copy();
            final String id = tag.getString("id");

            final Block block = world.getBlock(cell[0], cell[1], cell[2]);
            final int meta = world.getBlockMetadata(cell[0], cell[1], cell[2]);
            if (!block.hasTileEntity(meta)) {
                // world.setTileEntity would still add it to the ticking list, as an orphan no chunk holds.
                warnings.add(
                    String.format(
                        "Skipped tile entity %s at %d %d %d: the block there (%s, metadata %d) has no tile entity",
                        id,
                        cell[0],
                        cell[1],
                        cell[2],
                        BLOCK_REGISTRY.getNameForObject(block),
                        meta));
                continue;
            }

            tag.setInteger("x", cell[0]);
            tag.setInteger("y", cell[1]);
            tag.setInteger("z", cell[2]);
            if (gregtech != null) {
                GTPasteCompat.fillOwner(tag, player);
            }

            final TileEntity tileEntity = createTileEntity(tag);
            if (tileEntity == null) {
                warnings.add(
                    String.format(
                        "Could not create tile entity %s at %d %d %d (is its mod installed?)",
                        id,
                        cell[0],
                        cell[1],
                        cell[2]));
                continue;
            }

            world.setTileEntity(cell[0], cell[1], cell[2], tileEntity);
            world.markTileEntityChunkModified(cell[0], cell[1], cell[2], tileEntity);
            tileCells.add(cell);
        }

        for (int[] cell : tileCells) {
            world.markBlockForUpdate(cell[0], cell[1], cell[2]);
        }

        final int removedItems = force ? removeFreshItems(world, box) : 0;

        LAST_PASTES.put(player.getUniqueID(), box);

        Reference.logger.info(
            "{} pasted {} in dimension {} from {} {} {} to {} {} {}{}: {} blocks, {} tile entities, {} spilled items"
                + " removed, {} warnings",
            player.getCommandSenderName(),
            file.getPath(),
            box.dimension,
            box.minX,
            box.minY,
            box.minZ,
            box.maxX,
            box.maxY,
            box.maxZ,
            force ? " with force" : "",
            placedBlocks,
            tileCells.size(),
            removedItems,
            warnings.size());
        for (String warning : warnings) {
            Reference.logger.warn("Paste of {}: {}", name, warning);
        }

        return new Result(placedBlocks, tileCells.size(), warnings);
    }

    /**
     * Sets the box of the player's last paste back to air, emptying its inventories first so nothing spills.
     *
     * @return the box that was cleared
     * @throws CommandException when there is nothing to undo or the box is not loaded
     */
    public static PasteBox undo(final EntityPlayerMP player) {
        final PasteBox box = LAST_PASTES.get(player.getUniqueID());
        if (box == null) {
            throw new CommandException(Names.Command.Paste.Message.NOTHING_TO_UNDO);
        }

        final World world = DimensionManager.getWorld(box.dimension);
        if (world == null || !world.checkChunksExist(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ)) {
            throw new CommandException(Names.Command.Paste.Message.UNDO_NOT_LOADED);
        }

        final List<String> warnings = new ArrayList<>();
        emptyInventories(world, box, warnings);
        for (int y = box.maxY; y >= box.minY; y--) {
            for (int z = box.minZ; z <= box.maxZ; z++) {
                for (int x = box.minX; x <= box.maxX; x++) {
                    world.setBlock(x, y, z, Blocks.air, 0, 2);
                }
            }
        }
        final int removedItems = removeFreshItems(world, box);

        LAST_PASTES.remove(player.getUniqueID());

        Reference.logger.info(
            "{} undid their paste in dimension {} from {} {} {} to {} {} {}: {} spilled items removed",
            player.getCommandSenderName(),
            box.dimension,
            box.minX,
            box.minY,
            box.minZ,
            box.maxX,
            box.maxY,
            box.maxZ,
            removedItems);
        for (String warning : warnings) {
            Reference.logger.warn("Undo of a paste: {}", warning);
        }

        return box;
    }

    /**
     * The block of every cell. Refuses the paste if any cell names a block this game does not have, rather than
     * placing air there.
     */
    private static Block[] resolveBlocks(final DecodedBlocks blocks) {
        final Set<String> unknown = new TreeSet<>(blocks.unresolvedNames);
        final Block[] cells = new Block[blocks.blockIds.length];
        for (int i = 0; i < cells.length; i++) {
            final int id = blocks.blockIds[i];
            if (id >= 0) {
                cells[i] = BLOCK_REGISTRY.getRaw(id);
                if (cells[i] == null) {
                    unknown.add("id " + id);
                }
            }
        }

        if (!unknown.isEmpty()) {
            final StringBuilder listed = new StringBuilder();
            final Iterator<String> names = unknown.iterator();
            for (int i = 0; i < MAX_LISTED_NAMES && names.hasNext(); i++) {
                if (i > 0) {
                    listed.append(", ");
                }
                listed.append(names.next());
            }
            if (unknown.size() > MAX_LISTED_NAMES) {
                listed.append(" and ")
                    .append(unknown.size() - MAX_LISTED_NAMES)
                    .append(" more");
            }
            throw new CommandException(Names.Command.Paste.Message.UNKNOWN_BLOCKS, listed.toString());
        }

        return cells;
    }

    /**
     * The file's tile entity compounds, keyed by the cell index of their local x/y/z, in file order.
     */
    private static Map<Integer, NBTTagCompound> readTileEntities(final NBTTagCompound root, final DecodedBlocks blocks,
        final PasteBox box, final List<String> warnings) {
        final Map<Integer, NBTTagCompound> tiles = new LinkedHashMap<>();
        final NBTTagList list = root.getTagList(Names.NBT.TILE_ENTITIES, Constants.NBT.TAG_COMPOUND);
        for (int i = 0; i < list.tagCount(); i++) {
            final NBTTagCompound tile = list.getCompoundTagAt(i);
            final int x = tile.getInteger("x");
            final int y = tile.getInteger("y");
            final int z = tile.getInteger("z");

            if (x < 0 || y < 0 || z < 0 || x >= blocks.width || y >= blocks.height || z >= blocks.length) {
                warnings.add(
                    String.format(
                        "Skipped tile entity %s at local %d %d %d: outside the schematic",
                        tile.getString("id"),
                        x,
                        y,
                        z));
                continue;
            }

            if (tiles.put(blocks.index(x, y, z), tile) != null) {
                warnings.add(
                    String.format(
                        "Two tile entities for %d %d %d; kept the later one",
                        box.minX + x,
                        box.minY + y,
                        box.minZ + z));
            }
        }

        return tiles;
    }

    /**
     * A GT machine block without tile entity NBT would get an empty tile entity that GT locks as unloadable, so such
     * a file is refused rather than pasted half broken.
     */
    private static void refuseMachinesWithoutData(final GTPasteCompat gregtech, final DecodedBlocks blocks,
        final PasteBox box, final Block[] cells, final Map<Integer, NBTTagCompound> tiles) {
        int count = 0;
        String first = null;
        for (int i = 0; i < cells.length; i++) {
            if (gregtech.isMachine(cells[i]) && !tiles.containsKey(i)) {
                count++;
                if (first == null) {
                    first = describe(worldPosition(blocks, box, i));
                }
            }
        }

        if (count > 0) {
            throw new CommandException(Names.Command.Paste.Message.MACHINES_WITHOUT_DATA, count, first);
        }
    }

    /**
     * A GT frame's material does not fit the file's Data byte, so it is read back from the frame's tile entity, and a
     * frame keeps that tile entity only when it has covers, exactly as in a world.
     */
    private static void planFrames(final GTPasteCompat gregtech, final DecodedBlocks blocks, final PasteBox box,
        final Block[] cells, final Map<Integer, NBTTagCompound> tiles, final int[] metadata,
        final List<String> warnings) {
        for (int i = 0; i < cells.length; i++) {
            if (!gregtech.isFrame(cells[i])) {
                continue;
            }

            final int frameMetadata = GTPasteCompat.frameMetadata(tiles.get(i));
            if (frameMetadata < 0) {
                warnings.add(
                    String.format(
                        "GT frame at %s has no frame tile entity to read its material from, so it keeps the file's"
                            + " metadata %d",
                        describe(worldPosition(blocks, box, i)),
                        metadata[i]));
                continue;
            }

            metadata[i] = frameMetadata;
            if (!GTPasteCompat.frameKeepsTileEntity(frameMetadata)) {
                tiles.remove(i);
            }
        }
    }

    /**
     * Refuses the paste if any block in the box is neither air nor replaceable.
     */
    private static void refuseOccupiedTarget(final World world, final PasteBox box) {
        int count = 0;
        String first = null;
        for (int y = box.minY; y <= box.maxY; y++) {
            for (int z = box.minZ; z <= box.maxZ; z++) {
                for (int x = box.minX; x <= box.maxX; x++) {
                    final Block existing = world.getBlock(x, y, z);
                    if (!existing.isAir(world, x, y, z) && !existing.isReplaceable(world, x, y, z)) {
                        count++;
                        if (first == null) {
                            first = describe(new int[] { x, y, z });
                        }
                    }
                }
            }
        }

        if (count > 0) {
            throw new CommandException(Names.Command.Paste.Message.NOT_EMPTY, count, first);
        }
    }

    /**
     * Empties every inventory in the box, so replacing or removing its block spills nothing.
     */
    private static void emptyInventories(final World world, final PasteBox box, final List<String> warnings) {
        for (int y = box.minY; y <= box.maxY; y++) {
            for (int z = box.minZ; z <= box.maxZ; z++) {
                for (int x = box.minX; x <= box.maxX; x++) {
                    if (!world.getBlock(x, y, z)
                        .hasTileEntity(world.getBlockMetadata(x, y, z))) {
                        continue;
                    }

                    final TileEntity tileEntity = world.getTileEntity(x, y, z);
                    if (tileEntity instanceof IInventory inventory) {
                        try {
                            for (int slot = 0; slot < inventory.getSizeInventory(); slot++) {
                                inventory.setInventorySlotContents(slot, null);
                            }
                        } catch (Exception e) {
                            Reference.logger.warn("Could not empty the inventory at {} {} {}", x, y, z, e);
                            warnings.add(String.format("Could not empty the inventory at %d %d %d", x, y, z));
                        }
                    }
                }
            }
        }
    }

    /**
     * Removes the item entities that appeared in or next to the box during this command, which are what a replaced
     * block dropped. An item that has existed for a tick or more is left alone.
     *
     * @return how many were removed
     */
    private static int removeFreshItems(final World world, final PasteBox box) {
        final AxisAlignedBB bounds = AxisAlignedBB
            .getBoundingBox(box.minX - 1, box.minY - 1, box.minZ - 1, box.maxX + 2, box.maxY + 2, box.maxZ + 2);
        int removed = 0;
        for (EntityItem item : world.getEntitiesWithinAABB(EntityItem.class, bounds)) {
            if (item.ticksExisted == 0 && !item.isDead) {
                item.setDead();
                removed++;
            }
        }
        return removed;
    }

    private static TileEntity createTileEntity(final NBTTagCompound tag) {
        try {
            return NBTHelper.readTileEntityFromCompound(tag);
        } catch (Exception e) {
            Reference.logger.warn("Tile entity {} failed to load for a paste", tag.getString("id"), e);
            return null;
        }
    }

    private static int[] worldPosition(final DecodedBlocks blocks, final PasteBox box, final int index) {
        final int x = index % blocks.width;
        final int rest = index / blocks.width;
        final int z = rest % blocks.length;
        final int y = rest / blocks.length;
        return new int[] { box.minX + x, box.minY + y, box.minZ + z };
    }

    private static String describe(final int[] position) {
        return position[0] + " " + position[1] + " " + position[2];
    }

    /**
     * What one paste wrote.
     */
    public static final class Result {

        /** Cells that are not air. */
        public final int blocks;
        public final int tileEntities;
        /** Everything that did not paste as the file says, in English, for chat and the log. */
        public final List<String> warnings;

        private Result(final int blocks, final int tileEntities, final List<String> warnings) {
            this.blocks = blocks;
            this.tileEntities = tileEntities;
            this.warnings = warnings;
        }
    }

    /**
     * The box one paste wrote, inclusive on every side.
     */
    public static final class PasteBox {

        public final int dimension;
        public final int minX;
        public final int minY;
        public final int minZ;
        public final int maxX;
        public final int maxY;
        public final int maxZ;

        private PasteBox(final int dimension, final int minX, final int minY, final int minZ, final int maxX,
            final int maxY, final int maxZ) {
            this.dimension = dimension;
            this.minX = minX;
            this.minY = minY;
            this.minZ = minZ;
            this.maxX = maxX;
            this.maxY = maxY;
            this.maxZ = maxZ;
        }

        public int sizeX() {
            return this.maxX - this.minX + 1;
        }

        public int sizeY() {
            return this.maxY - this.minY + 1;
        }

        public int sizeZ() {
            return this.maxZ - this.minZ + 1;
        }
    }
}
