package com.github.lunatrius.schematica.compat;

import gregtech.api.threads.RunnableMachineUpdate;

/**
 * Holds back GregTech's machine update queue while /schematicaPaste writes the world, so a paste behaves like a chunk
 * load. This is the only class of the paste that touches a GregTech class, and the paster calls it only when GregTech
 * is loaded.
 * <p>
 * Why, in GT5-Unofficial 5.09.54.133: every {@code setBlock} of a GT machine block runs
 * {@code BlockMachines.onBlockAdded}, and replacing or removing one runs {@code BlockMachines.breakBlock}; both queue a
 * {@code GregTechAPI.causeMachineUpdate}. GT drains that queue at the end of the server tick
 * ({@code GTProxy.onServerTickEvent}), after the paste command has run, and so calls {@code onMachineBlockUpdate} on
 * the tile entities the paste just loaded. In {@code BaseMetaTileEntity} that sets {@code cableUpdateDelay} to 10 while
 * {@code mTickTimer} is still 0, but the power network map ({@code generatePowerNodes}) is only built when
 * {@code mTickTimer > 20 && cableUpdateDelay == 0}. The delay runs out too early and goes negative, the map is never
 * built, and the cables carry no power until a wire is reconnected by hand. A chunk load queues no machine update, so
 * its tile entities keep the initial delay of 30 and build the map at tick 31.
 * <p>
 * GT does the same when it writes blocks itself: {@code MTEForgeOfGods.createRenderer} and
 * {@code MapGenRuins.setGTMachine} both turn {@code RunnableMachineUpdate} off around their {@code setBlock} calls and
 * back on afterwards. The flag also gates {@code RunnableCableUpdate}, which shares it.
 * <p>
 * Nothing is queued in its place on purpose: a neighbour notification, {@code issueBlockUpdate} or
 * {@code causeCableUpdate} would check pipe connections against tile entities that are still placeholders, and a
 * {@code causeMachineUpdate} is the bug itself.
 */
public final class GTPasteUpdates {

    private GTPasteUpdates() {}

    /**
     * Stops GregTech queueing machine and cable updates until {@link #restore}.
     *
     * @return whether the queue was enabled before, to pass to {@link #restore}
     */
    public static boolean suspend() {
        final boolean wasEnabled = RunnableMachineUpdate.isEnabled();
        RunnableMachineUpdate.setEnabled(false);
        return wasEnabled;
    }

    /**
     * Puts the queue back the way {@link #suspend} found it.
     */
    public static void restore(final boolean wasEnabled) {
        RunnableMachineUpdate.setEnabled(wasEnabled);
    }
}
