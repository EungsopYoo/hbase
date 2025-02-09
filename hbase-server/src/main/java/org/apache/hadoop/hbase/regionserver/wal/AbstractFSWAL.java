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
package org.apache.hadoop.hbase.regionserver.wal;

import static org.apache.hadoop.hbase.wal.AbstractFSWALProvider.WAL_FILE_NAME_DELIMITER;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

import com.google.common.annotations.VisibleForTesting;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.PathFilter;
import org.apache.hadoop.hbase.Cell;
import org.apache.hadoop.hbase.CellUtil;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.HRegionInfo;
import org.apache.hadoop.hbase.classification.InterfaceAudience;
import org.apache.hadoop.hbase.io.util.HeapMemorySizeUtil;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.util.DrainBarrier;
import org.apache.hadoop.hbase.util.EnvironmentEdgeManager;
import org.apache.hadoop.hbase.util.FSUtils;
import org.apache.hadoop.hbase.wal.WAL;
import org.apache.hadoop.hbase.wal.WALFactory;
import org.apache.hadoop.hbase.wal.WALKey;
import org.apache.hadoop.hdfs.protocol.DatanodeInfo;
import org.apache.hadoop.util.StringUtils;
import org.apache.htrace.NullScope;
import org.apache.htrace.Span;
import org.apache.htrace.Trace;
import org.apache.htrace.TraceScope;

/**
 * Implementation of {@link WAL} to go against {@link FileSystem}; i.e. keep WALs in HDFS. Only one
 * WAL is ever being written at a time. When a WAL hits a configured maximum size, it is rolled.
 * This is done internal to the implementation.
 * <p>
 * As data is flushed from the MemStore to other on-disk structures (files sorted by key, hfiles), a
 * WAL becomes obsolete. We can let go of all the log edits/entries for a given HRegion-sequence id.
 * A bunch of work in the below is done keeping account of these region sequence ids -- what is
 * flushed out to hfiles, and what is yet in WAL and in memory only.
 * <p>
 * It is only practical to delete entire files. Thus, we delete an entire on-disk file
 * <code>F</code> when all of the edits in <code>F</code> have a log-sequence-id that's older
 * (smaller) than the most-recent flush.
 * <p>
 * To read an WAL, call
 * {@link WALFactory#createReader(org.apache.hadoop.fs.FileSystem, org.apache.hadoop.fs.Path)}. *
 * <h2>Failure Semantic</h2> If an exception on append or sync, roll the WAL because the current WAL
 * is now a lame duck; any more appends or syncs will fail also with the same original exception. If
 * we have made successful appends to the WAL and we then are unable to sync them, our current
 * semantic is to return error to the client that the appends failed but also to abort the current
 * context, usually the hosting server. We need to replay the WALs. <br>
 * TODO: Change this semantic. A roll of WAL may be sufficient as long as we have flagged client
 * that the append failed. <br>
 * TODO: replication may pick up these last edits though they have been marked as failed append
 * (Need to keep our own file lengths, not rely on HDFS).
 */
@InterfaceAudience.Private
public abstract class AbstractFSWAL<W> implements WAL {

  private static final Log LOG = LogFactory.getLog(AbstractFSWAL.class);

  protected static final int DEFAULT_SLOW_SYNC_TIME_MS = 100; // in ms

  /**
   * file system instance
   */
  protected final FileSystem fs;

  /**
   * WAL directory, where all WAL files would be placed.
   */
  protected final Path walDir;

  /**
   * dir path where old logs are kept.
   */
  protected final Path walArchiveDir;

  /**
   * Matches just those wal files that belong to this wal instance.
   */
  protected final PathFilter ourFiles;

  /**
   * Prefix of a WAL file, usually the region server name it is hosted on.
   */
  protected final String walFilePrefix;

  /**
   * Suffix included on generated wal file names
   */
  protected final String walFileSuffix;

  /**
   * Prefix used when checking for wal membership.
   */
  protected final String prefixPathStr;

  protected final WALCoprocessorHost coprocessorHost;

  /**
   * conf object
   */
  protected final Configuration conf;

  /** Listeners that are called on WAL events. */
  protected final List<WALActionsListener> listeners =
      new CopyOnWriteArrayList<WALActionsListener>();

