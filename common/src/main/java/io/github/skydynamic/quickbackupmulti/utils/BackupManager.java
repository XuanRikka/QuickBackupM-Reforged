package io.github.skydynamic.quickbackupmulti.utils;

import io.github.skydynamic.increment.storage.lib.database.Database;
import io.github.skydynamic.increment.storage.lib.database.DatabaseTables;
import io.github.skydynamic.increment.storage.lib.database.StorageInfo;
import io.github.skydynamic.quickbackupmulti.DatabaseCache;
import io.github.skydynamic.quickbackupmulti.QuickbackupmultiReforged;
import io.github.skydynamic.quickbackupmulti.database.DatabaseManager;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.filefilter.IOFileFilter;
import org.apache.commons.io.filefilter.NameFileFilter;
import org.apache.commons.io.filefilter.NotFileFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import static io.github.skydynamic.quickbackupmulti.translate.Translate.tr;

public class BackupManager {
    private static final Logger logger = LoggerFactory.getLogger("Qbm-BackupManager");

    /**
     * Single global mutex for every backup/restore operation. A Semaphore (not a
     * ReentrantLock) on purpose: the restore path acquires it on the restore-countdown
     * timer thread and releases it on a different thread (server thread / restore
     * worker) once the restore finishes. Without this, a scheduled backup firing
     * mid-backup or mid-restore tears region files inside the deduplicated blob store,
     * poisoning every later backup that dedups against them.
     */
    public static final Semaphore OPERATION_MUTEX = new Semaphore(1);

    /**
     * Marker file: set when a restore's rollback ALSO failed, i.e. the
     * "restore_temp" storage is the only surviving copy of the pre-restore world.
     * While present, arming a new restore is refused (a new restore would start by
     * overwriting restore_temp via makeTempBackup). Cleared on any successful
     * restore or rollback.
     */
    private static final String RESCUE_MARKER = "restore_temp.rescue";

