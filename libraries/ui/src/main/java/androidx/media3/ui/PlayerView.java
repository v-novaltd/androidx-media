/*
 * Copyright 2019 The Android Open Source Project
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
package androidx.media3.ui;

import static android.os.Build.VERSION.SDK_INT;
import static androidx.media3.common.Player.COMMAND_GET_CURRENT_MEDIA_ITEM;
import static androidx.media3.common.Player.COMMAND_GET_METADATA;
import static androidx.media3.common.Player.COMMAND_GET_TEXT;
import static androidx.media3.common.Player.COMMAND_GET_TIMELINE;
import static androidx.media3.common.Player.COMMAND_GET_TRACKS;
import static androidx.media3.common.Player.COMMAND_SET_VIDEO_SURFACE;
import static androidx.media3.common.util.Assertions.checkNotNull;
import static androidx.media3.common.util.Util.getDrawable;
import static java.lang.annotation.ElementType.TYPE_USE;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.opengl.GLSurfaceView;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.AttachedSurfaceControl;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.SurfaceControl;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.window.SurfaceSyncGroup;
import androidx.annotation.ColorInt;
import androidx.annotation.IntDef;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.content.ContextCompat;
import androidx.media3.common.AdOverlayInfo;
import androidx.media3.common.AdViewProvider;
import androidx.media3.common.C;
import androidx.media3.common.ErrorMessageProvider;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.Player.DiscontinuityReason;
import androidx.media3.common.Timeline;
import androidx.media3.common.Timeline.Period;
import androidx.media3.common.Tracks;
import androidx.media3.common.VideoSize;
import androidx.media3.common.text.CueGroup;
import androidx.media3.common.util.Assertions;
import androidx.media3.common.util.RepeatModeUtil;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.ui.AspectRatioFrameLayout.ResizeMode;
import com.google.common.collect.ImmutableList;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import org.checkerframework.checker.nullness.qual.EnsuresNonNullIf;

/**
 * A high level view for {@link Player} media playbacks. It displays video, subtitles and album art
 * during playback, and displays playback controls using a {@link PlayerControlView}.
 *
 * <p>A PlayerView can be customized by setting attributes (or calling corresponding methods), or
 * overriding drawables.
 *
 * <h2>Attributes</h2>
 *
 * The following attributes can be set on a PlayerView when used in a layout XML file:
 *
 * <ul>
 *   <li><b>{@code artwork_display_mode}</b> - Whether artwork is used if available in audio streams
 *       and {@link ArtworkDisplayMode how it is displayed}.
 *       <ul>
 *         <li>Corresponding method: {@link #setArtworkDisplayMode(int)}
 *         <li>Default: {@link #ARTWORK_DISPLAY_MODE_FIT}
 *       </ul>
 *   <li><b>{@code default_artwork}</b> - Default artwork to use if no artwork available in audio
 *       streams.
 *       <ul>
 *         <li>Corresponding method: {@link #setDefaultArtwork(Drawable)}
 *         <li>Default: {@code null}
 *       </ul>
 *   <li><b>{@code image_display_mode}</b> - The {@link ImageDisplayMode mode} in which images are
 *       displayed.
 *       <ul>
 *         <li>Corresponding method: {@link #setImageDisplayMode(int)}
 *         <li>Default: {@code #IMAGE_DISPLAY_MODE_FIT}
 *       </ul>
 *   <li><b>{@code use_controller}</b> - Whether the playback controls can be shown.
 *       <ul>
 *         <li>Corresponding method: {@link #setUseController(boolean)}
 *         <li>Default: {@code true}
 *       </ul>
 *   <li><b>{@code hide_on_touch}</b> - Whether the playback controls are hidden by touch events.
 *       <ul>
 *         <li>Corresponding method: {@link #setControllerHideOnTouch(boolean)}
 *         <li>Default: {@code true}
 *       </ul>
 *   <li><b>{@code auto_show}</b> - Whether the playback controls are automatically shown when
 *       playback starts, pauses, ends, or fails. If set to false, the playback controls can be
 *       manually operated with {@link #showController()} and {@link #hideController()}.
 *       <ul>
 *         <li>Corresponding method: {@link #setControllerAutoShow(boolean)}
 *         <li>Default: {@code true}
 *       </ul>
 *   <li><b>{@code hide_during_ads}</b> - Whether the playback controls are hidden during ads.
 *       Controls are always shown during ads if they are enabled and the player is paused.
 *       <ul>
 *         <li>Corresponding method: {@link #setControllerHideDuringAds(boolean)}
 *         <li>Default: {@code true}
 *       </ul>
 *   <li><b>{@code show_buffering}</b> - Whether the buffering spinner is displayed when the player
 *       is buffering. Valid values are {@code never}, {@code when_playing} and {@code always}.
 *       <ul>
 *         <li>Corresponding method: {@link #setShowBuffering(int)}
 *         <li>Default: {@code never}
 *       </ul>
 *   <li><b>{@code resize_mode}</b> - Controls how video and album art is resized within the view.
 *       Valid values are {@code fit}, {@code fixed_width}, {@code fixed_height}, {@code fill} and
 *       {@code zoom}.
 *       <ul>
 *         <li>Corresponding method: {@link #setResizeMode(int)}
 *         <li>Default: {@code fit}
 *       </ul>
 *   <li><b>{@code surface_type}</b> - The type of surface view used for video playbacks. Valid
 *       values are {@code surface_view}, {@code texture_view}, {@code spherical_gl_surface_view},
 *       {@code video_decoder_gl_surface_view} and {@code none}. Using {@code none} is recommended
 *       for audio only applications, since creating the surface can be expensive. Using {@code
 *       surface_view} is recommended for video applications. See <a
 *       href="https://developer.android.com/media/media3/ui/playerview#surfacetype">Choosing a
 *       surface type</a> for more information.
 *       <ul>
 *         <li>Corresponding method: None
 *         <li>Default: {@code surface_view}
 *       </ul>
 *   <li><b>{@code shutter_background_color}</b> - The background color of the {@code exo_shutter}
 *       view.
 *       <ul>
 *         <li>Corresponding method: {@link #setShutterBackgroundColor(int)}
 *         <li>Default: {@code unset}
 *       </ul>
 *   <li><b>{@code keep_content_on_player_reset}</b> - Whether the currently displayed video frame
 *       or media artwork is kept visible when the player is reset.
 *       <ul>
 *         <li>Corresponding method: {@link #setKeepContentOnPlayerReset(boolean)}
 *         <li>Default: {@code false}
 *       </ul>
 *   <li>All attributes that can be set on {@link PlayerControlView} and {@link DefaultTimeBar} can
 *       also be set on a PlayerView, and will be propagated to the inflated {@link
 *       PlayerControlView}.
 * </ul>
 *
 * <h2>Overriding drawables</h2>
 *
 * The drawables used by {@link PlayerControlView} can be overridden by drawables with the same
 * names defined in your application. See the {@link PlayerControlView} documentation for a list of
 * drawables that can be overridden.
 */
public class PlayerView extends FrameLayout implements AdViewProvider {

  /** Listener to be notified about changes of the visibility of the UI controls. */
  public interface ControllerVisibilityListener {

    /**
     * Called when the visibility changes.
     *
     * @param visibility The new visibility. Either {@link View#VISIBLE} or {@link View#GONE}.
     */
    void onVisibilityChanged(int visibility);
  }

  /**
   * Listener invoked when the fullscreen button is clicked. The implementation is responsible for
   * changing the UI layout.
   */
  public interface FullscreenButtonClickListener {

    /**
     * Called when the fullscreen button is clicked.
     *
     * @param isFullscreen {@code true} if the video rendering surface should be fullscreen, {@code
     *     false} otherwise.
     */
    void onFullscreenButtonClick(boolean isFullscreen);
  }

  /**
   * Determines the artwork display mode. One of {@link #ARTWORK_DISPLAY_MODE_OFF}, {@link
   * #ARTWORK_DISPLAY_MODE_FIT} or {@link #ARTWORK_DISPLAY_MODE_FILL}.
   */
  @UnstableApi
  @Documented
  @Retention(RetentionPolicy.SOURCE)
  @Target(TYPE_USE)
  @IntDef({ARTWORK_DISPLAY_MODE_OFF, ARTWORK_DISPLAY_MODE_FIT, ARTWORK_DISPLAY_MODE_FILL})
  public @interface ArtworkDisplayMode {}

  /** No artwork is shown. */
  @UnstableApi public static final int ARTWORK_DISPLAY_MODE_OFF = 0;

  /** The artwork is fit into the player view and centered creating a letterbox style. */
  @UnstableApi public static final int ARTWORK_DISPLAY_MODE_FIT = 1;

  /**
   * The artwork covers the entire space of the player view. If the aspect ratio of the image is
   * different than the player view some areas of the image are cropped.
   */
  @UnstableApi public static final int ARTWORK_DISPLAY_MODE_FILL = 2;

  /**
   * Determines the image display mode. {@link #IMAGE_DISPLAY_MODE_FIT} or {@link
   * #IMAGE_DISPLAY_MODE_FILL}.
   */
  @UnstableApi
  @Documented
  @Retention(RetentionPolicy.SOURCE)
  @Target(TYPE_USE)
  @IntDef({IMAGE_DISPLAY_MODE_FIT, IMAGE_DISPLAY_MODE_FILL})
  public @interface ImageDisplayMode {}

