/*
 * Copyright 2023 The Android Open Source Project
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

import static androidx.media3.common.util.Assertions.checkArgument;
import static java.lang.annotation.ElementType.TYPE_USE;
import static java.lang.annotation.RetentionPolicy.SOURCE;

import androidx.annotation.IntDef;
import androidx.media3.common.MediaItem;
import androidx.media3.common.VideoCompositorSettings;
import androidx.media3.common.util.UnstableApi;
import com.google.common.collect.ImmutableList;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.util.List;

/**
 * A composition of {@link MediaItem} instances, with transformations to apply to them.
 *
 * <p>The {@link MediaItem} instances can be concatenated or mixed. {@link Effects} can be applied
 * to individual {@link MediaItem} instances, as well as to the composition.
 */
@UnstableApi
public final class Composition {

  /** A builder for {@link Composition} instances. */
  public static final class Builder {

    private ImmutableList<EditedMediaItemSequence> sequences;
    private VideoCompositorSettings videoCompositorSettings;
    private Effects effects;
    private boolean forceAudioTrack;
    private boolean transmuxAudio;
    private boolean transmuxVideo;
    private @HdrMode int hdrMode;
    private boolean retainHdrFromUltraHdrImage;

    /**
     * Creates an instance.
     *
     * @see Builder#Builder(List)
     */
    public Builder(EditedMediaItemSequence sequence, EditedMediaItemSequence... sequences) {
      this(
          new ImmutableList.Builder<EditedMediaItemSequence>()
              .add(sequence)
              .add(sequences)
              .build());
    }

    /**
     * Creates an instance.
     *
     * @param sequences The {@link EditedMediaItemSequence} instances to compose. The list must be
     *     non empty. See {@link Composition#sequences} for more details.
     */
    public Builder(List<EditedMediaItemSequence> sequences) {
      checkArgument(
          !sequences.isEmpty(),
          "The composition must contain at least one EditedMediaItemSequence.");
      this.sequences = ImmutableList.copyOf(sequences);
      videoCompositorSettings = VideoCompositorSettings.DEFAULT;
      effects = Effects.EMPTY;
    }

    /** Creates a new instance to build upon the provided {@link Composition}. */
    private Builder(Composition composition) {
      sequences = composition.sequences;
      videoCompositorSettings = composition.videoCompositorSettings;
      effects = composition.effects;
      forceAudioTrack = composition.forceAudioTrack;
      transmuxAudio = composition.transmuxAudio;
      transmuxVideo = composition.transmuxVideo;
      hdrMode = composition.hdrMode;
      retainHdrFromUltraHdrImage = composition.retainHdrFromUltraHdrImage;
    }

    /**
     * Sets the {@link VideoCompositorSettings} to apply to the {@link Composition}.
     *
     * <p>The default value is {@link VideoCompositorSettings#DEFAULT}.
     *
     * @param videoCompositorSettings The {@link VideoCompositorSettings}.
     * @return This builder.
     */
    @CanIgnoreReturnValue
    public Builder setVideoCompositorSettings(VideoCompositorSettings videoCompositorSettings) {
      this.videoCompositorSettings = videoCompositorSettings;
      return this;
    }

    /**
     * Sets the {@link Effects} to apply to the {@link Composition}.
     *
     * <p>The default value is {@link Effects#EMPTY}.
     *
     * @param effects The {@link Composition} {@link Effects}.
     * @return This builder.
     */
    @CanIgnoreReturnValue
    public Builder setEffects(Effects effects) {
      this.effects = effects;
      return this;
    }

    /**
     * @deprecated Use {@link
     *     EditedMediaItemSequence.Builder#experimentalSetForceAudioTrack(boolean)} instead.
     */
    @Deprecated
    @CanIgnoreReturnValue
    public Builder experimentalSetForceAudioTrack(boolean forceAudioTrack) {
      this.forceAudioTrack = forceAudioTrack;
      return this;
    }

