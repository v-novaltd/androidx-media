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
package androidx.media3.common;

import android.os.Looper;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.TextureView;
import androidx.annotation.GuardedBy;
import androidx.annotation.Nullable;
import androidx.media3.common.text.Cue;
import androidx.media3.common.text.CueGroup;
import androidx.media3.common.util.Size;
import androidx.media3.common.util.UnstableApi;
import java.util.IdentityHashMap;
import java.util.List;

/**
 * A {@link Player} that forwards method calls to another {@link Player}. Applications can use this
 * class to suppress or modify specific operations, by overriding the respective methods.
 *
 * <p>Subclasses must ensure they maintain consistency with the {@link Player} interface, including
 * interactions with {@link Player.Listener}, which can be quite fiddly. For example, if removing an
 * available {@link Player.Command} and disabling the corresponding method, subclasses need to:
 *
 * <ul>
 *   <li>Override {@link #isCommandAvailable(int)} and {@link #getAvailableCommands()}
 *   <li>Override and no-op the method itself
 *   <li>Override {@link #addListener(Listener)} and wrap the provided {@link Player.Listener} with
 *       an implementation that drops calls to {@link
 *       Player.Listener#onAvailableCommandsChanged(Commands)} and {@link
 *       Player.Listener#onEvents(Player, Events)} if they were only triggered by a change in
 *       command availability that is 'invisible' after the command removal.
 * </ul>
 *
 * <p>Many customization use-cases are instead better served by {@link ForwardingSimpleBasePlayer},
 * which allows subclasses to more concisely modify the behavior of an operation, or disallow a
 * {@link Player.Command}. In many cases {@link ForwardingSimpleBasePlayer} should be used in
 * preference to {@code ForwardingPlayer}.
 */
@UnstableApi
public class ForwardingPlayer implements Player {

  private final Player player;

  @GuardedBy("listeners")
  private final IdentityHashMap<Listener, ForwardingListener> listeners = new IdentityHashMap<>();

  /** Creates a new instance that forwards all operations to {@code player}. */
  public ForwardingPlayer(Player player) {
    this.player = player;
  }

  /** Calls {@link Player#getApplicationLooper()} on the delegate and returns the result. */
  @Override
  public Looper getApplicationLooper() {
    return player.getApplicationLooper();
  }

  /**
   * Calls {@link Player#addListener(Listener)} on the delegate.
   *
   * <p>Overrides of this method must <strong>not</strong> directly call {@code
   * delegate.addListener}. If the override wants to pass the {@link Player.Listener} instance to
   * the delegate {@link Player}, it must do so by calling {@code super.addListener} instead. This
   * ensures the correct {@link Player} instance is passed to {@link
   * Player.Listener#onEvents(Player, Events)} (i.e. this forwarding instance, and not the
   * underlying {@code delegate} instance).
   */
  @Override
  public void addListener(Listener listener) {
    synchronized (listeners) {
      ForwardingListener forwardingListener = listeners.get(listener);
      if (forwardingListener == null) {
        forwardingListener = new ForwardingListener(this, listener);
      }
      player.addListener(forwardingListener);
      listeners.put(listener, forwardingListener);
    }
  }

  /**
   * Calls {@link Player#removeListener(Listener)} on the delegate.
   *
   * <p>Overrides of this method must <strong>not</strong> directly call {@code
   * delegate.removeListener}. If the override wants to pass the {@link Player.Listener} instance to
   * the delegate {@link Player}, it must do so by calling {@code super.removeListener} instead.
   */
  @Override
  public void removeListener(Listener listener) {
    synchronized (listeners) {
      Listener forwardingListener = listeners.remove(listener);
      // If forwardingListener is null, we don't know this listener. Just pass in the real listener
      // as the underlying player would not know it either. It can decide to throw or ignore.
      player.removeListener(forwardingListener != null ? forwardingListener : listener);
    }
  }

  /** Calls {@link Player#setMediaItems(List)} on the delegate. */
  @Override
  public void setMediaItems(List<MediaItem> mediaItems) {
    player.setMediaItems(mediaItems);
  }

  /** Calls {@link Player#setMediaItems(List, boolean)} ()} on the delegate. */
  @Override
  public void setMediaItems(List<MediaItem> mediaItems, boolean resetPosition) {
    player.setMediaItems(mediaItems, resetPosition);
  }

