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

import static androidx.media3.common.util.Assertions.checkNotNull;
import static java.lang.annotation.ElementType.TYPE_USE;
import static java.lang.annotation.RetentionPolicy.SOURCE;

import androidx.annotation.IntDef;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.Metadata;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.Assertions;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.extractor.DiscardingTrackOutput;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.ExtractorInput;
import androidx.media3.extractor.ExtractorOutput;
import androidx.media3.extractor.ExtractorsFactory;
import androidx.media3.extractor.GaplessInfoHolder;
import androidx.media3.extractor.Id3Peeker;
import androidx.media3.extractor.MpegAudioUtil;
import androidx.media3.extractor.PositionHolder;
import androidx.media3.extractor.TrackOutput;
import androidx.media3.extractor.metadata.id3.Id3Decoder;
import androidx.media3.extractor.metadata.id3.Id3Decoder.FramePredicate;
import androidx.media3.extractor.metadata.id3.MlltFrame;
import androidx.media3.extractor.metadata.id3.TextInformationFrame;
import androidx.media3.extractor.mp3.Seeker.UnseekableSeeker;
import com.google.common.math.LongMath;
import com.google.common.primitives.Ints;
import java.io.EOFException;
import java.io.IOException;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.math.RoundingMode;
import org.checkerframework.checker.nullness.qual.EnsuresNonNull;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.RequiresNonNull;

/** Extracts data from the MP3 container format. */
@UnstableApi
public final class Mp3Extractor implements Extractor {

  /** Factory for {@link Mp3Extractor} instances. */
  public static final ExtractorsFactory FACTORY = () -> new Extractor[] {new Mp3Extractor()};

  /**
   * Flags controlling the behavior of the extractor. Possible flag values are {@link
   * #FLAG_ENABLE_CONSTANT_BITRATE_SEEKING}, {@link #FLAG_ENABLE_CONSTANT_BITRATE_SEEKING_ALWAYS},
   * {@link #FLAG_ENABLE_INDEX_SEEKING} and {@link #FLAG_DISABLE_ID3_METADATA}.
   */
  @Documented
  @Retention(SOURCE)
  @Target(TYPE_USE)
  @IntDef(
      flag = true,
      value = {
        FLAG_ENABLE_CONSTANT_BITRATE_SEEKING,
        FLAG_ENABLE_CONSTANT_BITRATE_SEEKING_ALWAYS,
        FLAG_ENABLE_INDEX_SEEKING,
        FLAG_DISABLE_ID3_METADATA
      })
  public @interface Flags {}

  /**
   * Flag to force enable seeking using a constant bitrate assumption in cases where seeking would
   * otherwise not be possible.
   *
   * <p>This flag is ignored if {@link #FLAG_ENABLE_INDEX_SEEKING} is set.
   */
  public static final int FLAG_ENABLE_CONSTANT_BITRATE_SEEKING = 1;

  /**
   * Like {@link #FLAG_ENABLE_CONSTANT_BITRATE_SEEKING}, except that seeking is also enabled in
   * cases where the content length (and hence the duration of the media) is unknown. Application
   * code should ensure that requested seek positions are valid when using this flag, or be ready to
   * handle playback failures reported through {@link Player.Listener#onPlayerError} with {@link
   * PlaybackException#errorCode} set to {@link
   * PlaybackException#ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE}.
   *
   * <p>If this flag is set, then the behavior enabled by {@link
   * #FLAG_ENABLE_CONSTANT_BITRATE_SEEKING} is implicitly enabled.
   *
   * <p>This flag is ignored if {@link #FLAG_ENABLE_INDEX_SEEKING} is set.
   */
  public static final int FLAG_ENABLE_CONSTANT_BITRATE_SEEKING_ALWAYS = 1 << 1;

  /**
   * Flag to force index seeking, in which a time-to-byte mapping is built as the file is read.
   *
   * <p>This seeker may require to scan a significant portion of the file to compute a seek point.
   * Therefore, it should only be used if one of the following is true:
   *
   * <ul>
   *   <li>The file is small.
   *   <li>The bitrate is variable (or it's unknown whether it's variable) and the file does not
   *       provide precise enough seeking metadata.
   * </ul>
   */
  public static final int FLAG_ENABLE_INDEX_SEEKING = 1 << 2;

