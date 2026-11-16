# Noms et pictogrammes des appareils

Demande approuvée : nom par défaut, modification du nom et pictogramme par télé. La dernière connexion est abandonnée ; suivi de lecture hors de cette étape.

Conserver le style ivoire/vert validé. Dans Réglages, chaque appareil affiche son pictogramme, son nom, Modifier et Retirer (confirmation actuelle conservée). Modifier ouvre un formulaire en ligne : nom1–40 caractères, choix Télé/Salon/Chambre/Bureau/Autre, Enregistrer/Annuler. Échec : conserver saisie et ancien affichage ; réponse tardive ne modifie pas un autre écran. Le compte propriétaire seul peut modifier un appareil de son foyer. Aucun accès au compte propriétaire en tant qu’appareil.

Migration additive0016 : device_icon sur household_members, défaut tv ; RPC set_device_settings(h,device,name,icon). Validation et contrôle propriétaire côté serveur. Nom existant conservé, pictogramme initial Télé. Pas de nouvelle règle RLS large. La télé détecte fabricant/modèle uniquement lors d’un nouvel appairage ; aucun renommage automatique d’une télé existante ni écrasement du nom personnalisé. Ancien APK compatible, future mise à jour TV nécessaire pour le modèle détecté.

Implémentation : éditeur DOM dédié, RPC existantes préservées, nom par défaut Kotlin pur testé et branché dans MainActivity. Démo en mémoire. Tests backend PostgreSQL jetable (droits, foyer, validations), web et Kotlin. Branche parametres-appareils. Publication/migration production distinctes une fois le résultat prêt ; aucun test en production. Ni date d’activité, ni version/modèle détaillés, ni statut de lecture ajoutés dans cette étape.
