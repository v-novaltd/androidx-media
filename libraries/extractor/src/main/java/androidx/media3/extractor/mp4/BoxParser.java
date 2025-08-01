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
package androidx.media3.extractor.mp4;

import static androidx.media3.common.MimeTypes.getMimeTypeFromMp4ObjectType;
import static androidx.media3.common.util.Assertions.checkNotNull;
import static androidx.media3.common.util.Assertions.checkState;
import static androidx.media3.common.util.Util.castNonNull;
import static java.lang.Math.max;
import static java.nio.ByteOrder.BIG_ENDIAN;
import static java.nio.ByteOrder.LITTLE_ENDIAN;

import android.util.Pair;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.ColorInfo;
import androidx.media3.common.DrmInitData;
import androidx.media3.common.Format;
import androidx.media3.common.Metadata;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.ParserException;
import androidx.media3.common.util.CodecSpecificDataUtil;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.NullableType;
import androidx.media3.common.util.ParsableBitArray;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.container.DolbyVisionConfig;
import androidx.media3.container.Mp4AlternateGroupData;
import androidx.media3.container.Mp4Box;
import androidx.media3.container.Mp4Box.LeafBox;
import androidx.media3.container.Mp4LocationData;
import androidx.media3.container.Mp4TimestampData;
import androidx.media3.container.NalUnitUtil;
import androidx.media3.extractor.AacUtil;
import androidx.media3.extractor.Ac3Util;
import androidx.media3.extractor.Ac4Util;
import androidx.media3.extractor.AvcConfig;
import androidx.media3.extractor.ExtractorUtil;
import androidx.media3.extractor.GaplessInfoHolder;
import androidx.media3.extractor.HevcConfig;
import androidx.media3.extractor.OpusUtil;
import androidx.media3.extractor.VorbisUtil;
import androidx.media3.extractor.text.vobsub.VobsubParser;
import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.primitives.Ints;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** Utility methods for parsing MP4 format box payloads according to ISO/IEC 14496-12. */
@SuppressWarnings("ConstantField")
@UnstableApi
public final class BoxParser {

  private static final String TAG = "BoxParsers";

  @SuppressWarnings("ConstantCaseForConstants")
  private static final int TYPE_clcp = 0x636c6370;

  @SuppressWarnings("ConstantCaseForConstants")
  private static final int TYPE_mdta = 0x6d647461;

  @SuppressWarnings("ConstantCaseForConstants")
  private static final int TYPE_meta = 0x6d657461;

  @SuppressWarnings("ConstantCaseForConstants")
  private static final int TYPE_nclc = 0x6e636c63;

  @SuppressWarnings("ConstantCaseForConstants")
  private static final int TYPE_nclx = 0x6e636c78;

  @SuppressWarnings("ConstantCaseForConstants")
  private static final int TYPE_sbtl = 0x7362746c;

  @SuppressWarnings("ConstantCaseForConstants")
  private static final int TYPE_soun = 0x736f756e;

  @SuppressWarnings("ConstantCaseForConstants")
  private static final int TYPE_subt = 0x73756274;

  @SuppressWarnings("ConstantCaseForConstants")
  private static final int TYPE_subp = 0x73756270;

  @SuppressWarnings("ConstantCaseForConstants")
  private static final int TYPE_text = 0x74657874;

  @SuppressWarnings("ConstantCaseForConstants")
  private static final int TYPE_vide = 0x76696465;

  private static final int SAMPLE_RATE_AMR_NB = 8_000;
  private static final int SAMPLE_RATE_AMR_WB = 16_000;

  /**
   * The threshold number of samples to trim from the start/end of an audio track when applying an
   * edit below which gapless info can be used (rather than removing samples from the sample table).
   */
  private static final int MAX_GAPLESS_TRIM_SIZE_SAMPLES = 4;

  /**
   * A small tolerance for comparing track durations, in timescale units. This is to account for
   * small rounding errors that can be introduced when scaling edit list durations from the movie
   * timescale to the track timescale.
   */
  private static final int EDIT_LIST_DURATION_TOLERANCE_TIMESCALE_UNITS = 2;

  /** The magic signature for an Opus Identification header, as defined in RFC-7845. */
  private static final byte[] opusMagic = Util.getUtf8Bytes("OpusHead");

  /** Parses the version number out of the additional integer component of a full box. */
  public static int parseFullBoxVersion(int fullBoxInt) {
    return 0x000000FF & (fullBoxInt >> 24);
  }

  /** Parses the box flags out of the additional integer component of a full box. */
  public static int parseFullBoxFlags(int fullBoxInt) {
    return 0x00FFFFFF & fullBoxInt;
  }

  /**
   * Parse the trak boxes in a moov box (defined in ISO/IEC 14496-12).
   *
   * @param moov Moov box to decode.
   * @param gaplessInfoHolder Holder to populate with gapless playback information.
   * @param duration The duration in units of the timescale declared in the mvhd box, or {@link
   *     C#TIME_UNSET} if the duration should be parsed from the tkhd box.
   * @param drmInitData {@link DrmInitData} to be included in the format, or {@code null}.
   * @param ignoreEditLists Whether to ignore any edit lists in the trak boxes.
   * @param isQuickTime True for QuickTime media. False otherwise.
   * @param modifyTrackFunction A function to apply to the {@link Track Tracks} in the result.
   * @param omitTrackSampleTable Whether to optimize for metadata retrieval by skipping allocation
   *     and population of per-sample arrays in the {@link TrackSampleTable}.
   * @return A list of {@link TrackSampleTable} instances.
   * @throws ParserException Thrown if the trak boxes can't be parsed.
   */
  public static List<TrackSampleTable> parseTraks(
      Mp4Box.ContainerBox moov,
      GaplessInfoHolder gaplessInfoHolder,
      long duration,
      @Nullable DrmInitData drmInitData,
      boolean ignoreEditLists,
      boolean isQuickTime,
      Function<@NullableType Track, @NullableType Track> modifyTrackFunction,
      boolean omitTrackSampleTable)
      throws ParserException {
    List<TrackSampleTable> trackSampleTables = new ArrayList<>();
    for (int i = 0; i < moov.containerChildren.size(); i++) {
      Mp4Box.ContainerBox atom = moov.containerChildren.get(i);
      if (atom.type != Mp4Box.TYPE_trak) {
        continue;
      }
      @Nullable
      Track track =
          modifyTrackFunction.apply(
              parseTrak(
                  atom,
                  checkNotNull(moov.getLeafBoxOfType(Mp4Box.TYPE_mvhd)),
                  duration,
                  drmInitData,
                  ignoreEditLists,
                  isQuickTime));
      if (track == null) {
        continue;
      }
      Mp4Box.ContainerBox stblAtom =
          checkNotNull(
              checkNotNull(
                      checkNotNull(atom.getContainerBoxOfType(Mp4Box.TYPE_mdia))
                          .getContainerBoxOfType(Mp4Box.TYPE_minf))
                  .getContainerBoxOfType(Mp4Box.TYPE_stbl));
      TrackSampleTable trackSampleTable =
          parseStbl(track, stblAtom, gaplessInfoHolder, omitTrackSampleTable);
      trackSampleTables.add(trackSampleTable);
    }
    return trackSampleTables;
  }

  /**
   * Parses a udta box.
   *
   * @param udtaBox The udta (user data) box to decode.
   * @return Parsed metadata.
   */
  public static Metadata parseUdta(LeafBox udtaBox) {
    ParsableByteArray udtaData = udtaBox.data;
    udtaData.setPosition(Mp4Box.HEADER_SIZE);
    Metadata metadata = new Metadata();
    while (udtaData.bytesLeft() >= Mp4Box.HEADER_SIZE) {
      int atomPosition = udtaData.getPosition();
      int atomSize = udtaData.readInt();
      int atomType = udtaData.readInt();
      if (atomType == Mp4Box.TYPE_meta) {
        udtaData.setPosition(atomPosition);
        metadata =
            metadata.copyWithAppendedEntriesFrom(parseUdtaMeta(udtaData, atomPosition + atomSize));
      } else if (atomType == Mp4Box.TYPE_smta) {
        udtaData.setPosition(atomPosition);
        metadata =
            metadata.copyWithAppendedEntriesFrom(
                SmtaAtomUtil.parseSmta(udtaData, atomPosition + atomSize));
      } else if (atomType == Mp4Box.TYPE_xyz) {
        metadata = metadata.copyWithAppendedEntriesFrom(parseXyz(udtaData));
      }
      udtaData.setPosition(atomPosition + atomSize);
    }
    return metadata;
  }

  /**
   * Parses an mvhd box (defined in ISO/IEC 14496-12).
   *
   * @param mvhd Contents of the mvhd box to be parsed.
   * @return An object containing the parsed data.
   */
  public static Mp4TimestampData parseMvhd(ParsableByteArray mvhd) {
    mvhd.setPosition(Mp4Box.HEADER_SIZE);
    int fullAtom = mvhd.readInt();
    int version = parseFullBoxVersion(fullAtom);
    long creationTimestampSeconds;
    long modificationTimestampSeconds;
    if (version == 0) {
      creationTimestampSeconds = mvhd.readUnsignedInt();
      modificationTimestampSeconds = mvhd.readUnsignedInt();
    } else {
      creationTimestampSeconds = mvhd.readLong();
      modificationTimestampSeconds = mvhd.readLong();
    }

    long timescale = mvhd.readUnsignedInt();
    return new Mp4TimestampData(creationTimestampSeconds, modificationTimestampSeconds, timescale);
  }

  /**
   * Parses a metadata meta box if it contains metadata with handler 'mdta'.
   *
   * @param meta The metadata box to decode.
   * @return Parsed metadata, or null.
   */
  @Nullable
  public static Metadata parseMdtaFromMeta(Mp4Box.ContainerBox meta) {
    @Nullable LeafBox hdlrAtom = meta.getLeafBoxOfType(Mp4Box.TYPE_hdlr);
    @Nullable LeafBox keysAtom = meta.getLeafBoxOfType(Mp4Box.TYPE_keys);
    @Nullable LeafBox ilstAtom = meta.getLeafBoxOfType(Mp4Box.TYPE_ilst);
    if (hdlrAtom == null
        || keysAtom == null
        || ilstAtom == null
        || parseHdlr(hdlrAtom.data) != TYPE_mdta) {
      // There isn't enough information to parse the metadata, or the handler type is unexpected.
      return null;
    }

    // Parse metadata keys.
    ParsableByteArray keys = keysAtom.data;
    keys.setPosition(Mp4Box.FULL_HEADER_SIZE);
    int entryCount = keys.readInt();
    String[] keyNames = new String[entryCount];
    for (int i = 0; i < entryCount; i++) {
      int entrySize = keys.readInt();
      keys.skipBytes(4); // keyNamespace
      int keySize = entrySize - 8;
      keyNames[i] = keys.readString(keySize);
    }

    // Parse metadata items.
    ParsableByteArray ilst = ilstAtom.data;
    ilst.setPosition(Mp4Box.HEADER_SIZE);
    ArrayList<Metadata.Entry> entries = new ArrayList<>();
    while (ilst.bytesLeft() > Mp4Box.HEADER_SIZE) {
      int atomPosition = ilst.getPosition();
      int atomSize = ilst.readInt();
      int keyIndex = ilst.readInt() - 1;
      if (keyIndex >= 0 && keyIndex < keyNames.length) {
        String key = keyNames[keyIndex];
        @Nullable
        Metadata.Entry entry =
            MetadataUtil.parseMdtaMetadataEntryFromIlst(ilst, atomPosition + atomSize, key);
        if (entry != null) {
          entries.add(entry);
        }
      } else {
        Log.w(TAG, "Skipped metadata with unknown key index: " + keyIndex);
      }
      ilst.setPosition(atomPosition + atomSize);
    }
    return entries.isEmpty() ? null : new Metadata(entries);
  }

  /**
   * Possibly skips the version and flags fields (1+3 byte) of a full meta box.
   *
   * <p>Boxes of type {@link Mp4Box#TYPE_meta} are defined to be full boxes which have four
   * additional bytes for a version and a flags field (see 4.2 'Object Structure' in ISO/IEC
   * 14496-12:2005). QuickTime do not have such a full box structure. Since some of these files are
   * encoded wrongly, we can't rely on the file type though. Instead we must check the 8 bytes after
   * the common header bytes ourselves.
   *
   * @param meta The 8 or more bytes following the meta box size and type.
   */
  public static void maybeSkipRemainingMetaBoxHeaderBytes(ParsableByteArray meta) {
    int endPosition = meta.getPosition();
    // The next 8 bytes can be either:
    // (iso) [1 byte version + 3 bytes flags][4 byte size of next atom]
    // (qt)  [4 byte size of next atom      ][4 byte hdlr atom type   ]
    // In case of (iso) we need to skip the next 4 bytes.
    meta.skipBytes(4);
    if (meta.readInt() != Mp4Box.TYPE_hdlr) {
      endPosition += 4;
    }
    meta.setPosition(endPosition);
  }

  /**
   * Parses a trak box (defined in ISO/IEC 14496-12).
   *
   * @param trak Box to decode.
   * @param mvhd Movie header box, used to get the timescale.
   * @param duration The duration in units of the timescale declared in the mvhd box, or {@link
   *     C#TIME_UNSET} if the duration should be parsed from the tkhd box.
   * @param drmInitData {@link DrmInitData} to be included in the format, or {@code null}.
   * @param ignoreEditLists Whether to ignore any edit lists in the trak box.
   * @param isQuickTime True for QuickTime media. False otherwise.
   * @return A {@link Track} instance, or {@code null} if the track's type isn't supported.
   * @throws ParserException Thrown if the trak box can't be parsed.
   */
  @Nullable
  public static Track parseTrak(
      Mp4Box.ContainerBox trak,
      LeafBox mvhd,
      long duration,
      @Nullable DrmInitData drmInitData,
      boolean ignoreEditLists,
      boolean isQuickTime)
      throws ParserException {
    Mp4Box.ContainerBox mdia = checkNotNull(trak.getContainerBoxOfType(Mp4Box.TYPE_mdia));
    @C.TrackType
    int trackType =
        getTrackTypeForHdlr(parseHdlr(checkNotNull(mdia.getLeafBoxOfType(Mp4Box.TYPE_hdlr)).data));
    if (trackType == C.TRACK_TYPE_UNKNOWN) {
      return null;
    }

    TkhdData tkhdData = parseTkhd(checkNotNull(trak.getLeafBoxOfType(Mp4Box.TYPE_tkhd)).data);
    if (duration == C.TIME_UNSET) {
      duration = tkhdData.duration;
    }
    long movieTimescale = parseMvhd(mvhd.data).timescale;
    long durationUs;
    if (duration == C.TIME_UNSET) {
      durationUs = C.TIME_UNSET;
    } else {
      durationUs = Util.scaleLargeTimestamp(duration, C.MICROS_PER_SECOND, movieTimescale);
    }
    Mp4Box.ContainerBox stbl =
        checkNotNull(
            checkNotNull(mdia.getContainerBoxOfType(Mp4Box.TYPE_minf))
                .getContainerBoxOfType(Mp4Box.TYPE_stbl));

    MdhdData mdhdData = parseMdhd(checkNotNull(mdia.getLeafBoxOfType(Mp4Box.TYPE_mdhd)).data);
    LeafBox stsd = stbl.getLeafBoxOfType(Mp4Box.TYPE_stsd);
    if (stsd == null) {
      throw ParserException.createForMalformedContainer(
          "Malformed sample table (stbl) missing sample description (stsd)", /* cause= */ null);
    }
    StsdData stsdData = parseStsd(stsd.data, tkhdData, mdhdData.language, drmInitData, isQuickTime);
    @Nullable long[] editListDurations = null;
    @Nullable long[] editListMediaTimes = null;
    if (!ignoreEditLists) {
      @Nullable Mp4Box.ContainerBox edtsAtom = trak.getContainerBoxOfType(Mp4Box.TYPE_edts);
      if (edtsAtom != null) {
        @Nullable Pair<long[], long[]> edtsData = parseEdts(edtsAtom);
        if (edtsData != null) {
          editListDurations = edtsData.first;
          editListMediaTimes = edtsData.second;
        }
      }
    }
    if (stsdData.format == null) {
      return null;
    }
    Format format;
    if (tkhdData.alternateGroup != 0) {
      Mp4AlternateGroupData alternateGroupEntry =
          new Mp4AlternateGroupData(tkhdData.alternateGroup);
      format =
          stsdData
              .format
              .buildUpon()
              .setMetadata(
                  stsdData.format.metadata != null
                      ? stsdData.format.metadata.copyWithAppendedEntries(alternateGroupEntry)
                      : new Metadata(alternateGroupEntry))
              .build();
    } else {
      format = stsdData.format;
    }
    return new Track(
        tkhdData.id,
        trackType,
        mdhdData.timescale,
        movieTimescale,
        durationUs,
        mdhdData.mediaDurationUs,
        format,
        stsdData.requiredSampleTransformation,
        stsdData.trackEncryptionBoxes,
        stsdData.nalUnitLengthFieldLength,
        editListDurations,
        editListMediaTimes);
  }

