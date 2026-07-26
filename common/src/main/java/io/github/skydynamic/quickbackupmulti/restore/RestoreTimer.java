package io.github.skydynamic.quickbackupmulti.restore;

import io.github.skydynamic.quickbackupmulti.ModEnvType;
import io.github.skydynamic.quickbackupmulti.QuickbackupmultiReforged;
import io.github.skydynamic.quickbackupmulti.command.RestoreCommand;
import io.github.skydynamic.quickbackupmulti.utils.BackupManager;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;

public class RestoreTimer extends TimerTask {
    private final ModEnvType env;
    private final List<ServerPlayer> players;

    public RestoreTimer(ModEnvType env, List<ServerPlayer> players) {
        this.env = env;
        this.players = players;
    }

    @Override
    public void run() {
        // Wait for any in-flight backup to finish before tearing the server down —
        // stopping the server mid-backup tears blob files and permanently corrupts
        // the deduplicated store. Released by OnServerStoppedHandler (server env)
        // or by ClientRestoreDelegate's async task (client env).
        try {
            BackupManager.OPERATION_MUTEX.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            QuickbackupmultiReforged.logger.error("Restore aborted: interrupted while waiting for in-flight backup", e);
            return;
        }
        boolean handedOff = false;
        try {
            // Atomically claim the operation under the same monitor /qb cancel uses.
            // Whichever side removes the "QBM" entry wins: if cancel got there first
            // (it can land while this thread waits on the mutex, or in the final
            // instant of the countdown), back off completely — otherwise a cancelled
            // restore would still halt a dedicated server that then neither restores
            // nor restarts.
            ConcurrentHashMap<String, Object> claimed;
            synchronized (RestoreCommand.getRestoreDataMap()) {
                claimed = RestoreCommand.getRestoreDataMap().remove("QBM");
                if (claimed == null) {
                    BackupManager.OPERATION_MUTEX.release();
                    return;
                }
                QuickbackupmultiReforged.getModContainer().setRestoringBackup(true);
            }
            // Release the countdown machinery (also lets the non-daemon Timer thread
            // terminate instead of parking forever). Timer.cancel from within its own
            // task is explicitly allowed.
            ((Timer) claimed.get("Timer")).cancel();
            ((ScheduledExecutorService) claimed.get("Countdown")).shutdown();

            if (env == ModEnvType.SERVER) {
                for (ServerPlayer player : players) {
                    player.connection.disconnect(Component.literal("Server restore backup"));
                }
                QuickbackupmultiReforged.getServerManager().stopServer();
                handedOff = true; // mutex now released by OnServerStoppedHandler
            } else {
                new ClientRestoreDelegate().run();
                handedOff = true; // mutex now released by the delegate's async task
            }
        } catch (Throwable t) {
            QuickbackupmultiReforged.logger.error("Restore aborted unexpectedly before hand-off", t);
            if (!handedOff) {
                QuickbackupmultiReforged.getModContainer().setRestoringBackup(false);
                BackupManager.OPERATION_MUTEX.release();
            }
        }
    }
}
