--
-- Licensed to the Apache Software Foundation (ASF) under one
-- or more contributor license agreements. See the NOTICE file
-- distributed with this work for additional information
-- regarding copyright ownership. The ASF licenses this file
-- to you under the Apache License, Version 2.0 (the
-- "License"); you may not use this file except in compliance
-- with the License. You may obtain a copy of the License at
--
-- http://www.apache.org/licenses/LICENSE-2.0
--
-- Unless required by applicable law or agreed to in writing,
-- software distributed under the License is distributed on an
-- "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
-- KIND, either express or implied. See the License for the
-- specific language governing permissions and limitations
-- under the License.
--

-- Only the columns the schedule history read touches; types follow the Liquibase changelogs.
CREATE TABLE m_office (id BIGINT PRIMARY KEY, hierarchy VARCHAR(100));
CREATE TABLE m_client (id BIGINT PRIMARY KEY, office_id BIGINT NOT NULL, transfer_to_office_id BIGINT);
CREATE TABLE m_group (id BIGINT PRIMARY KEY, office_id BIGINT NOT NULL);
CREATE TABLE m_loan (id BIGINT PRIMARY KEY, client_id BIGINT, group_id BIGINT, currency_digits SMALLINT NOT NULL);
CREATE TABLE m_appuser (id BIGINT PRIMARY KEY, username VARCHAR(100) NOT NULL);
CREATE TABLE m_code_value (id INT PRIMARY KEY, code_value VARCHAR(100) NOT NULL);
CREATE TABLE m_loan_repayment_schedule_history (
    id BIGINT PRIMARY KEY,
    loan_id BIGINT NOT NULL,
    loan_reschedule_request_id BIGINT,
    fromdate DATE,
    duedate DATE NOT NULL,
    installment SMALLINT NOT NULL,
    principal_amount DECIMAL(19, 6),
    interest_amount DECIMAL(19, 6),
    fee_charges_amount DECIMAL(19, 6),
    penalty_charges_amount DECIMAL(19, 6),
    created_date TIMESTAMP(6),
    version INT NOT NULL,
    created_on_utc TIMESTAMP(6)
);
CREATE TABLE m_loan_reschedule_request (
    id BIGINT PRIMARY KEY,
    loan_id BIGINT NOT NULL,
    status_enum SMALLINT NOT NULL,
    reschedule_from_installment SMALLINT NOT NULL,
    reschedule_from_date DATE NOT NULL,
    reschedule_reason_cv_id INT,
    reschedule_reason_comment VARCHAR(500),
    submitted_on_date DATE NOT NULL,
    submitted_by_user_id BIGINT NOT NULL,
    approved_on_date DATE,
    approved_by_user_id BIGINT
);
CREATE TABLE m_loan_term_variations (
    id BIGINT PRIMARY KEY,
    loan_id BIGINT NOT NULL,
    term_type SMALLINT NOT NULL,
    applicable_date DATE NOT NULL,
    decimal_value DECIMAL(19, 6),
    date_value DATE,
    parent_id BIGINT
);
CREATE TABLE m_portfolio_command_source (
    id BIGINT PRIMARY KEY,
    action_name VARCHAR(50) NOT NULL,
    entity_name VARCHAR(50) NOT NULL,
    resource_id BIGINT,
    loan_id BIGINT,
    made_on_date_utc TIMESTAMP(6),
    checked_on_date_utc TIMESTAMP(6),
    status SMALLINT NOT NULL
);
CREATE TABLE m_loan_reschedule_request_term_variations_mapping (
    id BIGINT PRIMARY KEY,
    loan_reschedule_request_id BIGINT NOT NULL,
    loan_term_variations_id BIGINT NOT NULL
);

INSERT INTO m_office VALUES (1, '.'), (2, '.2.'), (3, '.3.');
INSERT INTO m_client VALUES (10, 2, NULL), (11, 3, NULL);
INSERT INTO m_appuser VALUES (1, 'mifos'), (2, 'approver');
INSERT INTO m_code_value VALUES (23, 'CBE rate change');

