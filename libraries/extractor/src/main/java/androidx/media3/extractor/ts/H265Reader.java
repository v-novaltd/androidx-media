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
package androidx.media3.extractor.ts;

import static com.google.common.base.Preconditions.checkState;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.ColorInfo;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.Assertions;
import androidx.media3.common.util.CodecSpecificDataUtil;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.container.NalUnitUtil;
import androidx.media3.extractor.ExtractorOutput;
import androidx.media3.extractor.TrackOutput;
import androidx.media3.extractor.ts.TsPayloadReader.TrackIdGenerator;
import java.util.Collections;
import org.checkerframework.checker.nullness.qual.EnsuresNonNull;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.RequiresNonNull;

/** Parses a continuous H.265 byte stream and extracts individual frames. */
@UnstableApi
public final class H265Reader implements ElementaryStreamReader {

  private final SeiReader seiReader;
  private final String containerMimeType;

  private @MonotonicNonNull String formatId;
  private @MonotonicNonNull TrackOutput output;
  private @MonotonicNonNull SampleReader sampleReader;

  // State that should not be reset on seek.
  private boolean hasOutputFormat;

  // State that should be reset on seek.
  private final boolean[] prefixFlags;
  private final NalUnitTargetBuffer vps;
  private final NalUnitTargetBuffer sps;
  private final NalUnitTargetBuffer pps;
  private final NalUnitTargetBuffer prefixSei;
  private final NalUnitTargetBuffer suffixSei;
  private long totalBytesWritten;

  // Per packet state that gets reset at the start of each packet.
  private long pesTimeUs;

  // Scratch variables to avoid allocations.
  private final ParsableByteArray seiWrapper;

  /**
   * @param seiReader An SEI reader for consuming closed caption channels.
   * @param containerMimeType The MIME type of the container holding the stream.
   */
  public H265Reader(SeiReader seiReader, String containerMimeType) {
    this.seiReader = seiReader;
    this.containerMimeType = containerMimeType;
    prefixFlags = new boolean[3];
    vps = new NalUnitTargetBuffer(NalUnitUtil.H265_NAL_UNIT_TYPE_VPS, 128);
    sps = new NalUnitTargetBuffer(NalUnitUtil.H265_NAL_UNIT_TYPE_SPS, 128);
    pps = new NalUnitTargetBuffer(NalUnitUtil.H265_NAL_UNIT_TYPE_PPS, 128);
    prefixSei = new NalUnitTargetBuffer(NalUnitUtil.H265_NAL_UNIT_TYPE_PREFIX_SEI, 128);
    suffixSei = new NalUnitTargetBuffer(NalUnitUtil.H265_NAL_UNIT_TYPE_SUFFIX_SEI, 128);
    pesTimeUs = C.TIME_UNSET;
    seiWrapper = new ParsableByteArray();
  }

  @Override
  public void seek() {
    totalBytesWritten = 0;
    pesTimeUs = C.TIME_UNSET;
    NalUnitUtil.clearPrefixFlags(prefixFlags);
    vps.reset();
    sps.reset();
    pps.reset();
    prefixSei.reset();
    suffixSei.reset();
    seiReader.clear();
    if (sampleReader != null) {
      sampleReader.reset();
    }
  }

  @Override
  public void createTracks(ExtractorOutput extractorOutput, TrackIdGenerator idGenerator) {
    idGenerator.generateNewId();
    formatId = idGenerator.getFormatId();
    output = extractorOutput.track(idGenerator.getTrackId(), C.TRACK_TYPE_VIDEO);
    sampleReader = new SampleReader(output);
    seiReader.createTracks(extractorOutput, idGenerator);
  }

  @Override
  public void packetStarted(long pesTimeUs, @TsPayloadReader.Flags int flags) {
    // TODO (Internal b/32267012): Consider using random access indicator.
    this.pesTimeUs = pesTimeUs;
  }

