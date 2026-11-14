# Environnement cloud de développement

État du 14 novembre 2026, préparation de B2. Ces chemins décrivent la machine cloud actuelle ; ils ne remplacent pas les commandes génériques de PASSATION.md sur une autre machine.

## Activer les outils sur cette machine

```bash
cd /workspace/iptv-home
source /workspace/iptv-env/activate.sh
```

Le helper, conservé hors dépôt dans l'environnement, définit JDK 21 complet, le SDK Android 36 et les dossiers de caches inscriptibles. Le JDK a été extrait de paquets Debian téléchargés avec les index signés ; les outils Android 19 ont été vérifiés avec l'empreinte officielle Google. Les outils Android plus récents essayaient d'écrire dans le dossier personnel en lecture seule.

- `JAVA_HOME=/workspace/jdk21/usr/lib/jvm/java-21-openjdk-amd64`
- `GRADLE_USER_HOME=/workspace/.gradle`
- `ANDROID_USER_HOME=/workspace/android-cli`
- `ANDROID_SDK_ROOT=/workspace/android-sdk`
- SDK 36 et build-tools 36.0.0 ; `apps/local.properties` ignoré contient le chemin SDK.
- Le fichier externe `/workspace/.gradle/gradle.properties` configure le proxy Java à partir du proxy fourni par la plateforme, le JDK local, le dossier personnel Java inscriptible et la compilation Kotlin dans le processus. Ne pas recopier une adresse de proxy d'une ancienne machine : la recalculer depuis la configuration courante si elle change.
- Ne pas lancer deux builds Gradle simultanément dans ce checkout : ils écrivent les mêmes sorties de compilation.

## Tests

```bash
# Après activation, racine du dépôt :
iptv_backend_test
npm test -w web/config
npm test -w tools/test-lecture
npm test -w tools/source-sport
npm run build -w web/config

cd apps
./gradlew --max-workers=2 :shared:jvmTest :tv:testDebugUnitTest
```

`iptv_backend_test` monte le checkout, le binaire Node et la déclaration de l'utilisateur courant dans l'image officielle PostgreSQL 16 épinglée par digest. Les tests créent puis détruisent leurs bases locales. PostgreSQL n'est pas installé à son chemin natif dans la machine principale : `npm test -w backend` sans ce helper échoue donc sur initdb manquant, indépendamment du code. Sur une autre machine, PostgreSQL 16 natif à `/usr/lib/postgresql/16/bin` permet les commandes habituelles.

Ne jamais tester contre le vrai Supabase ni créer son foyer depuis les outils. Utiliser les fixtures et la page en `?demo`.

## Page locale et configuration réutilisable

```bash
npm run dev -w web/config -- --host 0.0.0.0 --port 5173 --strictPort
```

Les requêtes locales de vérification ont renvoyé le HTML français de configuration et le module principal. Aucun lien de prévisualisation localhost fourni par l'onboarding.

Les champs `install_script` et `start_skill` du brouillon de l'environnement ont été sauvegardés. Le script de réinstallation sur les outils conservés a été rejoué avec succès (`npm ci`, verrou inchangé). La sauvegarde ne publie pas l'environnement : pour réutiliser son instantané dans une nouvelle tâche, revoir/enregistrer les réglages puis publier l'environnement dans l'interface. Restauration dans une nouvelle tâche non encore vérifiée.

Les helpers et caches extérieurs au dépôt dépendent de cet instantané ; ils ne sont pas fournis par Git seul. Garder les vérifications TLS, signatures et empreintes actives lors d'une nouvelle préparation.
