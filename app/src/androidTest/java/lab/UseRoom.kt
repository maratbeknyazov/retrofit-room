package lab.roomlab

internal inline fun <T> AppDatabase.useRoom(block: (AppDatabase) -> T): T =
    try { block(this) } finally { close() }
