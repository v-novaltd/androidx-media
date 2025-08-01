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
package androidx.media3.exoplayer.drm;

import static android.os.Build.VERSION.SDK_INT;
import static androidx.media3.common.util.Assertions.checkNotNull;

import android.annotation.SuppressLint;
import android.media.DeniedByServerException;
import android.media.MediaCrypto;
import android.media.MediaCryptoException;
import android.media.MediaDrm;
import android.media.MediaDrmException;
import android.media.NotProvisionedException;
import android.media.UnsupportedSchemeException;
import android.media.metrics.LogSessionId;
import android.os.Build;
import android.os.PersistableBundle;
import android.text.TextUtils;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.media3.common.C;
import androidx.media3.common.DrmInitData;
import androidx.media3.common.DrmInitData.SchemeData;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.Assertions;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.analytics.PlayerId;
import androidx.media3.extractor.mp4.PsshAtomUtil;
import androidx.media3.extractor.mp4.PsshAtomUtil.PsshAtom;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** An {@link ExoMediaDrm} implementation that wraps the framework {@link MediaDrm}. */
public final class FrameworkMediaDrm implements ExoMediaDrm {

  private static final String TAG = "FrameworkMediaDrm";

  /**
   * {@link ExoMediaDrm.Provider} that returns a new {@link FrameworkMediaDrm} for the requested
   * UUID. Returns a {@link DummyExoMediaDrm} if the protection scheme identified by the given UUID
   * is not supported by the device.
   */
  @UnstableApi
  public static final Provider DEFAULT_PROVIDER =
      uuid -> {
        try {
          return newInstance(uuid);
        } catch (UnsupportedDrmException e) {
          Log.e(TAG, "Failed to instantiate a FrameworkMediaDrm for uuid: " + uuid + ".");
          return new DummyExoMediaDrm();
        }
      };

  private static final String CENC_SCHEME_MIME_TYPE = "cenc";
  private static final String MOCK_LA_URL_VALUE = "https://x";
  private static final String MOCK_LA_URL = "<LA_URL>" + MOCK_LA_URL_VALUE + "</LA_URL>";
  private static final int UTF_16_BYTES_PER_CHARACTER = 2;

  private final UUID uuid;
  private final MediaDrm mediaDrm;
  private int referenceCount;

  /**
   * Returns whether the DRM scheme with the given UUID is supported on this device.
   *
   * @see MediaDrm#isCryptoSchemeSupported(UUID)
   */
  public static boolean isCryptoSchemeSupported(UUID uuid) {
    return MediaDrm.isCryptoSchemeSupported(adjustUuid(uuid));
  }

  /**
   * Creates an instance with an initial reference count of 1. {@link #release()} must be called on
   * the instance when it's no longer required.
   *
   * @param uuid The scheme uuid.
   * @return The created instance.
   * @throws UnsupportedDrmException If the DRM scheme is unsupported or cannot be instantiated.
   */
  @UnstableApi
  public static FrameworkMediaDrm newInstance(UUID uuid) throws UnsupportedDrmException {
    try {
      return new FrameworkMediaDrm(uuid);
    } catch (UnsupportedSchemeException e) {
      throw new UnsupportedDrmException(UnsupportedDrmException.REASON_UNSUPPORTED_SCHEME, e);
    } catch (Exception e) {
      throw new UnsupportedDrmException(UnsupportedDrmException.REASON_INSTANTIATION_ERROR, e);
    }
  }

  private FrameworkMediaDrm(UUID uuid) throws UnsupportedSchemeException {
    Assertions.checkNotNull(uuid);
    Assertions.checkArgument(!C.COMMON_PSSH_UUID.equals(uuid), "Use C.CLEARKEY_UUID instead");
    this.uuid = uuid;
    this.mediaDrm = new MediaDrm(adjustUuid(uuid));
    // Creators of an instance automatically acquire ownership of the created instance.
    referenceCount = 1;
    if (C.WIDEVINE_UUID.equals(uuid) && needsForceWidevineL3Workaround()) {
      forceWidevineL3(mediaDrm);
    }
  }

  @UnstableApi
  @Override
  public void setOnEventListener(@Nullable ExoMediaDrm.OnEventListener listener) {
    mediaDrm.setOnEventListener(
        listener == null
            ? null
            : (mediaDrm, sessionId, event, extra, data) ->
                listener.onEvent(FrameworkMediaDrm.this, sessionId, event, extra, data));
  }

