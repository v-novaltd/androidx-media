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
package androidx.media3.common.util;

import static androidx.media3.common.util.Assertions.checkArgument;

import android.annotation.SuppressLint;
import android.media.MediaCodecInfo;
import android.util.Pair;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.ColorInfo;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Provides utilities for handling various types of codec-specific data. */
@SuppressLint("InlinedApi")
@UnstableApi
public final class CodecSpecificDataUtil {

  private static final byte[] NAL_START_CODE = new byte[] {0, 0, 0, 1};
  private static final String[] HEVC_GENERAL_PROFILE_SPACE_STRINGS =
      new String[] {"", "A", "B", "C"};

  // MP4V-ES
  private static final int VISUAL_OBJECT_LAYER = 1;
  private static final int VISUAL_OBJECT_LAYER_START = 0x20;
  private static final int EXTENDED_PAR = 0x0F;
  private static final int RECTANGULAR = 0x00;

  // Codecs to constant mappings.
  // H263
  private static final String CODEC_ID_H263 = "s263";
  // AVC.
  private static final String CODEC_ID_AVC1 = "avc1";
  private static final String CODEC_ID_AVC2 = "avc2";
  // VP9
  private static final String CODEC_ID_VP09 = "vp09";
  // HEVC.
  private static final String CODEC_ID_HEV1 = "hev1";
  private static final String CODEC_ID_HVC1 = "hvc1";
  // AV1.
  private static final String CODEC_ID_AV01 = "av01";
  // APV.
  private static final String CODEC_ID_APV1 = "apv1";
  // MP4A AAC.
  private static final String CODEC_ID_MP4A = "mp4a";
  // AC-4
  private static final String CODEC_ID_AC4 = "ac-4";
  // IAMF
  private static final String CODEC_ID_IAMF = "iamf";

  private static final Pattern PROFILE_PATTERN = Pattern.compile("^\\D?(\\d+)$");

  private static final String TAG = "CodecSpecificDataUtil";

  /**
   * Parses an ALAC AudioSpecificConfig (i.e. an <a
   * href="https://github.com/macosforge/alac/blob/master/ALACMagicCookieDescription.txt">ALACSpecificConfig</a>).
   *
   * @param audioSpecificConfig A byte array containing the AudioSpecificConfig to parse.
   * @return A pair consisting of the sample rate in Hz and the channel count.
   */
  public static Pair<Integer, Integer> parseAlacAudioSpecificConfig(byte[] audioSpecificConfig) {
    ParsableByteArray byteArray = new ParsableByteArray(audioSpecificConfig);
    byteArray.setPosition(9);
    int channelCount = byteArray.readUnsignedByte();
    byteArray.setPosition(20);
    int sampleRate = byteArray.readUnsignedIntToInt();
    return Pair.create(sampleRate, channelCount);
  }

  /**
   * Returns initialization data for formats with MIME type {@link MimeTypes#APPLICATION_CEA708}.
   *
   * @param isWideAspectRatio Whether the CEA-708 closed caption service is formatted for displays
   *     with 16:9 aspect ratio.
   * @return Initialization data for formats with MIME type {@link MimeTypes#APPLICATION_CEA708}.
   */
  public static List<byte[]> buildCea708InitializationData(boolean isWideAspectRatio) {
    return Collections.singletonList(isWideAspectRatio ? new byte[] {1} : new byte[] {0});
  }

  /**
   * Builds an RFC 6381 IAMF codec string from the given {@code initializationData}.
   *
   * <p>The format is defined by the <a
   * href="https://aomediacodec.github.io/iamf/#codecsparameter">IAMF Codec Parameters String</a>
   * specification.
   */
  @Nullable
  public static String buildIamfCodecString(byte[] initializationData) {
    ParsableByteArray parsableByteArray = new ParsableByteArray(initializationData);
    if (!skipObuHeader(parsableByteArray, /* expectedObuType= */ 0x1F)) { // OBU_IA_Sequence_Header
      return null;
    }
    // IASequenceHeaderOBU
    parsableByteArray.skipBytes(4); // ia_code (4 bytes)
    int primaryProfile = parsableByteArray.readUnsignedByte(); // primary_profile (1 byte)
    int additionalProfile = parsableByteArray.readUnsignedByte(); // additional_profile (1 byte)

    if (!skipObuHeader(parsableByteArray, /* expectedObuType= */ 0x00)) { // OBU_IA_Codec_Config
      return null;
    }

    // CodecConfigOBU
    parsableByteArray.skipLeb128(); // codec_config_id
    String codecId = parsableByteArray.readString(4); // codec_id (4 bytes)

    if (codecId.equals("mp4a")) {
      parsableByteArray.skipLeb128(); // num_samples_per_frame
      parsableByteArray.skipBytes(2); // audio_roll_distance (2 bytes)

      ParsableBitArray decoderConfigBitArray = new ParsableBitArray();
      decoderConfigBitArray.reset(parsableByteArray);
      int audioObjectType = decoderConfigBitArray.readBits(5);
      // If audioObjectType is an escape value, then we need to read 6 bits.
      if (audioObjectType == 0x1F) {
        audioObjectType = 32 + decoderConfigBitArray.readBits(6);
      }
      codecId += ".40." + audioObjectType;
    }
    return Util.formatInvariant("iamf.%03X.%03X.%s", primaryProfile, additionalProfile, codecId);
  }

  private static boolean skipObuHeader(ParsableByteArray parsableByteArray, int expectedObuType) {
    // OBUHeader
    // obu_type (5 bits) + obu_redundant_copy (1 bit) + obu_trimming_status_flag  (1 bit) +
    // obu_extension_flag (1 bit)
    int obuHeaderByte = parsableByteArray.readUnsignedByte();
    if (obuHeaderByte >> 3 != expectedObuType) {
      return false;
    }
    parsableByteArray.skipLeb128(); // obu_size
    return true;
  }

  /**
   * Returns whether the CEA-708 closed caption service with the given initialization data is
   * formatted for displays with 16:9 aspect ratio.
   *
   * @param initializationData The initialization data to parse.
   * @return Whether the CEA-708 closed caption service is formatted for displays with 16:9 aspect
   *     ratio.
   */
  public static boolean parseCea708InitializationData(List<byte[]> initializationData) {
    return initializationData.size() == 1
        && initializationData.get(0).length == 1
        && initializationData.get(0)[0] == 1;
  }

