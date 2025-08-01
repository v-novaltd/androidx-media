/*
 * Copyright 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.media3.extractor.mp4;

import static com.google.common.truth.Truth.assertThat;

import android.content.Context;
import androidx.media3.common.Format;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.PositionHolder;
import androidx.media3.extractor.SniffFailure;
import androidx.media3.extractor.text.DefaultSubtitleParserFactory;
import androidx.media3.extractor.text.SubtitleParser;
import androidx.media3.test.utils.DumpFileAsserts;
import androidx.media3.test.utils.FakeExtractorInput;
import androidx.media3.test.utils.FakeExtractorOutput;
import androidx.media3.test.utils.FakeTrackOutput;
import androidx.media3.test.utils.TestUtil;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import java.io.IOException;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Non-parameterized tests for {@link Mp4Extractor}. */
@RunWith(AndroidJUnit4.class)
public final class Mp4ExtractorNonParameterizedTest {

  @Test
  public void sniff_reportsUnsupportedBrandsFailure() throws Exception {
    Mp4Extractor extractor = new Mp4Extractor(SubtitleParser.Factory.UNSUPPORTED);
    FakeExtractorInput input = createInputForSample("sample_unsupported_brands.mp4");

    boolean sniffResult = extractor.sniff(input);
    ImmutableList<SniffFailure> sniffFailures = extractor.getSniffFailureDetails();

    assertThat(sniffResult).isFalse();
    SniffFailure sniffFailure = Iterables.getOnlyElement(sniffFailures);
    assertThat(sniffFailure).isInstanceOf(UnsupportedBrandsSniffFailure.class);
    UnsupportedBrandsSniffFailure unsupportedBrandsSniffFailure =
        (UnsupportedBrandsSniffFailure) sniffFailure;
    assertThat(unsupportedBrandsSniffFailure.majorBrand).isEqualTo(1767992930);
    assertThat(unsupportedBrandsSniffFailure.compatibleBrands.asList())
        .containsExactly(1919903851, 1835102819, 1953459817, 1801548922)
        .inOrder();
  }

  @Test
  public void sniff_reportsWrongFragmentationFailure() throws Exception {
    Mp4Extractor extractor = new Mp4Extractor(SubtitleParser.Factory.UNSUPPORTED);
    FakeExtractorInput input = createInputForSample("sample_fragmented.mp4");

    boolean sniffResult = extractor.sniff(input);
    ImmutableList<SniffFailure> sniffFailures = extractor.getSniffFailureDetails();

    assertThat(sniffResult).isFalse();
    SniffFailure sniffFailure = Iterables.getOnlyElement(sniffFailures);
    assertThat(sniffFailure).isInstanceOf(IncorrectFragmentationSniffFailure.class);
    IncorrectFragmentationSniffFailure incorrectFragmentationSniffFailure =
        (IncorrectFragmentationSniffFailure) sniffFailure;
    assertThat(incorrectFragmentationSniffFailure.fileIsFragmented).isTrue();
  }

  @Test
  public void getSeekPoints_withEmptyTracks_returnsValidInformation() throws Exception {
    Mp4Extractor extractor = new Mp4Extractor(SubtitleParser.Factory.UNSUPPORTED);
    FakeExtractorInput input = createInputForSample("sample_empty_track.mp4");
    FakeExtractorOutput output =
        new FakeExtractorOutput(
            (id, type) -> new FakeTrackOutput(/* deduplicateConsecutiveFormats= */ true));
    PositionHolder seekPositionHolder = new PositionHolder();
    extractor.init(output);
    int readResult = Extractor.RESULT_CONTINUE;
    while (readResult != Extractor.RESULT_END_OF_INPUT) {
      readResult = extractor.read(input, seekPositionHolder);
      if (readResult == Extractor.RESULT_SEEK) {
        long seekPosition = seekPositionHolder.position;
        input.setPosition((int) seekPosition);
      }
    }
    ImmutableList.Builder<Long> trackSeekTimesUs = ImmutableList.builder();
    long testPositionUs = output.seekMap.getDurationUs() / 2;

    for (int i = 0; i < output.numberOfTracks; i++) {
      int trackId = output.trackOutputs.keyAt(i);
      trackSeekTimesUs.add(extractor.getSeekPoints(testPositionUs, trackId).first.timeUs);
    }
    long extractorSeekTimeUs = extractor.getSeekPoints(testPositionUs).first.timeUs;

    assertThat(output.numberOfTracks).isEqualTo(2);
    assertThat(extractorSeekTimeUs).isIn(trackSeekTimesUs.build());
  }