  /** Calls {@link Player#setMediaItems(List, int, long)} on the delegate. */
  @Override
  public void setMediaItems(List<MediaItem> mediaItems, int startIndex, long startPositionMs) {
    player.setMediaItems(mediaItems, startIndex, startPositionMs);
  }

  /** Calls {@link Player#setMediaItem(MediaItem)} on the delegate. */
  @Override
  public void setMediaItem(MediaItem mediaItem) {
    player.setMediaItem(mediaItem);
  }

  /** Calls {@link Player#setMediaItem(MediaItem, long)} on the delegate. */
  @Override
  public void setMediaItem(MediaItem mediaItem, long startPositionMs) {
    player.setMediaItem(mediaItem, startPositionMs);
  }

  /** Calls {@link Player#setMediaItem(MediaItem, boolean)} on the delegate. */
  @Override
  public void setMediaItem(MediaItem mediaItem, boolean resetPosition) {
    player.setMediaItem(mediaItem, resetPosition);
  }

  /** Calls {@link Player#addMediaItem(MediaItem)} on the delegate. */
  @Override
  public void addMediaItem(MediaItem mediaItem) {
    player.addMediaItem(mediaItem);
  }

  /** Calls {@link Player#addMediaItem(int, MediaItem)} on the delegate. */
  @Override
  public void addMediaItem(int index, MediaItem mediaItem) {
    player.addMediaItem(index, mediaItem);
  }

  /** Calls {@link Player#addMediaItems(List)} on the delegate. */
  @Override
  public void addMediaItems(List<MediaItem> mediaItems) {
    player.addMediaItems(mediaItems);
  }

  /** Calls {@link Player#addMediaItems(int, List)} on the delegate. */
  @Override
  public void addMediaItems(int index, List<MediaItem> mediaItems) {
    player.addMediaItems(index, mediaItems);
  }

  /** Calls {@link Player#moveMediaItem(int, int)} on the delegate. */
  @Override
  public void moveMediaItem(int currentIndex, int newIndex) {
    player.moveMediaItem(currentIndex, newIndex);
  }

  /** Calls {@link Player#moveMediaItems(int, int, int)} on the delegate. */
  @Override
  public void moveMediaItems(int fromIndex, int toIndex, int newIndex) {
    player.moveMediaItems(fromIndex, toIndex, newIndex);
  }

  /** Calls {@link Player#replaceMediaItem(int, MediaItem)} on the delegate. */
  @Override
  public void replaceMediaItem(int index, MediaItem mediaItem) {
    player.replaceMediaItem(index, mediaItem);
  }

  /** Calls {@link Player#replaceMediaItems(int, int, List)} on the delegate. */
  @Override
  public void replaceMediaItems(int fromIndex, int toIndex, List<MediaItem> mediaItems) {
    player.replaceMediaItems(fromIndex, toIndex, mediaItems);
  }

  /** Calls {@link Player#removeMediaItem(int)} on the delegate. */
  @Override
  public void removeMediaItem(int index) {
    player.removeMediaItem(index);
  }

  /** Calls {@link Player#removeMediaItems(int, int)} on the delegate. */
  @Override
  public void removeMediaItems(int fromIndex, int toIndex) {
    player.removeMediaItems(fromIndex, toIndex);
  }

  /** Calls {@link Player#clearMediaItems()} on the delegate. */
  @Override
  public void clearMediaItems() {
    player.clearMediaItems();
  }

  /** Calls {@link Player#isCommandAvailable(int)} on the delegate and returns the result. */
  @Override
  public boolean isCommandAvailable(@Command int command) {
    return player.isCommandAvailable(command);
  }

  /** Calls {@link Player#canAdvertiseSession()} on the delegate and returns the result. */
  @Override
  public boolean canAdvertiseSession() {
    return player.canAdvertiseSession();
  }

  /** Calls {@link Player#getAvailableCommands()} on the delegate and returns the result. */
  @Override
  public Commands getAvailableCommands() {
    return player.getAvailableCommands();
  }

  /** Calls {@link Player#prepare()} on the delegate. */
  @Override
  public void prepare() {
    player.prepare();
  }

  /** Calls {@link Player#getPlaybackState()} on the delegate and returns the result. */
  @Override
  public int getPlaybackState() {
    return player.getPlaybackState();
  }

  /** Calls {@link Player#getPlaybackSuppressionReason()} on the delegate and returns the result. */
  @Override
  public int getPlaybackSuppressionReason() {
    return player.getPlaybackSuppressionReason();
  }

