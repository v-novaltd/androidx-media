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
import static androidx.media3.exoplayer.DecoderReuseEvaluation.DISCARD_REASON_AUDIO_CHANNEL_COUNT_CHANGED;
import static androidx.media3.exoplayer.DecoderReuseEvaluation.DISCARD_REASON_AUDIO_ENCODING_CHANGED;
import static androidx.media3.exoplayer.DecoderReuseEvaluation.DISCARD_REASON_AUDIO_SAMPLE_RATE_CHANGED;
import static androidx.media3.exoplayer.DecoderReuseEvaluation.DISCARD_REASON_INITIALIZATION_DATA_CHANGED;
import static androidx.media3.exoplayer.DecoderReuseEvaluation.DISCARD_REASON_MIME_TYPE_CHANGED;
import static androidx.media3.exoplayer.DecoderReuseEvaluation.DISCARD_REASON_VIDEO_COLOR_INFO_CHANGED;
import static androidx.media3.exoplayer.DecoderReuseEvaluation.DISCARD_REASON_VIDEO_RESOLUTION_CHANGED;
import static androidx.media3.exoplayer.DecoderReuseEvaluation.DISCARD_REASON_VIDEO_ROTATION_CHANGED;
import static androidx.media3.exoplayer.DecoderReuseEvaluation.DISCARD_REASON_WORKAROUND;
import static androidx.media3.exoplayer.DecoderReuseEvaluation.REUSE_RESULT_NO;
import static androidx.media3.exoplayer.DecoderReuseEvaluation.REUSE_RESULT_YES_WITHOUT_RECONFIGURATION;
import static androidx.media3.exoplayer.DecoderReuseEvaluation.REUSE_RESULT_YES_WITH_FLUSH;
import static androidx.media3.exoplayer.DecoderReuseEvaluation.REUSE_RESULT_YES_WITH_RECONFIGURATION;
import static androidx.media3.exoplayer.mediacodec.MediaCodecPerformancePointCoverageProvider.COVERAGE_RESULT_NO;
import static androidx.media3.exoplayer.mediacodec.MediaCodecPerformancePointCoverageProvider.COVERAGE_RESULT_YES;
import static androidx.media3.exoplayer.mediacodec.MediaCodecUtil.createCodecProfileLevel;

import android.graphics.Point;
import android.media.MediaCodec;
import android.media.MediaCodecInfo.AudioCapabilities;
import android.media.MediaCodecInfo.CodecCapabilities;
import android.media.MediaCodecInfo.CodecProfileLevel;
import android.media.MediaCodecInfo.VideoCapabilities;
import android.os.Build;
import android.util.Pair;
import android.util.Range;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.media3.common.C;
import androidx.media3.common.ColorInfo;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.Assertions;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.exoplayer.DecoderReuseEvaluation;
import androidx.media3.exoplayer.DecoderReuseEvaluation.DecoderDiscardReasons;
import java.util.Objects;

/** Information about a {@link MediaCodec} for a given MIME type. */
@SuppressWarnings("InlinedApi")
@UnstableApi
public final class MediaCodecInfo {

  public static final String TAG = "MediaCodecInfo";

  /**
   * The value returned by {@link #getMaxSupportedInstances()} if the upper bound on the maximum
   * number of supported instances is unknown.
   */
  public static final int MAX_SUPPORTED_INSTANCES_UNKNOWN = -1;

  /**
   * The name of the decoder.
   *
   * <p>May be passed to {@link MediaCodec#createByCodecName(String)} to create an instance of the
   * decoder.
   */
  public final String name;

  /** The MIME type handled by the codec. */
  public final String mimeType;

  /**
   * The MIME type that the codec uses for media of type {@link #mimeType}. Equal to {@link
   * #mimeType} unless the codec is known to use a non-standard MIME type alias.
   */
  public final String codecMimeType;

  /**
   * The capabilities of the decoder, like the profiles/levels it supports, or {@code null} if not
   * known.
   */
  @Nullable public final CodecCapabilities capabilities;

  /**
   * Whether the decoder supports seamless resolution switches.
   *
   * @see CodecCapabilities#isFeatureSupported(String)
   * @see CodecCapabilities#FEATURE_AdaptivePlayback
   */
  public final boolean adaptive;

  /**
   * Whether the decoder supports tunneling.
   *
   * @see CodecCapabilities#isFeatureSupported(String)
   * @see CodecCapabilities#FEATURE_TunneledPlayback
   */
  public final boolean tunneling;

  /**
   * Whether the decoder is secure.
   *
   * @see CodecCapabilities#isFeatureSupported(String)
   * @see CodecCapabilities#FEATURE_SecurePlayback
   */
  public final boolean secure;

  /**
   * Whether the codec is hardware accelerated.
   *
   * <p>This could be an approximation as the exact information is only provided in API levels 29+.
   *
   * @see android.media.MediaCodecInfo#isHardwareAccelerated()
   */
  public final boolean hardwareAccelerated;