  /** The image is fit into the player view and centered creating a letterbox style. */
  @UnstableApi public static final int IMAGE_DISPLAY_MODE_FIT = 0;

  /**
   * The image covers the entire space of the player view. If the aspect ratio of the image is
   * different than the player view some areas of the image are cropped.
   */
  @UnstableApi public static final int IMAGE_DISPLAY_MODE_FILL = 1;

  // LINT.IfChange
  /**
   * Determines when the buffering view is shown. One of {@link #SHOW_BUFFERING_NEVER}, {@link
   * #SHOW_BUFFERING_WHEN_PLAYING} or {@link #SHOW_BUFFERING_ALWAYS}.
   */
  @UnstableApi
  @Documented
  @Retention(RetentionPolicy.SOURCE)
  @Target(TYPE_USE)
  @IntDef({SHOW_BUFFERING_NEVER, SHOW_BUFFERING_WHEN_PLAYING, SHOW_BUFFERING_ALWAYS})
  public @interface ShowBuffering {}

  /** The buffering view is never shown. */
  @UnstableApi public static final int SHOW_BUFFERING_NEVER = 0;

  /**
   * The buffering view is shown when the player is in the {@link Player#STATE_BUFFERING buffering}
   * state and {@link Player#getPlayWhenReady() playWhenReady} is {@code true}.
   */
  @UnstableApi public static final int SHOW_BUFFERING_WHEN_PLAYING = 1;

  /**
   * The buffering view is always shown when the player is in the {@link Player#STATE_BUFFERING
   * buffering} state.
   */
  @UnstableApi public static final int SHOW_BUFFERING_ALWAYS = 2;

  // LINT.ThenChange(../../../../res/values/attrs.xml)

  // LINT.IfChange
  private static final int SURFACE_TYPE_NONE = 0;
  private static final int SURFACE_TYPE_SURFACE_VIEW = 1;
  private static final int SURFACE_TYPE_TEXTURE_VIEW = 2;
  private static final int SURFACE_TYPE_SPHERICAL_GL_SURFACE_VIEW = 3;
  private static final int SURFACE_TYPE_VIDEO_DECODER_GL_SURFACE_VIEW = 4;
  // LINT.ThenChange(../../../../res/values/attrs.xml)

  private final ComponentListener componentListener;
  @Nullable private final AspectRatioFrameLayout contentFrame;
  @Nullable private final View shutterView;
  @Nullable private final View surfaceView;
  private final boolean surfaceViewIgnoresVideoAspectRatio;
  @Nullable private final SurfaceSyncGroupCompatV34 surfaceSyncGroupV34;
  @Nullable private final ImageView imageView;
  @Nullable private final ImageView artworkView;
  @Nullable private final SubtitleView subtitleView;
  @Nullable private final View bufferingView;
  @Nullable private final TextView errorMessageView;
  @Nullable private final PlayerControlView controller;
  @Nullable private final FrameLayout adOverlayFrameLayout;
  @Nullable private final FrameLayout overlayFrameLayout;
  private final Handler mainLooperHandler;
  @Nullable private final Class<?> exoPlayerClazz;
  @Nullable private final Method setImageOutputMethod;
  @Nullable private final Object imageOutput;

  @Nullable private Player player;
  private boolean useController;

  // At most one of controllerVisibilityListener and legacyControllerVisibilityListener is non-null.
  @Nullable private ControllerVisibilityListener controllerVisibilityListener;

  @SuppressWarnings("deprecation")
  @Nullable
  private PlayerControlView.VisibilityListener legacyControllerVisibilityListener;

  @Nullable private FullscreenButtonClickListener fullscreenButtonClickListener;

  private @ArtworkDisplayMode int artworkDisplayMode;
  private @ImageDisplayMode int imageDisplayMode;

  @Nullable private Drawable defaultArtwork;
  private @ShowBuffering int showBuffering;
  private boolean keepContentOnPlayerReset;
  @Nullable private ErrorMessageProvider<? super PlaybackException> errorMessageProvider;
  @Nullable private CharSequence customErrorMessage;
  private int controllerShowTimeoutMs;
  private boolean controllerAutoShow;
  private boolean controllerHideDuringAds;
  private boolean controllerHideOnTouch;
  private boolean enableComposeSurfaceSyncWorkaround;

  public PlayerView(Context context) {
    this(context, /* attrs= */ null);
  }

  public PlayerView(Context context, @Nullable AttributeSet attrs) {
    this(context, attrs, /* defStyleAttr= */ 0);
  }

  // Using deprecated PlayerControlView.VisibilityListener internally
  @SuppressWarnings({"nullness:argument", "nullness:method.invocation", "deprecation"})
  public PlayerView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
    super(context, attrs, defStyleAttr);

    componentListener = new ComponentListener();
    mainLooperHandler = new Handler(Looper.getMainLooper());

    if (isInEditMode()) {
      contentFrame = null;
      shutterView = null;
      surfaceView = null;
      surfaceViewIgnoresVideoAspectRatio = false;
      surfaceSyncGroupV34 = null;
      imageView = null;
      artworkView = null;
      subtitleView = null;
      bufferingView = null;
      errorMessageView = null;
      controller = null;
      adOverlayFrameLayout = null;
      overlayFrameLayout = null;
      exoPlayerClazz = null;
      setImageOutputMethod = null;
      imageOutput = null;
      ImageView logo = new ImageView(context);
      configureEditModeLogo(context, getResources(), logo);
      addView(logo);
      return;
    }

    boolean shutterColorSet = false;
    int shutterColor = 0;
    int playerLayoutId = R.layout.exo_player_view;
    boolean useArtwork = true;
    int artworkDisplayMode = ARTWORK_DISPLAY_MODE_FIT;
    int imageDisplayMode = IMAGE_DISPLAY_MODE_FIT;
    int defaultArtworkId = 0;
    boolean useController = true;
    int surfaceType = SURFACE_TYPE_SURFACE_VIEW;
    int resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT;
    int controllerShowTimeoutMs = PlayerControlView.DEFAULT_SHOW_TIMEOUT_MS;
    boolean controllerHideOnTouch = true;
    boolean controllerAutoShow = true;
    boolean controllerHideDuringAds = true;
    int showBuffering = SHOW_BUFFERING_NEVER;
    if (attrs != null) {
      TypedArray a =
          context
              .getTheme()
              .obtainStyledAttributes(
                  attrs, R.styleable.PlayerView, defStyleAttr, /* defStyleRes= */ 0);
      try {
        shutterColorSet = a.hasValue(R.styleable.PlayerView_shutter_background_color);
        shutterColor = a.getColor(R.styleable.PlayerView_shutter_background_color, shutterColor);
        playerLayoutId = a.getResourceId(R.styleable.PlayerView_player_layout_id, playerLayoutId);
        useArtwork = a.getBoolean(R.styleable.PlayerView_use_artwork, useArtwork);
        artworkDisplayMode =
            a.getInt(R.styleable.PlayerView_artwork_display_mode, artworkDisplayMode);
        defaultArtworkId =
            a.getResourceId(R.styleable.PlayerView_default_artwork, defaultArtworkId);
        imageDisplayMode = a.getInt(R.styleable.PlayerView_image_display_mode, imageDisplayMode);
        useController = a.getBoolean(R.styleable.PlayerView_use_controller, useController);
        surfaceType = a.getInt(R.styleable.PlayerView_surface_type, surfaceType);
        resizeMode = a.getInt(R.styleable.PlayerView_resize_mode, resizeMode);
        controllerShowTimeoutMs =
            a.getInt(R.styleable.PlayerView_show_timeout, controllerShowTimeoutMs);
        controllerHideOnTouch =
            a.getBoolean(R.styleable.PlayerView_hide_on_touch, controllerHideOnTouch);
        controllerAutoShow = a.getBoolean(R.styleable.PlayerView_auto_show, controllerAutoShow);
        showBuffering = a.getInteger(R.styleable.PlayerView_show_buffering, showBuffering);
        keepContentOnPlayerReset =
            a.getBoolean(
                R.styleable.PlayerView_keep_content_on_player_reset, keepContentOnPlayerReset);
        controllerHideDuringAds =
            a.getBoolean(R.styleable.PlayerView_hide_during_ads, controllerHideDuringAds);
      } finally {
        a.recycle();
      }
    }

    LayoutInflater.from(context).inflate(playerLayoutId, this);
    setDescendantFocusability(FOCUS_AFTER_DESCENDANTS);

    // Content frame.
    contentFrame = findViewById(R.id.exo_content_frame);
    if (contentFrame != null) {
      setResizeModeRaw(contentFrame, resizeMode);
    }

    // Shutter view.
    shutterView = findViewById(R.id.exo_shutter);
    if (shutterView != null && shutterColorSet) {
      shutterView.setBackgroundColor(shutterColor);
    }

