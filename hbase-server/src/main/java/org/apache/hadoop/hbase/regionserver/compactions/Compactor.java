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
package org.apache.hadoop.hbase.regionserver.compactions;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.security.PrivilegedExceptionAction;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.Cell;
import org.apache.hadoop.hbase.CellUtil;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.KeyValueUtil;
import org.apache.hadoop.hbase.classification.InterfaceAudience;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.io.compress.Compression;
import org.apache.hadoop.hbase.io.hfile.HFile;
import org.apache.hadoop.hbase.io.hfile.HFile.FileInfo;
import org.apache.hadoop.hbase.regionserver.HStore;
import org.apache.hadoop.hbase.regionserver.InternalScanner;
import org.apache.hadoop.hbase.regionserver.KeyValueScanner;
import org.apache.hadoop.hbase.regionserver.ScanType;
import org.apache.hadoop.hbase.regionserver.ScannerContext;
import org.apache.hadoop.hbase.regionserver.Store;
import org.apache.hadoop.hbase.regionserver.StoreFile;
import org.apache.hadoop.hbase.regionserver.StoreFileScanner;
import org.apache.hadoop.hbase.regionserver.StoreScanner;
import org.apache.hadoop.hbase.regionserver.TimeRangeTracker;
import org.apache.hadoop.hbase.regionserver.throttle.ThroughputControlUtil;
import org.apache.hadoop.hbase.regionserver.throttle.ThroughputController;
import org.apache.hadoop.hbase.security.User;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.util.EnvironmentEdgeManager;
import org.apache.hadoop.hbase.util.Writables;
import org.apache.hadoop.util.StringUtils.TraditionalBinaryPrefix;

/**
 * A compactor is a compaction algorithm associated a given policy. Base class also contains
 * reusable parts for implementing compactors (what is common and what isn't is evolving).
 */
@InterfaceAudience.Private
public abstract class Compactor {
  private static final Log LOG = LogFactory.getLog(Compactor.class);
  private static final long COMPACTION_PROGRESS_LOG_INTERVAL = 60 * 1000;
  protected CompactionProgress progress;
  protected final Configuration conf;
  protected final Store store;

  protected final int compactionKVMax;
  protected final Compression.Algorithm compactionCompression;

  /** specify how many days to keep MVCC values during major compaction **/ 
  protected final int keepSeqIdPeriod;

  //TODO: depending on Store is not good but, realistically, all compactors currently do.
  Compactor(final Configuration conf, final Store store) {
    this.conf = conf;
    this.store = store;
    this.compactionKVMax =
      this.conf.getInt(HConstants.COMPACTION_KV_MAX, HConstants.COMPACTION_KV_MAX_DEFAULT);
    this.compactionCompression = (this.store.getFamily() == null) ?
        Compression.Algorithm.NONE : this.store.getFamily().getCompactionCompressionType();
    this.keepSeqIdPeriod = Math.max(this.conf.getInt(HConstants.KEEP_SEQID_PERIOD, 
      HConstants.MIN_KEEP_SEQID_PERIOD), HConstants.MIN_KEEP_SEQID_PERIOD);
  }

  public interface CellSink {
    void append(Cell cell) throws IOException;
  }

  public CompactionProgress getProgress() {
    return this.progress;
  }

  /** The sole reason this class exists is that java has no ref/out/pointer parameters. */
  protected static class FileDetails {
    /** Maximum key count after compaction (for blooms) */
    public long maxKeyCount = 0;
    /** Earliest put timestamp if major compaction */
    public long earliestPutTs = HConstants.LATEST_TIMESTAMP;
    /** Latest put timestamp */
    public long latestPutTs = HConstants.LATEST_TIMESTAMP;
    /** The last key in the files we're compacting. */
    public long maxSeqId = 0;
    /** Latest memstore read point found in any of the involved files */
    public long maxMVCCReadpoint = 0;
    /** Max tags length**/
    public int maxTagsLength = 0;
    /** Min SeqId to keep during a major compaction **/
    public long minSeqIdToKeep = 0;
  }

