# Clé de signature de l'app télé

Fichier : `iptv-home.jks` (PKCS12, RSA 4096, valable 30 ans, alias `iptv-home`).

Mot de passe (magasin et clé) : `a2jFlFXztDoTZ17mABt4Enxw`

Le build lit `signing.properties` (même dossier) pour signer la version `release`.

Pourquoi elle est dans le dépôt (privé) : Android n'installe une mise à jour que si
elle est signée par la **même** clé que la version déjà installée. Les sessions de
travail ne gardent rien entre elles, donc la clé doit vivre ici.

Risque accepté : quiconque a accès au dépôt privé pourrait signer une version ;
elle devrait de toute façon être installée à la main sur la télé.

**Ne jamais régénérer cette clé** : sinon il faudrait désinstaller l'app (et perdre
son appairage) avant d'installer la nouvelle version.
