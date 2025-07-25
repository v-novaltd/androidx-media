/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.media3.exoplayer.hls;

import static androidx.media3.common.util.Assertions.checkNotNull;
import static androidx.media3.common.util.Assertions.checkState;
import static java.lang.Math.max;
import static java.lang.annotation.ElementType.TYPE_USE;

import android.net.Uri;
import android.os.SystemClock;
import android.util.Pair;
import androidx.annotation.IntDef;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.TrackGroup;
import androidx.media3.common.util.TimestampAdjuster;
import androidx.media3.common.util.UriUtil;
import androidx.media3.common.util.Util;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;
import androidx.media3.exoplayer.LoadingInfo;
import androidx.media3.exoplayer.SeekParameters;
import androidx.media3.exoplayer.analytics.PlayerId;
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist;
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist.Segment;
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistTracker;
import androidx.media3.exoplayer.source.BehindLiveWindowException;
import androidx.media3.exoplayer.source.chunk.BaseMediaChunkIterator;
import androidx.media3.exoplayer.source.chunk.Chunk;
import androidx.media3.exoplayer.source.chunk.DataChunk;
import androidx.media3.exoplayer.source.chunk.MediaChunk;
import androidx.media3.exoplayer.source.chunk.MediaChunkIterator;
import androidx.media3.exoplayer.trackselection.BaseTrackSelection;
import androidx.media3.exoplayer.trackselection.ExoTrackSelection;
import androidx.media3.exoplayer.upstream.CmcdConfiguration;
import androidx.media3.exoplayer.upstream.CmcdData;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.primitives.Ints;
import java.io.IOException;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/** Source of Hls (possibly adaptive) chunks. */
/* package */ class HlsChunkSource {

  /** Chunk holder that allows the scheduling of retries. */
  public static final class HlsChunkHolder {

    public HlsChunkHolder() {
      clear();
    }

    /** The chunk to be loaded next. */
    @Nullable public Chunk chunk;

    /** Indicates that the end of the stream has been reached. */
    public boolean endOfStream;

    /** Indicates that the chunk source is waiting for the referred playlist to be refreshed. */
    @Nullable public Uri playlistUrl;

    /** Clears the holder. */
    public void clear() {
      chunk = null;
      endOfStream = false;
      playlistUrl = null;
    }
  }

  /**
   * Chunk publication state. One of {@link #CHUNK_PUBLICATION_STATE_PRELOAD}, {@link
   * #CHUNK_PUBLICATION_STATE_PUBLISHED}, {@link #CHUNK_PUBLICATION_STATE_REMOVED}.
   */
  @Documented
  @Target(TYPE_USE)
  @IntDef({
    CHUNK_PUBLICATION_STATE_PRELOAD,
    CHUNK_PUBLICATION_STATE_PUBLISHED,
    CHUNK_PUBLICATION_STATE_REMOVED
  })
  @Retention(RetentionPolicy.SOURCE)
  @interface ChunkPublicationState {}

  /** Indicates that the chunk is based on a preload hint. */
  public static final int CHUNK_PUBLICATION_STATE_PRELOAD = 0;

  /** Indicates that the chunk is definitely published. */
  public static final int CHUNK_PUBLICATION_STATE_PUBLISHED = 1;

  /**
   * Indicates that the chunk has been removed from the playlist.
   *
   * <p>See RFC 8216, Section 6.2.6 also.
   */
  public static final int CHUNK_PUBLICATION_STATE_REMOVED = 2;

  /**
   * The maximum number of keys that the key cache can hold. This value must be 2 or greater in
   * order to hold initialization segment and media segment keys simultaneously.
   */
  private static final int KEY_CACHE_SIZE = 4;

  private final HlsExtractorFactory extractorFactory;
  private final DataSource mediaDataSource;
  private final DataSource encryptionDataSource;
  private final TimestampAdjusterProvider timestampAdjusterProvider;
  private final Uri[] playlistUrls;
  private final Format[] playlistFormats;
  private final HlsPlaylistTracker playlistTracker;
  private final TrackGroup trackGroup;
  @Nullable private final List<Format> muxedCaptionFormats;
  private final FullSegmentEncryptionKeyCache keyCache;
  private final PlayerId playerId;
  @Nullable private final CmcdConfiguration cmcdConfiguration;
  private final long timestampAdjusterInitializationTimeoutMs;

  private boolean isPrimaryTimestampSource;
  private byte[] scratchSpace;
  @Nullable private IOException fatalError;
  @Nullable private Uri lastPlaylistErrorUrl;
  @Nullable private Uri nextChunkStuckOnPlaylistUrl;
  private boolean independentSegments;

  // Note: The track group in the selection is typically *not* equal to trackGroup. This is due to
  // the way in which HlsSampleStreamWrapper generates track groups. Use only index based methods
  // in ExoTrackSelection to avoid unexpected behavior.
  private ExoTrackSelection trackSelection;
  private long liveEdgeInPeriodTimeUs;

  /**
   * The time at which the last {@link #getNextChunk(LoadingInfo, long, long, List, boolean,
   * HlsChunkHolder)} method was called, as measured by {@link SystemClock#elapsedRealtime}.
   */
  private long lastChunkRequestRealtimeMs;

  /**
   * @param extractorFactory An {@link HlsExtractorFactory} from which to obtain the extractors for
   *     media chunks.
   * @param playlistTracker The {@link HlsPlaylistTracker} from which to obtain media playlists.
   * @param playlistUrls The {@link Uri}s of the media playlists that can be adapted between by this
   *     chunk source.
   * @param playlistFormats The {@link Format Formats} corresponding to the media playlists.
   * @param dataSourceFactory An {@link HlsDataSourceFactory} to create {@link DataSource}s for the
   *     chunks.
   * @param mediaTransferListener The transfer listener which should be informed of any media data
   *     transfers. May be null if no listener is available.
   * @param timestampAdjusterProvider A provider of {@link TimestampAdjuster} instances. If multiple
   *     {@link HlsChunkSource}s are used for a single playback, they should all share the same
   *     provider.
   * @param timestampAdjusterInitializationTimeoutMs The timeout for the loading thread to wait for
   *     the timestamp adjuster to initialize, in milliseconds. A timeout of zero is interpreted as
   *     an infinite timeout.
   * @param muxedCaptionFormats List of muxed caption {@link Format}s. Null if no closed caption
   *     information is available in the multivariant playlist.
   * @param playerId The {@link PlayerId} of the player using this chunk source.
   * @param cmcdConfiguration The {@link CmcdConfiguration} for this chunk source.
   */
  public HlsChunkSource(
      HlsExtractorFactory extractorFactory,
      HlsPlaylistTracker playlistTracker,
      Uri[] playlistUrls,
      Format[] playlistFormats,
      HlsDataSourceFactory dataSourceFactory,
      @Nullable TransferListener mediaTransferListener,
      TimestampAdjusterProvider timestampAdjusterProvider,
      long timestampAdjusterInitializationTimeoutMs,
      @Nullable List<Format> muxedCaptionFormats,
      PlayerId playerId,
      @Nullable CmcdConfiguration cmcdConfiguration) {
    this.extractorFactory = extractorFactory;
    this.playlistTracker = playlistTracker;
    this.playlistUrls = playlistUrls;
    this.playlistFormats = playlistFormats;
    this.timestampAdjusterProvider = timestampAdjusterProvider;
    this.timestampAdjusterInitializationTimeoutMs = timestampAdjusterInitializationTimeoutMs;
    this.muxedCaptionFormats = muxedCaptionFormats;
    this.playerId = playerId;
    this.cmcdConfiguration = cmcdConfiguration;
    this.lastChunkRequestRealtimeMs = C.TIME_UNSET;
    keyCache = new FullSegmentEncryptionKeyCache(KEY_CACHE_SIZE);
    scratchSpace = Util.EMPTY_BYTE_ARRAY;
    liveEdgeInPeriodTimeUs = C.TIME_UNSET;
    mediaDataSource = dataSourceFactory.createDataSource(C.DATA_TYPE_MEDIA);
    if (mediaTransferListener != null) {
      mediaDataSource.addTransferListener(mediaTransferListener);
    }
    encryptionDataSource = dataSourceFactory.createDataSource(C.DATA_TYPE_DRM);
    trackGroup = new TrackGroup(playlistFormats);
    // Use only non-trickplay variants for preparation. See [Internal ref: b/161529098].
    ArrayList<Integer> initialTrackSelection = new ArrayList<>();
    for (int i = 0; i < playlistUrls.length; i++) {
      if ((playlistFormats[i].roleFlags & C.ROLE_FLAG_TRICK_PLAY) == 0) {
        initialTrackSelection.add(i);
      }
    }
    trackSelection =
        new InitializationTrackSelection(trackGroup, Ints.toArray(initialTrackSelection));
  }

  /**
   * If the source is currently having difficulty providing chunks, then this method throws the
   * underlying error. Otherwise does nothing.
   *
   * @throws IOException The underlying error.
   */
  public void maybeThrowError() throws IOException {
    if (fatalError != null) {
      throw fatalError;
    }
    if (lastPlaylistErrorUrl != null && lastPlaylistErrorUrl.equals(nextChunkStuckOnPlaylistUrl)) {
      playlistTracker.maybeThrowPlaylistRefreshError(lastPlaylistErrorUrl);
    }
  }

  /** Returns the track group exposed by the source. */
  public TrackGroup getTrackGroup() {
    return trackGroup;
  }

  /** Returns whether the chunk source has independent segments. */
  public boolean hasIndependentSegments() {
    return independentSegments;
  }

  /**
   * Sets the current track selection.
   *
   * @param trackSelection The {@link ExoTrackSelection}.
   */
  public void setTrackSelection(ExoTrackSelection trackSelection) {
    // Deactivate the selected playlist from the old track selection for playback.
    deactivatePlaylistForSelectedTrack();
    this.trackSelection = trackSelection;
  }

  /** Returns the current {@link ExoTrackSelection}. */
  public ExoTrackSelection getTrackSelection() {
    return trackSelection;
  }

  /** Resets the source. */
  public void reset() {
    deactivatePlaylistForSelectedTrack();
    fatalError = null;
  }

  /**
   * Sets whether this chunk source is responsible for initializing timestamp adjusters.
   *
   * @param isPrimaryTimestampSource True if this chunk source is responsible for initializing
   *     timestamp adjusters.
   */
  public void setIsPrimaryTimestampSource(boolean isPrimaryTimestampSource) {
    this.isPrimaryTimestampSource = isPrimaryTimestampSource;
  }

  /**
   * Adjusts a seek position given the specified {@link SeekParameters}.
   *
   * @param positionUs The seek position in microseconds.
   * @param seekParameters Parameters that control how the seek is performed.
   * @return The adjusted seek position, in microseconds.
   */
  public long getAdjustedSeekPositionUs(long positionUs, SeekParameters seekParameters) {
    int selectedIndex = trackSelection.getSelectedIndex();
    @Nullable
    HlsMediaPlaylist mediaPlaylist =
        selectedIndex < playlistUrls.length && selectedIndex != C.INDEX_UNSET
            ? playlistTracker.getPlaylistSnapshot(
                playlistUrls[trackSelection.getSelectedIndexInTrackGroup()],
                /* isForPlayback= */ true)
            : null;

    if (mediaPlaylist == null || mediaPlaylist.segments.isEmpty()) {
      return positionUs;
    }

    // The playlist is non-empty, so we can use segment start times as sync points. We can always
    // safely assume that the segment contains the positionUs starts with sync samples (even if it
    // actually doesn't) and set the below firstSyncUs as the start time of that segment, as it
    // doesn't harm the seeking performance if it is resolved to be the seek position. However, we
    // should set the secondSyncUs as the start time of the segment after the positionUs only when
    // we're sure that the segments start with sync samples (i.e., EXT-X-INDEPENDENT-SEGMENTS is
    // set).
    //
    // Note that in the rare case that (a) an adaptive quality switch occurs between the adjustment
    // and the seek being performed, and (b) segment start times are not aligned across variants,
    // it's possible that the adjusted position may not be at a sync point when it was intended to
    // be. However, this is very much an edge case, and getting it wrong is worth it for getting
    // the vast majority of cases right whilst keeping the implementation relatively simple.
    long startOfPlaylistInPeriodUs =
        mediaPlaylist.startTimeUs - playlistTracker.getInitialStartTimeUs();
    long relativePositionUs = positionUs - startOfPlaylistInPeriodUs;
    int segmentIndex =
        Util.binarySearchFloor(
            mediaPlaylist.segments,
            relativePositionUs,
            /* inclusive= */ true,
            /* stayInBounds= */ true);
    long firstSyncUs = mediaPlaylist.segments.get(segmentIndex).relativeStartTimeUs;
    long secondSyncUs = firstSyncUs;
    if (mediaPlaylist.hasIndependentSegments && segmentIndex != mediaPlaylist.segments.size() - 1) {
      secondSyncUs = mediaPlaylist.segments.get(segmentIndex + 1).relativeStartTimeUs;
    }
    return seekParameters.resolveSeekPositionUs(relativePositionUs, firstSyncUs, secondSyncUs)
        + startOfPlaylistInPeriodUs;
  }

  /**
   * Returns the publication state of the given chunk.
   *
   * @param mediaChunk The media chunk for which to evaluate the publication state.
   * @return Whether the media chunk is {@link #CHUNK_PUBLICATION_STATE_PRELOAD a preload chunk},
   *     has been {@link #CHUNK_PUBLICATION_STATE_REMOVED removed} or is definitely {@link
   *     #CHUNK_PUBLICATION_STATE_PUBLISHED published}.
   */
  public @ChunkPublicationState int getChunkPublicationState(HlsMediaChunk mediaChunk) {
    if (mediaChunk.partIndex == C.INDEX_UNSET) {
      // Chunks based on full segments can't be removed and are always published.
      return CHUNK_PUBLICATION_STATE_PUBLISHED;
    }
    Uri playlistUrl = playlistUrls[trackGroup.indexOf(mediaChunk.trackFormat)];
    HlsMediaPlaylist mediaPlaylist =
        checkNotNull(playlistTracker.getPlaylistSnapshot(playlistUrl, /* isForPlayback= */ false));
    int segmentIndexInPlaylist = (int) (mediaChunk.chunkIndex - mediaPlaylist.mediaSequence);
    if (segmentIndexInPlaylist < 0) {
      // The parent segment of the previous chunk is not in the current playlist anymore.
      return CHUNK_PUBLICATION_STATE_PUBLISHED;
    }
    List<HlsMediaPlaylist.Part> partsInCurrentPlaylist =
        segmentIndexInPlaylist < mediaPlaylist.segments.size()
            ? mediaPlaylist.segments.get(segmentIndexInPlaylist).parts
            : mediaPlaylist.trailingParts;
    if (mediaChunk.partIndex >= partsInCurrentPlaylist.size()) {
      // In case the part hinted in the previous playlist has been wrongly assigned to the then full
      // but not yet terminated segment, we discard it regardless whether the URI is different or
      // not. While this is theoretically possible and unspecified, it appears to be an edge case
      // which we can avoid with a small inefficiency of discarding in vain. We could allow this
      // here but, if the chunk is not discarded, it could create unpredictable problems later,
      // because the media sequence in previous.chunkIndex does not match to the actual media
      // sequence in the new playlist.
      return CHUNK_PUBLICATION_STATE_REMOVED;
    }
    HlsMediaPlaylist.Part newPart = partsInCurrentPlaylist.get(mediaChunk.partIndex);
    if (newPart.isPreload) {
      // The playlist did not change and the part in the new playlist is still a preload hint.
      return CHUNK_PUBLICATION_STATE_PRELOAD;
    }
    Uri newUri = Uri.parse(UriUtil.resolve(mediaPlaylist.baseUri, newPart.url));
    return Objects.equals(newUri, mediaChunk.dataSpec.uri)
        ? CHUNK_PUBLICATION_STATE_PUBLISHED
        : CHUNK_PUBLICATION_STATE_REMOVED;
  }

  /**
   * Returns the duration of a newly published part.
   *
   * @param mediaChunk The media chunk of a yet unpublished part for which to evaluate the duration.
   * @return The duration in microseconds.
   */
  public long getPublishedPartDurationUs(HlsMediaChunk mediaChunk) {
    checkState(mediaChunk.partIndex != C.INDEX_UNSET);
    Uri playlistUrl = playlistUrls[trackGroup.indexOf(mediaChunk.trackFormat)];
    HlsMediaPlaylist mediaPlaylist =
        checkNotNull(playlistTracker.getPlaylistSnapshot(playlistUrl, /* isForPlayback= */ false));
    int segmentIndexInPlaylist = (int) (mediaChunk.chunkIndex - mediaPlaylist.mediaSequence);
    if (segmentIndexInPlaylist < 0) {
      // The parent segment of the previous chunk is not in the current playlist anymore.
      return 0;
    }
    List<HlsMediaPlaylist.Part> partsInCurrentPlaylist =
        segmentIndexInPlaylist < mediaPlaylist.segments.size()
            ? mediaPlaylist.segments.get(segmentIndexInPlaylist).parts
            : mediaPlaylist.trailingParts;
    HlsMediaPlaylist.Part part = partsInCurrentPlaylist.get(mediaChunk.partIndex);
    return part.durationUs;
  }

  /**
   * Returns the next chunk to load.
   *
   * <p>If a chunk is available then {@link HlsChunkHolder#chunk} is set. If the end of the stream
   * has been reached then {@link HlsChunkHolder#endOfStream} is set. If a chunk is not available
   * but the end of the stream has not been reached, {@link HlsChunkHolder#playlistUrl} is set to
   * contain the {@link Uri} that refers to the playlist that needs refreshing.
   *
   * @param loadingInfo The {@link LoadingInfo} when loading request is made.
   * @param loadPositionUs The load position in microseconds since the start of the period at which
   *     to provide new samples.
   * @param largestReadPositionUs The largest position up to which samples have been consumed
   *     already.
   * @param queue The queue of buffered {@link HlsMediaChunk}s.
   * @param allowEndOfStream Whether {@link HlsChunkHolder#endOfStream} is allowed to be set for
   *     non-empty media playlists. If {@code false}, the last available chunk is returned instead.
   *     If the media playlist is empty, {@link HlsChunkHolder#endOfStream} is always set.
   * @param out A holder to populate.
   */
  public void getNextChunk(
      LoadingInfo loadingInfo,
      long loadPositionUs,
      long largestReadPositionUs,
      List<HlsMediaChunk> queue,
      boolean allowEndOfStream,
      HlsChunkHolder out) {
    @Nullable HlsMediaChunk previous = queue.isEmpty() ? null : Iterables.getLast(queue);
    int oldTrackIndex = previous == null ? C.INDEX_UNSET : trackGroup.indexOf(previous.trackFormat);
    long playbackPositionUs = loadingInfo.playbackPositionUs;
    long bufferedDurationUs = loadPositionUs - playbackPositionUs;
    long timeToLiveEdgeUs = resolveTimeToLiveEdgeUs(playbackPositionUs);
    if (previous != null && !independentSegments) {
      // Unless segments are known to be independent, switching tracks requires downloading
      // overlapping segments. Hence we subtract the previous segment's duration from the buffered
      // duration.
      // This may affect the live-streaming adaptive track selection logic, when we compare the
      // buffered duration to time-to-live-edge to decide whether to switch. Therefore, we subtract
      // the duration of the last loaded segment from timeToLiveEdgeUs as well.
      long subtractedDurationUs = previous.getDurationUs();
      bufferedDurationUs = max(0, bufferedDurationUs - subtractedDurationUs);
      if (timeToLiveEdgeUs != C.TIME_UNSET) {
        timeToLiveEdgeUs = max(0, timeToLiveEdgeUs - subtractedDurationUs);
      }
    }

    // Select the track.
    MediaChunkIterator[] mediaChunkIterators = createMediaChunkIterators(previous, loadPositionUs);
    trackSelection.updateSelectedTrack(
        playbackPositionUs, bufferedDurationUs, timeToLiveEdgeUs, queue, mediaChunkIterators);
    int selectedTrackIndex = trackSelection.getSelectedIndexInTrackGroup();
    boolean switchingTrack = oldTrackIndex != selectedTrackIndex;
    Uri selectedPlaylistUrl = playlistUrls[selectedTrackIndex];
    if (!playlistTracker.isSnapshotValid(selectedPlaylistUrl)) {
      out.playlistUrl = selectedPlaylistUrl;
      nextChunkStuckOnPlaylistUrl = selectedPlaylistUrl;
      // Retry when playlist is refreshed.
      return;
    }
    @Nullable
    HlsMediaPlaylist playlist =
        playlistTracker.getPlaylistSnapshot(selectedPlaylistUrl, /* isForPlayback= */ true);
    // playlistTracker snapshot is valid (checked by if() above), so playlist must be non-null.
    checkNotNull(playlist);
    independentSegments = playlist.hasIndependentSegments;

    updateLiveEdgeTimeUs(playlist);

    // Select the chunk.
    long startOfPlaylistInPeriodUs = playlist.startTimeUs - playlistTracker.getInitialStartTimeUs();
    Pair<Long, Integer> nextMediaSequenceAndPartIndex =
        getNextMediaSequenceAndPartIndex(
            previous, switchingTrack, playlist, startOfPlaylistInPeriodUs, loadPositionUs);
    long chunkMediaSequence = nextMediaSequenceAndPartIndex.first;
    int partIndex = nextMediaSequenceAndPartIndex.second;
    boolean shouldForceKeepCurrentTrackSelection =
        shouldForceKeepCurrentTrackSelection(
            switchingTrack,
            playlist,
            chunkMediaSequence,
            partIndex,
            previous,
            startOfPlaylistInPeriodUs,
            largestReadPositionUs);
    if (shouldForceKeepCurrentTrackSelection) {
      selectedTrackIndex = oldTrackIndex;
      selectedPlaylistUrl = playlistUrls[selectedTrackIndex];
      playlist =
          playlistTracker.getPlaylistSnapshot(selectedPlaylistUrl, /* isForPlayback= */ true);
      // playlistTracker snapshot is valid (checked by if() above), so playlist must be non-null.
      checkNotNull(playlist);
      startOfPlaylistInPeriodUs = playlist.startTimeUs - playlistTracker.getInitialStartTimeUs();
      // Get the next segment/part without switching tracks.
      Pair<Long, Integer> nextMediaSequenceAndPartIndexWithoutAdapting =
          getNextMediaSequenceAndPartIndex(
              previous,
              /* switchingTrack= */ false,
              playlist,
              startOfPlaylistInPeriodUs,
              loadPositionUs);
      chunkMediaSequence = nextMediaSequenceAndPartIndexWithoutAdapting.first;
      partIndex = nextMediaSequenceAndPartIndexWithoutAdapting.second;
    }

    // If the selected track index changes from another one, we should deactivate the old playlist
    // for playback.
    if (selectedTrackIndex != oldTrackIndex && oldTrackIndex != C.INDEX_UNSET) {
      Uri oldPlaylistUrl = playlistUrls[oldTrackIndex];
      playlistTracker.deactivatePlaylistForPlayback(oldPlaylistUrl);
    }

    if (chunkMediaSequence < playlist.mediaSequence) {
      fatalError = new BehindLiveWindowException();
      return;
    }

    @Nullable
    SegmentBaseHolder segmentBaseHolder =
        getNextSegmentHolder(playlist, chunkMediaSequence, partIndex);
    if (segmentBaseHolder == null) {
      if (!playlist.hasEndTag) {
        // Reload the playlist in case of a live stream.
        out.playlistUrl = selectedPlaylistUrl;
        nextChunkStuckOnPlaylistUrl = selectedPlaylistUrl;
        return;
      } else if (allowEndOfStream || playlist.segments.isEmpty()) {
        out.endOfStream = true;
        return;
      }
      // Use the last segment available in case of a VOD stream.
      segmentBaseHolder =
          new SegmentBaseHolder(
              Iterables.getLast(playlist.segments),
              playlist.mediaSequence + playlist.segments.size() - 1,
              /* partIndex= */ C.INDEX_UNSET);
    }

    // We have a valid media segment, we can discard any playlist errors at this point.
    nextChunkStuckOnPlaylistUrl = null;

    @Nullable CmcdData.Factory cmcdDataFactory = null;
    if (cmcdConfiguration != null) {
      cmcdDataFactory =
          new CmcdData.Factory(cmcdConfiguration, CmcdData.STREAMING_FORMAT_HLS)
              .setTrackSelection(trackSelection)
              .setBufferedDurationUs(max(0, bufferedDurationUs))
              .setPlaybackRate(loadingInfo.playbackSpeed)
              .setIsLive(!playlist.hasEndTag)
              .setDidRebuffer(loadingInfo.rebufferedSince(lastChunkRequestRealtimeMs))
              .setIsBufferEmpty(queue.isEmpty())
              .setChunkDurationUs(segmentBaseHolder.segmentBase.durationUs);
      long nextMediaSequence =
          segmentBaseHolder.partIndex == C.INDEX_UNSET
              ? segmentBaseHolder.mediaSequence + 1
              : segmentBaseHolder.mediaSequence;
      int nextPartIndex =
          segmentBaseHolder.partIndex == C.INDEX_UNSET
              ? C.INDEX_UNSET
              : segmentBaseHolder.partIndex + 1;
      SegmentBaseHolder nextSegmentBaseHolder =
          getNextSegmentHolder(playlist, nextMediaSequence, nextPartIndex);
      if (nextSegmentBaseHolder != null) {
        Uri uri = UriUtil.resolveToUri(playlist.baseUri, segmentBaseHolder.segmentBase.url);
        Uri nextUri = UriUtil.resolveToUri(playlist.baseUri, nextSegmentBaseHolder.segmentBase.url);
        cmcdDataFactory.setNextObjectRequest(UriUtil.getRelativePath(uri, nextUri));

        String nextRangeRequest = nextSegmentBaseHolder.segmentBase.byteRangeOffset + "-";
        if (nextSegmentBaseHolder.segmentBase.byteRangeLength != C.LENGTH_UNSET) {
          nextRangeRequest +=
              (nextSegmentBaseHolder.segmentBase.byteRangeOffset
                  + nextSegmentBaseHolder.segmentBase.byteRangeLength);
        }
        cmcdDataFactory.setNextRangeRequest(nextRangeRequest);
      }
    }
    lastChunkRequestRealtimeMs = SystemClock.elapsedRealtime();

    // Check if the media segment or its initialization segment are fully encrypted.
    @Nullable
    Uri initSegmentKeyUri =
        getFullEncryptionKeyUri(playlist, segmentBaseHolder.segmentBase.initializationSegment);
    out.chunk =
        maybeCreateEncryptionChunkFor(
            initSegmentKeyUri, selectedTrackIndex, /* isInitSegment= */ true, cmcdDataFactory);
    if (out.chunk != null) {
      return;
    }
    @Nullable
    Uri mediaSegmentKeyUri = getFullEncryptionKeyUri(playlist, segmentBaseHolder.segmentBase);
    out.chunk =
        maybeCreateEncryptionChunkFor(
            mediaSegmentKeyUri, selectedTrackIndex, /* isInitSegment= */ false, cmcdDataFactory);
    if (out.chunk != null) {
      return;
    }

    boolean isIndependent = isIndependent(segmentBaseHolder, playlist);
    boolean shouldSpliceIn =
        HlsMediaChunk.shouldSpliceIn(
            previous,
            loadPositionUs,
            selectedPlaylistUrl,
            isIndependent,
            segmentBaseHolder,
            startOfPlaylistInPeriodUs);
    if (shouldSpliceIn && segmentBaseHolder.isPreload) {
      // We don't support discarding spliced-in segments [internal: b/159904763], but preload
      // parts may need to be discarded if they are removed before becoming permanently published.
      // Hence, don't allow this combination and instead wait with loading the next part until it
      // becomes fully available (or the track selection selects another track).
      return;
    }

    out.chunk =
        HlsMediaChunk.createInstance(
            extractorFactory,
            mediaDataSource,
            playlistFormats[selectedTrackIndex],
            startOfPlaylistInPeriodUs,
            playlist,
            segmentBaseHolder,
            selectedPlaylistUrl,
            muxedCaptionFormats,
            trackSelection.getSelectionReason(),
            trackSelection.getSelectionData(),
            isPrimaryTimestampSource,
            timestampAdjusterProvider,
            timestampAdjusterInitializationTimeoutMs,
            previous,
            /* mediaSegmentKey= */ keyCache.get(mediaSegmentKeyUri),
            /* initSegmentKey= */ keyCache.get(initSegmentKeyUri),
            shouldSpliceIn,
            isIndependent,
            playerId,
            cmcdDataFactory);
  }

  private static boolean isIndependent(
      HlsChunkSource.SegmentBaseHolder segmentBaseHolder, HlsMediaPlaylist mediaPlaylist) {
    if (segmentBaseHolder.segmentBase instanceof HlsMediaPlaylist.Part) {
      return ((HlsMediaPlaylist.Part) segmentBaseHolder.segmentBase).isIndependent
          || (segmentBaseHolder.partIndex == 0 && mediaPlaylist.hasIndependentSegments);
    }
    return mediaPlaylist.hasIndependentSegments;
  }

  @Nullable
  private static SegmentBaseHolder getNextSegmentHolder(
      HlsMediaPlaylist mediaPlaylist, long nextMediaSequence, int nextPartIndex) {
    int segmentIndexInPlaylist = (int) (nextMediaSequence - mediaPlaylist.mediaSequence);
    if (segmentIndexInPlaylist == mediaPlaylist.segments.size()) {
      int index = nextPartIndex != C.INDEX_UNSET ? nextPartIndex : 0;
      return index < mediaPlaylist.trailingParts.size()
          ? new SegmentBaseHolder(mediaPlaylist.trailingParts.get(index), nextMediaSequence, index)
          : null;
    }

    Segment mediaSegment = mediaPlaylist.segments.get(segmentIndexInPlaylist);
    if (nextPartIndex == C.INDEX_UNSET) {
      return new SegmentBaseHolder(mediaSegment, nextMediaSequence, /* partIndex= */ C.INDEX_UNSET);
    }

    if (nextPartIndex < mediaSegment.parts.size()) {
      // The requested part is available in the requested segment.
      return new SegmentBaseHolder(
          mediaSegment.parts.get(nextPartIndex), nextMediaSequence, nextPartIndex);
    } else if (segmentIndexInPlaylist + 1 < mediaPlaylist.segments.size()) {
      // The first part of the next segment is requested, but we can use the next full segment.
      return new SegmentBaseHolder(
          mediaPlaylist.segments.get(segmentIndexInPlaylist + 1),
          nextMediaSequence + 1,
          /* partIndex= */ C.INDEX_UNSET);
    } else if (!mediaPlaylist.trailingParts.isEmpty()) {
      // The part index is rolling over to the first trailing part.
      return new SegmentBaseHolder(
          mediaPlaylist.trailingParts.get(0), nextMediaSequence + 1, /* partIndex= */ 0);
    }
    // End of stream.
    return null;
  }

  /**
   * Called when the {@link HlsSampleStreamWrapper} has finished loading a chunk obtained from this
   * source.
   *
   * @param chunk The chunk whose load has been completed.
   */
  public void onChunkLoadCompleted(Chunk chunk) {
    if (chunk instanceof EncryptionKeyChunk) {
      EncryptionKeyChunk encryptionKeyChunk = (EncryptionKeyChunk) chunk;
      scratchSpace = encryptionKeyChunk.getDataHolder();
      keyCache.put(encryptionKeyChunk.dataSpec.uri, checkNotNull(encryptionKeyChunk.getResult()));
    }
  }

  /**
   * Attempts to exclude the track associated with the given chunk. Exclusion will fail if the track
   * is the only non-excluded track in the selection.
   *
   * @param chunk The chunk whose load caused the exclusion attempt.
   * @param exclusionDurationMs The number of milliseconds for which the track selection should be
   *     excluded.
   * @return Whether the exclusion succeeded.
   */
  public boolean maybeExcludeTrack(Chunk chunk, long exclusionDurationMs) {
    return trackSelection.excludeTrack(
        trackSelection.indexOf(trackGroup.indexOf(chunk.trackFormat)), exclusionDurationMs);
  }

  /**
   * Called when a playlist load encounters an error.
   *
   * @param playlistUrl The {@link Uri} of the playlist whose load encountered an error.
   * @param exclusionDurationMs The duration for which the playlist should be excluded. Or {@link
   *     C#TIME_UNSET} if the playlist should not be excluded.
   * @return Whether the playlist will be excluded from future loads.
   */
  public boolean onPlaylistError(Uri playlistUrl, long exclusionDurationMs) {
    int trackGroupIndex = C.INDEX_UNSET;
    for (int i = 0; i < playlistUrls.length; i++) {
      if (playlistUrls[i].equals(playlistUrl)) {
        trackGroupIndex = i;
        break;
      }
    }
    if (trackGroupIndex == C.INDEX_UNSET) {
      return true;
    }
    int trackSelectionIndex = trackSelection.indexOf(trackGroupIndex);
    if (trackSelectionIndex == C.INDEX_UNSET) {
      return true;
    }
    lastPlaylistErrorUrl = playlistUrl;
    return exclusionDurationMs != C.TIME_UNSET
        && trackSelection.excludeTrack(trackSelectionIndex, exclusionDurationMs)
        && playlistTracker.excludeMediaPlaylist(playlistUrl, exclusionDurationMs);
  }

  /**
   * Returns an array of {@link MediaChunkIterator}s for upcoming media chunks.
   *
   * @param previous The previous media chunk. May be null.
   * @param loadPositionUs The position at which the iterators will start.
   * @return Array of {@link MediaChunkIterator}s for each track.
   */
  public MediaChunkIterator[] createMediaChunkIterators(
      @Nullable HlsMediaChunk previous, long loadPositionUs) {
    int oldTrackIndex = previous == null ? C.INDEX_UNSET : trackGroup.indexOf(previous.trackFormat);
    MediaChunkIterator[] chunkIterators = new MediaChunkIterator[trackSelection.length()];
    for (int i = 0; i < chunkIterators.length; i++) {
      int trackIndex = trackSelection.getIndexInTrackGroup(i);
      Uri playlistUrl = playlistUrls[trackIndex];
      if (!playlistTracker.isSnapshotValid(playlistUrl)) {
        chunkIterators[i] = MediaChunkIterator.EMPTY;
        continue;
      }
      @Nullable
      HlsMediaPlaylist playlist =
          playlistTracker.getPlaylistSnapshot(playlistUrl, /* isForPlayback= */ false);
      // Playlist snapshot is valid (checked by if() above) so playlist must be non-null.
      checkNotNull(playlist);
      long startOfPlaylistInPeriodUs =
          playlist.startTimeUs - playlistTracker.getInitialStartTimeUs();
      boolean switchingTrack = trackIndex != oldTrackIndex;
      Pair<Long, Integer> chunkMediaSequenceAndPartIndex =
          getNextMediaSequenceAndPartIndex(
              previous, switchingTrack, playlist, startOfPlaylistInPeriodUs, loadPositionUs);
      long chunkMediaSequence = chunkMediaSequenceAndPartIndex.first;
      int partIndex = chunkMediaSequenceAndPartIndex.second;
      chunkIterators[i] =
          new HlsMediaPlaylistSegmentIterator(
              playlist.baseUri,
              startOfPlaylistInPeriodUs,
              getSegmentBaseList(playlist, chunkMediaSequence, partIndex));
    }
    return chunkIterators;
  }

  /**
   * Evaluates whether {@link MediaChunk MediaChunks} should be removed from the back of the queue.
   *
   * <p>Removing {@link MediaChunk MediaChunks} from the back of the queue can be useful if they
   * could be replaced with chunks of a significantly higher quality (e.g. because the available
   * bandwidth has substantially increased).
   *
   * <p>Will only be called if no {@link MediaChunk} in the queue is currently loading.
   *
   * @param playbackPositionUs The current playback position, in microseconds.
   * @param queue The queue of buffered {@link MediaChunk MediaChunks}.
   * @return The preferred queue size.
   */
  public int getPreferredQueueSize(long playbackPositionUs, List<? extends MediaChunk> queue) {
    if (fatalError != null || trackSelection.length() < 2) {
      return queue.size();
    }
    return trackSelection.evaluateQueueSize(playbackPositionUs, queue);
  }

  /**
   * Returns whether an ongoing load of a chunk should be canceled.
   *
   * @param playbackPositionUs The current playback position, in microseconds.
   * @param loadingChunk The currently loading {@link Chunk}.
   * @param queue The queue of buffered {@link MediaChunk MediaChunks}.
   * @return Whether the ongoing load of {@code loadingChunk} should be canceled.
   */
  public boolean shouldCancelLoad(
      long playbackPositionUs, Chunk loadingChunk, List<? extends MediaChunk> queue) {
    if (fatalError != null) {
      return false;
    }
    return trackSelection.shouldCancelChunkLoad(playbackPositionUs, loadingChunk, queue);
  }

  // Package methods.

  /**
   * Returns a list with all segment bases in the playlist starting from {@code mediaSequence} and
   * {@code partIndex} in the given playlist. The list may be empty if the starting point is not in
   * the playlist.
   */
  @VisibleForTesting
  /* package */ static List<HlsMediaPlaylist.SegmentBase> getSegmentBaseList(
      HlsMediaPlaylist playlist, long mediaSequence, int partIndex) {
    int firstSegmentIndexInPlaylist = (int) (mediaSequence - playlist.mediaSequence);
    if (firstSegmentIndexInPlaylist < 0 || playlist.segments.size() < firstSegmentIndexInPlaylist) {
      // The first media sequence is not in the playlist.
      return ImmutableList.of();
    }
    List<HlsMediaPlaylist.SegmentBase> segmentBases = new ArrayList<>();
    if (firstSegmentIndexInPlaylist < playlist.segments.size()) {
      if (partIndex != C.INDEX_UNSET) {
        // The iterator starts with a part that belongs to a segment.
        Segment firstSegment = playlist.segments.get(firstSegmentIndexInPlaylist);
        if (partIndex == 0) {
          // Use the full segment instead of the first part.
          segmentBases.add(firstSegment);
        } else if (partIndex < firstSegment.parts.size()) {
          // Add the parts from the first requested segment.
          segmentBases.addAll(firstSegment.parts.subList(partIndex, firstSegment.parts.size()));
        }
        firstSegmentIndexInPlaylist++;
      }
      partIndex = 0;
      // Add all remaining segments.
      segmentBases.addAll(
          playlist.segments.subList(firstSegmentIndexInPlaylist, playlist.segments.size()));
    }

    if (playlist.partTargetDurationUs != C.TIME_UNSET) {
      // That's a low latency playlist.
      partIndex = partIndex == C.INDEX_UNSET ? 0 : partIndex;
      if (partIndex < playlist.trailingParts.size()) {
        segmentBases.addAll(
            playlist.trailingParts.subList(partIndex, playlist.trailingParts.size()));
      }
    }
    return Collections.unmodifiableList(segmentBases);
  }

  /** Returns whether this chunk source obtains chunks for the playlist with the given url. */
  public boolean obtainsChunksForPlaylist(Uri playlistUrl) {
    return Util.contains(playlistUrls, playlistUrl);
  }

  // Private methods.

  /**
   * Returns the media sequence number and part index to load next in the {@code mediaPlaylist}.
   *
   * @param previous The last (at least partially) loaded segment.
   * @param switchingTrack Whether the segment to load is not preceded by a segment in the same
   *     track.
   * @param mediaPlaylist The media playlist to which the segment to load belongs.
   * @param startOfPlaylistInPeriodUs The start of {@code mediaPlaylist} relative to the period
   *     start in microseconds.
   * @param loadPositionUs The current load position relative to the period start in microseconds.
   * @return The media sequence and part index to load.
   */
  private Pair<Long, Integer> getNextMediaSequenceAndPartIndex(
      @Nullable HlsMediaChunk previous,
      boolean switchingTrack,
      HlsMediaPlaylist mediaPlaylist,
      long startOfPlaylistInPeriodUs,
      long loadPositionUs) {
    if (previous == null || switchingTrack) {
      long endOfPlaylistInPeriodUs = startOfPlaylistInPeriodUs + mediaPlaylist.durationUs;
      long targetPositionInPeriodUs =
          (previous == null || independentSegments) ? loadPositionUs : previous.startTimeUs;
      if (!mediaPlaylist.hasEndTag && targetPositionInPeriodUs >= endOfPlaylistInPeriodUs) {
        // If the playlist is too old to contain the chunk, we need to refresh it.
        return new Pair<>(
            mediaPlaylist.mediaSequence + mediaPlaylist.segments.size(),
            /* partIndex */ C.INDEX_UNSET);
      }
      long targetPositionInPlaylistUs = targetPositionInPeriodUs - startOfPlaylistInPeriodUs;
      int segmentIndexInPlaylist =
          Util.binarySearchFloor(
              mediaPlaylist.segments,
              /* value= */ targetPositionInPlaylistUs,
              /* inclusive= */ true,
              /* stayInBounds= */ !playlistTracker.isLive() || previous == null);
      long mediaSequence = segmentIndexInPlaylist + mediaPlaylist.mediaSequence;
      int partIndex = C.INDEX_UNSET;
      if (segmentIndexInPlaylist >= 0) {
        // In case we are inside the live window, we try to pick a part if available.
        Segment segment = mediaPlaylist.segments.get(segmentIndexInPlaylist);
        List<HlsMediaPlaylist.Part> parts =
            targetPositionInPlaylistUs < segment.relativeStartTimeUs + segment.durationUs
                ? segment.parts
                : mediaPlaylist.trailingParts;
        for (int i = 0; i < parts.size(); i++) {
          HlsMediaPlaylist.Part part = parts.get(i);
          if (targetPositionInPlaylistUs < part.relativeStartTimeUs + part.durationUs) {
            if (part.isIndependent) {
              partIndex = i;
              // Increase media sequence by one if the part is a trailing part.
              mediaSequence += parts == mediaPlaylist.trailingParts ? 1 : 0;
            }
            break;
          }
        }
      }
      return new Pair<>(mediaSequence, partIndex);
    }
    // If loading has not completed, we return the previous chunk again.
    return (previous.isLoadCompleted()
        ? new Pair<>(
            previous.partIndex == C.INDEX_UNSET
                ? previous.getNextChunkIndex()
                : previous.chunkIndex,
            previous.partIndex == C.INDEX_UNSET ? C.INDEX_UNSET : previous.partIndex + 1)
        : new Pair<>(previous.chunkIndex, previous.partIndex));
  }

  private static boolean shouldForceKeepCurrentTrackSelection(
      boolean switchingTrack,
      HlsMediaPlaylist playlist,
      long mediaSequence,
      int part,
      @Nullable HlsMediaChunk previousChunk,
      long startOfPlaylistInPeriodUs,
      long largestReadPositionUs) {
    if (!switchingTrack) {
      // We are already keeping the current selection.
      return false;
    }
    if (previousChunk == null) {
      // This is the first chunk, we can select any track.
      return false;
    }
    if (mediaSequence < playlist.mediaSequence) {
      // Falling behind the live edge. We should keep the current selection to rescue playback.
      return true;
    }
    // Find segment/part data in playlist.
    @Nullable
    SegmentBaseHolder segmentBaseHolder = getNextSegmentHolder(playlist, mediaSequence, part);
    if (segmentBaseHolder == null) {
      // Can't even resolve segment or part data, will be handled elsewhere.
      return false;
    }
    long startTimeInPeriodUs =
        startOfPlaylistInPeriodUs + segmentBaseHolder.segmentBase.relativeStartTimeUs;
    if (startTimeInPeriodUs < largestReadPositionUs) {
      // Switching to this track will result a stuck playback because we already read past its start
      // position and we can no longer append or splice in the samples successfully.
      return true;
    }
    // No reason to ignore the new track selection.
    return false;
  }

  private long resolveTimeToLiveEdgeUs(long playbackPositionUs) {
    final boolean resolveTimeToLiveEdgePossible = liveEdgeInPeriodTimeUs != C.TIME_UNSET;
    return resolveTimeToLiveEdgePossible
        ? liveEdgeInPeriodTimeUs - playbackPositionUs
        : C.TIME_UNSET;
  }

  private void updateLiveEdgeTimeUs(HlsMediaPlaylist mediaPlaylist) {
    liveEdgeInPeriodTimeUs =
        mediaPlaylist.hasEndTag
            ? C.TIME_UNSET
            : (mediaPlaylist.getEndTimeUs() - playlistTracker.getInitialStartTimeUs());
  }

  @Nullable
  private Chunk maybeCreateEncryptionChunkFor(
      @Nullable Uri keyUri,
      int selectedTrackIndex,
      boolean isInitSegment,
      @Nullable CmcdData.Factory cmcdDataFactory) {
    if (keyUri == null) {
      return null;
    }

    @Nullable byte[] encryptionKey = keyCache.remove(keyUri);
    if (encryptionKey != null) {
      // The key was present in the key cache. We re-insert it to prevent it from being evicted by
      // the following key addition. Note that removal of the key is necessary to affect the
      // eviction order.
      keyCache.put(keyUri, encryptionKey);
      return null;
    }

    DataSpec dataSpec =
        new DataSpec.Builder().setUri(keyUri).setFlags(DataSpec.FLAG_ALLOW_GZIP).build();
    if (cmcdDataFactory != null) {
      if (isInitSegment) {
        cmcdDataFactory.setObjectType(CmcdData.OBJECT_TYPE_INIT_SEGMENT);
      }
      CmcdData cmcdData = cmcdDataFactory.createCmcdData();
      dataSpec = cmcdData.addToDataSpec(dataSpec);
    }

    return new EncryptionKeyChunk(
        encryptionDataSource,
        dataSpec,
        playlistFormats[selectedTrackIndex],
        trackSelection.getSelectionReason(),
        trackSelection.getSelectionData(),
        scratchSpace);
  }

  @Nullable
  private static Uri getFullEncryptionKeyUri(
      HlsMediaPlaylist playlist, @Nullable HlsMediaPlaylist.SegmentBase segmentBase) {
    if (segmentBase == null || segmentBase.fullSegmentEncryptionKeyUri == null) {
      return null;
    }
    return UriUtil.resolveToUri(playlist.baseUri, segmentBase.fullSegmentEncryptionKeyUri);
  }

  private void deactivatePlaylistForSelectedTrack() {
    int selectedTrackIndex = this.trackSelection.getSelectedIndexInTrackGroup();
    playlistTracker.deactivatePlaylistForPlayback(playlistUrls[selectedTrackIndex]);
  }

  // Package classes.

  /* package */ static final class SegmentBaseHolder {

    public final HlsMediaPlaylist.SegmentBase segmentBase;
    public final long mediaSequence;
    public final int partIndex;
    public final boolean isPreload;

    /** Creates a new instance. */
    public SegmentBaseHolder(
        HlsMediaPlaylist.SegmentBase segmentBase, long mediaSequence, int partIndex) {
      this.segmentBase = segmentBase;
      this.mediaSequence = mediaSequence;
      this.partIndex = partIndex;
      this.isPreload =
          segmentBase instanceof HlsMediaPlaylist.Part
              && ((HlsMediaPlaylist.Part) segmentBase).isPreload;
    }
  }

  // Private classes.

  /** A {@link ExoTrackSelection} to use for initialization. */
  private static final class InitializationTrackSelection extends BaseTrackSelection {

    private int selectedIndex;

    public InitializationTrackSelection(TrackGroup group, int[] tracks) {
      super(group, tracks);
      // The initially selected index corresponds to the first EXT-X-STREAMINF tag in the
      // multivariant playlist.
      selectedIndex = indexOf(group.getFormat(tracks[0]));
    }

    @Override
    public void updateSelectedTrack(
        long playbackPositionUs,
        long bufferedDurationUs,
        long availableDurationUs,
        List<? extends MediaChunk> queue,
        MediaChunkIterator[] mediaChunkIterators) {
      long nowMs = SystemClock.elapsedRealtime();
      if (!isTrackExcluded(selectedIndex, nowMs)) {
        return;
      }
      // Try from lowest bitrate to highest.
      for (int i = length - 1; i >= 0; i--) {
        if (!isTrackExcluded(i, nowMs)) {
          selectedIndex = i;
          return;
        }
      }
      // Should never happen.
      throw new IllegalStateException();
    }

    @Override
    public int getSelectedIndex() {
      return selectedIndex;
    }

    @Override
    public @C.SelectionReason int getSelectionReason() {
      return C.SELECTION_REASON_UNKNOWN;
    }

    @Override
    @Nullable
    public Object getSelectionData() {
      return null;
    }
  }

  private static final class EncryptionKeyChunk extends DataChunk {

    private byte @MonotonicNonNull [] result;

    public EncryptionKeyChunk(
        DataSource dataSource,
        DataSpec dataSpec,
        Format trackFormat,
        @C.SelectionReason int trackSelectionReason,
        @Nullable Object trackSelectionData,
        byte[] scratchSpace) {
      super(
          dataSource,
          dataSpec,
          C.DATA_TYPE_DRM,
          trackFormat,
          trackSelectionReason,
          trackSelectionData,
          scratchSpace);
    }

    @Override
    protected void consume(byte[] data, int limit) {
      result = Arrays.copyOf(data, limit);
    }

    /** Return the result of this chunk, or null if loading is not complete. */
    @Nullable
    public byte[] getResult() {
      return result;
    }
  }

  @VisibleForTesting
  /* package */ static final class HlsMediaPlaylistSegmentIterator extends BaseMediaChunkIterator {

    private final List<HlsMediaPlaylist.SegmentBase> segmentBases;
    private final long startOfPlaylistInPeriodUs;
    private final String playlistBaseUri;

    /**
     * Creates an iterator instance wrapping a list of {@link HlsMediaPlaylist.SegmentBase}.
     *
     * @param playlistBaseUri The base URI of the {@link HlsMediaPlaylist}.
     * @param startOfPlaylistInPeriodUs The start time of the playlist in the period, in
     *     microseconds.
     * @param segmentBases The list of {@link HlsMediaPlaylist.SegmentBase segment bases} to wrap.
     */
    public HlsMediaPlaylistSegmentIterator(
        String playlistBaseUri,
        long startOfPlaylistInPeriodUs,
        List<HlsMediaPlaylist.SegmentBase> segmentBases) {
      super(/* fromIndex= */ 0, segmentBases.size() - 1);
      this.playlistBaseUri = playlistBaseUri;
      this.startOfPlaylistInPeriodUs = startOfPlaylistInPeriodUs;
      this.segmentBases = segmentBases;
    }

    @Override
    public DataSpec getDataSpec() {
      checkInBounds();
      HlsMediaPlaylist.SegmentBase segmentBase = segmentBases.get((int) getCurrentIndex());
      Uri chunkUri = UriUtil.resolveToUri(playlistBaseUri, segmentBase.url);
      return new DataSpec(chunkUri, segmentBase.byteRangeOffset, segmentBase.byteRangeLength);
    }

    @Override
    public long getChunkStartTimeUs() {
      checkInBounds();
      return startOfPlaylistInPeriodUs
          + segmentBases.get((int) getCurrentIndex()).relativeStartTimeUs;
    }

    @Override
    public long getChunkEndTimeUs() {
      checkInBounds();
      HlsMediaPlaylist.SegmentBase segmentBase = segmentBases.get((int) getCurrentIndex());
      long segmentStartTimeInPeriodUs = startOfPlaylistInPeriodUs + segmentBase.relativeStartTimeUs;
      return segmentStartTimeInPeriodUs + segmentBase.durationUs;
    }
  }
}
