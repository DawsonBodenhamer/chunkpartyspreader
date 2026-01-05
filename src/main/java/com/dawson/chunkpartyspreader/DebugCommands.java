package com.dawson.chunkpartyspreader;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Provides the /cps_sim command suite for synthetic player testing.
 */
@Mod.EventBusSubscriber(modid = ChunkPartySpreader.MODID)
public class DebugCommands {

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        register(event.getDispatcher());
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("cps_sim")
                .requires(s -> s.hasPermission(2)) // OPs only

                // Sub-command: /cps_sim join_fake <name>
                .then(Commands.literal("join_fake")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(DebugCommands::simulateJoin)))

                // Sub-command: /cps_sim reset_data
                .then(Commands.literal("reset_data")
                        .executes(DebugCommands::resetData))

                // Sub-command: /cps_sim status
                .then(Commands.literal("status")
                        .executes(DebugCommands::status))
        );
    }

    private static int simulateJoin(CommandContext<CommandSourceStack> context) {
        String name = StringArgumentType.getString(context, "name");
        ServerLevel level = context.getSource().getLevel();

        // Generate a consistent UUID based on the name to test persistence
        UUID fakeId = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
        GameProfile profile = new GameProfile(fakeId, name);

        // Create the synthetic player
        ServerPlayer fakePlayer = FakePlayerFactory.get(level, profile);

        // Force the fake player to the origin initially to ensure we aren't just seeing the command author's coords
        fakePlayer.setPosRaw(0, 100, 0);

        context.getSource().sendSuccess(() ->
                Component.literal("--------------------------------------------------").withStyle(ChatFormatting.GOLD), false);
        context.getSource().sendSuccess(() ->
                Component.literal("Simulating Join for: " + name).withStyle(ChatFormatting.YELLOW), false);
        context.getSource().sendSuccess(() ->
                Component.literal("UUID: " + fakeId).withStyle(ChatFormatting.GRAY), false);

        // --- EXECUTE LOGIC ---
        SpreaderEvents.processPlayerJoin(fakePlayer);

        // --- REPORT RESULTS ---
        SpreaderWorldData data = SpreaderWorldData.get(level);
        BlockPos assignment = data.getAssignment(fakeId);
        boolean isPending = SpreaderEvents.isPending(fakeId);

        if (assignment != null) {
            context.getSource().sendSuccess(() ->
                    Component.literal("✔ Assignment Exists: " + assignment.toShortString())
                            .withStyle(ChatFormatting.GREEN), false);

            // Since the player is a FakePlayer, teleportTo doesn't update position immediately,
            // so manually update it here just for the sake of the report matching the logic.
            fakePlayer.setPosRaw(assignment.getX() + 0.5, 320, assignment.getZ() + 0.5);

            String loc = String.format("%.1f, %.1f, %.1f", fakePlayer.getX(), fakePlayer.getY(), fakePlayer.getZ());
            context.getSource().sendSuccess(() ->
                    Component.literal("✔ Entity Location: " + loc).withStyle(ChatFormatting.AQUA), false);
        } else {
            context.getSource().sendSuccess(() ->
                    Component.literal("✘ Failed to assign home chunk.").withStyle(ChatFormatting.RED), false);
        }

        if (isPending) {
            context.getSource().sendSuccess(() ->
                    Component.literal("✔ Stasis Mode: ACTIVE (Added to pending map)").withStyle(ChatFormatting.GREEN), false);
        } else {
            context.getSource().sendSuccess(() ->
                    Component.literal("ℹ Stasis Mode: INACTIVE (Player was not added to wait list)").withStyle(ChatFormatting.YELLOW), false);
        }

        context.getSource().sendSuccess(() ->
                Component.literal("--------------------------------------------------").withStyle(ChatFormatting.GOLD), false);

        return 1;
    }

    private static int resetData(CommandContext<CommandSourceStack> context) {
        ServerLevel level = context.getSource().getLevel();
        SpreaderWorldData.get(level).reset();
        context.getSource().sendSuccess(() ->
                Component.literal("CPS Data has been wiped. Spiral Index reset to 0.").withStyle(ChatFormatting.RED), true);
        return 1;
    }

    private static int status(CommandContext<CommandSourceStack> context) {
        ServerLevel level = context.getSource().getLevel();
        SpreaderWorldData data = SpreaderWorldData.get(level);
        int idx = data.getCurrentSpiralIndex();

        context.getSource().sendSuccess(() ->
                Component.literal("Current Spiral Index: " + idx).withStyle(ChatFormatting.GOLD), false);
        return 1;
    }
}