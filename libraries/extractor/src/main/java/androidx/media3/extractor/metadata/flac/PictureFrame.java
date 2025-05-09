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
package androidx.media3.extractor.metadata.flac;

import androidx.annotation.Nullable;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Metadata;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.common.util.UnstableApi;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** A picture parsed from a Vorbis Comment or a FLAC picture block. */
@UnstableApi
public final class PictureFrame implements Metadata.Entry {

  /** The type of the picture. */
  public final int pictureType;

  /** The MIME type of the picture. */
  public final String mimeType;

  /** A description of the picture. */
  public final String description;

  /** The width of the picture in pixels. */
  public final int width;

  /** The height of the picture in pixels. */
  public final int height;

  /** The color depth of the picture in bits-per-pixel. */
  public final int depth;

  /** For indexed-color pictures (e.g. GIF), the number of colors used. 0 otherwise. */
  public final int colors;

  /** The encoded picture data. */
  public final byte[] pictureData;

  public PictureFrame(
      int pictureType,
      String mimeType,
      String description,
      int width,
      int height,
      int depth,
      int colors,
      byte[] pictureData) {
    this.pictureType = pictureType;
    this.mimeType = mimeType;
    this.description = description;
    this.width = width;
    this.height = height;
    this.depth = depth;
    this.colors = colors;
    this.pictureData = pictureData;
  }

  @Override
  public void populateMediaMetadata(MediaMetadata.Builder builder) {
    builder.maybeSetArtworkData(pictureData, pictureType);
  }

  @Override
  public String toString() {
    return "Picture: mimeType=" + mimeType + ", description=" + description;
  }

  @Override
  public boolean equals(@Nullable Object obj) {
    if (this == obj) {
      return true;
    }
    if (obj == null || getClass() != obj.getClass()) {
      return false;
    }
    PictureFrame other = (PictureFrame) obj;
    return (pictureType == other.pictureType)
        && mimeType.equals(other.mimeType)
        && description.equals(other.description)
        && (width == other.width)
        && (height == other.height)
        && (depth == other.depth)
        && (colors == other.colors)
        && Arrays.equals(pictureData, other.pictureData);
  }

  @Override
  public int hashCode() {
    int result = 17;
    result = 31 * result + pictureType;
    result = 31 * result + mimeType.hashCode();
    result = 31 * result + description.hashCode();
    result = 31 * result + width;
    result = 31 * result + height;
    result = 31 * result + depth;
    result = 31 * result + colors;
    result = 31 * result + Arrays.hashCode(pictureData);
    return result;
  }

  /**
   * Parses a {@code METADATA_BLOCK_PICTURE} into a {@code PictureFrame} instance.
   *
   * <p>{@code pictureBlock} may be read directly from a <a
   * href="https://www.rfc-editor.org/rfc/rfc9639.html#name-picture">FLAC file</a>, or decoded from
   * the base64 content of a <a
   * href="https://wiki.xiph.org/VorbisComment#METADATA_BLOCK_PICTURE">Vorbis Comment</a>.
   *
   * @param pictureBlock The data of the {@code METADATA_BLOCK_PICTURE}, not including any headers.
   * @return A {@code PictureFrame} parsed from {@code pictureBlock}.
   */
  public static PictureFrame fromPictureBlock(ParsableByteArray pictureBlock) {
    int pictureType = pictureBlock.readInt();
    int mimeTypeLength = pictureBlock.readInt();
    String mimeType =
        MimeTypes.normalizeMimeType(
            pictureBlock.readString(mimeTypeLength, StandardCharsets.US_ASCII));
    int descriptionLength = pictureBlock.readInt();
    String description = pictureBlock.readString(descriptionLength);
    int width = pictureBlock.readInt();
    int height = pictureBlock.readInt();
    int depth = pictureBlock.readInt();
    int colors = pictureBlock.readInt();
    int pictureDataLength = pictureBlock.readInt();
    byte[] pictureData = new byte[pictureDataLength];
    pictureBlock.readBytes(pictureData, 0, pictureDataLength);

    return new PictureFrame(
        pictureType, mimeType, description, width, height, depth, colors, pictureData);
  }
}