    // Create a surface view and insert it into the content frame, if there is one.
    boolean surfaceViewIgnoresVideoAspectRatio = false;
    if (contentFrame != null && surfaceType != SURFACE_TYPE_NONE) {
      ViewGroup.LayoutParams params =
          new ViewGroup.LayoutParams(
              ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
      switch (surfaceType) {
        case SURFACE_TYPE_TEXTURE_VIEW:
          surfaceView = new TextureView(context);
          break;
        case SURFACE_TYPE_SPHERICAL_GL_SURFACE_VIEW:
          try {
            // LINT.IfChange
            Class<?> clazz =
                Class.forName("androidx.media3.exoplayer.video.spherical.SphericalGLSurfaceView");
            surfaceView = (View) clazz.getConstructor(Context.class).newInstance(context);
            // LINT.ThenChange(../../../../../../proguard-rules.txt)
          } catch (Exception e) {
            throw new IllegalStateException(
                "spherical_gl_surface_view requires an ExoPlayer dependency", e);
          }
          surfaceViewIgnoresVideoAspectRatio = true;
          break;
        case SURFACE_TYPE_VIDEO_DECODER_GL_SURFACE_VIEW:
          try {
            // LINT.IfChange
            Class<?> clazz =
                Class.forName("androidx.media3.exoplayer.video.VideoDecoderGLSurfaceView");
            surfaceView = (View) clazz.getConstructor(Context.class).newInstance(context);
            // LINT.ThenChange(../../../../../../proguard-rules.txt)
          } catch (Exception e) {
            throw new IllegalStateException(
                "video_decoder_gl_surface_view requires an ExoPlayer dependency", e);
          }
          break;
        default:
          SurfaceView view = new SurfaceView(context);
          if (SDK_INT >= 34) {
            Api34.setSurfaceLifecycleToFollowsAttachment(view);
          }
          surfaceView = view;
          break;
      }
      surfaceView.setLayoutParams(params);
      // We don't want surfaceView to be clickable separately to the PlayerView itself, but we
      // do want to register as an OnClickListener so that surfaceView implementations can propagate
      // click events up to the PlayerView by calling their own performClick method.
      surfaceView.setOnClickListener(componentListener);
      surfaceView.setClickable(false);
      contentFrame.addView(surfaceView, 0);
    } else {
      surfaceView = null;
    }
    this.surfaceViewIgnoresVideoAspectRatio = surfaceViewIgnoresVideoAspectRatio;
    this.surfaceSyncGroupV34 = SDK_INT == 34 ? new SurfaceSyncGroupCompatV34() : null;

    // Ad overlay frame layout.
    adOverlayFrameLayout = findViewById(R.id.exo_ad_overlay);

    // Overlay frame layout.
    overlayFrameLayout = findViewById(R.id.exo_overlay);

    // Image view.
    imageView = findViewById(R.id.exo_image);
    this.imageDisplayMode = imageDisplayMode;

    // ExoPlayer image output classes and methods.
    Class<?> exoPlayerClazz;
    Method setImageOutputMethod;
    Object imageOutput;
    try {
      exoPlayerClazz = Class.forName("androidx.media3.exoplayer.ExoPlayer");
      Class<?> imageOutputClazz = Class.forName("androidx.media3.exoplayer.image.ImageOutput");
      setImageOutputMethod = exoPlayerClazz.getMethod("setImageOutput", imageOutputClazz);
      imageOutput =
          Proxy.newProxyInstance(
              imageOutputClazz.getClassLoader(),
              new Class<?>[] {imageOutputClazz},
              (proxy, method, args) -> {
                if (method.getName().equals("onImageAvailable")) {
                  onImageAvailable((Bitmap) args[1]);
                }
                return null;
              });
    } catch (ClassNotFoundException | NoSuchMethodException e) {
      // Expected if ExoPlayer module not available.
      exoPlayerClazz = null;
      setImageOutputMethod = null;
      imageOutput = null;
    }
    this.exoPlayerClazz = exoPlayerClazz;
    this.setImageOutputMethod = setImageOutputMethod;
    this.imageOutput = imageOutput;

    // Artwork view.
    artworkView = findViewById(R.id.exo_artwork);
    boolean isArtworkEnabled =
        useArtwork && artworkDisplayMode != ARTWORK_DISPLAY_MODE_OFF && artworkView != null;
    this.artworkDisplayMode = isArtworkEnabled ? artworkDisplayMode : ARTWORK_DISPLAY_MODE_OFF;
    if (defaultArtworkId != 0) {
      defaultArtwork = ContextCompat.getDrawable(getContext(), defaultArtworkId);
    }

    // Subtitle view.
    subtitleView = findViewById(R.id.exo_subtitles);
    if (subtitleView != null) {
      subtitleView.setUserDefaultStyle();
      subtitleView.setUserDefaultTextSize();
    }

    // Buffering view.
    bufferingView = findViewById(R.id.exo_buffering);
    if (bufferingView != null) {
      bufferingView.setVisibility(View.GONE);
    }
    this.showBuffering = showBuffering;

    // Error message view.
    errorMessageView = findViewById(R.id.exo_error_message);
    if (errorMessageView != null) {
      errorMessageView.setVisibility(View.GONE);
    }

    // Playback control view.
    PlayerControlView customController = findViewById(R.id.exo_controller);
    View controllerPlaceholder = findViewById(R.id.exo_controller_placeholder);
    if (customController != null) {
      this.controller = customController;
    } else if (controllerPlaceholder != null) {
      // Propagate attrs as playbackAttrs so that PlayerControlView's custom attributes are
      // transferred, but standard attributes (e.g. background) are not.
      this.controller = new PlayerControlView(context, null, 0, attrs);
      controller.setId(R.id.exo_controller);
      controller.setLayoutParams(controllerPlaceholder.getLayoutParams());
      ViewGroup parent = ((ViewGroup) controllerPlaceholder.getParent());
      int controllerIndex = parent.indexOfChild(controllerPlaceholder);
      parent.removeView(controllerPlaceholder);
      parent.addView(controller, controllerIndex);
    } else {
      this.controller = null;
    }
    this.controllerShowTimeoutMs = controller != null ? controllerShowTimeoutMs : 0;
    this.controllerHideOnTouch = controllerHideOnTouch;
    this.controllerAutoShow = controllerAutoShow;
    this.controllerHideDuringAds = controllerHideDuringAds;
    this.useController = useController && controller != null;
    if (controller != null) {
      controller.hideImmediately();
      controller.addVisibilityListener(/* listener= */ componentListener);
    }
    if (useController) {
      setClickable(true);
    }
    updateContentDescription();
  }

  /**
   * Switches the view targeted by a given {@link Player}.
   *
   * @param player The player whose target view is being switched.
   * @param oldPlayerView The old view to detach from the player.
   * @param newPlayerView The new view to attach to the player.
   */
  @UnstableApi
  public static void switchTargetView(
      Player player, @Nullable PlayerView oldPlayerView, @Nullable PlayerView newPlayerView) {
    if (oldPlayerView == newPlayerView) {
      return;
    }
    // We attach the new view before detaching the old one because this ordering allows the player
    // to swap directly from one surface to another, without transitioning through a state where no
    // surface is attached. This is significantly more efficient and achieves a more seamless
    // transition when using platform provided video decoders.
    if (newPlayerView != null) {
      newPlayerView.setPlayer(player);
    }
    if (oldPlayerView != null) {
      oldPlayerView.setPlayer(null);
    }
  }

  /** Returns the player currently set on this view, or null if no player is set. */
  @Nullable
  public Player getPlayer() {
    return player;
  }

  /**
   * Sets the {@link Player} to use.
   *
   * <p>To transition a {@link Player} from targeting one view to another, it's recommended to use
   * {@link #switchTargetView(Player, PlayerView, PlayerView)} rather than this method. If you do
   * wish to use this method directly, be sure to attach the player to the new view <em>before</em>
   * calling {@code setPlayer(null)} to detach it from the old one. This ordering is significantly
   * more efficient and may allow for more seamless transitions.
   *
   * @param player The {@link Player} to use, or {@code null} to detach the current player. Only
   *     players which are accessed on the main thread are supported ({@code
   *     player.getApplicationLooper() == Looper.getMainLooper()}).
   */
  public void setPlayer(@Nullable Player player) {
    Assertions.checkState(Looper.myLooper() == Looper.getMainLooper());
    Assertions.checkArgument(
        player == null || player.getApplicationLooper() == Looper.getMainLooper());
    if (this.player == player) {
      return;
    }

    @Nullable Player oldPlayer = this.player;
    if (oldPlayer != null) {
      oldPlayer.removeListener(componentListener);
      if (oldPlayer.isCommandAvailable(COMMAND_SET_VIDEO_SURFACE)) {
        if (surfaceView instanceof TextureView) {
          oldPlayer.clearVideoTextureView((TextureView) surfaceView);
        } else if (surfaceView instanceof SurfaceView) {
          oldPlayer.clearVideoSurfaceView((SurfaceView) surfaceView);
        }
      }
      clearImageOutput(oldPlayer);
    }
    if (subtitleView != null) {
      subtitleView.setCues(null);
    }
    this.player = player;
    if (useController()) {
      controller.setPlayer(player);
    }
    updateBuffering();
    updateErrorMessage();
    updateForCurrentTrackSelections(/* isNewPlayer= */ true);
    if (player != null) {
      if (player.isCommandAvailable(COMMAND_SET_VIDEO_SURFACE)) {
        if (surfaceView instanceof TextureView) {
          player.setVideoTextureView((TextureView) surfaceView);
        } else if (surfaceView instanceof SurfaceView) {
          player.setVideoSurfaceView((SurfaceView) surfaceView);
        }
        if (!player.isCommandAvailable(COMMAND_GET_TRACKS)
            || player.getCurrentTracks().isTypeSupported(C.TRACK_TYPE_VIDEO)) {
          // If the player already is or was playing a video, onVideoSizeChanged isn't called.
          updateAspectRatio();
        }
      }
      if (subtitleView != null && player.isCommandAvailable(COMMAND_GET_TEXT)) {
        subtitleView.setCues(player.getCurrentCues().cues);
      }
      player.addListener(componentListener);
      setImageOutput(player);
      maybeShowController(false);
    } else {
      hideController();
    }
  }