  /**
   * Whether the codec is software only.
   *
   * <p>This could be an approximation as the exact information is only provided in API levels 29+.
   *
   * @see android.media.MediaCodecInfo#isSoftwareOnly()
   */
  public final boolean softwareOnly;

  /**
   * Whether the codec is from the vendor.
   *
   * <p>This could be an approximation as the exact information is only provided in API levels 29+.
   *
   * @see android.media.MediaCodecInfo#isVendor()
   */
  public final boolean vendor;

  /**
   * Whether the codec supports "detached" surface mode where it is able to decode without an
   * attached surface. Only relevant for video codecs.
   *
   * @see android.media.MediaCodecInfo.CodecCapabilities#FEATURE_DetachedSurface
   */
  public final boolean detachedSurfaceSupported;

  private final boolean isVideo;

  // Memoization for getMaxSupportedFrameRate
  private int maxFrameRateWidth;
  private int maxFrameRateHeight;
  private float maxFrameRate;

  /**
   * Creates an instance.
   *
   * @param name The name of the {@link MediaCodec}.
   * @param mimeType A MIME type supported by the {@link MediaCodec}.
   * @param codecMimeType The MIME type that the codec uses for media of type {@code #mimeType}.
   *     Equal to {@code mimeType} unless the codec is known to use a non-standard MIME type alias.
   * @param capabilities The capabilities of the {@link MediaCodec} for the specified MIME type, or
   *     {@code null} if not known.
   * @param hardwareAccelerated Whether the {@link MediaCodec} is hardware accelerated.
   * @param softwareOnly Whether the {@link MediaCodec} is software only.
   * @param vendor Whether the {@link MediaCodec} is provided by the vendor.
   * @param forceDisableAdaptive Whether {@link #adaptive} should be forced to {@code false}.
   * @param forceSecure Whether {@link #secure} should be forced to {@code true}.
   * @return The created instance.
   */
  public static MediaCodecInfo newInstance(
      String name,
      String mimeType,
      String codecMimeType,
      @Nullable CodecCapabilities capabilities,
      boolean hardwareAccelerated,
      boolean softwareOnly,
      boolean vendor,
      boolean forceDisableAdaptive,
      boolean forceSecure) {
    return new MediaCodecInfo(
        name,
        mimeType,
        codecMimeType,
        capabilities,
        hardwareAccelerated,
        softwareOnly,
        vendor,
        /* adaptive= */ !forceDisableAdaptive && capabilities != null && isAdaptive(capabilities),
        /* tunneling= */ capabilities != null && isTunneling(capabilities),
        /* secure= */ forceSecure || (capabilities != null && isSecure(capabilities)),
        isDetachedSurfaceSupported(capabilities));
  }

  @VisibleForTesting
  /* package */ MediaCodecInfo(
      String name,
      String mimeType,
      String codecMimeType,
      @Nullable CodecCapabilities capabilities,
      boolean hardwareAccelerated,
      boolean softwareOnly,
      boolean vendor,
      boolean adaptive,
      boolean tunneling,
      boolean secure,
      boolean detachedSurfaceSupported) {
    this.name = Assertions.checkNotNull(name);
    this.mimeType = mimeType;
    this.codecMimeType = codecMimeType;
    this.capabilities = capabilities;
    this.hardwareAccelerated = hardwareAccelerated;
    this.softwareOnly = softwareOnly;
    this.vendor = vendor;
    this.adaptive = adaptive;
    this.tunneling = tunneling;
    this.secure = secure;
    this.detachedSurfaceSupported = detachedSurfaceSupported;
    isVideo = MimeTypes.isVideo(mimeType);
    maxFrameRate = C.RATE_UNSET;
    maxFrameRateWidth = C.LENGTH_UNSET;
    maxFrameRateHeight = C.LENGTH_UNSET;
  }

  @Override
  public String toString() {
    return name;
  }

  /**
   * The profile levels supported by the decoder.
   *
   * @return The profile levels supported by the decoder.
   */
  public CodecProfileLevel[] getProfileLevels() {
    return capabilities == null || capabilities.profileLevels == null
        ? new CodecProfileLevel[0]
        : capabilities.profileLevels;
  }

  /**
   * Returns an upper bound on the maximum number of supported instances, or {@link
   * #MAX_SUPPORTED_INSTANCES_UNKNOWN} if unknown. Applications should not expect to operate more
   * instances than the returned maximum.
   *
   * @see CodecCapabilities#getMaxSupportedInstances()
   */
  public int getMaxSupportedInstances() {
    if (capabilities == null) {
      return MAX_SUPPORTED_INSTANCES_UNKNOWN;
    }
    return capabilities.getMaxSupportedInstances();
  }

