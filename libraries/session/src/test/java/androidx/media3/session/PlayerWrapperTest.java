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
package androidx.media3.session;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

import android.os.Looper;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.Timeline;
import androidx.media3.test.utils.FakeTimeline;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/** Tests for {@link PlayerWrapper}. */
@RunWith(AndroidJUnit4.class)
public class PlayerWrapperTest {

  @Rule public final MockitoRule mockito = MockitoJUnit.rule();

  @Mock private Player player;

  private PlayerWrapper playerWrapper;

  @Before
  public void setUp() {
    playerWrapper = new PlayerWrapper(player);
    when(player.isCommandAvailable(anyInt())).thenReturn(true);
    when(player.getApplicationLooper()).thenReturn(Looper.myLooper());
  }

  @Test
  public void
      getCurrentTimelineWithCommandCheck_withoutCommandGetTimelineAndGetCurrentMediaItem_isEmpty() {
    when(player.isCommandAvailable(Player.COMMAND_GET_TIMELINE)).thenReturn(false);
    when(player.isCommandAvailable(Player.COMMAND_GET_CURRENT_MEDIA_ITEM)).thenReturn(false);
    when(player.getCurrentTimeline()).thenReturn(new FakeTimeline(/* windowCount= */ 3));

    Timeline currentTimeline = playerWrapper.getCurrentTimelineWithCommandCheck();

    assertThat(currentTimeline.isEmpty()).isTrue();
  }

  @Test
  public void getCurrentTimelineWithCommandCheck_withoutCommandGetTimelineWhenEmpty_isEmpty() {
    when(player.isCommandAvailable(Player.COMMAND_GET_TIMELINE)).thenReturn(false);
    when(player.isCommandAvailable(Player.COMMAND_GET_CURRENT_MEDIA_ITEM)).thenReturn(true);
    when(player.getCurrentTimeline()).thenReturn(Timeline.EMPTY);

    Timeline currentTimeline = playerWrapper.getCurrentTimelineWithCommandCheck();

    assertThat(currentTimeline.isEmpty()).isTrue();
  }

  @Test
  public void
      getCurrentTimelineWithCommandCheck_withoutCommandGetTimelineWhenMultipleItems_hasSingleItemTimeline() {
    when(player.isCommandAvailable(Player.COMMAND_GET_TIMELINE)).thenReturn(false);
    when(player.isCommandAvailable(Player.COMMAND_GET_CURRENT_MEDIA_ITEM)).thenReturn(true);
    when(player.getCurrentMediaItem()).thenReturn(MediaItem.fromUri("http://www.example.com"));

    Timeline currentTimeline = playerWrapper.getCurrentTimelineWithCommandCheck();

    assertThat(currentTimeline.getWindowCount()).isEqualTo(1);
  }

  @Test
  public void getCurrentTimelineWithCommandCheck_withCommandGetTimeline_returnOriginalTimeline() {
    when(player.isCommandAvailable(Player.COMMAND_GET_TIMELINE)).thenReturn(true);
    when(player.isCommandAvailable(Player.COMMAND_GET_CURRENT_MEDIA_ITEM)).thenReturn(false);
    when(player.getCurrentTimeline()).thenReturn(new FakeTimeline(/* windowCount= */ 3));

    Timeline currentTimeline = playerWrapper.getCurrentTimelineWithCommandCheck();

    assertThat(currentTimeline.getWindowCount()).isEqualTo(3);
  }

  @Test
  public void createSessionPositionInfo() {
    int testAdGroupIndex = 12;
    int testAdIndexInAdGroup = 99;
    boolean testIsPlayingAd = true;
    long testDurationMs = 5000;
    long testCurrentPositionMs = 223;
    long testBufferedPositionMs = 500;
    int testBufferedPercentage = 10;
    long testTotalBufferedDurationMs = 30;
    long testCurrentLiveOffsetMs = 212;
    long testContentDurationMs = 6000;
    long testContentPositionMs = 333;
    long testContentBufferedPositionMs = 2223;
    int testmediaItemIndex = 7;
    int testPeriodIndex = 8;
    when(player.getCurrentAdGroupIndex()).thenReturn(testAdGroupIndex);
    when(player.getCurrentAdIndexInAdGroup()).thenReturn(testAdIndexInAdGroup);
    when(player.isPlayingAd()).thenReturn(testIsPlayingAd);
    when(player.getDuration()).thenReturn(testDurationMs);
    when(player.getCurrentPosition()).thenReturn(testCurrentPositionMs);
    when(player.getBufferedPosition()).thenReturn(testBufferedPositionMs);
    when(player.getBufferedPercentage()).thenReturn(testBufferedPercentage);
    when(player.getTotalBufferedDuration()).thenReturn(testTotalBufferedDurationMs);
    when(player.getCurrentLiveOffset()).thenReturn(testCurrentLiveOffsetMs);
    when(player.getContentDuration()).thenReturn(testContentDurationMs);
    when(player.getContentPosition()).thenReturn(testContentPositionMs);
    when(player.getContentBufferedPosition()).thenReturn(testContentBufferedPositionMs);
    when(player.getCurrentMediaItemIndex()).thenReturn(testmediaItemIndex);
    when(player.getCurrentPeriodIndex()).thenReturn(testPeriodIndex);

    SessionPositionInfo sessionPositionInfo = playerWrapper.createSessionPositionInfo();

    assertThat(sessionPositionInfo.positionInfo.positionMs).isEqualTo(testCurrentPositionMs);
    assertThat(sessionPositionInfo.positionInfo.contentPositionMs).isEqualTo(testContentPositionMs);
    assertThat(sessionPositionInfo.positionInfo.adGroupIndex).isEqualTo(testAdGroupIndex);
    assertThat(sessionPositionInfo.positionInfo.adIndexInAdGroup).isEqualTo(testAdIndexInAdGroup);
    assertThat(sessionPositionInfo.positionInfo.mediaItemIndex).isEqualTo(testmediaItemIndex);
    assertThat(sessionPositionInfo.positionInfo.periodIndex).isEqualTo(testPeriodIndex);
    assertThat(sessionPositionInfo.isPlayingAd).isEqualTo(testIsPlayingAd);
    assertThat(sessionPositionInfo.durationMs).isEqualTo(testDurationMs);
    assertThat(sessionPositionInfo.bufferedPositionMs).isEqualTo(testBufferedPositionMs);
    assertThat(sessionPositionInfo.bufferedPercentage).isEqualTo(testBufferedPercentage);
    assertThat(sessionPositionInfo.totalBufferedDurationMs).isEqualTo(testTotalBufferedDurationMs);
    assertThat(sessionPositionInfo.currentLiveOffsetMs).isEqualTo(testCurrentLiveOffsetMs);
    assertThat(sessionPositionInfo.contentDurationMs).isEqualTo(testContentDurationMs);
    assertThat(sessionPositionInfo.contentBufferedPositionMs)
        .isEqualTo(testContentBufferedPositionMs);
  }
}