    /**
     * Sets whether to transmux the {@linkplain MediaItem media items'} audio tracks.
     *
     * <p>The default value is {@code false}.
     *
     * <p>If the {@link Composition} contains one {@link MediaItem}, the value set is ignored. The
     * audio track will only be transcoded if necessary.
     *
     * <p>If the input {@link Composition} contains multiple {@linkplain MediaItem media items}, all
     * the audio tracks are transcoded by default. They are all transmuxed if {@code transmuxAudio}
     * is {@code true}. Transmuxed tracks must be compatible (typically, all the {@link MediaItem}
     * instances containing the track to transmux are concatenated in a single {@link
     * EditedMediaItemSequence} and have the same sample format for that track). Any transcoding
     * effects requested will be ignored.
     *
     * <p>Requesting audio transmuxing and {@linkplain #experimentalSetForceAudioTrack(boolean)
     * forcing an audio track} are not allowed together because generating silence requires
     * transcoding.
     *
     * @param transmuxAudio Whether to transmux the audio tracks.
     * @return This builder.
     */
    @CanIgnoreReturnValue
    public Builder setTransmuxAudio(boolean transmuxAudio) {
      this.transmuxAudio = transmuxAudio;
      return this;
    }

    /**
     * Sets whether to transmux the {@linkplain MediaItem media items'} video tracks.
     *
     * <p>The default value is {@code false}.
     *
     * <p>If the {@link Composition} contains one {@link MediaItem}, the value set is ignored. The
     * video track will only be transcoded if necessary.
     *
     * <p>If the input {@link Composition} contains multiple {@linkplain MediaItem media items}, all
     * the video tracks are transcoded by default. They are all transmuxed if {@code transmuxVideo}
     * is {@code true}. Transmuxed tracks must be compatible (typically, all the {@link MediaItem}
     * instances containing the track to transmux are concatenated in a single {@link
     * EditedMediaItemSequence} and have the same sample format for that track). Any transcoding
     * effects requested will be ignored.
     *
     * @param transmuxVideo Whether to transmux the video tracks.
     * @return This builder.
     */
    @CanIgnoreReturnValue
    public Builder setTransmuxVideo(boolean transmuxVideo) {
      this.transmuxVideo = transmuxVideo;
      return this;
    }

    /**
     * Sets the {@link HdrMode} for HDR video input.
     *
     * <p>The default value is {@link #HDR_MODE_KEEP_HDR}. Apps that need to tone-map HDR to SDR
     * should generally prefer {@link #HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL} over {@link
     * #HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_MEDIACODEC}, because its behavior is likely to be more
     * consistent across devices.
     *
     * @param hdrMode The {@link HdrMode} used.
     * @return This builder.
     */
    @CanIgnoreReturnValue
    public Builder setHdrMode(@HdrMode int hdrMode) {
      this.hdrMode = hdrMode;
      return this;
    }

    /**
     * Sets whether to use produce an HDR output video from Ultra HDR image input.
     *
     * <p>If the {@link HdrMode} is {@link #HDR_MODE_KEEP_HDR}, then setting this to {@code true}
     * applies the recovery map (i.e. the gainmap) to the base image to produce HDR video frames.
     * This is automatically overridden to true, if the first asset is a HDR video.
     *
     * <p>The output video will have the same color encoding as the first {@link EditedMediaItem}
     * the sequence. If the Ultra HDR image is first in the sequence, output video will default to
     * BT2020 HLG full range colors.
     *
     * <p>Ignored if {@link HdrMode} is not {@link #HDR_MODE_KEEP_HDR}.
     *
     * <p>Supported on API 34+, by some device and HDR format combinations. Ignored if unsupported
     * by device or API level.
     *
     * <p>The default value is {@code false}.
     *
     * @param retainHdrFromUltraHdrImage Whether to use produce an HDR output video from Ultra HDR
     *     image input.
     * @return This builder.
     */
    @CanIgnoreReturnValue
    public Builder experimentalSetRetainHdrFromUltraHdrImage(boolean retainHdrFromUltraHdrImage) {
      this.retainHdrFromUltraHdrImage = retainHdrFromUltraHdrImage;
      return this;
    }

