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
package androidx.media3.extractor.mp3;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.util.Assertions;
import androidx.media3.common.util.Util;
import androidx.media3.extractor.SeekPoint;

/** MP3 seeker that uses metadata from a Xing header. */
/* package */ final class XingSeeker implements Seeker {

  private static final String TAG = "XingSeeker";

  /**
   * Returns a {@link XingSeeker} for seeking in the stream, if required information is present.
   * Returns {@code null} if not. On returning, {@code frame}'s position is not specified so the
   * caller should reset it.
   *
   * @param xingFrame The parsed Xing data from this audio frame.
   * @param position The position of the start of this frame in the stream.
   * @return A {@link XingSeeker} for seeking in the stream, or {@code null} if the required
   *     information is not present.
   */
  @Nullable
  public static XingSeeker create(XingFrame xingFrame, long position) {
    long durationUs = xingFrame.computeDurationUs();
    if (durationUs == C.TIME_UNSET) {
      return null;
    }
    return new XingSeeker(
        position,
        xingFrame.header.frameSize,
        durationUs,
        xingFrame.header.bitrate,
        xingFrame.dataSize,
        xingFrame.tableOfContents);
  }

  private final long dataStartPosition;
  private final int xingFrameSize;
  private final long durationUs;
  private final int bitrate;

  /** Data size, including the XING frame. */
  private final long dataSize;

  private final long dataEndPosition;

  /**
   * Entries are in the range [0, 255], but are stored as long integers for convenience. Null if the
   * table of contents was missing from the header, in which case seeking is not be supported.
   */
  @Nullable private final long[] tableOfContents;

  private XingSeeker(
      long dataStartPosition,
      int xingFrameSize,
      long durationUs,
      int bitrate,
      long dataSize,
      @Nullable long[] tableOfContents) {
    this.dataStartPosition = dataStartPosition;
    this.xingFrameSize = xingFrameSize;
    this.durationUs = durationUs;
    this.bitrate = bitrate;
    this.dataSize = dataSize;
    this.tableOfContents = tableOfContents;
    dataEndPosition = dataSize == C.LENGTH_UNSET ? C.INDEX_UNSET : dataStartPosition + dataSize;
  }

  @Override
  public boolean isSeekable() {
    return tableOfContents != null;
  }

  @Override
  public SeekPoints getSeekPoints(long timeUs) {
    if (!isSeekable()) {
      return new SeekPoints(new SeekPoint(0, dataStartPosition + xingFrameSize));
    }
    timeUs = Util.constrainValue(timeUs, 0, durationUs);
    double percent = (timeUs * 100d) / durationUs;
    double scaledPosition;
    if (percent <= 0) {
      scaledPosition = 0;
    } else if (percent >= 100) {
      scaledPosition = 256;
    } else {
      int prevTableIndex = (int) percent;
      long[] tableOfContents = Assertions.checkStateNotNull(this.tableOfContents);
      double prevScaledPosition = tableOfContents[prevTableIndex];
      double nextScaledPosition = prevTableIndex == 99 ? 256 : tableOfContents[prevTableIndex + 1];
      // Linearly interpolate between the two scaled positions.
      double interpolateFraction = percent - prevTableIndex;
      scaledPosition =
          prevScaledPosition + (interpolateFraction * (nextScaledPosition - prevScaledPosition));
    }
    long positionOffset = Math.round((scaledPosition / 256) * dataSize);
    // Ensure returned positions skip the frame containing the XING header.
    positionOffset = Util.constrainValue(positionOffset, xingFrameSize, dataSize - 1);
    return new SeekPoints(new SeekPoint(timeUs, dataStartPosition + positionOffset));
  }

  @Override
  public long getTimeUs(long position) {
    long positionOffset = position - dataStartPosition;
    if (!isSeekable() || positionOffset <= xingFrameSize) {
      return 0L;
    }
    long[] tableOfContents = Assertions.checkStateNotNull(this.tableOfContents);
    double scaledPosition = (positionOffset * 256d) / dataSize;
    int prevTableIndex = Util.binarySearchFloor(tableOfContents, (long) scaledPosition, true, true);
    long prevTimeUs = getTimeUsForTableIndex(prevTableIndex);
    long prevScaledPosition = tableOfContents[prevTableIndex];
    long nextTimeUs = getTimeUsForTableIndex(prevTableIndex + 1);
    long nextScaledPosition = prevTableIndex == 99 ? 256 : tableOfContents[prevTableIndex + 1];
    // Linearly interpolate between the two table entries.
    double interpolateFraction =
        prevScaledPosition == nextScaledPosition
            ? 0
            : ((scaledPosition - prevScaledPosition) / (nextScaledPosition - prevScaledPosition));
    return prevTimeUs + Math.round(interpolateFraction * (nextTimeUs - prevTimeUs));
  }

  @Override
  public long getDurationUs() {
    return durationUs;
  }

  @Override
  public long getDataStartPosition() {
    return dataStartPosition + xingFrameSize;
  }

  @Override
  public long getDataEndPosition() {
    return dataEndPosition;
  }

  @Override
  public int getAverageBitrate() {
    return bitrate;
  }

  /**
   * Returns the time in microseconds for a given table index.
   *
   * @param tableIndex A table index in the range [0, 100].
   * @return The corresponding time in microseconds.
   */
  private long getTimeUsForTableIndex(int tableIndex) {
    return (durationUs * tableIndex) / 100;
  }
}
