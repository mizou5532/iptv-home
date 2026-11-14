package home.iptv.shared.text

import java.text.Normalizer

internal actual fun stripDiacritics(s: String): String =
    Normalizer.normalize(s, Normalizer.Form.NFD).filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() && Character.getType(it) != Character.COMBINING_SPACING_MARK.toInt() && Character.getType(it) != Character.ENCLOSING_MARK.toInt() }
