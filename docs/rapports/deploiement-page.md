# Mise en ligne de la page de configuration et de l'app télé

L'outil de déploiement Vercel n'a pas accès au dépôt GitHub et ne peut pas recevoir de gros fichiers.
La mise en ligne passe donc par deux endroits :

| Quoi | Où | Comment |
|---|---|---|
| `index.html`, `version.json`, `vercel.json` | Vercel, projet `iptv-home-config` | déploiement de fichiers (petits) |
| JS et CSS de la page (`assets/app-<empreinte>.js/.css`) | Supabase, compartiment public `web` | `tools/web/construire-page.sh`, puis envoi avec une règle d'écriture temporaire |
| APK de la télé | Supabase, compartiment public `apk` (`iptv-home.apk`) | envoi avec une règle d'écriture temporaire |

`vercel.json` réécrit `/assets/:file` vers `…/storage/v1/object/public/web/assets/:file` et `/iptv-home.apk`
vers `…/storage/v1/object/public/apk/iptv-home.apk`. Les règles d'écriture temporaires sont supprimées juste
après l'envoi : il ne doit rester **aucune** règle sur `storage.objects`.

Mode démo : `https://iptv-home-config.vercel.app/?demo` (données inventées, aucune action réelle).
