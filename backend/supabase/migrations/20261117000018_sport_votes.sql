-- Mémoire du Sport pour toute la maison (17 nov. 2026) : quelle chaîne diffuse vraiment les matchs d'une équipe.
-- +1 quand un profil regarde un match plus de 3 minutes sur une chaîne ; -3 avec « Pas le bon match ».
-- team_key : « nhl:MTL », « soccer:83 » (même clé que les chaînes préférées) ; channel_key : nom normalisé de la chaîne.
create table public.sport_channel_vote (
  household_id uuid not null references public.households on delete cascade,
  team_key text not null check (length(team_key) between 1 and 80),
  channel_key text not null check (length(channel_key) between 1 and 200),
  score int not null default 0 check (score between -9 and 9),
  updated_at timestamptz not null default now(),
  primary key (household_id, team_key, channel_key)
);
alter table public.sport_channel_vote enable row level security;
create policy sport_channel_vote_select on public.sport_channel_vote
  for select to authenticated using (public.is_member(household_id));
revoke all on public.sport_channel_vote from public, anon, authenticated;
grant select on public.sport_channel_vote to authenticated;

-- Seule façon d'écrire : un membre (télé comprise) ajoute delta (-3..+1), score borné à [-9, 9].
create function public.sport_vote(h uuid, team_key text, channel_key text, delta int) returns int
language plpgsql security definer set search_path = public as $$
declare s int;
begin
  if not public.is_member(h) then raise exception 'not a member' using errcode = '42501'; end if;
  if delta not between -3 and 1 then raise exception 'bad delta' using errcode = '22023'; end if;
  insert into public.sport_channel_vote as v (household_id, team_key, channel_key, score, updated_at)
  values (h, sport_vote.team_key, sport_vote.channel_key, greatest(-9, least(9, delta)), now())
  on conflict on constraint sport_channel_vote_pkey do update
    set score = greatest(-9, least(9, v.score + delta)), updated_at = now()
  returning score into s;
  return s;
end $$;
revoke all on function public.sport_vote(uuid, text, text, int) from public, anon;
grant execute on function public.sport_vote(uuid, text, text, int) to authenticated;
