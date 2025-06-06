/*
 * Copyright 2023 The Android Open Source Project
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

import static android.os.Build.VERSION.SDK_INT;
import static androidx.media3.common.util.Assertions.checkNotNull;
import static androidx.media3.common.util.Assertions.checkState;
import static androidx.media3.transformer.ExportException.ERROR_CODE_IO_UNSPECIFIED;
import static androidx.media3.transformer.ExportException.ERROR_CODE_UNSPECIFIED;
import static androidx.media3.transformer.SampleConsumer.INPUT_RESULT_END_OF_STREAM;
import static androidx.media3.transformer.SampleConsumer.INPUT_RESULT_SUCCESS;
import static androidx.media3.transformer.SampleConsumer.INPUT_RESULT_TRY_AGAIN_LATER;
import static androidx.media3.transformer.Transformer.PROGRESS_STATE_AVAILABLE;
import static androidx.media3.transformer.Transformer.PROGRESS_STATE_NOT_STARTED;
import static com.google.common.util.concurrent.Futures.immediateFailedFuture;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Looper;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.ColorInfo;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.ParserException;
import androidx.media3.common.util.BitmapLoader;
import androidx.media3.common.util.ConstantRateTimestampIterator;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.transformer.SampleConsumer.InputResult;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * An {@link AssetLoader} implementation that loads images into {@link Bitmap} instances.
 *
 * <p>Supports the image formats listed <a
 * href="https://developer.android.com/media/platform/supported-formats#image-formats">here</a>
 * except from GIFs, which could exhibit unexpected behavior.
 */
@UnstableApi
public final class ImageAssetLoader implements AssetLoader {

  /** An {@link AssetLoader.Factory} for {@link ImageAssetLoader} instances. */
  public static final class Factory implements AssetLoader.Factory {

    private final Context context;
    private final BitmapLoader bitmapLoader;

    /**
     * Creates an instance.
     *
     * @param context The {@link Context}.
     * @param bitmapLoader The {@link BitmapLoader} to use to load and decode images.
     */
    public Factory(Context context, BitmapLoader bitmapLoader) {
      this.context = context;
      this.bitmapLoader = bitmapLoader;
    }

    @Override
    public AssetLoader createAssetLoader(
        EditedMediaItem editedMediaItem,
        Looper looper,
        Listener listener,
        CompositionSettings compositionSettings) {
      return new ImageAssetLoader(
          context,
          editedMediaItem,
          listener,
          bitmapLoader,
          compositionSettings.retainHdrFromUltraHdrImage);
    }
  }

  private static final int QUEUE_BITMAP_INTERVAL_MS = 10;

  private final Context context;
  private final EditedMediaItem editedMediaItem;
  private final BitmapLoader bitmapLoader;
  private final Listener listener;
  private final boolean retainHdrFromUltraHdrImage;
  private final ScheduledExecutorService scheduledExecutorService;

  @Nullable private SampleConsumer sampleConsumer;
  private @Transformer.ProgressState int progressState;

  private volatile int progress;

  private ImageAssetLoader(
      Context context,
      EditedMediaItem editedMediaItem,
      Listener listener,
      BitmapLoader bitmapLoader,
      boolean retainHdrFromUltraHdrImage) {
    checkState(editedMediaItem.durationUs != C.TIME_UNSET);
    checkState(editedMediaItem.frameRate != C.RATE_UNSET_INT);
    this.context = context;
    this.editedMediaItem = editedMediaItem;
    this.listener = listener;
    this.bitmapLoader = bitmapLoader;
    this.retainHdrFromUltraHdrImage = retainHdrFromUltraHdrImage;
    scheduledExecutorService = Executors.newSingleThreadScheduledExecutor();
    progressState = PROGRESS_STATE_NOT_STARTED;
  }