  /** Calls {@link Player#isPlaying()} on the delegate and returns the result. */
  @Override
  public boolean isPlaying() {
    return player.isPlaying();
  }

  /** Calls {@link Player#getPlayerError()} on the delegate and returns the result. */
  @Nullable
  @Override
  public PlaybackException getPlayerError() {
    return player.getPlayerError();
  }

  /** Calls {@link Player#play()} on the delegate. */
  @Override
  public void play() {
    player.play();
  }

  /** Calls {@link Player#pause()} on the delegate. */
  @Override
  public void pause() {
    player.pause();
  }

  /** Calls {@link Player#setPlayWhenReady(boolean)} on the delegate. */
  @Override
  public void setPlayWhenReady(boolean playWhenReady) {
    player.setPlayWhenReady(playWhenReady);
  }

  /** Calls {@link Player#getPlayWhenReady()} on the delegate and returns the result. */
  @Override
  public boolean getPlayWhenReady() {
    return player.getPlayWhenReady();
  }

  /** Calls {@link Player#setRepeatMode(int)} on the delegate. */
  @Override
  public void setRepeatMode(@RepeatMode int repeatMode) {
    player.setRepeatMode(repeatMode);
  }

  /** Calls {@link Player#getRepeatMode()} on the delegate and returns the result. */
  @Override
  public int getRepeatMode() {
    return player.getRepeatMode();
  }

  /** Calls {@link Player#setShuffleModeEnabled(boolean)} on the delegate. */
  @Override
  public void setShuffleModeEnabled(boolean shuffleModeEnabled) {
    player.setShuffleModeEnabled(shuffleModeEnabled);
  }

  /** Calls {@link Player#getShuffleModeEnabled()} on the delegate and returns the result. */
  @Override
  public boolean getShuffleModeEnabled() {
    return player.getShuffleModeEnabled();
  }

  /** Calls {@link Player#isLoading()} on the delegate and returns the result. */
  @Override
  public boolean isLoading() {
    return player.isLoading();
  }

  /** Calls {@link Player#seekToDefaultPosition()} on the delegate. */
  @Override
  public void seekToDefaultPosition() {
    player.seekToDefaultPosition();
  }

  /** Calls {@link Player#seekToDefaultPosition(int)} on the delegate. */
  @Override
  public void seekToDefaultPosition(int mediaItemIndex) {
    player.seekToDefaultPosition(mediaItemIndex);
  }

  /** Calls {@link Player#seekTo(long)} on the delegate. */
  @Override
  public void seekTo(long positionMs) {
    player.seekTo(positionMs);
  }

  /** Calls {@link Player#seekTo(int, long)} on the delegate. */
  @Override
  public void seekTo(int mediaItemIndex, long positionMs) {
    player.seekTo(mediaItemIndex, positionMs);
  }

  /** Calls {@link Player#getSeekBackIncrement()} on the delegate and returns the result. */
  @Override
  public long getSeekBackIncrement() {
    return player.getSeekBackIncrement();
  }

  /** Calls {@link Player#seekBack()} on the delegate. */
  @Override
  public void seekBack() {
    player.seekBack();
  }

  /** Calls {@link Player#getSeekForwardIncrement()} on the delegate and returns the result. */
  @Override
  public long getSeekForwardIncrement() {
    return player.getSeekForwardIncrement();
  }

  /** Calls {@link Player#seekForward()} on the delegate. */
  @Override
  public void seekForward() {
    player.seekForward();
  }

  /** Calls {@link Player#hasPreviousMediaItem()} on the delegate and returns the result. */
  @Override
  public boolean hasPreviousMediaItem() {
    return player.hasPreviousMediaItem();
  }

  /** Calls {@link Player#seekToPreviousMediaItem()} on the delegate. */
  @Override
  public void seekToPreviousMediaItem() {
    player.seekToPreviousMediaItem();
  }

  /** Calls {@link Player#seekToPrevious()} on the delegate. */
  @Override
  public void seekToPrevious() {
    player.seekToPrevious();
  }

  /** Calls {@link Player#getMaxSeekToPreviousPosition()} on the delegate and returns the result. */
  @Override
  public long getMaxSeekToPreviousPosition() {
    return player.getMaxSeekToPreviousPosition();
  }

  /** Calls {@link Player#hasNextMediaItem()} on the delegate and returns the result. */
  @Override
  public boolean hasNextMediaItem() {
    return player.hasNextMediaItem();
  }

