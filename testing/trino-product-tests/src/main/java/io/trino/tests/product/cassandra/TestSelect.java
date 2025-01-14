/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.trino.tests.product.cassandra;

import io.airlift.units.Duration;
import io.trino.jdbc.Row;
import io.trino.tempto.ProductTest;
import io.trino.tempto.Requirement;
import io.trino.tempto.RequirementsProvider;
import io.trino.tempto.configuration.Configuration;
import io.trino.tempto.internal.query.CassandraQueryExecutor;
import io.trino.tempto.query.QueryResult;
import org.testng.annotations.Test;

import java.util.function.Consumer;

import static io.trino.tempto.assertions.QueryAssert.Row.row;
import static io.trino.tempto.fulfillment.table.TableRequirements.immutableTable;
import static io.trino.tests.product.TestGroups.CASSANDRA;
import static io.trino.tests.product.TestGroups.PROFILE_SPECIFIC_TESTS;
import static io.trino.tests.product.cassandra.CassandraTpchTableDefinitions.CASSANDRA_SUPPLIER;
import static io.trino.tests.product.cassandra.TestConstants.CONNECTOR_NAME;
import static io.trino.tests.product.cassandra.TestConstants.KEY_SPACE;
import static io.trino.tests.product.utils.QueryAssertions.assertContainsEventually;
import static io.trino.tests.product.utils.QueryExecutors.onTrino;
import static java.lang.String.format;
import static java.util.concurrent.TimeUnit.MINUTES;
import static org.assertj.core.api.Assertions.assertThat;

