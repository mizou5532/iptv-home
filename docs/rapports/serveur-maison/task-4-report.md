# Task 4 : rapport
Implémenté : HomeProtocol.kt (SnapStream, SnapCategory, LiveSnapshot, SnapEpg, EpgReply, HOME_PROTOCOL=1, HomeToken, liveSnapshotRev, accountFingerprint(server, username), sha256Hex en Kotlin pur commun, vérifié sur vecteurs NIST) ; SyncClient : registerHomeServer / homeServer / issueHomeServerToken / checkHomeServerToken (défauts + SupabaseSyncClient, RPC h/url/pin/version et h/token, lecture home_server filtrée par household_id) ; HomeServerInfo. IptvApp.accountFingerprint() délègue à la fonction partagée (même algorithme, SHA-256 trim/lowercase serveur + "|" + nom, 32 premiers caractères).
TDD : RED = échec de compilation des tests de sync (fonctions absentes) ; GREEN = HomeProtocolTest (5) + HomeServerSyncTest (4) passent.
Totaux : shared 414 (405 + 9), tv 585, 0 échec.
Test d'empreinte : valeur 507eaea3... calculée avec shasum sur l'entrée normalisée (équivaut à l'ancien code MessageDigest).
Fichiers : shared/.../home/HomeProtocol.kt, sync/SyncClient.kt, tv/.../IptvApp.kt, tests HomeProtocolTest, HomeServerSyncTest.
Concerns : SHA-256 maison plutôt qu'expect/actual (testé sur 3 vecteurs) ; le serveur maison (JVM) pourra aussi l'utiliser. Le RED du protocole n'était que compilation.
