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
package androidx.media3.exoplayer;

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.os.Looper;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.TextureView;
import androidx.annotation.IntRange;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.AuxEffectInfo;
import androidx.media3.common.BasePlayer;
import androidx.media3.common.C;
import androidx.media3.common.DeviceInfo;
import androidx.media3.common.Effect;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.common.PriorityTaskManager;
import androidx.media3.common.Timeline;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.Tracks;
import androidx.media3.common.VideoSize;
import androidx.media3.common.text.CueGroup;
import androidx.media3.common.util.Clock;
import androidx.media3.common.util.ConditionVariable;
import androidx.media3.common.util.Size;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.analytics.AnalyticsCollector;
import androidx.media3.exoplayer.analytics.AnalyticsListener;
import androidx.media3.exoplayer.image.ImageOutput;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.ShuffleOrder;
import androidx.media3.exoplayer.source.TrackGroupArray;
import androidx.media3.exoplayer.trackselection.TrackSelectionArray;
import androidx.media3.exoplayer.trackselection.TrackSelector;
import androidx.media3.exoplayer.upstream.BandwidthMeter;
import androidx.media3.exoplayer.video.VideoFrameMetadataListener;
import androidx.media3.exoplayer.video.spherical.CameraMotionListener;
import androidx.media3.extractor.ExtractorsFactory;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.List;

/**
 * @deprecated Use {@link ExoPlayer} instead.
 */
@UnstableApi
@Deprecated
public class SimpleExoPlayer extends BasePlayer implements ExoPlayer {

  /**
   * @deprecated Use {@link ExoPlayer.Builder} instead.
   */
  @Deprecated
  @SuppressWarnings("deprecation")
  public static final class Builder {

    private final ExoPlayer.Builder wrappedBuilder;

    /**
     * @deprecated Use {@link ExoPlayer.Builder#Builder(Context)} instead.
     */
    @Deprecated
    public Builder(Context context) {
      wrappedBuilder = new ExoPlayer.Builder(context);
    }

    /**
     * @deprecated Use {@link ExoPlayer.Builder#Builder(Context, RenderersFactory)} instead.
     */
    @Deprecated
    public Builder(Context context, RenderersFactory renderersFactory) {
      wrappedBuilder = new ExoPlayer.Builder(context, renderersFactory);
    }

    /**
     * @deprecated Use {@link ExoPlayer.Builder#Builder(Context, MediaSource.Factory)} and {@link
     *     DefaultMediaSourceFactory#DefaultMediaSourceFactory(Context, ExtractorsFactory)} instead.
     */
    @Deprecated
    public Builder(Context context, ExtractorsFactory extractorsFactory) {
      wrappedBuilder =
          new ExoPlayer.Builder(context, new DefaultMediaSourceFactory(context, extractorsFactory));
    }

    /**
     * @deprecated Use {@link ExoPlayer.Builder#Builder(Context, RenderersFactory,
     *     MediaSource.Factory)} and {@link
     *     DefaultMediaSourceFactory#DefaultMediaSourceFactory(Context, ExtractorsFactory)} instead.
     */
    @Deprecated
    public Builder(
        Context context, RenderersFactory renderersFactory, ExtractorsFactory extractorsFactory) {
      wrappedBuilder =
          new ExoPlayer.Builder(
              context, renderersFactory, new DefaultMediaSourceFactory(context, extractorsFactory));
    }

    /**
     * @deprecated Use {@link ExoPlayer.Builder#Builder(Context, RenderersFactory,
     *     MediaSource.Factory, TrackSelector, LoadControl, BandwidthMeter, AnalyticsCollector)}
     *     instead.
     */
    @Deprecated
    public Builder(
        Context context,
        RenderersFactory renderersFactory,
        TrackSelector trackSelector,
        MediaSource.Factory mediaSourceFactory,
        LoadControl loadControl,
        BandwidthMeter bandwidthMeter,
        AnalyticsCollector analyticsCollector) {
      wrappedBuilder =
          new ExoPlayer.Builder(
              context,
              renderersFactory,
              mediaSourceFactory,
              trackSelector,
              loadControl,
              bandwidthMeter,
              analyticsCollector);
    }

    /**
     * @deprecated Use {@link ExoPlayer.Builder#experimentalSetForegroundModeTimeoutMs(long)}
     *     instead.
     */
    @CanIgnoreReturnValue
    @Deprecated
    public Builder experimentalSetForegroundModeTimeoutMs(long timeoutMs) {
      wrappedBuilder.experimentalSetForegroundModeTimeoutMs(timeoutMs);
      return this;
    }

    /**
     * @deprecated Use {@link ExoPlayer.Builder#setTrackSelector(TrackSelector)} instead.
     */
    @CanIgnoreReturnValue
    @Deprecated
    public Builder setTrackSelector(TrackSelector trackSelector) {
      wrappedBuilder.setTrackSelector(trackSelector);
      return this;
    }