  private void setImageOutput(Player player) {
    if (exoPlayerClazz != null && exoPlayerClazz.isAssignableFrom(player.getClass())) {
      try {
        checkNotNull(setImageOutputMethod).invoke(player, checkNotNull(imageOutput));
      } catch (IllegalAccessException | InvocationTargetException e) {
        throw new RuntimeException(e);
      }
    }
  }

  @SuppressWarnings("argument.type.incompatible") // null allowed as method parameter in invoke
  private void clearImageOutput(Player player) {
    if (exoPlayerClazz != null && exoPlayerClazz.isAssignableFrom(player.getClass())) {
      try {
        checkNotNull(setImageOutputMethod).invoke(player, new Object[] {null});
      } catch (IllegalAccessException | InvocationTargetException e) {
        throw new RuntimeException(e);
      }
    }
  }

  @Override
  public void setVisibility(int visibility) {
    super.setVisibility(visibility);
    if (surfaceView instanceof SurfaceView) {
      // Work around https://github.com/google/ExoPlayer/issues/3160.
      surfaceView.setVisibility(visibility);
    }
  }

  /**
   * Sets the {@link ResizeMode}.
   *
   * @param resizeMode The {@link ResizeMode}.
   */
  @UnstableApi
  public void setResizeMode(@ResizeMode int resizeMode) {
    Assertions.checkStateNotNull(contentFrame);
    contentFrame.setResizeMode(resizeMode);
  }

  /** Returns the {@link ResizeMode}. */
  @UnstableApi
  public @ResizeMode int getResizeMode() {
    Assertions.checkStateNotNull(contentFrame);
    return contentFrame.getResizeMode();
  }

  /**
   * @deprecated Use {@link #getArtworkDisplayMode()} instead.
   */
  @UnstableApi
  @Deprecated
  public boolean getUseArtwork() {
    return this.artworkDisplayMode != ARTWORK_DISPLAY_MODE_OFF;
  }

  /**
   * @deprecated Use {@link #setArtworkDisplayMode(int)} instead.
   */
  @UnstableApi
  @Deprecated
  public void setUseArtwork(boolean useArtwork) {
    setArtworkDisplayMode(useArtwork ? ARTWORK_DISPLAY_MODE_OFF : ARTWORK_DISPLAY_MODE_FIT);
  }

  /** Sets whether and how artwork is displayed if present in the media. */
  @UnstableApi
  public void setArtworkDisplayMode(@ArtworkDisplayMode int artworkDisplayMode) {
    Assertions.checkState(artworkDisplayMode == ARTWORK_DISPLAY_MODE_OFF || artworkView != null);
    if (this.artworkDisplayMode != artworkDisplayMode) {
      this.artworkDisplayMode = artworkDisplayMode;
      updateForCurrentTrackSelections(/* isNewPlayer= */ false);
    }
  }

  /** Returns the {@link ArtworkDisplayMode artwork display mode}. */
  @UnstableApi
  public @ArtworkDisplayMode int getArtworkDisplayMode() {
    return artworkDisplayMode;
  }

  /** Returns the default artwork to display. */
  @UnstableApi
  @Nullable
  public Drawable getDefaultArtwork() {
    return defaultArtwork;
  }

  /**
   * Sets the default artwork to display if {@code useArtwork} is {@code true} and no artwork is
   * present in the media.
   *
   * @param defaultArtwork the default artwork to display
   */
  @UnstableApi
  public void setDefaultArtwork(@Nullable Drawable defaultArtwork) {
    if (this.defaultArtwork != defaultArtwork) {
      this.defaultArtwork = defaultArtwork;
      updateForCurrentTrackSelections(/* isNewPlayer= */ false);
    }
  }

  /** Sets how images are displayed if present in the media. */
  @UnstableApi
  public void setImageDisplayMode(@ImageDisplayMode int imageDisplayMode) {
    Assertions.checkState(imageView != null);
    if (this.imageDisplayMode != imageDisplayMode) {
      this.imageDisplayMode = imageDisplayMode;
      updateImageViewAspectRatio();
    }
  }

  /** Returns the {@link ImageDisplayMode image display mode}. */
  @UnstableApi
  public @ImageDisplayMode int getImageDisplayMode() {
    return imageDisplayMode;
  }

  /** Returns whether the playback controls can be shown. */
  public boolean getUseController() {
    return useController;
  }

  /**
   * Sets whether the playback controls can be shown. If set to {@code false} the playback controls
   * are never visible and are disconnected from the player.
   *
   * <p>This call will update whether the view is clickable. After the call, the view will be
   * clickable if playback controls can be shown or if the view has a registered click listener.
   *
   * @param useController Whether the playback controls can be shown.
   */
  public void setUseController(boolean useController) {
    Assertions.checkState(!useController || controller != null);
    setClickable(useController || hasOnClickListeners());
    if (this.useController == useController) {
      return;
    }
    this.useController = useController;
    if (useController()) {
      controller.setPlayer(player);
    } else if (controller != null) {
      controller.hide();
      controller.setPlayer(/* player= */ null);
    }
    updateContentDescription();
  }

  /**
   * Sets the background color of the {@code exo_shutter} view.
   *
   * @param color The background color.
   */
  @UnstableApi
  public void setShutterBackgroundColor(@ColorInt int color) {
    if (shutterView != null) {
      shutterView.setBackgroundColor(color);
    }
  }

  /**
   * Sets whether the currently displayed video frame or media artwork is kept visible when the
   * player is reset. A player reset is defined to mean the player being re-prepared with different
   * media, the player transitioning to unprepared media or an empty list of media items, or the
   * player being replaced or cleared by calling {@link #setPlayer(Player)}.
   *
   * <p>If enabled, the currently displayed video frame or media artwork will be kept visible until
   * the player set on the view has been successfully prepared with new media and loaded enough of
   * it to have determined the available tracks. Hence enabling this option allows transitioning
   * from playing one piece of media to another, or from using one player instance to another,
   * without clearing the view's content.
   *
   * <p>If disabled, the currently displayed video frame or media artwork will be hidden as soon as
   * the player is reset. Note that the video frame is hidden by making {@code exo_shutter} visible.
   * Hence the video frame will not be hidden if using a custom layout that omits this view.
   *
   * @param keepContentOnPlayerReset Whether the currently displayed video frame or media artwork is
   *     kept visible when the player is reset.
   */
  @UnstableApi
  public void setKeepContentOnPlayerReset(boolean keepContentOnPlayerReset) {
    if (this.keepContentOnPlayerReset != keepContentOnPlayerReset) {
      this.keepContentOnPlayerReset = keepContentOnPlayerReset;
      updateForCurrentTrackSelections(/* isNewPlayer= */ false);
    }
  }

  /**
   * Sets whether a buffering spinner is displayed when the player is in the buffering state. The
   * buffering spinner is not displayed by default.
   *
   * @param showBuffering The mode that defines when the buffering spinner is displayed. One of
   *     {@link #SHOW_BUFFERING_NEVER}, {@link #SHOW_BUFFERING_WHEN_PLAYING} and {@link
   *     #SHOW_BUFFERING_ALWAYS}.
   */
  @UnstableApi
  public void setShowBuffering(@ShowBuffering int showBuffering) {
    if (this.showBuffering != showBuffering) {
      this.showBuffering = showBuffering;
      updateBuffering();
    }
  }

  /**
   * Sets the optional {@link ErrorMessageProvider}.
   *
   * @param errorMessageProvider The error message provider.
   */
  public void setErrorMessageProvider(
      @Nullable ErrorMessageProvider<? super PlaybackException> errorMessageProvider) {
    if (this.errorMessageProvider != errorMessageProvider) {
      this.errorMessageProvider = errorMessageProvider;
      updateErrorMessage();
    }
  }

  /**
   * Sets a custom error message to be displayed by the view. The error message will be displayed
   * permanently, unless it is cleared by passing {@code null} to this method.
   *
   * @param message The message to display, or {@code null} to clear a previously set message.
   */
  @UnstableApi
  public void setCustomErrorMessage(@Nullable CharSequence message) {
    Assertions.checkState(errorMessageView != null);
    customErrorMessage = message;
    updateErrorMessage();
  }

  @Override
  public boolean dispatchKeyEvent(KeyEvent event) {
    if (player != null
        && player.isCommandAvailable(COMMAND_GET_CURRENT_MEDIA_ITEM)
        && player.isPlayingAd()) {
      return super.dispatchKeyEvent(event);
    }

    boolean isDpadKey = isDpadKey(event.getKeyCode());
    boolean handled = false;
    if (isDpadKey && useController() && !controller.isFullyVisible()) {
      // Handle the key event by showing the controller.
      maybeShowController(true);
      handled = true;
    } else if (dispatchMediaKeyEvent(event) || super.dispatchKeyEvent(event)) {
      // The key event was handled as a media key or by the super class. We should also show the
      // controller, or extend its show timeout if already visible.
      maybeShowController(true);
      handled = true;
    } else if (isDpadKey && useController()) {
      // The key event wasn't handled, but we should extend the controller's show timeout.
      maybeShowController(true);
    }
    return handled;
  }

