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
package androidx.media3.exoplayer.audio;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.media.AudioFormat;
import android.media.AudioTrack;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.util.Util;
import androidx.media3.test.utils.FakeClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit tests for {@link AudioTrackPositionTracker}. */
@RunWith(AndroidJUnit4.class)
public class AudioTrackPositionTrackerTest {
  private static final android.media.AudioAttributes AUDIO_ATTRIBUTES =
      AudioAttributes.DEFAULT.getPlatformAudioAttributes();
  private static final int BYTES_PER_FRAME_16_BIT = 2;
  private static final int CHANNEL_COUNT_STEREO = 2;
  private static final int OUTPUT_PCM_FRAME_SIZE =
      Util.getPcmFrameSize(C.ENCODING_PCM_16BIT, CHANNEL_COUNT_STEREO);
  private static final int SAMPLE_RATE = 44100;
  private static final long START_TIME_MS = 9999L;
  private static final long TIME_TO_ADVANCE_MS = 1000L;
  private static final int MIN_BUFFER_SIZE =
      AudioTrack.getMinBufferSize(
          SAMPLE_RATE, Util.getAudioTrackChannelConfig(CHANNEL_COUNT_STEREO), C.ENCODING_PCM_16BIT);
  private static final AudioFormat AUDIO_FORMAT =
      Util.getAudioFormat(
          SAMPLE_RATE, Util.getAudioTrackChannelConfig(CHANNEL_COUNT_STEREO), C.ENCODING_PCM_16BIT);

  private final AudioTrackPositionTracker audioTrackPositionTracker =
      new AudioTrackPositionTracker(mock(AudioTrackPositionTracker.Listener.class));
  private final AudioTrack audioTrack = createDefaultAudioTrack();
  private final FakeClock clock =
      new FakeClock(/* initialTimeMs= */ START_TIME_MS, /* isAutoAdvancing= */ true);

  @Before
  public void setUp() {
    audioTrackPositionTracker.setClock(clock);
  }

  @Test
  public void
      getCurrentPositionUs_withoutExpectRawPlaybackHeadReset_returnsPositionWithWrappedValue() {
    audioTrackPositionTracker.setAudioTrack(
        audioTrack,
        C.ENCODING_PCM_16BIT,
        OUTPUT_PCM_FRAME_SIZE,
        MIN_BUFFER_SIZE,
        /* enableOnAudioPositionAdvancingFix= */ true);
    audioTrackPositionTracker.start();
    audioTrack.play();
    // Advance and write to audio track at least twice to move rawHeadPosition past wrap point.
    for (int i = 0; i < 2; i++) {
      writeBytesAndAdvanceTime(audioTrack);
      audioTrackPositionTracker.getCurrentPositionUs();
    }

    // Reset audio track and write bytes to simulate position overflow.
    audioTrack.flush();
    writeBytesAndAdvanceTime(audioTrack);

    assertThat(audioTrackPositionTracker.getCurrentPositionUs()).isGreaterThan(4294967296L);
  }

  @Test
  public void getCurrentPositionUs_withExpectRawPlaybackHeadReset_returnsAccumulatedPosition() {
    audioTrackPositionTracker.setAudioTrack(
        audioTrack,
        C.ENCODING_PCM_16BIT,
        OUTPUT_PCM_FRAME_SIZE,
        MIN_BUFFER_SIZE,
        /* enableOnAudioPositionAdvancingFix= */ true);
    audioTrackPositionTracker.start();
    audioTrack.play();
    // Advance and write to audio track at least twice to move rawHeadPosition past wrap point.
    for (int i = 0; i < 2; i++) {
      writeBytesAndAdvanceTime(audioTrack);
      audioTrackPositionTracker.getCurrentPositionUs();
    }

    // Reset audio track to simulate track reuse and transition.
    // Set tracker to expect playback head reset.
    audioTrack.flush();
    audioTrackPositionTracker.expectRawPlaybackHeadReset();
    writeBytesAndAdvanceTime(audioTrack);

    // Expected position is msToUs(# of writes)*TIME_TO_ADVANCE_MS.
    assertThat(audioTrackPositionTracker.getCurrentPositionUs()).isEqualTo(3000000L);
  }