  /**
   * Flag to disable parsing of ID3 metadata. Can be set to save memory if ID3 metadata is not
   * required.
   */
  public static final int FLAG_DISABLE_ID3_METADATA = 1 << 3;

  private static final String TAG = "Mp3Extractor";

  /** Predicate that matches ID3 frames containing only required gapless/seeking metadata. */
  private static final FramePredicate REQUIRED_ID3_FRAME_PREDICATE =
      (majorVersion, id0, id1, id2, id3) ->
          ((id0 == 'C' && id1 == 'O' && id2 == 'M' && (id3 == 'M' || majorVersion == 2))
              || (id0 == 'M' && id1 == 'L' && id2 == 'L' && (id3 == 'T' || majorVersion == 2)));

  /** The maximum number of bytes to search when synchronizing, before giving up. */
  private static final int MAX_SYNC_BYTES = 128 * 1024;

  /**
   * The maximum number of bytes to peek when sniffing, excluding the ID3 header, before giving up.
   */
  private static final int MAX_SNIFF_BYTES = 32 * 1024;

  /** Maximum length of data read into {@link #scratch}. */
  private static final int SCRATCH_LENGTH = 10;

  /** Mask that includes the audio header values that must match between frames. */
  private static final int MPEG_AUDIO_HEADER_MASK = 0xFFFE0C00;

  @Documented
  @Target(TYPE_USE)
  @Retention(SOURCE)
  @IntDef(value = {SEEK_HEADER_XING, SEEK_HEADER_INFO, SEEK_HEADER_VBRI, SEEK_HEADER_UNSET})
  private @interface SeekHeader {}

  private static final int SEEK_HEADER_XING = 0x58696e67;
  private static final int SEEK_HEADER_INFO = 0x496e666f;
  private static final int SEEK_HEADER_VBRI = 0x56425249;
  private static final int SEEK_HEADER_UNSET = 0;

  private final @Flags int flags;
  private final long forcedFirstSampleTimestampUs;
  private final ParsableByteArray scratch;
  private final MpegAudioUtil.Header synchronizedHeader;
  private final GaplessInfoHolder gaplessInfoHolder;
  private final Id3Peeker id3Peeker;
  private final TrackOutput skippingTrackOutput;

  private @MonotonicNonNull ExtractorOutput extractorOutput;
  private @MonotonicNonNull TrackOutput realTrackOutput;
  private TrackOutput currentTrackOutput; // skippingTrackOutput or realTrackOutput.

  private int synchronizedHeaderData;

  @Nullable private Metadata metadata;
  private long basisTimeUs;
  private long samplesRead;
  private long firstSamplePosition;
  private long endPositionOfLastSampleRead;
  private int sampleBytesRemaining;

  private @MonotonicNonNull Seeker seeker;
  private boolean disableSeeking;
  private boolean isSeekInProgress;
  private long seekTimeUs;

  public Mp3Extractor() {
    this(0);
  }

  /**
   * @param flags Flags that control the extractor's behavior.
   */
  public Mp3Extractor(@Flags int flags) {
    this(flags, C.TIME_UNSET);
  }

  /**
   * @param flags Flags that control the extractor's behavior.
   * @param forcedFirstSampleTimestampUs A timestamp to force for the first sample, or {@link
   *     C#TIME_UNSET} if forcing is not required.
   */
  public Mp3Extractor(@Flags int flags, long forcedFirstSampleTimestampUs) {
    if ((flags & FLAG_ENABLE_CONSTANT_BITRATE_SEEKING_ALWAYS) != 0) {
      flags |= FLAG_ENABLE_CONSTANT_BITRATE_SEEKING;
    }
    this.flags = flags;
    this.forcedFirstSampleTimestampUs = forcedFirstSampleTimestampUs;
    scratch = new ParsableByteArray(SCRATCH_LENGTH);
    synchronizedHeader = new MpegAudioUtil.Header();
    gaplessInfoHolder = new GaplessInfoHolder();
    basisTimeUs = C.TIME_UNSET;
    id3Peeker = new Id3Peeker();
    skippingTrackOutput = new DiscardingTrackOutput();
    currentTrackOutput = skippingTrackOutput;
    endPositionOfLastSampleRead = C.INDEX_UNSET;
  }