  /**
   * Called to process media key events. Any {@link KeyEvent} can be passed but only media key
   * events will be handled. Does nothing if playback controls are disabled.
   *
   * @param event A key event.
   * @return Whether the key event was handled.
   */
  @UnstableApi
  public boolean dispatchMediaKeyEvent(KeyEvent event) {
    return useController() && controller.dispatchMediaKeyEvent(event);
  }

  /** Returns whether the controller is currently fully visible. */
  @UnstableApi
  public boolean isControllerFullyVisible() {
    return controller != null && controller.isFullyVisible();
  }

  /**
   * Shows the playback controls. Does nothing if playback controls are disabled.
   *
   * <p>The playback controls are automatically hidden during playback after {{@link
   * #getControllerShowTimeoutMs()}}. They are shown indefinitely when playback has not started yet,
   * is paused, has ended or failed.
   */
  @UnstableApi
  public void showController() {
    showController(shouldShowControllerIndefinitely());
  }

  /** Hides the playback controls. Does nothing if playback controls are disabled. */
  @UnstableApi
  public void hideController() {
    if (controller != null) {
      controller.hide();
    }
  }

  /**
   * Returns the playback controls timeout. The playback controls are automatically hidden after
   * this duration of time has elapsed without user input and with playback or buffering in
   * progress.
   *
   * @return The timeout in milliseconds. A non-positive value will cause the controller to remain
   *     visible indefinitely.
   */
  @UnstableApi
  public int getControllerShowTimeoutMs() {
    return controllerShowTimeoutMs;
  }

  /**
   * Sets the playback controls timeout. The playback controls are automatically hidden after this
   * duration of time has elapsed without user input and with playback or buffering in progress.
   *
   * @param controllerShowTimeoutMs The timeout in milliseconds. A non-positive value will cause the
   *     controller to remain visible indefinitely.
   */
  @UnstableApi
  public void setControllerShowTimeoutMs(int controllerShowTimeoutMs) {
    Assertions.checkStateNotNull(controller);
    this.controllerShowTimeoutMs = controllerShowTimeoutMs;
    if (controller.isFullyVisible()) {
      // Update the controller's timeout if necessary.
      showController();
    }
  }

  /** Returns whether the playback controls are hidden by touch events. */
  @UnstableApi
  public boolean getControllerHideOnTouch() {
    return controllerHideOnTouch;
  }

  /**
   * Sets whether the playback controls are hidden by touch events.
   *
   * @param controllerHideOnTouch Whether the playback controls are hidden by touch events.
   */
  @UnstableApi
  public void setControllerHideOnTouch(boolean controllerHideOnTouch) {
    Assertions.checkStateNotNull(controller);
    this.controllerHideOnTouch = controllerHideOnTouch;
    updateContentDescription();
  }

  /**
   * Returns whether the playback controls are automatically shown when playback starts, pauses,
   * ends, or fails. If set to false, the playback controls can be manually operated with {@link
   * #showController()} and {@link #hideController()}.
   */
  @UnstableApi
  public boolean getControllerAutoShow() {
    return controllerAutoShow;
  }

  /**
   * Sets whether the playback controls are automatically shown when playback starts, pauses, ends,
   * or fails. If set to false, the playback controls can be manually operated with {@link
   * #showController()} and {@link #hideController()}.
   *
   * @param controllerAutoShow Whether the playback controls are allowed to show automatically.
   */
  @UnstableApi
  public void setControllerAutoShow(boolean controllerAutoShow) {
    this.controllerAutoShow = controllerAutoShow;
  }

  /**
   * Sets whether the playback controls are hidden when ads are playing. Controls are always shown
   * during ads if they are enabled and the player is paused.
   *
   * @param controllerHideDuringAds Whether the playback controls are hidden when ads are playing.
   */
  @UnstableApi
  public void setControllerHideDuringAds(boolean controllerHideDuringAds) {
    this.controllerHideDuringAds = controllerHideDuringAds;
  }

  /**
   * Sets the {@link ControllerVisibilityListener}.
   *
   * <p>If {@code listener} is non-null then any listener set by {@link
   * #setControllerVisibilityListener(PlayerControlView.VisibilityListener)} is removed.
   *
   * @param listener The listener to be notified about visibility changes, or null to remove the
   *     current listener.
   */
  @SuppressWarnings("deprecation") // Clearing the legacy listener.
  public void setControllerVisibilityListener(@Nullable ControllerVisibilityListener listener) {
    this.controllerVisibilityListener = listener;
    if (listener != null) {
      setControllerVisibilityListener((PlayerControlView.VisibilityListener) null);
    }
  }

  /**
   * Sets whether {@linkplain PlayerControlView#isAnimationEnabled() controller animation is
   * enabled}.
   */
  @UnstableApi
  public void setControllerAnimationEnabled(boolean animationEnabled) {
    Assertions.checkStateNotNull(controller);
    controller.setAnimationEnabled(animationEnabled);
  }

  /**
   * Sets the {@link PlayerControlView.VisibilityListener}.
   *
   * <p>If {@code listener} is non-null then any listener set by {@link
   * #setControllerVisibilityListener(ControllerVisibilityListener)} is removed.
   *
   * @deprecated Use {@link #setControllerVisibilityListener(ControllerVisibilityListener)} instead.
   */
  @SuppressWarnings("deprecation")
  @Deprecated
  @UnstableApi
  public void setControllerVisibilityListener(
      @Nullable PlayerControlView.VisibilityListener listener) {
    Assertions.checkStateNotNull(controller);
    if (this.legacyControllerVisibilityListener == listener) {
      return;
    }

    if (this.legacyControllerVisibilityListener != null) {
      controller.removeVisibilityListener(this.legacyControllerVisibilityListener);
    }
    this.legacyControllerVisibilityListener = listener;
    if (listener != null) {
      controller.addVisibilityListener(listener);
      setControllerVisibilityListener((ControllerVisibilityListener) null);
    }
  }

  /**
   * Sets the {@link FullscreenButtonClickListener}.
   *
   * <p>Clears any listener set by {@link
   * #setControllerOnFullScreenModeChangedListener(PlayerControlView.OnFullScreenModeChangedListener)}.
   *
   * @param listener The listener to be notified when the fullscreen button is clicked, or null to
   *     remove the current listener and hide the fullscreen button.
   */
  @SuppressWarnings("deprecation") // Calling the deprecated method on PlayerControlView for now.
  public void setFullscreenButtonClickListener(@Nullable FullscreenButtonClickListener listener) {
    Assertions.checkStateNotNull(controller);
    this.fullscreenButtonClickListener = listener;
    controller.setOnFullScreenModeChangedListener(componentListener);
  }

  /**
   * Sets whether the player is currently in fullscreen, this will change the displayed icon.
   *
   * <p>If {@code isFullscreen} is {@code true},
   * {@code @drawable/exo_styled_controls_fullscreen_exit} will be displayed or else
   * {@code @drawable/exo_styled_controls_fullscreen_enter}.
   *
   * @param isFullscreen Whether the player is currently in fullscreen.
   */
  @UnstableApi
  public void setFullscreenButtonState(boolean isFullscreen) {
    Assertions.checkStateNotNull(controller);
    controller.updateIsFullscreen(isFullscreen);
  }

  /**
   * Sets the {@link PlayerControlView.OnFullScreenModeChangedListener}.
   *
   * <p>Clears any listener set by {@link
   * #setFullscreenButtonClickListener(FullscreenButtonClickListener)}.
   *
   * @param listener The listener to be notified when the fullscreen button is clicked, or null to
   *     remove the current listener and hide the fullscreen button.
   * @deprecated Use {@link #setFullscreenButtonClickListener(FullscreenButtonClickListener)}
   *     instead.
   */
  @SuppressWarnings("deprecation") // Forwarding deprecated call
  @Deprecated
  @UnstableApi
  public void setControllerOnFullScreenModeChangedListener(
      @Nullable PlayerControlView.OnFullScreenModeChangedListener listener) {
    Assertions.checkStateNotNull(controller);
    this.fullscreenButtonClickListener = null;
    controller.setOnFullScreenModeChangedListener(listener);
  }

  /**
   * Sets whether the rewind button is shown.
   *
   * @param showRewindButton Whether the rewind button is shown.
   */
  @UnstableApi
  public void setShowRewindButton(boolean showRewindButton) {
    Assertions.checkStateNotNull(controller);
    controller.setShowRewindButton(showRewindButton);
  }

  /**
   * Sets whether the fast forward button is shown.
   *
   * @param showFastForwardButton Whether the fast forward button is shown.
   */
  @UnstableApi
  public void setShowFastForwardButton(boolean showFastForwardButton) {
    Assertions.checkStateNotNull(controller);
    controller.setShowFastForwardButton(showFastForwardButton);
  }

  /**
   * Sets whether the previous button is shown.
   *
   * @param showPreviousButton Whether the previous button is shown.
   */
  @UnstableApi
  public void setShowPreviousButton(boolean showPreviousButton) {
    Assertions.checkStateNotNull(controller);
    controller.setShowPreviousButton(showPreviousButton);
  }

  /**
   * Sets whether the next button is shown.
   *
   * @param showNextButton Whether the next button is shown.
   */
  @UnstableApi
  public void setShowNextButton(boolean showNextButton) {
    Assertions.checkStateNotNull(controller);
    controller.setShowNextButton(showNextButton);
  }

