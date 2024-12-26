/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hbase.regionserver.querymatcher;

import java.io.IOException;
import org.apache.hadoop.hbase.CellUtil;
import org.apache.hadoop.hbase.ExtendedCell;
import org.apache.hadoop.hbase.KeepDeletedCells;
import org.apache.hadoop.hbase.PrivateCellUtil;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.regionserver.ScanInfo;
import org.apache.yetus.audience.InterfaceAudience;

/**
 * Query matcher for normal user scan.
 */
@InterfaceAudience.Private
public abstract class NormalUserScanQueryMatcher extends UserScanQueryMatcher {

  /** Keeps track of deletes */
  private final DeleteTracker deletes;

  /** True if we are doing a 'Get' Scan. Every Get is actually a one-row Scan. */
  private final boolean get;

  /** whether time range queries can see rows "behind" a delete */
  protected final boolean seePastDeleteMarkers;

  private final int scanMaxVersions;

  protected NormalUserScanQueryMatcher(Scan scan, ScanInfo scanInfo, ColumnTracker columns,
    boolean hasNullColumn, DeleteTracker deletes, long oldestUnexpiredTS, long now) {
    super(scan, scanInfo, columns, hasNullColumn, oldestUnexpiredTS, now);
    this.deletes = deletes;
    this.get = scan.isGetScan();
    this.seePastDeleteMarkers = scanInfo.getKeepDeletedCells() != KeepDeletedCells.FALSE;
    this.scanMaxVersions = Math.max(scan.getMaxVersions(), scanInfo.getMaxVersions());
  }

  @Override
  public void beforeShipped() throws IOException {
    super.beforeShipped();
    deletes.beforeShipped();
  }

  @Override
  public MatchCode match(ExtendedCell cell) throws IOException {
    // set visibilityLabelEnabled to true pessimistically if it cannot be determined
    return match(cell, null, true);
  }

  @Override
  public MatchCode match(ExtendedCell cell, ExtendedCell prevCell, boolean visibilityLabelEnabled)
    throws IOException {
    if (filter != null && filter.filterAllRemaining()) {
      return MatchCode.DONE_SCAN;
    }
    MatchCode returnCode;
    if ((returnCode = preCheck(cell)) != null) {
      return returnCode;
    }
    long timestamp = cell.getTimestamp();
    byte typeByte = cell.getTypeByte();
    if (PrivateCellUtil.isDelete(typeByte)) {
      boolean includeDeleteMarker =
        seePastDeleteMarkers ? tr.withinTimeRange(timestamp) : tr.withinOrAfterTimeRange(timestamp);
      if (includeDeleteMarker) {
        this.deletes.add(cell);
      }
      // optimization for delete markers
      if ((returnCode = checkCanSeekNextCol(cell, prevCell, visibilityLabelEnabled)) != null) {
        return returnCode;
      }
      return MatchCode.SKIP;
    }
    // optimization when prevCell is Delete or DeleteFamilyVersion
    if ((returnCode = checkDeletedEffectively(cell, prevCell, visibilityLabelEnabled)) != null) {
      return returnCode;
    }
    if ((returnCode = checkDeleted(deletes, cell)) != null) {
      return returnCode;
    }
    return matchColumn(cell, timestamp, typeByte);
  }

  private MatchCode checkCanSeekNextCol(ExtendedCell cell, ExtendedCell prevCell,
    boolean visibilityLabelEnabled) {
    // optimization for DeleteFamily and DeleteColumn(only for empty qualifier)
    if (
      canOptimizeReadDeleteMarkers(visibilityLabelEnabled) && (PrivateCellUtil.isDeleteFamily(cell)
        || PrivateCellUtil.isDeleteColumns(cell) && cell.getQualifierLength() > 0)
    ) {
      return MatchCode.SEEK_NEXT_COL;
    }
    // optimization for duplicate Delete and DeleteFamilyVersion
    return checkDeletedEffectively(cell, prevCell, visibilityLabelEnabled);
  }

  // If prevCell is a delete marker and cell is a Put or delete marker,
  // it means the cell is deleted effectively.
  // And we can do SEEK_NEXT_COL.
  private MatchCode checkDeletedEffectively(ExtendedCell cell, ExtendedCell prevCell,
    boolean visibilityLabelEnabled) {
    if (
      prevCell != null && canOptimizeReadDeleteMarkers(visibilityLabelEnabled)
        && CellUtil.matchingRowColumn(prevCell, cell) && CellUtil.matchingTimestamp(prevCell, cell)
        && (PrivateCellUtil.isDeleteType(prevCell) && cell.getQualifierLength() > 0
          || PrivateCellUtil.isDeleteFamilyVersion(prevCell))
    ) {
      return MatchCode.SEEK_NEXT_COL;
    }
    return null;
  }

  private boolean canOptimizeReadDeleteMarkers(boolean visibilityLabelEnabled) {
    // for simplicity, optimization works only for these cases
    return !seePastDeleteMarkers && scanMaxVersions == 1 && !visibilityLabelEnabled;
  }

  @Override
  protected void reset() {
    deletes.reset();
  }

  @Override
  protected boolean isGet() {
    return get;
  }

  public static NormalUserScanQueryMatcher create(Scan scan, ScanInfo scanInfo,
    ColumnTracker columns, DeleteTracker deletes, boolean hasNullColumn, long oldestUnexpiredTS,
    long now) throws IOException {
    if (scan.isReversed()) {
      if (scan.includeStopRow()) {
        return new NormalUserScanQueryMatcher(scan, scanInfo, columns, hasNullColumn, deletes,
          oldestUnexpiredTS, now) {

          @Override
          protected boolean moreRowsMayExistsAfter(int cmpToStopRow) {
            return cmpToStopRow >= 0;
          }
        };
      } else {
        return new NormalUserScanQueryMatcher(scan, scanInfo, columns, hasNullColumn, deletes,
          oldestUnexpiredTS, now) {

          @Override
          protected boolean moreRowsMayExistsAfter(int cmpToStopRow) {
            return cmpToStopRow > 0;
          }
        };
      }
    } else {
      if (scan.includeStopRow()) {
        return new NormalUserScanQueryMatcher(scan, scanInfo, columns, hasNullColumn, deletes,
          oldestUnexpiredTS, now) {

          @Override
          protected boolean moreRowsMayExistsAfter(int cmpToStopRow) {
            return cmpToStopRow <= 0;
          }
        };
      } else {
        return new NormalUserScanQueryMatcher(scan, scanInfo, columns, hasNullColumn, deletes,
          oldestUnexpiredTS, now) {

          @Override
          protected boolean moreRowsMayExistsAfter(int cmpToStopRow) {
            return cmpToStopRow < 0;
          }
        };
      }
    }
  }
}
