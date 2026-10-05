package com.github.lunatrius.schematica.command;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.ChunkCoordinates;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.MathHelper;

import com.github.lunatrius.schematica.Schematica;
import com.github.lunatrius.schematica.reference.Constants;
import com.github.lunatrius.schematica.reference.Names;
import com.github.lunatrius.schematica.reference.Reference;
import com.github.lunatrius.schematica.util.FileUtils;
import com.github.lunatrius.schematica.world.paste.SchematicPaster;

/**
 * {@code /schematicaPaste <x> <y> <z> <name...> [force]} writes a schematic from the player's schematic directory into
 * the world with its tile entity NBT, and {@code /schematicaPaste undo} clears the player's last paste back to air.
 * Operators only, like {@code /setblock}. In single player the directory is the client's own schematics folder.
 */
public class CommandSchematicaPaste extends CommandBase {

    @Override
    public String getCommandName() {
        return Names.Command.Paste.NAME;
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return Names.Command.Paste.Message.USAGE;
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 2;
    }

    @Override
    public List<String> addTabCompletionOptions(ICommandSender sender, String[] args) {
        // Vanilla completes arguments without checking the permission level, and this lists the player's files.
        if (!canCommandSenderUseCommand(sender) || !(sender instanceof EntityPlayer player)) {
            return null;
        }

        if (args.length == 1) {
            return getListOfStringsMatchingLastWord(args, Names.Command.Paste.UNDO);
        }

        if (args.length == 4) {
            final File directory = Schematica.proxy.getPlayerSchematicDirectory(player, true);
            final File[] files = directory != null ? directory.listFiles() : null;
            if (files == null) {
                return null;
            }

            final List<String> names = new ArrayList<>();
            for (File file : files) {
                if (file.isFile() && hasSchematicExtension(file.getName())) {
                    names.add(toCommandName(file.getName()));
                }
            }

            return getListOfStringsFromIterableMatchingLastWord(args, names);
        }

        if (args.length >= 5) {
            return getListOfStringsMatchingLastWord(args, Names.Command.Paste.FORCE);
        }

        return null;
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        if (!(sender instanceof EntityPlayerMP player)) {
            throw new CommandException(Names.Command.Paste.Message.PLAYERS_ONLY);
        }

        if (args.length == 1 && Names.Command.Paste.UNDO.equals(args[0])) {
            final SchematicPaster.PasteBox box = SchematicPaster.undo(player);
            sender.addChatMessage(
                new ChatComponentTranslation(
                    Names.Command.Paste.Message.UNDONE,
                    box.sizeX(),
                    box.sizeY(),
                    box.sizeZ()));
            return;
        }

        if (args.length < 4) {
            throw new WrongUsageException(getCommandUsage(sender));
        }

        final ChunkCoordinates position = sender.getPlayerCoordinates();
        final int x = MathHelper.floor_double(func_110666_a(sender, position.posX, args[0]));
        final int y = MathHelper.floor_double(func_110666_a(sender, position.posY, args[1]));
        final int z = MathHelper.floor_double(func_110666_a(sender, position.posZ, args[2]));

        // The name is the rest of the line, so it may hold spaces; a last word of "force" is the flag.
        final boolean force = args.length > 4 && Names.Command.Paste.FORCE.equals(args[args.length - 1]);
        final String name = String.join(" ", Arrays.copyOfRange(args, 3, force ? args.length - 1 : args.length));

        final File directory = Schematica.proxy.getPlayerSchematicDirectory(player, true);
        if (directory == null) {
            throw new CommandException(Names.Command.Paste.Message.DIRECTORY_UNAVAILABLE);
        }

        final File file = resolve(player, directory, name);
        final boolean extendedFormat = file.getName()
            .toLowerCase(Locale.ROOT)
            .endsWith(Constants.Files.EXTENSION_SCHEMPLUS);
        final SchematicPaster.Result result = SchematicPaster
            .paste(player, player.worldObj, file, extendedFormat, x, y, z, force);

        sender.addChatMessage(
            new ChatComponentTranslation(
                Names.Command.Paste.Message.PASTED,
                name,
                x,
                y,
                z,
                result.blocks,
                result.tileEntities));
        if (!result.warnings.isEmpty()) {
            final ChatComponentTranslation warnings = new ChatComponentTranslation(
                Names.Command.Paste.Message.WARNINGS,
                result.warnings.size(),
                result.warnings.get(0));
            warnings.getChatStyle()
                .setColor(EnumChatFormatting.YELLOW);
            sender.addChatMessage(warnings);
        }
    }

    /**
     * The name a command uses for a schematic file: its path without {@code .schematic}, the extension the command
     * tries first. A {@code .schemplus} file keeps its extension, so it is never mistaken for a {@code .schematic}
     * file of the same name.
     */
    public static String toCommandName(final String path) {
        if (path.toLowerCase(Locale.ROOT)
            .endsWith(Constants.Files.EXTENSION_SCHEMATIC)) {
            return path.substring(0, path.length() - Constants.Files.EXTENSION_SCHEMATIC.length());
        }

        return path;
    }

    private static boolean hasSchematicExtension(final String name) {
        final String lowerName = name.toLowerCase(Locale.ROOT);
        return lowerName.endsWith(Constants.Files.EXTENSION_SCHEMATIC)
            || lowerName.endsWith(Constants.Files.EXTENSION_SCHEMPLUS);
    }

    /**
     * Finds the file a name means: the name itself when it has an extension, otherwise {@code .schematic} and then
     * {@code .schemplus}. A name that leads out of the directory is refused.
     */
    private static File resolve(final EntityPlayerMP player, final File directory, final String name) {
        final String[] candidates = hasSchematicExtension(name) ? new String[] { name }
            : new String[] { name + Constants.Files.EXTENSION_SCHEMATIC, name + Constants.Files.EXTENSION_SCHEMPLUS };

        for (String candidate : candidates) {
            if (!FileUtils.contains(directory, candidate)) {
                Reference.logger.error("{} has tried to paste the file {}", player.getDisplayName(), candidate);
                throw new CommandException(Names.Command.Paste.Message.NOT_FOUND, name);
            }

            final File file = new File(directory, candidate);
            if (file.isFile()) {
                return file;
            }
        }

        throw new CommandException(Names.Command.Paste.Message.NOT_FOUND, name);
    }
}
