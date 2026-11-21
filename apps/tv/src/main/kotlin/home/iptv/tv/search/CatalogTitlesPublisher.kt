package home.iptv.tv.search

import home.iptv.shared.catalog.CatalogTitlesState

class AndroidCatalogTitlesState(context: android.content.Context) : CatalogTitlesState {
    private val prefs = context.applicationContext.getSharedPreferences("iptv_home_catalog_titles", android.content.Context.MODE_PRIVATE)
    override fun publishedAt(household: String, kind: String) = prefs.getLong("$household:$kind", 0L)
    override fun savePublished(household: String, kind: String, at: Long) { prefs.edit().putLong("$household:$kind", at).apply() }
}