  @UnstableApi
  @Override
  public void setOnKeyStatusChangeListener(
      @Nullable ExoMediaDrm.OnKeyStatusChangeListener listener) {
    mediaDrm.setOnKeyStatusChangeListener(
        listener == null
            ? null
            : (mediaDrm, sessionId, keyInfo, hasNewUsableKey) -> {
              List<KeyStatus> exoKeyInfo = new ArrayList<>();
              for (MediaDrm.KeyStatus keyStatus : keyInfo) {
                exoKeyInfo.add(new KeyStatus(keyStatus.getStatusCode(), keyStatus.getKeyId()));
              }
              listener.onKeyStatusChange(
                  FrameworkMediaDrm.this, sessionId, exoKeyInfo, hasNewUsableKey);
            },
        /* handler= */ null);
  }

  @UnstableApi
  @Override
  public void setOnExpirationUpdateListener(@Nullable OnExpirationUpdateListener listener) {
    mediaDrm.setOnExpirationUpdateListener(
        listener == null
            ? null
            : (mediaDrm, sessionId, expirationTimeMs) ->
                listener.onExpirationUpdate(FrameworkMediaDrm.this, sessionId, expirationTimeMs),
        /* handler= */ null);
  }

  @UnstableApi
  @Override
  public byte[] openSession() throws MediaDrmException {
    return mediaDrm.openSession();
  }

  @UnstableApi
  @Override
  public void closeSession(byte[] sessionId) {
    mediaDrm.closeSession(sessionId);
  }

  @UnstableApi
  @Override
  public void setPlayerIdForSession(byte[] sessionId, PlayerId playerId) {
    if (SDK_INT >= 31) {
      try {
        Api31.setLogSessionIdOnMediaDrmSession(mediaDrm, sessionId, playerId);
      } catch (UnsupportedOperationException e) {
        Log.w(TAG, "setLogSessionId failed.");
      }
    }
  }

  // Return values of MediaDrm.KeyRequest.getRequestType are equal to KeyRequest.RequestType.
  @SuppressLint("WrongConstant")
  @UnstableApi
  @Override
  public KeyRequest getKeyRequest(
      byte[] scope,
      @Nullable List<DrmInitData.SchemeData> schemeDatas,
      int keyType,
      @Nullable HashMap<String, String> optionalParameters)
      throws NotProvisionedException {
    SchemeData schemeData = null;
    byte[] initData = null;
    String mimeType = null;
    if (schemeDatas != null) {
      schemeData = getSchemeData(uuid, schemeDatas);
      initData = adjustRequestInitData(uuid, Assertions.checkNotNull(schemeData.data));
      mimeType = adjustRequestMimeType(uuid, schemeData.mimeType);
    }
    MediaDrm.KeyRequest request =
        mediaDrm.getKeyRequest(scope, initData, mimeType, keyType, optionalParameters);

    byte[] requestData = adjustRequestData(uuid, request.getData());
    String licenseServerUrl = adjustLicenseServerUrl(request.getDefaultUrl());
    if (TextUtils.isEmpty(licenseServerUrl)
        && schemeData != null
        && !TextUtils.isEmpty(schemeData.licenseServerUrl)) {
      licenseServerUrl = schemeData.licenseServerUrl;
    }

    @KeyRequest.RequestType int requestType = request.getRequestType();
    return new KeyRequest(requestData, licenseServerUrl, requestType);
  }

  private String adjustLicenseServerUrl(String licenseServerUrl) {
    if (MOCK_LA_URL.equals(licenseServerUrl)) {
      return "";
    }
    if (SDK_INT >= 33 && "https://default.url".equals(licenseServerUrl)) {
      // Work around b/247808112
      String pluginVersion = getPropertyString("version");
      if (Objects.equals(pluginVersion, "1.2") || Objects.equals(pluginVersion, "aidl-1")) {
        return "";
      }
    }
    return licenseServerUrl;
  }

  @UnstableApi
  @Override
  @Nullable
  public byte[] provideKeyResponse(byte[] scope, byte[] response)
      throws NotProvisionedException, DeniedByServerException {
    if (C.CLEARKEY_UUID.equals(uuid)) {
      response = ClearKeyUtil.adjustResponseData(response);
    }

    return mediaDrm.provideKeyResponse(scope, response);
  }

  @UnstableApi
  @Override
  public ProvisionRequest getProvisionRequest() {
    final MediaDrm.ProvisionRequest request = mediaDrm.getProvisionRequest();
    return new ProvisionRequest(request.getData(), request.getDefaultUrl());
  }