  @Override
  public void consume(ParsableByteArray data) {
    assertTracksCreated();

    while (data.bytesLeft() > 0) {
      int offset = data.getPosition();
      int limit = data.limit();
      byte[] dataArray = data.getData();

      // Append the data to the buffer.
      totalBytesWritten += data.bytesLeft();
      output.sampleData(data, data.bytesLeft());

      // Scan the appended data, processing NAL units as they are encountered
      while (offset < limit) {
        int nalUnitOffset = NalUnitUtil.findNalUnit(dataArray, offset, limit, prefixFlags);

        if (nalUnitOffset == limit) {
          // We've scanned to the end of the data without finding the start of another NAL unit.
          nalUnitData(dataArray, offset, limit);
          return;
        }

        // We've seen the start of a NAL unit of the following type.
        int nalUnitType = NalUnitUtil.getH265NalUnitType(dataArray, nalUnitOffset);

        // Case of a 4 byte start code prefix 0x00000001, recoil NAL unit offset by one byte
        // to avoid previous byte being assigned to the previous access unit.
        int prefixSize = 3;
        if (nalUnitOffset > 0 && dataArray[nalUnitOffset - 1] == 0x00) {
          nalUnitOffset--;
          prefixSize = 4;
        }

        // This is the number of bytes from the current offset to the start of the next NAL unit.
        // It may be negative if the NAL unit started in the previously consumed data.
        int lengthToNalUnit = nalUnitOffset - offset;
        if (lengthToNalUnit > 0) {
          nalUnitData(dataArray, offset, nalUnitOffset);
        }

        int bytesWrittenPastPosition = limit - nalUnitOffset;
        long absolutePosition = totalBytesWritten - bytesWrittenPastPosition;
        // Indicate the end of the previous NAL unit. If the length to the start of the next unit
        // is negative then we wrote too many bytes to the NAL buffers. Discard the excess bytes
        // when notifying that the unit has ended.
        endNalUnit(
            absolutePosition,
            bytesWrittenPastPosition,
            lengthToNalUnit < 0 ? -lengthToNalUnit : 0,
            pesTimeUs);
        // Indicate the start of the next NAL unit.
        startNalUnit(absolutePosition, bytesWrittenPastPosition, nalUnitType, pesTimeUs);
        // Continue scanning the data.
        offset = nalUnitOffset + prefixSize;
      }
    }
  }

  @Override
  public void packetFinished(boolean isEndOfInput) {
    assertTracksCreated();
    if (isEndOfInput) {
      seiReader.flush();
      // Simulate end of current NAL unit and start an unspecified one to trigger output of current
      // sample
      endNalUnit(totalBytesWritten, 0, 0, pesTimeUs);
      startNalUnit(totalBytesWritten, 0, NalUnitUtil.H265_NAL_UNIT_TYPE_UNSPECIFIED, pesTimeUs);
    }
  }

  @RequiresNonNull("sampleReader")
  private void startNalUnit(long position, int offset, int nalUnitType, long pesTimeUs) {
    sampleReader.startNalUnit(position, offset, nalUnitType, pesTimeUs, hasOutputFormat);
    if (!hasOutputFormat) {
      vps.startNalUnit(nalUnitType);
      sps.startNalUnit(nalUnitType);
      pps.startNalUnit(nalUnitType);
    }
    prefixSei.startNalUnit(nalUnitType);
    suffixSei.startNalUnit(nalUnitType);
  }

  @RequiresNonNull("sampleReader")
  private void nalUnitData(byte[] dataArray, int offset, int limit) {
    sampleReader.readNalUnitData(dataArray, offset, limit);
    if (!hasOutputFormat) {
      vps.appendToNalUnit(dataArray, offset, limit);
      sps.appendToNalUnit(dataArray, offset, limit);
      pps.appendToNalUnit(dataArray, offset, limit);
    }
    prefixSei.appendToNalUnit(dataArray, offset, limit);
    suffixSei.appendToNalUnit(dataArray, offset, limit);
  }