  /** Calls {@link Player#seekToNextMediaItem()} on the delegate. */
  @Override
  public void seekToNextMediaItem() {
    player.seekToNextMediaItem();
  }

  /** Calls {@link Player#seekToNext()} on the delegate. */
  @Override
  public void seekToNext() {
    player.seekToNext();
  }

  /** Calls {@link Player#setPlaybackParameters(PlaybackParameters)} on the delegate. */
  @Override
  public void setPlaybackParameters(PlaybackParameters playbackParameters) {
    player.setPlaybackParameters(playbackParameters);
  }

  /** Calls {@link Player#setPlaybackSpeed(float)} on the delegate. */
  @Override
  public void setPlaybackSpeed(float speed) {
    player.setPlaybackSpeed(speed);
  }

  /** Calls {@link Player#getPlaybackParameters()} on the delegate and returns the result. */
  @Override
  public PlaybackParameters getPlaybackParameters() {
    return player.getPlaybackParameters();
  }

  /** Calls {@link Player#stop()} on the delegate. */
  @Override
  public void stop() {
    player.stop();
  }

  /** Calls {@link Player#release()} on the delegate. */
  @Override
  public void release() {
    player.release();
  }

  /** Calls {@link Player#getCurrentTracks()} on the delegate and returns the result. */
  @Override
  public Tracks getCurrentTracks() {
    return player.getCurrentTracks();
  }

  /** Calls {@link Player#getTrackSelectionParameters()} on the delegate and returns the result. */
  @Override
  public TrackSelectionParameters getTrackSelectionParameters() {
    return player.getTrackSelectionParameters();
  }

  /** Calls {@link Player#setTrackSelectionParameters(TrackSelectionParameters)} on the delegate. */
  @Override
  public void setTrackSelectionParameters(TrackSelectionParameters parameters) {
    player.setTrackSelectionParameters(parameters);
  }

  /** Calls {@link Player#getMediaMetadata()} on the delegate and returns the result. */
  @Override
  public MediaMetadata getMediaMetadata() {
    return player.getMediaMetadata();
  }

  /** Calls {@link Player#getPlaylistMetadata()} on the delegate and returns the result. */
  @Override
  public MediaMetadata getPlaylistMetadata() {
    return player.getPlaylistMetadata();
  }

  /** Calls {@link Player#setPlaylistMetadata(MediaMetadata)} on the delegate. */
  @Override
  public void setPlaylistMetadata(MediaMetadata mediaMetadata) {
    player.setPlaylistMetadata(mediaMetadata);
  }

  /** Calls {@link Player#getCurrentManifest()} on the delegate and returns the result. */
  @Nullable
  @Override
  public Object getCurrentManifest() {
    return player.getCurrentManifest();
  }

  /** Calls {@link Player#getCurrentTimeline()} on the delegate and returns the result. */
  @Override
  public Timeline getCurrentTimeline() {
    return player.getCurrentTimeline();
  }

  /** Calls {@link Player#getCurrentPeriodIndex()} on the delegate and returns the result. */
  @Override
  public int getCurrentPeriodIndex() {
    return player.getCurrentPeriodIndex();
  }

  /**
   * Calls {@link Player#getCurrentWindowIndex()} on the delegate and returns the result.
   *
   * @deprecated Use {@link #getCurrentMediaItemIndex()} instead.
   */
  @SuppressWarnings("deprecation") // Forwarding to deprecated method
  @Deprecated
  @Override
  public int getCurrentWindowIndex() {
    return player.getCurrentWindowIndex();
  }

  /** Calls {@link Player#getCurrentMediaItemIndex()} on the delegate and returns the result. */
  @Override
  public int getCurrentMediaItemIndex() {
    return player.getCurrentMediaItemIndex();
  }

  /**
   * Calls {@link Player#getNextWindowIndex()} on the delegate and returns the result.
   *
   * @deprecated Use {@link #getNextMediaItemIndex()} instead.
   */
  @SuppressWarnings("deprecation") // Forwarding to deprecated method
  @Deprecated
  @Override
  public int getNextWindowIndex() {
    return player.getNextWindowIndex();
  }

  /** Calls {@link Player#getNextMediaItemIndex()} on the delegate and returns the result. */
  @Override
  public int getNextMediaItemIndex() {
    return player.getNextMediaItemIndex();
  }