public class TestSelect
        extends ProductTest
        implements RequirementsProvider
{
    private Configuration configuration;

    @Override
    public Requirement getRequirements(Configuration configuration)
    {
        this.configuration = configuration;
        return immutableTable(CASSANDRA_SUPPLIER);
    }

    @Test(groups = {CASSANDRA, PROFILE_SPECIFIC_TESTS})
    public void testSelectWithMorePartitioningKeysThanLimit()
    {
        String sql = format(
                "SELECT s_suppkey FROM %s.%s.%s WHERE s_suppkey = 10",
                CONNECTOR_NAME,
                KEY_SPACE,
                CASSANDRA_SUPPLIER.getName());
        QueryResult queryResult = onTrino()
                .executeQuery(sql);

        assertThat(queryResult).containsOnly(row(10));
    }

    @Test(groups = {CASSANDRA, PROFILE_SPECIFIC_TESTS})
    public void testSelectWithMorePartitioningKeysThanLimitNonPK()
    {
        String sql = format(
                "SELECT s_suppkey FROM %s.%s.%s WHERE s_name = 'Supplier#000000010'",
                CONNECTOR_NAME,
                KEY_SPACE,
                CASSANDRA_SUPPLIER.getName());
        QueryResult queryResult = onTrino()
                .executeQuery(sql);

        assertThat(queryResult).containsOnly(row(10));
    }

    @Test(groups = {CASSANDRA, PROFILE_SPECIFIC_TESTS})
    public void testSelectClusteringMaterializedView()
    {
        String mvName = "clustering_mv";
        onCassandra(format("DROP MATERIALIZED VIEW IF EXISTS %s.%s", KEY_SPACE, mvName));
        onCassandra(format("CREATE MATERIALIZED VIEW %s.%s AS " +
                        "SELECT * FROM %s.%s " +
                        "WHERE s_nationkey IS NOT NULL " +
                        "PRIMARY KEY (s_nationkey, s_suppkey) " +
                        "WITH CLUSTERING ORDER BY (s_nationkey DESC)",
                KEY_SPACE,
                mvName,
                KEY_SPACE,
                CASSANDRA_SUPPLIER.getName()));

        assertContainsEventually(() -> onTrino().executeQuery(format("SHOW TABLES FROM %s.%s", CONNECTOR_NAME, KEY_SPACE)),
                onTrino().executeQuery(format("SELECT '%s'", mvName)),
                new Duration(1, MINUTES));

        // Materialized view may not return all results during the creation
        assertContainsEventually(() -> onTrino().executeQuery(format("SELECT status_replicated FROM %s.system.built_views WHERE view_name = '%s'", CONNECTOR_NAME, mvName)),
                onTrino().executeQuery("SELECT true"),
                new Duration(1, MINUTES));

        QueryResult aggregateQueryResult = onTrino()
                .executeQuery(format(
                        "SELECT MAX(s_nationkey), SUM(s_suppkey), AVG(s_acctbal) " +
                                "FROM %s.%s.%s WHERE s_suppkey BETWEEN 1 AND 10 ", CONNECTOR_NAME, KEY_SPACE, mvName));
        assertThat(aggregateQueryResult).containsOnly(
                row(24, 55, 4334.653));

        QueryResult orderedResult = onTrino()
                .executeQuery(format(
                        "SELECT s_nationkey, s_suppkey, s_acctbal " +
                                "FROM %s.%s.%s ORDER BY s_nationkey LIMIT 1", CONNECTOR_NAME, KEY_SPACE, mvName));
        assertThat(orderedResult).containsOnly(
                row(0, 24, 9170.71));

        onCassandra(format("DROP MATERIALIZED VIEW IF EXISTS %s.%s", KEY_SPACE, mvName));
    }

    @Test(groups = {CASSANDRA, PROFILE_SPECIFIC_TESTS})
    public void testSelectTupleTypeInPrimaryKey()
    {
        String tableName = "select_tuple_in_primary_key_table";
        onCassandra(format("DROP TABLE IF EXISTS %s.%s", KEY_SPACE, tableName));

        onCassandra(format("CREATE TABLE %s.%s (intkey int, tuplekey frozen<tuple<int, text, float>>, PRIMARY KEY (intkey, tuplekey))",
                KEY_SPACE, tableName));

        onCassandra(format("INSERT INTO %s.%s (intkey, tuplekey) VALUES(1, (1, 'text-1', 1.11))", KEY_SPACE, tableName));

        Consumer<QueryResult> assertion = queryResult -> {
            assertThat(queryResult).hasRowsCount(1);
            assertThat(queryResult.row(0).get(0)).isEqualTo(1);
            assertThat(queryResult.row(0).get(1)).isEqualTo(Row.builder()
                    .addUnnamedField(1)
                    .addUnnamedField("text-1")
                    .addUnnamedField(1.11f)
                    .build());
        };
        assertion.accept(onTrino().executeQuery(format("SELECT * FROM %s.%s.%s", CONNECTOR_NAME, KEY_SPACE, tableName)));
        assertion.accept(onTrino().executeQuery(format("SELECT * FROM %s.%s.%s WHERE intkey = 1 and tuplekey = row(1, 'text-1', 1.11)", CONNECTOR_NAME, KEY_SPACE, tableName)));

        onCassandra(format("DROP TABLE IF EXISTS %s.%s", KEY_SPACE, tableName));
    }

    @Test(groups = {CASSANDRA, PROFILE_SPECIFIC_TESTS})
    public void testSelectUserDefinedTypeInPrimaryKey()
    {
        String udtName = "type_user_defined_primary_key";
        String tableName = "select_udt_in_primary_key_table";

        onCassandra(format("DROP TABLE IF EXISTS %s.%s", KEY_SPACE, tableName));
        onCassandra(format("DROP TYPE IF EXISTS %s.%s", KEY_SPACE, udtName));

        onCassandra(format("CREATE TYPE %s.%s (field1 text)", KEY_SPACE, udtName));
        onCassandra(format("CREATE TABLE %s.%s (intkey int, udtkey frozen<%s>, PRIMARY KEY (intkey, udtkey))", KEY_SPACE, tableName, udtName));

        onCassandra(format("INSERT INTO %s.%s (intkey, udtkey) VALUES(1, {field1: 'udt-1'})", KEY_SPACE, tableName));

        Consumer<QueryResult> assertion = queryResult -> {
            assertThat(queryResult).hasRowsCount(1);
            assertThat(queryResult.row(0).get(0)).isEqualTo(1);
            assertThat(queryResult.row(0).get(1)).isEqualTo(Row.builder()
                    .addField("field1", "udt-1")
                    .build());
        };
        assertion.accept(onTrino().executeQuery(format("SELECT * FROM %s.%s.%s", CONNECTOR_NAME, KEY_SPACE, tableName)));
        assertion.accept(onTrino().executeQuery(format("SELECT * FROM %s.%s.%s WHERE intkey = 1 AND udtkey = CAST(ROW('udt-1') AS ROW(x VARCHAR))", CONNECTOR_NAME, KEY_SPACE, tableName)));

        onCassandra(format("DROP TABLE %s.%s", KEY_SPACE, tableName));
        onCassandra(format("DROP TYPE %s.%s", KEY_SPACE, udtName));
    }

    private void onCassandra(String query)
    {
        try (CassandraQueryExecutor queryExecutor = new CassandraQueryExecutor(configuration)) {
            queryExecutor.executeQuery(query);
        }
    }
}
