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
package androidx.media3.exoplayer.hls.playlist;

import static androidx.media3.common.util.Assertions.checkNotNull;
import static androidx.media3.common.util.Assertions.checkState;
import static androidx.media3.common.util.Util.castNonNull;
import static androidx.media3.common.util.Util.msToUs;
import static androidx.media3.common.util.Util.parseXsDateTime;
import static androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist.Interstitial.CUE_TRIGGER_ONCE;
import static androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist.Interstitial.CUE_TRIGGER_POST;
import static androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist.Interstitial.CUE_TRIGGER_PRE;

import android.net.Uri;
import android.text.TextUtils;
import android.util.Base64;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.DrmInitData;
import androidx.media3.common.DrmInitData.SchemeData;
import androidx.media3.common.Format;
import androidx.media3.common.Metadata;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.ParserException;
import androidx.media3.common.util.Assertions;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.UriUtil;
import androidx.media3.common.util.Util;
import androidx.media3.exoplayer.hls.HlsTrackMetadataEntry;
import androidx.media3.exoplayer.hls.HlsTrackMetadataEntry.VariantInfo;
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist.Interstitial;
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist.Part;
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist.RenditionReport;
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist.Segment;
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist.Rendition;
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist.Variant;
import androidx.media3.exoplayer.upstream.ParsingLoadable;
import androidx.media3.extractor.mp4.PsshAtomUtil;
import com.google.common.collect.Iterables;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Queue;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.checkerframework.checker.nullness.qual.EnsuresNonNullIf;
import org.checkerframework.checker.nullness.qual.PolyNull;

/** HLS playlists parsing logic. */
@UnstableApi
public final class HlsPlaylistParser implements ParsingLoadable.Parser<HlsPlaylist> {

  /** Exception thrown when merging a delta update fails. */
  public static final class DeltaUpdateException extends IOException {}

  private static final String LOG_TAG = "HlsPlaylistParser";

  private static final String PLAYLIST_HEADER = "#EXTM3U";

  private static final String TAG_PREFIX = "#EXT";

  private static final String TAG_VERSION = "#EXT-X-VERSION";
  private static final String TAG_PLAYLIST_TYPE = "#EXT-X-PLAYLIST-TYPE";
  private static final String TAG_DEFINE = "#EXT-X-DEFINE";
  private static final String TAG_SERVER_CONTROL = "#EXT-X-SERVER-CONTROL";
  private static final String TAG_STREAM_INF = "#EXT-X-STREAM-INF";
  private static final String TAG_PART_INF = "#EXT-X-PART-INF";
  private static final String TAG_PART = "#EXT-X-PART";
  private static final String TAG_I_FRAME_STREAM_INF = "#EXT-X-I-FRAME-STREAM-INF";
  private static final String TAG_IFRAME = "#EXT-X-I-FRAMES-ONLY";
  private static final String TAG_MEDIA = "#EXT-X-MEDIA";
  private static final String TAG_TARGET_DURATION = "#EXT-X-TARGETDURATION";
  private static final String TAG_DISCONTINUITY = "#EXT-X-DISCONTINUITY";
  private static final String TAG_DISCONTINUITY_SEQUENCE = "#EXT-X-DISCONTINUITY-SEQUENCE";
  private static final String TAG_PROGRAM_DATE_TIME = "#EXT-X-PROGRAM-DATE-TIME";
  private static final String TAG_INIT_SEGMENT = "#EXT-X-MAP";
  private static final String TAG_INDEPENDENT_SEGMENTS = "#EXT-X-INDEPENDENT-SEGMENTS";
  private static final String TAG_MEDIA_DURATION = "#EXTINF";
  private static final String TAG_MEDIA_SEQUENCE = "#EXT-X-MEDIA-SEQUENCE";
  private static final String TAG_START = "#EXT-X-START";
  private static final String TAG_ENDLIST = "#EXT-X-ENDLIST";
  private static final String TAG_KEY = "#EXT-X-KEY";
  private static final String TAG_SESSION_KEY = "#EXT-X-SESSION-KEY";
  private static final String TAG_BYTERANGE = "#EXT-X-BYTERANGE";
  private static final String TAG_GAP = "#EXT-X-GAP";
  private static final String TAG_SKIP = "#EXT-X-SKIP";
  private static final String TAG_PRELOAD_HINT = "#EXT-X-PRELOAD-HINT";
  private static final String TAG_RENDITION_REPORT = "#EXT-X-RENDITION-REPORT";
  private static final String TAG_DATERANGE = "#EXT-X-DATERANGE";

  private static final String TYPE_AUDIO = "AUDIO";
  private static final String TYPE_VIDEO = "VIDEO";
  private static final String TYPE_SUBTITLES = "SUBTITLES";
  private static final String TYPE_CLOSED_CAPTIONS = "CLOSED-CAPTIONS";
  private static final String TYPE_PART = "PART";
  private static final String TYPE_MAP = "MAP";

  private static final String METHOD_NONE = "NONE";
  private static final String METHOD_AES_128 = "AES-128";
  private static final String METHOD_SAMPLE_AES = "SAMPLE-AES";
  // Replaced by METHOD_SAMPLE_AES_CTR. Keep for backward compatibility.
  private static final String METHOD_SAMPLE_AES_CENC = "SAMPLE-AES-CENC";
  private static final String METHOD_SAMPLE_AES_CTR = "SAMPLE-AES-CTR";
  private static final String KEYFORMAT_PLAYREADY = "com.microsoft.playready";
  private static final String KEYFORMAT_IDENTITY = "identity";
  private static final String KEYFORMAT_WIDEVINE_PSSH_BINARY =
      "urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed";
  private static final String KEYFORMAT_WIDEVINE_PSSH_JSON = "com.widevine";

  private static final String BOOLEAN_TRUE = "YES";
  private static final String BOOLEAN_FALSE = "NO";

  private static final String ATTR_CLOSED_CAPTIONS_NONE = "CLOSED-CAPTIONS=NONE";

  /** Regex to match a quoted-string attribute value, as defined in RFC 8216 Section 4.2. */
  // The additional \f matching is required due to https://issuetracker.google.com/417657093.
  private static final String ATTR_QUOTED_STRING_VALUE_PATTERN = "\"((?:.|\f)+?)\"";