  /**
   * Returns whether the decoder may support decoding the given {@code format} both functionally and
   * performantly.
   *
   * @param format The input media format.
   * @return Whether the decoder may support decoding the given {@code format}.
   * @throws MediaCodecUtil.DecoderQueryException Thrown if an error occurs while querying decoders.
   */
  public boolean isFormatSupported(Format format) throws MediaCodecUtil.DecoderQueryException {
    if (!isSampleMimeTypeSupported(format)) {
      return false;
    }

    if (!isCodecProfileAndLevelSupported(format, /* checkPerformanceCapabilities= */ true)) {
      return false;
    }

    if (!isCompressedAudioBitDepthSupported(format)) {
      return false;
    }

    if (isVideo) {
      if (format.width <= 0 || format.height <= 0) {
        return true;
      }
      return isVideoSizeAndRateSupportedV21(format.width, format.height, format.frameRate);
    } else { // Audio
      return (format.sampleRate == Format.NO_VALUE
              || isAudioSampleRateSupportedV21(format.sampleRate))
          && (format.channelCount == Format.NO_VALUE
              || isAudioChannelCountSupportedV21(format.channelCount));
    }
  }

  /**
   * Returns whether the decoder may functionally support decoding the given {@code format}.
   *
   * @param format The input media format.
   * @return Whether the decoder may functionally support decoding the given {@code format}.
   */
  public boolean isFormatFunctionallySupported(Format format) {
    return isSampleMimeTypeSupported(format)
        && isCodecProfileAndLevelSupported(format, /* checkPerformanceCapabilities= */ false)
        && isCompressedAudioBitDepthSupported(format);
  }

  private boolean isSampleMimeTypeSupported(Format format) {
    return mimeType.equals(format.sampleMimeType)
        || mimeType.equals(MediaCodecUtil.getAlternativeCodecMimeType(format));
  }

  private boolean isCodecProfileAndLevelSupported(
      Format format, boolean checkPerformanceCapabilities) {
    Pair<Integer, Integer> codecProfileAndLevel = MediaCodecUtil.getCodecProfileAndLevel(format);
    if (format.sampleMimeType != null && format.sampleMimeType.equals(MimeTypes.VIDEO_MV_HEVC)) {
      String normalizedCodecMimeType = MimeTypes.normalizeMimeType(codecMimeType);
      if (normalizedCodecMimeType.equals(MimeTypes.VIDEO_MV_HEVC)) {
        // Currently as there is no formal support for MV-HEVC within Android framework, the profile
        // is not correctly specified by the underlying codec; just assume the profile obtained from
        // the MV-HEVC sample is supported.
        return true;
      } else if (normalizedCodecMimeType.equals(MimeTypes.VIDEO_H265)) {
        // Falling back to single-layer HEVC from MV-HEVC.  Get base layer profile and level.
        codecProfileAndLevel = MediaCodecUtil.getHevcBaseLayerCodecProfileAndLevel(format);
      }
    }

    if (codecProfileAndLevel == null) {
      // If we don't know any better, we assume that the profile and level are supported.
      return true;
    }
    int profile = codecProfileAndLevel.first;
    int level = codecProfileAndLevel.second;
    if (MimeTypes.VIDEO_DOLBY_VISION.equals(format.sampleMimeType)) {
      // If this codec is H.264, H.265 or AV1, we only support the Dolby Vision base layer and need
      // to map the Dolby Vision profile to the corresponding base layer profile. Also assume all
      // levels of this base layer profile are supported.
      switch (mimeType) {
        case MimeTypes.VIDEO_H264:
          profile = CodecProfileLevel.AVCProfileHigh;
          level = 0;
          break;
        case MimeTypes.VIDEO_H265:
          profile = CodecProfileLevel.HEVCProfileMain10;
          level = 0;
          break;
        case MimeTypes.VIDEO_AV1:
          profile = CodecProfileLevel.AV1ProfileMain10;
          level = 0;
          break;
        default:
          break;
      }
    }

    if (!isVideo
        && !mimeType.equals(MimeTypes.AUDIO_AC4)
        && profile != CodecProfileLevel.AACObjectXHE) {
      // AC-4 decoders report profile levels or audio capabilities that determine whether the input
      // format is supported or not.
      // With other encodings some devices/builds underreport audio capabilities, so assume support
      // except for xHE-AAC which may not be widely supported. See
      // https://github.com/google/ExoPlayer/issues/5145.
      return true;
    }

    CodecProfileLevel[] profileLevels = getProfileLevels();
    if (mimeType.equals(MimeTypes.AUDIO_AC4) && profileLevels.length == 0) {
      // Some older devices don't report profile levels for AC-4. Estimate them using other data
      // in the codec capabilities.
      profileLevels = estimateLegacyAc4ProfileLevels(capabilities);
    }
    if (SDK_INT == 23 && MimeTypes.VIDEO_VP9.equals(mimeType) && profileLevels.length == 0) {
      // Some older devices don't report profile levels for VP9. Estimate them using other data in
      // the codec capabilities.
      profileLevels = estimateLegacyVp9ProfileLevels(capabilities);
    }

    for (CodecProfileLevel profileLevel : profileLevels) {
      if (profileLevel.profile == profile
          && (profileLevel.level >= level || !checkPerformanceCapabilities)
          && !needsProfileExcludedWorkaround(mimeType, profile)) {
        return true;
      }
    }
    logNoSupport("codec.profileLevel, " + format.codecs + ", " + codecMimeType);
    return false;
  }