    /**
     * @deprecated Use {@link ExoPlayer.Builder#setMediaSourceFactory(MediaSource.Factory)} instead.
     */
    @CanIgnoreReturnValue
    @Deprecated
    public Builder setMediaSourceFactory(MediaSource.Factory mediaSourceFactory) {
      wrappedBuilder.setMediaSourceFactory(mediaSourceFactory);
      return this;
    }

    /**
     * @deprecated Use {@link ExoPlayer.Builder#setLoadControl(LoadControl)} instead.
     */
    @CanIgnoreReturnValue
    @Deprecated
    public Builder setLoadControl(LoadControl loadControl) {
      wrappedBuilder.setLoadControl(loadControl);
      return this;
    }

    /**
     * @deprecated Use {@link ExoPlayer.Builder#setBandwidthMeter(BandwidthMeter)} instead.
     */
    @CanIgnoreReturnValue
    @Deprecated
    public Builder setBandwidthMeter(BandwidthMeter bandwidthMeter) {
      wrappedBuilder.setBandwidthMeter(bandwidthMeter);
      return this;
    }

    /**
     * @deprecated Use {@link ExoPlayer.Builder#setLooper(Looper)} instead.
     */
    @CanIgnoreReturnValue
    @Deprecated
    public Builder setLooper(Looper looper) {
      wrappedBuilder.setLooper(looper);
      return this;
    }

    /**
     * @deprecated Use {@link ExoPlayer.Builder#setAnalyticsCollector(AnalyticsCollector)} instead.
     */
    @CanIgnoreReturnValue
    @Deprecated
    public Builder setAnalyticsCollector(AnalyticsCollector analyticsCollector) {
      wrappedBuilder.setAnalyticsCollector(analyticsCollector);
      return this;
    }

    /**
     * @deprecated Use {@link ExoPlayer.Builder#setPriorityTaskManager(PriorityTaskManager)}
     *     instead.
     */
    @CanIgnoreReturnValue
    @Deprecated
    public Builder setPriorityTaskManager(@Nullable PriorityTaskManager priorityTaskManager) {
      wrappedBuilder.setPriorityTaskManager(priorityTaskManager);
      return this;
    }

    /**
     * @deprecated Use {@link ExoPlayer.Builder#setAudioAttributes(AudioAttributes, boolean)}
     *     instead.
     */
    @CanIgnoreReturnValue
    @Deprecated
    public Builder setAudioAttributes(AudioAttributes audioAttributes, boolean handleAudioFocus) {
      wrappedBuilder.setAudioAttributes(audioAttributes, handleAudioFocus);
      return this;
    }

    /**
     * @deprecated Use {@link ExoPlayer.Builder#setWakeMode(int)} instead.
     */
    @CanIgnoreReturnValue
    @Deprecated
    public Builder setWakeMode(@C.WakeMode int wakeMode) {
      wrappedBuilder.setWakeMode(wakeMode);
      return this;
    }

    /**
     * @deprecated Use {@link ExoPlayer.Builder#setHandleAudioBecomingNoisy(boolean)} instead.
     */
    @CanIgnoreReturnValue
    @Deprecated
    public Builder setHandleAudioBecomingNoisy(boolean handleAudioBecomingNoisy) {
      wrappedBuilder.setHandleAudioBecomingNoisy(handleAudioBecomingNoisy);
      return this;
    }

    /**
     * @deprecated Use {@link ExoPlayer.Builder#setSkipSilenceEnabled(boolean)} instead.
     */
    @CanIgnoreReturnValue
    @Deprecated
    public Builder setSkipSilenceEnabled(boolean skipSilenceEnabled) {
      wrappedBuilder.setSkipSilenceEnabled(skipSilenceEnabled);
      return this;
    }

    /**
     * @deprecated Use {@link ExoPlayer.Builder#setVideoScalingMode(int)} instead.
     */
    @CanIgnoreReturnValue
    @Deprecated
    public Builder setVideoScalingMode(@C.VideoScalingMode int videoScalingMode) {
      wrappedBuilder.setVideoScalingMode(videoScalingMode);
      return this;
    }

    /**
     * @deprecated Use {@link ExoPlayer.Builder#setVideoChangeFrameRateStrategy(int)} instead.
     */
    @CanIgnoreReturnValue
    @Deprecated
    public Builder setVideoChangeFrameRateStrategy(
        @C.VideoChangeFrameRateStrategy int videoChangeFrameRateStrategy) {
      wrappedBuilder.setVideoChangeFrameRateStrategy(videoChangeFrameRateStrategy);
      return this;
    }

    /**
     * @deprecated Use {@link ExoPlayer.Builder#setUseLazyPreparation(boolean)} instead.
     */
    @CanIgnoreReturnValue
    @Deprecated
    public Builder setUseLazyPreparation(boolean useLazyPreparation) {
      wrappedBuilder.setUseLazyPreparation(useLazyPreparation);
      return this;
    }

    /**
     * @deprecated Use {@link ExoPlayer.Builder#setSeekParameters(SeekParameters)} instead.
     */
    @CanIgnoreReturnValue
    @Deprecated
    public Builder setSeekParameters(SeekParameters seekParameters) {
      wrappedBuilder.setSeekParameters(seekParameters);
      return this;
    }

