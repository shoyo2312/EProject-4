--
-- PostgreSQL database dump
--

\restrict af9aVb7hlVk8CsNwN8lT9fLmfvMpx5B0UM4AZVz7STK029l6pC5sPwYzfBQVfk6

-- Dumped from database version 16.15
-- Dumped by pg_dump version 16.15

SET statement_timeout = 0;
SET lock_timeout = 0;
SET idle_in_transaction_session_timeout = 0;
SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SELECT pg_catalog.set_config('search_path', '', false);
SET check_function_bodies = false;
SET xmloption = content;
SET client_min_messages = warning;
SET row_security = off;

SET default_tablespace = '';

SET default_table_access_method = heap;

--
-- Name: auth_security_events; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.auth_security_events (
    id bigint NOT NULL,
    user_id bigint NOT NULL,
    event_type character varying(32) NOT NULL,
    ip_address character varying(45),
    user_agent character varying(512),
    device_label character varying(128),
    detail character varying(255),
    created_at timestamp with time zone NOT NULL,
    device_model character varying(64),
    location character varying(128)
);


--
-- Name: flyway_schema_history; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.flyway_schema_history (
    installed_rank integer NOT NULL,
    version character varying(50),
    description character varying(200) NOT NULL,
    type character varying(20) NOT NULL,
    script character varying(1000) NOT NULL,
    checksum integer,
    installed_by character varying(100) NOT NULL,
    installed_on timestamp without time zone DEFAULT now() NOT NULL,
    execution_time integer NOT NULL,
    success boolean NOT NULL
);


--
-- Name: known_device_accounts; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.known_device_accounts (
    user_id bigint NOT NULL
);


--
-- Name: known_devices; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.known_devices (
    id bigint NOT NULL,
    user_id bigint NOT NULL,
    device_hash character varying(64) NOT NULL,
    last_seen_at timestamp with time zone NOT NULL
);


--
-- Name: outbox_events; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.outbox_events (
    id bigint NOT NULL,
    aggregate_type character varying(100) NOT NULL,
    aggregate_id character varying(100) NOT NULL,
    event_type character varying(100) NOT NULL,
    payload text NOT NULL,
    published_at timestamp with time zone,
    created_at timestamp with time zone NOT NULL
);


--
-- Name: refresh_tokens; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.refresh_tokens (
    id bigint NOT NULL,
    user_id bigint NOT NULL,
    token_hash character varying(64) NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    revoked_at timestamp with time zone,
    created_at timestamp with time zone NOT NULL,
    rotated_at timestamp with time zone
);


--
-- Name: remembered_devices; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.remembered_devices (
    id bigint NOT NULL,
    user_id bigint NOT NULL,
    token_hash character varying(64) NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    created_at timestamp with time zone NOT NULL
);


--
-- Name: user_identities; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.user_identities (
    id bigint NOT NULL,
    user_id bigint NOT NULL,
    provider character varying(20) NOT NULL,
    provider_uid character varying(128) NOT NULL,
    created_at timestamp with time zone NOT NULL,
    needs_reconfirmation boolean DEFAULT false NOT NULL
);


--
-- Name: users; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.users (
    id bigint NOT NULL,
    username character varying(50) NOT NULL,
    email character varying(255),
    password_hash character varying(255),
    role character varying(20) DEFAULT 'USER'::character varying NOT NULL,
    status character varying(20) DEFAULT 'ACTIVE'::character varying NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    deleted_at timestamp with time zone,
    version bigint DEFAULT 0 NOT NULL,
    email_verified boolean DEFAULT false NOT NULL,
    email_verified_at timestamp with time zone,
    last_login_at timestamp with time zone,
    banned_at timestamp with time zone,
    ban_reason text,
    banned_until timestamp with time zone,
    username_changed_at timestamp with time zone,
    deactivated_at timestamp with time zone,
    deletion_scheduled_at timestamp with time zone
);


--
-- Name: verification_tokens; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.verification_tokens (
    id bigint NOT NULL,
    user_id bigint NOT NULL,
    token_hash character varying(64) NOT NULL,
    token_type character varying(30) NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    used_at timestamp with time zone,
    created_at timestamp with time zone NOT NULL
);


--
-- Name: auth_security_events auth_security_events_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.auth_security_events
    ADD CONSTRAINT auth_security_events_pkey PRIMARY KEY (id);


--
-- Name: flyway_schema_history flyway_schema_history_pk; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.flyway_schema_history
    ADD CONSTRAINT flyway_schema_history_pk PRIMARY KEY (installed_rank);


--
-- Name: known_device_accounts known_device_accounts_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.known_device_accounts
    ADD CONSTRAINT known_device_accounts_pkey PRIMARY KEY (user_id);


--
-- Name: known_devices known_devices_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.known_devices
    ADD CONSTRAINT known_devices_pkey PRIMARY KEY (id);