  /**
   * Returns initialization data in CodecPrivate format of VP9.
   *
   * <p>Each feature of VP9 CodecPrivate is defined by the binary format of ID (1 byte), length (1
   * byte), and data (1 byte). See <a>
   * href="https://www.webmproject.org/docs/container/#vp9-codec-feature-metadata-codecprivate">CodecPrivate
   * format of VP9</a> for more details.
   *
   * @param profile The VP9 codec profile.
   * @param level The VP9 codec level.
   * @param bitDepth The bit depth of the luma and color components.
   * @param chromaSubsampling The chroma subsampling.
   */
  public static ImmutableList<byte[]> buildVp9CodecPrivateInitializationData(
      byte profile, byte level, byte bitDepth, byte chromaSubsampling) {
    byte profileId = 0x01;
    byte levelId = 0x02;
    byte bitDepthId = 0x03;
    byte chromaSubsamplingId = 0x04;
    byte length = 0x01;
    return ImmutableList.of(
        new byte[] {
          profileId, length, profile,
          levelId, length, level,
          bitDepthId, length, bitDepth,
          chromaSubsamplingId, length, chromaSubsampling
        });
  }

  /**
   * Returns initialization data for Dolby Vision according to <a
   * href="https://dolby.my.salesforce.com/sfc/p/#700000009YuG/a/4u000000l6FB/076wHYEmyEfz09m0V1bo85_25hlUJjaiWTbzorNmYY4">Dolby
   * Vision ISO MediaFormat (section 2.2) specification</a>.
   *
   * @param profile The Dolby Vision codec profile.
   * @param level The Dolby Vision codec level.
   */
  public static byte[] buildDolbyVisionInitializationData(int profile, int level) {
    byte[] dolbyVisionCsd = new byte[24];
    byte blCompatibilityId = 0x00;
    // MD compression is not permitted for profile 7 and earlier. Only some devices
    // support it from profile 8
    byte mdCompression = 0x00;
    if (profile == 8) {
      blCompatibilityId = 0x04;
    } else if (profile == 9) {
      blCompatibilityId = 0x02;
      mdCompression = 0x01;
    }

    dolbyVisionCsd[0] = 0x01; // dv_version_major
    dolbyVisionCsd[1] = 0x00; // dv_version_minor
    dolbyVisionCsd[2] = (byte) ((profile & 0x7f) << 1); // dv_profile
    dolbyVisionCsd[2] = (byte) ((dolbyVisionCsd[2] | ((level >> 5) & 0x1)) & 0xff);
    dolbyVisionCsd[3] = (byte) ((level & 0x1f) << 3); // dv_level
    dolbyVisionCsd[3] = (byte) (dolbyVisionCsd[3] | (1 << 2)); // rpu_present_flag
    dolbyVisionCsd[3] = (byte) (dolbyVisionCsd[3] | (0 << 1)); // el_present_flag
    dolbyVisionCsd[3] = (byte) (dolbyVisionCsd[3] | 1); // bl_present_flag
    dolbyVisionCsd[4] = (byte) (blCompatibilityId << 4); // dv_bl_signal_compatibility_id
    dolbyVisionCsd[4] = (byte) (dolbyVisionCsd[4] | (mdCompression << 2)); // dv_md_compression
    return dolbyVisionCsd;
  }

  /**
   * Parses an MPEG-4 Visual configuration information, as defined in ISO/IEC14496-2.
   *
   * @param videoSpecificConfig A byte array containing the MPEG-4 Visual configuration information
   *     to parse.
   * @return A pair of the video's width and height.
   */
  public static Pair<Integer, Integer> getVideoResolutionFromMpeg4VideoConfig(
      byte[] videoSpecificConfig) {
    int offset = 0;
    boolean foundVOL = false;
    ParsableByteArray scratchBytes = new ParsableByteArray(videoSpecificConfig);
    while (offset + 3 < videoSpecificConfig.length) {
      if (scratchBytes.readUnsignedInt24() != VISUAL_OBJECT_LAYER
          || (videoSpecificConfig[offset + 3] & 0xF0) != VISUAL_OBJECT_LAYER_START) {
        scratchBytes.setPosition(scratchBytes.getPosition() - 2);
        offset++;
        continue;
      }
      foundVOL = true;
      break;
    }

    checkArgument(foundVOL, "Invalid input: VOL not found.");

    ParsableBitArray scratchBits = new ParsableBitArray(videoSpecificConfig);
    // Skip the start codecs from the bitstream
    scratchBits.skipBits((offset + 4) * 8);
    scratchBits.skipBits(1); // random_accessible_vol
    scratchBits.skipBits(8); // video_object_type_indication

    if (scratchBits.readBit()) { // object_layer_identifier
      scratchBits.skipBits(4); // video_object_layer_verid
      scratchBits.skipBits(3); // video_object_layer_priority
    }

    int aspectRatioInfo = scratchBits.readBits(4);
    if (aspectRatioInfo == EXTENDED_PAR) {
      scratchBits.skipBits(8); // par_width
      scratchBits.skipBits(8); // par_height
    }

    if (scratchBits.readBit()) { // vol_control_parameters
      scratchBits.skipBits(2); // chroma_format
      scratchBits.skipBits(1); // low_delay
      if (scratchBits.readBit()) { // vbv_parameters
        scratchBits.skipBits(79);
      }
    }

    int videoObjectLayerShape = scratchBits.readBits(2);
    checkArgument(
        videoObjectLayerShape == RECTANGULAR,
        "Only supports rectangular video object layer shape.");

    checkArgument(scratchBits.readBit()); // marker_bit
    int vopTimeIncrementResolution = scratchBits.readBits(16);
    checkArgument(scratchBits.readBit()); // marker_bit

    if (scratchBits.readBit()) { // fixed_vop_rate
      checkArgument(vopTimeIncrementResolution > 0);
      vopTimeIncrementResolution--;
      int numBitsToSkip = 0;
      while (vopTimeIncrementResolution > 0) {
        numBitsToSkip++;
        vopTimeIncrementResolution >>= 1;
      }
      scratchBits.skipBits(numBitsToSkip); // fixed_vop_time_increment
    }

    checkArgument(scratchBits.readBit()); // marker_bit
    int videoObjectLayerWidth = scratchBits.readBits(13);
    checkArgument(scratchBits.readBit()); // marker_bit
    int videoObjectLayerHeight = scratchBits.readBits(13);
    checkArgument(scratchBits.readBit()); // marker_bit

    scratchBits.skipBits(1); // interlaced

    return Pair.create(videoObjectLayerWidth, videoObjectLayerHeight);
  }

