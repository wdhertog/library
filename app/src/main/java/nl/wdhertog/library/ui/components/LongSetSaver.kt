package nl.wdhertog.library.ui.components

import androidx.compose.runtime.saveable.Saver

/** Een selectie van boek-id's die het draaien van het scherm overleeft (rememberSaveable). */
val LongSetSaver: Saver<Set<Long>, LongArray> = Saver(
    save = { it.toLongArray() },
    restore = { it.toSet() },
)
