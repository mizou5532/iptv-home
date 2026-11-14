package home.iptv.shared.groups

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable
data class GroupPage(
    val rev: Long,
    @SerialName("rules_version") val rulesVersion: Int,
    val groups: List<GroupPageEntry>,
    @SerialName("next_id") val nextId: String? = null,
)
@Serializable
data class GroupPageEntry(
    val id: String,
    val title: String,
    @SerialName("source_ids") val sourceIds: List<String>,
    @SerialName("blocked_auto_keys") val blockedAutoKeys: List<String>,
)

/** Transport injectable ; aucune donnee partielle ne sort de snapshot. */
class GroupsClient(private val page: suspend (householdId: String, afterId: String?, limit: Int) -> GroupPage) {
    constructor(supabase: SupabaseClient) : this({ h, after, limit ->
        check(supabase.auth.sessionStatus.value is SessionStatus.Authenticated) { "Session absente" }
        supabase.postgrest.rpc("live_group_page", buildJsonObject {
            put("h", h)
            put("after_id", after?.let(::JsonPrimitive) ?: JsonNull)
            put("lim", limit)
        }).decodeAs<GroupPage>()
    })

    suspend fun revision(householdId: String): Long = page(householdId, null, 1).rev

    suspend fun snapshot(householdId: String): GroupSnapshot {
        repeat(3) {
            val groups = mutableListOf<ManualGroup>()
            val seenCursors = HashSet<String>()
            val seenGroups = HashSet<String>()
            var after: String? = null
            var revision: Long? = null
            do {
                val current = page(householdId, after, 500)
                require(current.rulesVersion == 1) { "Version de regroupement inconnue" }
                if (revision != null && revision != current.rev) break
                revision = current.rev
                for (group in current.groups) {
                    check(seenGroups.add(group.id)) { "Groupe repete" }
                    groups += ManualGroup(group.id, group.title, group.sourceIds, group.blockedAutoKeys)
                }
                after = current.nextId
                if (after == null) return GroupSnapshot(current.rev, 1, groups)
                check(seenCursors.add(after)) { "Curseur repete" }
            } while (true)
        }
        error("Revision de groupes instable")
    }
}