  private boolean isCompressedAudioBitDepthSupported(Format format) {
    // MediaCodec doesn't have a way to query FLAC decoder bit-depth support.
    // c2.android.flac.decoder is known not to support 32-bit until API 34. We optimistically assume
    // that another (unrecognized) FLAC decoder does support 32-bit on all API levels where it
    // exists.
    return !Objects.equals(format.sampleMimeType, MimeTypes.AUDIO_FLAC)
        || format.pcmEncoding != C.ENCODING_PCM_32BIT
        || SDK_INT >= 34
        || !name.equals("c2.android.flac.decoder");
  }

  /** Whether the codec handles HDR10+ out-of-band metadata. */
  public boolean isHdr10PlusOutOfBandMetadataSupported() {
    if (SDK_INT >= 29 && MimeTypes.VIDEO_VP9.equals(mimeType)) {
      for (CodecProfileLevel capabilities : getProfileLevels()) {
        if (capabilities.profile == CodecProfileLevel.VP9Profile2HDR10Plus) {
          return true;
        }
      }
    }
    return false;
  }

  /**
   * Returns whether it may be possible to adapt an instance of this decoder to playing a different
   * format when the codec is configured to play media in the specified {@code format}.
   *
   * <p>For adaptation to succeed, the codec must also be configured with appropriate maximum values
   * and {@link #canReuseCodec(Format, Format)} must return {@code true} for the old/new formats.
   *
   * @param format The format of media for which the decoder will be configured.
   * @return Whether adaptation may be possible
   */
  public boolean isSeamlessAdaptationSupported(Format format) {
    if (isVideo) {
      return adaptive;
    } else {
      Pair<Integer, Integer> profileLevel = MediaCodecUtil.getCodecProfileAndLevel(format);
      return profileLevel != null && profileLevel.first == CodecProfileLevel.AACObjectXHE;
    }
  }

  /**
   * Evaluates whether it's possible to reuse an instance of this decoder that's currently decoding
   * {@code oldFormat} to decode {@code newFormat} instead.
   *
   * <p>For adaptation to succeed, the codec must also be configured with maximum values that are
   * compatible with the new format.
   *
   * @param oldFormat The format being decoded.
   * @param newFormat The new format.
   * @return The result of the evaluation.
   */
  public DecoderReuseEvaluation canReuseCodec(Format oldFormat, Format newFormat) {
    @DecoderDiscardReasons int discardReasons = 0;
    if (!Objects.equals(oldFormat.sampleMimeType, newFormat.sampleMimeType)) {
      discardReasons |= DISCARD_REASON_MIME_TYPE_CHANGED;
    }

    if (isVideo) {
      if (oldFormat.rotationDegrees != newFormat.rotationDegrees) {
        discardReasons |= DISCARD_REASON_VIDEO_ROTATION_CHANGED;
      }
      boolean resolutionChanged =
          oldFormat.width != newFormat.width || oldFormat.height != newFormat.height;
      if (!adaptive && resolutionChanged) {
        discardReasons |= DISCARD_REASON_VIDEO_RESOLUTION_CHANGED;
      }
      if ((!ColorInfo.isEquivalentToAssumedSdrDefault(oldFormat.colorInfo)
              || !ColorInfo.isEquivalentToAssumedSdrDefault(newFormat.colorInfo))
          && !Objects.equals(oldFormat.colorInfo, newFormat.colorInfo)) {
        // Don't perform detailed checks if both ColorInfos fall within the default SDR assumption.
        discardReasons |= DISCARD_REASON_VIDEO_COLOR_INFO_CHANGED;
      }
      if (needsAdaptationReconfigureWorkaround(name)
          && !oldFormat.initializationDataEquals(newFormat)) {
        discardReasons |= DISCARD_REASON_WORKAROUND;
      }

      if (oldFormat.decodedWidth != Format.NO_VALUE
          && oldFormat.decodedHeight != Format.NO_VALUE
          && oldFormat.decodedWidth == newFormat.decodedWidth
          && oldFormat.decodedHeight == newFormat.decodedHeight
          && resolutionChanged) {
        // Work around a bug where MediaCodec fails to adapt between formats if the compressed
        // picture dimensions match but the cropped region for display differs.
        // See b/409036359.
        discardReasons |= DISCARD_REASON_WORKAROUND;
      }

      if (discardReasons == 0) {
        return new DecoderReuseEvaluation(
            name,
            oldFormat,
            newFormat,
            oldFormat.initializationDataEquals(newFormat)
                ? REUSE_RESULT_YES_WITHOUT_RECONFIGURATION
                : REUSE_RESULT_YES_WITH_RECONFIGURATION,
            /* discardReasons= */ 0);
      }
    } else {
      if (oldFormat.channelCount != newFormat.channelCount) {
        discardReasons |= DISCARD_REASON_AUDIO_CHANNEL_COUNT_CHANGED;
      }
      if (oldFormat.sampleRate != newFormat.sampleRate) {
        discardReasons |= DISCARD_REASON_AUDIO_SAMPLE_RATE_CHANGED;
      }
      if (oldFormat.pcmEncoding != newFormat.pcmEncoding) {
        discardReasons |= DISCARD_REASON_AUDIO_ENCODING_CHANGED;
      }

      // Check whether we're adapting between two xHE-AAC formats, for which adaptation is possible
      // without reconfiguration or flushing.
      if (discardReasons == 0 && MimeTypes.AUDIO_AAC.equals(mimeType)) {
        @Nullable
        Pair<Integer, Integer> oldCodecProfileLevel =
            MediaCodecUtil.getCodecProfileAndLevel(oldFormat);
        @Nullable
        Pair<Integer, Integer> newCodecProfileLevel =
            MediaCodecUtil.getCodecProfileAndLevel(newFormat);
        if (oldCodecProfileLevel != null && newCodecProfileLevel != null) {
          int oldProfile = oldCodecProfileLevel.first;
          int newProfile = newCodecProfileLevel.first;
          if (oldProfile == CodecProfileLevel.AACObjectXHE
              && newProfile == CodecProfileLevel.AACObjectXHE) {
            return new DecoderReuseEvaluation(
                name,
                oldFormat,
                newFormat,
                REUSE_RESULT_YES_WITHOUT_RECONFIGURATION,
                /* discardReasons= */ 0);
          }
        }
      }

      if (!oldFormat.initializationDataEquals(newFormat)) {
        discardReasons |= DISCARD_REASON_INITIALIZATION_DATA_CHANGED;
      }
      if (needsAdaptationFlushWorkaround(mimeType)) {
        discardReasons |= DISCARD_REASON_WORKAROUND;
      }

      if (discardReasons == 0) {
        return new DecoderReuseEvaluation(
            name, oldFormat, newFormat, REUSE_RESULT_YES_WITH_FLUSH, /* discardReasons= */ 0);
      }
    }

    return new DecoderReuseEvaluation(name, oldFormat, newFormat, REUSE_RESULT_NO, discardReasons);
  }

