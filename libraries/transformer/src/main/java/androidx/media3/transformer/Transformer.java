/*
 * Copyright 2021 The Android Open Source Project
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

package androidx.media3.transformer;

import static android.os.Build.VERSION.SDK_INT;
import static androidx.media3.common.util.Assertions.checkArgument;
import static androidx.media3.common.util.Assertions.checkNotNull;
import static androidx.media3.common.util.Assertions.checkState;
import static androidx.media3.common.util.Util.isRunningOnEmulator;
import static androidx.media3.extractor.AacUtil.AAC_LC_AUDIO_SAMPLE_COUNT;
import static androidx.media3.transformer.ExportException.ERROR_CODE_MUXING_APPEND;
import static androidx.media3.transformer.ExportResult.OPTIMIZATION_ABANDONED_KEYFRAME_PLACEMENT_OPTIMAL_FOR_TRIM;
import static androidx.media3.transformer.ExportResult.OPTIMIZATION_ABANDONED_OTHER;
import static androidx.media3.transformer.ExportResult.OPTIMIZATION_ABANDONED_TRIM_AND_TRANSCODING_TRANSFORMATION_REQUESTED;
import static androidx.media3.transformer.ExportResult.OPTIMIZATION_FAILED_EXTRACTION_FAILED;
import static androidx.media3.transformer.ExportResult.OPTIMIZATION_FAILED_FORMAT_MISMATCH;
import static androidx.media3.transformer.TransformerUtil.maybeSetMuxerWrapperAdditionalRotationDegrees;
import static androidx.media3.transformer.TransformerUtil.shouldTranscodeAudio;
import static androidx.media3.transformer.TransformerUtil.shouldTranscodeVideo;
import static androidx.media3.transformer.TransmuxTranscodeHelper.buildUponCompositionForTrimOptimization;
import static java.lang.Math.round;
import static java.lang.annotation.ElementType.TYPE_USE;

import android.content.Context;
import android.media.metrics.LogSessionId;
import android.os.Looper;
import androidx.annotation.IntDef;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.media3.common.C;
import androidx.media3.common.DebugViewProvider;
import androidx.media3.common.Effect;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaLibraryInfo;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.VideoFrameProcessor;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.audio.ChannelMixingAudioProcessor;
import androidx.media3.common.audio.ToInt16PcmAudioProcessor;
import androidx.media3.common.util.Clock;
import androidx.media3.common.util.HandlerWrapper;
import androidx.media3.common.util.ListenerSet;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.effect.DebugTraceUtil;
import androidx.media3.effect.DefaultVideoFrameProcessor;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.muxer.Muxer;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.io.File;
import java.io.IOException;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/**
 * A transformer to export media inputs.
 *
 * <p>The same Transformer instance can be used to export multiple inputs (sequentially, not
 * concurrently).
 *
 * <p>Transformer instances must be accessed from a single application thread. For the vast majority
 * of cases this should be the application's main thread. The thread on which a Transformer instance
 * must be accessed can be explicitly specified by passing a {@link Looper} when creating the
 * transformer. If no Looper is specified, then the Looper of the thread that the {@link
 * Transformer.Builder} is created on is used, or if that thread does not have a Looper, the Looper
 * of the application's main thread is used. In all cases the Looper of the thread from which the
 * transformer must be accessed can be queried using {@link #getApplicationLooper()}.
 */
@UnstableApi
public final class Transformer {

  static {
    MediaLibraryInfo.registerModule("media3.transformer");
  }

  /** A builder for {@link Transformer} instances. */
  public static final class Builder {

    private static final ImmutableList<Integer> ALL_ROTATION_DEGREES =
        ImmutableList.of(0, 90, 180, 270);

    // Mandatory field.
    private final Context context;

    // Optional fields.
    private @MonotonicNonNull String audioMimeType;
    private @MonotonicNonNull String videoMimeType;
    private @MonotonicNonNull TransformationRequest transformationRequest;
    private final ImmutableList<AudioProcessor> audioProcessors;
    private final ImmutableList<Effect> videoEffects;
    private boolean removeAudio;
    private boolean removeVideo;
    private boolean trimOptimizationEnabled;
    private boolean mp4EditListTrimEnabled;
    private ImmutableList<Integer> allowedEncodingRotationDegrees;
    private boolean fileStartsOnVideoFrameEnabled;
    private boolean usePlatformDiagnostics;
    private long maxDelayBetweenMuxerSamplesMs;
    private int maxFramesInEncoder;
    private ListenerSet<Transformer.Listener> listeners;
    private AssetLoader.@MonotonicNonNull Factory assetLoaderFactory;
    private AudioMixer.Factory audioMixerFactory;
    private VideoFrameProcessor.Factory videoFrameProcessorFactory;
    private Codec.EncoderFactory encoderFactory;
    private Muxer.Factory muxerFactory;
    private Looper looper;
    private DebugViewProvider debugViewProvider;
    private Clock clock;
    private EditingMetricsCollector.MetricsReporter.@MonotonicNonNull Factory
        metricsReporterFactory;

    /**
     * Creates a builder with default values.
     *
     * @param context The {@link Context}.
     */
    public Builder(Context context) {
      this.context = context.getApplicationContext();
      maxDelayBetweenMuxerSamplesMs = DEFAULT_MAX_DELAY_BETWEEN_MUXER_SAMPLES_MS;
      maxFramesInEncoder = C.INDEX_UNSET;
      audioProcessors = ImmutableList.of();
      videoEffects = ImmutableList.of();
      audioMixerFactory = new DefaultAudioMixer.Factory();
      videoFrameProcessorFactory = new DefaultVideoFrameProcessor.Factory.Builder().build();
      encoderFactory = new DefaultEncoderFactory.Builder(this.context).build();
      muxerFactory = new DefaultMuxer.Factory();
      looper = Util.getCurrentOrMainLooper();
      debugViewProvider = DebugViewProvider.NONE;
      clock = Clock.DEFAULT;
      listeners = new ListenerSet<>(looper, clock, (listener, flags) -> {});
      if (SDK_INT >= 35) {
        usePlatformDiagnostics = true;
        metricsReporterFactory =
            new EditingMetricsCollector.DefaultMetricsReporter.Factory(context);
      }
      allowedEncodingRotationDegrees = ALL_ROTATION_DEGREES;
    }

    /** Creates a builder with the values of the provided {@link Transformer}. */
    private Builder(Transformer transformer) {
      this.context = transformer.context;
      this.audioMimeType = transformer.transformationRequest.audioMimeType;
      this.videoMimeType = transformer.transformationRequest.videoMimeType;
      this.transformationRequest = transformer.transformationRequest;
      this.audioProcessors = transformer.audioProcessors;
      this.videoEffects = transformer.videoEffects;
      this.removeAudio = transformer.removeAudio;
      this.removeVideo = transformer.removeVideo;
      this.trimOptimizationEnabled = transformer.trimOptimizationEnabled;
      this.mp4EditListTrimEnabled = transformer.mp4EditListTrimEnabled;
      this.allowedEncodingRotationDegrees = transformer.allowedEncodingRotationDegrees;
      this.fileStartsOnVideoFrameEnabled = transformer.fileStartsOnVideoFrameEnabled;
      this.usePlatformDiagnostics = transformer.usePlatformDiagnostics;
      this.maxDelayBetweenMuxerSamplesMs = transformer.maxDelayBetweenMuxerSamplesMs;
      this.maxFramesInEncoder = transformer.maxFramesInEncoder;
      this.listeners = transformer.listeners;
      this.assetLoaderFactory = transformer.assetLoaderFactory;
      this.audioMixerFactory = transformer.audioMixerFactory;
      this.videoFrameProcessorFactory = transformer.videoFrameProcessorFactory;
      this.encoderFactory = transformer.encoderFactory;
      this.muxerFactory = transformer.muxerFactory;
      this.looper = transformer.looper;
      this.debugViewProvider = transformer.debugViewProvider;
      this.clock = transformer.clock;
      this.metricsReporterFactory = transformer.metricsReporterFactory;
    }

    /**
     * Sets the audio {@linkplain MimeTypes MIME type} of the output.
     *
     * <p>If no audio MIME type is passed, the output audio MIME type is the same as the first
     * {@link MediaItem} in the {@link Composition}.
     *
     * <p>Supported MIME types are:
     *
     * <ul>
     *   <li>{@link MimeTypes#AUDIO_AAC}
     *   <li>{@link MimeTypes#AUDIO_AMR_NB}
     *   <li>{@link MimeTypes#AUDIO_AMR_WB}
     * </ul>
     *
     * If the MIME type is not supported, {@link Transformer} will fallback to a supported MIME type
     * and {@link Listener#onFallbackApplied(Composition, TransformationRequest,
     * TransformationRequest)} will be invoked with the fallback value.
     *
     * @param audioMimeType The MIME type of the audio samples in the output.
     * @return This builder.
     * @throws IllegalArgumentException If the audio MIME type passed is not an audio {@linkplain
     *     MimeTypes MIME type}.
     */
    @CanIgnoreReturnValue
    public Builder setAudioMimeType(String audioMimeType) {
      audioMimeType = MimeTypes.normalizeMimeType(audioMimeType);
      checkArgument(MimeTypes.isAudio(audioMimeType), "Not an audio MIME type: " + audioMimeType);
      this.audioMimeType = audioMimeType;
      return this;
    }

