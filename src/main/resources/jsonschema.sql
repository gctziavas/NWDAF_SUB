drop table nnwdaf_events_subscription;

create table if not exists nnwdaf_events_subscription(
    id SERIAL PRIMARY KEY,
    sub jsonb
);

