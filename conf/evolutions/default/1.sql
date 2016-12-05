# --- !Ups

create table map_info (
  id                            bigserial PRIMARY KEY,
  name                          varchar(255) UNIQUE,
  is_uploaded                   boolean,
  upload_date                   date,
  sync_date                     date,
  sync_success                  boolean,
  downloads_count               bigint
);


# --- !Downs

drop table if exists map_info;