  /**
   * Sets which repeat toggle modes are enabled.
   *
   * @param repeatToggleModes A set of {@link RepeatModeUtil.RepeatToggleModes}.
   */
  @UnstableApi
  public void setRepeatToggleModes(@RepeatModeUtil.RepeatToggleModes int repeatToggleModes) {
    Assertions.checkStateNotNull(controller);
    controller.setRepeatToggleModes(repeatToggleModes);
  }

  /**
   * Sets whether the shuffle button is shown.
   *
   * @param showShuffleButton Whether the shuffle button is shown.
   */
  @UnstableApi
  public void setShowShuffleButton(boolean showShuffleButton) {
    Assertions.checkStateNotNull(controller);
    controller.setShowShuffleButton(showShuffleButton);
  }

  /**
   * Sets whether the subtitle button is shown.
   *
   * @param showSubtitleButton Whether the subtitle button is shown.
   */
  @UnstableApi
  public void setShowSubtitleButton(boolean showSubtitleButton) {
    Assertions.checkStateNotNull(controller);
    controller.setShowSubtitleButton(showSubtitleButton);
  }

  /**
   * Sets whether the vr button is shown.
   *
   * @param showVrButton Whether the vr button is shown.
   */
  @UnstableApi
  public void setShowVrButton(boolean showVrButton) {
    Assertions.checkStateNotNull(controller);
    controller.setShowVrButton(showVrButton);
  }

  /**
   * @deprecated Replace multi-window time bar display by merging source windows together instead,
   *     for example using ExoPlayer's {@code ConcatenatingMediaSource2}.
   */
  @SuppressWarnings("deprecation") // Forwarding to deprecated method.
  @Deprecated
  @UnstableApi
  public void setShowMultiWindowTimeBar(boolean showMultiWindowTimeBar) {
    Assertions.checkStateNotNull(controller);
    controller.setShowMultiWindowTimeBar(showMultiWindowTimeBar);
  }

  /**
   * Sets whether the time bar should {@linkplain Player#seekTo seek} immediately as the user drags
   * the scrubber around (true), or only seek when the user releases the scrubber (false).
   *
   * <p>This can only be used if the {@linkplain #setPlayer connected player} is an instance of
   * {@code androidx.media3.exoplayer.ExoPlayer}.
   */
  @UnstableApi
  public void setTimeBarScrubbingEnabled(boolean timeBarScrubbingEnabled) {
    Assertions.checkStateNotNull(controller);
    controller.setTimeBarScrubbingEnabled(timeBarScrubbingEnabled);
  }

  /**
   * Sets whether a play button is shown if playback is {@linkplain
   * Player#getPlaybackSuppressionReason() suppressed}.
   *
   * <p>The default is {@code true}.
   *
   * @param showPlayButtonIfSuppressed Whether to show a play button if playback is {@linkplain
   *     Player#getPlaybackSuppressionReason() suppressed}.
   */
  @UnstableApi
  public void setShowPlayButtonIfPlaybackIsSuppressed(boolean showPlayButtonIfSuppressed) {
    Assertions.checkStateNotNull(controller);
    controller.setShowPlayButtonIfPlaybackIsSuppressed(showPlayButtonIfSuppressed);
  }

  /**
   * Sets the millisecond positions of extra ad markers relative to the start of the window (or
   * timeline, if in multi-window mode) and whether each extra ad has been played or not. The
   * markers are shown in addition to any ad markers for ads in the player's timeline.
   *
   * @param extraAdGroupTimesMs The millisecond timestamps of the extra ad markers to show, or
   *     {@code null} to show no extra ad markers.
   * @param extraPlayedAdGroups Whether each ad has been played, or {@code null} to show no extra ad
   *     markers.
   */
  @UnstableApi
  public void setExtraAdGroupMarkers(
      @Nullable long[] extraAdGroupTimesMs, @Nullable boolean[] extraPlayedAdGroups) {
    Assertions.checkStateNotNull(controller);
    controller.setExtraAdGroupMarkers(extraAdGroupTimesMs, extraPlayedAdGroups);
  }

  /**
   * Sets the {@link AspectRatioFrameLayout.AspectRatioListener}.
   *
   * @param listener The listener to be notified about aspect ratios changes of the video content or
   *     the content frame.
   */
  @UnstableApi
  public void setAspectRatioListener(
      @Nullable AspectRatioFrameLayout.AspectRatioListener listener) {
    Assertions.checkStateNotNull(contentFrame);
    contentFrame.setAspectRatioListener(listener);
  }

  /**
   * Whether to enable a workaround for the Compose {@code AndroidView} and {@link SurfaceView}
   * compatibility issue described in <a
   * href="https://github.com/androidx/media/issues/1237">androidx/media#1237</a>.
   *
   * <p>This workaround causes issues with shared element transitions in XML views, so is disabled
   * by default (<a href="https://github.com/androidx/media/issues/1594">androidx/media#1594</a>).
   */
  @UnstableApi
  public void setEnableComposeSurfaceSyncWorkaround(boolean enableComposeSurfaceSyncWorkaround) {
    this.enableComposeSurfaceSyncWorkaround = enableComposeSurfaceSyncWorkaround;
  }

  /**
   * Gets the view onto which video is rendered. This is a:
   *
   * <ul>
   *   <li>{@link SurfaceView} by default, or if the {@code surface_type} attribute is set to {@code
   *       surface_view}.
   *   <li>{@link TextureView} if {@code surface_type} is {@code texture_view}.
   *   <li>{@code SphericalGLSurfaceView} if {@code surface_type} is {@code
   *       spherical_gl_surface_view}.
   *   <li>{@code VideoDecoderGLSurfaceView} if {@code surface_type} is {@code
   *       video_decoder_gl_surface_view}.
   *   <li>{@code null} if {@code surface_type} is {@code none}.
   * </ul>
   *
   * @return The {@link SurfaceView}, {@link TextureView}, {@code SphericalGLSurfaceView}, {@code
   *     VideoDecoderGLSurfaceView} or {@code null}.
   */
  @UnstableApi
  @Nullable
  public View getVideoSurfaceView() {
    return surfaceView;
  }

  /**
   * Gets the overlay {@link FrameLayout}, which can be populated with UI elements to show on top of
   * the player.
   *
   * @return The overlay {@link FrameLayout}, or {@code null} if the layout has been customized and
   *     the overlay is not present.
   */
  @UnstableApi
  @Nullable
  public FrameLayout getOverlayFrameLayout() {
    return overlayFrameLayout;
  }

  /**
   * Gets the {@link SubtitleView}.
   *
   * @return The {@link SubtitleView}, or {@code null} if the layout has been customized and the
   *     subtitle view is not present.
   */
  @UnstableApi
  @Nullable
  public SubtitleView getSubtitleView() {
    return subtitleView;
  }

  @Override
  public boolean performClick() {
    toggleControllerVisibility();
    return super.performClick();
  }

  @Override
  public boolean onTrackballEvent(MotionEvent ev) {
    if (!useController() || player == null) {
      return false;
    }
    maybeShowController(true);
    return true;
  }

  /**
   * Should be called when the player is visible to the user, if the {@code surface_type} extends
   * {@link GLSurfaceView}. It is the counterpart to {@link #onPause()}.
   *
   * <p>This method should typically be called in {@code Activity.onStart()}, or {@code
   * Activity.onResume()} for API versions &lt;= 23.
   */
  public void onResume() {
    if (surfaceView instanceof GLSurfaceView) {
      ((GLSurfaceView) surfaceView).onResume();
    }
  }

  /**
   * Should be called when the player is no longer visible to the user, if the {@code surface_type}
   * extends {@link GLSurfaceView}. It is the counterpart to {@link #onResume()}.
   *
   * <p>This method should typically be called in {@code Activity.onStop()}, or {@code
   * Activity.onPause()} for API versions &lt;= 23.
   */
  public void onPause() {
    if (surfaceView instanceof GLSurfaceView) {
      ((GLSurfaceView) surfaceView).onPause();
    }
  }

  /**
   * Called when there's a change in the desired aspect ratio of the content frame. The default
   * implementation sets the aspect ratio of the content frame to the specified value.
   *
   * @param contentFrame The content frame, or {@code null}.
   * @param aspectRatio The aspect ratio to apply.
   */
  @UnstableApi
  protected void onContentAspectRatioChanged(
      @Nullable AspectRatioFrameLayout contentFrame, float aspectRatio) {
    if (contentFrame != null) {
      contentFrame.setAspectRatio(aspectRatio);
    }
  }

  // AdsLoader.AdViewProvider implementation.

  @Override
  public ViewGroup getAdViewGroup() {
    return Assertions.checkStateNotNull(
        adOverlayFrameLayout, "exo_ad_overlay must be present for ad playback");
  }

  @Override
  public List<AdOverlayInfo> getAdOverlayInfos() {
    List<AdOverlayInfo> overlayViews = new ArrayList<>();
    if (overlayFrameLayout != null) {
      overlayViews.add(
          new AdOverlayInfo.Builder(overlayFrameLayout, AdOverlayInfo.PURPOSE_NOT_VISIBLE)
              .setDetailedReason("Transparent overlay does not impact viewability")
              .build());
    }
    if (controller != null) {
      overlayViews.add(
          new AdOverlayInfo.Builder(controller, AdOverlayInfo.PURPOSE_CONTROLS).build());
    }
    return ImmutableList.copyOf(overlayViews);
  }

