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
package androidx.media3.session;

import static android.os.Build.VERSION.SDK_INT;
import static androidx.media3.common.util.Assertions.checkNotNull;

import android.app.ForegroundServiceStartNotAllowedException;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.view.KeyEvent;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.content.ContextCompat;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.UnstableApi;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * A media button receiver receives hardware media playback button intent, such as those sent by
 * wired and wireless headsets.
 *
 * <p>You can add this MediaButtonReceiver to your app by adding it directly to your
 * AndroidManifest.xml:
 *
 * <pre>
 * &lt;receiver
 *     android:name="androidx.media3.session.MediaButtonReceiver"
 *     android:exported="true"&gt;
 *   &lt;intent-filter&gt;
 *     &lt;action android:name="android.intent.action.MEDIA_BUTTON" /&gt;
 *   &lt;/intent-filter&gt;
 * &lt;/receiver&gt;
 * </pre>
 *
 * <p>Apps that add this receiver to the manifest, must implement {@link
 * MediaSession.Callback#onPlaybackResumption} or active automatic playback resumption (Note: If you
 * choose to make this receiver start your own service that is not a {@link MediaSessionService} or
 * {@link MediaLibraryService}, then you need to fulfill all requirements around starting a service
 * in the foreground on all API levels your app should properly work on).
 *
 * <h2>Service discovery</h2>
 *
 * <p>This class assumes you have a {@link Service} in your app's manifest that controls media
 * playback via a {@link MediaSession}. Once a key event is received by this receiver, it tries to
 * find a {@link Service} that can handle the action {@link Intent#ACTION_MEDIA_BUTTON}, {@link
 * MediaSessionService#SERVICE_INTERFACE} or {@link MediaSessionService#SERVICE_INTERFACE}. If an
 * appropriate service is found, this class starts the service as a foreground service and sends the
 * key event to the service by an {@link Intent} with action {@link Intent#ACTION_MEDIA_BUTTON}. If
 * neither is available or more than one valid service is found for one of the actions, an {@link
 * IllegalStateException} is thrown.
 *
 * <h3>Service handling ACTION_MEDIA_BUTTON</h3>
 *
 * <p>A service can receive a key event by including an intent filter that handles {@code
 * android.intent.action.MEDIA_BUTTON}.
 *
 * <pre>
 * &lt;service android:name="com.example.android.MediaPlaybackService" &gt;
 *   &lt;intent-filter&gt;
 *     &lt;action android:name="android.intent.action.MEDIA_BUTTON" /&gt;
 *   &lt;/intent-filter&gt;
 * &lt;/service&gt;
 * </pre>
 *
 * <h3>Service handling action {@link MediaSessionService} or {@link MediaLibraryService}</h3>
 *
 * <p>If you are using a {@link MediaSessionService} or {@link MediaLibraryService}, the service
 * interface name is already used as the intent action. In this case, no further configuration is
 * required.
 *
 * <pre>
 * &lt;service android:name="com.example.android.MediaPlaybackService" &gt;
 *   &lt;intent-filter&gt;
 *     &lt;action android:name="androidx.media3.session.MediaLibraryService" /&gt;
 *   &lt;/intent-filter&gt;
 * &lt;/service&gt;
 * </pre>
 */
@UnstableApi
public class MediaButtonReceiver extends BroadcastReceiver {

  private static final String TAG = "MediaButtonReceiver";
  private static final String[] ACTIONS = {
    Intent.ACTION_MEDIA_BUTTON,
    MediaLibraryService.SERVICE_INTERFACE,
    MediaSessionService.SERVICE_INTERFACE
  };

  /**
   * {@inheritDoc}
   *
   * <p>Apps can override this method if the default behaviour doesn't match their needs. When doing
   * that, an app would eventually call {@link #handleIntentAndMaybeStartTheService(Context,
   * Intent)} to continue the process to start the service. If you are not calling this method, then
   * using this class probably is not needed. Instead, a custom implementation of {@link
   * BroadcastReceiver} can be provided in the manifest for the action {@link
   * Intent#ACTION_MEDIA_BUTTON}.
   *
   * <p>The recommended approach is to use this receiver without overriding this method, but instead
   * use {@link #shouldStartForegroundService(Context, Intent)} to indicate whether to start the
   * service or not. When overriding this method, we can't provide further support because the
   * strongly recommended way to handle the intent is using the default implementation.
   */
  @Override
  public void onReceive(Context context, @Nullable Intent intent) {
    handleIntentAndMaybeStartTheService(context, intent);
  }

  /**
   * Handles the intent and starts the service if {@link #shouldStartForegroundService} indicates to
   * do so.
   *
   * @param context The context.
   * @param intent The intent.
   */
  @UnstableApi
  protected final void handleIntentAndMaybeStartTheService(
      Context context, @Nullable Intent intent) {
    if (intent == null
        || !Objects.equals(intent.getAction(), Intent.ACTION_MEDIA_BUTTON)
        || !intent.hasExtra(Intent.EXTRA_KEY_EVENT)) {
      android.util.Log.d(TAG, "Ignore unsupported intent: " + intent);
      return;
    }

    @Nullable
    KeyEvent keyEvent = checkNotNull(intent.getExtras()).getParcelable(Intent.EXTRA_KEY_EVENT);
    if (keyEvent == null
        || keyEvent.getAction() != KeyEvent.ACTION_DOWN
        || keyEvent.getRepeatCount() != 0) {
      // Only handle the intent once with the earliest key event that arrives.
      return;
    }
    if (SDK_INT >= 26) {
      if (keyEvent.getKeyCode() != KeyEvent.KEYCODE_MEDIA_PLAY
          && keyEvent.getKeyCode() != KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
          && keyEvent.getKeyCode() != KeyEvent.KEYCODE_HEADSETHOOK) {
        // Starting with Android 8 (API 26), the service must be started immediately in the
        // foreground when being started. Also starting with Android 8, the system sends media
        // button intents to this receiver only when the session is released or not active, meaning
        // the service is not running. Hence we only accept a PLAY command here that ensures that
        // playback is started and the MediaSessionService/MediaLibraryService is put into the
        // foreground (see https://developer.android.com/media/legacy/media-buttons and
        // https://developer.android.com/about/versions/oreo/android-8.0-changes#back-all).
        android.util.Log.w(
            TAG,
            "Ignore key event that is not a `play` command on API 26 or above to avoid an"
                + " 'ForegroundServiceDidNotStartInTimeException'");
        return;
      }
    }

    for (String action : ACTIONS) {
      ComponentName mediaButtonServiceComponentName = getServiceComponentByAction(context, action);
      if (mediaButtonServiceComponentName != null) {
        Intent serviceIntent = new Intent();
        serviceIntent.setComponent(mediaButtonServiceComponentName);
        serviceIntent.fillIn(intent, 0);
        if (!shouldStartForegroundService(context, serviceIntent)) {
          Log.i(
              TAG,
              "onReceive(Intent) does not start the media button event target service into the"
                  + " foreground on app request: "
                  + mediaButtonServiceComponentName.getClassName());
          return;
        }
        try {
          ContextCompat.startForegroundService(context, serviceIntent);
        } catch (/* ForegroundServiceStartNotAllowedException */ IllegalStateException e) {
          if (SDK_INT >= 31 && Api31.instanceOfForegroundServiceStartNotAllowedException(e)) {
            onForegroundServiceStartNotAllowedException(
                context, serviceIntent, Api31.castToForegroundServiceStartNotAllowedException(e));
          } else {
            throw e;
          }
        }
        return;
      }
    }
    throw new IllegalStateException(
        "Could not find any Service that handles any of the actions " + Arrays.toString(ACTIONS));
  }

  /**
   * Returns whether to start the {@linkplain Intent#getComponent() media button event target
   * service} into the foreground.
   *
   * <p>Returns true by default. Apps can override this method to decide to not start a service when
   * receiving an event with {@link KeyEvent#KEYCODE_MEDIA_PLAY} or {@link
   * KeyEvent#KEYCODE_MEDIA_PLAY_PAUSE} that should be suppressed.
   *
   * <p>Note: Once the service is started into the foreground by the receiver, the app must start
   * playback to get into the foreground or the system will crash the service with a {@code
   * ForegroundServiceDidNotStartInTimeException} or an {@link IllegalStateException}.
   *
   * @param context The {@link Context} that {@linkplain #onReceive(Context, Intent) was received by
   *     the media button event receiver}.
   * @param intent The intent that will be used by {@linkplain
   *     Context#startForegroundService(Intent) for starting the foreground service}.
   * @return true if the service should be {@linkplain ContextCompat#startForegroundService(Context,
   *     Intent) started as a foreground service}. If false is returned the service is not started
   *     and the receiver call is a no-op.
   */
  protected boolean shouldStartForegroundService(Context context, Intent intent) {
    return true;
  }

  /**
   * @deprecated Use {@link #onForegroundServiceStartNotAllowedException(Context, Intent,
   *     ForegroundServiceStartNotAllowedException)} instead.
   */
  @Deprecated
  @RequiresApi(31)
  protected void onForegroundServiceStartNotAllowedException(
      Intent intent, ForegroundServiceStartNotAllowedException e) {
    Log.e(
        TAG,
        "caught exception when trying to start a foreground service from the "
            + "background: "
            + e.getMessage());
  }

  /**
   * This method is called when an exception is thrown when calling {@link
   * Context#startForegroundService(Intent)} as a result of receiving a media button event.
   *
   * <p>By default, this method only logs the exception and it can be safely overridden. Apps that
   * find that such a media button event has been legitimately sent, may choose to override this
   * method and take the opportunity to post a notification from where the user journey can
   * continue.
   *
   * <p>This exception can be thrown if a broadcast media button event is received and a media
   * service is found in the manifest that is registered to handle {@link
   * Intent#ACTION_MEDIA_BUTTON}. If this happens on API 31+ and the app is in the background then
   * an exception is thrown.
   *
   * <p>With the exception of devices that are running API 20 and below, a media button intent is
   * only required to be sent to this receiver for a Bluetooth media button event that wants to
   * restart the service. In such a case the app gets an exemption and is allowed to start the
   * foreground service. In this case this method will never be called.
   *
   * <p>In all other cases of attempting to start a Media3 service or to send a media button event,
   * apps must use a {@link MediaBrowser} or {@link MediaController} to bind to the service instead
   * of broadcasting an intent.
   *
   * @param context The broadcast receiver's {@linkplain Context}
   * @param intent The intent that was used {@linkplain Context#startForegroundService(Intent) for
   *     starting the foreground service}.
   * @param e The exception thrown by the system and caught by this broadcast receiver.
   */
  @SuppressWarnings("deprecation")
  @RequiresApi(31)
  protected void onForegroundServiceStartNotAllowedException(
      Context context, Intent intent, ForegroundServiceStartNotAllowedException e) {
    onForegroundServiceStartNotAllowedException(intent, e);
  }

  @SuppressWarnings("QueryPermissionsNeeded") // Needs to be provided in the app manifest.
  @Nullable
  private static ComponentName getServiceComponentByAction(Context context, String action) {
    PackageManager pm = context.getPackageManager();
    Intent queryIntent = new Intent(action);
    queryIntent.setPackage(context.getPackageName());
    List<ResolveInfo> resolveInfos = pm.queryIntentServices(queryIntent, /* flags= */ 0);
    if (resolveInfos.size() == 1) {
      ResolveInfo resolveInfo = resolveInfos.get(0);
      return new ComponentName(resolveInfo.serviceInfo.packageName, resolveInfo.serviceInfo.name);
    } else if (resolveInfos.isEmpty()) {
      return null;
    } else {
      throw new IllegalStateException(
          "Expected 1 service that handles " + action + ", found " + resolveInfos.size());
    }
  }

  @RequiresApi(31)
  private static final class Api31 {
    /**
     * Returns true if the passed exception is a {@link ForegroundServiceStartNotAllowedException}.
     */
    public static boolean instanceOfForegroundServiceStartNotAllowedException(
        IllegalStateException e) {
      return e instanceof ForegroundServiceStartNotAllowedException;
    }

    /**
     * Casts the {@link IllegalStateException} to a {@link
     * ForegroundServiceStartNotAllowedException} and throws an exception if the cast fails.
     */
    public static ForegroundServiceStartNotAllowedException
        castToForegroundServiceStartNotAllowedException(IllegalStateException e) {
      return (ForegroundServiceStartNotAllowedException) e;
    }
  }
}