  /**
   * Parses an stbl box (defined in ISO/IEC 14496-12).
   *
   * @param track Track to which this sample table corresponds.
   * @param stblBox stbl (sample table) box to decode.
   * @param gaplessInfoHolder Holder to populate with gapless playback information.
   * @param omitTrackSampleTable Whether to optimize for metadata retrieval by skipping allocation
   *     and population of per-sample arrays in the {@link TrackSampleTable}.
   * @return Sample table described by the stbl box.
   * @throws ParserException Thrown if the stbl box can't be parsed.
   */
  public static TrackSampleTable parseStbl(
      Track track,
      Mp4Box.ContainerBox stblBox,
      GaplessInfoHolder gaplessInfoHolder,
      boolean omitTrackSampleTable)
      throws ParserException {
    SampleSizeBox sampleSizeBox;
    @Nullable LeafBox stszAtom = stblBox.getLeafBoxOfType(Mp4Box.TYPE_stsz);
    if (stszAtom != null) {
      sampleSizeBox = new StszSampleSizeBox(stszAtom, track.format);
    } else {
      @Nullable LeafBox stz2Atom = stblBox.getLeafBoxOfType(Mp4Box.TYPE_stz2);
      if (stz2Atom == null) {
        throw ParserException.createForMalformedContainer(
            "Track has no sample table size information", /* cause= */ null);
      }
      sampleSizeBox = new Stz2SampleSizeBox(stz2Atom);
    }

    int sampleCount = sampleSizeBox.getSampleCount();
    if (sampleCount == 0) {
      return new TrackSampleTable(
          track,
          /* offsets= */ new long[0],
          /* sizes= */ new int[0],
          /* maximumSize= */ 0,
          /* timestampsUs= */ new long[0],
          /* flags= */ new int[0],
          /* durationUs= */ 0,
          /* sampleCount= */ 0);
    }

    if (track.type == C.TRACK_TYPE_VIDEO && track.mediaDurationUs > 0) {
      float frameRate = sampleCount / (track.mediaDurationUs / 1000000f);
      Format format = track.format.buildUpon().setFrameRate(frameRate).build();
      track = track.copyWithFormat(format);
    }

    // Entries are byte offsets of chunks.
    boolean chunkOffsetsAreLongs = false;
    @Nullable LeafBox chunkOffsetsAtom = stblBox.getLeafBoxOfType(Mp4Box.TYPE_stco);
    if (chunkOffsetsAtom == null) {
      chunkOffsetsAreLongs = true;
      chunkOffsetsAtom = checkNotNull(stblBox.getLeafBoxOfType(Mp4Box.TYPE_co64));
    }
    ParsableByteArray chunkOffsets = chunkOffsetsAtom.data;
    // Entries are (chunk number, number of samples per chunk, sample description index).
    ParsableByteArray stsc = checkNotNull(stblBox.getLeafBoxOfType(Mp4Box.TYPE_stsc)).data;
    // Entries are (number of samples, timestamp delta between those samples).
    ParsableByteArray stts = checkNotNull(stblBox.getLeafBoxOfType(Mp4Box.TYPE_stts)).data;
    // Entries are the indices of samples that are synchronization samples.
    @Nullable LeafBox stssAtom = stblBox.getLeafBoxOfType(Mp4Box.TYPE_stss);
    @Nullable ParsableByteArray stss = stssAtom != null ? stssAtom.data : null;
    // Entries are (number of samples, timestamp offset).
    @Nullable LeafBox cttsAtom = stblBox.getLeafBoxOfType(Mp4Box.TYPE_ctts);
    @Nullable ParsableByteArray ctts = cttsAtom != null ? cttsAtom.data : null;

    // Prepare to read chunk information.
    ChunkIterator chunkIterator = new ChunkIterator(stsc, chunkOffsets, chunkOffsetsAreLongs);

    // Prepare to read sample timestamps.
    stts.setPosition(Mp4Box.FULL_HEADER_SIZE);
    int remainingTimestampDeltaChanges = stts.readUnsignedIntToInt() - 1;
    int remainingSamplesAtTimestampDelta = stts.readUnsignedIntToInt();
    int timestampDeltaInTimeUnits = stts.readUnsignedIntToInt();

    // Prepare to read sample timestamp offsets, if ctts is present.
    int remainingSamplesAtTimestampOffset = 0;
    int remainingTimestampOffsetChanges = 0;
    int timestampOffset = 0;
    if (ctts != null) {
      ctts.setPosition(Mp4Box.FULL_HEADER_SIZE);
      remainingTimestampOffsetChanges = ctts.readUnsignedIntToInt();
    }

    int nextSynchronizationSampleIndex = C.INDEX_UNSET;
    int remainingSynchronizationSamples = 0;
    if (stss != null) {
      stss.setPosition(Mp4Box.FULL_HEADER_SIZE);
      remainingSynchronizationSamples = stss.readUnsignedIntToInt();
      if (remainingSynchronizationSamples > 0) {
        nextSynchronizationSampleIndex = stss.readUnsignedIntToInt() - 1;
      } else {
        // Ignore empty stss boxes, which causes all samples to be treated as sync samples.
        stss = null;
      }
    }

    // Fixed sample size raw audio may need to be rechunked.
    int fixedSampleSize = sampleSizeBox.getFixedSampleSize();
    @Nullable String sampleMimeType = track.format.sampleMimeType;
    boolean rechunkFixedSizeSamples =
        fixedSampleSize != C.LENGTH_UNSET
            && (MimeTypes.AUDIO_RAW.equals(sampleMimeType)
                || MimeTypes.AUDIO_MLAW.equals(sampleMimeType)
                || MimeTypes.AUDIO_ALAW.equals(sampleMimeType))
            && remainingTimestampDeltaChanges == 0
            && remainingTimestampOffsetChanges == 0
            && remainingSynchronizationSamples == 0;

    long[] offsets;
    int[] sizes;
    int maximumSize = 0;
    long[] timestamps;
    int[] flags;
    long timestampTimeUnits = 0;
    long duration;
    long totalSize = 0;

    if (rechunkFixedSizeSamples) {
      long[] chunkOffsetsBytes = new long[chunkIterator.length];
      int[] chunkSampleCounts = new int[chunkIterator.length];
      while (chunkIterator.moveNext()) {
        chunkOffsetsBytes[chunkIterator.index] = chunkIterator.offset;
        chunkSampleCounts[chunkIterator.index] = chunkIterator.numSamples;
      }
      FixedSampleSizeRechunker.Results rechunkedResults =
          FixedSampleSizeRechunker.rechunk(
              fixedSampleSize, chunkOffsetsBytes, chunkSampleCounts, timestampDeltaInTimeUnits);
      offsets = omitTrackSampleTable ? new long[0] : rechunkedResults.offsets;
      sizes = omitTrackSampleTable ? new int[0] : rechunkedResults.sizes;
      timestamps = omitTrackSampleTable ? new long[0] : rechunkedResults.timestamps;
      flags = omitTrackSampleTable ? new int[0] : rechunkedResults.flags;
      maximumSize = rechunkedResults.maximumSize;
      duration = rechunkedResults.duration;
      totalSize = rechunkedResults.totalSize;
    } else {
      offsets = omitTrackSampleTable ? new long[0] : new long[sampleCount];
      sizes = omitTrackSampleTable ? new int[0] : new int[sampleCount];
      timestamps = omitTrackSampleTable ? new long[0] : new long[sampleCount];
      flags = omitTrackSampleTable ? new int[0] : new int[sampleCount];
      long offset = 0;
      int remainingSamplesInChunk = 0;

      for (int i = 0; i < sampleCount; i++) {
        // Advance to the next chunk if necessary.
        boolean chunkDataComplete = true;
        while (remainingSamplesInChunk == 0 && (chunkDataComplete = chunkIterator.moveNext())) {
          offset = chunkIterator.offset;
          remainingSamplesInChunk = chunkIterator.numSamples;
        }
        if (!chunkDataComplete) {
          Log.w(TAG, "Unexpected end of chunk data");
          sampleCount = i;
          if (!omitTrackSampleTable) {
            offsets = Arrays.copyOf(offsets, sampleCount);
            sizes = Arrays.copyOf(sizes, sampleCount);
            timestamps = Arrays.copyOf(timestamps, sampleCount);
            flags = Arrays.copyOf(flags, sampleCount);
          }
          break;
        }

        // Add on the timestamp offset if ctts is present.
        if (ctts != null) {
          while (remainingSamplesAtTimestampOffset == 0 && remainingTimestampOffsetChanges > 0) {
            remainingSamplesAtTimestampOffset = ctts.readUnsignedIntToInt();
            // The BMFF spec (ISO/IEC 14496-12) states that sample offsets should be unsigned
            // integers in version 0 ctts boxes, however some streams violate the spec and use
            // signed integers instead. It's safe to always decode sample offsets as signed integers
            // here, because unsigned integers will still be parsed correctly (unless their top bit
            // is set, which is never true in practice because sample offsets are always small).
            timestampOffset = ctts.readInt();
            remainingTimestampOffsetChanges--;
          }
          remainingSamplesAtTimestampOffset--;
        }

        int currentSampleSize = sampleSizeBox.readNextSampleSize();
        totalSize += currentSampleSize;
        if (currentSampleSize > maximumSize) {
          maximumSize = currentSampleSize;
        }

        if (!omitTrackSampleTable) {
          offsets[i] = offset;
          sizes[i] = currentSampleSize;
          timestamps[i] = timestampTimeUnits + timestampOffset;
          // All samples are synchronization samples if the stss is not present.
          flags[i] = stss == null ? C.BUFFER_FLAG_KEY_FRAME : 0;
          if (i == nextSynchronizationSampleIndex) {
            flags[i] = C.BUFFER_FLAG_KEY_FRAME;
          }
        }

        if (stss != null && i == nextSynchronizationSampleIndex) {
          remainingSynchronizationSamples--;
          if (remainingSynchronizationSamples > 0) {
            nextSynchronizationSampleIndex = checkNotNull(stss).readUnsignedIntToInt() - 1;
          }
        }

        // Add on the duration of this sample.
        timestampTimeUnits += timestampDeltaInTimeUnits;
        remainingSamplesAtTimestampDelta--;
        if (remainingSamplesAtTimestampDelta == 0 && remainingTimestampDeltaChanges > 0) {
          remainingSamplesAtTimestampDelta = stts.readUnsignedIntToInt();
          // The BMFF spec (ISO/IEC 14496-12) states that sample deltas should be unsigned integers
          // in stts boxes, however some streams violate the spec and use signed integers instead.
          // See https://github.com/google/ExoPlayer/issues/3384. It's safe to always decode sample
          // deltas as signed integers here, because unsigned integers will still be parsed
          // correctly (unless their top bit is set, which is never true in practice because sample
          // deltas are always small).
          timestampDeltaInTimeUnits = stts.readInt();
          remainingTimestampDeltaChanges--;
        }

        offset += currentSampleSize;
        remainingSamplesInChunk--;
      }
      duration = timestampTimeUnits + timestampOffset;

      // If the stbl's child boxes are not consistent the container is malformed, but the stream may
      // still be playable.
      boolean isCttsValid = true;
      if (ctts != null) {
        while (remainingTimestampOffsetChanges > 0) {
          if (ctts.readUnsignedIntToInt() != 0) {
            isCttsValid = false;
            break;
          }
          ctts.readInt(); // Ignore offset.
          remainingTimestampOffsetChanges--;
        }
      }
      if (remainingSynchronizationSamples != 0
          || remainingSamplesAtTimestampDelta != 0
          || remainingSamplesInChunk != 0
          || remainingTimestampDeltaChanges != 0
          || remainingSamplesAtTimestampOffset != 0
          || !isCttsValid) {
        Log.w(
            TAG,
            "Inconsistent stbl box for track "
                + track.id
                + ": remainingSynchronizationSamples "
                + remainingSynchronizationSamples
                + ", remainingSamplesAtTimestampDelta "
                + remainingSamplesAtTimestampDelta
                + ", remainingSamplesInChunk "
                + remainingSamplesInChunk
                + ", remainingTimestampDeltaChanges "
                + remainingTimestampDeltaChanges
                + ", remainingSamplesAtTimestampOffset "
                + remainingSamplesAtTimestampOffset
                + (!isCttsValid ? ", ctts invalid" : ""));
      }
    }

    if (track.mediaDurationUs > 0) {
      long averageBitrate =
          Util.scaleLargeValue(
              totalSize * C.BITS_PER_BYTE,
              C.MICROS_PER_SECOND,
              track.mediaDurationUs,
              RoundingMode.HALF_DOWN);
      if (averageBitrate > 0 && averageBitrate < Integer.MAX_VALUE) {
        Format format = track.format.buildUpon().setAverageBitrate((int) averageBitrate).build();
        track = track.copyWithFormat(format);
      }
    }

    long durationUs = Util.scaleLargeTimestamp(duration, C.MICROS_PER_SECOND, track.timescale);

    if (track.editListDurations == null) {
      if (!omitTrackSampleTable) {
        Util.scaleLargeTimestampsInPlace(timestamps, C.MICROS_PER_SECOND, track.timescale);
        return new TrackSampleTable(
            track, offsets, sizes, maximumSize, timestamps, flags, durationUs, sampleCount);
      } else {
        return new TrackSampleTable(
            track,
            /* offsets= */ new long[0],
            /* sizes= */ new int[0],
            maximumSize,
            /* timestampsUs= */ new long[0],
            /* flags= */ new int[0],
            durationUs,
            sampleCount);
      }
    }

    if (omitTrackSampleTable) {
      long editedDurationUs;
      long[] editListMediaTimes = checkNotNull(track.editListMediaTimes);
      if (track.editListDurations.length == 1 && track.editListDurations[0] == 0) {
        long editStartTime = editListMediaTimes[0];
        editedDurationUs =
            Util.scaleLargeTimestamp(
                duration - editStartTime, C.MICROS_PER_SECOND, track.timescale);
      } else {
        long pts = 0;
        for (int i = 0; i < track.editListDurations.length; i++) {
          if (editListMediaTimes[i] != -1) {
            pts += track.editListDurations[i];
          }
        }
        editedDurationUs = Util.scaleLargeTimestamp(pts, C.MICROS_PER_SECOND, track.movieTimescale);
      }
      return new TrackSampleTable(
          track,
          /* offsets= */ new long[0],
          /* sizes= */ new int[0],
          maximumSize,
          /* timestampsUs= */ new long[0],
          /* flags= */ new int[0],
          editedDurationUs,
          sampleCount);
    }

    // See the BMFF spec (ISO/IEC 14496-12) subsection 8.6.6. Edit lists that require prerolling
    // from a sync sample after reordering are not supported. Partial audio sample truncation is
    // only supported in edit lists with one edit that removes less than
    // MAX_GAPLESS_TRIM_SIZE_SAMPLES samples from the start/end of the track. This implementation
    // handles simple discarding/delaying of samples. The extractor may place further restrictions
    // on what edited streams are playable.

    if (track.editListDurations.length == 1
        && track.type == C.TRACK_TYPE_AUDIO
        && timestamps.length >= 2) {
      long editStartTime = checkNotNull(track.editListMediaTimes)[0];
      long editEndTime =
          editStartTime
              + Util.scaleLargeTimestamp(
                  track.editListDurations[0], track.timescale, track.movieTimescale);
      if (canApplyEditWithGaplessInfo(timestamps, duration, editStartTime, editEndTime)) {
        // Clamp padding to 0 to account for rounding errors where editEndTime is slightly
        // greater than duration.
        long paddingTimeUnits = max(0, duration - editEndTime);
        long encoderDelay =
            Util.scaleLargeTimestamp(
                editStartTime - timestamps[0], track.format.sampleRate, track.timescale);
        long encoderPadding =
            Util.scaleLargeTimestamp(paddingTimeUnits, track.format.sampleRate, track.timescale);
        if ((encoderDelay != 0 || encoderPadding != 0)
            && encoderDelay <= Integer.MAX_VALUE
            && encoderPadding <= Integer.MAX_VALUE) {
          gaplessInfoHolder.encoderDelay = (int) encoderDelay;
          gaplessInfoHolder.encoderPadding = (int) encoderPadding;
          Util.scaleLargeTimestampsInPlace(timestamps, C.MICROS_PER_SECOND, track.timescale);
          long editedDurationUs =
              Util.scaleLargeTimestamp(
                  track.editListDurations[0], C.MICROS_PER_SECOND, track.movieTimescale);
          return new TrackSampleTable(
              track, offsets, sizes, maximumSize, timestamps, flags, editedDurationUs, sampleCount);
        }
      }
    }

    if (track.editListDurations.length == 1 && track.editListDurations[0] == 0) {
      // The current version of the spec leaves handling of an edit with zero segment_duration in
      // unfragmented files open to interpretation. We handle this as a special case and include all
      // samples in the edit.
      long editStartTime = checkNotNull(track.editListMediaTimes)[0];
      for (int i = 0; i < timestamps.length; i++) {
        timestamps[i] =
            Util.scaleLargeTimestamp(
                timestamps[i] - editStartTime, C.MICROS_PER_SECOND, track.timescale);
      }
      durationUs =
          Util.scaleLargeTimestamp(duration - editStartTime, C.MICROS_PER_SECOND, track.timescale);
      return new TrackSampleTable(
          track, offsets, sizes, maximumSize, timestamps, flags, durationUs, sampleCount);
    }

    // When applying edit lists, we need to include any partial clipped samples at the end to ensure
    // the final output is rendered correctly (see https://github.com/google/ExoPlayer/issues/2408).
    // For audio only, we can omit any sample that starts at exactly the end point of an edit as
    // there is no partial audio in this case.
    boolean omitZeroDurationClippedSample = track.type == C.TRACK_TYPE_AUDIO;

    // Count the number of samples after applying edits.
    int editedSampleCount = 0;
    int nextSampleIndex = 0;
    boolean copyMetadata = false;
    int[] startIndices = new int[track.editListDurations.length];
    int[] endIndices = new int[track.editListDurations.length];
    long[] editListMediaTimes = checkNotNull(track.editListMediaTimes);
    for (int i = 0; i < track.editListDurations.length; i++) {
      long editMediaTime = editListMediaTimes[i];
      if (editMediaTime != -1) {
        long editDuration =
            Util.scaleLargeTimestamp(
                track.editListDurations[i], track.timescale, track.movieTimescale);
        // The timestamps array is in the order read from the media, which might not be strictly
        // sorted. However, all sync frames are guaranteed to be in order, and any out-of-order
        // frames appear after their respective sync frames. This ensures that although the result
        // of the binary search might not be entirely accurate (due to the out-of-order timestamps),
        // the following logic ensures correctness for both start and end indices.

        // The startIndices calculation finds the largest timestamp that is less than or equal to
        // editMediaTime. It then walks backward to ensure the index points to a sync frame, since
        // decoding must start from a keyframe. If a sync frame is not found by walking backward, it
        // walks forward from the initially found index to find a sync frame.
        startIndices[i] =
            Util.binarySearchFloor(
                timestamps, editMediaTime, /* inclusive= */ true, /* stayInBounds= */ true);

        // The endIndices calculation finds the smallest timestamp that is greater than
        // editMediaTime + editDuration, except when omitZeroDurationClippedSample is true, in which
        // case it finds the smallest timestamp that is greater than or equal to editMediaTime +
        // editDuration.
        endIndices[i] =
            Util.binarySearchCeil(
                timestamps,
                editMediaTime + editDuration,
                /* inclusive= */ omitZeroDurationClippedSample,
                /* stayInBounds= */ false);

        int initialStartIndex = startIndices[i];
        while (startIndices[i] >= 0 && (flags[startIndices[i]] & C.BUFFER_FLAG_KEY_FRAME) == 0) {
          startIndices[i]--;
        }

        if (startIndices[i] < 0) {
          startIndices[i] = initialStartIndex;
          while (startIndices[i] < endIndices[i]
              && (flags[startIndices[i]] & C.BUFFER_FLAG_KEY_FRAME) == 0) {
            startIndices[i]++;
          }
        }

        if (track.type == C.TRACK_TYPE_VIDEO && startIndices[i] != endIndices[i]) {
          // To account for out-of-order video frames that may have timestamps smaller than or equal
          // to editMediaTime + editDuration, but still fall within the valid range, the loop walks
          // forward through the timestamps array to ensure all frames with timestamps within the
          // edit duration are included.
          while (endIndices[i] < timestamps.length - 1
              && timestamps[endIndices[i] + 1] <= (editMediaTime + editDuration)) {
            endIndices[i]++;
          }
        }
        editedSampleCount += endIndices[i] - startIndices[i];
        copyMetadata |= nextSampleIndex != startIndices[i];
        nextSampleIndex = endIndices[i];
      }
    }
    copyMetadata |= editedSampleCount != sampleCount;

    // Calculate edited sample timestamps and update the corresponding metadata arrays.
    long[] editedOffsets = copyMetadata ? new long[editedSampleCount] : offsets;
    int[] editedSizes = copyMetadata ? new int[editedSampleCount] : sizes;
    int editedMaximumSize = copyMetadata ? 0 : maximumSize;
    int[] editedFlags = copyMetadata ? new int[editedSampleCount] : flags;
    long[] editedTimestamps = new long[editedSampleCount];
    long pts = 0;
    int sampleIndex = 0;
    boolean hasPrerollSamples = false;
    for (int i = 0; i < track.editListDurations.length; i++) {
      long editMediaTime = track.editListMediaTimes[i];
      int startIndex = startIndices[i];
      int endIndex = endIndices[i];
      if (copyMetadata) {
        int count = endIndex - startIndex;
        System.arraycopy(offsets, startIndex, editedOffsets, sampleIndex, count);
        System.arraycopy(sizes, startIndex, editedSizes, sampleIndex, count);
        System.arraycopy(flags, startIndex, editedFlags, sampleIndex, count);
      }
      for (int j = startIndex; j < endIndex; j++) {
        long ptsUs = Util.scaleLargeTimestamp(pts, C.MICROS_PER_SECOND, track.movieTimescale);
        long timeInSegmentUs =
            Util.scaleLargeTimestamp(
                timestamps[j] - editMediaTime, C.MICROS_PER_SECOND, track.timescale);
        if (timeInSegmentUs < 0) {
          hasPrerollSamples = true;
        }
        editedTimestamps[sampleIndex] = ptsUs + timeInSegmentUs;
        if (copyMetadata && editedSizes[sampleIndex] > editedMaximumSize) {
          editedMaximumSize = sizes[j];
        }
        sampleIndex++;
      }
      pts += track.editListDurations[i];
    }
    long editedDurationUs =
        Util.scaleLargeTimestamp(pts, C.MICROS_PER_SECOND, track.movieTimescale);
    if (hasPrerollSamples) {
      Format format = track.format.buildUpon().setHasPrerollSamples(true).build();
      track = track.copyWithFormat(format);
    }
    return new TrackSampleTable(
        track,
        editedOffsets,
        editedSizes,
        editedMaximumSize,
        editedTimestamps,
        editedFlags,
        editedDurationUs,
        editedOffsets.length);
  }

