# Design QA — refonte mobile ivoire/vert

final result: blocked

Référence : dernière image compacte ivoire/vert validée par le propriétaire dans cette conversation. Avatar, typographie compacte, sélecteur de départ, organisation, photo, équipes et navigation basse sont traduits dans le code.

L’outil cua échoue à l’initialisation : `the gaas-browser node_repl sandbox cannot be disabled` (ouverture URL et inventaire des surfaces). Le relais `sites-preview` échoue également (`timed out waiting for relay readiness`). Aucune capture réelle de l’application, comparaison de pixels ou validation tactile/focus/rendu mobile-bureau n’est revendiquée.

Vite local 4173 répond HTTP200 ; ce contrôle et la compilation ne remplacent pas la validation navigateur. Les comportements sont couverts par Vitest/jsdom, sur données inventées uniquement.

À vérifier dès qu’un navigateur est disponible : 390×844 et 320px, desktop 1280px, débordements, navigation clavier/focus, navigation basse et safe area iPhone, catégories virtualisées, formulaires et éditeur photo (canvas/GIF). Ne pas publier tant que le rendu n’est pas validé.

## Retour du propriétaire

16 novembre : essai réel sur iPhone, défaut des pictogrammes signalé puis corrigé. Le propriétaire confirme ensuite « Oui ça semble bien fonctionner » et autorise l’intégration Git dans main. Retour utilisateur positif enregistré ; aucune comparaison visuelle automatisée réalisée.
