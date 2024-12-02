/*******************************************************************************
 *     ___                  _   ____  ____
 *    / _ \ _   _  ___  ___| |_|  _ \| __ )
 *   | | | | | | |/ _ \/ __| __| | | |  _ \
 *   | |_| | |_| |  __/\__ \ |_| |_| | |_) |
 *    \__\_\\__,_|\___||___/\__|____/|____/
 *
 *  Copyright (c) 2014-2019 Appsicle
 *  Copyright (c) 2019-2024 QuestDB
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 ******************************************************************************/

package io.questdb.test.cutlass.pgwire;

import org.junit.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.locks.LockSupport;

import static org.junit.Assert.assertEquals;

// Reproducing https://github.com/questdb/questdb/issues/5202

// Database fixture:
/*
CREATE TABLE bugtest(
  timestamp TIMESTAMP,
  symbol SYMBOL,
  price DOUBLE,
  amount DOUBLE
  ) TIMESTAMP(timestamp)
PARTITION BY DAY;

INSERT INTO bugtest
VALUES
    ('2021-10-05T11:31:35.878Z', 'AAPL', 245, 123.4),
    ('2021-10-05T12:31:35.878Z', 'AAPL', 245, 123.3),
    ('2021-10-05T13:31:35.878Z', 'AAPL', 250, 123.1),
    ('2021-10-05T14:31:35.878Z', 'AAPL', 250, 123.0);
 */

// Run this JUnit test concurrently with the following script:

/*
#! /bin/bash

set -e

export PGPASSWORD=quest
expected=$(cat expected.txt)
while true; do
    actual=$(psql -h localhost -p 8812 -U admin -d qdb -c "SELECT 1 FROM bugtest")
    if [[ "$actual" != "$expected" ]]; then
        echo "Error! Expected:"
        echo "$expected"
        echo "Actual:"
        echo "$actual"
        exit
    fi
done
 */

// expected.txt:
// Watch out, the first line must have one trailing space!

/*
 1
---
 1
 1
 1
 1
(4 rows)
 */

public class RegressionTest extends BasePGTest {

    public RegressionTest() {
        super(LegacyMode.MODERN);
    }

    @Test
    public void testX() throws Exception {
        while (true) {
            try (Connection connection = getConnection(Mode.EXTENDED, 8812, true, -1);
                 PreparedStatement st = connection.prepareStatement("SELECT 1 FROM bugtest")
            ) {
                try (ResultSet rs = st.executeQuery()) {
                    int rowCount = 0;
                    while (rs.next()) {
                        rowCount++;
                        assertEquals(1, rs.getInt(1));
                    }
                    assertEquals(4, rowCount);
                }
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
            LockSupport.parkNanos(1_000_000);
        }
    }
}
