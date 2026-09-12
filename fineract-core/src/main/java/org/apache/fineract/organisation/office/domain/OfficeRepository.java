/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.fineract.organisation.office.domain;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.apache.fineract.infrastructure.core.domain.ExternalId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OfficeRepository extends JpaRepository<Office, Long>, JpaSpecificationExecutor<Office> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select office from Office office where office.id = :id")
    Optional<Office> findForAccountingLockById(@Param("id") Long id);

    // A committed closure change must change the locked row, including for PostgreSQL repeatable-read snapshots.
    @Modifying(flushAutomatically = true)
    @Query(value = "update m_office set accounting_closure_version = accounting_closure_version + 1 where id = :id", nativeQuery = true)
    int incrementAccountingClosureVersion(@Param("id") Long id);

    Optional<Office> findByExternalId(ExternalId externalId);
}