  @Nullable
  private static Metadata parseUdtaMeta(ParsableByteArray meta, int limit) {
    meta.skipBytes(Mp4Box.HEADER_SIZE);
    maybeSkipRemainingMetaBoxHeaderBytes(meta);
    while (meta.getPosition() < limit) {
      int atomPosition = meta.getPosition();
      int atomSize = meta.readInt();
      int atomType = meta.readInt();
      if (atomType == Mp4Box.TYPE_ilst) {
        meta.setPosition(atomPosition);
        return parseIlst(meta, atomPosition + atomSize);
      }
      meta.setPosition(atomPosition + atomSize);
    }
    return null;
  }

  @Nullable
  private static Metadata parseIlst(ParsableByteArray ilst, int limit) {
    ilst.skipBytes(Mp4Box.HEADER_SIZE);
    ArrayList<Metadata.Entry> entries = new ArrayList<>();
    while (ilst.getPosition() < limit) {
      @Nullable Metadata.Entry entry = MetadataUtil.parseIlstElement(ilst);
      if (entry != null) {
        entries.add(entry);
      }
    }
    return entries.isEmpty() ? null : new Metadata(entries);
  }

  /** Parses the location metadata from the xyz atom. */
  @Nullable
  private static Metadata parseXyz(ParsableByteArray xyzBox) {
    int length = xyzBox.readShort();
    xyzBox.skipBytes(2); // language code.
    String location = xyzBox.readString(length);
    // The location string looks like "+35.1345-15.1020/".
    int plusSignIndex = location.lastIndexOf('+');
    int minusSignIndex = location.lastIndexOf('-');
    int latitudeEndIndex = max(plusSignIndex, minusSignIndex);
    try {
      float latitude = Float.parseFloat(location.substring(0, latitudeEndIndex));
      float longitude =
          Float.parseFloat(location.substring(latitudeEndIndex, location.length() - 1));
      return new Metadata(new Mp4LocationData(latitude, longitude));
    } catch (IndexOutOfBoundsException | NumberFormatException exception) {
      // Invalid input.
      return null;
    }
  }

  /**
   * Parses a tkhd atom (defined in ISO/IEC 14496-12).
   *
   * @param tkhd Contents of the tkhd atom to be parsed.
   * @return An object containing the parsed data.
   */
  private static TkhdData parseTkhd(ParsableByteArray tkhd) {
    tkhd.setPosition(Mp4Box.HEADER_SIZE);
    int fullAtom = tkhd.readInt();
    int version = parseFullBoxVersion(fullAtom);

    tkhd.skipBytes(version == 0 ? 8 : 16);
    int trackId = tkhd.readInt();

    tkhd.skipBytes(4);
    boolean durationUnknown = true;
    int durationPosition = tkhd.getPosition();
    int durationByteCount = version == 0 ? 4 : 8;
    for (int i = 0; i < durationByteCount; i++) {
      if (tkhd.getData()[durationPosition + i] != -1) {
        durationUnknown = false;
        break;
      }
    }
    long duration;
    if (durationUnknown) {
      tkhd.skipBytes(durationByteCount);
      duration = C.TIME_UNSET;
    } else {
      duration = version == 0 ? tkhd.readUnsignedInt() : tkhd.readUnsignedLongToLong();
      if (duration == 0) {
        // 0 duration normally indicates that the file is fully fragmented (i.e. all of the media
        // samples are in fragments). Treat as unknown.
        duration = C.TIME_UNSET;
      }
    }

    tkhd.skipBytes(10);
    int alternateGroup = tkhd.readUnsignedShort();
    tkhd.skipBytes(4);
    int a00 = tkhd.readInt();
    int a01 = tkhd.readInt();
    tkhd.skipBytes(4);
    int a10 = tkhd.readInt();
    int a11 = tkhd.readInt();

    // Matrices which imply reflection are resolved to a correct rotation, without handling the
    // reflection (because reflection is not currently supported by MediaCodec). This means
    // the video ends the right way up, but incorrectly reflected in the Y axis.
    // TODO: b/390422593 - Add richer transformation matrix support.
    int rotationDegrees;
    int fixedOne = 65536;
    if (a00 == 0 && a01 == fixedOne && (a10 == -fixedOne || a10 == fixedOne) && a11 == 0) {
      rotationDegrees = 90;
    } else if (a00 == 0 && a01 == -fixedOne && (a10 == fixedOne || a10 == -fixedOne) && a11 == 0) {
      rotationDegrees = 270;
    } else if ((a00 == -fixedOne || a00 == fixedOne) && a01 == 0 && a10 == 0 && a11 == -fixedOne) {
      rotationDegrees = 180;
    } else {
      // Only 0, 90, 180 and 270 are supported. Treat anything else as 0.
      rotationDegrees = 0;
    }
    // skip remaining 4 matrix entries
    tkhd.skipBytes(16);
    // ignore fractional part of width and height
    int width = tkhd.readShort();
    tkhd.skipBytes(2);
    int height = tkhd.readShort();

    return new TkhdData(trackId, duration, alternateGroup, rotationDegrees, width, height);
  }

  /**
   * Parses an hdlr atom.
   *
   * @param hdlr The hdlr atom to decode.
   * @return The handler value.
   */
  private static int parseHdlr(ParsableByteArray hdlr) {
    hdlr.setPosition(Mp4Box.FULL_HEADER_SIZE + 4);
    return hdlr.readInt();
  }

  /** Returns the track type for a given handler value. */
  private static @C.TrackType int getTrackTypeForHdlr(int hdlr) {
    if (hdlr == TYPE_soun) {
      return C.TRACK_TYPE_AUDIO;
    } else if (hdlr == TYPE_vide) {
      return C.TRACK_TYPE_VIDEO;
    } else if (hdlr == TYPE_text
        || hdlr == TYPE_sbtl
        || hdlr == TYPE_subt
        || hdlr == TYPE_clcp
        || hdlr == TYPE_subp) {
      return C.TRACK_TYPE_TEXT;
    } else if (hdlr == TYPE_meta) {
      return C.TRACK_TYPE_METADATA;
    } else {
      return C.TRACK_TYPE_UNKNOWN;
    }
  }

  /**
   * Parses an mdhd atom (defined in ISO/IEC 14496-12).
   *
   * @param mdhd The mdhd atom to decode.
   * @return An {@link MdhdData} object containing the parsed data.
   */
  private static MdhdData parseMdhd(ParsableByteArray mdhd) {
    mdhd.setPosition(Mp4Box.HEADER_SIZE);
    int fullAtom = mdhd.readInt();
    int version = parseFullBoxVersion(fullAtom);
    mdhd.skipBytes(version == 0 ? 8 : 16);
    long timescale = mdhd.readUnsignedInt();
    boolean mediaDurationUnknown = true;
    int mediaDurationPosition = mdhd.getPosition();
    int mediaDurationByteCount = version == 0 ? 4 : 8;
    for (int i = 0; i < mediaDurationByteCount; i++) {
      if (mdhd.getData()[mediaDurationPosition + i] != -1) {
        mediaDurationUnknown = false;
        break;
      }
    }
    long mediaDurationUs;
    if (mediaDurationUnknown) {
      mdhd.skipBytes(mediaDurationByteCount);
      mediaDurationUs = C.TIME_UNSET;
    } else {
      long mediaDuration = version == 0 ? mdhd.readUnsignedInt() : mdhd.readUnsignedLongToLong();
      if (mediaDuration == 0) {
        // 0 duration normally indicates that the file is fully fragmented (i.e. all of the media
        // samples are in fragments). Treat as unknown.
        mediaDurationUs = C.TIME_UNSET;
      } else {
        mediaDurationUs = Util.scaleLargeTimestamp(mediaDuration, C.MICROS_PER_SECOND, timescale);
      }
    }

    String language = getLanguageFromCode(/* languageCode= */ mdhd.readUnsignedShort());
    return new MdhdData(timescale, mediaDurationUs, language);
  }

  @Nullable
  private static String getLanguageFromCode(int languageCode) {
    char[] chars = {
      (char) (((languageCode >> 10) & 0x1F) + 0x60),
      (char) (((languageCode >> 5) & 0x1F) + 0x60),
      (char) ((languageCode & 0x1F) + 0x60)
    };

    for (char c : chars) {
      if (c < 'a' || c > 'z') {
        return null;
      }
    }
    return new String(chars);
  }