  /**
   * Whether the decoder supports video with a given width, height and frame rate.
   *
   * @param width Width in pixels.
   * @param height Height in pixels.
   * @param frameRate Optional frame rate in frames per second. Ignored if set to {@link
   *     Format#NO_VALUE} or any value less than or equal to 0.
   * @return Whether the decoder supports video with the given width, height and frame rate.
   */
  public boolean isVideoSizeAndRateSupportedV21(int width, int height, double frameRate) {
    if (capabilities == null) {
      logNoSupport("sizeAndRate.caps");
      return false;
    }
    VideoCapabilities videoCapabilities = capabilities.getVideoCapabilities();
    if (videoCapabilities == null) {
      logNoSupport("sizeAndRate.vCaps");
      return false;
    }

    if (SDK_INT >= 29) {
      @MediaCodecPerformancePointCoverageProvider.PerformancePointCoverageResult
      int evaluation =
          MediaCodecPerformancePointCoverageProvider.areResolutionAndFrameRateCovered(
              videoCapabilities, width, height, frameRate);
      if (evaluation == COVERAGE_RESULT_YES) {
        return true;
      } else if (evaluation == COVERAGE_RESULT_NO) {
        logNoSupport("sizeAndRate.cover, " + width + "x" + height + "@" + frameRate);
        return false;
      }
      // If COVERAGE_RESULT_NO_PERFORMANCE_POINTS_UNSUPPORTED then logic falls through
      // to code below.
    }

    if (!areSizeAndRateSupported(videoCapabilities, width, height, frameRate)) {
      if (width >= height
          || !needsRotatedVerticalResolutionWorkaround(name)
          || !areSizeAndRateSupported(videoCapabilities, height, width, frameRate)) {
        logNoSupport("sizeAndRate.support, " + width + "x" + height + "@" + frameRate);
        return false;
      }
      logAssumedSupport("sizeAndRate.rotated, " + width + "x" + height + "@" + frameRate);
    }
    return true;
  }

  /**
   * Returns the max video frame rate that this codec can support at the provided resolution, or
   * {@link C#RATE_UNSET} if this is not a video codec.
   */
  public float getMaxSupportedFrameRate(int width, int height) {
    if (!isVideo) {
      return C.RATE_UNSET;
    }
    if (maxFrameRate != C.RATE_UNSET
        && maxFrameRateWidth == width
        && maxFrameRateHeight == height) {
      return maxFrameRate;
    }
    maxFrameRate = computeMaxSupportedFrameRate(width, height);
    maxFrameRateWidth = width;
    maxFrameRateHeight = height;
    return maxFrameRate;
  }