  /**
   * Builds an RFC 6381 AVC codec string using the provided parameters.
   *
   * @param profileIdc The encoding profile.
   * @param constraintsFlagsAndReservedZero2Bits The constraint flags followed by the reserved zero
   *     2 bits, all contained in the least significant byte of the integer.
   * @param levelIdc The encoding level.
   * @return An RFC 6381 AVC codec string built using the provided parameters.
   */
  public static String buildAvcCodecString(
      int profileIdc, int constraintsFlagsAndReservedZero2Bits, int levelIdc) {
    return String.format(
        "avc1.%02X%02X%02X", profileIdc, constraintsFlagsAndReservedZero2Bits, levelIdc);
  }

  /** Builds an RFC 6381 HEVC codec string using the provided parameters. */
  public static String buildHevcCodecString(
      int generalProfileSpace,
      boolean generalTierFlag,
      int generalProfileIdc,
      int generalProfileCompatibilityFlags,
      int[] constraintBytes,
      int generalLevelIdc) {
    StringBuilder builder =
        new StringBuilder(
            Util.formatInvariant(
                "hvc1.%s%d.%X.%c%d",
                HEVC_GENERAL_PROFILE_SPACE_STRINGS[generalProfileSpace],
                generalProfileIdc,
                generalProfileCompatibilityFlags,
                generalTierFlag ? 'H' : 'L',
                generalLevelIdc));
    // Omit trailing zero bytes.
    int trailingZeroIndex = constraintBytes.length;
    while (trailingZeroIndex > 0 && constraintBytes[trailingZeroIndex - 1] == 0) {
      trailingZeroIndex--;
    }
    for (int i = 0; i < trailingZeroIndex; i++) {
      builder.append(String.format(".%02X", constraintBytes[i]));
    }
    return builder.toString();
  }

  /**
   * Returns an APV codec string based on the provided initialization data.
   *
   * <p>Reference: <a
   * href="https://github.com/AcademySoftwareFoundation/openapv/blob/main/readme/apv_isobmff.md#sub-parameters-for-the-mime-tyype-codecs-parameter">
   * Codecs Parameter in APV ISO Base Media File Format</a>
   *
   * @param initializationData The initialization data containing profile, level, and band
   *     information.
   * @return The generated APV codec string.
   */
  public static String buildApvCodecString(byte[] initializationData) {
    checkArgument(
        initializationData.length >= 17, "Invalid APV CSD length: %s" + initializationData.length);
    checkArgument(
        initializationData[0] == 0x01,
        "Invalid APV CSD version: %s" + initializationData[0]); // configurationVersion == 1

    int profile = initializationData[5];
    int level = initializationData[6];
    int band = initializationData[7];
    return Util.formatInvariant("apv1.apvf%d.apvl%d.apvb%d", profile, level, band);
  }

  /** Builds an RFC 6381 H263 codec string using profile and level. */
  public static String buildH263CodecString(int profile, int level) {
    return Util.formatInvariant("s263.%d.%d", profile, level);
  }

  /**
   * Builds a Dolby Vision codec string using profile and level.
   *
   * <p>Reference: <a>
   * href="https://professionalsupport.dolby.com/s/article/What-is-Dolby-Vision-Profile?language=en_US">
   * Dolby Vision Profile and Level (section 2.3)</a>
   */
  public static String buildDolbyVisionCodecString(int profile, int level) {
    if (profile > 9) {
      return Util.formatInvariant("dvh1.%02d.%02d", profile, level);
    } else if (profile > 8) {
      return Util.formatInvariant("dvav.%02d.%02d", profile, level);
    } else {
      return Util.formatInvariant("dvhe.%02d.%02d", profile, level);
    }
  }

  /**
   * Returns profile and level (as defined by {@link MediaCodecInfo.CodecProfileLevel})
   * corresponding to the codec description string (as defined by RFC 6381) of the given format.
   *
   * @param format Media format with a codec description string, as defined by RFC 6381.
   * @return A pair (profile constant, level constant) if the codec of the {@code format} is
   *     well-formed and recognized, or null otherwise.
   */
  @Nullable
  public static Pair<Integer, Integer> getCodecProfileAndLevel(Format format) {
    if (format.codecs == null) {
      return null;
    }
    String[] parts = format.codecs.split("\\.");
    // Dolby Vision can use DV, AVC or HEVC codec IDs, so check the MIME type first.
    if (MimeTypes.VIDEO_DOLBY_VISION.equals(format.sampleMimeType)) {
      return getDolbyVisionProfileAndLevel(format.codecs, parts);
    }
    switch (parts[0]) {
      case CODEC_ID_H263:
        return getH263ProfileAndLevel(format.codecs, parts);
      case CODEC_ID_AVC1:
      case CODEC_ID_AVC2:
        return getAvcProfileAndLevel(format.codecs, parts);
      case CODEC_ID_VP09:
        return getVp9ProfileAndLevel(format.codecs, parts);
      case CODEC_ID_HEV1:
      case CODEC_ID_HVC1:
        return getHevcProfileAndLevel(format.codecs, parts, format.colorInfo);
      case CODEC_ID_AV01:
        return getAv1ProfileAndLevel(format.codecs, parts, format.colorInfo);
      case CODEC_ID_APV1:
        return getApvProfileAndLevel(format.codecs, parts);
      case CODEC_ID_MP4A:
        return getAacCodecProfileAndLevel(format.codecs, parts);
      case CODEC_ID_AC4:
        return getAc4CodecProfileAndLevel(format.codecs, parts);
      case CODEC_ID_IAMF:
        return getIamfCodecProfileAndLevel(format.codecs, parts);
      default:
        return null;
    }
  }

