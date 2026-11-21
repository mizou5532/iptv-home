package home.iptv.tv.feature

import home.iptv.shared.meta.MediaKind
import home.iptv.shared.versions.Lang
import home.iptv.shared.versions.Version
import home.iptv.shared.versions.VersionIndexDisk
import home.iptv.shared.versions.VersionIndexes
import home.iptv.shared.versions.VersionTag

/** Index des versions de l'app ; null (tests, aperçus) = aucune version connue, aucun bouton « Version ». */
val LocalVersionIndexes = androidx.compose.runtime.staticCompositionLocalOf<VersionIndexes?> { null }

/** Fichier texte simple (séparateurs de contrôle), écrit d'un coup puis renommé (jamais à moitié). */
class FileVersionIndexDisk(private val dir: java.io.File) : VersionIndexDisk {
    private fun file(kind: MediaKind) = java.io.File(dir.apply { mkdirs() }, "versions-${kind.name.lowercase()}.txt")

    override fun load(kind: MediaKind, fingerprint: String): Map<String, List<Version>>? = runCatching {
        val f = file(kind)
        if (!f.exists()) return null
        f.bufferedReader().use { r ->
            if (r.readLine() != fingerprint) return null
            val out = LinkedHashMap<String, List<Version>>()
            r.lineSequence().forEach { line ->
                val key = line.substringBefore(GROUP)
                val versions = line.substringAfter(GROUP).split(ITEM).filter { it.isNotEmpty() }.map { decode(it) }
                out[key] = versions
            }
            out
        }
    }.getOrNull()

    override fun save(kind: MediaKind, fingerprint: String, groups: Map<String, List<Version>>) {
        val f = file(kind)
        val tmp = java.io.File(f.path + ".tmp")
        tmp.bufferedWriter().use { w ->
            w.write(fingerprint); w.write("\n")
            groups.forEach { (key, vs) ->
                w.write(clean(key)); w.write(GROUP.toString())
                vs.forEachIndexed { i, v -> if (i > 0) w.write(ITEM.toString()); w.write(encode(v)) }
                w.write("\n")
            }
        }
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    private fun clean(s: String?) = s.orEmpty().replace(GROUP, ' ').replace(ITEM, ' ').replace(FIELD, ' ').replace('\n', ' ').replace('\r', ' ')

    private fun encode(v: Version) = listOf(v.id, v.categoryId, v.title, v.tag.lang?.name.orEmpty(), if (v.tag.uhd) "1" else "",
        if (v.tag.hdr) "1" else "", v.tag.source.orEmpty(), v.cover.orEmpty(), v.year.orEmpty()).joinToString(FIELD.toString()) { clean(it) }

    private fun decode(s: String): Version {
        val p = s.split(FIELD)
        fun at(i: Int) = p.getOrNull(i).orEmpty()
        val lang = at(3).takeIf { it.isNotEmpty() }?.let { n -> Lang.entries.firstOrNull { it.name == n } }
        return Version(at(0), at(1), at(2), VersionTag(lang, at(4) == "1", at(5) == "1", at(6).ifEmpty { null }),
            at(7).ifEmpty { null }, at(8).ifEmpty { null })
    }

    private companion object { const val GROUP = '\u0001'; const val ITEM = '\u0002'; const val FIELD = '\u0003' }
}
