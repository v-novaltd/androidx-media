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
package androidx.media3.exoplayer.source;

import static androidx.media3.common.util.Assertions.checkNotNull;
import static com.google.common.base.MoreObjects.firstNonNull;

import android.net.Uri;
import androidx.annotation.Nullable;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.Timeline;
import androidx.media3.common.util.Consumer;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.text.TextRenderer;
import androidx.media3.exoplayer.upstream.Allocator;
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy;
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy;
import androidx.media3.exoplayer.util.ReleasableExecutor;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.concurrent.Executor;

/**
 * @deprecated The only use for this class is subtitle playback, but it is only compatible with
 *     legacy subtitle decoding, which is not supported by default. Users of this class almost
 *     certainly need to {@linkplain TextRenderer#experimentalSetLegacyDecodingEnabled(boolean)
 *     enable legacy subtitle decoding} on their {@link ExoPlayer} instance in order to avoid
 *     playback errors when trying to play subtitles.
 */
@UnstableApi
@Deprecated
public final class SingleSampleMediaSource extends BaseMediaSource {

  /** Factory for {@link SingleSampleMediaSource}. */
  public static final class Factory {

    private final DataSource.Factory dataSourceFactory;

    private LoadErrorHandlingPolicy loadErrorHandlingPolicy;
    private boolean treatLoadErrorsAsEndOfStream;
    @Nullable private Object tag;
    @Nullable private String trackId;
    @Nullable private Supplier<ReleasableExecutor> downloadExecutorSupplier;

    /**
     * Creates a factory for {@link SingleSampleMediaSource}s.
     *
     * @param dataSourceFactory The factory from which the {@link DataSource} to read the media will
     *     be obtained.
     */
    public Factory(DataSource.Factory dataSourceFactory) {
      this.dataSourceFactory = checkNotNull(dataSourceFactory);
      loadErrorHandlingPolicy = new DefaultLoadErrorHandlingPolicy();
      treatLoadErrorsAsEndOfStream = true;
    }

    /**
     * Sets a tag for the media source which will be published in the {@link Timeline} of the source
     * as {@link MediaItem.LocalConfiguration#tag Window#mediaItem.localConfiguration.tag}.
     *
     * @param tag A tag for the media source.
     * @return This factory, for convenience.
     */
    @CanIgnoreReturnValue
    public Factory setTag(@Nullable Object tag) {
      this.tag = tag;
      return this;
    }

    /**
     * @deprecated Use {@link MediaItem.SubtitleConfiguration.Builder#setId(String)} instead (on the
     *     {@link MediaItem.SubtitleConfiguration} passed to {@link
     *     #createMediaSource(MediaItem.SubtitleConfiguration, long)}). {@code trackId} will only be
     *     used if {@link MediaItem.SubtitleConfiguration#id} is {@code null}.
     */
    @CanIgnoreReturnValue
    @Deprecated
    public Factory setTrackId(@Nullable String trackId) {
      this.trackId = trackId;
      return this;
    }

    /**
     * Sets the {@link LoadErrorHandlingPolicy}. The default value is created by calling {@link
     * DefaultLoadErrorHandlingPolicy#DefaultLoadErrorHandlingPolicy()}.
     *
     * @param loadErrorHandlingPolicy A {@link LoadErrorHandlingPolicy}.
     * @return This factory, for convenience.
     */
    @CanIgnoreReturnValue
    public Factory setLoadErrorHandlingPolicy(
        @Nullable LoadErrorHandlingPolicy loadErrorHandlingPolicy) {
      this.loadErrorHandlingPolicy =
          loadErrorHandlingPolicy != null
              ? loadErrorHandlingPolicy
              : new DefaultLoadErrorHandlingPolicy();
      return this;
    }

    /**
     * Sets whether load errors will be treated as end-of-stream signal (load errors will not be
     * propagated). The default value is true.
     *
     * @param treatLoadErrorsAsEndOfStream If true, load errors will not be propagated by sample
     *     streams, treating them as ended instead. If false, load errors will be propagated
     *     normally by {@link SampleStream#maybeThrowError()}.
     * @return This factory, for convenience.
     */
    @CanIgnoreReturnValue
    public Factory setTreatLoadErrorsAsEndOfStream(boolean treatLoadErrorsAsEndOfStream) {
      this.treatLoadErrorsAsEndOfStream = treatLoadErrorsAsEndOfStream;
      return this;
    }