  /**
   * Calls {@link Player#getPreviousWindowIndex()} on the delegate and returns the result.
   *
   * @deprecated Use {@link #getPreviousMediaItemIndex()} instead.
   */
  @SuppressWarnings("deprecation") // Forwarding to deprecated method
  @Deprecated
  @Override
  public int getPreviousWindowIndex() {
    return player.getPreviousWindowIndex();
  }

  /** Calls {@link Player#getPreviousMediaItemIndex()} on the delegate and returns the result. */
  @Override
  public int getPreviousMediaItemIndex() {
    return player.getPreviousMediaItemIndex();
  }

  /** Calls {@link Player#getCurrentMediaItem()} on the delegate and returns the result. */
  @Nullable
  @Override
  public MediaItem getCurrentMediaItem() {
    return player.getCurrentMediaItem();
  }

  /** Calls {@link Player#getMediaItemCount()} on the delegate and returns the result. */
  @Override
  public int getMediaItemCount() {
    return player.getMediaItemCount();
  }

  /** Calls {@link Player#getMediaItemAt(int)} on the delegate and returns the result. */
  @Override
  public MediaItem getMediaItemAt(int index) {
    return player.getMediaItemAt(index);
  }

  /** Calls {@link Player#getDuration()} on the delegate and returns the result. */
  @Override
  public long getDuration() {
    return player.getDuration();
  }

  /** Calls {@link Player#getCurrentPosition()} on the delegate and returns the result. */
  @Override
  public long getCurrentPosition() {
    return player.getCurrentPosition();
  }

  /** Calls {@link Player#getBufferedPosition()} on the delegate and returns the result. */
  @Override
  public long getBufferedPosition() {
    return player.getBufferedPosition();
  }

  /** Calls {@link Player#getBufferedPercentage()} on the delegate and returns the result. */
  @Override
  public int getBufferedPercentage() {
    return player.getBufferedPercentage();
  }

  /** Calls {@link Player#getTotalBufferedDuration()} on the delegate and returns the result. */
  @Override
  public long getTotalBufferedDuration() {
    return player.getTotalBufferedDuration();
  }

  /**
   * Calls {@link Player#isCurrentWindowDynamic()} on the delegate and returns the result.
   *
   * @deprecated Use {@link #isCurrentMediaItemDynamic()} instead.
   */
  @SuppressWarnings("deprecation") // Forwarding to deprecated method
  @Deprecated
  @Override
  public boolean isCurrentWindowDynamic() {
    return player.isCurrentWindowDynamic();
  }

  /** Calls {@link Player#isCurrentMediaItemDynamic()} on the delegate and returns the result. */
  @Override
  public boolean isCurrentMediaItemDynamic() {
    return player.isCurrentMediaItemDynamic();
  }

  /**
   * Calls {@link Player#isCurrentWindowLive()} on the delegate and returns the result.
   *
   * @deprecated Use {@link #isCurrentMediaItemLive()} instead.
   */
  @SuppressWarnings("deprecation") // Forwarding to deprecated method
  @Deprecated
  @Override
  public boolean isCurrentWindowLive() {
    return player.isCurrentWindowLive();
  }

  /** Calls {@link Player#isCurrentMediaItemLive()} on the delegate and returns the result. */
  @Override
  public boolean isCurrentMediaItemLive() {
    return player.isCurrentMediaItemLive();
  }

  /** Calls {@link Player#getCurrentLiveOffset()} on the delegate and returns the result. */
  @Override
  public long getCurrentLiveOffset() {
    return player.getCurrentLiveOffset();
  }

  /**
   * Calls {@link Player#isCurrentWindowSeekable()} on the delegate and returns the result.
   *
   * @deprecated Use {@link #isCurrentMediaItemSeekable()} instead.
   */
  @SuppressWarnings("deprecation") // Forwarding to deprecated method
  @Deprecated
  @Override
  public boolean isCurrentWindowSeekable() {
    return player.isCurrentWindowSeekable();
  }

  /** Calls {@link Player#isCurrentMediaItemSeekable()} on the delegate and returns the result. */
  @Override
  public boolean isCurrentMediaItemSeekable() {
    return player.isCurrentMediaItemSeekable();
  }

  /** Calls {@link Player#isPlayingAd()} on the delegate and returns the result. */
  @Override
  public boolean isPlayingAd() {
    return player.isPlayingAd();
  }

  /** Calls {@link Player#getCurrentAdGroupIndex()} on the delegate and returns the result. */
  @Override
  public int getCurrentAdGroupIndex() {
    return player.getCurrentAdGroupIndex();
  }