  // Extractor implementation.

  @Override
  public boolean sniff(ExtractorInput input) throws IOException {
    return synchronize(input, true);
  }

  @Override
  public void init(ExtractorOutput output) {
    extractorOutput = output;
    realTrackOutput = extractorOutput.track(0, C.TRACK_TYPE_AUDIO);
    currentTrackOutput = realTrackOutput;
    extractorOutput.endTracks();
  }

  @Override
  public void seek(long position, long timeUs) {
    synchronizedHeaderData = 0;
    basisTimeUs = C.TIME_UNSET;
    samplesRead = 0;
    sampleBytesRemaining = 0;
    seekTimeUs = timeUs;
    if (seeker instanceof IndexSeeker && !((IndexSeeker) seeker).isTimeUsInIndex(timeUs)) {
      isSeekInProgress = true;
      currentTrackOutput = skippingTrackOutput;
    }
  }

  @Override
  public void release() {
    // Do nothing
  }

  @Override
  public int read(ExtractorInput input, PositionHolder seekPosition) throws IOException {
    assertInitialized();
    int readResult = readInternal(input);
    if (readResult == RESULT_END_OF_INPUT && seeker instanceof IndexSeeker) {
      // Duration is exact when index seeker is used.
      long durationUs = computeTimeUs(samplesRead);
      if (seeker.getDurationUs() != durationUs) {
        ((IndexSeeker) seeker).setDurationUs(durationUs);
        extractorOutput.seekMap(seeker);
        realTrackOutput.durationUs(seeker.getDurationUs());
      }
    }
    return readResult;
  }

  /**
   * Disables the extractor from being able to seek through the media.
   *
   * <p>Please note that this needs to be called before {@link #read}.
   */
  public void disableSeeking() {
    disableSeeking = true;
  }

  // Internal methods.

  @RequiresNonNull({"extractorOutput", "realTrackOutput"})
  private int readInternal(ExtractorInput input) throws IOException {
    if (synchronizedHeaderData == 0) {
      try {
        synchronize(input, false);
      } catch (EOFException e) {
        return RESULT_END_OF_INPUT;
      }
    }
    if (seeker == null) {
      seeker = computeSeeker(input);
      extractorOutput.seekMap(seeker);
      Format.Builder format =
          new Format.Builder()
              .setContainerMimeType(MimeTypes.AUDIO_MPEG)
              .setSampleMimeType(synchronizedHeader.mimeType)
              .setMaxInputSize(MpegAudioUtil.MAX_FRAME_SIZE_BYTES)
              .setChannelCount(synchronizedHeader.channels)
              .setSampleRate(synchronizedHeader.sampleRate)
              .setEncoderDelay(gaplessInfoHolder.encoderDelay)
              .setEncoderPadding(gaplessInfoHolder.encoderPadding)
              .setMetadata((flags & FLAG_DISABLE_ID3_METADATA) != 0 ? null : metadata);
      if (seeker.getAverageBitrate() != C.RATE_UNSET_INT) {
        format.setAverageBitrate(seeker.getAverageBitrate());
      }
      currentTrackOutput.format(format.build());
      firstSamplePosition = input.getPosition();
    } else if (firstSamplePosition != 0) {
      long inputPosition = input.getPosition();
      if (inputPosition < firstSamplePosition) {
        // Skip past the seek frame.
        input.skipFully((int) (firstSamplePosition - inputPosition));
      }
    }
    return readSample(input);
  }

