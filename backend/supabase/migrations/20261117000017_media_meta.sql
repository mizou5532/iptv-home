-- Fiches TMDB / note IMDb des films et séries, partagées par tous les foyers (ce sont des données publiques).
-- Remplies seulement par la fonction media-meta (clé de service) ; lues par les membres d'un foyer.
create table public.media_meta (
  key text primary key,                       -- « movie:the batman:2022 » (titre normalisé, année ou vide)
  kind text not null check (kind in ('movie', 'series')),
  query_title text not null,
  query_year int,
  status text not null check (status in ('ok', 'not_found')),
  tmdb_id int,
  imdb_id text,
  title text,                                 -- titre TMDB en français (ou original)
  overview text,                              -- résumé TMDB en français, si présent
  year int,
  poster text,                                -- chemins TMDB (« /abc.jpg ») : taille choisie par la télé
  backdrop text,
  logo text,
  imdb_rating numeric(3, 1),
  imdb_votes int,
  fetched_at timestamptz not null default now(),
  rating_at timestamptz
);

alter table public.media_meta enable row level security;

create policy media_meta_select on public.media_meta for select to authenticated
  using (exists (select 1 from public.household_members m where m.user_id = auth.uid()));