  @UnstableApi
  @Override
  public void provideProvisionResponse(byte[] response) throws DeniedByServerException {
    mediaDrm.provideProvisionResponse(response);
  }

  @UnstableApi
  @Override
  public Map<String, String> queryKeyStatus(byte[] sessionId) {
    return mediaDrm.queryKeyStatus(sessionId);
  }

  @UnstableApi
  @Override
  public boolean requiresSecureDecoder(byte[] sessionId, String mimeType) {
    boolean result;
    if (SDK_INT >= 31 && isMediaDrmRequiresSecureDecoderImplemented()) {
      result =
          Api31.requiresSecureDecoder(mediaDrm, mimeType, mediaDrm.getSecurityLevel(sessionId));
    } else {
      MediaCrypto mediaCrypto = null;
      try {
        mediaCrypto = new MediaCrypto(adjustUuid(uuid), sessionId);
        result = mediaCrypto.requiresSecureDecoderComponent(mimeType);
      } catch (MediaCryptoException e) {
        // This shouldn't happen, but if it does then assume that most DRM schemes need a secure
        // decoder but ClearKey doesn't (because ClearKey never uses secure decryption). Requesting
        // a secure decoder when it's not supported leads to playback failures:
        // https://github.com/androidx/media/issues/1732
        result = !uuid.equals(C.CLEARKEY_UUID);
      } finally {
        if (mediaCrypto != null) {
          mediaCrypto.release();
        }
      }
    }
    return result;
  }

  @UnstableApi
  @Override
  public synchronized void acquire() {
    Assertions.checkState(referenceCount > 0);
    referenceCount++;
  }

  @UnstableApi
  @Override
  public synchronized void release() {
    if (--referenceCount == 0) {
      mediaDrm.release();
    }
  }

  @UnstableApi
  @Override
  public void restoreKeys(byte[] sessionId, byte[] keySetId) {
    mediaDrm.restoreKeys(sessionId, keySetId);
  }

  @Override
  @UnstableApi
  @RequiresApi(29)
  public void removeOfflineLicense(byte[] keySetId) {
    if (SDK_INT < 29) {
      throw new UnsupportedOperationException();
    }
    mediaDrm.removeOfflineLicense(keySetId);
  }

  @Override
  @UnstableApi
  @RequiresApi(29)
  public List<byte[]> getOfflineLicenseKeySetIds() {
    if (SDK_INT < 29) {
      throw new UnsupportedOperationException();
    }
    return mediaDrm.getOfflineLicenseKeySetIds();
  }

  @UnstableApi
  @Override
  @Nullable
  public PersistableBundle getMetrics() {
    if (SDK_INT < 28) {
      return null;
    }
    return mediaDrm.getMetrics();
  }

  @UnstableApi
  @Override
  public String getPropertyString(String propertyName) {
    return mediaDrm.getPropertyString(propertyName);
  }

  @UnstableApi
  @Override
  public byte[] getPropertyByteArray(String propertyName) {
    return mediaDrm.getPropertyByteArray(propertyName);
  }

  @UnstableApi
  @Override
  public void setPropertyString(String propertyName, String value) {
    mediaDrm.setPropertyString(propertyName, value);
  }

  @UnstableApi
  @Override
  public void setPropertyByteArray(String propertyName, byte[] value) {
    mediaDrm.setPropertyByteArray(propertyName, value);
  }

  @UnstableApi
  @Override
  public FrameworkCryptoConfig createCryptoConfig(byte[] sessionId) throws MediaCryptoException {
    return new FrameworkCryptoConfig(adjustUuid(uuid), sessionId);
  }

  @UnstableApi
  @Override
  public @C.CryptoType int getCryptoType() {
    return C.CRYPTO_TYPE_FRAMEWORK;
  }

  /**
   * {@link MediaDrm#requiresSecureDecoder} is nominally available from API 31, but it's only
   * functional for Widevine if the plugin version is greater than 16.0. See b/352419654#comment63.
   */
  @RequiresApi(31)
  private boolean isMediaDrmRequiresSecureDecoderImplemented() {
    // TODO: b/359768062 - Add an SDK_INT guard clause once WV 16.0 is not permitted on any device.
    if (uuid.equals(C.WIDEVINE_UUID)) {
      String pluginVersion = getPropertyString(MediaDrm.PROPERTY_VERSION);
      return !pluginVersion.startsWith("v5.")
          && !pluginVersion.startsWith("14.")
          && !pluginVersion.startsWith("15.")
          && !pluginVersion.startsWith("16.0");
    } else {
      // Assume that ClearKey plugin always supports this method, and no non-Google plugin does. See
      // b/352419654#comment71.
      return uuid.equals(C.CLEARKEY_UUID);
    }
  }

