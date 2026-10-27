# =============================================================
# TikTok Backend — Makefile shortcuts
# Dùng: make <target>
# Ví dụ: make build | make run-auth | make infra-up
# =============================================================

# Nạp .env (nếu có) và export ra mọi child process (mvnw, docker-compose...).
# Chạy `cp .env.example .env` một lần trước khi dùng make run-*.
ifneq (,$(wildcard ./.env))
    include .env
    export
endif

.PHONY: help \
	build image build-libs build-auth build-user build-video build-interaction \
	build-story build-recommendation build-chat build-gateway \
	build-admin \
	clean \
	test test-auth test-user \
	infra-up infra-down infra-reset infra-logs infra-status infra-check geoip \
	run-gateway run-auth run-user run-video run-interaction run-story \
	run-recommendation run-chat run-media run-notification \
	run-admin run-analytics run-search \
	migrate-auth migrate-user \
	migrate-admin migrate-all \
	moderation-up moderation-test moderation-score-texts \
	dlt-list dlt-replay \
	seed-media seed seed-backdate seed-refresh

# Hiển thị help
help:
	@echo ""
	@echo "TikTok Backend — Available commands:"
	@echo ""
	@echo "  Build:"
	@echo "    make build              Build toàn bộ monorepo"
	@echo "    make build-libs         Build chỉ libs (common, event, crypto)"
	@echo "    make build-auth         Build auth-service"
	@echo "    make build-user         Build user-service"
	@echo "    make build-video        Build video-service"
	@echo "    make build-interaction  Build interaction-service"
	@echo "    make build-story        Build story-service"
	@echo "    make build-recommendation Build recommendation-service"
	@echo "    make build-chat         Build chat-service"
	@echo "    make build-admin        Build admin-service"
	@echo "    make build-gateway      Build api-gateway"
	@echo ""
	@echo "  Demo seed (scripts/seed/README.md):"
	@echo "    make seed-media         Tải video/ảnh demo từ Pixabay vào SEED_MEDIA"
	@echo "    make seed               Nạp 50 user, 200 video, 4 tháng tương tác qua API thật (~1.5h)"
	@echo "    make seed-backdate      Lùi ngày giờ trong mọi store theo ledger"
	@echo "    make seed-refresh       Tương tác mới cho trending, chạy trước giờ demo"
	@echo "    make clean              Clean tất cả target/"
	@echo "    make image SERVICE=x    Build Docker image cho 1 service Java (Dockerfile ở root)"
	@echo ""
	@echo "  Test:"
	@echo "    make test               Chạy toàn bộ tests"
	@echo "    make test-auth          Chạy tests của auth-service"
	@echo "    make test-user          Chạy tests của user-service"
	@echo ""
	@echo "  Infrastructure:"
	@echo "    make infra-up           Start tất cả Docker containers"
	@echo "    make infra-check        Kiểm tra mọi port infra chỉ bind 127.0.0.1"
	@echo "    make infra-down         Stop containers (giữ data)"
	@echo "    make infra-reset        Stop containers + xoá data (volumes)"
	@echo "    make infra-logs         Xem logs tất cả containers"
	@echo "    make infra-status       Xem trạng thái containers"
	@echo "    make geoip              Tải DB-IP City Lite vào data/geoip (bỏ qua nếu mới hơn 1 tháng)"
	@echo ""
	@echo "  Run services (local; trừ gateway, chỉ nghe 127.0.0.1 — BIND=0.0.0.0 để mở):"
	@echo "    make run-gateway        Run api-gateway :8080"
	@echo "    make run-auth           Run auth-service :8081"
	@echo "    make run-user           Run user-service :8082"
	@echo "    make run-video          Run video-service :8083"
	@echo "    make run-media          Run media-worker :8084"
	@echo "    make run-interaction    Run interaction-service :8085"
	@echo "    make run-story          Run story-service :8086"
	@echo "    make run-recommendation Run recommendation-service :8087"
	@echo "    make run-chat           Run chat-service :8088"
	@echo "    make run-notification   Run notification-service :8089"
	@echo "    make run-search         Run search-service :8095"
	@echo "    make run-admin          Run admin-service :8096"
	@echo "    make run-analytics      Run analytics-service :8097"
	@echo "    make rank-up            Start rank-service :8098 (internal)"
	@echo "    make rank-train         Train the feed ranking model from ClickHouse"
	@echo "    make rank-test          Run rank-service tests"
	@echo "    make moderation-up      Start moderation-service :8099 (frame + text screening)"
	@echo "    make moderation-test    Run moderation-service tests"
	@echo "    make moderation-score-texts FILE=x  Precision of the text classifier on judged comments"
	@echo ""
	@echo "  Database:"
	@echo "    make migrate-auth       Chạy Flyway migration cho auth-service"
	@echo "    make migrate-user       Chạy Flyway migration cho user-service"
	@echo "    make migrate-admin      Chạy Flyway migration cho admin-service"
	@echo "    make migrate-all        Chạy Flyway migration tất cả PostgreSQL services"
	@echo ""
	@echo "  Dead letters (đọc javadoc kafka-lib DltReplay trước khi replay):"
	@echo "    make dlt-list TOPIC=x.DLT               Liệt kê record trong DLT"
	@echo "    make dlt-replay TOPIC=x.DLT OFFSET=p:o  Replay một record (FORCE=1 khi đã kiểm tra thứ tự)"
	@echo "    make dlt-replay TOPIC=x.DLT ALL=1       Replay mọi record nhóm dlt-replay chưa đi qua"
	@echo ""