    /**
     * Sets a supplier for an {@link Executor} that is used for loading the media.
     *
     * @param downloadExecutor A {@link Supplier} that provides an externally managed {@link
     *     Executor} for downloading and extraction.
     * @param downloadExecutorReleaser A callback triggered once a load task is finished and a
     *     supplied executor is no longer required.
     * @return This factory, for convenience.
     */
    @CanIgnoreReturnValue
    public <T extends Executor> Factory setDownloadExecutor(
        Supplier<T> downloadExecutor, Consumer<T> downloadExecutorReleaser) {
      this.downloadExecutorSupplier =
          () -> ReleasableExecutor.from(downloadExecutor.get(), downloadExecutorReleaser);
      return this;
    }

    /**
     * Returns a new {@link SingleSampleMediaSource} using the current parameters.
     *
     * @param subtitleConfiguration The {@link MediaItem.SubtitleConfiguration}.
     * @param durationUs The duration of the media stream in microseconds.
     * @return The new {@link SingleSampleMediaSource}.
     */
    public SingleSampleMediaSource createMediaSource(
        MediaItem.SubtitleConfiguration subtitleConfiguration, long durationUs) {
      return new SingleSampleMediaSource(
          trackId,
          subtitleConfiguration,
          dataSourceFactory,
          durationUs,
          loadErrorHandlingPolicy,
          treatLoadErrorsAsEndOfStream,
          tag,
          downloadExecutorSupplier);
    }
  }

  private final DataSpec dataSpec;
  private final DataSource.Factory dataSourceFactory;
  private final Format format;
  private final long durationUs;
  private final LoadErrorHandlingPolicy loadErrorHandlingPolicy;
  private final boolean treatLoadErrorsAsEndOfStream;
  private final Timeline timeline;
  private final MediaItem mediaItem;
  @Nullable private final Supplier<ReleasableExecutor> downloadExecutorSupplier;

  @Nullable private TransferListener transferListener;

  private SingleSampleMediaSource(
      @Nullable String trackId,
      MediaItem.SubtitleConfiguration subtitleConfiguration,
      DataSource.Factory dataSourceFactory,
      long durationUs,
      LoadErrorHandlingPolicy loadErrorHandlingPolicy,
      boolean treatLoadErrorsAsEndOfStream,
      @Nullable Object tag,
      @Nullable Supplier<ReleasableExecutor> downloadExecutorSupplier) {
    this.dataSourceFactory = dataSourceFactory;
    this.durationUs = durationUs;
    this.loadErrorHandlingPolicy = loadErrorHandlingPolicy;
    this.treatLoadErrorsAsEndOfStream = treatLoadErrorsAsEndOfStream;
    this.mediaItem =
        new MediaItem.Builder()
            .setUri(Uri.EMPTY)
            .setMediaId(subtitleConfiguration.uri.toString())
            .setSubtitleConfigurations(ImmutableList.of(subtitleConfiguration))
            .setTag(tag)
            .build();
    this.format =
        new Format.Builder()
            .setSampleMimeType(firstNonNull(subtitleConfiguration.mimeType, MimeTypes.TEXT_UNKNOWN))
            .setLanguage(subtitleConfiguration.language)
            .setSelectionFlags(subtitleConfiguration.selectionFlags)
            .setRoleFlags(subtitleConfiguration.roleFlags)
            .setLabel(subtitleConfiguration.label)
            .setId(subtitleConfiguration.id != null ? subtitleConfiguration.id : trackId)
            .build();
    this.dataSpec =
        new DataSpec.Builder()
            .setUri(subtitleConfiguration.uri)
            .setFlags(DataSpec.FLAG_ALLOW_GZIP)
            .build();
    this.timeline =
        new SinglePeriodTimeline(
            durationUs,
            /* isSeekable= */ true,
            /* isDynamic= */ false,
            /* useLiveConfiguration= */ false,
            /* manifest= */ null,
            mediaItem);
    this.downloadExecutorSupplier = downloadExecutorSupplier;
  }

  // MediaSource implementation.

  @Override
  public MediaItem getMediaItem() {
    return mediaItem;
  }

  @Override
  protected void prepareSourceInternal(@Nullable TransferListener mediaTransferListener) {
    transferListener = mediaTransferListener;
    refreshSourceInfo(timeline);
  }

  @Override
  public void maybeThrowSourceInfoRefreshError() {
    // Do nothing.
  }

  @Override
  public MediaPeriod createPeriod(MediaPeriodId id, Allocator allocator, long startPositionUs) {
    return new SingleSampleMediaPeriod(
        dataSpec,
        dataSourceFactory,
        transferListener,
        format,
        durationUs,
        loadErrorHandlingPolicy,
        createEventDispatcher(id),
        treatLoadErrorsAsEndOfStream,
        downloadExecutorSupplier != null ? downloadExecutorSupplier.get() : null);
  }

  @Override
  public void releasePeriod(MediaPeriod mediaPeriod) {
    ((SingleSampleMediaPeriod) mediaPeriod).release();
  }

  @Override
  protected void releaseSourceInternal() {
    // Do nothing.
  }
}