  @RequiresNonNull({"output", "sampleReader"})
  private void endNalUnit(long position, int offset, int discardPadding, long pesTimeUs) {
    sampleReader.endNalUnit(position, offset, hasOutputFormat);
    if (!hasOutputFormat) {
      vps.endNalUnit(discardPadding);
      sps.endNalUnit(discardPadding);
      pps.endNalUnit(discardPadding);
      if (vps.isCompleted() && sps.isCompleted() && pps.isCompleted()) {
        Format format = parseMediaFormat(formatId, vps, sps, pps, containerMimeType);
        output.format(format);
        checkState(format.maxNumReorderSamples != Format.NO_VALUE);
        seiReader.setReorderingQueueSize(format.maxNumReorderSamples);
        hasOutputFormat = true;
      }
    }
    if (prefixSei.endNalUnit(discardPadding)) {
      int unescapedLength = NalUnitUtil.unescapeStream(prefixSei.nalData, prefixSei.nalLength);
      seiWrapper.reset(prefixSei.nalData, unescapedLength);

      // Skip the NAL prefix and type.
      seiWrapper.skipBytes(5);
      seiReader.consume(pesTimeUs, seiWrapper);
    }
    if (suffixSei.endNalUnit(discardPadding)) {
      int unescapedLength = NalUnitUtil.unescapeStream(suffixSei.nalData, suffixSei.nalLength);
      seiWrapper.reset(suffixSei.nalData, unescapedLength);

      // Skip the NAL prefix and type.
      seiWrapper.skipBytes(5);
      seiReader.consume(pesTimeUs, seiWrapper);
    }
  }

  private static Format parseMediaFormat(
      @Nullable String formatId,
      NalUnitTargetBuffer vps,
      NalUnitTargetBuffer sps,
      NalUnitTargetBuffer pps,
      String containerMimeType) {
    // Build codec-specific data.
    byte[] csdData = new byte[vps.nalLength + sps.nalLength + pps.nalLength];
    System.arraycopy(vps.nalData, 0, csdData, 0, vps.nalLength);
    System.arraycopy(sps.nalData, 0, csdData, vps.nalLength, sps.nalLength);
    System.arraycopy(pps.nalData, 0, csdData, vps.nalLength + sps.nalLength, pps.nalLength);

    // Skip the 3-byte NAL unit start code synthesised by the NalUnitTargetBuffer constructor.
    NalUnitUtil.H265SpsData spsData =
        NalUnitUtil.parseH265SpsNalUnit(
            sps.nalData, /* nalOffset= */ 3, sps.nalLength, /* vpsData= */ null);

    @Nullable String codecs = null;
    if (spsData.profileTierLevel != null) {
      codecs =
          CodecSpecificDataUtil.buildHevcCodecString(
              spsData.profileTierLevel.generalProfileSpace,
              spsData.profileTierLevel.generalTierFlag,
              spsData.profileTierLevel.generalProfileIdc,
              spsData.profileTierLevel.generalProfileCompatibilityFlags,
              spsData.profileTierLevel.constraintBytes,
              spsData.profileTierLevel.generalLevelIdc);
    }
    return new Format.Builder()
        .setId(formatId)
        .setContainerMimeType(containerMimeType)
        .setSampleMimeType(MimeTypes.VIDEO_H265)
        .setCodecs(codecs)
        .setWidth(spsData.width)
        .setHeight(spsData.height)
        .setDecodedWidth(spsData.decodedWidth)
        .setDecodedHeight(spsData.decodedHeight)
        .setColorInfo(
            new ColorInfo.Builder()
                .setColorSpace(spsData.colorSpace)
                .setColorRange(spsData.colorRange)
                .setColorTransfer(spsData.colorTransfer)
                .setLumaBitdepth(spsData.bitDepthLumaMinus8 + 8)
                .setChromaBitdepth(spsData.bitDepthChromaMinus8 + 8)
                .build())
        .setPixelWidthHeightRatio(spsData.pixelWidthHeightRatio)
        .setMaxNumReorderSamples(spsData.maxNumReorderPics)
        .setMaxSubLayers(spsData.maxSubLayersMinus1 + 1)
        .setInitializationData(Collections.singletonList(csdData))
        .build();
  }

  @EnsuresNonNull({"output", "sampleReader"})
  private void assertTracksCreated() {
    Assertions.checkStateNotNull(output);
    Util.castNonNull(sampleReader);
  }

  private static final class SampleReader {

    /**
     * Offset in bytes of the first_slice_segment_in_pic_flag in a NAL unit containing a
     * slice_segment_layer_rbsp.
     */
    private static final int FIRST_SLICE_FLAG_OFFSET = 2;

    private final TrackOutput output;

    // Per NAL unit state. A sample consists of one or more NAL units.
    private long nalUnitPosition;
    private boolean nalUnitHasKeyframeData;
    private int nalUnitBytesRead;
    private long nalUnitTimeUs;
    private boolean lookingForFirstSliceFlag;
    private boolean isFirstSlice;
    private boolean isFirstPrefixNalUnit;

