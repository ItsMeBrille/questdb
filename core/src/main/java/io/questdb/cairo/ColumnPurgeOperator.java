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

package io.questdb.cairo;

import io.questdb.griffin.PurgingOperator;
import io.questdb.log.Log;
import io.questdb.log.LogFactory;
import io.questdb.std.FilesFacade;
import io.questdb.std.LongList;
import io.questdb.std.MemoryTag;
import io.questdb.std.Misc;
import io.questdb.std.Rows;
import io.questdb.std.Unsafe;
import io.questdb.std.datetime.microtime.MicrosecondClock;
import io.questdb.std.str.LPSZ;
import io.questdb.std.str.Path;
import io.questdb.tasks.ColumnPurgeTask;

import java.io.Closeable;

import static io.questdb.cairo.TableUtils.TXN_FILE_NAME;

public class ColumnPurgeOperator implements Closeable {
    private static final Log LOG = LogFactory.getLog(ColumnPurgeOperator.class);
    private final LongList completedRowIds = new LongList();
    private final CairoEngine engine;
    private final FilesFacade ff;
    private final MicrosecondClock microClock;
    private final Path path;
    private final int pathRootLen;
    private final TableWriter purgeLogWriter;
    private final String updateCompleteColumnName;
    private final int updateCompleteColumnWriterIndex;
    private long longBytes;
    private int pathTableLen;
    private long purgeLogPartitionFd = -1;
    private long purgeLogPartitionTimestamp = Long.MAX_VALUE;
    private TxReader txReader;
    private TxnScoreboard txnScoreboard;

    public ColumnPurgeOperator(CairoEngine engine, TableWriter purgeLogWriter, String updateCompleteColumnName) {
        try {
            this.engine = engine;
            final CairoConfiguration configuration = engine.getConfiguration();
            this.ff = configuration.getFilesFacade();
            this.path = new Path(255, MemoryTag.NATIVE_SQL_COMPILER);
            path.of(configuration.getDbRoot());
            pathRootLen = path.size();
            this.purgeLogWriter = purgeLogWriter;
            this.updateCompleteColumnName = updateCompleteColumnName;
            this.updateCompleteColumnWriterIndex = purgeLogWriter.getMetadata().getColumnIndex(updateCompleteColumnName);
            txReader = new TxReader(ff);
            microClock = configuration.getMicrosecondClock();
            longBytes = Unsafe.malloc(Long.BYTES, MemoryTag.NATIVE_SQL_COMPILER);
        } catch (Throwable th) {
            close();
            throw th;
        }
    }

    public ColumnPurgeOperator(CairoEngine engine) {
        try {
            this.engine = engine;
            final CairoConfiguration configuration = engine.getConfiguration();
            this.ff = configuration.getFilesFacade();
            this.path = new Path(255, MemoryTag.NATIVE_SQL_COMPILER);
            path.of(configuration.getDbRoot());
            pathRootLen = path.size();
            this.purgeLogWriter = null;
            this.updateCompleteColumnName = null;
            this.updateCompleteColumnWriterIndex = -1;
            txnScoreboard = null;
            txReader = null;
            microClock = configuration.getMicrosecondClock();
            longBytes = 0;
        } catch (Throwable th) {
            close();
            throw th;
        }
    }

    @Override
    public void close() {
        if (longBytes != 0L) {
            Unsafe.free(longBytes, Long.BYTES, MemoryTag.NATIVE_SQL_COMPILER);
            longBytes = 0;
        }
        closePurgeLogCompleteFile();
        Misc.free(path);
        txnScoreboard = Misc.free(txnScoreboard);
    }

    public boolean purge(ColumnPurgeTask task) {
        assert task.getTableName() != null;
        try {
            boolean done = purge0(task, ScoreboardUseMode.INTERNAL);
            setCompletionTimestamp(completedRowIds, microClock.getTicks());
            return done;
        } catch (Throwable ex) {
            // Can be some IO exception
            LOG.error().$("could not purge").$(ex).$();
            return false;
        }
    }

    public boolean purge(ColumnPurgeTask task, TableReader tableReader) {
        assert task.getTableName() != null;
        try {
            txReader = tableReader.getTxFile();
            txnScoreboard = tableReader.getTxnScoreboard();
            return purge0(task, ScoreboardUseMode.EXTERNAL);
        } catch (Throwable ex) {
            // Can be some IO exception
            LOG.error().$("could not purge").$(ex).$();
            return false;
        }
    }

    public void purgeExclusive(ColumnPurgeTask task) {
        assert task.getTableName() != null;
        try {
            purge0(task, ScoreboardUseMode.EXCLUSIVE);
        } catch (Throwable ex) {
            // Can be some IO exception
            LOG.error().$("could not purge").$(ex).$();
        }
    }

