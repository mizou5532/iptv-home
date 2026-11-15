#!/usr/bin/env bash
# Construit la page de configuration pour la mise en ligne :
# - les fichiers JS/CSS (noms avec empreinte) vont dans le compartiment Supabase public « web » (assets/) ;
# - Vercel (projet iptv-home-config) ne sert que index.html, version.json et vercel.json, et réécrit
#   /assets/* vers Supabase et /iptv-home.apk vers le compartiment « apk ».
# supabase-js est chargé depuis jsdelivr (version épinglée) pour garder les fichiers petits.
# Usage : tools/web/construire-page.sh DOSSIER_SORTIE
set -euo pipefail
out=${1:?dossier de sortie}
cd "$(dirname "$0")/../../web/config"
rm -rf "$out"; mkdir -p "$out"
../../node_modules/.bin/esbuild src/main.ts --bundle --format=esm --splitting --minify --target=es2020 \
  --outdir="$out/assets" --entry-names=app-[hash] --chunk-names=[name]-[hash] \
  --alias:@supabase/supabase-js=https://cdn.jsdelivr.net/npm/@supabase/supabase-js@2.118.0/+esm \
  --external:'https://cdn.jsdelivr.net/*' --define:import.meta.env.VITE_PLAYBACK_ADMIN='"0"' --log-level=warning
ls "$out/assets"
