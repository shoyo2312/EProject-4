alter table user_profiles
    add column follower_count  bigint not null default 0,
    add column following_count bigint not null default 0;

update user_profiles p
set follower_count  = (select count(*) from user_follows f where f.following_id = p.user_id and f.deleted_at is null),
    following_count = (select count(*) from user_follows f where f.follower_id = p.user_id and f.deleted_at is null);
