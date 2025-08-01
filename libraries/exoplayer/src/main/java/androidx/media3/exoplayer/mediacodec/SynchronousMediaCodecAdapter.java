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

import static android.os.Build.VERSION.SDK_INT;
import static androidx.media3.common.util.Assertions.checkNotNull;

import android.annotation.SuppressLint;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Bundle;
import android.os.Handler;
import android.os.PersistableBundle;
import android.view.Surface;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.media3.common.C;
import androidx.media3.common.util.TraceUtil;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.decoder.CryptoInfo;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * A {@link MediaCodecAdapter} that operates the underlying {@link MediaCodec} in synchronous mode.
 */
@UnstableApi
public final class SynchronousMediaCodecAdapter implements MediaCodecAdapter {

  /** A factory for {@link SynchronousMediaCodecAdapter} instances. */
  public static class Factory implements MediaCodecAdapter.Factory {

    @SuppressLint("WrongConstant") // Can't verify codec flag IntDef
    @Override
    public MediaCodecAdapter createAdapter(Configuration configuration) throws IOException {
      @Nullable MediaCodec codec = null;
      try {
        codec = createCodec(configuration);
        TraceUtil.beginSection("configureCodec");
        int flags = 0;
        if (configuration.surface == null
            && configuration.codecInfo.detachedSurfaceSupported
            && SDK_INT >= 35) {
          flags |= MediaCodec.CONFIGURE_FLAG_DETACHED_SURFACE;
        }
        codec.configure(
            configuration.mediaFormat, configuration.surface, configuration.crypto, flags);
        TraceUtil.endSection();
        TraceUtil.beginSection("startCodec");
        codec.start();
        TraceUtil.endSection();
        return new SynchronousMediaCodecAdapter(codec, configuration.loudnessCodecController);
      } catch (IOException | RuntimeException e) {
        if (codec != null) {
          codec.release();
        }
        throw e;
      }
    }

    /** Creates a new {@link MediaCodec} instance. */
    protected MediaCodec createCodec(Configuration configuration) throws IOException {
      checkNotNull(configuration.codecInfo);
      String codecName = configuration.codecInfo.name;
      TraceUtil.beginSection("createCodec:" + codecName);
      MediaCodec mediaCodec = MediaCodec.createByCodecName(codecName);
      TraceUtil.endSection();
      return mediaCodec;
    }
  }

  private final MediaCodec codec;
  @Nullable private final LoudnessCodecController loudnessCodecController;

  private SynchronousMediaCodecAdapter(
      MediaCodec mediaCodec, @Nullable LoudnessCodecController loudnessCodecController) {
    this.codec = mediaCodec;
    this.loudnessCodecController = loudnessCodecController;
    if (SDK_INT >= 35 && loudnessCodecController != null) {
      loudnessCodecController.addMediaCodec(codec);
    }
  }

  @Override
  public boolean needsReconfiguration() {
    return false;
  }

  @Override
  public int dequeueInputBufferIndex() {
    return codec.dequeueInputBuffer(0);
  }

  @Override
  public int dequeueOutputBufferIndex(MediaCodec.BufferInfo bufferInfo) {
    int index;
    do {
      index = codec.dequeueOutputBuffer(bufferInfo, 0);
    } while (index == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED);

    return index;
  }

  @Override
  public MediaFormat getOutputFormat() {
    return codec.getOutputFormat();
  }

  @Override
  @Nullable
  public ByteBuffer getInputBuffer(int index) {
    return codec.getInputBuffer(index);
  }

  @Override
  @Nullable
  public ByteBuffer getOutputBuffer(int index) {
    return codec.getOutputBuffer(index);
  }

  @Override
  public void queueInputBuffer(
      int index, int offset, int size, long presentationTimeUs, int flags) {
    codec.queueInputBuffer(index, offset, size, presentationTimeUs, flags);
  }

  @Override
  public void queueSecureInputBuffer(
      int index, int offset, CryptoInfo info, long presentationTimeUs, int flags) {
    codec.queueSecureInputBuffer(
        index, offset, info.getFrameworkCryptoInfo(), presentationTimeUs, flags);
  }

  @Override
  public void releaseOutputBuffer(int index, boolean render) {
    codec.releaseOutputBuffer(index, render);
  }

  @Override
  public void releaseOutputBuffer(int index, long renderTimeStampNs) {
    codec.releaseOutputBuffer(index, renderTimeStampNs);
  }

  @Override
  public void flush() {
    codec.flush();
  }

  @Override
  public void release() {
    try {
      if (SDK_INT >= 30 && SDK_INT < 33) {
        // Stopping the codec before releasing it works around a bug on APIs 30, 31 and 32 where
        // MediaCodec.release() returns too early before fully detaching a Surface, and a
        // subsequent MediaCodec.configure() call using the same Surface then fails. See
        // https://github.com/google/ExoPlayer/issues/8696 and b/191966399.
        codec.stop();
      }
    } finally {
      if (SDK_INT >= 35 && loudnessCodecController != null) {
        loudnessCodecController.removeMediaCodec(codec);
      }
      codec.release();
    }
  }

  @Override
  public void setOnFrameRenderedListener(OnFrameRenderedListener listener, Handler handler) {
    codec.setOnFrameRenderedListener(
        (codec, presentationTimeUs, nanoTime) ->
            listener.onFrameRendered(
                SynchronousMediaCodecAdapter.this, presentationTimeUs, nanoTime),
        handler);
  }

  @Override
  public void setOutputSurface(Surface surface) {
    codec.setOutputSurface(surface);
  }

  @RequiresApi(35)
  @Override
  public void detachOutputSurface() {
    codec.detachOutputSurface();
  }

  @Override
  public void setParameters(Bundle params) {
    codec.setParameters(params);
  }

  @Override
  public void setVideoScalingMode(@C.VideoScalingMode int scalingMode) {
    codec.setVideoScalingMode(scalingMode);
  }

  @Override
  @RequiresApi(26)
  public PersistableBundle getMetrics() {
    return codec.getMetrics();
  }
}
