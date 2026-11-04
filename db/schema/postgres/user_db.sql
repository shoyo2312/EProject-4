--
-- PostgreSQL database dump
--

\restrict 7czbgYdy5dozwaYT83aOqKgx07XNd1uweMh91xQJOgVbKZArlXloGZvfaNk3dAp

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

--
-- Name: pg_trgm; Type: EXTENSION; Schema: -; Owner: -
--

CREATE EXTENSION IF NOT EXISTS pg_trgm WITH SCHEMA public;


--
-- Name: EXTENSION pg_trgm; Type: COMMENT; Schema: -; Owner: -
--

COMMENT ON EXTENSION pg_trgm IS 'text similarity measurement and index searching based on trigrams';


SET default_tablespace = '';

SET default_table_access_method = heap;

--
-- Name: data_export_requests; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.data_export_requests (
    id bigint NOT NULL,
    user_id bigint NOT NULL,
    format character varying(8) NOT NULL,
    categories text[] NOT NULL,
    status character varying(16) NOT NULL,
    claimed_at timestamp with time zone,
    attempts integer DEFAULT 0 NOT NULL,
    object_key character varying(255),
    size_bytes bigint,
    ready_at timestamp with time zone,
    expires_at timestamp with time zone,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    deleted_at timestamp with time zone,
    version bigint DEFAULT 0 NOT NULL
);