    private static boolean couldNotRemove(FilesFacade ff, LPSZ path) {
        if (ff.removeQuiet(path)) {
            return false;
        }

        final int errno = ff.errno();
        if (ff.exists(path)) {
            LOG.info().$("cannot delete file, will retry [path=").$(path).$(", errno=").$(errno).I$();
            return true;
        }

        // the file did not exist, we don't care of the error
        return false;
    }

    private boolean checkScoreboardHasReadersBeforeUpdate(long columnVersion, ColumnPurgeTask task) {
        long updateTxn = task.getUpdateTxn();
        try {
            return !txnScoreboard.isRangeAvailable(columnVersion + 1, updateTxn);
        } catch (CairoException ex) {
            // Scoreboard can be over allocated, don't stall purge because of that, re-schedule another run instead
            LOG.error().$("cannot lock last txn in scoreboard, column purge will re-run [table=")
                    .utf8(task.getTableName().getTableName())
                    .$(", txn=").$(updateTxn)
                    .$(", msg=").$(ex.getFlyweightMessage())
                    .$(", errno=").$(ex.getErrno())
                    .I$();
            return true;
        }
    }

    private void closePurgeLogCompleteFile() {
        if (ff.close(purgeLogPartitionFd)) {
            LOG.info().$("closed purge log complete file [fd=").$(purgeLogPartitionFd).I$();
            purgeLogPartitionFd = -1;
        }
    }

    private boolean openScoreboardAndTxn(ColumnPurgeTask task, ScoreboardUseMode scoreboardUseMode) {
        if (scoreboardUseMode == ScoreboardUseMode.INTERNAL) {
            Misc.free(txnScoreboard);
            txnScoreboard = engine.getTxnScoreboard(task.getTableName());
        }

        // In exclusive mode, we still need to check that purge will delete column in the correct table,
        // e.g., table is not truncated after the update happened
        if (scoreboardUseMode == ScoreboardUseMode.INTERNAL || scoreboardUseMode == ScoreboardUseMode.EXCLUSIVE) {
            int tableId = readTableId(path);
            if (tableId != task.getTableId()) {
                LOG.info().$("cannot purge orphan table [path=").$(path.trimTo(pathTableLen)).I$();
                return false;
            }

            path.trimTo(pathTableLen).concat(TXN_FILE_NAME);
            txReader.ofRO(path.$(), task.getPartitionBy());
            txReader.unsafeLoadAll();
            if (txReader.getTruncateVersion() != task.getTruncateVersion()) {
                LOG.info().$("cannot purge, purge request overlaps with truncate [path=").$(path.trimTo(pathTableLen)).I$();
                return false;
            }
        }

        return true;
    }

