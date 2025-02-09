/**
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
package org.apache.hadoop.hbase.regionserver;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.regionserver.compactions.CompactionConfiguration;
import org.apache.hadoop.hbase.regionserver.compactions.DateTieredCompactionPolicy;
import org.apache.hadoop.hbase.regionserver.compactions.DateTieredCompactionRequest;
import org.apache.hadoop.hbase.testclassification.SmallTests;
import org.apache.hadoop.hbase.util.EnvironmentEdgeManager;
import org.apache.hadoop.hbase.util.ManualEnvironmentEdge;
import org.junit.Assert;
import org.junit.Test;
import org.junit.experimental.categories.Category;

@Category(SmallTests.class)
public class TestDateTieredCompactionPolicy extends TestCompactionPolicy {
  ArrayList<StoreFile> sfCreate(long[] minTimestamps, long[] maxTimestamps, long[] sizes)
      throws IOException {
    ManualEnvironmentEdge timeMachine = new ManualEnvironmentEdge();
    EnvironmentEdgeManager.injectEdge(timeMachine);
    // Has to be  > 0 and < now.
    timeMachine.setValue(1);
    ArrayList<Long> ageInDisk = new ArrayList<Long>();
    for (int i = 0; i < sizes.length; i++) {
      ageInDisk.add(0L);
    }

    ArrayList<StoreFile> ret = Lists.newArrayList();
    for (int i = 0; i < sizes.length; i++) {
      MockStoreFile msf =
          new MockStoreFile(TEST_UTIL, TEST_FILE, sizes[i], ageInDisk.get(i), false, i);
      msf.setTimeRangeTracker(new TimeRangeTracker(minTimestamps[i], maxTimestamps[i]));
      ret.add(msf);
    }
    return ret;
  }

  @Override
  protected void config() {
    super.config();

    // Set up policy
    conf.set(StoreEngine.STORE_ENGINE_CLASS_KEY,
      "org.apache.hadoop.hbase.regionserver.DateTieredStoreEngine");
    conf.setLong(CompactionConfiguration.MAX_AGE_MILLIS_KEY, 100);
    conf.setLong(CompactionConfiguration.INCOMING_WINDOW_MIN_KEY, 3);
    conf.setLong(CompactionConfiguration.BASE_WINDOW_MILLIS_KEY, 6);
    conf.setInt(CompactionConfiguration.WINDOWS_PER_TIER_KEY, 4);
    conf.setBoolean(CompactionConfiguration.SINGLE_OUTPUT_FOR_MINOR_COMPACTION_KEY, false);

    // Special settings for compaction policy per window
    this.conf.setInt(CompactionConfiguration.HBASE_HSTORE_COMPACTION_MIN_KEY, 2);
    this.conf.setInt(CompactionConfiguration.HBASE_HSTORE_COMPACTION_MAX_KEY, 12);
    this.conf.setFloat(CompactionConfiguration.HBASE_HSTORE_COMPACTION_RATIO_KEY, 1.2F);

    conf.setInt(HStore.BLOCKING_STOREFILES_KEY, 20);
    conf.setLong(HConstants.MAJOR_COMPACTION_PERIOD, 10);
  }

  void compactEquals(long now, ArrayList<StoreFile> candidates, long[] expectedFileSizes,
      long[] expectedBoundaries, boolean isMajor, boolean toCompact) throws IOException {
    ManualEnvironmentEdge timeMachine = new ManualEnvironmentEdge();
    EnvironmentEdgeManager.injectEdge(timeMachine);
    timeMachine.setValue(now);
    DateTieredCompactionRequest request;
    if (isMajor) {
      for (StoreFile file : candidates) {
        ((MockStoreFile)file).setIsMajor(true);
      }
      Assert.assertEquals(toCompact, ((DateTieredCompactionPolicy) store.storeEngine.getCompactionPolicy())
        .shouldPerformMajorCompaction(candidates));
      request = (DateTieredCompactionRequest) ((DateTieredCompactionPolicy) store.storeEngine
          .getCompactionPolicy()).selectMajorCompaction(candidates);
    } else {
      Assert.assertEquals(toCompact, ((DateTieredCompactionPolicy) store.storeEngine.getCompactionPolicy())
          .needsCompaction(candidates, ImmutableList.<StoreFile> of()));
      request = (DateTieredCompactionRequest) ((DateTieredCompactionPolicy) store.storeEngine
          .getCompactionPolicy()).selectMinorCompaction(candidates, false, false);
    }
    List<StoreFile> actual = Lists.newArrayList(request.getFiles());
    Assert.assertEquals(Arrays.toString(expectedFileSizes), Arrays.toString(getSizes(actual)));
    Assert.assertEquals(Arrays.toString(expectedBoundaries),
    Arrays.toString(request.getBoundaries().toArray()));
  }

  /**
   * Test for incoming window
   * @throws IOException with error
   */
  @Test
  public void incomingWindow() throws IOException {
    long[] minTimestamps = new long[] { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0 };
    long[] maxTimestamps = new long[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15 };
    long[] sizes = new long[] { 30, 31, 32, 33, 34, 20, 21, 22, 23, 24, 25, 10, 11, 12, 13 };

    compactEquals(16, sfCreate(minTimestamps, maxTimestamps, sizes), new long[] { 10, 11, 12, 13 },
      new long[] { Long.MIN_VALUE, 12 }, false, true);
  }

  /**
   * Not enough files in incoming window
   * @throws IOException with error
   */
  @Test
  public void NotIncomingWindow() throws IOException {
    long[] minTimestamps = new long[] { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0 };
    long[] maxTimestamps = new long[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13 };
    long[] sizes = new long[] { 30, 31, 32, 33, 34, 20, 21, 22, 23, 24, 25, 10, 11 };

    compactEquals(16, sfCreate(minTimestamps, maxTimestamps, sizes), new long[] { 20, 21, 22, 23,
        24, 25 }, new long[] { Long.MIN_VALUE, 6}, false, true);
  }

  /**
   * Test for file on the upper bound of incoming window
   * @throws IOException with error
   */
  @Test
  public void OnUpperBoundOfIncomingWindow() throws IOException {
    long[] minTimestamps = new long[] { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0 };
    long[] maxTimestamps = new long[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 18 };
    long[] sizes = new long[] { 30, 31, 32, 33, 34, 20, 21, 22, 23, 24, 25, 10, 11, 12, 13 };

    compactEquals(16, sfCreate(minTimestamps, maxTimestamps, sizes), new long[] { 10, 11, 12, 13 },
      new long[] { Long.MIN_VALUE, 12 }, false, true);
  }

  /**
   * Test for file newer than incoming window
   * @throws IOException with error
   */
  @Test
  public void NewerThanIncomingWindow() throws IOException {
    long[] minTimestamps = new long[] { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0 };
    long[] maxTimestamps = new long[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 19 };
    long[] sizes = new long[] { 30, 31, 32, 33, 34, 20, 21, 22, 23, 24, 25, 10, 11, 12, 13 };

    compactEquals(16, sfCreate(minTimestamps, maxTimestamps, sizes), new long[] { 10, 11, 12, 13 },
      new long[] { Long.MIN_VALUE, 12}, false, true);
  }

  /**
   * If there is no T1 window, we don't build T2
   * @throws IOException with error
   */
  @Test
  public void NoT2() throws IOException {
    long[] minTimestamps = new long[] { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0 };
    long[] maxTimestamps = new long[] { 44, 60, 61, 97, 100, 193 };
    long[] sizes = new long[] { 0, 20, 21, 22, 23, 1 };

    compactEquals(194, sfCreate(minTimestamps, maxTimestamps, sizes), new long[] { 22, 23 },
      new long[] { Long.MIN_VALUE, 96}, false, true);
  }

  @Test
  public void T1() throws IOException {
    long[] minTimestamps = new long[] { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0 };
    long[] maxTimestamps = new long[] { 44, 60, 61, 96, 100, 104, 120, 124, 143, 145, 157 };
    long[] sizes = new long[] { 0, 50, 51, 40, 41, 42, 30, 31, 32, 2, 1 };

    compactEquals(161, sfCreate(minTimestamps, maxTimestamps, sizes), new long[] { 30, 31, 32 },
      new long[] { Long.MIN_VALUE, 120 }, false, true);
  }

  /**
   * Apply exploring logic on non-incoming window
   * @throws IOException with error
   */
  @Test
  public void RatioT0() throws IOException {
    long[] minTimestamps = new long[] { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0 };
    long[] maxTimestamps = new long[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12 };
    long[] sizes = new long[] { 30, 31, 32, 33, 34, 20, 21, 22, 280, 23, 24, 1 };

    compactEquals(16, sfCreate(minTimestamps, maxTimestamps, sizes), new long[] { 20, 21, 22 },
      new long[] { Long.MIN_VALUE }, false, true);
  }

  /**
   * Also apply ratio-based logic on t2 window
   * @throws IOException with error
   */
  @Test
  public void RatioT2() throws IOException {
    long[] minTimestamps = new long[] { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0 };
    long[] maxTimestamps = new long[] { 44, 60, 61, 96, 100, 104, 120, 124, 143, 145, 157 };
    long[] sizes = new long[] { 0, 50, 51, 40, 41, 42, 350, 30, 31, 2, 1 };

    compactEquals(161, sfCreate(minTimestamps, maxTimestamps, sizes), new long[] { 30, 31 },
      new long[] { Long.MIN_VALUE }, false, true);
  }

  /**
   * The next compaction call after testTieredCompactionRatioT0 is compacted
   * @throws IOException with error
   */
  @Test
  public void RatioT0Next() throws IOException {
    long[] minTimestamps = new long[] { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0 };
    long[] maxTimestamps = new long[] { 1, 2, 3, 4, 5, 8, 9, 10, 11, 12 };
    long[] sizes = new long[] { 30, 31, 32, 33, 34, 22, 280, 23, 24, 1 };

    compactEquals(16, sfCreate(minTimestamps, maxTimestamps, sizes), new long[] { 23, 24 },
      new long[] { Long.MIN_VALUE }, false, true);
  }

  /**
   * Older than now(161) - maxAge(100)
   * @throws IOException with error
   */
  @Test
  public void olderThanMaxAge() throws IOException {
    long[] minTimestamps = new long[] { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0 };
    long[] maxTimestamps = new long[] { 44, 60, 61, 96, 100, 104, 105, 106, 113, 145, 157 };
    long[] sizes = new long[] { 0, 50, 51, 40, 41, 42, 33, 30, 31, 2, 1 };

    compactEquals(161, sfCreate(minTimestamps, maxTimestamps, sizes), new long[] { 40, 41, 42, 33,
        30, 31 }, new long[] { Long.MIN_VALUE, 96 }, false, true);
  }

  /**
   * Out-of-order data
   * @throws IOException with error
   */
  @Test
  public void outOfOrder() throws IOException {
    long[] minTimestamps = new long[] { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0 };
    long[] maxTimestamps = new long[] { 0, 13, 3, 10, 11, 1, 2, 12, 14, 15 };
    long[] sizes = new long[] { 30, 31, 32, 33, 34, 22, 28, 23, 24, 1 };

    compactEquals(16, sfCreate(minTimestamps, maxTimestamps, sizes), new long[] { 31, 32, 33, 34,
        22, 28, 23, 24, 1 }, new long[] { Long.MIN_VALUE, 12 }, false, true);
  }

  /**
   * Negative epoch time
   * @throws IOException with error
   */
  @Test
  public void negativeEpochtime() throws IOException {
    long[] minTimestamps =
        new long[] { -1000, -1000, -1000, -1000, -1000, -1000, -1000, -1000, -1000, -1000 };
    long[] maxTimestamps = new long[] { -28, -11, -10, -9, -8, -7, -6, -5, -4, -3 };
    long[] sizes = new long[] { 30, 31, 32, 33, 34, 22, 25, 23, 24, 1 };

    compactEquals(1, sfCreate(minTimestamps, maxTimestamps, sizes),
      new long[] { 31, 32, 33, 34, 22, 25, 23, 24, 1 },
      new long[] { Long.MIN_VALUE, -24 }, false, true);
  }

  /**
   * Major compaction
   * @throws IOException with error
   */
  @Test
  public void majorCompation() throws IOException {
    long[] minTimestamps = new long[] { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0 };
    long[] maxTimestamps = new long[] { 44, 60, 61, 96, 100, 104, 105, 106, 113, 145, 157 };
    long[] sizes = new long[] { 0, 50, 51, 40, 41, 42, 33, 30, 31, 2, 1 };

    compactEquals(161, sfCreate(minTimestamps, maxTimestamps, sizes), new long[] { 0, 50, 51, 40,41, 42,
      33, 30, 31, 2, 1 }, new long[] { Long.MIN_VALUE, 24, 48, 72, 96, 120, 144, 150, 156 }, true, true);
  }

  /**
   * Major compaction with negative numbers
   * @throws IOException with error
   */
  @Test
  public void negativeForMajor() throws IOException {
    long[] minTimestamps =
        new long[] { -155, -100, -100, -100, -100, -100, -100, -100, -100, -100, -100 };
    long[] maxTimestamps = new long[] { -8, -7, -6, -5, -4, -3, -2, -1, 0, 6, 13 };
    long[] sizes = new long[] { 0, 50, 51, 40, 41, 42, 33, 30, 31, 2, 1 };

    compactEquals(16, sfCreate(minTimestamps, maxTimestamps, sizes), new long[] { 0, 50, 51, 40,
        41, 42, 33, 30, 31, 2, 1 },
      new long[] { Long.MIN_VALUE, -144, -120, -96, -72, -48, -24, 0, 6, 12 }, true, true);
  }

  /**
   * Major compaction with maximum values
   * @throws IOException with error
   */
  @Test
  public void maxValuesForMajor() throws IOException {
    conf.setLong(CompactionConfiguration.BASE_WINDOW_MILLIS_KEY, Long.MAX_VALUE / 2);
    conf.setInt(CompactionConfiguration.WINDOWS_PER_TIER_KEY, 2);
    store.storeEngine.getCompactionPolicy().setConf(conf);
    long[] minTimestamps =
        new long[] { Long.MIN_VALUE, -100 };
    long[] maxTimestamps = new long[] { -8, Long.MAX_VALUE };
    long[] sizes = new long[] { 0, 1 };

    compactEquals(Long.MAX_VALUE, sfCreate(minTimestamps, maxTimestamps, sizes),
      new long[] { 0, 1 },
      new long[] { Long.MIN_VALUE, -4611686018427387903L, 0, 4611686018427387903L,
      9223372036854775806L }, true, true);
  }
}