  /**
   * Extracts some details about the files to compact that are commonly needed by compactors.
   * @param filesToCompact Files.
   * @param allFiles Whether all files are included for compaction
   * @return The result.
   */
  protected FileDetails getFileDetails(
      Collection<StoreFile> filesToCompact, boolean allFiles) throws IOException {
    FileDetails fd = new FileDetails();
    long oldestHFileTimeStampToKeepMVCC = System.currentTimeMillis() - 
      (1000L * 60 * 60 * 24 * this.keepSeqIdPeriod);  

    for (StoreFile file : filesToCompact) {
      if(allFiles && (file.getModificationTimeStamp() < oldestHFileTimeStampToKeepMVCC)) {
        // when isAllFiles is true, all files are compacted so we can calculate the smallest 
        // MVCC value to keep
        if(fd.minSeqIdToKeep < file.getMaxMemstoreTS()) {
          fd.minSeqIdToKeep = file.getMaxMemstoreTS();
        }
      }
      long seqNum = file.getMaxSequenceId();
      fd.maxSeqId = Math.max(fd.maxSeqId, seqNum);
      StoreFile.Reader r = file.getReader();
      if (r == null) {
        LOG.warn("Null reader for " + file.getPath());
        continue;
      }
      // NOTE: use getEntries when compacting instead of getFilterEntries, otherwise under-sized
      // blooms can cause progress to be miscalculated or if the user switches bloom
      // type (e.g. from ROW to ROWCOL)
      long keyCount = r.getEntries();
      fd.maxKeyCount += keyCount;
      // calculate the latest MVCC readpoint in any of the involved store files
      Map<byte[], byte[]> fileInfo = r.loadFileInfo();
      byte tmp[] = null;
      // Get and set the real MVCCReadpoint for bulk loaded files, which is the
      // SeqId number.
      if (r.isBulkLoaded()) {
        fd.maxMVCCReadpoint = Math.max(fd.maxMVCCReadpoint, r.getSequenceID());
      }
      else {
        tmp = fileInfo.get(HFile.Writer.MAX_MEMSTORE_TS_KEY);
        if (tmp != null) {
          fd.maxMVCCReadpoint = Math.max(fd.maxMVCCReadpoint, Bytes.toLong(tmp));
        }
      }
      tmp = fileInfo.get(FileInfo.MAX_TAGS_LEN);
      if (tmp != null) {
        fd.maxTagsLength = Math.max(fd.maxTagsLength, Bytes.toInt(tmp));
      }
      // If required, calculate the earliest put timestamp of all involved storefiles.
      // This is used to remove family delete marker during compaction.
      long earliestPutTs = 0;
      if (allFiles) {
        tmp = fileInfo.get(StoreFile.EARLIEST_PUT_TS);
        if (tmp == null) {
          // There's a file with no information, must be an old one
          // assume we have very old puts
          fd.earliestPutTs = earliestPutTs = HConstants.OLDEST_TIMESTAMP;
        } else {
          earliestPutTs = Bytes.toLong(tmp);
          fd.earliestPutTs = Math.min(fd.earliestPutTs, earliestPutTs);
        }
      }
      tmp = fileInfo.get(StoreFile.TIMERANGE_KEY);
      TimeRangeTracker trt = new TimeRangeTracker();
      if (tmp == null) {
        fd.latestPutTs = HConstants.LATEST_TIMESTAMP;
      } else {
        Writables.copyWritable(tmp, trt);
        fd.latestPutTs = trt.getMaximumTimestamp();
      }
      if (LOG.isDebugEnabled()) {
        LOG.debug("Compacting " + file +
          ", keycount=" + keyCount +
          ", bloomtype=" + r.getBloomFilterType().toString() +
          ", size=" + TraditionalBinaryPrefix.long2String(r.length(), "", 1) +
          ", encoding=" + r.getHFileReader().getDataBlockEncoding() +
          ", seqNum=" + seqNum +
          (allFiles ? ", earliestPutTs=" + earliestPutTs: ""));
      }
    }
    return fd;
  }

  /**
   * Creates file scanners for compaction.
   * @param filesToCompact Files.
   * @return Scanners.
   */
  protected List<StoreFileScanner> createFileScanners(
      final Collection<StoreFile> filesToCompact,
      long smallestReadPoint,
      boolean useDropBehind) throws IOException {
    return StoreFileScanner.getScannersForStoreFiles(filesToCompact,
        /* cache blocks = */ false,
        /* use pread = */ false,
        /* is compaction */ true,
        /* use Drop Behind */ useDropBehind,
      smallestReadPoint);
  }

  protected long getSmallestReadPoint() {
    return store.getSmallestReadPoint();
  }

