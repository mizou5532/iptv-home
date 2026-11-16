package home.iptv.tv.search

import home.iptv.shared.search.SearchSection
import home.iptv.tv.live.LiveSession
import kotlinx.coroutines.CoroutineScope

typealias SearchFactory = (SearchSection, LiveSession, CoroutineScope) -> SearchViewModel