  @Test
  public void getCurrentPositionUs_withExpectPositionResetThenPause_returnsCorrectPosition() {
    audioTrackPositionTracker.setAudioTrack(
        audioTrack,
        C.ENCODING_PCM_16BIT,
        OUTPUT_PCM_FRAME_SIZE,
        MIN_BUFFER_SIZE,
        /* enableOnAudioPositionAdvancingFix= */ true);
    audioTrackPositionTracker.start();
    audioTrack.play();
    // Advance and write to audio track at least twice to move rawHeadPosition past wrap point.
    for (int i = 0; i < 2; i++) {
      writeBytesAndAdvanceTime(audioTrack);
      audioTrackPositionTracker.getCurrentPositionUs();
    }
    // Reset audio track to simulate track transition and set tracker to expect playback head reset.
    audioTrack.flush();
    audioTrackPositionTracker.expectRawPlaybackHeadReset();
    writeBytesAndAdvanceTime(audioTrack);
    assertThat(audioTrackPositionTracker.getCurrentPositionUs()).isEqualTo(3000000L);

    // Pause tracker, pause audio track, and advance time to test that position does not change
    // during pause
    audioTrackPositionTracker.pause();
    audioTrack.pause();
    clock.advanceTime(TIME_TO_ADVANCE_MS);

    // Expected position is msToUs(# of writes)*TIME_TO_ADVANCE_MS.
    assertThat(audioTrackPositionTracker.getCurrentPositionUs()).isEqualTo(3000000L);
  }

  @Test
  public void getCurrentPositionUs_withSetAudioTrackResettingSumPosition_returnsCorrectPosition() {
    AudioTrack audioTrack1 = createDefaultAudioTrack();
    AudioTrack audioTrack2 = createDefaultAudioTrack();
    audioTrackPositionTracker.setAudioTrack(
        audioTrack1,
        C.ENCODING_PCM_16BIT,
        OUTPUT_PCM_FRAME_SIZE,
        MIN_BUFFER_SIZE,
        /* enableOnAudioPositionAdvancingFix= */ true);
    audioTrackPositionTracker.start();
    audioTrack1.play();
    // Advance and write to audio track at least twice to move rawHeadPosition past wrap point.
    for (int i = 0; i < 2; i++) {
      writeBytesAndAdvanceTime(audioTrack1);
      audioTrackPositionTracker.getCurrentPositionUs();
    }
    // Reset audio track and set tracker to expect playback head reset to simulate track transition.
    audioTrack1.flush();
    audioTrackPositionTracker.expectRawPlaybackHeadReset();
    writeBytesAndAdvanceTime(audioTrack1);
    // Test for correct setup with current position being accumulated position.
    assertThat(audioTrackPositionTracker.getCurrentPositionUs()).isEqualTo(3000000L);

    // Set new audio track and reset position tracker to simulate transition to new AudioTrack.
    audioTrackPositionTracker.reset();
    audioTrackPositionTracker.setAudioTrack(
        audioTrack2,
        C.ENCODING_PCM_16BIT,
        OUTPUT_PCM_FRAME_SIZE,
        MIN_BUFFER_SIZE,
        /* enableOnAudioPositionAdvancingFix= */ true);
    audioTrackPositionTracker.start();
    audioTrack2.play();
    writeBytesAndAdvanceTime(audioTrack2);

    // Expected position is msToUs(1 write)*TIME_TO_ADVANCE_MS.
    assertThat(audioTrackPositionTracker.getCurrentPositionUs()).isEqualTo(1000000L);
  }

  @Test
  public void getCurrentPositionUs_withSetAudioTrackResetsExpectPosition_returnsWrappedValue() {
    // Set tracker to expect playback head reset to simulate expected track transition.
    audioTrackPositionTracker.expectRawPlaybackHeadReset();
    audioTrackPositionTracker.setAudioTrack(
        audioTrack,
        C.ENCODING_PCM_16BIT,
        OUTPUT_PCM_FRAME_SIZE,
        MIN_BUFFER_SIZE,
        /* enableOnAudioPositionAdvancingFix= */ true);
    audioTrackPositionTracker.start();
    audioTrack.play();

    // Advance and write to audio track at least twice to move rawHeadPosition past wrap point.
    for (int i = 0; i < 2; i++) {
      writeBytesAndAdvanceTime(audioTrack);
      audioTrackPositionTracker.getCurrentPositionUs();
    }
    // Reset audio track and write bytes to simulate position overflow.
    audioTrack.flush();
    writeBytesAndAdvanceTime(audioTrack);

    assertThat(audioTrackPositionTracker.getCurrentPositionUs()).isGreaterThan(4294967296L);
  }

  @Test
  public void getCurrentPositionUs_afterHandleEndOfStreamWithPause_returnsCorrectPosition() {
    audioTrackPositionTracker.setAudioTrack(
        audioTrack,
        C.ENCODING_PCM_16BIT,
        OUTPUT_PCM_FRAME_SIZE,
        MIN_BUFFER_SIZE,
        /* enableOnAudioPositionAdvancingFix= */ true);
    audioTrackPositionTracker.start();
    audioTrack.play();
    for (int i = 0; i < 2; i++) {
      writeBytesAndAdvanceTime(audioTrack);
    }
    audioTrackPositionTracker.handleEndOfStream(2_000_000L);

    audioTrackPositionTracker.pause();
    audioTrack.pause();
    // Advance time during paused state.
    clock.advanceTime(2_000L);

    assertThat(audioTrackPositionTracker.getCurrentPositionUs()).isEqualTo(2_000_000L);
  }

