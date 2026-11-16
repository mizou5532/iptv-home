package home.iptv.shared.sync

/** Nom de modèle public uniquement : aucun numéro de série ni identifiant réseau. */
fun defaultDeviceName(manufacturer: String?, model: String?): String {
    fun clean(value: String?): String = value.orEmpty().replace(Regex("[\\p{Cc}\\p{Cf}]"), " ")
        .trim().replace(Regex("\\s+"), " ").takeUnless { it.equals("unknown", true) || it.equals("null", true) }.orEmpty()
    val maker = clean(manufacturer)
    val type = clean(model)
    val name = when {
        type.isEmpty() -> maker
        maker.isEmpty() || type.equals(maker, true) || type.startsWith("$maker ", true) -> type
        else -> "$maker $type"
    }
    return name.ifBlank { "Télé Android" }.take(31).trimEnd()
}
