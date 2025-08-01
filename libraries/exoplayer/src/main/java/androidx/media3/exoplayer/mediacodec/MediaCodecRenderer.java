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
package androidx.media3.exoplayer.mediacodec;

import static android.os.Build.VERSION.SDK_INT;
import static androidx.media3.common.util.Assertions.checkNotNull;
import static androidx.media3.common.util.Assertions.checkState;
import static androidx.media3.common.util.Assertions.checkStateNotNull;
import static androidx.media3.exoplayer.DecoderReuseEvaluation.DISCARD_REASON_DRM_SESSION_CHANGED;
import static androidx.media3.exoplayer.DecoderReuseEvaluation.DISCARD_REASON_OPERATING_RATE_CHANGED;
import static androidx.media3.exoplayer.DecoderReuseEvaluation.DISCARD_REASON_REUSE_NOT_IMPLEMENTED;
import static androidx.media3.exoplayer.DecoderReuseEvaluation.DISCARD_REASON_WORKAROUND;
import static androidx.media3.exoplayer.DecoderReuseEvaluation.REUSE_RESULT_NO;
import static androidx.media3.exoplayer.DecoderReuseEvaluation.REUSE_RESULT_YES_WITHOUT_RECONFIGURATION;
import static androidx.media3.exoplayer.DecoderReuseEvaluation.REUSE_RESULT_YES_WITH_FLUSH;
import static androidx.media3.exoplayer.DecoderReuseEvaluation.REUSE_RESULT_YES_WITH_RECONFIGURATION;
import static androidx.media3.exoplayer.source.SampleStream.FLAG_OMIT_SAMPLE_DATA;
import static androidx.media3.exoplayer.source.SampleStream.FLAG_PEEK;
import static androidx.media3.exoplayer.source.SampleStream.FLAG_REQUIRE_FORMAT;
import static java.lang.Math.max;
import static java.lang.annotation.ElementType.TYPE_USE;

import android.media.MediaCodec;
import android.media.MediaCodec.CodecException;
import android.media.MediaCrypto;
import android.media.MediaCryptoException;
import android.media.MediaFormat;
import android.media.metrics.LogSessionId;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import androidx.annotation.CallSuper;
import androidx.annotation.CheckResult;
import androidx.annotation.IntDef;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.util.Assertions;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.TimedValueQueue;
import androidx.media3.common.util.TraceUtil;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.decoder.CryptoConfig;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.decoder.DecoderInputBuffer.InsufficientCapacityException;
import androidx.media3.exoplayer.BaseRenderer;
import androidx.media3.exoplayer.DecoderCounters;
import androidx.media3.exoplayer.DecoderReuseEvaluation;
import androidx.media3.exoplayer.DecoderReuseEvaluation.DecoderDiscardReasons;
import androidx.media3.exoplayer.ExoPlaybackException;
import androidx.media3.exoplayer.FormatHolder;
import androidx.media3.exoplayer.analytics.PlayerId;
import androidx.media3.exoplayer.audio.OggOpusAudioPacketizer;
import androidx.media3.exoplayer.drm.DrmSession;
import androidx.media3.exoplayer.drm.DrmSession.DrmSessionException;
import androidx.media3.exoplayer.drm.FrameworkCryptoConfig;
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil.DecoderQueryException;
import androidx.media3.exoplayer.source.MediaPeriod;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.SampleStream;
import androidx.media3.exoplayer.source.SampleStream.ReadDataResult;
import androidx.media3.exoplayer.source.SampleStream.ReadFlags;
import androidx.media3.extractor.OpusUtil;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Objects;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.RequiresNonNull;

/** An abstract renderer that uses {@link MediaCodec} to decode samples for rendering. */
//
// The input media in the SampleStreams and the behavior of MediaCodec are outside of the control of
// this class and we try to make as few assumptions as possible.
//
// Assumptions about the input streams:
// - The first stream may pre-roll samples from a keyframe preceding the start time. Subsequent
//   streams added via replaceSampleStream are always fully rendered.
//
// Assumptions about the codec output:
// - Output timestamps from one stream are monotonically increasing.
// - Output samples from the first stream with less than the declared start time are pre-rolled
//   samples that are meant to be dropped. Output samples from subsequent streams are always fully
//   rendered.
// - There is exactly one last output sample with a timestamp greater or equal to the largest input
//   sample timestamp of this stream.
//
// Explicit non-assumptions this class accepts as valid behavior:
// - Input sample timestamps may not be monotonically increasing (e.g. for B-frames).
// - Input and output sample timestamps may be less than the declared stream start time.
// - Input and output sample timestamps may exceed the stream start time of the next stream.
//   (The points above imply that output sample timestamps may jump backwards at stream transitions)
// - Input and output sample timestamps may not be the same.
// - The number of output samples may be different from the number of input samples.
@UnstableApi
public abstract class MediaCodecRenderer extends BaseRenderer {

  /** Thrown when a failure occurs instantiating a decoder. */
  public static class DecoderInitializationException extends Exception {

    private static final int CUSTOM_ERROR_CODE_BASE = -50000;
    private static final int NO_SUITABLE_DECODER_ERROR = CUSTOM_ERROR_CODE_BASE + 1;
    private static final int DECODER_QUERY_ERROR = CUSTOM_ERROR_CODE_BASE + 2;

    /** The MIME type for which a decoder was being initialized. */
    @Nullable public final String mimeType;

    /** Whether it was required that the decoder support a secure output path. */
    public final boolean secureDecoderRequired;

    /**
     * The {@link MediaCodecInfo} of the decoder that failed to initialize. Null if no suitable
     * decoder was found.
     */
    @Nullable public final MediaCodecInfo codecInfo;

    /** An optional developer-readable diagnostic information string. May be null. */
    @Nullable public final String diagnosticInfo;

    /**
     * If the decoder failed to initialize and another decoder being used as a fallback also failed
     * to initialize, the {@link DecoderInitializationException} for the fallback decoder. Null if
     * there was no fallback decoder or no suitable decoders were found.
     */
    @Nullable public final DecoderInitializationException fallbackDecoderInitializationException;

    public DecoderInitializationException(
        Format format, @Nullable Throwable cause, boolean secureDecoderRequired, int errorCode) {
      this(
          "Decoder init failed: [" + errorCode + "], " + format,
          cause,
          format.sampleMimeType,
          secureDecoderRequired,
          /* mediaCodecInfo= */ null,
          buildCustomDiagnosticInfo(errorCode),
          /* fallbackDecoderInitializationException= */ null);
    }

    public DecoderInitializationException(
        Format format,
        @Nullable Throwable cause,
        boolean secureDecoderRequired,
        MediaCodecInfo mediaCodecInfo) {
      this(
          "Decoder init failed: " + mediaCodecInfo.name + ", " + format,
          cause,
          format.sampleMimeType,
          secureDecoderRequired,
          mediaCodecInfo,
          (cause instanceof CodecException) ? ((CodecException) cause).getDiagnosticInfo() : null,
          /* fallbackDecoderInitializationException= */ null);
    }

    private DecoderInitializationException(
        @Nullable String message,
        @Nullable Throwable cause,
        @Nullable String mimeType,
        boolean secureDecoderRequired,
        @Nullable MediaCodecInfo mediaCodecInfo,
        @Nullable String diagnosticInfo,
        @Nullable DecoderInitializationException fallbackDecoderInitializationException) {
      super(message, cause);
      this.mimeType = mimeType;
      this.secureDecoderRequired = secureDecoderRequired;
      this.codecInfo = mediaCodecInfo;
      this.diagnosticInfo = diagnosticInfo;
      this.fallbackDecoderInitializationException = fallbackDecoderInitializationException;
    }

    @CheckResult
    private DecoderInitializationException copyWithFallbackException(
        DecoderInitializationException fallbackException) {
      return new DecoderInitializationException(
          getMessage(),
          getCause(),
          mimeType,
          secureDecoderRequired,
          codecInfo,
          diagnosticInfo,
          fallbackException);
    }

    private static String buildCustomDiagnosticInfo(int errorCode) {
      String sign = errorCode < 0 ? "neg_" : "";
      String packageName = "androidx.media3.exoplayer.mediacodec";
      return packageName + ".MediaCodecRenderer_" + sign + Math.abs(errorCode);
    }
  }

  /** Indicates no codec operating rate should be set. */
  protected static final float CODEC_OPERATING_RATE_UNSET = -1;

  private static final String TAG = "MediaCodecRenderer";

  /**
   * If the {@link MediaCodec} is hotswapped (i.e. replaced during playback), this is the period of
   * time during which {@link #isReady()} will report true regardless of whether the new codec has
   * output frames that are ready to be rendered.
   *
   * <p>This allows codec hotswapping to be performed seamlessly, without interrupting the playback
   * of other renderers, provided the new codec is able to decode some frames within this time
   * period.
   */
  private static final long MAX_CODEC_HOTSWAP_TIME_MS = 1000;

  @Documented
  @Retention(RetentionPolicy.SOURCE)
  @Target(TYPE_USE)
  @IntDef({
    RECONFIGURATION_STATE_NONE,
    RECONFIGURATION_STATE_WRITE_PENDING,
    RECONFIGURATION_STATE_QUEUE_PENDING
  })
  private @interface ReconfigurationState {}

  /** There is no pending adaptive reconfiguration work. */
  private static final int RECONFIGURATION_STATE_NONE = 0;

  /** Codec configuration data needs to be written into the next buffer. */
  private static final int RECONFIGURATION_STATE_WRITE_PENDING = 1;

  /**
   * Codec configuration data has been written into the next buffer, but that buffer still needs to
   * be returned to the codec.
   */
  private static final int RECONFIGURATION_STATE_QUEUE_PENDING = 2;

  @Documented
  @Retention(RetentionPolicy.SOURCE)
  @Target(TYPE_USE)
  @IntDef({DRAIN_STATE_NONE, DRAIN_STATE_SIGNAL_END_OF_STREAM, DRAIN_STATE_WAIT_END_OF_STREAM})
  private @interface DrainState {}

  /** The codec is not being drained. */
  private static final int DRAIN_STATE_NONE = 0;

  /** The codec needs to be drained, but we haven't signaled an end of stream to it yet. */
  private static final int DRAIN_STATE_SIGNAL_END_OF_STREAM = 1;

  /** The codec needs to be drained, and we're waiting for it to output an end of stream. */
  private static final int DRAIN_STATE_WAIT_END_OF_STREAM = 2;

  @Documented
  @Retention(RetentionPolicy.SOURCE)
  @Target(TYPE_USE)
  @IntDef({
    DRAIN_ACTION_NONE,
    DRAIN_ACTION_FLUSH,
    DRAIN_ACTION_FLUSH_AND_UPDATE_DRM_SESSION,
    DRAIN_ACTION_REINITIALIZE
  })
  private @interface DrainAction {}

  /** No special action should be taken. */
  private static final int DRAIN_ACTION_NONE = 0;

  /** The codec should be flushed. */
  private static final int DRAIN_ACTION_FLUSH = 1;

  /** The codec should be flushed and updated to use the pending DRM session. */
  private static final int DRAIN_ACTION_FLUSH_AND_UPDATE_DRM_SESSION = 2;

  /** The codec should be reinitialized. */
  private static final int DRAIN_ACTION_REINITIALIZE = 3;

  @Documented
  @Retention(RetentionPolicy.SOURCE)
  @Target(TYPE_USE)
  @IntDef({
    ADAPTATION_WORKAROUND_MODE_NEVER,
    ADAPTATION_WORKAROUND_MODE_SAME_RESOLUTION,
    ADAPTATION_WORKAROUND_MODE_ALWAYS
  })
  private @interface AdaptationWorkaroundMode {}

  /** The adaptation workaround is never used. */
  private static final int ADAPTATION_WORKAROUND_MODE_NEVER = 0;

  /**
   * The adaptation workaround is used when adapting between formats of the same resolution only.
   */
  private static final int ADAPTATION_WORKAROUND_MODE_SAME_RESOLUTION = 1;

  /** The adaptation workaround is always used when adapting between formats. */
  private static final int ADAPTATION_WORKAROUND_MODE_ALWAYS = 2;

  /**
   * H.264/AVC buffer to queue when using the adaptation workaround (see {@link
   * #codecAdaptationWorkaroundMode(String)}. Consists of three NAL units with start codes: Baseline
   * sequence/picture parameter sets and a 32 * 32 pixel IDR slice. This stream can be queued to
   * force a resolution change when adapting to a new format.
   */
  private static final byte[] ADAPTATION_WORKAROUND_BUFFER =
      new byte[] {
        0, 0, 1, 103, 66, -64, 11, -38, 37, -112, 0, 0, 1, 104, -50, 15, 19, 32, 0, 0, 1, 101, -120,
        -124, 13, -50, 113, 24, -96, 0, 47, -65, 28, 49, -61, 39, 93, 120
      };

  private static final int ADAPTATION_WORKAROUND_SLICE_WIDTH_HEIGHT = 32;

  private final MediaCodecAdapter.Factory codecAdapterFactory;
  private final MediaCodecSelector mediaCodecSelector;
  private final boolean enableDecoderFallback;
  private final float assumedMinimumCodecOperatingRate;
  private final DecoderInputBuffer noDataBuffer;
  private final DecoderInputBuffer buffer;
  private final DecoderInputBuffer bypassSampleBuffer;
  private final BatchBuffer bypassBatchBuffer;
  private final MediaCodec.BufferInfo outputBufferInfo;
  private final ArrayDeque<OutputStreamInfo> pendingOutputStreamChanges;
  private final OggOpusAudioPacketizer oggOpusAudioPacketizer;

