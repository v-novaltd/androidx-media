/*
 * Copyright (C) 2017 The Android Open Source Project
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
package androidx.media3.common.audio;

import static androidx.media3.common.util.Assertions.checkArgument;

import androidx.annotation.IntRange;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;

/**
 * Interface for audio processors, which take audio data as input and transform it, potentially
 * modifying its channel count, encoding and/or sample rate.
 *
 * <p>In addition to being able to modify the format of audio, implementations may allow parameters
 * to be set that affect the output audio and whether the processor is active/inactive.
 */
@UnstableApi
public interface AudioProcessor {

  /** PCM audio format that may be handled by an audio processor. */
  final class AudioFormat {
    /**
     * An {@link AudioFormat} instance to represent an unset {@link AudioFormat}. This should not be
     * returned by {@link #configure(AudioFormat)} if the processor {@link #isActive()}.
     *
     * <p>Typically used to represent an inactive {@link AudioProcessor} {@linkplain
     * #configure(AudioFormat) output format}.
     */
    public static final AudioFormat NOT_SET =
        new AudioFormat(
            /* sampleRate= */ Format.NO_VALUE,
            /* channelCount= */ Format.NO_VALUE,
            /* encoding= */ Format.NO_VALUE);

    /** The sample rate in Hertz. */
    public final int sampleRate;

    /** The number of interleaved channels. */
    public final int channelCount;

    /** The type of linear PCM encoding. */
    public final @C.PcmEncoding int encoding;

    /** The number of bytes used to represent one audio frame. */
    public final int bytesPerFrame;

    /**
     * Creates an instance using the {@link Format#sampleRate}, {@link Format#channelCount} and
     * {@link Format#pcmEncoding}.
     */
    public AudioFormat(Format format) {
      this(format.sampleRate, format.channelCount, format.pcmEncoding);
    }

    public AudioFormat(int sampleRate, int channelCount, @C.PcmEncoding int encoding) {
      this.sampleRate = sampleRate;
      this.channelCount = channelCount;
      this.encoding = encoding;
      bytesPerFrame =
          Util.isEncodingLinearPcm(encoding)
              ? Util.getPcmFrameSize(encoding, channelCount)
              : Format.NO_VALUE;
    }

    @Override
    public String toString() {
      return "AudioFormat["
          + "sampleRate="
          + sampleRate
          + ", channelCount="
          + channelCount
          + ", encoding="
          + encoding
          + ']';
    }