  private float computeMaxSupportedFrameRate(int width, int height) {
    // TODO: b/400765670 - Use the PerformancePoint API for this when it's available. Without
    // that API, we binary search instead.
    float maxFrameRate = 1024;
    float minFrameRate = 0;
    if (isVideoSizeAndRateSupportedV21(width, height, maxFrameRate)) {
      return maxFrameRate;
    }
    while (Math.abs(maxFrameRate - minFrameRate) > 5f) {
      float testFrameRate = minFrameRate + (maxFrameRate - minFrameRate) / 2;
      if (isVideoSizeAndRateSupportedV21(width, height, testFrameRate)) {
        minFrameRate = testFrameRate;
      } else {
        maxFrameRate = testFrameRate;
      }
    }
    return minFrameRate;
  }

  /**
   * Returns the smallest video size greater than or equal to a specified size that also satisfies
   * the {@link MediaCodec}'s width and height alignment requirements.
   *
   * @param width Width in pixels.
   * @param height Height in pixels.
   * @return The smallest video size greater than or equal to the specified size that also satisfies
   *     the {@link MediaCodec}'s width and height alignment requirements, or null if not a video
   *     codec.
   */
  @Nullable
  public Point alignVideoSizeV21(int width, int height) {
    if (capabilities == null) {
      return null;
    }
    VideoCapabilities videoCapabilities = capabilities.getVideoCapabilities();
    if (videoCapabilities == null) {
      return null;
    }
    return alignVideoSize(videoCapabilities, width, height);
  }

  /**
   * Whether the decoder supports audio with a given sample rate.
   *
   * @param sampleRate The sample rate in Hz.
   * @return Whether the decoder supports audio with the given sample rate.
   */
  public boolean isAudioSampleRateSupportedV21(int sampleRate) {
    if (capabilities == null) {
      logNoSupport("sampleRate.caps");
      return false;
    }
    AudioCapabilities audioCapabilities = capabilities.getAudioCapabilities();
    if (audioCapabilities == null) {
      logNoSupport("sampleRate.aCaps");
      return false;
    }
    if (!audioCapabilities.isSampleRateSupported(sampleRate)) {
      logNoSupport("sampleRate.support, " + sampleRate);
      return false;
    }
    return true;
  }

  /**
   * Whether the decoder supports audio with a given channel count.
   *
   * @param channelCount The channel count.
   * @return Whether the decoder supports audio with the given channel count.
   */
  public boolean isAudioChannelCountSupportedV21(int channelCount) {
    if (capabilities == null) {
      logNoSupport("channelCount.caps");
      return false;
    }
    AudioCapabilities audioCapabilities = capabilities.getAudioCapabilities();
    if (audioCapabilities == null) {
      logNoSupport("channelCount.aCaps");
      return false;
    }
    int maxInputChannelCount =
        adjustMaxInputChannelCount(name, mimeType, audioCapabilities.getMaxInputChannelCount());
    if (maxInputChannelCount < channelCount) {
      logNoSupport("channelCount.support, " + channelCount);
      return false;
    }
    return true;
  }

  private void logNoSupport(String message) {
    Log.d(
        TAG,
        "NoSupport ["
            + message
            + "] ["
            + name
            + ", "
            + mimeType
            + "] ["
            + Util.DEVICE_DEBUG_INFO
            + "]");
  }

  private void logAssumedSupport(String message) {
    Log.d(
        TAG,
        "AssumedSupport ["
            + message
            + "] ["
            + name
            + ", "
            + mimeType
            + "] ["
            + Util.DEVICE_DEBUG_INFO
            + "]");
  }

  private static int adjustMaxInputChannelCount(String name, String mimeType, int maxChannelCount) {
    if (maxChannelCount > 1 || (SDK_INT >= 26 && maxChannelCount > 0)) {
      // The maximum channel count looks like it's been set correctly.
      return maxChannelCount;
    }
    if (MimeTypes.AUDIO_MPEG.equals(mimeType)
        || MimeTypes.AUDIO_AMR_NB.equals(mimeType)
        || MimeTypes.AUDIO_AMR_WB.equals(mimeType)
        || MimeTypes.AUDIO_AAC.equals(mimeType)
        || MimeTypes.AUDIO_VORBIS.equals(mimeType)
        || MimeTypes.AUDIO_OPUS.equals(mimeType)
        || MimeTypes.AUDIO_RAW.equals(mimeType)
        || MimeTypes.AUDIO_FLAC.equals(mimeType)
        || MimeTypes.AUDIO_ALAW.equals(mimeType)
        || MimeTypes.AUDIO_MLAW.equals(mimeType)
        || MimeTypes.AUDIO_MSGSM.equals(mimeType)) {
      // Platform code should have set a default.
      return maxChannelCount;
    }
    // The maximum channel count looks incorrect. Adjust it to an assumed default.
    int assumedMaxChannelCount;
    if (MimeTypes.AUDIO_AC3.equals(mimeType)) {
      assumedMaxChannelCount = 6;
    } else if (MimeTypes.AUDIO_E_AC3.equals(mimeType)) {
      assumedMaxChannelCount = 16;
    } else {
      // Default to the platform limit, which is 30.
      assumedMaxChannelCount = 30;
    }
    Log.w(
        TAG,
        "AssumedMaxChannelAdjustment: "
            + name
            + ", ["
            + maxChannelCount
            + " to "
            + assumedMaxChannelCount
            + "]");
    return assumedMaxChannelCount;
  }

