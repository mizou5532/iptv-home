# B1 — Organiser depuis le téléphone et nouvel écran du direct

*Étape 3, partie B1 · 14 novembre 2026 · conception validée par le propriétaire dans la conversation (proposition « Organiser », découpage B1/B2, option 2 pour les thèmes).*

Document parent : `docs/superpowers/specs/2026-11-14-player-iptv-design.md` (§6.3 Direct, §6.8 Organisation, §6.9 Synchro). Maquettes : https://claude.ai/artifact/F7jLeEDSvJYRa69UMLh5Y4 (maquette 3 = direct, maquette 7 = organisation ; privées, lisibles avec l'outil Artifact `read`).

## 1. Pourquoi

La partie A marche sur la TCL (installation, QR, profils, chaînes, lecture, 2ᵉ ouverture très rapide), mais :

- **organiser avec la télécommande est pénible** : masquer des centaines de catégories, ranger ses favoris, faire des playlists. Le propriétaire veut faire tout ça **sur son iPhone**, profil par profil, sans forcément y lire de vidéo ;
- **l'écran du direct est « vraiment moche »** : il doit ressembler aux maquettes.

Réussite : le propriétaire, sur le divan, ouvre la page sur l'iPhone, choisit un profil, coche les quelques catégories qu'il garde, touche « tout masquer sauf ça », met RDS, TVA Sports et Sportsnet en favoris dans son ordre, crée une playlist « Matchs » ; quelques secondes plus tard la télé, sur ce profil, s'ouvre sur ses favoris, puis ses playlists, puis ses catégories dans son ordre, sans rien de ce qu'il a masqué. Et l'écran est beau.

## 2. Objectifs

1. **La télé publie une copie légère du catalogue du direct** dans le compte en ligne : catégories (toutes, y compris celles masquées pour tout le monde) et chaînes (noms, numéros, catégorie ; seulement pour les catégories que la télé télécharge). Pas de vidéo, pas d'adresse de flux, pas d'identifiants.
2. **Onglet « Organiser » sur la page de configuration** (`web/config`, même connexion) :
   - sélecteur de profil en haut, plus une entrée « Tout le monde » (propriétaire) ;
   - **Catégories** : liste avec case « affichée », recherche dans les noms, glisser pour l'ordre, action groupée **« Tout masquer sauf ce que j'ai coché »** (et l'inverse « tout réafficher ») ;
   - toucher une catégorie → ses chaînes : ⭐ favori, ajouter à une playlist, masquer la chaîne ;
   - **Favoris** : liste ordonnée, glisser pour l'ordre, retirer ;
   - **Playlists** : créer, renommer, supprimer, ordonner les playlists et leurs chaînes, ajouter des chaînes par la recherche ;
   - **Recherche** de chaînes dans tout le catalogue publié (insensible à la casse et aux accents ; « rds » trouve toutes les RDS et leurs copies).
3. **Masquer « pour tout le monde »** (propriétaire) : la catégorie disparaît pour tous les profils et la télé ne la télécharge plus (règle existante de la partie A). La réafficher la fait revenir au rafraîchissement suivant.
4. **La télé suit l'organisation du profil choisi** :
   - colonne de gauche : **Favoris** (si non vide), puis chaque **playlist**, puis les **catégories** dans l'ordre du profil (sinon l'ordre « tout le monde », sinon celui du fournisseur) ;
   - rien de ce qui est masqué (pour tout le monde ou pour ce profil) n'apparaît ;
   - un changement fait sur le téléphone apparaît sur la télé **en moins de ~5 s** quand l'écran du direct est ouvert, et au plus tard à la prochaine ouverture du profil ;
   - hors ligne : la télé utilise la dernière organisation connue (gardée en local).