  @Test
  public void
      extract_fileHavingNoAuxiliaryTracksWithReadAuxiliaryTracksFlag_extractsPrimaryVideoTracks()
          throws Exception {
    Context context = ApplicationProvider.getApplicationContext();
    String inputFilePath = "media/mp4/sample.mp4";
    Mp4Extractor mp4Extractor =
        new Mp4Extractor(
            new DefaultSubtitleParserFactory(), Mp4Extractor.FLAG_READ_AUXILIARY_TRACKS);

    FakeExtractorOutput primaryTracksOutput =
        TestUtil.extractAllSamplesFromFile(mp4Extractor, context, inputFilePath);

    String dumpFilePath = getDumpFilePath(inputFilePath, "_with_flag_read_auxiliary_tracks");
    DumpFileAsserts.assertOutput(context, primaryTracksOutput, dumpFilePath);
  }

  @Test
  public void extract_fileHavingAuxiliaryTracksWithReadAuxiliaryTracksFlag_extractsAuxiliaryTracks()
      throws Exception {
    Context context = ApplicationProvider.getApplicationContext();
    String inputFilePath = "media/mp4/sample_with_fake_auxiliary_tracks.mp4";
    Mp4Extractor mp4Extractor =
        new Mp4Extractor(
            new DefaultSubtitleParserFactory(), Mp4Extractor.FLAG_READ_AUXILIARY_TRACKS);

    FakeExtractorOutput auxiliaryTracksOutput =
        TestUtil.extractAllSamplesFromFile(mp4Extractor, context, inputFilePath);

    String dumpFilePath = getDumpFilePath(inputFilePath, "_with_flag_read_auxiliary_tracks");
    DumpFileAsserts.assertOutput(context, auxiliaryTracksOutput, dumpFilePath);
  }

  @Test
  public void
      extract_fileHavingAuxiliaryTracksInterleavedWithPrimaryVideoTracksWithReadAuxiliaryTracksFlag_extractsAuxiliaryTracks()
          throws Exception {
    Context context = ApplicationProvider.getApplicationContext();
    String inputFilePath =
        "media/mp4/sample_with_fake_auxiliary_tracks_interleaved_with_primary_video_tracks.mp4";
    Mp4Extractor mp4Extractor =
        new Mp4Extractor(
            new DefaultSubtitleParserFactory(), Mp4Extractor.FLAG_READ_AUXILIARY_TRACKS);

    FakeExtractorOutput auxiliaryTracksOutput =
        TestUtil.extractAllSamplesFromFile(mp4Extractor, context, inputFilePath);

    String dumpFilePath = getDumpFilePath(inputFilePath, "_with_flag_read_auxiliary_tracks");
    DumpFileAsserts.assertOutput(context, auxiliaryTracksOutput, dumpFilePath);
  }

  @Test
  public void extract_withOmitTrackSampleTableFlag_extractsCorrectMetadata() throws Exception {
    Context context = ApplicationProvider.getApplicationContext();
    String inputFilePath = "media/mp4/sample.mp4";
    Mp4Extractor mp4Extractor =
        new Mp4Extractor(
            new DefaultSubtitleParserFactory(),
            /* flags= */ Mp4Extractor.FLAG_OMIT_TRACK_SAMPLE_TABLE);

    FakeExtractorOutput output =
        TestUtil.extractAllSamplesFromFile(mp4Extractor, context, inputFilePath);

    assertThat(output.seekMap).isNotNull();
    assertThat(output.seekMap.getDurationUs()).isEqualTo(1024000);
    assertThat(output.seekMap.isSeekable()).isTrue();
    assertThat(output.numberOfTracks).isEqualTo(2);

    // Check Video Track Format
    Format videoFormat = output.trackOutputs.get(0).lastFormat;
    assertThat(videoFormat.sampleMimeType).isEqualTo("video/avc");
    assertThat(videoFormat.width).isEqualTo(1080);
    assertThat(videoFormat.height).isEqualTo(720);

    // Check Audio Track Format
    Format audioFormat = output.trackOutputs.get(1).lastFormat;
    assertThat(audioFormat.sampleMimeType).isEqualTo("audio/mp4a-latm");
    assertThat(audioFormat.channelCount).isEqualTo(1);
    assertThat(audioFormat.sampleRate).isEqualTo(44100);

    // Importantly, check that no actual sample data was output
    assertThat(output.trackOutputs.get(0).getSampleCount()).isEqualTo(0);
    assertThat(output.trackOutputs.get(1).getSampleCount()).isEqualTo(0);
  }

  private static String getDumpFilePath(String inputFilePath, String suffix) {
    return inputFilePath.replaceFirst("media", "extractordumps") + suffix;
  }

  private static FakeExtractorInput createInputForSample(String sample) throws IOException {
    return new FakeExtractorInput.Builder()
        .setData(
            TestUtil.getByteArray(
                ApplicationProvider.getApplicationContext(), "media/mp4/" + sample))
        .build();
  }
}