    /**
     * Sets the video {@linkplain MimeTypes MIME type} of the output.
     *
     * <p>If no video MIME type is passed, the output video MIME type is the same as the first
     * {@link MediaItem} in the {@link Composition}.
     *
     * <p>Supported MIME types are:
     *
     * <ul>
     *   <li>{@link MimeTypes#VIDEO_H263}
     *   <li>{@link MimeTypes#VIDEO_H264}
     *   <li>{@link MimeTypes#VIDEO_H265} from API level 24
     *   <li>{@link MimeTypes#VIDEO_MP4V}
     * </ul>
     *
     * If the MIME type is not supported, {@link Transformer} will fallback to a supported MIME type
     * and {@link Listener#onFallbackApplied(Composition, TransformationRequest,
     * TransformationRequest)} will be invoked with the fallback value.
     *
     * @param videoMimeType The MIME type of the video samples in the output.
     * @return This builder.
     * @throws IllegalArgumentException If the video MIME type passed is not a video {@linkplain
     *     MimeTypes MIME type}.
     */
    @CanIgnoreReturnValue
    public Builder setVideoMimeType(String videoMimeType) {
      videoMimeType = MimeTypes.normalizeMimeType(videoMimeType);
      checkArgument(MimeTypes.isVideo(videoMimeType), "Not a video MIME type: " + videoMimeType);
      this.videoMimeType = videoMimeType;
      return this;
    }

    /**
     * Sets whether to attempt to optimize trims from the start of the {@link EditedMediaItem} by
     * transcoding as little of the file as possible and transmuxing the rest.
     *
     * <p>This optimization has the following limitations:
     *
     * <ul>
     *   <li>Only supported for single-asset (i.e. only one {@link EditedMediaItem} in the whole
     *       {@link Composition}) exports of mp4 files.
     *   <li>Not guaranteed to work with any effects.
     * </ul>
     *
     * <p>This process relies on the given {@linkplain #setEncoderFactory EncoderFactory} providing
     * the right encoder level and profiles when transcoding, so that the transcoded and transmuxed
     * segments of the file can be stitched together. If the file segments can't be stitched
     * together, Transformer throw away any progress and proceed with unoptimized export instead.
     *
     * <p>The {@link ExportResult#optimizationResult} will indicate whether the optimization was
     * applied.
     *
     * @param enabled Whether to enable trim optimization.
     * @return This builder.
     */
    @CanIgnoreReturnValue
    public Builder experimentalSetTrimOptimizationEnabled(boolean enabled) {
      trimOptimizationEnabled = enabled;
      return this;
    }

    /**
     * Sets whether to use an MP4 edit list for trimming, to instruct players to ignore frames
     * between the key frame before the trim start point, and the trim start point.
     *
     * <p>This optimization has the following limitations, and will throw an {@link
     * IllegalStateException} if they are not met:
     *
     * <ul>
     *   <li>Transformer is configured with any {@link Muxer.Factory} where {@link
     *       Muxer.Factory#supportsWritingNegativeTimestampsInEditList()} is false. It is
     *       recommended to use {@link InAppMp4Muxer.Factory}.
     *   <li>Transformer has to transcode for any reason while trimming (such as if any video
     *       effects, apart from 90, 180 and 270 degree rotations are applied while trimming).
     * </ul>
     *
     * <p>This optimization will be ignored in the following cases:
     *
     * <ul>
     *   <li>Transformer input contains multiple assets (i.e. there is more than one {@link
     *       EditedMediaItem} in the {@link Composition}).
     *   <li>Transformer input contains a single {@link MediaItem} but the {@linkplain
     *       MediaItem#clippingConfiguration clipping configuration} is not set.
     * </ul>
     *
     * <p>If players do not respect the edit list the output file will be played from the key frame
     * before the trim start point rather than the requested trim start point.
     *
     * @param enabled Whether to enable mp4 edit list trimming.
     * @return This builder.
     */
    @CanIgnoreReturnValue
    public Builder experimentalSetMp4EditListTrimEnabled(boolean enabled) {
      mp4EditListTrimEnabled = enabled;
      return this;
    }

    /**
     * Sets whether to encode portrait videos in portrait orientation.
     *
     * <p>The default value is {@code false}. In this case, portrait videos will be rotated by 90
     * degrees before being encoded, and metadata will be added to the output file to indicate that
     * the video should be rotated back when played. This is done because encoding landscape videos
     * is more largely supported. Enabling portrait encoding should therefore be done carefully
     * because it is likely to result in more failures.
     *
     * @param enabled Whether to encode portrait videos in portrait orientation.
     * @return This builder.
     */
    @CanIgnoreReturnValue
    public Builder setPortraitEncodingEnabled(boolean enabled) {
      allowedEncodingRotationDegrees = enabled ? ImmutableList.of(0) : ALL_ROTATION_DEGREES;
      return this;
    }

    /**
     * Limits how many video frames can be processed at any time by the {@linkplain Codec encoder}.
     *
     * <p>A video frame starts encoding when it enters the {@linkplain Codec#getInputSurface()
     * encoder input surface}, and finishes encoding when the corresponding {@linkplain
     * Codec#releaseOutputBuffer encoder output buffer is released}.
     *
     * <p>The default value is {@link C#INDEX_UNSET}, which means no limit is enforced.
     *
     * <p>This method is experimental and will be renamed or removed in a future release.
     *
     * @param maxFramesInEncoder The maximum number of frames that the video encoder is allowed to
     *     process at a time, or {@link C#INDEX_UNSET} if no limit is enforced.
     * @return This builder.
     * @throws IllegalArgumentException If {@code maxFramesInEncoder} is not equal to {@link
     *     C#INDEX_UNSET} and is non-positive.
     */
    @CanIgnoreReturnValue
    public Builder experimentalSetMaxFramesInEncoder(int maxFramesInEncoder) {
      checkArgument(maxFramesInEncoder > 0 || maxFramesInEncoder == C.INDEX_UNSET);
      this.maxFramesInEncoder = maxFramesInEncoder;
      return this;
    }

    /**
     * Sets whether to ensure that the output file starts on a video frame.
     *
     * <p>Any audio samples that are earlier than the first video frame will be dropped. This can
     * make the output of trimming operations more compatible with player implementations that don't
     * show the first video frame until its presentation timestamp.
     *
     * <p>Ignored when {@linkplain #experimentalSetTrimOptimizationEnabled trim optimization} or
     * {@linkplain #experimentalSetMp4EditListTrimEnabled trimming with MP4 edit list} is set.
     *
     * @param enabled Whether to ensure that the file starts on a video frame.
     * @return This builder.
     */
    @CanIgnoreReturnValue
    public Builder setEnsureFileStartsOnVideoFrameEnabled(boolean enabled) {
      fileStartsOnVideoFrameEnabled = enabled;
      return this;
    }

    /**
     * Sets the maximum delay allowed between output samples regardless of the track type, or {@link
     * C#TIME_UNSET} if there is no maximum. The default value is {@link
     * #DEFAULT_MAX_DELAY_BETWEEN_MUXER_SAMPLES_MS}.
     *
     * <p>The export will be aborted when no sample is written in {@code
     * maxDelayBetweenMuxerSamplesMs}. Note that there is no guarantee that the export will be
     * aborted exactly at that time.
     *
     * @param maxDelayBetweenMuxerSamplesMs The maximum delay allowed (in microseconds).
     * @return This builder.
     */
    @CanIgnoreReturnValue
    public Builder setMaxDelayBetweenMuxerSamplesMs(long maxDelayBetweenMuxerSamplesMs) {
      this.maxDelayBetweenMuxerSamplesMs = maxDelayBetweenMuxerSamplesMs;
      return this;
    }

    /**
     * Adds a {@link Transformer.Listener} to listen to the export events.
     *
     * <p>This is equivalent to {@link Transformer#addListener(Listener)}.
     *
     * @param listener A {@link Transformer.Listener}.
     * @return This builder.
     */
    @CanIgnoreReturnValue
    public Builder addListener(Transformer.Listener listener) {
      this.listeners.add(listener);
      return this;
    }

    /**
     * Removes a {@link Transformer.Listener}.
     *
     * <p>This is equivalent to {@link Transformer#removeListener(Listener)}.
     *
     * @param listener A {@link Transformer.Listener}.
     * @return This builder.
     */
    @CanIgnoreReturnValue
    public Builder removeListener(Transformer.Listener listener) {
      this.listeners.remove(listener);
      return this;
    }

    /**
     * Removes all {@linkplain Transformer.Listener listeners}.
     *
     * <p>This is equivalent to {@link Transformer#removeAllListeners()}.
     *
     * @return This builder.
     */
    @CanIgnoreReturnValue
    public Builder removeAllListeners() {
      this.listeners.clear();
      return this;
    }

    /**
     * Sets the {@link AssetLoader.Factory} to be used to retrieve the samples to export.
     *
     * <p>The default value is a {@link DefaultAssetLoaderFactory} built with a {@link
     * DefaultMediaSourceFactory} and a {@link DefaultDecoderFactory}.
     *
     * @param assetLoaderFactory An {@link AssetLoader.Factory}.
     * @return This builder.
     */
    @CanIgnoreReturnValue
    public Builder setAssetLoaderFactory(AssetLoader.Factory assetLoaderFactory) {
      this.assetLoaderFactory = assetLoaderFactory;
      return this;
    }

    /**
     * Sets the {@link AudioMixer.Factory} to be used when {@linkplain AudioMixer audio mixing} is
     * needed.
     *
     * <p>The default value is a {@link DefaultAudioMixer.Factory} with default values.
     *
     * @param audioMixerFactory A {@link AudioMixer.Factory}.
     * @return This builder.
     */
    @CanIgnoreReturnValue
    public Builder setAudioMixerFactory(AudioMixer.Factory audioMixerFactory) {
      this.audioMixerFactory = audioMixerFactory;
      return this;
    }

