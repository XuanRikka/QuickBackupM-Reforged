package io.github.skydynamic.quickbackupmulti.neoforge.events;

import io.github.skydynamic.quickbackupmulti.QuickbackupmultiReforged;
import io.github.skydynamic.quickbackupmulti.neoforge.QuickbackupmultiReforgedNeoForge;
import io.github.skydynamic.quickbackupmulti.event.OnServerStoppedHandler;
import io.github.skydynamic.quickbackupmulti.neoforge.ServerManagerNeoforge;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

@EventBusSubscriber(modid = QuickbackupmultiReforged.MOD_ID)
public class NeoForgeEvents {
    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        QuickbackupmultiReforgedNeoForge.getModContainer().setDispatcher(event.getDispatcher());
        QuickbackupmultiReforged.registerCommand();
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        QuickbackupmultiReforged.setServerManager(new ServerManagerNeoforge(event.getServer()));

        // suck ModifiableBiomeInfo & ModifiableStructureInfo
        if (QuickbackupmultiReforged.getModContainer().isRestoringBackup()) {
            QuickbackupmultiReforged.getModContainer().setRestoringBackup(false);
        }
    }

    // Single handler for BOTH dists. This used to be two methods split by @OnlyIn,
    // but NeoForge 26.x no longer strips @OnlyIn members at runtime — both would
    // stay registered and the restore state machine would run twice per stop.
    // The common handler is dist-agnostic anyway, matching the Fabric path.
    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        OnServerStoppedHandler.handle();
    }
}
