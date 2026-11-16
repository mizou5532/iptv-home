# Étape 5 — Recherche sur la télé : conception

16 novembre 2026. Statut : **en attente de validation du propriétaire**.
Conception générale : `2026-11-14-player-iptv-design.md` §6.7. Version visée : **0.6.0 / versionCode 7**.

## 1. Pourquoi

Le propriétaire a quitté IPTV Player Pro en partie parce que « la recherche est mauvaise » : elle ne trouve pas
« canadiens » quand on tape « canadien » ou « canadi ». Il veut trouver une chaîne, une série ou un film en
quelques lettres, à la télécommande ou à la voix.

## 2. Ce que le propriétaire a décidé (ses mots, 16 nov.)

- La recherche trouve dans **toutes les catégories gardées**, jamais dans ce qui est masqué.
- Elle est disponible **partout**, et donne les résultats **de la section où l'on se trouve** : sur Direct, des
  chaînes ; sur Séries, des séries ; sur Films, des films.

## 3. Objectifs, hors-champ, à éviter

**Objectifs**
1. Résultats pendant la frappe, en moins de 100 ms par lettre une fois le catalogue en mémoire.
2. Tolérance : majuscules, accents, mots partiels (« canadi »), ordre des mots, une faute de frappe
   (« canadeins »), surnoms (« habs » → Canadiens, « barca » → Barcelone).
3. Catalogue complet des séries et des films des catégories **non masquées**, rempli en arrière-plan.
4. Recherche à la voix.
5. Tout se fait à la télécommande ; chaque écran a un test qui imite la télécommande.

**Hors-champ (décisions de Claude, à corriger si besoin)**
- Recherche dans les programmes du guide : le guide est reporté par le propriétaire.
- Résultats mélangés de plusieurs sections et filtres « Tout / Direct / Séries / Films » : remplacés par la
  règle « la section où l'on se trouve ».
- Recherche sur l'iPhone (elle existe déjà pour les chaînes dans « Organiser »), historique des recherches,
  suggestions, recherche par acteur ou par résumé (titre seulement).
- Aucune migration, aucun changement du compte en ligne, aucun changement de la page iPhone.

**À éviter absolument**
- Demander au fournisseur une catégorie masquée « pour tout le monde » ou pour le profil (règle absolue 7).
- Afficher un élément masqué, même une fraction de seconde.
- Gêner la lecture en cours ou ralentir l'ouverture de l'application à cause du remplissage.
- Laisser croire que la recherche est complète alors que le remplissage n'est pas fini.

## 4. Approches considérées

| | Approche | Pour | Contre |
|---|---|---|---|
| A | Chercher seulement dans ce que la télé connaît déjà | Rien à télécharger, livré vite | Un film d'une catégorie jamais ouverte est introuvable : on retombe sur « la recherche ne trouve pas » |
| **B — retenue** | La télé remplit en arrière-plan la liste des titres de toutes les catégories non masquées (séries et films), puis cherche dans sa mémoire locale | Trouve tout, instantané, marche hors ligne, respecte le masquage | Incomplète pendant le premier remplissage ; plus de demandes au fournisseur |
| C | Demander au fournisseur le catalogue entier en une fois, sans catégorie | Un seul appel | Télécharge aussi les catégories masquées (interdit), réponse énorme, déjà écartée en 4A/4B |
| D | Recherche faite par le compte en ligne (Supabase) | Identique sur tous les appareils | Il faudrait publier tout le catalogue en ligne (écarté en 4B), dépend d'internet, plus lent par lettre |

Le propriétaire a choisi « toutes mes catégories gardées » : c'est B. Le choix technique (mémoire locale de la
télé, pas de service en ligne) est celui de Claude.

## 5. Ce que voit le propriétaire

Un bouton **Rechercher** (loupe) dans la barre du haut, à côté de Direct / Séries / Films, sur les trois accueils.

