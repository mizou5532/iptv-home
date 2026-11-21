package home.iptv.shared.groups

internal actual fun <T> withGroupLock(lock: Any, block: () -> T): T = synchronized(lock) { block() }