  private static final Pattern REGEX_AVERAGE_BANDWIDTH =
      Pattern.compile("AVERAGE-BANDWIDTH=(\\d+)\\b");
  private static final Pattern REGEX_VIDEO =
      Pattern.compile("VIDEO=" + ATTR_QUOTED_STRING_VALUE_PATTERN);
  private static final Pattern REGEX_AUDIO =
      Pattern.compile("AUDIO=" + ATTR_QUOTED_STRING_VALUE_PATTERN);
  private static final Pattern REGEX_SUBTITLES =
      Pattern.compile("SUBTITLES=" + ATTR_QUOTED_STRING_VALUE_PATTERN);
  private static final Pattern REGEX_CLOSED_CAPTIONS =
      Pattern.compile("CLOSED-CAPTIONS=" + ATTR_QUOTED_STRING_VALUE_PATTERN);
  private static final Pattern REGEX_BANDWIDTH = Pattern.compile("[^-]BANDWIDTH=(\\d+)\\b");
  private static final Pattern REGEX_CHANNELS =
      Pattern.compile("CHANNELS=" + ATTR_QUOTED_STRING_VALUE_PATTERN);
  private static final Pattern REGEX_VIDEO_RANGE = Pattern.compile("VIDEO-RANGE=(SDR|PQ|HLG)");
  private static final Pattern REGEX_CODECS =
      Pattern.compile("CODECS=" + ATTR_QUOTED_STRING_VALUE_PATTERN);
  private static final Pattern REGEX_SUPPLEMENTAL_CODECS =
      Pattern.compile("SUPPLEMENTAL-CODECS=" + ATTR_QUOTED_STRING_VALUE_PATTERN);
  private static final Pattern REGEX_RESOLUTION = Pattern.compile("RESOLUTION=(\\d+x\\d+)");
  private static final Pattern REGEX_FRAME_RATE = Pattern.compile("FRAME-RATE=([\\d\\.]+)\\b");
  private static final Pattern REGEX_TARGET_DURATION =
      Pattern.compile(TAG_TARGET_DURATION + ":(\\d+)\\b");
  private static final Pattern REGEX_ATTR_DURATION = Pattern.compile("DURATION=([\\d\\.]+)\\b");
  private static final Pattern REGEX_ATTR_DURATION_PREFIXED =
      Pattern.compile("[:,]DURATION=([\\d\\.]+)\\b");
  private static final Pattern REGEX_PART_TARGET_DURATION =
      Pattern.compile("PART-TARGET=([\\d\\.]+)\\b");
  private static final Pattern REGEX_VERSION = Pattern.compile(TAG_VERSION + ":(\\d+)\\b");
  private static final Pattern REGEX_PLAYLIST_TYPE =
      Pattern.compile(TAG_PLAYLIST_TYPE + ":(.+)\\b");
  private static final Pattern REGEX_CAN_SKIP_UNTIL =
      Pattern.compile("CAN-SKIP-UNTIL=([\\d\\.]+)\\b");
  private static final Pattern REGEX_CAN_SKIP_DATE_RANGES =
      compileBooleanAttrPattern("CAN-SKIP-DATERANGES");
  private static final Pattern REGEX_SKIPPED_SEGMENTS =
      Pattern.compile("SKIPPED-SEGMENTS=(\\d+)\\b");
  private static final Pattern REGEX_HOLD_BACK = Pattern.compile("[:|,]HOLD-BACK=([\\d\\.]+)\\b");
  private static final Pattern REGEX_PART_HOLD_BACK =
      Pattern.compile("PART-HOLD-BACK=([\\d\\.]+)\\b");
  private static final Pattern REGEX_CAN_BLOCK_RELOAD =
      compileBooleanAttrPattern("CAN-BLOCK-RELOAD");
  private static final Pattern REGEX_MEDIA_SEQUENCE =
      Pattern.compile(TAG_MEDIA_SEQUENCE + ":(\\d+)\\b");
  private static final Pattern REGEX_MEDIA_DURATION =
      Pattern.compile(TAG_MEDIA_DURATION + ":([\\d\\.]+)\\b");
  private static final Pattern REGEX_MEDIA_TITLE =
      Pattern.compile(TAG_MEDIA_DURATION + ":[\\d\\.]+\\b,(.+)");
  private static final Pattern REGEX_LAST_MSN = Pattern.compile("LAST-MSN" + "=(\\d+)\\b");
  private static final Pattern REGEX_LAST_PART = Pattern.compile("LAST-PART" + "=(\\d+)\\b");
  private static final Pattern REGEX_TIME_OFFSET = Pattern.compile("TIME-OFFSET=(-?[\\d\\.]+)\\b");
  private static final Pattern REGEX_BYTERANGE =
      Pattern.compile(TAG_BYTERANGE + ":(\\d+(?:@\\d+)?)\\b");
  private static final Pattern REGEX_ATTR_BYTERANGE =
      Pattern.compile("BYTERANGE=\"(\\d+(?:@\\d+)?)\\b\"");
  private static final Pattern REGEX_BYTERANGE_START = Pattern.compile("BYTERANGE-START=(\\d+)\\b");
  private static final Pattern REGEX_BYTERANGE_LENGTH =
      Pattern.compile("BYTERANGE-LENGTH=(\\d+)\\b");
  private static final Pattern REGEX_METHOD =
      Pattern.compile(
          "METHOD=("
              + METHOD_NONE
              + "|"
              + METHOD_AES_128
              + "|"
              + METHOD_SAMPLE_AES
              + "|"
              + METHOD_SAMPLE_AES_CENC
              + "|"
              + METHOD_SAMPLE_AES_CTR
              + ")"
              + "\\s*(?:,|$)");
  private static final Pattern REGEX_KEYFORMAT =
      Pattern.compile("KEYFORMAT=" + ATTR_QUOTED_STRING_VALUE_PATTERN);
  private static final Pattern REGEX_KEYFORMATVERSIONS =
      Pattern.compile("KEYFORMATVERSIONS=" + ATTR_QUOTED_STRING_VALUE_PATTERN);
  private static final Pattern REGEX_URI =
      Pattern.compile("URI=" + ATTR_QUOTED_STRING_VALUE_PATTERN);
  private static final Pattern REGEX_IV = Pattern.compile("IV=([^,.*]+)");
  private static final Pattern REGEX_TYPE =
      Pattern.compile(
          "TYPE=("
              + TYPE_AUDIO
              + "|"
              + TYPE_VIDEO
              + "|"
              + TYPE_SUBTITLES
              + "|"
              + TYPE_CLOSED_CAPTIONS
              + ")");
  private static final Pattern REGEX_PRELOAD_HINT_TYPE =
      Pattern.compile("TYPE=(" + TYPE_PART + "|" + TYPE_MAP + ")");
  private static final Pattern REGEX_LANGUAGE =
      Pattern.compile("LANGUAGE=" + ATTR_QUOTED_STRING_VALUE_PATTERN);
  private static final Pattern REGEX_NAME =
      Pattern.compile("NAME=" + ATTR_QUOTED_STRING_VALUE_PATTERN);
  private static final Pattern REGEX_GROUP_ID =
      Pattern.compile("GROUP-ID=" + ATTR_QUOTED_STRING_VALUE_PATTERN);
  private static final Pattern REGEX_CHARACTERISTICS =
      Pattern.compile("CHARACTERISTICS=" + ATTR_QUOTED_STRING_VALUE_PATTERN);
  private static final Pattern REGEX_INSTREAM_ID =
      Pattern.compile("INSTREAM-ID=\"((?:CC|SERVICE)\\d+)\"");
  private static final Pattern REGEX_AUTOSELECT = compileBooleanAttrPattern("AUTOSELECT");
  private static final Pattern REGEX_DEFAULT = compileBooleanAttrPattern("DEFAULT");
  private static final Pattern REGEX_FORCED = compileBooleanAttrPattern("FORCED");
  private static final Pattern REGEX_INDEPENDENT = compileBooleanAttrPattern("INDEPENDENT");
  private static final Pattern REGEX_GAP = compileBooleanAttrPattern("GAP");
  private static final Pattern REGEX_PRECISE = compileBooleanAttrPattern("PRECISE");
  private static final Pattern REGEX_VALUE =
      Pattern.compile("VALUE=" + ATTR_QUOTED_STRING_VALUE_PATTERN);
  private static final Pattern REGEX_IMPORT =
      Pattern.compile("IMPORT=" + ATTR_QUOTED_STRING_VALUE_PATTERN);
  private static final Pattern REGEX_ID =
      Pattern.compile("[:,]ID=" + ATTR_QUOTED_STRING_VALUE_PATTERN);
  private static final Pattern REGEX_CLASS =
      Pattern.compile("CLASS=" + ATTR_QUOTED_STRING_VALUE_PATTERN);
  private static final Pattern REGEX_START_DATE =
      Pattern.compile("START-DATE=" + ATTR_QUOTED_STRING_VALUE_PATTERN);
  private static final Pattern REGEX_CUE =
      Pattern.compile("CUE=" + ATTR_QUOTED_STRING_VALUE_PATTERN);
  private static final Pattern REGEX_END_DATE =
      Pattern.compile("END-DATE=" + ATTR_QUOTED_STRING_VALUE_PATTERN);
  private static final Pattern REGEX_PLANNED_DURATION =
      Pattern.compile("PLANNED-DURATION=([\\d\\.]+)\\b");
  private static final Pattern REGEX_END_ON_NEXT = compileBooleanAttrPattern("END-ON-NEXT");
  private static final Pattern REGEX_ASSET_URI =
      Pattern.compile("X-ASSET-URI=" + ATTR_QUOTED_STRING_VALUE_PATTERN);
  private static final Pattern REGEX_ASSET_LIST_URI =
      Pattern.compile("X-ASSET-LIST=" + ATTR_QUOTED_STRING_VALUE_PATTERN);
  private static final Pattern REGEX_RESUME_OFFSET =
      Pattern.compile("X-RESUME-OFFSET=(-?[\\d\\.]+)\\b");
  private static final Pattern REGEX_PLAYOUT_LIMIT =
      Pattern.compile("X-PLAYOUT-LIMIT=([\\d\\.]+)\\b");
  private static final Pattern REGEX_SNAP =
      Pattern.compile("X-SNAP=" + ATTR_QUOTED_STRING_VALUE_PATTERN);
  private static final Pattern REGEX_RESTRICT =
      Pattern.compile("X-RESTRICT=" + ATTR_QUOTED_STRING_VALUE_PATTERN);
  private static final Pattern REGEX_CONTENT_MAY_VARY =
      Pattern.compile("X-CONTENT-MAY-VARY=" + ATTR_QUOTED_STRING_VALUE_PATTERN);
  private static final Pattern REGEX_TIMELINE_OCCUPIES =
      Pattern.compile("X-TIMELINE-OCCUPIES=" + ATTR_QUOTED_STRING_VALUE_PATTERN);
  private static final Pattern REGEX_TIMELINE_STYLE =
      Pattern.compile("X-TIMELINE-STYLE=" + ATTR_QUOTED_STRING_VALUE_PATTERN);
  private static final Pattern REGEX_SKIP_CONTROL_OFFSET =
      Pattern.compile("X-SKIP-CONTROL-OFFSET=([\\d\\.]+)\\b");
  private static final Pattern REGEX_SKIP_CONTROL_DURATION =
      Pattern.compile("X-SKIP-CONTROL-DURATION=([\\d\\.]+)\\b");
  private static final Pattern REGEX_SKIP_CONTROL_LABEL_ID =
      Pattern.compile("X-SKIP-CONTROL-LABEL-ID=" + ATTR_QUOTED_STRING_VALUE_PATTERN);
  private static final Pattern REGEX_VARIABLE_REFERENCE =
      Pattern.compile("\\{\\$([a-zA-Z0-9\\-_]+)\\}");
  private static final Pattern REGEX_CLIENT_DEFINED_ATTRIBUTE_PREFIX =
      Pattern.compile("\\b(X-[A-Z0-9-]+)=");

  private static final String DATERANGE_CLASS_INTERSTITIALS = "com.apple.hls.interstitial";

  private final HlsMultivariantPlaylist multivariantPlaylist;
  @Nullable private final HlsMediaPlaylist previousMediaPlaylist;

  /**
   * Creates an instance where media playlists are parsed without inheriting attributes from a
   * multivariant playlist.
   */
  public HlsPlaylistParser() {
    this(HlsMultivariantPlaylist.EMPTY, /* previousMediaPlaylist= */ null);
  }

  /**
   * Creates an instance where parsed media playlists inherit attributes from the given multivariant
   * playlist.
   *
   * @param multivariantPlaylist The multivariant playlist from which media playlists will inherit
   *     attributes.
   * @param previousMediaPlaylist The previous media playlist from which the new media playlist may
   *     inherit skipped segments.
   */
  public HlsPlaylistParser(
      HlsMultivariantPlaylist multivariantPlaylist,
      @Nullable HlsMediaPlaylist previousMediaPlaylist) {
    this.multivariantPlaylist = multivariantPlaylist;
    this.previousMediaPlaylist = previousMediaPlaylist;
  }

  @Override
  public HlsPlaylist parse(Uri uri, InputStream inputStream) throws IOException {
    BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
    Queue<String> extraLines = new ArrayDeque<>();
    String line;
    try {
      if (!checkPlaylistHeader(reader)) {
        throw ParserException.createForMalformedManifest(
            /* message= */ "Input does not start with the #EXTM3U header.", /* cause= */ null);
      }
      while ((line = reader.readLine()) != null) {
        line = line.trim();
        if (line.isEmpty()) {
          // Do nothing.
        } else if (line.startsWith(TAG_STREAM_INF)) {
          extraLines.add(line);
          return parseMultivariantPlaylist(new LineIterator(extraLines, reader), uri.toString());
        } else if (line.startsWith(TAG_TARGET_DURATION)
            || line.startsWith(TAG_MEDIA_SEQUENCE)
            || line.startsWith(TAG_MEDIA_DURATION)
            || line.startsWith(TAG_KEY)
            || line.startsWith(TAG_BYTERANGE)
            || line.equals(TAG_DISCONTINUITY)
            || line.equals(TAG_DISCONTINUITY_SEQUENCE)
            || line.equals(TAG_ENDLIST)) {
          extraLines.add(line);
          return parseMediaPlaylist(
              multivariantPlaylist,
              previousMediaPlaylist,
              new LineIterator(extraLines, reader),
              uri.toString());
        } else {
          extraLines.add(line);
        }
      }
    } finally {
      Util.closeQuietly(reader);
    }
    throw ParserException.createForMalformedManifest(
        "Failed to parse the playlist, could not identify any tags.", /* cause= */ null);
  }

