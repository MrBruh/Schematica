package com.github.lunatrius.schematica.mixins;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.gtnewhorizon.gtnhmixins.ILateMixinLoader;
import com.gtnewhorizon.gtnhmixins.LateMixin;

import cpw.mods.fml.relauncher.FMLLaunchHandler;

/**
 * Mixins into other mods, applied once every mod is known. Each one is listed only when its target mod is loaded, so
 * none of them is touched, or needs its target on the classpath, when that mod is absent.
 */
@LateMixin
public class LateMixinLoader implements ILateMixinLoader {

    @Override
    public String getMixinConfig() {
        return "mixins.Schematica.late.json";
    }

    @Override
    public List<String> getMixins(Set<String> loadedMods) {
        final List<String> mixins = new ArrayList<>();
        // The hologram is drawn on the client only, so a dedicated server needs none of these.
        if (FMLLaunchHandler.side()
            .isClient() && loadedMods.contains("gregtech")) {
            mixins.add("gregtech.MixinGTRenderedTexture");
        }
        return mixins;
    }
}
