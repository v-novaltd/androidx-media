/*
 * Copyright (C) 2018 The Android Open Source Project
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
package androidx.media3.exoplayer.trackselection;

import static androidx.media3.common.C.FORMAT_EXCEEDS_CAPABILITIES;
import static androidx.media3.common.C.FORMAT_HANDLED;
import static androidx.media3.common.C.FORMAT_UNSUPPORTED_SUBTYPE;
import static androidx.media3.common.C.FORMAT_UNSUPPORTED_TYPE;
import static androidx.media3.common.TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED;
import static androidx.media3.common.TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_REQUIRED;
import static androidx.media3.exoplayer.RendererCapabilities.ADAPTIVE_NOT_SEAMLESS;
import static androidx.media3.exoplayer.RendererCapabilities.ADAPTIVE_NOT_SUPPORTED;
import static androidx.media3.exoplayer.RendererCapabilities.ADAPTIVE_SEAMLESS;
import static androidx.media3.exoplayer.RendererCapabilities.AUDIO_OFFLOAD_GAPLESS_SUPPORTED;
import static androidx.media3.exoplayer.RendererCapabilities.AUDIO_OFFLOAD_SUPPORTED;
import static androidx.media3.exoplayer.RendererCapabilities.DECODER_SUPPORT_FALLBACK;
import static androidx.media3.exoplayer.RendererCapabilities.DECODER_SUPPORT_FALLBACK_MIMETYPE;
import static androidx.media3.exoplayer.RendererCapabilities.DECODER_SUPPORT_PRIMARY;
import static androidx.media3.exoplayer.RendererCapabilities.HARDWARE_ACCELERATION_NOT_SUPPORTED;
import static androidx.media3.exoplayer.RendererCapabilities.HARDWARE_ACCELERATION_SUPPORTED;
import static androidx.media3.exoplayer.RendererCapabilities.TUNNELING_NOT_SUPPORTED;
import static androidx.media3.exoplayer.RendererConfiguration.DEFAULT;
import static androidx.media3.exoplayer.audio.AudioSink.OFFLOAD_MODE_DISABLED;
import static androidx.media3.exoplayer.audio.AudioSink.OFFLOAD_MODE_ENABLED_GAPLESS_NOT_REQUIRED;
import static androidx.media3.exoplayer.audio.AudioSink.OFFLOAD_MODE_ENABLED_GAPLESS_REQUIRED;
import static com.google.common.truth.Truth.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.media.Spatializer;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.Timeline;
import androidx.media3.common.TrackGroup;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.TrackSelectionParameters.AudioOffloadPreferences;
import androidx.media3.common.Tracks;
import androidx.media3.common.util.HandlerWrapper;
import androidx.media3.common.util.Util;
import androidx.media3.exoplayer.ExoPlaybackException;
import androidx.media3.exoplayer.Renderer;
import androidx.media3.exoplayer.RendererCapabilities;
import androidx.media3.exoplayer.RendererCapabilities.Capabilities;
import androidx.media3.exoplayer.RendererConfiguration;
import androidx.media3.exoplayer.audio.AudioRendererEventListener;
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId;
import androidx.media3.exoplayer.source.TrackGroupArray;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector.Parameters;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector.SelectionOverride;
import androidx.media3.exoplayer.trackselection.TrackSelector.InvalidationListener;
import androidx.media3.exoplayer.upstream.BandwidthMeter;
import androidx.media3.test.utils.FakeAudioRenderer;
import androidx.media3.test.utils.FakeTimeline;
import androidx.media3.test.utils.TestUtil;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.robolectric.shadows.ShadowDisplay;
import org.robolectric.shadows.ShadowDisplayManager;

/** Unit tests for {@link DefaultTrackSelector}. */
@RunWith(AndroidJUnit4.class)
public final class DefaultTrackSelectorTest {

  @Rule public final MockitoRule mockito = MockitoJUnit.rule();

  private static final RendererCapabilities ALL_AUDIO_FORMAT_SUPPORTED_RENDERER_CAPABILITIES =
      new FakeRendererCapabilities(C.TRACK_TYPE_AUDIO);
  private static final RendererCapabilities ALL_TEXT_FORMAT_SUPPORTED_RENDERER_CAPABILITIES =
      new FakeRendererCapabilities(C.TRACK_TYPE_TEXT);
  private static final RendererCapabilities ALL_AUDIO_FORMAT_EXCEEDED_RENDERER_CAPABILITIES =
      new FakeRendererCapabilities(
          C.TRACK_TYPE_AUDIO, RendererCapabilities.create(FORMAT_EXCEEDS_CAPABILITIES));
  private static final RendererCapabilities ALL_VIDEO_FORMAT_EXCEEDED_RENDERER_CAPABILITIES =
      new FakeRendererCapabilities(
          C.TRACK_TYPE_VIDEO, RendererCapabilities.create(FORMAT_EXCEEDS_CAPABILITIES));

  private static final RendererCapabilities VIDEO_CAPABILITIES =
      new FakeRendererCapabilities(C.TRACK_TYPE_VIDEO);
  private static final RendererCapabilities AUDIO_CAPABILITIES =
      new FakeRendererCapabilities(C.TRACK_TYPE_AUDIO);
  private static final RendererCapabilities IMAGE_CAPABILITIES =
      new FakeRendererCapabilities(C.TRACK_TYPE_IMAGE);
  private static final RendererCapabilities NO_SAMPLE_CAPABILITIES =
      new FakeRendererCapabilities(C.TRACK_TYPE_NONE);
  private static final RendererCapabilities[] RENDERER_CAPABILITIES =
      new RendererCapabilities[] {VIDEO_CAPABILITIES, AUDIO_CAPABILITIES};
  private static final RendererCapabilities[] RENDERER_CAPABILITIES_WITH_NO_SAMPLE_RENDERER =
      new RendererCapabilities[] {VIDEO_CAPABILITIES, NO_SAMPLE_CAPABILITIES};

  private static final Format VIDEO_FORMAT =
      new Format.Builder()
          .setSampleMimeType(MimeTypes.VIDEO_H264)
          .setWidth(1024)
          .setHeight(768)
          .setAverageBitrate(450000)
          .build();
  private static final Format AUDIO_FORMAT =
      new Format.Builder()
          .setSampleMimeType(MimeTypes.AUDIO_AAC)
          .setChannelCount(2)
          .setSampleRate(44100)
          .setAverageBitrate(128000)
          .build();
  private static final Format TEXT_FORMAT =
      new Format.Builder().setSampleMimeType(MimeTypes.TEXT_VTT).build();
  private static final Format IMAGE_FORMAT =
      new Format.Builder().setSampleMimeType(MimeTypes.IMAGE_PNG).build();

  private static final TrackGroup VIDEO_TRACK_GROUP = new TrackGroup(VIDEO_FORMAT);
  private static final TrackGroup AUDIO_TRACK_GROUP = new TrackGroup(AUDIO_FORMAT);
  private static final TrackGroupArray TRACK_GROUPS =
      new TrackGroupArray(VIDEO_TRACK_GROUP, AUDIO_TRACK_GROUP);
  private static final TrackSelection VIDEO_TRACK_SELECTION =
      new FixedTrackSelection(VIDEO_TRACK_GROUP, 0);
  private static final TrackSelection AUDIO_TRACK_SELECTION =
      new FixedTrackSelection(AUDIO_TRACK_GROUP, 0);
  private static final TrackSelection[] TRACK_SELECTIONS =
      new TrackSelection[] {VIDEO_TRACK_SELECTION, AUDIO_TRACK_SELECTION};
  private static final TrackSelection[] TRACK_SELECTIONS_WITH_NO_SAMPLE_RENDERER =
      new TrackSelection[] {VIDEO_TRACK_SELECTION, null};

  private static final Timeline TIMELINE = new FakeTimeline();

  private static MediaPeriodId periodId;

  @Mock private InvalidationListener invalidationListener;
  @Mock private BandwidthMeter bandwidthMeter;

  private Parameters defaultParameters;
  private DefaultTrackSelector trackSelector;

  @BeforeClass
  public static void setUpBeforeClass() {
    periodId = new MediaPeriodId(TIMELINE.getUidOfPeriod(/* periodIndex= */ 0));
  }

  @Before
  public void setUp() {
    when(bandwidthMeter.getBitrateEstimate()).thenReturn(1000000L);
    Context context = ApplicationProvider.getApplicationContext();
    defaultParameters = Parameters.DEFAULT;
    trackSelector = new DefaultTrackSelector(context);
    trackSelector.init(invalidationListener, bandwidthMeter);
  }

  @After
  public void tearDown() {
    trackSelector.release();
  }

  @Test
  public void parameters_buildUponThenBuild_isEqual() {
    Parameters parameters = buildParametersForEqualsTest();
    assertThat(parameters.buildUpon().build()).isEqualTo(parameters);
  }

  /** Tests {@link Parameters} bundle implementation. */
  @Test
  public void roundTripViaBundle_ofParameters_yieldsEqualInstance() {
    Parameters parametersToBundle = buildParametersForEqualsTest();

    Parameters parametersFromBundle = Parameters.fromBundle(parametersToBundle.toBundle());

    assertThat(parametersFromBundle).isEqualTo(parametersToBundle);
  }

  /** Tests that an empty override clears a track selection. */
  @Test
  public void selectTracks_withOverrideWithoutTracks_clearsTrackSelection()
      throws ExoPlaybackException {
    trackSelector.setParameters(
        trackSelector
            .buildUponParameters()
            .addOverride(new TrackSelectionOverride(VIDEO_TRACK_GROUP, ImmutableList.of()))
            .build());

    TrackSelectorResult result =
        trackSelector.selectTracks(RENDERER_CAPABILITIES, TRACK_GROUPS, periodId, TIMELINE);

    assertSelections(result, new TrackSelection[] {null, TRACK_SELECTIONS[1]});
    assertThat(result.rendererConfigurations)
        .isEqualTo(new RendererConfiguration[] {null, DEFAULT});
  }

  /** Tests that a null override clears a track selection. */
  @Test
  public void selectTracksWithNullOverride() throws ExoPlaybackException {
    trackSelector.setParameters(
        trackSelector
            .buildUponParameters()
            .setSelectionOverride(0, new TrackGroupArray(VIDEO_TRACK_GROUP), null));
    TrackSelectorResult result =
        trackSelector.selectTracks(RENDERER_CAPABILITIES, TRACK_GROUPS, periodId, TIMELINE);
    assertSelections(result, new TrackSelection[] {null, TRACK_SELECTIONS[1]});
    assertThat(result.rendererConfigurations)
        .isEqualTo(new RendererConfiguration[] {null, DEFAULT});
  }

  /** Tests that a null override can be cleared. */
  @Test
  public void selectTracksWithClearedNullOverride() throws ExoPlaybackException {
    trackSelector.setParameters(
        trackSelector
            .buildUponParameters()
            .setSelectionOverride(0, new TrackGroupArray(VIDEO_TRACK_GROUP), null)
            .clearSelectionOverride(0, new TrackGroupArray(VIDEO_TRACK_GROUP)));
    TrackSelectorResult result =
        trackSelector.selectTracks(RENDERER_CAPABILITIES, TRACK_GROUPS, periodId, TIMELINE);
    assertSelections(result, TRACK_SELECTIONS);
    assertThat(result.rendererConfigurations)
        .isEqualTo(new RendererConfiguration[] {DEFAULT, DEFAULT});
  }

  @Test
  public void selectTrack_withMixedEmptyAndNonEmptyTrackOverrides_appliesNonEmptyOverride()
      throws Exception {
    TrackGroup videoGroupHighBitrate =
        new TrackGroup(VIDEO_FORMAT.buildUpon().setAverageBitrate(1_000_000).build());
    TrackGroup videoGroupMidBitrate =
        new TrackGroup(VIDEO_FORMAT.buildUpon().setAverageBitrate(500_000).build());
    TrackGroup videoGroupLowBitrate =
        new TrackGroup(VIDEO_FORMAT.buildUpon().setAverageBitrate(100_000).build());
    trackSelector.setParameters(
        trackSelector
            .buildUponParameters()
            .addOverride(
                new TrackSelectionOverride(
                    videoGroupHighBitrate, /* trackIndices= */ ImmutableList.of()))
            .addOverride(
                new TrackSelectionOverride(
                    videoGroupMidBitrate, /* trackIndices= */ ImmutableList.of(0)))
            .addOverride(
                new TrackSelectionOverride(
                    videoGroupLowBitrate, /* trackIndices= */ ImmutableList.of()))
            .build());

    TrackSelectorResult result =
        trackSelector.selectTracks(
            RENDERER_CAPABILITIES,
            new TrackGroupArray(videoGroupHighBitrate, videoGroupMidBitrate, videoGroupLowBitrate),
            periodId,
            TIMELINE);

    assertThat(result.selections)
        .asList()
        .containsExactly(new FixedTrackSelection(videoGroupMidBitrate, /* track= */ 0), null)
        .inOrder();
  }

  /** Tests that an empty override is not applied for a different set of available track groups. */
  @Test
  public void selectTracks_withEmptyTrackOverrideForDifferentTracks_hasNoEffect()
      throws ExoPlaybackException {
    TrackGroup videoGroup0 = VIDEO_TRACK_GROUP.copyWithId("0");
    TrackGroup videoGroup1 = VIDEO_TRACK_GROUP.copyWithId("1");
    trackSelector.setParameters(
        trackSelector
            .buildUponParameters()
            .setOverrideForType(
                new TrackSelectionOverride(
                    new TrackGroup(VIDEO_FORMAT, VIDEO_FORMAT), ImmutableList.of()))
            .build());

    TrackSelectorResult result =
        trackSelector.selectTracks(
            RENDERER_CAPABILITIES,
            new TrackGroupArray(videoGroup0, AUDIO_TRACK_GROUP, videoGroup1),
            periodId,
            TIMELINE);

    assertThat(result.selections)
        .asList()
        .containsExactly(
            new FixedTrackSelection(videoGroup0, /* track= */ 0),
            new FixedTrackSelection(AUDIO_TRACK_GROUP, /* track= */ 0))
        .inOrder();
    assertThat(result.rendererConfigurations)
        .isEqualTo(new RendererConfiguration[] {DEFAULT, DEFAULT});
  }

  @Test
  public void selectTrack_withOverrideForDifferentRenderer_clearsDefaultSelectionOfSameType()
      throws Exception {
    Format videoFormatH264 =
        VIDEO_FORMAT.buildUpon().setId("H264").setSampleMimeType(MimeTypes.VIDEO_H264).build();
    Format videoFormatAv1 =
        VIDEO_FORMAT.buildUpon().setId("AV1").setSampleMimeType(MimeTypes.VIDEO_AV1).build();
    TrackGroup videoGroupH264 = new TrackGroup(videoFormatH264);
    TrackGroup videoGroupAv1 = new TrackGroup(videoFormatAv1);
    Map<String, Integer> rendererCapabilitiesMap =
        ImmutableMap.of(
            videoFormatH264.id, FORMAT_HANDLED, videoFormatAv1.id, FORMAT_UNSUPPORTED_TYPE);
    RendererCapabilities rendererCapabilitiesH264 =
        new FakeMappedRendererCapabilities(C.TRACK_TYPE_VIDEO, rendererCapabilitiesMap);
    rendererCapabilitiesMap =
        ImmutableMap.of(
            videoFormatH264.id, FORMAT_UNSUPPORTED_TYPE, videoFormatAv1.id, FORMAT_HANDLED);
    RendererCapabilities rendererCapabilitiesAv1 =
        new FakeMappedRendererCapabilities(C.TRACK_TYPE_VIDEO, rendererCapabilitiesMap);

    // Try to force selection of one TrackGroup in both directions to ensure the default gets
    // overridden without having to know what the default is.
    trackSelector.setParameters(
        trackSelector
            .buildUponParameters()
            .setOverrideForType(new TrackSelectionOverride(videoGroupH264, /* trackIndex= */ 0))
            .build());
    TrackSelectorResult result =
        trackSelector.selectTracks(
            new RendererCapabilities[] {rendererCapabilitiesH264, rendererCapabilitiesAv1},
            new TrackGroupArray(videoGroupH264, videoGroupAv1),
            periodId,
            TIMELINE);

    assertThat(result.selections)
        .asList()
        .containsExactly(new FixedTrackSelection(videoGroupH264, /* track= */ 0), null)
        .inOrder();

    trackSelector.setParameters(
        trackSelector
            .buildUponParameters()
            .setOverrideForType(new TrackSelectionOverride(videoGroupAv1, /* trackIndex= */ 0))
            .build());
    result =
        trackSelector.selectTracks(
            new RendererCapabilities[] {rendererCapabilitiesH264, rendererCapabilitiesAv1},
            new TrackGroupArray(videoGroupH264, videoGroupAv1),
            periodId,
            TIMELINE);

    assertThat(result.selections)
        .asList()
        .containsExactly(null, new FixedTrackSelection(videoGroupAv1, /* track= */ 0))
        .inOrder();
  }

  @Test
  public void selectTracks_withOverrideForUnmappedGroup_disablesAllRenderersOfSameType()
      throws Exception {
    Format audioSupported = AUDIO_FORMAT.buildUpon().setId("supported").build();
    Format audioUnsupported = AUDIO_FORMAT.buildUpon().setId("unsupported").build();
    TrackGroup audioGroupSupported = new TrackGroup(audioSupported);
    TrackGroup audioGroupUnsupported = new TrackGroup(audioUnsupported);
    Map<String, Integer> audioRendererCapabilitiesMap =
        ImmutableMap.of(
            audioSupported.id, FORMAT_HANDLED, audioUnsupported.id, FORMAT_UNSUPPORTED_TYPE);
    RendererCapabilities audioRendererCapabilties =
        new FakeMappedRendererCapabilities(C.TRACK_TYPE_AUDIO, audioRendererCapabilitiesMap);

    trackSelector.setParameters(
        trackSelector
            .buildUponParameters()
            .setOverrideForType(
                new TrackSelectionOverride(audioGroupUnsupported, /* trackIndex= */ 0))
            .build());
    TrackSelectorResult result =
        trackSelector.selectTracks(
            new RendererCapabilities[] {VIDEO_CAPABILITIES, audioRendererCapabilties},
            new TrackGroupArray(VIDEO_TRACK_GROUP, audioGroupSupported, audioGroupUnsupported),
            periodId,
            TIMELINE);

    assertThat(result.selections).asList().containsExactly(VIDEO_TRACK_SELECTION, null).inOrder();
  }

  /** Tests that an override is not applied for a different set of available track groups. */
  @Test
  public void selectTracksWithNullOverrideForDifferentTracks() throws ExoPlaybackException {
    TrackGroup videoGroup0 = VIDEO_TRACK_GROUP.copyWithId("0");
    TrackGroup videoGroup1 = VIDEO_TRACK_GROUP.copyWithId("1");
    trackSelector.setParameters(
        trackSelector
            .buildUponParameters()
            .setSelectionOverride(0, new TrackGroupArray(VIDEO_TRACK_GROUP.copyWithId("2")), null));
    TrackSelectorResult result =
        trackSelector.selectTracks(
            RENDERER_CAPABILITIES,
            new TrackGroupArray(videoGroup0, AUDIO_TRACK_GROUP, videoGroup1),
            periodId,
            TIMELINE);
    assertThat(result.selections)
        .asList()
        .containsExactly(
            new FixedTrackSelection(videoGroup0, /* track= */ 0),
            new FixedTrackSelection(AUDIO_TRACK_GROUP, /* track= */ 0))
        .inOrder();
    assertThat(result.rendererConfigurations)
        .isEqualTo(new RendererConfiguration[] {DEFAULT, DEFAULT});
  }

  /** Tests disabling a track type. */
  @Test
  public void selectVideoAudioTracks_withDisabledAudioType_onlyVideoIsSelected()
      throws ExoPlaybackException {
    trackSelector.setParameters(
        defaultParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, /* disabled= */ true));

    TrackSelectorResult result =
        trackSelector.selectTracks(
            RENDERER_CAPABILITIES,
            new TrackGroupArray(VIDEO_TRACK_GROUP, AUDIO_TRACK_GROUP),
            periodId,
            TIMELINE);