    /**
     * @deprecated Use {@link ExoPlayer.Builder#setSeekBackIncrementMs(long)} instead.
     */
    @CanIgnoreReturnValue
    @Deprecated
    public Builder setSeekBackIncrementMs(@IntRange(from = 1) long seekBackIncrementMs) {
      wrappedBuilder.setSeekBackIncrementMs(seekBackIncrementMs);
      return this;
    }

    /**
     * @deprecated Use {@link ExoPlayer.Builder#setSeekForwardIncrementMs(long)} instead.
     */
    @CanIgnoreReturnValue
    @Deprecated
    public Builder setSeekForwardIncrementMs(@IntRange(from = 1) long seekForwardIncrementMs) {
      wrappedBuilder.setSeekForwardIncrementMs(seekForwardIncrementMs);
      return this;
    }

    /**
     * @deprecated Use {@link ExoPlayer.Builder#setReleaseTimeoutMs(long)} instead.
     */
    @CanIgnoreReturnValue
    @Deprecated
    public Builder setReleaseTimeoutMs(long releaseTimeoutMs) {
      wrappedBuilder.setReleaseTimeoutMs(releaseTimeoutMs);
      return this;
    }

    /**
     * @deprecated Use {@link ExoPlayer.Builder#setDetachSurfaceTimeoutMs(long)} instead.
     */
    @CanIgnoreReturnValue
    @Deprecated
    public Builder setDetachSurfaceTimeoutMs(long detachSurfaceTimeoutMs) {
      wrappedBuilder.setDetachSurfaceTimeoutMs(detachSurfaceTimeoutMs);
      return this;
    }

    /**
     * @deprecated Use {@link ExoPlayer.Builder#setPauseAtEndOfMediaItems(boolean)} instead.
     */
    @CanIgnoreReturnValue
    @Deprecated
    public Builder setPauseAtEndOfMediaItems(boolean pauseAtEndOfMediaItems) {
      wrappedBuilder.setPauseAtEndOfMediaItems(pauseAtEndOfMediaItems);
      return this;
    }

    /**
     * @deprecated Use {@link
     *     ExoPlayer.Builder#setLivePlaybackSpeedControl(LivePlaybackSpeedControl)} instead.
     */
    @CanIgnoreReturnValue
    @Deprecated
    public Builder setLivePlaybackSpeedControl(LivePlaybackSpeedControl livePlaybackSpeedControl) {
      wrappedBuilder.setLivePlaybackSpeedControl(livePlaybackSpeedControl);
      return this;
    }

    /**
     * @deprecated Use {@link ExoPlayer.Builder#setClock(Clock)} instead.
     */
    @CanIgnoreReturnValue
    @Deprecated
    @VisibleForTesting
    public Builder setClock(Clock clock) {
      wrappedBuilder.setClock(clock);
      return this;
    }

    /**
     * @deprecated Use {@link ExoPlayer.Builder#build()} instead.
     */
    @Deprecated
    public SimpleExoPlayer build() {
      return wrappedBuilder.buildSimpleExoPlayer();
    }
  }

  private final ExoPlayerImpl player;
  private final ConditionVariable constructorFinished;

  /**
   * @deprecated Use the {@link ExoPlayer.Builder}.
   */
  @Deprecated
  protected SimpleExoPlayer(
      Context context,
      RenderersFactory renderersFactory,
      TrackSelector trackSelector,
      MediaSource.Factory mediaSourceFactory,
      LoadControl loadControl,
      BandwidthMeter bandwidthMeter,
      AnalyticsCollector analyticsCollector,
      boolean useLazyPreparation,
      Clock clock,
      Looper applicationLooper) {
    this(
        new ExoPlayer.Builder(
                context,
                renderersFactory,
                mediaSourceFactory,
                trackSelector,
                loadControl,
                bandwidthMeter,
                analyticsCollector)
            .setUseLazyPreparation(useLazyPreparation)
            .setClock(clock)
            .setLooper(applicationLooper));
  }

  /**
   * @param builder The {@link Builder} to obtain all construction parameters.
   */
  @SuppressWarnings("deprecation") // Supporting deprecated builder.
  protected SimpleExoPlayer(Builder builder) {
    this(builder.wrappedBuilder);
  }

  /**
   * @param builder The {@link ExoPlayer.Builder} to obtain all construction parameters.
   */
  /* package */ SimpleExoPlayer(ExoPlayer.Builder builder) {
    constructorFinished = new ConditionVariable();
    try {
      player = new ExoPlayerImpl(builder, /* wrappingPlayer= */ this);
    } finally {
      constructorFinished.open();
    }
  }

  @Override
  public boolean isSleepingForOffload() {
    blockUntilConstructorFinished();
    return player.isSleepingForOffload();
  }

  @Override
  public void setVideoScalingMode(@C.VideoScalingMode int videoScalingMode) {
    blockUntilConstructorFinished();
    player.setVideoScalingMode(videoScalingMode);
  }