  // Internal methods.

  @EnsuresNonNullIf(expression = "controller", result = true)
  private boolean useController() {
    if (useController) {
      Assertions.checkStateNotNull(controller);
      return true;
    }
    return false;
  }

  private boolean useArtwork() {
    if (artworkDisplayMode != ARTWORK_DISPLAY_MODE_OFF) {
      Assertions.checkStateNotNull(artworkView);
      return true;
    }
    return false;
  }

  private void toggleControllerVisibility() {
    if (!useController() || player == null) {
      return;
    }
    if (!controller.isFullyVisible()) {
      maybeShowController(true);
    } else if (controllerHideOnTouch) {
      controller.hide();
    }
  }

  /** Shows the playback controls, but only if forced or shown indefinitely. */
  private void maybeShowController(boolean isForced) {
    if (isPlayingAd() && controllerHideDuringAds) {
      return;
    }
    if (useController()) {
      boolean wasShowingIndefinitely =
          controller.isFullyVisible() && controller.getShowTimeoutMs() <= 0;
      boolean shouldShowIndefinitely = shouldShowControllerIndefinitely();
      if (isForced || wasShowingIndefinitely || shouldShowIndefinitely) {
        showController(shouldShowIndefinitely);
      }
    }
  }

  private boolean shouldShowControllerIndefinitely() {
    if (player == null) {
      return true;
    }
    int playbackState = player.getPlaybackState();
    return controllerAutoShow
        && (!player.isCommandAvailable(COMMAND_GET_TIMELINE)
            || !player.getCurrentTimeline().isEmpty())
        && (playbackState == Player.STATE_IDLE
            || playbackState == Player.STATE_ENDED
            || !checkNotNull(player).getPlayWhenReady());
  }

  private void showController(boolean showIndefinitely) {
    if (!useController()) {
      return;
    }
    controller.setShowTimeoutMs(showIndefinitely ? 0 : controllerShowTimeoutMs);
    controller.show();
  }

  private boolean isPlayingAd() {
    return player != null
        && player.isCommandAvailable(COMMAND_GET_CURRENT_MEDIA_ITEM)
        && player.isPlayingAd()
        && player.getPlayWhenReady();
  }

  private void updateForCurrentTrackSelections(boolean isNewPlayer) {
    @Nullable Player player = this.player;

    // Unless configured to keep, clear output completely when tracks are empty or for a new player.
    boolean hasTracks =
        player != null
            && player.isCommandAvailable(COMMAND_GET_TRACKS)
            && !player.getCurrentTracks().isEmpty();
    if (!keepContentOnPlayerReset && (!hasTracks || isNewPlayer)) {
      hideArtwork();
      closeShutter();
      hideAndClearImage();
    }
    if (!hasTracks) {
      // Nothing else to check.
      return;
    }

    boolean hasSelectedVideoTrack = hasSelectedVideoTrack();
    boolean hasSelectedImageTrack = hasSelectedImageTrack();

    // When video and image are disabled, close shutter and clear image. Do nothing if one or both
    // are enabled to keep the current state (previous frame or video). Once ready, the first frame
    // or image will update the view.
    if (!hasSelectedVideoTrack && !hasSelectedImageTrack) {
      closeShutter();
      hideAndClearImage();
    }
    // Exception: If both were enabled (shutter open and image set) and we switch to one only, we
    // can hide the other output immediately as no further callbacks will arrive.
    boolean wasVideoAndImageSet =
        shutterView != null && shutterView.getVisibility() == INVISIBLE && isImageSet();
    if (hasSelectedImageTrack && !hasSelectedVideoTrack && wasVideoAndImageSet) {
      closeShutter();
      showImage();
    } else if (hasSelectedVideoTrack && !hasSelectedImageTrack && wasVideoAndImageSet) {
      hideAndClearImage();
    }

    // Display artwork if enabled and available, else hide it.
    boolean shouldShowArtwork = !hasSelectedVideoTrack && !hasSelectedImageTrack && useArtwork();
    if (shouldShowArtwork) {
      if (setArtworkFromMediaMetadata(player)) {
        return;
      }
      if (setDrawableArtwork(defaultArtwork)) {
        return;
      }
    }
    // Artwork disabled or unavailable.
    hideArtwork();
  }

  private boolean setArtworkFromMediaMetadata(@Nullable Player player) {
    if (player == null || !player.isCommandAvailable(COMMAND_GET_METADATA)) {
      return false;
    }
    MediaMetadata mediaMetadata = player.getMediaMetadata();
    if (mediaMetadata.artworkData == null) {
      return false;
    }
    Bitmap bitmap =
        BitmapFactory.decodeByteArray(
            mediaMetadata.artworkData, /* offset= */ 0, mediaMetadata.artworkData.length);
    return setDrawableArtwork(new BitmapDrawable(getResources(), bitmap));
  }

  private boolean setDrawableArtwork(@Nullable Drawable drawable) {
    if (artworkView != null && drawable != null) {
      int drawableWidth = drawable.getIntrinsicWidth();
      int drawableHeight = drawable.getIntrinsicHeight();
      if (drawableWidth > 0 && drawableHeight > 0) {
        float artworkLayoutAspectRatio = (float) drawableWidth / drawableHeight;
        ImageView.ScaleType scaleStyle = ImageView.ScaleType.FIT_XY;
        if (artworkDisplayMode == ARTWORK_DISPLAY_MODE_FILL) {
          artworkLayoutAspectRatio = (float) getWidth() / getHeight();
          scaleStyle = ImageView.ScaleType.CENTER_CROP;
        }
        onContentAspectRatioChanged(contentFrame, artworkLayoutAspectRatio);
        artworkView.setScaleType(scaleStyle);
        artworkView.setImageDrawable(drawable);
        artworkView.setVisibility(VISIBLE);
        return true;
      }
    }
    return false;
  }

  private void hideArtwork() {
    if (artworkView != null) {
      artworkView.setImageResource(android.R.color.transparent); // Clears any bitmap reference.
      artworkView.setVisibility(INVISIBLE);
    }
  }

  private boolean hasSelectedImageTrack() {
    @Nullable Player player = this.player;
    return player != null
        && imageOutput != null
        && player.isCommandAvailable(COMMAND_GET_TRACKS)
        && player.getCurrentTracks().isTypeSelected(C.TRACK_TYPE_IMAGE);
  }

  private boolean hasSelectedVideoTrack() {
    @Nullable Player player = this.player;
    return player != null
        && player.isCommandAvailable(COMMAND_GET_TRACKS)
        && player.getCurrentTracks().isTypeSelected(C.TRACK_TYPE_VIDEO);
  }

  private boolean isImageSet() {
    if (imageView == null) {
      return false;
    }
    @Nullable Drawable drawable = imageView.getDrawable();
    // The transparent placeholder has an alpha value of 0, but BitmapDrawables never have alpha 0.
    return drawable != null && drawable.getAlpha() != 0;
  }

  private void setImage(Drawable drawable) {
    if (imageView == null) {
      return;
    }
    imageView.setImageDrawable(drawable);
    updateImageViewAspectRatio();
  }

  private void updateImageViewAspectRatio() {
    if (imageView == null) {
      return;
    }
    @Nullable Drawable drawable = imageView.getDrawable();
    if (drawable == null) {
      return;
    }
    int drawableWidth = drawable.getIntrinsicWidth();
    int drawableHeight = drawable.getIntrinsicHeight();
    if (drawableWidth <= 0 || drawableHeight <= 0) {
      return;
    }
    float drawableLayoutAspectRatio = (float) drawableWidth / drawableHeight;
    ImageView.ScaleType scaleStyle = ImageView.ScaleType.FIT_XY;
    if (imageDisplayMode == IMAGE_DISPLAY_MODE_FILL) {
      drawableLayoutAspectRatio = (float) getWidth() / getHeight();
      scaleStyle = ImageView.ScaleType.CENTER_CROP;
    }
    if (imageView.getVisibility() == VISIBLE) {
      onContentAspectRatioChanged(contentFrame, drawableLayoutAspectRatio);
    }
    imageView.setScaleType(scaleStyle);
  }

  private void hideAndClearImage() {
    hideImage();
    if (imageView != null) {
      imageView.setImageResource(android.R.color.transparent);
    }
  }

  private void showImage() {
    if (imageView != null) {
      imageView.setVisibility(VISIBLE);
      updateImageViewAspectRatio();
    }
  }

  private void hideImage() {
    if (imageView != null) {
      imageView.setVisibility(INVISIBLE);
    }
  }

  private void onImageAvailable(Bitmap bitmap) {
    mainLooperHandler.post(
        () -> {
          setImage(new BitmapDrawable(getResources(), bitmap));
          if (!hasSelectedVideoTrack()) {
            showImage();
            closeShutter();
          }
        });
  }

  private void closeShutter() {
    if (shutterView != null) {
      shutterView.setVisibility(View.VISIBLE);
    }
  }

  private void updateBuffering() {
    if (bufferingView != null) {
      boolean showBufferingSpinner =
          player != null
              && player.getPlaybackState() == Player.STATE_BUFFERING
              && (showBuffering == SHOW_BUFFERING_ALWAYS
                  || (showBuffering == SHOW_BUFFERING_WHEN_PLAYING && player.getPlayWhenReady()));
      bufferingView.setVisibility(showBufferingSpinner ? View.VISIBLE : View.GONE);
    }
  }