  private static boolean checkPlaylistHeader(BufferedReader reader) throws IOException {
    int last = reader.read();
    if (last == 0xEF) {
      if (reader.read() != 0xBB || reader.read() != 0xBF) {
        return false;
      }
      // The playlist contains a Byte Order Mark, which gets discarded.
      last = reader.read();
    }
    last = skipIgnorableWhitespace(reader, true, last);
    int playlistHeaderLength = PLAYLIST_HEADER.length();
    for (int i = 0; i < playlistHeaderLength; i++) {
      if (last != PLAYLIST_HEADER.charAt(i)) {
        return false;
      }
      last = reader.read();
    }
    last = skipIgnorableWhitespace(reader, false, last);
    return Util.isLinebreak(last);
  }

  private static int skipIgnorableWhitespace(BufferedReader reader, boolean skipLinebreaks, int c)
      throws IOException {
    while (c != -1 && Character.isWhitespace(c) && (skipLinebreaks || !Util.isLinebreak(c))) {
      c = reader.read();
    }
    return c;
  }

  private static boolean isDolbyVisionFormat(
      @Nullable String videoRange,
      @Nullable String codecs,
      @Nullable String supplementalCodecs,
      @Nullable String supplementalProfiles) {
    if (!MimeTypes.isDolbyVisionCodec(codecs, supplementalCodecs)) {
      return false;
    }
    if (supplementalCodecs == null) {
      // Dolby Vision profile 5 that doesn't define supplemental codecs.
      return true;
    }
    if (videoRange == null || supplementalProfiles == null) {
      // Video range and supplemental profiles need to be defined for a full validity check.
      return false;
    }
    if ((videoRange.equals("PQ") && !supplementalProfiles.equals("db1p"))
        || (videoRange.equals("SDR") && !supplementalProfiles.equals("db2g"))
        || (videoRange.equals("HLG") && !supplementalProfiles.startsWith("db4"))) { // db4g or db4h
      return false;
    }
    return true;
  }

  private static HlsMultivariantPlaylist parseMultivariantPlaylist(
      LineIterator iterator, String baseUri) throws IOException {
    HashMap<Uri, ArrayList<VariantInfo>> urlToVariantInfos = new HashMap<>();
    HashMap<String, String> variableDefinitions = new HashMap<>();
    ArrayList<Variant> variants = new ArrayList<>();
    ArrayList<Rendition> videos = new ArrayList<>();
    ArrayList<Rendition> audios = new ArrayList<>();
    ArrayList<Rendition> subtitles = new ArrayList<>();
    ArrayList<Rendition> closedCaptions = new ArrayList<>();
    ArrayList<String> mediaTags = new ArrayList<>();
    ArrayList<DrmInitData> sessionKeyDrmInitData = new ArrayList<>();
    ArrayList<String> tags = new ArrayList<>();
    Format muxedAudioFormat = null;
    List<Format> muxedCaptionFormats = null;
    boolean noClosedCaptions = false;
    boolean hasIndependentSegmentsTag = false;

    String line;
    while (iterator.hasNext()) {
      line = iterator.next();

      if (line.startsWith(TAG_PREFIX)) {
        // We expose all tags through the playlist.
        tags.add(line);
      }
      boolean isIFrameOnlyVariant = line.startsWith(TAG_I_FRAME_STREAM_INF);

      if (line.startsWith(TAG_DEFINE)) {
        variableDefinitions.put(
            /* key= */ parseStringAttr(line, REGEX_NAME, variableDefinitions),
            /* value= */ parseStringAttr(line, REGEX_VALUE, variableDefinitions));
      } else if (line.equals(TAG_INDEPENDENT_SEGMENTS)) {
        hasIndependentSegmentsTag = true;
      } else if (line.startsWith(TAG_MEDIA)) {
        // Media tags are parsed at the end to include codec information from #EXT-X-STREAM-INF
        // tags.
        mediaTags.add(line);
      } else if (line.startsWith(TAG_SESSION_KEY)) {
        String keyFormat =
            parseOptionalStringAttr(line, REGEX_KEYFORMAT, KEYFORMAT_IDENTITY, variableDefinitions);
        SchemeData schemeData = parseDrmSchemeData(line, keyFormat, variableDefinitions);
        if (schemeData != null) {
          String method = parseStringAttr(line, REGEX_METHOD, variableDefinitions);
          String scheme = parseEncryptionScheme(method);
          sessionKeyDrmInitData.add(new DrmInitData(scheme, schemeData));
        }
      } else if (line.startsWith(TAG_STREAM_INF) || isIFrameOnlyVariant) {
        noClosedCaptions |= line.contains(ATTR_CLOSED_CAPTIONS_NONE);
        int roleFlags = isIFrameOnlyVariant ? C.ROLE_FLAG_TRICK_PLAY : 0;
        int peakBitrate = parseIntAttr(line, REGEX_BANDWIDTH);
        int averageBitrate = parseOptionalIntAttr(line, REGEX_AVERAGE_BANDWIDTH, -1);
        String videoRange = parseOptionalStringAttr(line, REGEX_VIDEO_RANGE, variableDefinitions);
        String codecs = parseOptionalStringAttr(line, REGEX_CODECS, variableDefinitions);
        String supplementalCodecsStrings =
            parseOptionalStringAttr(line, REGEX_SUPPLEMENTAL_CODECS, variableDefinitions);
        String supplementalCodecs = null;
        String supplementalProfiles = null; // i.e. Compatibility brand
        if (supplementalCodecsStrings != null) {
          String[] supplementalCodecsString = Util.splitAtFirst(supplementalCodecsStrings, ",");
          // TODO: Support more than one element
          String[] codecsAndProfiles = Util.split(supplementalCodecsString[0], "/");
          supplementalCodecs = codecsAndProfiles[0];
          if (codecsAndProfiles.length > 1) {
            supplementalProfiles = codecsAndProfiles[1];
          }
        }
        String videoCodecs = Util.getCodecsOfType(codecs, C.TRACK_TYPE_VIDEO);
        if (isDolbyVisionFormat(
            videoRange, videoCodecs, supplementalCodecs, supplementalProfiles)) {
          videoCodecs = supplementalCodecs != null ? supplementalCodecs : videoCodecs;
          String nonVideoCodecs = Util.getCodecsWithoutType(codecs, C.TRACK_TYPE_VIDEO);
          codecs = nonVideoCodecs != null ? videoCodecs + "," + nonVideoCodecs : videoCodecs;
        }

        String resolutionString =
            parseOptionalStringAttr(line, REGEX_RESOLUTION, variableDefinitions);
        int width;
        int height;
        if (resolutionString != null) {
          String[] widthAndHeight = Util.split(resolutionString, "x");
          width = Integer.parseInt(widthAndHeight[0]);
          height = Integer.parseInt(widthAndHeight[1]);
          if (width <= 0 || height <= 0) {
            // Resolution string is invalid.
            width = Format.NO_VALUE;
            height = Format.NO_VALUE;
          }
        } else {
          width = Format.NO_VALUE;
          height = Format.NO_VALUE;
        }
        float frameRate = Format.NO_VALUE;
        String frameRateString =
            parseOptionalStringAttr(line, REGEX_FRAME_RATE, variableDefinitions);
        if (frameRateString != null) {
          frameRate = Float.parseFloat(frameRateString);
        }
        String videoGroupId = parseOptionalStringAttr(line, REGEX_VIDEO, variableDefinitions);
        String audioGroupId = parseOptionalStringAttr(line, REGEX_AUDIO, variableDefinitions);
        String subtitlesGroupId =
            parseOptionalStringAttr(line, REGEX_SUBTITLES, variableDefinitions);
        String closedCaptionsGroupId =
            parseOptionalStringAttr(line, REGEX_CLOSED_CAPTIONS, variableDefinitions);
        Uri uri;
        if (isIFrameOnlyVariant) {
          uri =
              UriUtil.resolveToUri(baseUri, parseStringAttr(line, REGEX_URI, variableDefinitions));
        } else if (!iterator.hasNext()) {
          throw ParserException.createForMalformedManifest(
              "#EXT-X-STREAM-INF must be followed by another line", /* cause= */ null);
        } else {
          // The following line contains #EXT-X-STREAM-INF's URI.
          line = replaceVariableReferences(iterator.next(), variableDefinitions);
          uri = UriUtil.resolveToUri(baseUri, line);
        }

        Format format =
            new Format.Builder()
                .setId(variants.size())
                .setContainerMimeType(MimeTypes.APPLICATION_M3U8)
                .setCodecs(codecs)
                .setAverageBitrate(averageBitrate)
                .setPeakBitrate(peakBitrate)
                .setWidth(width)
                .setHeight(height)
                .setFrameRate(frameRate)
                .setRoleFlags(roleFlags)
                .build();
        Variant variant =
            new Variant(
                uri, format, videoGroupId, audioGroupId, subtitlesGroupId, closedCaptionsGroupId);
        variants.add(variant);
        @Nullable ArrayList<VariantInfo> variantInfosForUrl = urlToVariantInfos.get(uri);
        if (variantInfosForUrl == null) {
          variantInfosForUrl = new ArrayList<>();
          urlToVariantInfos.put(uri, variantInfosForUrl);
        }
        variantInfosForUrl.add(
            new VariantInfo(
                averageBitrate,
                peakBitrate,
                videoGroupId,
                audioGroupId,
                subtitlesGroupId,
                closedCaptionsGroupId));
      }
    }

    // TODO: Don't deduplicate variants by URL.
    ArrayList<Variant> deduplicatedVariants = new ArrayList<>();
    HashSet<Uri> urlsInDeduplicatedVariants = new HashSet<>();
    for (int i = 0; i < variants.size(); i++) {
      Variant variant = variants.get(i);
      if (urlsInDeduplicatedVariants.add(variant.url)) {
        Assertions.checkState(variant.format.metadata == null);
        HlsTrackMetadataEntry hlsMetadataEntry =
            new HlsTrackMetadataEntry(
                /* groupId= */ null,
                /* name= */ null,
                checkNotNull(urlToVariantInfos.get(variant.url)));
        Metadata metadata = new Metadata(hlsMetadataEntry);
        Format format = variant.format.buildUpon().setMetadata(metadata).build();
        deduplicatedVariants.add(variant.copyWithFormat(format));
      }
    }

    for (int i = 0; i < mediaTags.size(); i++) {
      line = mediaTags.get(i);
      String groupId = parseStringAttr(line, REGEX_GROUP_ID, variableDefinitions);
      String name = parseStringAttr(line, REGEX_NAME, variableDefinitions);
      Format.Builder formatBuilder =
          new Format.Builder()
              .setId(groupId + ":" + name)
              .setLabel(name)
              .setContainerMimeType(MimeTypes.APPLICATION_M3U8)
              .setSelectionFlags(parseSelectionFlags(line))
              .setRoleFlags(parseRoleFlags(line, variableDefinitions))
              .setLanguage(parseOptionalStringAttr(line, REGEX_LANGUAGE, variableDefinitions));

      @Nullable String referenceUri = parseOptionalStringAttr(line, REGEX_URI, variableDefinitions);
      @Nullable Uri uri = referenceUri == null ? null : UriUtil.resolveToUri(baseUri, referenceUri);
      Metadata metadata =
          new Metadata(new HlsTrackMetadataEntry(groupId, name, Collections.emptyList()));
      switch (parseStringAttr(line, REGEX_TYPE, variableDefinitions)) {
        case TYPE_VIDEO:
          @Nullable Variant variant = getVariantWithVideoGroup(variants, groupId);
          if (variant != null) {
            Format variantFormat = variant.format;
            @Nullable
            String codecs = Util.getCodecsOfType(variantFormat.codecs, C.TRACK_TYPE_VIDEO);
            formatBuilder
                .setCodecs(codecs)
                .setSampleMimeType(MimeTypes.getMediaMimeType(codecs))
                .setWidth(variantFormat.width)
                .setHeight(variantFormat.height)
                .setFrameRate(variantFormat.frameRate);
          }
          if (uri == null) {
            // TODO: Remove this case and add a Rendition with a null uri to videos.
          } else {
            formatBuilder.setMetadata(metadata);
            videos.add(new Rendition(uri, formatBuilder.build(), groupId, name));
          }
          break;
        case TYPE_AUDIO:
          @Nullable String sampleMimeType = null;
          variant = getVariantWithAudioGroup(variants, groupId);
          if (variant != null) {
            @Nullable
            String codecs = Util.getCodecsOfType(variant.format.codecs, C.TRACK_TYPE_AUDIO);
            formatBuilder.setCodecs(codecs);
            sampleMimeType = MimeTypes.getMediaMimeType(codecs);
          }
          @Nullable
          String channelsString =
              parseOptionalStringAttr(line, REGEX_CHANNELS, variableDefinitions);
          if (channelsString != null) {
            int channelCount = Integer.parseInt(Util.splitAtFirst(channelsString, "/")[0]);
            formatBuilder.setChannelCount(channelCount);
            if (MimeTypes.AUDIO_E_AC3.equals(sampleMimeType) && channelsString.endsWith("/JOC")) {
              sampleMimeType = MimeTypes.AUDIO_E_AC3_JOC;
              formatBuilder.setCodecs(MimeTypes.CODEC_E_AC3_JOC);
            }
          }
          formatBuilder.setSampleMimeType(sampleMimeType);
          if (uri != null) {
            formatBuilder.setMetadata(metadata);
            audios.add(new Rendition(uri, formatBuilder.build(), groupId, name));
          } else if (variant != null) {
            // TODO: Remove muxedAudioFormat and add a Rendition with a null uri to audios.
            muxedAudioFormat = formatBuilder.build();
          }
          break;
        case TYPE_SUBTITLES:
          sampleMimeType = null;
          variant = getVariantWithSubtitleGroup(variants, groupId);
          if (variant != null) {
            @Nullable
            String codecs = Util.getCodecsOfType(variant.format.codecs, C.TRACK_TYPE_TEXT);
            formatBuilder.setCodecs(codecs);
            sampleMimeType = MimeTypes.getMediaMimeType(codecs);
          }
          if (sampleMimeType == null) {
            sampleMimeType = MimeTypes.TEXT_VTT;
          }
          formatBuilder.setSampleMimeType(sampleMimeType).setMetadata(metadata);
          if (uri != null) {
            subtitles.add(new Rendition(uri, formatBuilder.build(), groupId, name));
          } else {
            Log.w(LOG_TAG, "EXT-X-MEDIA tag with missing mandatory URI attribute: skipping");
          }
          break;
        case TYPE_CLOSED_CAPTIONS:
          String instreamId = parseStringAttr(line, REGEX_INSTREAM_ID, variableDefinitions);
          int accessibilityChannel;
          if (instreamId.startsWith("CC")) {
            sampleMimeType = MimeTypes.APPLICATION_CEA608;
            accessibilityChannel = Integer.parseInt(instreamId.substring(2));
          } else /* starts with SERVICE */ {
            sampleMimeType = MimeTypes.APPLICATION_CEA708;
            accessibilityChannel = Integer.parseInt(instreamId.substring(7));
          }
          if (muxedCaptionFormats == null) {
            muxedCaptionFormats = new ArrayList<>();
          }
          formatBuilder
              .setSampleMimeType(sampleMimeType)
              .setAccessibilityChannel(accessibilityChannel);
          muxedCaptionFormats.add(formatBuilder.build());
          // TODO: Remove muxedCaptionFormats and add a Rendition with a null uri to closedCaptions.
          break;
        default:
          // Do nothing.
          break;
      }
    }

    if (noClosedCaptions) {
      muxedCaptionFormats = Collections.emptyList();
    }

    return new HlsMultivariantPlaylist(
        baseUri,
        tags,
        deduplicatedVariants,
        videos,
        audios,
        subtitles,
        closedCaptions,
        muxedAudioFormat,
        muxedCaptionFormats,
        hasIndependentSegmentsTag,
        variableDefinitions,
        sessionKeyDrmInitData);
  }