  @Override
  public @C.VideoScalingMode int getVideoScalingMode() {
    blockUntilConstructorFinished();
    return player.getVideoScalingMode();
  }

  @Override
  public void setVideoChangeFrameRateStrategy(
      @C.VideoChangeFrameRateStrategy int videoChangeFrameRateStrategy) {
    blockUntilConstructorFinished();
    player.setVideoChangeFrameRateStrategy(videoChangeFrameRateStrategy);
  }

  @Override
  public @C.VideoChangeFrameRateStrategy int getVideoChangeFrameRateStrategy() {
    blockUntilConstructorFinished();
    return player.getVideoChangeFrameRateStrategy();
  }

  @Override
  public VideoSize getVideoSize() {
    blockUntilConstructorFinished();
    return player.getVideoSize();
  }

  @Override
  public Size getSurfaceSize() {
    blockUntilConstructorFinished();
    return player.getSurfaceSize();
  }

  @Override
  public void clearVideoSurface() {
    blockUntilConstructorFinished();
    player.clearVideoSurface();
  }

  @Override
  public void clearVideoSurface(@Nullable Surface surface) {
    blockUntilConstructorFinished();
    player.clearVideoSurface(surface);
  }

  @Override
  public void setVideoSurface(@Nullable Surface surface) {
    blockUntilConstructorFinished();
    player.setVideoSurface(surface);
  }

  @Override
  public void setVideoSurfaceHolder(@Nullable SurfaceHolder surfaceHolder) {
    blockUntilConstructorFinished();
    player.setVideoSurfaceHolder(surfaceHolder);
  }

  @Override
  public void clearVideoSurfaceHolder(@Nullable SurfaceHolder surfaceHolder) {
    blockUntilConstructorFinished();
    player.clearVideoSurfaceHolder(surfaceHolder);
  }

  @Override
  public void setVideoSurfaceView(@Nullable SurfaceView surfaceView) {
    blockUntilConstructorFinished();
    player.setVideoSurfaceView(surfaceView);
  }

  @Override
  public void clearVideoSurfaceView(@Nullable SurfaceView surfaceView) {
    blockUntilConstructorFinished();
    player.clearVideoSurfaceView(surfaceView);
  }

  @Override
  public void setVideoTextureView(@Nullable TextureView textureView) {
    blockUntilConstructorFinished();
    player.setVideoTextureView(textureView);
  }

  @Override
  public void clearVideoTextureView(@Nullable TextureView textureView) {
    blockUntilConstructorFinished();
    player.clearVideoTextureView(textureView);
  }

  @Override
  public void addAudioOffloadListener(AudioOffloadListener listener) {
    blockUntilConstructorFinished();
    player.addAudioOffloadListener(listener);
  }

  @Override
  public void removeAudioOffloadListener(AudioOffloadListener listener) {
    blockUntilConstructorFinished();
    player.removeAudioOffloadListener(listener);
  }

  @Override
  public void setAudioAttributes(AudioAttributes audioAttributes, boolean handleAudioFocus) {
    blockUntilConstructorFinished();
    player.setAudioAttributes(audioAttributes, handleAudioFocus);
  }

  @Override
  public AudioAttributes getAudioAttributes() {
    blockUntilConstructorFinished();
    return player.getAudioAttributes();
  }

  @Override
  public void setAudioSessionId(int audioSessionId) {
    blockUntilConstructorFinished();
    player.setAudioSessionId(audioSessionId);
  }

  @Override
  public int getAudioSessionId() {
    blockUntilConstructorFinished();
    return player.getAudioSessionId();
  }

  @Override
  public void setAuxEffectInfo(AuxEffectInfo auxEffectInfo) {
    blockUntilConstructorFinished();
    player.setAuxEffectInfo(auxEffectInfo);
  }

  @Override
  public void clearAuxEffectInfo() {
    blockUntilConstructorFinished();
    player.clearAuxEffectInfo();
  }

  @Override
  public void setPreferredAudioDevice(@Nullable AudioDeviceInfo audioDeviceInfo) {
    blockUntilConstructorFinished();
    player.setPreferredAudioDevice(audioDeviceInfo);
  }

  @Override
  public void setVolume(float volume) {
    blockUntilConstructorFinished();
    player.setVolume(volume);
  }

  @Override
  public float getVolume() {
    blockUntilConstructorFinished();
    return player.getVolume();
  }

  @Override
  public void mute() {
    blockUntilConstructorFinished();
    player.mute();
  }

  @Override
  public void unmute() {
    blockUntilConstructorFinished();
    player.unmute();
  }

  @Override
  public boolean getSkipSilenceEnabled() {
    blockUntilConstructorFinished();
    return player.getSkipSilenceEnabled();
  }

  @Override
  public void setVideoEffects(List<Effect> videoEffects) {
    blockUntilConstructorFinished();
    player.setVideoEffects(videoEffects);
  }

  @Override
  public void setSkipSilenceEnabled(boolean skipSilenceEnabled) {
    blockUntilConstructorFinished();
    player.setSkipSilenceEnabled(skipSilenceEnabled);
  }