  /** Calls {@link Player#getCurrentAdIndexInAdGroup()} on the delegate and returns the result. */
  @Override
  public int getCurrentAdIndexInAdGroup() {
    return player.getCurrentAdIndexInAdGroup();
  }

  /** Calls {@link Player#getContentDuration()} on the delegate and returns the result. */
  @Override
  public long getContentDuration() {
    return player.getContentDuration();
  }

  /** Calls {@link Player#getContentPosition()} on the delegate and returns the result. */
  @Override
  public long getContentPosition() {
    return player.getContentPosition();
  }

  /** Calls {@link Player#getContentBufferedPosition()} on the delegate and returns the result. */
  @Override
  public long getContentBufferedPosition() {
    return player.getContentBufferedPosition();
  }

  /** Calls {@link Player#getAudioAttributes()} on the delegate and returns the result. */
  @Override
  public AudioAttributes getAudioAttributes() {
    return player.getAudioAttributes();
  }

  /** Calls {@link Player#setVolume(float)} on the delegate. */
  @Override
  public void setVolume(float volume) {
    player.setVolume(volume);
  }

  /** Calls {@link Player#getVolume()} on the delegate and returns the result. */
  @Override
  public float getVolume() {
    return player.getVolume();
  }

  /** Calls {@link Player#mute()} on the delegate. */
  @Override
  public void mute() {
    player.mute();
  }

  /** Calls {@link Player#unmute()} on the delegate. */
  @Override
  public void unmute() {
    player.unmute();
  }

  /** Calls {@link Player#getVideoSize()} on the delegate and returns the result. */
  @Override
  public VideoSize getVideoSize() {
    return player.getVideoSize();
  }

  /** Calls {@link Player#getSurfaceSize()} on the delegate and returns the result. */
  @Override
  public Size getSurfaceSize() {
    return player.getSurfaceSize();
  }

  /** Calls {@link Player#clearVideoSurface()} on the delegate. */
  @Override
  public void clearVideoSurface() {
    player.clearVideoSurface();
  }

  /** Calls {@link Player#clearVideoSurface(Surface)} on the delegate. */
  @Override
  public void clearVideoSurface(@Nullable Surface surface) {
    player.clearVideoSurface(surface);
  }

  /** Calls {@link Player#setVideoSurface(Surface)} on the delegate. */
  @Override
  public void setVideoSurface(@Nullable Surface surface) {
    player.setVideoSurface(surface);
  }

  /** Calls {@link Player#setVideoSurfaceHolder(SurfaceHolder)} on the delegate. */
  @Override
  public void setVideoSurfaceHolder(@Nullable SurfaceHolder surfaceHolder) {
    player.setVideoSurfaceHolder(surfaceHolder);
  }

  /** Calls {@link Player#clearVideoSurfaceHolder(SurfaceHolder)} on the delegate. */
  @Override
  public void clearVideoSurfaceHolder(@Nullable SurfaceHolder surfaceHolder) {
    player.clearVideoSurfaceHolder(surfaceHolder);
  }

  /** Calls {@link Player#setVideoSurfaceView(SurfaceView)} on the delegate. */
  @Override
  public void setVideoSurfaceView(@Nullable SurfaceView surfaceView) {
    player.setVideoSurfaceView(surfaceView);
  }

  /** Calls {@link Player#clearVideoSurfaceView(SurfaceView)} on the delegate. */
  @Override
  public void clearVideoSurfaceView(@Nullable SurfaceView surfaceView) {
    player.clearVideoSurfaceView(surfaceView);
  }

  /** Calls {@link Player#setVideoTextureView(TextureView)} on the delegate. */
  @Override
  public void setVideoTextureView(@Nullable TextureView textureView) {
    player.setVideoTextureView(textureView);
  }

  /** Calls {@link Player#clearVideoTextureView(TextureView)} on the delegate. */
  @Override
  public void clearVideoTextureView(@Nullable TextureView textureView) {
    player.clearVideoTextureView(textureView);
  }

  /** Calls {@link Player#getCurrentCues()} on the delegate and returns the result. */
  @Override
  public CueGroup getCurrentCues() {
    return player.getCurrentCues();
  }

  /** Calls {@link Player#getDeviceInfo()} on the delegate and returns the result. */
  @Override
  public DeviceInfo getDeviceInfo() {
    return player.getDeviceInfo();
  }

  /** Calls {@link Player#getDeviceVolume()} on the delegate and returns the result. */
  @Override
  public int getDeviceVolume() {
    return player.getDeviceVolume();
  }