  @Nullable
  private static Variant getVariantWithAudioGroup(ArrayList<Variant> variants, String groupId) {
    for (int i = 0; i < variants.size(); i++) {
      Variant variant = variants.get(i);
      if (groupId.equals(variant.audioGroupId)) {
        return variant;
      }
    }
    return null;
  }

  @Nullable
  private static Variant getVariantWithVideoGroup(ArrayList<Variant> variants, String groupId) {
    for (int i = 0; i < variants.size(); i++) {
      Variant variant = variants.get(i);
      if (groupId.equals(variant.videoGroupId)) {
        return variant;
      }
    }
    return null;
  }

  @Nullable
  private static Variant getVariantWithSubtitleGroup(ArrayList<Variant> variants, String groupId) {
    for (int i = 0; i < variants.size(); i++) {
      Variant variant = variants.get(i);
      if (groupId.equals(variant.subtitleGroupId)) {
        return variant;
      }
    }
    return null;
  }

  private static HlsMediaPlaylist parseMediaPlaylist(
      HlsMultivariantPlaylist multivariantPlaylist,
      @Nullable HlsMediaPlaylist previousMediaPlaylist,
      LineIterator iterator,
      String baseUri)
      throws IOException {
    @HlsMediaPlaylist.PlaylistType int playlistType = HlsMediaPlaylist.PLAYLIST_TYPE_UNKNOWN;
    long startOffsetUs = C.TIME_UNSET;
    long mediaSequence = 0;
    int version = 1; // Default version == 1.
    long targetDurationUs = C.TIME_UNSET;
    long partTargetDurationUs = C.TIME_UNSET;
    boolean hasIndependentSegmentsTag = multivariantPlaylist.hasIndependentSegments;
    boolean hasEndTag = false;
    @Nullable Segment initializationSegment = null;
    HashMap<String, String> variableDefinitions = new HashMap<>();
    HashMap<String, Segment> urlToInferredInitSegment = new HashMap<>();
    List<Segment> segments = new ArrayList<>();
    List<Part> trailingParts = new ArrayList<>();
    @Nullable Part preloadPart = null;
    List<RenditionReport> renditionReports = new ArrayList<>();
    List<String> tags = new ArrayList<>();
    LinkedHashMap<String, Interstitial.Builder> interstitialBuilderMap = new LinkedHashMap<>();

    long segmentDurationUs = 0;
    String segmentTitle = "";
    boolean hasDiscontinuitySequence = false;
    int playlistDiscontinuitySequence = 0;
    int relativeDiscontinuitySequence = 0;
    long playlistStartTimeUs = 0;
    long segmentStartTimeUs = 0;
    boolean preciseStart = false;
    long segmentByteRangeOffset = 0;
    long segmentByteRangeLength = C.LENGTH_UNSET;
    long partStartTimeUs = 0;
    long partByteRangeOffset = 0;
    boolean isIFrameOnly = false;
    long segmentMediaSequence = 0;
    boolean hasGapTag = false;
    HlsMediaPlaylist.ServerControl serverControl =
        new HlsMediaPlaylist.ServerControl(
            /* skipUntilUs= */ C.TIME_UNSET,
            /* canSkipDateRanges= */ false,
            /* holdBackUs= */ C.TIME_UNSET,
            /* partHoldBackUs= */ C.TIME_UNSET,
            /* canBlockReload= */ false);

    @Nullable DrmInitData playlistProtectionSchemes = null;
    @Nullable String fullSegmentEncryptionKeyUri = null;
    @Nullable String fullSegmentEncryptionIV = null;
    TreeMap<String, SchemeData> currentSchemeDatas = new TreeMap<>();
    @Nullable String encryptionScheme = null;
    @Nullable DrmInitData cachedDrmInitData = null;

    String line;
    while (iterator.hasNext()) {
      line = iterator.next();

      if (line.startsWith(TAG_PREFIX)) {
        // We expose all tags through the playlist.
        tags.add(line);
      }

      if (line.startsWith(TAG_PLAYLIST_TYPE)) {
        String playlistTypeString = parseStringAttr(line, REGEX_PLAYLIST_TYPE, variableDefinitions);
        if ("VOD".equals(playlistTypeString)) {
          playlistType = HlsMediaPlaylist.PLAYLIST_TYPE_VOD;
        } else if ("EVENT".equals(playlistTypeString)) {
          playlistType = HlsMediaPlaylist.PLAYLIST_TYPE_EVENT;
        }
      } else if (line.equals(TAG_IFRAME)) {
        isIFrameOnly = true;
      } else if (line.startsWith(TAG_START)) {
        startOffsetUs = (long) (parseDoubleAttr(line, REGEX_TIME_OFFSET) * C.MICROS_PER_SECOND);
        preciseStart =
            parseOptionalBooleanAttribute(line, REGEX_PRECISE, /* defaultValue= */ false);
      } else if (line.startsWith(TAG_SERVER_CONTROL)) {
        serverControl = parseServerControl(line);
      } else if (line.startsWith(TAG_PART_INF)) {
        double partTargetDurationSeconds = parseDoubleAttr(line, REGEX_PART_TARGET_DURATION);
        partTargetDurationUs = (long) (partTargetDurationSeconds * C.MICROS_PER_SECOND);
      } else if (line.startsWith(TAG_INIT_SEGMENT)) {
        String uri = parseStringAttr(line, REGEX_URI, variableDefinitions);
        String byteRange = parseOptionalStringAttr(line, REGEX_ATTR_BYTERANGE, variableDefinitions);
        if (byteRange != null) {
          String[] splitByteRange = Util.split(byteRange, "@");
          segmentByteRangeLength = Long.parseLong(splitByteRange[0]);
          if (splitByteRange.length > 1) {
            segmentByteRangeOffset = Long.parseLong(splitByteRange[1]);
          }
        }
        if (segmentByteRangeLength == C.LENGTH_UNSET) {
          // The segment has no byte range defined.
          segmentByteRangeOffset = 0;
        }
        if (fullSegmentEncryptionKeyUri != null && fullSegmentEncryptionIV == null) {
          // See RFC 8216, Section 4.3.2.5.
          throw ParserException.createForMalformedManifest(
              "The encryption IV attribute must be present when an initialization segment is"
                  + " encrypted with METHOD=AES-128.",
              /* cause= */ null);
        }
        initializationSegment =
            new Segment(
                uri,
                segmentByteRangeOffset,
                segmentByteRangeLength,
                fullSegmentEncryptionKeyUri,
                fullSegmentEncryptionIV);
        if (segmentByteRangeLength != C.LENGTH_UNSET) {
          segmentByteRangeOffset += segmentByteRangeLength;
        }
        segmentByteRangeLength = C.LENGTH_UNSET;
      } else if (line.startsWith(TAG_TARGET_DURATION)) {
        targetDurationUs = parseIntAttr(line, REGEX_TARGET_DURATION) * C.MICROS_PER_SECOND;
      } else if (line.startsWith(TAG_MEDIA_SEQUENCE)) {
        mediaSequence = parseLongAttr(line, REGEX_MEDIA_SEQUENCE);
        segmentMediaSequence = mediaSequence;
      } else if (line.startsWith(TAG_VERSION)) {
        version = parseIntAttr(line, REGEX_VERSION);
      } else if (line.startsWith(TAG_DEFINE)) {
        String importName = parseOptionalStringAttr(line, REGEX_IMPORT, variableDefinitions);
        if (importName != null) {
          String value = multivariantPlaylist.variableDefinitions.get(importName);
          if (value != null) {
            variableDefinitions.put(importName, value);
          } else {
            // The multivariant playlist does not declare the imported variable. Ignore.
          }
        } else {
          variableDefinitions.put(
              parseStringAttr(line, REGEX_NAME, variableDefinitions),
              parseStringAttr(line, REGEX_VALUE, variableDefinitions));
        }
      } else if (line.startsWith(TAG_MEDIA_DURATION)) {
        segmentDurationUs = parseTimeSecondsToUs(line, REGEX_MEDIA_DURATION);
        segmentTitle = parseOptionalStringAttr(line, REGEX_MEDIA_TITLE, "", variableDefinitions);
      } else if (line.startsWith(TAG_SKIP)) {
        int skippedSegmentCount = parseIntAttr(line, REGEX_SKIPPED_SEGMENTS);
        checkState(previousMediaPlaylist != null && segments.isEmpty());
        int startIndex = (int) (mediaSequence - castNonNull(previousMediaPlaylist).mediaSequence);
        int endIndex = startIndex + skippedSegmentCount;
        if (startIndex < 0 || endIndex > previousMediaPlaylist.segments.size()) {
          // Throw to force a reload if not all segments are available in the previous playlist.
          throw new DeltaUpdateException();
        }
        for (int i = startIndex; i < endIndex; i++) {
          Segment segment = previousMediaPlaylist.segments.get(i);
          if (mediaSequence != previousMediaPlaylist.mediaSequence) {
            // If the media sequences of the playlists are not the same, we need to recreate the
            // object with the updated relative start time and the relative discontinuity
            // sequence. With identical playlist media sequences these values do not change.
            int newRelativeDiscontinuitySequence =
                previousMediaPlaylist.discontinuitySequence
                    - playlistDiscontinuitySequence
                    + segment.relativeDiscontinuitySequence;
            segment = segment.copyWith(segmentStartTimeUs, newRelativeDiscontinuitySequence);
          }
          segments.add(segment);
          segmentStartTimeUs += segment.durationUs;
          partStartTimeUs = segmentStartTimeUs;
          if (segment.byteRangeLength != C.LENGTH_UNSET) {
            segmentByteRangeOffset = segment.byteRangeOffset + segment.byteRangeLength;
          }
          relativeDiscontinuitySequence = segment.relativeDiscontinuitySequence;
          initializationSegment = segment.initializationSegment;
          cachedDrmInitData = segment.drmInitData;
          fullSegmentEncryptionKeyUri = segment.fullSegmentEncryptionKeyUri;
          if (segment.encryptionIV == null
              || !segment.encryptionIV.equals(Long.toHexString(segmentMediaSequence))) {
            fullSegmentEncryptionIV = segment.encryptionIV;
          }
          segmentMediaSequence++;
        }
      } else if (line.startsWith(TAG_KEY)) {
        String method = parseStringAttr(line, REGEX_METHOD, variableDefinitions);
        String keyFormat =
            parseOptionalStringAttr(line, REGEX_KEYFORMAT, KEYFORMAT_IDENTITY, variableDefinitions);
        fullSegmentEncryptionKeyUri = null;
        fullSegmentEncryptionIV = null;
        if (METHOD_NONE.equals(method)) {
          currentSchemeDatas.clear();
          cachedDrmInitData = null;
        } else /* !METHOD_NONE.equals(method) */ {
          fullSegmentEncryptionIV = parseOptionalStringAttr(line, REGEX_IV, variableDefinitions);
          if (KEYFORMAT_IDENTITY.equals(keyFormat)) {
            if (METHOD_AES_128.equals(method)) {
              // The segment is fully encrypted using an identity key.
              fullSegmentEncryptionKeyUri = parseStringAttr(line, REGEX_URI, variableDefinitions);
            } else {
              // Do nothing. Samples are encrypted using an identity key, but this is not supported.
              // Hopefully, a traditional DRM alternative is also provided.
            }
          } else {
            if (encryptionScheme == null) {
              encryptionScheme = parseEncryptionScheme(method);
            }
            SchemeData schemeData = parseDrmSchemeData(line, keyFormat, variableDefinitions);
            if (schemeData != null) {
              cachedDrmInitData = null;
              currentSchemeDatas.put(keyFormat, schemeData);
            }
          }
        }
      } else if (line.startsWith(TAG_BYTERANGE)) {
        String byteRange = parseStringAttr(line, REGEX_BYTERANGE, variableDefinitions);
        String[] splitByteRange = Util.split(byteRange, "@");
        segmentByteRangeLength = Long.parseLong(splitByteRange[0]);
        if (splitByteRange.length > 1) {
          segmentByteRangeOffset = Long.parseLong(splitByteRange[1]);
        }
      } else if (line.startsWith(TAG_DISCONTINUITY_SEQUENCE)) {
        hasDiscontinuitySequence = true;
        playlistDiscontinuitySequence = Integer.parseInt(line.substring(line.indexOf(':') + 1));
      } else if (line.equals(TAG_DISCONTINUITY)) {
        relativeDiscontinuitySequence++;
      } else if (line.startsWith(TAG_PROGRAM_DATE_TIME)) {
        if (playlistStartTimeUs == 0) {
          long programDatetimeUs =
              msToUs(Util.parseXsDateTime(line.substring(line.indexOf(':') + 1)));
          playlistStartTimeUs = programDatetimeUs - segmentStartTimeUs;
        }
      } else if (line.equals(TAG_GAP)) {
        hasGapTag = true;
      } else if (line.equals(TAG_INDEPENDENT_SEGMENTS)) {
        hasIndependentSegmentsTag = true;
      } else if (line.equals(TAG_ENDLIST)) {
        hasEndTag = true;
      } else if (line.startsWith(TAG_RENDITION_REPORT)) {
        long lastMediaSequence = parseOptionalLongAttr(line, REGEX_LAST_MSN, C.INDEX_UNSET);
        int lastPartIndex = parseOptionalIntAttr(line, REGEX_LAST_PART, C.INDEX_UNSET);
        String uri = parseStringAttr(line, REGEX_URI, variableDefinitions);
        Uri playlistUri = Uri.parse(UriUtil.resolve(baseUri, uri));
        renditionReports.add(new RenditionReport(playlistUri, lastMediaSequence, lastPartIndex));
      } else if (line.startsWith(TAG_PRELOAD_HINT)) {
        if (preloadPart != null) {
          continue;
        }
        String type = parseStringAttr(line, REGEX_PRELOAD_HINT_TYPE, variableDefinitions);
        if (!TYPE_PART.equals(type)) {
          continue;
        }
        String url = parseStringAttr(line, REGEX_URI, variableDefinitions);
        long byteRangeStart =
            parseOptionalLongAttr(line, REGEX_BYTERANGE_START, /* defaultValue= */ C.LENGTH_UNSET);
        long byteRangeLength =
            parseOptionalLongAttr(line, REGEX_BYTERANGE_LENGTH, /* defaultValue= */ C.LENGTH_UNSET);
        @Nullable
        String segmentEncryptionIV =
            getSegmentEncryptionIV(
                segmentMediaSequence, fullSegmentEncryptionKeyUri, fullSegmentEncryptionIV);
        if (cachedDrmInitData == null && !currentSchemeDatas.isEmpty()) {
          SchemeData[] schemeDatas = currentSchemeDatas.values().toArray(new SchemeData[0]);
          cachedDrmInitData = new DrmInitData(encryptionScheme, schemeDatas);
          if (playlistProtectionSchemes == null) {
            playlistProtectionSchemes = getPlaylistProtectionSchemes(encryptionScheme, schemeDatas);
          }
        }
        if (byteRangeStart == C.LENGTH_UNSET || byteRangeLength != C.LENGTH_UNSET) {
          // Skip preload part if it is an unbounded range request.
          preloadPart =
              new Part(
                  url,
                  initializationSegment,
                  /* durationUs= */ 0,
                  relativeDiscontinuitySequence,
                  partStartTimeUs,
                  cachedDrmInitData,
                  fullSegmentEncryptionKeyUri,
                  segmentEncryptionIV,
                  byteRangeStart != C.LENGTH_UNSET ? byteRangeStart : 0,
                  byteRangeLength,
                  /* hasGapTag= */ false,
                  /* isIndependent= */ false,
                  /* isPreload= */ true);
        }
      } else if (line.startsWith(TAG_PART)) {
        @Nullable
        String segmentEncryptionIV =
            getSegmentEncryptionIV(
                segmentMediaSequence, fullSegmentEncryptionKeyUri, fullSegmentEncryptionIV);
        String url = parseStringAttr(line, REGEX_URI, variableDefinitions);
        long partDurationUs =
            (long) (parseDoubleAttr(line, REGEX_ATTR_DURATION) * C.MICROS_PER_SECOND);
        boolean isIndependent =
            parseOptionalBooleanAttribute(line, REGEX_INDEPENDENT, /* defaultValue= */ false);
        // The first part of a segment is always independent if the segments are independent.
        isIndependent |= hasIndependentSegmentsTag && trailingParts.isEmpty();
        boolean isGap = parseOptionalBooleanAttribute(line, REGEX_GAP, /* defaultValue= */ false);
        @Nullable
        String byteRange = parseOptionalStringAttr(line, REGEX_ATTR_BYTERANGE, variableDefinitions);
        long partByteRangeLength = C.LENGTH_UNSET;
        if (byteRange != null) {
          String[] splitByteRange = Util.split(byteRange, "@");
          partByteRangeLength = Long.parseLong(splitByteRange[0]);
          if (splitByteRange.length > 1) {
            partByteRangeOffset = Long.parseLong(splitByteRange[1]);
          }
        }
        if (partByteRangeLength == C.LENGTH_UNSET) {
          partByteRangeOffset = 0;
        }
        if (cachedDrmInitData == null && !currentSchemeDatas.isEmpty()) {
          SchemeData[] schemeDatas = currentSchemeDatas.values().toArray(new SchemeData[0]);
          cachedDrmInitData = new DrmInitData(encryptionScheme, schemeDatas);
          if (playlistProtectionSchemes == null) {
            playlistProtectionSchemes = getPlaylistProtectionSchemes(encryptionScheme, schemeDatas);
          }
        }
        trailingParts.add(
            new Part(
                url,
                initializationSegment,
                partDurationUs,
                relativeDiscontinuitySequence,
                partStartTimeUs,
                cachedDrmInitData,
                fullSegmentEncryptionKeyUri,
                segmentEncryptionIV,
                partByteRangeOffset,
                partByteRangeLength,
                isGap,
                isIndependent,
                /* isPreload= */ false));
        partStartTimeUs += partDurationUs;
        if (partByteRangeLength != C.LENGTH_UNSET) {
          partByteRangeOffset += partByteRangeLength;
        }
      } else if (line.startsWith(TAG_DATERANGE)
          && parseOptionalStringAttr(line, REGEX_CLASS, /* defaultValue= */ "", variableDefinitions)
              .equals(DATERANGE_CLASS_INTERSTITIALS)) {
        String id = parseStringAttr(line, REGEX_ID, variableDefinitions);
        @Nullable Uri assetUri = null;
        String assetUriString = parseOptionalStringAttr(line, REGEX_ASSET_URI, variableDefinitions);
        if (assetUriString != null) {
          assetUri = Uri.parse(assetUriString);
        }
        @Nullable Uri assetListUri = null;
        String assetListUriString =
            parseOptionalStringAttr(line, REGEX_ASSET_LIST_URI, variableDefinitions);
        if (assetListUriString != null) {
          assetListUri = Uri.parse(assetListUriString);
        }
        long startDateUnixUs = C.TIME_UNSET;
        @Nullable
        String startDateUnixMsString =
            parseOptionalStringAttr(line, REGEX_START_DATE, variableDefinitions);
        if (startDateUnixMsString != null) {
          startDateUnixUs = msToUs(parseXsDateTime(startDateUnixMsString));
        }
        long endDateUnixUs = C.TIME_UNSET;
        @Nullable
        String endDateUnixMsString =
            parseOptionalStringAttr(line, REGEX_END_DATE, variableDefinitions);
        if (endDateUnixMsString != null) {
          endDateUnixUs = msToUs(parseXsDateTime(endDateUnixMsString));
        }
        List<@Interstitial.CueTriggerType String> cue = new ArrayList<>();
        String cueString = parseOptionalStringAttr(line, REGEX_CUE, variableDefinitions);
        if (cueString != null) {
          String[] identifiers = Util.split(/* value= */ cueString, /* regex= */ ",");
          for (String identifier : identifiers) {
            identifier = identifier.trim();
            switch (identifier) {
              case CUE_TRIGGER_ONCE:
              case CUE_TRIGGER_POST:
              case CUE_TRIGGER_PRE:
                cue.add(identifier);
                break;
              default:
                break; // ignore for forward compatibility
            }
          }
        }
        double durationSec = parseOptionalDoubleAttr(line, REGEX_ATTR_DURATION_PREFIXED, -1.0d);
        long durationUs = C.TIME_UNSET;
        if (durationSec >= 0) {
          durationUs = (long) (durationSec * C.MICROS_PER_SECOND);
        }
        double plannedDurationSec = parseOptionalDoubleAttr(line, REGEX_PLANNED_DURATION, -1.0d);
        long plannedDurationUs = C.TIME_UNSET;
        if (plannedDurationSec >= 0) {
          plannedDurationUs = (long) (plannedDurationSec * C.MICROS_PER_SECOND);
        }
        boolean endOnNext = parseOptionalBooleanAttribute(line, REGEX_END_ON_NEXT, false);
        double resumeOffsetUsDouble =
            parseOptionalDoubleAttr(line, REGEX_RESUME_OFFSET, Double.MIN_VALUE);
        long resumeOffsetUs = C.TIME_UNSET;
        if (resumeOffsetUsDouble != Double.MIN_VALUE) {
          resumeOffsetUs = (long) (resumeOffsetUsDouble * C.MICROS_PER_SECOND);
        }
        double playoutLimitSec = parseOptionalDoubleAttr(line, REGEX_PLAYOUT_LIMIT, -1.0d);
        long playoutLimitUs = C.TIME_UNSET;
        if (playoutLimitSec >= 0) {
          playoutLimitUs = (long) (playoutLimitSec * C.MICROS_PER_SECOND);
        }
        List<@Interstitial.SnapType String> snapTypes = new ArrayList<>();
        String snapTypesString = parseOptionalStringAttr(line, REGEX_SNAP, variableDefinitions);
        if (snapTypesString != null) {
          String[] snapTypesSplit = Util.split(snapTypesString, ",");
          for (String snapType : snapTypesSplit) {
            snapType = snapType.trim();
            switch (snapType) {
              case Interstitial.SNAP_TYPE_IN:
              case Interstitial.SNAP_TYPE_OUT:
                snapTypes.add(snapType);
                break;
              default:
                break; // ignore for forward compatibility
            }
          }
        }
        List<@Interstitial.NavigationRestriction String> restrictions = new ArrayList<>();
        String restrictionsString =
            parseOptionalStringAttr(line, REGEX_RESTRICT, variableDefinitions);
        if (restrictionsString != null) {
          String[] restrictionsSplit = Util.split(restrictionsString, ",");
          for (String restriction : restrictionsSplit) {
            restriction = restriction.trim();
            switch (restriction) {
              case Interstitial.NAVIGATION_RESTRICTION_JUMP:
              case Interstitial.NAVIGATION_RESTRICTION_SKIP:
                restrictions.add(restriction);
                break;
              default:
                break; // ignore for forward compatibility
            }
          }
        }

        @Nullable Boolean contentMayVary = null;
        String contentMayVaryString =
            parseOptionalStringAttr(line, REGEX_CONTENT_MAY_VARY, variableDefinitions);
        if (contentMayVaryString != null) {
          contentMayVary = !contentMayVaryString.equals(BOOLEAN_FALSE); // default is true
        }

        @Nullable String timelineOccupies = null;
        String timelineOccupiesString =
            parseOptionalStringAttr(line, REGEX_TIMELINE_OCCUPIES, variableDefinitions);
        if (timelineOccupiesString != null) {
          if (timelineOccupiesString.equals(Interstitial.TIMELINE_OCCUPIES_RANGE)) {
            timelineOccupies = Interstitial.TIMELINE_OCCUPIES_RANGE;
          } else if (timelineOccupiesString.equals(Interstitial.TIMELINE_OCCUPIES_POINT)) {
            timelineOccupies = Interstitial.TIMELINE_OCCUPIES_POINT;
          }
        }

        @Nullable String timelineStyle = null;
        String timelineStyleString =
            parseOptionalStringAttr(line, REGEX_TIMELINE_STYLE, variableDefinitions);
        if (timelineStyleString != null) {
          if (timelineStyleString.equals(Interstitial.TIMELINE_STYLE_PRIMARY)) {
            timelineStyle = Interstitial.TIMELINE_STYLE_PRIMARY;
          } else if (timelineStyleString.equals(Interstitial.TIMELINE_STYLE_HIGHLIGHT)) {
            timelineStyle = Interstitial.TIMELINE_STYLE_HIGHLIGHT;
          }
        }

        double skipControlOffsetSec =
            parseOptionalDoubleAttr(line, REGEX_SKIP_CONTROL_OFFSET, -1.0d);
        long skipControlOffsetUs = C.TIME_UNSET;
        if (skipControlOffsetSec >= 0) {
          skipControlOffsetUs = (long) (skipControlOffsetSec * C.MICROS_PER_SECOND);
        }
        double skipControlDurationSec =
            parseOptionalDoubleAttr(line, REGEX_SKIP_CONTROL_DURATION, -1.0d);
        long skipControlDurationUs = C.TIME_UNSET;
        if (skipControlDurationSec >= 0) {
          skipControlDurationUs = (long) (skipControlDurationSec * C.MICROS_PER_SECOND);
        }
        @Nullable
        String skipControlLabelId =
            parseOptionalStringAttr(line, REGEX_SKIP_CONTROL_LABEL_ID, variableDefinitions);

        List<HlsMediaPlaylist.ClientDefinedAttribute> clientDefinedAttributes = new ArrayList<>();
        String attributes = line.substring("#EXT-X-DATERANGE:".length());
        Matcher matcher = REGEX_CLIENT_DEFINED_ATTRIBUTE_PREFIX.matcher(attributes);
        while (matcher.find()) {
          String attributePrefix = matcher.group();
          switch (attributePrefix) {
            case "X-ASSET-URI=": // fall through
            case "X-ASSET-LIST=": // fall through
            case "X-RESUME-OFFSET=": // fall through
            case "X-PLAYOUT-LIMIT=": // fall through
            case "X-SNAP=": // fall through
            case "X-RESTRICT=": // fall through
            case "X-CONTENT-MAY-VARY=": // fall through
            case "X-TIMELINE-OCCUPIES=": // fall through
            case "X-TIMELINE-STYLE=": // fall through
            case "X-SKIP-CONTROL-OFFSET=": // fall through
            case "X-SKIP-CONTROL-DURATION=": // fall through
            case "X-SKIP-CONTROL-LABEL-ID=":
              // ignore interstitial attributes
              break;
            default:
              clientDefinedAttributes.add(
                  parseClientDefinedAttribute(
                      attributes,
                      attributePrefix.substring(0, attributePrefix.length() - 1),
                      variableDefinitions));
              break;
          }
        }

        Interstitial.Builder interstitialBuilder =
            (interstitialBuilderMap.containsKey(id)
                    ? interstitialBuilderMap.get(id)
                    : new Interstitial.Builder(id))
                .setAssetUri(assetUri)
                .setAssetListUri(assetListUri)
                .setStartDateUnixUs(startDateUnixUs)
                .setEndDateUnixUs(endDateUnixUs)
                .setDurationUs(durationUs)
                .setPlannedDurationUs(plannedDurationUs)
                .setCue(cue)
                .setEndOnNext(endOnNext)
                .setResumeOffsetUs(resumeOffsetUs)
                .setPlayoutLimitUs(playoutLimitUs)
                .setSnapTypes(snapTypes)
                .setRestrictions(restrictions)
                .setClientDefinedAttributes(clientDefinedAttributes)
                .setContentMayVary(contentMayVary)
                .setTimelineOccupies(timelineOccupies)
                .setTimelineStyle(timelineStyle)
                .setSkipControlOffsetUs(skipControlOffsetUs)
                .setSkipControlDurationUs(skipControlDurationUs)
                .setSkipControlLabelId(skipControlLabelId);
        interstitialBuilderMap.put(id, interstitialBuilder);
      } else if (!line.startsWith("#")) {
        @Nullable
        String segmentEncryptionIV =
            getSegmentEncryptionIV(
                segmentMediaSequence, fullSegmentEncryptionKeyUri, fullSegmentEncryptionIV);
        segmentMediaSequence++;
        String segmentUri = replaceVariableReferences(line, variableDefinitions);
        @Nullable Segment inferredInitSegment = urlToInferredInitSegment.get(segmentUri);
        if (segmentByteRangeLength == C.LENGTH_UNSET) {
          // The segment has no byte range defined.
          segmentByteRangeOffset = 0;
        } else if (isIFrameOnly && initializationSegment == null && inferredInitSegment == null) {
          // The segment is a resource byte range without an initialization segment.
          // As per RFC 8216, Section 4.3.3.6, we assume the initialization section exists in the
          // bytes preceding the first segment in this segment's URL.
          // We assume the implicit initialization segment is unencrypted, since there's no way for
          // the playlist to provide an initialization vector for it.
          inferredInitSegment =
              new Segment(
                  segmentUri,
                  /* byteRangeOffset= */ 0,
                  segmentByteRangeOffset,
                  /* fullSegmentEncryptionKeyUri= */ null,
                  /* encryptionIV= */ null);
          urlToInferredInitSegment.put(segmentUri, inferredInitSegment);
        }

        if (cachedDrmInitData == null && !currentSchemeDatas.isEmpty()) {
          SchemeData[] schemeDatas = currentSchemeDatas.values().toArray(new SchemeData[0]);
          cachedDrmInitData = new DrmInitData(encryptionScheme, schemeDatas);
          if (playlistProtectionSchemes == null) {
            playlistProtectionSchemes = getPlaylistProtectionSchemes(encryptionScheme, schemeDatas);
          }
        }

        segments.add(
            new Segment(
                segmentUri,
                initializationSegment != null ? initializationSegment : inferredInitSegment,
                segmentTitle,
                segmentDurationUs,
                relativeDiscontinuitySequence,
                segmentStartTimeUs,
                cachedDrmInitData,
                fullSegmentEncryptionKeyUri,
                segmentEncryptionIV,
                segmentByteRangeOffset,
                segmentByteRangeLength,
                hasGapTag,
                trailingParts));
        segmentStartTimeUs += segmentDurationUs;
        partStartTimeUs = segmentStartTimeUs;
        segmentDurationUs = 0;
        segmentTitle = "";
        trailingParts = new ArrayList<>();
        if (segmentByteRangeLength != C.LENGTH_UNSET) {
          segmentByteRangeOffset += segmentByteRangeLength;
        }
        segmentByteRangeLength = C.LENGTH_UNSET;
        hasGapTag = false;
      }
    }

    Map<Uri, RenditionReport> renditionReportMap = new HashMap<>();
    for (int i = 0; i < renditionReports.size(); i++) {
      RenditionReport renditionReport = renditionReports.get(i);
      long lastMediaSequence = renditionReport.lastMediaSequence;
      if (lastMediaSequence == C.INDEX_UNSET) {
        lastMediaSequence = mediaSequence + segments.size() - (trailingParts.isEmpty() ? 1 : 0);
      }
      int lastPartIndex = renditionReport.lastPartIndex;
      if (lastPartIndex == C.INDEX_UNSET && partTargetDurationUs != C.TIME_UNSET) {
        List<Part> lastParts =
            trailingParts.isEmpty() ? Iterables.getLast(segments).parts : trailingParts;
        lastPartIndex = lastParts.size() - 1;
      }
      renditionReportMap.put(
          renditionReport.playlistUri,
          new RenditionReport(renditionReport.playlistUri, lastMediaSequence, lastPartIndex));
    }

    if (preloadPart != null) {
      trailingParts.add(preloadPart);
    }

    List<Interstitial> interstitials = new ArrayList<>();
    for (Interstitial.Builder interstitialBuilder : interstitialBuilderMap.values()) {
      Interstitial interstitial = interstitialBuilder.build();
      if (interstitial != null) {
        interstitials.add(interstitial);
      }
    }

    return new HlsMediaPlaylist(
        playlistType,
        baseUri,
        tags,
        startOffsetUs,
        preciseStart,
        playlistStartTimeUs,
        hasDiscontinuitySequence,
        playlistDiscontinuitySequence,
        mediaSequence,
        version,
        targetDurationUs,
        partTargetDurationUs,
        hasIndependentSegmentsTag,
        hasEndTag,
        /* hasProgramDateTime= */ playlistStartTimeUs != 0,
        playlistProtectionSchemes,
        segments,
        trailingParts,
        serverControl,
        renditionReportMap,
        interstitials);
  }

