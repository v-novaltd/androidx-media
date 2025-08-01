/*
 * Copyright (C) 2019 The Android Open Source Project
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
package androidx.media3.exoplayer.mediacodec;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaCrypto;
import android.media.MediaFormat;
import android.os.Bundle;
import android.os.Handler;
import android.os.PersistableBundle;
import android.view.Surface;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.decoder.CryptoInfo;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Abstracts {@link MediaCodec} operations.
 *
 * <p>{@code MediaCodecAdapter} offers a common interface to interact with a {@link MediaCodec}
 * regardless of the mode the {@link MediaCodec} is operating in.
 */
@UnstableApi
public interface MediaCodecAdapter {
  /** Configuration parameters for a {@link MediaCodecAdapter}. */
  final class Configuration {

    /**
     * Creates a configuration for audio decoding.
     *
     * @param codecInfo See {@link #codecInfo}.
     * @param mediaFormat See {@link #mediaFormat}.
     * @param format See {@link #format}.
     * @param crypto See {@link #crypto}.
     * @param loudnessCodecController See {@link #loudnessCodecController}.
     * @return The created instance.
     */
    public static Configuration createForAudioDecoding(
        MediaCodecInfo codecInfo,
        MediaFormat mediaFormat,
        Format format,
        @Nullable MediaCrypto crypto,
        @Nullable LoudnessCodecController loudnessCodecController) {
      return new Configuration(
          codecInfo, mediaFormat, format, /* surface= */ null, crypto, loudnessCodecController);
    }

    /**
     * Creates a configuration for video decoding.
     *
     * @param codecInfo See {@link #codecInfo}.
     * @param mediaFormat See {@link #mediaFormat}.
     * @param format See {@link #format}.
     * @param surface See {@link #surface}.
     * @param crypto See {@link #crypto}.
     * @return The created instance.
     */
    public static Configuration createForVideoDecoding(
        MediaCodecInfo codecInfo,
        MediaFormat mediaFormat,
        Format format,
        @Nullable Surface surface,
        @Nullable MediaCrypto crypto) {
      return new Configuration(
          codecInfo, mediaFormat, format, surface, crypto, /* loudnessCodecController= */ null);
    }

    /** Information about the {@link MediaCodec} being configured. */
    public final MediaCodecInfo codecInfo;

    /** The {@link MediaFormat} for which the codec is being configured. */
    public final MediaFormat mediaFormat;

    /** The {@link Format} for which the codec is being configured. */
    public final Format format;

    /**
     * For video decoding, the output where the object will render the decoded frames. This must be
     * null if the codec is not a video decoder, or if it is configured for {@link ByteBuffer}
     * output.
     */
    @Nullable public final Surface surface;

    /** For DRM protected playbacks, a {@link MediaCrypto} to use for decryption. */
    @Nullable public final MediaCrypto crypto;

    /** The {@link LoudnessCodecController} for audio codecs. */
    @Nullable public final LoudnessCodecController loudnessCodecController;

    private Configuration(
        MediaCodecInfo codecInfo,
        MediaFormat mediaFormat,
        Format format,
        @Nullable Surface surface,
        @Nullable MediaCrypto crypto,
        @Nullable LoudnessCodecController loudnessCodecController) {
      this.codecInfo = codecInfo;
      this.mediaFormat = mediaFormat;
      this.format = format;
      this.surface = surface;
      this.crypto = crypto;
      this.loudnessCodecController = loudnessCodecController;
    }
  }

  /** A factory for {@link MediaCodecAdapter} instances. */
  interface Factory {

    /**
     * @deprecated Use {@link #getDefault} instead.
     */
    @Deprecated
    @SuppressWarnings("deprecation") // Forwarding to deprecated method.
    Factory DEFAULT = new DefaultMediaCodecAdapterFactory();

    /**
     * Returns the default factory that should be used in most cases.
     *
     * @param context A {@link Context}.
     * @return The default factory.
     */
    static Factory getDefault(Context context) {
      return new DefaultMediaCodecAdapterFactory(context);
    }

    /** Creates a {@link MediaCodecAdapter} instance. */
    MediaCodecAdapter createAdapter(Configuration configuration) throws IOException;
  }

  /**
   * Listener to be called when an output frame has rendered on the output surface.
   *
   * @see MediaCodec.OnFrameRenderedListener
   */
  interface OnFrameRenderedListener {
    void onFrameRendered(MediaCodecAdapter codec, long presentationTimeUs, long nanoTime);
  }

  /** Listener to be called when an input or output buffer becomes available. */
  interface OnBufferAvailableListener {
    /**
     * Called when an input buffer becomes available.
     *
     * @see MediaCodec.Callback#onInputBufferAvailable(MediaCodec, int)
     */
    default void onInputBufferAvailable() {}

    /**
     * Called when an output buffer becomes available.
     *
     * @see MediaCodec.Callback#onOutputBufferAvailable(MediaCodec, int, MediaCodec.BufferInfo)
     */
    default void onOutputBufferAvailable() {}
  }

  /**
   * Returns the next available input buffer index from the underlying {@link MediaCodec} or {@link
   * MediaCodec#INFO_TRY_AGAIN_LATER} if no such buffer exists.
   *
   * @throws IllegalStateException If the underlying {@link MediaCodec} raised an error.
   */
  int dequeueInputBufferIndex();

