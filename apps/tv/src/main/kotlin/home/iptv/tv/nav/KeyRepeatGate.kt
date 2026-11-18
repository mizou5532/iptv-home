package home.iptv.tv.nav

/**
 * Flèche maintenue sur la télécommande : la télé répète l'appui environ 20 fois par seconde, et la sélection filait
 * trop vite pour être suivie (essai du propriétaire : « la sélection bondit, on va trop vite »). Les répétitions sont
 * ramenées à une toutes les [minRepeatMs] ; un appui simple (première frappe) passe toujours, sans aucun délai.
 */
class KeyRepeatGate(private val minRepeatMs: Long = REPEAT_MIN_MS) {
    private var lastKey = -1
    private var lastAt = Long.MIN_VALUE

    /** Vrai si cet appui de flèche doit déplacer la sélection ; faux s'il est absorbé (répétition trop rapprochée). */
    fun allow(keyCode: Int, repeatCount: Int, eventTimeMs: Long): Boolean {
        if (repeatCount == 0 || keyCode != lastKey || eventTimeMs - lastAt >= minRepeatMs || eventTimeMs < lastAt) {
            lastKey = keyCode
            lastAt = eventTimeMs
            return true
        }
        return false
    }
}

/** Rythme maximal d'une flèche maintenue : environ 10 déplacements par seconde (répétition de la télé toutes les 50 ms : un appui sur deux). */
const val REPEAT_MIN_MS = 90L