  /**
   * Parses a stsd atom (defined in ISO/IEC 14496-12).
   *
   * @param stsd The stsd atom to decode.
   * @param tkhdData The track header data from the tkhd box.
   * @param language The language of the track, or {@code null} if unset.
   * @param drmInitData {@link DrmInitData} to be included in the format, or {@code null}.
   * @param isQuickTime True for QuickTime media. False otherwise.
   * @return An object containing the parsed data.
   */
  private static StsdData parseStsd(
      ParsableByteArray stsd,
      TkhdData tkhdData,
      @Nullable String language,
      @Nullable DrmInitData drmInitData,
      boolean isQuickTime)
      throws ParserException {
    stsd.setPosition(Mp4Box.FULL_HEADER_SIZE);
    int numberOfEntries = stsd.readInt();
    StsdData out = new StsdData(numberOfEntries);
    for (int i = 0; i < numberOfEntries; i++) {
      int childStartPosition = stsd.getPosition();
      int childAtomSize = stsd.readInt();
      ExtractorUtil.checkContainerInput(childAtomSize > 0, "childAtomSize must be positive");
      int childAtomType = stsd.readInt();
      if (childAtomType == Mp4Box.TYPE_avc1
          || childAtomType == Mp4Box.TYPE_avc3
          || childAtomType == Mp4Box.TYPE_encv
          || childAtomType == Mp4Box.TYPE_m1v_
          || childAtomType == Mp4Box.TYPE_mp4v
          || childAtomType == Mp4Box.TYPE_hvc1
          || childAtomType == Mp4Box.TYPE_hev1
          || childAtomType == Mp4Box.TYPE_s263
          || childAtomType == Mp4Box.TYPE_H263
          || childAtomType == Mp4Box.TYPE_h263
          || childAtomType == Mp4Box.TYPE_vp08
          || childAtomType == Mp4Box.TYPE_vp09
          || childAtomType == Mp4Box.TYPE_av01
          || childAtomType == Mp4Box.TYPE_dvav
          || childAtomType == Mp4Box.TYPE_dva1
          || childAtomType == Mp4Box.TYPE_dvhe
          || childAtomType == Mp4Box.TYPE_dvh1
          || childAtomType == Mp4Box.TYPE_apv1) {
        parseVideoSampleEntry(
            stsd,
            childAtomType,
            childStartPosition,
            childAtomSize,
            tkhdData.id,
            language,
            tkhdData.rotationDegrees,
            drmInitData,
            out,
            i);
      } else if (childAtomType == Mp4Box.TYPE_mp4a
          || childAtomType == Mp4Box.TYPE_enca
          || childAtomType == Mp4Box.TYPE_ac_3
          || childAtomType == Mp4Box.TYPE_ec_3
          || childAtomType == Mp4Box.TYPE_ac_4
          || childAtomType == Mp4Box.TYPE_mlpa
          || childAtomType == Mp4Box.TYPE_dtsc
          || childAtomType == Mp4Box.TYPE_dtse
          || childAtomType == Mp4Box.TYPE_dtsh
          || childAtomType == Mp4Box.TYPE_dtsl
          || childAtomType == Mp4Box.TYPE_dtsx
          || childAtomType == Mp4Box.TYPE_samr
          || childAtomType == Mp4Box.TYPE_sawb
          || childAtomType == Mp4Box.TYPE_lpcm
          || childAtomType == Mp4Box.TYPE_sowt
          || childAtomType == Mp4Box.TYPE_twos
          || childAtomType == Mp4Box.TYPE__mp2
          || childAtomType == Mp4Box.TYPE__mp3
          || childAtomType == Mp4Box.TYPE_mha1
          || childAtomType == Mp4Box.TYPE_mhm1
          || childAtomType == Mp4Box.TYPE_alac
          || childAtomType == Mp4Box.TYPE_alaw
          || childAtomType == Mp4Box.TYPE_ulaw
          || childAtomType == Mp4Box.TYPE_Opus
          || childAtomType == Mp4Box.TYPE_fLaC
          || childAtomType == Mp4Box.TYPE_iamf
          || childAtomType == Mp4Box.TYPE_ipcm
          || childAtomType == Mp4Box.TYPE_fpcm) {
        parseAudioSampleEntry(
            stsd,
            childAtomType,
            childStartPosition,
            childAtomSize,
            tkhdData.id,
            language,
            isQuickTime,
            drmInitData,
            out,
            i);
      } else if (childAtomType == Mp4Box.TYPE_TTML
          || childAtomType == Mp4Box.TYPE_tx3g
          || childAtomType == Mp4Box.TYPE_wvtt
          || childAtomType == Mp4Box.TYPE_stpp
          || childAtomType == Mp4Box.TYPE_c608
          || childAtomType == Mp4Box.TYPE_mp4s) {
        parseTextSampleEntry(
            stsd, childAtomType, childStartPosition, childAtomSize, tkhdData, language, out);
      } else if (childAtomType == Mp4Box.TYPE_mett) {
        parseMetaDataSampleEntry(stsd, childAtomType, childStartPosition, tkhdData.id, out);
      } else if (childAtomType == Mp4Box.TYPE_camm) {
        out.format =
            new Format.Builder()
                .setId(tkhdData.id)
                .setSampleMimeType(MimeTypes.APPLICATION_CAMERA_MOTION)
                .build();
      }
      stsd.setPosition(childStartPosition + childAtomSize);
    }
    return out;
  }

  private static void parseTextSampleEntry(
      ParsableByteArray parent,
      int atomType,
      int position,
      int atomSize,
      TkhdData tkhdData,
      @Nullable String language,
      StsdData out) {
    parent.setPosition(position + Mp4Box.HEADER_SIZE + StsdData.STSD_HEADER_SIZE);

    // Default values.
    @Nullable ImmutableList<byte[]> initializationData = null;
    long subsampleOffsetUs = Format.OFFSET_SAMPLE_RELATIVE;

    @Nullable String mimeType = null;
    if (atomType == Mp4Box.TYPE_TTML) {
      mimeType = MimeTypes.APPLICATION_TTML;
    } else if (atomType == Mp4Box.TYPE_tx3g) {
      mimeType = MimeTypes.APPLICATION_TX3G;
      int sampleDescriptionLength = atomSize - Mp4Box.HEADER_SIZE - 8;
      byte[] sampleDescriptionData = new byte[sampleDescriptionLength];
      parent.readBytes(sampleDescriptionData, 0, sampleDescriptionLength);
      initializationData = ImmutableList.of(sampleDescriptionData);
    } else if (atomType == Mp4Box.TYPE_wvtt) {
      mimeType = MimeTypes.APPLICATION_MP4VTT;
    } else if (atomType == Mp4Box.TYPE_stpp) {
      mimeType = MimeTypes.APPLICATION_TTML;
      subsampleOffsetUs = 0; // Subsample timing is absolute.
    } else if (atomType == Mp4Box.TYPE_c608) {
      // Defined by the QuickTime File Format specification.
      mimeType = MimeTypes.APPLICATION_MP4CEA608;
      out.requiredSampleTransformation = Track.TRANSFORMATION_CEA608_CDAT;
    } else if (atomType == Mp4Box.TYPE_mp4s) {
      int pos = parent.getPosition();
      parent.skipBytes(4); // child atom size
      int childAtomType = parent.readInt();
      if (childAtomType == Mp4Box.TYPE_esds) {
        EsdsData esds = parseEsdsFromParent(parent, pos);
        if (esds.initializationData == null || esds.initializationData.length != 64) {
          return;
        }
        mimeType = MimeTypes.APPLICATION_VOBSUB;
        String idx = formatVobsubIdx(esds.initializationData, tkhdData.width, tkhdData.height);
        initializationData = ImmutableList.of(Util.getUtf8Bytes(idx));
      }
    } else {
      // Never happens.
      throw new IllegalStateException();
    }

    if (mimeType != null) {
      out.format =
          new Format.Builder()
              .setId(tkhdData.id)
              .setSampleMimeType(mimeType)
              .setLanguage(language)
              .setSubsampleOffsetUs(subsampleOffsetUs)
              .setInitializationData(initializationData)
              .build();
    }
  }

  /**
   * Format {@link EsdsData#initializationData} as a VobSub IDX string for consumption by {@link
   * VobsubParser}.
   */
  private static String formatVobsubIdx(byte[] src, int width, int height) {
    checkState(src.length == 64);
    List<String> palette = new ArrayList<>(16);
    for (int i = 0; i < src.length - 3; i += 4) {
      int yuv = Ints.fromBytes(src[i], src[i + 1], src[i + 2], src[i + 3]);
      palette.add(String.format("%06x", vobsubYuvToRgb(yuv)));
    }
    return "size: " + width + "x" + height + "\npalette: " + Joiner.on(", ").join(palette) + "\n";
  }

  /**
   * Convert a VobSub YUV palette color (as stored in {@link EsdsData#initializationData}) to RGB
   * (as consumed by {@link VobsubParser}).
   *
   * <p>This uses conversion coefficients derived from BT.601.
   */
  private static int vobsubYuvToRgb(int yuv) {
    int y = (yuv >> 16) & 0xFF;
    int v = (yuv >> 8) & 0xFF;
    int u = yuv & 0xFF;

    int r = y + 14075 * (v - 128) / 10000;
    int g = y - 3455 * (u - 128) / 10000 - 7169 * (v - 128) / 10000;
    int b = y + 17790 * (u - 128) / 10000;

    return (Util.constrainValue(r, 0, 255) << 16)
        | (Util.constrainValue(g, 0, 255) << 8)
        | Util.constrainValue(b, 0, 255);
  }