    /**
     * Sets the {@link VideoFrameProcessor.Factory} to be used to create {@link VideoFrameProcessor}
     * instances.
     *
     * <p>The default value is a {@link DefaultVideoFrameProcessor.Factory} built with default
     * values.
     *
     * <p>If passing in a {@link DefaultVideoFrameProcessor.Factory}, the caller must not {@link
     * DefaultVideoFrameProcessor.Factory.Builder#setTextureOutput set the texture output}.
     *
     * <p>If exporting a {@link Composition} with multiple video {@linkplain EditedMediaItemSequence
     * sequences}, the {@link VideoFrameProcessor.Factory} must be a {@link
     * DefaultVideoFrameProcessor.Factory}.
     *
     * @param videoFrameProcessorFactory A {@link VideoFrameProcessor.Factory}.
     * @return This builder.
     */
    @CanIgnoreReturnValue
    public Builder setVideoFrameProcessorFactory(
        VideoFrameProcessor.Factory videoFrameProcessorFactory) {
      this.videoFrameProcessorFactory = videoFrameProcessorFactory;
      return this;
    }

    /**
     * Sets the {@link Codec.EncoderFactory} that will be used by the transformer.
     *
     * <p>The default value is a {@link DefaultEncoderFactory} instance.
     *
     * @param encoderFactory The {@link Codec.EncoderFactory} instance.
     * @return This builder.
     */
    @CanIgnoreReturnValue
    public Builder setEncoderFactory(Codec.EncoderFactory encoderFactory) {
      this.encoderFactory = encoderFactory;
      return this;
    }

    /**
     * Sets the {@link Muxer.Factory} for muxers that write the media container.
     *
     * <p>The default value is a {@link DefaultMuxer.Factory}.
     *
     * @param muxerFactory A {@link Muxer.Factory}.
     * @return This builder.
     */
    @CanIgnoreReturnValue
    public Builder setMuxerFactory(Muxer.Factory muxerFactory) {
      this.muxerFactory = muxerFactory;
      return this;
    }

    /**
     * Sets the {@link Looper} that must be used for all calls to the transformer and that is used
     * to call listeners on.
     *
     * <p>The default value is the Looper of the thread that this builder was created on, or if that
     * thread does not have a Looper, the Looper of the application's main thread.
     *
     * @param looper A {@link Looper}.
     * @return This builder.
     */
    @CanIgnoreReturnValue
    public Builder setLooper(Looper looper) {
      this.looper = looper;
      this.listeners = listeners.copy(looper, (listener, flags) -> {});
      return this;
    }

    /**
     * Sets a provider for views to show diagnostic information (if available) during export.
     *
     * <p>This is intended for debugging. The default value is {@link DebugViewProvider#NONE}, which
     * doesn't show any debug info.
     *
     * <p>Not all exports will result in debug views being populated.
     *
     * @param debugViewProvider Provider for debug views.
     * @return This builder.
     */
    @CanIgnoreReturnValue
    public Builder setDebugViewProvider(DebugViewProvider debugViewProvider) {
      this.debugViewProvider = debugViewProvider;
      return this;
    }

    /**
     * Sets the {@link Clock} that will be used by the transformer.
     *
     * <p>The default value is {@link Clock#DEFAULT}.
     *
     * @param clock The {@link Clock} instance.
     * @return This builder.
     */
    @CanIgnoreReturnValue
    @VisibleForTesting
    public Builder setClock(Clock clock) {
      this.clock = clock;
      this.listeners = listeners.copy(looper, clock, (listener, flags) -> {});
      return this;
    }

    /**
     * Sets the {@link EditingMetricsCollector.MetricsReporter.Factory} that will be used to report
     * the metrics.
     *
     * <p>The default value is {@link EditingMetricsCollector.DefaultMetricsReporter.Factory}.
     *
     * @param metricsReporterFactory A {@link EditingMetricsCollector.MetricsReporter.Factory}.
     * @return This builder.
     */
    @CanIgnoreReturnValue
    @VisibleForTesting
    /* package */ Builder setMetricsReporterFactory(
        EditingMetricsCollector.MetricsReporter.Factory metricsReporterFactory) {
      this.metricsReporterFactory = metricsReporterFactory;
      return this;
    }

    /**
     * Sets whether transformer reports diagnostics data to the Android platform.
     *
     * <p>If enabled, transformer will use the {@link android.media.metrics.MediaMetricsManager} to
     * create an {@link android.media.metrics.EditingSession} and forward editing events and
     * performance data to this session. This helps to provide system performance and debugging
     * information for media editing on this device. This data may also be collected by Google <a
     * href="https://support.google.com/accounts/answer/6078260">if sharing usage and diagnostics
     * data is enabled</a> by the user of the device.
     *
     * <p>The default value is {@code true}.
     *
     * @param usePlatformDiagnostics Whether transformer reports diagnostics data to the Android
     *     platform.
     * @return This builder.
     */
    @CanIgnoreReturnValue
    public Builder setUsePlatformDiagnostics(boolean usePlatformDiagnostics) {
      this.usePlatformDiagnostics = usePlatformDiagnostics;
      return this;
    }

    /**
     * Builds a {@link Transformer} instance.
     *
     * @throws IllegalStateException If both audio and video have been removed (otherwise the output
     *     would not contain any samples).
     * @throws IllegalStateException If the muxer doesn't support the requested audio/video MIME
     *     type.
     * @throws IllegalStateException If {@link #experimentalSetMp4EditListTrimEnabled(boolean
     *     enabled)} is enabled but the {@link Muxer.Factory} does not support writing negative
     *     timestamps to an edit list.
     */
    public Transformer build() {
      TransformationRequest.Builder transformationRequestBuilder =
          transformationRequest == null
              ? new TransformationRequest.Builder()
              : transformationRequest.buildUpon();
      if (audioMimeType != null) {
        transformationRequestBuilder.setAudioMimeType(audioMimeType);
      }
      if (videoMimeType != null) {
        transformationRequestBuilder.setVideoMimeType(videoMimeType);
      }
      transformationRequest = transformationRequestBuilder.build();
      if (transformationRequest.audioMimeType != null) {
        checkSampleMimeType(transformationRequest.audioMimeType);
      }
      if (transformationRequest.videoMimeType != null) {
        checkSampleMimeType(transformationRequest.videoMimeType);
      }
      checkState(
          !mp4EditListTrimEnabled || muxerFactory.supportsWritingNegativeTimestampsInEditList(),
          String.format(
              "Muxer.Factory %s does not support writing negative timestamps to an edit list.",
              muxerFactory));
      return new Transformer(
          context,
          transformationRequest,
          audioProcessors,
          videoEffects,
          removeAudio,
          removeVideo,
          trimOptimizationEnabled,
          mp4EditListTrimEnabled,
          allowedEncodingRotationDegrees,
          fileStartsOnVideoFrameEnabled,
          usePlatformDiagnostics,
          maxDelayBetweenMuxerSamplesMs,
          maxFramesInEncoder,
          listeners,
          assetLoaderFactory,
          audioMixerFactory,
          videoFrameProcessorFactory,
          encoderFactory,
          muxerFactory,
          looper,
          debugViewProvider,
          clock,
          metricsReporterFactory);
    }

    private void checkSampleMimeType(String sampleMimeType) {
      checkState(
          muxerFactory
              .getSupportedSampleMimeTypes(MimeTypes.getTrackType(sampleMimeType))
              .contains(sampleMimeType),
          "Unsupported sample MIME type " + sampleMimeType);
    }
  }

  /**
   * A listener for the export events.
   *
   * <p>If the export is not cancelled, either {@link #onError} or {@link #onCompleted} will be
   * called once for each export.
   */
  public interface Listener {

    /**
     * Called when the export is completed successfully.
     *
     * @param composition The {@link Composition} for which the export is completed.
     * @param exportResult The {@link ExportResult} of the export.
     */
    default void onCompleted(Composition composition, ExportResult exportResult) {}

    /**
     * Called if an exception occurs during the export.
     *
     * <p>The export output file (if any) is not deleted in this case.
     *
     * @param composition The {@link Composition} for which the exception occurs.
     * @param exportResult The {@link ExportResult} of the export.
     * @param exportException The {@link ExportException} describing the exception. This is the same
     *     instance as the {@linkplain ExportResult#exportException exception} in {@code result}.
     */
    default void onError(
        Composition composition, ExportResult exportResult, ExportException exportException) {}

    /**
     * Called when falling back to an alternative {@link TransformationRequest} or changing the
     * video frames' resolution is necessary to comply with muxer or device constraints.
     *
     * @param composition The {@link Composition} for which the export is requested.
     * @param originalTransformationRequest The unsupported {@link TransformationRequest} used when
     *     building {@link Transformer}.
     * @param fallbackTransformationRequest The alternative {@link TransformationRequest}, with
     *     supported {@link TransformationRequest#audioMimeType}, {@link
     *     TransformationRequest#videoMimeType}, {@link TransformationRequest#outputHeight}, and
     *     {@link TransformationRequest#hdrMode} values set.
     */
    default void onFallbackApplied(
        Composition composition,
        TransformationRequest originalTransformationRequest,
        TransformationRequest fallbackTransformationRequest) {}
  }

  /**
   * Progress state. One of {@link #PROGRESS_STATE_NOT_STARTED}, {@link
   * #PROGRESS_STATE_WAITING_FOR_AVAILABILITY}, {@link #PROGRESS_STATE_AVAILABLE} or {@link
   * #PROGRESS_STATE_UNAVAILABLE}.
   */
  @Documented
  @Retention(RetentionPolicy.SOURCE)
  @Target(TYPE_USE)
  @IntDef({
    PROGRESS_STATE_NOT_STARTED,
    PROGRESS_STATE_WAITING_FOR_AVAILABILITY,
    PROGRESS_STATE_AVAILABLE,
    PROGRESS_STATE_UNAVAILABLE
  })
  public @interface ProgressState {}