  @Override
  // Ignore Future returned by scheduledExecutorService because failures are already handled in the
  // runnable.
  @SuppressWarnings("FutureReturnValueIgnored")
  public void start() {
    progressState = PROGRESS_STATE_AVAILABLE;
    listener.onDurationUs(editedMediaItem.durationUs);
    listener.onTrackCount(1);
    ListenableFuture<Bitmap> future;

    @Nullable
    String mimeType = TransformerUtil.getImageMimeType(context, editedMediaItem.mediaItem);
    if (mimeType == null || !bitmapLoader.supportsMimeType(mimeType)) {
      future =
          immediateFailedFuture(
              ParserException.createForUnsupportedContainerFeature(
                  "Attempted to load a Bitmap from unsupported MIME type: " + mimeType));
    } else {
      future =
          bitmapLoader.loadBitmap(checkNotNull(editedMediaItem.mediaItem.localConfiguration).uri);
    }

    Futures.addCallback(
        future,
        new FutureCallback<Bitmap>() {
          @Override
          public void onSuccess(Bitmap bitmap) {
            progress = 50;
            Format inputFormat =
                new Format.Builder()
                    .setHeight(bitmap.getHeight())
                    .setWidth(bitmap.getWidth())
                    .setSampleMimeType(MimeTypes.IMAGE_RAW)
                    .setColorInfo(ColorInfo.SRGB_BT709_FULL)
                    .build();
            Format outputFormat =
                retainHdrFromUltraHdrImage && SDK_INT >= 34 && bitmap.hasGainmap()
                    ? inputFormat.buildUpon().setSampleMimeType(MimeTypes.IMAGE_JPEG_R).build()
                    : inputFormat;
            try {
              listener.onTrackAdded(inputFormat, SUPPORTED_OUTPUT_TYPE_DECODED);
              scheduledExecutorService.submit(() -> queueBitmapInternal(bitmap, outputFormat));
            } catch (RuntimeException e) {
              listener.onError(ExportException.createForAssetLoader(e, ERROR_CODE_UNSPECIFIED));
            }
          }

          @Override
          public void onFailure(Throwable t) {
            listener.onError(ExportException.createForAssetLoader(t, ERROR_CODE_IO_UNSPECIFIED));
          }
        },
        scheduledExecutorService);
  }

  @Override
  public @Transformer.ProgressState int getProgress(ProgressHolder progressHolder) {
    if (progressState == PROGRESS_STATE_AVAILABLE) {
      progressHolder.progress = progress;
    }
    return progressState;
  }

  @Override
  public ImmutableMap<Integer, String> getDecoderNames() {
    return ImmutableMap.of();
  }

  @Override
  public void release() {
    progressState = PROGRESS_STATE_NOT_STARTED;
    scheduledExecutorService.shutdownNow();
  }

  // Ignore Future returned by scheduledExecutorService because failures are already handled in the
  // runnable.
  @SuppressWarnings("FutureReturnValueIgnored")
  private void queueBitmapInternal(Bitmap bitmap, Format format) {
    try {
      if (sampleConsumer == null) {
        sampleConsumer = listener.onOutputFormat(format);
        scheduledExecutorService.schedule(
            () -> queueBitmapInternal(bitmap, format), QUEUE_BITMAP_INTERVAL_MS, MILLISECONDS);
        return;
      }
      // TODO: b/262693274 - Consider using listener.onDurationUs() or the MediaItem change callback
      //  rather than setting duration here.
      @InputResult
      int result =
          sampleConsumer.queueInputBitmap(
              bitmap,
              new ConstantRateTimestampIterator(
                  editedMediaItem.durationUs, editedMediaItem.frameRate));

      switch (result) {
        case INPUT_RESULT_SUCCESS:
          progress = 100;
          sampleConsumer.signalEndOfVideoInput();
          break;
        case INPUT_RESULT_TRY_AGAIN_LATER:
          scheduledExecutorService.schedule(
              () -> queueBitmapInternal(bitmap, format), QUEUE_BITMAP_INTERVAL_MS, MILLISECONDS);
          break;
        case INPUT_RESULT_END_OF_STREAM:
          progress = 100;
          break;
        default:
          throw new IllegalStateException();
      }
    } catch (ExportException e) {
      listener.onError(e);
    } catch (RuntimeException e) {
      listener.onError(ExportException.createForAssetLoader(e, ERROR_CODE_UNSPECIFIED));
    }
  }
}