  /**
   * Class that does accounting of sequenceids in WAL subsystem. Holds oldest outstanding sequence
   * id as yet not flushed as well as the most recent edit sequence id appended to the WAL. Has
   * facility for answering questions such as "Is it safe to GC a WAL?".
   */
  protected final SequenceIdAccounting sequenceIdAccounting = new SequenceIdAccounting();

  /** The barrier used to ensure that close() waits for all log rolls and flushes to finish. */
  protected final DrainBarrier closeBarrier = new DrainBarrier();

  protected final int slowSyncNs;

  // If > than this size, roll the log.
  protected final long logrollsize;

  /*
   * If more than this many logs, force flush of oldest region to oldest edit goes to disk. If too
   * many and we crash, then will take forever replaying. Keep the number of logs tidy.
   */
  protected final int maxLogs;

  /**
   * This lock makes sure only one log roll runs at a time. Should not be taken while any other lock
   * is held. We don't just use synchronized because that results in bogus and tedious findbugs
   * warning when it thinks synchronized controls writer thread safety. It is held when we are
   * actually rolling the log. It is checked when we are looking to see if we should roll the log or
   * not.
   */
  protected final ReentrantLock rollWriterLock = new ReentrantLock(true);

  // The timestamp (in ms) when the log file was created.
  protected final AtomicLong filenum = new AtomicLong(-1);

  // Number of transactions in the current Wal.
  protected final AtomicInteger numEntries = new AtomicInteger(0);

  /**
   * The highest known outstanding unsync'd WALEdit transaction id. Usually, we use a queue to pass
   * WALEdit to background consumer thread, and the transaction id is the sequence number of the
   * corresponding entry in queue.
   */
  protected volatile long highestUnsyncedTxid = -1;

  /**
   * Updated to the transaction id of the last successful sync call. This can be less than
   * {@link #highestUnsyncedTxid} for case where we have an append where a sync has not yet come in
   * for it.
   */
  protected final AtomicLong highestSyncedTxid = new AtomicLong(0);

  /**
   * The total size of wal
   */
  protected final AtomicLong totalLogSize = new AtomicLong(0);
  /**
   * Current log file.
   */
  volatile W writer;

  protected volatile boolean closed = false;

  protected final AtomicBoolean shutdown = new AtomicBoolean(false);
  /**
   * WAL Comparator; it compares the timestamp (log filenum), present in the log file name. Throws
   * an IllegalArgumentException if used to compare paths from different wals.
   */
  final Comparator<Path> LOG_NAME_COMPARATOR = new Comparator<Path>() {
    @Override
    public int compare(Path o1, Path o2) {
      return Long.compare(getFileNumFromFileName(o1), getFileNumFromFileName(o2));
    }
  };

  /**
   * Map of WAL log file to the latest sequence ids of all regions it has entries of. The map is
   * sorted by the log file creation timestamp (contained in the log file name).
   */
  protected ConcurrentNavigableMap<Path, Map<byte[], Long>> byWalRegionSequenceIds =
      new ConcurrentSkipListMap<Path, Map<byte[], Long>>(LOG_NAME_COMPARATOR);

  /**
   * Map of {@link SyncFuture}s keyed by Handler objects. Used so we reuse SyncFutures.
   * <p>
   * TODO: Reus FSWALEntry's rather than create them anew each time as we do SyncFutures here.
   * <p>
   * TODO: Add a FSWalEntry and SyncFuture as thread locals on handlers rather than have them get
   * them from this Map?
   */
  private final ConcurrentMap<Thread, SyncFuture> syncFuturesByHandler;

  /**
   * A log file has a creation timestamp (in ms) in its file name ({@link #filenum}. This helper
   * method returns the creation timestamp from a given log file. It extracts the timestamp assuming
   * the filename is created with the {@link #computeFilename(long filenum)} method.
   * @return timestamp, as in the log file name.
   */
  protected long getFileNumFromFileName(Path fileName) {
    if (fileName == null) {
      throw new IllegalArgumentException("file name can't be null");
    }
    if (!ourFiles.accept(fileName)) {
      throw new IllegalArgumentException("The log file " + fileName
          + " doesn't belong to this WAL. (" + toString() + ")");
    }
    final String fileNameString = fileName.toString();
    String chompedPath =
        fileNameString.substring(prefixPathStr.length(),
          (fileNameString.length() - walFileSuffix.length()));
    return Long.parseLong(chompedPath);
  }