  /** Indicates that the corresponding operation hasn't been started. */
  public static final int PROGRESS_STATE_NOT_STARTED = 0;

  /** Indicates that the progress is currently unavailable, but might become available. */
  public static final int PROGRESS_STATE_WAITING_FOR_AVAILABILITY = 1;

  /** Indicates that the progress is available. */
  public static final int PROGRESS_STATE_AVAILABLE = 2;

  /** Indicates that the progress is permanently unavailable. */
  public static final int PROGRESS_STATE_UNAVAILABLE = 3;

  /**
   * The default value for the {@linkplain Builder#setMaxDelayBetweenMuxerSamplesMs maximum delay
   * between output samples}.
   */
  public static final long DEFAULT_MAX_DELAY_BETWEEN_MUXER_SAMPLES_MS =
      isRunningOnEmulator() ? 25_000 : 10_000;

  @Documented
  @Retention(RetentionPolicy.SOURCE)
  @Target(TYPE_USE)
  @IntDef({
    TRANSFORMER_STATE_PROCESS_FULL_INPUT,
    TRANSFORMER_STATE_REMUX_PROCESSED_VIDEO,
    TRANSFORMER_STATE_PROCESS_REMAINING_VIDEO,
    TRANSFORMER_STATE_PROCESS_AUDIO,
    TRANSFORMER_STATE_COPY_OUTPUT,
    TRANSFORMER_STATE_PROCESS_MEDIA_START,
    TRANSFORMER_STATE_REMUX_REMAINING_MEDIA
  })
  private @interface TransformerState {}

  /** The default Transformer state. */
  private static final int TRANSFORMER_STATE_PROCESS_FULL_INPUT = 0;

  /**
   * The first state of a {@link #resume(Composition composition, String outputFilePath, String
   * oldFilePath)} export.
   *
   * <p>In this state, the paused export file's encoded video track is muxed into a video-only file,
   * stored at {@code oldFilePath}.
   *
   * <p>The video-only file is kept open to allow the {@link
   * #TRANSFORMER_STATE_PROCESS_REMAINING_VIDEO} to continue writing to the same file & video track.
   *
   * <p>A successful operation in this state moves the Transformer to the {@link
   * #TRANSFORMER_STATE_PROCESS_REMAINING_VIDEO} state.
   */
  private static final int TRANSFORMER_STATE_REMUX_PROCESSED_VIDEO = 1;

  /**
   * The second state of a {@link #resume(Composition composition, String outputFilePath, String
   * oldFilePath)} export.
   *
   * <p>In this state, the remaining {@link Composition} video data is processed and muxed into the
   * same video-only file, stored at {@code oldFilePath}.
   *
   * <p>A successful operation in this state moves the Transformer to the {@link
   * #TRANSFORMER_STATE_PROCESS_AUDIO} state.
   */
  private static final int TRANSFORMER_STATE_PROCESS_REMAINING_VIDEO = 2;

  /**
   * The third state of a {@link #resume(Composition composition, String outputFilePath, String
   * oldFilePath)} resumed export.
   *
   * <p>In this state, the entire {@link Composition} audio is processed and muxed. This same
   * operation also transmuxes the video-only file produced by {@link
   * #TRANSFORMER_STATE_PROCESS_REMAINING_VIDEO}, interleaving of the audio and video tracks. The
   * output is stored at {@code oldFilePath}.
   *
   * <p>A successful operation in this state moves the Transformer to the {@link
   * #TRANSFORMER_STATE_COPY_OUTPUT} state.
   */
  private static final int TRANSFORMER_STATE_PROCESS_AUDIO = 3;

  /**
   * The final state of a {@link #resume(Composition composition, String outputFilePath, String
   * oldFilePath)} export.
   *
   * <p>In this state, the successful exported file (stored at {@code oldFilePath}) is copied to the
   * {@code outputFilePath}.
   */
  private static final int TRANSFORMER_STATE_COPY_OUTPUT = 4;

  private static final int TRANSFORMER_STATE_PROCESS_MEDIA_START = 5;
  private static final int TRANSFORMER_STATE_REMUX_REMAINING_MEDIA = 6;
  private static final String EXPORTER_NAME =
      "androidx.media3:media3-transformer:" + MediaLibraryInfo.VERSION;
  private final Context context;
  private final TransformationRequest transformationRequest;
  private final ImmutableList<AudioProcessor> audioProcessors;
  private final ImmutableList<Effect> videoEffects;
  private final boolean removeAudio;
  private final boolean removeVideo;
  private final boolean trimOptimizationEnabled;
  private final boolean mp4EditListTrimEnabled;
  private final ImmutableList<Integer> allowedEncodingRotationDegrees;
  private final boolean fileStartsOnVideoFrameEnabled;
  private final boolean usePlatformDiagnostics;
  private final long maxDelayBetweenMuxerSamplesMs;
  private final int maxFramesInEncoder;

  private final ListenerSet<Transformer.Listener> listeners;
  @Nullable private final AssetLoader.Factory assetLoaderFactory;
  private final AudioMixer.Factory audioMixerFactory;
  private final VideoFrameProcessor.Factory videoFrameProcessorFactory;
  private final Codec.EncoderFactory encoderFactory;
  private final Muxer.Factory muxerFactory;
  private final Looper looper;
  private final DebugViewProvider debugViewProvider;
  private final Clock clock;
  private final HandlerWrapper applicationHandler;
  private final ComponentListener componentListener;
  private final ExportResult.Builder exportResultBuilder;
  @Nullable private final EditingMetricsCollector.MetricsReporter.Factory metricsReporterFactory;

  @Nullable private TransformerInternal transformerInternal;
  @Nullable private MuxerWrapper remuxingMuxerWrapper;
  private @MonotonicNonNull Composition composition;
  private @MonotonicNonNull String outputFilePath;
  private @MonotonicNonNull String oldFilePath;
  private @TransformerState int transformerState;
  private TransmuxTranscodeHelper.@MonotonicNonNull ResumeMetadata resumeMetadata;
  private @MonotonicNonNull ListenableFuture<TransmuxTranscodeHelper.ResumeMetadata>
      getResumeMetadataFuture;
  private @MonotonicNonNull EditingMetricsCollector editingMetricsCollector;
  private @MonotonicNonNull ListenableFuture<Void> copyOutputFuture;
  @Nullable private Mp4Info mediaItemInfo;
  @Nullable private WatchdogTimer exportWatchdogTimer;

  private Transformer(
      Context context,
      TransformationRequest transformationRequest,
      ImmutableList<AudioProcessor> audioProcessors,
      ImmutableList<Effect> videoEffects,
      boolean removeAudio,
      boolean removeVideo,
      boolean trimOptimizationEnabled,
      boolean mp4EditListTrimEnabled,
      ImmutableList<Integer> allowedEncodingRotationDegrees,
      boolean fileStartsOnVideoFrameEnabled,
      boolean usePlatformDiagnostics,
      long maxDelayBetweenMuxerSamplesMs,
      int maxFramesInEncoder,
      ListenerSet<Listener> listeners,
      @Nullable AssetLoader.Factory assetLoaderFactory,
      AudioMixer.Factory audioMixerFactory,
      VideoFrameProcessor.Factory videoFrameProcessorFactory,
      Codec.EncoderFactory encoderFactory,
      Muxer.Factory muxerFactory,
      Looper looper,
      DebugViewProvider debugViewProvider,
      Clock clock,
      @Nullable EditingMetricsCollector.MetricsReporter.Factory metricsReporterFactory) {
    checkState(!removeAudio || !removeVideo, "Audio and video cannot both be removed.");
    this.context = context;
    this.transformationRequest = transformationRequest;
    this.audioProcessors = audioProcessors;
    this.videoEffects = videoEffects;
    this.removeAudio = removeAudio;
    this.removeVideo = removeVideo;
    this.trimOptimizationEnabled = trimOptimizationEnabled;
    this.mp4EditListTrimEnabled = mp4EditListTrimEnabled;
    this.allowedEncodingRotationDegrees = allowedEncodingRotationDegrees;
    this.fileStartsOnVideoFrameEnabled = fileStartsOnVideoFrameEnabled;
    this.usePlatformDiagnostics = usePlatformDiagnostics;
    this.maxDelayBetweenMuxerSamplesMs = maxDelayBetweenMuxerSamplesMs;
    this.maxFramesInEncoder = maxFramesInEncoder;
    this.listeners = listeners;
    this.assetLoaderFactory = assetLoaderFactory;
    this.audioMixerFactory = audioMixerFactory;
    this.videoFrameProcessorFactory = videoFrameProcessorFactory;
    this.encoderFactory = encoderFactory;
    this.muxerFactory = muxerFactory;
    this.looper = looper;
    this.debugViewProvider = debugViewProvider;
    this.clock = clock;
    this.metricsReporterFactory = metricsReporterFactory;
    transformerState = TRANSFORMER_STATE_PROCESS_FULL_INPUT;
    applicationHandler = clock.createHandler(looper, /* callback= */ null);
    componentListener = new ComponentListener();
    exportResultBuilder = new ExportResult.Builder();
  }

  /** Returns a {@link Transformer.Builder} initialized with the values of this instance. */
  public Builder buildUpon() {
    return new Builder(this);
  }

