-- Username, so a profile can be found by the handle people actually type.
--
-- Fed by the UserRegisteredEvent this service already consumes, and never updated afterwards:
-- auth-service has no rename endpoint, so a handle is fixed for the life of the account and this
-- copy cannot drift from it.
--
-- Deliberately NOT backfilled. Rows created before this column exists have no handle available
-- from inside this service, and display_name is not a safe substitute — it starts out equal to
-- the username but the owner can change it. Guessing would write a wrong handle that nothing
-- later corrects; those accounts stay findable by their display name until a backfill from
-- auth-service supplies the real value.
alter table user_profiles
    add column username varchar(100);

-- Substring search over both name fields. Trigram rather than a plain b-tree: the query is
-- "%q%", and a b-tree cannot serve a leading wildcard at all, so without this every search is a
-- sequential scan of the whole table.
create extension if not exists pg_trgm;

-- Indexed on lower(...) because that is exactly what the query compares — an index on the bare
-- column would simply not be used.
create index idx_user_profiles_display_name_trgm
    on user_profiles using gin (lower(display_name) gin_trgm_ops);

create index idx_user_profiles_username_trgm
    on user_profiles using gin (lower(username) gin_trgm_ops);