    /** Builds a {@link Composition} instance. */
    public Composition build() {
      ImmutableList<EditedMediaItemSequence> updatedSequences;
      if (forceAudioTrack) {
        ImmutableList.Builder<EditedMediaItemSequence> updatedSequencesBuilder =
            new ImmutableList.Builder<>();
        for (int i = 0; i < sequences.size(); i++) {
          updatedSequencesBuilder.add(
              sequences.get(i).buildUpon().experimentalSetForceAudioTrack(forceAudioTrack).build());
        }
        updatedSequences = updatedSequencesBuilder.build();
      } else {
        updatedSequences = sequences;
      }
      return new Composition(
          updatedSequences,
          videoCompositorSettings,
          effects,
          forceAudioTrack,
          transmuxAudio,
          transmuxVideo,
          hdrMode,
          retainHdrFromUltraHdrImage && hdrMode == HDR_MODE_KEEP_HDR);
    }

    /**
     * Sets {@link Composition#sequences}.
     *
     * @param sequences The {@link EditedMediaItemSequence} instances to compose. The list must not
     *     be empty.
     * @return This builder.
     */
    @CanIgnoreReturnValue
    /* package */ Builder setSequences(List<EditedMediaItemSequence> sequences) {
      checkArgument(
          !sequences.isEmpty(),
          "The composition must contain at least one EditedMediaItemSequence.");
      this.sequences = ImmutableList.copyOf(sequences);
      return this;
    }
  }

  /**
   * The strategy to use to transcode or edit High Dynamic Range (HDR) input video.
   *
   * <p>One of {@link #HDR_MODE_KEEP_HDR}, {@link #HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_MEDIACODEC},
   * {@link #HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL}, or {@link
   * #HDR_MODE_EXPERIMENTAL_FORCE_INTERPRET_HDR_AS_SDR}.
   *
   * <p>Standard Dynamic Range (SDR) input video is unaffected by these settings.
   */
  @Documented
  @Retention(SOURCE)
  @Target(TYPE_USE)
  @IntDef({
    HDR_MODE_KEEP_HDR,
    HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_MEDIACODEC,
    HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL,
    HDR_MODE_EXPERIMENTAL_FORCE_INTERPRET_HDR_AS_SDR,
  })
  public @interface HdrMode {}

  /**
   * Processes HDR input as HDR, to generate HDR output.
   *
   * <p>The HDR output format (ex. color transfer) will be the same as the HDR input format.
   *
   * <p>Supported on API 31+, by some device and HDR format combinations.
   *
   * <p>If not supported, {@link Transformer} will attempt to use {@link
   * #HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL}.
   */
  public static final int HDR_MODE_KEEP_HDR = 0;

  /**
   * Tone map HDR input to SDR before processing, to generate SDR output, using the {@link
   * android.media.MediaCodec} decoder tone-mapper.
   *
   * <p>Supported on API 31+, by some device and HDR format combinations. Tone-mapping is only
   * guaranteed to be supported on API 33+, on devices with HDR capture support.
   *
   * <p>If not supported, {@link Transformer} throws an {@link ExportException}.
   */
  public static final int HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_MEDIACODEC = 1;

  /**
   * Tone map HDR input to SDR before processing, to generate SDR output, using an OpenGL
   * tone-mapper.
   *
   * <p>Supported on API 29+.
   *
   * <p>This may exhibit mild differences from {@link
   * #HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_MEDIACODEC}, depending on the device's tone-mapping
   * implementation, but should have much wider support and have more consistent results across
   * devices.
   *
   * <p>If not supported, {@link Transformer} throws an {@link ExportException}.
   */
  public static final int HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL = 2;

