package io.github.skydynamic.quickbackupmulti.event;

import io.github.skydynamic.quickbackupmulti.ModEnvType;
import io.github.skydynamic.quickbackupmulti.QuickbackupmultiReforged;
import io.github.skydynamic.quickbackupmulti.schedule.ScheduleManager;
import io.github.skydynamic.quickbackupmulti.utils.BackupManager;

public class OnServerStoppedHandler {
    public static void handle() {
        ScheduleManager.clearAllSchedule();
        if (QuickbackupmultiReforged.getModContainer().isRestoringBackup()) {
            QuickbackupmultiReforged.logger.info("[QBM-DBG] OnServerStoppedHandler — isRestoringBackup=true envType={} selection='{}'",
                QuickbackupmultiReforged.getModContainer().getEnvType(),
                QuickbackupmultiReforged.getModContainer().getCurrentSelectionBackup());
            if (QuickbackupmultiReforged.getModContainer().getEnvType() == ModEnvType.SERVER) {
                try {
                    String selection = QuickbackupmultiReforged.getModContainer().getCurrentSelectionBackup();
                    boolean storageExists = QuickbackupmultiReforged.getDatabase().storageExists(selection);
                    QuickbackupmultiReforged.logger.info("[QBM-DBG] OnServerStoppedHandler selection='{}' storageExistsByDb={}", selection, storageExists);
                    // Restoring "restore_temp" itself IS the rescue action: taking a
                    // temp backup first would overwrite the very storage being restored.
                    boolean restoringTemp = "restore_temp".equals(selection);
                    boolean tempReady = restoringTemp;
                    if (!restoringTemp) {
                        try {
                            BackupManager.makeTempBackup();
                            tempReady = true;
                        } catch (Exception e) {
                            // No rollback point could be created: abort while the world
                            // is still untouched instead of restoring without a net.
                            QuickbackupmultiReforged.logger.error("Temp backup failed — aborting restore, world left untouched", e);
                        }
                    }
                    if (tempReady) {
                        QuickbackupmultiReforged.logger.info("[QBM-DBG] OnServerStoppedHandler calling cleanSaveDirectory");
                        // Remove files the backup does not contain, otherwise the restore
                        // is an overlay merge leaving newer region/player files behind.
                        try {
                            BackupManager.cleanSaveDirectory();
                        } catch (Exception e) {
                            QuickbackupmultiReforged.logger.warn("Cleaning save directory failed; restore continues as overlay", e);
                        }
                        QuickbackupmultiReforged.logger.info("[QBM-DBG] OnServerStoppedHandler calling restoreBackup('{}')", selection);
                        boolean restoreResult = BackupManager.restoreBackup(selection);
                        QuickbackupmultiReforged.logger.info("[QBM-DBG] OnServerStoppedHandler restoreBackup('{}') result={}", selection, restoreResult);
                        if (!restoreResult) {
                            QuickbackupmultiReforged.logger.warn("Restore failed, try to restore from temp backup");
                            try {
                                BackupManager.cleanSaveDirectory();
                            } catch (Exception e) {
                                QuickbackupmultiReforged.logger.warn("Cleaning save directory before rollback failed", e);
                            }
                            boolean restoreTempResult = BackupManager.restoreBackup("restore_temp");
                            if (!restoreTempResult) {
                                QuickbackupmultiReforged.logger.error(
                                    "Restore from temp backup failed — 'restore_temp' now holds the only pre-restore copy; further restores are blocked until it is recovered (/qb restore restore_temp).");
                                BackupManager.markRescuePending();
                            } else {
                                QuickbackupmultiReforged.logger.info("Restore from temp backup success.");
                            }
                        } else {
                            BackupManager.clearRescuePending();
                        }
                    }
                } finally {
                    QuickbackupmultiReforged.logger.info("[QBM-DBG] OnServerStoppedHandler restore branch done — releasing mutex, afterRestarting=true; autoRestartMode={}",
                        QuickbackupmultiReforged.getModConfig().getAutoRestartMode());
                    QuickbackupmultiReforged.getModContainer().setRestoringBackup(false);
                    QuickbackupmultiReforged.getModContainer().setAfterRestarting(true);
                    BackupManager.OPERATION_MUTEX.release();
                }
                // The restart MUST happen after the mutex release above: with the
                // DEFAULT mode, startServer() re-enters the blocking server main loop,
                // so anything after it (a finally release included) only runs when the
                // RESTARTED server eventually stops — holding the permit for its whole
                // uptime would silently reject every backup and deadlock re-restores.
                switch (QuickbackupmultiReforged.getModConfig().getAutoRestartMode()) {
                    case DISABLE -> {
                    }
                    case DEFAULT -> QuickbackupmultiReforged.getServerManager().startServer();
                    case MCSM -> new Thread(() -> System.exit(1)).start();
                }
            }
        } else {
            QuickbackupmultiReforged.getDatabase().closeDatabase();
        }
    }
}