  // hdrStaticInfo is allocated using allocate() in allocateHdrStaticInfo().
  @SuppressWarnings("ByteBufferBackingArray")
  private static void parseVideoSampleEntry(
      ParsableByteArray parent,
      int atomType,
      int position,
      int size,
      int trackId,
      @Nullable String language,
      int rotationDegrees,
      @Nullable DrmInitData drmInitData,
      StsdData out,
      int entryIndex)
      throws ParserException {
    parent.setPosition(position + Mp4Box.HEADER_SIZE + StsdData.STSD_HEADER_SIZE);

    parent.skipBytes(16);
    int width = parent.readUnsignedShort();
    int height = parent.readUnsignedShort();
    boolean pixelWidthHeightRatioFromPasp = false;
    float pixelWidthHeightRatio = 1;
    // Set default luma and chroma bit depths to 8 as old codecs might not even signal them
    int bitdepthLuma = 8;
    int bitdepthChroma = 8;
    parent.skipBytes(50);

    int childPosition = parent.getPosition();
    if (atomType == Mp4Box.TYPE_encv) {
      @Nullable
      Pair<Integer, TrackEncryptionBox> sampleEntryEncryptionData =
          parseSampleEntryEncryptionData(parent, position, size);
      if (sampleEntryEncryptionData != null) {
        atomType = sampleEntryEncryptionData.first;
        drmInitData =
            drmInitData == null
                ? null
                : drmInitData.copyWithSchemeType(sampleEntryEncryptionData.second.schemeType);
        out.trackEncryptionBoxes[entryIndex] = sampleEntryEncryptionData.second;
      }
      parent.setPosition(childPosition);
    }
    // TODO: Uncomment when [Internal: b/63092960] is fixed.
    // else {
    //   drmInitData = null;
    // }

    @Nullable String mimeType = null;
    if (atomType == Mp4Box.TYPE_m1v_) {
      mimeType = MimeTypes.VIDEO_MPEG;
    } else if (atomType == Mp4Box.TYPE_H263) {
      mimeType = MimeTypes.VIDEO_H263;
    }

    @Nullable List<byte[]> initializationData = null;
    @Nullable String codecs = null;
    @Nullable byte[] projectionData = null;
    @C.StereoMode int stereoMode = Format.NO_VALUE;
    @Nullable EsdsData esdsData = null;
    @Nullable BtrtData btrtData = null;
    int maxNumReorderSamples = Format.NO_VALUE;
    int maxSubLayers = Format.NO_VALUE;
    @Nullable NalUnitUtil.H265VpsData vpsData = null;
    int decodedWidth = Format.NO_VALUE;
    int decodedHeight = Format.NO_VALUE;

    // HDR related metadata.
    @C.ColorSpace int colorSpace = Format.NO_VALUE;
    @C.ColorRange int colorRange = Format.NO_VALUE;
    @C.ColorTransfer int colorTransfer = Format.NO_VALUE;
    // The format of HDR static info is defined in CTA-861-G:2017, Table 45.
    @Nullable ByteBuffer hdrStaticInfo = null;

    while (childPosition - position < size) {
      parent.setPosition(childPosition);
      int childStartPosition = parent.getPosition();
      int childAtomSize = parent.readInt();
      if (childAtomSize == 0 && parent.getPosition() - position == size) {
        // Handle optional terminating four zero bytes in MOV files.
        break;
      }
      ExtractorUtil.checkContainerInput(childAtomSize > 0, "childAtomSize must be positive");
      int childAtomType = parent.readInt();
      if (childAtomType == Mp4Box.TYPE_avcC) {
        ExtractorUtil.checkContainerInput(mimeType == null, /* message= */ null);
        mimeType = MimeTypes.VIDEO_H264;
        parent.setPosition(childStartPosition + Mp4Box.HEADER_SIZE);
        AvcConfig avcConfig = AvcConfig.parse(parent);
        initializationData = avcConfig.initializationData;
        out.nalUnitLengthFieldLength = avcConfig.nalUnitLengthFieldLength;
        if (!pixelWidthHeightRatioFromPasp) {
          pixelWidthHeightRatio = avcConfig.pixelWidthHeightRatio;
        }
        codecs = avcConfig.codecs;
        maxNumReorderSamples = avcConfig.maxNumReorderFrames;
        colorSpace = avcConfig.colorSpace;
        colorRange = avcConfig.colorRange;
        colorTransfer = avcConfig.colorTransfer;
        bitdepthLuma = avcConfig.bitdepthLuma;
        bitdepthChroma = avcConfig.bitdepthChroma;
      } else if (childAtomType == Mp4Box.TYPE_hvcC) {
        ExtractorUtil.checkContainerInput(mimeType == null, /* message= */ null);
        mimeType = MimeTypes.VIDEO_H265;
        parent.setPosition(childStartPosition + Mp4Box.HEADER_SIZE);
        HevcConfig hevcConfig = HevcConfig.parse(parent);
        initializationData = hevcConfig.initializationData;
        out.nalUnitLengthFieldLength = hevcConfig.nalUnitLengthFieldLength;
        if (!pixelWidthHeightRatioFromPasp) {
          pixelWidthHeightRatio = hevcConfig.pixelWidthHeightRatio;
        }
        maxNumReorderSamples = hevcConfig.maxNumReorderPics;
        maxSubLayers = hevcConfig.maxSubLayers;
        codecs = hevcConfig.codecs;
        if (hevcConfig.stereoMode != Format.NO_VALUE) {
          // HEVCDecoderConfigurationRecord may include 3D reference displays information SEI.
          stereoMode = hevcConfig.stereoMode;
        }
        decodedWidth = hevcConfig.decodedWidth;
        decodedHeight = hevcConfig.decodedHeight;
        colorSpace = hevcConfig.colorSpace;
        colorRange = hevcConfig.colorRange;
        colorTransfer = hevcConfig.colorTransfer;
        bitdepthLuma = hevcConfig.bitdepthLuma;
        bitdepthChroma = hevcConfig.bitdepthChroma;
        vpsData = hevcConfig.vpsData;
      } else if (childAtomType == Mp4Box.TYPE_lhvC) {
        // The lhvC atom must follow the hvcC atom; so the media type must be already set.
        ExtractorUtil.checkContainerInput(
            MimeTypes.VIDEO_H265.equals(mimeType), "lhvC must follow hvcC atom");
        ExtractorUtil.checkContainerInput(
            vpsData != null && vpsData.layerInfos.size() >= 2, "must have at least two layers");

        parent.setPosition(childStartPosition + Mp4Box.HEADER_SIZE);
        HevcConfig lhevcConfig = HevcConfig.parseLayered(parent, checkNotNull(vpsData));
        ExtractorUtil.checkContainerInput(
            out.nalUnitLengthFieldLength == lhevcConfig.nalUnitLengthFieldLength,
            "nalUnitLengthFieldLength must be same for both hvcC and lhvC atoms");

        // Only stereo MV-HEVC is currently supported, for which both views must have the same below
        // configuration values.
        if (lhevcConfig.colorSpace != Format.NO_VALUE) {
          ExtractorUtil.checkContainerInput(
              colorSpace == lhevcConfig.colorSpace, "colorSpace must be the same for both views");
        }
        if (lhevcConfig.colorRange != Format.NO_VALUE) {
          ExtractorUtil.checkContainerInput(
              colorRange == lhevcConfig.colorRange, "colorRange must be the same for both views");
        }
        if (lhevcConfig.colorTransfer != Format.NO_VALUE) {
          ExtractorUtil.checkContainerInput(
              colorTransfer == lhevcConfig.colorTransfer,
              "colorTransfer must be the same for both views");
        }
        ExtractorUtil.checkContainerInput(
            bitdepthLuma == lhevcConfig.bitdepthLuma,
            "bitdepthLuma must be the same for both views");
        ExtractorUtil.checkContainerInput(
            bitdepthChroma == lhevcConfig.bitdepthChroma,
            "bitdepthChroma must be the same for both views");

        mimeType = MimeTypes.VIDEO_MV_HEVC;
        if (initializationData != null) {
          initializationData =
              ImmutableList.<byte[]>builder()
                  .addAll(initializationData)
                  .addAll(lhevcConfig.initializationData)
                  .build();
        } else {
          ExtractorUtil.checkContainerInput(
              false, "initializationData must be already set from hvcC atom");
        }
        codecs = lhevcConfig.codecs;
      } else if (childAtomType == Mp4Box.TYPE_vexu) {
        VexuData vexuData = parseVideoExtendedUsageBox(parent, childStartPosition, childAtomSize);
        if (vexuData != null && vexuData.eyesData != null) {
          if (vpsData != null && vpsData.layerInfos.size() >= 2) {
            // This is MV-HEVC case, so both eye views should be marked as available.
            ExtractorUtil.checkContainerInput(
                vexuData.hasBothEyeViews(), "both eye views must be marked as available");
            // Based on subsection 1.4.3 of Apple’s proposed ISOBMFF extensions for stereo video
            // (https://developer.apple.com/av-foundation/Stereo-Video-ISOBMFF-Extensions.pdf):
            // "For multiview coding, there is no implied ordering and the eye_views_reversed field
            // should be set to 0".
            ExtractorUtil.checkContainerInput(
                !vexuData.eyesData.striData.eyeViewsReversed,
                "for MV-HEVC, eye_views_reversed must be set to false");
          } else if (stereoMode == Format.NO_VALUE) {
            stereoMode =
                vexuData.eyesData.striData.eyeViewsReversed
                    ? C.STEREO_MODE_INTERLEAVED_RIGHT_PRIMARY
                    : C.STEREO_MODE_INTERLEAVED_LEFT_PRIMARY;
          }
        }
      } else if (childAtomType == Mp4Box.TYPE_dvcC
          || childAtomType == Mp4Box.TYPE_dvvC
          || childAtomType == Mp4Box.TYPE_dvwC) {
        int childAtomBodySize = childAtomSize - Mp4Box.HEADER_SIZE;
        byte[] initializationDataChunk = new byte[childAtomBodySize];
        parent.readBytes(initializationDataChunk, /* offset= */ 0, childAtomBodySize);
        // Add the initialization data of Dolby Vision to the existing list of initialization data.
        if (initializationData != null) {
          initializationData =
              ImmutableList.<byte[]>builder()
                  .addAll(initializationData)
                  .add(initializationDataChunk)
                  .build();
        } else {
          ExtractorUtil.checkContainerInput(
              false, "initializationData must already be set from hvcC or avcC atom");
        }
        parent.setPosition(childStartPosition + Mp4Box.HEADER_SIZE);
        @Nullable DolbyVisionConfig dolbyVisionConfig = DolbyVisionConfig.parse(parent);
        if (dolbyVisionConfig != null) {
          codecs = dolbyVisionConfig.codecs;
          mimeType = MimeTypes.VIDEO_DOLBY_VISION;
        }
      } else if (childAtomType == Mp4Box.TYPE_vpcC) {
        ExtractorUtil.checkContainerInput(mimeType == null, /* message= */ null);
        mimeType = (atomType == Mp4Box.TYPE_vp08) ? MimeTypes.VIDEO_VP8 : MimeTypes.VIDEO_VP9;
        parent.setPosition(childStartPosition + Mp4Box.FULL_HEADER_SIZE);
        // See vpcC atom syntax: https://www.webmproject.org/vp9/mp4/#syntax_1
        byte profile = (byte) parent.readUnsignedByte();
        byte level = (byte) parent.readUnsignedByte();
        int byte3 = parent.readUnsignedByte();
        bitdepthLuma = byte3 >> 4;
        bitdepthChroma = bitdepthLuma;
        byte chromaSubsampling = (byte) ((byte3 >> 1) & 0b111);
        if (mimeType.equals(MimeTypes.VIDEO_VP9)) {
          // CSD should be in CodecPrivate format according to VP9 Codec spec.
          initializationData =
              CodecSpecificDataUtil.buildVp9CodecPrivateInitializationData(
                  profile, level, (byte) bitdepthLuma, chromaSubsampling);
        }
        boolean fullRangeFlag = (byte3 & 0b1) != 0;
        int colorPrimaries = parent.readUnsignedByte();
        int transferCharacteristics = parent.readUnsignedByte();
        colorSpace = ColorInfo.isoColorPrimariesToColorSpace(colorPrimaries);
        colorRange = fullRangeFlag ? C.COLOR_RANGE_FULL : C.COLOR_RANGE_LIMITED;
        colorTransfer =
            ColorInfo.isoTransferCharacteristicsToColorTransfer(transferCharacteristics);
      } else if (childAtomType == Mp4Box.TYPE_av1C) {
        mimeType = MimeTypes.VIDEO_AV1;

        int childAtomBodySize = childAtomSize - Mp4Box.HEADER_SIZE;
        byte[] initializationDataChunk = new byte[childAtomBodySize];
        parent.readBytes(initializationDataChunk, /* offset= */ 0, childAtomBodySize);
        initializationData = ImmutableList.of(initializationDataChunk);

        parent.setPosition(childStartPosition + Mp4Box.HEADER_SIZE);
        ColorInfo colorInfo = parseAv1c(parent);

        bitdepthLuma = colorInfo.lumaBitdepth;
        bitdepthChroma = colorInfo.chromaBitdepth;
        colorSpace = colorInfo.colorSpace;
        colorRange = colorInfo.colorRange;
        colorTransfer = colorInfo.colorTransfer;
      } else if (childAtomType == Mp4Box.TYPE_clli) {
        if (hdrStaticInfo == null) {
          hdrStaticInfo = allocateHdrStaticInfo();
        }
        // The contents of the clli box occupy the last 4 bytes of the HDR static info array. Note
        // that each field is read in big endian and written in little endian.
        hdrStaticInfo.position(21);
        hdrStaticInfo.putShort(parent.readShort()); // max_content_light_level.
        hdrStaticInfo.putShort(parent.readShort()); // max_pic_average_light_level.
      } else if (childAtomType == Mp4Box.TYPE_mdcv) {
        if (hdrStaticInfo == null) {
          hdrStaticInfo = allocateHdrStaticInfo();
        }
        // The contents of the mdcv box occupy 20 bytes after the first byte of the HDR static info
        // array. Note that each field is read in big endian and written in little endian.
        short displayPrimariesGX = parent.readShort();
        short displayPrimariesGY = parent.readShort();
        short displayPrimariesBX = parent.readShort();
        short displayPrimariesBY = parent.readShort();
        short displayPrimariesRX = parent.readShort();
        short displayPrimariesRY = parent.readShort();
        short whitePointX = parent.readShort();
        short whitePointY = parent.readShort();
        long maxDisplayMasteringLuminance = parent.readUnsignedInt();
        long minDisplayMasteringLuminance = parent.readUnsignedInt();

        hdrStaticInfo.position(1);
        hdrStaticInfo.putShort(displayPrimariesRX);
        hdrStaticInfo.putShort(displayPrimariesRY);
        hdrStaticInfo.putShort(displayPrimariesGX);
        hdrStaticInfo.putShort(displayPrimariesGY);
        hdrStaticInfo.putShort(displayPrimariesBX);
        hdrStaticInfo.putShort(displayPrimariesBY);
        hdrStaticInfo.putShort(whitePointX);
        hdrStaticInfo.putShort(whitePointY);
        hdrStaticInfo.putShort((short) (maxDisplayMasteringLuminance / 10000));
        hdrStaticInfo.putShort((short) (minDisplayMasteringLuminance / 10000));
      } else if (childAtomType == Mp4Box.TYPE_d263) {
        ExtractorUtil.checkContainerInput(mimeType == null, /* message= */ null);
        mimeType = MimeTypes.VIDEO_H263;
      } else if (childAtomType == Mp4Box.TYPE_esds) {
        ExtractorUtil.checkContainerInput(mimeType == null, /* message= */ null);
        esdsData = parseEsdsFromParent(parent, childStartPosition);
        mimeType = esdsData.mimeType;
        @Nullable byte[] initializationDataBytes = esdsData.initializationData;
        if (initializationDataBytes != null) {
          initializationData = ImmutableList.of(initializationDataBytes);
        }
      } else if (childAtomType == Mp4Box.TYPE_btrt) {
        btrtData = parseBtrtFromParent(parent, childStartPosition);
      } else if (childAtomType == Mp4Box.TYPE_pasp) {
        pixelWidthHeightRatio = parsePaspFromParent(parent, childStartPosition);
        pixelWidthHeightRatioFromPasp = true;
      } else if (childAtomType == Mp4Box.TYPE_sv3d) {
        projectionData = parseProjFromParent(parent, childStartPosition, childAtomSize);
      } else if (childAtomType == Mp4Box.TYPE_st3d) {
        int version = parent.readUnsignedByte();
        parent.skipBytes(3); // Flags.
        if (version == 0) {
          int layout = parent.readUnsignedByte();
          switch (layout) {
            case 0:
              stereoMode = C.STEREO_MODE_MONO;
              break;
            case 1:
              stereoMode = C.STEREO_MODE_TOP_BOTTOM;
              break;
            case 2:
              stereoMode = C.STEREO_MODE_LEFT_RIGHT;
              break;
            case 3:
              stereoMode = C.STEREO_MODE_STEREO_MESH;
              break;
            default:
              break;
          }
        }
      } else if (childAtomType == Mp4Box.TYPE_apvC) {
        mimeType = MimeTypes.VIDEO_APV;

        int childAtomBodySize = childAtomSize - Mp4Box.FULL_HEADER_SIZE;
        byte[] initializationDataChunk = new byte[childAtomBodySize];
        parent.setPosition(childStartPosition + Mp4Box.FULL_HEADER_SIZE); // Skip version and flags.
        parent.readBytes(initializationDataChunk, /* offset= */ 0, childAtomBodySize);
        codecs = CodecSpecificDataUtil.buildApvCodecString(initializationDataChunk);
        initializationData = ImmutableList.of(initializationDataChunk);

        ColorInfo colorInfo = parseApvc(new ParsableByteArray(initializationDataChunk));

        bitdepthLuma = colorInfo.lumaBitdepth;
        bitdepthChroma = colorInfo.chromaBitdepth;
        colorSpace = colorInfo.colorSpace;
        colorRange = colorInfo.colorRange;
        colorTransfer = colorInfo.colorTransfer;
      } else if (childAtomType == Mp4Box.TYPE_colr) {
        // Only modify these values if 'colorSpace' and 'colorTransfer' have not been previously
        // established by the bitstream. The absence of color descriptors ('colorSpace' and
        // 'colorTransfer') does not necessarily mean that 'colorRange' has default values, hence it
        // is not being verified here.
        // If 'Atom.TYPE_avcC', 'Atom.TYPE_hvcC', 'Atom.TYPE_vpcC' or 'Atom.TYPE_av1c' is available,
        // they will take precedence and overwrite any existing values.
        if (colorSpace == Format.NO_VALUE && colorTransfer == Format.NO_VALUE) {
          int colorType = parent.readInt();
          if (colorType == TYPE_nclx || colorType == TYPE_nclc) {
            // For more info on syntax, see Section 8.5.2.2 in ISO/IEC 14496-12:2012(E) and
            // https://developer.apple.com/library/archive/documentation/QuickTime/QTFF/QTFFChap3/qtff3.html.
            int colorPrimaries = parent.readUnsignedShort();
            int transferCharacteristics = parent.readUnsignedShort();
            parent.skipBytes(2); // matrix_coefficients.

            // Only try and read full_range_flag if the box is long enough. It should be present in
            // all colr boxes with type=nclx (Section 8.5.2.2 in ISO/IEC 14496-12:2012(E)) but some
            // device cameras record videos with type=nclx without this final flag (and therefore
            // size=18): https://github.com/google/ExoPlayer/issues/9332
            boolean fullRangeFlag =
                childAtomSize == 19 && (parent.readUnsignedByte() & 0b10000000) != 0;
            colorSpace = ColorInfo.isoColorPrimariesToColorSpace(colorPrimaries);
            colorRange = fullRangeFlag ? C.COLOR_RANGE_FULL : C.COLOR_RANGE_LIMITED;
            colorTransfer =
                ColorInfo.isoTransferCharacteristicsToColorTransfer(transferCharacteristics);
          } else {
            Log.w(TAG, "Unsupported color type: " + Mp4Box.getBoxTypeString(colorType));
          }
        }
      }
      childPosition += childAtomSize;
    }

    // If the media type was not recognized, ignore the track.
    if (mimeType == null) {
      return;
    }

    Format.Builder formatBuilder =
        new Format.Builder()
            .setId(trackId)
            .setSampleMimeType(mimeType)
            .setCodecs(codecs)
            .setWidth(width)
            .setHeight(height)
            .setDecodedWidth(decodedWidth)
            .setDecodedHeight(decodedHeight)
            .setPixelWidthHeightRatio(pixelWidthHeightRatio)
            .setRotationDegrees(rotationDegrees)
            .setProjectionData(projectionData)
            .setStereoMode(stereoMode)
            .setInitializationData(initializationData)
            .setMaxNumReorderSamples(maxNumReorderSamples)
            .setMaxSubLayers(maxSubLayers)
            .setDrmInitData(drmInitData)
            .setLanguage(language)
            // Note that if either mdcv or clli are missing, we leave the corresponding HDR static
            // metadata bytes with value zero. See [Internal ref: b/194535665].
            .setColorInfo(
                new ColorInfo.Builder()
                    .setColorSpace(colorSpace)
                    .setColorRange(colorRange)
                    .setColorTransfer(colorTransfer)
                    .setHdrStaticInfo(hdrStaticInfo != null ? hdrStaticInfo.array() : null)
                    .setLumaBitdepth(bitdepthLuma)
                    .setChromaBitdepth(bitdepthChroma)
                    .build());

    // Prefer btrtData over esdsData for video track.
    if (btrtData != null) {
      formatBuilder
          .setAverageBitrate(Ints.saturatedCast(btrtData.avgBitrate))
          .setPeakBitrate(Ints.saturatedCast(btrtData.maxBitrate));
    } else if (esdsData != null) {
      formatBuilder
          .setAverageBitrate(Ints.saturatedCast(esdsData.bitrate))
          .setPeakBitrate(Ints.saturatedCast(esdsData.peakBitrate));
    }

    out.format = formatBuilder.build();
  }