  /**
   * Adds a {@link Transformer.Listener} to listen to the export events.
   *
   * @param listener A {@link Transformer.Listener}.
   * @throws IllegalStateException If this method is called from the wrong thread.
   */
  public void addListener(Transformer.Listener listener) {
    verifyApplicationThread();
    this.listeners.add(listener);
  }

  /**
   * Removes a {@link Transformer.Listener}.
   *
   * @param listener A {@link Transformer.Listener}.
   * @throws IllegalStateException If this method is called from the wrong thread.
   */
  public void removeListener(Transformer.Listener listener) {
    verifyApplicationThread();
    this.listeners.remove(listener);
  }

  /**
   * Removes all {@linkplain Transformer.Listener listeners}.
   *
   * @throws IllegalStateException If this method is called from the wrong thread.
   */
  public void removeAllListeners() {
    verifyApplicationThread();
    this.listeners.clear();
  }

  /**
   * Starts an asynchronous operation to export the given {@link Composition}.
   *
   * <p>The first {@link EditedMediaItem} in the first {@link EditedMediaItemSequence} that has a
   * given {@linkplain C.TrackType track} will determine the output format for that track, unless
   * the format is set when {@linkplain Builder#build building} the {@code Transformer}. For
   * example, consider the following composition
   *
   * <pre>
   *   Composition {
   *     EditedMediaItemSequence {
   *       [ImageMediaItem, VideoMediaItem]
   *     },
   *     EditedMediaItemSequence {
   *       [AudioMediaItem]
   *     },
   *   }
   * </pre>
   *
   * The video format will be determined by the {@code ImageMediaItem} in the first {@link
   * EditedMediaItemSequence}, while the audio format will be determined by the {@code
   * AudioMediaItem} in the second {@code EditedMediaItemSequence}.
   *
   * <p>Some {@linkplain Composition compositions} are not supported yet. More specifically,
   * {@linkplain EditedMediaItemSequence Sequences} within the {@link Composition} must meet the
   * following conditions:
   *
   * <ul>
   *   <li>If an {@link EditedMediaItem} in a sequence contains data of a given {@linkplain
   *       C.TrackType track}, so must all items in that sequence.
   *       <ul>
   *         <li>For audio, this condition can be removed by setting {@link
   *             EditedMediaItemSequence.Builder#experimentalSetForceAudioTrack(boolean)} flag.
   *       </ul>
   *   <li>If a sequence starts with an HDR {@link EditedMediaItem}, all the following items in the
   *       sequence must be HDR.
   *   <li>All {@linkplain EditedMediaItem items} containing audio data must output 16 bit PCM audio
   *       with the same number of channels. This can be done by adding a {@link
   *       ToInt16PcmAudioProcessor} and/or a {@link ChannelMixingAudioProcessor} to the {@linkplain
   *       EditedMediaItem#effects item specific effects}.
   * </ul>
   *
   * <p>The export state is notified through the {@linkplain Builder#addListener(Listener)
   * listener}.
   *
   * <p>Concurrent exports on the same Transformer object are not allowed.
   *
   * <p>If no custom {@link Transformer.Builder#setMuxerFactory(Muxer.Factory) Muxer.Factory} is
   * specified, the output is an MP4 file.
   *
   * <p>The output can contain at most one video track and one audio track. Other track types are
   * ignored. For adaptive bitrate inputs, if no custom {@link
   * Transformer.Builder#setAssetLoaderFactory(AssetLoader.Factory) AssetLoader.Factory} is
   * specified, the highest bitrate video and audio streams are selected.
   *
   * <p>If exporting the video track entails transcoding, the output frames' dimensions will be
   * swapped if the output video's height is larger than the width. This is to improve compatibility
   * among different device encoders.
   *
   * @param composition The {@link Composition} to export.
   * @param path The path to the output file.
   * @throws IllegalStateException If this method is called from the wrong thread.
   * @throws IllegalStateException If an export is already in progress.
   */
  public void start(Composition composition, String path) {
    verifyApplicationThread();
    initialize(composition, path);
    if (trimOptimizationEnabled && isSingleAssetTrimming()) {
      processMediaBeforeFirstSyncSampleAfterTrimStartTime();
    } else {
      startInternal(
          composition,
          new MuxerWrapper(
              path,
              muxerFactory,
              componentListener,
              MuxerWrapper.MUXER_MODE_DEFAULT,
              /* dropSamplesBeforeFirstVideoSample= */ fileStartsOnVideoFrameEnabled,
              /* appendVideoFormat= */ null,
              /* writeNegativeTimestampsToEditList= */ shouldApplyMp4EditListTrim()),
          componentListener,
          /* initialTimestampOffsetUs= */ 0,
          /* useDefaultAssetLoaderFactory= */ false);
    }
  }

  /**
   * Starts an asynchronous operation to export the given {@link EditedMediaItem}.
   *
   * <p>The export state is notified through the {@linkplain Builder#addListener(Listener)
   * listener}.
   *
   * <p>Concurrent exports on the same Transformer object are not allowed.
   *
   * <p>If no custom {@link Transformer.Builder#setMuxerFactory(Muxer.Factory) Muxer.Factory} is
   * specified, the output is an MP4 file.
   *
   * <p>The output can contain at most one video track and one audio track. Other track types are
   * ignored. For adaptive bitrate inputs, if no custom {@link
   * Transformer.Builder#setAssetLoaderFactory(AssetLoader.Factory) AssetLoader.Factory} is
   * specified, the highest bitrate video and audio streams are selected.
   *
   * <p>If exporting the video track entails transcoding, the output frames' dimensions will be
   * swapped if the output video's height is larger than the width. This is to improve compatibility
   * among different device encoders.
   *
   * @param editedMediaItem The {@link EditedMediaItem} to export.
   * @param path The path to the output file.
   * @throws IllegalStateException If this method is called from the wrong thread.
   * @throws IllegalStateException If an export is already in progress.
   */
  public void start(EditedMediaItem editedMediaItem, String path) {
    start(
        new Composition.Builder(new EditedMediaItemSequence.Builder(editedMediaItem).build())
            .build(),
        path);
  }

  /**
   * Starts an asynchronous operation to export the given {@link MediaItem}.
   *
   * <p>The export state is notified through the {@linkplain Builder#addListener(Listener)
   * listener}.
   *
   * <p>Concurrent exports on the same Transformer object are not allowed.
   *
   * <p>If no custom {@link Transformer.Builder#setMuxerFactory(Muxer.Factory) Muxer.Factory} is
   * specified, the output is an MP4 file.
   *
   * <p>The output can contain at most one video track and one audio track. Other track types are
   * ignored. For adaptive bitrate inputs, if no custom {@link
   * Transformer.Builder#setAssetLoaderFactory(AssetLoader.Factory) AssetLoader.Factory} is
   * specified, the highest bitrate video and audio streams are selected.
   *
   * <p>If exporting the video track entails transcoding, the output frames' dimensions will be
   * swapped if the output video's height is larger than the width. This is to improve compatibility
   * among different device encoders.
   *
   * @param mediaItem The {@link MediaItem} to export.
   * @param path The path to the output file.
   * @throws IllegalArgumentException If the {@link MediaItem} is not supported.
   * @throws IllegalStateException If this method is called from the wrong thread.
   * @throws IllegalStateException If an export is already in progress.
   */
  public void start(MediaItem mediaItem, String path) {
    EditedMediaItem editedMediaItem =
        new EditedMediaItem.Builder(mediaItem)
            .setRemoveAudio(removeAudio)
            .setRemoveVideo(removeVideo)
            .setEffects(new Effects(audioProcessors, videoEffects))
            .build();
    start(editedMediaItem, path);
  }

  /**
   * Returns the {@link Looper} associated with the application thread that's used to access the
   * transformer and on which transformer events are received.
   */
  public Looper getApplicationLooper() {
    return looper;
  }

  /**
   * Returns the current {@link ProgressState} and updates {@code progressHolder} with the current
   * progress if it is {@link #PROGRESS_STATE_AVAILABLE available}.
   *
   * <p>If the export is {@linkplain #resume(Composition, String, String) resumed}, this method
   * returns {@link #PROGRESS_STATE_UNAVAILABLE}.
   *
   * <p>After an export {@linkplain Listener#onCompleted(Composition, ExportResult) completes}, this
   * method returns {@link #PROGRESS_STATE_NOT_STARTED}.
   *
   * @param progressHolder A {@link ProgressHolder}, updated to hold the percentage progress if
   *     {@link #PROGRESS_STATE_AVAILABLE available}.
   * @return The {@link ProgressState}.
   * @throws IllegalStateException If this method is called from the wrong thread.
   */
  public @ProgressState int getProgress(ProgressHolder progressHolder) {
    verifyApplicationThread();
    if (isExportResumed()) {
      // Progress updates are unavailable for resumed exports.
      return PROGRESS_STATE_UNAVAILABLE;
    }

    if (isExportTrimOptimization()) {
      return getTrimOptimizationProgress(progressHolder);
    }

    return transformerInternal == null
        ? PROGRESS_STATE_NOT_STARTED
        : transformerInternal.getProgress(progressHolder);
  }

  private boolean isSingleAssetTrimming() {
    if (isMultiAsset()) {
      return false;
    }

    MediaItem.ClippingConfiguration clippingConfiguration =
        checkNotNull(composition)
            .sequences
            .get(0)
            .editedMediaItems
            .get(0)
            .mediaItem
            .clippingConfiguration;
    return !clippingConfiguration.equals(MediaItem.ClippingConfiguration.UNSET);
  }

  private boolean shouldApplyMp4EditListTrim() {
    return mp4EditListTrimEnabled && isSingleAssetTrimming();
  }

