# Installer l'app sur la télé (TCL QM85, Google TV)

Compte environ 10 minutes. Tu as besoin de la télécommande et de ton iPhone.

> L'adresse de la page (`iptv-home-config.vercel.app`) sera confirmée au moment
> de la mise en ligne. Si elle change, ce guide sera mis à jour.

## 1. Installer « Downloader » depuis le Play Store

1. Sur la télé, ouvre **Google Play Store** (ou cherche avec le micro : « Downloader »).
2. Installe **Downloader** (éditeur : AFTVnews, icône orange).
3. Ne l'ouvre pas encore.

## 2. Autoriser Downloader à installer une app

Google TV cache ce réglage tant que le mode développeur n'est pas activé.

1. **Paramètres** (roue dentée en haut à droite) → **Système** → **À propos**.
2. Descends jusqu'à **Build du système d'exploitation Android TV**.
3. Appuie **7 fois** sur OK dessus. Le message « Vous êtes maintenant développeur » apparaît.
4. Reviens à **Paramètres** → **Applications** → **Sécurité et restrictions** → **Sources inconnues**.
5. Active **Downloader**.

Selon la version de Google TV, « Sources inconnues » peut être dans **Paramètres → Confidentialité → Sécurité et restrictions**.

## 3. Télécharger et installer l'app

1. Ouvre **Downloader**. Accepte l'accès aux fichiers s'il le demande.
2. Dans la case d'adresse, tape :
   `iptv-home-config.vercel.app/iptv-home.apk`
3. Appuie sur **Go**. Le téléchargement fait environ 35 Mo.
4. À la fin, choisis **Installer**, puis **Terminé**. Ne choisis pas « Ouvrir » tout de suite.
5. Downloader propose de supprimer le fichier téléchargé : choisis **Supprimer**. L'app reste installée.

## 4. Ouvrir l'app

1. Accueil → **Applications** → **iptv-home**. Tu peux l'ajouter aux favoris pour l'avoir sur l'accueil.
2. La télé affiche un **QR code** et un **code à 6 caractères**, valable 10 minutes.

## 5. Configurer avec ton iPhone

1. Scanne le QR code avec l'appareil photo de l'iPhone. La page de configuration s'ouvre avec le code déjà rempli.
2. **Crée ton compte** (adresse courriel + mot de passe). Confirme ton adresse avec le courriel reçu ; le lien te ramène sur la page.
3. Entre les **identifiants IPTV** de ton fournisseur : adresse du serveur, nom d'utilisateur, mot de passe. Ils restent entre ta page et ta télé.
4. Nomme les **profils** (toi, ta sœur, ton frère…).
5. **Valide la télé**.

Quelques secondes plus tard, la télé passe à **« Qui regarde ? »**. Choisis ton profil : la liste des catégories et des chaînes se charge. La première fois, ça peut prendre un moment ; les fois suivantes, la liste s'affiche tout de suite.

## Si quelque chose bloque

- **Le code a expiré** : la télé en affiche un nouveau automatiquement ; rescanne.
- **« Pas de connexion »** : vérifie le Wi-Fi de la télé, puis relance l'app.
- **« Identifiants IPTV manquants »** : retourne sur la page de configuration et entre-les.
- **Une chaîne ne lit pas** : Retour, essaie une autre copie de la chaîne. Le regroupement des copies arrive dans la partie B.

## Mettre à jour l'app plus tard

Même chemin : Downloader → même adresse → Installer. La nouvelle version remplace l'ancienne et garde ton appairage.