  private static boolean isAdaptive(CodecCapabilities capabilities) {
    return capabilities.isFeatureSupported(CodecCapabilities.FEATURE_AdaptivePlayback);
  }

  private static boolean isTunneling(CodecCapabilities capabilities) {
    return capabilities.isFeatureSupported(CodecCapabilities.FEATURE_TunneledPlayback);
  }

  private static boolean isSecure(CodecCapabilities capabilities) {
    return capabilities.isFeatureSupported(CodecCapabilities.FEATURE_SecurePlayback);
  }

  private static boolean isDetachedSurfaceSupported(@Nullable CodecCapabilities capabilities) {
    return SDK_INT >= 35
        && capabilities != null
        && capabilities.isFeatureSupported(CodecCapabilities.FEATURE_DetachedSurface)
        && !needsDetachedSurfaceUnsupportedWorkaround();
  }

  private static boolean areSizeAndRateSupported(
      VideoCapabilities capabilities, int width, int height, double frameRate) {
    // Don't ever fail due to alignment. See: https://github.com/google/ExoPlayer/issues/6551.
    Point alignedSize = alignVideoSize(capabilities, width, height);
    width = alignedSize.x;
    height = alignedSize.y;

    // VideoCapabilities.areSizeAndRateSupported incorrectly returns false if frameRate < 1 on some
    // versions of Android, so we only check the size in this case [Internal ref: b/153940404].
    if (frameRate == Format.NO_VALUE || frameRate < 1) {
      return capabilities.isSizeSupported(width, height);
    } else {
      // The signaled frame rate may be slightly higher than the actual frame rate, so we take the
      // floor to avoid situations where a range check in areSizeAndRateSupported fails due to
      // slightly exceeding the limits for a standard format (e.g., 1080p at 30 fps).
      double floorFrameRate = Math.floor(frameRate);
      if (!capabilities.areSizeAndRateSupported(width, height, floorFrameRate)) {
        return false;
      }
      if (SDK_INT < 24) {
        return true;
      }
      @Nullable
      Range<Double> achievableFrameRates = capabilities.getAchievableFrameRatesFor(width, height);
      if (achievableFrameRates == null) {
        return true;
      }
      return floorFrameRate <= achievableFrameRates.getUpper();
    }
  }

  private static Point alignVideoSize(VideoCapabilities capabilities, int width, int height) {
    int widthAlignment = capabilities.getWidthAlignment();
    int heightAlignment = capabilities.getHeightAlignment();
    return new Point(
        Util.ceilDivide(width, widthAlignment) * widthAlignment,
        Util.ceilDivide(height, heightAlignment) * heightAlignment);
  }

  /**
   * Called on devices with AC-4 decoders whose {@link CodecCapabilities} do not report profile
   * levels. The returned {@link CodecProfileLevel CodecProfileLevels} are estimated based on other
   * data in the {@link CodecCapabilities}.
   *
   * @param capabilities The {@link CodecCapabilities} for an AC-4 decoder, or {@code null} if not
   *     known.
   * @return The estimated {@link CodecProfileLevel CodecProfileLevels} for the decoder.
   */
  private static CodecProfileLevel[] estimateLegacyAc4ProfileLevels(
      @Nullable CodecCapabilities capabilities) {
    int maxInChannelCount = 2;
    if (capabilities != null) {
      @Nullable AudioCapabilities audioCapabilities = capabilities.getAudioCapabilities();
      if (audioCapabilities != null) {
        maxInChannelCount = audioCapabilities.getMaxInputChannelCount();
      }
    }

    int level = CodecProfileLevel.AC4Level3;
    if (maxInChannelCount > 18) { // AC-4 Level 3 stream is up to 17.1 channel
      level = CodecProfileLevel.AC4Level4;
    }

    return new CodecProfileLevel[] {
      createCodecProfileLevel(CodecProfileLevel.AC4Profile00, level),
      createCodecProfileLevel(CodecProfileLevel.AC4Profile10, level),
      createCodecProfileLevel(CodecProfileLevel.AC4Profile11, level),
      createCodecProfileLevel(CodecProfileLevel.AC4Profile21, level),
      createCodecProfileLevel(CodecProfileLevel.AC4Profile22, level)
    };
  }

