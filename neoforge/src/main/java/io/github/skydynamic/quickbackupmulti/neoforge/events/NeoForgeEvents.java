package io.github.skydynamic.quickbackupmulti.neoforge.events;

import io.github.skydynamic.quickbackupmulti.QuickbackupmultiReforged;
import io.github.skydynamic.quickbackupmulti.neoforge.QuickbackupmultiReforgedNeoForge;
import io.github.skydynamic.quickbackupmulti.event.OnServerStoppedHandler;
import io.github.skydynamic.quickbackupmulti.neoforge.ServerManagerNeoforge;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
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

    @OnlyIn(Dist.DEDICATED_SERVER)
    @SubscribeEvent
    public static void onDedicatedServerStopped(ServerStoppedEvent event) {
        // Route ALL modes through the common handler, matching Fabric: the old
        // DEFAULT-mode special case called restoreBackup() bare — no schedule
        // teardown, no temp backup (no rollback point), and the restore result
        // was ignored before relaunching the server on a possibly broken save.
        OnServerStoppedHandler.handle();
    }

    @OnlyIn(Dist.CLIENT)
    @SubscribeEvent
    public static void onIntegratedServerStopped(ServerStoppedEvent event) {
        OnServerStoppedHandler.handle();
    }
}