  private static DrmInitData getPlaylistProtectionSchemes(
      @Nullable String encryptionScheme, SchemeData[] schemeDatas) {
    SchemeData[] playlistSchemeDatas = new SchemeData[schemeDatas.length];
    for (int i = 0; i < schemeDatas.length; i++) {
      playlistSchemeDatas[i] = schemeDatas[i].copyWithData(null);
    }
    return new DrmInitData(encryptionScheme, playlistSchemeDatas);
  }

  @Nullable
  private static String getSegmentEncryptionIV(
      long segmentMediaSequence,
      @Nullable String fullSegmentEncryptionKeyUri,
      @Nullable String fullSegmentEncryptionIV) {
    if (fullSegmentEncryptionKeyUri == null) {
      return null;
    } else if (fullSegmentEncryptionIV != null) {
      return fullSegmentEncryptionIV;
    }
    return Long.toHexString(segmentMediaSequence);
  }

  private static @C.SelectionFlags int parseSelectionFlags(String line) {
    int flags = 0;
    if (parseOptionalBooleanAttribute(line, REGEX_DEFAULT, false)) {
      flags |= C.SELECTION_FLAG_DEFAULT;
    }
    if (parseOptionalBooleanAttribute(line, REGEX_FORCED, false)) {
      flags |= C.SELECTION_FLAG_FORCED;
    }
    if (parseOptionalBooleanAttribute(line, REGEX_AUTOSELECT, false)) {
      flags |= C.SELECTION_FLAG_AUTOSELECT;
    }
    return flags;
  }