  /**
   * Returns Hevc profile and level corresponding to the codec description string (as defined by RFC
   * 6381) and it {@link ColorInfo}.
   *
   * @param codec The codec description string (as defined by RFC 6381).
   * @param parts The codec string split by ".".
   * @param colorInfo The {@link ColorInfo}.
   * @return A pair (profile constant, level constant) if profile and level are recognized, or
   *     {@code null} otherwise.
   */
  @Nullable
  public static Pair<Integer, Integer> getHevcProfileAndLevel(
      String codec, String[] parts, @Nullable ColorInfo colorInfo) {
    if (parts.length < 4) {
      // The codec has fewer parts than required by the HEVC codec string format.
      Log.w(TAG, "Ignoring malformed HEVC codec string: " + codec);
      return null;
    }
    // The profile_space gets ignored.
    Matcher matcher = PROFILE_PATTERN.matcher(parts[1]);
    if (!matcher.matches()) {
      Log.w(TAG, "Ignoring malformed HEVC codec string: " + codec);
      return null;
    }
    @Nullable String profileString = matcher.group(1);
    int profile;
    if ("1".equals(profileString)) {
      profile = MediaCodecInfo.CodecProfileLevel.HEVCProfileMain;
    } else if ("2".equals(profileString)) {
      if (colorInfo != null && colorInfo.colorTransfer == C.COLOR_TRANSFER_ST2084) {
        profile = MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10;
      } else {
        // For all other cases, we map to the Main10 profile. Note that this includes HLG
        // HDR. On Android 13+, the platform guarantees that a decoder that advertises
        // HEVCProfileMain10 will be able to decode HLG. This is not guaranteed for older
        // Android versions, but we still map to Main10 for backwards compatibility.
        profile = MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10;
      }
    } else if ("6".equals(profileString)) {
      // Framework does not have profileLevel.HEVCProfileMultiviewMain defined.
      profile = 6;
    } else {
      Log.w(TAG, "Unknown HEVC profile string: " + profileString);
      return null;
    }
    @Nullable String levelString = parts[3];
    @Nullable Integer level = hevcCodecStringToProfileLevel(levelString);
    if (level == null) {
      Log.w(TAG, "Unknown HEVC level string: " + levelString);
      return null;
    }
    return new Pair<>(profile, level);
  }

  /**
   * Constructs a NAL unit consisting of the NAL start code followed by the specified data.
   *
   * @param data An array containing the data that should follow the NAL start code.
   * @param offset The start offset into {@code data}.
   * @param length The number of bytes to copy from {@code data}
   * @return The constructed NAL unit.
   */
  public static byte[] buildNalUnit(byte[] data, int offset, int length) {
    byte[] nalUnit = new byte[length + NAL_START_CODE.length];
    System.arraycopy(NAL_START_CODE, 0, nalUnit, 0, NAL_START_CODE.length);
    System.arraycopy(data, offset, nalUnit, NAL_START_CODE.length, length);
    return nalUnit;
  }

  /**
   * Splits an array of NAL units.
   *
   * <p>If the input consists of NAL start code delimited units, then the returned array consists of
   * the split NAL units, each of which is still prefixed with the NAL start code. For any other
   * input, null is returned.
   *
   * @param data An array of data.
   * @return The individual NAL units, or null if the input did not consist of NAL start code
   *     delimited units.
   */
  @Nullable
  public static byte[][] splitNalUnits(byte[] data) {
    if (!isNalStartCode(data, 0)) {
      // data does not consist of NAL start code delimited units.
      return null;
    }
    List<Integer> starts = new ArrayList<>();
    int nalUnitIndex = 0;
    do {
      starts.add(nalUnitIndex);
      nalUnitIndex = findNalStartCode(data, nalUnitIndex + NAL_START_CODE.length);
    } while (nalUnitIndex != C.INDEX_UNSET);
    byte[][] split = new byte[starts.size()][];
    for (int i = 0; i < starts.size(); i++) {
      int startIndex = starts.get(i);
      int endIndex = i < starts.size() - 1 ? starts.get(i + 1) : data.length;
      byte[] nal = new byte[endIndex - startIndex];
      System.arraycopy(data, startIndex, nal, 0, nal.length);
      split[i] = nal;
    }
    return split;
  }

  /**
   * Returns Dolby Vision level number corresponding to the level constant.
   *
   * @param levelConstant The Dolby Vision level constant.
   * @return The Dolby Vision level number.
   * @throws IllegalArgumentException if the level constant is not recognized.
   */
  public static int dolbyVisionConstantToLevelNumber(int levelConstant) {
    switch (levelConstant) {
      case MediaCodecInfo.CodecProfileLevel.DolbyVisionLevelHd24:
        return 1;
      case MediaCodecInfo.CodecProfileLevel.DolbyVisionLevelHd30:
        return 2;
      case MediaCodecInfo.CodecProfileLevel.DolbyVisionLevelFhd24:
        return 3;
      case MediaCodecInfo.CodecProfileLevel.DolbyVisionLevelFhd30:
        return 4;
      case MediaCodecInfo.CodecProfileLevel.DolbyVisionLevelFhd60:
        return 5;
      case MediaCodecInfo.CodecProfileLevel.DolbyVisionLevelUhd24:
        return 6;
      case MediaCodecInfo.CodecProfileLevel.DolbyVisionLevelUhd30:
        return 7;
      case MediaCodecInfo.CodecProfileLevel.DolbyVisionLevelUhd48:
        return 8;
      case MediaCodecInfo.CodecProfileLevel.DolbyVisionLevelUhd60:
        return 9;
      case MediaCodecInfo.CodecProfileLevel.DolbyVisionLevelUhd120:
        return 10;
      case MediaCodecInfo.CodecProfileLevel.DolbyVisionLevel8k30:
        return 11;
      case MediaCodecInfo.CodecProfileLevel.DolbyVisionLevel8k60:
        return 12;
      // TODO: b/179261323 - use framework constant for level 13.
      case 0x1000:
        return 13;
      default:
        throw new IllegalArgumentException("Unknown Dolby Vision level: " + levelConstant);
    }
  }

  /**
   * Returns Dolby Vision profile number corresponding to the profile constant.
   *
   * @param profileConstant The Dolby Vision profile constant.
   * @return The Dolby Vision profile number.
   * @throws IllegalArgumentException if the profile constant is not recognized.
   */
  public static int dolbyVisionConstantToProfileNumber(int profileConstant) {
    switch (profileConstant) {
      case MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvavPer:
        return 0;
      case MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvavPen:
        return 1;
      case MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvheDer:
        return 2;
      case MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvheDen:
        return 3;
      case MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvheDtr:
        return 4;
      case MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvheStn:
        return 5;
      case MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvheDth:
        return 6;
      case MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvheDtb:
        return 7;
      case MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvheSt:
        return 8;
      case MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvavSe:
        return 9;
      case MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvav110:
        return 10;
      default:
        throw new IllegalArgumentException("Unknown Dolby Vision profile: " + profileConstant);
    }
  }