```
┌──────────────────────────────────────────────────────────────┐
│ ← Rechercher dans : FILMS                                    │
│ ┌──────────────────────────────────────┐  ┌───────────────┐  │
│ │ canadi▌                              │  │ 🎤 Parler     │  │
│ └──────────────────────────────────────┘  └───────────────┘  │
│ 12 résultats                                                  │
│ ┌─────┐ ┌─────┐ ┌─────┐ ┌─────┐ ┌─────┐                      │
│ │     │ │     │ │     │ │     │ │     │   affiches, comme     │
│ │     │ │     │ │     │ │     │ │     │   sur l'accueil       │
│ └─────┘ └─────┘ └─────┘ └─────┘ └─────┘                      │
│ Catalogue en préparation : 120 catégories sur 300.            │
│ D'autres résultats peuvent apparaître.                        │
└──────────────────────────────────────────────────────────────┘
```

- **Direct** : liste de chaînes (logo, nom, catégorie), copies regroupées comme sur l'écran du direct. OK lance
  la chaîne comme depuis la liste (aperçu, puis plein écran).
- **Séries / Films** : grille d'affiches. OK ouvre la fiche ; Retour depuis la fiche revient aux résultats, le
  focus sur la même affiche et le texte tapé conservé.
- Champ vide : un texte d'aide (« Tape quelques lettres ou appuie sur Parler »). Aucun résultat : « Rien trouvé
  pour “…” » et, si le remplissage n'est pas fini, la ligne « Catalogue en préparation ».
- Saisie : le clavier de la télé (Google TV) s'ouvre sur le champ. Bas depuis le champ va aux résultats ; Haut
  depuis la première rangée revient au champ ; Retour ferme la recherche et rend le focus au bouton Rechercher.
- **Parler** : ouvre la reconnaissance vocale de la télé (`RecognizerIntent.ACTION_RECOGNIZE_SPEECH`, en
  français) ; le texte reconnu remplit le champ. Si la télé n'offre pas ce service, le bouton n'est pas affiché
  (le micro du clavier Google TV reste utilisable). Le bouton Assistant de la télécommande appartient à Google
  TV : l'application ne peut pas l'intercepter, ce n'est pas un objectif.

## 6. Comment ça marche

### 6.1 Moteur de recherche (cœur partagé `apps/shared`, sans Android)

Fonction pure, testable sur JVM : `(requête, éléments) → résultats classés`.

- Normalisation par `searchNorm` (déjà là : accents, casse, espaces), puis découpage en mots ; la ponctuation
  et les séparateurs (`|`, `:`, `-`, `.`) coupent les mots.
- Chaque mot tapé doit correspondre à un mot du titre : égal, **début de mot**, contenu dans un mot, ou à une
  faute près (une lettre changée, ajoutée, retirée ou deux lettres inversées) pour les mots tapés de 4 lettres
  ou plus ; deux fautes à partir de 8 lettres. Le dernier mot tapé est toujours traité comme un début de mot.
- Classement : titre égal > tous les mots en début de mot > mots contenus > avec faute ; à égalité, titre le
  plus court, puis ordre alphabétique. Résultat stable (même requête, même ordre).
- **Surnoms** : petite table dans le code (`habs`, `ch`, `canadien` → `canadiens` ; `barca`, `fcb` →
  `barcelone`/`barcelona` ; `psg` → `paris` ; `mtl` → `montreal` ; etc.). Un mot tapé qui est un surnom cherche
  aussi ses équivalents. Table volontairement courte, à compléter à l'étape 6 (sport).
- Au plus 60 résultats affichés.
- Performance : index en mémoire par section (mots distincts → éléments), construit hors du fil principal et
  reconstruit quand le catalogue ou les préférences changent. Cible : < 100 ms pour 50 000 titres, vérifiée
  par un test avec un catalogue inventé de cette taille. La recherche est annulée et relancée à chaque lettre
  (pas de résultat en retard affiché sur une requête plus récente).

### 6.2 Ce qui est cherché

