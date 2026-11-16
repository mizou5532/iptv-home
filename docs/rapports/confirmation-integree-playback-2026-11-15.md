# Confirmation intégrée — point 5 : protection de la lecture (15 novembre 2026)

**Qui :** Cosmo, l'assistant personnel du propriétaire (pas l'agent de développement habituel). Le point 5 de l'audit du 15 nov. avait été **réservé** par le propriétaire (« on pourra attaquer le point cinq tout seul ») ; il l'a débloqué le même soir, après les points 1 à 4. Voir le rapport précédent : `confirmations-integrees-restantes-2026-11-15.md`.

**Branche :** `fix/confirmation-integree-playback`, créée à partir de `fix/confirmations-integrees-restantes` @ `245a399` — elle contient donc aussi les points 1 à 4 et se fusionne après elle (ou avec elle). Ni `main` ni la branche Films n'ont été touchées. **Rien n'est déployé.**

## Ce qui a changé (1 fichier de code)

**`web/config/src/org/playback-admin.ts`** — l'écran « Protection de la lecture » gardait toutes ses mutations (activation de la protection, confirmation d'arrêt physique) par un seul `window.confirm(...)` dans la fonction `mutate`. C'était le dernier `confirm()` natif de la page.

- `mutate` est scindé en deux : `runMutation` (le corps d'avant, inchangé : boutons désactivés, RPC, rechargement, message d'erreur masqué) et une étape de confirmation intégrée.
- La confirmation s'affiche dans la section elle-même : zone `#playback-confirm` (conteneur `.tools`, texte `.ctext`, boutons « Confirmer » `data-cf="yes"` / « Annuler » `data-cf="no"`), construite en `createElement` / `textContent` comme le reste du fichier — aucun `innerHTML` ajouté.
- **Les textes d'avertissement sont inchangés, au mot près** (« Toutes les installations sont-elles à jour et toutes les anciennes lectures physiquement arrêtées ? », « As-tu vérifié physiquement que la lecture précédente est arrêtée et le lecteur fermé ? Une absence de réponse ne suffit pas. »).
- Aucune RPC d'écriture avant un clic explicite sur « Confirmer » — la garantie du commentaire en tête du fichier (« seules les actions confirmées appellent une RPC d'écriture ») reste vraie. « Annuler » retire la zone sans rien écrire. Cliquer « Actualiser » pendant une confirmation en attente réaffiche l'écran et l'abandonne.
- Une seule confirmation en attente à la fois : cliquer une autre action remplace la confirmation affichée.

## Tests

- `web/config/test/playback-admin.test.ts` réécrit pour le flux intégré ; le `vi.stubGlobal('confirm', …)` a disparu (l'import `vi` aussi). Les tests vérifient : zone affichée avec le texte d'avertissement et **aucune écriture** tant qu'on n'a pas confirmé ; « Annuler » = aucune écriture ; « Confirmer » = activation / récupération effectives ; l'échec de récupération reste affiché sans exposer le message serveur.
- Suite complète web/config : **146/146 verts, 12 fichiers**.
- `npm run build` (`tsc` + `vite`) : OK.
- Build de production `tools/web/construire-page.sh` : OK. **Le paquet ne contient plus aucune confirmation native** (`confirm(` : 0 occurrence, contre 1 avant — c'était celle-ci), et la zone `playback-confirm` y est présente.
- Aucun fichier hors `web/config` touché.

## État final des confirmations de la page

Avec les points 1 à 4 (branche `fix/confirmations-integrees-restantes`) et ce point 5, **la page de configuration n'utilise plus aucune boîte de confirmation native du navigateur**. Tout passe par le même patron : texte d'avertissement dans la page + boutons « Confirmer » / « Annuler ».
