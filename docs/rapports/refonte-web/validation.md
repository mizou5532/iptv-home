# Refonte web mobile — réalisation et validation

16 novembre 2026. Branche `refonte-web-profils`, base `fc0b9a3` de `plan-7-themes`. Demande et validation visuelle reçues dans la conversation : thème ivoire/vert, variante compacte, puis instruction de commencer. Aucun déploiement demandé ni effectué.

## Réalisation

- `profile-view.ts` : liste des profils et espace individuel ; réutilisation du chargement et des écritures existants, choix de départ, accès photo/équipes, passage à Organiser. Les noms sont échappés ; chargements tardifs ne réécrivent pas un écran démonté.
- `shell.ts` et `icons.ts` : en-tête et navigation basse Profils/Organiser/Réglages ; icônes Phosphor, pas de dessin artisanal ni de nouvelle police externe.
- `main.ts` : intégration aux services existants ; mêmes accès et RPC. QR en attente ouvre Réglages. Les champs après écriture sont ceux du formulaire d’origine, pas une recherche globale dans une nouvelle page.
- `org/view.ts` : portée initiale + notification au shell, nettoyage recherche au départ ; profil disparu = message explicite, jamais repli silencieux vers une autre personne. Copies/catégories/favoris/playlists et confirmations préservés.
- `ui.css` et HTML : thème clair ivoire/vert compact, focus visible, safe areas, navigation basse, classes existantes et hauteurs de virtualisation préservées. Aperçu TV dans l’éditeur photo garde volontairement le style réel de l’app télé.
- Démo : mise à jour des profils et équipes en mémoire, même organisation fictive ; client Supabase non instancié. Association, compte et envoi de photos indisponibles en démo et expliqués. Pas de prétendue photo envoyée.

## Vérifications exécutées

1. Tests de portée initiale rouges sur le choix p2 (ancien moteur ouvrait p1), puis verts. Nouveau module profils absent à la première compilation de test, puis parcours choix/écriture/rollback/erreur/démontage testés.
2. Navigation démo et code QR rouges avant intégration, puis verts.
3. Relecture indépendante sur le diff complet : quatre défauts importants corrigés. Régression association suspendue puis navigation reproduit TypeError sur champ disparu ; tests contraste, écriture de départ pendant aller-retour et profil supprimé rouges puis verts.
4. `npm test -w web/config -- --maxWorkers=1` : **18 fichiers, 172 tests verts, aucun échec**.
5. `npm run build -w web/config` : TypeScript et Vite réussis. Aucun changement de délai de test ni assertion retirée.
6. `git diff --check` propre.

Tests backend/Android non relancés : aucun code de ces composants modifié. Tous les contrôles fonctionnels se font sur doubles locaux/démo, jamais en production.

## Limite bloquante de validation visuelle

`cua.getBrowser` et `cua.getState` échouent avant ouverture de navigateur (`the gaas-browser node_repl sandbox cannot be disabled`). `sites-preview start` ne parvient pas à ouvrir le relais. Le serveur Vite local sur 4173 répond HTTP200, sans preuve du rendu ni du fonctionnement du canvas photo. **Aucune capture mobile/bureau ni essai tactile réel revendiqués**. `web/config/design-qa.md` reste `final result: blocked`.

## Décisions et suite

- Conserver TypeScript/Vite et les RPC existantes plutôt que refaire un prototype séparé : la refonte est branchée dans le vrai site.
- Écritures de départ en vol suivies par client DB : pendant un retour à Profils, attendre le résultat avant relecture pour éviter des boutons périmés. Boutons de profil occupés pendant l’écriture.
- Aucune modification de l’APK, de version.json ou de migration. Pas de fusion main ni publication. Avant livraison : inspection dans navigateur disponible, correction de tout défaut de rendu, accord distinct de publication, puis publication web seule en préservant exactement les 4 règles avatars permanentes.


## Aperçu demandé par le propriétaire

Accord explicite reçu pour mettre une version d’essai séparée à disposition sur iPhone. Ajout de `VITE_DEMO_ONLY=1`, avec test rouge puis vert prouvant que retirer `?demo` ne crée pas de client distant. **173 tests web verts** ; build démo réussi (entrée ~69 Ko, hors Supabase inutilisé). Paquet autonome dans `/workspace/iptv-work/refonte-web-demo` : index + assets + configuration Vercel sans réécritures production, en-tête noindex, aucun APK/version.json.

Déploiement non encore créé : `get_project(iptv-home-config)` reçoit403 pour absence de grant sur l’équipe. Procédure officielle `authorize_team_access` exécutée ; lien éphémère transmis au propriétaire. Reprendre sur la même connexion après son consentement, cible preview exclusivement. Le site public et ses fichiers restent inchangés.


## Aperçu mis en ligne après réautorisation Vercel

Accès équipe accordé par le propriétaire. Paquet statique autonome déployé sur le projet existant `iptv-home-config` en cible API `staging` (aperçu, jamais production). ID `dpl_AM3Y9TZuLHq8LuTCW3cNZZRJErit`, état READY, URL `https://iptv-home-config-fq936star-hamzahlal86-9766s-projects.vercel.app`.