| Section | Source | Filtre |
|---|---|---|
| Direct | chaînes du cache local (déjà complet), regroupées par les groupes de copies existants | catégories et chaînes masquées du profil et de « tout le monde » exclues |
| Séries | `series_item` du cache local | catégories de séries masquées exclues |
| Films | `movie_item` du cache local | catégories de films masquées exclues |

Un titre présent dans plusieurs catégories n'apparaît qu'une fois ; il est écarté si **une** de ses catégories
connues est masquée (« masqué gagne », comme à l'accueil Films). Les préférences inconnues (pas encore
chargées) = aucun résultat et aucun téléchargement.

### 6.3 Remplissage du catalogue en arrière-plan (séries et films)

- Démarre quelques secondes après l'arrivée sur l'accueil d'un profil, une fois les préférences connues.
- Parcourt les catégories **non masquées** dont la liste n'est pas fraîche (mêmes durées de fraîcheur que
  l'existant), **une demande à la fois**, avec une courte pause entre deux, en réutilisant
  `SeriesRepository.seriesOf` / `MovieRepository.moviesOf` (qui écrivent déjà le cache et refusent une catégorie
  masquée). Priorité à la section affichée.
- Un seul remplissage par processus, partagé entre profils ; une catégorie masquée entre-temps n'est pas
  demandée ; une catégorie en échec est réessayée au prochain passage, pas en boucle.
- Ne bloque jamais une demande de l'écran (rangée d'accueil, fiche) : celles-ci passent devant.
- Continue pendant la lecture (ce sont de petites demandes de liste, pas de la vidéo), mais s'arrête à la mise
  en veille / `onStop` et reprend au retour.
- Publie son avancement (`faites / total` par section) pour la ligne « Catalogue en préparation ».
- Effet secondaire voulu : « Ajoutés récemment » des Films devient complet.

Rien n'est envoyé au compte en ligne. Aucun identifiant ni adresse de flux dans les journaux.

### 6.4 Écran et navigation (`apps/tv`)

- Nouveau paquet `search/` : `SearchViewModel` (texte, résultats, avancement, section), `SearchScreen`.
- Bouton Rechercher dans la barre du haut partagée ; la recherche s'ouvre par-dessus l'accueil de la section,
  comme une fiche. Les lecteurs existants sont réutilisés tels quels (direct, fiche série, fiche film).
- États « chargement », « vide », « erreur » toujours focalisables ; aucun bouton focalisé qui disparaît sans
  que le focus soit redonné (leçon M3 de la 4B).

## 7. Tests attendus

- Moteur : accents, casse, mot partiel, ordre des mots, une faute, deux fautes sur mot long, pas de faute sur
  mot court, surnoms, classement, doublons, limite, stabilité, performance sur 50 000 titres.
- Sources : masquage profil et « tout le monde », préférences inconnues, titre dans une catégorie masquée et
  une autre visible, chaînes regroupées.
- Remplissage : jamais une catégorie masquée, une demande à la fois, priorité aux demandes de l'écran, reprise
  après échec, arrêt et reprise, avancement.
- Écran (Robolectric, télécommande) : ouvrir depuis chaque section, taper, descendre aux résultats, OK, Retour
  qui rend le focus, résultats qui changent sous le focus, bouton Parler absent si le service manque.
- Suites complètes partagé / télé / backend / web vertes avant la version.

## 8. Laissé au constructeur

Structure exacte de l'index, découpage des fichiers, durée de la pause entre deux demandes (ordre de grandeur :
une demi-seconde), anti-rebond de la frappe, présentation fine dans l'esprit des écrans existants, contenu
exact de la table des surnoms.

## 9. À vérifier sur la vraie télé (impossible ici)

Le clavier Google TV sur le champ, la reconnaissance vocale réelle, la durée du premier remplissage chez ce
fournisseur, la fluidité pendant le remplissage.

## 10. Livraison

Branche `plan-5-recherche`, plan détaillé en tâches avec tests, relecture indépendante, version 0.6.0 signée
avec la clé existante, **accord du propriétaire avant la mise en ligne** (APK et `version.json` seulement : pas
de migration, pas de nouvelle page), essai TCL, puis fusion dans `main`.