  /** Calls {@link Player#isDeviceMuted()} on the delegate and returns the result. */
  @Override
  public boolean isDeviceMuted() {
    return player.isDeviceMuted();
  }

  /**
   * @deprecated Use {@link #setDeviceVolume(int, int)} instead.
   */
  @SuppressWarnings("deprecation") // Intentionally forwarding deprecated method
  @Deprecated
  @Override
  public void setDeviceVolume(int volume) {
    player.setDeviceVolume(volume);
  }

  /** Calls {@link Player#setDeviceVolume(int, int)} on the delegate. */
  @Override
  public void setDeviceVolume(int volume, @C.VolumeFlags int flags) {
    player.setDeviceVolume(volume, flags);
  }

  /**
   * @deprecated Use {@link #increaseDeviceVolume(int)} instead.
   */
  @SuppressWarnings("deprecation") // Intentionally forwarding deprecated method
  @Deprecated
  @Override
  public void increaseDeviceVolume() {
    player.increaseDeviceVolume();
  }

  /** Calls {@link Player#increaseDeviceVolume(int)} on the delegate. */
  @Override
  public void increaseDeviceVolume(@C.VolumeFlags int flags) {
    player.increaseDeviceVolume(flags);
  }

  /**
   * @deprecated Use {@link #decreaseDeviceVolume(int)} instead.
   */
  @SuppressWarnings("deprecation") // Intentionally forwarding deprecated method
  @Deprecated
  @Override
  public void decreaseDeviceVolume() {
    player.decreaseDeviceVolume();
  }

  /** Calls {@link Player#decreaseDeviceVolume(int)} on the delegate. */
  @Override
  public void decreaseDeviceVolume(@C.VolumeFlags int flags) {
    player.decreaseDeviceVolume(flags);
  }

  /**
   * @deprecated Use {@link #setDeviceMuted(boolean, int)} instead.
   */
  @SuppressWarnings("deprecation") // Intentionally forwarding deprecated method
  @Deprecated
  @Override
  public void setDeviceMuted(boolean muted) {
    player.setDeviceMuted(muted);
  }

  /** Calls {@link Player#setDeviceMuted(boolean, int)} on the delegate. */
  @Override
  public void setDeviceMuted(boolean muted, @C.VolumeFlags int flags) {
    player.setDeviceMuted(muted, flags);
  }

  /** Calls {@link Player#setAudioAttributes(AudioAttributes, boolean)} on the delegate. */
  @Override
  public void setAudioAttributes(AudioAttributes audioAttributes, boolean handleAudioFocus) {
    player.setAudioAttributes(audioAttributes, handleAudioFocus);
  }

  /** Returns the {@link Player} to which operations are forwarded. */
  public Player getWrappedPlayer() {
    return player;
  }

  private static final class ForwardingListener implements Listener {

    private final ForwardingPlayer forwardingPlayer;
    private final Listener listener;

    public ForwardingListener(ForwardingPlayer forwardingPlayer, Listener listener) {
      this.forwardingPlayer = forwardingPlayer;
      this.listener = listener;
    }

    @Override
    public void onEvents(Player player, Events events) {
      // Replace player with forwarding player.
      listener.onEvents(forwardingPlayer, events);
    }

    @Override
    public void onTimelineChanged(Timeline timeline, @TimelineChangeReason int reason) {
      listener.onTimelineChanged(timeline, reason);
    }

    @Override
    public void onMediaItemTransition(
        @Nullable MediaItem mediaItem, @MediaItemTransitionReason int reason) {
      listener.onMediaItemTransition(mediaItem, reason);
    }

    @Override
    public void onTracksChanged(Tracks tracks) {
      listener.onTracksChanged(tracks);
    }

    @Override
    public void onMediaMetadataChanged(MediaMetadata mediaMetadata) {
      listener.onMediaMetadataChanged(mediaMetadata);
    }

    @Override
    public void onPlaylistMetadataChanged(MediaMetadata mediaMetadata) {
      listener.onPlaylistMetadataChanged(mediaMetadata);
    }