  /**
   * Finds the next occurrence of the NAL start code from a given index.
   *
   * @param data The data in which to search.
   * @param index The first index to test.
   * @return The index of the first byte of the found start code, or {@link C#INDEX_UNSET}.
   */
  private static int findNalStartCode(byte[] data, int index) {
    int endIndex = data.length - NAL_START_CODE.length;
    for (int i = index; i <= endIndex; i++) {
      if (isNalStartCode(data, i)) {
        return i;
      }
    }
    return C.INDEX_UNSET;
  }

  /**
   * Tests whether there exists a NAL start code at a given index.
   *
   * @param data The data.
   * @param index The index to test.
   * @return Whether there exists a start code that begins at {@code index}.
   */
  private static boolean isNalStartCode(byte[] data, int index) {
    if (data.length - index <= NAL_START_CODE.length) {
      return false;
    }
    for (int j = 0; j < NAL_START_CODE.length; j++) {
      if (data[index + j] != NAL_START_CODE[j]) {
        return false;
      }
    }
    return true;
  }

  @Nullable
  private static Pair<Integer, Integer> getDolbyVisionProfileAndLevel(
      String codec, String[] parts) {
    if (parts.length < 3) {
      // The codec has fewer parts than required by the Dolby Vision codec string format.
      Log.w(TAG, "Ignoring malformed Dolby Vision codec string: " + codec);
      return null;
    }
    // The profile_space gets ignored.
    Matcher matcher = PROFILE_PATTERN.matcher(parts[1]);
    if (!matcher.matches()) {
      Log.w(TAG, "Ignoring malformed Dolby Vision codec string: " + codec);
      return null;
    }
    @Nullable String profileString = matcher.group(1);
    @Nullable Integer profile = dolbyVisionStringToProfile(profileString);
    if (profile == null) {
      Log.w(TAG, "Unknown Dolby Vision profile string: " + profileString);
      return null;
    }
    String levelString = parts[2];
    @Nullable Integer level = dolbyVisionStringToLevel(levelString);
    if (level == null) {
      Log.w(TAG, "Unknown Dolby Vision level string: " + levelString);
      return null;
    }
    return new Pair<>(profile, level);
  }

  /** Returns H263 profile and level from codec string. */
  private static Pair<Integer, Integer> getH263ProfileAndLevel(String codec, String[] parts) {
    Pair<Integer, Integer> defaultProfileAndLevel =
        new Pair<>(
            MediaCodecInfo.CodecProfileLevel.H263ProfileBaseline,
            MediaCodecInfo.CodecProfileLevel.H263Level10);
    if (parts.length < 3) {
      Log.w(TAG, "Ignoring malformed H263 codec string: " + codec);
      return defaultProfileAndLevel;
    }

    try {
      int profile = Integer.parseInt(parts[1]);
      int level = Integer.parseInt(parts[2]);
      return new Pair<>(profile, level);
    } catch (NumberFormatException e) {
      Log.w(TAG, "Ignoring malformed H263 codec string: " + codec);
      return defaultProfileAndLevel;
    }
  }

  @Nullable
  private static Pair<Integer, Integer> getAvcProfileAndLevel(String codec, String[] parts) {
    if (parts.length < 2) {
      // The codec has fewer parts than required by the AVC codec string format.
      Log.w(TAG, "Ignoring malformed AVC codec string: " + codec);
      return null;
    }
    int profileInteger;
    int levelInteger;
    try {
      if (parts[1].length() == 6) {
        // Format: avc1.xxccyy, where xx is profile and yy level, both hexadecimal.
        profileInteger = Integer.parseInt(parts[1].substring(0, 2), 16);
        levelInteger = Integer.parseInt(parts[1].substring(4), 16);
      } else if (parts.length >= 3) {
        // Format: avc1.xx.[y]yy where xx is profile and [y]yy level, both decimal.
        profileInteger = Integer.parseInt(parts[1]);
        levelInteger = Integer.parseInt(parts[2]);
      } else {
        // We don't recognize the format.
        Log.w(TAG, "Ignoring malformed AVC codec string: " + codec);
        return null;
      }
    } catch (NumberFormatException e) {
      Log.w(TAG, "Ignoring malformed AVC codec string: " + codec);
      return null;
    }

    int profile = avcProfileNumberToConst(profileInteger);
    if (profile == -1) {
      Log.w(TAG, "Unknown AVC profile: " + profileInteger);
      return null;
    }
    int level = avcLevelNumberToConst(levelInteger);
    if (level == -1) {
      Log.w(TAG, "Unknown AVC level: " + levelInteger);
      return null;
    }
    return new Pair<>(profile, level);
  }

  @Nullable
  private static Pair<Integer, Integer> getVp9ProfileAndLevel(String codec, String[] parts) {
    if (parts.length < 3) {
      Log.w(TAG, "Ignoring malformed VP9 codec string: " + codec);
      return null;
    }
    int profileInteger;
    int levelInteger;
    try {
      profileInteger = Integer.parseInt(parts[1]);
      levelInteger = Integer.parseInt(parts[2]);
    } catch (NumberFormatException e) {
      Log.w(TAG, "Ignoring malformed VP9 codec string: " + codec);
      return null;
    }

    int profile = vp9ProfileNumberToConst(profileInteger);
    if (profile == -1) {
      Log.w(TAG, "Unknown VP9 profile: " + profileInteger);
      return null;
    }
    int level = vp9LevelNumberToConst(levelInteger);
    if (level == -1) {
      Log.w(TAG, "Unknown VP9 level: " + levelInteger);
      return null;
    }
    return new Pair<>(profile, level);
  }

  @Nullable
  private static Pair<Integer, Integer> getAv1ProfileAndLevel(
      String codec, String[] parts, @Nullable ColorInfo colorInfo) {
    if (parts.length < 4) {
      Log.w(TAG, "Ignoring malformed AV1 codec string: " + codec);
      return null;
    }
    int profileInteger;
    int levelInteger;
    int bitDepthInteger;
    try {
      profileInteger = Integer.parseInt(parts[1]);
      levelInteger = Integer.parseInt(parts[2].substring(0, 2));
      bitDepthInteger = Integer.parseInt(parts[3]);
    } catch (NumberFormatException e) {
      Log.w(TAG, "Ignoring malformed AV1 codec string: " + codec);
      return null;
    }

    if (profileInteger != 0) {
      Log.w(TAG, "Unknown AV1 profile: " + profileInteger);
      return null;
    }
    if (bitDepthInteger != 8 && bitDepthInteger != 10) {
      Log.w(TAG, "Unknown AV1 bit depth: " + bitDepthInteger);
      return null;
    }
    int profile;
    if (bitDepthInteger == 8) {
      profile = MediaCodecInfo.CodecProfileLevel.AV1ProfileMain8;
    } else if (colorInfo != null
        && (colorInfo.hdrStaticInfo != null
            || colorInfo.colorTransfer == C.COLOR_TRANSFER_HLG
            || colorInfo.colorTransfer == C.COLOR_TRANSFER_ST2084)) {
      profile = MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10HDR10;
    } else {
      profile = MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10;
    }

    int level = av1LevelNumberToConst(levelInteger);
    if (level == -1) {
      Log.w(TAG, "Unknown AV1 level: " + levelInteger);
      return null;
    }
    return new Pair<>(profile, level);
  }

