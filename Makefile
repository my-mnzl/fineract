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

.PHONY: dev db-seed db-custom-migrate

DB_HOST=localhost
DB_PORT=3307
DB_USER=root
DB_PASS=password
DB_CONTAINER=mnzl-local-mariadb

db-seed:
	docker exec -i $(DB_CONTAINER) mariadb -u$(DB_USER) -p$(DB_PASS) -e \
	  "CREATE DATABASE IF NOT EXISTS fineract_tenants; CREATE DATABASE IF NOT EXISTS fineract_default;"

db-custom-migrate:
	docker exec -i $(DB_CONTAINER) mariadb -u$(DB_USER) -p$(DB_PASS) fineract_default -e " \
	  CREATE TABLE IF NOT EXISTS m_mnzl_simulation ( \
	    id BIGINT AUTO_INCREMENT NOT NULL, \
	    uuid VARCHAR(36) NOT NULL, \
	    name VARCHAR(255), \
	    status VARCHAR(20) NOT NULL, \
	    progress INT NOT NULL DEFAULT 0, \
	    total_actions INT NOT NULL DEFAULT 0, \
	    loan_product_id BIGINT NOT NULL, \
	    principal DECIMAL(19,6) NOT NULL, \
	    interest_rate DECIMAL(19,6) NOT NULL, \
	    number_of_repayments INT NOT NULL, \
	    scenario_json TEXT NOT NULL, \
	    result_json TEXT, \
	    error_message TEXT, \
	    created_by BIGINT NOT NULL, \
	    created_date TIMESTAMP NULL, \
	    started_at TIMESTAMP NULL, \
	    completed_at TIMESTAMP NULL, \
	    CONSTRAINT pk_m_mnzl_simulation PRIMARY KEY (id), \
	    CONSTRAINT uk_m_mnzl_simulation_uuid UNIQUE (uuid), \
	    CONSTRAINT fk_m_mnzl_simulation_product FOREIGN KEY (loan_product_id) REFERENCES m_product_loan(id), \
	    CONSTRAINT fk_m_mnzl_simulation_user FOREIGN KEY (created_by) REFERENCES m_appuser(id) \
	  ); \
	  INSERT IGNORE INTO m_permission (grouping, code, entity_name, action_name, can_maker_checker) VALUES \
	    ('portfolio', 'CREATE_MNZL_SIMULATION', 'MNZL_SIMULATION', 'CREATE', 0), \
	    ('portfolio', 'READ_MNZL_SIMULATION', 'MNZL_SIMULATION', 'READ', 0), \
	    ('portfolio', 'DELETE_MNZL_SIMULATION', 'MNZL_SIMULATION', 'DELETE', 0); \
	"

dev:
	FINERACT_HIKARI_JDBC_URL=jdbc:mariadb://$(DB_HOST):$(DB_PORT)/fineract_tenants \
	FINERACT_HIKARI_USERNAME=$(DB_USER) \
	FINERACT_HIKARI_PASSWORD=$(DB_PASS) \
	FINERACT_DEFAULT_TENANTDB_HOSTNAME=$(DB_HOST) \
	FINERACT_DEFAULT_TENANTDB_PORT=$(DB_PORT) \
	FINERACT_DEFAULT_TENANTDB_UID=$(DB_USER) \
	FINERACT_DEFAULT_TENANTDB_PWD=$(DB_PASS) \
	FINERACT_LIQUIBASE_ENABLED=false \
	MNZL_LOAN_SIMULATOR_INSURANCE_CHARGE_ID=$(if $(MNZL_INSURANCE_CHARGE_ID),$(MNZL_INSURANCE_CHARGE_ID),7) \
	./gradlew :fineract-provider:devRun \
	  --args="--server.ssl.enabled=false --server.port=8080"