  private boolean isExportResumed() {
    return transformerState == TRANSFORMER_STATE_REMUX_PROCESSED_VIDEO
        || transformerState == TRANSFORMER_STATE_PROCESS_REMAINING_VIDEO
        || transformerState == TRANSFORMER_STATE_PROCESS_AUDIO
        || transformerState == TRANSFORMER_STATE_COPY_OUTPUT;
  }

  private boolean isExportTrimOptimization() {
    return transformerState == TRANSFORMER_STATE_PROCESS_MEDIA_START
        || transformerState == TRANSFORMER_STATE_REMUX_REMAINING_MEDIA;
  }

  private @ProgressState int getTrimOptimizationProgress(ProgressHolder progressHolder) {
    if (mediaItemInfo == null) {
      return PROGRESS_STATE_WAITING_FOR_AVAILABILITY;
    }
    MediaItem firstMediaItem =
        checkNotNull(composition).sequences.get(0).editedMediaItems.get(0).mediaItem;
    long trimStartTimeUs = firstMediaItem.clippingConfiguration.startPositionUs;
    long transcodeDuration = mediaItemInfo.firstSyncSampleTimestampUsAfterTimeUs - trimStartTimeUs;
    float transcodeWeighting = (float) transcodeDuration / mediaItemInfo.durationUs;

    if (transformerState == TRANSFORMER_STATE_PROCESS_MEDIA_START) {
      if (transformerInternal == null) {
        return PROGRESS_STATE_WAITING_FOR_AVAILABILITY;
      }
      @ProgressState
      int processMediaStartProgressState = transformerInternal.getProgress(progressHolder);
      switch (processMediaStartProgressState) {
        case PROGRESS_STATE_NOT_STARTED:
        case PROGRESS_STATE_WAITING_FOR_AVAILABILITY:
          return PROGRESS_STATE_WAITING_FOR_AVAILABILITY;
        case PROGRESS_STATE_AVAILABLE:
          progressHolder.progress = round(progressHolder.progress * transcodeWeighting);
          return PROGRESS_STATE_AVAILABLE;
        case PROGRESS_STATE_UNAVAILABLE:
          return PROGRESS_STATE_UNAVAILABLE;
        default:
          throw new IllegalStateException();
      }
    }

    float fullTranscodeProgress = 100 * transcodeWeighting;
    if (transformerInternal == null) {
      // Transformer has not started remuxing the remaining media yet.
      progressHolder.progress = round(fullTranscodeProgress);
      return PROGRESS_STATE_AVAILABLE;
    }
    @ProgressState
    int remuxRemainingMediaProgressState = transformerInternal.getProgress(progressHolder);
    switch (remuxRemainingMediaProgressState) {
      case PROGRESS_STATE_NOT_STARTED:
      case PROGRESS_STATE_WAITING_FOR_AVAILABILITY:
        progressHolder.progress = round(fullTranscodeProgress);
        return PROGRESS_STATE_AVAILABLE;
      case PROGRESS_STATE_AVAILABLE:
        progressHolder.progress =
            round(fullTranscodeProgress + (1 - transcodeWeighting) * progressHolder.progress);
        return PROGRESS_STATE_AVAILABLE;
      case PROGRESS_STATE_UNAVAILABLE:
        return PROGRESS_STATE_UNAVAILABLE;
      default:
        throw new IllegalStateException();
    }
  }

  /**
   * Cancels the export that is currently in progress, if any.
   *
   * <p>The export output file (if any) is not deleted.
   *
   * @throws IllegalStateException If this method is called from the wrong thread.
   */
  public void cancel() {
    verifyApplicationThread();
    if (transformerInternal == null) {
      maybeStopExportWatchdogTimer();
      return;
    }
    try {
      transformerInternal.cancel();
    } finally {
      ProgressHolder progressHolder = new ProgressHolder();
      int progressState = getProgress(progressHolder);
      transformerInternal = null;

      if (canCollectEditingMetrics()) {
        int progressPercentage =
            (progressState == PROGRESS_STATE_AVAILABLE)
                ? progressHolder.progress
                : C.PERCENTAGE_UNSET;
        checkNotNull(editingMetricsCollector).onExportCancelled(progressPercentage);
      }
    }

    if (getResumeMetadataFuture != null && !getResumeMetadataFuture.isDone()) {
      getResumeMetadataFuture.cancel(/* mayInterruptIfRunning= */ false);
    }
    if (copyOutputFuture != null && !copyOutputFuture.isDone()) {
      copyOutputFuture.cancel(/* mayInterruptIfRunning= */ false);
    }
    maybeStopExportWatchdogTimer();
  }

  /**
   * Resumes a previously {@linkplain #cancel() cancelled} export.
   *
   * <p>An export can be resumed only when:
   *
   * <ul>
   *   <li>The {@link Composition} contains a single {@link EditedMediaItemSequence} having
   *       continuous audio and video tracks.
   *   <li>The output is an MP4 file.
   * </ul>
   *
   * <p>Note that export optimizations (such as {@linkplain
   * Builder#experimentalSetTrimOptimizationEnabled trim optimization}) will not be applied upon
   * resumption.
   *
   * @param composition The {@link Composition} to resume export.
   * @param outputFilePath The path to the output file. This must be different from the output path
   *     of the cancelled export.
   * @param oldFilePath The output path of the the cancelled export.
   */
  public void resume(Composition composition, String outputFilePath, String oldFilePath) {
    verifyApplicationThread();
    initialize(composition, outputFilePath);
    this.oldFilePath = oldFilePath;
    remuxProcessedVideo();
  }

  private void maybeInitializeExportWatchdogTimer() {
    if (maxDelayBetweenMuxerSamplesMs == C.TIME_UNSET) {
      return;
    }
    exportWatchdogTimer =
        new WatchdogTimer(
            maxDelayBetweenMuxerSamplesMs,
            () -> {
              ExportException exportException =
                  ExportException.createForMuxer(
                      new IllegalStateException(
                          Util.formatInvariant(
                              "Abort: no output sample written in the last %d milliseconds."
                                  + " DebugTrace: %s",
                              maxDelayBetweenMuxerSamplesMs,
                              DebugTraceUtil.generateTraceSummary())),
                      ExportException.ERROR_CODE_MUXING_TIMEOUT);
              checkNotNull(transformerInternal).endWithException(exportException);
            });
    exportWatchdogTimer.start();
  }

  private void maybeStopExportWatchdogTimer() {
    if (exportWatchdogTimer != null) {
      exportWatchdogTimer.stop();
      exportWatchdogTimer = null;
    }
  }

  private void initialize(Composition composition, String outputFilePath) {
    maybeInitializeExportWatchdogTimer();
    this.composition = composition;
    this.outputFilePath = outputFilePath;
    exportResultBuilder.reset();
  }

  private void processFullInput() {
    transformerState = TRANSFORMER_STATE_PROCESS_FULL_INPUT;
    startInternal(
        checkNotNull(composition),
        new MuxerWrapper(
            checkNotNull(outputFilePath),
            muxerFactory,
            componentListener,
            MuxerWrapper.MUXER_MODE_DEFAULT,
            /* dropSamplesBeforeFirstVideoSample= */ false,
            /* appendVideoFormat= */ null,
            /* writeNegativeTimestampsToEditList= */ false),
        componentListener,
        /* initialTimestampOffsetUs= */ 0,
        /* useDefaultAssetLoaderFactory= */ false);
  }

  private void remuxProcessedVideo() {
    transformerState = TRANSFORMER_STATE_REMUX_PROCESSED_VIDEO;
    getResumeMetadataFuture =
        TransmuxTranscodeHelper.getResumeMetadataAsync(
            context, checkNotNull(oldFilePath), checkNotNull(composition));
    Futures.addCallback(
        getResumeMetadataFuture,
        new FutureCallback<TransmuxTranscodeHelper.ResumeMetadata>() {
          @Override
          public void onSuccess(TransmuxTranscodeHelper.ResumeMetadata resumeMetadata) {
            // If there is no video track to remux or the last sync sample is actually the first
            // sample, then start the normal Export.
            if (resumeMetadata.lastSyncSampleTimestampUs == C.TIME_UNSET
                || resumeMetadata.lastSyncSampleTimestampUs == 0) {
              processFullInput();
              return;
            }

            Transformer.this.resumeMetadata = resumeMetadata;

            remuxingMuxerWrapper =
                new MuxerWrapper(
                    checkNotNull(outputFilePath),
                    muxerFactory,
                    componentListener,
                    MuxerWrapper.MUXER_MODE_MUX_PARTIAL,
                    /* dropSamplesBeforeFirstVideoSample= */ false,
                    /* appendVideoFormat= */ resumeMetadata.videoFormat,
                    /* writeNegativeTimestampsToEditList= */ false);

            startInternal(
                TransmuxTranscodeHelper.createVideoOnlyComposition(
                    oldFilePath,
                    /* clippingEndPositionUs= */ resumeMetadata.lastSyncSampleTimestampUs),
                checkNotNull(remuxingMuxerWrapper),
                componentListener,
                /* initialTimestampOffsetUs= */ 0,
                /* useDefaultAssetLoaderFactory= */ true);
          }

          @Override
          public void onFailure(Throwable t) {
            // In case of error fallback to normal Export.
            processFullInput();
          }
        },
        applicationHandler::post);
  }

