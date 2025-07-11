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

package androidx.media3.exoplayer.rtsp;

import static androidx.media3.common.util.Assertions.checkArgument;
import static androidx.media3.common.util.Assertions.checkNotNull;
import static androidx.media3.exoplayer.rtsp.SessionDescription.SUPPORTED_SDP_VERSION;
import static com.google.common.base.Strings.nullToEmpty;

import android.net.Uri;
import androidx.annotation.Nullable;
import androidx.media3.common.ParserException;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.Util;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses a String based SDP message into {@link SessionDescription}. */
/* package */ final class SessionDescriptionParser {
  private static final String TAG = "SDPParser";

  // SDP line always starts with an one letter tag, followed by an equal sign. The information
  // under the given tag follows an optional space.
  private static final Pattern SDP_LINE_PATTERN = Pattern.compile("([a-z])=\\s?((?:.|\f)+)");
  // SDP line with a one letter tag, an equal sign, and an empty value.
  private static final Pattern SDP_LINE_WITH_EMPTY_VALUE_PATTERN = Pattern.compile("^([a-z])=$");
  // Matches an attribute line (with a= sdp tag removed. Example: range:npt=0-50.0).
  // Attribute can also be a flag, i.e. without a value, like recvonly. Reference RFC4566 Section 9
  // Page 43, under "token-char".
  private static final Pattern ATTRIBUTE_PATTERN =
      Pattern.compile(
          "([\\x21\\x23-\\x27\\x2a\\x2b\\x2d\\x2e\\x30-\\x39\\x41-\\x5a\\x5e-\\x7e]+)(?::((?:.|\f)*))?");
  // SDP media description line: <mediaType> <port> <transmissionProtocol> <rtpPayloadType>
  // For instance: audio 0 RTP/AVP 97
  private static final Pattern MEDIA_DESCRIPTION_PATTERN =
      Pattern.compile("(\\S+)\\s(\\S+)\\s(\\S+)\\s(\\S+)");

  private static final String VERSION_TYPE = "v";
  private static final String ORIGIN_TYPE = "o";
  private static final String SESSION_TYPE = "s";
  private static final String INFORMATION_TYPE = "i";
  private static final String URI_TYPE = "u";
  private static final String EMAIL_TYPE = "e";
  private static final String PHONE_NUMBER_TYPE = "p";
  private static final String CONNECTION_TYPE = "c";
  private static final String BANDWIDTH_TYPE = "b";
  private static final String TIMING_TYPE = "t";
  private static final String KEY_TYPE = "k";
  private static final String ATTRIBUTE_TYPE = "a";
  private static final String MEDIA_TYPE = "m";
  private static final String REPEAT_TYPE = "r";
  private static final String ZONE_TYPE = "z";

  /**
   * Parses a String based SDP message into {@link SessionDescription}.
   *
   * @throws ParserException On SDP message line that cannot be parsed, or when one or more of the
   *     mandatory SDP fields {@link SessionDescription#timing}, {@link SessionDescription#origin}
   *     and {@link SessionDescription#sessionName} are not set.
   */
  public static SessionDescription parse(String sdpString) throws ParserException {
    SessionDescription.Builder sessionDescriptionBuilder = new SessionDescription.Builder();
    @Nullable MediaDescription.Builder mediaDescriptionBuilder = null;

    // Tracks if currently parsing an invalid media description and should skip any parsed
    // and related SDP lines until the next valid media description.
    boolean isSkippingMediaDescription = false;

    // Lines are separated by an CRLF.
    for (String line : RtspMessageUtil.splitRtspMessageBody(sdpString)) {
      line = line.trim();

      if (line.isEmpty()) {
        continue;
      }

      Matcher matcher = SDP_LINE_PATTERN.matcher(line);
      if (!matcher.matches()) {
        Matcher sdpTagMatcher = SDP_LINE_WITH_EMPTY_VALUE_PATTERN.matcher(line);
        if (sdpTagMatcher.matches() && Objects.equals(sdpTagMatcher.group(1), INFORMATION_TYPE)) {
          // Allow and skip empty Session Information (tag 'i') attributes
          continue;
        }
        throw ParserException.createForMalformedManifest(
            "Malformed SDP line: " + line, /* cause= */ null);
      }

      String sdpType = checkNotNull(matcher.group(1));
      String sdpValue = checkNotNull(matcher.group(2));

      switch (sdpType) {
        case VERSION_TYPE:
          if (!SUPPORTED_SDP_VERSION.equals(sdpValue)) {
            throw ParserException.createForMalformedManifest(
                String.format("SDP version %s is not supported.", sdpValue), /* cause= */ null);
          }
          break;

        case ORIGIN_TYPE:
          sessionDescriptionBuilder.setOrigin(sdpValue);
          break;

        case SESSION_TYPE:
          sessionDescriptionBuilder.setSessionName(sdpValue);
          break;

        case INFORMATION_TYPE:
          if (isSkippingMediaDescription) {
            continue;
          }
          if (mediaDescriptionBuilder == null) {
            sessionDescriptionBuilder.setSessionInfo(sdpValue);
          } else {
            mediaDescriptionBuilder.setMediaTitle(sdpValue);
          }
          break;

        case URI_TYPE:
          sessionDescriptionBuilder.setUri(Uri.parse(sdpValue));
          break;

        case EMAIL_TYPE:
          sessionDescriptionBuilder.setEmailAddress(sdpValue);
          break;

        case PHONE_NUMBER_TYPE:
          sessionDescriptionBuilder.setPhoneNumber(sdpValue);
          break;

        case CONNECTION_TYPE:
          if (isSkippingMediaDescription) {
            continue;
          }
          if (mediaDescriptionBuilder == null) {
            sessionDescriptionBuilder.setConnection(sdpValue);
          } else {
            mediaDescriptionBuilder.setConnection(sdpValue);
          }
          break;

        case BANDWIDTH_TYPE:
          if (isSkippingMediaDescription) {
            continue;
          }
          String[] bandwidthComponents = Util.split(sdpValue, ":\\s?");
          checkArgument(bandwidthComponents.length == 2);
          int bitrateKbps = Integer.parseInt(bandwidthComponents[1]);

          // Converting kilobits per second to bits per second.
          if (mediaDescriptionBuilder == null) {
            sessionDescriptionBuilder.setBitrate(bitrateKbps * 1000);
          } else {
            mediaDescriptionBuilder.setBitrate(bitrateKbps * 1000);
          }
          break;

        case TIMING_TYPE:
          sessionDescriptionBuilder.setTiming(sdpValue);
          break;

        case KEY_TYPE:
          if (isSkippingMediaDescription) {
            continue;
          }
          if (mediaDescriptionBuilder == null) {
            sessionDescriptionBuilder.setKey(sdpValue);
          } else {
            mediaDescriptionBuilder.setKey(sdpValue);
          }
          break;

        case ATTRIBUTE_TYPE:
          // Parsing attribute
          if (isSkippingMediaDescription) {
            continue;
          }
          matcher = ATTRIBUTE_PATTERN.matcher(sdpValue);
          if (!matcher.matches()) {
            throw ParserException.createForMalformedManifest(
                "Malformed Attribute line: " + line, /* cause= */ null);
          }

          String attributeName = checkNotNull(matcher.group(1));
          // The second catching group is optional and thus could be null.
          String attributeValue = nullToEmpty(matcher.group(2));

          if (mediaDescriptionBuilder == null) {
            sessionDescriptionBuilder.addAttribute(attributeName, attributeValue);
          } else {
            mediaDescriptionBuilder.addAttribute(attributeName, attributeValue);
          }
          break;

        case MEDIA_TYPE:
          if (mediaDescriptionBuilder != null) {
            addMediaDescriptionToSession(sessionDescriptionBuilder, mediaDescriptionBuilder);
          }
          mediaDescriptionBuilder = parseMediaDescriptionLine(sdpValue);
          isSkippingMediaDescription = mediaDescriptionBuilder == null;
          break;
        case REPEAT_TYPE:
        case ZONE_TYPE:
        default:
          // Not handled.
      }
    }

    if (mediaDescriptionBuilder != null) {
      addMediaDescriptionToSession(sessionDescriptionBuilder, mediaDescriptionBuilder);
    }

    try {
      return sessionDescriptionBuilder.build();
    } catch (IllegalArgumentException | IllegalStateException e) {
      throw ParserException.createForMalformedManifest(/* message= */ null, e);
    }
  }

  private static void addMediaDescriptionToSession(
      SessionDescription.Builder sessionDescriptionBuilder,
      MediaDescription.Builder mediaDescriptionBuilder)
      throws ParserException {
    try {
      sessionDescriptionBuilder.addMediaDescription(mediaDescriptionBuilder.build());
    } catch (IllegalArgumentException | IllegalStateException e) {
      throw ParserException.createForMalformedManifest(/* message= */ null, e);
    }
  }

  /**
   * Parses a Media Description SDP line.
   *
   * <p>Returns a {@link MediaDescription.Builder} from parsing a valid SDP {@code line} is parsed
   * or {@code null} if {@code line} contains invalid port or format values.
   *
   * @param line representing a Media Description.
   * @return valid {@link MediaDescription.Builder} or {@code null} if {@code line} contains invalid
   *     port or format values.
   * @throws ParserException if malformed SDP media description line.
   */
  @Nullable
  private static MediaDescription.Builder parseMediaDescriptionLine(String line)
      throws ParserException {
    Matcher matcher = MEDIA_DESCRIPTION_PATTERN.matcher(line);
    if (!matcher.matches()) {
      throw ParserException.createForMalformedManifest(
          "Malformed SDP media description line: " + line, /* cause= */ null);
    }
    String mediaType = checkNotNull(matcher.group(1));
    String portString = checkNotNull(matcher.group(2));
    String transportProtocol = checkNotNull(matcher.group(3));
    String payloadTypeString = checkNotNull(matcher.group(4));

    @Nullable MediaDescription.Builder mediaDescriptionBuilder = null;
    try {
      mediaDescriptionBuilder =
          new MediaDescription.Builder(
              mediaType,
              Integer.parseInt(portString),
              transportProtocol,
              Integer.parseInt(payloadTypeString));
    } catch (NumberFormatException e) {
      Log.w(TAG, "Malformed SDP media description line: " + line, e);
    }
    return mediaDescriptionBuilder;
  }

  /** Prevents initialization. */
  private SessionDescriptionParser() {}
}
