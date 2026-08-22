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
package org.apache.fineract.infrastructure.core.service.database;

import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

public class DatabaseSpecificSQLGeneratorTest {

    private final DatabaseTypeResolver databaseTypeResolver = Mockito.mock(DatabaseTypeResolver.class);
    private final RoutingDataSource dataSource = Mockito.mock(RoutingDataSource.class);
    private final DatabaseSpecificSQLGenerator databaseSpecificSQLGenerator = new DatabaseSpecificSQLGenerator(databaseTypeResolver,
            dataSource);

    @Test
    public void testCountQueryResultOnEmptyString() {
        String sql = "";
        String countQuery = databaseSpecificSQLGenerator.countQueryResult(sql);
        Assertions.assertEquals("SELECT COUNT(*) FROM () AS temp", countQuery);
    }

    @Test
    public void testCountQueryResultOnSqlWithoutLimitOrOffset() {
        String sql = "SELECT 1 FROM test_table WHERE asd=2";
        String countQuery = databaseSpecificSQLGenerator.countQueryResult(sql);
        Assertions.assertEquals("SELECT COUNT(*) FROM (" + sql + ") AS temp", countQuery);
    }

    @Test
    public void testCountQueryResultOnSqlWithLimit() {
        String sql = "SELECT 1 FROM test_table WHERE asd=2 LIMIT 2";
        String countQuery = databaseSpecificSQLGenerator.countQueryResult(sql);
        Assertions.assertEquals("SELECT COUNT(*) FROM (SELECT 1 FROM test_table WHERE asd=2) AS temp", countQuery);
    }

    @Test
    public void testCountQueryResultOnSqlWithOffset() {
        String sql = "SELECT 1 FROM test_table WHERE asd=2 OFFSET 2";
        String countQuery = databaseSpecificSQLGenerator.countQueryResult(sql);
        Assertions.assertEquals("SELECT COUNT(*) FROM (SELECT 1 FROM test_table WHERE asd=2) AS temp", countQuery);
    }

    @Test
    public void testCountQueryResultOnSqlWithLimitAndOffset() {
        String sql = "SELECT 1 FROM test_table WHERE asd=2 OFFSET 2 LIMIT 50";
        String countQuery = databaseSpecificSQLGenerator.countQueryResult(sql);
        Assertions.assertEquals("SELECT COUNT(*) FROM (SELECT 1 FROM test_table WHERE asd=2) AS temp", countQuery);
    }

    @Test
    public void testInsertOnConflictUpdateOnMySql() {
        Mockito.when(databaseTypeResolver.isMySQL()).thenReturn(true);

        String clause = databaseSpecificSQLGenerator.insertOnConflictUpdate(List.of("loan_id"),
                List.of("principal_overdue_derived", "total_overdue_derived"));

        Assertions.assertEquals(" ON DUPLICATE KEY UPDATE `principal_overdue_derived`=VALUES(`principal_overdue_derived`), "
                + "`total_overdue_derived`=VALUES(`total_overdue_derived`)", clause);
    }

    @Test
    public void testInsertOnConflictUpdateOnPostgreSql() {
        Mockito.when(databaseTypeResolver.isMySQL()).thenReturn(false);
        Mockito.when(databaseTypeResolver.isPostgreSQL()).thenReturn(true);

        String clause = databaseSpecificSQLGenerator.insertOnConflictUpdate(List.of("loan_id"),
                List.of("principal_overdue_derived", "total_overdue_derived"));

        Assertions.assertEquals(
                " ON CONFLICT (\"loan_id\") DO UPDATE SET \"principal_overdue_derived\"=EXCLUDED.\"principal_overdue_derived\", "
                        + "\"total_overdue_derived\"=EXCLUDED.\"total_overdue_derived\"",
                clause);
    }

    @Test
    public void testInsertOnConflictUpdateOnPostgreSqlRequiresConflictColumns() {
        Mockito.when(databaseTypeResolver.isMySQL()).thenReturn(false);
        Mockito.when(databaseTypeResolver.isPostgreSQL()).thenReturn(true);

        IllegalArgumentException exception = Assertions.assertThrows(IllegalArgumentException.class,
                () -> databaseSpecificSQLGenerator.insertOnConflictUpdate(List.of(), List.of("principal_overdue_derived")));

        Assertions.assertEquals("conflictColumns must not be empty for PostgreSQL ON CONFLICT clause", exception.getMessage());
    }
}