    // Per sample state that gets reset at the start of each sample.
    private boolean readingSample;
    private boolean readingPrefix;
    private long samplePosition;
    private long sampleTimeUs;
    private boolean sampleIsKeyframe;

    public SampleReader(TrackOutput output) {
      this.output = output;
    }

    public void reset() {
      lookingForFirstSliceFlag = false;
      isFirstSlice = false;
      isFirstPrefixNalUnit = false;
      readingSample = false;
      readingPrefix = false;
    }

    public void startNalUnit(
        long position, int offset, int nalUnitType, long pesTimeUs, boolean hasOutputFormat) {
      isFirstSlice = false;
      isFirstPrefixNalUnit = false;
      nalUnitTimeUs = pesTimeUs;
      nalUnitBytesRead = 0;
      nalUnitPosition = position;

      if (!isVclBodyNalUnit(nalUnitType)) {
        if (readingSample && !readingPrefix) {
          if (hasOutputFormat) {
            outputSample(offset);
          }
          readingSample = false;
        }
        if (isPrefixNalUnit(nalUnitType)) {
          isFirstPrefixNalUnit = !readingPrefix;
          readingPrefix = true;
        }
      }

      // Look for the first slice flag if this NAL unit contains a slice_segment_layer_rbsp.
      nalUnitHasKeyframeData =
          (nalUnitType >= NalUnitUtil.H265_NAL_UNIT_TYPE_BLA_W_LP
              && nalUnitType <= NalUnitUtil.H265_NAL_UNIT_TYPE_CRA);
      lookingForFirstSliceFlag =
          nalUnitHasKeyframeData || nalUnitType <= NalUnitUtil.H265_NAL_UNIT_TYPE_RASL_R;
    }

    public void readNalUnitData(byte[] data, int offset, int limit) {
      if (lookingForFirstSliceFlag) {
        int headerOffset = offset + FIRST_SLICE_FLAG_OFFSET - nalUnitBytesRead;
        if (headerOffset < limit) {
          isFirstSlice = (data[headerOffset] & 0x80) != 0;
          lookingForFirstSliceFlag = false;
        } else {
          nalUnitBytesRead += limit - offset;
        }
      }
    }

    public void endNalUnit(long position, int offset, boolean hasOutputFormat) {
      if (readingPrefix && isFirstSlice) {
        // This sample has parameter sets. Reset the key-frame flag based on the first slice.
        sampleIsKeyframe = nalUnitHasKeyframeData;
        readingPrefix = false;
      } else if (isFirstPrefixNalUnit || isFirstSlice) {
        // This NAL unit is at the start of a new sample (access unit).
        if (hasOutputFormat && readingSample) {
          // Output the sample ending before this NAL unit.
          int nalUnitLength = (int) (position - nalUnitPosition);
          outputSample(offset + nalUnitLength);
        }
        samplePosition = nalUnitPosition;
        sampleTimeUs = nalUnitTimeUs;
        sampleIsKeyframe = nalUnitHasKeyframeData;
        readingSample = true;
      }
    }

    private void outputSample(int offset) {
      if (sampleTimeUs == C.TIME_UNSET || nalUnitPosition == samplePosition) {
        return;
      }
      @C.BufferFlags int flags = sampleIsKeyframe ? C.BUFFER_FLAG_KEY_FRAME : 0;
      int size = (int) (nalUnitPosition - samplePosition);
      output.sampleMetadata(sampleTimeUs, flags, size, offset, null);
    }

    /** Returns whether a NAL unit type is one that occurs before any VCL NAL units in a sample. */
    private static boolean isPrefixNalUnit(int nalUnitType) {
      return (NalUnitUtil.H265_NAL_UNIT_TYPE_VPS <= nalUnitType
              && nalUnitType <= NalUnitUtil.H265_NAL_UNIT_TYPE_AUD)
          || nalUnitType == NalUnitUtil.H265_NAL_UNIT_TYPE_PREFIX_SEI;
    }

    /** Returns whether a NAL unit type is one that occurs in the VLC body of a sample. */
    private static boolean isVclBodyNalUnit(int nalUnitType) {
      return nalUnitType < NalUnitUtil.H265_NAL_UNIT_TYPE_VPS
          || nalUnitType == NalUnitUtil.H265_NAL_UNIT_TYPE_SUFFIX_SEI;
    }
  }
}
