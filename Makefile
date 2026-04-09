# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements. See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership. The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License. You may obtain a copy of the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied. See the License for the
# specific language governing permissions and limitations
# under the License.

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