  @RequiresNonNull({"realTrackOutput", "seeker"})
  private int readSample(ExtractorInput extractorInput) throws IOException {
    if (sampleBytesRemaining == 0) {
      extractorInput.resetPeekPosition();
      if (peekEndOfStreamOrHeader(extractorInput)) {
        return RESULT_END_OF_INPUT;
      }
      scratch.setPosition(0);
      int sampleHeaderData = scratch.readInt();
      if (!headersMatch(sampleHeaderData, synchronizedHeaderData)
          || MpegAudioUtil.getFrameSize(sampleHeaderData) == C.LENGTH_UNSET) {
        // We have lost synchronization, so attempt to resynchronize starting at the next byte.
        extractorInput.skipFully(1);
        synchronizedHeaderData = 0;
        return RESULT_CONTINUE;
      }
      synchronizedHeader.setForHeaderData(sampleHeaderData);
      if (basisTimeUs == C.TIME_UNSET) {
        basisTimeUs = seeker.getTimeUs(extractorInput.getPosition());
        if (forcedFirstSampleTimestampUs != C.TIME_UNSET) {
          long embeddedFirstSampleTimestampUs = seeker.getTimeUs(0);
          basisTimeUs += forcedFirstSampleTimestampUs - embeddedFirstSampleTimestampUs;
        }
      }
      sampleBytesRemaining = synchronizedHeader.frameSize;
      endPositionOfLastSampleRead = extractorInput.getPosition() + synchronizedHeader.frameSize;
      if (seeker instanceof IndexSeeker) {
        IndexSeeker indexSeeker = (IndexSeeker) seeker;
        // Add seek point corresponding to the next frame instead of the current one to be able to
        // start writing to the realTrackOutput on time when a seek is in progress.
        indexSeeker.maybeAddSeekPoint(
            computeTimeUs(samplesRead + synchronizedHeader.samplesPerFrame),
            endPositionOfLastSampleRead);
        if (isSeekInProgress && indexSeeker.isTimeUsInIndex(seekTimeUs)) {
          isSeekInProgress = false;
          currentTrackOutput = realTrackOutput;
        }
      }
    }
    int bytesAppended = currentTrackOutput.sampleData(extractorInput, sampleBytesRemaining, true);
    if (bytesAppended == C.RESULT_END_OF_INPUT) {
      return RESULT_END_OF_INPUT;
    }
    sampleBytesRemaining -= bytesAppended;
    if (sampleBytesRemaining > 0) {
      return RESULT_CONTINUE;
    }
    currentTrackOutput.sampleMetadata(
        computeTimeUs(samplesRead), C.BUFFER_FLAG_KEY_FRAME, synchronizedHeader.frameSize, 0, null);
    samplesRead += synchronizedHeader.samplesPerFrame;
    sampleBytesRemaining = 0;
    return RESULT_CONTINUE;
  }

  private long computeTimeUs(long samplesRead) {
    return basisTimeUs + samplesRead * C.MICROS_PER_SECOND / synchronizedHeader.sampleRate;
  }