  /**
   * Parses the av1C configuration record and OBU sequence header and returns a {@link ColorInfo}
   * from their data.
   *
   * <p>See av1C configuration record syntax in this <a
   * href="https://aomediacodec.github.io/av1-isobmff/#av1codecconfigurationbox-syntax">spec</a>.
   *
   * <p>See av1C OBU syntax in this <a
   * href="https://aomediacodec.github.io/av1-spec/av1-spec.pdf">spec</a>.
   *
   * <p>The sections referenced in the method are from these specs.
   *
   * @param data The av1C atom data.
   * @return {@link ColorInfo} parsed from the av1C data.
   */
  private static ColorInfo parseAv1c(ParsableByteArray data) {
    ColorInfo.Builder colorInfo = new ColorInfo.Builder();
    ParsableBitArray bitArray = new ParsableBitArray(data.getData());
    bitArray.setPosition(data.getPosition() * 8); // Convert byte to bit position.

    // Parse av1C config record for bitdepth info.
    // See https://aomediacodec.github.io/av1-isobmff/#av1codecconfigurationbox-syntax.
    bitArray.skipBytes(1); // marker, version
    int seqProfile = bitArray.readBits(3); // seq_profile
    bitArray.skipBits(6); // seq_level_idx_0, seq_tier_0
    boolean highBitdepth = bitArray.readBit(); // high_bitdepth
    boolean twelveBit = bitArray.readBit(); // twelve_bit
    if (seqProfile == 2 && highBitdepth) {
      colorInfo.setLumaBitdepth(twelveBit ? 12 : 10);
      colorInfo.setChromaBitdepth(twelveBit ? 12 : 10);
    } else if (seqProfile <= 2) {
      colorInfo.setLumaBitdepth(highBitdepth ? 10 : 8);
      colorInfo.setChromaBitdepth(highBitdepth ? 10 : 8);
    }
    // Skip monochrome, chroma_subsampling_x, chroma_subsampling_y, chroma_sample_position,
    // reserved and initial_presentation_delay.
    bitArray.skipBits(13);

    // 5.3.1. General OBU syntax
    bitArray.skipBit(); // obu_forbidden_bit
    int obuType = bitArray.readBits(4); // obu_type
    if (obuType != 1) { // obu_type != OBU_SEQUENCE_HEADER
      Log.i(TAG, "Unsupported obu_type: " + obuType);
      return colorInfo.build();
    }
    if (bitArray.readBit()) { // obu_extension_flag
      Log.i(TAG, "Unsupported obu_extension_flag");
      return colorInfo.build();
    }
    boolean obuHasSizeField = bitArray.readBit(); // obu_has_size_field
    bitArray.skipBit(); // obu_reserved_1bit
    // obu_size is unsigned leb128 and if obu_size <= 127 then it can be simplified as readBits(8).
    if (obuHasSizeField && bitArray.readBits(8) > 127) { // obu_size
      Log.i(TAG, "Excessive obu_size");
      return colorInfo.build();
    }
    // 5.5.1. General OBU sequence header syntax
    int obuSeqHeaderSeqProfile = bitArray.readBits(3); // seq_profile
    bitArray.skipBit(); // still_picture
    if (bitArray.readBit()) { // reduced_still_picture_header
      Log.i(TAG, "Unsupported reduced_still_picture_header");
      return colorInfo.build();
    }
    if (bitArray.readBit()) { // timing_info_present_flag
      Log.i(TAG, "Unsupported timing_info_present_flag");
      return colorInfo.build();
    }
    if (bitArray.readBit()) { // initial_display_delay_present_flag
      Log.i(TAG, "Unsupported initial_display_delay_present_flag");
      return colorInfo.build();
    }
    int operatingPointsCountMinus1 = bitArray.readBits(5); // operating_points_cnt_minus_1
    for (int i = 0; i <= operatingPointsCountMinus1; i++) {
      bitArray.skipBits(12); // operating_point_idc[i]
      int seqLevelIdx = bitArray.readBits(5); // seq_level_idx[i]
      if (seqLevelIdx > 7) {
        bitArray.skipBit(); // seq_tier[i]
      }
    }
    int frameWidthBitsMinus1 = bitArray.readBits(4); // frame_width_bits_minus_1
    int frameHeightBitsMinus1 = bitArray.readBits(4); // frame_height_bits_minus_1
    bitArray.skipBits(frameWidthBitsMinus1 + 1); // max_frame_width_minus_1
    bitArray.skipBits(frameHeightBitsMinus1 + 1); // max_frame_height_minus_1
    if (bitArray.readBit()) { // frame_id_numbers_present_flag
      bitArray.skipBits(7); // delta_frame_id_length_minus_2, additional_frame_id_length_minus_1
    }
    bitArray.skipBits(7); // use_128x128_superblock...enable_dual_filter: 7 flags
    boolean enableOrderHint = bitArray.readBit(); // enable_order_hint
    if (enableOrderHint) {
      bitArray.skipBits(2); // enable_jnt_comp, enable_ref_frame_mvs
    }
    int seqForceScreenContentTools =
        bitArray.readBit() // seq_choose_screen_content_tools
            ? 2 // SELECT_SCREEN_CONTENT_TOOLS
            : bitArray.readBits(1); // seq_force_screen_content_tools
    if (seqForceScreenContentTools > 0) {
      if (!bitArray.readBit()) { // seq_choose_integer_mv
        bitArray.skipBits(1); // seq_force_integer_mv
      }
    }
    if (enableOrderHint) {
      bitArray.skipBits(3); // order_hint_bits_minus_1
    }
    bitArray.skipBits(3); // enable_superres, enable_cdef, enable_restoration
    // 5.5.2. OBU Color config syntax
    boolean colorConfigHighBitdepth = bitArray.readBit(); // high_bitdepth
    if (obuSeqHeaderSeqProfile == 2 && colorConfigHighBitdepth) {
      bitArray.skipBit(); // twelve_bit
    }

    boolean monochrome = (obuSeqHeaderSeqProfile != 1) && bitArray.readBit(); // mono_chrome

    if (bitArray.readBit()) { // color_description_present_flag
      int colorPrimaries = bitArray.readBits(8); // color_primaries
      int transferCharacteristics = bitArray.readBits(8); // transfer_characteristics
      int matrixCoefficients = bitArray.readBits(8); // matrix_coefficients
      int colorRange =
          (!monochrome
                  && colorPrimaries == 1 // CP_BT_709
                  && transferCharacteristics == 13 // TC_SRGB
                  && matrixCoefficients == 0) // MC_IDENTITY
              ? 1
              : bitArray.readBits(1); // color_range;
      colorInfo
          .setColorSpace(ColorInfo.isoColorPrimariesToColorSpace(colorPrimaries))
          .setColorRange((colorRange == 1) ? C.COLOR_RANGE_FULL : C.COLOR_RANGE_LIMITED)
          .setColorTransfer(
              ColorInfo.isoTransferCharacteristicsToColorTransfer(transferCharacteristics));
    }
    return colorInfo.build();
  }

  /**
   * Parses the apvC configuration record and returns a {@link ColorInfo} from its data.
   *
   * <p>See apvC configuration record syntax from the <a
   * href="https://github.com/openapv/openapv/blob/main/readme/apv_isobmff.md#syntax-1">spec</a>.
   *
   * <p>The sections referenced in the method are from this spec.
   *
   * @param data The apvC atom data.
   * @return {@link ColorInfo} parsed from the apvC data.
   */
  private static ColorInfo parseApvc(ParsableByteArray data) {
    ColorInfo.Builder colorInfo = new ColorInfo.Builder();
    ParsableBitArray bitArray = new ParsableBitArray(data.getData());
    bitArray.setPosition(data.getPosition() * 8); // Convert byte to bit position.
    // See APVDecoderConfigurationRecord syntax.
    bitArray.skipBytes(1); // configurationVersion
    int numConfigurationEntries = bitArray.readBits(8); // number_of_configuration_entry
    for (int i = 0; i < numConfigurationEntries; i++) {
      bitArray.skipBytes(1); // pbu_type
      int numberOfFrameInfo = bitArray.readBits(8);
      for (int j = 0; j < numberOfFrameInfo; j++) {
        bitArray.skipBits(6); // reserved_zero_6bits
        boolean isColorDescriptionPresent =
            bitArray.readBit(); // color_description_present_flag_info
        bitArray.skipBit(); // capture_time_distance_ignored
        // Skip profile_idc (1 byte), level_idc (1 byte), band_idc (1 byte), frame_width (4 bytes),
        // frame_height (4 bytes).
        bitArray.skipBytes(11);
        bitArray.skipBits(4); // chroma_format_idc (4 bits)
        int bitDepth = bitArray.readBits(4) + 8; // bit_depth_minus8 + 8
        colorInfo.setLumaBitdepth(bitDepth);
        colorInfo.setChromaBitdepth(bitDepth);
        bitArray.skipBytes(1); // capture_time_distance
        if (isColorDescriptionPresent) {
          int colorPrimaries = bitArray.readBits(8); // color_primaries
          int transferCharacteristics = bitArray.readBits(8); // transfer_characteristics
          bitArray.skipBytes(1); // matrix_coefficients
          boolean fullRangeFlag = bitArray.readBit(); // full_range_flag
          colorInfo
              .setColorSpace(ColorInfo.isoColorPrimariesToColorSpace(colorPrimaries))
              .setColorRange(fullRangeFlag ? C.COLOR_RANGE_FULL : C.COLOR_RANGE_LIMITED)
              .setColorTransfer(
                  ColorInfo.isoTransferCharacteristicsToColorTransfer(transferCharacteristics));
        }
      }
    }
    return colorInfo.build();
  }

  private static ByteBuffer allocateHdrStaticInfo() {
    // For HDR static info, Android decoders expect a 25-byte array. The first byte is zero to
    // represent Static Metadata Type 1, as per CTA-861-G:2017, Table 44. The following 24 bytes
    // follow CTA-861-G:2017, Table 45.
    return ByteBuffer.allocate(25).order(ByteOrder.LITTLE_ENDIAN);
  }

  private static void parseMetaDataSampleEntry(
      ParsableByteArray parent, int atomType, int position, int trackId, StsdData out) {
    parent.setPosition(position + Mp4Box.HEADER_SIZE + StsdData.STSD_HEADER_SIZE);
    if (atomType == Mp4Box.TYPE_mett) {
      parent.readNullTerminatedString(); // Skip optional content_encoding
      @Nullable String mimeType = parent.readNullTerminatedString();
      if (mimeType != null) {
        out.format = new Format.Builder().setId(trackId).setSampleMimeType(mimeType).build();
      }
    }
  }

  /**
   * Parses the edts atom (defined in ISO/IEC 14496-12 subsection 8.6.5).
   *
   * @param edtsAtom edts (edit box) atom to decode.
   * @return Pair of edit list durations and edit list media times, or {@code null} if they are not
   *     present.
   */
  @Nullable
  private static Pair<long[], long[]> parseEdts(Mp4Box.ContainerBox edtsAtom) {
    @Nullable LeafBox elstAtom = edtsAtom.getLeafBoxOfType(Mp4Box.TYPE_elst);
    if (elstAtom == null) {
      return null;
    }
    ParsableByteArray elstData = elstAtom.data;
    elstData.setPosition(Mp4Box.HEADER_SIZE);
    int fullAtom = elstData.readInt();
    int version = parseFullBoxVersion(fullAtom);
    int entryCount = elstData.readUnsignedIntToInt();
    long[] editListDurations = new long[entryCount];
    long[] editListMediaTimes = new long[entryCount];
    for (int i = 0; i < entryCount; i++) {
      editListDurations[i] =
          version == 1 ? elstData.readUnsignedLongToLong() : elstData.readUnsignedInt();
      editListMediaTimes[i] = version == 1 ? elstData.readLong() : elstData.readInt();
      int mediaRateInteger = elstData.readShort();
      if (mediaRateInteger != 1) {
        // The extractor does not handle dwell edits (mediaRateInteger == 0).
        throw new IllegalArgumentException("Unsupported media rate.");
      }
      elstData.skipBytes(2);
    }
    return Pair.create(editListDurations, editListMediaTimes);
  }

  private static float parsePaspFromParent(ParsableByteArray parent, int position) {
    parent.setPosition(position + Mp4Box.HEADER_SIZE);
    int hSpacing = parent.readUnsignedIntToInt();
    int vSpacing = parent.readUnsignedIntToInt();
    return (float) hSpacing / vSpacing;
  }