# ──────────────────────────────────────────────
# Build
# ──────────────────────────────────────────────

build:
	./mvnw clean install -DskipTests

# One Java service's image from the shared root Dockerfile: make image SERVICE=auth-service
image:
	@test -n "$(SERVICE)" || { echo "usage: make image SERVICE=<directory under services/>"; exit 1; }
	docker build --build-arg SERVICE=$(SERVICE) -t tiktok/$(SERVICE):latest .

build-libs:
	./mvnw clean install -DskipTests -pl libs/common-lib,libs/event-schema,libs/crypto-lib

build-auth:
	./mvnw clean install -DskipTests -pl libs/common-lib,libs/event-schema,libs/crypto-lib,services/auth-service -am

build-user:
	./mvnw clean install -DskipTests -pl libs/common-lib,libs/event-schema,services/user-service -am

build-video:
	./mvnw clean install -DskipTests -pl libs/common-lib,libs/event-schema,services/video-service -am

build-interaction:
	./mvnw clean install -DskipTests -pl libs/common-lib,libs/event-schema,libs/crypto-lib,services/interaction-service -am

build-story:
	./mvnw clean install -DskipTests -pl libs/common-lib,libs/event-schema,services/story-service -am

build-recommendation:
	./mvnw clean install -DskipTests -pl libs/common-lib,libs/event-schema,services/recommendation-service -am

build-chat:
	./mvnw clean install -DskipTests -pl libs/common-lib,libs/event-schema,services/chat-service -am

build-admin:
	./mvnw clean install -DskipTests -pl libs/common-lib,libs/event-schema,services/admin-service -am

build-gateway:
	./mvnw clean install -DskipTests -pl libs/common-lib,libs/event-schema,services/api-gateway -am

clean:
	./mvnw clean

# ──────────────────────────────────────────────
# Test
# ──────────────────────────────────────────────

test:
	./mvnw test

test-auth:
	./mvnw test -pl services/auth-service

test-user:
	./mvnw test -pl services/user-service

# ──────────────────────────────────────────────
# Infrastructure (Docker)
# ──────────────────────────────────────────────

# Redis không password, Elasticsearch/Kafka không auth, DB dùng password mặc định: publish ra
# 0.0.0.0 là mở cho cả mạng LAN. Mọi port phải bind 127.0.0.1.
infra-check:
	@docker compose config --format json | python3 -c 'import json,sys; c=json.load(sys.stdin); bad=[n+":"+str(p.get("published")) for n,s in c["services"].items() for p in s.get("ports",[]) if p.get("host_ip")!="127.0.0.1"]; print("exposed beyond loopback: "+", ".join(bad)) if bad else print("✓ every port is bound to 127.0.0.1"); sys.exit(1 if bad else 0)'

infra-up:
	docker-compose up -d
	@echo ""
	@echo "✓ Infrastructure started. Waiting for healthchecks..."
	@sleep 5
	@docker-compose ps

infra-down:
	docker-compose down

infra-reset:
	docker-compose down -v
	@echo "✓ All containers and volumes removed"

infra-logs:
	docker-compose logs -f

infra-status:
	docker-compose ps

# The file auth-service turns a sign-in's address into a place with (GeoIpLocator). ~120 MB and
# git-ignored; production fetches it through the geoip-init container instead.
geoip:
	sh scripts/geoip/download.sh data/geoip

# ──────────────────────────────────────────────
# Run services locally
# ──────────────────────────────────────────────

# Every service but the gateway listens on loopback only, the way a deployment publishes only the
# gateway's port: otherwise anyone on the same Wi-Fi reaches each service directly, past the
# gateway's rate limits and path rules. Not server.address in the services' own config, because
# the gateway has to stay reachable, and on Node 18 the frontends' proxy resolves "localhost" to
# ::1, which a 127.0.0.1-only gateway refuses. `make run-user BIND=0.0.0.0` to open one up.
BIND ?= 127.0.0.1
RUN_INTERNAL = ./mvnw spring-boot:run -Dspring-boot.run.profiles=local -Dspring-boot.run.arguments=--server.address=$(BIND)

run-gateway:
	./mvnw spring-boot:run -pl services/api-gateway -Dspring-boot.run.profiles=local

run-auth:
	$(RUN_INTERNAL) -pl services/auth-service

run-user:
	$(RUN_INTERNAL) -pl services/user-service

run-video:
	$(RUN_INTERNAL) -pl services/video-service

# Transcodes uploaded videos into HLS. Without it an upload stays PENDING forever:
# video-service only flips a video to PUBLISHED on the event this worker emits.
run-media:
	$(RUN_INTERNAL) -pl services/media-worker

run-interaction:
	$(RUN_INTERNAL) -pl services/interaction-service

