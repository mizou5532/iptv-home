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