  @Nullable
  private static Pair<Integer, Integer> getApvProfileAndLevel(String codec, String[] parts) {
    if (parts.length < 4) {
      Log.w(TAG, "Ignoring malformed APV codec string: " + codec);
      return null;
    }
    int profileInteger;
    int levelInteger;
    int bandInteger;
    try {
      profileInteger = Integer.parseInt(parts[1].substring(4));
      levelInteger = Integer.parseInt(parts[2].substring(4));
      bandInteger = Integer.parseInt(parts[3].substring(4));
    } catch (NumberFormatException e) {
      Log.w(TAG, "Ignoring malformed APV codec string: " + codec, e);
      return null;
    }

    int profile = 0;
    if (profileInteger == 33) {
      // TODO(b/426125651): Replace apv profile value with
      // MediaCodecInfo.CodecProfileLevel.APVProfile422_10 when compile SDK is updated to 36.
      profile = 0x01;
    } else if (profileInteger == 44) {
      // TODO(b/426125651): Replace apv profile value with
      // MediaCodecInfo.CodecProfileLevel.APVProfile422_10HDR10Plus when compile SDK is updated
      // to 36.
      profile = 0x2000;
    } else {
      Log.w(TAG, "Ignoring invalid APV profile: " + profileInteger);
      return null;
    }
    int levelNum = (levelInteger / 30) * 2;
    if (levelInteger % 30 == 0) {
      levelNum -= 1;
    }
    int level = ((0x100 << (levelNum - 1)) | (1 << bandInteger));

    return new Pair<>(profile, level);
  }

  @Nullable
  private static Pair<Integer, Integer> getAacCodecProfileAndLevel(String codec, String[] parts) {
    if (parts.length != 3) {
      Log.w(TAG, "Ignoring malformed MP4A codec string: " + codec);
      return null;
    }
    try {
      // Get the object type indication, which is a hexadecimal value (see RFC 6381/ISO 14496-1).
      int objectTypeIndication = Integer.parseInt(parts[1], 16);
      @Nullable String mimeType = MimeTypes.getMimeTypeFromMp4ObjectType(objectTypeIndication);
      if (MimeTypes.AUDIO_AAC.equals(mimeType)) {
        // For MPEG-4 audio this is followed by an audio object type indication as a decimal number.
        int audioObjectTypeIndication = Integer.parseInt(parts[2]);
        int profile = mp4aAudioObjectTypeToProfile(audioObjectTypeIndication);
        if (profile != -1) {
          // Level is set to zero in AAC decoder CodecProfileLevels.
          return new Pair<>(profile, 0);
        }
      }
    } catch (NumberFormatException e) {
      Log.w(TAG, "Ignoring malformed MP4A codec string: " + codec);
    }
    return null;
  }

  @Nullable
  private static Pair<Integer, Integer> getAc4CodecProfileAndLevel(String codec, String[] parts) {
    if (parts.length != 4) {
      Log.w(TAG, "Ignoring malformed AC-4 codec string: " + codec);
      return null;
    }
    int bitstreamVersionInteger;
    int presentationVersionInteger;
    int levelInteger;
    try {
      bitstreamVersionInteger = Integer.parseInt(parts[1]);
      presentationVersionInteger = Integer.parseInt(parts[2]);
      levelInteger = Integer.parseInt(parts[3]);
    } catch (NumberFormatException e) {
      Log.w(TAG, "Ignoring malformed AC-4 codec string: " + codec);
      return null;
    }

    int profile =
        ac4BitstreamAndPresentationVersionsToProfileConst(
            bitstreamVersionInteger, presentationVersionInteger);
    if (profile == -1) {
      Log.w(
          TAG,
          "Unknown AC-4 profile: " + bitstreamVersionInteger + "." + presentationVersionInteger);
      return null;
    }
    int level = ac4LevelNumberToConst(levelInteger);
    if (level == -1) {
      Log.w(TAG, "Unknown AC-4 level: " + levelInteger);
      return null;
    }
    return new Pair<>(profile, level);
  }

  @Nullable
  private static Pair<Integer, Integer> getIamfCodecProfileAndLevel(String codec, String[] parts) {
    if (parts.length < 4) {
      Log.w(TAG, "Ignoring malformed IAMF codec string: " + codec);
      return null;
    }

    int primaryProfileValue;
    try {
      primaryProfileValue = Integer.parseInt(parts[1]);
    } catch (NumberFormatException e) {
      Log.w(TAG, "Ignoring malformed primary profile in IAMF codec string: " + parts[1], e);
      return null;
    }

    int profileBitmask = 0x1 << (16 + primaryProfileValue);
    int versionBitmask = 0x1 << 24;
    int auxiliaryProfileValue = 0;

    switch (parts[3]) {
      case "Opus":
        auxiliaryProfileValue = 0x1; // Bit 0
        break;
      case "mp4a":
        auxiliaryProfileValue = 0x1 << 1; // Bit 1
        break;
      case "fLaC":
        auxiliaryProfileValue = 0x1 << 2; // Bit 2
        break;
      case "ipcm":
        auxiliaryProfileValue = 0x1 << 3; // Bit 3
        break;
      default:
        Log.w(TAG, "Ignoring unknown codec identifier for IAMF auxiliary profile: " + parts[3]);
        return null;
    }
    // IAMF profiles are defined as the combination of (listed from LSB to MSB):
    //  - audio codec (2 bytes)
    //  - profile (1 byte, offset 16)
    //  - specification version (1 byte, offset 24)
    return new Pair<>(versionBitmask | profileBitmask | auxiliaryProfileValue, /* level= */ 0);
  }