  @Override
  public void setScrubbingModeEnabled(boolean scrubbingModeEnabled) {
    blockUntilConstructorFinished();
    player.setScrubbingModeEnabled(scrubbingModeEnabled);
  }

  @Override
  public boolean isScrubbingModeEnabled() {
    blockUntilConstructorFinished();
    return player.isScrubbingModeEnabled();
  }

  @Override
  public void setScrubbingModeParameters(ScrubbingModeParameters scrubbingModeParameters) {
    blockUntilConstructorFinished();
    player.setScrubbingModeParameters(scrubbingModeParameters);
  }

  @Override
  public ScrubbingModeParameters getScrubbingModeParameters() {
    blockUntilConstructorFinished();
    return player.getScrubbingModeParameters();
  }

  @Override
  public AnalyticsCollector getAnalyticsCollector() {
    blockUntilConstructorFinished();
    return player.getAnalyticsCollector();
  }

  @Override
  public void addAnalyticsListener(AnalyticsListener listener) {
    blockUntilConstructorFinished();
    player.addAnalyticsListener(listener);
  }

  @Override
  public void removeAnalyticsListener(AnalyticsListener listener) {
    blockUntilConstructorFinished();
    player.removeAnalyticsListener(listener);
  }

  @Override
  public void setHandleAudioBecomingNoisy(boolean handleAudioBecomingNoisy) {
    blockUntilConstructorFinished();
    player.setHandleAudioBecomingNoisy(handleAudioBecomingNoisy);
  }

  @Override
  public void setPriority(@C.Priority int priority) {
    blockUntilConstructorFinished();
    player.setPriority(priority);
  }

  @Override
  public void setPriorityTaskManager(@Nullable PriorityTaskManager priorityTaskManager) {
    blockUntilConstructorFinished();
    player.setPriorityTaskManager(priorityTaskManager);
  }

  @Override
  @Nullable
  public Format getVideoFormat() {
    blockUntilConstructorFinished();
    return player.getVideoFormat();
  }

  @Override
  @Nullable
  public Format getAudioFormat() {
    blockUntilConstructorFinished();
    return player.getAudioFormat();
  }

  @Override
  @Nullable
  public DecoderCounters getVideoDecoderCounters() {
    blockUntilConstructorFinished();
    return player.getVideoDecoderCounters();
  }

  @Override
  @Nullable
  public DecoderCounters getAudioDecoderCounters() {
    blockUntilConstructorFinished();
    return player.getAudioDecoderCounters();
  }

  @Override
  public void setVideoFrameMetadataListener(VideoFrameMetadataListener listener) {
    blockUntilConstructorFinished();
    player.setVideoFrameMetadataListener(listener);
  }

  @Override
  public void clearVideoFrameMetadataListener(VideoFrameMetadataListener listener) {
    blockUntilConstructorFinished();
    player.clearVideoFrameMetadataListener(listener);
  }

  @Override
  public void setCameraMotionListener(CameraMotionListener listener) {
    blockUntilConstructorFinished();
    player.setCameraMotionListener(listener);
  }

  @Override
  public void clearCameraMotionListener(CameraMotionListener listener) {
    blockUntilConstructorFinished();
    player.clearCameraMotionListener(listener);
  }

  @Override
  public CueGroup getCurrentCues() {
    blockUntilConstructorFinished();
    return player.getCurrentCues();
  }

  // ExoPlayer implementation

  @Override
  public Looper getPlaybackLooper() {
    blockUntilConstructorFinished();
    return player.getPlaybackLooper();
  }

  @Override
  public Looper getApplicationLooper() {
    blockUntilConstructorFinished();
    return player.getApplicationLooper();
  }

  @Override
  public Clock getClock() {
    blockUntilConstructorFinished();
    return player.getClock();
  }

  @Override
  public void addListener(Listener listener) {
    blockUntilConstructorFinished();
    player.addListener(listener);
  }

  @Override
  public void removeListener(Listener listener) {
    blockUntilConstructorFinished();
    player.removeListener(listener);
  }

  @Override
  public @State int getPlaybackState() {
    blockUntilConstructorFinished();
    return player.getPlaybackState();
  }

  @Override
  public @PlaybackSuppressionReason int getPlaybackSuppressionReason() {
    blockUntilConstructorFinished();
    return player.getPlaybackSuppressionReason();
  }

  @Override
  @Nullable
  public ExoPlaybackException getPlayerError() {
    blockUntilConstructorFinished();
    return player.getPlayerError();
  }

  @Override
  public Commands getAvailableCommands() {
    blockUntilConstructorFinished();
    return player.getAvailableCommands();
  }

  @Override
  public void prepare() {
    blockUntilConstructorFinished();
    player.prepare();
  }

  /**
   * @deprecated Use {@link #setMediaSource(MediaSource)} and {@link ExoPlayer#prepare()} instead.
   */
  @Deprecated
  @Override
  @SuppressWarnings("deprecation") // Forwarding deprecated method.
  public void prepare(MediaSource mediaSource) {
    blockUntilConstructorFinished();
    player.prepare(mediaSource);
  }

