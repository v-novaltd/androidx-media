/*
 * Copyright (C) 2017 The Android Open Source Project
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
package androidx.media3.test.utils;

import android.media.AudioDeviceInfo;
import android.os.Looper;
import androidx.annotation.Nullable;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.AuxEffectInfo;
import androidx.media3.common.C;
import androidx.media3.common.Effect;
import androidx.media3.common.Format;
import androidx.media3.common.PriorityTaskManager;
import androidx.media3.common.util.Clock;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.DecoderCounters;
import androidx.media3.exoplayer.ExoPlaybackException;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.PlayerMessage;
import androidx.media3.exoplayer.Renderer;
import androidx.media3.exoplayer.ScrubbingModeParameters;
import androidx.media3.exoplayer.SeekParameters;
import androidx.media3.exoplayer.analytics.AnalyticsCollector;
import androidx.media3.exoplayer.analytics.AnalyticsListener;
import androidx.media3.exoplayer.image.ImageOutput;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.ShuffleOrder;
import androidx.media3.exoplayer.source.TrackGroupArray;
import androidx.media3.exoplayer.trackselection.TrackSelectionArray;
import androidx.media3.exoplayer.trackselection.TrackSelector;
import androidx.media3.exoplayer.video.VideoFrameMetadataListener;
import androidx.media3.exoplayer.video.spherical.CameraMotionListener;
import java.util.List;

/**
 * An abstract {@link ExoPlayer} implementation that throws {@link UnsupportedOperationException}
 * from every method.
 */
@UnstableApi
public class StubExoPlayer extends StubPlayer implements ExoPlayer {

  @Override
  public Looper getPlaybackLooper() {
    throw new UnsupportedOperationException();
  }

  @Override
  public Clock getClock() {
    throw new UnsupportedOperationException();
  }

  @Override
  public void addAudioOffloadListener(AudioOffloadListener listener) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void removeAudioOffloadListener(AudioOffloadListener listener) {
    throw new UnsupportedOperationException();
  }

  @Override
  public AnalyticsCollector getAnalyticsCollector() {
    throw new UnsupportedOperationException();
  }

  @Override
  public void addAnalyticsListener(AnalyticsListener listener) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void removeAnalyticsListener(AnalyticsListener listener) {
    throw new UnsupportedOperationException();
  }

  @Override
  public ExoPlaybackException getPlayerError() {
    throw new UnsupportedOperationException();
  }

  /**
   * @deprecated Use {@link #setMediaSource(MediaSource)} and {@link #prepare()} instead.
   */
  @Deprecated
  @Override
  public void prepare(MediaSource mediaSource) {
    throw new UnsupportedOperationException();
  }

  /**
   * @deprecated Use {@link #setMediaSource(MediaSource, boolean)} and {@link #prepare()} instead.
   */
  @Deprecated
  @Override
  public void prepare(MediaSource mediaSource, boolean resetPosition, boolean resetState) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void setPreloadConfiguration(PreloadConfiguration preloadConfiguration) {
    throw new UnsupportedOperationException();
  }

  @Override
  public PreloadConfiguration getPreloadConfiguration() {
    throw new UnsupportedOperationException();
  }

  @Override
  public void setMediaSource(MediaSource mediaSource) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void setMediaSource(MediaSource mediaSource, long startPositionMs) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void setMediaSource(MediaSource mediaSource, boolean resetPosition) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void setMediaSources(List<MediaSource> mediaSources) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void setMediaSources(List<MediaSource> mediaSources, boolean resetPosition) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void setMediaSources(
      List<MediaSource> mediaSources, int startMediaItemIndex, long startPositionMs) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void addMediaSource(MediaSource mediaSource) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void addMediaSource(int index, MediaSource mediaSource) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void addMediaSources(List<MediaSource> mediaSources) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void addMediaSources(int index, List<MediaSource> mediaSources) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void setShuffleOrder(ShuffleOrder shuffleOrder) {
    throw new UnsupportedOperationException();
  }

  @Override
  public ShuffleOrder getShuffleOrder() {
    throw new UnsupportedOperationException();
  }

  @Override
  public void setAudioAttributes(AudioAttributes audioAttributes, boolean handleAudioFocus) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void setAudioSessionId(int audioSessionId) {
    throw new UnsupportedOperationException();
  }