  private boolean synchronize(ExtractorInput input, boolean sniffing) throws IOException {
    int validFrameCount = 0;
    int candidateSynchronizedHeaderData = 0;
    int peekedId3Bytes = 0;
    int searchedBytes = 0;
    int searchLimitBytes = sniffing ? MAX_SNIFF_BYTES : MAX_SYNC_BYTES;
    input.resetPeekPosition();
    if (input.getPosition() == 0) {
      // We need to parse enough ID3 metadata to retrieve any gapless/seeking playback information
      // even if ID3 metadata parsing is disabled.
      boolean parseAllId3Frames = (flags & FLAG_DISABLE_ID3_METADATA) == 0;
      Id3Decoder.FramePredicate id3FramePredicate =
          parseAllId3Frames ? null : REQUIRED_ID3_FRAME_PREDICATE;
      metadata = id3Peeker.peekId3Data(input, id3FramePredicate, searchLimitBytes);
      if (metadata != null) {
        gaplessInfoHolder.setFromMetadata(metadata);
      }
      peekedId3Bytes = (int) input.getPeekPosition();
      if (!sniffing) {
        input.skipFully(peekedId3Bytes);
      }
    }
    while (true) {
      if (peekEndOfStreamOrHeader(input)) {
        if (validFrameCount > 0) {
          // We reached the end of the stream but found at least one valid frame.
          break;
        }
        maybeUpdateCbrDurationToLastSample();
        throw new EOFException();
      }
      scratch.setPosition(0);
      int headerData = scratch.readInt();
      int frameSize;
      if ((candidateSynchronizedHeaderData != 0
              && !headersMatch(headerData, candidateSynchronizedHeaderData))
          || (frameSize = MpegAudioUtil.getFrameSize(headerData)) == C.LENGTH_UNSET) {
        // The header doesn't match the candidate header or is invalid. Try the next byte offset.
        if (searchedBytes++ == searchLimitBytes) {
          if (!sniffing) {
            maybeUpdateCbrDurationToLastSample();
            throw new EOFException();
          }
          return false;
        }
        validFrameCount = 0;
        candidateSynchronizedHeaderData = 0;
        if (sniffing) {
          input.resetPeekPosition();
          input.advancePeekPosition(peekedId3Bytes + searchedBytes);
        } else {
          input.skipFully(1);
        }
      } else {
        // The header matches the candidate header and/or is valid.
        validFrameCount++;
        if (validFrameCount == 1) {
          synchronizedHeader.setForHeaderData(headerData);
          candidateSynchronizedHeaderData = headerData;
        } else if (validFrameCount == 4) {
          break;
        }
        input.advancePeekPosition(frameSize - 4);
      }
    }
    // Prepare to read the synchronized frame.
    if (sniffing) {
      input.skipFully(peekedId3Bytes + searchedBytes);
    } else {
      input.resetPeekPosition();
    }
    synchronizedHeaderData = candidateSynchronizedHeaderData;
    return true;
  }

  /**
   * Returns whether the extractor input is peeking the end of the stream. If {@code false},
   * populates the scratch buffer with the next four bytes.
   */
  private boolean peekEndOfStreamOrHeader(ExtractorInput extractorInput) throws IOException {
    if (seeker != null) {
      long dataEndPosition = seeker.getDataEndPosition();
      if (dataEndPosition != C.INDEX_UNSET
          && extractorInput.getPeekPosition() > dataEndPosition - 4) {
        return true;
      }
    }
    try {
      return !extractorInput.peekFully(
          scratch.getData(), /* offset= */ 0, /* length= */ 4, /* allowEndOfInput= */ true);
    } catch (EOFException e) {
      return true;
    }
  }