  /**
   * @deprecated Use {@link #setMediaSource(MediaSource, boolean)} and {@link ExoPlayer#prepare()}
   *     instead.
   */
  @Deprecated
  @Override
  @SuppressWarnings("deprecation") // Forwarding deprecated method.
  public void prepare(MediaSource mediaSource, boolean resetPosition, boolean resetState) {
    blockUntilConstructorFinished();
    player.prepare(mediaSource, resetPosition, resetState);
  }

  @Override
  public void setMediaItems(List<MediaItem> mediaItems, boolean resetPosition) {
    blockUntilConstructorFinished();
    player.setMediaItems(mediaItems, resetPosition);
  }

  @Override
  public void setMediaItems(List<MediaItem> mediaItems, int startIndex, long startPositionMs) {
    blockUntilConstructorFinished();
    player.setMediaItems(mediaItems, startIndex, startPositionMs);
  }

  @Override
  public void setMediaSources(List<MediaSource> mediaSources) {
    blockUntilConstructorFinished();
    player.setMediaSources(mediaSources);
  }

  @Override
  public void setMediaSources(List<MediaSource> mediaSources, boolean resetPosition) {
    blockUntilConstructorFinished();
    player.setMediaSources(mediaSources, resetPosition);
  }

  @Override
  public void setMediaSources(
      List<MediaSource> mediaSources, int startMediaItemIndex, long startPositionMs) {
    blockUntilConstructorFinished();
    player.setMediaSources(mediaSources, startMediaItemIndex, startPositionMs);
  }

  @Override
  public void setMediaSource(MediaSource mediaSource) {
    blockUntilConstructorFinished();
    player.setMediaSource(mediaSource);
  }

  @Override
  public void setMediaSource(MediaSource mediaSource, boolean resetPosition) {
    blockUntilConstructorFinished();
    player.setMediaSource(mediaSource, resetPosition);
  }

  @Override
  public void setMediaSource(MediaSource mediaSource, long startPositionMs) {
    blockUntilConstructorFinished();
    player.setMediaSource(mediaSource, startPositionMs);
  }

  @Override
  public void addMediaItems(int index, List<MediaItem> mediaItems) {
    blockUntilConstructorFinished();
    player.addMediaItems(index, mediaItems);
  }

  @Override
  public void addMediaSource(MediaSource mediaSource) {
    blockUntilConstructorFinished();
    player.addMediaSource(mediaSource);
  }

  @Override
  public void addMediaSource(int index, MediaSource mediaSource) {
    blockUntilConstructorFinished();
    player.addMediaSource(index, mediaSource);
  }

  @Override
  public void addMediaSources(List<MediaSource> mediaSources) {
    blockUntilConstructorFinished();
    player.addMediaSources(mediaSources);
  }

  @Override
  public void addMediaSources(int index, List<MediaSource> mediaSources) {
    blockUntilConstructorFinished();
    player.addMediaSources(index, mediaSources);
  }

  @Override
  public void moveMediaItems(int fromIndex, int toIndex, int newIndex) {
    blockUntilConstructorFinished();
    player.moveMediaItems(fromIndex, toIndex, newIndex);
  }

  @Override
  public void replaceMediaItems(int fromIndex, int toIndex, List<MediaItem> mediaItems) {
    blockUntilConstructorFinished();
    player.replaceMediaItems(fromIndex, toIndex, mediaItems);
  }

  @Override
  public void removeMediaItems(int fromIndex, int toIndex) {
    blockUntilConstructorFinished();
    player.removeMediaItems(fromIndex, toIndex);
  }

  @Override
  public void setShuffleOrder(ShuffleOrder shuffleOrder) {
    blockUntilConstructorFinished();
    player.setShuffleOrder(shuffleOrder);
  }

  @Override
  public ShuffleOrder getShuffleOrder() {
    blockUntilConstructorFinished();
    return player.getShuffleOrder();
  }

  @Override
  public void setPlayWhenReady(boolean playWhenReady) {
    blockUntilConstructorFinished();
    player.setPlayWhenReady(playWhenReady);
  }

  @Override
  public boolean getPlayWhenReady() {
    blockUntilConstructorFinished();
    return player.getPlayWhenReady();
  }

  @Override
  public void setPauseAtEndOfMediaItems(boolean pauseAtEndOfMediaItems) {
    blockUntilConstructorFinished();
    player.setPauseAtEndOfMediaItems(pauseAtEndOfMediaItems);
  }

  @Override
  public boolean getPauseAtEndOfMediaItems() {
    blockUntilConstructorFinished();
    return player.getPauseAtEndOfMediaItems();
  }

  @Override
  public @RepeatMode int getRepeatMode() {
    blockUntilConstructorFinished();
    return player.getRepeatMode();
  }

  @Override
  public void setRepeatMode(@RepeatMode int repeatMode) {
    blockUntilConstructorFinished();
    player.setRepeatMode(repeatMode);
  }

