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