  private int calculateMaxLogFiles(float memstoreSizeRatio, long logRollSize) {
    MemoryUsage mu = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
    return Math.round(mu.getMax() * memstoreSizeRatio * 2 / logRollSize);
  }

  protected AbstractFSWAL(final FileSystem fs, final Path rootDir, final String logDir,
      final String archiveDir, final Configuration conf, final List<WALActionsListener> listeners,
      final boolean failIfWALExists, final String prefix, final String suffix)
      throws FailedLogCloseException, IOException {
    this.fs = fs;
    this.walDir = new Path(rootDir, logDir);
    this.walArchiveDir = new Path(rootDir, archiveDir);
    this.conf = conf;

    if (!fs.exists(walDir) && !fs.mkdirs(walDir)) {
      throw new IOException("Unable to mkdir " + walDir);
    }

    if (!fs.exists(this.walArchiveDir)) {
      if (!fs.mkdirs(this.walArchiveDir)) {
        throw new IOException("Unable to mkdir " + this.walArchiveDir);
      }
    }

    // If prefix is null||empty then just name it wal
    this.walFilePrefix =
        prefix == null || prefix.isEmpty() ? "wal" : URLEncoder.encode(prefix, "UTF8");
    // we only correctly differentiate suffices when numeric ones start with '.'
    if (suffix != null && !(suffix.isEmpty()) && !(suffix.startsWith(WAL_FILE_NAME_DELIMITER))) {
      throw new IllegalArgumentException("WAL suffix must start with '" + WAL_FILE_NAME_DELIMITER
          + "' but instead was '" + suffix + "'");
    }
    // Now that it exists, set the storage policy for the entire directory of wal files related to
    // this FSHLog instance
    FSUtils.setStoragePolicy(fs, conf, this.walDir, HConstants.WAL_STORAGE_POLICY,
      HConstants.DEFAULT_WAL_STORAGE_POLICY);
    this.walFileSuffix = (suffix == null) ? "" : URLEncoder.encode(suffix, "UTF8");
    this.prefixPathStr = new Path(walDir, walFilePrefix + WAL_FILE_NAME_DELIMITER).toString();

    this.ourFiles = new PathFilter() {
      @Override
      public boolean accept(final Path fileName) {
        // The path should start with dir/<prefix> and end with our suffix
        final String fileNameString = fileName.toString();
        if (!fileNameString.startsWith(prefixPathStr)) {
          return false;
        }
        if (walFileSuffix.isEmpty()) {
          // in the case of the null suffix, we need to ensure the filename ends with a timestamp.
          return org.apache.commons.lang.StringUtils.isNumeric(fileNameString
              .substring(prefixPathStr.length()));
        } else if (!fileNameString.endsWith(walFileSuffix)) {
          return false;
        }
        return true;
      }
    };

    if (failIfWALExists) {
      final FileStatus[] walFiles = FSUtils.listStatus(fs, walDir, ourFiles);
      if (null != walFiles && 0 != walFiles.length) {
        throw new IOException("Target WAL already exists within directory " + walDir);
      }
    }

    // Register listeners. TODO: Should this exist anymore? We have CPs?
    if (listeners != null) {
      for (WALActionsListener i : listeners) {
        registerWALActionsListener(i);
      }
    }
    this.coprocessorHost = new WALCoprocessorHost(this, conf);

    // Get size to roll log at. Roll at 95% of HDFS block size so we avoid crossing HDFS blocks
    // (it costs a little x'ing bocks)
    final long blocksize =
        this.conf.getLong("hbase.regionserver.hlog.blocksize",
          FSUtils.getDefaultBlockSize(this.fs, this.walDir));
    this.logrollsize =
        (long) (blocksize * conf.getFloat("hbase.regionserver.logroll.multiplier", 0.95f));

    float memstoreRatio =
        conf.getFloat(HeapMemorySizeUtil.MEMSTORE_SIZE_KEY, conf.getFloat(
          HeapMemorySizeUtil.MEMSTORE_SIZE_OLD_KEY, HeapMemorySizeUtil.DEFAULT_MEMSTORE_SIZE));
    boolean maxLogsDefined = conf.get("hbase.regionserver.maxlogs") != null;
    if (maxLogsDefined) {
      LOG.warn("'hbase.regionserver.maxlogs' was deprecated.");
    }
    this.maxLogs =
        conf.getInt("hbase.regionserver.maxlogs",
          Math.max(32, calculateMaxLogFiles(memstoreRatio, logrollsize)));

    LOG.info("WAL configuration: blocksize=" + StringUtils.byteDesc(blocksize) + ", rollsize="
        + StringUtils.byteDesc(this.logrollsize) + ", prefix=" + this.walFilePrefix + ", suffix="
        + walFileSuffix + ", logDir=" + this.walDir + ", archiveDir=" + this.walArchiveDir);
    this.slowSyncNs =
        1000000 * conf.getInt("hbase.regionserver.hlog.slowsync.ms", DEFAULT_SLOW_SYNC_TIME_MS);
    int maxHandlersCount = conf.getInt(HConstants.REGION_SERVER_HANDLER_COUNT, 200);
    // Presize our map of SyncFutures by handler objects.
    this.syncFuturesByHandler = new ConcurrentHashMap<Thread, SyncFuture>(maxHandlersCount);
  }

