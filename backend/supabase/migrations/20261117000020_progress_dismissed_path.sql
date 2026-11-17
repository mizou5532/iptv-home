-- Avertissement du linter Supabase (function_search_path_mutable) : chemin de recherche fixé.
alter function public.progress_dismissed(timestamptz, timestamptz) set search_path = public;