--
-- Name: outbox_events outbox_events_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.outbox_events
    ADD CONSTRAINT outbox_events_pkey PRIMARY KEY (id);


--
-- Name: refresh_tokens refresh_tokens_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.refresh_tokens
    ADD CONSTRAINT refresh_tokens_pkey PRIMARY KEY (id);


--
-- Name: remembered_devices remembered_devices_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.remembered_devices
    ADD CONSTRAINT remembered_devices_pkey PRIMARY KEY (id);


--
-- Name: known_devices uq_known_devices_user_device; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.known_devices
    ADD CONSTRAINT uq_known_devices_user_device UNIQUE (user_id, device_hash);


--
-- Name: user_identities user_identities_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_identities
    ADD CONSTRAINT user_identities_pkey PRIMARY KEY (id);


--
-- Name: users users_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.users
    ADD CONSTRAINT users_pkey PRIMARY KEY (id);


--
-- Name: verification_tokens verification_tokens_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.verification_tokens
    ADD CONSTRAINT verification_tokens_pkey PRIMARY KEY (id);


--
-- Name: flyway_schema_history_s_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX flyway_schema_history_s_idx ON public.flyway_schema_history USING btree (success);


--
-- Name: idx_auth_security_events_created; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_auth_security_events_created ON public.auth_security_events USING btree (created_at);


--
-- Name: idx_auth_security_events_user; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_auth_security_events_user ON public.auth_security_events USING btree (user_id, created_at DESC);


--
-- Name: idx_identities_user; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_identities_user ON public.user_identities USING btree (user_id);


--
-- Name: idx_known_devices_last_seen; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_known_devices_last_seen ON public.known_devices USING btree (last_seen_at);


--
-- Name: idx_outbox_events_published_at; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_outbox_events_published_at ON public.outbox_events USING btree (published_at) WHERE (published_at IS NOT NULL);


--
-- Name: idx_outbox_events_unpublished; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_outbox_events_unpublished ON public.outbox_events USING btree (created_at) WHERE (published_at IS NULL);


--
-- Name: idx_refresh_tokens_expires_at; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_refresh_tokens_expires_at ON public.refresh_tokens USING btree (expires_at);


--
-- Name: idx_refresh_tokens_user_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_refresh_tokens_user_id ON public.refresh_tokens USING btree (user_id);


--
-- Name: idx_remembered_devices_user; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_remembered_devices_user ON public.remembered_devices USING btree (user_id);


--
-- Name: idx_users_banned_until; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_users_banned_until ON public.users USING btree (banned_until) WHERE (((status)::text = 'BANNED'::text) AND (banned_until IS NOT NULL));


--
-- Name: idx_users_deletion_scheduled_at; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_users_deletion_scheduled_at ON public.users USING btree (deletion_scheduled_at) WHERE ((deleted_at IS NULL) AND (deletion_scheduled_at IS NOT NULL));


--
-- Name: idx_verification_tokens_expires_at; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_verification_tokens_expires_at ON public.verification_tokens USING btree (expires_at);


--
-- Name: idx_verification_tokens_user_type; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_verification_tokens_user_type ON public.verification_tokens USING btree (user_id, token_type);


--
-- Name: uq_identity; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_identity ON public.user_identities USING btree (provider, provider_uid);


--
-- Name: uq_refresh_tokens_token_hash; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_refresh_tokens_token_hash ON public.refresh_tokens USING btree (token_hash);


--
-- Name: uq_remembered_devices_hash; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_remembered_devices_hash ON public.remembered_devices USING btree (token_hash);


--
-- Name: uq_users_email; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_users_email ON public.users USING btree (lower((email)::text)) WHERE (deleted_at IS NULL);


--
-- Name: uq_users_username; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_users_username ON public.users USING btree (lower((username)::text)) WHERE (deleted_at IS NULL);


--
-- Name: uq_verification_tokens_hash; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_verification_tokens_hash ON public.verification_tokens USING btree (token_hash);


--
-- Name: known_device_accounts known_device_accounts_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.known_device_accounts
    ADD CONSTRAINT known_device_accounts_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id);


--
-- Name: known_devices known_devices_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.known_devices
    ADD CONSTRAINT known_devices_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id);


--
-- Name: remembered_devices remembered_devices_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.remembered_devices
    ADD CONSTRAINT remembered_devices_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id);


--
-- Name: user_identities user_identities_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_identities
    ADD CONSTRAINT user_identities_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id);


--
-- Name: verification_tokens verification_tokens_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.verification_tokens
    ADD CONSTRAINT verification_tokens_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id);


--
-- PostgreSQL database dump complete
--

\unrestrict af9aVb7hlVk8CsNwN8lT9fLmfvMpx5B0UM4AZVz7STK029l6pC5sPwYzfBQVfk6