  @Override
  public void registerWALActionsListener(WALActionsListener listener) {
    this.listeners.add(listener);
  }

  @Override
  public boolean unregisterWALActionsListener(WALActionsListener listener) {
    return this.listeners.remove(listener);
  }

  @Override
  public WALCoprocessorHost getCoprocessorHost() {
    return coprocessorHost;
  }

  @Override
  public Long startCacheFlush(byte[] encodedRegionName, Set<byte[]> families) {
    if (!closeBarrier.beginOp()) {
      LOG.info("Flush not started for " + Bytes.toString(encodedRegionName) + "; server closing.");
      return null;
    }
    return this.sequenceIdAccounting.startCacheFlush(encodedRegionName, families);
  }

  @Override
  public void completeCacheFlush(byte[] encodedRegionName) {
    this.sequenceIdAccounting.completeCacheFlush(encodedRegionName);
    closeBarrier.endOp();
  }

  @Override
  public void abortCacheFlush(byte[] encodedRegionName) {
    this.sequenceIdAccounting.abortCacheFlush(encodedRegionName);
    closeBarrier.endOp();
  }

  @Override
  public long getEarliestMemstoreSeqNum(byte[] encodedRegionName) {
    // Used by tests. Deprecated as too subtle for general usage.
    return this.sequenceIdAccounting.getLowestSequenceId(encodedRegionName);
  }

  @Override
  public long getEarliestMemstoreSeqNum(byte[] encodedRegionName, byte[] familyName) {
    // This method is used by tests and for figuring if we should flush or not because our
    // sequenceids are too old. It is also used reporting the master our oldest sequenceid for use
    // figuring what edits can be skipped during log recovery. getEarliestMemStoreSequenceId
    // from this.sequenceIdAccounting is looking first in flushingOldestStoreSequenceIds, the
    // currently flushing sequence ids, and if anything found there, it is returning these. This is
    // the right thing to do for the reporting oldest sequenceids to master; we won't skip edits if
    // we crash during the flush. For figuring what to flush, we might get requeued if our sequence
    // id is old even though we are currently flushing. This may mean we do too much flushing.
    return this.sequenceIdAccounting.getLowestSequenceId(encodedRegionName, familyName);
  }

  @Override
  public byte[][] rollWriter() throws FailedLogCloseException, IOException {
    return rollWriter(false);
  }

  /**
   * This is a convenience method that computes a new filename with a given file-number.
   * @param filenum to use
   * @return Path
   */
  protected Path computeFilename(final long filenum) {
    if (filenum < 0) {
      throw new RuntimeException("WAL file number can't be < 0");
    }
    String child = walFilePrefix + WAL_FILE_NAME_DELIMITER + filenum + walFileSuffix;
    return new Path(walDir, child);
  }

  /**
   * This is a convenience method that computes a new filename with a given using the current WAL
   * file-number
   * @return Path
   */
  public Path getCurrentFileName() {
    return computeFilename(this.filenum.get());
  }

  /**
   * retrieve the next path to use for writing. Increments the internal filenum.
   */
  private Path getNewPath() throws IOException {
    this.filenum.set(System.currentTimeMillis());
    Path newPath = getCurrentFileName();
    while (fs.exists(newPath)) {
      this.filenum.incrementAndGet();
      newPath = getCurrentFileName();
    }
    return newPath;
  }