-- 100: archived by a foreclosure-style operation (no request, pre-UTC audit column only), then by two reschedules of
-- the same installments (both archives copy the installments' creation stamp; only the approvals tell them apart).
-- 101: never archived. 102: belongs to an office outside the caller's hierarchy; its archive row (version 1,
-- installment 1) would show up in 100's version 1 if the loan filter were missing.
INSERT INTO m_loan VALUES (100, 10, NULL, 2), (101, 10, NULL, 2), (102, 11, NULL, 2);

-- Request 1 changed the rate from installment 3 (1 Feb 2027). Request 2 then moved installment 2 from 1 Jan to 5 Jan
-- with a grace on principal; approving it shifted request 1's stored applicable_date in place to 5 Feb, and core
-- derived an extend-repayment-period child (parent_id) from the grace.
INSERT INTO m_loan_reschedule_request VALUES (1, 100, 200, 3, '2027-02-01', 23, '', '2026-11-12', 1, '2026-11-12', 2),
    (2, 100, 200, 2, '2027-01-01', 23, NULL, '2026-12-03', 1, '2026-12-03', 1);
-- Request 1: processed approval, plus rows that must be ignored and would win the max() otherwise: an unprocessed retry,
-- and a loan APPROVE whose resource id (a loan id) collides with request 1's id. Request 2: no command.
INSERT INTO m_portfolio_command_source VALUES
    (1, 'CREATE', 'RESCHEDULELOAN', 1, 100, '2026-11-08 14:05:01.362909', NULL, 1),
    (2, 'APPROVE', 'RESCHEDULELOAN', 1, 100, '2026-11-08 14:05:03.776947', NULL, 1),
    (3, 'APPROVE', 'RESCHEDULELOAN', 1, 100, '2026-11-09 09:00:00.000000', NULL, 2),
    (4, 'APPROVE', 'LOAN', 1, 1, '2026-11-09 09:00:00.000000', NULL, 1);
INSERT INTO m_loan_term_variations VALUES (1, 100, 10, '2027-02-05', 26.000000, NULL, NULL),
    (2, 102, 2, '2026-12-01', 30.000000, NULL, NULL),
    (3, 100, 4, '2027-01-01', NULL, '2027-01-05', NULL),
    (4, 100, 8, '2027-01-01', 2.000000, NULL, NULL),
    (5, 100, 9, '2027-01-01', 2.000000, NULL, 4);
INSERT INTO m_loan_reschedule_request_term_variations_mapping VALUES (1, 1, 1), (2, 2, 3), (3, 2, 4), (4, 2, 5);

INSERT INTO m_loan_repayment_schedule_history VALUES
    (1, 100, NULL, '2026-01-08', '2026-02-01', 2, 1000.000000, 300.004000, 20.000000, NULL, '2026-02-17 13:20:26.823820', 1, NULL),
    (2, 100, NULL, '2025-12-08', '2026-01-01', 1, 990.000000, 310.000000, 20.000000, 0.000000, '2026-02-17 13:20:26.823820', 1, NULL),
    (3, 100, 1, '2026-12-01', '2027-01-01', 2, 1010.000000, 290.000000, 20.000000, 0.000000, NULL, 2, '2026-07-08 13:00:52.895358'),
    (4, 100, 1, '2026-11-08', '2026-12-01', 1, 1000.000000, 300.000000, 20.000000, 0.000000, NULL, 2, '2026-07-08 13:00:52.886972'),
    (6, 100, 2, '2026-11-08', '2026-12-01', 1, 1000.000000, 312.000000, 20.000000, 0.000000, NULL, 3, '2026-07-08 13:00:52.886972'),
    (7, 100, 2, '2026-12-01', '2027-01-01', 2, 1000.000000, 312.000000, 20.000000, 0.000000, NULL, 3, '2026-07-08 13:00:52.895358'),
    (5, 102, NULL, '2026-11-08', '2026-12-01', 1, 1.000000, 1.000000, 0.000000, 0.000000, NULL, 1, '2026-10-28 12:46:07.580910');