  /**
   * Calls coprocessor, if any, to create compaction scanner - before normal scanner creation.
   * @param request Compaction request.
   * @param scanType Scan type.
   * @param earliestPutTs Earliest put ts.
   * @param scanners File scanners for compaction files.
   * @return Scanner override by coprocessor; null if not overriding.
   */
  protected InternalScanner preCreateCoprocScanner(final CompactionRequest request,
      ScanType scanType, long earliestPutTs,  List<StoreFileScanner> scanners) throws IOException {
    return preCreateCoprocScanner(request, scanType, earliestPutTs, scanners, null);
  }

  protected InternalScanner preCreateCoprocScanner(final CompactionRequest request,
      final ScanType scanType, final long earliestPutTs, final List<StoreFileScanner> scanners,
      User user) throws IOException {
    if (store.getCoprocessorHost() == null) return null;
    if (user == null) {
      return store.getCoprocessorHost().preCompactScannerOpen(store, scanners, scanType,
        earliestPutTs, request);
    } else {
      try {
        return user.getUGI().doAs(new PrivilegedExceptionAction<InternalScanner>() {
          @Override
          public InternalScanner run() throws Exception {
            return store.getCoprocessorHost().preCompactScannerOpen(store, scanners,
              scanType, earliestPutTs, request);
          }
        });
      } catch (InterruptedException ie) {
        InterruptedIOException iioe = new InterruptedIOException();
        iioe.initCause(ie);
        throw iioe;
      }
    }
  }

  /**
   * Calls coprocessor, if any, to create scanners - after normal scanner creation.
   * @param request Compaction request.
   * @param scanType Scan type.
   * @param scanner The default scanner created for compaction.
   * @return Scanner scanner to use (usually the default); null if compaction should not proceed.
   */
   protected InternalScanner postCreateCoprocScanner(final CompactionRequest request,
      final ScanType scanType, final InternalScanner scanner, User user) throws IOException {
     if (store.getCoprocessorHost() == null) return scanner;
     if (user == null) {
       return store.getCoprocessorHost().preCompact(store, scanner, scanType, request);
     } else {
       try {
         return user.getUGI().doAs(new PrivilegedExceptionAction<InternalScanner>() {
           @Override
           public InternalScanner run() throws Exception {
             return store.getCoprocessorHost().preCompact(store, scanner, scanType, request);
           }
         });
       } catch (InterruptedException ie) {
         InterruptedIOException iioe = new InterruptedIOException();
         iioe.initCause(ie);
         throw iioe;
       }
     }
  }

