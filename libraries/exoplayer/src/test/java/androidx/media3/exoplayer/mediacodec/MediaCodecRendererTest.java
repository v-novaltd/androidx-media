/*
 * Copyright (C) 2023 The Android Open Source Project
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

import static androidx.media3.exoplayer.DecoderReuseEvaluation.REUSE_RESULT_YES_WITHOUT_RECONFIGURATION;
import static androidx.media3.test.utils.FakeSampleStream.FakeSampleStreamItem.END_OF_STREAM_ITEM;
import static androidx.media3.test.utils.FakeSampleStream.FakeSampleStreamItem.oneByteSample;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;

import android.media.MediaCodec;
import android.media.MediaCrypto;
import android.media.MediaDrm;
import android.media.MediaFormat;
import android.os.Bundle;
import android.os.Handler;
import android.os.PersistableBundle;
import android.os.SystemClock;
import android.view.Surface;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.Clock;
import androidx.media3.decoder.CryptoInfo;
import androidx.media3.exoplayer.DecoderReuseEvaluation;
import androidx.media3.exoplayer.ExoPlaybackException;
import androidx.media3.exoplayer.RendererCapabilities;
import androidx.media3.exoplayer.RendererConfiguration;
import androidx.media3.exoplayer.analytics.PlayerId;
import androidx.media3.exoplayer.drm.DrmSessionEventListener;
import androidx.media3.exoplayer.drm.DrmSessionManager;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.upstream.DefaultAllocator;
import androidx.media3.test.utils.FakeSampleStream;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InOrder;

/** Unit tests for {@link MediaCodecRenderer} */
@RunWith(AndroidJUnit4.class)
public class MediaCodecRendererTest {

