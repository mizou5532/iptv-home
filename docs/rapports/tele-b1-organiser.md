# B1 — Organiser depuis le téléphone : essai chez le propriétaire (0.2.0)

Date : 14 novembre 2026. Résultat : **tout fonctionne**.
- Mise à jour 0.2.0 installée par-dessus la 0.1.0, sans redemander le QR : OK
- Catégories visibles dans « Organiser » : OK (toutes)
- La télé suit les changements en quelques secondes : OK
- Nouvel écran du direct, logos affichés : OK
- 2ᵉ ouverture toujours très rapide : OK

Volume réel publié : 916 catégories, 56 808 chaînes, plus grosse catégorie 1 259 chaînes.
Stabilité des stream_id (spec §9.5) : empreinte md5 des ids au 2026-11-14 19:23 UTC = d2753b5287786b83a5a5e84c788bb277
(à recomparer plus tard : `select md5(string_agg(id, ',' order by id)) from live_catalog_channel`).