  private static @C.RoleFlags int parseRoleFlags(
      String line, Map<String, String> variableDefinitions) {
    String concatenatedCharacteristics =
        parseOptionalStringAttr(line, REGEX_CHARACTERISTICS, variableDefinitions);
    if (TextUtils.isEmpty(concatenatedCharacteristics)) {
      return 0;
    }
    String[] characteristics = Util.split(concatenatedCharacteristics, ",");
    @C.RoleFlags int roleFlags = 0;
    if (Util.contains(characteristics, "public.accessibility.describes-video")) {
      roleFlags |= C.ROLE_FLAG_DESCRIBES_VIDEO;
    }
    if (Util.contains(characteristics, "public.accessibility.transcribes-spoken-dialog")) {
      roleFlags |= C.ROLE_FLAG_TRANSCRIBES_DIALOG;
    }
    if (Util.contains(characteristics, "public.accessibility.describes-music-and-sound")) {
      roleFlags |= C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND;
    }
    if (Util.contains(characteristics, "public.easy-to-read")) {
      roleFlags |= C.ROLE_FLAG_EASY_TO_READ;
    }
    return roleFlags;
  }

  @Nullable
  private static SchemeData parseDrmSchemeData(
      String line, String keyFormat, Map<String, String> variableDefinitions)
      throws ParserException {
    String keyFormatVersions =
        parseOptionalStringAttr(line, REGEX_KEYFORMATVERSIONS, "1", variableDefinitions);
    if (KEYFORMAT_WIDEVINE_PSSH_BINARY.equals(keyFormat)) {
      String uriString = parseStringAttr(line, REGEX_URI, variableDefinitions);
      return new SchemeData(
          C.WIDEVINE_UUID,
          MimeTypes.VIDEO_MP4,
          Base64.decode(uriString.substring(uriString.indexOf(',')), Base64.DEFAULT));
    } else if (KEYFORMAT_WIDEVINE_PSSH_JSON.equals(keyFormat)) {
      return new SchemeData(C.WIDEVINE_UUID, "hls", Util.getUtf8Bytes(line));
    } else if (KEYFORMAT_PLAYREADY.equals(keyFormat) && "1".equals(keyFormatVersions)) {
      String uriString = parseStringAttr(line, REGEX_URI, variableDefinitions);
      byte[] data = Base64.decode(uriString.substring(uriString.indexOf(',')), Base64.DEFAULT);
      byte[] psshData = PsshAtomUtil.buildPsshAtom(C.PLAYREADY_UUID, data);
      return new SchemeData(C.PLAYREADY_UUID, MimeTypes.VIDEO_MP4, psshData);
    }
    return null;
  }

