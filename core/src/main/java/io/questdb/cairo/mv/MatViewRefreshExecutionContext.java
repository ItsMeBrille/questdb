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

package io.questdb.cairo.mv;

import io.questdb.cairo.*;
import io.questdb.cairo.security.ReadOnlySecurityContext;
import io.questdb.griffin.SqlExecutionContextImpl;
import io.questdb.griffin.engine.functions.bind.BindVariableServiceImpl;

public class MatViewRefreshExecutionContext extends SqlExecutionContextImpl {
    private TableToken viewTableToken;
    private TableWriter viewTableWriter;

    public MatViewRefreshExecutionContext(CairoEngine engine) {
        super(engine, 1);
        with(new ReadOnlySecurityContext() {
                 @Override
                 public void authorizeInsert(TableToken tableToken) {
                     if (!tableToken.equals(viewTableToken)) {
                         throw CairoException.authorization().put("Write permission denied").setCacheable(true);
                     }
                 }
             }, new BindVariableServiceImpl(engine.getConfiguration())
        );
    }

    public TableWriterAPI getTableWriterAPI(TableToken tableToken, String reason) {
        if (tableToken.equals(viewTableToken)) {
            return viewTableWriter;
        }
        throw CairoException.nonCritical().put("unexpected write attempt in materialized view ").put(viewTableToken);
    }

    public void of(TableReader baseTableReader, TableWriter viewTableWriter) {
        this.viewTableToken = viewTableWriter.getTableToken();
        this.viewTableWriter = viewTableWriter;
    }
}
