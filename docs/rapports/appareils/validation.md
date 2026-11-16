# Paramètres appareils — journal de réalisation

Branche parametres-appareils depuis main0c1bdee. Demande : noms par défaut, renommer, pictogrammes, sans dernière connexion. Précision suivante : récupérer les modèles des télés déjà associées, actuellement toutes nommées Télé du salon. Nécessite ouverture de chaque app après mise à jour ; aucune déduction de pièce possible.

Backend : migration additive20261116210619_device_settings,8 tests (droits, validations, ancien appareil, report modèle et protection des noms manuels). RED2 puis4 échecs attendus avant SQL ; GREEN **187 tests backend sur15 fichiers** dans PostgreSQL16 jetable Docker, jamais la production.

Web : module devices.ts + device-view.ts, éditeur inline dans Réglages, noms/pictogrammes avec attente et erreurs, retrait confirmé conservé. Tracés Phosphor directs comme correctif iPhone. Tests RED module absent, GREEN **178 tests web /20 fichiers**, build TypeScript/Vite réussi.5 pictogrammes tv/sofa/bed/desk/other.

En cours : Kotlin modèle automatique et remontée asynchrone après appairage déjà existant, identifiant visible sur l'écran des profils. Tests écrits ; implémentation et compilation à terminer. Aucun déploiement, aucune migration réelle, aucun changement aux appareils du propriétaire. Les artefacts de CLI Supabase `.temp` restent locaux.
