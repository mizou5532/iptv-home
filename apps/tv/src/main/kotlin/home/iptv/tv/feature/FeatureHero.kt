package home.iptv.tv.feature

import home.iptv.tv.live.backKey
import home.iptv.tv.live.okPress
import home.iptv.tv.live.ignoreHeldOk
import home.iptv.shared.meta.MediaKind
import home.iptv.shared.meta.MediaRef
import home.iptv.shared.meta.imdbLabel
import home.iptv.shared.meta.tmdbImage
import home.iptv.tv.meta.rememberMeta
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import home.iptv.tv.live.LiveButton
import home.iptv.tv.live.LiveDims
import home.iptv.tv.live.focusLook
import home.iptv.tv.series.LocalCoverServer
import home.iptv.tv.series.resolveCover
import home.iptv.tv.theme.LocalIptvTokens
import kotlinx.coroutines.delay

/** Défilement automatique de la bannière ; les tests de télécommande le coupent (une attente sans fin bloquerait l'attente du calme). */
val LocalFeatureAutoRotate = androidx.compose.runtime.staticCompositionLocalOf { true }

internal object HeroDims {
    val Height = 300.dp
    /** Espace laissé à droite du texte : la photo de la bannière. */
    val TextEndGap = 290.dp
    /** Image de fond demandée à la taille de la carte (pas l'original du fournisseur, souvent bien plus grand). */
    const val BackdropW = 1280
    const val BackdropH = 520
    const val ThumbW = 320
    const val ThumbH = 180
}

/** Image d'une suggestion : l'image de fond si elle existe, sinon l'affiche (recadrée). */
@Composable
internal fun FeatureImage(backdrop: String?, cover: String?, w: Int, h: Int, modifier: Modifier = Modifier, tmdb: String? = null, softCover: Boolean = false) {
    val context = LocalContext.current
    val server = LocalCoverServer.current
    // Image TMDB (vraie image de fond paysage) d'abord ; sinon celle du fournisseur.
    val landscape = remember(tmdb, backdrop) { tmdb ?: resolveCover(backdrop, server) }
    val url = landscape ?: remember(cover) { resolveCover(cover, server) }
    if (url != null) {
        // Seulement l'affiche (verticale) pour un grand fond : étirée, elle était pixelisée (essai TCL 0.17.0).
        // [softCover] : on la floute fortement et on l'assombrit, comme une ambiance de couleurs.
        val soft = softCover && landscape == null
        AsyncImage(
            filterQuality = androidx.compose.ui.graphics.FilterQuality.High,
            model = remember(url, w, h, soft) { ImageRequest.Builder(context).data(url).size(if (soft) w / 4 else w, if (soft) h / 4 else h).build() },
            contentDescription = null, contentScale = ContentScale.Crop,
            modifier = if (soft) modifier.blur(48.dp).alpha(0.45f) else modifier,
        )
    }
}