    @Override
    public void onIsLoadingChanged(boolean isLoading) {
      listener.onIsLoadingChanged(isLoading);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onLoadingChanged(boolean isLoading) {
      listener.onIsLoadingChanged(isLoading);
    }

    @Override
    public void onAvailableCommandsChanged(Commands availableCommands) {
      listener.onAvailableCommandsChanged(availableCommands);
    }

    @Override
    public void onTrackSelectionParametersChanged(TrackSelectionParameters parameters) {
      listener.onTrackSelectionParametersChanged(parameters);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onPlayerStateChanged(boolean playWhenReady, @State int playbackState) {
      listener.onPlayerStateChanged(playWhenReady, playbackState);
    }

    @Override
    public void onPlaybackStateChanged(@State int playbackState) {
      listener.onPlaybackStateChanged(playbackState);
    }

    @Override
    public void onPlayWhenReadyChanged(
        boolean playWhenReady, @PlayWhenReadyChangeReason int reason) {
      listener.onPlayWhenReadyChanged(playWhenReady, reason);
    }

    @Override
    public void onPlaybackSuppressionReasonChanged(
        @PlayWhenReadyChangeReason int playbackSuppressionReason) {
      listener.onPlaybackSuppressionReasonChanged(playbackSuppressionReason);
    }

    @Override
    public void onIsPlayingChanged(boolean isPlaying) {
      listener.onIsPlayingChanged(isPlaying);
    }

    @Override
    public void onRepeatModeChanged(@RepeatMode int repeatMode) {
      listener.onRepeatModeChanged(repeatMode);
    }

    @Override
    public void onShuffleModeEnabledChanged(boolean shuffleModeEnabled) {
      listener.onShuffleModeEnabledChanged(shuffleModeEnabled);
    }

    @Override
    public void onPlayerError(PlaybackException error) {
      listener.onPlayerError(error);
    }

    @Override
    public void onPlayerErrorChanged(@Nullable PlaybackException error) {
      listener.onPlayerErrorChanged(error);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onPositionDiscontinuity(@DiscontinuityReason int reason) {
      listener.onPositionDiscontinuity(reason);
    }

    @Override
    public void onPositionDiscontinuity(
        PositionInfo oldPosition, PositionInfo newPosition, @DiscontinuityReason int reason) {
      listener.onPositionDiscontinuity(oldPosition, newPosition, reason);
    }

    @Override
    public void onPlaybackParametersChanged(PlaybackParameters playbackParameters) {
      listener.onPlaybackParametersChanged(playbackParameters);
    }

    @Override
    public void onSeekBackIncrementChanged(long seekBackIncrementMs) {
      listener.onSeekBackIncrementChanged(seekBackIncrementMs);
    }

    @Override
    public void onSeekForwardIncrementChanged(long seekForwardIncrementMs) {
      listener.onSeekForwardIncrementChanged(seekForwardIncrementMs);
    }

    @Override
    public void onMaxSeekToPreviousPositionChanged(long maxSeekToPreviousPositionMs) {
      listener.onMaxSeekToPreviousPositionChanged(maxSeekToPreviousPositionMs);
    }

    @Override
    public void onVideoSizeChanged(VideoSize videoSize) {
      listener.onVideoSizeChanged(videoSize);
    }

    @Override
    public void onSurfaceSizeChanged(int width, int height) {
      listener.onSurfaceSizeChanged(width, height);
    }

    @Override
    public void onRenderedFirstFrame() {
      listener.onRenderedFirstFrame();
    }

    @Override
    public void onAudioSessionIdChanged(int audioSessionId) {
      listener.onAudioSessionIdChanged(audioSessionId);
    }

    @Override
    public void onAudioAttributesChanged(AudioAttributes audioAttributes) {
      listener.onAudioAttributesChanged(audioAttributes);
    }

    @Override
    public void onVolumeChanged(float volume) {
      listener.onVolumeChanged(volume);
    }

    @Override
    public void onSkipSilenceEnabledChanged(boolean skipSilenceEnabled) {
      listener.onSkipSilenceEnabledChanged(skipSilenceEnabled);
    }

    @SuppressWarnings("deprecation") // Intentionally forwarding deprecated method
    @Override
    public void onCues(List<Cue> cues) {
      listener.onCues(cues);
    }

    @Override
    public void onCues(CueGroup cueGroup) {
      listener.onCues(cueGroup);
    }

    @Override
    public void onMetadata(Metadata metadata) {
      listener.onMetadata(metadata);
    }

    @Override
    public void onDeviceInfoChanged(DeviceInfo deviceInfo) {
      listener.onDeviceInfoChanged(deviceInfo);
    }

    @Override
    public void onDeviceVolumeChanged(int volume, boolean muted) {
      listener.onDeviceVolumeChanged(volume, muted);
    }
  }
}
