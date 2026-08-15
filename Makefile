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
	build build-libs build-auth build-user build-video build-order build-interaction \
	build-story build-recommendation build-chat build-gateway \
	build-product build-payment build-inventory build-admin \
	clean \
	test test-auth test-user \
	infra-up infra-down infra-reset infra-logs infra-status \
	run-gateway run-auth run-user run-video run-interaction run-story \
	run-recommendation run-chat run-order run-payment run-inventory \
	run-admin run-analytics \
	migrate-auth migrate-user migrate-order migrate-product migrate-payment \
	migrate-inventory migrate-admin migrate-all

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
	@echo "    make build-order        Build order-service"
	@echo "    make build-product      Build product-service"
	@echo "    make build-payment      Build payment-service"
	@echo "    make build-inventory    Build inventory-service"
	@echo "    make build-admin        Build admin-service"
	@echo "    make build-gateway      Build api-gateway"
	@echo "    make clean              Clean tất cả target/"
	@echo ""
	@echo "  Test:"
	@echo "    make test               Chạy toàn bộ tests"
	@echo "    make test-auth          Chạy tests của auth-service"
	@echo "    make test-user          Chạy tests của user-service"
	@echo ""
	@echo "  Infrastructure:"
	@echo "    make infra-up           Start tất cả Docker containers"
	@echo "    make infra-down         Stop containers (giữ data)"
	@echo "    make infra-reset        Stop containers + xoá data (volumes)"
	@echo "    make infra-logs         Xem logs tất cả containers"
	@echo "    make infra-status       Xem trạng thái containers"
	@echo ""
	@echo "  Run services (local):"
	@echo "    make run-gateway        Run api-gateway :8080"
	@echo "    make run-auth           Run auth-service :8081"
	@echo "    make run-user           Run user-service :8082"
	@echo "    make run-video          Run video-service :8083"
	@echo "    make run-interaction    Run interaction-service :8085"
	@echo "    make run-story          Run story-service :8086"
	@echo "    make run-recommendation Run recommendation-service :8087"
	@echo "    make run-chat           Run chat-service :8088"
	@echo "    make run-order          Run order-service :8092"
	@echo "    make run-payment        Run payment-service"
	@echo "    make run-inventory      Run inventory-service"
	@echo "    make run-admin          Run admin-service :8096"
	@echo "    make run-analytics      Run analytics-service :8097"
	@echo ""
	@echo "  Database:"
	@echo "    make migrate-auth       Chạy Flyway migration cho auth-service"
	@echo "    make migrate-user       Chạy Flyway migration cho user-service"
	@echo "    make migrate-order      Chạy Flyway migration cho order-service"
	@echo "    make migrate-product    Chạy Flyway migration cho product-service"
	@echo "    make migrate-payment    Chạy Flyway migration cho payment-service"
	@echo "    make migrate-inventory  Chạy Flyway migration cho inventory-service"
	@echo "    make migrate-admin      Chạy Flyway migration cho admin-service"
	@echo "    make migrate-all        Chạy Flyway migration tất cả PostgreSQL services"
	@echo ""

# ──────────────────────────────────────────────
# Build
# ──────────────────────────────────────────────

build:
	./mvnw clean install -DskipTests

build-libs:
	./mvnw clean install -DskipTests -pl libs/common-lib,libs/event-schema,libs/crypto-lib

build-auth:
	./mvnw clean install -DskipTests -pl libs/common-lib,libs/event-schema,libs/crypto-lib,services/auth-service -am

build-user:
	./mvnw clean install -DskipTests -pl libs/common-lib,libs/event-schema,services/user-service -am

build-video:
	./mvnw clean install -DskipTests -pl libs/common-lib,libs/event-schema,services/video-service -am

build-order:
	./mvnw clean install -DskipTests -pl libs/common-lib,libs/event-schema,services/order-service -am

build-interaction:
	./mvnw clean install -DskipTests -pl libs/common-lib,libs/event-schema,libs/crypto-lib,services/interaction-service -am

build-story:
	./mvnw clean install -DskipTests -pl libs/common-lib,libs/event-schema,services/story-service -am

build-recommendation:
	./mvnw clean install -DskipTests -pl libs/common-lib,libs/event-schema,services/recommendation-service -am

build-chat:
	./mvnw clean install -DskipTests -pl libs/common-lib,libs/event-schema,services/chat-service -am

build-product:
	./mvnw clean install -DskipTests -pl libs/common-lib,libs/event-schema,services/product-service -am

build-payment:
	./mvnw clean install -DskipTests -pl libs/common-lib,libs/event-schema,services/payment-service -am

build-inventory:
	./mvnw clean install -DskipTests -pl libs/common-lib,libs/event-schema,services/inventory-service -am

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

# ──────────────────────────────────────────────
# Run services locally
# ──────────────────────────────────────────────

run-gateway:
	./mvnw spring-boot:run -pl services/api-gateway -Dspring-boot.run.profiles=local

run-auth:
	./mvnw spring-boot:run -pl services/auth-service -Dspring-boot.run.profiles=local

run-user:
	./mvnw spring-boot:run -pl services/user-service -Dspring-boot.run.profiles=local

run-video:
	./mvnw spring-boot:run -pl services/video-service -Dspring-boot.run.profiles=local

run-interaction:
	./mvnw spring-boot:run -pl services/interaction-service -Dspring-boot.run.profiles=local

run-story:
	./mvnw spring-boot:run -pl services/story-service -Dspring-boot.run.profiles=local

run-recommendation:
	./mvnw spring-boot:run -pl services/recommendation-service -Dspring-boot.run.profiles=local

run-chat:
	./mvnw spring-boot:run -pl services/chat-service -Dspring-boot.run.profiles=local

run-order:
	./mvnw spring-boot:run -pl services/order-service -Dspring-boot.run.profiles=local

run-payment:
	./mvnw spring-boot:run -pl services/payment-service -Dspring-boot.run.profiles=local

run-inventory:
	./mvnw spring-boot:run -pl services/inventory-service -Dspring-boot.run.profiles=local

# Cả hai service này phục vụ admin console (../tiktok-admin) và đều yêu cầu ROLE_ADMIN.
run-admin:
	./mvnw spring-boot:run -pl services/admin-service -Dspring-boot.run.profiles=local

run-analytics:
	./mvnw spring-boot:run -pl services/analytics-service -Dspring-boot.run.profiles=local

# ──────────────────────────────────────────────
# Database migrations
# ──────────────────────────────────────────────

migrate-auth:
	./mvnw flyway:migrate -pl services/auth-service

migrate-user:
	./mvnw flyway:migrate -pl services/user-service

migrate-order:
	./mvnw flyway:migrate -pl services/order-service

migrate-product:
	./mvnw flyway:migrate -pl services/product-service

migrate-payment:
	./mvnw flyway:migrate -pl services/payment-service

migrate-inventory:
	./mvnw flyway:migrate -pl services/inventory-service

migrate-admin:
	./mvnw flyway:migrate -pl services/admin-service

migrate-all:
	./mvnw flyway:migrate -pl services/auth-service,services/user-service,services/product-service,services/order-service,services/payment-service,services/inventory-service,services/admin-service