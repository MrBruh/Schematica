package com.github.lunatrius.schematica.world.schematic;

import java.util.Set;

/**
 * The block grid of a schematic exactly as its file stores it: the dimensions, one block id and one metadata byte per
 * cell, in the file's own cell order {@code x + (y * length + z) * width}.
 * <p>
 * Unlike {@link com.github.lunatrius.schematica.api.ISchematic}, nothing is instantiated and the metadata keeps all
 * eight bits of the {@code Data} byte.
 */
public final class DecodedBlocks {

    public final int width;
    public final int height;
    public final int length;

    /**
     * The block id of each cell in this game's registry, after the {@code SchematicaMapping} remap. Negative when the
     * cell's mapped name has no usable id here (see {@link #unresolvedNames}).
     */
    public final int[] blockIds;

    /** The whole {@code Data} byte of each cell, 0 to 255. */
    public final int[] metadata;

    /** Names from {@code SchematicaMapping} that at least one cell uses but that resolved to no block id here. */
    public final Set<String> unresolvedNames;

    DecodedBlocks(final int width, final int height, final int length, final int[] blockIds, final int[] metadata,
        final Set<String> unresolvedNames) {
        this.width = width;
        this.height = height;
        this.length = length;
        this.blockIds = blockIds;
        this.metadata = metadata;
        this.unresolvedNames = unresolvedNames;
    }

    public int index(final int x, final int y, final int z) {
        return x + (y * this.length + z) * this.width;
    }
}