  /**
   * Interpret HDR input as SDR, likely with a washed out look.
   *
   * <p>This is much more widely supported than {@link #HDR_MODE_KEEP_HDR}, {@link
   * #HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_MEDIACODEC}, and {@link
   * #HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL}. However, as HDR transfer functions and metadata
   * will be ignored, contents will be displayed incorrectly, likely with a washed out look.
   *
   * <p>Using this API may lead to codec errors before API 29.
   *
   * <p>Use of this flag may result in {@code ERROR_CODE_DECODING_FORMAT_UNSUPPORTED}.
   *
   * <p>This field is experimental, and will be renamed or removed in a future release.
   */
  public static final int HDR_MODE_EXPERIMENTAL_FORCE_INTERPRET_HDR_AS_SDR = 3;

  /**
   * The {@link EditedMediaItemSequence} instances to compose.
   *
   * <p>{@link MediaItem} instances from different sequences that are overlapping in time will be
   * mixed in the output.
   */
  public final ImmutableList<EditedMediaItemSequence> sequences;

  /** The {@link VideoCompositorSettings} to apply to the composition. */
  public final VideoCompositorSettings videoCompositorSettings;

  // TODO: b/302695659 - Ensure composition level effects are only applied consistently between the
  //  different VideoGraphs.
  /** The {@link Effects} to apply to the composition. */
  public final Effects effects;

  /**
   * @deprecated Use {@link EditedMediaItemSequence.Builder#experimentalSetForceAudioTrack(boolean)}
   *     to set the flag and {@link EditedMediaItemSequence#forceAudioTrack} to read the flag.
   */
  @Deprecated public final boolean forceAudioTrack;

  /**
   * Whether to transmux the {@linkplain MediaItem media items'} audio tracks.
   *
   * <p>For more information, see {@link Builder#setTransmuxAudio(boolean)}.
   */
  public final boolean transmuxAudio;

  /**
   * Whether to transmux the {@linkplain MediaItem media items'} video tracks.
   *
   * <p>For more information, see {@link Builder#setTransmuxVideo(boolean)}.
   */
  public final boolean transmuxVideo;

  /**
   * The {@link HdrMode} specifying how to handle HDR input video.
   *
   * <p>For more information, see {@link Builder#setHdrMode(int)}.
   */
  public final @HdrMode int hdrMode;

  /**
   * Sets whether to use produce an HDR output video from Ultra HDR image input.
   *
   * <p>For more information, see {@link
   * Builder#experimentalSetRetainHdrFromUltraHdrImage(boolean)}.
   */
  public final boolean retainHdrFromUltraHdrImage;

  /** Returns a {@link Composition.Builder} initialized with the values of this instance. */
  /* package */ Builder buildUpon() {
    return new Builder(this);
  }

  private Composition(
      List<EditedMediaItemSequence> sequences,
      VideoCompositorSettings videoCompositorSettings,
      Effects effects,
      boolean forceAudioTrack,
      boolean transmuxAudio,
      boolean transmuxVideo,
      @HdrMode int hdrMode,
      boolean retainHdrFromUltraHdrImage) {
    checkArgument(
        !transmuxAudio || !forceAudioTrack,
        "Audio transmuxing and audio track forcing are not allowed together.");
    checkArgument(
        hasNonLoopingSequence(sequences),
        "Composition must have at least one non-looping sequence.");
    this.sequences = ImmutableList.copyOf(sequences);
    this.videoCompositorSettings = videoCompositorSettings;
    this.effects = effects;
    this.transmuxAudio = transmuxAudio;
    this.transmuxVideo = transmuxVideo;
    this.forceAudioTrack = forceAudioTrack;
    this.hdrMode = hdrMode;
    this.retainHdrFromUltraHdrImage = retainHdrFromUltraHdrImage;
  }

  /**
   * Return whether any {@linkplain EditedMediaItemSequence sequences} contain a {@linkplain
   * EditedMediaItemSequence.Builder#addGap(long) gap}.
   */
  /* package */ boolean hasGaps() {
    for (int i = 0; i < sequences.size(); i++) {
      if (sequences.get(i).hasGaps()) {
        return true;
      }
    }
    return false;
  }

  private static boolean hasNonLoopingSequence(List<EditedMediaItemSequence> sequences) {
    for (EditedMediaItemSequence sequence : sequences) {
      if (!sequence.isLooping) {
        return true;
      }
    }
    return false;
  }
}
