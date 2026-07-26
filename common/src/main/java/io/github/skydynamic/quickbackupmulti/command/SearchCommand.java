package io.github.skydynamic.quickbackupmulti.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.github.skydynamic.increment.storage.lib.database.StorageInfo;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import io.github.skydynamic.quickbackupmulti.utils.BackupManager;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;

import static io.github.skydynamic.quickbackupmulti.translate.Translate.tr;
import static io.github.skydynamic.quickbackupmulti.utils.ListBackupsUtils.search;

public class SearchCommand {
    public static final LiteralArgumentBuilder<CommandSourceStack> cmd = Commands.literal("search")
        .then(Commands.argument("name", StringArgumentType.string())
            .executes(it ->
                searchSaveBackups(it.getSource(), StringArgumentType.getString(it, "name"))
            )
        );

    private static int searchSaveBackups(CommandSourceStack commandSource, String string) {
        // One sorted fetch serves both matching and index numbering — the previous
        // shape re-queried the DB and rescanned the whole list per hit (O(m×n)).
        List<StorageInfo> backups = BackupManager.getSortedBackups();
        List<Integer> matchedIndices = new ArrayList<>();
        for (int i = 0; i < backups.size(); i++) {
            StorageInfo info = backups.get(i);
            if (StringUtils.containsIgnoreCase(info.getName(), string)
                || StringUtils.containsIgnoreCase(info.getDesc(), string)) {
                matchedIndices.add(i + 1);
            }
        }
        if (matchedIndices.isEmpty()) {
            commandSource.sendSystemMessage(Component.nullToEmpty(tr("quickbackupmulti.search.fail")));
        } else {
            commandSource.sendSystemMessage(search(backups, matchedIndices));
        }
        return 1;
    }
}