  private static HlsMediaPlaylist.ServerControl parseServerControl(String line) {
    double skipUntilSeconds =
        parseOptionalDoubleAttr(line, REGEX_CAN_SKIP_UNTIL, /* defaultValue= */ C.TIME_UNSET);
    long skipUntilUs =
        skipUntilSeconds == C.TIME_UNSET
            ? C.TIME_UNSET
            : (long) (skipUntilSeconds * C.MICROS_PER_SECOND);
    boolean canSkipDateRanges =
        parseOptionalBooleanAttribute(line, REGEX_CAN_SKIP_DATE_RANGES, /* defaultValue= */ false);
    double holdBackSeconds =
        parseOptionalDoubleAttr(line, REGEX_HOLD_BACK, /* defaultValue= */ C.TIME_UNSET);
    long holdBackUs =
        holdBackSeconds == C.TIME_UNSET
            ? C.TIME_UNSET
            : (long) (holdBackSeconds * C.MICROS_PER_SECOND);
    double partHoldBackSeconds = parseOptionalDoubleAttr(line, REGEX_PART_HOLD_BACK, C.TIME_UNSET);
    long partHoldBackUs =
        partHoldBackSeconds == C.TIME_UNSET
            ? C.TIME_UNSET
            : (long) (partHoldBackSeconds * C.MICROS_PER_SECOND);
    boolean canBlockReload =
        parseOptionalBooleanAttribute(line, REGEX_CAN_BLOCK_RELOAD, /* defaultValue= */ false);

    return new HlsMediaPlaylist.ServerControl(
        skipUntilUs, canSkipDateRanges, holdBackUs, partHoldBackUs, canBlockReload);
  }

