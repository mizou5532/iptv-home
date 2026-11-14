package home.iptv.shared.xtream

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class XtreamUrlsTest {
    private val c = XtreamCreds("http://tv.exemple.com:8080", "jean@x", "a&b#c/d e")

    @Test fun normalise() {
        mapOf(
            "tv.exemple.com:8080" to "http://tv.exemple.com:8080",
            "http://tv.exemple.com:8080/" to "http://tv.exemple.com:8080",
            "  http://tv.exemple.com:8080/player_api.php " to "http://tv.exemple.com:8080",
            "http://tv.exemple.com:8080/get.php?username=a&password=b&type=m3u_plus" to "http://tv.exemple.com:8080",
            "HTTP://TV.Exemple.com:8080" to "http://tv.exemple.com:8080",
            "https://tv.exemple.com" to "https://tv.exemple.com",
            "http://tv.exemple.com:8080/player_api.php?username=a&password=b" to "http://tv.exemple.com:8080",
            "http://evil.com:9001/any/path?" to "http://evil.com:9001",
        ).forEach { (entree, attendu) -> assertEquals(attendu, normalizeServer(entree), entree) }
    }

    @Test fun api() = assertEquals(
        "http://tv.exemple.com:8080/player_api.php?username=jean%40x&password=a%26b%23c%2Fd%20e&action=get_live_streams&category_id=12",
        apiUrl(c, "get_live_streams", mapOf("category_id" to "12")),
    )

    @Test fun apiSansAction() = assertEquals(
        "http://tv.exemple.com:8080/player_api.php?username=jean%40x&password=a%26b%23c%2Fd%20e",
        apiUrl(c),
    )

    @Test fun flux() = assertEquals(
        "http://tv.exemple.com:8080/live/jean%40x/a%26b%23c%2Fd%20e/7.ts",
        streamUrl(c, StreamKind.LIVE, "7", "ts"),
    )

    @Test fun fluxFilm() = assertEquals(
        "http://tv.exemple.com:8080/movie/jean%40x/a%26b%23c%2Fd%20e/991.mkv",
        streamUrl(c, StreamKind.MOVIE, "991", "mkv"),
    )

    @Test fun encodageUtf8() = assertEquals(
        "http://tv.exemple.com:8080/series/caf%C3%A9/%2B%20x/1.mp4",
        streamUrl(XtreamCreds("http://tv.exemple.com:8080", "café", "+ x"), StreamKind.SERIES, "1", "mp4"),
    )

    @Test fun identifiantsMasques() {
        assertFalse(c.toString().contains("jean"))
        assertFalse(c.toString().contains("a&b"))
    }
}