  private static SchemeData getSchemeData(UUID uuid, List<SchemeData> schemeDatas) {
    if (!C.WIDEVINE_UUID.equals(uuid)) {
      // For non-Widevine CDMs always use the first scheme data.
      return schemeDatas.get(0);
    }

    if (SDK_INT >= 28 && schemeDatas.size() > 1) {
      // For API level 28 and above, concatenate multiple PSSH scheme datas if possible.
      SchemeData firstSchemeData = schemeDatas.get(0);
      int concatenatedDataLength = 0;
      boolean canConcatenateData = true;
      for (int i = 0; i < schemeDatas.size(); i++) {
        SchemeData schemeData = schemeDatas.get(i);
        byte[] schemeDataData = Assertions.checkNotNull(schemeData.data);
        if (Objects.equals(schemeData.mimeType, firstSchemeData.mimeType)
            && Objects.equals(schemeData.licenseServerUrl, firstSchemeData.licenseServerUrl)
            && PsshAtomUtil.isPsshAtom(schemeDataData)) {
          concatenatedDataLength += schemeDataData.length;
        } else {
          canConcatenateData = false;
          break;
        }
      }
      if (canConcatenateData) {
        byte[] concatenatedData = new byte[concatenatedDataLength];
        int concatenatedDataPosition = 0;
        for (int i = 0; i < schemeDatas.size(); i++) {
          SchemeData schemeData = schemeDatas.get(i);
          byte[] schemeDataData = Assertions.checkNotNull(schemeData.data);
          int schemeDataLength = schemeDataData.length;
          System.arraycopy(
              schemeDataData, 0, concatenatedData, concatenatedDataPosition, schemeDataLength);
          concatenatedDataPosition += schemeDataLength;
        }
        return firstSchemeData.copyWithData(concatenatedData);
      }
    }

    // For API levels 23 - 27, prefer the first V1 PSSH box.
    for (int i = 0; i < schemeDatas.size(); i++) {
      SchemeData schemeData = schemeDatas.get(i);
      int version = PsshAtomUtil.parseVersion(Assertions.checkNotNull(schemeData.data));
      if (version == 1) {
        return schemeData;
      }
    }

    // If all else fails, use the first scheme data.
    return schemeDatas.get(0);
  }

  private static UUID adjustUuid(UUID uuid) {
    return cdmRequiresCommonPsshUuid(uuid) ? C.COMMON_PSSH_UUID : uuid;
  }

  private static byte[] adjustRequestInitData(UUID uuid, byte[] initData) {
    // TODO: Add API level check once [Internal ref: b/112142048] is fixed.
    if (C.PLAYREADY_UUID.equals(uuid)) {
      byte[] schemeSpecificData = PsshAtomUtil.parseSchemeSpecificData(initData, uuid);
      if (schemeSpecificData == null) {
        // The init data is not contained in a pssh box.
        schemeSpecificData = initData;
      }
      initData =
          PsshAtomUtil.buildPsshAtom(
              C.PLAYREADY_UUID, addLaUrlAttributeIfMissing(schemeSpecificData));
    }
    if (cdmRequiresCommonPsshUuid(uuid)) {
      PsshAtom psshAtom = PsshAtomUtil.parsePsshAtom(initData);
      if (psshAtom != null) {
        initData =
            PsshAtomUtil.buildPsshAtom(C.COMMON_PSSH_UUID, psshAtom.keyIds, psshAtom.schemeData);
      }
    }

    // Some Amazon devices require data to be extracted from the PSSH atom for PlayReady.
    if ((C.PLAYREADY_UUID.equals(uuid)
        && "Amazon".equals(Build.MANUFACTURER)
        && ("AFTB".equals(Build.MODEL) // Fire TV Gen 1
            || "AFTS".equals(Build.MODEL) // Fire TV Gen 2
            || "AFTM".equals(Build.MODEL) // Fire TV Stick Gen 1
            || "AFTT".equals(Build.MODEL)))) { // Fire TV Stick Gen 2
      byte[] psshData = PsshAtomUtil.parseSchemeSpecificData(initData, uuid);
      if (psshData != null) {
        // Extraction succeeded, so return the extracted data.
        return psshData;
      }
    }
    return initData;
  }