  @Nullable private Format inputFormat;
  private @MonotonicNonNull Format outputFormat;
  @Nullable private DrmSession codecDrmSession;
  @Nullable private DrmSession sourceDrmSession;
  private @MonotonicNonNull WakeupListener wakeupListener;

  /**
   * A framework {@link MediaCrypto} for use with {@link MediaCodec#queueSecureInputBuffer(int, int,
   * MediaCodec.CryptoInfo, long, int)} to play encrypted content.
   *
   * <p>Non-null if framework decryption is being used (i.e. {@link DrmSession#getCryptoConfig()
   * codecDrmSession.getCryptoConfig()} returns an instance of {@link FrameworkCryptoConfig}).
   *
   * <p>Can be null if the content is not encrypted (in which case {@link #codecDrmSession} and
   * {@link #sourceDrmSession} will also be null), or if decryption is happening without framework
   * support ({@link #codecDrmSession} and {@link #sourceDrmSession} will be non-null).
   */
  @Nullable private MediaCrypto mediaCrypto;

  private long renderTimeLimitMs;
  private float currentPlaybackSpeed;
  private float targetPlaybackSpeed;
  @Nullable private MediaCodecAdapter codec;
  @Nullable private Format codecInputFormat;
  @Nullable private MediaFormat codecOutputMediaFormat;
  private boolean codecOutputMediaFormatChanged;
  private float codecOperatingRate;
  @Nullable private ArrayDeque<MediaCodecInfo> availableCodecInfos;
  @Nullable private DecoderInitializationException preferredDecoderInitializationException;
  @Nullable private MediaCodecInfo codecInfo;
  private @AdaptationWorkaroundMode int codecAdaptationWorkaroundMode;
  private boolean codecNeedsSosFlushWorkaround;
  private boolean codecNeedsEosFlushWorkaround;
  private boolean codecNeedsAdaptationWorkaroundBuffer;
  private boolean shouldSkipAdaptationWorkaroundOutputBuffer;
  private boolean codecNeedsEosPropagation;
  private long lastOutputBufferProcessedRealtimeMs;
  private boolean codecRegisteredOnBufferAvailableListener;
  private long codecHotswapDeadlineMs;
  private int inputIndex;
  private int outputIndex;
  @Nullable private ByteBuffer outputBuffer;
  private boolean isDecodeOnlyOutputBuffer;
  private boolean isLastOutputBuffer;
  private boolean bypassEnabled;
  private boolean bypassSampleBufferPending;
  private boolean bypassDrainAndReinitialize;
  private boolean codecReconfigured;
  private @ReconfigurationState int codecReconfigurationState;
  private @DrainState int codecDrainState;
  private @DrainAction int codecDrainAction;
  private boolean codecReceivedBuffers;
  private boolean codecReceivedEos;
  private boolean codecHasOutputMediaFormat;
  private long largestQueuedPresentationTimeUs;
  private long lastBufferInStreamPresentationTimeUs;
  private boolean inputStreamEnded;
  private boolean outputStreamEnded;
  private boolean waitingForFirstSampleInFormat;
  private boolean pendingOutputEndOfStream;
  @Nullable private ExoPlaybackException pendingPlaybackException;
  protected DecoderCounters decoderCounters;
  private OutputStreamInfo outputStreamInfo;
  private long lastProcessedOutputBufferTimeUs;
  private boolean needToNotifyOutputFormatChangeAfterStreamChange;
  private boolean experimentalEnableProcessedStreamChangedAtStart;
  private boolean hasSkippedFlushAndWaitingForQueueInputBuffer;
  private long skippedFlushOffsetUs;