  @Test
  public void getCurrentPositionUs_afterHandleEndOfStreamWithPausePlay_returnsCorrectPosition() {
    audioTrackPositionTracker.setAudioTrack(
        audioTrack,
        C.ENCODING_PCM_16BIT,
        OUTPUT_PCM_FRAME_SIZE,
        MIN_BUFFER_SIZE,
        /* enableOnAudioPositionAdvancingFix= */ true);
    audioTrackPositionTracker.start();
    audioTrack.play();
    for (int i = 0; i < 2; i++) {
      writeBytesAndAdvanceTime(audioTrack);
    }
    // Provide Long.MAX_VALUE so that tracker relies on estimation and not total duration.
    audioTrackPositionTracker.handleEndOfStream(Long.MAX_VALUE);

    audioTrackPositionTracker.pause();
    audioTrack.pause();
    // Advance time during paused state.
    clock.advanceTime(2_000L);
    audioTrackPositionTracker.start();
    audioTrack.play();

    assertThat(audioTrackPositionTracker.getCurrentPositionUs()).isEqualTo(2_000_000L);
  }

  @Test
  public void onPositionAdvancing_isTriggeredWhenPlaying() {
    AudioTrackPositionTracker.Listener listener = mock(AudioTrackPositionTracker.Listener.class);
    AudioTrackPositionTracker audioTrackPositionTracker = new AudioTrackPositionTracker(listener);
    audioTrackPositionTracker.setClock(clock);
    audioTrackPositionTracker.setAudioTrack(
        audioTrack,
        C.ENCODING_PCM_16BIT,
        OUTPUT_PCM_FRAME_SIZE,
        MIN_BUFFER_SIZE,
        /* enableOnAudioPositionAdvancingFix= */ true);
    // Start the tracker to set the initial position for advancing check.
    audioTrackPositionTracker.start();
    audioTrack.play();
    // Write data to advance the position.
    writeBytesAndAdvanceTime(audioTrack);

    // Call getCurrentPositionUs() to request an update.
    audioTrackPositionTracker.getCurrentPositionUs();

    verify(listener).onPositionAdvancing(anyLong());
  }

  @Test
  public void onPositionAdvancing_isNotTriggeredWhenPaused() {
    AudioTrackPositionTracker.Listener listener = mock(AudioTrackPositionTracker.Listener.class);
    AudioTrackPositionTracker audioTrackPositionTracker = new AudioTrackPositionTracker(listener);
    audioTrackPositionTracker.setClock(clock);
    audioTrackPositionTracker.setAudioTrack(
        audioTrack,
        C.ENCODING_PCM_16BIT,
        OUTPUT_PCM_FRAME_SIZE,
        MIN_BUFFER_SIZE,
        /* enableOnAudioPositionAdvancingFix= */ true);
    // Start the tracker to set the initial position for advancing check.
    audioTrackPositionTracker.start();
    audioTrack.play();
    // Write data to advance the position.
    writeBytesAndAdvanceTime(audioTrack);
    // Pause the tracker and audio track.
    audioTrackPositionTracker.pause();
    audioTrack.pause();

    // Call getCurrentPositionUs() while stopped to request an update.
    audioTrackPositionTracker.getCurrentPositionUs();

    verify(listener, never()).onPositionAdvancing(anyLong());
  }

  @Test
  public void onPositionAdvancing_isTriggeredAgainAfterPauseAndResume() {
    AudioTrackPositionTracker.Listener listener = mock(AudioTrackPositionTracker.Listener.class);
    AudioTrackPositionTracker audioTrackPositionTracker = new AudioTrackPositionTracker(listener);
    audioTrackPositionTracker.setClock(clock);
    audioTrackPositionTracker.setAudioTrack(
        audioTrack,
        C.ENCODING_PCM_16BIT,
        OUTPUT_PCM_FRAME_SIZE,
        MIN_BUFFER_SIZE,
        /* enableOnAudioPositionAdvancingFix= */ true);
    // Start the tracker to set the initial position for advancing check.
    audioTrackPositionTracker.start();
    audioTrack.play();
    // Write data to advance the position.
    writeBytesAndAdvanceTime(audioTrack);
    // Call getCurrentPositionUs() to request an initial update.
    audioTrackPositionTracker.getCurrentPositionUs();

    // Pause the tracker and audio track.
    audioTrackPositionTracker.pause();
    audioTrack.pause();
    // Call getCurrentPositionUs() while paused to request an update.
    audioTrackPositionTracker.getCurrentPositionUs();
    // Write some more data to advance the position again.
    writeBytesAndAdvanceTime(audioTrack);
    // Start the tracker again.
    audioTrackPositionTracker.start();
    audioTrack.play();
    // Call getCurrentPositionUs() to request another update.
    audioTrackPositionTracker.getCurrentPositionUs();

    verify(listener, times(2)).onPositionAdvancing(anyLong());
  }