  private static int avcProfileNumberToConst(int profileNumber) {
    switch (profileNumber) {
      case 66:
        return MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline;
      case 77:
        return MediaCodecInfo.CodecProfileLevel.AVCProfileMain;
      case 88:
        return MediaCodecInfo.CodecProfileLevel.AVCProfileExtended;
      case 100:
        return MediaCodecInfo.CodecProfileLevel.AVCProfileHigh;
      case 110:
        return MediaCodecInfo.CodecProfileLevel.AVCProfileHigh10;
      case 122:
        return MediaCodecInfo.CodecProfileLevel.AVCProfileHigh422;
      case 244:
        return MediaCodecInfo.CodecProfileLevel.AVCProfileHigh444;
      default:
        return -1;
    }
  }

  private static int avcLevelNumberToConst(int levelNumber) {
    // TODO: Find int for CodecProfileLevel.AVCLevel1b.
    switch (levelNumber) {
      case 10:
        return MediaCodecInfo.CodecProfileLevel.AVCLevel1;
      case 11:
        return MediaCodecInfo.CodecProfileLevel.AVCLevel11;
      case 12:
        return MediaCodecInfo.CodecProfileLevel.AVCLevel12;
      case 13:
        return MediaCodecInfo.CodecProfileLevel.AVCLevel13;
      case 20:
        return MediaCodecInfo.CodecProfileLevel.AVCLevel2;
      case 21:
        return MediaCodecInfo.CodecProfileLevel.AVCLevel21;
      case 22:
        return MediaCodecInfo.CodecProfileLevel.AVCLevel22;
      case 30:
        return MediaCodecInfo.CodecProfileLevel.AVCLevel3;
      case 31:
        return MediaCodecInfo.CodecProfileLevel.AVCLevel31;
      case 32:
        return MediaCodecInfo.CodecProfileLevel.AVCLevel32;
      case 40:
        return MediaCodecInfo.CodecProfileLevel.AVCLevel4;
      case 41:
        return MediaCodecInfo.CodecProfileLevel.AVCLevel41;
      case 42:
        return MediaCodecInfo.CodecProfileLevel.AVCLevel42;
      case 50:
        return MediaCodecInfo.CodecProfileLevel.AVCLevel5;
      case 51:
        return MediaCodecInfo.CodecProfileLevel.AVCLevel51;
      case 52:
        return MediaCodecInfo.CodecProfileLevel.AVCLevel52;
      default:
        return -1;
    }
  }

  private static int vp9ProfileNumberToConst(int profileNumber) {
    switch (profileNumber) {
      case 0:
        return MediaCodecInfo.CodecProfileLevel.VP9Profile0;
      case 1:
        return MediaCodecInfo.CodecProfileLevel.VP9Profile1;
      case 2:
        return MediaCodecInfo.CodecProfileLevel.VP9Profile2;
      case 3:
        return MediaCodecInfo.CodecProfileLevel.VP9Profile3;
      default:
        return -1;
    }
  }

  private static int vp9LevelNumberToConst(int levelNumber) {
    switch (levelNumber) {
      case 10:
        return MediaCodecInfo.CodecProfileLevel.VP9Level1;
      case 11:
        return MediaCodecInfo.CodecProfileLevel.VP9Level11;
      case 20:
        return MediaCodecInfo.CodecProfileLevel.VP9Level2;
      case 21:
        return MediaCodecInfo.CodecProfileLevel.VP9Level21;
      case 30:
        return MediaCodecInfo.CodecProfileLevel.VP9Level3;
      case 31:
        return MediaCodecInfo.CodecProfileLevel.VP9Level31;
      case 40:
        return MediaCodecInfo.CodecProfileLevel.VP9Level4;
      case 41:
        return MediaCodecInfo.CodecProfileLevel.VP9Level41;
      case 50:
        return MediaCodecInfo.CodecProfileLevel.VP9Level5;
      case 51:
        return MediaCodecInfo.CodecProfileLevel.VP9Level51;
      case 60:
        return MediaCodecInfo.CodecProfileLevel.VP9Level6;
      case 61:
        return MediaCodecInfo.CodecProfileLevel.VP9Level61;
      case 62:
        return MediaCodecInfo.CodecProfileLevel.VP9Level62;
      default:
        return -1;
    }
  }

  @Nullable
  private static Integer hevcCodecStringToProfileLevel(@Nullable String codecString) {
    if (codecString == null) {
      return null;
    }
    switch (codecString) {
      case "L30":
        return MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel1;
      case "L60":
        return MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel2;
      case "L63":
        return MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel21;
      case "L90":
        return MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel3;
      case "L93":
        return MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel31;
      case "L120":
        return MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel4;
      case "L123":
        return MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel41;
      case "L150":
        return MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel5;
      case "L153":
        return MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel51;
      case "L156":
        return MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel52;
      case "L180":
        return MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel6;
      case "L183":
        return MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel61;
      case "L186":
        return MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel62;
      case "H30":
        return MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel1;
      case "H60":
        return MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel2;
      case "H63":
        return MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel21;
      case "H90":
        return MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel3;
      case "H93":
        return MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel31;
      case "H120":
        return MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel4;
      case "H123":
        return MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel41;
      case "H150":
        return MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel5;
      case "H153":
        return MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel51;
      case "H156":
        return MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel52;
      case "H180":
        return MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel6;
      case "H183":
        return MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel61;
      case "H186":
        return MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel62;
      default:
        return null;
    }
  }

  @Nullable
  private static Integer dolbyVisionStringToProfile(@Nullable String profileString) {
    if (profileString == null) {
      return null;
    }
    switch (profileString) {
      case "00":
        return MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvavPer;
      case "01":
        return MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvavPen;
      case "02":
        return MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvheDer;
      case "03":
        return MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvheDen;
      case "04":
        return MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvheDtr;
      case "05":
        return MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvheStn;
      case "06":
        return MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvheDth;
      case "07":
        return MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvheDtb;
      case "08":
        return MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvheSt;
      case "09":
        return MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvavSe;
      case "10":
        return MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvav110;
      default:
        return null;
    }
  }

