package io.github.skydynamic.quickbackupmulti.restore;

import io.github.skydynamic.quickbackupmulti.QuickbackupmultiReforged;
import io.github.skydynamic.quickbackupmulti.client.screen.RestoreScreen;
import io.github.skydynamic.quickbackupmulti.translate.Translate;
import io.github.skydynamic.quickbackupmulti.utils.BackupManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.storage.LevelStorageSource;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class ClientRestoreDelegate {
    private final RestoreScreen screen = new RestoreScreen(this::cancel);
    private final AtomicBoolean isCancelled = new AtomicBoolean(false);

    protected final Minecraft minecraftClient = Minecraft.getInstance();
    String levelId = QuickbackupmultiReforged.getModContainer().getLevelId();

    public void run() {
        long startTime = System.currentTimeMillis();

        // Tracks whether the live save has already been touched: recovery on failure
        // differs by phase (before deletion the world is intact and must not be touched).
        AtomicBoolean worldDeleted = new AtomicBoolean(false);

        // Everything fallible lives INSIDE the async body so the finally below is the
        // single owner of the mutex release — RestoreTimer acquired the permit and
        // hands ownership to this task the moment it is submitted.
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            CompletableFuture.runAsync(() -> {
                try {
                    // Leave the world on the render thread. The player may have already
                    // quit to the title screen during the countdown — in that case there
                    // is nothing to disconnect, just show the progress screen.
                    minecraftClient.executeBlocking(() -> {
                        if (minecraftClient.level != null) {
                            minecraftClient.level.disconnect(ClientLevel.DEFAULT_QUIT_MESSAGE);
                            minecraftClient.disconnect(screen, false);
                        } else {
                            minecraftClient.gui.setScreen(screen);
                        }
                    });

                    String selection = QuickbackupmultiReforged.getModContainer().getCurrentSelectionBackup();
                    // Restoring "restore_temp" itself IS the rescue action: taking a temp
                    // backup first would overwrite the very storage being restored.
                    boolean restoringTemp = "restore_temp".equals(selection);

                    screen.setState(Translate.tr("quickbackupmulti.restoring_backup.state.make_temp_backup"));
                    screen.setProgress(0.05f);
                    if (!restoringTemp) {
                        BackupManager.makeTempBackup();
                    }

                    screen.setState(Translate.tr("quickbackupmulti.restoring_backup.state.delete_origin_save"));
                    screen.setProgress(0.1f);
                    if (!deleteWorld()) {
                        // Deletion may have been partial — recover from the temp backup.
                        handleFailure(true);
                        return;
                    }
                    worldDeleted.set(true);

                    if (isCancelled.get()) {
                        handleCancellation();
                        return;
                    }

                    BackupManager.RestoreExtraRunnable extraRunnable = (totalProgress, currentProgress) -> {
                        screen.setState(Translate.tr("quickbackupmulti.restoring_backup.state.restoring_backup"));
                        screen.setProgress((float) currentProgress / totalProgress * 0.9f);
                    };
                    boolean restoreResult = BackupManager.restoreBackup(selection, extraRunnable);
                    if (!restoreResult) {
                        QuickbackupmultiReforged.logger.error("Restore failed, rolling back to temp backup");
                        handleFailure(true);
                        return;
                    }

                    if (isCancelled.get()) {
                        handleCancellation();
                        return;
                    }

                    // Restore finish and rejoin the world
                    BackupManager.clearRescuePending();
                    QuickbackupmultiReforged.getModContainer().setRestoringBackup(false);
                    long endTime = System.currentTimeMillis();
                    minecraftClient.execute(() -> {
                        Component title = Component.nullToEmpty(Translate.tr("quickbackupmulti.toast.end_title"));
                        Component desc = Component.nullToEmpty(Translate.tr("quickbackupmulti.toast.end_desc", (int) ((endTime - startTime)) / 1000));
                        SystemToast.addOrUpdate(minecraftClient.gui.toastManager(), SystemToast.SystemToastId.PERIODIC_NOTIFICATION, title, desc);
                    });
                    if (QuickbackupmultiReforged.getModConfig().isClientAutoReJoinWorld()) {
                        minecraftClient.execute(() -> minecraftClient.createWorldOpenFlows().openWorld(levelId,
                            () -> minecraftClient.gui.setScreen(null)));
                    } else {
                        minecraftClient.execute(() -> minecraftClient.gui.setScreen(null));
                    }
                } catch (Throwable t) {
                    QuickbackupmultiReforged.logger.error("Unexpected error during client restore", t);
                    handleFailure(worldDeleted.get());
                } finally {
                    BackupManager.OPERATION_MUTEX.release();
                }
            }, executor);
        } finally {
            // shutdown() lets the already-submitted task run to completion, then the
            // worker thread exits — no thread leak, no impact on the running restore.
            executor.shutdown();
        }
    }

    /**
     * Recovery for a failed restore. When {@code worldDeleted}, the live save is
     * gone or partial: clear it and re-materialize the pre-restore state from the
     * temp backup. The user is always informed and never left on the unclosable
     * RestoreScreen.
     */
    private void handleFailure(boolean worldDeleted) {
        boolean recovered = false;
        try {
            if (worldDeleted) {
                screen.setState(Translate.tr("quickbackupmulti.restoring_backup.state.restore_temp_backup"));
                deleteWorld();
                // storageExists guard: a pruned/missing temp storage yields an empty
                // hash map, which would make restoreBackup "succeed" restoring nothing.
                recovered = QuickbackupmultiReforged.getDatabase().storageExists("restore_temp")
                    && BackupManager.restoreBackup("restore_temp");
            }
        } catch (Exception e) {
            QuickbackupmultiReforged.logger.error("Error while recovering from failed restore", e);
        }
        if (worldDeleted && !recovered) {
            QuickbackupmultiReforged.logger.error(
                "Recovery failed — the pre-restore world is kept as the 'restore_temp' backup; recover it with /qb restore restore_temp. Further restores are blocked until then.");
            BackupManager.markRescuePending();
        }
        QuickbackupmultiReforged.getModContainer().setRestoringBackup(false);
        boolean intactOrRecovered = !worldDeleted || recovered;
        minecraftClient.execute(() -> {
            Component title = Component.nullToEmpty(Translate.tr("quickbackupmulti.toast.fail_title"));
            Component desc = Component.nullToEmpty(Translate.tr(!worldDeleted
                ? "quickbackupmulti.toast.fail_intact_desc"
                : (intactOrRecovered ? "quickbackupmulti.toast.fail_recovered_desc" : "quickbackupmulti.toast.fail_not_recovered_desc")));
            SystemToast.addOrUpdate(minecraftClient.gui.toastManager(), SystemToast.SystemToastId.PERIODIC_NOTIFICATION, title, desc);
            minecraftClient.gui.setScreen(null);
        });
    }

    private void handleCancellation() {
        boolean tempOk;
        try {
            screen.setState(Translate.tr("quickbackupmulti.restoring_backup.state.delete_origin_save"));
            if (!deleteWorld()) {
                QuickbackupmultiReforged.logger.warn("Old save not fully deleted before rollback; stale files may remain");
            }
            screen.setState(Translate.tr("quickbackupmulti.restoring_backup.state.restore_temp_backup"));
            tempOk = QuickbackupmultiReforged.getDatabase().storageExists("restore_temp")
                && BackupManager.restoreBackup("restore_temp");
        } catch (Exception e) {
            QuickbackupmultiReforged.logger.error("Error during cancellation", e);
            tempOk = false;
        }
        if (!tempOk) {
            BackupManager.markRescuePending();
        }
        QuickbackupmultiReforged.getModContainer().setRestoringBackup(false);
        boolean ok = tempOk;
        minecraftClient.execute(() -> {
            Component title;
            Component desc;
            if (ok) {
                title = Component.nullToEmpty(Translate.tr("quickbackupmulti.toast.cancel_success"));
                desc = Component.nullToEmpty(Translate.tr("quickbackupmulti.toast.cancel_success.desc"));
            } else {
                title = Component.nullToEmpty(Translate.tr("quickbackupmulti.toast.fail_title"));
                desc = Component.nullToEmpty(Translate.tr("quickbackupmulti.toast.fail_not_recovered_desc"));
            }
            SystemToast.addOrUpdate(minecraftClient.gui.toastManager(), SystemToast.SystemToastId.PERIODIC_NOTIFICATION, title, desc);
            minecraftClient.gui.setScreen(null);
        });
    }

    public void cancel(Button button) {
        isCancelled.set(true);

        if (button != null) {
            screen.setState(Translate.tr("quickbackupmulti.restoring_backup.state.cancel"));
            button.active = false;
        }

        QuickbackupmultiReforged.getModContainer().setRestoringBackup(false);
    }

    /**
     * Clear the live save so the restore reproduces the backup exactly. Deliberately
     * NOT {@code LevelStorageAccess.deleteLevel()}: that would also wipe ignored
     * files/folders (e.g. a user's dynmap directory), which exist in NO backup and
     * would be unrecoverable. cleanSaveDirectory honors the same ignore lists the
     * backups use, matching the dedicated-server restore path. The storage access is
     * held purely for its session lock while the directory is being modified.
     */
    private boolean deleteWorld() {
        try (LevelStorageSource.LevelStorageAccess ignored = minecraftClient.getLevelSource().createAccess(levelId)) {
            BackupManager.cleanSaveDirectory();
            return true;
        } catch (IOException e) {
            QuickbackupmultiReforged.logger.error("Error during delete level", e);
            return false;
        }
    }
}