  @Test
  public void onPositionAdvancing_isTriggeredWhenStopped() {
    AudioTrackPositionTracker.Listener listener = mock(AudioTrackPositionTracker.Listener.class);
    AudioTrackPositionTracker audioTrackPositionTracker = new AudioTrackPositionTracker(listener);
    audioTrackPositionTracker.setClock(clock);
    audioTrackPositionTracker.setAudioTrack(
        audioTrack,
        C.ENCODING_PCM_16BIT,
        OUTPUT_PCM_FRAME_SIZE,
        MIN_BUFFER_SIZE,
        /* enableOnAudioPositionAdvancingFix= */ true);
    // Start the tracker to set the initial position for advancing check.
    audioTrackPositionTracker.start();
    audioTrack.play();
    // Write data to advance the position.
    writeBytesAndAdvanceTime(audioTrack);
    // Simulate stopping the track before the advancing callback is triggered.
    audioTrackPositionTracker.handleEndOfStream(/* writtenFrames= */ SAMPLE_RATE);
    audioTrack.stop();
    verify(listener, never()).onPositionAdvancing(anyLong());

    // Call getCurrentPositionUs() while the track is stopped to request an update.
    audioTrackPositionTracker.getCurrentPositionUs();

    verify(listener).onPositionAdvancing(anyLong());
  }

  @Test
  public void onPositionAdvancing_isTriggeredAgainAfterStoppedPauseAndResume() {
    AudioTrackPositionTracker.Listener listener = mock(AudioTrackPositionTracker.Listener.class);
    AudioTrackPositionTracker audioTrackPositionTracker = new AudioTrackPositionTracker(listener);
    audioTrackPositionTracker.setClock(clock);
    audioTrackPositionTracker.setAudioTrack(
        audioTrack,
        C.ENCODING_PCM_16BIT,
        OUTPUT_PCM_FRAME_SIZE,
        MIN_BUFFER_SIZE,
        /* enableOnAudioPositionAdvancingFix= */ true);
    // Start the tracker to set the initial position for advancing check.
    audioTrackPositionTracker.start();
    audioTrack.play();
    // Write data to advance the position.
    writeBytesAndAdvanceTime(audioTrack);
    // Simulate stopping the track before the advancing callback is triggered.
    audioTrackPositionTracker.handleEndOfStream(/* writtenFrames= */ SAMPLE_RATE);
    audioTrack.stop();
    // Call getCurrentPositionUs() to request an initial update.
    audioTrackPositionTracker.getCurrentPositionUs();

    // Pause the tracker.
    audioTrackPositionTracker.pause();
    // Call getCurrentPositionUs() while paused to request an update.
    audioTrackPositionTracker.getCurrentPositionUs();
    // Start the tracker again.
    audioTrackPositionTracker.start();
    // Call getCurrentPositionUs() to request another update.
    audioTrackPositionTracker.getCurrentPositionUs();

    verify(listener, times(2)).onPositionAdvancing(anyLong());
  }

  private void writeBytesAndAdvanceTime(AudioTrack audioTrack) {
    ByteBuffer byteBuffer = createDefaultSilenceBuffer();
    int bytesRemaining = byteBuffer.remaining();
    audioTrack.write(byteBuffer, bytesRemaining, AudioTrack.WRITE_NON_BLOCKING);
    clock.advanceTime(TIME_TO_ADVANCE_MS);
  }

  /** Creates a one second silence buffer for 44.1 kHz stereo 16-bit audio. */
  private static ByteBuffer createDefaultSilenceBuffer() {
    return ByteBuffer.allocateDirect(SAMPLE_RATE * CHANNEL_COUNT_STEREO * BYTES_PER_FRAME_16_BIT)
        .order(ByteOrder.nativeOrder());
  }

  /** Creates a basic {@link AudioTrack} with 16 bit PCM encoding type. */
  private static AudioTrack createDefaultAudioTrack() {
    return new AudioTrack.Builder()
        .setAudioAttributes(AUDIO_ATTRIBUTES)
        .setAudioFormat(AUDIO_FORMAT)
        .setTransferMode(AudioTrack.MODE_STREAM)
        .setBufferSizeInBytes(MIN_BUFFER_SIZE)
        .build();
  }
}