  /**
   * Returns the next available output buffer index from the underlying {@link MediaCodec}. If the
   * next available output is a MediaFormat change, it will return {@link
   * MediaCodec#INFO_OUTPUT_FORMAT_CHANGED} and you should call {@link #getOutputFormat()} to get
   * the format. If there is no available output, this method will return {@link
   * MediaCodec#INFO_TRY_AGAIN_LATER}.
   *
   * @throws IllegalStateException If the underlying {@link MediaCodec} raised an error.
   */
  int dequeueOutputBufferIndex(MediaCodec.BufferInfo bufferInfo);

  /**
   * Gets the {@link MediaFormat} that was output from the {@link MediaCodec}.
   *
   * <p>Call this method if a previous call to {@link #dequeueOutputBufferIndex} returned {@link
   * MediaCodec#INFO_OUTPUT_FORMAT_CHANGED}.
   */
  MediaFormat getOutputFormat();

  /**
   * Returns a writable ByteBuffer object for a dequeued input buffer index.
   *
   * @see MediaCodec#getInputBuffer(int)
   */
  @Nullable
  ByteBuffer getInputBuffer(int index);

  /**
   * Returns a read-only ByteBuffer for a dequeued output buffer index.
   *
   * @see MediaCodec#getOutputBuffer(int)
   */
  @Nullable
  ByteBuffer getOutputBuffer(int index);

  /**
   * Submit an input buffer for decoding.
   *
   * <p>The {@code index} must be an input buffer index that has been obtained from a previous call
   * to {@link #dequeueInputBufferIndex()}.
   *
   * @see MediaCodec#queueInputBuffer
   */
  void queueInputBuffer(int index, int offset, int size, long presentationTimeUs, int flags);

  /**
   * Submit an input buffer that is potentially encrypted for decoding.
   *
   * <p>The {@code index} must be an input buffer index that has been obtained from a previous call
   * to {@link #dequeueInputBufferIndex()}.
   *
   * <p>This method behaves like {@link MediaCodec#queueSecureInputBuffer}, with the difference that
   * {@code info} is of type {@link CryptoInfo} and not {@link android.media.MediaCodec.CryptoInfo}.
   *
   * @see MediaCodec#queueSecureInputBuffer
   */
  void queueSecureInputBuffer(
      int index, int offset, CryptoInfo info, long presentationTimeUs, int flags);

  /**
   * Returns the buffer to the {@link MediaCodec}. If the {@link MediaCodec} was configured with an
   * output surface, setting {@code render} to {@code true} will first send the buffer to the output
   * surface. The surface will release the buffer back to the codec once it is no longer
   * used/displayed.
   *
   * @see MediaCodec#releaseOutputBuffer(int, boolean)
   */
  void releaseOutputBuffer(int index, boolean render);

  /**
   * Updates the output buffer's surface timestamp and sends it to the {@link MediaCodec} to render
   * it on the output surface. If the {@link MediaCodec} is not configured with an output surface,
   * this call will simply return the buffer to the {@link MediaCodec}.
   *
   * @see MediaCodec#releaseOutputBuffer(int, long)
   */
  void releaseOutputBuffer(int index, long renderTimeStampNs);

  /** Flushes the adapter and the underlying {@link MediaCodec}. */
  void flush();

  /** Releases the adapter and the underlying {@link MediaCodec}. */
  void release();

  /**
   * Registers a callback to be invoked when an output frame is rendered on the output surface.
   *
   * @see MediaCodec#setOnFrameRenderedListener
   */
  void setOnFrameRenderedListener(OnFrameRenderedListener listener, Handler handler);

  /**
   * Registers a listener that will be called when an input or output buffer becomes available.
   *
   * <p>Returns false if listener was not successfully registered for callbacks.
   *
   * @see MediaCodec.Callback#onInputBufferAvailable
   * @see MediaCodec.Callback#onOutputBufferAvailable
   * @return Whether listener was successfully registered.
   */
  default boolean registerOnBufferAvailableListener(
      MediaCodecAdapter.OnBufferAvailableListener listener) {
    return false;
  }

  /**
   * Dynamically sets the output surface of a {@link MediaCodec}.
   *
   * @see MediaCodec#setOutputSurface(Surface)
   */
  void setOutputSurface(Surface surface);

  /**
   * Detaches the current output surface.
   *
   * @see MediaCodec#detachOutputSurface()
   */
  @RequiresApi(35)
  void detachOutputSurface();

  /**
   * Communicate additional parameter changes to the {@link MediaCodec} instance.
   *
   * @see MediaCodec#setParameters(Bundle)
   */
  void setParameters(Bundle params);

  /**
   * Specifies the scaling mode to use, if a surface was specified when the codec was created.
   *
   * @see MediaCodec#setVideoScalingMode(int)
   */
  void setVideoScalingMode(@C.VideoScalingMode int scalingMode);

  /** Whether the adapter needs to be reconfigured before it is used. */
  boolean needsReconfiguration();

  /**
   * Returns metrics data about the current codec instance.
   *
   * @see MediaCodec#getMetrics()
   */
  @RequiresApi(26)
  PersistableBundle getMetrics();
}