Le lien de partage temporaire délivré par Vercel permet l’essai iPhone sans modifier la protection générale ; son jeton n’est pas conservé dans Git. HTML HTTP200, 4 assets HTTP200 et contenus textuels strictement identiques aux fichiers locaux. En-tête noindex présent. L’alias public habituel pointe toujours vers la production `dpl_99EPgT7nXsaa7i1CGgAjWL7gmFCj`, vérifié par get_alias. Pas de manipulation Supabase ni APK. À tester par le propriétaire : profil, onglet de départ, équipes, organisation et rendu mobile. Photo et association restent indisponibles en démo. Aucune validation visuelle navigateur revendiquée.


## Essai séparé avec informations réelles

Demande explicite suivante du propriétaire : essayer la refonte avec ses vraies informations en gardant une sauvegarde/branche différente. Branche `refonte-web-profils` conservée, aucun merge. Tag annoté et poussé `sauvegarde-site-avant-refonte-2026-11-16` sur `fc0b9a3`, code de la0.10 avant refonte. Le site habituel reste le repli.

Suites exécutées de nouveau : 173 tests web verts ; build normal `VITE_DEMO_ONLY=0` réussi. Paquet statique complet dans `/workspace/iptv-work/refonte-web-live`, index + assets + vercel.json/noindex uniquement, aucune réécriture vers les anciens assets, aucun APK ni version.json. Aucune modification produit nécessaire : authentification et API réelles étaient déjà conservées dans le build normal.

Déploiement Vercel staging READY `dpl_ApNYhSB7trKLL6wjxz8i9KnjUwQX`, URL `https://iptv-home-config-abg1jtuth-hamzahlal86-9766s-projects.vercel.app`. Lien de partage protégé transmis à l’utilisateur ; jeton temporaire non conservé dans Git. HTML et4 assets HTTP200, strictement identiques au paquet local. Alias public habituel vérifié, toujours affecté à `dpl_99EPgT7nXsaa7i1CGgAjWL7gmFCj`.

Le propriétaire doit se connecter lui-même avec son compte habituel ; aucun identifiant demandé à l’IA et aucune donnée réelle lue pour vérifier. Les réglages modifiés via cet aperçu concernent son vrai compte/la télé, information communiquée avant le déploiement. Ce qui est sauvegardé est le code précédent et son déploiement, pas un instantané de la base de données. Aucun changement Auth, droits, stockage, migration ouAPK. Les essais réels/rendu restent à faire par le propriétaire ; cela ne clôt pas la QA visuelle bloquée côté outil.


## Correctif icônes après capture iPhone du propriétaire

Symptôme : tous les pictogrammes sont des rectangles pleins, photo réelle bien chargée. Le bundle Vite contient des URLs `data:image/svg+xml` avec apostrophes littérales (attributs xmlns/viewBox/fill), injectées par icons.ts dans `style="--icon:url('…')"`. Les apostrophes ferment prématurément la chaîne CSS. Le masque est invalide, seul le fond currentColor se dessine. Ce n’est pas un téléchargement de logos manquants ni un problème du compte.

Correction locale unique : importer les SVG Phosphor en `?raw`, afficher leurs tracés fournis par la bibliothèque, dimensionner par CSS et garder fill=currentColor. Suppression du masque, de sa data URI et du fond rectangulaire. Test rouge puis vert `icons.test.ts` sur tous les11 usages, tracé SVG présent et sans dépendance à l’ancien attribut CSS. **174 tests web verts**, build normal Vite réussi, git diff --check propre. Le rendu iPhone sera à confirmer par le propriétaire après mise à jour de l’aperçu ; aucune vérification visuelle inventée.


Le correctif70fb6fc est publié sur un nouvel aperçu connecté : staging READY `dpl_8KBSzMjm45RRy6GPY4Cjag6hH8e3`, `https://iptv-home-config-ln27aqf7f-hamzahlal86-9766s-projects.vercel.app`. Lien de partage temporaire transmis sans enregistrer son jeton dans Git. HTML et4 assets HTTP200, contenus strictement identiques au paquet local. Domaine habituel toujours sur `dpl_99EPgT7nXsaa7i1CGgAjWL7gmFCj`, vérifié. Les anciennes URLs restent sur leur ancien déploiement ; se reconnecter sur le nouveau lien si nécessaire. Pas de modification des données ou de l’app télé. Affichage iPhone corrigé à confirmer par le propriétaire.


## Validation propriétaire et intégration main

Après essai de l’aperçu aux pictogrammes corrigés, le propriétaire confirme « Oui ça semble bien fonctionner » et demande de l’enregistrer dans la branche principale. Fusion complète de `refonte-web-profils` dans `main` (main était restée sur a220b2d ; le socle publié0.10.0 fait partie de l’ascendance de la refonte). Aucun travail distant divergent à écraser. Branche de refonte et tag de sauvegarde conservés. Cette intégration Git ne déploie pas le site habituel. La QA automatisée dans le navigateur reste non effectuée ; seul le retour positif du propriétaire sur son iPhone est enregistré.


## Passage demandé à l’adresse habituelle

Après fusion Git, le propriétaire signale qu’il ne voit toujours pas la refonte. Publication à l’adresse habituelle préparée en réponse : fichiers strictement identiques à l’aperçu corrigé validé, servis directement par Vercel. Conservation exacte des métadonnées publiques APK0.10.0 (code20, taille et SHA256) lues sur le site habituel avant publication, car la copie Git de version.json était restée à0.8.1. Réécriture de téléchargement APK conservée ; ancienne réécriture des assets vers Supabase retirée pour servir les nouveaux fichiers locaux. Aucun APK ou stockage Supabase modifié. Ancien déploiement dpl_99EPgT7nXsaa7i1CGgAjWL7gmFCj conservé pour retour arrière.