  /**
   * Performs the compaction.
   * @param fd FileDetails of cell sink writer
   * @param scanner Where to read from.
   * @param writer Where to write to.
   * @param smallestReadPoint Smallest read point.
   * @param cleanSeqId When true, remove seqId(used to be mvcc) value which is &lt;= smallestReadPoint
   * @param major Is a major compaction.
   * @return Whether compaction ended; false if it was interrupted for some reason.
   */
  protected boolean performCompaction(FileDetails fd, InternalScanner scanner, CellSink writer,
      long smallestReadPoint, boolean cleanSeqId,
      ThroughputController throughputController, boolean major) throws IOException {
    long bytesWrittenProgressForCloseCheck = 0;
    long bytesWrittenProgressForLog = 0;
    long bytesWrittenProgressForShippedCall = 0;
    // Since scanner.next() can return 'false' but still be delivering data,
    // we have to use a do/while loop.
    List<Cell> cells = new ArrayList<Cell>();
    long closeCheckSizeLimit = HStore.getCloseCheckInterval();
    long lastMillis = 0;
    if (LOG.isDebugEnabled()) {
      lastMillis = EnvironmentEdgeManager.currentTime();
    }
    String compactionName = ThroughputControlUtil.getNameForThrottling(store, "compaction");
    long now = 0;
    boolean hasMore;
    ScannerContext scannerContext =
        ScannerContext.newBuilder().setBatchLimit(compactionKVMax).build();

    throughputController.start(compactionName);
    KeyValueScanner kvs = (scanner instanceof KeyValueScanner)? (KeyValueScanner)scanner : null;
    long minFilesToCompact = Math.max(2L,
        conf.getInt(CompactionConfiguration.HBASE_HSTORE_COMPACTION_MIN_KEY,
            /* old name */ conf.getInt("hbase.hstore.compactionThreshold", 3)));
    long shippedCallSizeLimit = (long) minFilesToCompact * HConstants.DEFAULT_BLOCKSIZE;
    try {
      do {
        hasMore = scanner.next(cells, scannerContext);
        if (LOG.isDebugEnabled()) {
          now = EnvironmentEdgeManager.currentTime();
        }
        // output to writer:
        for (Cell c : cells) {
          if (cleanSeqId && c.getSequenceId() <= smallestReadPoint) {
            CellUtil.setSequenceId(c, 0);
          }
          writer.append(c);
          int len = KeyValueUtil.length(c);
          ++progress.currentCompactedKVs;
          progress.totalCompactedSize += len;
          bytesWrittenProgressForShippedCall += len;
          if (LOG.isDebugEnabled()) {
            bytesWrittenProgressForLog += len;
          }
          throughputController.control(compactionName, len);
          // check periodically to see if a system stop is requested
          if (closeCheckSizeLimit > 0) {
            bytesWrittenProgressForCloseCheck += len;
            if (bytesWrittenProgressForCloseCheck > closeCheckSizeLimit) {
              bytesWrittenProgressForCloseCheck = 0;
              if (!store.areWritesEnabled()) {
                progress.cancel();
                return false;
              }
            }
          }
          if (kvs != null && bytesWrittenProgressForShippedCall > shippedCallSizeLimit) {
            // The SHARED block references, being read for compaction, will be kept in prevBlocks
            // list(See HFileScannerImpl#prevBlocks). In case of scan flow, after each set of cells
            // being returned to client, we will call shipped() which can clear this list. Here by
            // we are doing the similar thing. In between the compaction (after every N cells
            // written with collective size of 'shippedCallSizeLimit') we will call shipped which
            // may clear prevBlocks list.
            kvs.shipped();
            bytesWrittenProgressForShippedCall = 0;
          }
        }
        // Log the progress of long running compactions every minute if
        // logging at DEBUG level
        if (LOG.isDebugEnabled()) {
          if ((now - lastMillis) >= COMPACTION_PROGRESS_LOG_INTERVAL) {
            LOG.debug("Compaction progress: "
                + compactionName
                + " "
                + progress
                + String.format(", rate=%.2f kB/sec", (bytesWrittenProgressForLog / 1024.0)
                    / ((now - lastMillis) / 1000.0)) + ", throughputController is "
                + throughputController);
            lastMillis = now;
            bytesWrittenProgressForLog = 0;
          }
        }
        cells.clear();
      } while (hasMore);
    } catch (InterruptedException e) {
      progress.cancel();
      throw new InterruptedIOException("Interrupted while control throughput of compacting "
          + compactionName);
    } finally {
      throughputController.finish(compactionName);
    }
    progress.complete();
    return true;
  }

  /**
   * @param store store
   * @param scanners Store file scanners.
   * @param scanType Scan type.
   * @param smallestReadPoint Smallest MVCC read point.
   * @param earliestPutTs Earliest put across all files.
   * @return A compaction scanner.
   */
  protected InternalScanner createScanner(Store store, List<StoreFileScanner> scanners,
      ScanType scanType, long smallestReadPoint, long earliestPutTs) throws IOException {
    Scan scan = new Scan();
    scan.setMaxVersions(store.getFamily().getMaxVersions());
    return new StoreScanner(store, store.getScanInfo(), scan, scanners,
        scanType, smallestReadPoint, earliestPutTs);
  }

  /**
   * @param store The store.
   * @param scanners Store file scanners.
   * @param smallestReadPoint Smallest MVCC read point.
   * @param earliestPutTs Earliest put across all files.
   * @param dropDeletesFromRow Drop deletes starting with this row, inclusive. Can be null.
   * @param dropDeletesToRow Drop deletes ending with this row, exclusive. Can be null.
   * @return A compaction scanner.
   */
  protected InternalScanner createScanner(Store store, List<StoreFileScanner> scanners,
     long smallestReadPoint, long earliestPutTs, byte[] dropDeletesFromRow,
     byte[] dropDeletesToRow) throws IOException {
    Scan scan = new Scan();
    scan.setMaxVersions(store.getFamily().getMaxVersions());
    return new StoreScanner(store, store.getScanInfo(), scan, scanners, smallestReadPoint,
        earliestPutTs, dropDeletesFromRow, dropDeletesToRow);
  }

  /**
   * Appends the metadata and closes the writer.
   * @param writer The current store writer.
   * @param fd The file details.
   * @param isMajor Is a major compaction.
   * @throws IOException
   */
  protected void appendMetadataAndCloseWriter(StoreFile.Writer writer, FileDetails fd,
      boolean isMajor) throws IOException {
    writer.appendMetadata(fd.maxSeqId, isMajor);
    writer.close();
  }
}
