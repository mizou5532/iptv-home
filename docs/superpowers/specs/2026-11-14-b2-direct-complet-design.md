# B2 — Le direct complet

*14 novembre 2026 · Proposition à valider avant tout code.*

Références : `PASSATION.md`, conception générale, conception B1 et feuille de route dans ce dépôt. La passation fait foi : B1 est essayé avec succès, fusionné dans main et installé en version 0.2.0. Ce document décrit la suite, pas une fonctionnalité déjà livrée.

## 1. Intention et réussite

Trouver sa chaîne rapidement, essayer ses copies facilement et consulter le programme sans quitter le direct. Conserver la rapidité de B1, l'organisation faite sur l'iPhone et la limite d'une seule lecture du fournisseur.

Exemple fictif : le profil ouvre ses favoris, trouve « RDS — 3 sources », appuie sur OK pour regarder dans l'aperçu, choisit une autre source si nécessaire, puis appuie à nouveau sur OK pour passer en plein écran. Retour conserve la lecture dans l'aperçu. Le téléphone permet de corriger les regroupements.

## 2. Décisions prises avec le propriétaire

- L'aperçu démarre uniquement sur OK, jamais au simple passage sur une chaîne.
- Les copies peuvent être dans une même catégorie ou dans plusieurs catégories.
- Regroupement automatique prudent, avec corrections sur l'iPhone : séparer un mauvais regroupement ou ajouter une copie oubliée.
- Choix de source manuel ; la bascule automatique reste à la toute fin du projet.

Les précisions suivantes sont mes propositions, soumises à validation avec ce document.

## 3. Approches considérées

**A — retenue par le propriétaire : automatique avec corrections.** L'app reconnaît les copies avec confiance et laisse les cas douteux séparés. Le téléphone permet de corriger. Peu de rangement initial, mais il faut gérer des corrections durables.

**B — écartée : tout regrouper manuellement sur l'iPhone.** Plus prévisible, mais trop de travail sur un gros catalogue.

On conserve les fondations actuelles : Kotlin partagé, Media3 sur la télé, page TypeScript existante et Supabase. Aucun nouveau service en ligne.

## 4. Regroupement et choix des sources

### Reconnaître sans confondre

- Regrouper à travers les catégories, à partir des noms et d'indices concordants du catalogue. La catégorie seule ne décide pas de l'identité.
- Normaliser casse, accents, espaces et suffixes techniques connus (ex. HD/FHD), sans supprimer indistinctement les chiffres, langues, régions, mentions de décalage horaire ou variantes éditoriales. RDS et RDS 2 restent distinctes.
- Aucun rapprochement flou agressif. Un identifiant de guide commun est un indice, pas une preuve suffisante à lui seul.
- Ne pas ouvrir de vidéo pour détecter ou tester les copies. Aucun accès au vrai fournisseur depuis les outils cloud.
- Les règles et leur version sont testées sur des noms fictifs ; les règles détaillées restent au constructeur. En cas de doute, conserver des entrées séparées.

### Affichage et masquage

- Une ligne par groupe dans une section, avec son nom et le nombre de sources utilisables pour ce profil.
- Une catégorie contient un groupe dès qu'elle contient au moins une de ses sources visibles. Le choix des sources donne accès à toutes les sources visibles du groupe, y compris dans d'autres catégories.
- « Masqué gagne » avant tout affichage et toute lecture : chaîne ou catégorie masquée pour le profil ou le foyer = source exclue. Aucun regroupement ne la réintroduit.
- Une catégorie masquée pour le foyer n'est jamais téléchargée. Les regroupements peuvent être corrigés à partir de la copie figée du téléphone, sans provoquer de téléchargement.
- Un groupe sans source visible disparaît de la télé. Une source unique reste une chaîne ordinaire.
- Prévoir un réglage par profil « Regrouper les copies », activé par défaut ; le désactiver affiche les chaînes séparées sans effacer les corrections.

### Favoris et listes existants

- Conserver les favoris, listes et identifiants de chaînes existants ; aucune conversion destructive.
- Dans les favoris ou une liste, plusieurs sources appartenant au même groupe donnent une ligne, à la position de la première source présente. Leurs autres sources visibles restent accessibles par le sélecteur.
- Les favoris continuent de désigner les chaînes concrètes enregistrées. Le groupe est une présentation : une autre copie n'est pas ajoutée en favori automatiquement. Si le seul favori est masqué ou disparu, il ne fait pas apparaître le groupe par substitution.
- La page garde les actions B1 sur les chaînes concrètes. Les corrections de groupes n'ajoutent ni ne retirent de favoris.

