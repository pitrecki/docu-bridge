create table host_record (id bigint primary key, label varchar(255));
create table allowed_record (id bigint primary key);
insert into host_record (id, label) values (1, 'host data');