  /**
   * Called on devices with {@link Build.VERSION#SDK_INT} 23 and below, for VP9 decoders whose
   * {@link CodecCapabilities} do not correctly report profile levels. The returned {@link
   * CodecProfileLevel CodecProfileLevels} are estimated based on other data in the {@link
   * CodecCapabilities}.
   *
   * @param capabilities The {@link CodecCapabilities} for a VP9 decoder, or {@code null} if not
   *     known.
   * @return The estimated {@link CodecProfileLevel CodecProfileLevels} for the decoder.
   */
  private static CodecProfileLevel[] estimateLegacyVp9ProfileLevels(
      @Nullable CodecCapabilities capabilities) {
    int maxBitrate = 0;
    if (capabilities != null) {
      @Nullable VideoCapabilities videoCapabilities = capabilities.getVideoCapabilities();
      if (videoCapabilities != null) {
        maxBitrate = videoCapabilities.getBitrateRange().getUpper();
      }
    }

    // Values taken from https://www.webmproject.org/vp9/levels.
    int level;
    if (maxBitrate >= 180_000_000) {
      level = CodecProfileLevel.VP9Level52;
    } else if (maxBitrate >= 120_000_000) {
      level = CodecProfileLevel.VP9Level51;
    } else if (maxBitrate >= 60_000_000) {
      level = CodecProfileLevel.VP9Level5;
    } else if (maxBitrate >= 30_000_000) {
      level = CodecProfileLevel.VP9Level41;
    } else if (maxBitrate >= 18_000_000) {
      level = CodecProfileLevel.VP9Level4;
    } else if (maxBitrate >= 12_000_000) {
      level = CodecProfileLevel.VP9Level31;
    } else if (maxBitrate >= 7_200_000) {
      level = CodecProfileLevel.VP9Level3;
    } else if (maxBitrate >= 3_600_000) {
      level = CodecProfileLevel.VP9Level21;
    } else if (maxBitrate >= 1_800_000) {
      level = CodecProfileLevel.VP9Level2;
    } else if (maxBitrate >= 800_000) {
      level = CodecProfileLevel.VP9Level11;
    } else { // Assume level 1 is always supported.
      level = CodecProfileLevel.VP9Level1;
    }

    // Since this method is for legacy devices only, assume that only profile 0 is supported.
    return new CodecProfileLevel[] {createCodecProfileLevel(CodecProfileLevel.VP9Profile0, level)};
  }

  /**
   * Returns whether the decoder is known to fail when an attempt is made to reconfigure it with a
   * new format's configuration data.
   *
   * @param name The name of the decoder.
   * @return Whether the decoder is known to fail when an attempt is made to reconfigure it with a
   *     new format's configuration data.
   */
  private static boolean needsAdaptationReconfigureWorkaround(String name) {
    return Build.MODEL.startsWith("SM-T230") && "OMX.MARVELL.VIDEO.HW.CODA7542DECODER".equals(name);
  }

  /**
   * Returns whether the decoder is known to behave incorrectly if flushed to adapt to a new format.
   *
   * @param mimeType The name of the MIME type.
   * @return Whether the decoder is known to to behave incorrectly if flushed to adapt to a new
   *     format.
   */
  private static boolean needsAdaptationFlushWorkaround(String mimeType) {
    // For Opus, we don't flush and reuse the codec because the decoder may discard samples after
    // flushing, which would result in audio being dropped just after a stream change (see
    // [Internal: b/143450854]). For other formats, we allow reuse after flushing if the codec
    // initialization data is unchanged.
    return MimeTypes.AUDIO_OPUS.equals(mimeType);
  }

  /**
   * Capabilities are known to be inaccurately reported for vertical resolutions on some devices.
   * [Internal ref: b/31387661]. When this workaround is enabled, we also check whether the
   * capabilities indicate support if the width and height are swapped. If they do, we assume that
   * the vertical resolution is also supported.
   *
   * @param name The name of the codec.
   * @return Whether to enable the workaround.
   */
  private static boolean needsRotatedVerticalResolutionWorkaround(String name) {
    if ("OMX.MTK.VIDEO.DECODER.HEVC".equals(name) && "mcv5a".equals(Build.DEVICE)) {
      // See https://github.com/google/ExoPlayer/issues/6612.
      return false;
    }
    return true;
  }

  /**
   * Returns whether a profile is excluded from the list of supported profiles. This may happen when
   * a device declares support for a profile it doesn't actually support.
   */
  private static boolean needsProfileExcludedWorkaround(String mimeType, int profile) {
    // See https://github.com/google/ExoPlayer/issues/3537
    return MimeTypes.VIDEO_H265.equals(mimeType)
        && CodecProfileLevel.HEVCProfileMain10 == profile
        && ("sailfish".equals(Build.DEVICE) || "marlin".equals(Build.DEVICE));
  }

  /** Returns whether the device is known to have issues with the detached surface mode. */
  private static boolean needsDetachedSurfaceUnsupportedWorkaround() {
    return Build.MANUFACTURER.equals("Xiaomi")
        || Build.MANUFACTURER.equals("OPPO")
        || Build.MANUFACTURER.equals("realme")
        || Build.MANUFACTURER.equals("motorola")
        || Build.MANUFACTURER.equals("LENOVO");
  }
}