### Choisir une source

- Bouton « Sources » accessible depuis l'aperçu et les commandes du plein écran ; nom original, catégorie, qualité si connue et source en cours.
- Au premier lancement : dernière source ayant réellement produit une image sur cette télé pour ce profil et ce groupe, si elle reste visible ; sinon ordre stable du fournisseur, avec identifiant comme départage. Pas de priorité de qualité inventée.
- Changer de source arrête et libère l'ancienne lecture avant d'ouvrir la suivante. Le groupe conserve sa place dans la liste.
- Une erreur affiche « Cette source ne répond pas » et propose « Choisir une autre source » et « Réessayer ». Aucun passage automatique à une autre source.
- Mémorisation locale après la première image, pas simplement après un clic. Une disparition ou un masquage invalide le choix mémorisé.

## 5. Corriger depuis l'iPhone

- Ajouter « Copies des chaînes » dans l'onglet Organiser existant, accessible au propriétaire.
- Corrections communes au foyer : ce qui constitue une même chaîne ne varie pas par profil. Les masquages et le réglage de regroupement restent propres à leurs portées actuelles.
- Rechercher une chaîne, consulter son groupe et ses catégories ; réunir des sources, retirer une source, garder une chaîne séparée ou rétablir le regroupement automatique.
- Aperçu et confirmation de la composition avant enregistrement ; aucune vidéo sur le téléphone.
- Les corrections manuelles priment sur les suggestions automatiques. Une source retirée ne revient pas automatiquement dans ce groupe au rafraîchissement suivant.
- Utiliser des identifiants de groupes stables pour les corrections, reliés aux identifiants de chaînes concrets ; une source appartient à un seul groupe effectif. Fusion et séparation sont atomiques ; les modifications concurrentes incompatibles sont signalées, sans écraser silencieusement.
- Une nouvelle copie n'est pas ajoutée automatiquement à un groupe corrigé manuellement : elle reste séparée jusqu'à correction. Dans un groupe entièrement automatique, elle suit les règles prudentes.
- Les sources disparues sont signalées sur le téléphone et exclues de la lecture. Ne jamais remplacer une source par simple ressemblance de nom si le fournisseur change ses identifiants.
- Les corrections arrivent sur la télé en environ cinq secondes quand la synchronisation fonctionne. Hors ligne : dernier état connu ; le téléphone signale un échec d'enregistrement et ne prétend pas avoir enregistré.

## 6. Aperçu et télécommande

- Conserver les colonnes sections et chaînes de B1 ; ajouter à droite une zone d'aperçu avec le nom de la chaîne en lecture, ses programmes et l'accès aux sources.
- Au départ, aucune vidéo. Premier OK sur une chaîne : lancement dans l'aperçu. OK sur la chaîne déjà en lecture : plein écran. OK sur une autre : remplacement dans l'aperçu.
- Parcourir la liste ou changer de catégorie conserve la vidéo en cours. Distinguer visuellement la chaîne parcourue et la chaîne en lecture. Les informations du panneau concernent la lecture ; avant toute lecture, elles concernent la chaîne parcourue.
- Aperçu et plein écran partagent le même lecteur et le même flux : changer la taille ne reconnecte pas au fournisseur.
- Retour depuis le plein écran revient à la liste et à l'aperçu, sur la chaîne en lecture. Dans un panneau Sources ou une confirmation, Retour ferme d'abord ce panneau.
- Depuis la liste, Retour suit la navigation B1 ; quitter le direct, changer de profil, mettre l'app en arrière-plan ou choisir « Arrêter » libère la lecture et sa réservation. Proposer une action Arrêter accessible dans le panneau.
- Plein écran : haut/bas change manuellement de chaîne dans l'ordre visible de la section de départ, sans boucler aux extrémités ; OK affiche les commandes dont Sources. Retour revient à la chaîne en lecture. Si la section disparaît après une modification, revenir aux favoris s'ils existent, sinon à la première catégorie visible.
- Si un masquage ou une révocation retire le droit de lire la source en cours, arrêter et expliquer ; ne pas changer automatiquement de source. Un simple changement de groupe ou d'ordre conserve la lecture autorisée.
- Tous les boutons sont accessibles à la télécommande, avec un focus visible et des textes français lisibles à trois mètres. Tailles personnalisables et autres thèmes restent à l'étape 7.

