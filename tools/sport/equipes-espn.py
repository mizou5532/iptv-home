#!/usr/bin/env python3
"""Liste des équipes (ESPN) intégrée à la page iPhone pour choisir ses équipes favorites.
ESPN refuse les lectures directes depuis la page (CORS) : on relance ce script quand une ligue change.
Usage : python3 tools/sport/equipes-espn.py > web/config/src/teams-data.json"""
import json, subprocess, sys

LIGUES = [  # clé (= League.key de la télé), nom affiché, chemin ESPN
    ("nhl", "LNH", "hockey/nhl"), ("nba", "NBA", "basketball/nba"), ("nfl", "NFL", "football/nfl"),
    ("esp.1", "Liga", "soccer/esp.1"), ("eng.1", "Premier League", "soccer/eng.1"), ("fra.1", "Ligue 1", "soccer/fra.1"),
    ("ita.1", "Serie A", "soccer/ita.1"), ("ger.1", "Bundesliga", "soccer/ger.1"), ("usa.1", "MLS", "soccer/usa.1"),
]
out = []
for key, label, path in LIGUES:
    raw = subprocess.run(["curl", "-s", "-m", "30", f"https://site.api.espn.com/apis/site/v2/sports/{path}/teams"], capture_output=True, text=True).stdout
    teams = json.loads(raw)["sports"][0]["leagues"][0]["teams"]
    rows = []
    for t in (x["team"] for x in teams):
        logo = next((l["href"] for l in t.get("logos", []) if "default" in l.get("rel", [])), (t.get("logos") or [{}])[0].get("href"))
        rows.append({"id": t["id"], "abbr": t.get("abbreviation", ""), "name": t["displayName"], "logo": logo, "color": t.get("color")})
    rows.sort(key=lambda r: r["name"])
    out.append({"key": key, "label": label, "teams": rows})
    print(f"{label}: {len(rows)}", file=sys.stderr)
json.dump(out, sys.stdout, ensure_ascii=False, separators=(",", ":"))