/**
 * Bannière d'accueil (maquette « Accueil B ») : grande carte arrondie avec l'image de fond de la suggestion, son titre,
 * ses boutons (rien à droite : la photo). Elle passe à la suggestion suivante toutes les 10 secondes ; le
 * compte repart chaque fois que la télécommande bouge dans la bannière, pour ne pas changer le titre sous le doigt.
 * Les couleurs suivent le profil : sur un fond clair (Maha), le dégradé et le texte sont clairs et prune.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun FeatureHero(
    features: List<Feature>,
    primaryRequester: FocusRequester,
    onPlay: (Feature) -> Unit,
    onOpen: (Feature) -> Unit,
    modifier: Modifier = Modifier,
    /** La télécommande entre dans la bannière : l'accueil remonte tout en haut pour la montrer en entier. */
    onFocused: () -> Unit = {},
    /** Délai entre deux titres (réglage du profil, 10 s par défaut). */
    rotateMs: Long = FEATURE_ROTATE_MS,
) {
    if (features.isEmpty()) return
    val t = LocalIptvTokens.current
    var index by remember { mutableIntStateOf(0) }
    var nudge by remember { mutableIntStateOf(0) }
    val count = features.size
    val current = features[index.coerceIn(0, count - 1)]
    val latestCount by rememberUpdatedState(count)
    val auto = LocalFeatureAutoRotate.current
    LaunchedEffect(index, nudge, count, auto, rotateMs) {
        if (count < 2 || !auto) return@LaunchedEffect
        delay(rotateMs)
        index = (index + 1) % latestCount
    }
    val shape = remember(t.radiusCard) { RoundedCornerShape(t.radiusCard * 1.6f) }
    val fade = t.background
    // La télé TCL affiche 960 × 540 dp : la bannière prend ~44 % de la hauteur (220 à 300 dp), jamais tout l'écran.
    val screenH = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp
    val height = (screenH * 0.44f).dp.coerceIn(220.dp, HeroDims.Height)
    val focusedNow by rememberUpdatedState(onFocused)
    Box(
        modifier.fillMaxWidth().height(height).clip(shape).background(t.surfaceRaised)
            .onFocusChanged { if (it.hasFocus) { nudge++; focusedNow() } },
    ) {
        Crossfade(targetState = current, animationSpec = tween(600), label = "bannière") { f ->
            Box(Modifier.fillMaxSize()) {
                val fm = rememberMeta(f.metaRef)
                FeatureImage(f.backdrop, f.cover, HeroDims.BackdropW, HeroDims.BackdropH, Modifier.fillMaxSize(), tmdbImage(fm?.backdrop, "w1280"), softCover = true)
                Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(
                    0f to fade.copy(alpha = 0.94f), 0.42f to fade.copy(alpha = 0.6f), 0.7f to fade.copy(alpha = 0.05f), 1f to Color.Transparent,
                )))
            }
        }
        // Une seule suggestion (série épinglée de Maha) : rien que l'image de fond, le titre, où on en est et
        // « Reprendre » (demande du propriétaire, 16 nov. : ni affiche, ni résumé, ni file).
        val single = count == 1
        Column(
            // Le texte reste à gauche (sur le dégradé) : la droite montre la photo, sans suggestions (demande du propriétaire, 17 nov.).
            Modifier.fillMaxHeight().fillMaxWidth()
                .padding(start = 28.dp, top = 22.dp, bottom = 18.dp, end = if (!single) HeroDims.TextEndGap else 28.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(current.reason, color = t.accent.takeUnless { t.light } ?: t.text, fontSize = t.metaTextSize * 0.85f, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            // Fiche TMDB : logo du titre (image) s'il existe, sinon le titre français ; note IMDb avec les détails.
            val cm = rememberMeta(current.metaRef)
            val logo = tmdbImage(cm?.logo, "w500")
            if (logo != null) TitleLogo(logo, cm?.title ?: current.title, if (single) 84.dp else 64.dp)
            else Text(cm?.title ?: current.title, color = t.text, fontSize = t.titleSize * 1.15f, fontWeight = FontWeight.Bold, maxLines = if (single) 2 else 1, overflow = TextOverflow.Ellipsis, lineHeight = t.titleSize * 1.25f)
            listOfNotNull(current.meta, imdbLabel(cm?.imdbRating)).joinToString("  ·  ").takeIf { it.isNotBlank() }
                ?.let { Text(it, color = t.textMuted, fontSize = t.metaTextSize, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            current.progress?.let { p ->
                // Où on en est : barre à la couleur du profil (plus longue quand la série est seule).
                Box(Modifier.width(if (single) 320.dp else 260.dp).height(6.dp).clip(RoundedCornerShape(3.dp)).background(t.text.copy(alpha = 0.18f))) {
                    Box(Modifier.fillMaxWidth(p).height(6.dp).clip(RoundedCornerShape(3.dp)).background(if (t.light) t.text else t.accent))
                }
            }
            if (!single) (current.plot?.takeIf { it.isNotBlank() } ?: cm?.overview)?.let { Text(it, color = t.text.copy(alpha = 0.85f), fontSize = t.metaTextSize * 0.95f, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = t.metaTextSize * 1.35f) }
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(LiveDims.Gap), verticalAlignment = Alignment.CenterVertically) {
                val playable = current.resume != null || current.isMovie
                val primary = when {
                    current.resume != null -> if (current.progress != null) "Reprendre" else "Lire"
                    current.isMovie -> if (current.startS > 0) "Reprendre" else "Lire"
                    else -> "Voir la série"
                }
                LiveButton(primary, { if (playable) onPlay(current) else onOpen(current) }, Modifier.focusRequester(primaryRequester), accent = true, small = true)
                if (playable && !single) LiveButton(if (current.isMovie) "Fiche" else "Tous les épisodes", { onOpen(current) }, small = true)
                Spacer(Modifier.width(6.dp))
                HeroDots(count, index, rotateMs)
            }
        }
    }
}

/** Points de la bannière : le point courant s'allonge et se remplit pendant les 10 secondes. */
@Composable
private fun HeroDots(count: Int, index: Int, rotateMs: Long) {
    if (count < 2) return
    val t = LocalIptvTokens.current
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(count) { i ->
            if (i == index) {
                var started by remember(index) { mutableStateOf(false) }
                val auto = LocalFeatureAutoRotate.current
                LaunchedEffect(index, auto) { started = auto }
                val fill by animateFloatAsState(if (started) 1f else 0f, tween(rotateMs.toInt(), easing = LinearEasing), label = "décompte")
                Box(Modifier.width(36.dp).height(6.dp).clip(RoundedCornerShape(3.dp)).background(t.text.copy(alpha = 0.25f))) {
                    Box(Modifier.fillMaxWidth(fill).height(6.dp).background(t.text))
                }
            } else {
                Box(Modifier.size(6.dp).clip(RoundedCornerShape(3.dp)).background(t.text.copy(alpha = 0.4f)))
            }
        }
    }
}