  @Test
  public void render_withReplaceStream_triggersOutputCallbacksInCorrectOrder() throws Exception {
    Format format1 =
        new Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC).setAverageBitrate(1000).build();
    Format format2 =
        new Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC).setAverageBitrate(1500).build();
    FakeSampleStream fakeSampleStream1 =
        createFakeSampleStream(format1, /* sampleTimesUs...= */ 0, 100, 200, 300);
    FakeSampleStream fakeSampleStream2 =
        createFakeSampleStream(format2, /* sampleTimesUs...= */ 0, 100, 200);
    MediaSource.MediaPeriodId mediaPeriodId1 = new MediaSource.MediaPeriodId(new Object());
    MediaSource.MediaPeriodId mediaPeriodId2 = new MediaSource.MediaPeriodId(new Object());
    MediaCodecRenderer renderer = spy(new TestRenderer());
    renderer.init(/* index= */ 0, PlayerId.UNSET, Clock.DEFAULT);

    renderer.enable(
        RendererConfiguration.DEFAULT,
        new Format[] {format1},
        fakeSampleStream1,
        /* positionUs= */ 0,
        /* joining= */ false,
        /* mayRenderStartOfStream= */ true,
        /* startPositionUs= */ 0,
        /* offsetUs= */ 0,
        mediaPeriodId1);
    renderer.start();
    long positionUs = 0;
    while (!renderer.hasReadStreamToEnd()) {
      renderer.render(positionUs, SystemClock.elapsedRealtime());
      positionUs += 100;
    }
    renderer.replaceStream(
        new Format[] {format2},
        fakeSampleStream2,
        /* startPositionUs= */ 400,
        /* offsetUs= */ 400,
        mediaPeriodId2);
    renderer.setCurrentStreamFinal();
    while (!renderer.isEnded()) {
      renderer.render(positionUs, SystemClock.elapsedRealtime());
      positionUs += 100;
    }

    InOrder inOrder = inOrder(renderer);
    inOrder.verify(renderer).onOutputStreamOffsetUsChanged(0);
    inOrder.verify(renderer).onProcessedStreamChange();
    inOrder.verify(renderer).onOutputFormatChanged(eq(format1), any());
    inOrder.verify(renderer).onProcessedOutputBuffer(0);
    inOrder.verify(renderer).onProcessedOutputBuffer(100);
    inOrder.verify(renderer).onProcessedOutputBuffer(200);
    inOrder.verify(renderer).onProcessedOutputBuffer(300);
    inOrder.verify(renderer).onOutputStreamOffsetUsChanged(400);
    inOrder.verify(renderer).onProcessedStreamChange();
    inOrder.verify(renderer).onOutputFormatChanged(eq(format2), any());
    inOrder.verify(renderer).onProcessedOutputBuffer(400);
    inOrder.verify(renderer).onProcessedOutputBuffer(500);
    inOrder.verify(renderer).onProcessedOutputBuffer(600);
  }

  @Test
  public void
      render_withReplaceStreamAndBufferBeyondDuration_triggersOutputCallbacksInCorrectOrder()
          throws Exception {
    Format format1 =
        new Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC).setAverageBitrate(1000).build();
    Format format2 =
        new Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC).setAverageBitrate(1500).build();
    FakeSampleStream fakeSampleStream1 =
        createFakeSampleStream(format1, /* sampleTimesUs...= */ 0, 100, 200, 300, 400, 500, 600);
    FakeSampleStream fakeSampleStream2 =
        createFakeSampleStream(format2, /* sampleTimesUs...= */ 0, 100, 200);
    MediaSource.MediaPeriodId mediaPeriodId1 = new MediaSource.MediaPeriodId(new Object());
    MediaSource.MediaPeriodId mediaPeriodId2 = new MediaSource.MediaPeriodId(new Object());
    MediaCodecRenderer renderer = spy(new TestRenderer());
    renderer.init(/* index= */ 0, PlayerId.UNSET, Clock.DEFAULT);

    renderer.enable(
        RendererConfiguration.DEFAULT,
        new Format[] {format1},
        fakeSampleStream1,
        /* positionUs= */ 0,
        /* joining= */ false,
        /* mayRenderStartOfStream= */ true,
        /* startPositionUs= */ 0,
        /* offsetUs= */ 0,
        mediaPeriodId1);
    renderer.start();
    long positionUs = 0;
    while (!renderer.hasReadStreamToEnd()) {
      renderer.render(positionUs, SystemClock.elapsedRealtime());
      positionUs += 100;
    }
    renderer.replaceStream(
        new Format[] {format2},
        fakeSampleStream2,
        /* startPositionUs= */ 400,
        /* offsetUs= */ 400,
        mediaPeriodId2);
    renderer.setCurrentStreamFinal();
    while (!renderer.isEnded()) {
      renderer.render(positionUs, SystemClock.elapsedRealtime());
      positionUs += 100;
    }

    InOrder inOrder = inOrder(renderer);
    inOrder.verify(renderer).onOutputStreamOffsetUsChanged(0);
    inOrder.verify(renderer).onProcessedStreamChange();
    inOrder.verify(renderer).onOutputFormatChanged(eq(format1), any());
    inOrder.verify(renderer).onProcessedOutputBuffer(0);
    inOrder.verify(renderer).onProcessedOutputBuffer(100);
    inOrder.verify(renderer).onProcessedOutputBuffer(200);
    inOrder.verify(renderer).onProcessedOutputBuffer(300);
    inOrder.verify(renderer).onProcessedOutputBuffer(400);
    inOrder.verify(renderer).onProcessedOutputBuffer(500);
    inOrder.verify(renderer).onProcessedOutputBuffer(600);
    inOrder.verify(renderer).onOutputStreamOffsetUsChanged(400);
    inOrder.verify(renderer).onProcessedStreamChange();
    inOrder.verify(renderer).onOutputFormatChanged(eq(format2), any());
    inOrder.verify(renderer).onProcessedOutputBuffer(400);
    inOrder.verify(renderer).onProcessedOutputBuffer(500);
    inOrder.verify(renderer).onProcessedOutputBuffer(600);
  }

  @Test
  public void
      render_withReplaceStreamAndBufferLessThanStartPosition_triggersOutputCallbacksInCorrectOrder()
          throws Exception {
    Format format1 =
        new Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC).setAverageBitrate(1000).build();
    Format format2 =
        new Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC).setAverageBitrate(1500).build();
    FakeSampleStream fakeSampleStream1 =
        createFakeSampleStream(format1, /* sampleTimesUs...= */ 0, 100, 200, 300);
    FakeSampleStream fakeSampleStream2 =
        createFakeSampleStream(format2, /* sampleTimesUs...= */ 0, 100, 200, 300, 400);
    MediaSource.MediaPeriodId mediaPeriodId1 = new MediaSource.MediaPeriodId(new Object());
    MediaSource.MediaPeriodId mediaPeriodId2 = new MediaSource.MediaPeriodId(new Object());
    MediaCodecRenderer renderer = spy(new TestRenderer());
    renderer.init(/* index= */ 0, PlayerId.UNSET, Clock.DEFAULT);

    renderer.enable(
        RendererConfiguration.DEFAULT,
        new Format[] {format1},
        fakeSampleStream1,
        /* positionUs= */ 0,
        /* joining= */ false,
        /* mayRenderStartOfStream= */ true,
        /* startPositionUs= */ 0,
        /* offsetUs= */ 0,
        mediaPeriodId1);
    renderer.start();
    long positionUs = 0;
    while (!renderer.hasReadStreamToEnd()) {
      renderer.render(positionUs, SystemClock.elapsedRealtime());
      positionUs += 100;
    }
    renderer.replaceStream(
        new Format[] {format2},
        fakeSampleStream2,
        /* startPositionUs= */ 400,
        /* offsetUs= */ 200,
        mediaPeriodId2);
    renderer.setCurrentStreamFinal();
    while (!renderer.isEnded()) {
      renderer.render(positionUs, SystemClock.elapsedRealtime());
      positionUs += 100;
    }

    InOrder inOrder = inOrder(renderer);
    inOrder.verify(renderer).onOutputStreamOffsetUsChanged(0);
    inOrder.verify(renderer).onProcessedStreamChange();
    inOrder.verify(renderer).onOutputFormatChanged(eq(format1), any());
    inOrder.verify(renderer).onProcessedOutputBuffer(0);
    inOrder.verify(renderer).onProcessedOutputBuffer(100);
    inOrder.verify(renderer).onProcessedOutputBuffer(200);
    inOrder.verify(renderer).onProcessedOutputBuffer(300);
    inOrder.verify(renderer).onOutputStreamOffsetUsChanged(200);
    inOrder.verify(renderer).onProcessedStreamChange();
    inOrder.verify(renderer).onOutputFormatChanged(eq(format2), any());
    inOrder.verify(renderer).onProcessedOutputBuffer(200);
    inOrder.verify(renderer).onProcessedOutputBuffer(300);
    inOrder.verify(renderer).onProcessedOutputBuffer(400);
    inOrder.verify(renderer).onProcessedOutputBuffer(500);
    inOrder.verify(renderer).onProcessedOutputBuffer(600);
  }

  @Test
  public void
      render_withReplaceStreamAfterInitialEmptySampleStream_triggersOutputCallbacksInCorrectOrder()
          throws Exception {
    Format format1 =
        new Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC).setAverageBitrate(1000).build();
    Format format2 =
        new Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC).setAverageBitrate(1500).build();
    FakeSampleStream fakeSampleStream1 = createFakeSampleStream(format1 /* no samples */);
    FakeSampleStream fakeSampleStream2 =
        createFakeSampleStream(format2, /* sampleTimesUs...= */ 0, 100, 200);
    MediaSource.MediaPeriodId mediaPeriodId1 = new MediaSource.MediaPeriodId(new Object());
    MediaSource.MediaPeriodId mediaPeriodId2 = new MediaSource.MediaPeriodId(new Object());
    MediaCodecRenderer renderer = spy(new TestRenderer());
    renderer.init(/* index= */ 0, PlayerId.UNSET, Clock.DEFAULT);

    renderer.enable(
        RendererConfiguration.DEFAULT,
        new Format[] {format1},
        fakeSampleStream1,
        /* positionUs= */ 0,
        /* joining= */ false,
        /* mayRenderStartOfStream= */ true,
        /* startPositionUs= */ 0,
        /* offsetUs= */ 0,
        mediaPeriodId1);
    renderer.start();
    long positionUs = 0;
    while (!renderer.hasReadStreamToEnd()) {
      renderer.render(positionUs, SystemClock.elapsedRealtime());
      positionUs += 100;
    }
    renderer.replaceStream(
        new Format[] {format2},
        fakeSampleStream2,
        /* startPositionUs= */ 0,
        /* offsetUs= */ 0,
        mediaPeriodId2);
    renderer.setCurrentStreamFinal();
    while (!renderer.isEnded()) {
      renderer.render(positionUs, SystemClock.elapsedRealtime());
      positionUs += 100;
    }

    InOrder inOrder = inOrder(renderer);
    inOrder.verify(renderer).onOutputStreamOffsetUsChanged(0);
    inOrder.verify(renderer).onProcessedStreamChange();
    inOrder.verify(renderer).onOutputStreamOffsetUsChanged(0);
    inOrder.verify(renderer).onProcessedStreamChange();
    inOrder.verify(renderer).onOutputFormatChanged(eq(format2), any());
    inOrder.verify(renderer).onProcessedOutputBuffer(0);
    inOrder.verify(renderer).onProcessedOutputBuffer(100);
    inOrder.verify(renderer).onProcessedOutputBuffer(200);
  }

  @Test
  public void
      render_withReplaceStreamAfterIntermittentEmptySampleStream_triggersOutputCallbacksInCorrectOrder()
          throws Exception {
    Format format1 =
        new Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC).setAverageBitrate(1000).build();
    Format format2 =
        new Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC).setAverageBitrate(1500).build();
    Format format3 =
        new Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC).setAverageBitrate(2000).build();
    FakeSampleStream fakeSampleStream1 =
        createFakeSampleStream(format1, /* sampleTimesUs...= */ 0, 100);
    FakeSampleStream fakeSampleStream2 = createFakeSampleStream(format2 /* no samples */);
    FakeSampleStream fakeSampleStream3 =
        createFakeSampleStream(format3, /* sampleTimesUs...= */ 0, 100, 200);
    MediaSource.MediaPeriodId mediaPeriodId1 = new MediaSource.MediaPeriodId(new Object());
    MediaSource.MediaPeriodId mediaPeriodId2 = new MediaSource.MediaPeriodId(new Object());
    MediaSource.MediaPeriodId mediaPeriodId3 = new MediaSource.MediaPeriodId(new Object());
    MediaCodecRenderer renderer = spy(new TestRenderer());
    renderer.init(/* index= */ 0, PlayerId.UNSET, Clock.DEFAULT);

    renderer.enable(
        RendererConfiguration.DEFAULT,
        new Format[] {format1},
        fakeSampleStream1,
        /* positionUs= */ 0,
        /* joining= */ false,
        /* mayRenderStartOfStream= */ true,
        /* startPositionUs= */ 0,
        /* offsetUs= */ 0,
        mediaPeriodId1);
    renderer.start();
    long positionUs = 0;
    while (!renderer.hasReadStreamToEnd()) {
      renderer.render(positionUs, SystemClock.elapsedRealtime());
      positionUs += 100;
    }
    renderer.replaceStream(
        new Format[] {format2},
        fakeSampleStream2,
        /* startPositionUs= */ 200,
        /* offsetUs= */ 200,
        mediaPeriodId2);
    while (!renderer.hasReadStreamToEnd()) {
      renderer.render(positionUs, SystemClock.elapsedRealtime());
      positionUs += 100;
    }
    renderer.replaceStream(
        new Format[] {format3},
        fakeSampleStream3,
        /* startPositionUs= */ 200,
        /* offsetUs= */ 200,
        mediaPeriodId3);
    renderer.setCurrentStreamFinal();
    while (!renderer.isEnded()) {
      renderer.render(positionUs, SystemClock.elapsedRealtime());
      positionUs += 100;
    }

    InOrder inOrder = inOrder(renderer);
    inOrder.verify(renderer).onOutputStreamOffsetUsChanged(0);
    inOrder.verify(renderer).onProcessedStreamChange();
    inOrder.verify(renderer).onOutputFormatChanged(eq(format1), any());
    inOrder.verify(renderer).onProcessedOutputBuffer(0);
    inOrder.verify(renderer).onProcessedOutputBuffer(100);
    inOrder.verify(renderer).onOutputStreamOffsetUsChanged(200);
    inOrder.verify(renderer).onProcessedStreamChange();
    inOrder.verify(renderer).onOutputStreamOffsetUsChanged(200);
    inOrder.verify(renderer).onProcessedStreamChange();
    inOrder.verify(renderer).onOutputFormatChanged(eq(format3), any());
    inOrder.verify(renderer).onProcessedOutputBuffer(200);
    inOrder.verify(renderer).onProcessedOutputBuffer(300);
    inOrder.verify(renderer).onProcessedOutputBuffer(400);
  }

  @Test
  public void render_withReplaceStreamInBypassMode_triggersOutputCallbacksInCorrectOrder()
      throws Exception {
    Format format1 =
        new Format.Builder().setSampleMimeType(MimeTypes.AUDIO_RAW).setAverageBitrate(1000).build();
    Format format2 =
        new Format.Builder().setSampleMimeType(MimeTypes.AUDIO_RAW).setAverageBitrate(1500).build();
    FakeSampleStream fakeSampleStream1 =
        createFakeSampleStream(format1, /* sampleTimesUs...= */ 0, 100, 200);
    FakeSampleStream fakeSampleStream2 =
        createFakeSampleStream(format2, /* sampleTimesUs...= */ 0, 100, 200, 300, 400);
    MediaSource.MediaPeriodId mediaPeriodId1 = new MediaSource.MediaPeriodId(new Object());
    MediaSource.MediaPeriodId mediaPeriodId2 = new MediaSource.MediaPeriodId(new Object());
    MediaCodecRenderer renderer =
        spy(
            new TestRenderer() {
              @Override
              protected boolean shouldUseBypass(Format format) {
                return true;
              }
            });
    renderer.init(/* index= */ 0, PlayerId.UNSET, Clock.DEFAULT);
    // Enable renderer on the second media period.
    renderer.enable(
        RendererConfiguration.DEFAULT,
        new Format[] {format2},
        fakeSampleStream2,
        /* positionUs= */ 0,
        /* joining= */ false,
        /* mayRenderStartOfStream= */ true,
        /* startPositionUs= */ 0,
        /* offsetUs= */ 0,
        mediaPeriodId2);
    renderer.start();
    long positionUs = 0;
    while (!renderer.hasReadStreamToEnd()) {
      renderer.render(positionUs, SystemClock.elapsedRealtime());
      positionUs += 200;
    }
    // Seek to first media period and reset second media period/sample stream.
    renderer.stop();
    positionUs = 100;
    renderer.disable();
    fakeSampleStream2 =
        createFakeSampleStream(format2, /* sampleTimesUs...= */ 0, 100, 200, 300, 400);
    renderer.enable(
        RendererConfiguration.DEFAULT,
        new Format[] {format1},
        fakeSampleStream1,
        /* positionUs= */ positionUs,
        /* joining= */ false,
        /* mayRenderStartOfStream= */ true,
        /* startPositionUs= */ positionUs,
        /* offsetUs= */ 0,
        mediaPeriodId1);
    fakeSampleStream1.seekToUs(positionUs, true);
    renderer.resetPosition(positionUs);
    renderer.start();
    while (!renderer.hasReadStreamToEnd()) {
      renderer.render(positionUs, SystemClock.elapsedRealtime());
      positionUs += 200;
    }
    renderer.replaceStream(
        new Format[] {format2},
        fakeSampleStream2,
        /* startPositionUs= */ 300,
        /* offsetUs= */ 300,
        mediaPeriodId2);
    renderer.setCurrentStreamFinal();
    while (!renderer.isEnded()) {
      renderer.render(positionUs, SystemClock.elapsedRealtime());
      positionUs += 200;
    }

    InOrder inOrder = inOrder(renderer);
    inOrder.verify(renderer).onOutputStreamOffsetUsChanged(0);
    inOrder.verify(renderer).onProcessedStreamChange();
    inOrder.verify(renderer).onOutputFormatChanged(eq(format2), any());
    inOrder.verify(renderer).onProcessedOutputBuffer(0);
    inOrder.verify(renderer).onProcessedOutputBuffer(100);
    inOrder.verify(renderer).onProcessedOutputBuffer(200);
    inOrder.verify(renderer).onProcessedOutputBuffer(300);
    inOrder.verify(renderer).onProcessedOutputBuffer(400);
    inOrder.verify(renderer).onOutputStreamOffsetUsChanged(0);
    inOrder.verify(renderer, times(2)).onPositionReset(100, false);
    inOrder.verify(renderer).onOutputFormatChanged(eq(format1), any());
    inOrder.verify(renderer).onProcessedOutputBuffer(100);
    inOrder.verify(renderer).onProcessedOutputBuffer(200);
    inOrder.verify(renderer).onOutputStreamOffsetUsChanged(300);
    inOrder.verify(renderer).onProcessedStreamChange();
    inOrder.verify(renderer).onOutputFormatChanged(eq(format2), any());
    inOrder.verify(renderer).onProcessedOutputBuffer(300);
    inOrder.verify(renderer).onProcessedOutputBuffer(400);
    inOrder.verify(renderer).onProcessedOutputBuffer(500);
    inOrder.verify(renderer).onProcessedOutputBuffer(600);
  }

  @Test
  public void render_afterEnableWithStartPositionUs_skipsSamplesBeforeStartPositionUs()
      throws Exception {
    Format format =
        new Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC).setAverageBitrate(1000).build();
    FakeSampleStream fakeSampleStream =
        createFakeSampleStream(format, /* sampleTimesUs...= */ 0, 100, 200, 300, 400, 500);
    MediaSource.MediaPeriodId mediaPeriodId = new MediaSource.MediaPeriodId(new Object());
    MediaCodecRenderer renderer = spy(new TestRenderer());
    renderer.init(/* index= */ 0, PlayerId.UNSET, Clock.DEFAULT);

    renderer.enable(
        RendererConfiguration.DEFAULT,
        new Format[] {format},
        fakeSampleStream,
        /* positionUs= */ 0,
        /* joining= */ false,
        /* mayRenderStartOfStream= */ true,
        /* startPositionUs= */ 300,
        /* offsetUs= */ 0,
        mediaPeriodId);
    renderer.start();
    renderer.setCurrentStreamFinal();
    long positionUs = 0;
    while (!renderer.isEnded()) {
      renderer.render(positionUs, SystemClock.elapsedRealtime());
      positionUs += 100;
    }

    InOrder inOrder = inOrder(renderer);
    verifyProcessOutputBufferDecodeOnly(
        inOrder, renderer, /* presentationTimeUs= */ 0, /* isDecodeOnly= */ true);
    verifyProcessOutputBufferDecodeOnly(
        inOrder, renderer, /* presentationTimeUs= */ 100, /* isDecodeOnly= */ true);
    verifyProcessOutputBufferDecodeOnly(
        inOrder, renderer, /* presentationTimeUs= */ 200, /* isDecodeOnly= */ true);
    verifyProcessOutputBufferDecodeOnly(
        inOrder, renderer, /* presentationTimeUs= */ 300, /* isDecodeOnly= */ false);
    verifyProcessOutputBufferDecodeOnly(
        inOrder, renderer, /* presentationTimeUs= */ 400, /* isDecodeOnly= */ false);
    verifyProcessOutputBufferDecodeOnly(
        inOrder, renderer, /* presentationTimeUs= */ 500, /* isDecodeOnly= */ false);
  }

  @Test
  public void render_afterPositionReset_skipsSamplesBeforeStartPositionUs() throws Exception {
    Format format =
        new Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC).setAverageBitrate(1000).build();
    FakeSampleStream fakeSampleStream =
        createFakeSampleStream(format, /* sampleTimesUs...= */ 0, 100, 200, 300, 400, 500);
    MediaSource.MediaPeriodId mediaPeriodId = new MediaSource.MediaPeriodId(new Object());
    MediaCodecRenderer renderer = spy(new TestRenderer());
    renderer.init(/* index= */ 0, PlayerId.UNSET, Clock.DEFAULT);
    renderer.enable(
        RendererConfiguration.DEFAULT,
        new Format[] {format},
        fakeSampleStream,
        /* positionUs= */ 0,
        /* joining= */ false,
        /* mayRenderStartOfStream= */ true,
        /* startPositionUs= */ 400,
        /* offsetUs= */ 0,
        mediaPeriodId);
    renderer.start();

    renderer.resetPosition(/* positionUs= */ 200);
    renderer.setCurrentStreamFinal();
    long positionUs = 0;
    while (!renderer.isEnded()) {
      renderer.render(positionUs, SystemClock.elapsedRealtime());
      positionUs += 100;
    }

    InOrder inOrder = inOrder(renderer);
    verifyProcessOutputBufferDecodeOnly(
        inOrder, renderer, /* presentationTimeUs= */ 0, /* isDecodeOnly= */ true);
    verifyProcessOutputBufferDecodeOnly(
        inOrder, renderer, /* presentationTimeUs= */ 100, /* isDecodeOnly= */ true);
    verifyProcessOutputBufferDecodeOnly(
        inOrder, renderer, /* presentationTimeUs= */ 200, /* isDecodeOnly= */ false);
    verifyProcessOutputBufferDecodeOnly(
        inOrder, renderer, /* presentationTimeUs= */ 300, /* isDecodeOnly= */ false);
    verifyProcessOutputBufferDecodeOnly(
        inOrder, renderer, /* presentationTimeUs= */ 400, /* isDecodeOnly= */ false);
    verifyProcessOutputBufferDecodeOnly(
        inOrder, renderer, /* presentationTimeUs= */ 500, /* isDecodeOnly= */ false);
  }

  @Test
  public void render_wrapsIllegalStateExceptionFromMediaCodecInExoPlaybackException()
      throws Exception {
    MediaCodecAdapter.Factory throwingMediaCodecAdapterFactory =
        new ThrowingMediaCodecAdapter.Factory(
            () -> {
              IllegalStateException ise = new IllegalStateException("ISE from inside MediaCodec");
              StackTraceElement[] stackTrace = ise.getStackTrace();
              stackTrace[0] =
                  new StackTraceElement(
                      "android.media.MediaCodec",
                      "fakeMethod",
                      stackTrace[0].getFileName(),
                      stackTrace[0].getLineNumber());
              ise.setStackTrace(stackTrace);
              return ise;
            });
    TestRenderer renderer = new TestRenderer(throwingMediaCodecAdapterFactory);
    renderer.init(/* index= */ 0, PlayerId.UNSET, Clock.DEFAULT);
    Format format =
        new Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC).setAverageBitrate(1000).build();
    FakeSampleStream fakeSampleStream =
        createFakeSampleStream(format, /* sampleTimesUs...= */ 0, 100, 200, 300, 400, 500);
    MediaSource.MediaPeriodId mediaPeriodId = new MediaSource.MediaPeriodId(new Object());
    renderer.enable(
        RendererConfiguration.DEFAULT,
        new Format[] {format},
        fakeSampleStream,
        /* positionUs= */ 0,
        /* joining= */ false,
        /* mayRenderStartOfStream= */ true,
        /* startPositionUs= */ 400,
        /* offsetUs= */ 0,
        mediaPeriodId);
    renderer.start();

    ExoPlaybackException playbackException =
        assertThrows(
            ExoPlaybackException.class,
            () -> renderer.render(/* positionUs= */ 0, SystemClock.elapsedRealtime()));

    assertThat(playbackException.type).isEqualTo(ExoPlaybackException.TYPE_RENDERER);
    assertThat(playbackException)
        .hasCauseThat()
        .hasCauseThat()
        .isInstanceOf(IllegalStateException.class);
    assertThat(playbackException)
        .hasCauseThat()
        .hasCauseThat()
        .hasMessageThat()
        .contains("ISE from inside MediaCodec");
  }

  // b/347367307#comment6
  @Test
  public void render_wrapsCryptoExceptionFromAnyMediaCodecMethod() throws Exception {
    MediaCodecAdapter.Factory throwingMediaCodecAdapterFactory =
        new ThrowingMediaCodecAdapter.Factory(
            () ->
                new MediaCodec.CryptoException(
                    MediaDrm.ErrorCodes.ERROR_INSUFFICIENT_OUTPUT_PROTECTION, "Test exception"));
    TestRenderer renderer = new TestRenderer(throwingMediaCodecAdapterFactory);
    renderer.init(/* index= */ 0, PlayerId.UNSET, Clock.DEFAULT);
    Format format =
        new Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC).setAverageBitrate(1000).build();
    FakeSampleStream fakeSampleStream =
        createFakeSampleStream(format, /* sampleTimesUs...= */ 0, 100, 200, 300, 400, 500);
    MediaSource.MediaPeriodId mediaPeriodId = new MediaSource.MediaPeriodId(new Object());
    renderer.enable(
        RendererConfiguration.DEFAULT,
        new Format[] {format},
        fakeSampleStream,
        /* positionUs= */ 0,
        /* joining= */ false,
        /* mayRenderStartOfStream= */ true,
        /* startPositionUs= */ 400,
        /* offsetUs= */ 0,
        mediaPeriodId);
    renderer.start();

    ExoPlaybackException playbackException =
        assertThrows(
            ExoPlaybackException.class,
            () -> renderer.render(/* positionUs= */ 0, SystemClock.elapsedRealtime()));

    assertThat(playbackException.type).isEqualTo(ExoPlaybackException.TYPE_RENDERER);
    assertThat(playbackException).hasCauseThat().isInstanceOf(MediaCodec.CryptoException.class);
    assertThat(playbackException).hasCauseThat().hasMessageThat().contains("Test exception");
  }

  private FakeSampleStream createFakeSampleStream(Format format, long... sampleTimesUs) {
    ImmutableList.Builder<FakeSampleStream.FakeSampleStreamItem> sampleListBuilder =
        ImmutableList.builder();
    for (long sampleTimeUs : sampleTimesUs) {
      sampleListBuilder.add(oneByteSample(sampleTimeUs, C.BUFFER_FLAG_KEY_FRAME));
    }
    sampleListBuilder.add(END_OF_STREAM_ITEM);
    FakeSampleStream sampleStream =
        new FakeSampleStream(
            new DefaultAllocator(/* trimOnReset= */ true, /* individualAllocationSize= */ 1024),
            /* mediaSourceEventDispatcher= */ null,
            DrmSessionManager.DRM_UNSUPPORTED,
            new DrmSessionEventListener.EventDispatcher(),
            /* initialFormat= */ format,
            sampleListBuilder.build());
    sampleStream.writeData(/* startPositionUs= */ 0);
    return sampleStream;
  }

  private static class TestRenderer extends MediaCodecRenderer {

    public TestRenderer() {
      this(MediaCodecAdapter.Factory.getDefault(ApplicationProvider.getApplicationContext()));
    }

    public TestRenderer(MediaCodecAdapter.Factory mediaCodecAdapterFactory) {
      super(
          C.TRACK_TYPE_AUDIO,
          mediaCodecAdapterFactory,
          /* mediaCodecSelector= */ (mimeType, requiresSecureDecoder, requiresTunnelingDecoder) ->
              Collections.singletonList(
                  MediaCodecInfo.newInstance(
                      /* name= */ "name",
                      /* mimeType= */ mimeType,
                      /* codecMimeType= */ mimeType,
                      /* capabilities= */ null,
                      /* hardwareAccelerated= */ false,
                      /* softwareOnly= */ true,
                      /* vendor= */ false,
                      /* forceDisableAdaptive= */ false,
                      /* forceSecure= */ false)),
          /* enableDecoderFallback= */ false,
          /* assumedMinimumCodecOperatingRate= */ 44100);
      experimentalEnableProcessedStreamChangedAtStart();
    }

    @Override
    public String getName() {
      return "test";
    }

    @Override
    protected @Capabilities int supportsFormat(MediaCodecSelector mediaCodecSelector, Format format)
        throws MediaCodecUtil.DecoderQueryException {
      return RendererCapabilities.create(C.FORMAT_HANDLED);
    }

    @Override
    protected List<MediaCodecInfo> getDecoderInfos(
        MediaCodecSelector mediaCodecSelector, Format format, boolean requiresSecureDecoder)
        throws MediaCodecUtil.DecoderQueryException {
      return mediaCodecSelector.getDecoderInfos(
          format.sampleMimeType,
          /* requiresSecureDecoder= */ false,
          /* requiresTunnelingDecoder= */ false);
    }

    @Override
    protected MediaCodecAdapter.Configuration getMediaCodecConfiguration(
        MediaCodecInfo codecInfo,
        Format format,
        @Nullable MediaCrypto crypto,
        float codecOperatingRate) {
      return MediaCodecAdapter.Configuration.createForAudioDecoding(
          codecInfo, new MediaFormat(), format, crypto, /* loudnessCodecController= */ null);
    }

    @Override
    protected boolean processOutputBuffer(
        long positionUs,
        long elapsedRealtimeUs,
        @Nullable MediaCodecAdapter codec,
        @Nullable ByteBuffer buffer,
        int bufferIndex,
        int bufferFlags,
        int sampleCount,
        long bufferPresentationTimeUs,
        boolean isDecodeOnlyBuffer,
        boolean isLastBuffer,
        Format format)
        throws ExoPlaybackException {
      if (bufferPresentationTimeUs <= positionUs) {
        // Only release buffers when the position advances far enough for realistic behavior where
        // input of buffers to the codec is faster than output.
        if (codec != null) {
          codec.releaseOutputBuffer(bufferIndex, /* render= */ true);
        }
        return true;
      }
      return false;
    }

    @Override
    protected DecoderReuseEvaluation canReuseCodec(
        MediaCodecInfo codecInfo, Format oldFormat, Format newFormat) {
      return new DecoderReuseEvaluation(
          codecInfo.name,
          oldFormat,
          newFormat,
          REUSE_RESULT_YES_WITHOUT_RECONFIGURATION,
          /* discardReasons= */ 0);
    }
  }

  /**
   * A {@link MediaCodecAdapter} that throws a pre-specified exception from every decoding-related
   * interaction.
   */
  private static class ThrowingMediaCodecAdapter implements MediaCodecAdapter {

    public static class Factory implements MediaCodecAdapter.Factory {

      private final Supplier<RuntimeException> exceptionSupplier;

      public Factory(Supplier<RuntimeException> exceptionSupplier) {
        this.exceptionSupplier = exceptionSupplier;
      }

      @Override
      public MediaCodecAdapter createAdapter(Configuration configuration) throws IOException {
        return new ThrowingMediaCodecAdapter(exceptionSupplier);
      }
    }

    private final Supplier<RuntimeException> exceptionSupplier;

    private ThrowingMediaCodecAdapter(Supplier<RuntimeException> exceptionSupplier) {
      this.exceptionSupplier = exceptionSupplier;
    }

    @Override
    public int dequeueInputBufferIndex() {
      throw exceptionSupplier.get();
    }

    @Override
    public int dequeueOutputBufferIndex(MediaCodec.BufferInfo bufferInfo) {
      throw exceptionSupplier.get();
    }

    @Override
    public MediaFormat getOutputFormat() {
      throw exceptionSupplier.get();
    }

    @Nullable
    @Override
    public ByteBuffer getInputBuffer(int index) {
      throw exceptionSupplier.get();
    }

    @Nullable
    @Override
    public ByteBuffer getOutputBuffer(int index) {
      throw exceptionSupplier.get();
    }

    @Override
    public void queueInputBuffer(
        int index, int offset, int size, long presentationTimeUs, int flags) {
      throw exceptionSupplier.get();
    }

    @Override
    public void queueSecureInputBuffer(
        int index, int offset, CryptoInfo info, long presentationTimeUs, int flags) {
      throw exceptionSupplier.get();
    }

    @Override
    public void releaseOutputBuffer(int index, boolean render) {
      throw exceptionSupplier.get();
    }

    @Override
    public void releaseOutputBuffer(int index, long renderTimeStampNs) {
      throw exceptionSupplier.get();
    }

    @Override
    public void flush() {
      throw exceptionSupplier.get();
    }

    @Override
    public void release() {}

    @Override
    public void setOnFrameRenderedListener(OnFrameRenderedListener listener, Handler handler) {}

    @Override
    public boolean registerOnBufferAvailableListener(OnBufferAvailableListener listener) {
      return false;
    }

    @Override
    public void setOutputSurface(Surface surface) {
      throw exceptionSupplier.get();
    }

    @Override
    public void detachOutputSurface() {
      throw exceptionSupplier.get();
    }

    @Override
    public void setParameters(Bundle params) {
      throw exceptionSupplier.get();
    }

    @Override
    public void setVideoScalingMode(@C.VideoScalingMode int scalingMode) {
      throw exceptionSupplier.get();
    }

    @Override
    public boolean needsReconfiguration() {
      return false;
    }

    @Override
    public PersistableBundle getMetrics() {
      return new PersistableBundle();
    }
  }

  private static void verifyProcessOutputBufferDecodeOnly(
      InOrder inOrder, MediaCodecRenderer renderer, long presentationTimeUs, boolean isDecodeOnly)
      throws Exception {
    inOrder
        .verify(renderer)
        .processOutputBuffer(
            anyLong(),
            anyLong(),
            any(),
            any(),
            anyInt(),
            anyInt(),
            anyInt(),
            eq(presentationTimeUs),
            eq(isDecodeOnly),
            anyBoolean(),
            any());
  }
}