  @Override
  public int getAudioSessionId() {
    throw new UnsupportedOperationException();
  }

  @Override
  public void setAuxEffectInfo(AuxEffectInfo auxEffectInfo) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void clearAuxEffectInfo() {
    throw new UnsupportedOperationException();
  }

  @Override
  public void setPreferredAudioDevice(@Nullable AudioDeviceInfo audioDeviceInfo) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void setSkipSilenceEnabled(boolean skipSilenceEnabled) {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean getSkipSilenceEnabled() {
    throw new UnsupportedOperationException();
  }

  @Override
  public void setScrubbingModeEnabled(boolean scrubbingModeEnabled) {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean isScrubbingModeEnabled() {
    throw new UnsupportedOperationException();
  }

  @Override
  public void setScrubbingModeParameters(ScrubbingModeParameters scrubbingModeParameters) {
    throw new UnsupportedOperationException();
  }

  @Override
  public ScrubbingModeParameters getScrubbingModeParameters() {
    throw new UnsupportedOperationException();
  }

  @Override
  public void setVideoEffects(List<Effect> videoEffects) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void setVideoScalingMode(int videoScalingMode) {
    throw new UnsupportedOperationException();
  }

  @Override
  public int getVideoScalingMode() {
    throw new UnsupportedOperationException();
  }

  @Override
  public void setVideoChangeFrameRateStrategy(int videoChangeFrameRateStrategy) {
    throw new UnsupportedOperationException();
  }

  @Override
  public int getVideoChangeFrameRateStrategy() {
    throw new UnsupportedOperationException();
  }

  @Override
  public void setVideoFrameMetadataListener(VideoFrameMetadataListener listener) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void clearVideoFrameMetadataListener(VideoFrameMetadataListener listener) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void setCameraMotionListener(CameraMotionListener listener) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void clearCameraMotionListener(CameraMotionListener listener) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void setSeekParameters(@Nullable SeekParameters seekParameters) {
    throw new UnsupportedOperationException();
  }

  @Override
  public SeekParameters getSeekParameters() {
    throw new UnsupportedOperationException();
  }

  @Override
  public PlayerMessage createMessage(PlayerMessage.Target target) {
    throw new UnsupportedOperationException();
  }

  @Override
  public int getRendererCount() {
    throw new UnsupportedOperationException();
  }

  @Override
  public int getRendererType(int index) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Renderer getRenderer(int index) {
    throw new UnsupportedOperationException();
  }

  @Override
  @Nullable
  public TrackSelector getTrackSelector() {
    throw new UnsupportedOperationException();
  }

  /**
   * @deprecated Use {@link #getCurrentTracks()}.
   */
  @Deprecated
  @Override
  public TrackGroupArray getCurrentTrackGroups() {
    throw new UnsupportedOperationException();
  }

  /**
   * @deprecated Use {@link #getCurrentTracks()}.
   */
  @Deprecated
  @Override
  public TrackSelectionArray getCurrentTrackSelections() {
    throw new UnsupportedOperationException();
  }

  @Override
  public void setForegroundMode(boolean foregroundMode) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void setPauseAtEndOfMediaItems(boolean pauseAtEndOfMediaItems) {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean getPauseAtEndOfMediaItems() {
    throw new UnsupportedOperationException();
  }

  @Nullable
  @Override
  public Format getAudioFormat() {
    throw new UnsupportedOperationException();
  }

  @Nullable
  @Override
  public Format getVideoFormat() {
    throw new UnsupportedOperationException();
  }

  @Nullable
  @Override
  public DecoderCounters getAudioDecoderCounters() {
    throw new UnsupportedOperationException();
  }

  @Nullable
  @Override
  public DecoderCounters getVideoDecoderCounters() {
    throw new UnsupportedOperationException();
  }

  @Override
  public void setHandleAudioBecomingNoisy(boolean handleAudioBecomingNoisy) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void setWakeMode(int wakeMode) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void setPriority(@C.Priority int priority) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void setPriorityTaskManager(@Nullable PriorityTaskManager priorityTaskManager) {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean isSleepingForOffload() {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean isTunnelingEnabled() {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean isReleased() {
    throw new UnsupportedOperationException();
  }

  @Override
  public void setImageOutput(@Nullable ImageOutput imageOutput) {
    throw new UnsupportedOperationException();
  }
}