  @Override
  public void setPreloadConfiguration(PreloadConfiguration preloadConfiguration) {
    blockUntilConstructorFinished();
    player.setPreloadConfiguration(preloadConfiguration);
  }

  @Override
  public PreloadConfiguration getPreloadConfiguration() {
    blockUntilConstructorFinished();
    return player.getPreloadConfiguration();
  }

  @Override
  public void setShuffleModeEnabled(boolean shuffleModeEnabled) {
    blockUntilConstructorFinished();
    player.setShuffleModeEnabled(shuffleModeEnabled);
  }

  @Override
  public boolean getShuffleModeEnabled() {
    blockUntilConstructorFinished();
    return player.getShuffleModeEnabled();
  }

  @Override
  public boolean isLoading() {
    blockUntilConstructorFinished();
    return player.isLoading();
  }

  @SuppressWarnings("ForOverride") // Forwarding to ForOverride method in ExoPlayerImpl.
  @Override
  protected void seekTo(
      int mediaItemIndex,
      long positionMs,
      @Player.Command int seekCommand,
      boolean isRepeatingCurrentItem) {
    blockUntilConstructorFinished();
    player.seekTo(mediaItemIndex, positionMs, seekCommand, isRepeatingCurrentItem);
  }

  @Override
  public long getSeekBackIncrement() {
    blockUntilConstructorFinished();
    return player.getSeekBackIncrement();
  }

  @Override
  public long getSeekForwardIncrement() {
    blockUntilConstructorFinished();
    return player.getSeekForwardIncrement();
  }

  @Override
  public long getMaxSeekToPreviousPosition() {
    blockUntilConstructorFinished();
    return player.getMaxSeekToPreviousPosition();
  }

  @Override
  public void setPlaybackParameters(PlaybackParameters playbackParameters) {
    blockUntilConstructorFinished();
    player.setPlaybackParameters(playbackParameters);
  }

  @Override
  public PlaybackParameters getPlaybackParameters() {
    blockUntilConstructorFinished();
    return player.getPlaybackParameters();
  }

  @Override
  public void setSeekParameters(@Nullable SeekParameters seekParameters) {
    blockUntilConstructorFinished();
    player.setSeekParameters(seekParameters);
  }

  @Override
  public SeekParameters getSeekParameters() {
    blockUntilConstructorFinished();
    return player.getSeekParameters();
  }

  @Override
  public void setForegroundMode(boolean foregroundMode) {
    blockUntilConstructorFinished();
    player.setForegroundMode(foregroundMode);
  }

  @Override
  public void stop() {
    blockUntilConstructorFinished();
    player.stop();
  }

  @Override
  public void release() {
    blockUntilConstructorFinished();
    player.release();
  }

  @Override
  public PlayerMessage createMessage(PlayerMessage.Target target) {
    blockUntilConstructorFinished();
    return player.createMessage(target);
  }

  @Override
  public int getRendererCount() {
    blockUntilConstructorFinished();
    return player.getRendererCount();
  }

  @Override
  public @C.TrackType int getRendererType(int index) {
    blockUntilConstructorFinished();
    return player.getRendererType(index);
  }

  @Override
  public Renderer getRenderer(int index) {
    blockUntilConstructorFinished();
    return player.getRenderer(index);
  }

  @Override
  public TrackSelector getTrackSelector() {
    blockUntilConstructorFinished();
    return player.getTrackSelector();
  }

  /**
   * @deprecated Use {@link #getCurrentTracks()}.
   */
  @Deprecated
  @Override
  public TrackGroupArray getCurrentTrackGroups() {
    blockUntilConstructorFinished();
    return player.getCurrentTrackGroups();
  }

  /**
   * @deprecated Use {@link #getCurrentTracks()}.
   */
  @Deprecated
  @Override
  public TrackSelectionArray getCurrentTrackSelections() {
    blockUntilConstructorFinished();
    return player.getCurrentTrackSelections();
  }

  @Override
  public Tracks getCurrentTracks() {
    blockUntilConstructorFinished();
    return player.getCurrentTracks();
  }

  @Override
  public TrackSelectionParameters getTrackSelectionParameters() {
    blockUntilConstructorFinished();
    return player.getTrackSelectionParameters();
  }

  @Override
  public void setTrackSelectionParameters(TrackSelectionParameters parameters) {
    blockUntilConstructorFinished();
    player.setTrackSelectionParameters(parameters);
  }

  @Override
  public MediaMetadata getMediaMetadata() {
    blockUntilConstructorFinished();
    return player.getMediaMetadata();
  }

  @Override
  public MediaMetadata getPlaylistMetadata() {
    blockUntilConstructorFinished();
    return player.getPlaylistMetadata();
  }

  @Override
  public void setPlaylistMetadata(MediaMetadata mediaMetadata) {
    blockUntilConstructorFinished();
    player.setPlaylistMetadata(mediaMetadata);
  }

  @Override
  public Timeline getCurrentTimeline() {
    blockUntilConstructorFinished();
    return player.getCurrentTimeline();
  }