## 7. Guide maintenant / ensuite

- Utiliser le guide court Xtream déjà présent dans le cœur partagé ; ne pas télécharger le guide complet.
- Demander les programmes pour la source en lecture ou, avant toute lecture, la chaîne sélectionnée après un bref délai de stabilité. Annuler les demandes dépassées et limiter le cache et les requêtes simultanées.
- Afficher titre, horaires locaux de l'appareil et avancement du programme actuel, puis le suivant. Vérifier les horaires ; demander assez d'entrées pour retrouver maintenant et ensuite si la réponse contient des émissions déjà terminées.
- La source choisie détermine son guide : ne pas emprunter celui d'une autre copie susceptible d'être décalée.
- Rafraîchir à la fin du programme ou après expiration du cache, sans bloquer navigation ou lecture. L'heure locale suit les changements d'heure.
- Données absentes, invalides ou expirées : « Programme indisponible » pour la partie concernée. Aucun titre périmé présenté comme actuel. Le guide reste une information du fournisseur, sans promesse de fiabilité ni de reconnaissance des matchs.

## 8. Qui regarde et « Prendre la main »

- Brancher réellement le lecteur sur les fonctions de présence existantes : réservation avant ouverture, battements réguliers pendant aperçu et plein écran, libération après arrêt. Le client Kotlin actuel ne les expose pas encore.
- Même appareil et même lecture : passer de l'aperçu au plein écran ne crée pas de réservation supplémentaire. Changer de source ou de chaîne réutilise la place de l'appareil sans réserver deux lectures.
- Si la place est occupée : « Le salon regarde … (profil). Prendre la main ? », avec Annuler sélectionné par défaut. Aucune vidéo n'est ouverte avant la décision et l'autorisation.
- Confirmation : demander à l'ancien appareil d'arrêter, puis ouvrir le nouveau flux seulement après confirmation de cet arrêt. La suppression immédiate d'une ligne de présence ne prouve pas l'arrêt vidéo : les fonctions existantes devront être complétées si nécessaire.
- Sans confirmation d'arrêt, afficher une attente puis un échec explicite, sans démarrer de deuxième flux. Une présence périmée (seuil actuel : 45 secondes) ne prouve pas à elle seule qu'un appareil hors ligne a cessé de lire ; aucune prise de main forcée silencieuse.
- Récupération après arrêt brutal : pour une présence périmée ou un ancien appareil incapable de confirmer l'arrêt, proposer une action distincte « J'ai arrêté l'autre appareil ». Expliquer qu'il faut d'abord arrêter réellement la vidéo ou éteindre cet appareil. Après confirmation explicite du propriétaire, remplacer atomiquement la réservation périmée ou celle qui bloque et ouvrir la nouvelle lecture. Annuler reste possible. Cette récupération repose sur sa vérification ; ce n'est pas une détection automatique de l'arrêt. Une simple panne réseau ne la déclenche jamais seule.
- L'ancien appareil affiche « La lecture a été reprise sur un autre appareil ». Une révocation ou une perte de réservation confirmée arrête le lecteur, avec un contrôle de secours en plus du temps réel.
- Si Supabase devient indisponible pendant une lecture déjà autorisée, celle-ci continue comme prévu par la conception générale, sans nouvelle prise de main. Au retour du service, vérifier immédiatement les droits et la réservation ; une perte confirmée arrête la lecture.
- Sans possibilité de vérifier la disponibilité de la place, un nouveau lancement est refusé avec un message de synchronisation indisponible. C'est une proposition prudente pour respecter la connexion unique ; la navigation en cache reste disponible.
- La garantie porte sur les appareils participant à notre système. Un lecteur externe ne peut pas être arrêté par Supabase ; le fournisseur garde sa propre limite et peut refuser la lecture. Ne jamais prétendre contrôler un autre player.

## 9. Données, sécurité et compatibilité