  /**
   * @param trackType The {@link C.TrackType track type} that the renderer handles.
   * @param codecAdapterFactory A factory for {@link MediaCodecAdapter} instances.
   * @param mediaCodecSelector A decoder selector.
   * @param enableDecoderFallback Whether to enable fallback to lower-priority decoders if decoder
   *     initialization fails. This may result in using a decoder that is less efficient or slower
   *     than the primary decoder.
   * @param assumedMinimumCodecOperatingRate A codec operating rate that all codecs instantiated by
   *     this renderer are assumed to meet implicitly (i.e. without the operating rate being set
   *     explicitly using {@link MediaFormat#KEY_OPERATING_RATE}).
   */
  public MediaCodecRenderer(
      @C.TrackType int trackType,
      MediaCodecAdapter.Factory codecAdapterFactory,
      MediaCodecSelector mediaCodecSelector,
      boolean enableDecoderFallback,
      float assumedMinimumCodecOperatingRate) {
    super(trackType);
    this.codecAdapterFactory = codecAdapterFactory;
    this.mediaCodecSelector = checkNotNull(mediaCodecSelector);
    this.enableDecoderFallback = enableDecoderFallback;
    this.assumedMinimumCodecOperatingRate = assumedMinimumCodecOperatingRate;
    noDataBuffer = DecoderInputBuffer.newNoDataInstance();
    buffer = new DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DISABLED);
    bypassSampleBuffer = new DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DIRECT);
    bypassBatchBuffer = new BatchBuffer();
    outputBufferInfo = new MediaCodec.BufferInfo();
    currentPlaybackSpeed = 1f;
    targetPlaybackSpeed = 1f;
    renderTimeLimitMs = C.TIME_UNSET;
    pendingOutputStreamChanges = new ArrayDeque<>();
    outputStreamInfo = OutputStreamInfo.UNSET;
    // MediaCodec outputs audio buffers in native endian:
    // https://developer.android.com/reference/android/media/MediaCodec#raw-audio-buffers
    // and code called from MediaCodecAudioRenderer.processOutputBuffer expects this endianness.
    // Call ensureSpaceForWrite to make sure the buffer has non-null data, and set the expected
    // endianness.
    bypassBatchBuffer.ensureSpaceForWrite(/* length= */ 0);
    bypassBatchBuffer.data.order(ByteOrder.nativeOrder());
    oggOpusAudioPacketizer = new OggOpusAudioPacketizer();

    codecOperatingRate = CODEC_OPERATING_RATE_UNSET;
    codecAdaptationWorkaroundMode = ADAPTATION_WORKAROUND_MODE_NEVER;
    codecReconfigurationState = RECONFIGURATION_STATE_NONE;
    inputIndex = C.INDEX_UNSET;
    outputIndex = C.INDEX_UNSET;
    codecHotswapDeadlineMs = C.TIME_UNSET;
    largestQueuedPresentationTimeUs = C.TIME_UNSET;
    lastBufferInStreamPresentationTimeUs = C.TIME_UNSET;
    lastProcessedOutputBufferTimeUs = C.TIME_UNSET;
    lastOutputBufferProcessedRealtimeMs = C.TIME_UNSET;
    codecDrainState = DRAIN_STATE_NONE;
    codecDrainAction = DRAIN_ACTION_NONE;
    decoderCounters = new DecoderCounters();
    hasSkippedFlushAndWaitingForQueueInputBuffer = false;
    skippedFlushOffsetUs = 0;
  }

  /**
   * Sets a limit on the time a single {@link #render(long, long)} call can spend draining and
   * filling the decoder.
   *
   * <p>This method should be called right after creating an instance of this class.
   *
   * @param renderTimeLimitMs The render time limit in milliseconds, or {@link C#TIME_UNSET} for no
   *     limit.
   */
  public void setRenderTimeLimitMs(long renderTimeLimitMs) {
    this.renderTimeLimitMs = renderTimeLimitMs;
  }

  @Override
  public final @AdaptiveSupport int supportsMixedMimeTypeAdaptation() {
    return ADAPTIVE_NOT_SEAMLESS;
  }

  @Override
  public final @Capabilities int supportsFormat(Format format) throws ExoPlaybackException {
    try {
      return supportsFormat(mediaCodecSelector, format);
    } catch (DecoderQueryException e) {
      throw createRendererException(e, format, PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED);
    }
  }

  /**
   * Returns the {@link Capabilities} for the given {@link Format}.
   *
   * @param mediaCodecSelector The decoder selector.
   * @param format The {@link Format}.
   * @return The {@link Capabilities} for this {@link Format}.
   * @throws DecoderQueryException If there was an error querying decoders.
   */
  protected abstract @Capabilities int supportsFormat(
      MediaCodecSelector mediaCodecSelector, Format format) throws DecoderQueryException;

  @Override
  public final long getDurationToProgressUs(long positionUs, long elapsedRealtimeUs) {
    return getDurationToProgressUs(
        positionUs,
        elapsedRealtimeUs,
        /* isOnBufferAvailableListenerRegistered= */ codecRegisteredOnBufferAvailableListener);
  }

  /**
   * Enables the renderer to invoke {@link #onProcessedStreamChange()} on the first stream.
   *
   * <p>When not enabled, {@link #onProcessedStreamChange()} is invoked from the second stream
   * onwards.
   */
  public void experimentalEnableProcessedStreamChangedAtStart() {
    this.experimentalEnableProcessedStreamChangedAtStart = true;
  }

  /**
   * Returns minimum time playback must advance in order for the {@link #render} call to make
   * progress.
   *
   * <p>If the {@code Renderer} has a registered {@link
   * MediaCodecAdapter.OnBufferAvailableListener}, then the {@code Renderer} will be notified when
   * decoder input and output buffers become available. These callbacks may affect the calculated
   * minimum time playback must advance before a {@link #render} call can make progress.
   *
   * @param positionUs The current media time in microseconds, measured at the start of the current
   *     iteration of the rendering loop.
   * @param elapsedRealtimeUs {@link android.os.SystemClock#elapsedRealtime()} in microseconds,
   *     measured at the start of the current iteration of the rendering loop.
   * @param isOnBufferAvailableListenerRegistered Whether the {@code Renderer} is using a {@link
   *     MediaCodecAdapter} with successfully registered {@link
   *     MediaCodecAdapter.OnBufferAvailableListener OnBufferAvailableListener}.
   * @return minimum time playback must advance before renderer is able to make progress.
   */
  protected long getDurationToProgressUs(
      long positionUs, long elapsedRealtimeUs, boolean isOnBufferAvailableListenerRegistered) {
    return super.getDurationToProgressUs(positionUs, elapsedRealtimeUs);
  }

  /**
   * Returns a list of decoders that can decode media in the specified format, in priority order.
   *
   * @param mediaCodecSelector The decoder selector.
   * @param format The {@link Format} for which a decoder is required.
   * @param requiresSecureDecoder Whether a secure decoder is required.
   * @return A list of {@link MediaCodecInfo}s corresponding to decoders. May be empty.
   * @throws DecoderQueryException Thrown if there was an error querying decoders.
   */
  protected abstract List<MediaCodecInfo> getDecoderInfos(
      MediaCodecSelector mediaCodecSelector, Format format, boolean requiresSecureDecoder)
      throws DecoderQueryException;

  /**
   * Returns the {@link MediaCodecAdapter.Configuration} that will be used to create and configure a
   * {@link MediaCodec} to decode the given {@link Format} for a playback.
   *
   * @param codecInfo Information about the {@link MediaCodec} being configured.
   * @param format The {@link Format} for which the codec is being configured.
   * @param crypto For drm protected playbacks, a {@link MediaCrypto} to use for decryption.
   * @param codecOperatingRate The codec operating rate, or {@link #CODEC_OPERATING_RATE_UNSET} if
   *     no codec operating rate should be set.
   * @return The parameters needed to call {@link MediaCodec#configure}.
   */
  protected abstract MediaCodecAdapter.Configuration getMediaCodecConfiguration(
      MediaCodecInfo codecInfo,
      Format format,
      @Nullable MediaCrypto crypto,
      float codecOperatingRate);

  protected final void maybeInitCodecOrBypass() throws ExoPlaybackException {
    if (codec != null || bypassEnabled || inputFormat == null) {
      // We have a codec, are bypassing it, or don't have a format to decide how to render.
      return;
    }
    Format inputFormat = this.inputFormat;

    if (isBypassPossible(inputFormat)) {
      initBypass(inputFormat);
      return;
    }

    setCodecDrmSession(sourceDrmSession);
    if (codecDrmSession == null || initMediaCryptoIfDrmSessionReady()) {
      try {
        boolean mediaCryptoRequiresSecureDecoder =
            codecDrmSession != null
                && (codecDrmSession.getState() == DrmSession.STATE_OPENED
                    || codecDrmSession.getState() == DrmSession.STATE_OPENED_WITH_KEYS)
                && codecDrmSession.requiresSecureDecoder(
                    checkStateNotNull(inputFormat.sampleMimeType));
        maybeInitCodecWithFallback(mediaCrypto, mediaCryptoRequiresSecureDecoder);
      } catch (DecoderInitializationException e) {
        throw createRendererException(
            e, inputFormat, PlaybackException.ERROR_CODE_DECODER_INIT_FAILED);
      }
    }
    if (mediaCrypto != null && codec == null) {
      // mediaCrypto was created, but a codec wasn't, so release the mediaCrypto before returning.
      mediaCrypto.release();
      mediaCrypto = null;
    }
  }

  /**
   * Returns whether buffers in the input format can be processed without a codec.
   *
   * <p>This method returns the possibility of bypass mode with checking both the renderer
   * capabilities and DRM protection.
   *
   * @param format The input {@link Format}.
   * @return Whether playback bypassing {@link MediaCodec} is possible.
   */
  protected final boolean isBypassPossible(Format format) {
    return sourceDrmSession == null && shouldUseBypass(format);
  }

  /**
   * Returns whether buffers in the input format can be processed without a codec.
   *
   * <p>This method is only called if the content is not DRM protected, because if the content is
   * DRM protected use of bypass is never possible.
   *
   * @param format The input {@link Format}.
   * @return Whether playback bypassing {@link MediaCodec} is supported.
   */
  protected boolean shouldUseBypass(Format format) {
    return false;
  }

  protected boolean shouldInitCodec(MediaCodecInfo codecInfo) {
    return true;
  }

  /**
   * Returns whether the renderer needs to re-initialize the codec, possibly as a result of a change
   * in device capabilities.
   */
  protected boolean shouldReinitCodec() {
    return false;
  }

  /** Returns whether bypass is enabled by the renderer. */
  protected final boolean isBypassEnabled() {
    return bypassEnabled;
  }

  /**
   * Sets an exception to be re-thrown by render.
   *
   * @param exception The exception.
   */
  protected final void setPendingPlaybackException(ExoPlaybackException exception) {
    pendingPlaybackException = exception;
  }

  /**
   * Updates the output formats for the specified output buffer timestamp, calling {@link
   * #onOutputFormatChanged} if a change has occurred.
   *
   * <p>Subclasses should only call this method if operating in a mode where buffers are not
   * dequeued from the decoder, for example when using video tunneling).
   *
   * @throws ExoPlaybackException Thrown if an error occurs as a result of the output format change.
   */
  protected final void updateOutputFormatForTime(long presentationTimeUs)
      throws ExoPlaybackException {
    boolean outputFormatChanged = false;
    @Nullable Format format = outputStreamInfo.formatQueue.pollFloor(presentationTimeUs);
    if (format == null
        && needToNotifyOutputFormatChangeAfterStreamChange
        && codecOutputMediaFormat != null) {
      // After a stream change or after the initial start, there should be an input format change,
      // which we've not found. Check the Format queue in case the corresponding presentation
      // timestamp is greater than presentationTimeUs, which can happen for some codecs
      // [Internal ref: b/162719047 and https://github.com/google/ExoPlayer/issues/8594].
      format = outputStreamInfo.formatQueue.pollFirst();
    }
    if (format != null) {
      outputFormat = format;
      outputFormatChanged = true;
    }
    if (outputFormatChanged || (codecOutputMediaFormatChanged && outputFormat != null)) {
      onOutputFormatChanged(checkNotNull(outputFormat), codecOutputMediaFormat);
      codecOutputMediaFormatChanged = false;
      needToNotifyOutputFormatChangeAfterStreamChange = false;
    }
  }

  @Nullable
  protected final MediaCodecAdapter getCodec() {
    return codec;
  }

  @Nullable
  protected final Format getCodecInputFormat() {
    return codecInputFormat;
  }

  @Nullable
  protected final MediaFormat getCodecOutputMediaFormat() {
    return codecOutputMediaFormat;
  }

  @Nullable
  protected final MediaCodecInfo getCodecInfo() {
    return codecInfo;
  }

  @Override
  protected void onEnabled(boolean joining, boolean mayRenderStartOfStream)
      throws ExoPlaybackException {
    decoderCounters = new DecoderCounters();
  }

  @Override
  protected void onStreamChanged(
      Format[] formats,
      long startPositionUs,
      long offsetUs,
      MediaSource.MediaPeriodId mediaPeriodId)
      throws ExoPlaybackException {
    if (outputStreamInfo.streamOffsetUs == C.TIME_UNSET) {
      // This is the first stream.
      setOutputStreamInfo(
          new OutputStreamInfo(
              /* previousStreamLastBufferTimeUs= */ C.TIME_UNSET, startPositionUs, offsetUs));
      if (experimentalEnableProcessedStreamChangedAtStart) {
        onProcessedStreamChange();
      }
    } else if (pendingOutputStreamChanges.isEmpty()
        && (largestQueuedPresentationTimeUs == C.TIME_UNSET
            || (lastProcessedOutputBufferTimeUs != C.TIME_UNSET
                && lastProcessedOutputBufferTimeUs >= largestQueuedPresentationTimeUs))) {
      // All previous streams have never queued any samples or have been fully output already.
      setOutputStreamInfo(
          new OutputStreamInfo(
              /* previousStreamLastBufferTimeUs= */ C.TIME_UNSET, startPositionUs, offsetUs));
      if (outputStreamInfo.streamOffsetUs != C.TIME_UNSET) {
        onProcessedStreamChange();
      }
    } else {
      pendingOutputStreamChanges.add(
          new OutputStreamInfo(largestQueuedPresentationTimeUs, startPositionUs, offsetUs));
    }
  }

  @Override
  protected void onPositionReset(long positionUs, boolean joining) throws ExoPlaybackException {
    inputStreamEnded = false;
    outputStreamEnded = false;
    pendingOutputEndOfStream = false;
    if (bypassEnabled) {
      resetBypassState();
    } else {
      flushOrReinitializeCodec();
    }
    // If there is a format change on the input side still pending propagation to the output, we
    // need to queue a format next time a buffer is read. This is because we may not read a new
    // input format after the position reset.
    if (outputStreamInfo.formatQueue.size() > 0) {
      waitingForFirstSampleInFormat = true;
    }
    outputStreamInfo.formatQueue.clear();
    pendingOutputStreamChanges.clear();
  }

  @Override
  public void setPlaybackSpeed(float currentPlaybackSpeed, float targetPlaybackSpeed)
      throws ExoPlaybackException {
    this.currentPlaybackSpeed = currentPlaybackSpeed;
    this.targetPlaybackSpeed = targetPlaybackSpeed;
    updateCodecOperatingRate(codecInputFormat);
  }

  @Override
  protected void onDisabled() {
    inputFormat = null;
    setOutputStreamInfo(OutputStreamInfo.UNSET);
    pendingOutputStreamChanges.clear();
    if (bypassEnabled) {
      disableBypass();
    } else {
      flushOrReleaseCodec();
    }
  }

  @Override
  protected void onReset() {
    try {
      disableBypass();
      releaseCodec();
    } finally {
      setSourceDrmSession(null);
    }
  }

  private void disableBypass() {
    bypassEnabled = false;
    resetBypassState();
  }

  private void resetBypassState() {
    resetCommonStateForFlush();
    bypassDrainAndReinitialize = false;
    bypassBatchBuffer.clear();
    bypassSampleBuffer.clear();
    bypassSampleBufferPending = false;
    oggOpusAudioPacketizer.reset();
  }

  protected void releaseCodec() {
    try {
      if (codec != null) {
        codec.release();
        decoderCounters.decoderReleaseCount++;
        onCodecReleased(checkNotNull(codecInfo).name);
      }
    } finally {
      codec = null;
      try {
        if (mediaCrypto != null) {
          mediaCrypto.release();
        }
      } finally {
        mediaCrypto = null;
        setCodecDrmSession(null);
        resetCodecStateForRelease();
      }
    }
  }

  @Override
  protected void onStarted() {
    // Do nothing. Overridden to remove throws clause.
  }

  @Override
  protected void onStopped() {
    // Do nothing. Overridden to remove throws clause.
  }

  @Override
  public void handleMessage(@MessageType int messageType, @Nullable Object message)
      throws ExoPlaybackException {
    if (messageType == MSG_SET_WAKEUP_LISTENER) {
      wakeupListener = checkNotNull((WakeupListener) message);
    } else {
      super.handleMessage(messageType, message);
    }
  }

  @Override
  public void render(long positionUs, long elapsedRealtimeUs) throws ExoPlaybackException {
    if (pendingOutputEndOfStream) {
      pendingOutputEndOfStream = false;
      processEndOfStream();
    }
    if (pendingPlaybackException != null) {
      ExoPlaybackException playbackException = pendingPlaybackException;
      pendingPlaybackException = null;
      throw playbackException;
    }

    try {
      if (outputStreamEnded) {
        renderToEndOfStream();
        return;
      }
      if (inputFormat == null && !readSourceOmittingSampleData(FLAG_REQUIRE_FORMAT)) {
        // We still don't have a format and can't make progress without one.
        return;
      }
      // We have a format.
      maybeInitCodecOrBypass();
      if (bypassEnabled) {
        TraceUtil.beginSection("bypassRender");
        while (bypassRender(positionUs, elapsedRealtimeUs)) {}
        TraceUtil.endSection();
      } else if (codec != null) {
        long renderStartTimeMs = getClock().elapsedRealtime();
        TraceUtil.beginSection("drainAndFeed");
        while (drainOutputBuffer(positionUs, elapsedRealtimeUs)
            && shouldContinueRendering(renderStartTimeMs)) {}
        while (feedInputBuffer() && shouldContinueRendering(renderStartTimeMs)) {}
        TraceUtil.endSection();
      } else {
        decoderCounters.skippedInputBufferCount += skipSource(positionUs);
        // We need to read any format changes despite not having a codec so that drmSession can be
        // updated, and so that we have the most recent format should the codec be initialized. We
        // may also reach the end of the stream. FLAG_PEEK is used because we don't want to advance
        // the source further than skipSource has already done.
        readSourceOmittingSampleData(FLAG_PEEK);
      }
      decoderCounters.ensureUpdated();
    } catch (MediaCodec.CryptoException e) {
      throw createRendererException(
          e, inputFormat, Util.getErrorCodeForMediaDrmErrorCode(e.getErrorCode()));
    } catch (IllegalStateException e) {
      if (isMediaCodecException(e)) {
        onCodecError(e);
        boolean isRecoverable =
            (e instanceof CodecException) && ((CodecException) e).isRecoverable();
        if (isRecoverable) {
          releaseCodec();
        }
        MediaCodecDecoderException exception = createDecoderException(e, getCodecInfo());
        @PlaybackException.ErrorCode
        int errorCode =
            exception.errorCode == CodecException.ERROR_RECLAIMED
                ? PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED
                : PlaybackException.ERROR_CODE_DECODING_FAILED;
        throw createRendererException(exception, inputFormat, isRecoverable, errorCode);
      }
      throw e;
    }
  }

  /**
   * Flushes the codec. If flushing is not possible, the codec will be released and re-instantiated.
   * This method is a no-op if the codec is {@code null}.
   *
   * <p>The implementation of this method calls {@link #flushOrReleaseCodec()}, and {@link
   * #maybeInitCodecOrBypass()} if the codec needs to be re-instantiated.
   *
   * @return Whether the codec was released and reinitialized, rather than being flushed.
   * @throws ExoPlaybackException If an error occurs re-instantiating the codec.
   */
  protected final boolean flushOrReinitializeCodec() throws ExoPlaybackException {
    boolean released = flushOrReleaseCodec();
    if (released) {
      maybeInitCodecOrBypass();
    }
    return released;
  }

  /**
   * Attempts to flush or release the codec. If flushing is not possible, then the codec will be
   * released. This method is a no-op if the codec is {@code null}.
   *
   * @return Whether the codec was released.
   */
  private boolean flushOrReleaseCodec() {
    if (codec == null) {
      return false;
    }
    if (shouldReleaseCodecInsteadOfFlushing()) {
      releaseCodec();
      return true;
    } else if (shouldFlushCodec()) {
      flushCodec();
    } else {
      hasSkippedFlushAndWaitingForQueueInputBuffer = true;
    }
    return false;
  }

  /**
   * Returns whether the codec should be released rather than flushed.
   *
   * @see #flushOrReleaseCodec
   */
  protected boolean shouldReleaseCodecInsteadOfFlushing() {
    if (codecDrainAction == DRAIN_ACTION_REINITIALIZE
        || (codecNeedsSosFlushWorkaround && !codecHasOutputMediaFormat)
        || (codecNeedsEosFlushWorkaround && codecReceivedEos)) {
      return true;
    }
    if (codecDrainAction == DRAIN_ACTION_FLUSH_AND_UPDATE_DRM_SESSION) {
      // Needed to keep lint happy (it doesn't understand the checkState call alone)
      try {
        updateDrmSession();
      } catch (ExoPlaybackException e) {
        Log.w(TAG, "Failed to update the DRM session, releasing the codec instead.", e);
        return true;
      }
    }
    return false;
  }

  /**
   * Returns whether the codec should be flushed in cases such that the codec was not released.
   *
   * <p>Default is {@code true}.
   *
   * @see #flushOrReleaseCodec
   */
  protected boolean shouldFlushCodec() {
    return true;
  }

  /**
   * Returns the offset added to buffer timestamps prior to decoding.
   *
   * <p>The skippedFlushOffset ensures timestamps are greater than the current
   * "largestQueuedTimestamp" when skipping flushing the codec during a seek.
   */
  protected long getSkippedFlushOffsetUs() {
    return skippedFlushOffsetUs;
  }

  /** Flushes the codec. */
  private void flushCodec() {
    try {
      checkStateNotNull(codec).flush();
    } finally {
      resetCodecStateForFlush();
    }
  }

  /** Resets the renderer internal state used by both bypass and codec modes. */
  private void resetCommonStateForFlush() {
    largestQueuedPresentationTimeUs = C.TIME_UNSET;
    lastBufferInStreamPresentationTimeUs = C.TIME_UNSET;
    lastProcessedOutputBufferTimeUs = C.TIME_UNSET;
  }

  /** Resets the renderer internal state after a codec flush. */
  @CallSuper
  protected void resetCodecStateForFlush() {
    resetInputBuffer();
    resetOutputBuffer();
    resetCommonStateForFlush();
    codecHotswapDeadlineMs = C.TIME_UNSET;
    codecReceivedEos = false;
    lastOutputBufferProcessedRealtimeMs = C.TIME_UNSET;
    codecReceivedBuffers = false;
    codecNeedsAdaptationWorkaroundBuffer = false;
    shouldSkipAdaptationWorkaroundOutputBuffer = false;
    isDecodeOnlyOutputBuffer = false;
    isLastOutputBuffer = false;
    codecDrainState = DRAIN_STATE_NONE;
    codecDrainAction = DRAIN_ACTION_NONE;
    // Reconfiguration data sent shortly before the flush may not have been processed by the
    // decoder. If the codec has been reconfigured we always send reconfiguration data again to
    // guarantee that it's processed.
    codecReconfigurationState =
        codecReconfigured ? RECONFIGURATION_STATE_WRITE_PENDING : RECONFIGURATION_STATE_NONE;
    hasSkippedFlushAndWaitingForQueueInputBuffer = false;
    skippedFlushOffsetUs = 0;
  }

  /**
   * Resets the renderer internal state after a codec release.
   *
   * <p>Note that this only needs to reset state variables that are changed in addition to those
   * already changed in {@link #resetCodecStateForFlush()}.
   */
  @CallSuper
  protected void resetCodecStateForRelease() {
    resetCodecStateForFlush();

    pendingPlaybackException = null;
    availableCodecInfos = null;
    codecInfo = null;
    codecInputFormat = null;
    codecOutputMediaFormat = null;
    codecOutputMediaFormatChanged = false;
    codecHasOutputMediaFormat = false;
    codecOperatingRate = CODEC_OPERATING_RATE_UNSET;
    codecAdaptationWorkaroundMode = ADAPTATION_WORKAROUND_MODE_NEVER;
    codecNeedsSosFlushWorkaround = false;
    codecNeedsEosFlushWorkaround = false;
    codecNeedsEosPropagation = false;
    codecRegisteredOnBufferAvailableListener = false;
    codecReconfigured = false;
    codecReconfigurationState = RECONFIGURATION_STATE_NONE;
  }

  protected MediaCodecDecoderException createDecoderException(
      Throwable cause, @Nullable MediaCodecInfo codecInfo) {
    return new MediaCodecDecoderException(cause, codecInfo);
  }

  /**
   * Reads from the source when sample data is not required. If a format or an end of stream buffer
   * is read, it will be handled before the call returns.
   *
   * @param readFlags Additional {@link ReadFlags}. {@link SampleStream#FLAG_OMIT_SAMPLE_DATA} is
   *     added internally, and so does not need to be passed.
   * @return Whether a format was read and processed.
   */
  private boolean readSourceOmittingSampleData(@SampleStream.ReadFlags int readFlags)
      throws ExoPlaybackException {
    FormatHolder formatHolder = getFormatHolder();
    noDataBuffer.clear();
    @ReadDataResult
    int result = readSource(formatHolder, noDataBuffer, readFlags | FLAG_OMIT_SAMPLE_DATA);
    if (result == C.RESULT_FORMAT_READ) {
      onInputFormatChanged(formatHolder);
      return true;
    } else if (result == C.RESULT_BUFFER_READ && noDataBuffer.isEndOfStream()) {
      inputStreamEnded = true;
      processEndOfStream();
    }
    return false;
  }

  /**
   * Checks whether {@link #codecDrmSession} is ready for playback, and if so initializes {@link
   * #mediaCrypto} if needed.
   *
   * @return {@code true} if codec initialization should continue, or {@code false} if it should be
   *     aborted.
   */
  @RequiresNonNull("this.codecDrmSession")
  private boolean initMediaCryptoIfDrmSessionReady() throws ExoPlaybackException {
    checkState(mediaCrypto == null);
    DrmSession codecDrmSession = this.codecDrmSession;
    @Nullable CryptoConfig cryptoConfig = codecDrmSession.getCryptoConfig();
    if (FrameworkCryptoConfig.WORKAROUND_DEVICE_NEEDS_KEYS_TO_CONFIGURE_CODEC
        && cryptoConfig instanceof FrameworkCryptoConfig) {
      @DrmSession.State int drmSessionState = codecDrmSession.getState();
      if (drmSessionState == DrmSession.STATE_ERROR) {
        DrmSessionException drmSessionException =
            Assertions.checkNotNull(codecDrmSession.getError());
        throw createRendererException(
            drmSessionException, inputFormat, drmSessionException.errorCode);
      } else if (drmSessionState != DrmSession.STATE_OPENED_WITH_KEYS) {
        // Wait for keys.
        return false;
      }
    }
    if (cryptoConfig == null) {
      @Nullable DrmSessionException drmError = codecDrmSession.getError();
      if (drmError != null) {
        // Continue for now. We may be able to avoid failure if a new input format causes the
        // session to be replaced without it having been used.
        return true;
      } else {
        // The drm session isn't open yet.
        return false;
      }
    } else if (cryptoConfig instanceof FrameworkCryptoConfig) {
      FrameworkCryptoConfig frameworkCryptoConfig = (FrameworkCryptoConfig) cryptoConfig;
      try {
        mediaCrypto = new MediaCrypto(frameworkCryptoConfig.uuid, frameworkCryptoConfig.sessionId);
      } catch (MediaCryptoException e) {
        throw createRendererException(
            e, inputFormat, PlaybackException.ERROR_CODE_DRM_SYSTEM_ERROR);
      }
    }
    return true;
  }

  private void maybeInitCodecWithFallback(
      @Nullable MediaCrypto crypto, boolean mediaCryptoRequiresSecureDecoder)
      throws DecoderInitializationException, ExoPlaybackException {
    Format inputFormat = checkNotNull(this.inputFormat);
    if (availableCodecInfos == null) {
      try {
        List<MediaCodecInfo> allAvailableCodecInfos =
            getAvailableCodecInfos(mediaCryptoRequiresSecureDecoder);
        availableCodecInfos = new ArrayDeque<>();
        if (enableDecoderFallback) {
          availableCodecInfos.addAll(allAvailableCodecInfos);
        } else if (!allAvailableCodecInfos.isEmpty()) {
          availableCodecInfos.add(allAvailableCodecInfos.get(0));
        }
        preferredDecoderInitializationException = null;
      } catch (DecoderQueryException e) {
        throw new DecoderInitializationException(
            inputFormat,
            e,
            mediaCryptoRequiresSecureDecoder,
            DecoderInitializationException.DECODER_QUERY_ERROR);
      }
    }

    if (availableCodecInfos.isEmpty()) {
      throw new DecoderInitializationException(
          inputFormat,
          /* cause= */ null,
          mediaCryptoRequiresSecureDecoder,
          DecoderInitializationException.NO_SUITABLE_DECODER_ERROR);
    }

    ArrayDeque<MediaCodecInfo> availableCodecInfos = checkNotNull(this.availableCodecInfos);
    while (codec == null) {
      MediaCodecInfo codecInfo = checkNotNull(availableCodecInfos.peekFirst());
      if (!maybeInitializeProcessingPipeline(inputFormat)) {
        return;
      }

      if (!shouldInitCodec(codecInfo)) {
        return;
      }
      try {
        initCodec(codecInfo, crypto);
      } catch (Exception e) {
        Log.w(TAG, "Failed to initialize decoder: " + codecInfo, e);
        // This codec failed to initialize, so fall back to the next codec in the list (if any). We
        // won't try to use this codec again unless there's a format change or the renderer is
        // disabled and re-enabled.
        availableCodecInfos.removeFirst();
        DecoderInitializationException exception =
            new DecoderInitializationException(
                inputFormat, e, mediaCryptoRequiresSecureDecoder, codecInfo);
        onCodecError(exception);
        if (preferredDecoderInitializationException == null) {
          preferredDecoderInitializationException = exception;
        } else {
          preferredDecoderInitializationException =
              preferredDecoderInitializationException.copyWithFallbackException(exception);
        }
        if (availableCodecInfos.isEmpty()) {
          throw preferredDecoderInitializationException;
        }
      }
    }

    this.availableCodecInfos = null;
  }

  private List<MediaCodecInfo> getAvailableCodecInfos(boolean mediaCryptoRequiresSecureDecoder)
      throws DecoderQueryException {
    Format inputFormat = checkNotNull(this.inputFormat);
    List<MediaCodecInfo> codecInfos =
        getDecoderInfos(mediaCodecSelector, inputFormat, mediaCryptoRequiresSecureDecoder);
    if (codecInfos.isEmpty() && mediaCryptoRequiresSecureDecoder) {
      // The drm session indicates that a secure decoder is required, but the device does not
      // have one. Assuming that supportsFormat indicated support for the media being played, we
      // know that it does not require a secure output path. Most CDM implementations allow
      // playback to proceed with a non-secure decoder in this case, so we try our luck.
      codecInfos =
          getDecoderInfos(mediaCodecSelector, inputFormat, /* requiresSecureDecoder= */ false);
      if (!codecInfos.isEmpty()) {
        Log.w(
            TAG,
            "Drm session requires secure decoder for "
                + inputFormat.sampleMimeType
                + ", but no secure decoder available. Trying to proceed with "
                + codecInfos
                + ".");
      }
    }
    return codecInfos;
  }

  /** Configures rendering where no codec is used. */
  private void initBypass(Format format) {
    disableBypass(); // In case of transition between 2 bypass formats.

    String mimeType = format.sampleMimeType;
    if (!MimeTypes.AUDIO_AAC.equals(mimeType)
        && !MimeTypes.AUDIO_MPEG.equals(mimeType)
        && !MimeTypes.AUDIO_OPUS.equals(mimeType)) {
      // TODO(b/154746451): Batching provokes frame drops in non offload.
      bypassBatchBuffer.setMaxSampleCount(1);
    } else {
      bypassBatchBuffer.setMaxSampleCount(BatchBuffer.DEFAULT_MAX_SAMPLE_COUNT);
    }
    bypassEnabled = true;
  }

  private void initCodec(MediaCodecInfo codecInfo, @Nullable MediaCrypto crypto) throws Exception {
    this.codecInfo = codecInfo;
    Format inputFormat = checkNotNull(this.inputFormat);
    long codecInitializingTimestamp;
    long codecInitializedTimestamp;
    String codecName = codecInfo.name;
    float codecOperatingRate =
        getCodecOperatingRateV23(targetPlaybackSpeed, inputFormat, getStreamFormats());
    if (codecOperatingRate <= assumedMinimumCodecOperatingRate) {
      codecOperatingRate = CODEC_OPERATING_RATE_UNSET;
    }
    codecInitializingTimestamp = getClock().elapsedRealtime();
    MediaCodecAdapter.Configuration configuration =
        getMediaCodecConfiguration(codecInfo, inputFormat, crypto, codecOperatingRate);
    if (SDK_INT >= 31) {
      Api31.setLogSessionIdToMediaCodecFormat(configuration, getPlayerId());
    }
    try {
      TraceUtil.beginSection("createCodec:" + codecName);
      codec = codecAdapterFactory.createAdapter(configuration);
      codecRegisteredOnBufferAvailableListener =
          codec.registerOnBufferAvailableListener(new MediaCodecRendererCodecAdapterListener());
    } finally {
      TraceUtil.endSection();
    }
    codecInitializedTimestamp = getClock().elapsedRealtime();

    if (!codecInfo.isFormatSupported(inputFormat)) {
      Log.w(
          TAG,
          Util.formatInvariant(
              "Format exceeds selected codec's capabilities [%s, %s]",
              Format.toLogString(inputFormat), codecName));
    }

    this.codecOperatingRate = codecOperatingRate;
    codecInputFormat = inputFormat;
    codecAdaptationWorkaroundMode = codecAdaptationWorkaroundMode(codecName);
    codecNeedsSosFlushWorkaround = codecNeedsSosFlushWorkaround(codecName);
    codecNeedsEosFlushWorkaround = codecNeedsEosFlushWorkaround(codecName);
    codecNeedsEosPropagation = codecNeedsEosPropagationWorkaround(codecInfo);
    if (checkNotNull(codec).needsReconfiguration()) {
      this.codecReconfigured = true;
      this.codecReconfigurationState = RECONFIGURATION_STATE_WRITE_PENDING;
      this.codecNeedsAdaptationWorkaroundBuffer =
          codecAdaptationWorkaroundMode != ADAPTATION_WORKAROUND_MODE_NEVER;
    }

    if (getState() == STATE_STARTED) {
      codecHotswapDeadlineMs = getClock().elapsedRealtime() + MAX_CODEC_HOTSWAP_TIME_MS;
    }

    decoderCounters.decoderInitCount++;
    long elapsed = codecInitializedTimestamp - codecInitializingTimestamp;
    onCodecInitialized(codecName, configuration, codecInitializedTimestamp, elapsed);
  }

  private boolean shouldContinueRendering(long renderStartTimeMs) {
    return renderTimeLimitMs == C.TIME_UNSET
        || getClock().elapsedRealtime() - renderStartTimeMs < renderTimeLimitMs;
  }

  private boolean hasOutputBuffer() {
    return outputIndex >= 0;
  }

  private void resetInputBuffer() {
    inputIndex = C.INDEX_UNSET;
    buffer.data = null;
  }

  private void resetOutputBuffer() {
    outputIndex = C.INDEX_UNSET;
    outputBuffer = null;
  }

  private void setSourceDrmSession(@Nullable DrmSession session) {
    DrmSession.replaceSession(sourceDrmSession, session);
    sourceDrmSession = session;
  }

  private void setCodecDrmSession(@Nullable DrmSession session) {
    DrmSession.replaceSession(codecDrmSession, session);
    codecDrmSession = session;
  }

  /**
   * @return Whether it may be possible to feed more input data.
   * @throws ExoPlaybackException If an error occurs feeding the input buffer.
   */
  private boolean feedInputBuffer() throws ExoPlaybackException {
    if (codec == null || codecDrainState == DRAIN_STATE_WAIT_END_OF_STREAM || inputStreamEnded) {
      return false;
    }
    if (codecDrainState == DRAIN_STATE_NONE && shouldReinitCodec()) {
      drainAndReinitializeCodec();
    }

    MediaCodecAdapter codec = checkNotNull(this.codec);
    if (inputIndex < 0) {
      inputIndex = codec.dequeueInputBufferIndex();
      if (inputIndex < 0) {
        return false;
      }
      buffer.data = codec.getInputBuffer(inputIndex);
      buffer.clear();
    }

    if (codecDrainState == DRAIN_STATE_SIGNAL_END_OF_STREAM) {
      // We need to re-initialize the codec. Send an end of stream signal to the existing codec so
      // that it outputs any remaining buffers before we release it.
      if (codecNeedsEosPropagation) {
        // Do nothing.
      } else {
        codecReceivedEos = true;
        codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
        resetInputBuffer();
      }
      codecDrainState = DRAIN_STATE_WAIT_END_OF_STREAM;
      return false;
    }

    if (codecNeedsAdaptationWorkaroundBuffer) {
      codecNeedsAdaptationWorkaroundBuffer = false;
      checkNotNull(buffer.data).put(ADAPTATION_WORKAROUND_BUFFER);
      codec.queueInputBuffer(inputIndex, 0, ADAPTATION_WORKAROUND_BUFFER.length, 0, 0);
      resetInputBuffer();
      codecReceivedBuffers = true;
      return true;
    }

    // For adaptive reconfiguration, decoders expect all reconfiguration data to be supplied at
    // the start of the buffer that also contains the first frame in the new format.
    if (codecReconfigurationState == RECONFIGURATION_STATE_WRITE_PENDING) {
      for (int i = 0; i < checkNotNull(codecInputFormat).initializationData.size(); i++) {
        byte[] data = codecInputFormat.initializationData.get(i);
        checkNotNull(buffer.data).put(data);
      }
      codecReconfigurationState = RECONFIGURATION_STATE_QUEUE_PENDING;
    }
    int adaptiveReconfigurationBytes = checkNotNull(buffer.data).position();

    FormatHolder formatHolder = getFormatHolder();

    @SampleStream.ReadDataResult int result;
    try {
      result = readSource(formatHolder, buffer, /* readFlags= */ 0);
    } catch (InsufficientCapacityException e) {
      onCodecError(e);
      // Skip the sample that's too large by reading it without its data. Then flush the codec so
      // that rendering will resume from the next key frame.
      readSourceOmittingSampleData(/* readFlags= */ 0);
      flushCodec();
      return true;
    }

    if (result == C.RESULT_NOTHING_READ) {
      if (hasReadStreamToEnd()) {
        // Notify output queue of the last buffer's timestamp.
        lastBufferInStreamPresentationTimeUs = largestQueuedPresentationTimeUs;
      }
      return false;
    }
    if (result == C.RESULT_FORMAT_READ) {
      if (codecReconfigurationState == RECONFIGURATION_STATE_QUEUE_PENDING) {
        // We received two formats in a row. Clear the current buffer of any reconfiguration data
        // associated with the first format.
        buffer.clear();
        codecReconfigurationState = RECONFIGURATION_STATE_WRITE_PENDING;
      }
      onInputFormatChanged(formatHolder);
      return true;
    }

    // We've read a buffer.
    if (buffer.isEndOfStream()) {
      lastBufferInStreamPresentationTimeUs = largestQueuedPresentationTimeUs;
      if (codecReconfigurationState == RECONFIGURATION_STATE_QUEUE_PENDING) {
        // We received a new format immediately before the end of the stream. We need to clear
        // the corresponding reconfiguration data from the current buffer, but re-write it into
        // a subsequent buffer if there are any (for example, if the user seeks backwards).
        buffer.clear();
        codecReconfigurationState = RECONFIGURATION_STATE_WRITE_PENDING;
      }
      inputStreamEnded = true;
      if (!codecReceivedBuffers) {
        processEndOfStream();
        return false;
      }
      if (codecNeedsEosPropagation) {
        // Do nothing.
      } else {
        codecReceivedEos = true;
        codec.queueInputBuffer(
            inputIndex,
            /* offset= */ 0,
            /* size= */ 0,
            /* presentationTimeUs= */ 0,
            MediaCodec.BUFFER_FLAG_END_OF_STREAM);
        resetInputBuffer();
      }
      return false;
    }

    // This logic is required for cases where the decoder needs to be flushed or re-instantiated
    // during normal consumption of samples from the source (i.e., without a corresponding
    // Renderer.enable or Renderer.resetPosition call). This is necessary for certain legacy and
    // workaround behaviors, for example when switching the output Surface on API levels prior to
    // the introduction of MediaCodec.setOutputSurface, and when it's necessary to skip past a
    // sample that's too large to be held in one of the decoder's input buffers.
    if (!codecReceivedBuffers && !buffer.isKeyFrame()) {
      buffer.clear();
      if (codecReconfigurationState == RECONFIGURATION_STATE_QUEUE_PENDING) {
        // The buffer we just cleared contained reconfiguration data. We need to re-write this data
        // into a subsequent buffer (if there is one).
        codecReconfigurationState = RECONFIGURATION_STATE_WRITE_PENDING;
      }
      return true;
    }

    if (shouldDiscardDecoderInputBuffer(buffer)) {
      return true;
    }

    boolean bufferEncrypted = buffer.isEncrypted();
    if (bufferEncrypted) {
      buffer.cryptoInfo.increaseClearDataFirstSubSampleBy(adaptiveReconfigurationBytes);
    }

    long presentationTimeUs = buffer.timeUs;

    if (waitingForFirstSampleInFormat) {
      if (!pendingOutputStreamChanges.isEmpty()) {
        pendingOutputStreamChanges
            .peekLast()
            .formatQueue
            .add(presentationTimeUs, checkNotNull(inputFormat));
      } else {
        outputStreamInfo.formatQueue.add(presentationTimeUs, checkNotNull(inputFormat));
      }
      waitingForFirstSampleInFormat = false;
    }
    largestQueuedPresentationTimeUs = max(largestQueuedPresentationTimeUs, presentationTimeUs);
    if (hasReadStreamToEnd() || buffer.isLastSample()) {
      // Notify output queue of the last buffer's timestamp.
      lastBufferInStreamPresentationTimeUs = largestQueuedPresentationTimeUs;
    }
    buffer.flip();
    if (buffer.hasSupplementalData()) {
      handleInputBufferSupplementalData(buffer);
    }

    if (hasSkippedFlushAndWaitingForQueueInputBuffer) {
      if (presentationTimeUs <= largestQueuedPresentationTimeUs) {
        skippedFlushOffsetUs += largestQueuedPresentationTimeUs - presentationTimeUs + 1;
      }
      largestQueuedPresentationTimeUs = presentationTimeUs;
      hasSkippedFlushAndWaitingForQueueInputBuffer = false;
    }

    onQueueInputBuffer(buffer);
    int flags = getCodecBufferFlags(buffer);
    presentationTimeUs += skippedFlushOffsetUs;
    if (bufferEncrypted) {
      checkNotNull(codec)
          .queueSecureInputBuffer(
              inputIndex, /* offset= */ 0, buffer.cryptoInfo, presentationTimeUs, flags);
    } else {
      checkNotNull(codec)
          .queueInputBuffer(
              inputIndex,
              /* offset= */ 0,
              checkNotNull(buffer.data).limit(),
              presentationTimeUs,
              flags);
    }

    resetInputBuffer();
    codecReceivedBuffers = true;
    codecReconfigurationState = RECONFIGURATION_STATE_NONE;
    decoderCounters.queuedInputBufferCount++;
    return true;
  }

  /**
   * Initializes the processing pipeline, if needed by the implementation.
   *
   * <p>The default implementation is a no-op.
   *
   * @param format The {@link Format} for which the codec is being configured.
   * @return Returns {@code true} when the processing pipeline is successfully initialized, or the
   *     {@linkplain MediaCodecRenderer renderer} does not use a processing pipeline. The caller
   *     should try again later, if {@code false} is returned.
   * @throws ExoPlaybackException If an error occurs preparing for initializing the codec.
   */
  protected boolean maybeInitializeProcessingPipeline(Format format) throws ExoPlaybackException {
    // Do nothing.
    return true;
  }

  /**
   * Called when a {@link MediaCodec} has been created and configured.
   *
   * <p>The default implementation is a no-op.
   *
   * @param name The name of the codec that was initialized.
   * @param configuration The {@link MediaCodecAdapter.Configuration} used to configure the codec.
   * @param initializedTimestampMs {@link SystemClock#elapsedRealtime()} when initialization
   *     finished.
   * @param initializationDurationMs The time taken to initialize the codec in milliseconds.
   */
  protected void onCodecInitialized(
      String name,
      MediaCodecAdapter.Configuration configuration,
      long initializedTimestampMs,
      long initializationDurationMs) {
    // Do nothing.
  }

  /**
   * Called when a {@link MediaCodec} has been released.
   *
   * <p>The default implementation is a no-op.
   *
   * @param name The name of the codec that was released.
   */
  protected void onCodecReleased(String name) {
    // Do nothing.
  }

  /**
   * Called when a codec error has occurred.
   *
   * <p>The default implementation is a no-op.
   *
   * @param codecError The error.
   */
  protected void onCodecError(Exception codecError) {
    // Do nothing.
  }

  /**
   * Called when a new {@link Format} is read from the upstream {@link MediaPeriod}.
   *
   * @param formatHolder A {@link FormatHolder} that holds the new {@link Format}.
   * @throws ExoPlaybackException If an error occurs re-initializing the {@link MediaCodec}.
   * @return The result of the evaluation to determine whether the existing decoder instance can be
   *     reused for the new format, or {@code null} if the renderer did not have a decoder.
   */
  @CallSuper
  @Nullable
  protected DecoderReuseEvaluation onInputFormatChanged(FormatHolder formatHolder)
      throws ExoPlaybackException {
    waitingForFirstSampleInFormat = true;
    Format newFormat = checkNotNull(formatHolder.format);
    if (newFormat.sampleMimeType == null) {
      // If the new format is invalid, it is either a media bug or it is not intended to be played.
      // See also https://github.com/google/ExoPlayer/issues/8283.
      throw createRendererException(
          new IllegalArgumentException("Sample MIME type is null."),
          newFormat,
          PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED);
    }

    // Remove the initialization data (CSD) when decoding AV1 or VP9, as it is not required by
    // MediaCodec for these encodings, and some codec implementations fail if it's present.
    // * https://developer.android.com/reference/android/media/MediaCodec#CSD
    // * b/229399008#comment9
    // * https://github.com/androidx/media/issues/2408
    if ((Objects.equals(newFormat.sampleMimeType, MimeTypes.VIDEO_AV1)
            || Objects.equals(newFormat.sampleMimeType, MimeTypes.VIDEO_VP9))
        && !newFormat.initializationData.isEmpty()) {
      newFormat = newFormat.buildUpon().setInitializationData(null).build();
    }

    setSourceDrmSession(formatHolder.drmSession);
    inputFormat = newFormat;

    if (bypassEnabled) {
      bypassDrainAndReinitialize = true;
      return null; // Need to drain batch buffer first.
    }

    if (codec == null) {
      availableCodecInfos = null;
      maybeInitCodecOrBypass();
      return null;
    }

    // We have an existing codec that we may need to reconfigure, re-initialize, or release to
    // switch to bypass. If the existing codec instance is kept then its operating rate and DRM
    // session may need to be updated.

    // Copy the current codec and codecInfo to local variables so they remain accessible if the
    // member variables are updated during the logic below.
    MediaCodecAdapter codec = this.codec;
    MediaCodecInfo codecInfo = checkNotNull(this.codecInfo);

    Format oldFormat = checkNotNull(codecInputFormat);
    if (drmNeedsCodecReinitialization(codecInfo, newFormat, codecDrmSession, sourceDrmSession)) {
      drainAndReinitializeCodec();
      return new DecoderReuseEvaluation(
          codecInfo.name,
          oldFormat,
          newFormat,
          REUSE_RESULT_NO,
          DISCARD_REASON_DRM_SESSION_CHANGED);
    }
    boolean drainAndUpdateCodecDrmSession = sourceDrmSession != codecDrmSession;

    DecoderReuseEvaluation evaluation = canReuseCodec(codecInfo, oldFormat, newFormat);
    @DecoderDiscardReasons int overridingDiscardReasons = 0;
    switch (evaluation.result) {
      case REUSE_RESULT_NO:
        drainAndReinitializeCodec();
        break;
      case REUSE_RESULT_YES_WITH_FLUSH:
        if (!updateCodecOperatingRate(newFormat)) {
          overridingDiscardReasons |= DISCARD_REASON_OPERATING_RATE_CHANGED;
        } else {
          codecInputFormat = newFormat;
          if (drainAndUpdateCodecDrmSession) {
            if (!drainAndUpdateCodecDrmSession()) {
              overridingDiscardReasons |= DISCARD_REASON_WORKAROUND;
            }
          } else if (!drainAndFlushCodec()) {
            overridingDiscardReasons |= DISCARD_REASON_WORKAROUND;
          }
        }
        break;
      case REUSE_RESULT_YES_WITH_RECONFIGURATION:
        if (!updateCodecOperatingRate(newFormat)) {
          overridingDiscardReasons |= DISCARD_REASON_OPERATING_RATE_CHANGED;
        } else {
          codecReconfigured = true;
          codecReconfigurationState = RECONFIGURATION_STATE_WRITE_PENDING;
          codecNeedsAdaptationWorkaroundBuffer =
              codecAdaptationWorkaroundMode == ADAPTATION_WORKAROUND_MODE_ALWAYS
                  || (codecAdaptationWorkaroundMode == ADAPTATION_WORKAROUND_MODE_SAME_RESOLUTION
                      && newFormat.width == oldFormat.width
                      && newFormat.height == oldFormat.height);
          codecInputFormat = newFormat;
          if (drainAndUpdateCodecDrmSession && !drainAndUpdateCodecDrmSession()) {
            overridingDiscardReasons |= DISCARD_REASON_WORKAROUND;
          }
        }
        break;
      case REUSE_RESULT_YES_WITHOUT_RECONFIGURATION:
        if (!updateCodecOperatingRate(newFormat)) {
          overridingDiscardReasons |= DISCARD_REASON_OPERATING_RATE_CHANGED;
        } else {
          codecInputFormat = newFormat;
          if (drainAndUpdateCodecDrmSession && !drainAndUpdateCodecDrmSession()) {
            overridingDiscardReasons |= DISCARD_REASON_WORKAROUND;
          }
        }
        break;
      default:
        throw new IllegalStateException(); // Never happens.
    }

    if (evaluation.result != REUSE_RESULT_NO
        && (this.codec != codec || codecDrainAction == DRAIN_ACTION_REINITIALIZE)) {
      // Initial evaluation indicated reuse was possible, but codec re-initialization was triggered.
      // The reasons are indicated by overridingDiscardReasons.
      return new DecoderReuseEvaluation(
          codecInfo.name, oldFormat, newFormat, REUSE_RESULT_NO, overridingDiscardReasons);
    }

    return evaluation;
  }

  /**
   * Called when one of the output formats changes.
   *
   * <p>The default implementation is a no-op.
   *
   * @param format The input {@link Format} to which future output now corresponds. If the renderer
   *     is in bypass mode, this is also the output format.
   * @param mediaFormat The codec output {@link MediaFormat}, or {@code null} if the renderer is in
   *     bypass mode.
   * @throws ExoPlaybackException Thrown if an error occurs configuring the output.
   */
  protected void onOutputFormatChanged(Format format, @Nullable MediaFormat mediaFormat)
      throws ExoPlaybackException {
    // Do nothing.
  }

  /**
   * Handles supplemental data associated with an input buffer.
   *
   * <p>The default implementation is a no-op.
   *
   * @param buffer The input buffer that is about to be queued.
   * @throws ExoPlaybackException Thrown if an error occurs handling supplemental data.
   */
  protected void handleInputBufferSupplementalData(DecoderInputBuffer buffer)
      throws ExoPlaybackException {
    // Do nothing.
  }

  /**
   * Called immediately before an input buffer is queued into the codec.
   *
   * <p>The default implementation is a no-op.
   *
   * @param buffer The buffer to be queued.
   * @throws ExoPlaybackException Thrown if an error occurs handling the input buffer.
   */
  protected void onQueueInputBuffer(DecoderInputBuffer buffer) throws ExoPlaybackException {
    // Do nothing.
  }

  /**
   * Returns the flags that should be set on {@link MediaCodec#queueInputBuffer} or {@link
   * MediaCodec#queueSecureInputBuffer} for this buffer.
   *
   * @param buffer The input buffer.
   * @return The flags to set on {@link MediaCodec#queueInputBuffer} or {@link
   *     MediaCodec#queueSecureInputBuffer}.
   */
  protected int getCodecBufferFlags(DecoderInputBuffer buffer) {
    return 0;
  }

  /**
   * Returns whether the input buffer should be skipped before the decoder.
   *
   * <p>This can be used to skip decoding of buffers that are not depended on during seeking. See
   * {@link C#BUFFER_FLAG_NOT_DEPENDED_ON}.
   *
   * @param buffer The input buffer.
   */
  protected boolean shouldSkipDecoderInputBuffer(DecoderInputBuffer buffer) {
    return false;
  }

  /**
   * Returns whether the input buffer should be discarded before decoding.
   *
   * <p>Implement this method to to skip decoding of buffers that are not needed during a seek, or
   * to drop input buffers that cannot be rendered on time. See {@link
   * C#BUFFER_FLAG_NOT_DEPENDED_ON}.
   *
   * <p>Subclasses that implement this method are responsible for updating {@link #decoderCounters}.
   * For codecs with out-of-order buffers, consecutive dropped input buffers may have to be counted
   * after frame reordering. For example, in {@link #processOutputBuffer}.
   *
   * <p>Implementations of this method must update the {@linkplain DecoderInputBuffer#data decoder
   * input buffer contents}. Data that is only used for output at the current {@link
   * DecoderInputBuffer#timeUs} should be removed. Data that is referenced by later input buffers
   * should remain in the current buffer.
   *
   * @param buffer The input buffer.
   */
  protected boolean shouldDiscardDecoderInputBuffer(DecoderInputBuffer buffer) {
    if (shouldSkipDecoderInputBuffer(buffer)) {
      buffer.clear();
      decoderCounters.skippedInputBufferCount += 1;
      return true;
    }
    return false;
  }

  /**
   * Returns the presentation time of the last buffer in the stream.
   *
   * <p>If the last buffer has not yet been read off the sample queue then the return value will be
   * {@link C#TIME_UNSET}.
   *
   * @return The presentation time of the last buffer in the stream.
   */
  protected long getLastBufferInStreamPresentationTimeUs() {
    return lastBufferInStreamPresentationTimeUs;
  }

  /**
   * Called when an output buffer is successfully processed.
   *
   * @param presentationTimeUs The timestamp associated with the output buffer.
   */
  @CallSuper
  protected void onProcessedOutputBuffer(long presentationTimeUs) {
    lastProcessedOutputBufferTimeUs = presentationTimeUs;
    while (!pendingOutputStreamChanges.isEmpty()
        && presentationTimeUs >= pendingOutputStreamChanges.peek().previousStreamLastBufferTimeUs) {
      setOutputStreamInfo(checkNotNull(pendingOutputStreamChanges.poll()));
      onProcessedStreamChange();
    }
  }

  /** Called after the last output buffer before a stream change has been processed. */
  protected void onProcessedStreamChange() {
    // Do nothing.
  }

  /**
   * Evaluates whether the existing {@link MediaCodec} can be kept for a new {@link Format}, and if
   * it can whether it requires reconfiguration.
   *
   * <p>The default implementation does not allow decoder reuse.
   *
   * @param codecInfo A {@link MediaCodecInfo} describing the decoder.
   * @param oldFormat The {@link Format} for which the existing instance is configured.
   * @param newFormat The new {@link Format}.
   * @return The result of the evaluation.
   */
  protected DecoderReuseEvaluation canReuseCodec(
      MediaCodecInfo codecInfo, Format oldFormat, Format newFormat) {
    return new DecoderReuseEvaluation(
        codecInfo.name,
        oldFormat,
        newFormat,
        REUSE_RESULT_NO,
        DISCARD_REASON_REUSE_NOT_IMPLEMENTED);
  }

  /**
   * Called after the output stream offset changes.
   *
   * <p>The default implementation is a no-op.
   *
   * @param outputStreamOffsetUs The output stream offset in microseconds.
   */
  protected void onOutputStreamOffsetUsChanged(long outputStreamOffsetUs) {
    // Do nothing
  }

  @Override
  public boolean isEnded() {
    return outputStreamEnded;
  }

  @Override
  public boolean isReady() {
    return isReadyForDecoding();
  }

  /** Returns whether the renderer is ready to start or continue decoding. */
  protected final boolean isReadyForDecoding() {
    return inputFormat != null
        && (isSourceReady()
            || hasOutputBuffer()
            || (codecHotswapDeadlineMs != C.TIME_UNSET
                && getClock().elapsedRealtime() < codecHotswapDeadlineMs));
  }

  /** Returns the current playback speed, as set by {@link #setPlaybackSpeed}. */
  protected float getPlaybackSpeed() {
    return currentPlaybackSpeed;
  }

  /** Returns the operating rate used by the current codec */
  protected float getCodecOperatingRate() {
    return codecOperatingRate;
  }

  /**
   * Returns the {@link MediaFormat#KEY_OPERATING_RATE} value for a given playback speed, current
   * {@link Format} and set of possible stream formats.
   *
   * <p>The default implementation returns {@link #CODEC_OPERATING_RATE_UNSET}.
   *
   * @param targetPlaybackSpeed The target factor by which playback should be sped up. This may be
   *     different from the current playback speed, for example, if the speed is temporarily
   *     adjusted for live playback.
   * @param format The {@link Format} for which the codec is being configured.
   * @param streamFormats The possible stream formats.
   * @return The codec operating rate, or {@link #CODEC_OPERATING_RATE_UNSET} if no codec operating
   *     rate should be set.
   */
  protected float getCodecOperatingRateV23(
      float targetPlaybackSpeed, Format format, Format[] streamFormats) {
    return CODEC_OPERATING_RATE_UNSET;
  }

  /** Returns listener used to signal that {@link #render(long, long)} should be called. */
  @Nullable
  protected final WakeupListener getWakeupListener() {
    return wakeupListener;
  }

  /**
   * Updates the codec operating rate, or triggers codec release and re-initialization if a
   * previously set operating rate needs to be cleared.
   *
   * @throws ExoPlaybackException If an error occurs releasing or initializing a codec.
   * @return False if codec release and re-initialization was triggered. True in all other cases.
   */
  protected final boolean updateCodecOperatingRate() throws ExoPlaybackException {
    return updateCodecOperatingRate(codecInputFormat);
  }

  /**
   * Updates the codec operating rate, or triggers codec release and re-initialization if a
   * previously set operating rate needs to be cleared.
   *
   * @param format The {@link Format} for which the operating rate should be configured.
   * @throws ExoPlaybackException If an error occurs releasing or initializing a codec.
   * @return False if codec release and re-initialization was triggered. True in all other cases.
   */
  private boolean updateCodecOperatingRate(@Nullable Format format) throws ExoPlaybackException {
    if (codec == null
        || codecDrainAction == DRAIN_ACTION_REINITIALIZE
        || getState() == STATE_DISABLED) {
      // No need to update the operating rate.
      return true;
    }

    float newCodecOperatingRate =
        getCodecOperatingRateV23(targetPlaybackSpeed, checkNotNull(format), getStreamFormats());
    if (codecOperatingRate == newCodecOperatingRate) {
      // No change.
      return true;
    } else if (newCodecOperatingRate == CODEC_OPERATING_RATE_UNSET) {
      // The only way to clear the operating rate is to instantiate a new codec instance. See
      // [Internal ref: b/111543954].
      drainAndReinitializeCodec();
      return false;
    } else if (codecOperatingRate != CODEC_OPERATING_RATE_UNSET
        || newCodecOperatingRate > assumedMinimumCodecOperatingRate) {
      // We need to set the operating rate, either because we've set it previously or because it's
      // above the assumed minimum rate.
      Bundle codecParameters = new Bundle();
      codecParameters.putFloat(MediaFormat.KEY_OPERATING_RATE, newCodecOperatingRate);
      checkNotNull(codec).setParameters(codecParameters);
      codecOperatingRate = newCodecOperatingRate;
      return true;
    }

    return true;
  }

  /**
   * Starts draining the codec for a flush, or to release and re-initialize the codec if flushing
   * will not be possible. If no buffers have been queued to the codec then this method is a no-op.
   *
   * @return False if codec release and re-initialization was triggered due to the need to apply a
   *     flush workaround. True in all other cases.
   */
  private boolean drainAndFlushCodec() {
    if (codecReceivedBuffers) {
      codecDrainState = DRAIN_STATE_SIGNAL_END_OF_STREAM;
      if (codecNeedsEosFlushWorkaround) {
        codecDrainAction = DRAIN_ACTION_REINITIALIZE;
        return false;
      } else {
        codecDrainAction = DRAIN_ACTION_FLUSH;
      }
    }
    return true;
  }

  /**
   * Starts draining the codec to flush it and update its DRM session, or to release and
   * re-initialize the codec if flushing will not be possible. If no buffers have been queued to the
   * codec then this method updates the DRM session immediately without flushing the codec.
   *
   * @throws ExoPlaybackException If an error occurs updating the codec's DRM session.
   * @return False if codec release and re-initialization was triggered due to the need to apply a
   *     flush workaround. True in all other cases.
   */
  private boolean drainAndUpdateCodecDrmSession() throws ExoPlaybackException {
    if (codecReceivedBuffers) {
      codecDrainState = DRAIN_STATE_SIGNAL_END_OF_STREAM;
      if (codecNeedsEosFlushWorkaround) {
        codecDrainAction = DRAIN_ACTION_REINITIALIZE;
        return false;
      } else {
        codecDrainAction = DRAIN_ACTION_FLUSH_AND_UPDATE_DRM_SESSION;
      }
    } else {
      // Nothing has been queued to the decoder, so we can do the update immediately.
      updateDrmSession();
    }
    return true;
  }

  /**
   * Starts draining the codec for re-initialization. Re-initialization may occur immediately if no
   * buffers have been queued to the codec.
   *
   * @throws ExoPlaybackException If an error occurs re-initializing a codec.
   */
  private void drainAndReinitializeCodec() throws ExoPlaybackException {
    if (codecReceivedBuffers) {
      codecDrainState = DRAIN_STATE_SIGNAL_END_OF_STREAM;
      codecDrainAction = DRAIN_ACTION_REINITIALIZE;
    } else {
      // Nothing has been queued to the decoder, so we can re-initialize immediately.
      reinitializeCodec();
    }
  }

  /**
   * @return Whether it may be possible to drain more output data.
   * @throws ExoPlaybackException If an error occurs draining the output buffer.
   */
  private boolean drainOutputBuffer(long positionUs, long elapsedRealtimeUs)
      throws ExoPlaybackException {
    MediaCodecAdapter codec = checkNotNull(this.codec);
    if (!hasOutputBuffer()) {
      int outputIndex = codec.dequeueOutputBufferIndex(outputBufferInfo);
      if (outputIndex < 0) {
        if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED /* (-2) */) {
          processOutputMediaFormatChanged();
          return true;
        }
        // MediaCodec.INFO_TRY_AGAIN_LATER (-1) or unknown negative return value.
        if (codecNeedsEosPropagation
            && (inputStreamEnded || codecDrainState == DRAIN_STATE_WAIT_END_OF_STREAM)) {
          processEndOfStream();
        }
        if (lastOutputBufferProcessedRealtimeMs != C.TIME_UNSET
            && lastOutputBufferProcessedRealtimeMs + 100 < getClock().currentTimeMillis()) {
          // We processed the last output buffer more than 100ms ago without
          // receiving an EOS buffer. This is likely a misbehaving codec, so
          // process the end of stream manually. See b/359634542.
          processEndOfStream();
        }
        return false;
      }

      // We've dequeued a buffer.
      outputBufferInfo.presentationTimeUs -= skippedFlushOffsetUs;
      if (shouldSkipAdaptationWorkaroundOutputBuffer) {
        shouldSkipAdaptationWorkaroundOutputBuffer = false;
        codec.releaseOutputBuffer(outputIndex, false);
        return true;
      } else if (outputBufferInfo.size == 0
          && (outputBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
        // The dequeued buffer indicates the end of the stream. Process it immediately.
        processEndOfStream();
        return false;
      }

      this.outputIndex = outputIndex;
      outputBuffer = codec.getOutputBuffer(outputIndex);

      // The dequeued buffer is a media buffer. Do some initial setup.
      // It will be processed by calling processOutputBuffer (possibly multiple times).
      if (outputBuffer != null) {
        outputBuffer.position(outputBufferInfo.offset);
        outputBuffer.limit(outputBufferInfo.offset + outputBufferInfo.size);
      }
      updateOutputFormatForTime(outputBufferInfo.presentationTimeUs);
    }

    isDecodeOnlyOutputBuffer =
        hasSkippedFlushAndWaitingForQueueInputBuffer
            || outputBufferInfo.presentationTimeUs < getLastResetPositionUs();
    isLastOutputBuffer =
        lastBufferInStreamPresentationTimeUs != C.TIME_UNSET
            && lastBufferInStreamPresentationTimeUs <= outputBufferInfo.presentationTimeUs;

    boolean processedOutputBuffer =
        processOutputBuffer(
            positionUs,
            elapsedRealtimeUs,
            codec,
            outputBuffer,
            outputIndex,
            outputBufferInfo.flags,
            /* sampleCount= */ 1,
            outputBufferInfo.presentationTimeUs,
            isDecodeOnlyOutputBuffer,
            isLastOutputBuffer,
            checkNotNull(outputFormat));
    if (processedOutputBuffer) {
      onProcessedOutputBuffer(outputBufferInfo.presentationTimeUs);
      boolean isEndOfStream = (outputBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
      if (!isEndOfStream && codecReceivedEos && isLastOutputBuffer) {
        lastOutputBufferProcessedRealtimeMs = getClock().currentTimeMillis();
      }
      resetOutputBuffer();
      if (!isEndOfStream) {
        return true;
      }
      processEndOfStream();
    }

    return false;
  }

  /** Processes a change in the decoder output {@link MediaFormat}. */
  private void processOutputMediaFormatChanged() {
    codecHasOutputMediaFormat = true;
    MediaFormat mediaFormat = checkNotNull(codec).getOutputFormat();
    if (codecAdaptationWorkaroundMode != ADAPTATION_WORKAROUND_MODE_NEVER
        && mediaFormat.getInteger(MediaFormat.KEY_WIDTH) == ADAPTATION_WORKAROUND_SLICE_WIDTH_HEIGHT
        && mediaFormat.getInteger(MediaFormat.KEY_HEIGHT)
            == ADAPTATION_WORKAROUND_SLICE_WIDTH_HEIGHT) {
      // We assume this format changed event was caused by the adaptation workaround.
      shouldSkipAdaptationWorkaroundOutputBuffer = true;
      return;
    }
    codecOutputMediaFormat = mediaFormat;
    codecOutputMediaFormatChanged = true;
  }

  /**
   * Processes an output media buffer.
   *
   * <p>When a new {@link ByteBuffer} is passed to this method its position and limit delineate the
   * data to be processed. The return value indicates whether the buffer was processed in full. If
   * true is returned then the next call to this method will receive a new buffer to be processed.
   * If false is returned then the same buffer will be passed to the next call. An implementation of
   * this method is free to modify the buffer and can assume that the buffer will not be externally
   * modified between successive calls. Hence an implementation can, for example, modify the
   * buffer's position to keep track of how much of the data it has processed.
   *
   * <p>Note that the first call to this method following a call to {@link #onPositionReset(long,
   * boolean)} will always receive a new {@link ByteBuffer} to be processed.
   *
   * @param positionUs The current media time in microseconds, measured at the start of the current
   *     iteration of the rendering loop.
   * @param elapsedRealtimeUs {@link SystemClock#elapsedRealtime()} in microseconds, measured at the
   *     start of the current iteration of the rendering loop.
   * @param codec The {@link MediaCodecAdapter} instance, or null in bypass mode were no codec is
   *     used.
   * @param buffer The output buffer to process, or null if the buffer data is not made available to
   *     the application layer (see {@link MediaCodec#getOutputBuffer(int)}). This {@code buffer}
   *     can only be null for video data. Note that the buffer data can still be rendered in this
   *     case by using the {@code bufferIndex}.
   * @param bufferIndex The index of the output buffer.
   * @param bufferFlags The flags attached to the output buffer.
   * @param sampleCount The number of samples extracted from the sample queue in the buffer. This
   *     allows handling multiple samples as a batch for efficiency.
   * @param bufferPresentationTimeUs The presentation time of the output buffer in microseconds.
   * @param isDecodeOnlyBuffer Whether the buffer timestamp is less than the intended playback start
   *     position.
   * @param isLastBuffer Whether the buffer is known to contain the last sample of the current
   *     stream. This flag is set on a best effort basis, and any logic relying on it should degrade
   *     gracefully to handle cases where it's not set.
   * @param format The {@link Format} associated with the buffer.
   * @return Whether the output buffer was fully processed (for example, rendered or skipped).
   * @throws ExoPlaybackException If an error occurs processing the output buffer.
   */
  protected abstract boolean processOutputBuffer(
      long positionUs,
      long elapsedRealtimeUs,
      @Nullable MediaCodecAdapter codec,
      @Nullable ByteBuffer buffer,
      int bufferIndex,
      int bufferFlags,
      int sampleCount,
      long bufferPresentationTimeUs,
      boolean isDecodeOnlyBuffer,
      boolean isLastBuffer,
      Format format)
      throws ExoPlaybackException;

  /**
   * Incrementally renders any remaining output.
   *
   * <p>The default implementation is a no-op.
   *
   * @throws ExoPlaybackException Thrown if an error occurs rendering remaining output.
   */
  protected void renderToEndOfStream() throws ExoPlaybackException {
    // Do nothing.
  }

  /**
   * Processes an end of stream signal.
   *
   * @throws ExoPlaybackException If an error occurs processing the signal.
   */
  private void processEndOfStream() throws ExoPlaybackException {
    switch (codecDrainAction) {
      case DRAIN_ACTION_REINITIALIZE:
        reinitializeCodec();
        break;
      case DRAIN_ACTION_FLUSH_AND_UPDATE_DRM_SESSION:
        flushCodec();
        updateDrmSession();
        break;
      case DRAIN_ACTION_FLUSH:
        flushCodec();
        break;
      case DRAIN_ACTION_NONE:
      default:
        outputStreamEnded = true;
        renderToEndOfStream();
        break;
    }
  }

  /**
   * Notifies the renderer that output end of stream is pending and should be handled on the next
   * render.
   */
  protected final void setPendingOutputEndOfStream() {
    pendingOutputEndOfStream = true;
  }

  /**
   * Returns the offset that should be subtracted from {@code bufferPresentationTimeUs} in {@link
   * #processOutputBuffer(long, long, MediaCodecAdapter, ByteBuffer, int, int, int, long, boolean,
   * boolean, Format)} to get the playback position with respect to the media.
   */
  protected final long getOutputStreamOffsetUs() {
    return outputStreamInfo.streamOffsetUs;
  }

  /** Returns the start position of the current output stream in microseconds. */
  protected final long getOutputStreamStartPositionUs() {
    return outputStreamInfo.startPositionUs;
  }

  private void setOutputStreamInfo(OutputStreamInfo outputStreamInfo) {
    this.outputStreamInfo = outputStreamInfo;
    if (outputStreamInfo.streamOffsetUs != C.TIME_UNSET) {
      needToNotifyOutputFormatChangeAfterStreamChange = true;
      onOutputStreamOffsetUsChanged(outputStreamInfo.streamOffsetUs);
    }
  }

  /** Returns whether this renderer supports the given {@link Format Format's} DRM scheme. */
  protected static boolean supportsFormatDrm(Format format) {
    return format.cryptoType == C.CRYPTO_TYPE_NONE || format.cryptoType == C.CRYPTO_TYPE_FRAMEWORK;
  }

  /**
   * Returns whether it's necessary to re-initialize the codec to handle a DRM change. If {@code
   * false} is returned then either {@code oldSession == newSession} (i.e., there was no change), or
   * it's possible to update the existing codec using MediaCrypto.setMediaDrmSession.
   */
  private boolean drmNeedsCodecReinitialization(
      MediaCodecInfo codecInfo,
      Format newFormat,
      @Nullable DrmSession oldSession,
      @Nullable DrmSession newSession)
      throws ExoPlaybackException {
    if (oldSession == newSession) {
      // No need to re-initialize if the old and new sessions are the same.
      return false;
    }

    // Note: At least one of oldSession and newSession are non-null.

    if (newSession == null || oldSession == null) {
      // Changing from DRM to no DRM and vice-versa always requires re-initialization.
      return true;
    }

    @Nullable CryptoConfig newCryptoConfig = newSession.getCryptoConfig();
    if (newCryptoConfig == null) {
      // We'd only expect this to happen if the CDM from which newSession is obtained needs
      // provisioning. This is unlikely to happen (it probably requires a switch from one DRM scheme
      // to another, where the new CDM hasn't been used before and needs provisioning). It would be
      // possible to handle this case without codec re-initialization, but it would require the
      // re-use code path to be able to wait for provisioning to finish before calling
      // MediaCrypto.setMediaDrmSession. The extra complexity is not warranted given how unlikely
      // the case is to occur, so we re-initialize in this case.
      return true;
    }

    @Nullable CryptoConfig oldCryptoConfig = oldSession.getCryptoConfig();
    if (oldCryptoConfig == null || !newCryptoConfig.getClass().equals(oldCryptoConfig.getClass())) {
      // Switching between different CryptoConfig implementations suggests we're switching between
      // different forms of decryption (e.g. in-app vs framework-provided), which requires codec
      // re-initialization.
      return true;
    }

    if (!(newCryptoConfig instanceof FrameworkCryptoConfig)) {
      // Assume that non-framework CryptoConfig implementations indicate the codec can be re-used
      // (since it suggests that decryption is happening in-app before the data is passed to
      // MediaCodec, and therefore a change in DRM keys has no affect on the (decrypted) data seen
      // by MediaCodec).
      return false;
    }

    // Note: Both oldSession and newSession are non-null, and they are different sessions.

    if (!newSession.getSchemeUuid().equals(oldSession.getSchemeUuid())) {
      // MediaCrypto.setMediaDrmSession is unable to switch between DRM schemes.
      return true;
    }

    if (C.PLAYREADY_UUID.equals(oldSession.getSchemeUuid())
        || C.PLAYREADY_UUID.equals(newSession.getSchemeUuid())) {
      // The PlayReady CDM does not support MediaCrypto.setMediaDrmSession, either as the old or new
      // session.
      // TODO: Add an API check once [Internal ref: b/128835874] is fixed.
      return true;
    }

    // Re-initialization is required if newSession might require switching to the secure output
    // path. We assume newSession might require a secure decoder if it's not fully open yet.
    return !codecInfo.secure
        && (newSession.getState() == DrmSession.STATE_OPENING
            || ((newSession.getState() == DrmSession.STATE_OPENED
                    || newSession.getState() == DrmSession.STATE_OPENED_WITH_KEYS)
                && newSession.requiresSecureDecoder(checkNotNull(newFormat.sampleMimeType))));
  }

  private void reinitializeCodec() throws ExoPlaybackException {
    releaseCodec();
    maybeInitCodecOrBypass();
  }

  private void updateDrmSession() throws ExoPlaybackException {
    CryptoConfig cryptoConfig = checkNotNull(sourceDrmSession).getCryptoConfig();
    if (cryptoConfig instanceof FrameworkCryptoConfig) {
      try {
        checkNotNull(mediaCrypto)
            .setMediaDrmSession(((FrameworkCryptoConfig) cryptoConfig).sessionId);
      } catch (MediaCryptoException e) {
        throw createRendererException(
            e, inputFormat, PlaybackException.ERROR_CODE_DRM_SYSTEM_ERROR);
      }
    }
    setCodecDrmSession(sourceDrmSession);
    codecDrainState = DRAIN_STATE_NONE;
    codecDrainAction = DRAIN_ACTION_NONE;
  }

  /**
   * Processes any pending batch of buffers without using a decoder, and drains a new batch of
   * buffers from the source.
   *
   * @param positionUs The current media time in microseconds, measured at the start of the current
   *     iteration of the rendering loop.
   * @param elapsedRealtimeUs {@link SystemClock#elapsedRealtime()} in microseconds, measured at the
   *     start of the current iteration of the rendering loop.
   * @return Whether immediately calling this method again will make more progress.
   * @throws ExoPlaybackException If an error occurred while processing a buffer or handling a
   *     format change.
   */
  private boolean bypassRender(long positionUs, long elapsedRealtimeUs)
      throws ExoPlaybackException {

    // Process any batched data.
    checkState(!outputStreamEnded);
    if (bypassBatchBuffer.hasSamples()) {
      if (processOutputBuffer(
          positionUs,
          elapsedRealtimeUs,
          /* codec= */ null,
          bypassBatchBuffer.data,
          outputIndex,
          /* bufferFlags= */ 0,
          bypassBatchBuffer.getSampleCount(),
          bypassBatchBuffer.getFirstSampleTimeUs(),
          isDecodeOnly(getLastResetPositionUs(), bypassBatchBuffer.getLastSampleTimeUs()),
          bypassBatchBuffer.isEndOfStream(),
          checkNotNull(outputFormat))) {
        // The batch buffer has been fully processed.
        onProcessedOutputBuffer(bypassBatchBuffer.getLastSampleTimeUs());
        bypassBatchBuffer.clear();
      } else {
        // Could not process the whole batch buffer. Try again later.
        return false;
      }
    }

    // Process end of stream, if reached.
    if (inputStreamEnded) {
      outputStreamEnded = true;
      return false;
    }

    if (bypassSampleBufferPending) {
      Assertions.checkState(bypassBatchBuffer.append(bypassSampleBuffer));
      bypassSampleBufferPending = false;
    }

    if (bypassDrainAndReinitialize) {
      if (bypassBatchBuffer.hasSamples()) {
        // This can only happen if bypassSampleBufferPending was true above. Return true to try and
        // immediately process the sample, which has now been appended to the batch buffer.
        return true;
      }
      // The new format might require using a codec rather than bypass.
      disableBypass();
      bypassDrainAndReinitialize = false;
      maybeInitCodecOrBypass();
      if (!bypassEnabled) {
        // We're no longer in bypass mode.
        return false;
      }
    }

    // Read from the input, appending any sample buffers to the batch buffer.
    bypassRead();

    if (bypassBatchBuffer.hasSamples()) {
      bypassBatchBuffer.flip();
    }

    // We can make more progress if we have batched data, an EOS, or a re-initialization to process
    // (note that one or more of the code blocks above will be executed during the next call).
    return bypassBatchBuffer.hasSamples() || inputStreamEnded || bypassDrainAndReinitialize;
  }

  private void bypassRead() throws ExoPlaybackException {
    checkState(!inputStreamEnded);
    FormatHolder formatHolder = getFormatHolder();
    bypassSampleBuffer.clear();
    while (true) {
      bypassSampleBuffer.clear();
      @ReadDataResult int result = readSource(formatHolder, bypassSampleBuffer, /* readFlags= */ 0);
      switch (result) {
        case C.RESULT_FORMAT_READ:
          onInputFormatChanged(formatHolder);
          return;
        case C.RESULT_NOTHING_READ:
          if (hasReadStreamToEnd()) {
            // Notify output queue of the last buffer's timestamp.
            lastBufferInStreamPresentationTimeUs = largestQueuedPresentationTimeUs;
          }
          return;
        case C.RESULT_BUFFER_READ:
          if (bypassSampleBuffer.isEndOfStream()) {
            inputStreamEnded = true;
            lastBufferInStreamPresentationTimeUs = largestQueuedPresentationTimeUs;
            return;
          }
          largestQueuedPresentationTimeUs =
              max(largestQueuedPresentationTimeUs, bypassSampleBuffer.timeUs);
          if (hasReadStreamToEnd() || buffer.isLastSample()) {
            // Notify output queue of the last buffer's timestamp.
            lastBufferInStreamPresentationTimeUs = largestQueuedPresentationTimeUs;
          }
          if (waitingForFirstSampleInFormat) {
            // This is the first buffer in a new format, the output format must be updated.
            outputFormat = checkNotNull(inputFormat);
            if (Objects.equals(outputFormat.sampleMimeType, MimeTypes.AUDIO_OPUS)
                && !outputFormat.initializationData.isEmpty()) {
              // Format mimetype is Opus so format should be updated with preSkip data.
              // TODO(b/298634018): Adjust encoderDelay value based on starting position.
              int numberPreSkipSamples =
                  OpusUtil.getPreSkipSamples(outputFormat.initializationData.get(0));
              outputFormat = outputFormat.buildUpon().setEncoderDelay(numberPreSkipSamples).build();
            }
            onOutputFormatChanged(outputFormat, /* mediaFormat= */ null);
            waitingForFirstSampleInFormat = false;
          }
          // Try to append the buffer to the batch buffer.
          bypassSampleBuffer.flip();

          if (outputFormat != null
              && Objects.equals(outputFormat.sampleMimeType, MimeTypes.AUDIO_OPUS)) {
            if (bypassSampleBuffer.hasSupplementalData()) {
              // Set format on sample buffer so that it contains the mimetype and encodingDelay.
              bypassSampleBuffer.format = outputFormat;
              handleInputBufferSupplementalData(bypassSampleBuffer);
            }
            if (OpusUtil.needToDecodeOpusFrame(
                getLastResetPositionUs(), bypassSampleBuffer.timeUs)) {
              // Packetize as long as frame does not precede the last reset position by more than
              // seek-preroll.
              oggOpusAudioPacketizer.packetize(bypassSampleBuffer, outputFormat.initializationData);
            }
          }
          if (!haveBypassBatchBufferAndNewSampleSameDecodeOnlyState()
              || !bypassBatchBuffer.append(bypassSampleBuffer)) {
            bypassSampleBufferPending = true;
            return;
          }
          break;
        default:
          throw new IllegalStateException();
      }
    }
  }

  private boolean haveBypassBatchBufferAndNewSampleSameDecodeOnlyState() {
    // TODO: b/295800114 - Splitting the batch buffer by decode-only state isn't safe for formats
    // where not every sample is a keyframe because the downstream component may receive encoded
    // data starting from a non-keyframe sample.
    if (!bypassBatchBuffer.hasSamples()) {
      return true;
    }
    long lastResetPositionUs = getLastResetPositionUs();
    boolean batchBufferIsDecodeOnly =
        isDecodeOnly(lastResetPositionUs, bypassBatchBuffer.getLastSampleTimeUs());
    boolean sampleBufferIsDecodeOnly = isDecodeOnly(lastResetPositionUs, bypassSampleBuffer.timeUs);
    return batchBufferIsDecodeOnly == sampleBufferIsDecodeOnly;
  }

  /**
   * Returns if based on target play time and frame time that the frame should be decode-only.
   *
   * <p>If the format is Opus, then the frame is decode-only if the frame time precedes the start
   * time by more than seek-preroll.
   *
   * @param startTimeUs The time to start playing at.
   * @param frameTimeUs The time of the sample.
   * @return Whether the frame is decode-only.
   */
  private boolean isDecodeOnly(long startTimeUs, long frameTimeUs) {
    // Opus frames that precede the target position by more than seek-preroll should be skipped.
    return frameTimeUs < startTimeUs
        && (outputFormat == null
            || !Objects.equals(outputFormat.sampleMimeType, MimeTypes.AUDIO_OPUS)
            || !OpusUtil.needToDecodeOpusFrame(
                /* startTimeUs= */ startTimeUs, /* frameTimeUs= */ frameTimeUs));
  }

  private static boolean isMediaCodecException(IllegalStateException error) {
    if (error instanceof MediaCodec.CodecException) {
      return true;
    }
    StackTraceElement[] stackTrace = error.getStackTrace();
    return stackTrace.length > 0 && stackTrace[0].getClassName().equals("android.media.MediaCodec");
  }

  /**
   * Returns a mode that specifies when the adaptation workaround should be enabled.
   *
   * <p>When enabled, the workaround queues and discards a blank frame with a resolution whose width
   * and height both equal {@link #ADAPTATION_WORKAROUND_SLICE_WIDTH_HEIGHT}, to reset the decoder's
   * internal state when a format change occurs.
   *
   * <p>See [Internal: b/27807182]. See <a
   * href="https://github.com/google/ExoPlayer/issues/3257">GitHub issue #3257</a>.
   *
   * @param name The name of the decoder.
   * @return The mode specifying when the adaptation workaround should be enabled.
   */
  private @AdaptationWorkaroundMode int codecAdaptationWorkaroundMode(String name) {
    if (SDK_INT <= 25
        && "OMX.Exynos.avc.dec.secure".equals(name)
        && (Build.MODEL.startsWith("SM-T585")
            || Build.MODEL.startsWith("SM-A510")
            || Build.MODEL.startsWith("SM-A520")
            || Build.MODEL.startsWith("SM-J700"))) {
      return ADAPTATION_WORKAROUND_MODE_ALWAYS;
    } else if (SDK_INT < 24
        && ("OMX.Nvidia.h264.decode".equals(name) || "OMX.Nvidia.h264.decode.secure".equals(name))
        && ("flounder".equals(Build.DEVICE)
            || "flounder_lte".equals(Build.DEVICE)
            || "grouper".equals(Build.DEVICE)
            || "tilapia".equals(Build.DEVICE))) {
      return ADAPTATION_WORKAROUND_MODE_SAME_RESOLUTION;
    } else {
      return ADAPTATION_WORKAROUND_MODE_NEVER;
    }
  }

  /**
   * Returns whether the decoder is known to behave incorrectly if flushed prior to having output a
   * {@link MediaFormat}.
   *
   * <p>If true is returned, the renderer will work around the issue by instantiating a new decoder
   * when this case occurs.
   *
   * <p>See [Internal: b/141097367].
   *
   * @param name The name of the decoder.
   * @return True if the decoder is known to behave incorrectly if flushed prior to having output a
   *     {@link MediaFormat}. False otherwise.
   */
  private static boolean codecNeedsSosFlushWorkaround(String name) {
    return SDK_INT == 29 && "c2.android.aac.decoder".equals(name);
  }

  /**
   * Returns whether the decoder is known to handle the propagation of the {@link
   * MediaCodec#BUFFER_FLAG_END_OF_STREAM} flag incorrectly on the host device.
   *
   * <p>If true is returned, the renderer will work around the issue by approximating end of stream
   * behavior without relying on the flag being propagated through to an output buffer by the
   * underlying decoder.
   *
   * @param codecInfo Information about the {@link MediaCodec}.
   * @return True if the decoder is known to handle {@link MediaCodec#BUFFER_FLAG_END_OF_STREAM}
   *     propagation incorrectly on the host device. False otherwise.
   */
  // TODO: b/416719590 - Remove this suppression when the false positive is fixed.
  @SuppressWarnings("ObsoleteSdkInt")
  private static boolean codecNeedsEosPropagationWorkaround(MediaCodecInfo codecInfo) {
    String name = codecInfo.name;
    return (SDK_INT <= 25 && "OMX.rk.video_decoder.avc".equals(name))
        || (SDK_INT <= 29
            && ("OMX.broadcom.video_decoder.tunnel".equals(name)
                || "OMX.broadcom.video_decoder.tunnel.secure".equals(name)
                || "OMX.bcm.vdec.avc.tunnel".equals(name)
                || "OMX.bcm.vdec.avc.tunnel.secure".equals(name)
                || "OMX.bcm.vdec.hevc.tunnel".equals(name)
                || "OMX.bcm.vdec.hevc.tunnel.secure".equals(name)))
        || ("Amazon".equals(Build.MANUFACTURER) && "AFTS".equals(Build.MODEL) && codecInfo.secure);
  }

  /**
   * Returns whether the decoder is known to behave incorrectly if flushed after receiving an input
   * buffer with {@link MediaCodec#BUFFER_FLAG_END_OF_STREAM} set.
   *
   * <p>If true is returned, the renderer will work around the issue by instantiating a new decoder
   * when this case occurs.
   *
   * <p>See [Internal: b/8578467, b/23361053].
   *
   * @param name The name of the decoder.
   * @return True if the decoder is known to behave incorrectly if flushed after receiving an input
   *     buffer with {@link MediaCodec#BUFFER_FLAG_END_OF_STREAM} set. False otherwise.
   */
  private static boolean codecNeedsEosFlushWorkaround(String name) {
    return SDK_INT == 23 && "OMX.google.vorbis.decoder".equals(name);
  }

  private static final class OutputStreamInfo {

    public static final OutputStreamInfo UNSET =
        new OutputStreamInfo(
            /* previousStreamLastBufferTimeUs= */ C.TIME_UNSET,
            /* startPositionUs= */ C.TIME_UNSET,
            /* streamOffsetUs= */ C.TIME_UNSET);

    public final long previousStreamLastBufferTimeUs;
    public final long startPositionUs;
    public final long streamOffsetUs;
    public final TimedValueQueue<Format> formatQueue;

    public OutputStreamInfo(
        long previousStreamLastBufferTimeUs, long startPositionUs, long streamOffsetUs) {
      this.previousStreamLastBufferTimeUs = previousStreamLastBufferTimeUs;
      this.startPositionUs = startPositionUs;
      this.streamOffsetUs = streamOffsetUs;
      this.formatQueue = new TimedValueQueue<>();
    }
  }

  @RequiresApi(31)
  private static final class Api31 {
    private Api31() {}

    public static void setLogSessionIdToMediaCodecFormat(
        MediaCodecAdapter.Configuration codecConfiguration, PlayerId playerId) {
      LogSessionId logSessionId = playerId.getLogSessionId();
      if (!logSessionId.equals(LogSessionId.LOG_SESSION_ID_NONE)) {
        codecConfiguration.mediaFormat.setString("log-session-id", logSessionId.getStringId());
      }
    }
  }

  private final class MediaCodecRendererCodecAdapterListener
      implements MediaCodecAdapter.OnBufferAvailableListener {
    @Override
    public void onInputBufferAvailable() {
      if (wakeupListener != null) {
        wakeupListener.onWakeup();
      }
    }

    @Override
    public void onOutputBufferAvailable() {
      if (wakeupListener != null) {
        wakeupListener.onWakeup();
      }
    }
  }
}
