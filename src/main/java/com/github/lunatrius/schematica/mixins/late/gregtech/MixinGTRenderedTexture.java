package com.github.lunatrius.schematica.mixins.late.gregtech;

import net.minecraft.tileentity.TileEntity;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.github.lunatrius.schematica.client.world.SchematicWorld;
import com.gtnewhorizon.structurelib.alignment.IAlignment;
import com.gtnewhorizon.structurelib.alignment.IAlignmentProvider;
import com.gtnewhorizon.structurelib.alignment.enumerable.ExtendedFacing;

import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.render.ISBRContext;
import gregtech.api.render.ISBRWorldContext;
import gregtech.common.render.GTRenderedTexture;

/**
 * Reads a GregTech texture's extended facing from the world being rendered when that world is a schematic.
 * <p>
 * GTRenderedTexture.getExtendedFacing looks the tile entity up in the player's world at the coordinates it is drawing.
 * The hologram is drawn with schematic-local coordinates, so that lookup finds no multiblock there, returns null, and
 * the texture falls back to its "not a multiblock" flips: a controller's front drawn mirrored vertically when it faces
 * north and horizontally when it faces east. Here the tile entity comes from the schematic itself, through the same
 * alignment lookup GregTech uses. Rendering into any other world is left to GregTech, unchanged.
 */
@Mixin(value = GTRenderedTexture.class, remap = false)
public abstract class MixinGTRenderedTexture {

    @Shadow
    @Final
    private boolean useExtFacing;

    @Inject(method = "getExtendedFacing", at = @At("HEAD"), cancellable = true)
    private void schematica$getExtendedFacingInSchematic(ISBRContext ctx, CallbackInfoReturnable<ExtendedFacing> cir) {
        if (!this.useExtFacing || !(ctx instanceof ISBRWorldContext)) {
            return;
        }

        final ISBRWorldContext worldCtx = (ISBRWorldContext) ctx;
        if (!(worldCtx.getBlockAccess() instanceof SchematicWorld)) {
            return;
        }

        final TileEntity te = worldCtx.getTileEntity();

        IAlignment alignment = null;

        if (te instanceof IGregTechTileEntity) {
            final IMetaTileEntity meta = ((IGregTechTileEntity) te).getMetaTileEntity();

            if (meta instanceof IAlignmentProvider) {
                alignment = ((IAlignmentProvider) meta).getAlignment();
            }
        } else if (te instanceof IAlignmentProvider) {
            alignment = ((IAlignmentProvider) te).getAlignment();
        }

        // Null, as in GregTech, keeps the "not a multiblock" flips for a block that has no alignment.
        cir.setReturnValue(alignment != null ? alignment.getExtendedFacing() : null);
    }
}