  private static void parseAudioSampleEntry(
      ParsableByteArray parent,
      int atomType,
      int position,
      int size,
      int trackId,
      @Nullable String language,
      boolean isQuickTime,
      @Nullable DrmInitData drmInitData,
      StsdData out,
      int entryIndex)
      throws ParserException {
    parent.setPosition(position + Mp4Box.HEADER_SIZE + StsdData.STSD_HEADER_SIZE);

    int quickTimeSoundDescriptionVersion = 0;
    if (isQuickTime) {
      quickTimeSoundDescriptionVersion = parent.readUnsignedShort();
      parent.skipBytes(6);
    } else {
      parent.skipBytes(8);
    }

    int channelCount;
    int sampleRate;
    int sampleRateMlp = 0;
    @C.PcmEncoding int pcmEncoding = Format.NO_VALUE;
    @Nullable String codecs = null;
    @Nullable EsdsData esdsData = null;
    @Nullable BtrtData btrtData = null;

    if (quickTimeSoundDescriptionVersion == 0 || quickTimeSoundDescriptionVersion == 1) {
      channelCount = parent.readUnsignedShort();
      parent.skipBytes(6); // sampleSize, compressionId, packetSize.

      sampleRate = parent.readUnsignedFixedPoint1616();
      // The sample rate has been redefined as a 32-bit value for Dolby TrueHD (MLP) streams.
      parent.setPosition(parent.getPosition() - 4);
      sampleRateMlp = parent.readInt();

      if (quickTimeSoundDescriptionVersion == 1) {
        parent.skipBytes(16);
      }
    } else if (quickTimeSoundDescriptionVersion == 2) {
      parent.skipBytes(16); // always[3,16,Minus2,0,65536], sizeOfStructOnly

      sampleRate = (int) Math.round(parent.readDouble());
      channelCount = parent.readUnsignedIntToInt();

      parent.skipBytes(4); // always7F000000
      int bitsPerSample = parent.readUnsignedIntToInt();
      int formatSpecificFlags = parent.readUnsignedIntToInt();
      boolean isFloat = (formatSpecificFlags & 1) != 0;
      boolean isBigEndian = (formatSpecificFlags & (1 << 1)) != 0;
      if (!isFloat) {
        if (bitsPerSample == 8) {
          pcmEncoding = C.ENCODING_PCM_8BIT;
        } else if (bitsPerSample == 16) {
          pcmEncoding = isBigEndian ? C.ENCODING_PCM_16BIT_BIG_ENDIAN : C.ENCODING_PCM_16BIT;
        } else if (bitsPerSample == 24) {
          pcmEncoding = isBigEndian ? C.ENCODING_PCM_24BIT_BIG_ENDIAN : C.ENCODING_PCM_24BIT;
        } else if (bitsPerSample == 32) {
          pcmEncoding = isBigEndian ? C.ENCODING_PCM_32BIT_BIG_ENDIAN : C.ENCODING_PCM_32BIT;
        }
      } else if (bitsPerSample == 32) {
        pcmEncoding = C.ENCODING_PCM_FLOAT;
      }
      parent.skipBytes(8); // constBytesPerAudioPacket, constLPCMFramesPerAudioPacket
    } else {
      // Unsupported version.
      return;
    }

    if (atomType == Mp4Box.TYPE_iamf) {
      // As per the IAMF spec (https://aomediacodec.github.io/iamf/#iasampleentry-section),
      // channelCount and sampleRate SHALL be set to 0 and ignored. We ignore it by using
      // Format.NO_VALUE instead of 0.
      channelCount = Format.NO_VALUE;
      sampleRate = Format.NO_VALUE;
    } else if (atomType == Mp4Box.TYPE_samr) {
      // AMR NB audio is always mono, 8kHz
      channelCount = 1;
      sampleRate = SAMPLE_RATE_AMR_NB;
    } else if (atomType == Mp4Box.TYPE_sawb) {
      // AMR WB audio is always mono, 16kHz
      channelCount = 1;
      sampleRate = SAMPLE_RATE_AMR_WB;
    }

    int childPosition = parent.getPosition();
    if (atomType == Mp4Box.TYPE_enca) {
      @Nullable
      Pair<Integer, TrackEncryptionBox> sampleEntryEncryptionData =
          parseSampleEntryEncryptionData(parent, position, size);
      if (sampleEntryEncryptionData != null) {
        atomType = sampleEntryEncryptionData.first;
        drmInitData =
            drmInitData == null
                ? null
                : drmInitData.copyWithSchemeType(sampleEntryEncryptionData.second.schemeType);
        out.trackEncryptionBoxes[entryIndex] = sampleEntryEncryptionData.second;
      }
      parent.setPosition(childPosition);
    }
    // TODO: Uncomment when [Internal: b/63092960] is fixed.
    // else {
    //   drmInitData = null;
    // }

    // If the atom type determines a MIME type, set it immediately.
    @Nullable String mimeType = null;
    if (atomType == Mp4Box.TYPE_ac_3) {
      mimeType = MimeTypes.AUDIO_AC3;
    } else if (atomType == Mp4Box.TYPE_ec_3) {
      mimeType = MimeTypes.AUDIO_E_AC3;
    } else if (atomType == Mp4Box.TYPE_ac_4) {
      mimeType = MimeTypes.AUDIO_AC4;
    } else if (atomType == Mp4Box.TYPE_dtsc) {
      mimeType = MimeTypes.AUDIO_DTS;
    } else if (atomType == Mp4Box.TYPE_dtsh || atomType == Mp4Box.TYPE_dtsl) {
      mimeType = MimeTypes.AUDIO_DTS_HD;
    } else if (atomType == Mp4Box.TYPE_dtse) {
      mimeType = MimeTypes.AUDIO_DTS_EXPRESS;
    } else if (atomType == Mp4Box.TYPE_dtsx) {
      mimeType = MimeTypes.AUDIO_DTS_X;
    } else if (atomType == Mp4Box.TYPE_samr) {
      mimeType = MimeTypes.AUDIO_AMR_NB;
    } else if (atomType == Mp4Box.TYPE_sawb) {
      mimeType = MimeTypes.AUDIO_AMR_WB;
    } else if (atomType == Mp4Box.TYPE_sowt) {
      mimeType = MimeTypes.AUDIO_RAW;
      pcmEncoding = C.ENCODING_PCM_16BIT;
    } else if (atomType == Mp4Box.TYPE_twos) {
      mimeType = MimeTypes.AUDIO_RAW;
      pcmEncoding = C.ENCODING_PCM_16BIT_BIG_ENDIAN;
    } else if (atomType == Mp4Box.TYPE_lpcm) {
      mimeType = MimeTypes.AUDIO_RAW;
      if (pcmEncoding == Format.NO_VALUE) {
        pcmEncoding = C.ENCODING_PCM_16BIT;
      }
    } else if (atomType == Mp4Box.TYPE__mp2 || atomType == Mp4Box.TYPE__mp3) {
      mimeType = MimeTypes.AUDIO_MPEG;
    } else if (atomType == Mp4Box.TYPE_mha1) {
      mimeType = MimeTypes.AUDIO_MPEGH_MHA1;
    } else if (atomType == Mp4Box.TYPE_mhm1) {
      mimeType = MimeTypes.AUDIO_MPEGH_MHM1;
    } else if (atomType == Mp4Box.TYPE_alac) {
      mimeType = MimeTypes.AUDIO_ALAC;
    } else if (atomType == Mp4Box.TYPE_alaw) {
      mimeType = MimeTypes.AUDIO_ALAW;
    } else if (atomType == Mp4Box.TYPE_ulaw) {
      mimeType = MimeTypes.AUDIO_MLAW;
    } else if (atomType == Mp4Box.TYPE_Opus) {
      mimeType = MimeTypes.AUDIO_OPUS;
    } else if (atomType == Mp4Box.TYPE_fLaC) {
      mimeType = MimeTypes.AUDIO_FLAC;
    } else if (atomType == Mp4Box.TYPE_mlpa) {
      mimeType = MimeTypes.AUDIO_TRUEHD;
    } else if (atomType == Mp4Box.TYPE_iamf) {
      mimeType = MimeTypes.AUDIO_IAMF;
    }

    @Nullable List<byte[]> initializationData = null;
    while (childPosition - position < size) {
      parent.setPosition(childPosition);
      int childAtomSize = parent.readInt();
      ExtractorUtil.checkContainerInput(childAtomSize > 0, "childAtomSize must be positive");
      int childAtomType = parent.readInt();
      if (childAtomType == Mp4Box.TYPE_mhaC) {
        // See ISO_IEC_23008-3;2022 MHADecoderConfigurationRecord
        // The header consists of: size (4), boxtype 'mhaC' (4), configurationVersion (1),
        // mpegh3daProfileLevelIndication (1), referenceChannelLayout (1), mpegh3daConfigLength (2).
        parent.setPosition(childPosition + Mp4Box.HEADER_SIZE);
        parent.skipBytes(1); // configurationVersion
        int mpeghProfileLevelIndication = parent.readUnsignedByte();
        parent.skipBytes(1); // mpeghReferenceChannelLayout
        codecs =
            Objects.equals(mimeType, MimeTypes.AUDIO_MPEGH_MHM1)
                ? String.format("mhm1.%02X", mpeghProfileLevelIndication)
                : String.format("mha1.%02X", mpeghProfileLevelIndication);
        int mpegh3daConfigLength = parent.readUnsignedShort();
        byte[] initializationDataBytes = new byte[mpegh3daConfigLength];
        parent.readBytes(initializationDataBytes, 0, mpegh3daConfigLength);
        // The mpegh3daConfig should always be the first entry in initializationData.
        if (initializationData == null) {
          initializationData = ImmutableList.of(initializationDataBytes);
        } else {
          // We assume that the mhaP box has been parsed before and so add the compatible profile
          // level sets as the second entry.
          initializationData = ImmutableList.of(initializationDataBytes, initializationData.get(0));
        }
      } else if (childAtomType == Mp4Box.TYPE_mhaP) {
        // See ISO_IEC_23008-3;2022 MHAProfileAndLevelCompatibilitySetBox
        // The header consists of: size (4), boxtype 'mhaP' (4), numCompatibleSets (1).
        parent.setPosition(childPosition + Mp4Box.HEADER_SIZE);
        int numCompatibleSets = parent.readUnsignedByte();
        if (numCompatibleSets > 0) {
          byte[] mpeghCompatibleProfileLevelSet = new byte[numCompatibleSets];
          parent.readBytes(mpeghCompatibleProfileLevelSet, 0, numCompatibleSets);
          if (initializationData == null) {
            initializationData = ImmutableList.of(mpeghCompatibleProfileLevelSet);
          } else {
            // We assume that the mhaC box has been parsed before and so add the compatible profile
            // level sets as the second entry.
            initializationData =
                ImmutableList.of(initializationData.get(0), mpeghCompatibleProfileLevelSet);
          }
        }
      } else if (childAtomType == Mp4Box.TYPE_esds
          || (isQuickTime && childAtomType == Mp4Box.TYPE_wave)) {
        int esdsAtomPosition =
            childAtomType == Mp4Box.TYPE_esds
                ? childPosition
                : findBoxPosition(parent, Mp4Box.TYPE_esds, childPosition, childAtomSize);
        if (esdsAtomPosition != C.INDEX_UNSET) {
          esdsData = parseEsdsFromParent(parent, esdsAtomPosition);
          mimeType = esdsData.mimeType;
          @Nullable byte[] initializationDataBytes = esdsData.initializationData;
          if (initializationDataBytes != null) {
            if (MimeTypes.AUDIO_VORBIS.equals(mimeType)) {
              initializationData =
                  VorbisUtil.parseVorbisCsdFromEsdsInitializationData(initializationDataBytes);
            } else {
              if (MimeTypes.AUDIO_AAC.equals(mimeType)) {
                // Update sampleRate and channelCount from the AudioSpecificConfig initialization
                // data, which is more reliable. See [Internal: b/10903778].
                AacUtil.Config aacConfig =
                    AacUtil.parseAudioSpecificConfig(initializationDataBytes);
                sampleRate = aacConfig.sampleRateHz;
                channelCount = aacConfig.channelCount;
                codecs = aacConfig.codecs;
              }
              initializationData = ImmutableList.of(initializationDataBytes);
            }
          }
        }
      } else if (childAtomType == Mp4Box.TYPE_btrt) {
        btrtData = parseBtrtFromParent(parent, childPosition);
      } else if (childAtomType == Mp4Box.TYPE_dac3) {
        parent.setPosition(Mp4Box.HEADER_SIZE + childPosition);
        out.format =
            Ac3Util.parseAc3AnnexFFormat(parent, Integer.toString(trackId), language, drmInitData);
      } else if (childAtomType == Mp4Box.TYPE_dec3) {
        parent.setPosition(Mp4Box.HEADER_SIZE + childPosition);
        out.format =
            Ac3Util.parseEAc3AnnexFFormat(parent, Integer.toString(trackId), language, drmInitData);
      } else if (childAtomType == Mp4Box.TYPE_dac4) {
        parent.setPosition(Mp4Box.HEADER_SIZE + childPosition);
        out.format =
            Ac4Util.parseAc4AnnexEFormat(parent, Integer.toString(trackId), language, drmInitData);
      } else if (childAtomType == Mp4Box.TYPE_dmlp) {
        if (sampleRateMlp <= 0) {
          throw ParserException.createForMalformedContainer(
              "Invalid sample rate for Dolby TrueHD MLP stream: " + sampleRateMlp,
              /* cause= */ null);
        }
        sampleRate = sampleRateMlp;
        // The channel count from the sample entry must be ignored for Dolby TrueHD (MLP) streams
        // because these streams can carry simultaneously multiple representations of the same
        // audio. Use stereo by default.
        channelCount = 2;
      } else if (childAtomType == Mp4Box.TYPE_ddts || childAtomType == Mp4Box.TYPE_udts) {
        out.format =
            new Format.Builder()
                .setId(trackId)
                .setSampleMimeType(mimeType)
                .setChannelCount(channelCount)
                .setSampleRate(sampleRate)
                .setDrmInitData(drmInitData)
                .setLanguage(language)
                .build();
      } else if (childAtomType == Mp4Box.TYPE_dOps) {
        // Build an Opus Identification Header (defined in RFC-7845) by concatenating the Opus Magic
        // Signature and the body of the dOps atom.
        int childAtomBodySize = childAtomSize - Mp4Box.HEADER_SIZE;
        byte[] headerBytes = Arrays.copyOf(opusMagic, opusMagic.length + childAtomBodySize);
        parent.setPosition(childPosition + Mp4Box.HEADER_SIZE);
        parent.readBytes(headerBytes, opusMagic.length, childAtomBodySize);
        initializationData = OpusUtil.buildInitializationData(headerBytes);
      } else if (childAtomType == Mp4Box.TYPE_dfLa) {
        int childAtomBodySize = childAtomSize - Mp4Box.FULL_HEADER_SIZE;
        byte[] initializationDataBytes = new byte[4 + childAtomBodySize];
        initializationDataBytes[0] = 0x66; // f
        initializationDataBytes[1] = 0x4C; // L
        initializationDataBytes[2] = 0x61; // a
        initializationDataBytes[3] = 0x43; // C
        parent.setPosition(childPosition + Mp4Box.FULL_HEADER_SIZE);
        parent.readBytes(initializationDataBytes, /* offset= */ 4, childAtomBodySize);
        initializationData = ImmutableList.of(initializationDataBytes);
      } else if (childAtomType == Mp4Box.TYPE_alac) {
        int childAtomBodySize = childAtomSize - Mp4Box.FULL_HEADER_SIZE;
        byte[] initializationDataBytes = new byte[childAtomBodySize];
        parent.setPosition(childPosition + Mp4Box.FULL_HEADER_SIZE);
        parent.readBytes(initializationDataBytes, /* offset= */ 0, childAtomBodySize);
        // Update sampleRate and channelCount from the AudioSpecificConfig initialization data,
        // which is more reliable. See https://github.com/google/ExoPlayer/pull/6629.
        Pair<Integer, Integer> audioSpecificConfig =
            CodecSpecificDataUtil.parseAlacAudioSpecificConfig(initializationDataBytes);
        sampleRate = audioSpecificConfig.first;
        channelCount = audioSpecificConfig.second;
        initializationData = ImmutableList.of(initializationDataBytes);
      } else if (childAtomType == Mp4Box.TYPE_iacb) {
        parent.setPosition(
            childPosition + Mp4Box.HEADER_SIZE + 1); // header and configuration version
        int configObusSize = parent.readUnsignedLeb128ToInt();
        byte[] initializationDataBytes = new byte[configObusSize];
        parent.readBytes(initializationDataBytes, /* offset= */ 0, configObusSize);
        codecs = CodecSpecificDataUtil.buildIamfCodecString(initializationDataBytes);
        initializationData = ImmutableList.of(initializationDataBytes);
      } else if (childAtomType == Mp4Box.TYPE_pcmC) {
        // See ISO 23003-5 for the definition of the pcmC box.
        parent.setPosition(childPosition + Mp4Box.FULL_HEADER_SIZE);
        int formatFlags = parent.readUnsignedByte();
        ByteOrder byteOrder = (formatFlags & 0x1) != 0 ? LITTLE_ENDIAN : BIG_ENDIAN;
        int sampleSize = parent.readUnsignedByte();
        if (atomType == Mp4Box.TYPE_ipcm) {
          pcmEncoding = Util.getPcmEncoding(sampleSize, byteOrder);
        } else if (atomType == Mp4Box.TYPE_fpcm
            && sampleSize == 32
            && byteOrder.equals(LITTLE_ENDIAN)) {
          // Only single-width little-endian floating point PCM is supported.
          pcmEncoding = C.ENCODING_PCM_FLOAT;
        }
        if (pcmEncoding != Format.NO_VALUE) {
          mimeType = MimeTypes.AUDIO_RAW;
        }
      }
      childPosition += childAtomSize;
    }

    if (out.format == null && mimeType != null) {
      Format.Builder formatBuilder =
          new Format.Builder()
              .setId(trackId)
              .setSampleMimeType(mimeType)
              .setCodecs(codecs)
              .setChannelCount(channelCount)
              .setSampleRate(sampleRate)
              .setPcmEncoding(pcmEncoding)
              .setInitializationData(initializationData)
              .setDrmInitData(drmInitData)
              .setLanguage(language);

      // Prefer esdsData over btrtData for audio track.
      if (esdsData != null) {
        formatBuilder
            .setAverageBitrate(Ints.saturatedCast(esdsData.bitrate))
            .setPeakBitrate(Ints.saturatedCast(esdsData.peakBitrate));
      } else if (btrtData != null) {
        formatBuilder
            .setAverageBitrate(Ints.saturatedCast(btrtData.avgBitrate))
            .setPeakBitrate(Ints.saturatedCast(btrtData.maxBitrate));
      }

      out.format = formatBuilder.build();
    }
  }

  /**
   * Returns the position of the first box with the given {@code boxType} within {@code parent}, or
   * {@link C#INDEX_UNSET} if no such box is found.
   *
   * @param parent The {@link ParsableByteArray} to search. The search will start from the {@link
   *     ParsableByteArray#getPosition() current position}.
   * @param boxType The box type to search for.
   * @param parentBoxPosition The position in {@code parent} of the box we are searching.
   * @param parentBoxSize The size of the parent box we are searching in bytes.
   * @return The position of the first box with the given {@code boxType} within {@code parent}, or
   *     {@link C#INDEX_UNSET} if no such box is found.
   */
  private static int findBoxPosition(
      ParsableByteArray parent, int boxType, int parentBoxPosition, int parentBoxSize)
      throws ParserException {
    int childAtomPosition = parent.getPosition();
    ExtractorUtil.checkContainerInput(childAtomPosition >= parentBoxPosition, /* message= */ null);
    while (childAtomPosition - parentBoxPosition < parentBoxSize) {
      parent.setPosition(childAtomPosition);
      int childAtomSize = parent.readInt();
      ExtractorUtil.checkContainerInput(childAtomSize > 0, "childAtomSize must be positive");
      int childType = parent.readInt();
      if (childType == boxType) {
        return childAtomPosition;
      }
      childAtomPosition += childAtomSize;
    }
    return C.INDEX_UNSET;
  }

  /** Returns codec-specific initialization data contained in an esds box. */
  private static EsdsData parseEsdsFromParent(ParsableByteArray parent, int position) {
    parent.setPosition(position + Mp4Box.HEADER_SIZE + 4);
    // Start of the ES_Descriptor (defined in ISO/IEC 14496-1)
    parent.skipBytes(1); // ES_Descriptor tag
    parseExpandableClassSize(parent);
    parent.skipBytes(2); // ES_ID

    int flags = parent.readUnsignedByte();
    if ((flags & 0x80 /* streamDependenceFlag */) != 0) {
      parent.skipBytes(2);
    }
    if ((flags & 0x40 /* URL_Flag */) != 0) {
      parent.skipBytes(parent.readUnsignedByte());
    }
    if ((flags & 0x20 /* OCRstreamFlag */) != 0) {
      parent.skipBytes(2);
    }

    // Start of the DecoderConfigDescriptor (defined in ISO/IEC 14496-1)
    parent.skipBytes(1); // DecoderConfigDescriptor tag
    parseExpandableClassSize(parent);

    // Set the MIME type based on the object type indication (ISO/IEC 14496-1 table 5).
    int objectTypeIndication = parent.readUnsignedByte();
    @Nullable String mimeType = getMimeTypeFromMp4ObjectType(objectTypeIndication);
    if (MimeTypes.AUDIO_MPEG.equals(mimeType)
        || MimeTypes.AUDIO_DTS.equals(mimeType)
        || MimeTypes.AUDIO_DTS_HD.equals(mimeType)) {
      return new EsdsData(
          mimeType,
          /* initializationData= */ null,
          /* bitrate= */ Format.NO_VALUE,
          /* peakBitrate= */ Format.NO_VALUE);
    }

    parent.skipBytes(4);
    long peakBitrate = parent.readUnsignedInt();
    long bitrate = parent.readUnsignedInt();

    // Start of the DecoderSpecificInfo.
    parent.skipBytes(1); // DecoderSpecificInfo tag
    int initializationDataSize = parseExpandableClassSize(parent);
    byte[] initializationData = new byte[initializationDataSize];
    parent.readBytes(initializationData, 0, initializationDataSize);

    // Skipping zero values as unknown.
    return new EsdsData(
        mimeType,
        /* initializationData= */ initializationData,
        /* bitrate= */ bitrate > 0 ? bitrate : Format.NO_VALUE,
        /* peakBitrate= */ peakBitrate > 0 ? peakBitrate : Format.NO_VALUE);
  }

  /**
   * Returns bitrate data contained in a btrt box, as specified by Section 8.5.2.2 in ISO/IEC
   * 14496-12:2012(E).
   */
  private static BtrtData parseBtrtFromParent(ParsableByteArray parent, int position) {
    parent.setPosition(position + Mp4Box.HEADER_SIZE);

    parent.skipBytes(4); // bufferSizeDB
    long maxBitrate = parent.readUnsignedInt();
    long avgBitrate = parent.readUnsignedInt();

    return new BtrtData(avgBitrate, maxBitrate);
  }

  /**
   * Returns stereo video playback related meta data from the vexu box. See
   * https://developer.apple.com/av-foundation/Stereo-Video-ISOBMFF-Extensions.pdf for ref.
   */
  @Nullable
  /* package */ static VexuData parseVideoExtendedUsageBox(
      ParsableByteArray parent, int position, int size) throws ParserException {
    parent.setPosition(position + Mp4Box.HEADER_SIZE);
    int childPosition = parent.getPosition();
    @Nullable EyesData eyesData = null;
    while (childPosition - position < size) {
      parent.setPosition(childPosition);
      int childAtomSize = parent.readInt();
      ExtractorUtil.checkContainerInput(childAtomSize > 0, "childAtomSize must be positive");
      int childAtomType = parent.readInt();
      if (childAtomType == Mp4Box.TYPE_eyes) {
        eyesData = parseStereoViewBox(parent, childPosition, childAtomSize);
      }
      childPosition += childAtomSize;
    }
    return eyesData == null ? null : new VexuData(eyesData);
  }