  private void processRemainingVideo() {
    transformerState = TRANSFORMER_STATE_PROCESS_REMAINING_VIDEO;
    Composition videoOnlyComposition =
        TransmuxTranscodeHelper.buildUponComposition(
            checkNotNull(composition),
            /* removeAudio= */ true,
            /* removeVideo= */ false,
            resumeMetadata);

    checkNotNull(remuxingMuxerWrapper);
    remuxingMuxerWrapper.changeToAppendMode();

    startInternal(
        videoOnlyComposition,
        remuxingMuxerWrapper,
        componentListener,
        /* initialTimestampOffsetUs= */ checkNotNull(resumeMetadata).lastSyncSampleTimestampUs,
        /* useDefaultAssetLoaderFactory= */ false);
  }

  private void processAudio() {
    transformerState = TRANSFORMER_STATE_PROCESS_AUDIO;

    MuxerWrapper muxerWrapper =
        new MuxerWrapper(
            checkNotNull(oldFilePath),
            muxerFactory,
            componentListener,
            MuxerWrapper.MUXER_MODE_DEFAULT,
            /* dropSamplesBeforeFirstVideoSample= */ false,
            /* appendVideoFormat= */ null,
            /* writeNegativeTimestampsToEditList= */ shouldApplyMp4EditListTrim());

    startInternal(
        TransmuxTranscodeHelper.createAudioTranscodeAndVideoTransmuxComposition(
            checkNotNull(composition), checkNotNull(outputFilePath)),
        muxerWrapper,
        componentListener,
        /* initialTimestampOffsetUs= */ 0,
        /* useDefaultAssetLoaderFactory= */ false);
  }

  // TODO: b/308253384 - Move copy output logic into MuxerWrapper.
  private void copyOutput() {
    transformerState = TRANSFORMER_STATE_COPY_OUTPUT;
    copyOutputFuture =
        TransmuxTranscodeHelper.copyFileAsync(
            new File(checkNotNull(oldFilePath)), new File(checkNotNull(outputFilePath)));

    Futures.addCallback(
        copyOutputFuture,
        new FutureCallback<Void>() {
          @Override
          public void onSuccess(Void result) {
            onExportCompletedWithSuccess();
          }

          @Override
          public void onFailure(Throwable t) {
            onExportCompletedWithError(
                ExportException.createForUnexpected(
                    new IOException("Copy output task failed for the resumed export", t)));
          }
        },
        applicationHandler::post);
  }

  private void processMediaBeforeFirstSyncSampleAfterTrimStartTime() {
    transformerState = TRANSFORMER_STATE_PROCESS_MEDIA_START;
    EditedMediaItem firstEditedMediaItem =
        checkNotNull(composition).sequences.get(0).editedMediaItems.get(0);
    long trimStartTimeUs = firstEditedMediaItem.mediaItem.clippingConfiguration.startPositionUs;
    long trimEndTimeUs = firstEditedMediaItem.mediaItem.clippingConfiguration.endPositionUs;
    ListenableFuture<Mp4Info> getMp4InfoFuture =
        TransmuxTranscodeHelper.getMp4Info(
            context,
            checkNotNull(firstEditedMediaItem.mediaItem.localConfiguration).uri.toString(),
            trimStartTimeUs);
    Futures.addCallback(
        getMp4InfoFuture,
        new FutureCallback<Mp4Info>() {
          @Override
          public void onSuccess(Mp4Info mp4Info) {
            if (mp4Info.firstSyncSampleTimestampUsAfterTimeUs == C.TIME_UNSET) {
              exportResultBuilder.setOptimizationResult(OPTIMIZATION_ABANDONED_OTHER);
              processFullInput();
              return;
            }
            if (mp4Info.firstSyncSampleTimestampUsAfterTimeUs == C.TIME_END_OF_SOURCE
                || (trimEndTimeUs != C.TIME_END_OF_SOURCE
                    && trimEndTimeUs < mp4Info.firstSyncSampleTimestampUsAfterTimeUs)) {
              exportResultBuilder.setOptimizationResult(
                  OPTIMIZATION_ABANDONED_KEYFRAME_PLACEMENT_OPTIMAL_FOR_TRIM);
              processFullInput();
              return;
            }
            long maxEncodedAudioBufferDurationUs = 0;
            if (mp4Info.audioFormat != null && mp4Info.audioFormat.sampleRate != Format.NO_VALUE) {
              maxEncodedAudioBufferDurationUs =
                  Util.sampleCountToDurationUs(
                      AAC_LC_AUDIO_SAMPLE_COUNT, mp4Info.audioFormat.sampleRate);
            }
            if (mp4Info.firstSyncSampleTimestampUsAfterTimeUs
                == mp4Info.firstVideoSampleTimestampUs) {
              // The video likely includes an edit list. For example, an edit list adds 1_000ms to
              // each video sample and the trim position is from 100ms, the first sample would be
              // at 1_000ms, the first sync sample after 100ms would also be at 1_000ms; but in this
              // case processing should start from 100ms rather than 1_000ms. The resulting video
              // should be 100ms shorter than the original video, and the first video timestamp
              // should have timestamp at 900ms.
              Transformer.this.composition =
                  buildUponCompositionForTrimOptimization(
                      composition,
                      trimStartTimeUs,
                      trimEndTimeUs,
                      mp4Info.durationUs,
                      /* startsAtKeyFrame= */ true,
                      /* clearVideoEffects= */ false);
              exportResultBuilder.setOptimizationResult(
                  OPTIMIZATION_ABANDONED_KEYFRAME_PLACEMENT_OPTIMAL_FOR_TRIM);
              processFullInput();
              return;
            }
            // Ensure there is an audio sample to mux between the two clip times to prevent
            // Transformer from hanging because it received an audio track but no audio samples.
            if (mp4Info.firstSyncSampleTimestampUsAfterTimeUs - trimStartTimeUs
                    <= maxEncodedAudioBufferDurationUs
                || mp4Info.isFirstVideoSampleAfterTimeUsSyncSample) {
              Transformer.this.composition =
                  buildUponCompositionForTrimOptimization(
                      composition,
                      /* startTimeUs= */ mp4Info.firstSyncSampleTimestampUsAfterTimeUs,
                      trimEndTimeUs,
                      mp4Info.durationUs,
                      /* startsAtKeyFrame= */ true,
                      /* clearVideoEffects= */ false);
              exportResultBuilder.setOptimizationResult(
                  OPTIMIZATION_ABANDONED_KEYFRAME_PLACEMENT_OPTIMAL_FOR_TRIM);
              processFullInput();
              return;
            }
            remuxingMuxerWrapper =
                new MuxerWrapper(
                    checkNotNull(outputFilePath),
                    muxerFactory,
                    componentListener,
                    MuxerWrapper.MUXER_MODE_MUX_PARTIAL,
                    /* dropSamplesBeforeFirstVideoSample= */ false,
                    mp4Info.videoFormat,
                    /* writeNegativeTimestampsToEditList= */ false);
            if (shouldTranscodeVideo(
                    checkNotNull(mp4Info.videoFormat),
                    composition,
                    /* sequenceIndex= */ 0,
                    transformationRequest,
                    encoderFactory,
                    remuxingMuxerWrapper)
                || (mp4Info.audioFormat != null
                    && shouldTranscodeAudio(
                        mp4Info.audioFormat,
                        composition,
                        /* sequenceIndex= */ 0,
                        transformationRequest,
                        encoderFactory,
                        remuxingMuxerWrapper))) {
              remuxingMuxerWrapper = null;
              exportResultBuilder.setOptimizationResult(
                  OPTIMIZATION_ABANDONED_TRIM_AND_TRANSCODING_TRANSFORMATION_REQUESTED);
              processFullInput();
              return;
            }

            Transformer.this.mediaItemInfo = mp4Info;
            maybeSetMuxerWrapperAdditionalRotationDegrees(
                remuxingMuxerWrapper,
                firstEditedMediaItem.effects.videoEffects,
                checkNotNull(mp4Info.videoFormat));
            Composition trancodeComposition =
                buildUponCompositionForTrimOptimization(
                    composition,
                    trimStartTimeUs,
                    /* endTimeUs= */ mp4Info.firstSyncSampleTimestampUsAfterTimeUs,
                    /* mediaDurationUs= */ mp4Info.durationUs,
                    /* startsAtKeyFrame= */ false,
                    /* clearVideoEffects= */ true);
            startInternal(
                trancodeComposition,
                checkNotNull(remuxingMuxerWrapper),
                componentListener,
                /* initialTimestampOffsetUs= */ 0,
                /* useDefaultAssetLoaderFactory= */ false);
          }

          @Override
          public void onFailure(Throwable t) {
            exportResultBuilder.setOptimizationResult(OPTIMIZATION_FAILED_EXTRACTION_FAILED);
            processFullInput();
          }
        },
        applicationHandler::post);
  }

  private void remuxRemainingMedia() {
    transformerState = TRANSFORMER_STATE_REMUX_REMAINING_MEDIA;
    EditedMediaItem firstEditedMediaItem =
        checkNotNull(composition).sequences.get(0).editedMediaItems.get(0);
    Mp4Info mediaItemInfo = checkNotNull(this.mediaItemInfo);
    long trimStartTimeUs = firstEditedMediaItem.mediaItem.clippingConfiguration.startPositionUs;
    long trimEndTimeUs = firstEditedMediaItem.mediaItem.clippingConfiguration.endPositionUs;
    Composition transmuxComposition =
        buildUponCompositionForTrimOptimization(
            composition,
            mediaItemInfo.firstSyncSampleTimestampUsAfterTimeUs,
            trimEndTimeUs,
            mediaItemInfo.durationUs,
            /* startsAtKeyFrame= */ true,
            /* clearVideoEffects= */ true);
    checkNotNull(remuxingMuxerWrapper);
    remuxingMuxerWrapper.changeToAppendMode();
    startInternal(
        transmuxComposition,
        remuxingMuxerWrapper,
        componentListener,
        /* initialTimestampOffsetUs= */ mediaItemInfo.firstSyncSampleTimestampUsAfterTimeUs
            - trimStartTimeUs,
        /* useDefaultAssetLoaderFactory= */ false);
  }

