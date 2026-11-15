package home.iptv.shared.progress

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray

data class DecodedProgressQueue(val entries: List<QueuedProgress>)

/** Format partagé par la persistance Android et les tests JVM. */
object ProgressQueueCodec {
    private val json = Json { ignoreUnknownKeys = true }
    fun encode(entries: List<QueuedProgress>): String = json.encodeToString(ListSerializer(QueuedProgress.serializer()), entries)
    fun decode(raw: String): DecodedProgressQueue = DecodedProgressQueue(runCatching {
        json.parseToJsonElement(raw).jsonArray.mapNotNull { element ->
            runCatching { json.decodeFromJsonElement(QueuedProgress.serializer(), element) }.getOrNull()
        }
    }.getOrDefault(emptyList()))
}