  @RequiresNonNull("realTrackOutput")
  private Seeker computeSeeker(ExtractorInput input) throws IOException {
    // Read past any seek frame and set the seeker based on metadata or a seek frame. Metadata
    // takes priority as it can provide greater precision.
    Seeker seekFrameSeeker = maybeReadSeekFrame(input);
    Seeker metadataSeeker = maybeHandleSeekMetadata(metadata, input.getPosition());

    if (disableSeeking) {
      return new UnseekableSeeker();
    }

    @Nullable Seeker resultSeeker = null;
    if ((flags & FLAG_ENABLE_INDEX_SEEKING) != 0) {
      long durationUs;
      long dataEndPosition = C.INDEX_UNSET;
      if (metadataSeeker != null) {
        durationUs = metadataSeeker.getDurationUs();
        dataEndPosition = metadataSeeker.getDataEndPosition();
      } else if (seekFrameSeeker != null) {
        durationUs = seekFrameSeeker.getDurationUs();
        dataEndPosition = seekFrameSeeker.getDataEndPosition();
      } else {
        durationUs = getId3TlenUs(metadata);
      }
      resultSeeker =
          new IndexSeeker(
              durationUs, /* dataStartPosition= */ input.getPosition(), dataEndPosition);
    } else if (metadataSeeker != null) {
      resultSeeker = metadataSeeker;
    } else if (seekFrameSeeker != null) {
      resultSeeker = seekFrameSeeker;
    }

    if (resultSeeker != null
        && shouldFallbackToConstantBitrateSeeking(resultSeeker)
        && resultSeeker.getDurationUs() != C.TIME_UNSET
        && (resultSeeker.getDataEndPosition() != C.INDEX_UNSET
            || input.getLength() != C.LENGTH_UNSET)) {
      // resultSeeker does not allow seeking, but does provide a duration and constant bitrate
      // seeking has been requested, so we can do 'enhanced' CBR seeking using this duration info.
      long dataStart =
          resultSeeker.getDataStartPosition() != C.INDEX_UNSET
              ? resultSeeker.getDataStartPosition()
              : 0;
      long inputLength =
          resultSeeker.getDataEndPosition() != C.INDEX_UNSET
              ? resultSeeker.getDataEndPosition()
              : input.getLength();
      long audioLength = inputLength - dataStart;
      int bitrate =
          Ints.saturatedCast(
              Util.scaleLargeValue(
                  audioLength,
                  Byte.SIZE * C.MICROS_PER_SECOND,
                  resultSeeker.getDurationUs(),
                  RoundingMode.HALF_UP));
      // inputLength will never be LENGTH_UNSET because of the outer if-condition, so we can pass
      // (vacuously) false here for allowSeeksIfLengthUnknown.
      resultSeeker =
          new ConstantBitrateSeeker(
              inputLength,
              dataStart,
              bitrate,
              C.LENGTH_UNSET,
              /* allowSeeksIfLengthUnknown= */ false);
    } else if (resultSeeker == null || shouldFallbackToConstantBitrateSeeking(resultSeeker)) {
      // Either we found no seek or VBR info, so we must assume the file is CBR (even without the
      // flag(s) being set), or an 'enable CBR seeking flag' is set and we found some seek info, but
      // not enough to do 'enhanced' CBR seeking with. In either case, we fall back to CBR seeking
      // without any additional info from the file.
      resultSeeker =
          getConstantBitrateSeeker(
              input, (flags & FLAG_ENABLE_CONSTANT_BITRATE_SEEKING_ALWAYS) != 0);
    }
    realTrackOutput.durationUs(resultSeeker.getDurationUs());
    return resultSeeker;
  }

  private boolean shouldFallbackToConstantBitrateSeeking(Seeker seeker) {
    return !seeker.isSeekable() && (flags & FLAG_ENABLE_CONSTANT_BITRATE_SEEKING) != 0;
  }

  /**
   * Consumes the next frame from the {@code input} if it contains VBRI or Xing seeking metadata,
   * returning a {@link Seeker} if the metadata was present and valid, or {@code null} otherwise.
   * After this method returns, the input position is the start of the first frame of audio.
   *
   * @param input The {@link ExtractorInput} from which to read.
   * @return A {@link Seeker} if seeking metadata was present and valid, or {@code null} otherwise.
   * @throws IOException Thrown if there was an error reading from the stream. Not expected if the
   *     next two frames were already peeked during synchronization.
   */
  @Nullable
  private Seeker maybeReadSeekFrame(ExtractorInput input) throws IOException {
    ParsableByteArray frame = new ParsableByteArray(synchronizedHeader.frameSize);
    input.peekFully(frame.getData(), 0, synchronizedHeader.frameSize);
    int xingBase =
        (synchronizedHeader.version & 1) != 0
            ? (synchronizedHeader.channels != 1 ? 36 : 21) // MPEG 1
            : (synchronizedHeader.channels != 1 ? 21 : 13); // MPEG 2 or 2.5
    @SeekHeader int seekHeader = getSeekFrameHeader(frame, xingBase);
    @Nullable Seeker seeker;
    switch (seekHeader) {
      case SEEK_HEADER_XING:
      case SEEK_HEADER_INFO:
        XingFrame xingFrame = XingFrame.parse(synchronizedHeader, frame);
        if (!gaplessInfoHolder.hasGaplessInfo()
            && xingFrame.encoderDelay != C.LENGTH_UNSET
            && xingFrame.encoderPadding != C.LENGTH_UNSET) {
          gaplessInfoHolder.encoderDelay = xingFrame.encoderDelay;
          gaplessInfoHolder.encoderPadding = xingFrame.encoderPadding;
        }
        long startPosition = input.getPosition();
        if (input.getLength() != C.LENGTH_UNSET
            && xingFrame.dataSize != C.LENGTH_UNSET
            && input.getLength() != startPosition + xingFrame.dataSize) {
          Log.i(
              TAG,
              "Data size mismatch between stream ("
                  + input.getLength()
                  + ") and Xing frame ("
                  + (startPosition + xingFrame.dataSize)
                  + "), using Xing value.");
        }
        input.skipFully(synchronizedHeader.frameSize);
        // An Xing frame indicates the file is VBR (so we have to use the seek header for seeking)
        // while an Info header indicates the file is CBR, in which case ConstantBitrateSeeker will
        // give more accurate seeking than the low-resolution seek table in the Info header. We can
        // still use the length from the Info frame if we don't know the stream length directly.
        if (seekHeader == SEEK_HEADER_XING) {
          seeker = XingSeeker.create(xingFrame, startPosition);
        } else { // seekHeader == SEEK_HEADER_INFO
          seeker = getConstantBitrateSeeker(startPosition, xingFrame, input.getLength());
        }
        break;
      case SEEK_HEADER_VBRI:
        seeker =
            VbriSeeker.create(input.getLength(), input.getPosition(), synchronizedHeader, frame);
        input.skipFully(synchronizedHeader.frameSize);
        break;
      case SEEK_HEADER_UNSET:
      default:
        // This frame doesn't contain seeking information, so reset the peek position.
        seeker = null;
        input.resetPeekPosition();
    }
    return seeker;
  }