    @Override
    public boolean equals(@Nullable Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof AudioFormat)) {
        return false;
      }
      AudioFormat that = (AudioFormat) o;
      return sampleRate == that.sampleRate
          && channelCount == that.channelCount
          && encoding == that.encoding;
    }

    @Override
    public int hashCode() {
      return Objects.hash(sampleRate, channelCount, encoding);
    }
  }

  /** Exception thrown when the given {@link AudioFormat} can not be handled. */
  final class UnhandledAudioFormatException extends Exception {
    public final AudioFormat inputAudioFormat;

    public UnhandledAudioFormatException(AudioFormat inputAudioFormat) {
      this("Unhandled input format:", inputAudioFormat);
    }

    public UnhandledAudioFormatException(String message, AudioFormat audioFormat) {
      super(message + " " + audioFormat);
      this.inputAudioFormat = audioFormat;
    }
  }

  /**
   * Record class that holds information about the underlying stream being processed by an {@link
   * AudioProcessor}
   */
  final class StreamMetadata {

    /** A {@link StreamMetadata} instance populated with default values. */
    public static final StreamMetadata DEFAULT = new StreamMetadata(/* positionOffsetUs= */ 0);

    /**
     * The position of the underlying stream in microseconds from which the processor will start
     * receiving input buffers after a call to {@link #flush(StreamMetadata)}.
     */
    public final long positionOffsetUs;

    /**
     * Creates a new instance with the specified {@code positionOffsetUs}.
     *
     * @param positionOffsetUs The stream position in microseconds from which the processor will
     *     start receiving input buffers after a call to {@link #flush(StreamMetadata)}
     */
    public StreamMetadata(@IntRange(from = 0) long positionOffsetUs) {
      checkArgument(positionOffsetUs >= 0);
      this.positionOffsetUs = positionOffsetUs;
    }
  }

  /** An empty, direct {@link ByteBuffer}. */
  ByteBuffer EMPTY_BUFFER = ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder());

  /**
   * Returns the expected duration of the output stream when the processor is applied given a input
   * {@code durationUs}.
   */
  default long getDurationAfterProcessorApplied(long durationUs) {
    return durationUs;
  }

  /**
   * Configures the processor to process input audio with the specified format. After calling this
   * method, call {@link #isActive()} to determine whether the audio processor is active. Returns
   * the configured output audio format if this instance is active.
   *
   * <p>After calling this method, it is necessary to {@link #flush()} the processor to apply the
   * new configuration. Before applying the new configuration, it is safe to queue input and get
   * output in the old input/output formats. Call {@link #queueEndOfStream()} when no more input
   * will be supplied in the old input format.
   *
   * @param inputAudioFormat The format of audio that will be queued after the next call to {@link
   *     #flush()}.
   * @return The configured output audio format if this instance is {@link #isActive() active}.
   * @throws UnhandledAudioFormatException Thrown if the specified format can't be handled as input.
   */
  AudioFormat configure(AudioFormat inputAudioFormat) throws UnhandledAudioFormatException;

  /** Returns whether the processor is configured and will process input buffers. */
  boolean isActive();

  /**
   * Queues audio data between the position and limit of the {@code inputBuffer} for processing.
   * After calling this method, processed output may be available via {@link #getOutput()}. Calling
   * {@code queueInput(ByteBuffer)} again invalidates any pending output.
   *
   * @param inputBuffer The input buffer to process. It must be a direct byte buffer with native
   *     byte order. Its contents are treated as read-only. Its position will be advanced by the
   *     number of bytes consumed (which may be zero). The caller retains ownership of the provided
   *     buffer.
   */
  void queueInput(ByteBuffer inputBuffer);

  /**
   * Queues an end of stream signal. After this method has been called, {@link
   * #queueInput(ByteBuffer)} may not be called until after the next call to {@link #flush()}.
   * Calling {@link #getOutput()} will return any remaining output data. Multiple calls may be
   * required to read all of the remaining output data. {@link #isEnded()} will return {@code true}
   * once all remaining output data has been read.
   */
  void queueEndOfStream();

  /**
   * Returns a buffer containing processed output data between its position and limit. The buffer
   * will always be a direct byte buffer with native byte order. Calling this method invalidates any
   * previously returned buffer. The buffer will be empty if no output is available.
   *
   * @return A buffer containing processed output data between its position and limit.
   */
  ByteBuffer getOutput();

  /**
   * Returns whether this processor will return no more output from {@link #getOutput()} until
   * {@link #flush()} has been called and more input has been queued.
   */
  boolean isEnded();

  /**
   * Clears any buffered data and pending output. If the audio processor is active, also prepares
   * the audio processor to receive a new stream of input in the last configured (pending) format.
   *
   * <p>This is equivalent to {@code flush(new StreamMetadata(C.TIME_UNSET))}.
   *
   * @deprecated Use {@link #flush(StreamMetadata)} instead.
   */
  @Deprecated
  default void flush() {
    throw new IllegalStateException(
        "AudioProcessor must implement at least one #flush() overload.");
  }

  /**
   * Prepares the audio processor to receive a new stream of input in the last {@linkplain
   * #configure configured format} and clears any buffered data and pending output.
   *
   * <p>This method is a no-op if the processor is not {@linkplain #isActive() active}.
   */
  default void flush(StreamMetadata streamMetadata) {
    flush();
  }

  /** Resets the processor to its unconfigured state, releasing any resources. */
  void reset();
}