  @Override
  public int getCurrentPeriodIndex() {
    blockUntilConstructorFinished();
    return player.getCurrentPeriodIndex();
  }

  @Override
  public int getCurrentMediaItemIndex() {
    blockUntilConstructorFinished();
    return player.getCurrentMediaItemIndex();
  }

  @Override
  public long getDuration() {
    blockUntilConstructorFinished();
    return player.getDuration();
  }

  @Override
  public long getCurrentPosition() {
    blockUntilConstructorFinished();
    return player.getCurrentPosition();
  }

  @Override
  public long getBufferedPosition() {
    blockUntilConstructorFinished();
    return player.getBufferedPosition();
  }

  @Override
  public long getTotalBufferedDuration() {
    blockUntilConstructorFinished();
    return player.getTotalBufferedDuration();
  }

  @Override
  public boolean isPlayingAd() {
    blockUntilConstructorFinished();
    return player.isPlayingAd();
  }

  @Override
  public int getCurrentAdGroupIndex() {
    blockUntilConstructorFinished();
    return player.getCurrentAdGroupIndex();
  }

  @Override
  public int getCurrentAdIndexInAdGroup() {
    blockUntilConstructorFinished();
    return player.getCurrentAdIndexInAdGroup();
  }

  @Override
  public long getContentPosition() {
    blockUntilConstructorFinished();
    return player.getContentPosition();
  }

  @Override
  public long getContentBufferedPosition() {
    blockUntilConstructorFinished();
    return player.getContentBufferedPosition();
  }

  @Override
  public void setWakeMode(@C.WakeMode int wakeMode) {
    blockUntilConstructorFinished();
    player.setWakeMode(wakeMode);
  }

  @Override
  public DeviceInfo getDeviceInfo() {
    blockUntilConstructorFinished();
    return player.getDeviceInfo();
  }

  @Override
  public int getDeviceVolume() {
    blockUntilConstructorFinished();
    return player.getDeviceVolume();
  }

  @Override
  public boolean isDeviceMuted() {
    blockUntilConstructorFinished();
    return player.isDeviceMuted();
  }

  /**
   * @deprecated Use {@link #setDeviceVolume(int, int)} instead.
   */
  @Deprecated
  @Override
  @SuppressWarnings("deprecation") // Forwarding deprecated method.
  public void setDeviceVolume(int volume) {
    blockUntilConstructorFinished();
    player.setDeviceVolume(volume);
  }

  @Override
  public void setDeviceVolume(int volume, @C.VolumeFlags int flags) {
    blockUntilConstructorFinished();
    player.setDeviceVolume(volume, flags);
  }

  /**
   * @deprecated Use {@link #increaseDeviceVolume(int)} instead.
   */
  @Deprecated
  @Override
  @SuppressWarnings("deprecation") // Forwarding deprecated method.
  public void increaseDeviceVolume() {
    blockUntilConstructorFinished();
    player.increaseDeviceVolume();
  }

  @Override
  public void increaseDeviceVolume(@C.VolumeFlags int flags) {
    blockUntilConstructorFinished();
    player.increaseDeviceVolume(flags);
  }

  /**
   * @deprecated Use {@link #decreaseDeviceVolume(int)} instead.
   */
  @Deprecated
  @Override
  @SuppressWarnings("deprecation") // Forwarding deprecated method.
  public void decreaseDeviceVolume() {
    blockUntilConstructorFinished();
    player.decreaseDeviceVolume();
  }

  @Override
  public void decreaseDeviceVolume(@C.VolumeFlags int flags) {
    blockUntilConstructorFinished();
    player.decreaseDeviceVolume(flags);
  }

  /**
   * @deprecated Use {@link #setDeviceMuted(boolean, int)} instead.
   */
  @Deprecated
  @Override
  @SuppressWarnings("deprecation") // Forwarding deprecated method.
  public void setDeviceMuted(boolean muted) {
    blockUntilConstructorFinished();
    player.setDeviceMuted(muted);
  }

  @Override
  public void setDeviceMuted(boolean muted, @C.VolumeFlags int flags) {
    blockUntilConstructorFinished();
    player.setDeviceMuted(muted, flags);
  }

  @Override
  public boolean isTunnelingEnabled() {
    blockUntilConstructorFinished();
    return player.isTunnelingEnabled();
  }

  @Override
  public boolean isReleased() {
    return player.isReleased();
  }

  @Override
  public void setImageOutput(@Nullable ImageOutput imageOutput) {
    blockUntilConstructorFinished();
    player.setImageOutput(imageOutput);
  }

  /* package */ void setThrowsWhenUsingWrongThread(boolean throwsWhenUsingWrongThread) {
    blockUntilConstructorFinished();
    player.setThrowsWhenUsingWrongThread(throwsWhenUsingWrongThread);
  }

  private void blockUntilConstructorFinished() {
    // The constructor may be executed on a background thread. Wait with accessing the player from
    // the app thread until the constructor finished executing.
    constructorFinished.blockUninterruptible();
  }
}
