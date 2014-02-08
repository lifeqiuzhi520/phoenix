/*
 * Copyright 2014 The Apache Software Foundation
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.phoenix.compile;

import static org.apache.phoenix.util.TestUtil.TEST_PROPERTIES;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.filter.Filter;
import org.apache.hadoop.hbase.filter.FilterList;
import org.junit.Ignore;
import org.junit.Test;

import org.apache.phoenix.compile.GroupByCompiler.GroupBy;
import org.apache.phoenix.expression.Expression;
import org.apache.phoenix.filter.SkipScanFilter;
import org.apache.phoenix.jdbc.PhoenixConnection;
import org.apache.phoenix.parse.SQLParser;
import org.apache.phoenix.parse.SelectStatement;
import org.apache.phoenix.query.BaseConnectionlessQueryTest;
import org.apache.phoenix.util.QueryUtil;


/**
 * Test compilation of various statements with hints.
 */
public class StatementHintsCompilationTest extends BaseConnectionlessQueryTest {

    private static StatementContext compileStatement(String query, Scan scan, List<Object> binds) throws SQLException {
        return compileStatement(query, scan, binds, null, null);
    }
    
    private static boolean usingSkipScan(Scan scan) {
        Filter filter = scan.getFilter();
        if (filter instanceof FilterList) {
            FilterList filterList = (FilterList) filter;
            for (Filter childFilter : filterList.getFilters()) {
                if (childFilter instanceof SkipScanFilter) {
                    return true;
                }
            }
            return false;
        }
        return filter instanceof SkipScanFilter;
    }

    private static StatementContext compileStatement(String query, Scan scan, List<Object> binds, Integer limit, Set<Expression> extractedNodes) throws SQLException {
        SQLParser parser = new SQLParser(query);
        SelectStatement statement = parser.parseQuery();
        PhoenixConnection pconn = DriverManager.getConnection(getUrl(), TEST_PROPERTIES).unwrap(PhoenixConnection.class);
        ColumnResolver resolver = FromCompiler.getResolver(statement, pconn);
        statement = StatementNormalizer.normalize(statement, resolver);
        StatementContext context = new StatementContext(statement, pconn, resolver, binds, scan);

        Integer actualLimit = LimitCompiler.compile(context, statement);
        assertEquals(limit, actualLimit);
        GroupBy groupBy = GroupByCompiler.compile(context, statement);
        statement = HavingCompiler.rewrite(context, statement, groupBy);
        WhereCompiler.compileWhereClause(context, statement, extractedNodes);
        return context;
    }

    @Test
    public void testSelectForceSkipScan() throws Exception {
        String id = "000000000000001";
        // A where clause without the first column usually compiles into a range scan.
        String query = "SELECT /*+ SKIP_SCAN */ * FROM atable WHERE entity_id='" + id + "'";
        Scan scan = new Scan();
        List<Object> binds = Collections.emptyList();
        
        compileStatement(query, scan, binds);
        assertTrue("The first filter should be SkipScanFilter.", usingSkipScan(scan));
    }

    @Test
    public void testSelectForceRangeScan() throws Exception {
        String query = "SELECT /*+ RANGE_SCAN */ * FROM atable WHERE organization_id in (" +
                "'000000000000001', '000000000000002', '000000000000003', '000000000000004')";
        Scan scan = new Scan();
        List<Object> binds = Collections.emptyList();
        
        compileStatement(query, scan, binds);
        // Verify that it is not using SkipScanFilter.
        assertFalse("The first filter should not be SkipScanFilter.", usingSkipScan(scan));
    }
    
    @Ignore @Test
    public void testSelectForceRangeScanForEH() throws Exception {
        Connection conn = DriverManager.getConnection(getUrl());
        conn.createStatement().execute("create table eh (organization_id char(15) not null,parent_id char(15) not null, created_date date not null, entity_history_id char(15) not null constraint pk primary key (organization_id, parent_id, created_date, entity_history_id))");
        ResultSet rs = conn.createStatement().executeQuery("explain select /*+ RANGE_SCAN */ ORGANIZATION_ID, PARENT_ID, CREATED_DATE, ENTITY_HISTORY_ID from eh where ORGANIZATION_ID='111111111111111' and SUBSTR(PARENT_ID, 1, 3) = 'foo' and TO_DATE ('2012-0-1 00:00:00') <= CREATED_DATE and CREATED_DATE <= TO_DATE ('2012-11-31 00:00:00') order by ORGANIZATION_ID, PARENT_ID, CREATED_DATE DESC, ENTITY_HISTORY_ID limit 100");
        assertEquals("CLIENT PARALLEL 1-WAY RANGE SCAN OVER EH ['111111111111111','foo','2011-12-01 00:00:00.000'] - ['111111111111111','fop','2012-12-01 00:00:00.000']\n" + 
                "    SERVER FILTER BY (CREATED_DATE >= 2011-11-30 AND CREATED_DATE <= 2012-11-30)\n" + 
                "    SERVER TOP 100 ROWS SORTED BY [ORGANIZATION_ID, PARENT_ID, CREATED_DATE DESC, ENTITY_HISTORY_ID]\n" + 
                "CLIENT MERGE SORT",QueryUtil.getExplainPlan(rs));
    }
}