    static {
        // The 26.x client shutdown watchdog kills a lingering JVM via System.exit,
        // which runs shutdown hooks first. Waiting on the operation mutex here lets
        // an in-flight backup/restore finish instead of being killed mid-copy —
        // a truncated blob is named by its full-content hash and would silently
        // poison every later deduplicated backup.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                if (!OPERATION_MUTEX.tryAcquire(60, TimeUnit.SECONDS)) {
                    logger.warn("JVM exiting while a backup/restore is still running after 60s — the backup store may be incomplete");
                }
            } catch (InterruptedException ignored) {
            }
        }, "QBM-ShutdownGuard"));
    }

    public static boolean isRescuePending() {
        return Files.exists(getBackupPath().resolve(RESCUE_MARKER));
    }

    public static void markRescuePending() {
        try {
            Files.writeString(getBackupPath().resolve(RESCUE_MARKER),
                "A restore failed AND its rollback failed. The 'restore_temp' storage holds the only copy of the pre-restore world.\n"
                    + "Recover it with: /qb restore restore_temp (or export it with /qb export restore_temp).\n"
                    + "This marker is cleared automatically on a successful restore.\n");
        } catch (IOException e) {
            logger.error("Failed to write rescue marker", e);
        }
    }

    public static void clearRescuePending() {
        try {
            Files.deleteIfExists(getBackupPath().resolve(RESCUE_MARKER));
        } catch (IOException e) {
            logger.error("Failed to clear rescue marker", e);
        }
    }
    private static final IOFileFilter folderFilter = new NotFileFilter(new NameFileFilter(QuickbackupmultiReforged.getModConfig().getIgnoredFolders()));
    private static final IOFileFilter fileFilter = new NotFileFilter(new NameFileFilter(QuickbackupmultiReforged.getModConfig().getIgnoredFiles()));

    public static Path getBackupPath() {
        Path path = Path.of(QuickbackupmultiReforged.getModConfig().getStoragePath()).resolve(QuickbackupmultiReforged.getModContainer().getLevelId());
        if (!Files.exists(path) || !Files.isDirectory(path)) {
            try {
                Files.createDirectories(path);
            } catch (IOException e) {
                logger.error("Create backup path error: {}", e.getMessage());
            }
        }
        return path;
    }

    public static List<StorageInfo> getBackupsList() {
        List<StorageInfo> backupList;
        if (!QuickbackupmultiReforged.getModConfig().isCacheDatabase()) {
            backupList = QuickbackupmultiReforged.getDatabase()
                .getAllStorageInfo()
                .stream()
                .filter(StorageInfo::getUseIncrementalStorage)
                .toList();
        } else {
            backupList = DatabaseCache.getStorageInfoCaches();
        }
        return backupList;
    }

    public static List<StorageInfo> getSortedBackups() {
        return getBackupsList().stream()
            .sorted(Comparator.comparingLong(StorageInfo::getTimestamp))
            .toList();
    }

    // Tab-completion cache: suggestion callbacks run on the server thread on EVERY
    // keystroke, and each getSortedBackups() is a full H2 table scan + sort. A short
    // TTL bounds staleness while make/delete invalidate it for immediate freshness.
    private static volatile List<StorageInfo> suggestionCache;
    private static volatile long suggestionCacheTimestamp;
    private static final long SUGGESTION_CACHE_TTL_MS = 3000;

    public static List<StorageInfo> getSuggestionBackups() {
        List<StorageInfo> cached = suggestionCache;
        if (cached != null && System.currentTimeMillis() - suggestionCacheTimestamp < SUGGESTION_CACHE_TTL_MS) {
            return cached;
        }
        List<StorageInfo> fresh = getSortedBackups();
        suggestionCache = fresh;
        suggestionCacheTimestamp = System.currentTimeMillis();
        return fresh;
    }

    public static void invalidateSuggestionCache() {
        suggestionCache = null;
    }

    public static StorageInfo getBackupByIndex(int index) {
        List<StorageInfo> backups = getSortedBackups();
        if (index < 1 || index > backups.size()) {
            return null;
        }
        return backups.get(index - 1);
    }

    public static int getBackupIndex(String name) {
        List<StorageInfo> backups = getSortedBackups();
        for (int i = 0; i < backups.size(); i++) {
            if (backups.get(i).getName().equals(name)) {
                return i + 1;
            }
        }
        return -1;
    }

    private static void makeFullBackup(CommandSourceStack commandSource) {
        if (QuickbackupmultiReforged.getModConfig().getFullBackupInterval() == -1) {
            return;
        }

        boolean shouldMakeFullBackup;
        if (!getBackupPath().resolve("full").toFile().exists()) {
            logger.info("Do not have a full backup, make a full backup for future use...");
            shouldMakeFullBackup = true;
        } else {
            List<StorageInfo> storageInfoList = QuickbackupmultiReforged.getDatabase().getAllStorageInfo();
            List<StorageInfo> incrementalBackups = storageInfoList.stream().filter(StorageInfo::getUseIncrementalStorage).toList();
            List<StorageInfo> fullBackups = storageInfoList.stream().filter(it -> !it.getUseIncrementalStorage()).toList();
            shouldMakeFullBackup = !incrementalBackups.isEmpty()
                && incrementalBackups.size() % QuickbackupmultiReforged.getModConfig().getFullBackupInterval() == 0;
            if (shouldMakeFullBackup && fullBackups.size() >= QuickbackupmultiReforged.getModConfig().getSaveFullBackupCount()) {
                fullBackups.stream().min(Comparator.comparingLong(StorageInfo::getTimestamp))
                    .ifPresent(oldestFullBackup -> {
                        try {
                            logger.info("Delete oldest full backup: {}", oldestFullBackup.getName());
                            FileUtils.deleteDirectory(getBackupPath().resolve("full").resolve(oldestFullBackup.getName()).toFile());
                        } catch (IOException e) {
                            logger.error("delete oldest full backup failed: ", e);
                        }
                    });
            }
        }
        if (!shouldMakeFullBackup) {
            return;
        }
        // The full backup copies the whole live world (minutes for big saves) while
        // still holding the operation mutex inside the noSave window — deliberately:
        // it is the disaster-recovery copy that must NOT depend on the blob store or
        // the database being healthy. Without this message the pause reads as a hang.
        commandSource.sendSystemMessage(Component.nullToEmpty(tr("quickbackupmulti.make.full_backup.start")));
        logger.info("Make a full backup for future use...");
        long startTime = System.currentTimeMillis();
        QuickbackupmultiReforged.getManager().fullStorage(
            "FullBackup-" + (QuickbackupmultiReforged.getModContainer().getLevelId().isEmpty() ? "Server" : QuickbackupmultiReforged.getModContainer().getLevelId()),
            "Full backup",
            QuickbackupmultiReforged.getModContainer().getCurrentSavePath().toFile(),
            fileFilter,
            folderFilter
        );
        double intervalTime = (System.currentTimeMillis() - startTime) / 1000.0;
        commandSource.sendSystemMessage(Component.nullToEmpty(tr("quickbackupmulti.make.full_backup.success", intervalTime)));
    }

    public static void makeBackup(CommandSourceStack commandSource, String name, String desc) {
        if (QuickbackupmultiReforged.getModContainer().isRestoringBackup()) {
            commandSource.sendSystemMessage(Component.nullToEmpty(tr("quickbackupmulti.restore.busy")));
            return;
        }
        if (!OPERATION_MUTEX.tryAcquire()) {
            commandSource.sendSystemMessage(Component.nullToEmpty(tr("quickbackupmulti.restore.busy")));
            return;
        }
        try {
            if (QuickbackupmultiReforged.getDatabase().storageExists(name)) {
                commandSource.sendSystemMessage(Component.nullToEmpty(tr("quickbackupmulti.make.fail_exists")));
                return;
            }
            long startTime = System.currentTimeMillis();
            MinecraftServer server = commandSource.getServer();
            // Levels whose noSave flag THIS invocation flipped; mutated/read only on the
            // server thread via executeBlocking, so the finally below can restore exactly
            // them — a failed backup must never leave noSave=true (world would silently
            // stop saving forever), nor clobber noSave flags owned by other mods.
            List<ServerLevel> flippedLevels = new ArrayList<>();
            boolean incrementalOk = false;
            try {
                commandSource.sendSystemMessage(Component.nullToEmpty(tr("quickbackupmulti.make.start")));
                server.executeBlocking(() -> {
                    server.saveEverything(true, true, true);
                    for (ServerLevel serverLevel : server.getAllLevels()) {
                        if (serverLevel == null || serverLevel.noSave) continue;
                        serverLevel.noSave = true;
                        flippedLevels.add(serverLevel);
                    }
                });

                QuickbackupmultiReforged.getManager().incrementalStorage(
                    name,
                    desc,
                    QuickbackupmultiReforged.getModContainer().getCurrentSavePath().toFile(),
                    fileFilter,
                    folderFilter
                );
                incrementalOk = true;
                invalidateSuggestionCache();

                long endTime = System.currentTimeMillis();
                double intervalTime = (endTime - startTime) / 1000.0;
                commandSource.sendSystemMessage(Component.nullToEmpty(tr("quickbackupmulti.make.success", intervalTime)));
            } catch (Exception e) {
                logger.error("Make Backup Failed", e);
                commandSource.sendSystemMessage(Component.nullToEmpty(tr("quickbackupmulti.make.fail",  e.toString())));
            } finally {
                try {
                    // The full backup copies the live save directory, so it MUST run
                    // while noSave is still set: with saving re-enabled, region files
                    // get rewritten mid-copy and the disaster-recovery snapshot ends
                    // up torn. Skip it entirely when the incremental phase failed
                    // (the save dir may not even be flushed).
                    if (incrementalOk) {
                        try {
                            makeFullBackup(commandSource);
                        } catch (Exception e) {
                            logger.error("Full backup failed", e);
                            commandSource.sendSystemMessage(Component.nullToEmpty(tr("quickbackupmulti.make.full_backup.fail", e.toString())));
                        }
                    }
                } finally {
                    try {
                        server.executeBlocking(() -> {
                            for (ServerLevel serverLevel : flippedLevels) {
                                serverLevel.noSave = false;
                            }
                        });
                    } catch (Exception e) {
                        logger.error("Failed to re-enable world saving after backup; autosave may stay disabled until server stop", e);
                    }
                }
            }
        } finally {
            OPERATION_MUTEX.release();
        }
    }

    public static void makeTempBackup() {
        logger.info("Make a temp backup...");
        QuickbackupmultiReforged.getManager().incrementalStorageTemp(
            QuickbackupmultiReforged.getModContainer().getCurrentSavePath().toFile(), fileFilter, folderFilter
        );
        logger.info("Make a temp backup success.");
    }

    public static boolean deleteBackup(CommandSourceStack commandSource, String name) {
        if (QuickbackupmultiReforged.getDatabase().storageExists(name)) {
            QuickbackupmultiReforged.getManager().deleteStorage(name);
            invalidateSuggestionCache();
            return true;
        } else {
            return false;
        }
    }

    /**
     * Reconstruct a backup's files from the hash-deduplicated blob store into {@code targetRoot}.
     * This is the shared logic behind both restore (target = live world) and export (target = arbitrary directory).
     */
    private static boolean reconstructBackup(String name, Path targetRoot, RestoreExtraRunnable extraRunnable) {
        Map<String, String> hashMap = QuickbackupmultiReforged.getDatabase().getFileHashMap(name);
        // Hoisted out of the loop: getBackupPath() stats (and may create) the
        // directory on every call, which used to run twice per restored file.
        Path backupPath = getBackupPath();
        Path blobsDir = backupPath.resolve("blogs");
        Path tempBlobsDir = backupPath.resolve("blogs_temp");
        int total = hashMap.size();
        java.util.concurrent.atomic.AtomicInteger done = new java.util.concurrent.atomic.AtomicInteger();
        logger.info("[QBM-DBG] reconstructBackup name='{}' targetRoot='{}' totalFiles={} backupPath='{}'", name, targetRoot, total, backupPath);
        logger.info("[QBM-DBG] reconstructBackup databasePath(exposed)='{}' managerStoragePath='{}' levelId='{}'",
            QuickbackupmultiReforged.getDatabase() != null ? "<db-ok>" : "<db-null>",
            QuickbackupmultiReforged.getModConfig().getStoragePath(),
            QuickbackupmultiReforged.getModContainer().getLevelId());
        // If the hash map is empty the restore would silently produce an empty world.
        if (total == 0) {
            logger.warn("[QBM-DBG] reconstructBackup name='{}' got EMPTY file hash map — restore will produce an empty/initial world!", name);
        }
        // World reconstruction is many small independent file copies — parallelizing
        // them cuts restore wall-time severalfold on SSDs. Worker count is modest so
        // HDD users aren't hurt by seek thrash.
        int threads = Math.min(4, Math.max(1, Runtime.getRuntime().availableProcessors() / 2));
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "QBM-Reconstruct");
            t.setDaemon(false);
            return t;
        });
        try {
            List<java.util.concurrent.Future<?>> futures = new ArrayList<>(total);
            int missingBlobs = 0;
            for (Map.Entry<String, String> entry : hashMap.entrySet()) {
                String fileHash = entry.getKey();
                String fileName = entry.getValue();
                File hashFile;
                if (fileHash.startsWith("blog_temp")) {
                    hashFile = tempBlobsDir.resolve(fileHash).toFile();
                } else {
                    hashFile = blobsDir.resolve(fileHash.substring(0, 2)).resolve(fileHash).toFile();
                }
                if (!hashFile.exists()) {
                    missingBlobs++;
                    logger.warn("[QBM-DBG] reconstructBackup MISSING blob for fileName='{}' hash='{}' expectedAt='{}'", fileName, fileHash, hashFile);
                }
                futures.add(pool.submit(() -> {
                    FileUtils.copyFile(hashFile, targetRoot.resolve(fileName).toFile());
                    if (extraRunnable != null) {
                        extraRunnable.execute(total, done.incrementAndGet());
                    }
                    return null;
                }));
            }
            for (java.util.concurrent.Future<?> future : futures) {
                future.get();
            }
            logger.info("[QBM-DBG] reconstructBackup name='{}' DONE: copied={} missingBlobs={}", name, done.get(), missingBlobs);
            return true;
        } catch (Exception e) {
            logger.error("Reconstruct backup failed", e);
            return false;
        } finally {
            // Callers treat a false return as "target is in an unknown state" and roll
            // back, so make sure no copy task is still writing when we hand back control.
            pool.shutdownNow();
            try {
                if (!pool.awaitTermination(60, TimeUnit.SECONDS)) {
                    logger.warn("Reconstruct worker pool did not terminate within 60s");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public static boolean restoreBackup(String name, RestoreExtraRunnable extraRunnable) {
        Path savePath = QuickbackupmultiReforged.getModContainer().getCurrentSavePath();
        return reconstructBackup(name, savePath, extraRunnable);
    }

    public static boolean restoreBackup(String name) {
        return restoreBackup(name, null);
    }

    /**
     * Export (reconstruct) a backup into {@code targetDir} as plain world files.
     *
     * @return {@code true} on success, {@code false} if the backup does not exist or reconstruction failed.
     */
    public static boolean exportBackup(String name, Path targetDir) {
        if (!QuickbackupmultiReforged.getDatabase().storageExists(name)) {
            return false;
        }
        try {
            Files.createDirectories(targetDir);
        } catch (IOException e) {
            logger.error("Create export directory failed", e);
            return false;
        }
        return reconstructBackup(name, targetDir, null);
    }

    public static void deleteWorld(String worldName) {
        // The deletion target MUST come from the parameter, never from the tracked
        // levelId (getBackupPath()): before any world is joined levelId is "" and
        // Path.resolve("") is the storage ROOT — deleting it would wipe the backups
        // of every world on disk.
        if (worldName == null || worldName.isBlank()) {
            logger.warn("Refusing to delete backups: empty world name");
            return;
        }
        Path storageRoot = Path.of(QuickbackupmultiReforged.getModConfig().getStoragePath()).toAbsolutePath().normalize();
        Path worldBackupPath = storageRoot.resolve(worldName).normalize();
        if (worldBackupPath.equals(storageRoot) || !worldBackupPath.startsWith(storageRoot)) {
            logger.warn("Refusing to delete backups: resolved path {} escapes storage root {}", worldBackupPath, storageRoot);
            return;
        }
        DatabaseManager databaseManager = new DatabaseManager(
            "QuickBackupMulti",
            QuickbackupmultiReforged.getModConfig().getStoragePath(),
            UUID.nameUUIDFromBytes(worldName.getBytes())
        );
        Database database = new Database(databaseManager);
        try {
            FileUtils.deleteDirectory(worldBackupPath.toFile());
            List<StorageInfo> storageInfoList = database.getAllStorageInfo();
            for (StorageInfo storageInfo : storageInfoList) {
                database.deleteTableValue(storageInfo.getName(), DatabaseTables.FILE_HASH);
                database.deleteTableValue(storageInfo.getName(), DatabaseTables.STORAGE_INFO);
            }
        } catch (IOException e) {
            logger.error("Delete Failed", e);
        } finally {
            database.closeDatabase();
        }
    }

    /**
     * Delete everything in the live save directory except the entries the backup
     * filters ignore (session.lock etc.), so a following restore reproduces the
     * backup exactly instead of overlay-merging onto newer files. The ignore lists
     * MUST be honored: ignored entries are absent from backups, so deleting them
     * would destroy data with no copy anywhere.
     */
    public static void cleanSaveDirectory() throws IOException {
        Path savePath = QuickbackupmultiReforged.getModContainer().getCurrentSavePath();
        logger.info("[QBM-DBG] cleanSaveDirectory savePath='{}' exists={}", savePath, Files.exists(savePath));
        List<String> ignoredFiles = QuickbackupmultiReforged.getModConfig().getIgnoredFiles();
        List<String> ignoredFolders = QuickbackupmultiReforged.getModConfig().getIgnoredFolders();
        int[] deleted = {0};
        Files.walkFileTree(savePath, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (!dir.equals(savePath) && ignoredFolders.contains(dir.getFileName().toString())) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (!ignoredFiles.contains(file.getFileName().toString())) {
                    Files.delete(file);
                    deleted[0]++;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                if (exc != null) throw exc;
                if (!dir.equals(savePath)) {
                    try (var entries = Files.list(dir)) {
                        if (entries.findFirst().isEmpty()) {
                            Files.delete(dir);
                        }
                    }
                }
                return FileVisitResult.CONTINUE;
            }
        });
        boolean levelDatExists = Files.exists(savePath.resolve("level.dat"));
        logger.info("[QBM-DBG] cleanSaveDirectory DONE: deletedFiles={} level.dat.still.exists={} savePathAfter='{}'", deleted[0], levelDatExists, savePath);
    }

    @FunctionalInterface
    public interface RestoreExtraRunnable {
        void execute(int totalProgress, int currentProgress);
    }
}