  private boolean isMultiAsset() {
    return checkNotNull(composition).sequences.size() > 1
        || composition.sequences.get(0).editedMediaItems.size() > 1;
  }

  private void verifyApplicationThread() {
    if (Looper.myLooper() != looper) {
      throw new IllegalStateException("Transformer is accessed on the wrong thread.");
    }
  }

  private boolean canCollectEditingMetrics() {
    return SDK_INT >= 35 && usePlatformDiagnostics;
  }

  private void startInternal(
      Composition composition,
      MuxerWrapper muxerWrapper,
      ComponentListener componentListener,
      long initialTimestampOffsetUs,
      boolean useDefaultAssetLoaderFactory) {
    checkState(transformerInternal == null, "There is already an export in progress.");
    TransformationRequest transformationRequest = this.transformationRequest;
    if (composition.hdrMode != Composition.HDR_MODE_KEEP_HDR) {
      transformationRequest =
          transformationRequest.buildUpon().setHdrMode(composition.hdrMode).build();
    }
    LogSessionId logSessionId = null;
    if (canCollectEditingMetrics()) {
      @Nullable String muxerName = null;
      if (muxerFactory instanceof InAppMp4Muxer.Factory) {
        muxerName = InAppMp4Muxer.MUXER_NAME;
      } else if (muxerFactory instanceof InAppFragmentedMp4Muxer.Factory) {
        muxerName = InAppFragmentedMp4Muxer.MUXER_NAME;
      } else if (muxerFactory instanceof DefaultMuxer.Factory) {
        muxerName = DefaultMuxer.MUXER_NAME;
      }
      EditingMetricsCollector.MetricsReporter metricsReporter =
          checkNotNull(metricsReporterFactory).create();
      if (metricsReporter instanceof EditingMetricsCollector.DefaultMetricsReporter) {
        logSessionId =
            ((EditingMetricsCollector.DefaultMetricsReporter) metricsReporter).getLogSessionId();
      }
      editingMetricsCollector =
          new EditingMetricsCollector(metricsReporter, EXPORTER_NAME, muxerName);
    }
    FallbackListener fallbackListener =
        new FallbackListener(composition, listeners, applicationHandler, transformationRequest);
    AssetLoader.Factory assetLoaderFactory = this.assetLoaderFactory;
    if (useDefaultAssetLoaderFactory || assetLoaderFactory == null) {
      assetLoaderFactory =
          new DefaultAssetLoaderFactory(
              context, new DefaultDecoderFactory.Builder(context).build(), clock, logSessionId);
    }
    DebugTraceUtil.reset();
    transformerInternal =
        new TransformerInternal(
            context,
            composition,
            transformationRequest,
            assetLoaderFactory,
            audioMixerFactory,
            videoFrameProcessorFactory,
            encoderFactory,
            allowedEncodingRotationDegrees,
            maxFramesInEncoder,
            muxerWrapper,
            componentListener,
            fallbackListener,
            applicationHandler,
            debugViewProvider,
            clock,
            initialTimestampOffsetUs,
            logSessionId,
            shouldApplyMp4EditListTrim());
    transformerInternal.start();
  }

  private void onExportCompletedWithSuccess() {
    maybeStopExportWatchdogTimer();
    ExportResult exportResult = exportResultBuilder.build();
    listeners.queueEvent(
        /* eventFlag= */ C.INDEX_UNSET,
        listener -> listener.onCompleted(checkNotNull(composition), exportResult));
    listeners.flushEvents();
    if (canCollectEditingMetrics()) {
      checkNotNull(editingMetricsCollector).onExportSuccess(exportResult);
    }
    transformerState = TRANSFORMER_STATE_PROCESS_FULL_INPUT;
  }

  private void onExportCompletedWithError(ExportException exception) {
    maybeStopExportWatchdogTimer();
    ExportResult exportResult = exportResultBuilder.build();
    listeners.queueEvent(
        /* eventFlag= */ C.INDEX_UNSET,
        listener -> listener.onError(checkNotNull(composition), exportResult, exception));
    listeners.flushEvents();
    if (canCollectEditingMetrics()) {
      ProgressHolder progressHolder = new ProgressHolder();
      int progressState = getProgress(progressHolder);
      int progressPercentage =
          (progressState == PROGRESS_STATE_AVAILABLE)
              ? progressHolder.progress
              : C.PERCENTAGE_UNSET;
      checkNotNull(editingMetricsCollector)
          .onExportError(progressPercentage, exception, exportResult);
    }
    transformerState = TRANSFORMER_STATE_PROCESS_FULL_INPUT;
  }

  private final class ComponentListener
      implements TransformerInternal.Listener, MuxerWrapper.Listener {

    // TransformerInternal.Listener implementation

    @Override
    public void onCompleted(
        ImmutableList<ExportResult.ProcessedInput> processedInputs,
        @Nullable String audioEncoderName,
        @Nullable String videoEncoderName) {
      exportResultBuilder.addProcessedInputs(processedInputs);

      // When an export is resumed, the audio and video encoder name (if any) can comes from
      // different intermittent exports, so set encoder names only when they are available.
      if (audioEncoderName != null) {
        exportResultBuilder.setAudioEncoderName(audioEncoderName);
      }
      if (videoEncoderName != null) {
        exportResultBuilder.setVideoEncoderName(videoEncoderName);
      }

      // TODO: b/213341814 - Add event flags for Transformer events.
      transformerInternal = null;
      if (transformerState == TRANSFORMER_STATE_REMUX_PROCESSED_VIDEO) {
        processRemainingVideo();
      } else if (transformerState == TRANSFORMER_STATE_PROCESS_REMAINING_VIDEO) {
        remuxingMuxerWrapper = null;
        processAudio();
      } else if (transformerState == TRANSFORMER_STATE_PROCESS_AUDIO) {
        copyOutput();
      } else if (transformerState == TRANSFORMER_STATE_PROCESS_MEDIA_START) {
        remuxRemainingMedia();
      } else if (transformerState == TRANSFORMER_STATE_REMUX_REMAINING_MEDIA) {
        mediaItemInfo = null;
        exportResultBuilder.setOptimizationResult(ExportResult.OPTIMIZATION_SUCCEEDED);
        onExportCompletedWithSuccess();
      } else {
        onExportCompletedWithSuccess();
      }
    }

    @Override
    @SuppressWarnings("UngroupedOverloads") // Grouped by interface.
    public void onError(
        ImmutableList<ExportResult.ProcessedInput> processedInputs,
        @Nullable String audioEncoderName,
        @Nullable String videoEncoderName,
        ExportException exportException) {
      if (exportException.errorCode == ERROR_CODE_MUXING_APPEND
          && (isExportTrimOptimization() || isExportResumed())) {
        remuxingMuxerWrapper = null;
        transformerInternal = null;
        exportResultBuilder.reset();
        exportResultBuilder.setOptimizationResult(OPTIMIZATION_FAILED_FORMAT_MISMATCH);
        processFullInput();
        return;
      }

      exportResultBuilder.addProcessedInputs(processedInputs);

      // When an export is resumed, the audio and video encoder name (if any) can comes from
      // different intermittent exports, so set encoder names only when they are available.
      if (audioEncoderName != null) {
        exportResultBuilder.setAudioEncoderName(audioEncoderName);
      }
      if (videoEncoderName != null) {
        exportResultBuilder.setVideoEncoderName(videoEncoderName);
      }

      exportResultBuilder.setExportException(exportException);
      onExportCompletedWithError(exportException);
      transformerInternal = null;
    }

    // MuxerWrapper.Listener implementation

    @Override
    public void onTrackEnded(
        @C.TrackType int trackType, Format format, int averageBitrate, int sampleCount) {
      if (trackType == C.TRACK_TYPE_AUDIO) {
        exportResultBuilder
            .setAudioMimeType(format.sampleMimeType)
            .setAverageAudioBitrate(averageBitrate);
        if (format.channelCount != Format.NO_VALUE) {
          exportResultBuilder.setChannelCount(format.channelCount);
        }
        if (format.sampleRate != Format.NO_VALUE) {
          exportResultBuilder.setSampleRate(format.sampleRate);
        }
      } else if (trackType == C.TRACK_TYPE_VIDEO) {
        exportResultBuilder
            .setVideoMimeType(format.sampleMimeType)
            .setAverageVideoBitrate(averageBitrate)
            .setColorInfo(format.colorInfo)
            .setVideoFrameCount(sampleCount);
        if (format.height != Format.NO_VALUE) {
          exportResultBuilder.setHeight(format.height);
        }
        if (format.width != Format.NO_VALUE) {
          exportResultBuilder.setWidth(format.width);
        }
      }
    }

    @Override
    public void onSampleWrittenOrDropped() {
      if (exportWatchdogTimer != null) {
        exportWatchdogTimer.reset();
      } else {
        checkState(maxDelayBetweenMuxerSamplesMs == C.TIME_UNSET);
      }
    }

    @Override
    public void onEnded(long durationMs, long fileSizeBytes) {
      exportResultBuilder.setDurationMs(durationMs).setFileSizeBytes(fileSizeBytes);
      checkNotNull(transformerInternal).endWithCompletion();
    }

    @Override
    @SuppressWarnings("UngroupedOverloads") // Grouped by interface.
    public void onError(ExportException exportException) {
      checkNotNull(transformerInternal).endWithException(exportException);
    }
  }
}
