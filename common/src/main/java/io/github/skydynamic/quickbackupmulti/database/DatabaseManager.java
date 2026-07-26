package io.github.skydynamic.quickbackupmulti.database;

import io.github.skydynamic.increment.storage.lib.manager.IDatabaseManager;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Setter
@Getter
public class DatabaseManager implements IDatabaseManager {
    private String fileName = "QuickBackupMulti";
    private String databasePath = "./QuickBackupMulti";
    private UUID collectionUuid;

    public DatabaseManager(
        String fileName,
        String dataBasePath,
        UUID collectionUuid
    ) {
        this.fileName = fileName;
        this.databasePath = dataBasePath;
        this.collectionUuid = collectionUuid;
    }

    public DatabaseManager(UUID uuid) {
        this.collectionUuid = uuid;
    }

    /**
     * The storage lib builds its JDBC URL as {@code jdbc:h2:file:<databasePath>/<fileName>},
     * so H2 URL settings can only be injected here. Without DB_CLOSE_DELAY the whole
     * database is opened (file lock, recovery check) and closed again on EVERY query;
     * -1 keeps it open until JVM exit (H2's DB_CLOSE_ON_EXIT hook still closes it cleanly).
     */
    public String getFileName() {
        return fileName + ";DB_CLOSE_DELAY=-1";
    }
}
