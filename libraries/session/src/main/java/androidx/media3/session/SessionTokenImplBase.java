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
package androidx.media3.session;

import static androidx.media3.common.util.Assertions.checkArgument;
import static androidx.media3.common.util.Assertions.checkNotEmpty;
import static androidx.media3.common.util.Assertions.checkNotNull;

import android.content.ComponentName;
import android.media.session.MediaSession;
import android.os.Bundle;
import android.os.IBinder;
import android.text.TextUtils;
import androidx.annotation.Nullable;
import androidx.core.app.BundleCompat;
import androidx.media3.common.util.Util;
import java.util.Objects;

/* package */ final class SessionTokenImplBase implements SessionToken.SessionTokenImpl {

  private final int uid;

  private final @SessionToken.TokenType int type;

  private final int libraryVersion;

  private final int interfaceVersion;

  private final String packageName;

  private final String serviceName;

  @Nullable private final ComponentName componentName;

  @Nullable private final IBinder iSession;

  private final Bundle extras;

  @Nullable private final MediaSession.Token platformToken;

  public SessionTokenImplBase(ComponentName serviceComponent, int uid, int type) {
    this(
        uid,
        type,
        SessionToken.UNKNOWN_SESSION_VERSION,
        SessionToken.UNKNOWN_INTERFACE_VERSION,
        checkNotNull(serviceComponent).getPackageName(),
        /* serviceName= */ serviceComponent.getClassName(),
        /* componentName= */ serviceComponent,
        /* iSession= */ null,
        /* extras= */ Bundle.EMPTY,
        /* platformToken= */ null);
  }

  public SessionTokenImplBase(
      int uid,
      int type,
      int libraryVersion,
      int interfaceVersion,
      String packageName,
      IMediaSession iSession,
      Bundle tokenExtras,
      @Nullable MediaSession.Token platformToken) {
    this(
        uid,
        type,
        libraryVersion,
        interfaceVersion,
        checkNotNull(packageName),
        /* serviceName= */ "",
        /* componentName= */ null,
        iSession.asBinder(),
        checkNotNull(tokenExtras),
        platformToken);
  }

  private SessionTokenImplBase(
      int uid,
      int type,
      int libraryVersion,
      int interfaceVersion,
      String packageName,
      String serviceName,
      @Nullable ComponentName componentName,
      @Nullable IBinder iSession,
      Bundle extras,
      @Nullable MediaSession.Token platformToken) {
    this.uid = uid;
    this.type = type;
    this.libraryVersion = libraryVersion;
    this.interfaceVersion = interfaceVersion;
    this.packageName = packageName;
    this.serviceName = serviceName;
    this.componentName = componentName;
    this.iSession = iSession;
    this.extras = extras;
    this.platformToken = platformToken;
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        uid,
        type,
        libraryVersion,
        interfaceVersion,
        packageName,
        serviceName,
        componentName,
        iSession,
        platformToken);
  }

  @Override
  public boolean equals(@Nullable Object obj) {
    if (!(obj instanceof SessionTokenImplBase)) {
      return false;
    }
    SessionTokenImplBase other = (SessionTokenImplBase) obj;
    return uid == other.uid
        && type == other.type
        && libraryVersion == other.libraryVersion
        && interfaceVersion == other.interfaceVersion
        && TextUtils.equals(packageName, other.packageName)
        && TextUtils.equals(serviceName, other.serviceName)
        && Objects.equals(componentName, other.componentName)
        && Objects.equals(iSession, other.iSession)
        && Objects.equals(platformToken, other.platformToken);
  }

  @Override
  public String toString() {
    return "SessionToken {pkg="
        + packageName
        + " type="
        + type
        + " libraryVersion="
        + libraryVersion
        + " interfaceVersion="
        + interfaceVersion
        + " service="
        + serviceName
        + " IMediaSession="
        + iSession
        + " extras="
        + extras
        + "}";
  }

  @Override
  public boolean isLegacySession() {
    return false;
  }

  @Override
  public int getUid() {
    return uid;
  }

  @Override
  public String getPackageName() {
    return packageName;
  }

  @Override
  public String getServiceName() {
    return serviceName;
  }

  @Override
  @Nullable
  public ComponentName getComponentName() {
    return componentName;
  }

  @Override
  @SessionToken.TokenType
  public int getType() {
    return type;
  }

  @Override
  public int getLibraryVersion() {
    return libraryVersion;
  }

  @Override
  public int getInterfaceVersion() {
    return interfaceVersion;
  }

  @Override
  public Bundle getExtras() {
    return new Bundle(extras);
  }

  @Override
  @Nullable
  public Object getBinder() {
    return iSession;
  }

  @Nullable
  @Override
  public MediaSession.Token getPlatformToken() {
    return platformToken;
  }

  private static final String FIELD_UID = Util.intToStringMaxRadix(0);
  private static final String FIELD_TYPE = Util.intToStringMaxRadix(1);
  private static final String FIELD_LIBRARY_VERSION = Util.intToStringMaxRadix(2);
  private static final String FIELD_PACKAGE_NAME = Util.intToStringMaxRadix(3);
  private static final String FIELD_SERVICE_NAME = Util.intToStringMaxRadix(4);
  private static final String FIELD_COMPONENT_NAME = Util.intToStringMaxRadix(5);
  private static final String FIELD_ISESSION = Util.intToStringMaxRadix(6);
  private static final String FIELD_EXTRAS = Util.intToStringMaxRadix(7);
  private static final String FIELD_INTERFACE_VERSION = Util.intToStringMaxRadix(8);
  private static final String FIELD_PLATFORM_TOKEN = Util.intToStringMaxRadix(9);

  // Next field key = 10

  @Override
  public Bundle toBundle() {
    Bundle bundle = new Bundle();
    bundle.putInt(FIELD_UID, uid);
    bundle.putInt(FIELD_TYPE, type);
    bundle.putInt(FIELD_LIBRARY_VERSION, libraryVersion);
    bundle.putString(FIELD_PACKAGE_NAME, packageName);
    bundle.putString(FIELD_SERVICE_NAME, serviceName);
    BundleCompat.putBinder(bundle, FIELD_ISESSION, iSession);
    bundle.putParcelable(FIELD_COMPONENT_NAME, componentName);
    bundle.putBundle(FIELD_EXTRAS, extras);
    bundle.putInt(FIELD_INTERFACE_VERSION, interfaceVersion);
    if (platformToken != null) {
      bundle.putParcelable(FIELD_PLATFORM_TOKEN, platformToken);
    }
    return bundle;
  }

  /** Restores a {@code SessionTokenImplBase} from a {@link Bundle}. */
  public static SessionTokenImplBase fromBundle(
      Bundle bundle, @Nullable MediaSession.Token platformToken) {
    checkArgument(bundle.containsKey(FIELD_UID), "uid should be set.");
    int uid = bundle.getInt(FIELD_UID);
    checkArgument(bundle.containsKey(FIELD_TYPE), "type should be set.");
    int type = bundle.getInt(FIELD_TYPE);
    int libraryVersion =
        bundle.getInt(
            FIELD_LIBRARY_VERSION, /* defaultValue= */ SessionToken.UNKNOWN_SESSION_VERSION);
    int interfaceVersion =
        bundle.getInt(
            FIELD_INTERFACE_VERSION, /* defaultValue= */ SessionToken.UNKNOWN_INTERFACE_VERSION);
    String packageName =
        checkNotEmpty(bundle.getString(FIELD_PACKAGE_NAME), "package name should be set.");
    String serviceName = bundle.getString(FIELD_SERVICE_NAME, /* defaultValue= */ "");
    @Nullable IBinder iSession = BundleCompat.getBinder(bundle, FIELD_ISESSION);
    @Nullable ComponentName componentName = bundle.getParcelable(FIELD_COMPONENT_NAME);
    @Nullable Bundle extras = bundle.getBundle(FIELD_EXTRAS);
    @Nullable
    MediaSession.Token platformTokenFromBundle = bundle.getParcelable(FIELD_PLATFORM_TOKEN);
    if (platformTokenFromBundle != null) {
      platformToken = platformTokenFromBundle;
    }
    return new SessionTokenImplBase(
        uid,
        type,
        libraryVersion,
        interfaceVersion,
        packageName,
        serviceName,
        componentName,
        iSession,
        extras == null ? Bundle.EMPTY : extras,
        platformToken);
  }
}