5. **Nouvel écran du direct sur la télé**, dans l'esprit de la maquette 3, **thème sombre seulement** : colonne de sections/catégories, liste de chaînes avec numéro et logo, focus très visible, gros texte lisible à 3 m, animations sobres. Les couleurs, polices et formes passent par des **jetons de thème** pour que l'étape 7 ajoute clair et fun sans refaire l'écran.
6. **Mise à jour de l'app** (0.2.0) installable par-dessus la 0.1.0 (même clé), par le même chemin Downloader ; même procédure de dépôt de l'APK (compartiment Supabase `apk`, règle d'écriture temporaire supprimée après).

## 3. Non-objectifs (B1)

- Lire des vidéos sur le téléphone.
- Thèmes clair et fun, tailles d'affichage (étape 7).
- Regroupement des copies, guide maintenant/ensuite, aperçu, zapping, « Prendre la main » (B2).
- **Bascule automatique vers une autre copie** : repoussée **à la toute fin du projet** à la demande du propriétaire ; d'ici là, changement de chaîne manuel.
- Organiser films et séries (étape 4, même page).
- Réordonner les chaînes **à l'intérieur** d'une catégorie du fournisseur (les favoris et playlists servent à ça).
- Organiser depuis la télécommande (appui long, etc.).
- Comptes séparés pour la sœur et le frère : c'est le compte du propriétaire qui organise tous les profils.

## 4. Anti-objectifs (échec même si « ça marche »)

- La télé devient plus lente à ouvrir ou à faire défiler (la 2ᵉ ouverture doit rester « très très rapide ») ; la publication du catalogue ne doit jamais bloquer l'écran ni couper une lecture.
- Une catégorie masquée pour tout le monde est téléchargée chez le fournisseur.
- Un identifiant Xtream ou une adresse de flux se retrouve dans la copie du catalogue, un journal ou la page.
- La page devient inutilisable avec des centaines de catégories et des dizaines de milliers de chaînes (doit rester fluide sur iPhone).
- Le quota gratuit de Supabase est menacé (taille de la base, Realtime).

## 5. Contraintes

- Fournisseur en **http** : la page (https) ne peut pas le lire ; les serveurs en ligne sont refusés par le fournisseur. Seule la télé, à la maison, parle au fournisseur.
- Supabase gratuit : base 500 Mo, Realtime limité. Catalogue du direct estimé à quelques dizaines de milliers de chaînes.
- Le §6.9 du document parent disait « le catalogue n'est pas synchronisé » : **décision modifiée** pour le direct seulement (copie légère, noms et numéros).
- Logos des chaînes : adresses http du fournisseur → affichés sur la télé seulement, pas sur la page (contenu mixte).
- Sécurité existante : RLS partout, écritures sensibles par fonctions, une seule maison, jamais la clé `service_role`.
- Tables existantes : `favorites` (sans position), `playlists` (avec position), `playlist_items` (avec position), `hidden_items` (foyer ou profil, `live_category` / `channel`), `category_order` (`live`, foyer ou profil). Toute évolution passe par une **nouvelle migration**.

## 6. Approches considérées

| | Approche | Pour | Contre |
|---|---|---|---|
| **A — choisie** | La télé dépose une copie légère du catalogue du direct dans Supabase ; la page la lit et écrit les préférences ; la télé suit les préférences | Marche avec un simple navigateur ; réutilise la synchro et la page existantes ; pas d'abonnement Apple | Quelques dizaines de milliers de lignes dans la base ; la liste du téléphone n'est à jour qu'après un rafraîchissement de la télé |
| B | Le téléphone lit le fournisseur directement | Rien à stocker | Impossible : le navigateur bloque l'http, et un relais en ligne est refusé par le fournisseur |
| C | Le téléphone parle à la télé sur le Wi-Fi | Liste toujours fraîche | Une page https ne peut pas joindre un serveur http local ; il faudrait une app iPhone native (abonnement Apple refusé) |

Télécommande : l'app **Google TV** sur iPhone sert déjà de télécommande et de clavier ; rien à construire.

## 7. Décisions déjà prises

- Approche A. La page « Organiser » vit dans `web/config` (même connexion propriétaire).
- Thèmes : **option 2** — B1 en sombre seulement, construit sur des jetons de thème ; clair et fun à l'étape 7.
- Bascule automatique : à la toute fin du projet.
- Découpage : B1 (ce document) puis B2.

## 8. Laissé au constructeur (avec mes propositions)

- **Schéma de la copie du catalogue** : proposition `live_catalog_category(household_id, id, name, position, updated_at)` et `live_catalog_channel(household_id, id, category_id, name, number, position)`, lecture par les membres, écriture par les appareils du foyer (fonction `security definer` ou RLS), remplacement **par catégorie** et **seulement si elle a changé** (empreinte), envoi par lots, en arrière-plan après le rafraîchissement local.
- **Position des favoris** : nouvelle colonne `position` (migration).
- **Notification de la télé** : proposition d'une petite table « révision des préférences » par foyer/profil, incrémentée par déclencheur sur `favorites`, `playlists`, `playlist_items`, `hidden_items`, `category_order`, et seule publiée dans Realtime ; la télé relit les préférences du profil quand la révision change. Repli : relire à l'ouverture du profil.
- **Glisser pour l'ordre sur iPhone** : poignée tactile, ou boutons monter/descendre si le glisser est peu fiable ; enregistrement groupé.
- **Grandes listes sur la page** : rendu virtualisé ou paginé, recherche côté client sur les noms déjà chargés ou côté serveur (index trigramme) selon le volume réel.
- **Écran télé** : détail de la mise en page et des animations d'après la maquette 3, sans l'aperçu.
- **Comportement si la télé n'a encore rien publié** : la page explique « Ouvre l'app sur la télé une fois pour envoyer la liste des chaînes ».

## 9. Règles précisées après relecture (mes choix, à corriger si besoin)

1. **« Masqué gagne. »** Une chaîne favorite ou dans une playlist n'apparaît pas sur la télé si elle est masquée (elle-même ou sa catégorie, pour tout le monde ou pour ce profil) ou si elle n'existe plus chez le fournisseur. Sur le téléphone, elle reste visible en grisé avec la raison (« catégorie masquée », « n'existe plus ») et peut être retirée.
2. **Copie du catalogue et masquage pour tout le monde** : quand une catégorie est masquée pour tout le monde, la télé arrête de la télécharger mais **ne supprime pas** ses chaînes déjà publiées (copie figée), pour que le téléphone puisse toujours la montrer, la réafficher et nommer les favoris qu'elle contient. Une catégorie disparue chez le fournisseur est retirée de la copie avec ses chaînes.
3. **Portées** : masquer une chaîne existe pour ce profil **et** pour tout le monde (propriétaire). « Tout masquer sauf ce que j'ai coché » marche dans les deux portées ; en « Tout le monde », une confirmation indique combien de catégories ne seront plus téléchargées.
4. **Ordre des catégories sur la télé** : ordre du profil s'il existe, sinon ordre « tout le monde », sinon ordre du fournisseur ; les catégories absentes de l'ordre enregistré (nouvelles chez le fournisseur) s'ajoutent **à la fin**, dans l'ordre du fournisseur ; les identifiants qui n'existent plus sont ignorés.
5. **Identité d'une chaîne** : le `stream_id` Xtream (comme dans la partie A). Risque : si le fournisseur renumérote ses chaînes, les favoris se perdent. À vérifier pendant l'essai de B1 (comparer les identifiants publiés d'un jour à l'autre) ; une reconnaissance par nom n'est pas dans B1.
6. **Volume** : prévoir jusqu'à **100 000 chaînes** et 2 000 catégories ; tester la page et la publication avec un jeu de données de cette taille. La télé publie aussi les nombres réels, pour ajuster ensuite.
7. **Mise en production** : comme pour la partie A, Claude applique la nouvelle migration au projet Supabase, publie la page et dépose l'APK (règle d'écriture temporaire supprimée après) ; le propriétaire installe la mise à jour avec Downloader et fait l'essai.