  private static String adjustRequestMimeType(UUID uuid, String mimeType) {
    // Prior to API level 26 the ClearKey CDM only accepted "cenc" as the scheme for MP4.
    if (SDK_INT < 26
        && C.CLEARKEY_UUID.equals(uuid)
        && (MimeTypes.VIDEO_MP4.equals(mimeType) || MimeTypes.AUDIO_MP4.equals(mimeType))) {
      return CENC_SCHEME_MIME_TYPE;
    }
    return mimeType;
  }

  private static byte[] adjustRequestData(UUID uuid, byte[] requestData) {
    if (C.CLEARKEY_UUID.equals(uuid)) {
      return ClearKeyUtil.adjustRequestData(requestData);
    }
    return requestData;
  }

  private static boolean cdmRequiresCommonPsshUuid(UUID uuid) {
    // ClearKey had to be accessed using the Common PSSH UUID prior to API level 27.
    return SDK_INT < 27 && Objects.equals(uuid, C.CLEARKEY_UUID);
  }

  private static void forceWidevineL3(MediaDrm mediaDrm) {
    mediaDrm.setPropertyString("securityLevel", "L3");
  }

  /**
   * Returns whether the device codec is known to fail if security level L1 is used.
   *
   * <p>See <a href="https://github.com/google/ExoPlayer/issues/4413">GitHub issue #4413</a>.
   */
  private static boolean needsForceWidevineL3Workaround() {
    return "ASUS_Z00AD".equals(Build.MODEL);
  }

  /**
   * If the LA_URL tag is missing, injects a mock LA_URL value to avoid causing the CDM to throw
   * when creating the key request. The LA_URL attribute is optional but some Android PlayReady
   * implementations are known to require it. Does nothing it the provided {@code data} already
   * contains an LA_URL value.
   */
  private static byte[] addLaUrlAttributeIfMissing(byte[] data) {
    ParsableByteArray byteArray = new ParsableByteArray(data);
    // See https://docs.microsoft.com/en-us/playready/specifications/specifications for more
    // information about the init data format.
    int length = byteArray.readLittleEndianInt();
    int objectRecordCount = byteArray.readLittleEndianShort();
    int recordType = byteArray.readLittleEndianShort();
    if (objectRecordCount != 1 || recordType != 1) {
      Log.i(TAG, "Unexpected record count or type. Skipping LA_URL workaround.");
      return data;
    }
    int recordLength = byteArray.readLittleEndianShort();
    String xml = byteArray.readString(recordLength, StandardCharsets.UTF_16LE);
    if (xml.contains("<LA_URL>")) {
      // LA_URL already present. Do nothing.
      return data;
    }
    // This PlayReady object record does not include an LA_URL. We add a mock value for it.
    int endOfDataTagIndex = xml.indexOf("</DATA>");
    if (endOfDataTagIndex == -1) {
      Log.w(TAG, "Could not find the </DATA> tag. Skipping LA_URL workaround.");
    }
    String xmlWithMockLaUrl =
        xml.substring(/* beginIndex= */ 0, /* endIndex= */ endOfDataTagIndex)
            + MOCK_LA_URL
            + xml.substring(/* beginIndex= */ endOfDataTagIndex);
    int extraBytes = MOCK_LA_URL.length() * UTF_16_BYTES_PER_CHARACTER;
    ByteBuffer newData = ByteBuffer.allocate(length + extraBytes);
    newData.order(ByteOrder.LITTLE_ENDIAN);
    newData.putInt(length + extraBytes);
    newData.putShort((short) objectRecordCount);
    newData.putShort((short) recordType);
    newData.putShort((short) (xmlWithMockLaUrl.length() * UTF_16_BYTES_PER_CHARACTER));
    newData.put(xmlWithMockLaUrl.getBytes(StandardCharsets.UTF_16LE));
    return newData.array();
  }

  @RequiresApi(31)
  private static class Api31 {
    private Api31() {}

    public static boolean requiresSecureDecoder(
        MediaDrm mediaDrm, String mimeType, int securityLevel) {
      return mediaDrm.requiresSecureDecoder(mimeType, securityLevel);
    }

    public static void setLogSessionIdOnMediaDrmSession(
        MediaDrm mediaDrm, byte[] drmSessionId, PlayerId playerId) {
      LogSessionId logSessionId = playerId.getLogSessionId();
      if (!logSessionId.equals(LogSessionId.LOG_SESSION_ID_NONE)) {
        MediaDrm.PlaybackComponent playbackComponent =
            checkNotNull(mediaDrm.getPlaybackComponent(drmSessionId));
        playbackComponent.setLogSessionId(logSessionId);
      }
    }
  }
}