run-story:
	$(RUN_INTERNAL) -pl services/story-service

run-recommendation:
	$(RUN_INTERNAL) -pl services/recommendation-service

run-chat:
	$(RUN_INTERNAL) -pl services/chat-service

run-notification:
	$(RUN_INTERNAL) -pl services/notification-service

# Cả hai service này phục vụ admin console (../tiktok-admin) và đều yêu cầu ROLE_ADMIN.
run-search:
	$(RUN_INTERNAL) -pl services/search-service

run-admin:
	$(RUN_INTERNAL) -pl services/admin-service

run-analytics:
	$(RUN_INTERNAL) -pl services/analytics-service

# ─────────────────────────────────────────────────────────────────
# Dead letters — kafka-lib's DltReplay; its class comment says when replaying is safe.
# KAFKA_BOOTSTRAP_SERVERS picks the cluster, the compose broker by default.
# ─────────────────────────────────────────────────────────────────

DLT_TOOL = ./mvnw -q -pl libs/kafka-lib compile exec:java -Dexec.classpathScope=compile \
	-Dexec.mainClass=com.tiktok.kafka.dlt.DltReplay

dlt-list:
	@test -n "$(TOPIC)" || { echo "TOPIC=<topic>.DLT is required"; exit 2; }
	$(DLT_TOOL) -Dexec.args="list $(TOPIC)"

dlt-replay:
	@test -n "$(TOPIC)" || { echo "TOPIC=<topic>.DLT is required"; exit 2; }
	@test -n "$(OFFSET)$(ALL)" || { echo "OFFSET=<partition:offset> or ALL=1 is required"; exit 2; }
	$(DLT_TOOL) -Dexec.args="replay $(TOPIC) $(if $(ALL),all,$(OFFSET))$(if $(FORCE), --force,)"

# ─────────────────────────────────────────────────────────────────
# moderation-service (Python)
# ─────────────────────────────────────────────────────────────────

# First build downloads the model weights into the image, so it is slow once and offline after.
moderation-up:
	docker compose up -d --build moderation-service

# verdict.py is pure arithmetic and imports nothing heavy, so the tests need neither torch nor
# the weights -- but they still run in the image, because that is the only place the Python
# version this service ships on is written down.
moderation-test:
	docker compose run --rm --no-deps --entrypoint sh \
		-v "$$(pwd)/services/moderation-service:/app" moderation-service -c \
		"pip install -q -r requirements-dev.txt && python -m pytest -q"

# The text classifier against a file of comments you have judged ("+ " should hold, "- " should
# not), on the running service: precision on this platform's text, before and after any change to
# the threshold or the weights. FILE=path/to/comments.txt
moderation-score-texts:
	python3 services/moderation-service/score_texts.py $(FILE)

# ─────────────────────────────────────────────────────────────────
# rank-service (Python)
# ─────────────────────────────────────────────────────────────────

rank-up:
	docker compose up -d --build rank-service

# Trains from the watch log analytics-service has been filling, then tells the running service
# to load the result. Needs ClickHouse up and some watch history in it.
rank-train:
	docker compose run --rm --no-deps \
		-e CLICKHOUSE_HOST=clickhouse \
		-v "$$(pwd)/services/rank-service/model:/app/model" \
		rank-service python train.py
	docker compose exec rank-service python -c \
		"import urllib.request; print(urllib.request.urlopen(urllib.request.Request('http://localhost:8098/reload', method='POST')).read())"

# The image does not carry the tests or pytest, so the source is mounted in. Running them in
# the image rather than a host venv is not fussiness: LightGBM needs libgomp, which macOS does
# not ship, and the Dockerfile is the only place that dependency is written down.
# The repo root goes in, not just this service: test_feature_contract.py reads the serving
# constants back out of recommendation-service's Java to check they still agree with features.py.
rank-test:
	docker compose run --rm --no-deps --entrypoint sh -w /repo/services/rank-service \
		-v "$$(pwd):/repo" rank-service -c \
		"pip install -q -r requirements-dev.txt && python -m pytest -q"

# ──────────────────────────────────────────────
# Database migrations
# ──────────────────────────────────────────────

migrate-auth:
	./mvnw flyway:migrate -pl services/auth-service

migrate-user:
	./mvnw flyway:migrate -pl services/user-service

migrate-admin:
	./mvnw flyway:migrate -pl services/admin-service

migrate-all:
	./mvnw flyway:migrate -pl services/auth-service,services/user-service,services/admin-service
# ============================================
# Demo seed (scripts/seed/README.md)
# ============================================
SEED_MEDIA ?= $(HOME)/Projects/EProject/EProject4/seed-media

seed-media:
	python3 scripts/seed/fetch-pixabay.py $(SEED_MEDIA)

seed:
	SEED_AUTH_LOG=$(SEED_AUTH_LOG) python3 -I scripts/seed/seed.py $(SEED_MEDIA) --phase all

seed-backdate:
	python3 -I scripts/seed/backdate.py $(SEED_MEDIA)

seed-refresh:
	python3 -I scripts/seed/seed.py $(SEED_MEDIA) --refresh