    assertThat(result.selections).asList().containsExactly(VIDEO_TRACK_SELECTION, null).inOrder();
    assertThat(result.rendererConfigurations).asList().containsExactly(DEFAULT, null);
  }

  /** Tests that a disabled track type can be enabled again. */
  @Test
  @SuppressWarnings("deprecation")
  public void selectTracks_withClearedDisabledTrackType_selectsAll() throws ExoPlaybackException {
    trackSelector.setParameters(
        trackSelector
            .buildUponParameters()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, /* disabled= */ true)
            .setDisabledTrackTypes(ImmutableSet.of()));

    TrackSelectorResult result =
        trackSelector.selectTracks(RENDERER_CAPABILITIES, TRACK_GROUPS, periodId, TIMELINE);

    assertThat(result.selections).asList().containsExactlyElementsIn(TRACK_SELECTIONS).inOrder();
    assertThat(result.rendererConfigurations).asList().containsExactly(DEFAULT, DEFAULT).inOrder();
  }

  /** Tests disabling NONE track type rendering. */
  @Test
  public void selectTracks_withDisabledNoneTracksAndNoSampleRenderer_disablesNoSampleRenderer()
      throws ExoPlaybackException {
    trackSelector.setParameters(
        defaultParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_NONE, /* disabled= */ true));

    TrackSelectorResult result =
        trackSelector.selectTracks(
            new RendererCapabilities[] {VIDEO_CAPABILITIES, NO_SAMPLE_CAPABILITIES},
            TRACK_GROUPS,
            periodId,
            TIMELINE);

    assertThat(result.selections).asList().containsExactly(VIDEO_TRACK_SELECTION, null).inOrder();
    assertThat(result.rendererConfigurations).asList().containsExactly(DEFAULT, null).inOrder();
  }

  /** Tests disabling a renderer. */
  @Test
  public void selectTracksWithDisabledRenderer() throws ExoPlaybackException {
    trackSelector.setParameters(defaultParameters.buildUpon().setRendererDisabled(1, true));
    TrackSelectorResult result =
        trackSelector.selectTracks(RENDERER_CAPABILITIES, TRACK_GROUPS, periodId, TIMELINE);
    assertSelections(result, new TrackSelection[] {TRACK_SELECTIONS[0], null});
    assertThat(new RendererConfiguration[] {DEFAULT, null})
        .isEqualTo(result.rendererConfigurations);
  }

  /** Tests that a disabled renderer can be enabled again. */
  @Test
  public void selectTracksWithClearedDisabledRenderer() throws ExoPlaybackException {
    trackSelector.setParameters(
        trackSelector
            .buildUponParameters()
            .setRendererDisabled(1, true)
            .setRendererDisabled(1, false));
    TrackSelectorResult result =
        trackSelector.selectTracks(RENDERER_CAPABILITIES, TRACK_GROUPS, periodId, TIMELINE);
    assertSelections(result, TRACK_SELECTIONS);
    assertThat(new RendererConfiguration[] {DEFAULT, DEFAULT})
        .isEqualTo(result.rendererConfigurations);
  }

  /** Tests a no-sample renderer is enabled without a track selection by default. */
  @Test
  public void selectTracksWithNoSampleRenderer() throws ExoPlaybackException {
    TrackSelectorResult result =
        trackSelector.selectTracks(
            RENDERER_CAPABILITIES_WITH_NO_SAMPLE_RENDERER, TRACK_GROUPS, periodId, TIMELINE);
    assertSelections(result, TRACK_SELECTIONS_WITH_NO_SAMPLE_RENDERER);
    assertThat(new RendererConfiguration[] {DEFAULT, DEFAULT})
        .isEqualTo(result.rendererConfigurations);
  }

  /** Tests disabling a no-sample renderer. */
  @Test
  public void selectTracksWithDisabledNoSampleRenderer() throws ExoPlaybackException {
    trackSelector.setParameters(defaultParameters.buildUpon().setRendererDisabled(1, true));
    TrackSelectorResult result =
        trackSelector.selectTracks(
            RENDERER_CAPABILITIES_WITH_NO_SAMPLE_RENDERER, TRACK_GROUPS, periodId, TIMELINE);
    assertSelections(result, TRACK_SELECTIONS_WITH_NO_SAMPLE_RENDERER);
    assertThat(new RendererConfiguration[] {DEFAULT, null})
        .isEqualTo(result.rendererConfigurations);
  }

  /**
   * Tests that track selector will not call {@link
   * InvalidationListener#onTrackSelectionsInvalidated()} when it's set with default values of
   * {@link Parameters}.
   */
  @Test
  public void setParameterWithDefaultParametersDoesNotNotifyInvalidationListener() {
    trackSelector.setParameters(defaultParameters);
    verify(invalidationListener, never()).onTrackSelectionsInvalidated();
  }

  /**
   * Tests that track selector will call {@link InvalidationListener#onTrackSelectionsInvalidated()}
   * when it's set with non-default values of {@link Parameters}.
   */
  @Test
  public void setParameterWithNonDefaultParameterNotifyInvalidationListener() {
    Parameters.Builder builder = defaultParameters.buildUpon().setPreferredAudioLanguage("eng");
    trackSelector.setParameters(builder);
    verify(invalidationListener).onTrackSelectionsInvalidated();
  }

  /**
   * Tests that track selector will not call {@link
   * InvalidationListener#onTrackSelectionsInvalidated()} again when it's set with the same values
   * of {@link Parameters}.
   */
  @Test
  public void setParameterWithSameParametersDoesNotNotifyInvalidationListenerAgain() {
    Parameters.Builder builder = defaultParameters.buildUpon().setPreferredAudioLanguage("eng");
    trackSelector.setParameters(builder);
    trackSelector.setParameters(builder);
    verify(invalidationListener, times(1)).onTrackSelectionsInvalidated();
  }

  /**
   * Tests that track selector will select audio track with {@link C#SELECTION_FLAG_DEFAULT} given
   * default values of {@link Parameters}.
   */
  @Test
  public void selectTracksSelectTrackWithSelectionFlag() throws Exception {
    Format.Builder formatBuilder = AUDIO_FORMAT.buildUpon();
    Format audioFormat = formatBuilder.setSelectionFlags(0).build();
    Format formatWithSelectionFlag =
        formatBuilder.setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build();
    TrackGroupArray trackGroups = wrapFormats(audioFormat, formatWithSelectionFlag);

    TrackSelectorResult result =
        trackSelector.selectTracks(
            new RendererCapabilities[] {ALL_AUDIO_FORMAT_SUPPORTED_RENDERER_CAPABILITIES},
            trackGroups,
            periodId,
            TIMELINE);
    assertFixedSelection(result.selections[0], trackGroups, formatWithSelectionFlag);
  }

  /** Tests that adaptive audio track selections respect the maximum audio bitrate. */
  @Test
  public void selectAdaptiveAudioTrackGroupWithMaxBitrate() throws ExoPlaybackException {
    Format.Builder formatBuilder = AUDIO_FORMAT.buildUpon();
    Format format128k = formatBuilder.setAverageBitrate(128 * 1024).build();
    Format format192k = formatBuilder.setAverageBitrate(192 * 1024).build();
    Format format256k = formatBuilder.setAverageBitrate(256 * 1024).build();
    RendererCapabilities[] rendererCapabilities = {
      ALL_AUDIO_FORMAT_SUPPORTED_RENDERER_CAPABILITIES
    };
    TrackGroupArray trackGroups =
        new TrackGroupArray(new TrackGroup(format192k, format128k, format256k));

    TrackSelectorResult result =
        trackSelector.selectTracks(rendererCapabilities, trackGroups, periodId, TIMELINE);
    assertAdaptiveSelection(result.selections[0], trackGroups.get(0), 2, 0, 1);

    trackSelector.setParameters(
        trackSelector.buildUponParameters().setMaxAudioBitrate(256 * 1024 - 1));
    result = trackSelector.selectTracks(rendererCapabilities, trackGroups, periodId, TIMELINE);
    assertAdaptiveSelection(result.selections[0], trackGroups.get(0), 0, 1);

    trackSelector.setParameters(trackSelector.buildUponParameters().setMaxAudioBitrate(192 * 1024));
    result = trackSelector.selectTracks(rendererCapabilities, trackGroups, periodId, TIMELINE);
    assertAdaptiveSelection(result.selections[0], trackGroups.get(0), 0, 1);

    trackSelector.setParameters(
        trackSelector.buildUponParameters().setMaxAudioBitrate(192 * 1024 - 1));
    result = trackSelector.selectTracks(rendererCapabilities, trackGroups, periodId, TIMELINE);
    assertFixedSelection(result.selections[0], trackGroups.get(0), 1);

    trackSelector.setParameters(trackSelector.buildUponParameters().setMaxAudioBitrate(10));
    result = trackSelector.selectTracks(rendererCapabilities, trackGroups, periodId, TIMELINE);
    assertFixedSelection(result.selections[0], trackGroups.get(0), 1);
  }

  /**
   * Tests that track selector will select audio track with language that match preferred language
   * given by {@link Parameters}.
   */
  @Test
  public void selectTracksSelectPreferredAudioLanguage() throws Exception {
    Format.Builder formatBuilder = AUDIO_FORMAT.buildUpon();
    Format frAudioFormat = formatBuilder.setLanguage("fra").build();
    Format enAudioFormat = formatBuilder.setLanguage("eng").build();
    TrackGroupArray trackGroups = wrapFormats(frAudioFormat, enAudioFormat);

    trackSelector.setParameters(defaultParameters.buildUpon().setPreferredAudioLanguage("eng"));
    TrackSelectorResult result =
        trackSelector.selectTracks(
            new RendererCapabilities[] {ALL_AUDIO_FORMAT_SUPPORTED_RENDERER_CAPABILITIES},
            wrapFormats(frAudioFormat, enAudioFormat),
            periodId,
            TIMELINE);
    assertFixedSelection(result.selections[0], trackGroups, enAudioFormat);
  }

  /**
   * Tests that track selector will select the audio track with the highest number of matching role
   * flags given by {@link Parameters}.
   */
  @Test
  public void selectTracks_withPreferredAudioRoleFlags_selectPreferredTrack() throws Exception {
    Format.Builder formatBuilder = AUDIO_FORMAT.buildUpon();
    Format noRoleFlags = formatBuilder.build();
    Format lessRoleFlags = formatBuilder.setRoleFlags(C.ROLE_FLAG_CAPTION).build();
    Format moreRoleFlags =
        formatBuilder
            .setRoleFlags(C.ROLE_FLAG_CAPTION | C.ROLE_FLAG_COMMENTARY | C.ROLE_FLAG_DUB)
            .build();
    TrackGroupArray trackGroups = wrapFormats(noRoleFlags, moreRoleFlags, lessRoleFlags);

    trackSelector.setParameters(
        defaultParameters
            .buildUpon()
            .setPreferredAudioRoleFlags(C.ROLE_FLAG_CAPTION | C.ROLE_FLAG_COMMENTARY));
    TrackSelectorResult result =
        trackSelector.selectTracks(
            new RendererCapabilities[] {ALL_AUDIO_FORMAT_SUPPORTED_RENDERER_CAPABILITIES},
            trackGroups,
            periodId,
            TIMELINE);
    assertFixedSelection(result.selections[0], trackGroups, moreRoleFlags);

    // Also verify that exact match between parameters and tracks is preferred.
    trackSelector.setParameters(
        defaultParameters.buildUpon().setPreferredAudioRoleFlags(C.ROLE_FLAG_CAPTION));
    result =
        trackSelector.selectTracks(
            new RendererCapabilities[] {ALL_AUDIO_FORMAT_SUPPORTED_RENDERER_CAPABILITIES},
            trackGroups,
            periodId,
            TIMELINE);
    assertFixedSelection(result.selections[0], trackGroups, lessRoleFlags);
  }

  /**
   * Tests that track selector with select default audio track if no role flag preference is
   * specified by {@link Parameters}.
   */
  @Test
  public void selectTracks_withoutPreferredAudioRoleFlags_selectsDefaultTrack() throws Exception {
    Format firstFormat = AUDIO_FORMAT;
    Format defaultFormat =
        AUDIO_FORMAT.buildUpon().setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build();
    Format roleFlagFormat = AUDIO_FORMAT.buildUpon().setRoleFlags(C.ROLE_FLAG_CAPTION).build();
    TrackGroupArray trackGroups = wrapFormats(firstFormat, defaultFormat, roleFlagFormat);

    TrackSelectorResult result =
        trackSelector.selectTracks(
            new RendererCapabilities[] {ALL_AUDIO_FORMAT_SUPPORTED_RENDERER_CAPABILITIES},
            trackGroups,
            periodId,
            TIMELINE);
    assertFixedSelection(result.selections[0], trackGroups, defaultFormat);
  }

  /**
   * Tests that track selector with select the first audio track if no role flag preference is
   * specified by {@link Parameters} and no default track exists.
   */
  @Test
  public void selectTracks_withoutPreferredAudioRoleFlagsOrDefaultTrack_selectsFirstTrack()
      throws Exception {
    Format firstFormat = AUDIO_FORMAT;
    Format roleFlagFormat = AUDIO_FORMAT.buildUpon().setRoleFlags(C.ROLE_FLAG_CAPTION).build();
    TrackGroupArray trackGroups = wrapFormats(firstFormat, roleFlagFormat);

    TrackSelectorResult result =
        trackSelector.selectTracks(
            new RendererCapabilities[] {ALL_AUDIO_FORMAT_SUPPORTED_RENDERER_CAPABILITIES},
            trackGroups,
            periodId,
            TIMELINE);
    assertFixedSelection(result.selections[0], trackGroups, firstFormat);
  }

  /**
   * Tests that track selector will prefer selecting audio track with language that match preferred
   * language given by {@link Parameters} over track with {@link C#SELECTION_FLAG_DEFAULT}.
   */
  @Test
  public void selectTracksSelectPreferredAudioLanguageOverSelectionFlag() throws Exception {
    Format.Builder formatBuilder = AUDIO_FORMAT.buildUpon();
    Format frDefaultFormat =
        formatBuilder.setLanguage("fra").setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build();
    Format enNonDefaultFormat = formatBuilder.setLanguage("eng").setSelectionFlags(0).build();
    TrackGroupArray trackGroups = wrapFormats(frDefaultFormat, enNonDefaultFormat);

    trackSelector.setParameters(defaultParameters.buildUpon().setPreferredAudioLanguage("eng"));
    TrackSelectorResult result =
        trackSelector.selectTracks(
            new RendererCapabilities[] {ALL_AUDIO_FORMAT_SUPPORTED_RENDERER_CAPABILITIES},
            trackGroups,
            periodId,
            TIMELINE);
    assertFixedSelection(result.selections[0], trackGroups, enNonDefaultFormat);
  }

  /**
   * Tests that track selector will select a video track with a language that matches the preferred
   * language given by {@link Parameters}.
   */
  @Test
  public void selectTracksSelectPreferredAudioVideoLanguage() throws Exception {
    Format.Builder formatBuilder = VIDEO_FORMAT.buildUpon();
    Format frVideoFormat = formatBuilder.setLanguage("fra").build();
    Format enVideoFormat = formatBuilder.setLanguage("eng").build();
    TrackGroupArray trackGroups = wrapFormats(frVideoFormat, enVideoFormat);

    trackSelector.setParameters(defaultParameters.buildUpon().setPreferredVideoLanguage("eng"));
    TrackSelectorResult result =
        trackSelector.selectTracks(
            new RendererCapabilities[] {ALL_VIDEO_FORMAT_EXCEEDED_RENDERER_CAPABILITIES},
            wrapFormats(frVideoFormat, enVideoFormat),
            periodId,
            TIMELINE);
    assertFixedSelection(result.selections[0], trackGroups, enVideoFormat);
  }

  /**
   * Tests that the default track selector will select:
   *
   * <ul>
   *   <li>A main video track matching the selected audio language when a main video track in
   *       another language is present.
   *   <li>A main video track that doesn't match the selected audio language when a main video track
   *       in the selected audio language is not present (but alternate video tracks in this
   *       language are present).
   * </ul>
   */
  @Test
  public void defaultVideoTracksInteractWithSelectedAudioLanguageAsExpected()
      throws ExoPlaybackException {
    Format.Builder mainVideoBuilder = VIDEO_FORMAT.buildUpon().setRoleFlags(C.ROLE_FLAG_MAIN);
    Format mainEnglish = mainVideoBuilder.setLanguage("eng").build();
    Format mainGerman = mainVideoBuilder.setLanguage("deu").build();
    Format mainNoLanguage = mainVideoBuilder.setLanguage(C.LANGUAGE_UNDETERMINED).build();
    Format alternateGerman =
        VIDEO_FORMAT.buildUpon().setRoleFlags(C.ROLE_FLAG_ALTERNATE).setLanguage("deu").build();

    Format noLanguageAudio = AUDIO_FORMAT.buildUpon().setLanguage(null).build();
    Format germanAudio = AUDIO_FORMAT.buildUpon().setLanguage("deu").build();

    RendererCapabilities[] rendererCapabilities =
        new RendererCapabilities[] {VIDEO_CAPABILITIES, AUDIO_CAPABILITIES};

    // Neither the audio nor the forced text track define a language. We select them both under the
    // assumption that they have matching language.
    TrackGroupArray trackGroups = wrapFormats(noLanguageAudio, mainNoLanguage);
    TrackSelectorResult result =
        trackSelector.selectTracks(rendererCapabilities, trackGroups, periodId, TIMELINE);
    assertFixedSelection(result.selections[0], trackGroups, mainNoLanguage);

    // The audio declares german. The main german track should be selected (in favour of the main
    // english track).
    trackGroups = wrapFormats(germanAudio, mainGerman, mainEnglish);
    result = trackSelector.selectTracks(rendererCapabilities, trackGroups, periodId, TIMELINE);
    assertFixedSelection(result.selections[0], trackGroups, mainGerman);

    // The audio declares german. The main english track should be selected because there's no
    // main german track.
    trackGroups = wrapFormats(germanAudio, alternateGerman, mainEnglish);
    result = trackSelector.selectTracks(rendererCapabilities, trackGroups, periodId, TIMELINE);
    assertFixedSelection(result.selections[0], trackGroups, mainEnglish);
  }

  /**
   * Tests that track selector will prefer tracks that are within renderer's capabilities over track
   * that exceed renderer's capabilities.
   */
  @Test
  public void selectTracksPreferTrackWithinCapabilities() throws Exception {
    Format.Builder formatBuilder = AUDIO_FORMAT.buildUpon();
    Format supportedFormat = formatBuilder.setId("supportedFormat").build();
    Format exceededFormat = formatBuilder.setId("exceededFormat").build();
    TrackGroupArray trackGroups = wrapFormats(exceededFormat, supportedFormat);

    Map<String, Integer> mappedCapabilities = new HashMap<>();
    mappedCapabilities.put(supportedFormat.id, FORMAT_HANDLED);
    mappedCapabilities.put(exceededFormat.id, FORMAT_EXCEEDS_CAPABILITIES);
    RendererCapabilities mappedAudioRendererCapabilities =
        new FakeMappedRendererCapabilities(C.TRACK_TYPE_AUDIO, mappedCapabilities);

    TrackSelectorResult result =
        trackSelector.selectTracks(
            new RendererCapabilities[] {mappedAudioRendererCapabilities},
            trackGroups,
            periodId,
            TIMELINE);
    assertFixedSelection(result.selections[0], trackGroups, supportedFormat);
  }

  /**
   * Tests that track selector will select a track that exceeds the renderer's capabilities when
   * there are no other choice, given the default {@link Parameters}.
   */
  @Test
  public void selectTracksWithNoTrackWithinCapabilitiesSelectExceededCapabilityTrack()
      throws Exception {
    TrackGroupArray trackGroups = singleTrackGroup(AUDIO_FORMAT);

    TrackSelectorResult result =
        trackSelector.selectTracks(
            new RendererCapabilities[] {ALL_AUDIO_FORMAT_EXCEEDED_RENDERER_CAPABILITIES},
            trackGroups,
            periodId,
            TIMELINE);
    assertFixedSelection(result.selections[0], trackGroups, AUDIO_FORMAT);
  }

  /**
   * Tests that track selector will return a null track selection for a renderer when all tracks
   * exceed that renderer's capabilities when {@link Parameters} does not allow
   * exceeding-capabilities tracks.
   */
  @Test
  public void selectTracksWithNoTrackWithinCapabilitiesAndSetByParamsReturnNoSelection()
      throws Exception {
    TrackGroupArray trackGroups = singleTrackGroup(AUDIO_FORMAT);

    trackSelector.setParameters(
        defaultParameters.buildUpon().setExceedRendererCapabilitiesIfNecessary(false));
    TrackSelectorResult result =
        trackSelector.selectTracks(
            new RendererCapabilities[] {ALL_AUDIO_FORMAT_EXCEEDED_RENDERER_CAPABILITIES},
            trackGroups,
            periodId,
            TIMELINE);
    assertNoSelection(result.selections[0]);
  }

  /**
   * Tests that track selector will prefer tracks that are within renderer's capabilities over
   * tracks that have {@link C#SELECTION_FLAG_DEFAULT} but exceed renderer's capabilities.
   */
  @Test
  public void selectTracksPreferTrackWithinCapabilitiesOverSelectionFlag() throws Exception {
    Format.Builder formatBuilder = AUDIO_FORMAT.buildUpon();
    Format exceededWithSelectionFlagFormat =
        formatBuilder.setId("exceededFormat").setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build();
    Format supportedFormat = formatBuilder.setId("supportedFormat").setSelectionFlags(0).build();
    TrackGroupArray trackGroups = wrapFormats(exceededWithSelectionFlagFormat, supportedFormat);

    Map<String, Integer> mappedCapabilities = new HashMap<>();
    mappedCapabilities.put(supportedFormat.id, FORMAT_HANDLED);
    mappedCapabilities.put(exceededWithSelectionFlagFormat.id, FORMAT_EXCEEDS_CAPABILITIES);
    RendererCapabilities mappedAudioRendererCapabilities =
        new FakeMappedRendererCapabilities(C.TRACK_TYPE_AUDIO, mappedCapabilities);

    TrackSelectorResult result =
        trackSelector.selectTracks(
            new RendererCapabilities[] {mappedAudioRendererCapabilities},
            trackGroups,
            periodId,
            TIMELINE);
    assertFixedSelection(result.selections[0], trackGroups, supportedFormat);
  }

  /**
   * Tests that track selector will prefer tracks that are within renderer's capabilities over track
   * that have language matching preferred audio given by {@link Parameters} but exceed renderer's
   * capabilities.
   */
  @Test
  public void selectTracksPreferTrackWithinCapabilitiesOverPreferredLanguage() throws Exception {
    Format.Builder formatBuilder = AUDIO_FORMAT.buildUpon();
    Format exceededEnFormat = formatBuilder.setId("exceededFormat").setLanguage("eng").build();
    Format supportedFrFormat = formatBuilder.setId("supportedFormat").setLanguage("fra").build();
    TrackGroupArray trackGroups = wrapFormats(exceededEnFormat, supportedFrFormat);

    Map<String, Integer> mappedCapabilities = new HashMap<>();
    mappedCapabilities.put(exceededEnFormat.id, FORMAT_EXCEEDS_CAPABILITIES);
    mappedCapabilities.put(supportedFrFormat.id, FORMAT_HANDLED);
    RendererCapabilities mappedAudioRendererCapabilities =
        new FakeMappedRendererCapabilities(C.TRACK_TYPE_AUDIO, mappedCapabilities);

    trackSelector.setParameters(defaultParameters.buildUpon().setPreferredAudioLanguage("eng"));
    TrackSelectorResult result =
        trackSelector.selectTracks(
            new RendererCapabilities[] {mappedAudioRendererCapabilities},
            trackGroups,
            periodId,
            TIMELINE);
    assertFixedSelection(result.selections[0], trackGroups, supportedFrFormat);
  }

  /**
   * Tests that track selector will prefer tracks that are within renderer's capabilities over track
   * that have both language matching preferred audio given by {@link Parameters} and {@link
   * C#SELECTION_FLAG_DEFAULT}, but exceed renderer's capabilities.
   */
  @Test
  public void selectTracksPreferTrackWithinCapabilitiesOverSelectionFlagAndPreferredLanguage()
      throws Exception {
    Format.Builder formatBuilder = AUDIO_FORMAT.buildUpon();
    Format exceededDefaultSelectionEnFormat =
        formatBuilder
            .setId("exceededFormat")
            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
            .setLanguage("eng")
            .build();
    Format supportedFrFormat =
        formatBuilder.setId("supportedFormat").setSelectionFlags(0).setLanguage("fra").build();
    TrackGroupArray trackGroups = wrapFormats(exceededDefaultSelectionEnFormat, supportedFrFormat);

    Map<String, Integer> mappedCapabilities = new HashMap<>();
    mappedCapabilities.put(exceededDefaultSelectionEnFormat.id, FORMAT_EXCEEDS_CAPABILITIES);
    mappedCapabilities.put(supportedFrFormat.id, FORMAT_HANDLED);
    RendererCapabilities mappedAudioRendererCapabilities =
        new FakeMappedRendererCapabilities(C.TRACK_TYPE_AUDIO, mappedCapabilities);

    trackSelector.setParameters(defaultParameters.buildUpon().setPreferredAudioLanguage("eng"));
    TrackSelectorResult result =
        trackSelector.selectTracks(
            new RendererCapabilities[] {mappedAudioRendererCapabilities},
            trackGroups,
            periodId,
            TIMELINE);
    assertFixedSelection(result.selections[0], trackGroups, supportedFrFormat);
  }

  /**
   * Tests that track selector will select audio tracks with higher num channel when other factors
   * are the same, and tracks are within renderer's capabilities.
   */
  @Test
  public void
      selectTracks_audioChannelCountConstraintsDisabledAndTracksWithinCapabilities_selectHigherNumChannel()
          throws Exception {
    Format.Builder formatBuilder = AUDIO_FORMAT.buildUpon();
    Format higherChannelFormat = formatBuilder.setChannelCount(6).build();
    Format lowerChannelFormat = formatBuilder.setChannelCount(2).build();
    TrackGroupArray trackGroups = wrapFormats(higherChannelFormat, lowerChannelFormat);
    trackSelector.setParameters(
        trackSelector
            .buildUponParameters()
            .setConstrainAudioChannelCountToDeviceCapabilities(false)
            .build());

    TrackSelectorResult result =
        trackSelector.selectTracks(
            new RendererCapabilities[] {ALL_AUDIO_FORMAT_SUPPORTED_RENDERER_CAPABILITIES},
            trackGroups,
            periodId,
            TIMELINE);
    assertFixedSelection(result.selections[0], trackGroups, higherChannelFormat);
  }

  /**
   * Tests that track selector will select audio tracks with higher sample rate when other factors
   * are the same, and tracks are within renderer's capabilities.
   */
  @Test
  public void selectTracksWithinCapabilitiesSelectHigherSampleRate() throws Exception {
    Format.Builder formatBuilder = AUDIO_FORMAT.buildUpon();
    Format higherSampleRateFormat = formatBuilder.setSampleRate(44100).build();
    Format lowerSampleRateFormat = formatBuilder.setSampleRate(22050).build();
    TrackGroupArray trackGroups = wrapFormats(higherSampleRateFormat, lowerSampleRateFormat);

    TrackSelectorResult result =
        trackSelector.selectTracks(
            new RendererCapabilities[] {ALL_AUDIO_FORMAT_SUPPORTED_RENDERER_CAPABILITIES},
            trackGroups,
            periodId,
            TIMELINE);
    assertFixedSelection(result.selections[0], trackGroups, higherSampleRateFormat);
  }

  /**
   * Tests that track selector will select audio tracks with higher bit rate when other factors are
   * the same, and tracks are within renderer's capabilities, and have the same language.
   */
  @Test
  public void selectAudioTracks_withinCapabilities_andSameLanguage_selectsHigherBitrate()
      throws Exception {
    Format.Builder formatBuilder = AUDIO_FORMAT.buildUpon().setLanguage("en");
    Format lowerBitrateFormat = formatBuilder.setAverageBitrate(15000).build();
    Format higherBitrateFormat = formatBuilder.setAverageBitrate(30000).build();
    TrackGroupArray trackGroups = wrapFormats(lowerBitrateFormat, higherBitrateFormat);

    TrackSelectorResult result =
        trackSelector.selectTracks(
            new RendererCapabilities[] {ALL_AUDIO_FORMAT_SUPPORTED_RENDERER_CAPABILITIES},
            trackGroups,
            periodId,
            TIMELINE);
    assertFixedSelection(result.selections[0], trackGroups, higherBitrateFormat);
  }

  /**
   * Tests that track selector will select the first audio track even if other tracks with a
   * different language have higher bit rates, all other factors are the same, and tracks are within
   * renderer's capabilities.
   */
  @Test
  public void selectAudioTracks_withinCapabilities_andDifferentLanguage_selectsFirstTrack()
      throws Exception {
    Format.Builder formatBuilder = AUDIO_FORMAT.buildUpon();
    Format firstLanguageFormat = formatBuilder.setAverageBitrate(15000).setLanguage("hi").build();
    Format higherBitrateFormat = formatBuilder.setAverageBitrate(30000).setLanguage("te").build();
    TrackGroupArray trackGroups = wrapFormats(firstLanguageFormat, higherBitrateFormat);

    TrackSelectorResult result =
        trackSelector.selectTracks(
            new RendererCapabilities[] {ALL_AUDIO_FORMAT_SUPPORTED_RENDERER_CAPABILITIES},
            trackGroups,
            periodId,
            TIMELINE);
    assertFixedSelection(result.selections[0], trackGroups, firstLanguageFormat);
  }

  /**
   * Tests that track selector will prefer audio tracks with object based audio over tracks with
   * higher channel count when other factors are the same, and tracks are within renderer's
   * capabilities.
   */
  @Test
  public void
      selectTracks_audioChannelCountConstraintsDisabled_preferObjectBasedAudioBeforeChannelCount()
          throws Exception {
    Format channelBasedAudioWithHigherChannelCountFormat =
        new Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AC3).setChannelCount(6).build();
    Format objectBasedAudioWithLowerChannelCountFormat =
        new Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AC4).setChannelCount(2).build();
    TrackGroupArray trackGroups =
        wrapFormats(
            channelBasedAudioWithHigherChannelCountFormat,
            objectBasedAudioWithLowerChannelCountFormat);
    trackSelector.setParameters(
        trackSelector
            .buildUponParameters()
            .setConstrainAudioChannelCountToDeviceCapabilities(false)
            .build());

    TrackSelectorResult result =
        trackSelector.selectTracks(
            new RendererCapabilities[] {ALL_AUDIO_FORMAT_SUPPORTED_RENDERER_CAPABILITIES},
            trackGroups,
            periodId,
            TIMELINE);
    assertFixedSelection(
        result.selections[0], trackGroups, objectBasedAudioWithLowerChannelCountFormat);
  }

  /**
   * Tests that track selector will prefer audio tracks with higher channel count over tracks with
   * higher sample rate when audio channel count constraints are disabled, other factors are the
   * same, and tracks are within renderer's capabilities.
   */
  @Test
  public void
      selectTracks_audioChannelCountConstraintsDisabled_preferHigherNumChannelBeforeSampleRate()
          throws Exception {
    Format.Builder formatBuilder = AUDIO_FORMAT.buildUpon();
    Format higherChannelLowerSampleRateFormat =
        formatBuilder.setChannelCount(6).setSampleRate(22050).build();
    Format lowerChannelHigherSampleRateFormat =
        formatBuilder.setChannelCount(2).setSampleRate(44100).build();
    TrackGroupArray trackGroups =
        wrapFormats(higherChannelLowerSampleRateFormat, lowerChannelHigherSampleRateFormat);
    trackSelector.setParameters(
        trackSelector
            .buildUponParameters()
            .setConstrainAudioChannelCountToDeviceCapabilities(false)
            .build());

    TrackSelectorResult result =
        trackSelector.selectTracks(
            new RendererCapabilities[] {ALL_AUDIO_FORMAT_SUPPORTED_RENDERER_CAPABILITIES},
            trackGroups,
            periodId,
            TIMELINE);
    assertFixedSelection(result.selections[0], trackGroups, higherChannelLowerSampleRateFormat);
  }

  /**
   * Tests that track selector will prefer audio tracks with higher sample rate over tracks with
   * higher bitrate when other factors are the same, and tracks are within renderer's capabilities.
   */
  @Test
  public void selectTracksPreferHigherSampleRateBeforeBitrate() throws Exception {
    Format.Builder formatBuilder = AUDIO_FORMAT.buildUpon();
    Format higherSampleRateLowerBitrateFormat =
        formatBuilder.setAverageBitrate(15000).setSampleRate(44100).build();
    Format lowerSampleRateHigherBitrateFormat =
        formatBuilder.setAverageBitrate(30000).setSampleRate(22050).build();
    TrackGroupArray trackGroups =
        wrapFormats(higherSampleRateLowerBitrateFormat, lowerSampleRateHigherBitrateFormat);

    TrackSelectorResult result =
        trackSelector.selectTracks(
            new RendererCapabilities[] {ALL_AUDIO_FORMAT_SUPPORTED_RENDERER_CAPABILITIES},
            trackGroups,
            periodId,
            TIMELINE);
    assertFixedSelection(result.selections[0], trackGroups, higherSampleRateLowerBitrateFormat);
  }

  /**
   * Tests that track selector will select audio tracks with lower num channel when other factors
   * are the same, and tracks exceed renderer's capabilities.
   */
  @Test
  public void selectTracksExceedingCapabilitiesSelectLowerNumChannel() throws Exception {
    Format.Builder formatBuilder = AUDIO_FORMAT.buildUpon();
    Format higherChannelFormat = formatBuilder.setChannelCount(6).build();
    Format lowerChannelFormat = formatBuilder.setChannelCount(2).build();
    TrackGroupArray trackGroups = wrapFormats(higherChannelFormat, lowerChannelFormat);

    TrackSelectorResult result =
        trackSelector.selectTracks(
            new RendererCapabilities[] {ALL_AUDIO_FORMAT_EXCEEDED_RENDERER_CAPABILITIES},
            trackGroups,
            periodId,
            TIMELINE);
    assertFixedSelection(result.selections[0], trackGroups, lowerChannelFormat);
  }

  /**
   * Tests that track selector will select audio tracks with lower sample rate when other factors
   * are the same, and tracks exceed renderer's capabilities.
   */
  @Test
  public void selectTracksExceedingCapabilitiesSelectLowerSampleRate() throws Exception {
    Format.Builder formatBuilder = AUDIO_FORMAT.buildUpon();
    Format lowerSampleRateFormat = formatBuilder.setSampleRate(22050).build();
    Format higherSampleRateFormat = formatBuilder.setSampleRate(44100).build();
    TrackGroupArray trackGroups = wrapFormats(higherSampleRateFormat, lowerSampleRateFormat);

    TrackSelectorResult result =
        trackSelector.selectTracks(
            new RendererCapabilities[] {ALL_AUDIO_FORMAT_EXCEEDED_RENDERER_CAPABILITIES},
            trackGroups,
            periodId,
            TIMELINE);
    assertFixedSelection(result.selections[0], trackGroups, lowerSampleRateFormat);
  }

  /**
   * Tests that track selector will select audio tracks with lower bit-rate when other factors are
   * the same, and tracks exceed renderer's capabilities.
   */
  @Test
  public void selectTracksExceedingCapabilitiesSelectLowerBitrate() throws Exception {
    Format.Builder formatBuilder = AUDIO_FORMAT.buildUpon();
    Format lowerBitrateFormat = formatBuilder.setAverageBitrate(15000).build();
    Format higherBitrateFormat = formatBuilder.setAverageBitrate(30000).build();
    TrackGroupArray trackGroups = wrapFormats(lowerBitrateFormat, higherBitrateFormat);

    TrackSelectorResult result =
        trackSelector.selectTracks(
            new RendererCapabilities[] {ALL_AUDIO_FORMAT_EXCEEDED_RENDERER_CAPABILITIES},
            trackGroups,
            periodId,
            TIMELINE);
    assertFixedSelection(result.selections[0], trackGroups, lowerBitrateFormat);
  }

  /**
   * Tests that track selector will prefer audio tracks with lower channel count over tracks with
   * lower sample rate when other factors are the same, and tracks are within renderer's
   * capabilities.
   */
  @Test
  public void selectTracksExceedingCapabilitiesPreferLowerNumChannelBeforeSampleRate()
      throws Exception {
    Format.Builder formatBuilder = AUDIO_FORMAT.buildUpon();
    Format lowerChannelHigherSampleRateFormat =
        formatBuilder.setChannelCount(2).setSampleRate(44100).build();
    Format higherChannelLowerSampleRateFormat =
        formatBuilder.setChannelCount(6).setSampleRate(22050).build();
    TrackGroupArray trackGroups =
        wrapFormats(higherChannelLowerSampleRateFormat, lowerChannelHigherSampleRateFormat);

    TrackSelectorResult result =
        trackSelector.selectTracks(
            new RendererCapabilities[] {ALL_AUDIO_FORMAT_EXCEEDED_RENDERER_CAPABILITIES},
            trackGroups,
            periodId,
            TIMELINE);
    assertFixedSelection(result.selections[0], trackGroups, lowerChannelHigherSampleRateFormat);
  }

  /**
   * Tests that track selector will prefer audio tracks with lower sample rate over tracks with
   * lower bitrate when other factors are the same, and tracks are within renderer's capabilities.
   */
  @Test
  public void selectTracksExceedingCapabilitiesPreferLowerSampleRateBeforeBitrate()
      throws Exception {
    Format.Builder formatBuilder = AUDIO_FORMAT.buildUpon();
    Format higherSampleRateLowerBitrateFormat =
        formatBuilder.setAverageBitrate(15000).setSampleRate(44100).build();
    Format lowerSampleRateHigherBitrateFormat =
        formatBuilder.setAverageBitrate(30000).setSampleRate(22050).build();
    TrackGroupArray trackGroups =
        wrapFormats(higherSampleRateLowerBitrateFormat, lowerSampleRateHigherBitrateFormat);

    TrackSelectorResult result =
        trackSelector.selectTracks(
            new RendererCapabilities[] {ALL_AUDIO_FORMAT_EXCEEDED_RENDERER_CAPABILITIES},
            trackGroups,
            periodId,
            TIMELINE);
    assertFixedSelection(result.selections[0], trackGroups, lowerSampleRateHigherBitrateFormat);
  }

  /** Tests text track selection flags. */
  @Test
  public void textTrackSelectionFlags() throws ExoPlaybackException {
    Format.Builder formatBuilder = TEXT_FORMAT.buildUpon().setLanguage("eng");
    Format forcedOnly = formatBuilder.setSelectionFlags(C.SELECTION_FLAG_FORCED).build();
    Format forcedDefault =
        formatBuilder.setSelectionFlags(C.SELECTION_FLAG_FORCED | C.SELECTION_FLAG_DEFAULT).build();
    Format defaultOnly = formatBuilder.setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build();
    Format noFlag = formatBuilder.setSelectionFlags(0).build();

    RendererCapabilities[] textRendererCapabilities =
        new RendererCapabilities[] {ALL_TEXT_FORMAT_SUPPORTED_RENDERER_CAPABILITIES};

    // There is no text language preference, the first track flagged as default should be selected.
    TrackGroupArray trackGroups = wrapFormats(forcedOnly, forcedDefault, defaultOnly, noFlag);
    TrackSelectorResult result =
        trackSelector.selectTracks(textRendererCapabilities, trackGroups, periodId, TIMELINE);
    assertFixedSelection(result.selections[0], trackGroups, forcedDefault);

    // Ditto.
    trackGroups = wrapFormats(forcedOnly, noFlag, defaultOnly);
    result = trackSelector.selectTracks(textRendererCapabilities, trackGroups, periodId, TIMELINE);
    assertFixedSelection(result.selections[0], trackGroups, defaultOnly);

    // Default flags are disabled and no language preference is provided, so no text track is
    // selected.
    trackGroups = wrapFormats(defaultOnly, noFlag, forcedOnly, forcedDefault);
    trackSelector.setParameters(
        defaultParameters.buildUpon().setIgnoredTextSelectionFlags(C.SELECTION_FLAG_DEFAULT));
    result = trackSelector.selectTracks(textRendererCapabilities, trackGroups, periodId, TIMELINE);
    assertNoSelection(result.selections[0]);

    // All selection flags are disabled and there is no language preference, so nothing should be
    // selected.
    trackGroups = wrapFormats(forcedOnly, forcedDefault, defaultOnly, noFlag);
    trackSelector.setParameters(
        trackSelector
            .getParameters()
            .buildUpon()
            .setIgnoredTextSelectionFlags(C.SELECTION_FLAG_DEFAULT | C.SELECTION_FLAG_FORCED));
    result = trackSelector.selectTracks(textRendererCapabilities, trackGroups, periodId, TIMELINE);
    assertNoSelection(result.selections[0]);

    // There is a preferred language, so a language-matching track flagged as default should
    // be selected, and the one without forced flag should be preferred.
    trackSelector.setParameters(defaultParameters.buildUpon().setPreferredTextLanguage("eng"));
    result = trackSelector.selectTracks(textRendererCapabilities, trackGroups, periodId, TIMELINE);
    assertFixedSelection(result.selections[0], trackGroups, defaultOnly);

    // Same as above, but the default flag is disabled. If multiple tracks match the preferred
    // language, those not flagged as forced are preferred, as they likely include the contents of
    // forced subtitles.
    trackGroups = wrapFormats(noFlag, forcedOnly, forcedDefault, defaultOnly);
    trackSelector.setParameters(
        trackSelector
            .getParameters()
            .buildUpon()
            .setIgnoredTextSelectionFlags(C.SELECTION_FLAG_DEFAULT));
    result = trackSelector.selectTracks(textRendererCapabilities, trackGroups, periodId, TIMELINE);
    assertFixedSelection(result.selections[0], trackGroups, noFlag);
  }

  /**
   * Tests that the default track selector will select:
   *
   * <ul>
   *   <li>A forced text track with unknown language if the selected audio language is also unknown.
   *   <li>A forced text track matching the selected audio language.
   *   <li>A default text track matching the selected audio language when a default text track in
   *       another language is present.
   *   <li>A default text track that doesn't match the selected audio language when a default text
   *       track in the selected audio language is not present (but other text tracks in this
   *       language are present).
   * </ul>
   */
  @Test
  public void forcedAndDefaultTextTracksInteractWithSelectedAudioLanguageAsExpected()
      throws ExoPlaybackException {
    Format.Builder forcedTextBuilder =
        TEXT_FORMAT.buildUpon().setSelectionFlags(C.SELECTION_FLAG_FORCED);
    Format.Builder defaultTextBuilder =
        TEXT_FORMAT.buildUpon().setSelectionFlags(C.SELECTION_FLAG_DEFAULT);
    Format forcedEnglish = forcedTextBuilder.setLanguage("eng").build();
    Format defaultEnglish = defaultTextBuilder.setLanguage("eng").build();
    Format forcedGerman = forcedTextBuilder.setLanguage("deu").build();
    Format defaultGerman = defaultTextBuilder.setLanguage("deu").build();
    Format forcedNoLanguage = forcedTextBuilder.setLanguage(C.LANGUAGE_UNDETERMINED).build();

    Format noLanguageAudio = AUDIO_FORMAT.buildUpon().setLanguage(null).build();
    Format germanAudio = AUDIO_FORMAT.buildUpon().setLanguage("deu").build();

    RendererCapabilities[] rendererCapabilities =
        new RendererCapabilities[] {
          ALL_AUDIO_FORMAT_SUPPORTED_RENDERER_CAPABILITIES,
          ALL_TEXT_FORMAT_SUPPORTED_RENDERER_CAPABILITIES
        };

    // Neither the audio nor the forced text track define a language. We select them both under the
    // assumption that they have matching language.
    TrackGroupArray trackGroups = wrapFormats(noLanguageAudio, forcedNoLanguage);
    TrackSelectorResult result =
        trackSelector.selectTracks(rendererCapabilities, trackGroups, periodId, TIMELINE);
    assertFixedSelection(result.selections[1], trackGroups, forcedNoLanguage);

    // No forced text track should be selected because none of the forced text tracks' languages
    // matches the selected audio language.
    trackGroups = wrapFormats(noLanguageAudio, forcedEnglish, forcedGerman);
    result = trackSelector.selectTracks(rendererCapabilities, trackGroups, periodId, TIMELINE);
    assertNoSelection(result.selections[1]);

    // The audio declares german. The german forced track should be selected.
    trackGroups = wrapFormats(germanAudio, forcedGerman, forcedEnglish);
    result = trackSelector.selectTracks(rendererCapabilities, trackGroups, periodId, TIMELINE);
    assertFixedSelection(result.selections[1], trackGroups, forcedGerman);

    // Ditto
    trackGroups = wrapFormats(germanAudio, forcedEnglish, forcedGerman);
    result = trackSelector.selectTracks(rendererCapabilities, trackGroups, periodId, TIMELINE);
    assertFixedSelection(result.selections[1], trackGroups, forcedGerman);

    // The audio declares german. The default german track should be selected (in favour of the
    // default english track and forced german track).
    trackGroups =
        wrapFormats(germanAudio, forcedGerman, defaultGerman, forcedEnglish, defaultEnglish);
    result = trackSelector.selectTracks(rendererCapabilities, trackGroups, periodId, TIMELINE);
    assertFixedSelection(result.selections[1], trackGroups, defaultGerman);

    // The audio declares german. The default english track should be selected because there's no
    // default german track.
    trackGroups = wrapFormats(germanAudio, forcedGerman, forcedEnglish, defaultEnglish);
    result = trackSelector.selectTracks(rendererCapabilities, trackGroups, periodId, TIMELINE);
    assertFixedSelection(result.selections[1], trackGroups, defaultEnglish);
  }

  /**
   * Tests that the default track selector will select a text track with undetermined language if no
   * text track with the preferred language is available but {@link
   * Parameters#selectUndeterminedTextLanguage} is true.
   */
  @Test
  public void selectUndeterminedTextLanguageAsFallback() throws ExoPlaybackException {
    Format.Builder formatBuilder = TEXT_FORMAT.buildUpon();
    Format spanish = formatBuilder.setLanguage("spa").build();
    Format german = formatBuilder.setLanguage("de").build();
    Format undeterminedUnd = formatBuilder.setLanguage(C.LANGUAGE_UNDETERMINED).build();
    Format undeterminedNull = formatBuilder.setLanguage(null).build();

    RendererCapabilities[] textRendererCapabilites =
        new RendererCapabilities[] {ALL_TEXT_FORMAT_SUPPORTED_RENDERER_CAPABILITIES};

    TrackGroupArray trackGroups = wrapFormats(spanish, german, undeterminedUnd, undeterminedNull);
    TrackSelectorResult result =
        trackSelector.selectTracks(textRendererCapabilites, trackGroups, periodId, TIMELINE);
    assertNoSelection(result.selections[0]);

    trackSelector.setParameters(
        defaultParameters.buildUpon().setSelectUndeterminedTextLanguage(true));
    result = trackSelector.selectTracks(textRendererCapabilites, trackGroups, periodId, TIMELINE);
    assertFixedSelection(result.selections[0], trackGroups, undeterminedUnd);

    Parameters.Builder builder = defaultParameters.buildUpon().setPreferredTextLanguage("spa");
    trackSelector.setParameters(builder);
    result = trackSelector.selectTracks(textRendererCapabilites, trackGroups, periodId, TIMELINE);
    assertFixedSelection(result.selections[0], trackGroups, spanish);

    trackGroups = wrapFormats(german, undeterminedUnd, undeterminedNull);

    result = trackSelector.selectTracks(textRendererCapabilites, trackGroups, periodId, TIMELINE);
    assertNoSelection(result.selections[0]);

    trackSelector.setParameters(builder.setSelectUndeterminedTextLanguage(true));
    result = trackSelector.selectTracks(textRendererCapabilites, trackGroups, periodId, TIMELINE);
    assertFixedSelection(result.selections[0], trackGroups, undeterminedUnd);

    trackGroups = wrapFormats(german, undeterminedNull);
    result = trackSelector.selectTracks(textRendererCapabilites, trackGroups, periodId, TIMELINE);
    assertFixedSelection(result.selections[0], trackGroups, undeterminedNull);

    trackGroups = wrapFormats(german);
    result = trackSelector.selectTracks(textRendererCapabilites, trackGroups, periodId, TIMELINE);
    assertNoSelection(result.selections[0]);
  }

  /** Tests audio track selection when there are multiple audio renderers. */
  @Test
  public void selectPreferredTextTrackMultipleRenderers() throws Exception {
    Format.Builder formatBuilder = TEXT_FORMAT.buildUpon();
    Format english = formatBuilder.setId("en").setLanguage("en").build();
    Format german = formatBuilder.setId("de").setLanguage("de").build();

    // First renderer handles english.
    Map<String, Integer> firstRendererMappedCapabilities = new HashMap<>();
    firstRendererMappedCapabilities.put(english.id, FORMAT_HANDLED);
    firstRendererMappedCapabilities.put(german.id, FORMAT_UNSUPPORTED_SUBTYPE);
    RendererCapabilities firstRendererCapabilities =
        new FakeMappedRendererCapabilities(C.TRACK_TYPE_TEXT, firstRendererMappedCapabilities);

    // Second renderer handles german.
    Map<String, Integer> secondRendererMappedCapabilities = new HashMap<>();
    secondRendererMappedCapabilities.put(english.id, FORMAT_UNSUPPORTED_SUBTYPE);
    secondRendererMappedCapabilities.put(german.id, FORMAT_HANDLED);
    RendererCapabilities secondRendererCapabilities =
        new FakeMappedRendererCapabilities(C.TRACK_TYPE_TEXT, secondRendererMappedCapabilities);

    RendererCapabilities[] rendererCapabilities =
        new RendererCapabilities[] {firstRendererCapabilities, secondRendererCapabilities};
    TrackGroupArray trackGroups = wrapFormats(english, german);

    // Without an explicit language preference, nothing should be selected.
    TrackSelectorResult result =
        trackSelector.selectTracks(rendererCapabilities, trackGroups, periodId, TIMELINE);
    assertNoSelection(result.selections[0]);
    assertNoSelection(result.selections[1]);

    // Explicit language preference for english. First renderer should be used.
    trackSelector.setParameters(defaultParameters.buildUpon().setPreferredTextLanguage("en"));
    result = trackSelector.selectTracks(rendererCapabilities, trackGroups, periodId, TIMELINE);
    assertFixedSelection(result.selections[0], trackGroups, english);
    assertNoSelection(result.selections[1]);

    // Explicit language preference for German. Second renderer should be used.
    trackSelector.setParameters(defaultParameters.buildUpon().setPreferredTextLanguage("de"));
    result = trackSelector.selectTracks(rendererCapabilities, trackGroups, periodId, TIMELINE);
    assertNoSelection(result.selections[0]);
    assertFixedSelection(result.selections[1], trackGroups, german);
  }

  /**
   * Tests that track selector will select the text track with the highest number of matching role
   * flags given by {@link Parameters}.
   */
  @Test
  public void selectTracks_withPreferredTextRoleFlags_selectPreferredTrack() throws Exception {
    Format.Builder formatBuilder = TEXT_FORMAT.buildUpon();
    Format noRoleFlags = formatBuilder.build();
    Format lessRoleFlags = formatBuilder.setRoleFlags(C.ROLE_FLAG_CAPTION).build();
    Format moreRoleFlags =
        formatBuilder
            .setRoleFlags(C.ROLE_FLAG_CAPTION | C.ROLE_FLAG_COMMENTARY | C.ROLE_FLAG_DUB)
            .build();
    TrackGroupArray trackGroups = wrapFormats(noRoleFlags, moreRoleFlags, lessRoleFlags);

    trackSelector.setParameters(
        defaultParameters
            .buildUpon()
            .setPreferredTextRoleFlags(C.ROLE_FLAG_CAPTION | C.ROLE_FLAG_COMMENTARY));
    TrackSelectorResult result =
        trackSelector.selectTracks(
            new RendererCapabilities[] {ALL_TEXT_FORMAT_SUPPORTED_RENDERER_CAPABILITIES},
            trackGroups,
            periodId,
            TIMELINE);
    assertFixedSelection(result.selections[0], trackGroups, moreRoleFlags);

    // Also verify that exact match between parameters and tracks is preferred.
    trackSelector.setParameters(
        defaultParameters.buildUpon().setPreferredTextRoleFlags(C.ROLE_FLAG_CAPTION));
    result =
        trackSelector.selectTracks(
            new RendererCapabilities[] {ALL_TEXT_FORMAT_SUPPORTED_RENDERER_CAPABILITIES},
            trackGroups,
            periodId,
            TIMELINE);
    assertFixedSelection(result.selections[0], trackGroups, lessRoleFlags);
  }

  @Test
  public void selectTracks_withPreferredTextLabel_selectsLabeledTrack() throws Exception {
    Format unlabeledText = TEXT_FORMAT.buildUpon().setLabel(null).build();
    Format labeledText = TEXT_FORMAT.buildUpon().setLabel("commentary").build();
    TrackGroupArray trackGroups = wrapFormats(unlabeledText, labeledText);
    RendererCapabilities[] rendererCapabilities =
        new RendererCapabilities[] {ALL_TEXT_FORMAT_SUPPORTED_RENDERER_CAPABILITIES};

    trackSelector.setParameters(
        defaultParameters.buildUpon().setPreferredTextLabels("commentary").build());
    TrackSelectorResult result =
        trackSelector.selectTracks(rendererCapabilities, trackGroups, periodId, TIMELINE);

    assertFixedSelection(result.selections[0], trackGroups, labeledText);
  }

  @Test
  public void selectTracks_withMultiplePreferredTextLabels_selectsHigherPriorityLabeledTrack()
      throws Exception {
    Format lowPriorityLabeledText = TEXT_FORMAT.buildUpon().setLabel("low_priority").build();
    Format highPriorityLabeledText = TEXT_FORMAT.buildUpon().setLabel("high_priority").build();
    TrackGroupArray trackGroups = wrapFormats(lowPriorityLabeledText, highPriorityLabeledText);
    RendererCapabilities[] rendererCapabilities =
        new RendererCapabilities[] {ALL_TEXT_FORMAT_SUPPORTED_RENDERER_CAPABILITIES};

    trackSelector.setParameters(
        defaultParameters
            .buildUpon()
            .setPreferredTextLabels("high_priority", "low_priority")
            .build());
    TrackSelectorResult result =
        trackSelector.selectTracks(rendererCapabilities, trackGroups, periodId, TIMELINE);

    assertFixedSelection(result.selections[0], trackGroups, highPriorityLabeledText);
  }

  @Test
  public void selectTracks_roleFlagsPreferredOverTextLabel() throws Exception {
    Format labeledButWrongRoleFlag =
        TEXT_FORMAT
            .buildUpon()
            .setRoleFlags(C.ROLE_FLAG_SUPPLEMENTARY)
            .setLabel("commentary")
            .build();
    Format unlabeledButRightRoleFlag =
        TEXT_FORMAT.buildUpon().setRoleFlags(C.ROLE_FLAG_CAPTION).setLabel(null).build();
    TrackGroupArray trackGroups = wrapFormats(labeledButWrongRoleFlag, unlabeledButRightRoleFlag);
    RendererCapabilities[] rendererCapabilities =
        new RendererCapabilities[] {ALL_TEXT_FORMAT_SUPPORTED_RENDERER_CAPABILITIES};

    trackSelector.setParameters(
        defaultParameters
            .buildUpon()
            .setPreferredTextRoleFlags(C.ROLE_FLAG_CAPTION)
            .setPreferredTextLabels("commentary")
            .build());
    TrackSelectorResult result =
        trackSelector.selectTracks(rendererCapabilities, trackGroups, periodId, TIMELINE);

    assertFixedSelection(result.selections[0], trackGroups, unlabeledButRightRoleFlag);
  }

  @Test
  public void selectTracks_noSelectionWhenLanguageMismatchDespiteLabelMatch() throws Exception {
    Format labeledButWrongLanguage =
        TEXT_FORMAT.buildUpon().setLanguage("deu").setLabel("commentary").build();
    TrackGroupArray trackGroups = wrapFormats(labeledButWrongLanguage);
    RendererCapabilities[] rendererCapabilities =
        new RendererCapabilities[] {ALL_TEXT_FORMAT_SUPPORTED_RENDERER_CAPABILITIES};

    trackSelector.setParameters(
        defaultParameters
            .buildUpon()
            .setPreferredTextLanguage("eng")
            .setPreferredTextLabels("commentary")
            .build());
    TrackSelectorResult result =
        trackSelector.selectTracks(rendererCapabilities, trackGroups, periodId, TIMELINE);

    assertNoSelection(result.selections[0]);
  }

  @Test
  public void selectTracks_selectTextByDefault_selectsTrackWhenNoOtherPreferencesSet()
      throws Exception {
    // A text track with no language, role, or selection flags.
    Format plainTextFormat = TEXT_FORMAT.buildUpon().setLanguage(null).build();
    TrackGroupArray trackGroups = wrapFormats(plainTextFormat);
    RendererCapabilities[] rendererCapabilities =
        new RendererCapabilities[] {ALL_TEXT_FORMAT_SUPPORTED_RENDERER_CAPABILITIES};

    // With default parameters, the track is not selected as it matches no preference.
    TrackSelectorResult result =
        trackSelector.selectTracks(rendererCapabilities, trackGroups, periodId, TIMELINE);

    assertNoSelection(result.selections[0]);

    // When selectTextByDefault is true, the track is selected.
    trackSelector.setParameters(defaultParameters.buildUpon().setSelectTextByDefault(true).build());
    result = trackSelector.selectTracks(rendererCapabilities, trackGroups, periodId, TIMELINE);

    assertFixedSelection(result.selections[0], trackGroups, plainTextFormat);
  }

  @Test
  public void selectTracks_selectTextByDefault_selectsTrackEvenIfOtherPreferencesDoNotMatch()
      throws Exception {
    // A text track with a language, but no role or selection flags.
    Format frenchTextFormat = TEXT_FORMAT.buildUpon().setLanguage("fra").build();
    TrackGroupArray trackGroups = wrapFormats(frenchTextFormat);
    RendererCapabilities[] rendererCapabilities =
        new RendererCapabilities[] {ALL_TEXT_FORMAT_SUPPORTED_RENDERER_CAPABILITIES};

    // With preferred language "eng", the track is not selected.
    trackSelector.setParameters(defaultParameters.buildUpon().setPreferredTextLanguage("eng"));
    TrackSelectorResult result =
        trackSelector.selectTracks(rendererCapabilities, trackGroups, periodId, TIMELINE);
    assertNoSelection(result.selections[0]);

    // When selectTextByDefault is true, the track is selected even if the preferred language does
    // not match.
    trackSelector.setParameters(
        defaultParameters
            .buildUpon()
            .setPreferredTextLanguage("eng")
            .setSelectTextByDefault(true)
            .build());
    result = trackSelector.selectTracks(rendererCapabilities, trackGroups, periodId, TIMELINE);

    assertFixedSelection(result.selections[0], trackGroups, frenchTextFormat);
  }

  @Test
  public void selectTracks_selectTextByDefault_stillPrefersLanguageMatch() throws Exception {
    Format otherLanguageFormat = TEXT_FORMAT.buildUpon().setLanguage("deu").build();
    Format preferredLanguageFormat = TEXT_FORMAT.buildUpon().setLanguage("eng").build();
    TrackGroupArray trackGroups = wrapFormats(otherLanguageFormat, preferredLanguageFormat);
    RendererCapabilities[] rendererCapabilities =
        new RendererCapabilities[] {ALL_TEXT_FORMAT_SUPPORTED_RENDERER_CAPABILITIES};

    // With selectTextByDefault=true, both tracks are eligible, but the one matching the
    // preferred language should be chosen.
    trackSelector.setParameters(
        defaultParameters
            .buildUpon()
            .setSelectTextByDefault(true)
            .setPreferredTextLanguage("eng")
            .build());
    TrackSelectorResult result =
        trackSelector.selectTracks(rendererCapabilities, trackGroups, periodId, TIMELINE);

    assertFixedSelection(result.selections[0], trackGroups, preferredLanguageFormat);
  }

  @Test
  public void selectTracks_selectTextByDefault_hasNoEffectIfTextTrackTypeIsDisabled()
      throws Exception {
    Format plainTextFormat = TEXT_FORMAT.buildUpon().setLanguage(null).build();
    TrackGroupArray trackGroups = wrapFormats(plainTextFormat);
    RendererCapabilities[] rendererCapabilities =
        new RendererCapabilities[] {ALL_TEXT_FORMAT_SUPPORTED_RENDERER_CAPABILITIES};

    // Even with selectTextByDefault=true, no track is selected if the text type is disabled.
    trackSelector.setParameters(
        defaultParameters
            .buildUpon()
            .setSelectTextByDefault(true)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build());
    TrackSelectorResult result =
        trackSelector.selectTracks(rendererCapabilities, trackGroups, periodId, TIMELINE);

    assertNoSelection(result.selections[0]);
  }

  /**
   * Tests that track selector will select the lowest bitrate supported audio track when {@link
   * Parameters#forceLowestBitrate} is set.
   */
  @Test
  public void selectTracksWithinCapabilitiesAndForceLowestBitrateSelectLowerBitrate()
      throws Exception {
    Format.Builder formatBuilder = AUDIO_FORMAT.buildUpon();
    Format unsupportedLowBitrateFormat =
        formatBuilder.setId("unsupported").setAverageBitrate(5000).build();
    Format lowerBitrateFormat = formatBuilder.setId("lower").setAverageBitrate(15000).build();
    Format higherBitrateFormat = formatBuilder.setId("higher").setAverageBitrate(30000).build();
    TrackGroupArray trackGroups =
        wrapFormats(unsupportedLowBitrateFormat, lowerBitrateFormat, higherBitrateFormat);

    Map<String, Integer> mappedCapabilities = new HashMap<>();
    mappedCapabilities.put(unsupportedLowBitrateFormat.id, FORMAT_EXCEEDS_CAPABILITIES);
    mappedCapabilities.put(lowerBitrateFormat.id, FORMAT_HANDLED);
    mappedCapabilities.put(higherBitrateFormat.id, FORMAT_HANDLED);
    RendererCapabilities mappedAudioRendererCapabilities =
        new FakeMappedRendererCapabilities(C.TRACK_TYPE_AUDIO, mappedCapabilities);

    trackSelector.setParameters(defaultParameters.buildUpon().setForceLowestBitrate(true));
    TrackSelectorResult result =
        trackSelector.selectTracks(
            new RendererCapabilities[] {mappedAudioRendererCapabilities},
            trackGroups,
            periodId,
            TIMELINE);
    assertFixedSelection(result.selections[0], trackGroups, lowerBitrateFormat);
  }

  /**
   * Tests that track selector will select the highest bitrate supported audio track when {@link
   * Parameters#forceHighestSupportedBitrate} is set.
   */
  @Test
  public void selectTracksWithinCapabilitiesAndForceHighestBitrateSelectHigherBitrate()
      throws Exception {
    Format.Builder formatBuilder = AUDIO_FORMAT.buildUpon();
    Format lowerBitrateFormat = formatBuilder.setId("5000").setAverageBitrate(5000).build();
    Format higherBitrateFormat = formatBuilder.setId("15000").setAverageBitrate(15000).build();
    Format exceedsBitrateFormat = formatBuilder.setId("30000").setAverageBitrate(30000).build();
    TrackGroupArray trackGroups =
        wrapFormats(lowerBitrateFormat, higherBitrateFormat, exceedsBitrateFormat);

    Map<String, Integer> mappedCapabilities = new HashMap<>();
    mappedCapabilities.put(lowerBitrateFormat.id, FORMAT_HANDLED);
    mappedCapabilities.put(higherBitrateFormat.id, FORMAT_HANDLED);
    mappedCapabilities.put(exceedsBitrateFormat.id, FORMAT_EXCEEDS_CAPABILITIES);
    RendererCapabilities mappedAudioRendererCapabilities =
        new FakeMappedRendererCapabilities(C.TRACK_TYPE_AUDIO, mappedCapabilities);

    trackSelector.setParameters(
        defaultParameters.buildUpon().setForceHighestSupportedBitrate(true));
    TrackSelectorResult result =
        trackSelector.selectTracks(
            new RendererCapabilities[] {mappedAudioRendererCapabilities},
            trackGroups,
            periodId,
            TIMELINE);
    assertFixedSelection(result.selections[0], trackGroups, higherBitrateFormat);
  }

  @Test
  public void selectTracksWithMultipleAudioTracks() throws Exception {
    Format.Builder formatBuilder = AUDIO_FORMAT.buildUpon();
    TrackGroupArray trackGroups =
        singleTrackGroup(formatBuilder.setId("0").build(), formatBuilder.setId("1").build());
    TrackSelectorResult result =
        trackSelector.selectTracks(
            new RendererCapabilities[] {AUDIO_CAPABILITIES}, trackGroups, periodId, TIMELINE);

    assertThat(result.length).isEqualTo(1);
    assertAdaptiveSelection(result.selections[0], trackGroups.get(0), 0, 1);
  }

  /**
   * The following test is subject to the execution context. It currently runs on SDK 30 and the
   * environment matches a handheld device ({@link Util#isTv(Context)} returns {@code false}) and
   * there is no {@link android.media.Spatializer}. If the execution environment upgrades, the test
   * may start failing depending on how the Robolectric Spatializer behaves. If the test starts
   * failing, and Robolectric offers a shadow Spatializer whose behavior can be controlled, revise
   * this test so that the Spatializer cannot spatialize the multichannel format. Also add tests
   * where the Spatializer can spatialize multichannel formats and the track selector picks a
   * multichannel format.
   */
  @Test
  public void selectTracks_stereoAndMultichannelAACTracks_selectsStereo()
      throws ExoPlaybackException {
    Format stereoAudioFormat = AUDIO_FORMAT.buildUpon().setChannelCount(2).setId("0").build();
    Format multichannelAudioFormat = AUDIO_FORMAT.buildUpon().setChannelCount(6).setId("1").build();
    TrackGroupArray trackGroups = singleTrackGroup(stereoAudioFormat, multichannelAudioFormat);

    TrackSelectorResult result =
        trackSelector.selectTracks(
            new RendererCapabilities[] {AUDIO_CAPABILITIES}, trackGroups, periodId, TIMELINE);

    assertThat(result.length).isEqualTo(1);
    assertThat(result.selections[0].getSelectedFormat()).isSameInstanceAs(stereoAudioFormat);
  }

  /**
   * The following test is subject to the execution context. It currently runs on SDK 30 and the
   * environment matches a handheld device ({@link Util#isTv(Context)} returns {@code false}) and
   * there is no {@link android.media.Spatializer}. If the execution environment upgrades, the test
   * may start failing depending on how the Robolectric Spatializer behaves. If the test starts
   * failing, and Robolectric offers a shadow Spatializer whose behavior can be controlled, revise
   * this test so that the Spatializer does not support spatialization ({@link
   * Spatializer#getImmersiveAudioLevel()} returns {@link
   * Spatializer#SPATIALIZER_IMMERSIVE_LEVEL_NONE}).
   */
  @Test
  public void
      selectTracks_withAACStereoAndDolbyMultichannelTrackWithinCapabilities_selectsDolbyMultichannelTrack()
          throws ExoPlaybackException {
    Format stereoAudioFormat = AUDIO_FORMAT.buildUpon().setChannelCount(2).setId("0").build();
    Format multichannelAudioFormat =
        AUDIO_FORMAT
            .buildUpon()
            .setSampleMimeType(MimeTypes.AUDIO_AC3)
            .setChannelCount(6)
            .setId("1")
            .build();
    TrackGroupArray trackGroups = singleTrackGroup(stereoAudioFormat, multichannelAudioFormat);

    TrackSelectorResult result =
        trackSelector.selectTracks(
            new RendererCapabilities[] {AUDIO_CAPABILITIES}, trackGroups, periodId, TIMELINE);

    assertThat(result.length).isEqualTo(1);
    assertThat(result.selections[0].getSelectedFormat()).isSameInstanceAs(multichannelAudioFormat);
  }

  @Test
  public void
      selectTracks_audioChannelCountConstraintsDisabledAndMultipleAudioTracks_selectsAllTracksInBestConfigurationOnly()
          throws Exception {
    TrackGroupArray trackGroups =
        singleTrackGroup(
            buildAudioFormatWithConfiguration(
                /* id= */ "0", /* channelCount= */ 6, MimeTypes.AUDIO_AAC, /* sampleRate= */ 44100),
            buildAudioFormatWithConfiguration(
                /* id= */ "1", /* channelCount= */ 2, MimeTypes.AUDIO_AAC, /* sampleRate= */ 44100),
            buildAudioFormatWithConfiguration(
                /* id= */ "2", /* channelCount= */ 6, MimeTypes.AUDIO_AC3, /* sampleRate= */ 44100),
            buildAudioFormatWithConfiguration(
                /* id= */ "3", /* channelCount= */ 6, MimeTypes.AUDIO_AAC, /* sampleRate= */ 22050),
            buildAudioFormatWithConfiguration(
                /* id= */ "4", /* channelCount= */ 6, MimeTypes.AUDIO_AAC, /* sampleRate= */ 22050),
            buildAudioFormatWithConfiguration(
                /* id= */ "5", /* channelCount= */ 6, MimeTypes.AUDIO_AAC, /* sampleRate= */ 22050),
            buildAudioFormatWithConfiguration(
                /* id= */ "6",
                /* channelCount= */ 6,
                MimeTypes.AUDIO_AAC,
                /* sampleRate= */ 44100));
    trackSelector.setParameters(
        trackSelector
            .buildUponParameters()
            .setConstrainAudioChannelCountToDeviceCapabilities(false));

    TrackSelectorResult result =
        trackSelector.selectTracks(
            new RendererCapabilities[] {AUDIO_CAPABILITIES}, trackGroups, periodId, TIMELINE);

    assertThat(result.length).isEqualTo(1);
    assertAdaptiveSelection(result.selections[0], trackGroups.get(0), 0, 6);
  }

  @Test
  public void selectTracks_multipleAudioTracksWithoutBitrate_onlySelectsSingleTrack()
      throws Exception {
    TrackGroupArray trackGroups =
        singleTrackGroup(
            AUDIO_FORMAT.buildUpon().setId("0").setAverageBitrate(Format.NO_VALUE).build(),
            AUDIO_FORMAT.buildUpon().setId("1").setAverageBitrate(Format.NO_VALUE).build());
    TrackSelectorResult result =
        trackSelector.selectTracks(
            new RendererCapabilities[] {AUDIO_CAPABILITIES}, trackGroups, periodId, TIMELINE);

    assertThat(result.length).isEqualTo(1);
    assertFixedSelection(result.selections[0], trackGroups.get(0), /* expectedTrack= */ 0);
  }

  @Test
  public void selectTracksWithMultipleAudioTracksWithMixedSampleRates() throws Exception {
    Format.Builder formatBuilder = AUDIO_FORMAT.buildUpon();
    Format highSampleRateAudioFormat = formatBuilder.setSampleRate(44100).build();
    Format lowSampleRateAudioFormat = formatBuilder.setSampleRate(22050).build();

    // Should not adapt between mixed sample rates by default, so we expect a fixed selection
    // containing the higher sample rate stream.
    TrackGroupArray trackGroups =
        singleTrackGroup(highSampleRateAudioFormat, lowSampleRateAudioFormat);
    TrackSelectorResult result =
        trackSelector.selectTracks(
            new RendererCapabilities[] {AUDIO_CAPABILITIES}, trackGroups, periodId, TIMELINE);
    assertThat(result.length).isEqualTo(1);
    assertFixedSelection(result.selections[0], trackGroups, highSampleRateAudioFormat);

    // The same applies if the tracks are provided in the opposite order.
    trackGroups = singleTrackGroup(lowSampleRateAudioFormat, highSampleRateAudioFormat);
    result =
        trackSelector.selectTracks(
            new RendererCapabilities[] {AUDIO_CAPABILITIES}, trackGroups, periodId, TIMELINE);
    assertThat(result.length).isEqualTo(1);
    assertFixedSelection(result.selections[0], trackGroups, highSampleRateAudioFormat);

    // If we explicitly enable mixed sample rate adaptiveness, expect an adaptive selection.
    trackSelector.setParameters(
        defaultParameters.buildUpon().setAllowAudioMixedSampleRateAdaptiveness(true));
    result =
        trackSelector.selectTracks(
            new RendererCapabilities[] {AUDIO_CAPABILITIES}, trackGroups, periodId, TIMELINE);
    assertThat(result.length).isEqualTo(1);
    assertAdaptiveSelection(result.selections[0], trackGroups.get(0), 0, 1);
  }

  @Test
  public void selectTracksWithMultipleAudioTracksWithMixedMimeTypes() throws Exception {
    Format.Builder formatBuilder = AUDIO_FORMAT.buildUpon();
    Format aacAudioFormat = formatBuilder.setSampleMimeType(MimeTypes.AUDIO_AAC).build();
    Format opusAudioFormat = formatBuilder.setSampleMimeType(MimeTypes.AUDIO_OPUS).build();

    // Should not adapt between mixed MIME types by default, so we expect a fixed selection
    // containing the first stream.
    TrackGroupArray trackGroups = singleTrackGroup(aacAudioFormat, opusAudioFormat);
    TrackSelectorResult result =
        trackSelector.selectTracks(
            new RendererCapabilities[] {AUDIO_CAPABILITIES}, trackGroups, periodId, TIMELINE);
    assertThat(result.length).isEqualTo(1);
    assertFixedSelection(result.selections[0], trackGroups, aacAudioFormat);

    // The same applies if the tracks are provided in the opposite order.
    trackGroups = singleTrackGroup(opusAudioFormat, aacAudioFormat);
    result =
        trackSelector.selectTracks(
            new RendererCapabilities[] {AUDIO_CAPABILITIES}, trackGroups, periodId, TIMELINE);
    assertThat(result.length).isEqualTo(1);
    assertFixedSelection(result.selections[0], trackGroups, opusAudioFormat);

    // If we explicitly enable mixed MIME type adaptiveness, expect an adaptive selection.
    trackSelector.setParameters(
        defaultParameters.buildUpon().setAllowAudioMixedMimeTypeAdaptiveness(true));
    result =
        trackSelector.selectTracks(
            new RendererCapabilities[] {AUDIO_CAPABILITIES}, trackGroups, periodId, TIMELINE);
    assertThat(result.length).isEqualTo(1);
    assertAdaptiveSelection(result.selections[0], trackGroups.get(0), 0, 1);
  }

  @Test
  public void
      selectTracks_audioChannelCountConstraintsDisabledAndMultipleAudioTracksWithMixedChannelCounts()
          throws Exception {
    Format.Builder formatBuilder = AUDIO_FORMAT.buildUpon();
    Format stereoAudioFormat = formatBuilder.setChannelCount(2).build();
    Format surroundAudioFormat = formatBuilder.setChannelCount(5).build();
    trackSelector.setParameters(
        trackSelector
            .buildUponParameters()
            .setConstrainAudioChannelCountToDeviceCapabilities(false)
            .build());

    // Should not adapt between different channel counts, so we expect a fixed selection containing
    // the track with more channels.
    TrackGroupArray trackGroups = singleTrackGroup(stereoAudioFormat, surroundAudioFormat);
    TrackSelectorResult result =
        trackSelector.selectTracks(
            new RendererCapabilities[] {AUDIO_CAPABILITIES}, trackGroups, periodId, TIMELINE);
    assertThat(result.length).isEqualTo(1);
    assertFixedSelection(result.selections[0], trackGroups, surroundAudioFormat);

    // The same applies if the tracks are provided in the opposite order.
    trackGroups = singleTrackGroup(surroundAudioFormat, stereoAudioFormat);
    result =
        trackSelector.selectTracks(
            new RendererCapabilities[] {AUDIO_CAPABILITIES}, trackGroups, periodId, TIMELINE);
    assertThat(result.length).isEqualTo(1);
    assertFixedSelection(result.selections[0], trackGroups, surroundAudioFormat);

    // If we constrain the channel count to 4 we expect a fixed selection containing the track with
    // fewer channels.
    trackSelector.setParameters(
        defaultParameters
            .buildUpon()
            .setConstrainAudioChannelCountToDeviceCapabilities(false)
            .setMaxAudioChannelCount(4));
    result =
        trackSelector.selectTracks(
            new RendererCapabilities[] {AUDIO_CAPABILITIES}, trackGroups, periodId, TIMELINE);
    assertThat(result.length).isEqualTo(1);
    assertFixedSelection(result.selections[0], trackGroups, stereoAudioFormat);

    // If we constrain the channel count to 2 we expect a fixed selection containing the track with
    // fewer channels.
    trackSelector.setParameters(
        defaultParameters
            .buildUpon()
            .setConstrainAudioChannelCountToDeviceCapabilities(false)
            .setMaxAudioChannelCount(2));
    result =
        trackSelector.selectTracks(
            new RendererCapabilities[] {AUDIO_CAPABILITIES}, trackGroups, periodId, TIMELINE);
    assertThat(result.length).isEqualTo(1);
    assertFixedSelection(result.selections[0], trackGroups, stereoAudioFormat);

    // If we constrain the channel count to 1 we expect a fixed selection containing the track with
    // fewer channels.
    trackSelector.setParameters(
        defaultParameters
            .buildUpon()
            .setConstrainAudioChannelCountToDeviceCapabilities(false)
            .setMaxAudioChannelCount(1));
    result =
        trackSelector.selectTracks(
            new RendererCapabilities[] {AUDIO_CAPABILITIES}, trackGroups, periodId, TIMELINE);
    assertThat(result.length).isEqualTo(1);
    assertFixedSelection(result.selections[0], trackGroups, stereoAudioFormat);

    // If we disable exceeding of constraints we expect no selection.
    trackSelector.setParameters(
        defaultParameters
            .buildUpon()
            .setConstrainAudioChannelCountToDeviceCapabilities(false)
            .setMaxAudioChannelCount(1)
            .setExceedAudioConstraintsIfNecessary(false));
    result =
        trackSelector.selectTracks(
            new RendererCapabilities[] {AUDIO_CAPABILITIES}, trackGroups, periodId, TIMELINE);
    assertThat(result.length).isEqualTo(1);
    assertNoSelection(result.selections[0]);
  }

  @Test
  public void selectTracksWithMultipleAudioTracksWithMixedDecoderSupportLevels() throws Exception {
    Format.Builder formatBuilder = AUDIO_FORMAT.buildUpon();
    Format format0 = formatBuilder.setId("0").setAverageBitrate(200).build();
    Format format1 = formatBuilder.setId("1").setAverageBitrate(400).build();
    Format format2 = formatBuilder.setId("2").setAverageBitrate(600).build();
    Format format3 = formatBuilder.setId("3").setAverageBitrate(800).build();
    TrackGroupArray trackGroups = singleTrackGroup(format0, format1, format2, format3);
    @Capabilities int unsupported = RendererCapabilities.create(FORMAT_UNSUPPORTED_TYPE);
    @Capabilities
    int primaryHardware =
        RendererCapabilities.create(
            FORMAT_HANDLED,
            ADAPTIVE_NOT_SEAMLESS,
            TUNNELING_NOT_SUPPORTED,
            HARDWARE_ACCELERATION_SUPPORTED,
            DECODER_SUPPORT_PRIMARY);
    @Capabilities
    int primarySoftware =
        RendererCapabilities.create(
            FORMAT_HANDLED,
            ADAPTIVE_NOT_SEAMLESS,
            TUNNELING_NOT_SUPPORTED,
            HARDWARE_ACCELERATION_NOT_SUPPORTED,
            DECODER_SUPPORT_PRIMARY);
    @Capabilities
    int fallbackHardware =
        RendererCapabilities.create(
            FORMAT_HANDLED,
            ADAPTIVE_NOT_SEAMLESS,
            TUNNELING_NOT_SUPPORTED,
            HARDWARE_ACCELERATION_SUPPORTED,
            DECODER_SUPPORT_FALLBACK);
    @Capabilities
    int fallbackSoftware =
        RendererCapabilities.create(
            FORMAT_HANDLED,
            ADAPTIVE_NOT_SEAMLESS,
            TUNNELING_NOT_SUPPORTED,
            HARDWARE_ACCELERATION_NOT_SUPPORTED,
            DECODER_SUPPORT_FALLBACK);

    // Select all tracks supported by primary, hardware decoder by default.
    ImmutableMap<String, Integer> rendererCapabilitiesMap =
        ImmutableMap.of(
            "0",
            primaryHardware,
            "1",
            primaryHardware,
            "2",
            primarySoftware,
            "3",
            fallbackHardware);
    RendererCapabilities rendererCapabilities =
        new FakeMappedRendererCapabilities(C.TRACK_TYPE_AUDIO, rendererCapabilitiesMap);
    TrackSelectorResult result =
        trackSelector.selectTracks(
            new RendererCapabilities[] {rendererCapabilities}, trackGroups, periodId, TIMELINE);
    assertAdaptiveSelection(result.selections[0], trackGroups.get(0), 1, 0);

    // Select all tracks supported by primary, software decoder by default if no primary, hardware
    // decoder is available.
    rendererCapabilitiesMap =
        ImmutableMap.of(
            "0",
            fallbackHardware,
            "1",
            fallbackHardware,
            "2",
            primarySoftware,
            "3",
            fallbackSoftware);
    rendererCapabilities =
        new FakeMappedRendererCapabilities(C.TRACK_TYPE_AUDIO, rendererCapabilitiesMap);
    result =
        trackSelector.selectTracks(
            new RendererCapabilities[] {rendererCapabilities}, trackGroups, periodId, TIMELINE);
    assertFixedSelection(result.selections[0], trackGroups.get(0), 2);

    // Select all tracks supported by fallback, hardware decoder if no primary decoder is
    // available.
    rendererCapabilitiesMap =
        ImmutableMap.of(
            "0", fallbackHardware, "1", unsupported, "2", fallbackSoftware, "3", fallbackHardware);
    rendererCapabilities =
        new FakeMappedRendererCapabilities(C.TRACK_TYPE_AUDIO, rendererCapabilitiesMap);
    result =
        trackSelector.selectTracks(
            new RendererCapabilities[] {rendererCapabilities}, trackGroups, periodId, TIMELINE);
    assertAdaptiveSelection(result.selections[0], trackGroups.get(0), 3, 0);

    // Select all tracks supported by fallback, software decoder if no other decoder is available.
    rendererCapabilitiesMap =
        ImmutableMap.of(
            "0", fallbackSoftware, "1", fallbackSoftware, "2", unsupported, "3", fallbackSoftware);
    rendererCapabilities =
        new FakeMappedRendererCapabilities(C.TRACK_TYPE_AUDIO, rendererCapabilitiesMap);
    result =
        trackSelector.selectTracks(
            new RendererCapabilities[] {rendererCapabilities}, trackGroups, periodId, TIMELINE);
    assertAdaptiveSelection(result.selections[0], trackGroups.get(0), 3, 1, 0);

    // Select all tracks if mixed decoder support is allowed.
    rendererCapabilitiesMap =
        ImmutableMap.of(
            "0", primaryHardware, "1", unsupported, "2", primarySoftware, "3", fallbackHardware);
    rendererCapabilities =
        new FakeMappedRendererCapabilities(C.TRACK_TYPE_AUDIO, rendererCapabilitiesMap);
    trackSelector.setParameters(
        defaultParameters.buildUpon().setAllowAudioMixedDecoderSupportAdaptiveness(true));
    result =
        trackSelector.selectTracks(
            new RendererCapabilities[] {rendererCapabilities}, trackGroups, periodId, TIMELINE);
    assertAdaptiveSelection(result.selections[0], trackGroups.get(0), 3, 2, 0);
  }

  @Test
  public void selectTracks_withMultipleAudioTracksWithoutAdaptationSupport_selectsFixed()
      throws Exception {
    FakeRendererCapabilities seamlessAudioCapabilities =
        new FakeRendererCapabilities(
            C.TRACK_TYPE_AUDIO,
            RendererCapabilities.create(
                FORMAT_HANDLED, ADAPTIVE_NOT_SUPPORTED, TUNNELING_NOT_SUPPORTED));

    Format.Builder formatBuilder = AUDIO_FORMAT.buildUpon();
    TrackGroupArray trackGroups =
        singleTrackGroup(formatBuilder.setId("0").build(), formatBuilder.setId("1").build());
    // Explicitly allow non-seamless adaptation to ensure it's not used.
    trackSelector.setParameters(
        defaultParameters.buildUpon().setAllowAudioNonSeamlessAdaptiveness(true));
    TrackSelectorResult result =
        trackSelector.selectTracks(
            new RendererCapabilities[] {seamlessAudioCapabilities},
            trackGroups,
            periodId,
            TIMELINE);
    assertThat(result.length).isEqualTo(1);
    assertFixedSelection(result.selections[0], trackGroups.get(0), /* expectedTrack= */ 0);
  }

  @Test
  public void
      selectTracks_withMultipleAudioTracksWithNonSeamlessAdaptiveness_selectsAdaptiveOnlyIfAllowed()
          throws Exception {
    FakeRendererCapabilities nonSeamlessAudioCapabilities =
        new FakeRendererCapabilities(
            C.TRACK_TYPE_AUDIO,
            RendererCapabilities.create(
                FORMAT_HANDLED, ADAPTIVE_NOT_SEAMLESS, TUNNELING_NOT_SUPPORTED));

    Format.Builder formatBuilder = AUDIO_FORMAT.buildUpon();
    TrackGroupArray trackGroups =
        singleTrackGroup(formatBuilder.setId("0").build(), formatBuilder.setId("1").build());
    trackSelector.setParameters(
        defaultParameters.buildUpon().setAllowAudioNonSeamlessAdaptiveness(true));
    TrackSelectorResult result =
        trackSelector.selectTracks(
            new RendererCapabilities[] {nonSeamlessAudioCapabilities},
            trackGroups,
            periodId,
            TIMELINE);
    assertThat(result.length).isEqualTo(1);
    assertAdaptiveSelection(
        result.selections[0], trackGroups.get(0), /* expectedTracks...= */ 0, 1);

    trackSelector.setParameters(
        defaultParameters.buildUpon().setAllowAudioNonSeamlessAdaptiveness(false));
    result =
        trackSelector.selectTracks(
            new RendererCapabilities[] {nonSeamlessAudioCapabilities},
            trackGroups,
            periodId,
            TIMELINE);
    assertThat(result.length).isEqualTo(1);
    assertFixedSelection(result.selections[0], trackGroups.get(0), /* expectedTrack= */ 0);
  }

  @Test
  public void selectTracks_withMultipleAudioTracksWithSeamlessAdaptiveness_selectsAdaptive()
      throws Exception {
    FakeRendererCapabilities seamlessAudioCapabilities =
        new FakeRendererCapabilities(
            C.TRACK_TYPE_AUDIO,
            RendererCapabilities.create(
                FORMAT_HANDLED, ADAPTIVE_SEAMLESS, TUNNELING_NOT_SUPPORTED));

    Format.Builder formatBuilder = AUDIO_FORMAT.buildUpon();
    TrackGroupArray trackGroups =
        singleTrackGroup(formatBuilder.setId("0").build(), formatBuilder.setId("1").build());
    // Explicitly disallow non-seamless adaptation to ensure it's not used.
    trackSelector.setParameters(
        defaultParameters.buildUpon().setAllowAudioNonSeamlessAdaptiveness(false));
    TrackSelectorResult result =
        trackSelector.selectTracks(
            new RendererCapabilities[] {seamlessAudioCapabilities},
            trackGroups,
            periodId,
            TIMELINE);
    assertThat(result.length).isEqualTo(1);
    assertAdaptiveSelection(
        result.selections[0], trackGroups.get(0), /* expectedTracks...= */ 0, 1);
  }

  @Test
  public void selectTracksWithMultipleAudioTracksOverrideReturnsAdaptiveTrackSelection()
      throws Exception {
    Format.Builder formatBuilder = AUDIO_FORMAT.buildUpon();
    TrackGroupArray trackGroups =
        singleTrackGroup(
            formatBuilder.setId("0").build(),
            formatBuilder.setId("1").build(),
            formatBuilder.setId("2").build());
    trackSelector.setParameters(
        trackSelector
            .buildUponParameters()
            .setSelectionOverride(
                /* rendererIndex= */ 0,
                trackGroups,
                new SelectionOverride(/* groupIndex= */ 0, /* tracks=... */ 1, 2)));
    TrackSelectorResult result =
        trackSelector.selectTracks(
            new RendererCapabilities[] {AUDIO_CAPABILITIES}, trackGroups, periodId, TIMELINE);

    assertThat(result.length).isEqualTo(1);
    assertAdaptiveSelection(result.selections[0], trackGroups.get(0), 1, 2);
  }

  /** Tests audio track selection when there are multiple audio renderers. */
  @Test
  public void selectPreferredAudioTrackMultipleRenderers() throws Exception {
    Format.Builder formatBuilder = AUDIO_FORMAT.buildUpon();
    Format english = formatBuilder.setId("en").setLanguage("en").build();
    Format german = formatBuilder.setId("de").setLanguage("de").build();

    // First renderer handles english.
    Map<String, Integer> firstRendererMappedCapabilities = new HashMap<>();
    firstRendererMappedCapabilities.put(english.id, FORMAT_HANDLED);
    firstRendererMappedCapabilities.put(german.id, FORMAT_UNSUPPORTED_SUBTYPE);
    RendererCapabilities firstRendererCapabilities =
        new FakeMappedRendererCapabilities(C.TRACK_TYPE_AUDIO, firstRendererMappedCapabilities);

    // Second renderer handles german.
    Map<String, Integer> secondRendererMappedCapabilities = new HashMap<>();
    secondRendererMappedCapabilities.put(english.id, FORMAT_UNSUPPORTED_SUBTYPE);
    secondRendererMappedCapabilities.put(german.id, FORMAT_HANDLED);
    RendererCapabilities secondRendererCapabilities =
        new FakeMappedRendererCapabilities(C.TRACK_TYPE_AUDIO, secondRendererMappedCapabilities);

    RendererCapabilities[] rendererCapabilities =
        new RendererCapabilities[] {firstRendererCapabilities, secondRendererCapabilities};

    // Without an explicit language preference, prefer the first renderer.
    TrackGroupArray trackGroups = wrapFormats(english, german);
    TrackSelectorResult result =
        trackSelector.selectTracks(rendererCapabilities, trackGroups, periodId, TIMELINE);
    assertFixedSelection(result.selections[0], trackGroups, english);
    assertNoSelection(result.selections[1]);

    // Explicit language preference for english. First renderer should be used.
    trackSelector.setParameters(defaultParameters.buildUpon().setPreferredAudioLanguage("en"));
    result = trackSelector.selectTracks(rendererCapabilities, trackGroups, periodId, TIMELINE);
    assertFixedSelection(result.selections[0], trackGroups, english);
    assertNoSelection(result.selections[1]);

    // Explicit language preference for German. Second renderer should be used.
    trackSelector.setParameters(defaultParameters.buildUpon().setPreferredAudioLanguage("de"));
    result = trackSelector.selectTracks(rendererCapabilities, trackGroups, periodId, TIMELINE);
    assertNoSelection(result.selections[0]);
    assertFixedSelection(result.selections[1], trackGroups, german);
  }

  @Test
  public void selectTracksWithMultipleVideoTracks() throws Exception {
    Format.Builder formatBuilder = VIDEO_FORMAT.buildUpon();
    TrackGroupArray trackGroups =
        singleTrackGroup(formatBuilder.setId("0").build(), formatBuilder.setId("1").build());
    TrackSelectorResult result =
        trackSelector.selectTracks(
            new RendererCapabilities[] {VIDEO_CAPABILITIES}, trackGroups, periodId, TIMELINE);

    assertThat(result.length).isEqualTo(1);
    assertAdaptiveSelection(result.selections[0], trackGroups.get(0), 0, 1);
  }

  @Test
  public void selectTracksWithMultipleVideoTracksWithNonSeamlessAdaptiveness() throws Exception {
    FakeRendererCapabilities nonSeamlessVideoCapabilities =
        new FakeRendererCapabilities(
            C.TRACK_TYPE_VIDEO,
            RendererCapabilities.create(
                FORMAT_HANDLED, ADAPTIVE_NOT_SEAMLESS, TUNNELING_NOT_SUPPORTED));

    // Should do non-seamless adaptiveness by default, so expect an adaptive selection.
    Format.Builder formatBuilder = VIDEO_FORMAT.buildUpon();
    TrackGroupArray trackGroups =
        singleTrackGroup(formatBuilder.setId("0").build(), formatBuilder.setId("1").build());
    trackSelector.setParameters(
        defaultParameters.buildUpon().setAllowVideoNonSeamlessAdaptiveness(true));
    TrackSelectorResult result =
        trackSelector.selectTracks(
            new RendererCapabilities[] {nonSeamlessVideoCapabilities},
            trackGroups,
            periodId,
            TIMELINE);
    assertThat(result.length).isEqualTo(1);
    assertAdaptiveSelection(result.selections[0], trackGroups.get(0), 0, 1);

    // If we explicitly disable non-seamless adaptiveness, expect a fixed selection.
    trackSelector.setParameters(
        defaultParameters.buildUpon().setAllowVideoNonSeamlessAdaptiveness(false));
    result =
        trackSelector.selectTracks(
            new RendererCapabilities[] {nonSeamlessVideoCapabilities},
            trackGroups,
            periodId,
            TIMELINE);
    assertThat(result.length).isEqualTo(1);
    assertFixedSelection(result.selections[0], trackGroups.get(0), 0);
  }

  @Test
  public void selectTracksWithMultipleVideoTracksWithMixedMimeTypes() throws Exception {
    Format.Builder formatBuilder = VIDEO_FORMAT.buildUpon();
    Format h264VideoFormat = formatBuilder.setSampleMimeType(MimeTypes.VIDEO_H264).build();
    Format h265VideoFormat = formatBuilder.setSampleMimeType(MimeTypes.VIDEO_H265).build();

    // Should not adapt between mixed MIME types by default, so we expect a fixed selection
    // containing the first stream.
    TrackGroupArray trackGroups = singleTrackGroup(h264VideoFormat, h265VideoFormat);
    TrackSelectorResult result =
        trackSelector.selectTracks(
            new RendererCapabilities[] {VIDEO_CAPABILITIES}, trackGroups, periodId, TIMELINE);
    assertThat(result.length).isEqualTo(1);
    assertFixedSelection(result.selections[0], trackGroups, h264VideoFormat);

    // The same applies if the tracks are provided in the opposite order.
    trackGroups = singleTrackGroup(h265VideoFormat, h264VideoFormat);
    result =
        trackSelector.selectTracks(
            new RendererCapabilities[] {VIDEO_CAPABILITIES}, trackGroups, periodId, TIMELINE);
    assertThat(result.length).isEqualTo(1);
    assertFixedSelection(result.selections[0], trackGroups, h265VideoFormat);

    // If we explicitly enable mixed MIME type adaptiveness, expect an adaptive selection.
    trackSelector.setParameters(
        defaultParameters.buildUpon().setAllowVideoMixedMimeTypeAdaptiveness(true));
    result =
        trackSelector.selectTracks(
            new RendererCapabilities[] {VIDEO_CAPABILITIES}, trackGroups, periodId, TIMELINE);
    assertThat(result.length).isEqualTo(1);
    assertAdaptiveSelection(result.selections[0], trackGroups.get(0), 0, 1);
  }

  @Test
  public void selectTracksWithMultipleVideoTracksWithMixedDecoderSupportLevels() throws Exception {
    Format.Builder formatBuilder = VIDEO_FORMAT.buildUpon();
    Format format0 = formatBuilder.setId("0").setAverageBitrate(200).build();
    Format format1 = formatBuilder.setId("1").setAverageBitrate(400).build();
    Format format2 = formatBuilder.setId("2").setAverageBitrate(600).build();
    Format format3 = formatBuilder.setId("3").setAverageBitrate(800).build();
    TrackGroupArray trackGroups = singleTrackGroup(format0, format1, format2, format3);
    @Capabilities int unsupported = RendererCapabilities.create(FORMAT_UNSUPPORTED_TYPE);
    @Capabilities
    int primaryHardware =
        RendererCapabilities.create(
            FORMAT_HANDLED,
            ADAPTIVE_NOT_SEAMLESS,
            TUNNELING_NOT_SUPPORTED,
            HARDWARE_ACCELERATION_SUPPORTED,
            DECODER_SUPPORT_PRIMARY);
    @Capabilities
    int primarySoftware =
        RendererCapabilities.create(
            FORMAT_HANDLED,
            ADAPTIVE_NOT_SEAMLESS,
            TUNNELING_NOT_SUPPORTED,
            HARDWARE_ACCELERATION_NOT_SUPPORTED,
            DECODER_SUPPORT_PRIMARY);
    @Capabilities
    int fallbackHardware =
        RendererCapabilities.create(
            FORMAT_HANDLED,
            ADAPTIVE_NOT_SEAMLESS,
            TUNNELING_NOT_SUPPORTED,
            HARDWARE_ACCELERATION_SUPPORTED,
            DECODER_SUPPORT_FALLBACK);
    @Capabilities
    int fallbackSoftware =
        RendererCapabilities.create(
            FORMAT_HANDLED,
            ADAPTIVE_NOT_SEAMLESS,
            TUNNELING_NOT_SUPPORTED,
            HARDWARE_ACCELERATION_NOT_SUPPORTED,
            DECODER_SUPPORT_FALLBACK);

    // Select all tracks supported by primary, hardware decoder by default.
    ImmutableMap<String, Integer> rendererCapabilitiesMap =
        ImmutableMap.of(
            "0",
            primaryHardware,
            "1",
            primaryHardware,
            "2",
            primarySoftware,
            "3",
            fallbackHardware);
    RendererCapabilities rendererCapabilities =
        new FakeMappedRendererCapabilities(C.TRACK_TYPE_VIDEO, rendererCapabilitiesMap);
    TrackSelectorResult result =
        trackSelector.selectTracks(
            new RendererCapabilities[] {rendererCapabilities}, trackGroups, periodId, TIMELINE);
    assertAdaptiveSelection(result.selections[0], trackGroups.get(0), 1, 0);

    // Select all tracks supported by primary, software decoder by default if no primary, hardware
    // decoder is available.
    rendererCapabilitiesMap =
        ImmutableMap.of(
            "0",
            fallbackHardware,
            "1",
            fallbackHardware,
            "2",
            primarySoftware,
            "3",
            fallbackSoftware);
    rendererCapabilities =
        new FakeMappedRendererCapabilities(C.TRACK_TYPE_VIDEO, rendererCapabilitiesMap);
    result =
        trackSelector.selectTracks(
            new RendererCapabilities[] {rendererCapabilities}, trackGroups, periodId, TIMELINE);
    assertFixedSelection(result.selections[0], trackGroups.get(0), 2);

    // Select all tracks supported by fallback, hardware decoder if no primary decoder is
    // available.
    rendererCapabilitiesMap =
        ImmutableMap.of(
            "0", fallbackHardware, "1", unsupported, "2", fallbackSoftware, "3", fallbackHardware);
    rendererCapabilities =
        new FakeMappedRendererCapabilities(C.TRACK_TYPE_VIDEO, rendererCapabilitiesMap);
    result =
        trackSelector.selectTracks(
            new RendererCapabilities[] {rendererCapabilities}, trackGroups, periodId, TIMELINE);
    assertAdaptiveSelection(result.selections[0], trackGroups.get(0), 3, 0);

    // Select all tracks supported by fallback, software decoder if no other decoder is available.
    rendererCapabilitiesMap =
        ImmutableMap.of(
            "0", fallbackSoftware, "1", fallbackSoftware, "2", unsupported, "3", fallbackSoftware);
    rendererCapabilities =
        new FakeMappedRendererCapabilities(C.TRACK_TYPE_VIDEO, rendererCapabilitiesMap);
    result =
        trackSelector.selectTracks(
            new RendererCapabilities[] {rendererCapabilities}, trackGroups, periodId, TIMELINE);
    assertAdaptiveSelection(result.selections[0], trackGroups.get(0), 3, 1, 0);

    // Select all tracks if mixed decoder support is allowed.
    rendererCapabilitiesMap =
        ImmutableMap.of(
            "0", primaryHardware, "1", unsupported, "2", primarySoftware, "3", fallbackHardware);
    rendererCapabilities =
        new FakeMappedRendererCapabilities(C.TRACK_TYPE_VIDEO, rendererCapabilitiesMap);
    trackSelector.setParameters(
        defaultParameters.buildUpon().setAllowVideoMixedDecoderSupportAdaptiveness(true));
    result =
        trackSelector.selectTracks(
            new RendererCapabilities[] {rendererCapabilities}, trackGroups, periodId, TIMELINE);
    assertAdaptiveSelection(result.selections[0], trackGroups.get(0), 3, 2, 0);
  }

  @Test
  public void selectTracksWithMultipleVideoTracksOverrideReturnsAdaptiveTrackSelection()
      throws Exception {
    Format.Builder formatBuilder = VIDEO_FORMAT.buildUpon();
    TrackGroupArray trackGroups =
        singleTrackGroup(
            formatBuilder.setId("0").build(),
            formatBuilder.setId("1").build(),
            formatBuilder.setId("2").build());
    trackSelector.setParameters(
        trackSelector
            .buildUponParameters()
            .setSelectionOverride(
                /* rendererIndex= */ 0,
                trackGroups,
                new SelectionOverride(/* groupIndex= */ 0, /* tracks=... */ 1, 2)));
    TrackSelectorResult result =
        trackSelector.selectTracks(
            new RendererCapabilities[] {VIDEO_CAPABILITIES}, trackGroups, periodId, TIMELINE);

    assertThat(result.length).isEqualTo(1);
    assertAdaptiveSelection(result.selections[0], trackGroups.get(0), 1, 2);
  }

  @Test
  public void selectTracks_multipleVideoTracksWithoutBitrate_onlySelectsSingleTrack()
      throws Exception {
    TrackGroupArray trackGroups =
        singleTrackGroup(
            VIDEO_FORMAT.buildUpon().setId("0").setAverageBitrate(Format.NO_VALUE).build(),
            VIDEO_FORMAT.buildUpon().setId("1").setAverageBitrate(Format.NO_VALUE).build());
    TrackSelectorResult result =
        trackSelector.selectTracks(
            new RendererCapabilities[] {VIDEO_CAPABILITIES}, trackGroups, periodId, TIMELINE);

    assertThat(result.length).isEqualTo(1);
    assertFixedSelection(result.selections[0], trackGroups.get(0), /* expectedTrack= */ 0);
  }

  @Test
  public void selectTracks_multipleVideoAndAudioTracks() throws Exception {
    Format videoFormat1 = VIDEO_FORMAT.buildUpon().setAverageBitrate(1000).build();
    Format videoFormat2 = VIDEO_FORMAT.buildUpon().setAverageBitrate(2000).build();
    Format audioFormat1 = AUDIO_FORMAT.buildUpon().setAverageBitrate(100).build();
    Format audioFormat2 = AUDIO_FORMAT.buildUpon().setAverageBitrate(200).build();
    TrackGroupArray trackGroups =
        new TrackGroupArray(
            new TrackGroup(videoFormat1, videoFormat2), new TrackGroup(audioFormat1, audioFormat2));

    // Multiple adaptive selections allowed.
    trackSelector.setParameters(
        trackSelector.buildUponParameters().setAllowMultipleAdaptiveSelections(true));
    TrackSelectorResult result =
        trackSelector.selectTracks(
            new RendererCapabilities[] {VIDEO_CAPABILITIES, AUDIO_CAPABILITIES},
            trackGroups,
            periodId,
            TIMELINE);

    assertThat(result.length).isEqualTo(2);
    assertAdaptiveSelection(
        result.selections[0], trackGroups.get(0), /* expectedTracks...= */ 1, 0);
    assertAdaptiveSelection(
        result.selections[1], trackGroups.get(1), /* expectedTracks...= */ 1, 0);

    // Multiple adaptive selection disallowed.
    trackSelector.setParameters(
        trackSelector.buildUponParameters().setAllowMultipleAdaptiveSelections(false));
    result =
        trackSelector.selectTracks(
            new RendererCapabilities[] {VIDEO_CAPABILITIES, AUDIO_CAPABILITIES},
            trackGroups,
            periodId,
            TIMELINE);

    assertThat(result.length).isEqualTo(2);
    assertAdaptiveSelection(
        result.selections[0], trackGroups.get(0), /* expectedTracks...= */ 1, 0);
    assertFixedSelection(result.selections[1], trackGroups.get(1), /* expectedTrack= */ 1);
  }

  @Test
  public void selectTracks_withPreferredVideoMimeTypes_selectsTrackWithPreferredMimeType()
      throws Exception {
    Format formatAv1 = new Format.Builder().setSampleMimeType(MimeTypes.VIDEO_AV1).build();
    Format formatVp9 = new Format.Builder().setSampleMimeType(MimeTypes.VIDEO_VP9).build();
    Format formatH264Low =
        new Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).setAverageBitrate(400).build();
    Format formatH264High =
        new Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).setAverageBitrate(800).build();
    // Use an adaptive group to check that MIME type has a higher priority than number of tracks.
    TrackGroup adaptiveGroup = new TrackGroup(formatH264Low, formatH264High);
    TrackGroupArray trackGroups =
        new TrackGroupArray(new TrackGroup(formatAv1), new TrackGroup(formatVp9), adaptiveGroup);

    trackSelector.setParameters(
        defaultParameters.buildUpon().setPreferredVideoMimeType(MimeTypes.VIDEO_VP9));
    TrackSelectorResult result =
        trackSelector.selectTracks(
            new RendererCapabilities[] {VIDEO_CAPABILITIES}, trackGroups, periodId, TIMELINE);
    assertThat(result.length).isEqualTo(1);
    assertFixedSelection(result.selections[0], trackGroups, formatVp9);

    trackSelector.setParameters(
        defaultParameters
            .buildUpon()
            .setPreferredVideoMimeTypes(MimeTypes.VIDEO_VP9, MimeTypes.VIDEO_AV1));
    result =
        trackSelector.selectTracks(
            new RendererCapabilities[] {VIDEO_CAPABILITIES}, trackGroups, periodId, TIMELINE);
    assertThat(result.length).isEqualTo(1);
    assertFixedSelection(result.selections[0], trackGroups, formatVp9);

    trackSelector.setParameters(
        defaultParameters
            .buildUpon()
            .setPreferredVideoMimeTypes(MimeTypes.VIDEO_DIVX, MimeTypes.VIDEO_H264));
    result =
        trackSelector.selectTracks(
            new RendererCapabilities[] {VIDEO_CAPABILITIES}, trackGroups, periodId, TIMELINE);
    assertThat(result.length).isEqualTo(1);
    assertAdaptiveSelection(result.selections[0], adaptiveGroup, /* expectedTracks...= */ 1, 0);

    // Select default (=most tracks) if no preference is specified.
    trackSelector.setParameters(defaultParameters.buildUpon().setPreferredVideoMimeType(null));
    result =
        trackSelector.selectTracks(
            new RendererCapabilities[] {VIDEO_CAPABILITIES}, trackGroups, periodId, TIMELINE);
    assertThat(result.length).isEqualTo(1);
    assertAdaptiveSelection(result.selections[0], adaptiveGroup, /* expectedTracks...= */ 1, 0);
  }

  /**
   * Tests that track selector will select video track with support of its primary decoder over a
   * track that will use a decoder for it's format fallback sampleMimetype.
   */
  @Test
  public void selectTracks_withDecoderSupportFallbackMimetype_selectsTrackWithPrimaryDecoder()
      throws Exception {
    Format formatDV =
        new Format.Builder().setId("0").setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION).build();
    Format formatHevc =
        new Format.Builder().setId("1").setSampleMimeType(MimeTypes.VIDEO_H265).build();
    TrackGroupArray trackGroups =
        new TrackGroupArray(new TrackGroup(formatDV), new TrackGroup(formatHevc));
    @Capabilities
    int capabilitiesDecoderSupportPrimary =
        RendererCapabilities.create(
            FORMAT_HANDLED,
            ADAPTIVE_NOT_SEAMLESS,
            TUNNELING_NOT_SUPPORTED,
            HARDWARE_ACCELERATION_SUPPORTED,
            DECODER_SUPPORT_PRIMARY);
    int capabilitiesDecoderSupportFallbackType =
        RendererCapabilities.create(
            FORMAT_HANDLED,
            ADAPTIVE_NOT_SEAMLESS,
            TUNNELING_NOT_SUPPORTED,
            HARDWARE_ACCELERATION_SUPPORTED,
            DECODER_SUPPORT_FALLBACK_MIMETYPE);

    // Select track supported by primary decoder by default.
    ImmutableMap<String, Integer> rendererCapabilitiesMapDifferingDecoderSupport =
        ImmutableMap.of(
            "0", capabilitiesDecoderSupportFallbackType, "1", capabilitiesDecoderSupportPrimary);
    RendererCapabilities rendererCapabilitiesDifferingDecoderSupport =
        new FakeMappedRendererCapabilities(
            C.TRACK_TYPE_VIDEO, rendererCapabilitiesMapDifferingDecoderSupport);
    TrackSelectorResult result =
        trackSelector.selectTracks(
            new RendererCapabilities[] {rendererCapabilitiesDifferingDecoderSupport},
            trackGroups,
            periodId,
            TIMELINE);

    assertFixedSelection(result.selections[0], trackGroups, formatHevc);

    // Select Dolby Vision track over HEVC when renderer supports both equally
    ImmutableMap<String, Integer> rendererCapabilitiesMapAllPrimaryDecoderSupport =
        ImmutableMap.of(
            "0", capabilitiesDecoderSupportPrimary, "1", capabilitiesDecoderSupportPrimary);
    RendererCapabilities rendererCapabilitiesAllPrimaryDecoderSupport =
        new FakeMappedRendererCapabilities(
            C.TRACK_TYPE_VIDEO, rendererCapabilitiesMapAllPrimaryDecoderSupport);
    result =
        trackSelector.selectTracks(
            new RendererCapabilities[] {rendererCapabilitiesAllPrimaryDecoderSupport},
            trackGroups,
            periodId,
            TIMELINE);

    assertFixedSelection(result.selections[0], trackGroups, formatDV);
  }

  @Test
  public void selectTracks_withPreferredVideoLabel_selectsLabeledTrack() throws Exception {
    Format unlabeledVideo = VIDEO_FORMAT.buildUpon().setLabel(null).build();
    Format labeledVideo = VIDEO_FORMAT.buildUpon().setLabel("commentary").build();
    TrackGroupArray trackGroups = wrapFormats(unlabeledVideo, labeledVideo);
    RendererCapabilities[] rendererCapabilities = new RendererCapabilities[] {VIDEO_CAPABILITIES};

    trackSelector.setParameters(
        defaultParameters.buildUpon().setPreferredVideoLabels("commentary").build());
    TrackSelectorResult result =
        trackSelector.selectTracks(rendererCapabilities, trackGroups, periodId, TIMELINE);

    assertFixedSelection(result.selections[0], trackGroups, labeledVideo);
  }

  @Test
  public void selectTracks_withMultiplePreferredVideoLabels_selectsHigherPriorityLabeledTrack()
      throws Exception {
    Format lowPriorityLabeledVideo = VIDEO_FORMAT.buildUpon().setLabel("low_priority").build();
    Format highPriorityLabeledVideo = VIDEO_FORMAT.buildUpon().setLabel("high_priority").build();
    TrackGroupArray trackGroups = wrapFormats(lowPriorityLabeledVideo, highPriorityLabeledVideo);
    RendererCapabilities[] rendererCapabilities = new RendererCapabilities[] {VIDEO_CAPABILITIES};

    trackSelector.setParameters(
        defaultParameters
            .buildUpon()
            .setPreferredVideoLabels("high_priority", "low_priority")
            .build());
    TrackSelectorResult result =
        trackSelector.selectTracks(rendererCapabilities, trackGroups, periodId, TIMELINE);

    assertFixedSelection(result.selections[0], trackGroups, highPriorityLabeledVideo);
  }

  @Test
  public void selectTracks_videoRoleFlagsPreferredOverVideoLabel() throws Exception {
    Format labeledButWrongRoleFlag =
        VIDEO_FORMAT.buildUpon().setRoleFlags(C.ROLE_FLAG_DUB).setLabel("commentary").build();
    Format unlabeledButRightRoleFlag =
        VIDEO_FORMAT.buildUpon().setRoleFlags(C.ROLE_FLAG_COMMENTARY).setLabel(null).build();
    TrackGroupArray trackGroups = wrapFormats(labeledButWrongRoleFlag, unlabeledButRightRoleFlag);
    RendererCapabilities[] rendererCapabilities = new RendererCapabilities[] {VIDEO_CAPABILITIES};

    trackSelector.setParameters(
        defaultParameters
            .buildUpon()
            .setPreferredVideoRoleFlags(C.ROLE_FLAG_COMMENTARY)
            .setPreferredVideoLabels("commentary")
            .build());
    TrackSelectorResult result =
        trackSelector.selectTracks(rendererCapabilities, trackGroups, periodId, TIMELINE);

    assertFixedSelection(result.selections[0], trackGroups, unlabeledButRightRoleFlag);
  }

  @Test
  public void selectTracks_withViewportSize_selectsTrackWithinViewport() throws Exception {
    Format formatH264Low =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_H264)
            .setAverageBitrate(400)
            .setWidth(600)
            .setHeight(400)
            .build();
    Format formatH264Mid =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_H264)
            .setAverageBitrate(800)
            .setWidth(1200)
            .setHeight(800)
            .build();
    Format formatH264High =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_H264)
            .setAverageBitrate(2000)
            .setWidth(2400)
            .setHeight(1600)
            .build();
    TrackGroup adaptiveGroup = new TrackGroup(formatH264Low, formatH264Mid, formatH264High);
    TrackGroupArray trackGroups = new TrackGroupArray(adaptiveGroup);

    // Choose a viewport between low and mid, so that the low resolution only fits if orientation
    // changes are allowed.
    trackSelector.setParameters(
        defaultParameters
            .buildUpon()
            .setViewportSize(
                /* viewportWidth= */ 450,
                /* viewportHeight= */ 650,
                /* viewportOrientationMayChange= */ true));
    TrackSelectorResult result =
        trackSelector.selectTracks(
            new RendererCapabilities[] {VIDEO_CAPABILITIES}, trackGroups, periodId, TIMELINE);
    assertThat(result.length).isEqualTo(1);
    assertAdaptiveSelection(result.selections[0], adaptiveGroup, /* expectedTracks...= */ 1, 0);

    trackSelector.setParameters(
        defaultParameters
            .buildUpon()
            .setViewportSize(
                /* viewportWidth= */ 450,
                /* viewportHeight= */ 650,
                /* viewportOrientationMayChange= */ false));
    result =
        trackSelector.selectTracks(
            new RendererCapabilities[] {VIDEO_CAPABILITIES}, trackGroups, periodId, TIMELINE);
    assertThat(result.length).isEqualTo(1);
    assertFixedSelection(result.selections[0], adaptiveGroup, /* expectedTrack= */ 0);

    // Verify that selecting (almost) exactly one resolution does not include the next larger one.
    trackSelector.setParameters(
        defaultParameters
            .buildUpon()
            .setViewportSize(
                /* viewportWidth= */ 1201,
                /* viewportHeight= */ 801,
                /* viewportOrientationMayChange= */ true));
    result =
        trackSelector.selectTracks(
            new RendererCapabilities[] {VIDEO_CAPABILITIES}, trackGroups, periodId, TIMELINE);
    assertThat(result.length).isEqualTo(1);
    assertAdaptiveSelection(result.selections[0], adaptiveGroup, /* expectedTracks...= */ 1, 0);
  }

  @Test
  public void selectTracks_withViewportSizeSetToPhysicalDisplaySize_selectsTrackWithinDisplaySize()
      throws Exception {
    Format formatH264Low =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_H264)
            .setAverageBitrate(400)
            .setWidth(600)
            .setHeight(400)
            .build();
    Format formatH264Mid =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_H264)
            .setAverageBitrate(800)
            .setWidth(1200)
            .setHeight(800)
            .build();
    Format formatH264High =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_H264)
            .setAverageBitrate(2000)
            .setWidth(2400)
            .setHeight(1600)
            .build();
    TrackGroup adaptiveGroup = new TrackGroup(formatH264Low, formatH264Mid, formatH264High);
    TrackGroupArray trackGroups = new TrackGroupArray(adaptiveGroup);
    // Choose a display size (450x650) between low and mid, so that the low resolution only fits if
    // orientation changes are allowed.
    ShadowDisplayManager.changeDisplay(
        ShadowDisplay.getDefaultDisplay().getDisplayId(), "w450dp-h650dp-160dpi");

    trackSelector.setParameters(
        defaultParameters
            .buildUpon()
            .setViewportSizeToPhysicalDisplaySize(/* viewportOrientationMayChange= */ true));
    TrackSelectorResult result =
        trackSelector.selectTracks(
            new RendererCapabilities[] {VIDEO_CAPABILITIES}, trackGroups, periodId, TIMELINE);
    assertThat(result.length).isEqualTo(1);
    assertAdaptiveSelection(result.selections[0], adaptiveGroup, /* expectedTracks...= */ 1, 0);

    trackSelector.setParameters(
        defaultParameters
            .buildUpon()
            .setViewportSizeToPhysicalDisplaySize(/* viewportOrientationMayChange= */ false));
    result =
        trackSelector.selectTracks(
            new RendererCapabilities[] {VIDEO_CAPABILITIES}, trackGroups, periodId, TIMELINE);
    assertThat(result.length).isEqualTo(1);
    assertFixedSelection(result.selections[0], adaptiveGroup, /* expectedTrack= */ 0);
  }

  @Test
  public void
      selectTracks_withSingleTrackAndOffloadPreferenceEnabled_returnsRendererConfigOffloadModeEnabledGaplessRequired()
          throws Exception {
    Format formatAAC =
        new Format.Builder().setId("0").setSampleMimeType(MimeTypes.AUDIO_AAC).build();
    TrackGroupArray trackGroups = new TrackGroupArray(new TrackGroup(formatAAC));
    trackSelector.setParameters(
        trackSelector
            .buildUponParameters()
            .setAudioOffloadPreferences(
                new AudioOffloadPreferences.Builder()
                    .setAudioOffloadMode(AUDIO_OFFLOAD_MODE_ENABLED)
                    .setIsGaplessSupportRequired(true)
                    .setIsSpeedChangeSupportRequired(false)
                    .build())
            .build());
    RendererCapabilities capabilitiesOffloadSupport =
        new FakeRendererCapabilities(
            C.TRACK_TYPE_AUDIO,
            RendererCapabilities.create(
                FORMAT_HANDLED,
                ADAPTIVE_NOT_SEAMLESS,
                TUNNELING_NOT_SUPPORTED,
                AUDIO_OFFLOAD_SUPPORTED));

    TrackSelectorResult result =
        trackSelector.selectTracks(
            new RendererCapabilities[] {capabilitiesOffloadSupport},
            trackGroups,
            periodId,
            TIMELINE);

    assertFixedSelection(result.selections[0], trackGroups, formatAAC);
    assertThat(result.rendererConfigurations[0].offloadModePreferred)
        .isEqualTo(OFFLOAD_MODE_ENABLED_GAPLESS_REQUIRED);
  }

  @Test
  public void
      selectTracks_withMultipleAudioTracksAndOffloadPreferenceEnabled_returnsRendererConfigOffloadModeDisabled()
          throws Exception {
    Format.Builder formatBuilder = AUDIO_FORMAT.buildUpon();
    TrackGroupArray trackGroups =
        singleTrackGroup(formatBuilder.setId("0").build(), formatBuilder.setId("1").build());
    trackSelector.setParameters(
        trackSelector
            .buildUponParameters()
            .setAudioOffloadPreferences(
                new AudioOffloadPreferences.Builder()
                    .setAudioOffloadMode(AUDIO_OFFLOAD_MODE_ENABLED)
                    .setIsGaplessSupportRequired(true)
                    .setIsSpeedChangeSupportRequired(false)
                    .build())
            .build());
    RendererCapabilities capabilitiesOffloadSupport =
        new FakeRendererCapabilities(
            C.TRACK_TYPE_AUDIO,
            RendererCapabilities.create(
                FORMAT_HANDLED,
                ADAPTIVE_NOT_SEAMLESS,
                TUNNELING_NOT_SUPPORTED,
                AUDIO_OFFLOAD_SUPPORTED));

    TrackSelectorResult result =
        trackSelector.selectTracks(
            new RendererCapabilities[] {capabilitiesOffloadSupport},
            trackGroups,
            periodId,
            TIMELINE);

    assertThat(result.length).isEqualTo(1);
    assertAdaptiveSelection(result.selections[0], trackGroups.get(0), 0, 1);
    assertThat(result.rendererConfigurations[0].offloadModePreferred)
        .isEqualTo(OFFLOAD_MODE_DISABLED);
  }

  @Test
  public void
      selectTracks_withMultipleAudioTracksAndOffloadPreferenceRequired_returnsRendererConfigOffloadModeEnabledGaplessRequired()
          throws Exception {
    Format format1 = AUDIO_FORMAT.buildUpon().setId("0").build();
    Format format2 = AUDIO_FORMAT.buildUpon().setId("0").build();
    TrackGroupArray trackGroups = singleTrackGroup(format1, format2);
    trackSelector.setParameters(
        trackSelector
            .buildUponParameters()
            .setAudioOffloadPreferences(
                new AudioOffloadPreferences.Builder()
                    .setAudioOffloadMode(AUDIO_OFFLOAD_MODE_REQUIRED)
                    .setIsGaplessSupportRequired(true)
                    .setIsSpeedChangeSupportRequired(false)
                    .build())
            .build());
    RendererCapabilities capabilitiesOffloadSupport =
        new FakeRendererCapabilities(
            C.TRACK_TYPE_AUDIO,
            RendererCapabilities.create(
                FORMAT_HANDLED,
                ADAPTIVE_NOT_SEAMLESS,
                TUNNELING_NOT_SUPPORTED,
                AUDIO_OFFLOAD_SUPPORTED));

    TrackSelectorResult result =
        trackSelector.selectTracks(
            new RendererCapabilities[] {capabilitiesOffloadSupport},
            trackGroups,
            periodId,
            TIMELINE);

    assertThat(result.length).isEqualTo(1);
    assertFixedSelection(result.selections[0], trackGroups, format1);
    assertThat(result.rendererConfigurations[0].offloadModePreferred)
        .isEqualTo(OFFLOAD_MODE_ENABLED_GAPLESS_REQUIRED);
  }

  @Test
  public void
      selectTracks_withAudioAndVideoAndOffloadPreferenceEnabled_returnsRendererConfigOffloadModeDisabled()
          throws Exception {
    TrackGroupArray trackGroups =
        new TrackGroupArray(new TrackGroup(VIDEO_FORMAT), new TrackGroup(AUDIO_FORMAT));
    trackSelector.setParameters(
        trackSelector
            .buildUponParameters()
            .setAudioOffloadPreferences(
                new AudioOffloadPreferences.Builder()
                    .setAudioOffloadMode(AUDIO_OFFLOAD_MODE_ENABLED)
                    .setIsGaplessSupportRequired(false)
                    .setIsSpeedChangeSupportRequired(false)
                    .build())
            .build());
    RendererCapabilities audioCapabilities =
        new FakeRendererCapabilities(
            C.TRACK_TYPE_AUDIO,
            RendererCapabilities.create(
                FORMAT_HANDLED,
                ADAPTIVE_NOT_SEAMLESS,
                TUNNELING_NOT_SUPPORTED,
                AUDIO_OFFLOAD_SUPPORTED));

    TrackSelectorResult result =
        trackSelector.selectTracks(
            new RendererCapabilities[] {VIDEO_CAPABILITIES, audioCapabilities},
            trackGroups,
            periodId,
            TIMELINE);

    assertThat(result.length).isEqualTo(2);
    assertFixedSelection(result.selections[0], trackGroups, VIDEO_FORMAT);
    assertFixedSelection(result.selections[1], trackGroups, AUDIO_FORMAT);
    assertThat(result.rendererConfigurations[1].offloadModePreferred)
        .isEqualTo(OFFLOAD_MODE_DISABLED);
  }

  @Test
  public void
      selectTracks_withAudioAndVideoAndOffloadPreferenceRequired_returnsRendererConfigOffloadModeEnabledGaplessNotRequired()
          throws Exception {
    TrackGroupArray trackGroups =
        new TrackGroupArray(new TrackGroup(VIDEO_FORMAT), new TrackGroup(AUDIO_FORMAT));
    trackSelector.setParameters(
        trackSelector
            .buildUponParameters()
            .setAudioOffloadPreferences(
                new AudioOffloadPreferences.Builder()
                    .setAudioOffloadMode(AUDIO_OFFLOAD_MODE_REQUIRED)
                    .setIsGaplessSupportRequired(false)
                    .setIsSpeedChangeSupportRequired(false)
                    .build())
            .build());
    RendererCapabilities audioCapabilities =
        new FakeRendererCapabilities(
            C.TRACK_TYPE_AUDIO,
            RendererCapabilities.create(
                FORMAT_HANDLED,
                ADAPTIVE_NOT_SEAMLESS,
                TUNNELING_NOT_SUPPORTED,
                AUDIO_OFFLOAD_SUPPORTED));

    TrackSelectorResult result =
        trackSelector.selectTracks(
            new RendererCapabilities[] {VIDEO_CAPABILITIES, audioCapabilities},
            trackGroups,
            periodId,
            TIMELINE);

    assertThat(result.length).isEqualTo(2);
    assertThat(result.selections[0]).isNull();
    assertFixedSelection(result.selections[1], trackGroups, AUDIO_FORMAT);
    assertThat(result.rendererConfigurations[1].offloadModePreferred)
        .isEqualTo(OFFLOAD_MODE_ENABLED_GAPLESS_NOT_REQUIRED);
  }

  @Test
  public void
      selectTracks_gaplessTrackWithOffloadPreferenceGaplessRequired_returnsConfigOffloadDisabled()
          throws Exception {
    Format formatAac =
        new Format.Builder()
            .setId("0")
            .setSampleMimeType(MimeTypes.AUDIO_AAC)
            .setEncoderDelay(100)
            .build();
    TrackGroupArray trackGroups = new TrackGroupArray(new TrackGroup(formatAac));
    trackSelector.setParameters(
        trackSelector
            .buildUponParameters()
            .setAudioOffloadPreferences(
                new AudioOffloadPreferences.Builder()
                    .setAudioOffloadMode(AUDIO_OFFLOAD_MODE_ENABLED)
                    .setIsGaplessSupportRequired(true)
                    .setIsSpeedChangeSupportRequired(false)
                    .build())
            .build());
    // Offload playback without gapless transitions is supported
    RendererCapabilities capabilitiesOffloadSupport =
        new FakeRendererCapabilities(
            C.TRACK_TYPE_AUDIO,
            RendererCapabilities.create(
                FORMAT_HANDLED,
                ADAPTIVE_NOT_SEAMLESS,
                TUNNELING_NOT_SUPPORTED,
                AUDIO_OFFLOAD_SUPPORTED));

    TrackSelectorResult result =
        trackSelector.selectTracks(
            new RendererCapabilities[] {capabilitiesOffloadSupport},
            trackGroups,
            periodId,
            TIMELINE);

    assertThat(result.length).isEqualTo(1);
    assertFixedSelection(result.selections[0], trackGroups, formatAac);
    assertThat(result.rendererConfigurations[0].offloadModePreferred)
        .isEqualTo(OFFLOAD_MODE_DISABLED);
  }

  @Test
  public void
      selectTracks_gaplessTrackWithOffloadPreferenceGaplessRequiredWithGaplessSupport_returnsConfigOffloadModeEnabledGaplessRequired()
          throws Exception {
    Format formatAac =
        new Format.Builder()
            .setId("0")
            .setSampleMimeType(MimeTypes.AUDIO_AAC)
            .setEncoderDelay(100)
            .build();
    TrackGroupArray trackGroups = new TrackGroupArray(new TrackGroup(formatAac));
    trackSelector.setParameters(
        trackSelector
            .buildUponParameters()
            .setAudioOffloadPreferences(
                new AudioOffloadPreferences.Builder()
                    .setAudioOffloadMode(AUDIO_OFFLOAD_MODE_ENABLED)
                    .setIsGaplessSupportRequired(true)
                    .setIsSpeedChangeSupportRequired(false)
                    .build())
            .build());
    // Offload playback with gapless transitions is supported
    @SuppressWarnings("WrongConstant") // Combining these two values bit-wise is allowed
    RendererCapabilities capabilitiesOffloadSupport =
        new FakeRendererCapabilities(
            C.TRACK_TYPE_AUDIO,
            RendererCapabilities.create(
                FORMAT_HANDLED,
                ADAPTIVE_NOT_SEAMLESS,
                TUNNELING_NOT_SUPPORTED,
                /* audioOffloadSupport= */ AUDIO_OFFLOAD_SUPPORTED
                    | AUDIO_OFFLOAD_GAPLESS_SUPPORTED));

    TrackSelectorResult result =
        trackSelector.selectTracks(
            new RendererCapabilities[] {capabilitiesOffloadSupport},
            trackGroups,
            periodId,
            TIMELINE);

    assertThat(result.length).isEqualTo(1);
    assertFixedSelection(result.selections[0], trackGroups, formatAac);
    assertThat(result.rendererConfigurations[0].offloadModePreferred)
        .isEqualTo(OFFLOAD_MODE_ENABLED_GAPLESS_REQUIRED);
  }

  @Test
  public void
      selectTracks_gaplessTrackWithOffloadPreferenceRequiredGaplessRequired_returnsEmptySelection()
          throws Exception {
    Format formatAac =
        new Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC).setEncoderDelay(100).build();
    TrackGroupArray trackGroups = new TrackGroupArray(new TrackGroup(formatAac));
    trackSelector.setParameters(
        trackSelector
            .buildUponParameters()
            .setAudioOffloadPreferences(
                new AudioOffloadPreferences.Builder()
                    .setAudioOffloadMode(AUDIO_OFFLOAD_MODE_REQUIRED)
                    .setIsGaplessSupportRequired(true)
                    .setIsSpeedChangeSupportRequired(false)
                    .build())
            .build());
    // Offload playback without gapless transitions is supported
    RendererCapabilities capabilitiesOffloadSupport =
        new FakeRendererCapabilities(
            C.TRACK_TYPE_AUDIO,
            RendererCapabilities.create(
                FORMAT_HANDLED,
                ADAPTIVE_NOT_SEAMLESS,
                TUNNELING_NOT_SUPPORTED,
                AUDIO_OFFLOAD_SUPPORTED));

    TrackSelectorResult result =
        trackSelector.selectTracks(
            new RendererCapabilities[] {capabilitiesOffloadSupport},
            trackGroups,
            periodId,
            TIMELINE);

    assertThat(result.selections).hasLength(1);
    assertThat(result.selections[0]).isNull();
  }

  /**
   * Tests that track selector will select the video track with the highest number of matching role
   * flags given by {@link Parameters}.
   */
  @Test
  public void selectTracks_withPreferredVideoRoleFlags_selectPreferredTrack() throws Exception {
    Format.Builder formatBuilder = VIDEO_FORMAT.buildUpon();
    Format noRoleFlagsLow = formatBuilder.setAverageBitrate(4000).build();
    Format noRoleFlagsHigh = formatBuilder.setAverageBitrate(8000).build();
    Format lessRoleFlags = formatBuilder.setRoleFlags(C.ROLE_FLAG_CAPTION).build();
    Format moreRoleFlags =
        formatBuilder
            .setRoleFlags(C.ROLE_FLAG_CAPTION | C.ROLE_FLAG_COMMENTARY | C.ROLE_FLAG_DUB)
            .build();
    // Use an adaptive group to check that role flags have higher priority than number of tracks.
    TrackGroup adaptiveNoRoleFlagsGroup = new TrackGroup(noRoleFlagsLow, noRoleFlagsHigh);
    TrackGroupArray trackGroups =
        new TrackGroupArray(
            adaptiveNoRoleFlagsGroup, new TrackGroup(moreRoleFlags), new TrackGroup(lessRoleFlags));

    trackSelector.setParameters(
        defaultParameters
            .buildUpon()
            .setPreferredVideoRoleFlags(C.ROLE_FLAG_CAPTION | C.ROLE_FLAG_COMMENTARY));
    TrackSelectorResult result =
        trackSelector.selectTracks(
            new RendererCapabilities[] {VIDEO_CAPABILITIES}, trackGroups, periodId, TIMELINE);
    assertFixedSelection(result.selections[0], trackGroups, moreRoleFlags);

    // Also verify that exact match between parameters and tracks is preferred.
    trackSelector.setParameters(
        defaultParameters.buildUpon().setPreferredVideoRoleFlags(C.ROLE_FLAG_CAPTION));
    result =
        trackSelector.selectTracks(
            new RendererCapabilities[] {VIDEO_CAPABILITIES}, trackGroups, periodId, TIMELINE);
    assertFixedSelection(result.selections[0], trackGroups, lessRoleFlags);
  }

  @Test
  public void selectTracks_withPreferredAudioMimeTypes_selectsTrackWithPreferredMimeType()
      throws Exception {
    Format formatAac = new Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC).build();
    Format formatOpus = new Format.Builder().setSampleMimeType(MimeTypes.AUDIO_OPUS).build();
    Format formatFlac = new Format.Builder().setSampleMimeType(MimeTypes.AUDIO_FLAC).build();
    TrackGroupArray trackGroups = wrapFormats(formatAac, formatOpus, formatFlac);

    trackSelector.setParameters(
        trackSelector.buildUponParameters().setPreferredAudioMimeType(MimeTypes.AUDIO_OPUS));
    TrackSelectorResult result =
        trackSelector.selectTracks(
            new RendererCapabilities[] {AUDIO_CAPABILITIES}, trackGroups, periodId, TIMELINE);
    assertThat(result.length).isEqualTo(1);
    assertFixedSelection(result.selections[0], trackGroups, formatOpus);

    trackSelector.setParameters(
        trackSelector
            .buildUponParameters()
            .setPreferredAudioMimeTypes(MimeTypes.AUDIO_OPUS, MimeTypes.AUDIO_AAC));
    result =
        trackSelector.selectTracks(
            new RendererCapabilities[] {AUDIO_CAPABILITIES}, trackGroups, periodId, TIMELINE);
    assertThat(result.length).isEqualTo(1);
    assertFixedSelection(result.selections[0], trackGroups, formatOpus);

    trackSelector.setParameters(
        trackSelector
            .buildUponParameters()
            .setPreferredAudioMimeTypes(MimeTypes.AUDIO_AMR, MimeTypes.AUDIO_FLAC));
    result =
        trackSelector.selectTracks(
            new RendererCapabilities[] {AUDIO_CAPABILITIES}, trackGroups, periodId, TIMELINE);
    assertThat(result.length).isEqualTo(1);
    assertFixedSelection(result.selections[0], trackGroups, formatFlac);

    // Select first in the list if no preference is specified.
    trackSelector.setParameters(
        trackSelector.buildUponParameters().setPreferredAudioMimeType(null));
    result =
        trackSelector.selectTracks(
            new RendererCapabilities[] {AUDIO_CAPABILITIES}, trackGroups, periodId, TIMELINE);
    assertThat(result.length).isEqualTo(1);
    assertFixedSelection(result.selections[0], trackGroups, formatAac);
  }

  @Test
  public void selectTracks_withPreferredAudioLabel_selectsLabeledTrack() throws Exception {
    Format unlabeledAudio = AUDIO_FORMAT.buildUpon().setLabel(null).build();
    Format labeledAudio = AUDIO_FORMAT.buildUpon().setLabel("commentary").build();
    TrackGroupArray trackGroups = wrapFormats(unlabeledAudio, labeledAudio);
    RendererCapabilities[] rendererCapabilities =
        new RendererCapabilities[] {ALL_AUDIO_FORMAT_SUPPORTED_RENDERER_CAPABILITIES};

    trackSelector.setParameters(
        defaultParameters.buildUpon().setPreferredAudioLabels("commentary").build());
    TrackSelectorResult result =
        trackSelector.selectTracks(rendererCapabilities, trackGroups, periodId, TIMELINE);

    assertFixedSelection(result.selections[0], trackGroups, labeledAudio);
  }

  @Test
  public void selectTracks_withMultiplePreferredAudioLabels_selectsHigherPriorityLabeledTrack()
      throws Exception {
    Format lowPriorityLabeledAudio = AUDIO_FORMAT.buildUpon().setLabel("low_priority").build();
    Format highPriorityLabeledAudio = AUDIO_FORMAT.buildUpon().setLabel("high_priority").build();
    TrackGroupArray trackGroups = wrapFormats(lowPriorityLabeledAudio, highPriorityLabeledAudio);
    RendererCapabilities[] rendererCapabilities =
        new RendererCapabilities[] {ALL_AUDIO_FORMAT_SUPPORTED_RENDERER_CAPABILITIES};

    trackSelector.setParameters(
        defaultParameters
            .buildUpon()
            .setPreferredAudioLabels("high_priority", "low_priority")
            .build());
    TrackSelectorResult result =
        trackSelector.selectTracks(rendererCapabilities, trackGroups, periodId, TIMELINE);

    assertFixedSelection(result.selections[0], trackGroups, highPriorityLabeledAudio);
  }

  @Test
  public void selectTracks_roleFlagPreferredOverAudioLabel() throws Exception {
    Format labeledButWrongRoleFlag =
        AUDIO_FORMAT
            .buildUpon()
            .setRoleFlags(C.ROLE_FLAG_DESCRIBES_VIDEO)
            .setLabel("commentary")
            .build();
    Format unlabeledButRightRoleFlag =
        AUDIO_FORMAT.buildUpon().setRoleFlags(C.ROLE_FLAG_CAPTION).setLabel(null).build();
    TrackGroupArray trackGroups = wrapFormats(labeledButWrongRoleFlag, unlabeledButRightRoleFlag);
    RendererCapabilities[] rendererCapabilities =
        new RendererCapabilities[] {ALL_AUDIO_FORMAT_SUPPORTED_RENDERER_CAPABILITIES};

    trackSelector.setParameters(
        defaultParameters
            .buildUpon()
            .setPreferredAudioRoleFlags(C.ROLE_FLAG_CAPTION)
            .setPreferredAudioLabels("commentary")
            .build());
    TrackSelectorResult result =
        trackSelector.selectTracks(rendererCapabilities, trackGroups, periodId, TIMELINE);

    assertFixedSelection(result.selections[0], trackGroups, unlabeledButRightRoleFlag);
  }

  /**
   * Tests that the track selector will select a group with a single video track with a 'reasonable'
   * frame rate instead of a larger groups of tracks all with lower frame rates (the larger group of
   * tracks would normally be preferred).
   */
  @Test
  public void selectTracks_reasonableFrameRatePreferredOverTrackCount() throws Exception {
    Format.Builder formatBuilder = VIDEO_FORMAT.buildUpon();
    Format frameRateTooLow = formatBuilder.setFrameRate(5).build();
    Format frameRateAlsoTooLow = formatBuilder.setFrameRate(6).build();
    Format highEnoughFrameRate = formatBuilder.setFrameRate(30).build();
    // Use an adaptive group to check that frame rate has higher priority than number of tracks.
    TrackGroup adaptiveFrameRateTooLowGroup = new TrackGroup(frameRateTooLow, frameRateAlsoTooLow);
    TrackGroupArray trackGroups =
        new TrackGroupArray(adaptiveFrameRateTooLowGroup, new TrackGroup(highEnoughFrameRate));

    TrackSelectorResult result =
        trackSelector.selectTracks(
            new RendererCapabilities[] {VIDEO_CAPABILITIES}, trackGroups, periodId, TIMELINE);

    assertFixedSelection(result.selections[0], trackGroups, highEnoughFrameRate);
  }

  /**
   * Tests that the track selector will select the video track with a 'reasonable' frame rate that
   * has the best match on other attributes, instead of an otherwise preferred track with a lower
   * frame rate.
   */
  @Test
  public void selectTracks_reasonableFrameRatePreferredButNotHighestFrameRate() throws Exception {
    Format.Builder formatBuilder = VIDEO_FORMAT.buildUpon();
    Format frameRateUnsetHighRes =
        formatBuilder.setFrameRate(Format.NO_VALUE).setWidth(3840).setHeight(2160).build();
    Format frameRateTooLowHighRes =
        formatBuilder.setFrameRate(5).setWidth(3840).setHeight(2160).build();
    Format highEnoughFrameRateHighRes =
        formatBuilder.setFrameRate(30).setWidth(1920).setHeight(1080).build();
    Format highestFrameRateLowRes =
        formatBuilder.setFrameRate(60).setWidth(1280).setHeight(720).build();
    TrackGroupArray trackGroups =
        new TrackGroupArray(
            new TrackGroup(frameRateUnsetHighRes),
            new TrackGroup(frameRateTooLowHighRes),
            new TrackGroup(highestFrameRateLowRes),
            new TrackGroup(highEnoughFrameRateHighRes));

    TrackSelectorResult result =
        trackSelector.selectTracks(
            new RendererCapabilities[] {VIDEO_CAPABILITIES}, trackGroups, periodId, TIMELINE);

    assertFixedSelection(result.selections[0], trackGroups, highEnoughFrameRateHighRes);
  }

  /**
   * Tests that the track selector will select a track with {@link C#ROLE_FLAG_MAIN} with an
   * 'unreasonably low' frame rate, if the other track with a 'reasonable' frame rate is marked with
   * {@link C#ROLE_FLAG_ALTERNATE}. These role flags show an explicit signal from the media, so they
   * should be respected.
   */
  @Test
  public void selectTracks_roleFlagsOverrideReasonableFrameRate() throws Exception {
    Format.Builder formatBuilder = VIDEO_FORMAT.buildUpon();
    Format mainTrackWithLowFrameRate =
        formatBuilder.setFrameRate(3).setRoleFlags(C.ROLE_FLAG_MAIN).build();
    Format alternateTrackWithHighFrameRate =
        formatBuilder.setFrameRate(30).setRoleFlags(C.ROLE_FLAG_ALTERNATE).build();
    TrackGroupArray trackGroups =
        new TrackGroupArray(
            new TrackGroup(mainTrackWithLowFrameRate),
            new TrackGroup(alternateTrackWithHighFrameRate));

    TrackSelectorResult result =
        trackSelector.selectTracks(
            new RendererCapabilities[] {VIDEO_CAPABILITIES}, trackGroups, periodId, TIMELINE);

    assertFixedSelection(result.selections[0], trackGroups, mainTrackWithLowFrameRate);
  }

  /** Tests audio track selection when there are multiple audio renderers. */
  @Test
  public void selectTracks_multipleRenderer_allSelected() throws Exception {
    RendererCapabilities[] rendererCapabilities =
        new RendererCapabilities[] {VIDEO_CAPABILITIES, AUDIO_CAPABILITIES, AUDIO_CAPABILITIES};
    TrackGroupArray trackGroupArray = new TrackGroupArray(AUDIO_TRACK_GROUP);

    TrackSelectorResult result =
        trackSelector.selectTracks(rendererCapabilities, trackGroupArray, periodId, TIMELINE);

    assertThat(result.length).isEqualTo(3);
    assertThat(result.rendererConfigurations)
        .asList()
        .containsExactly(null, DEFAULT, null)
        .inOrder();
    assertThat(result.selections[0]).isNull();
    assertFixedSelection(
        result.selections[1], trackGroupArray, trackGroupArray.get(0).getFormat(0));
    assertThat(result.selections[2]).isNull();
    ImmutableList<Tracks.Group> trackGroups = result.tracks.getGroups();
    assertThat(trackGroups).hasSize(1);
    assertThat(trackGroups.get(0).getMediaTrackGroup()).isEqualTo(AUDIO_TRACK_GROUP);
    assertThat(trackGroups.get(0).isTrackSelected(0)).isTrue();
    assertThat(trackGroups.get(0).getTrackSupport(0)).isEqualTo(FORMAT_HANDLED);
  }

  /** Tests {@link SelectionOverride}'s bundle implementation. */
  @Test
  public void roundTripViaBundle_ofSelectionOverride_yieldsEqualInstance() {
    SelectionOverride selectionOverrideToBundle =
        new SelectionOverride(/* groupIndex= */ 1, /* tracks...= */ 2, 3);

    SelectionOverride selectionOverrideFromBundle =
        DefaultTrackSelector.SelectionOverride.fromBundle(selectionOverrideToBundle.toBundle());

    assertThat(selectionOverrideFromBundle).isEqualTo(selectionOverrideToBundle);
  }

  /**
   * {@link DefaultTrackSelector.Parameters.Builder} must override every method in {@link
   * TrackSelectionParameters.Builder} in order to 'fix' the return type to correctly allow chaining
   * the method calls.
   */
  @Test
  public void parametersBuilderOverridesAllTrackSelectionParametersBuilderMethods()
      throws Exception {
    List<Method> methods = TestUtil.getPublicMethods(TrackSelectionParameters.Builder.class);
    for (Method method : methods) {
      Method declaredMethod =
          Parameters.Builder.class.getDeclaredMethod(method.getName(), method.getParameterTypes());
      assertThat(declaredMethod.getDeclaringClass()).isEqualTo(Parameters.Builder.class);
      if (method.getReturnType().equals(TrackSelectionParameters.Builder.class)) {
        assertThat(declaredMethod.getReturnType()).isEqualTo(Parameters.Builder.class);
      }
    }
  }

  /**
   * The deprecated {@link DefaultTrackSelector.ParametersBuilder} is implemented by delegating to
   * an instance of {@link DefaultTrackSelector.Parameters.Builder}. However, it <b>also</b> extends
   * {@link TrackSelectionParameters.Builder}, and for the delegation-pattern to work correctly it
   * needs to override <b>every</b> setter method from the superclass (otherwise the setter won't be
   * propagated to the delegate). This test ensures that invariant. It also ensures the return type
   * is updated to correctly allow chaining the method calls.
   *
   * <p>The test can be removed when the deprecated {@link DefaultTrackSelector.ParametersBuilder}
   * is removed.
   */
  @SuppressWarnings("deprecation") // Testing deprecated builder
  @Test
  public void deprecatedParametersBuilderOverridesAllTrackSelectionParametersBuilderMethods()
      throws Exception {
    List<Method> methods = TestUtil.getPublicMethods(TrackSelectionParameters.Builder.class);
    for (Method method : methods) {
      Method declaredMethod =
          DefaultTrackSelector.ParametersBuilder.class.getDeclaredMethod(
              method.getName(), method.getParameterTypes());
      assertThat(declaredMethod.getDeclaringClass())
          .isEqualTo(DefaultTrackSelector.ParametersBuilder.class);
      if (method.getReturnType().equals(TrackSelectionParameters.Builder.class)) {
        assertThat(declaredMethod.getReturnType())
            .isEqualTo(DefaultTrackSelector.ParametersBuilder.class);
      }
    }
  }

  @Test
  public void onRendererCapabilitiesChangedWithDefaultParameters_notNotifyInvalidateListener() {
    Renderer renderer =
        new FakeAudioRenderer(
            /* handler= */ mock(HandlerWrapper.class),
            /* eventListener= */ mock(AudioRendererEventListener.class));

    trackSelector.onRendererCapabilitiesChanged(renderer);

    verify(invalidationListener, never()).onRendererCapabilitiesChanged(renderer);
  }

  @Test
  public void
      onRendererCapabilitiesChangedWithInvalidateSelectionsForRendererCapabilitiesChangeEnabled_notifyInvalidateListener() {
    trackSelector.setParameters(
        defaultParameters
            .buildUpon()
            .setAllowInvalidateSelectionsOnRendererCapabilitiesChange(true)
            .build());
    Renderer renderer =
        new FakeAudioRenderer(
            /* handler= */ mock(HandlerWrapper.class),
            /* eventListener= */ mock(AudioRendererEventListener.class));

    trackSelector.onRendererCapabilitiesChanged(renderer);

    verify(invalidationListener).onRendererCapabilitiesChanged(renderer);
  }

  @Test
  public void
      selectTracks_withImageAndVideoAndPrioritizeImageOverVideoEnabled_selectsOnlyImageTrack()
          throws Exception {
    TrackGroupArray trackGroups =
        new TrackGroupArray(new TrackGroup(IMAGE_FORMAT), new TrackGroup(VIDEO_FORMAT));
    trackSelector.setParameters(
        defaultParameters.buildUpon().setPrioritizeImageOverVideoEnabled(true).build());

    TrackSelectorResult result =
        trackSelector.selectTracks(
            new RendererCapabilities[] {VIDEO_CAPABILITIES, IMAGE_CAPABILITIES},
            trackGroups,
            periodId,
            TIMELINE);

    assertThat(result.length).isEqualTo(2);
    assertThat(result.selections[ /* video renderer index */0]).isNull();
    assertFixedSelection(
        result.selections[ /* image renderer index */1], trackGroups, IMAGE_FORMAT);
  }

  @Test
  public void selectTracks_withImageAndVideoTracksBothSupported_selectsOnlyVideoTrack()
      throws Exception {
    TrackGroupArray trackGroups =
        new TrackGroupArray(new TrackGroup(IMAGE_FORMAT), new TrackGroup(VIDEO_FORMAT));

    TrackSelectorResult result =
        trackSelector.selectTracks(
            new RendererCapabilities[] {VIDEO_CAPABILITIES, IMAGE_CAPABILITIES},
            trackGroups,
            periodId,
            TIMELINE);

    assertThat(result.length).isEqualTo(2);
    assertFixedSelection(
        result.selections[ /* video renderer index */0], trackGroups, VIDEO_FORMAT);
    assertThat(result.selections[ /* image renderer index */1]).isNull();
  }

  @Test
  public void selectTracks_withVideoAndImageAndOnlyImageSupported_selectsImageTrack()
      throws Exception {
    TrackGroupArray trackGroups =
        new TrackGroupArray(new TrackGroup(IMAGE_FORMAT), new TrackGroup(VIDEO_FORMAT));
    trackSelector.setParameters(
        defaultParameters.buildUpon().setExceedRendererCapabilitiesIfNecessary(false));

    TrackSelectorResult result =
        trackSelector.selectTracks(
            new RendererCapabilities[] {
              ALL_VIDEO_FORMAT_EXCEEDED_RENDERER_CAPABILITIES, IMAGE_CAPABILITIES
            },
            trackGroups,
            periodId,
            TIMELINE);

    assertThat(result.length).isEqualTo(2);
    assertThat(result.selections[ /* video renderer index */0]).isNull();
    assertFixedSelection(
        result.selections[ /* image renderer index */1], trackGroups, IMAGE_FORMAT);
  }

  @Test
  public void selectTracks_withVideoTrackOnlyAndPrioritizeImageOverVideoEnabled_selectsVideoTrack()
      throws Exception {
    TrackGroupArray trackGroups = new TrackGroupArray(new TrackGroup(VIDEO_FORMAT));
    trackSelector.setParameters(
        defaultParameters.buildUpon().setPrioritizeImageOverVideoEnabled(true).build());

    TrackSelectorResult result =
        trackSelector.selectTracks(
            new RendererCapabilities[] {VIDEO_CAPABILITIES, IMAGE_CAPABILITIES},
            trackGroups,
            periodId,
            TIMELINE);

    assertThat(result.length).isEqualTo(2);
    assertFixedSelection(
        result.selections[ /* video renderer index */0], trackGroups, VIDEO_FORMAT);
    assertThat(result.selections[ /* image renderer index */1]).isNull();
  }

  @Test
  public void selectTracks_withMultipleImageTracks_selectsHighestResolutionTrack()
      throws Exception {
    Format image1 = IMAGE_FORMAT.buildUpon().setWidth(320).setHeight(320).build();
    Format image2 = IMAGE_FORMAT.buildUpon().setWidth(480).setHeight(480).build();
    TrackGroupArray trackGroups = new TrackGroupArray(new TrackGroup(image1, image2));

    TrackSelectorResult result =
        trackSelector.selectTracks(
            new RendererCapabilities[] {IMAGE_CAPABILITIES}, trackGroups, periodId, TIMELINE);

    assertThat(result.length).isEqualTo(1);
    assertFixedSelection(result.selections[0], trackGroups, image2);
  }

  private static void assertSelections(TrackSelectorResult result, TrackSelection[] expected) {
    assertThat(result.length).isEqualTo(expected.length);
    for (int i = 0; i < expected.length; i++) {
      assertThat(result.selections[i]).isEqualTo(expected[i]);
    }
  }

  private static void assertFixedSelection(
      TrackSelection selection, TrackGroupArray trackGroups, Format expectedFormat) {
    int trackGroupIndex = -1;
    for (int i = 0; i < trackGroups.length; i++) {
      int expectedTrack = trackGroups.get(i).indexOf(expectedFormat);
      if (expectedTrack != -1) {
        assertThat(trackGroupIndex).isEqualTo(-1);
        assertFixedSelection(selection, trackGroups.get(i), expectedTrack);
        trackGroupIndex = i;
      }
    }
    // Assert that we found the expected format in a track group
    assertThat(trackGroupIndex).isNotEqualTo(-1);
  }

  private static void assertFixedSelection(
      TrackSelection selection, TrackGroup expectedTrackGroup, int expectedTrack) {
    assertThat(selection).isInstanceOf(FixedTrackSelection.class);
    assertThat(selection.getTrackGroup()).isEqualTo(expectedTrackGroup);
    assertThat(selection.length()).isEqualTo(1);
    assertThat(selection.getIndexInTrackGroup(0)).isEqualTo(expectedTrack);
    assertThat(selection.getFormat(0))
        .isSameInstanceAs(expectedTrackGroup.getFormat(selection.getIndexInTrackGroup(0)));
  }

  private static void assertNoSelection(TrackSelection selection) {
    assertThat(selection).isNull();
  }

  private static void assertAdaptiveSelection(
      TrackSelection selection, TrackGroup expectedTrackGroup, int... expectedTracks) {
    assertThat(selection).isInstanceOf(AdaptiveTrackSelection.class);
    assertThat(selection.getTrackGroup()).isEqualTo(expectedTrackGroup);
    assertThat(selection.length()).isEqualTo(expectedTracks.length);
    for (int i = 0; i < expectedTracks.length; i++) {
      assertThat(selection.getIndexInTrackGroup(i)).isEqualTo(expectedTracks[i]);
      assertThat(selection.getFormat(i))
          .isSameInstanceAs(expectedTrackGroup.getFormat(selection.getIndexInTrackGroup(i)));
    }
  }

  private static TrackGroupArray singleTrackGroup(Format... formats) {
    return new TrackGroupArray(new TrackGroup(formats));
  }

  private static TrackGroupArray wrapFormats(Format... formats) {
    TrackGroup[] trackGroups = new TrackGroup[formats.length];
    for (int i = 0; i < trackGroups.length; i++) {
      trackGroups[i] = new TrackGroup(formats[i]);
    }
    return new TrackGroupArray(trackGroups);
  }

  private static Format buildAudioFormatWithConfiguration(
      String id, int channelCount, String mimeType, int sampleRate) {
    return new Format.Builder()
        .setId(id)
        .setSampleMimeType(mimeType)
        .setChannelCount(channelCount)
        .setSampleRate(sampleRate)
        .setAverageBitrate(128000)
        .build();
  }

  /**
   * Returns {@link Parameters} suitable for simple round trip equality tests.
   *
   * <p>Primitive variables are set to different values (to the extent that this is possible), to
   * increase the probability of such tests failing if they accidentally compare mismatched
   * variables.
   */
  private static Parameters buildParametersForEqualsTest() {
    return Parameters.DEFAULT
        .buildUpon()
        // Video
        .setMaxVideoSize(/* maxVideoWidth= */ 0, /* maxVideoHeight= */ 1)
        .setMaxVideoFrameRate(2)
        .setMaxVideoBitrate(3)
        .setMinVideoSize(/* minVideoWidth= */ 4, /* minVideoHeight= */ 5)
        .setMinVideoFrameRate(6)
        .setMinVideoBitrate(7)
        .setExceedVideoConstraintsIfNecessary(false)
        .setAllowVideoMixedMimeTypeAdaptiveness(true)
        .setAllowVideoNonSeamlessAdaptiveness(false)
        .setAllowVideoMixedDecoderSupportAdaptiveness(true)
        .setViewportSize(
            /* viewportWidth= */ 8,
            /* viewportHeight= */ 9,
            /* viewportOrientationMayChange= */ true)
        .setPreferredVideoMimeTypes(MimeTypes.VIDEO_AV1, MimeTypes.VIDEO_H264)
        .setPreferredVideoLabels("video_label_1", "video_label_2")
        // Audio
        .setPreferredAudioLanguages("zh", "jp")
        .setPreferredAudioRoleFlags(C.ROLE_FLAG_COMMENTARY)
        .setPreferredAudioLabels("audio_label_1", "audio_label_2")
        .setMaxAudioChannelCount(10)
        .setMaxAudioBitrate(11)
        .setExceedAudioConstraintsIfNecessary(false)
        .setAllowAudioMixedMimeTypeAdaptiveness(true)
        .setAllowAudioMixedSampleRateAdaptiveness(false)
        .setAllowAudioMixedChannelCountAdaptiveness(true)
        .setAllowAudioMixedDecoderSupportAdaptiveness(false)
        .setPreferredAudioMimeTypes(MimeTypes.AUDIO_AC3, MimeTypes.AUDIO_E_AC3)
        .setConstrainAudioChannelCountToDeviceCapabilities(false)
        // Text
        .setSelectTextByDefault(true)
        .setPreferredTextLanguages("de", "en")
        .setPreferredTextRoleFlags(C.ROLE_FLAG_CAPTION)
        .setSelectUndeterminedTextLanguage(true)
        .setIgnoredTextSelectionFlags(C.SELECTION_FLAG_AUTOSELECT)
        .setPreferredTextLabels("text_label_1", "text_label_2")
        // General
        .setForceLowestBitrate(false)
        .setForceHighestSupportedBitrate(true)
        .setExceedRendererCapabilitiesIfNecessary(false)
        .setTunnelingEnabled(true)
        .setAllowMultipleAdaptiveSelections(true)
        .setAllowInvalidateSelectionsOnRendererCapabilitiesChange(false)
        .setSelectionOverride(
            /* rendererIndex= */ 2,
            new TrackGroupArray(VIDEO_TRACK_GROUP),
            new SelectionOverride(0, 1))
        .setSelectionOverride(
            /* rendererIndex= */ 2, new TrackGroupArray(AUDIO_TRACK_GROUP), /* override= */ null)
        .setSelectionOverride(
            /* rendererIndex= */ 5, new TrackGroupArray(VIDEO_TRACK_GROUP), /* override= */ null)
        .setRendererDisabled(1, true)
        .setRendererDisabled(3, true)
        .setRendererDisabled(5, false)
        .setOverrideForType(
            new TrackSelectionOverride(
                new TrackGroup(AUDIO_FORMAT, AUDIO_FORMAT, AUDIO_FORMAT, AUDIO_FORMAT),
                /* trackIndices= */ ImmutableList.of(0, 2, 3)))
        .setDisabledTrackTypes(ImmutableSet.of(C.TRACK_TYPE_AUDIO))
        .build();
  }

  /**
   * A {@link RendererCapabilities} that advertises support for all formats of a given type using a
   * provided support value. For any format that does not have the given track type, {@link
   * #supportsFormat(Format)} will return {@link C#FORMAT_UNSUPPORTED_TYPE}.
   */
  private static final class FakeRendererCapabilities implements RendererCapabilities {

    private final int trackType;
    private final @Capabilities int supportValue;

    /**
     * Returns {@link FakeRendererCapabilities} that advertises adaptive support for all tracks of
     * the given type.
     *
     * @param trackType the track type of all formats that this renderer capabilities advertises
     *     support for.
     */
    FakeRendererCapabilities(int trackType) {
      this(
          trackType,
          RendererCapabilities.create(FORMAT_HANDLED, ADAPTIVE_SEAMLESS, TUNNELING_NOT_SUPPORTED));
    }

    /**
     * Returns {@link FakeRendererCapabilities} that advertises support level using given value for
     * all tracks of the given type.
     *
     * @param trackType the track type of all formats that this renderer capabilities advertises
     *     support for.
     * @param supportValue the {@link Capabilities} that will be returned for formats with the given
     *     type.
     */
    FakeRendererCapabilities(int trackType, @Capabilities int supportValue) {
      this.trackType = trackType;
      this.supportValue = supportValue;
    }

    @Override
    public String getName() {
      return "FakeRenderer(" + Util.getTrackTypeString(trackType) + ")";
    }

    @Override
    public int getTrackType() {
      return trackType;
    }

    @Override
    public @Capabilities int supportsFormat(Format format) {
      return MimeTypes.getTrackType(format.sampleMimeType) == trackType
          ? supportValue
          : RendererCapabilities.create(C.FORMAT_UNSUPPORTED_TYPE);
    }

    @Override
    public @AdaptiveSupport int supportsMixedMimeTypeAdaptation() {
      return ADAPTIVE_SEAMLESS;
    }
  }

  /**
   * A {@link RendererCapabilities} that advertises support for different formats using a mapping
   * between format ID and format-support value.
   */
  private static final class FakeMappedRendererCapabilities implements RendererCapabilities {

    private final int trackType;
    private final Map<String, Integer> formatToCapability;

    /**
     * Returns {@link FakeRendererCapabilities} that advertises support level using the given
     * mapping between format ID and format-support value.
     *
     * @param trackType the track type to be returned for {@link #getTrackType()}
     * @param formatToCapability a map of (format id, support level) that will be used to return
     *     support level for any given format. For any format that's not in the map, {@link
     *     #supportsFormat(Format)} will return {@link C#FORMAT_UNSUPPORTED_TYPE}.
     */
    FakeMappedRendererCapabilities(int trackType, Map<String, Integer> formatToCapability) {
      this.trackType = trackType;
      this.formatToCapability = new HashMap<>(formatToCapability);
    }

    @Override
    public String getName() {
      return "FakeRenderer(" + Util.getTrackTypeString(trackType) + ")";
    }

    @Override
    public int getTrackType() {
      return trackType;
    }

    @Override
    public @Capabilities int supportsFormat(Format format) {
      return format.id != null && formatToCapability.containsKey(format.id)
          ? formatToCapability.get(format.id)
          : RendererCapabilities.create(C.FORMAT_UNSUPPORTED_TYPE);
    }

    @Override
    public @AdaptiveSupport int supportsMixedMimeTypeAdaptation() {
      return ADAPTIVE_SEAMLESS;
    }
  }
}