  /** Peeks the next frame and returns a {@link ConstantBitrateSeeker} based on its bitrate. */
  private Seeker getConstantBitrateSeeker(ExtractorInput input, boolean allowSeeksIfLengthUnknown)
      throws IOException {
    input.peekFully(scratch.getData(), 0, 4);
    scratch.setPosition(0);
    synchronizedHeader.setForHeaderData(scratch.readInt());
    return new ConstantBitrateSeeker(
        input.getLength(), input.getPosition(), synchronizedHeader, allowSeeksIfLengthUnknown);
  }

  /**
   * Returns a {@link ConstantBitrateSeeker} based on the provided {@link XingFrame Info frame}.
   *
   * @param infoFramePosition The position of the Info frame (from the beginning of the stream).
   * @param infoFrame The parsed Info frame.
   * @param fallbackStreamLength The complete length of the input stream (only used if {@link
   *     XingFrame#dataSize} is unset). Can be {@link C#LENGTH_UNSET} if the length is not known.
   * @return A {@link Seeker} if the {@link XingFrame} contains enough info to seek, or {@code null}
   *     otherwise.
   */
  @Nullable
  private Seeker getConstantBitrateSeeker(
      long infoFramePosition, XingFrame infoFrame, long fallbackStreamLength) {
    long durationUs = infoFrame.computeDurationUs();
    if (durationUs == C.TIME_UNSET) {
      return null;
    }
    long streamLength;
    long audioLength;
    // Prefer the stream length from the Info frame, because it may deliberately
    // exclude some unplayable/non-MP3 trailer data (e.g. album artwork), see
    // https://github.com/androidx/media/issues/1376#issuecomment-2117211184.
    if (infoFrame.dataSize != C.LENGTH_UNSET) {
      streamLength = infoFramePosition + infoFrame.dataSize;
      audioLength = infoFrame.dataSize - infoFrame.header.frameSize;
    } else if (fallbackStreamLength != C.LENGTH_UNSET) {
      streamLength = fallbackStreamLength;
      audioLength = fallbackStreamLength - infoFramePosition - infoFrame.header.frameSize;
    } else {
      return null;
    }

    // Derive the bitrate and frame size by averaging over the length of playable audio, to allow
    // for 'mostly' CBR streams that might have a small number of frames with a different bitrate.
    // We can assume infoFrame.frameCount is set, because otherwise computeDurationUs() would
    // have returned C.TIME_UNSET above. See also https://github.com/androidx/media/issues/1376.
    int averageBitrate =
        Ints.checkedCast(
            Util.scaleLargeValue(
                audioLength,
                C.BITS_PER_BYTE * C.MICROS_PER_SECOND,
                durationUs,
                RoundingMode.HALF_UP));
    int frameSize =
        Ints.checkedCast(LongMath.divide(audioLength, infoFrame.frameCount, RoundingMode.HALF_UP));
    // Set the seeker frame size to the average frame size (even though some constant bitrate
    // streams have variable frame sizes due to padding), to avoid the need to re-synchronize for
    // constant frame size streams.
    return new ConstantBitrateSeeker(
        streamLength,
        /* firstFramePosition= */ infoFramePosition + infoFrame.header.frameSize,
        averageBitrate,
        frameSize,
        /* allowSeeksIfLengthUnknown= */ false);
  }