--
-- Name: feed_keyword_filters; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.feed_keyword_filters (
    id bigint NOT NULL,
    user_id bigint NOT NULL,
    keyword character varying(200) NOT NULL,
    for_you boolean NOT NULL,
    following boolean NOT NULL,
    friends boolean NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    deleted_at timestamp with time zone,
    version bigint DEFAULT 0 NOT NULL
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
-- Name: inbox_events; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.inbox_events (
    id bigint NOT NULL,
    event_id character varying(100) NOT NULL,
    event_type character varying(100) NOT NULL,
    processed_at timestamp with time zone NOT NULL
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
-- Name: screen_time_days; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.screen_time_days (
    id bigint NOT NULL,
    user_id bigint NOT NULL,
    local_date date NOT NULL,
    day_seconds integer DEFAULT 0 NOT NULL,
    night_seconds integer DEFAULT 0 NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    deleted_at timestamp with time zone,
    version bigint DEFAULT 0 NOT NULL
);


--
-- Name: user_blocks; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.user_blocks (
    id bigint NOT NULL,
    blocker_id bigint NOT NULL,
    blocked_id bigint NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    deleted_at timestamp with time zone,
    version bigint DEFAULT 0 NOT NULL
);


--
-- Name: user_follow_requests; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.user_follow_requests (
    id bigint NOT NULL,
    requester_id bigint NOT NULL,
    target_id bigint NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    deleted_at timestamp with time zone,
    version bigint DEFAULT 0 NOT NULL
);


--
-- Name: user_follows; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.user_follows (
    id bigint NOT NULL,
    follower_id bigint NOT NULL,
    following_id bigint NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    deleted_at timestamp with time zone,
    version bigint DEFAULT 0 NOT NULL
);


--
-- Name: user_mutes; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.user_mutes (
    id bigint NOT NULL,
    muter_id bigint NOT NULL,
    muted_id bigint NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    deleted_at timestamp with time zone,
    version bigint DEFAULT 0 NOT NULL
);


--
-- Name: user_profiles; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.user_profiles (
    id bigint NOT NULL,
    user_id bigint NOT NULL,
    display_name character varying(100) NOT NULL,
    bio character varying(500),
    avatar_url character varying(500),
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    deleted_at timestamp with time zone,
    version bigint DEFAULT 0 NOT NULL,
    follower_count bigint DEFAULT 0 NOT NULL,
    following_count bigint DEFAULT 0 NOT NULL,
    username character varying(100),
    private_account boolean DEFAULT false NOT NULL,
    display_name_changed_at timestamp with time zone,
    deactivated_at timestamp with time zone,
    account_state_changed_at timestamp with time zone
);


--
-- Name: user_settings; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.user_settings (
    id bigint NOT NULL,
    user_id bigint NOT NULL,
    comment_audience character varying(16) DEFAULT 'EVERYONE'::character varying NOT NULL,
    dm_audience character varying(16) DEFAULT 'EVERYONE'::character varying NOT NULL,
    screen_time_limit_minutes integer,
    sleep_hours_start time without time zone,
    sleep_hours_end time without time zone,
    weekly_screen_time_digest boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    deleted_at timestamp with time zone,
    version bigint DEFAULT 0 NOT NULL,
    filter_spam_offensive boolean DEFAULT true NOT NULL,
    filter_all_comments boolean DEFAULT false NOT NULL,
    comment_keywords text[] DEFAULT '{}'::text[] NOT NULL,
    push_likes boolean DEFAULT true NOT NULL,
    push_comments boolean DEFAULT true NOT NULL,
    push_new_followers boolean DEFAULT true NOT NULL,
    in_app_likes boolean DEFAULT true NOT NULL,
    in_app_comments boolean DEFAULT true NOT NULL,
    in_app_new_followers boolean DEFAULT true NOT NULL,
    in_app_reposts boolean DEFAULT true NOT NULL,
    time_zone character varying(64),
    screen_time_limits_by_day integer[],
    screen_time_passcode_hash character varying(60),
    screen_time_passcode_failures integer DEFAULT 0 NOT NULL,
    screen_time_passcode_locked_until timestamp with time zone,
    last_digest_week date
);


--
-- Name: data_export_requests data_export_requests_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.data_export_requests
    ADD CONSTRAINT data_export_requests_pkey PRIMARY KEY (id);


--
-- Name: feed_keyword_filters feed_keyword_filters_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.feed_keyword_filters
    ADD CONSTRAINT feed_keyword_filters_pkey PRIMARY KEY (id);


--
-- Name: flyway_schema_history flyway_schema_history_pk; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.flyway_schema_history
    ADD CONSTRAINT flyway_schema_history_pk PRIMARY KEY (installed_rank);


--
-- Name: inbox_events inbox_events_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.inbox_events
    ADD CONSTRAINT inbox_events_pkey PRIMARY KEY (id);


--
-- Name: outbox_events outbox_events_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.outbox_events
    ADD CONSTRAINT outbox_events_pkey PRIMARY KEY (id);


--
-- Name: screen_time_days screen_time_days_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.screen_time_days
    ADD CONSTRAINT screen_time_days_pkey PRIMARY KEY (id);


--
-- Name: user_blocks user_blocks_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_blocks
    ADD CONSTRAINT user_blocks_pkey PRIMARY KEY (id);


--
-- Name: user_follow_requests user_follow_requests_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_follow_requests
    ADD CONSTRAINT user_follow_requests_pkey PRIMARY KEY (id);


--
-- Name: user_follows user_follows_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_follows
    ADD CONSTRAINT user_follows_pkey PRIMARY KEY (id);


--
-- Name: user_mutes user_mutes_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_mutes
    ADD CONSTRAINT user_mutes_pkey PRIMARY KEY (id);


--
-- Name: user_profiles user_profiles_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_profiles
    ADD CONSTRAINT user_profiles_pkey PRIMARY KEY (id);


--
-- Name: user_settings user_settings_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_settings
    ADD CONSTRAINT user_settings_pkey PRIMARY KEY (id);


--
-- Name: flyway_schema_history_s_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX flyway_schema_history_s_idx ON public.flyway_schema_history USING btree (success);


--
-- Name: idx_data_export_requests_processing; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_data_export_requests_processing ON public.data_export_requests USING btree (created_at) WHERE (((status)::text = 'PROCESSING'::text) AND (deleted_at IS NULL));


--
-- Name: idx_data_export_requests_user_created; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_data_export_requests_user_created ON public.data_export_requests USING btree (user_id, created_at DESC) WHERE (deleted_at IS NULL);


--
-- Name: idx_inbox_events_processed_at; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_inbox_events_processed_at ON public.inbox_events USING btree (processed_at);


--
-- Name: idx_outbox_events_published_at; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_outbox_events_published_at ON public.outbox_events USING btree (published_at) WHERE (published_at IS NOT NULL);


--
-- Name: idx_outbox_events_unpublished; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_outbox_events_unpublished ON public.outbox_events USING btree (created_at) WHERE (published_at IS NULL);


--
-- Name: idx_user_blocks_blocked_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_user_blocks_blocked_id ON public.user_blocks USING btree (blocked_id) WHERE (deleted_at IS NULL);


--
-- Name: idx_user_blocks_blocker_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_user_blocks_blocker_id ON public.user_blocks USING btree (blocker_id) WHERE (deleted_at IS NULL);


--
-- Name: idx_user_blocks_pair_history; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_user_blocks_pair_history ON public.user_blocks USING btree (blocker_id, blocked_id);


--
-- Name: idx_user_blocks_reverse_pair_history; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_user_blocks_reverse_pair_history ON public.user_blocks USING btree (blocked_id, blocker_id);


--
-- Name: idx_user_follow_requests_target_created; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_user_follow_requests_target_created ON public.user_follow_requests USING btree (target_id, created_at DESC) WHERE (deleted_at IS NULL);


--
-- Name: idx_user_follows_follower_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_user_follows_follower_id ON public.user_follows USING btree (follower_id) WHERE (deleted_at IS NULL);


--
-- Name: idx_user_follows_following_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_user_follows_following_id ON public.user_follows USING btree (following_id) WHERE (deleted_at IS NULL);


--
-- Name: idx_user_mutes_muter_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_user_mutes_muter_id ON public.user_mutes USING btree (muter_id) WHERE (deleted_at IS NULL);


--
-- Name: idx_user_profiles_display_name_trgm; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_user_profiles_display_name_trgm ON public.user_profiles USING gin (lower((display_name)::text) public.gin_trgm_ops);


--
-- Name: idx_user_profiles_user_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_user_profiles_user_id ON public.user_profiles USING btree (user_id);


--
-- Name: idx_user_profiles_username_lower; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_user_profiles_username_lower ON public.user_profiles USING btree (lower((username)::text)) WHERE (deleted_at IS NULL);


--
-- Name: idx_user_profiles_username_trgm; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_user_profiles_username_trgm ON public.user_profiles USING gin (lower((username)::text) public.gin_trgm_ops);


--
-- Name: uq_data_export_requests_user_processing; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_data_export_requests_user_processing ON public.data_export_requests USING btree (user_id) WHERE (((status)::text = 'PROCESSING'::text) AND (deleted_at IS NULL));


--
-- Name: uq_feed_keyword_filters_user_keyword; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_feed_keyword_filters_user_keyword ON public.feed_keyword_filters USING btree (user_id, lower((keyword)::text)) WHERE (deleted_at IS NULL);


--
-- Name: uq_inbox_events_event_id; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_inbox_events_event_id ON public.inbox_events USING btree (event_id);


--
-- Name: uq_screen_time_days_user_date; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_screen_time_days_user_date ON public.screen_time_days USING btree (user_id, local_date) WHERE (deleted_at IS NULL);


--
-- Name: uq_user_blocks_blocker_blocked; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_user_blocks_blocker_blocked ON public.user_blocks USING btree (blocker_id, blocked_id) WHERE (deleted_at IS NULL);


--
-- Name: uq_user_follow_requests_requester_target; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_user_follow_requests_requester_target ON public.user_follow_requests USING btree (requester_id, target_id) WHERE (deleted_at IS NULL);


--
-- Name: uq_user_follows_follower_following; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_user_follows_follower_following ON public.user_follows USING btree (follower_id, following_id) WHERE (deleted_at IS NULL);


--
-- Name: uq_user_mutes_muter_muted; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_user_mutes_muter_muted ON public.user_mutes USING btree (muter_id, muted_id) WHERE (deleted_at IS NULL);


--
-- Name: uq_user_profiles_user_id; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_user_profiles_user_id ON public.user_profiles USING btree (user_id) WHERE (deleted_at IS NULL);


--
-- Name: uq_user_settings_user_id; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_user_settings_user_id ON public.user_settings USING btree (user_id) WHERE (deleted_at IS NULL);


--
-- PostgreSQL database dump complete
--

\unrestrict 7czbgYdy5dozwaYT83aOqKgx07XNd1uweMh91xQJOgVbKZArlXloGZvfaNk3dAp