/** Carte paysage de « Reprendre » : image de fond (ou affiche recadrée), barre de progression, titre et détail. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun LandscapeCard(
    title: String,
    caption: String?,
    backdrop: String?,
    cover: String?,
    progress: Float?,
    requester: FocusRequester? = null,
    /** Fiche TMDB : vraie image paysage, titre français. */
    ref: MediaRef? = null,
    /** Maintenir OK sur la carte (retirer de « Reprendre »). */
    onLong: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val t = LocalIptvTokens.current
    var focused by remember { mutableStateOf(false) }
    val shape = remember(t.radiusCard) { RoundedCornerShape(t.radiusCard) }
    val m = rememberMeta(ref)
    Column(Modifier.width(208.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            Modifier.fillMaxWidth().height(117.dp)
                .then(if (requester != null) Modifier.focusRequester(requester) else Modifier)
                .focusLook(focused, shape, "carte $title").clip(shape).background(t.surfaceRaised)
                .onFocusChanged { focused = it.isFocused }
                .then(if (onLong != null) Modifier.okPress(onClick, onLong) else Modifier).clickable(onClick = onClick),
        ) {
            FeatureImage(backdrop, cover, HeroDims.ThumbW, HeroDims.ThumbH, Modifier.fillMaxSize(), tmdbImage(m?.backdrop, "w780"))
            if (progress != null && progress > 0f) {
                Box(Modifier.align(Alignment.BottomStart).padding(10.dp).fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.3f))) {
                    Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(4.dp).background(t.accent))
                }
            }
        }
        Text(m?.title ?: title, color = t.text, fontSize = t.metaTextSize, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        caption?.let { Text(it, color = t.textMuted, fontSize = t.metaTextSize * 0.9f, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

/** Logo du titre (image TMDB, fond transparent) à gauche de la bannière ; [title] pour la lecture d'écran. */
@Composable
internal fun TitleLogo(url: String, title: String, height: androidx.compose.ui.unit.Dp) {
    AsyncImage(
        model = home.iptv.tv.theme.rememberCrispRequest(url, 380.dp, height), contentDescription = title,
        filterQuality = androidx.compose.ui.graphics.FilterQuality.High, contentScale = ContentScale.Fit,
        alignment = Alignment.CenterStart, modifier = Modifier.height(height).fillMaxWidth(0.62f),
    )
}

/**
 * Confirmation du retrait d'un titre de « Reprendre » (appui long sur sa carte) : « Retirer » ou « Annuler ».
 * La progression est gardée ; relancer le titre le fait revenir. Retour = Annuler.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun DismissSheet(title: String, onConfirm: () -> Unit, onCancel: () -> Unit) {
    val t = LocalIptvTokens.current
    val cancel = remember { FocusRequester() }
    androidx.activity.compose.BackHandler(onBack = onCancel)
    LaunchedEffect(title) { androidx.compose.runtime.withFrameNanos { }; runCatching { cancel.requestFocus() } }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
        Column(
            Modifier.width(460.dp).clip(RoundedCornerShape(t.radiusCard)).background(t.surfaceRaised).backKey(onCancel).ignoreHeldOk().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Retirer de Reprendre ?", color = t.text, fontSize = t.titleSize * 0.8f, fontWeight = FontWeight.Bold)
            Text("« $title » ne sera plus proposé dans Reprendre ni dans la bannière. Ta position est gardée : si tu le relances, il revient.",
                color = t.textMuted, fontSize = t.metaTextSize)
            Row(horizontalArrangement = Arrangement.spacedBy(LiveDims.Gap)) {
                LiveButton("Retirer", onConfirm, accent = true)
                LiveButton("Annuler", onCancel, Modifier.focusRequester(cancel))
            }
        }
    }
}
