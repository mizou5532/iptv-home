package home.iptv.shared.groups

import java.io.File
import kotlinx.serialization.json.*
import kotlin.test.*

class LiveGroupsTest {
    private val fixture = Json.parseToJsonElement(File("../fixtures/live-groups-v2.json").takeIf { it.exists() }
        ?.readText() ?: File("../../fixtures/live-groups-v2.json").readText()).jsonObject
    private fun JsonElement.source(): GroupSource = jsonObject.let {
        GroupSource(it.getValue("id").jsonPrimitive.content, it.getValue("categoryId").jsonPrimitive.content,
            it.getValue("name").jsonPrimitive.content, it.getValue("position").jsonPrimitive.int)
    }
    private fun JsonElement.snapshot(): GroupSnapshot = jsonObject.let { obj ->
        GroupSnapshot(obj.getValue("rev").jsonPrimitive.long, obj.getValue("rulesVersion").jsonPrimitive.int,
            obj.getValue("groups").jsonArray.map { entry -> entry.jsonObject.let {
                ManualGroup(it.getValue("id").jsonPrimitive.content, it.getValue("title").jsonPrimitive.content,
                    it.getValue("sourceIds").jsonArray.map { id -> id.jsonPrimitive.content },
                    it.getValue("blockedAutoKeys").jsonArray.map { key -> key.jsonPrimitive.content })
            } })
    }
    @Test fun shared_auto_key_vectors() {
        for (vector in fixture.getValue("autoKeys").jsonArray) {
            val v = vector.jsonArray
            assertEquals(v[2].jsonPrimitive.contentOrNull, autoKey(v[0].jsonPrimitive.content, v[1].jsonPrimitive.contentOrNull), v.toString())
        }
    }
    @Test fun shared_groups_and_reversed_arrival() {
        for (case in fixture.getValue("cases").jsonArray) {
            val c = case.jsonObject
            val sources = c.getValue("sources").jsonArray.map { it.source() }
            val snapshot = c.getValue("snapshot").snapshot()
            val names = c["categoryNames"]?.jsonObject.orEmpty().mapValues { it.value.jsonPrimitive.content }
            for (input in listOf(sources, sources.reversed())) {
                val actual = buildGroups(input, snapshot) { s -> names[s.categoryId]?.let { home.iptv.shared.text.countryOf(it) } }.map { group -> buildJsonObject {
                    put("id", group.id); put("title", group.title)
                    put("sourceIds", JsonArray(group.sources.map { JsonPrimitive(it.id) }))
                } }
                assertEquals(c.getValue("expected"), JsonArray(actual), c.getValue("name").toString())
            }
        }
    }
    @Test fun ties_use_id_without_mutation() {
        val sources = listOf(GroupSource("b", "x", "RDS HD", 0), GroupSource("a", "y", "RDS SD", 0))
        assertEquals(listOf("a", "b"), buildGroups(sources, GroupSnapshot(0, 1, emptyList())).single().sources.map { it.id })
        assertEquals(listOf("b", "a"), sources.map { it.id })
    }
    @Test fun unsupported_rules_are_rejected() {
        assertFailsWith<IllegalArgumentException> { buildGroups(emptyList(), GroupSnapshot(0, 2, emptyList())) }
    }
    @Test fun catalogue_budget_uses_linear_source_reads() {
        var reads = 0
        val sources = object : AbstractList<GroupSource>() {
            override val size = 100_000
            override fun get(index: Int): GroupSource {
                reads++
                return GroupSource(index.toString(), (index % 2000).toString(), "Chaine ${index % 50000} HD", index)
            }
        }
        val result = buildGroups(sources, GroupSnapshot(0, 1, emptyList()))
        assertEquals(50000, result.size)
        assertTrue(result.all { it.sources.size == 2 })
        assertTrue(reads <= 300000, "source reads: $reads")
    }
}
