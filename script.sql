create table attachment
(
    id        varchar(255) not null
        primary key,
    filename  varchar(255) not null,
    mime_type varchar(255) null,
    type      varchar(255) not null,
    upload_at datetime(6)  null
);

create table dept
(
    id           varchar(255) not null
        primary key,
    create_at    datetime(6)  null,
    name         varchar(255) not null,
    parent_id    varchar(255) null,
    update_at    datetime(6)  null,
    enable_state varchar(255) not null,
    phone        varchar(255) null,
    principal    varchar(255) null,
    remark       varchar(255) null,
    type         varchar(255) not null
);

create index IDXdjek8mtfokomf5e3ngleqnwif
    on dept (parent_id);

create table persistent_logins
(
    series    varchar(255) not null
        primary key,
    last_used datetime(6)  null,
    token     varchar(255) null,
    username  varchar(255) null
);

create table role
(
    id           bigint auto_increment
        primary key,
    create_at    datetime(6)  null,
    update_at    datetime(6)  null,
    data_scope   varchar(255) not null,
    dept_type    varchar(255) not null,
    enable_state varchar(255) not null,
    name         varchar(255) not null,
    remark       varchar(255) null,
    type         varchar(255) not null,
    constraint UK_8sewwnpamngi6b1dwaa88askk
        unique (name)
);

create index IDX1up7s0ixmyo5r4jtxg3u273u1
    on role (update_at);

create table role_authorities
(
    role_id     bigint       not null,
    authorities varchar(255) null,
    constraint FKbyfnfkpgrf4jmo3nf97arsphd
        foreign key (role_id) references role (id)
);

create table user
(
    id           bigint auto_increment
        primary key,
    create_at    datetime(6)  null,
    update_at    datetime(6)  null,
    avatar       varchar(255) null,
    email        varchar(255) null,
    enable_state varchar(255) not null,
    name         varchar(255) not null,
    password     varchar(255) not null,
    phone        varchar(255) not null,
    remark       varchar(255) null,
    username     varchar(255) not null,
    dept_id      varchar(255) null,
    role_id      bigint       null,
    constraint UK_589idila9li6a4arw1t8ht1gx
        unique (phone),
    constraint UK_ob8kqyqqgmefl0aco34akdtpe
        unique (email),
    constraint UK_sb8bbouer5wak8vyiiy4pf2bx
        unique (username),
    constraint FK78ebq13b4xfaepkp1a2ueek47
        foreign key (dept_id) references dept (id),
    constraint FK9usot4gododq1u90duvulb92d
        foreign key (role_id) references role (id)
);

create index IDXav1aktu04oqbuq01nth50ixy8
    on user (update_at);

create index IDXg03movoougd6nbk5kd9kqmmp6
    on user (enable_state);


