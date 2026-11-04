--
-- PostgreSQL database dump
--

\restrict wRN0GmEnG3AcNUJiadCDmIQuj7icXA5ofcLeLtuDM3CEuZb96jW7M0kaXfNSROK

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
-- Name: dead_letter_replays; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.dead_letter_replays (
    id bigint NOT NULL,
    admin_id bigint NOT NULL,
    dlt_topic character varying(255) NOT NULL,
    dlt_partition integer NOT NULL,
    dlt_offset bigint NOT NULL,
    parked_at timestamp with time zone NOT NULL,
    outcome character varying(20) NOT NULL,
    detail character varying(500),
    forced boolean NOT NULL,
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
-- Name: moderation_actions; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.moderation_actions (
    id bigint NOT NULL,
    admin_id bigint NOT NULL,
    action_type character varying(30) NOT NULL,
    target_type character varying(20) NOT NULL,
    target_id character varying(100) NOT NULL,
    reason character varying(1000) NOT NULL,
    report_id bigint,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    deleted_at timestamp with time zone,
    version bigint DEFAULT 0 NOT NULL,
    banned_until timestamp with time zone
);


--
-- Name: moderation_terms; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.moderation_terms (
    id bigint NOT NULL,
    term character varying(100) NOT NULL,
    term_key character varying(200) NOT NULL,
    category character varying(20),
    action character varying(10) NOT NULL,
    note character varying(500),
    created_by bigint NOT NULL,
    deleted_by bigint,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    deleted_at timestamp with time zone,
    version bigint DEFAULT 0 NOT NULL
);


--
-- Name: moderation_terms_revision; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.moderation_terms_revision (
    id smallint NOT NULL,
    revision bigint NOT NULL,
    CONSTRAINT moderation_terms_revision_id_check CHECK ((id = 1))
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
-- Name: report_reason_weights; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.report_reason_weights (
    reason character varying(1000) NOT NULL,
    weight integer NOT NULL,
    auto_expire boolean DEFAULT true NOT NULL,
    CONSTRAINT report_reason_weights_weight_check CHECK (((weight >= 1) AND (weight <= 10)))
);


--
-- Name: reports; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.reports (
    id bigint NOT NULL,
    reporter_id bigint NOT NULL,
    target_type character varying(20) NOT NULL,
    target_id character varying(100) NOT NULL,
    reason character varying(1000) NOT NULL,
    status character varying(20) DEFAULT 'PENDING'::character varying NOT NULL,
    resolved_by bigint,
    resolved_at timestamp with time zone,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    deleted_at timestamp with time zone,
    version bigint DEFAULT 0 NOT NULL
);


--
-- Name: dead_letter_replays dead_letter_replays_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.dead_letter_replays
    ADD CONSTRAINT dead_letter_replays_pkey PRIMARY KEY (id);


--
-- Name: flyway_schema_history flyway_schema_history_pk; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.flyway_schema_history
    ADD CONSTRAINT flyway_schema_history_pk PRIMARY KEY (installed_rank);


--
-- Name: moderation_actions moderation_actions_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.moderation_actions
    ADD CONSTRAINT moderation_actions_pkey PRIMARY KEY (id);


--
-- Name: moderation_terms moderation_terms_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.moderation_terms
    ADD CONSTRAINT moderation_terms_pkey PRIMARY KEY (id);


--
-- Name: moderation_terms_revision moderation_terms_revision_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.moderation_terms_revision
    ADD CONSTRAINT moderation_terms_revision_pkey PRIMARY KEY (id);


--
-- Name: outbox_events outbox_events_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.outbox_events
    ADD CONSTRAINT outbox_events_pkey PRIMARY KEY (id);


--
-- Name: report_reason_weights report_reason_weights_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.report_reason_weights
    ADD CONSTRAINT report_reason_weights_pkey PRIMARY KEY (reason);


--
-- Name: reports reports_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.reports
    ADD CONSTRAINT reports_pkey PRIMARY KEY (id);


--
-- Name: flyway_schema_history_s_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX flyway_schema_history_s_idx ON public.flyway_schema_history USING btree (success);


--
-- Name: idx_dead_letter_replays_record; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_dead_letter_replays_record ON public.dead_letter_replays USING btree (dlt_topic, dlt_offset);


--
-- Name: idx_moderation_actions_created_at; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_moderation_actions_created_at ON public.moderation_actions USING btree (created_at);


--
-- Name: idx_moderation_actions_target; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_moderation_actions_target ON public.moderation_actions USING btree (target_type, target_id);


--
-- Name: idx_moderation_terms_live; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_moderation_terms_live ON public.moderation_terms USING btree (created_at DESC) WHERE (deleted_at IS NULL);


--
-- Name: idx_outbox_events_published_at; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_outbox_events_published_at ON public.outbox_events USING btree (published_at) WHERE (published_at IS NOT NULL);


--
-- Name: idx_outbox_events_unpublished; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_outbox_events_unpublished ON public.outbox_events USING btree (created_at) WHERE (published_at IS NULL);


--
-- Name: idx_reports_one_per_reporter_target; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX idx_reports_one_per_reporter_target ON public.reports USING btree (reporter_id, target_type, target_id) WHERE ((deleted_at IS NULL) AND ((status)::text = 'PENDING'::text));


--
-- Name: idx_reports_pending_target; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_reports_pending_target ON public.reports USING btree (target_type, target_id) WHERE ((deleted_at IS NULL) AND ((status)::text = 'PENDING'::text));


--
-- Name: idx_reports_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_reports_status ON public.reports USING btree (status) WHERE (deleted_at IS NULL);


--
-- Name: idx_reports_target; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_reports_target ON public.reports USING btree (target_type, target_id) WHERE (deleted_at IS NULL);


--
-- Name: uq_moderation_terms_term_key; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_moderation_terms_term_key ON public.moderation_terms USING btree (term_key) WHERE (deleted_at IS NULL);


--
-- PostgreSQL database dump complete
--

\unrestrict wRN0GmEnG3AcNUJiadCDmIQuj7icXA5ofcLeLtuDM3CEuZb96jW7M0kaXfNSROK