  /**
   * If {@link #seeker} is a seekable {@link ConstantBitrateSeeker}, this updates it to end at the
   * last sample we read (because we've failed to find a subsequent synchronization word so we
   * assume the MP3 data has ended).
   */
  private void maybeUpdateCbrDurationToLastSample() {
    if (seeker instanceof ConstantBitrateSeeker
        && seeker.isSeekable()
        && endPositionOfLastSampleRead != C.INDEX_UNSET
        && endPositionOfLastSampleRead != seeker.getDataEndPosition()) {
      seeker =
          ((ConstantBitrateSeeker) seeker).copyWithNewDataEndPosition(endPositionOfLastSampleRead);
      checkNotNull(extractorOutput).seekMap(seeker);
      checkNotNull(realTrackOutput).durationUs(seeker.getDurationUs());
    }
  }

  @EnsuresNonNull({"extractorOutput", "realTrackOutput"})
  private void assertInitialized() {
    Assertions.checkStateNotNull(realTrackOutput);
    Util.castNonNull(extractorOutput);
  }

  /** Returns whether the headers match in those bits masked by {@link #MPEG_AUDIO_HEADER_MASK}. */
  private static boolean headersMatch(int headerA, long headerB) {
    return (headerA & MPEG_AUDIO_HEADER_MASK) == (headerB & MPEG_AUDIO_HEADER_MASK);
  }

  /**
   * Returns {@link #SEEK_HEADER_XING}, {@link #SEEK_HEADER_INFO} or {@link #SEEK_HEADER_VBRI} if
   * the provided {@code frame} may have seeking metadata, or {@link #SEEK_HEADER_UNSET} otherwise.
   * If seeking metadata is present, {@code frame}'s position is advanced past the header.
   */
  private static @SeekHeader int getSeekFrameHeader(ParsableByteArray frame, int xingBase) {
    if (frame.limit() >= xingBase + 4) {
      frame.setPosition(xingBase);
      int headerData = frame.readInt();
      if (headerData == SEEK_HEADER_XING || headerData == SEEK_HEADER_INFO) {
        return headerData;
      }
    }
    if (frame.limit() >= 40) {
      frame.setPosition(36); // MPEG audio header (4 bytes) + 32 bytes.
      if (frame.readInt() == SEEK_HEADER_VBRI) {
        return SEEK_HEADER_VBRI;
      }
    }
    return SEEK_HEADER_UNSET;
  }

  @Nullable
  private static MlltSeeker maybeHandleSeekMetadata(
      @Nullable Metadata metadata, long firstFramePosition) {
    if (metadata != null) {
      int length = metadata.length();
      for (int i = 0; i < length; i++) {
        Metadata.Entry entry = metadata.get(i);
        if (entry instanceof MlltFrame) {
          return MlltSeeker.create(firstFramePosition, (MlltFrame) entry, getId3TlenUs(metadata));
        }
      }
    }
    return null;
  }

  private static long getId3TlenUs(@Nullable Metadata metadata) {
    if (metadata != null) {
      int length = metadata.length();
      for (int i = 0; i < length; i++) {
        Metadata.Entry entry = metadata.get(i);
        if (entry instanceof TextInformationFrame
            && ((TextInformationFrame) entry).id.equals("TLEN")) {
          return Util.msToUs(Long.parseLong(((TextInformationFrame) entry).values.get(0)));
        }
      }
    }
    return C.TIME_UNSET;
  }
}
