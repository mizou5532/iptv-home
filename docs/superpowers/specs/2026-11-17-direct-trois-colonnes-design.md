# Écran Direct : trois colonnes bien remplies

*17 nov. 2026. Choix du propriétaire sur les maquettes (https://claude.ai/artifact/Aocfa1Vmr4zFv25ceBpJd6) : proposition 1, « On va aller avec la 1. Procédez à ces modifications-là. »*

## Pourquoi

Sur la TCL, le propriétaire trouve l'écran Direct « très moche » : marges perdues à gauche et à droite, noms de
chaînes coupés, logos petits, noms bruts du fournisseur (« US| AT&T ᴿᴬᵂ ⁶⁰ᶠᵖˢ », « AT&T: 123 », « ##### AT&T ##### »),
« Programme indisponible » deux fois sous un cadre vide.

## Buts

- Occuper l'écran d'un bord à l'autre, sans changer l'organisation ni la télécommande.
- Noms lisibles : pays en pastille, qualité en étiquette, nom de la catégorie retiré devant la chaîne.
- Sous chaque chaîne visible, le programme en cours et sa barre d'avancement, quand le fournisseur donne le guide.

## Ce qui ne change pas

Les identifiants, le regroupement des copies, la recherche, le panneau « Sources » (noms d'origine, pour distinguer
les copies), le lecteur plein écran, la barre du haut (même place sur tous les onglets).

## Approches

1. **Trois colonnes resserrées (retenue)** : même structure, mesures et lignes refaites. Petit chantier, aucun
   apprentissage.
2. Pays puis catégories, mur de logos, guide horaire, rangées, vidéo plein écran : écartées par le propriétaire au
   profit de la 1.

## Conception

- **Mesures** (`LiveDims`) : marge des trois zones 28 dp au lieu de 56 (`BodyGutter` ; la barre du haut garde 56) ;
  largeurs 21 % / 38 % / 41 % ; logo 46 dp ; ligne de chaîne 64 dp.
- **Noms** (`apps/shared/.../text/LiveNames.kt`, pur, testé) :
  - `categoryName` : « US| NEWS ᴴᴰ/ᴿᴬᵂ ⁶⁰ᶠᵖˢ » → pays « US », titre « NEWS ». Préfixes reconnus : `XX|`, `|XX|`, `[XX]`.
    La casse du fournisseur est gardée (mettre « Nbc » ou « At&t » serait pire que des capitales).
  - `channelName` : retire le pays, le nom de la catégorie répété (`AT&T: `), la qualité finale ; rend l'étiquette
    de qualité (4K, FHD, HD, SD, sinon RAW).
  - `separatorTitle` : « ##### AT&T ##### » → titre de groupe.
  - Les numéros du fournisseur ne sont plus affichés.
- **Catégories** : pastille (pays, ★ favoris, ♪ playlist), en couleur d'accent pour la section choisie ; nombre de
  chaînes à droite. La barre d'accent verticale disparaît.
- **Chaînes** : logo, nom, « En lecture », étiquette de qualité à droite, programme en cours et barre fine.
  Les séparateurs deviennent des titres non focalisables, **à la même position dans la liste** (les index de
  restauration du focus et de la recherche ne bougent pas) ; ils ne comptent pas dans « 38 chaînes » et sont sautés
  par Haut/Bas en plein écran.
- **Programme par ligne** (`EpgRepository.glance`) : une demande de guide court par ligne visible, après 600 ms
  d'arrêt sur la ligne, 2 demandes à la fois (mêmes jetons que le guide principal), jamais le guide complet ni les
  copies ; réponse gardée 30 s à 5 min, erreur 1 min ; rien en arrière-plan.
- **Aperçu** : sans guide, une seule ligne « Guide non fourni pour cette chaîne. » ; avec guide, « Maintenant ·
  heures », titre, barre, « Ensuite · heure titre ».

## Laissé pour plus tard

- Nombre de copies sur le bouton « Sources ».
- Nom nettoyé dans le lecteur plein écran et la recherche (la recherche bénéficie déjà du nettoyage simple).
- Mise en casse des titres de catégories.
