CREATE TABLE users (
    id              BIGINT PRIMARY KEY,
    username        VARCHAR(50)  NOT NULL,
    email           VARCHAR(255) NOT NULL,
    password_hash   VARCHAR(255) NOT NULL,
    role            VARCHAR(20)  NOT NULL DEFAULT 'USER',
    status          VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    created_at      TIMESTAMPTZ  NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL,
    deleted_at      TIMESTAMPTZ,
    version         BIGINT       NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uq_users_username ON users (username) WHERE deleted_at IS NULL;
CREATE UNIQUE INDEX uq_users_email ON users (email) WHERE deleted_at IS NULL;
