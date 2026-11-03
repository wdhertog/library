package nl.wdhertog.library.data.sync

/**
 * SQLite-triggers die bijhouden welke gedeelde records veranderd zijn (in sync_dirty), alleen
 * zolang synchroniseren aan staat. Persoonlijke tabellen (leessessies, leesdoel) hebben geen trigger.
 *
 * Koppeltabellen hebben geen eigen uuid; een wijziging daarin telt als wijziging van het record
 * waar ze bij horen: auteurs bij het werk, werken bij het boek.
 */
object SyncTriggers {

    /** Gedeelde tabellen met een eigen uuid; de naam is ook de "entity" in de sync-tabellen. */
    val sharedTables = listOf("book", "work", "series", "location", "person", "loan")

    private const val ENABLED = "(SELECT value FROM sync_meta WHERE key = '${SyncKeys.ENABLED}') = '1'"

    val statements: List<String> by lazy {
        sharedTables.flatMap { table ->
            listOf(
                trigger("${table}_insert", "AFTER INSERT ON $table", "VALUES ('$table', NEW.uuid, 0)"),
                trigger("${table}_update", "AFTER UPDATE ON $table", "VALUES ('$table', NEW.uuid, 0)"),
                trigger("${table}_delete", "AFTER DELETE ON $table", "VALUES ('$table', OLD.uuid, 1)"),
            )
        } + listOf(
            // Auteurs van een werk: het werk is veranderd (als het nog bestaat).
            trigger("work_author_insert", "AFTER INSERT ON work_author", "SELECT 'work', uuid, 0 FROM work WHERE id = NEW.workId"),
            trigger("work_author_delete", "AFTER DELETE ON work_author", "SELECT 'work', uuid, 0 FROM work WHERE id = OLD.workId"),
            // Werken van een boek: het boek is veranderd.
            trigger("book_work_insert", "AFTER INSERT ON book_work", "SELECT 'book', uuid, 0 FROM book WHERE id = NEW.bookId"),
            trigger("book_work_update", "AFTER UPDATE ON book_work", "SELECT 'book', uuid, 0 FROM book WHERE id = NEW.bookId"),
            trigger("book_work_delete", "AFTER DELETE ON book_work", "SELECT 'book', uuid, 0 FROM book WHERE id = OLD.bookId"),
        )
    }

    private fun trigger(name: String, event: String, rows: String) =
        "CREATE TRIGGER IF NOT EXISTS sync_$name $event WHEN $ENABLED " +
            "BEGIN INSERT OR REPLACE INTO sync_dirty (entity, uuid, deleted) $rows; END"
}

/** Sleutels in sync_meta. */
object SyncKeys {
    const val ENABLED = "enabled"
    const val DEVICE_ID = "device_id"
    const val CLOCK = "clock"
}
