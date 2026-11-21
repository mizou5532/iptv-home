package home.iptv.shared.groups

import java.io.File
import kotlinx.serialization.json.*
import kotlin.test.*

class GroupIndexTest {
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

    @Test fun sameGroupsAsBeforeOnFixture() {
        for (case in fixture.getValue("cases").jsonArray) {
            val c = case.jsonObject
            val sources = c.getValue("sources").jsonArray.map { it.source() }
            val snapshot = c.getValue("snapshot").snapshot()
            val names = c["categoryNames"]?.jsonObject.orEmpty().mapValues { it.value.jsonPrimitive.content }
            val countries = names.mapNotNull { (id, n) -> home.iptv.shared.text.countryOf(n)?.let { id to it } }.toMap()
            val expected = buildGroups(sources, snapshot, keyOf = { autoKey(it.name, countries[it.categoryId]) }, titleOf = ::baseTitle)
            val index = groupSourceIndex(sources, snapshot, true, countries)
            val actual = sources.map { index.getValue(it.id) }.distinctBy { it.id }
            assertEquals(expected.map { it.id }.sorted(), actual.map { it.id }.sorted(), c.getValue("name").toString())
            for (g in expected) {
                val got = index.getValue(g.sources.first().id)
                assertEquals(g.id, got.id)
                assertEquals(g.title, got.title)
                assertEquals(g.sources.map { it.id }, got.sources.map { it.id })
            }
        }
    }

    @Test fun precomputedKeysWinOverParsing() {
        val a = GroupSource("a", "c1", "Alpha Premier", 0)
        val b = GroupSource("b", "c2", "Zeta Totalement Different", 1)
        val forced = PrecomputedKey("cle-forcee", "Titre forcé")
        val index = groupSourceIndex(listOf(a, b), GroupSnapshot(0, 1, emptyList()), true) { forced }
        assertEquals(index.getValue("a"), index.getValue("b"))
        val group = index.getValue("a")
        assertEquals("auto:v1:cle-forcee", group.id)
        assertEquals("Titre forcé", group.title)
        assertEquals(listOf("a", "b"), group.sources.map { it.id })
    }
}
