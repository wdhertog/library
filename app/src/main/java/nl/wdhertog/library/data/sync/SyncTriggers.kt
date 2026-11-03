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

    /**
     * Uit te voeren bij het openen van de database: oude definities weg, actuele erin. Zo werkt een
     * gewijzigde trigger na een update meteen.
     */
    val statements: List<String> by lazy {
        sharedTables.flatMap { table ->
            listOf(
                trigger("${table}_insert", "AFTER INSERT ON $table", "'$table'", "NEW.uuid", deleted = false),
                trigger("${table}_update", "AFTER UPDATE ON $table", "'$table'", "NEW.uuid", deleted = false),
                trigger("${table}_delete", "AFTER DELETE ON $table", "'$table'", "OLD.uuid", deleted = true),
            )
        }.flatten() + listOf(
            // Auteurs van een werk: het werk is veranderd (als het nog bestaat).
            trigger("work_author_insert", "AFTER INSERT ON work_author", "'work'", "(SELECT uuid FROM work WHERE id = NEW.workId)", false),
            trigger("work_author_delete", "AFTER DELETE ON work_author", "'work'", "(SELECT uuid FROM work WHERE id = OLD.workId)", false),
            // Werken van een boek: het boek is veranderd.
            trigger("book_work_insert", "AFTER INSERT ON book_work", "'book'", "(SELECT uuid FROM book WHERE id = NEW.bookId)", false),
            trigger("book_work_update", "AFTER UPDATE ON book_work", "'book'", "(SELECT uuid FROM book WHERE id = NEW.bookId)", false),
            trigger("book_work_delete", "AFTER DELETE ON book_work", "'book'", "(SELECT uuid FROM book WHERE id = OLD.bookId)", false),
        ).flatten()
    }

    /**
     * Synchronisatie helemaal opnieuw, na het terugzetten van een back-up. Die kan van een andere
     * telefoon komen (zelfde id), of ouder zijn dan wat gekoppelde telefoons al van deze telefoon hebben.
     */
    val resetStatements = listOf(
        "DELETE FROM sync_peer", "DELETE FROM sync_outbox", "DELETE FROM sync_shadow",
        "DELETE FROM sync_field_clock", "DELETE FROM sync_dirty",
        "DELETE FROM sync_meta WHERE key IN ('${SyncKeys.DEVICE_ID}', '${SyncKeys.ENABLED}', '${SyncKeys.CLOCK}')",
    )

    /**
     * Eerst weghalen, dan invoegen. Niet "INSERT OR REPLACE": in een trigger telt de conflictregel van
     * de buitenste opdracht (Room gebruikt OR ABORT), en dan zou een tweede wijziging aan hetzelfde
     * record de hele opdracht laten mislukken. Een uuid die niet (meer) te vinden is, slaat hij over.
     */
    private fun trigger(name: String, event: String, entity: String, uuid: String, deleted: Boolean) = listOf(
        "DROP TRIGGER IF EXISTS sync_$name",
        "CREATE TRIGGER sync_$name $event WHEN $ENABLED BEGIN " +
            "DELETE FROM sync_dirty WHERE entity = $entity AND uuid = $uuid; " +
            "INSERT INTO sync_dirty (entity, uuid, deleted) SELECT $entity, $uuid, ${if (deleted) 1 else 0} WHERE $uuid IS NOT NULL; " +
            "END",
    )
}

/** Sleutels in sync_meta. */
object SyncKeys {
    const val ENABLED = "enabled"
    const val DEVICE_ID = "device_id"
    const val CLOCK = "clock"
}
