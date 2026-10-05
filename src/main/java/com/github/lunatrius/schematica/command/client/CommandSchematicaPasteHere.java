package com.github.lunatrius.schematica.command.client;

import java.util.List;
import java.util.Locale;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityClientPlayerMP;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.util.ChatAllowedCharacters;

import org.apache.commons.lang3.StringUtils;

import com.github.lunatrius.core.util.vector.Vector3i;
import com.github.lunatrius.schematica.client.world.SchematicWorld;
import com.github.lunatrius.schematica.command.CommandSchematicaPaste;
import com.github.lunatrius.schematica.proxy.ClientProxy;
import com.github.lunatrius.schematica.reference.Names;

/**
 * {@code /schematicaPasteHere [force]}, a client command: sends {@code /schematicaPaste} for the loaded schematic at
 * the hologram's position, so the paste lands exactly where the hologram shows it. Registered with the
 * {@code ClientCommandHandler} by {@link ClientProxy} only, so a dedicated server never loads it.
 */
public class CommandSchematicaPasteHere extends CommandBase {

    /** {@code C01PacketChatMessage} cuts a chat line to this length, which would change the command. */
    private static final int CHAT_LIMIT = 100;

    @Override
    public String getCommandName() {
        return Names.Command.PasteHere.NAME;
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return Names.Command.PasteHere.Message.USAGE;
    }

    @Override
    public boolean canCommandSenderUseCommand(ICommandSender sender) {
        // This only sends a command; the server checks whether the player may paste.
        return true;
    }

    @Override
    public List<String> addTabCompletionOptions(ICommandSender sender, String[] args) {
        if (args.length == 1) {
            return getListOfStringsMatchingLastWord(args, Names.Command.Paste.FORCE);
        }

        return null;
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        final boolean force = args.length == 1 && Names.Command.Paste.FORCE.equals(args[0]);
        if (args.length > 1 || (args.length == 1 && !force)) {
            throw new WrongUsageException(getCommandUsage(sender));
        }

        final SchematicWorld schematic = ClientProxy.schematic;
        if (schematic == null) {
            throw new CommandException(Names.Command.PasteHere.Message.NO_SCHEMATIC);
        }

        // Schematica never transforms tile entity NBT (a GregTech machine's facing lives there), and the server
        // pastes the file as saved, so a rotated or flipped hologram would not match what lands in the world.
        if (schematic.rotationState != 0 || schematic.rotationStateX != 0
            || schematic.rotationStateY != 0
            || schematic.rotationStateZ != 0
            || schematic.flipStateX != 0
            || schematic.flipStateY != 0
            || schematic.flipStateZ != 0) {
            throw new CommandException(Names.Command.PasteHere.Message.TRANSFORMED);
        }

        if (schematic.relativePath == null) {
            throw new CommandException(Names.Command.PasteHere.Message.NO_PATH);
        }

        final String name = CommandSchematicaPaste.toCommandName(schematic.relativePath);
        if (!isSendable(name)) {
            throw new CommandException(Names.Command.PasteHere.Message.UNSENDABLE_PATH, name);
        }

        final Vector3i position = schematic.position;
        final String command = String.format(
            Locale.ROOT,
            "/%s %d %d %d %s%s",
            Names.Command.Paste.NAME,
            position.x,
            position.y,
            position.z,
            name,
            force ? " " + Names.Command.Paste.FORCE : "");
        if (command.length() > CHAT_LIMIT) {
            throw new CommandException(Names.Command.PasteHere.Message.TOO_LONG, command.length(), CHAT_LIMIT);
        }

        final EntityClientPlayerMP player = Minecraft.getMinecraft().thePlayer;
        if (player != null) {
            player.sendChatMessage(command);
        }
    }

    /**
     * Whether the name reaches the server's command parser unchanged. The server collapses runs of spaces, kicks a
     * player whose chat holds a character it does not allow, and reads a last word of {@code force} as the flag.
     */
    private static boolean isSendable(final String name) {
        if (!name.equals(StringUtils.normalizeSpace(name))) {
            return false;
        }

        for (int i = 0; i < name.length(); i++) {
            if (!ChatAllowedCharacters.isAllowedCharacter(name.charAt(i))) {
                return false;
            }
        }

        return !name.endsWith(" " + Names.Command.Paste.FORCE);
    }
}