  private static String parseEncryptionScheme(String method) {
    return METHOD_SAMPLE_AES_CENC.equals(method) || METHOD_SAMPLE_AES_CTR.equals(method)
        ? C.CENC_TYPE_cenc
        : C.CENC_TYPE_cbcs;
  }

  private static int parseIntAttr(String line, Pattern pattern) throws ParserException {
    return Integer.parseInt(parseStringAttr(line, pattern, Collections.emptyMap()));
  }

  private static int parseOptionalIntAttr(String line, Pattern pattern, int defaultValue) {
    Matcher matcher = pattern.matcher(line);
    if (matcher.find()) {
      return Integer.parseInt(checkNotNull(matcher.group(1)));
    }
    return defaultValue;
  }

  private static long parseLongAttr(String line, Pattern pattern) throws ParserException {
    return Long.parseLong(parseStringAttr(line, pattern, Collections.emptyMap()));
  }

  private static long parseOptionalLongAttr(String line, Pattern pattern, long defaultValue) {
    Matcher matcher = pattern.matcher(line);
    if (matcher.find()) {
      return Long.parseLong(checkNotNull(matcher.group(1)));
    }
    return defaultValue;
  }

  private static long parseTimeSecondsToUs(String line, Pattern pattern) throws ParserException {
    String timeValueSeconds = parseStringAttr(line, pattern, Collections.emptyMap());
    BigDecimal timeValue = new BigDecimal(timeValueSeconds);
    return timeValue.multiply(new BigDecimal(C.MICROS_PER_SECOND)).longValue();
  }

  private static double parseDoubleAttr(String line, Pattern pattern) throws ParserException {
    return Double.parseDouble(parseStringAttr(line, pattern, Collections.emptyMap()));
  }

  private static String parseStringAttr(
      String line, Pattern pattern, Map<String, String> variableDefinitions)
      throws ParserException {
    String value = parseOptionalStringAttr(line, pattern, variableDefinitions);
    if (value != null) {
      return value;
    } else {
      throw ParserException.createForMalformedManifest(
          "Couldn't match " + pattern.pattern() + " in " + line, /* cause= */ null);
    }
  }

  @Nullable
  private static String parseOptionalStringAttr(
      String line, Pattern pattern, Map<String, String> variableDefinitions) {
    return parseOptionalStringAttr(line, pattern, null, variableDefinitions);
  }

  private static @PolyNull String parseOptionalStringAttr(
      String line,
      Pattern pattern,
      @PolyNull String defaultValue,
      Map<String, String> variableDefinitions) {
    Matcher matcher = pattern.matcher(line);
    @PolyNull String value = matcher.find() ? checkNotNull(matcher.group(1)) : defaultValue;
    return variableDefinitions.isEmpty() || value == null
        ? value
        : replaceVariableReferences(value, variableDefinitions);
  }

  private static double parseOptionalDoubleAttr(String line, Pattern pattern, double defaultValue) {
    Matcher matcher = pattern.matcher(line);
    if (matcher.find()) {
      return Double.parseDouble(checkNotNull(matcher.group(1)));
    }
    return defaultValue;
  }

  private static HlsMediaPlaylist.ClientDefinedAttribute parseClientDefinedAttribute(
      String attributes, String clientAttribute, Map<String, String> variableDefinitions)
      throws ParserException {
    String prefix = clientAttribute + "=";
    int index = attributes.indexOf(prefix);
    int valueBeginIndex = index + prefix.length();
    int valueBeginMaxLength = attributes.length() == valueBeginIndex + 1 ? 1 : 2;
    String valueBegin =
        attributes.substring(valueBeginIndex, valueBeginIndex + valueBeginMaxLength);
    if (valueBegin.startsWith("\"")) {
      // a quoted string value
      Pattern pattern = Pattern.compile(clientAttribute + "=" + ATTR_QUOTED_STRING_VALUE_PATTERN);
      String value = parseStringAttr(attributes, pattern, variableDefinitions);
      return new HlsMediaPlaylist.ClientDefinedAttribute(
          clientAttribute, value, HlsMediaPlaylist.ClientDefinedAttribute.TYPE_TEXT);
    } else if (valueBegin.equals("0x") || valueBegin.equals("0X")) {
      // a hexadecimal sequence value
      Pattern pattern = Pattern.compile(clientAttribute + "=(0[xX][A-F0-9]+)");
      String value = parseStringAttr(attributes, pattern, variableDefinitions);
      return new HlsMediaPlaylist.ClientDefinedAttribute(
          clientAttribute, value, HlsMediaPlaylist.ClientDefinedAttribute.TYPE_HEX_TEXT);
    } else {
      // a decimal-floating-point value
      Pattern pattern = Pattern.compile(clientAttribute + "=([\\d\\.]+)\\b");
      return new HlsMediaPlaylist.ClientDefinedAttribute(
          clientAttribute, parseDoubleAttr(attributes, pattern));
    }
  }

  private static String replaceVariableReferences(
      String string, Map<String, String> variableDefinitions) {
    Matcher matcher = REGEX_VARIABLE_REFERENCE.matcher(string);
    // TODO: Replace StringBuffer with StringBuilder once Java 9 is available.
    StringBuffer stringWithReplacements = new StringBuffer();
    while (matcher.find()) {
      String groupName = matcher.group(1);
      if (variableDefinitions.containsKey(groupName)) {
        matcher.appendReplacement(
            stringWithReplacements, Matcher.quoteReplacement(variableDefinitions.get(groupName)));
      } else {
        // The variable is not defined. The value is ignored.
      }
    }
    matcher.appendTail(stringWithReplacements);
    return stringWithReplacements.toString();
  }

  private static boolean parseOptionalBooleanAttribute(
      String line, Pattern pattern, boolean defaultValue) {
    Matcher matcher = pattern.matcher(line);
    if (matcher.find()) {
      return BOOLEAN_TRUE.equals(matcher.group(1));
    }
    return defaultValue;
  }

  private static Pattern compileBooleanAttrPattern(String attribute) {
    return Pattern.compile(attribute + "=(" + BOOLEAN_FALSE + "|" + BOOLEAN_TRUE + ")");
  }

  private static class LineIterator {

    private final BufferedReader reader;
    private final Queue<String> extraLines;

    @Nullable private String next;

    public LineIterator(Queue<String> extraLines, BufferedReader reader) {
      this.extraLines = extraLines;
      this.reader = reader;
    }

    @EnsuresNonNullIf(expression = "next", result = true)
    public boolean hasNext() throws IOException {
      if (next != null) {
        return true;
      }
      if (!extraLines.isEmpty()) {
        next = checkNotNull(extraLines.poll());
        return true;
      }
      while ((next = reader.readLine()) != null) {
        next = next.trim();
        if (!next.isEmpty()) {
          return true;
        }
      }
      return false;
    }

    /** Return the next line, or throw {@link NoSuchElementException} if none. */
    public String next() throws IOException {
      if (hasNext()) {
        String result = next;
        next = null;
        return result;
      } else {
        throw new NoSuchElementException();
      }
    }
  }
}