- Conserver les tables et contrats B1 ; nouvelles migrations uniquement pour corrections de groupes, réglage par profil et éventuel protocole d'arrêt confirmé.
- Stocker noms, identifiants de chaînes et corrections ; jamais les adresses de flux ni les identifiants IPTV dans les données de regroupement, journaux, captures ou tests.
- Corrections lisibles par les membres du foyer, modifiables seulement par le propriétaire, avec RLS et fonctions autorisées. Aucun identifiant d'un autre foyer accepté. Publier les changements via la révision des préférences existante ou son extension cohérente, sans publier tout le catalogue dans Realtime.
- Le regroupement automatique doit donner le même résultat sur téléphone et télé pour une même version de catalogue et de règles. Partager un contrat et des jeux d'essai entre TypeScript et Kotlin ; la page indique quand sa copie est ancienne.
- Indexation locale et traitement en arrière-plan ; jamais de comparaison de toutes les paires de chaînes à chaque mouvement de télécommande. Maintenir le budget B1 de 100 000 chaînes et 2 000 catégories.
- Les anciennes versions ignorent les nouvelles préférences sans perdre leurs favoris. Une ancienne version incapable de confirmer l'arrêt ne permet pas la nouvelle prise de main : demander sa mise à jour, sans ouvrir une seconde lecture.
- La version 0.2.0 ne réserve aucune place : elle est invisible pour ce mécanisme. Au premier usage de B2, informer le propriétaire que toutes ses installations doivent être mises à jour et les anciennes lectures arrêtées avant de confirmer la mise en service du partage de place. Cette confirmation ne prétend pas détecter les anciens lecteurs. Tant que cela n'est pas fait, ne pas annoncer de protection entre appareils : permettre la navigation, et demander de finir la mise à jour avant de lancer une lecture B2. Après confirmation, prévenir qu'un ancien APK ou un autre player utilisé à nouveau échappe toujours au mécanisme. Les versions futures devront conserver le protocole d'arrêt confirmé.
- Même signature Android, jamais régénérée. Aucun usage de service_role. Aucun compte ni foyer créé dans Supabase de production par des tests.

## 10. Hors périmètre

Bascule automatique, films et séries, source sportive externe et recherche globale avancée, nouveaux thèmes, tailles personnalisables, organisation générale par télécommande, enregistrement et téléchargement hors ligne. La correction connue d'horodatage NULL de record_progress reste un point séparé, à planifier explicitement si elle est incluse ensuite.

## 11. Validation attendue

- Tests de regroupement dans une et plusieurs catégories : qualités, chiffres distinctifs, langues, régions, cas ambigus, exclusions et corrections persistantes ; mêmes résultats TypeScript/Kotlin.
- Favoris et listes B1 conservés ; regroupement désactivable ; aucune source masquée réintroduite ; disparition et réapparition du catalogue.
- Tests de lecture avec faux lecteur : un seul lecteur, fermeture avant changement, aucune reconnexion aperçu/plein écran, focus restauré, arrêt après révocation ou masquage.
- Guide fictif : horaires, changement d'heure, réponses malformées, émissions périmées, cache, requêtes annulées et ordre de réponses inversé.
- Postgres local : RLS, corrections et conflits atomiques, réservation concurrente, arrêt confirmé, expiration, absence d'accusé, récupération après arrêt physique confirmé, ancien client et déconnexion. Tester aussi la mise en service lorsque la version 0.2.0 est invisible et l'absence de blocage permanent après crash. Aucun test de production.
- Parcours téléphone en démonstration : créer, séparer, rétablir un groupe et conserver les favoris. Tests de volume B1 et absence de croissance non bornée du cache du guide.
- Essai sur la TCL par le propriétaire : rapidité, lisibilité, télécommande, source défaillante, aperçu/plein écran et guide avec son fournisseur. Une simulation à deux appareils valide la prise de main ; son essai réel nécessite deux installations compatibles.

## 12. Suite et points laissés au constructeur

Après validation : plan de réalisation et de tests, schéma détaillé des nouvelles migrations, limites chiffrées de cache/délais, algorithme prudent et détails de disposition. Les choix de cette conception ne valent pas autorisation de mise en production.

Branche de l'étape : `plan-3b2-direct`. Préparer et tester le code avant de demander les accords requis pour migration de production, publication de page/APK ou fusion dans main. Actualiser passation, feuille de route et journal à chaque étape ; commits et poussées sur la branche, selon les instructions du propriétaire.