  @Nullable
  private static Integer dolbyVisionStringToLevel(@Nullable String levelString) {
    if (levelString == null) {
      return null;
    }
    // TODO (Internal: b/179261323): use framework constant for level 13.
    switch (levelString) {
      case "01":
        return MediaCodecInfo.CodecProfileLevel.DolbyVisionLevelHd24;
      case "02":
        return MediaCodecInfo.CodecProfileLevel.DolbyVisionLevelHd30;
      case "03":
        return MediaCodecInfo.CodecProfileLevel.DolbyVisionLevelFhd24;
      case "04":
        return MediaCodecInfo.CodecProfileLevel.DolbyVisionLevelFhd30;
      case "05":
        return MediaCodecInfo.CodecProfileLevel.DolbyVisionLevelFhd60;
      case "06":
        return MediaCodecInfo.CodecProfileLevel.DolbyVisionLevelUhd24;
      case "07":
        return MediaCodecInfo.CodecProfileLevel.DolbyVisionLevelUhd30;
      case "08":
        return MediaCodecInfo.CodecProfileLevel.DolbyVisionLevelUhd48;
      case "09":
        return MediaCodecInfo.CodecProfileLevel.DolbyVisionLevelUhd60;
      case "10":
        return MediaCodecInfo.CodecProfileLevel.DolbyVisionLevelUhd120;
      case "11":
        return MediaCodecInfo.CodecProfileLevel.DolbyVisionLevel8k30;
      case "12":
        return MediaCodecInfo.CodecProfileLevel.DolbyVisionLevel8k60;
      case "13":
        return 0x1000;
      default:
        return null;
    }
  }

  private static int av1LevelNumberToConst(int levelNumber) {
    // See https://aomediacodec.github.io/av1-spec/av1-spec.pdf Annex A: Profiles and levels for
    // more information on mapping AV1 codec strings to levels.
    switch (levelNumber) {
      case 0:
        return MediaCodecInfo.CodecProfileLevel.AV1Level2;
      case 1:
        return MediaCodecInfo.CodecProfileLevel.AV1Level21;
      case 2:
        return MediaCodecInfo.CodecProfileLevel.AV1Level22;
      case 3:
        return MediaCodecInfo.CodecProfileLevel.AV1Level23;
      case 4:
        return MediaCodecInfo.CodecProfileLevel.AV1Level3;
      case 5:
        return MediaCodecInfo.CodecProfileLevel.AV1Level31;
      case 6:
        return MediaCodecInfo.CodecProfileLevel.AV1Level32;
      case 7:
        return MediaCodecInfo.CodecProfileLevel.AV1Level33;
      case 8:
        return MediaCodecInfo.CodecProfileLevel.AV1Level4;
      case 9:
        return MediaCodecInfo.CodecProfileLevel.AV1Level41;
      case 10:
        return MediaCodecInfo.CodecProfileLevel.AV1Level42;
      case 11:
        return MediaCodecInfo.CodecProfileLevel.AV1Level43;
      case 12:
        return MediaCodecInfo.CodecProfileLevel.AV1Level5;
      case 13:
        return MediaCodecInfo.CodecProfileLevel.AV1Level51;
      case 14:
        return MediaCodecInfo.CodecProfileLevel.AV1Level52;
      case 15:
        return MediaCodecInfo.CodecProfileLevel.AV1Level53;
      case 16:
        return MediaCodecInfo.CodecProfileLevel.AV1Level6;
      case 17:
        return MediaCodecInfo.CodecProfileLevel.AV1Level61;
      case 18:
        return MediaCodecInfo.CodecProfileLevel.AV1Level62;
      case 19:
        return MediaCodecInfo.CodecProfileLevel.AV1Level63;
      case 20:
        return MediaCodecInfo.CodecProfileLevel.AV1Level7;
      case 21:
        return MediaCodecInfo.CodecProfileLevel.AV1Level71;
      case 22:
        return MediaCodecInfo.CodecProfileLevel.AV1Level72;
      case 23:
        return MediaCodecInfo.CodecProfileLevel.AV1Level73;
      default:
        return -1;
    }
  }

  private static int mp4aAudioObjectTypeToProfile(int profileNumber) {
    switch (profileNumber) {
      case 1:
        return MediaCodecInfo.CodecProfileLevel.AACObjectMain;
      case 2:
        return MediaCodecInfo.CodecProfileLevel.AACObjectLC;
      case 3:
        return MediaCodecInfo.CodecProfileLevel.AACObjectSSR;
      case 4:
        return MediaCodecInfo.CodecProfileLevel.AACObjectLTP;
      case 5:
        return MediaCodecInfo.CodecProfileLevel.AACObjectHE;
      case 6:
        return MediaCodecInfo.CodecProfileLevel.AACObjectScalable;
      case 17:
        return MediaCodecInfo.CodecProfileLevel.AACObjectERLC;
      case 20:
        return MediaCodecInfo.CodecProfileLevel.AACObjectERScalable;
      case 23:
        return MediaCodecInfo.CodecProfileLevel.AACObjectLD;
      case 29:
        return MediaCodecInfo.CodecProfileLevel.AACObjectHE_PS;
      case 39:
        return MediaCodecInfo.CodecProfileLevel.AACObjectELD;
      case 42:
        return MediaCodecInfo.CodecProfileLevel.AACObjectXHE;
      default:
        return -1;
    }
  }

  private static int ac4BitstreamAndPresentationVersionsToProfileConst(
      int bitstreamVersionInteger, int presentationVersionInteger) {
    int ac4Profile = -1;
    switch (bitstreamVersionInteger) {
      case 0:
        if (presentationVersionInteger == 0) {
          ac4Profile = MediaCodecInfo.CodecProfileLevel.AC4Profile00;
        }
        break;
      case 1:
        if (presentationVersionInteger == 0) {
          ac4Profile = MediaCodecInfo.CodecProfileLevel.AC4Profile10;
        } else if (presentationVersionInteger == 1) {
          ac4Profile = MediaCodecInfo.CodecProfileLevel.AC4Profile11;
        }
        break;
      case 2:
        if (presentationVersionInteger == 1) {
          ac4Profile = MediaCodecInfo.CodecProfileLevel.AC4Profile21;
        } else if (presentationVersionInteger == 2) {
          ac4Profile = MediaCodecInfo.CodecProfileLevel.AC4Profile22;
        }
        break;
      default:
        break;
    }
    return ac4Profile;
  }

  private static int ac4LevelNumberToConst(int levelNumber) {
    switch (levelNumber) {
      case 0:
        return MediaCodecInfo.CodecProfileLevel.AC4Level0;
      case 1:
        return MediaCodecInfo.CodecProfileLevel.AC4Level1;
      case 2:
        return MediaCodecInfo.CodecProfileLevel.AC4Level2;
      case 3:
        return MediaCodecInfo.CodecProfileLevel.AC4Level3;
      case 4:
        return MediaCodecInfo.CodecProfileLevel.AC4Level4;
      default:
        return -1;
    }
  }

  private CodecSpecificDataUtil() {}
}
