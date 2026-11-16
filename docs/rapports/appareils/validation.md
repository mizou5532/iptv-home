# Paramètres appareils — journal de réalisation

Branche parametres-appareils depuis main0c1bdee. Demande : noms par défaut, renommer, pictogrammes, sans dernière connexion. Précision suivante : récupérer les modèles des télés déjà associées, actuellement toutes nommées Télé du salon. Nécessite ouverture de chaque app après mise à jour ; aucune déduction de pièce possible.

Backend : migration additive20261116210619_device_settings,8 tests (droits, validations, ancien appareil, report modèle et protection des noms manuels). RED2 puis4 échecs attendus avant SQL ; GREEN **187 tests backend sur15 fichiers** dans PostgreSQL16 jetable Docker, jamais la production.

Web : module devices.ts + device-view.ts, éditeur inline dans Réglages, noms/pictogrammes avec attente et erreurs, retrait confirmé conservé. Tracés Phosphor directs comme correctif iPhone. Tests RED module absent, GREEN **178 tests web /20 fichiers**, build TypeScript/Vite réussi.5 pictogrammes tv/sofa/bed/desk/other.

En cours : Kotlin modèle automatique et remontée asynchrone après appairage déjà existant, identifiant visible sur l'écran des profils. Tests écrits ; implémentation et compilation à terminer. Aucun déploiement, aucune migration réelle, aucun changement aux appareils du propriétaire. Les artefacts de CLI Supabase `.temp` restent locaux.


## Identité des télés déjà associées et relecture

La remontée `report_device_identity(h,default_name)` est limitée au foyer courant et au compte de l’appareil. Le modèle public est construit depuis fabricant/modèle, sans numéro de série. Un suffixe de6 caractères de l’identifiant applicatif différencie les modèles identiques. Le nom complet est également affiché sur Qui regarde. Rapport asynchrone après affichage des profils : réseau absent, attente ou migration non encore appliquée n’empêchent pas l’usage de la télé.

Preuves : compilation shared RED sur méthodes absentes, puis **320 tests shared verts** ; compilation de tous tests Kotlin et assembleDebug réussies. Harnais JavaExec ciblé StartupViewModelTest : **14 tests verts**, dont récupération d’identité et RPC suspendue ne bloquant pas l’accueil. Ce harnais n’est pas une exécution Robolectric.

Relecture indépendante :1 défaut important sur l’aller-retour Réglages pendant sauvegarde. Régression rouge puis verte : attente par foyer/appareil conservée au-delà du montage, boutons du nouveau montage bloqués puis nom/pictogramme actualisés. Régression supplémentaire sur réactivation du bouton après réussite. **179 tests web verts**, build Vite OK. Amélioration de preuve demandée par revue : vraie migration sur une base peuplée ; test échoue si le backfill protégeant les noms est désactivé, puis passe avec migration réelle. Suite backend complète **189 tests verts** sur PostgreSQL16 local. Tous les anciens noms personnalisés sont conservés lors de cette migration.

APKrelease0.11.0/code21 en construction, certificat et métadonnées à vérifier avant publication. Aucun déploiement ou migration production, site et APK0.10.0 inchangés.


## Artefacts prêts, activation non effectuée

APK **0.11.0/code21** : `apps/tv/build/outputs/apk/release/tv-release.apk`, copie locale `/workspace/iptv-work/appareils/iptv-home-0.11.0.apk`. Taille37734731, SHA256 `d195994b1055fe3c0ce520568e4a15a775f40e3ec08f695953c59794d44def6e`. Signature vérifiée avec le certificat existant attendu `5d8877ce9c75eebe8d4a0cfb20427cf13dbf443402fc5c055608358d76e615d4`. Jamais de clé régénérée. AssembleRelease réussi. Métadonnées web préparées mais pas publiées. Paquet web autonome `/workspace/iptv-work/appareils/web`, build TypeScript/Vite réussi après mise à jour de version.json.

Validation finale :189 backend PostgreSQL16 jetable ;179 web ;320 shared ;14 StartupViewModelTest via harnais JVM ciblé conservé dans ce dossier ; compilation de tous tests Kotlin et assembleDebug/Release réussies. Aucune suite Robolectric complète ni nouvel essai physique TCL revendiqués. L’écran TV existant ajoute seulement le nom/identifiant sous les profils. Le défaut important de revue a ses régressions RED→GREEN ; la remarque sur preuve de migration peuplée est également couverte.

Ordre d’activation après accord distinct du propriétaire : appliquer **uniquement** migration20261116210619_device_settings (pas db push,0010 reste locale), publier site+APK puis version.json exact en dernier ; préserver4 règles avatars permanentes et supprimer toute règle temporaire de transfert. Le propriétaire met ensuite à jour et ouvre l’app sur chaque télé déjà associée, puis recharge Réglages. Pas de réappairage, aucun nom personnalisé écrasé. Ne pas promettre la récupération d’une télé non mise à jour ou d’une pièce non déclarée.

Le code et les artefacts sont prêts à revue/activation ; aucune migration production, aucun site/APK publié dans cette étape. Le site actuel reste la refonte fonctionnelle en0.10.0. La demande initiale autorise la réalisation ; les règles de PASSATION exigent un accord distinct de publication/migration.