  @VisibleForTesting
  Path getOldPath() {
    long currentFilenum = this.filenum.get();
    Path oldPath = null;
    if (currentFilenum > 0) {
      // ComputeFilename will take care of meta wal filename
      oldPath = computeFilename(currentFilenum);
    } // I presume if currentFilenum is <= 0, this is first file and null for oldPath if fine?
    return oldPath;
  }

  /**
   * Tell listeners about pre log roll.
   */
  private void tellListenersAboutPreLogRoll(final Path oldPath, final Path newPath)
      throws IOException {
    if (!this.listeners.isEmpty()) {
      for (WALActionsListener i : this.listeners) {
        i.preLogRoll(oldPath, newPath);
      }
    }
  }

  /**
   * Tell listeners about post log roll.
   */
  private void tellListenersAboutPostLogRoll(final Path oldPath, final Path newPath)
      throws IOException {
    if (!this.listeners.isEmpty()) {
      for (WALActionsListener i : this.listeners) {
        i.postLogRoll(oldPath, newPath);
      }
    }
  }

  // public only until class moves to o.a.h.h.wal
  /** @return the number of rolled log files */
  public int getNumRolledLogFiles() {
    return byWalRegionSequenceIds.size();
  }

  // public only until class moves to o.a.h.h.wal
  /** @return the number of log files in use */
  public int getNumLogFiles() {
    // +1 for current use log
    return getNumRolledLogFiles() + 1;
  }