    private boolean purge0(ColumnPurgeTask task, final ScoreboardUseMode scoreboardMode) {
        setTablePath(task.getTableName());

        final LongList updatedColumnInfo = task.getUpdatedColumnInfo();
        long minUnlockedTxnRangeStarts = Long.MAX_VALUE;
        boolean allDone = true;
        boolean setupScoreboard = scoreboardMode != ScoreboardUseMode.EXTERNAL;

        try {
            completedRowIds.clear();
            for (int i = 0, n = updatedColumnInfo.size(); i < n; i += ColumnPurgeTask.BLOCK_SIZE) {
                final long columnVersion = updatedColumnInfo.getQuick(i + ColumnPurgeTask.OFFSET_COLUMN_VERSION);
                final long partitionTimestamp = updatedColumnInfo.getQuick(i + ColumnPurgeTask.OFFSET_PARTITION_TIMESTAMP);
                final long partitionTxnName = updatedColumnInfo.getQuick(i + ColumnPurgeTask.OFFSET_PARTITION_NAME_TXN);
                final long updateRowId = updatedColumnInfo.getQuick(i + ColumnPurgeTask.OFFSET_UPDATE_ROW_ID);
                int columnTypeRaw = task.getColumnType();
                int columnType = Math.abs(columnTypeRaw);
                // We don't know the type of the column, the files are found on the disk, but column
                // does not exist in the table metadata (e.g., column was dropped)
                boolean columnTypeRogue = columnTypeRaw == ColumnType.UNDEFINED;
                boolean isSymbolRootFiles = (ColumnType.isSymbol(columnType) || columnTypeRogue)
                        && partitionTimestamp == PurgingOperator.TABLE_ROOT_PARTITION;

                int pathTrimToPartition;
                CharSequence columnName = task.getColumnName();
                if (!isSymbolRootFiles) {
                    setUpPartitionPath(task.getPartitionBy(), partitionTimestamp, partitionTxnName);
                    pathTrimToPartition = path.size();
                    TableUtils.dFile(path, columnName, columnVersion);
                } else {
                    path.trimTo(pathTableLen);
                    pathTrimToPartition = path.size();
                    TableUtils.charFileName(path, columnName, columnVersion);
                }

                // perform existence check ahead of trying to remove files
                if (!ff.exists(path.$()) && !columnTypeRogue) {
                    if (ColumnType.isVarSize(columnType)) {
                        path.trimTo(pathTrimToPartition);
                        if (!ff.exists(TableUtils.iFile(path, columnName, columnVersion))) {
                            completedRowIds.add(updateRowId);
                            continue;
                        }
                    } else if (ColumnType.isSymbol(columnType)) {
                        // In the case of symbol root files, we need to check if .k and .v files exist in table root.
                        // In the case of symbol files in partition, we need to check if .k and .v files exist in partition
                        // that can be index files after index drop SQL.
                        if (!ff.exists(TableUtils.offsetFileName(path.trimTo(pathTrimToPartition), columnName, columnVersion))) {
                            if (!ff.exists(BitmapIndexUtils.keyFileName(path.trimTo(pathTrimToPartition), columnName, columnVersion))) {
                                if (!ff.exists(BitmapIndexUtils.valueFileName(path.trimTo(pathTrimToPartition), columnName, columnVersion))) {
                                    completedRowIds.add(updateRowId);
                                    continue;
                                }
                            }
                        }
                    } else {
                        // Files already deleted, move to the next partition
                        completedRowIds.add(updateRowId);
                        continue;
                    }
                }

                if (setupScoreboard) {
                    // Setup scoreboard lazily because columns we're purging
                    // may not exist, including the entire table. Setting up
                    // scoreboard ahead of checking file existence would fail in those
                    // cases.
                    if (!openScoreboardAndTxn(task, scoreboardMode)) {
                        // the current table state precludes us from purging its columns
                        // nothing to do here
                        completedRowIds.add(updateRowId);
                        continue;
                    }
                    // we would have mutated the path by checking the state of the table
                    // we will have to re-set up that
                    if (!isSymbolRootFiles) {
                        setUpPartitionPath(task.getPartitionBy(), partitionTimestamp, partitionTxnName);
                    } else {
                        path.trimTo(pathTableLen);
                    }
                    pathTrimToPartition = path.size();
                    TableUtils.dFile(path, columnName, columnVersion);
                    setupScoreboard = false;
                }

                if (txReader.isPartitionReadOnlyByPartitionTimestamp(partitionTimestamp)) {
                    // txReader is either open because scoreboardMode == ScoreboardUseMode.EXTERNAL,
                    // or it was open by openScoreboardAndTxn
                    LOG.info().$("skipping purge of read-only partition [path=").$(path.$())
                            .$(", column=").utf8(columnName)
                            .I$();
                    completedRowIds.add(updateRowId);
                    continue;
                }

                if (columnVersion < minUnlockedTxnRangeStarts) {
                    if (scoreboardMode != ScoreboardUseMode.EXCLUSIVE && checkScoreboardHasReadersBeforeUpdate(columnVersion, task)) {
                        // Reader lock still exists
                        allDone = false;
                        LOG.debug().$("cannot purge, version is in use [path=").$(path).I$();
                        continue;
                    } else {
                        minUnlockedTxnRangeStarts = columnVersion;
                    }
                }

                LOG.info().$("purging [path=").$(path).I$();

                // No readers looking at the column version, files can be deleted
                if (couldNotRemove(ff, path.$())) {
                    allDone = false;
                    continue;
                }

                if (ColumnType.isVarSize(columnType) || columnTypeRogue) {
                    path.trimTo(pathTrimToPartition);
                    TableUtils.iFile(path, columnName, columnVersion);

                    if (couldNotRemove(ff, path.$())) {
                        allDone = false;
                        continue;
                    }
                }

                // Check if it's a symbol, try to remove .k and .v files in the partition
                if (ColumnType.isSymbol(columnType) || columnTypeRogue) {
                    if (isSymbolRootFiles) {
                        path.trimTo(pathTrimToPartition);
                        if (couldNotRemove(ff, TableUtils.charFileName(path, columnName, columnVersion))) {
                            allDone = false;
                            continue;
                        }

                        path.trimTo(pathTrimToPartition);
                        if (couldNotRemove(ff, TableUtils.offsetFileName(path, columnName, columnVersion))) {
                            allDone = false;
                            continue;
                        }
                    }

                    path.trimTo(pathTrimToPartition);
                    if (couldNotRemove(ff, BitmapIndexUtils.keyFileName(path, columnName, columnVersion))) {
                        allDone = false;
                        continue;
                    }

                    path.trimTo(pathTrimToPartition);
                    if (couldNotRemove(ff, BitmapIndexUtils.valueFileName(path, columnName, columnVersion))) {
                        allDone = false;
                        continue;
                    }
                }
                completedRowIds.add(updateRowId);
            }
        } finally {
            if (scoreboardMode != ScoreboardUseMode.EXTERNAL) {
                txnScoreboard = Misc.free(txnScoreboard);
                Misc.free(txReader);
            }
        }

        return allDone;
    }

