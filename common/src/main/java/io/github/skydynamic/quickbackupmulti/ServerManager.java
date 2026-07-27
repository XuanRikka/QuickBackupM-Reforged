package io.github.skydynamic.quickbackupmulti;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.PacketProcessor;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerConnectionListener;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.SavedDataStorage;
import net.minecraft.world.level.validation.ContentValidationException;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class ServerManager {
    private final MinecraftServer server;

    public ServerManager(MinecraftServer server) {
        this.server = server;
    }

    public void startServer() {
        try {
            QuickbackupmultiReforged.logger.info("[QBM-DBG] ServerManager.startServer BEGIN — reused MinecraftServer={}, running-before={}", this.server, this.server.running);
            this.server.running = true;
            this.server.stopped = false;
            this.server.connection = new ServerConnectionListener(this.server);
            // CRITICAL: the base dir passed to createDefault() MUST be the original
            // run-directory (the one Main.main used), NOT the current level directory.
            // validateAndCreateAccess(levelId) resolves to baseDir.resolve(levelId),
            // so feeding it the already-resolved level path "./world" and id "world"
            // produces "./world/world" — a nested directory the restore never wrote to
            // (it wrote to "./world"), so the restarted server loads an empty/initial
            // world instead of the restored one. Use the parent LevelStorageSource's
            // baseDir, which is invariant across restarts.
            LevelStorageSource levelStorageSource = LevelStorageSource.createDefault(
                this.server.storageSource.parent().getBaseDir()
            );
            this.server.storageSource = levelStorageSource.validateAndCreateAccess(
                this.server.storageSource.getLevelId()
            );
            this.server.playerDataStorage = this.server.storageSource.createPlayerStorage();
            // 26.2 moved saved-data storage up to the server object, and stopServer()
            // closes it. Without a fresh instance the restarted server's very first
            // save throws "Trying to schedule save when SavedDataStorage is already
            // closed" and the watchdog kills the process. Rebuilding it here also
            // makes it re-read data.dat & co. from the freshly restored world.
            this.server.savedDataStorage = new SavedDataStorage(
                this.server.storageSource.getLevelPath(LevelResource.DATA),
                this.server.getFixerUpper(),
                this.server.registryAccess()
            );
            java.nio.file.Path levelDirPath = this.server.storageSource.getLevelDirectory().path();
            boolean levelDatExists = java.nio.file.Files.exists(levelDirPath.resolve("level.dat"));
            int[] fileCount = {0};
            try (var s = java.nio.file.Files.walk(levelDirPath)) {
                fileCount[0] = (int) s.count();
            } catch (Exception ignored) {}
            QuickbackupmultiReforged.logger.info("[QBM-DBG] ServerManager.startServer levelDir='{}' levelId='{}' level.dat.exists={} filesInLevelDir={}",
                levelDirPath, this.server.storageSource.getLevelId(), levelDatExists, fileCount[0]);
            resetLoaderStartupGuards();
            reopenPacketProcessor();
            this.server.runServer();
        } catch (IOException e) {
            QuickbackupmultiReforged.logger.error("Failed to start the server", e);
        } catch (ContentValidationException e1) {
            QuickbackupmultiReforged.logger.error("Level data is corrupted", e1);
        } catch (ReflectiveOperationException e2) {
            QuickbackupmultiReforged.logger.error("Failed to prepare the server for restart", e2);
        }
    }

    /**
     * 26.2 routes every inbound packet through a {@link PacketProcessor} that
     * stopServer() closes; a closed one silently drops all packets, so joins to the
     * restarted server die right after the handshake ("Server closed"). The field is
     * final, hence reflection. Replacing it is safe here: the old processor is closed
     * and its queue is only drained on the server thread we are on.
     */
    private void reopenPacketProcessor() throws ReflectiveOperationException {
        for (Field field : MinecraftServer.class.getDeclaredFields()) {
            if (!PacketProcessor.class.equals(field.getType())) {
                continue;
            }
            field.setAccessible(true);
            field.set(this.server, new PacketProcessor(this.server.getRunningThread()));
            return;
        }
        QuickbackupmultiReforged.logger.warn("No PacketProcessor field found; players may fail to join after the restart");
    }

    /**
     * Reuse of one MinecraftServer instance across a restart trips mod-loader
     * "started once" guards. Fabric API's lifecycle mixin adds a {@code startupReady}
     * AtomicBoolean that it flips in afterServerStartedEvent() and throws
     * "Server is already marked as started" on the second pass. The field is
     * loader-specific and injected by mixin, so it is only reachable reflectively;
     * failure to reset it is logged and left non-fatal — the restart itself is
     * more valuable than any single guard.
     */
    private void resetLoaderStartupGuards() {
        for (Class<?> type = this.server.getClass(); type != null; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (!field.getName().endsWith("startupReady") || !AtomicBoolean.class.isAssignableFrom(field.getType())) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    AtomicBoolean flag = (AtomicBoolean) field.get(this.server);
                    if (flag != null) {
                        flag.set(false);
                    }
                } catch (ReflectiveOperationException | RuntimeException e) {
                    QuickbackupmultiReforged.logger.warn("Could not reset loader startup guard {}", field.getName(), e);
                }
            }
        }
    }

    public void stopServer() {
        this.server.halt(false);
    }

    public List<ServerPlayer> getPlayers() {
        return this.server.getPlayerList().getPlayers();
    }

    public CommandSourceStack getCommandSource() {
        return this.server.createCommandSourceStack();
    }
}