  /**
   * If the number of un-archived WAL files is greater than maximum allowed, check the first
   * (oldest) WAL file, and returns those regions which should be flushed so that it can be
   * archived.
   * @return regions (encodedRegionNames) to flush in order to archive oldest WAL file.
   */
  byte[][] findRegionsToForceFlush() throws IOException {
    byte[][] regions = null;
    int logCount = getNumRolledLogFiles();
    if (logCount > this.maxLogs && logCount > 0) {
      Map.Entry<Path, Map<byte[], Long>> firstWALEntry = this.byWalRegionSequenceIds.firstEntry();
      regions = this.sequenceIdAccounting.findLower(firstWALEntry.getValue());
    }
    if (regions != null) {
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < regions.length; i++) {
        if (i > 0) {
          sb.append(", ");
        }
        sb.append(Bytes.toStringBinary(regions[i]));
      }
      LOG.info("Too many WALs; count=" + logCount + ", max=" + this.maxLogs + "; forcing flush of "
          + regions.length + " regions(s): " + sb.toString());
    }
    return regions;
  }

  /**
   * Archive old logs. A WAL is eligible for archiving if all its WALEdits have been flushed.
   */
  private void cleanOldLogs() throws IOException {
    List<Path> logsToArchive = null;
    // For each log file, look at its Map of regions to highest sequence id; if all sequence ids
    // are older than what is currently in memory, the WAL can be GC'd.
    for (Map.Entry<Path, Map<byte[], Long>> e : this.byWalRegionSequenceIds.entrySet()) {
      Path log = e.getKey();
      Map<byte[], Long> sequenceNums = e.getValue();
      if (this.sequenceIdAccounting.areAllLower(sequenceNums)) {
        if (logsToArchive == null) {
          logsToArchive = new ArrayList<Path>();
        }
        logsToArchive.add(log);
        if (LOG.isTraceEnabled()) {
          LOG.trace("WAL file ready for archiving " + log);
        }
      }
    }
    if (logsToArchive != null) {
      for (Path p : logsToArchive) {
        this.totalLogSize.addAndGet(-this.fs.getFileStatus(p).getLen());
        archiveLogFile(p);
        this.byWalRegionSequenceIds.remove(p);
      }
    }
  }

  /*
   * only public so WALSplitter can use.
   * @return archived location of a WAL file with the given path p
   */
  public static Path getWALArchivePath(Path archiveDir, Path p) {
    return new Path(archiveDir, p.getName());
  }

  private void archiveLogFile(final Path p) throws IOException {
    Path newPath = getWALArchivePath(this.walArchiveDir, p);
    // Tell our listeners that a log is going to be archived.
    if (!this.listeners.isEmpty()) {
      for (WALActionsListener i : this.listeners) {
        i.preLogArchive(p, newPath);
      }
    }
    LOG.info("Archiving " + p + " to " + newPath);
    if (!FSUtils.renameAndSetModifyTime(this.fs, p, newPath)) {
      throw new IOException("Unable to rename " + p + " to " + newPath);
    }
    // Tell our listeners that a log has been archived.
    if (!this.listeners.isEmpty()) {
      for (WALActionsListener i : this.listeners) {
        i.postLogArchive(p, newPath);
      }
    }
  }

  /**
   * Cleans up current writer closing it and then puts in place the passed in
   * <code>nextWriter</code>.
   * <p>
   * <ul>
   * <li>In the case of creating a new WAL, oldPath will be null.</li>
   * <li>In the case of rolling over from one file to the next, none of the parameters will be null.
   * </li>
   * <li>In the case of closing out this FSHLog with no further use newPath and nextWriter will be
   * null.</li>
   * </ul>
   * @param oldPath may be null
   * @param newPath may be null
   * @param nextWriter may be null
   * @return the passed in <code>newPath</code>
   * @throws IOException if there is a problem flushing or closing the underlying FS
   */
  Path replaceWriter(Path oldPath, Path newPath, W nextWriter) throws IOException {
    TraceScope scope = Trace.startSpan("FSHFile.replaceWriter");
    try {
      long oldFileLen = 0L;
      doReplaceWriter(oldPath, newPath, nextWriter);
      int oldNumEntries = this.numEntries.get();
      final String newPathString = (null == newPath ? null : FSUtils.getPath(newPath));
      if (oldPath != null) {
        this.byWalRegionSequenceIds.put(oldPath, this.sequenceIdAccounting.resetHighest());
        this.totalLogSize.addAndGet(oldFileLen);
        LOG.info("Rolled WAL " + FSUtils.getPath(oldPath) + " with entries=" + oldNumEntries
            + ", filesize=" + StringUtils.byteDesc(oldFileLen) + "; new WAL " + newPathString);
      } else {
        LOG.info("New WAL " + newPathString);
      }
      return newPath;
    } finally {
      scope.close();
    }
  }

  protected Span blockOnSync(final SyncFuture syncFuture) throws IOException {
    // Now we have published the ringbuffer, halt the current thread until we get an answer back.
    try {
      syncFuture.get();
      return syncFuture.getSpan();
    } catch (InterruptedException ie) {
      LOG.warn("Interrupted", ie);
      throw convertInterruptedExceptionToIOException(ie);
    } catch (ExecutionException e) {
      throw ensureIOException(e.getCause());
    }
  }

  private static IOException ensureIOException(final Throwable t) {
    return (t instanceof IOException) ? (IOException) t : new IOException(t);
  }

  private IOException convertInterruptedExceptionToIOException(final InterruptedException ie) {
    Thread.currentThread().interrupt();
    IOException ioe = new InterruptedIOException();
    ioe.initCause(ie);
    return ioe;
  }

  @Override
  public byte[][] rollWriter(boolean force) throws FailedLogCloseException, IOException {
    rollWriterLock.lock();
    try {
      // Return if nothing to flush.
      if (!force && this.writer != null && this.numEntries.get() <= 0) {
        return null;
      }
      byte[][] regionsToFlush = null;
      if (this.closed) {
        LOG.debug("WAL closed. Skipping rolling of writer");
        return regionsToFlush;
      }
      if (!closeBarrier.beginOp()) {
        LOG.debug("WAL closing. Skipping rolling of writer");
        return regionsToFlush;
      }
      TraceScope scope = Trace.startSpan("FSHLog.rollWriter");
      try {
        Path oldPath = getOldPath();
        Path newPath = getNewPath();
        // Any exception from here on is catastrophic, non-recoverable so we currently abort.
        W nextWriter = this.createWriterInstance(newPath);
        tellListenersAboutPreLogRoll(oldPath, newPath);
        // NewPath could be equal to oldPath if replaceWriter fails.
        newPath = replaceWriter(oldPath, newPath, nextWriter);
        tellListenersAboutPostLogRoll(oldPath, newPath);
        if (LOG.isDebugEnabled()) {
          LOG.debug("Create new " + getClass().getSimpleName() + " writer with pipeline: "
              + Arrays.toString(getPipeline()));
        }
        // Can we delete any of the old log files?
        if (getNumRolledLogFiles() > 0) {
          cleanOldLogs();
          regionsToFlush = findRegionsToForceFlush();
        }
      } finally {
        closeBarrier.endOp();
        assert scope == NullScope.INSTANCE || !scope.isDetached();
        scope.close();
      }
      return regionsToFlush;
    } finally {
      rollWriterLock.unlock();
    }
  }

  // public only until class moves to o.a.h.h.wal
  /** @return the size of log files in use */
  public long getLogFileSize() {
    return this.totalLogSize.get();
  }

  // public only until class moves to o.a.h.h.wal
  public void requestLogRoll() {
    requestLogRoll(false);
  }

  /**
   * Get the backing files associated with this WAL.
   * @return may be null if there are no files.
   */
  protected FileStatus[] getFiles() throws IOException {
    return FSUtils.listStatus(fs, walDir, ourFiles);
  }

  @Override
  public void shutdown() throws IOException {
    if (!shutdown.compareAndSet(false, true)) {
      return;
    }
    closed = true;
    try {
      // Prevent all further flushing and rolling.
      closeBarrier.stopAndDrainOps();
    } catch (InterruptedException e) {
      LOG.error("Exception while waiting for cache flushes and log rolls", e);
      Thread.currentThread().interrupt();
    }
    // Tell our listeners that the log is closing
    if (!this.listeners.isEmpty()) {
      for (WALActionsListener i : this.listeners) {
        i.logCloseRequested();
      }
    }
    doShutdown();
  }

  @Override
  public void close() throws IOException {
    shutdown();
    final FileStatus[] files = getFiles();
    if (null != files && 0 != files.length) {
      for (FileStatus file : files) {
        Path p = getWALArchivePath(this.walArchiveDir, file.getPath());
        // Tell our listeners that a log is going to be archived.
        if (!this.listeners.isEmpty()) {
          for (WALActionsListener i : this.listeners) {
            i.preLogArchive(file.getPath(), p);
          }
        }

        if (!FSUtils.renameAndSetModifyTime(fs, file.getPath(), p)) {
          throw new IOException("Unable to rename " + file.getPath() + " to " + p);
        }
        // Tell our listeners that a log was archived.
        if (!this.listeners.isEmpty()) {
          for (WALActionsListener i : this.listeners) {
            i.postLogArchive(file.getPath(), p);
          }
        }
      }
      LOG.debug("Moved " + files.length + " WAL file(s) to " + FSUtils.getPath(this.walArchiveDir));
    }
    LOG.info("Closed WAL: " + toString());
  }

  protected SyncFuture getSyncFuture(final long sequence, Span span) {
    SyncFuture syncFuture = this.syncFuturesByHandler.get(Thread.currentThread());
    if (syncFuture == null) {
      syncFuture = new SyncFuture();
      this.syncFuturesByHandler.put(Thread.currentThread(), syncFuture);
    }
    return syncFuture.reset(sequence, span);
  }

  protected void requestLogRoll(boolean tooFewReplicas) {
    if (!this.listeners.isEmpty()) {
      for (WALActionsListener i : this.listeners) {
        i.logRollRequested(tooFewReplicas);
      }
    }
  }

  long getUnflushedEntriesCount() {
    long highestSynced = this.highestSyncedTxid.get();
    long highestUnsynced = this.highestUnsyncedTxid;
    return highestSynced >= highestUnsynced ? 0 : highestUnsynced - highestSynced;
  }

  boolean isUnflushedEntries() {
    return getUnflushedEntriesCount() > 0;
  }

  /**
   * Exposed for testing only. Use to tricks like halt the ring buffer appending.
   */
  @VisibleForTesting
  void atHeadOfRingBufferEventHandlerAppend() {
    // Noop
  }

  protected boolean append(W writer, FSWALEntry entry) throws IOException {
    // TODO: WORK ON MAKING THIS APPEND FASTER. DOING WAY TOO MUCH WORK WITH CPs, PBing, etc.
    atHeadOfRingBufferEventHandlerAppend();
    long start = EnvironmentEdgeManager.currentTime();
    byte[] encodedRegionName = entry.getKey().getEncodedRegionName();
    long regionSequenceId = WALKey.NO_SEQUENCE_ID;
    // We are about to append this edit; update the region-scoped sequence number. Do it
    // here inside this single appending/writing thread. Events are ordered on the ringbuffer
    // so region sequenceids will also be in order.
    regionSequenceId = entry.stampRegionSequenceId();
    // Edits are empty, there is nothing to append. Maybe empty when we are looking for a
    // region sequence id only, a region edit/sequence id that is not associated with an actual
    // edit. It has to go through all the rigmarole to be sure we have the right ordering.
    if (entry.getEdit().isEmpty()) {
      return false;
    }

    // Coprocessor hook.
    if (!coprocessorHost.preWALWrite(entry.getHRegionInfo(), entry.getKey(), entry.getEdit())) {
      if (entry.getEdit().isReplay()) {
        // Set replication scope null so that this won't be replicated
        entry.getKey().serializeReplicationScope(false);
      }
    }
    if (!listeners.isEmpty()) {
      for (WALActionsListener i : listeners) {
        i.visitLogEntryBeforeWrite(entry.getKey(), entry.getEdit());
      }
    }
    doAppend(writer, entry);
    assert highestUnsyncedTxid < entry.getTxid();
    highestUnsyncedTxid = entry.getTxid();
    sequenceIdAccounting.update(encodedRegionName, entry.getFamilyNames(), regionSequenceId,
      entry.isInMemstore());
    coprocessorHost.postWALWrite(entry.getHRegionInfo(), entry.getKey(), entry.getEdit());
    // Update metrics.
    postAppend(entry, EnvironmentEdgeManager.currentTime() - start);
    numEntries.incrementAndGet();
    return true;
  }

  private long postAppend(final Entry e, final long elapsedTime) throws IOException {
    long len = 0;
    if (!listeners.isEmpty()) {
      for (Cell cell : e.getEdit().getCells()) {
        len += CellUtil.estimatedSerializedSizeOf(cell);
      }
      for (WALActionsListener listener : listeners) {
        listener.postAppend(len, elapsedTime, e.getKey(), e.getEdit());
      }
    }
    return len;
  }

  protected void postSync(final long timeInNanos, final int handlerSyncs) {
    if (timeInNanos > this.slowSyncNs) {
      String msg =
          new StringBuilder().append("Slow sync cost: ").append(timeInNanos / 1000000)
              .append(" ms, current pipeline: ").append(Arrays.toString(getPipeline())).toString();
      Trace.addTimelineAnnotation(msg);
      LOG.info(msg);
    }
    if (!listeners.isEmpty()) {
      for (WALActionsListener listener : listeners) {
        listener.postSync(timeInNanos, handlerSyncs);
      }
    }
  }

  /**
   * NOTE: This append, at a time that is usually after this call returns, starts an mvcc
   * transaction by calling 'begin' wherein which we assign this update a sequenceid. At assignment
   * time, we stamp all the passed in Cells inside WALEdit with their sequenceId. You must
   * 'complete' the transaction this mvcc transaction by calling
   * MultiVersionConcurrencyControl#complete(...) or a variant otherwise mvcc will get stuck. Do it
   * in the finally of a try/finally block within which this append lives and any subsequent
   * operations like sync or update of memstore, etc. Get the WriteEntry to pass mvcc out of the
   * passed in WALKey <code>walKey</code> parameter. Be warned that the WriteEntry is not
   * immediately available on return from this method. It WILL be available subsequent to a sync of
   * this append; otherwise, you will just have to wait on the WriteEntry to get filled in.
   */
  @Override
  public abstract long append(HRegionInfo info, WALKey key, WALEdit edits, boolean inMemstore)
      throws IOException;

  protected abstract void doAppend(W writer, FSWALEntry entry) throws IOException;

  protected abstract W createWriterInstance(Path path) throws IOException;

  /**
   * @return old wal file size
   */
  protected abstract long doReplaceWriter(Path oldPath, Path newPath, W nextWriter)
      throws IOException;

  protected abstract void doShutdown() throws IOException;

  /**
   * This method gets the pipeline for the current WAL.
   */
  @VisibleForTesting
  abstract DatanodeInfo[] getPipeline();

  /**
   * This method gets the datanode replication count for the current WAL.
   */
  @VisibleForTesting
  abstract int getLogReplication();
}