    private int readTableId(Path path) {
        final int INVALID_TABLE_ID = Integer.MIN_VALUE;
        long fd = ff.openRO(path.trimTo(pathTableLen).concat(TableUtils.META_FILE_NAME).$());
        if (fd < 0) {
            return INVALID_TABLE_ID;
        }
        try {
            if (ff.read(fd, longBytes, Integer.BYTES, TableUtils.META_OFFSET_TABLE_ID) != Integer.BYTES) {
                return INVALID_TABLE_ID;
            }
            return Unsafe.getUnsafe().getInt(longBytes);
        } finally {
            ff.close(fd);
        }
    }

    private void reopenPurgeLogPartition(int partitionIndex, long partitionTimestamp) {
        path.trimTo(pathRootLen);
        path.concat(purgeLogWriter.getTableToken());
        long partitionNameTxn = purgeLogWriter.getPartitionNameTxn(partitionIndex);
        TableUtils.setPathForNativePartition(
                path,
                purgeLogWriter.getPartitionBy(),
                partitionTimestamp,
                partitionNameTxn
        );
        TableUtils.dFile(
                path,
                updateCompleteColumnName,
                purgeLogWriter.getColumnNameTxn(partitionTimestamp, updateCompleteColumnWriterIndex)
        );
        closePurgeLogCompleteFile();
        purgeLogPartitionFd = TableUtils.openRW(ff, path.$(), LOG, purgeLogWriter.getConfiguration().getWriterFileOpenOpts());
        purgeLogPartitionTimestamp = partitionTimestamp;
        LOG.info().$("reopened purge log complete file [path=").$(path).$(", fd=").$(purgeLogPartitionFd).I$();
    }

    private void setCompletionTimestamp(LongList completedRecordIds, long timeMicro) {
        // This is an in-place update for known record ids of completed column in column version cleanup log table
        try {
            Unsafe.getUnsafe().putLong(longBytes, timeMicro);
            for (int rec = 0, n = completedRecordIds.size(); rec < n; rec++) {
                long recordId = completedRecordIds.getQuick(rec);
                int partitionIndex = Rows.toPartitionIndex(recordId);
                if (rec == 0) {
                    // The assumption is that all records belong to the same partition
                    // this is how the records are added to the table in ColumnPurgeJob,
                    // e.g., all records about the same column updated have identical timestamp
                    final long partitionTimestamp = purgeLogWriter.getPartitionTimestamp(partitionIndex);
                    if (purgeLogPartitionTimestamp != partitionTimestamp) {
                        reopenPurgeLogPartition(partitionIndex, partitionTimestamp);
                    }
                }
                long rowId = Rows.toLocalRowID(recordId);
                long offset = rowId * Long.BYTES;

                if (ff.write(purgeLogPartitionFd, longBytes, Long.BYTES, rowId * Long.BYTES) != Long.BYTES) {
                    int errno = ff.errno();
                    long length = ff.length(purgeLogPartitionFd);
                    LOG.error().$("could not mark record as purged [errno=").$(errno)
                            .$(", writeOffset=").$(offset)
                            .$(", fd=").$(purgeLogPartitionFd)
                            .$(", fileSize=").$(length)
                            .I$();
                    // Re-open of the file next run in case something went wrong.
                    purgeLogPartitionTimestamp = -1;
                }
            }
        } catch (CairoException ex) {
            LOG.error().$("could not update completion timestamp").$((Throwable) ex).$();
        }
    }

    private void setTablePath(TableToken tableName) {
        path.trimTo(pathRootLen).concat(tableName);
        pathTableLen = path.size();
    }

    private void setUpPartitionPath(int partitionBy, long partitionTimestamp, long partitionTxnName) {
        path.trimTo(pathTableLen);
        TableUtils.setPathForNativePartition(path, partitionBy, partitionTimestamp, partitionTxnName);
    }

    private enum ScoreboardUseMode {
        INTERNAL,
        EXTERNAL,
        EXCLUSIVE
    }
}
