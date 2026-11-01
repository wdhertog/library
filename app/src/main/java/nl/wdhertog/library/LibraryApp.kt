package nl.wdhertog.library

import android.app.Application
import nl.wdhertog.library.data.db.LibraryDatabase
import nl.wdhertog.library.data.repository.BookRepository

/** Handmatige dependency injection: één container voor de hele app. */
class AppContainer(app: Application) {
    private val database by lazy { LibraryDatabase.create(app) }
    val bookRepository by lazy { BookRepository(database) }
}

class LibraryApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
