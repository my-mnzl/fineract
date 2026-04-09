.PHONY: dev db-seed

DB_HOST=localhost
DB_PORT=3307
DB_USER=root
DB_PASS=password
DB_CONTAINER=mnzl-local-mariadb

db-seed:
	docker exec -i $(DB_CONTAINER) mariadb -u$(DB_USER) -p$(DB_PASS) -e \
	  "CREATE DATABASE IF NOT EXISTS fineract_tenants; CREATE DATABASE IF NOT EXISTS fineract_default;"

dev:
	FINERACT_HIKARI_JDBC_URL=jdbc:mariadb://$(DB_HOST):$(DB_PORT)/fineract_tenants \
	FINERACT_HIKARI_USERNAME=$(DB_USER) \
	FINERACT_HIKARI_PASSWORD=$(DB_PASS) \
	FINERACT_DEFAULT_TENANTDB_HOSTNAME=$(DB_HOST) \
	FINERACT_DEFAULT_TENANTDB_PORT=$(DB_PORT) \
	FINERACT_DEFAULT_TENANTDB_UID=$(DB_USER) \
	FINERACT_DEFAULT_TENANTDB_PWD=$(DB_PASS) \
	FINERACT_LIQUIBASE_ENABLED=false \
	./gradlew :fineract-provider:devRun \
	  --args="--server.ssl.enabled=false --server.port=8080"