  private void updateErrorMessage() {
    if (errorMessageView != null) {
      if (customErrorMessage != null) {
        errorMessageView.setText(customErrorMessage);
        errorMessageView.setVisibility(View.VISIBLE);
        return;
      }
      @Nullable PlaybackException error = player != null ? player.getPlayerError() : null;
      if (error != null && errorMessageProvider != null) {
        CharSequence errorMessage = errorMessageProvider.getErrorMessage(error).second;
        errorMessageView.setText(errorMessage);
        errorMessageView.setVisibility(View.VISIBLE);
      } else {
        errorMessageView.setVisibility(View.GONE);
      }
    }
  }

  private void updateContentDescription() {
    if (controller == null || !useController) {
      setContentDescription(/* contentDescription= */ null);
    } else if (controller.isFullyVisible()) {
      setContentDescription(
          /* contentDescription= */ controllerHideOnTouch
              ? getResources().getString(R.string.exo_controls_hide)
              : null);
    } else {
      setContentDescription(
          /* contentDescription= */ getResources().getString(R.string.exo_controls_show));
    }
  }

  private void updateControllerVisibility() {
    if (isPlayingAd() && controllerHideDuringAds) {
      hideController();
    } else {
      maybeShowController(false);
    }
  }

  private void updateAspectRatio() {
    VideoSize videoSize = player != null ? player.getVideoSize() : VideoSize.UNKNOWN;
    int width = videoSize.width;
    int height = videoSize.height;
    float videoAspectRatio =
        (height == 0 || width == 0) ? 0 : (width * videoSize.pixelWidthHeightRatio) / height;
    onContentAspectRatioChanged(
        contentFrame, surfaceViewIgnoresVideoAspectRatio ? 0 : videoAspectRatio);
  }

  @Override
  protected void dispatchDraw(Canvas canvas) {
    super.dispatchDraw(canvas);
    if (SDK_INT == 34 && surfaceSyncGroupV34 != null && enableComposeSurfaceSyncWorkaround) {
      surfaceSyncGroupV34.maybeMarkSyncReadyAndClear();
    }
  }

  private static void configureEditModeLogo(Context context, Resources resources, ImageView logo) {
    logo.setImageDrawable(getDrawable(context, resources, R.drawable.exo_edit_mode_logo));
    logo.setBackgroundColor(resources.getColor(R.color.exo_edit_mode_background_color, null));
  }

  @SuppressWarnings("ResourceType")
  private static void setResizeModeRaw(AspectRatioFrameLayout aspectRatioFrame, int resizeMode) {
    aspectRatioFrame.setResizeMode(resizeMode);
  }

  @SuppressLint("InlinedApi")
  private boolean isDpadKey(int keyCode) {
    return keyCode == KeyEvent.KEYCODE_DPAD_UP
        || keyCode == KeyEvent.KEYCODE_DPAD_UP_RIGHT
        || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
        || keyCode == KeyEvent.KEYCODE_DPAD_DOWN_RIGHT
        || keyCode == KeyEvent.KEYCODE_DPAD_DOWN
        || keyCode == KeyEvent.KEYCODE_DPAD_DOWN_LEFT
        || keyCode == KeyEvent.KEYCODE_DPAD_LEFT
        || keyCode == KeyEvent.KEYCODE_DPAD_UP_LEFT
        || keyCode == KeyEvent.KEYCODE_DPAD_CENTER;
  }

  // Implementing the deprecated PlayerControlView.VisibilityListener and
  // PlayerControlView.OnFullScreenModeChangedListener for now.
  @SuppressWarnings("deprecation")
  private final class ComponentListener
      implements Player.Listener,
          OnClickListener,
          PlayerControlView.VisibilityListener,
          PlayerControlView.OnFullScreenModeChangedListener {

    private final Period period;
    private @Nullable Object lastPeriodUidWithTracks;

    public ComponentListener() {
      period = new Period();
    }

    // Player.Listener implementation

    @Override
    public void onCues(CueGroup cueGroup) {
      if (subtitleView != null) {
        subtitleView.setCues(cueGroup.cues);
      }
    }

    @Override
    public void onVideoSizeChanged(VideoSize videoSize) {
      if (videoSize.equals(VideoSize.UNKNOWN)
          || player == null
          || player.getPlaybackState() == Player.STATE_IDLE) {
        return;
      }
      updateAspectRatio();
    }

    @Override
    public void onSurfaceSizeChanged(int width, int height) {
      if (SDK_INT == 34
          && surfaceView instanceof SurfaceView
          && enableComposeSurfaceSyncWorkaround) {
        // Register a SurfaceSyncGroup to work around https://github.com/androidx/media/issues/1237
        // (only present on API 34, fixed on API 35).
        checkNotNull(surfaceSyncGroupV34)
            .postRegister(
                mainLooperHandler, (SurfaceView) surfaceView, PlayerView.this::invalidate);
      }
    }

    @Override
    public void onRenderedFirstFrame() {
      if (shutterView != null) {
        shutterView.setVisibility(INVISIBLE);
        if (hasSelectedImageTrack()) {
          hideImage();
        } else {
          hideAndClearImage();
        }
      }
    }

    @Override
    public void onTracksChanged(Tracks tracks) {
      // Suppress the update if transitioning to an unprepared period within the same window. This
      // is necessary to avoid closing the shutter when such a transition occurs. See:
      // https://github.com/google/ExoPlayer/issues/5507.
      Player player = checkNotNull(PlayerView.this.player);
      Timeline timeline =
          player.isCommandAvailable(COMMAND_GET_TIMELINE)
              ? player.getCurrentTimeline()
              : Timeline.EMPTY;
      if (timeline.isEmpty()) {
        lastPeriodUidWithTracks = null;
      } else if (player.isCommandAvailable(COMMAND_GET_TRACKS)
          && !player.getCurrentTracks().isEmpty()) {
        lastPeriodUidWithTracks =
            timeline.getPeriod(player.getCurrentPeriodIndex(), period, /* setIds= */ true).uid;
      } else if (lastPeriodUidWithTracks != null) {
        int lastPeriodIndexWithTracks = timeline.getIndexOfPeriod(lastPeriodUidWithTracks);
        if (lastPeriodIndexWithTracks != C.INDEX_UNSET) {
          int lastWindowIndexWithTracks =
              timeline.getPeriod(lastPeriodIndexWithTracks, period).windowIndex;
          if (player.getCurrentMediaItemIndex() == lastWindowIndexWithTracks) {
            // We're in the same media item. Suppress the update.
            return;
          }
        }
        lastPeriodUidWithTracks = null;
      }

      updateForCurrentTrackSelections(/* isNewPlayer= */ false);
    }

    @Override
    public void onPlaybackStateChanged(@Player.State int playbackState) {
      updateBuffering();
      updateErrorMessage();
      updateControllerVisibility();
    }

    @Override
    public void onPlayWhenReadyChanged(
        boolean playWhenReady, @Player.PlayWhenReadyChangeReason int reason) {
      updateBuffering();
      updateControllerVisibility();
    }

    @Override
    public void onPositionDiscontinuity(
        Player.PositionInfo oldPosition,
        Player.PositionInfo newPosition,
        @DiscontinuityReason int reason) {
      if (isPlayingAd() && controllerHideDuringAds) {
        hideController();
      }
    }

    // OnClickListener implementation

    @Override
    public void onClick(View view) {
      toggleControllerVisibility();
    }

    // PlayerControlView.VisibilityListener implementation

    @Override
    public void onVisibilityChange(int visibility) {
      updateContentDescription();
      if (controllerVisibilityListener != null) {
        controllerVisibilityListener.onVisibilityChanged(visibility);
      }
    }

    // PlayerControlView.OnFullScreenModeChangedListener implementation

    @Override
    public void onFullScreenModeChanged(boolean isFullscreen) {
      if (fullscreenButtonClickListener != null) {
        fullscreenButtonClickListener.onFullscreenButtonClick(isFullscreen);
      }
    }
  }

  @RequiresApi(34)
  private static class Api34 {

    public static void setSurfaceLifecycleToFollowsAttachment(SurfaceView surfaceView) {
      surfaceView.setSurfaceLifecycle(SurfaceView.SURFACE_LIFECYCLE_FOLLOWS_ATTACHMENT);
    }
  }

  @RequiresApi(34)
  private static final class SurfaceSyncGroupCompatV34 {

    @Nullable SurfaceSyncGroup surfaceSyncGroup;

    public void postRegister(
        Handler mainLooperHandler, SurfaceView surfaceView, Runnable invalidate) {
      mainLooperHandler.post(
          () -> {
            @Nullable
            AttachedSurfaceControl rootSurfaceControl = surfaceView.getRootSurfaceControl();
            if (rootSurfaceControl == null) {
              // The SurfaceView isn't attached to a window, so don't apply the workaround.
              return;
            }
            surfaceSyncGroup = new SurfaceSyncGroup("exo-sync-b-334901521");
            Assertions.checkState(surfaceSyncGroup.add(rootSurfaceControl, () -> {}));
            invalidate.run();
            rootSurfaceControl.applyTransactionOnDraw(new SurfaceControl.Transaction());
          });
    }

    public void maybeMarkSyncReadyAndClear() {
      if (surfaceSyncGroup != null) {
        surfaceSyncGroup.markSyncReady();
        surfaceSyncGroup = null;
      }
    }
  }
}
