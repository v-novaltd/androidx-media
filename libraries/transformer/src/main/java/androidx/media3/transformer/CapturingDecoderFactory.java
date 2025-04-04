/*
 * Copyright 2022 The Android Open Source Project
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
package androidx.media3.transformer;

import android.media.metrics.LogSessionId;
import android.view.Surface;
import androidx.annotation.Nullable;
import androidx.media3.common.Format;

/** A forwarding {@link Codec.DecoderFactory} that captures details about the codecs created. */
/* package */ final class CapturingDecoderFactory implements Codec.DecoderFactory {
  private final Codec.DecoderFactory decoderFactory;

  @Nullable private String audioDecoderName;
  @Nullable private String videoDecoderName;

  public CapturingDecoderFactory(Codec.DecoderFactory decoderFactory) {
    this.decoderFactory = decoderFactory;
  }

  @Override
  public Codec createForAudioDecoding(Format format, @Nullable LogSessionId logSessionId)
      throws ExportException {
    Codec audioDecoder = decoderFactory.createForAudioDecoding(format, logSessionId);
    audioDecoderName = audioDecoder.getName();
    return audioDecoder;
  }

  @Override
  public Codec createForVideoDecoding(
      Format format,
      Surface outputSurface,
      boolean requestSdrToneMapping,
      @Nullable LogSessionId logSessionId)
      throws ExportException {
    Codec videoDecoder =
        decoderFactory.createForVideoDecoding(
            format, outputSurface, requestSdrToneMapping, logSessionId);
    videoDecoderName = videoDecoder.getName();
    return videoDecoder;
  }

  /**
   * Returns the name of the last audio {@linkplain Codec decoder} created, or {@code null} if none
   * were created.
   */
  @Nullable
  public String getAudioDecoderName() {
    return audioDecoderName;
  }

  /**
   * Returns the name of the last video {@linkplain Codec decoder} created, or {@code null} if none
   * were created.
   */
  @Nullable
  public String getVideoDecoderName() {
    return videoDecoderName;
  }
}