  @Nullable
  private static EyesData parseStereoViewBox(ParsableByteArray parent, int position, int size)
      throws ParserException {
    parent.setPosition(position + Mp4Box.HEADER_SIZE);
    int childPosition = parent.getPosition();
    while (childPosition - position < size) {
      parent.setPosition(childPosition);
      int childAtomSize = parent.readInt();
      ExtractorUtil.checkContainerInput(childAtomSize > 0, "childAtomSize must be positive");
      if (parent.readInt() == Mp4Box.TYPE_stri) {
        // The stri box extends FullBox that includes version (8 bits) and flags (24 bits).
        parent.skipBytes(4);
        int striInfo = parent.readUnsignedByte() & 0x0F;
        return new EyesData(
            new StriData(
                ((striInfo & 0x01) == 0x01),
                ((striInfo & 0x02) == 0x02),
                ((striInfo & 0x08) == 0x08)));
      }
      childPosition += childAtomSize;
    }
    return null;
  }

  /**
   * Parses encryption data from an audio/video sample entry, returning a pair consisting of the
   * unencrypted atom type and a {@link TrackEncryptionBox}. Null is returned if no common
   * encryption sinf atom was present.
   */
  @Nullable
  private static Pair<Integer, TrackEncryptionBox> parseSampleEntryEncryptionData(
      ParsableByteArray parent, int position, int size) throws ParserException {
    int childPosition = parent.getPosition();
    while (childPosition - position < size) {
      parent.setPosition(childPosition);
      int childAtomSize = parent.readInt();
      ExtractorUtil.checkContainerInput(childAtomSize > 0, "childAtomSize must be positive");
      int childAtomType = parent.readInt();
      if (childAtomType == Mp4Box.TYPE_sinf) {
        @Nullable
        Pair<Integer, TrackEncryptionBox> result =
            parseCommonEncryptionSinfFromParent(parent, childPosition, childAtomSize);
        if (result != null) {
          return result;
        }
      }
      childPosition += childAtomSize;
    }
    return null;
  }

  @Nullable
  /* package */ static Pair<Integer, TrackEncryptionBox> parseCommonEncryptionSinfFromParent(
      ParsableByteArray parent, int position, int size) throws ParserException {
    int childPosition = position + Mp4Box.HEADER_SIZE;
    int schemeInformationBoxPosition = C.INDEX_UNSET;
    int schemeInformationBoxSize = 0;
    @Nullable String schemeType = null;
    @Nullable Integer dataFormat = null;
    while (childPosition - position < size) {
      parent.setPosition(childPosition);
      int childAtomSize = parent.readInt();
      int childAtomType = parent.readInt();
      if (childAtomType == Mp4Box.TYPE_frma) {
        dataFormat = parent.readInt();
      } else if (childAtomType == Mp4Box.TYPE_schm) {
        parent.skipBytes(4);
        // Common encryption scheme_type values are defined in ISO/IEC 23001-7:2016, section 4.1.
        schemeType = parent.readString(4);
      } else if (childAtomType == Mp4Box.TYPE_schi) {
        schemeInformationBoxPosition = childPosition;
        schemeInformationBoxSize = childAtomSize;
      }
      childPosition += childAtomSize;
    }

    if (C.CENC_TYPE_cenc.equals(schemeType)
        || C.CENC_TYPE_cbc1.equals(schemeType)
        || C.CENC_TYPE_cens.equals(schemeType)
        || C.CENC_TYPE_cbcs.equals(schemeType)) {
      ExtractorUtil.checkContainerInput(dataFormat != null, "frma atom is mandatory");
      ExtractorUtil.checkContainerInput(
          schemeInformationBoxPosition != C.INDEX_UNSET, "schi atom is mandatory");
      @Nullable
      TrackEncryptionBox encryptionBox =
          parseSchiFromParent(
              parent, schemeInformationBoxPosition, schemeInformationBoxSize, schemeType);
      ExtractorUtil.checkContainerInput(encryptionBox != null, "tenc atom is mandatory");
      return Pair.create(dataFormat, castNonNull(encryptionBox));
    } else {
      return null;
    }
  }

  @Nullable
  private static TrackEncryptionBox parseSchiFromParent(
      ParsableByteArray parent, int position, int size, String schemeType) {
    int childPosition = position + Mp4Box.HEADER_SIZE;
    while (childPosition - position < size) {
      parent.setPosition(childPosition);
      int childAtomSize = parent.readInt();
      int childAtomType = parent.readInt();
      if (childAtomType == Mp4Box.TYPE_tenc) {
        int fullAtom = parent.readInt();
        int version = parseFullBoxVersion(fullAtom);
        parent.skipBytes(1); // reserved = 0.
        int defaultCryptByteBlock = 0;
        int defaultSkipByteBlock = 0;
        if (version == 0) {
          parent.skipBytes(1); // reserved = 0.
        } else /* version 1 or greater */ {
          int patternByte = parent.readUnsignedByte();
          defaultCryptByteBlock = (patternByte & 0xF0) >> 4;
          defaultSkipByteBlock = patternByte & 0x0F;
        }
        boolean defaultIsProtected = parent.readUnsignedByte() == 1;
        int defaultPerSampleIvSize = parent.readUnsignedByte();
        byte[] defaultKeyId = new byte[16];
        parent.readBytes(defaultKeyId, 0, defaultKeyId.length);
        byte[] constantIv = null;
        if (defaultIsProtected && defaultPerSampleIvSize == 0) {
          int constantIvSize = parent.readUnsignedByte();
          constantIv = new byte[constantIvSize];
          parent.readBytes(constantIv, 0, constantIvSize);
        }
        return new TrackEncryptionBox(
            defaultIsProtected,
            schemeType,
            defaultPerSampleIvSize,
            defaultKeyId,
            defaultCryptByteBlock,
            defaultSkipByteBlock,
            constantIv);
      }
      childPosition += childAtomSize;
    }
    return null;
  }

  /** Parses the proj box from sv3d box, as specified by https://github.com/google/spatial-media. */
  @Nullable
  private static byte[] parseProjFromParent(ParsableByteArray parent, int position, int size) {
    int childPosition = position + Mp4Box.HEADER_SIZE;
    while (childPosition - position < size) {
      parent.setPosition(childPosition);
      int childAtomSize = parent.readInt();
      int childAtomType = parent.readInt();
      if (childAtomType == Mp4Box.TYPE_proj) {
        return Arrays.copyOfRange(parent.getData(), childPosition, childPosition + childAtomSize);
      }
      childPosition += childAtomSize;
    }
    return null;
  }

  /** Parses the size of an expandable class, as specified by ISO/IEC 14496-1 subsection 8.3.3. */
  private static int parseExpandableClassSize(ParsableByteArray data) {
    int currentByte = data.readUnsignedByte();
    int size = currentByte & 0x7F;
    while ((currentByte & 0x80) == 0x80) {
      currentByte = data.readUnsignedByte();
      size = (size << 7) | (currentByte & 0x7F);
    }
    return size;
  }

  /** Returns whether it's possible to apply the specified edit using gapless playback info. */
  private static boolean canApplyEditWithGaplessInfo(
      long[] timestamps, long duration, long editStartTime, long editEndTime) {
    int lastIndex = timestamps.length - 1;
    int latestDelayIndex = Util.constrainValue(MAX_GAPLESS_TRIM_SIZE_SAMPLES, 0, lastIndex);
    int earliestPaddingIndex =
        Util.constrainValue(timestamps.length - MAX_GAPLESS_TRIM_SIZE_SAMPLES, 0, lastIndex);
    return timestamps[0] <= editStartTime
        && editStartTime < timestamps[latestDelayIndex]
        && timestamps[earliestPaddingIndex] < editEndTime
        && editEndTime <= duration + EDIT_LIST_DURATION_TOLERANCE_TIMESCALE_UNITS;
  }

  private BoxParser() {
    // Prevent instantiation.
  }

  private static final class ChunkIterator {

    public final int length;

    public int index;
    public int numSamples;
    public long offset;

    private final boolean chunkOffsetsAreLongs;
    private final ParsableByteArray chunkOffsets;
    private final ParsableByteArray stsc;

    private int nextSamplesPerChunkChangeIndex;
    private int remainingSamplesPerChunkChanges;

    public ChunkIterator(
        ParsableByteArray stsc, ParsableByteArray chunkOffsets, boolean chunkOffsetsAreLongs)
        throws ParserException {
      this.stsc = stsc;
      this.chunkOffsets = chunkOffsets;
      this.chunkOffsetsAreLongs = chunkOffsetsAreLongs;
      chunkOffsets.setPosition(Mp4Box.FULL_HEADER_SIZE);
      length = chunkOffsets.readUnsignedIntToInt();
      stsc.setPosition(Mp4Box.FULL_HEADER_SIZE);
      remainingSamplesPerChunkChanges = stsc.readUnsignedIntToInt();
      ExtractorUtil.checkContainerInput(stsc.readInt() == 1, "first_chunk must be 1");
      index = -1;
    }

    public boolean moveNext() {
      if (++index == length) {
        return false;
      }
      offset =
          chunkOffsetsAreLongs
              ? chunkOffsets.readUnsignedLongToLong()
              : chunkOffsets.readUnsignedInt();
      if (index == nextSamplesPerChunkChangeIndex) {
        numSamples = stsc.readUnsignedIntToInt();
        stsc.skipBytes(4); // Skip sample_description_index
        nextSamplesPerChunkChangeIndex =
            --remainingSamplesPerChunkChanges > 0
                ? (stsc.readUnsignedIntToInt() - 1)
                : C.INDEX_UNSET;
      }
      return true;
    }
  }

  /** Holds data parsed from a tkhd atom. */
  private static final class TkhdData {

    private final int id;
    private final long duration;
    private final int alternateGroup;
    private final int rotationDegrees;
    private final int width;
    private final int height;

    public TkhdData(
        int id, long duration, int alternateGroup, int rotationDegrees, int width, int height) {
      this.id = id;
      this.duration = duration;
      this.alternateGroup = alternateGroup;
      this.rotationDegrees = rotationDegrees;
      this.width = width;
      this.height = height;
    }
  }

  /** Holds data parsed from an stsd atom and its children. */
  private static final class StsdData {

    public static final int STSD_HEADER_SIZE = 8;

    public final TrackEncryptionBox[] trackEncryptionBoxes;

    @Nullable public Format format;
    public int nalUnitLengthFieldLength;
    public @Track.Transformation int requiredSampleTransformation;

    public StsdData(int numberOfEntries) {
      trackEncryptionBoxes = new TrackEncryptionBox[numberOfEntries];
      requiredSampleTransformation = Track.TRANSFORMATION_NONE;
    }
  }

  /** Data parsed from an esds box. */
  private static final class EsdsData {
    private final @NullableType String mimeType;
    private final byte @NullableType [] initializationData;
    private final long bitrate;
    private final long peakBitrate;

    public EsdsData(
        @NullableType String mimeType,
        byte @NullableType [] initializationData,
        long bitrate,
        long peakBitrate) {
      this.mimeType = mimeType;
      this.initializationData = initializationData;
      this.bitrate = bitrate;
      this.peakBitrate = peakBitrate;
    }
  }

  /** Data parsed from btrt box. */
  private static final class BtrtData {
    private final long avgBitrate;
    private final long maxBitrate;

    public BtrtData(long avgBitrate, long maxBitrate) {
      this.avgBitrate = avgBitrate;
      this.maxBitrate = maxBitrate;
    }
  }

  /** Data parsed from stri box. */
  private static final class StriData {
    private final boolean hasLeftEyeView;
    private final boolean hasRightEyeView;
    private final boolean eyeViewsReversed;

    public StriData(boolean hasLeftEyeView, boolean hasRightEyeView, boolean eyeViewsReversed) {
      this.hasLeftEyeView = hasLeftEyeView;
      this.hasRightEyeView = hasRightEyeView;
      this.eyeViewsReversed = eyeViewsReversed;
    }
  }

  /** Data parsed from eyes box. */
  private static final class EyesData {
    private final StriData striData;

    public EyesData(StriData striData) {
      this.striData = striData;
    }
  }

  /** Data parsed from mdhd box. */
  private static final class MdhdData {
    private final long timescale;
    private final long mediaDurationUs;
    @Nullable private final String language;

    public MdhdData(long timescale, long mediaDurationUs, @Nullable String language) {
      this.timescale = timescale;
      this.mediaDurationUs = mediaDurationUs;
      this.language = language;
    }
  }

  /** Data parsed from vexu box. */
  /* package */ static final class VexuData {
    @Nullable private final EyesData eyesData;

    public VexuData(EyesData eyesData) {
      this.eyesData = eyesData;
    }

    public boolean hasBothEyeViews() {
      return eyesData != null
          && eyesData.striData.hasLeftEyeView
          && eyesData.striData.hasRightEyeView;
    }
  }

  /** A box containing sample sizes (e.g. stsz, stz2). */
  private interface SampleSizeBox {

    /** Returns the number of samples. */
    int getSampleCount();

    /** Returns the size of each sample if fixed, or {@link C#LENGTH_UNSET} otherwise. */
    int getFixedSampleSize();

    /** Returns the size for the next sample. */
    int readNextSampleSize();
  }

  /** An stsz sample size box. */
  /* package */ static final class StszSampleSizeBox implements SampleSizeBox {

    private final int fixedSampleSize;
    private final int sampleCount;
    private final ParsableByteArray data;

    public StszSampleSizeBox(LeafBox stszAtom, Format trackFormat) {
      data = stszAtom.data;
      data.setPosition(Mp4Box.FULL_HEADER_SIZE);
      int fixedSampleSize = data.readUnsignedIntToInt();
      if (MimeTypes.AUDIO_RAW.equals(trackFormat.sampleMimeType)) {
        int pcmFrameSize = Util.getPcmFrameSize(trackFormat.pcmEncoding, trackFormat.channelCount);
        if (fixedSampleSize == 0 || fixedSampleSize % pcmFrameSize != 0) {
          // The sample size from the stsz box is inconsistent with the PCM encoding and channel
          // count derived from the stsd box. Choose stsd box as source of truth
          // [Internal ref: b/171627904].
          Log.w(
              TAG,
              "Audio sample size mismatch. stsd sample size: "
                  + pcmFrameSize
                  + ", stsz sample size: "
                  + fixedSampleSize);
          fixedSampleSize = pcmFrameSize;
        }
      }
      this.fixedSampleSize = fixedSampleSize == 0 ? C.LENGTH_UNSET : fixedSampleSize;
      sampleCount = data.readUnsignedIntToInt();
    }

    @Override
    public int getSampleCount() {
      return sampleCount;
    }

    @Override
    public int getFixedSampleSize() {
      return fixedSampleSize;
    }

    @Override
    public int readNextSampleSize() {
      return fixedSampleSize == C.LENGTH_UNSET ? data.readUnsignedIntToInt() : fixedSampleSize;
    }
  }

  /** An stz2 sample size box. */
  /* package */ static final class Stz2SampleSizeBox implements SampleSizeBox {

    private final ParsableByteArray data;
    private final int sampleCount;
    private final int fieldSize; // Can be 4, 8, or 16.

    // Used only if fieldSize == 4.
    private int sampleIndex;
    private int currentByte;

    public Stz2SampleSizeBox(LeafBox stz2Atom) {
      data = stz2Atom.data;
      data.setPosition(Mp4Box.FULL_HEADER_SIZE);
      fieldSize = data.readUnsignedIntToInt() & 0x000000FF;
      sampleCount = data.readUnsignedIntToInt();
    }

    @Override
    public int getSampleCount() {
      return sampleCount;
    }

    @Override
    public int getFixedSampleSize() {
      return C.LENGTH_UNSET;
    }

    @Override
    public int readNextSampleSize() {
      if (fieldSize == 8) {
        return data.readUnsignedByte();
      } else if (fieldSize == 16) {
        return data.readUnsignedShort();
      } else {
        // fieldSize == 4.
        if ((sampleIndex++ % 2) == 0) {
          // Read the next byte into our cached byte when we are reading the upper bits.
          currentByte = data.readUnsignedByte();
          // Read the upper bits from the byte and shift them to the lower 4 bits.
          return (currentByte & 0xF0) >> 4;
        } else {
          // Mask out the upper 4 bits of the last byte we read.
          return currentByte & 0x0F;
        }
      }
    }
  }
}
