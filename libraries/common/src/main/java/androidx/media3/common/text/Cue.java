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
package androidx.media3.common.text;

import static androidx.media3.common.text.CustomSpanBundler.bundleCustomSpans;
import static androidx.media3.common.util.Assertions.checkState;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.LOCAL_VARIABLE;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.ElementType.TYPE_USE;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Binder;
import android.os.Bundle;
import android.text.Layout;
import android.text.Layout.Alignment;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.SpannedString;
import android.text.TextUtils;
import androidx.annotation.ColorInt;
import androidx.annotation.IntDef;
import androidx.annotation.Nullable;
import androidx.media3.common.util.Assertions;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.io.ByteArrayOutputStream;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.ArrayList;
import java.util.Objects;
import org.checkerframework.dataflow.qual.Pure;

/** Contains information about a specific cue, including textual content and formatting data. */
// This class shouldn't be sub-classed. If a subtitle format needs additional fields, either they
// should be generic enough to be added here, or the format-specific decoder should pass the
// information around in a sidecar object.
public final class Cue {

  /**
   * @deprecated There's no general need for a cue with an empty text string. If you need one,
   *     create it yourself.
   */
  @Deprecated public static final Cue EMPTY = new Cue.Builder().setText("").build();

  /** An unset position, width or size. */
  // Note: We deliberately don't use Float.MIN_VALUE because it's positive & very close to zero.
  public static final float DIMEN_UNSET = -Float.MAX_VALUE;

  /**
   * The type of anchor, which may be unset. One of {@link #TYPE_UNSET}, {@link #ANCHOR_TYPE_START},
   * {@link #ANCHOR_TYPE_MIDDLE} or {@link #ANCHOR_TYPE_END}.
   */
  // @Target list includes both 'default' targets and TYPE_USE, to ensure backwards compatibility
  // with Kotlin usages from before TYPE_USE was added.
  @Documented
  @Retention(RetentionPolicy.SOURCE)
  @Target({FIELD, METHOD, PARAMETER, LOCAL_VARIABLE, TYPE_USE})
  @IntDef({TYPE_UNSET, ANCHOR_TYPE_START, ANCHOR_TYPE_MIDDLE, ANCHOR_TYPE_END})
  public @interface AnchorType {}

  /** An unset anchor, line, text size or vertical type value. */
  public static final int TYPE_UNSET = Integer.MIN_VALUE;

  /**
   * Anchors the left (for horizontal positions) or top (for vertical positions) edge of the cue
   * box.
   */
  public static final int ANCHOR_TYPE_START = 0;

  /** Anchors the middle of the cue box. */
  public static final int ANCHOR_TYPE_MIDDLE = 1;

  /**
   * Anchors the right (for horizontal positions) or bottom (for vertical positions) edge of the cue
   * box.
   */
  public static final int ANCHOR_TYPE_END = 2;

  /**
   * The type of line, which may be unset. One of {@link #TYPE_UNSET}, {@link #LINE_TYPE_FRACTION}
   * or {@link #LINE_TYPE_NUMBER}.
   */
  // @Target list includes both 'default' targets and TYPE_USE, to ensure backwards compatibility
  // with Kotlin usages from before TYPE_USE was added.
  @Documented
  @Retention(RetentionPolicy.SOURCE)
  @Target({FIELD, METHOD, PARAMETER, LOCAL_VARIABLE, TYPE_USE})
  @IntDef({TYPE_UNSET, LINE_TYPE_FRACTION, LINE_TYPE_NUMBER})
  public @interface LineType {}

  /** Value for {@link #lineType} when {@link #line} is a fractional position. */
  public static final int LINE_TYPE_FRACTION = 0;

  /** Value for {@link #lineType} when {@link #line} is a line number. */
  public static final int LINE_TYPE_NUMBER = 1;

  /**
   * The type of default text size for this cue, which may be unset. One of {@link #TYPE_UNSET},
   * {@link #TEXT_SIZE_TYPE_FRACTIONAL}, {@link #TEXT_SIZE_TYPE_FRACTIONAL_IGNORE_PADDING} or {@link
   * #TEXT_SIZE_TYPE_ABSOLUTE}.
   */
  // @Target list includes both 'default' targets and TYPE_USE, to ensure backwards compatibility
  // with Kotlin usages from before TYPE_USE was added.
  @Documented
  @Retention(RetentionPolicy.SOURCE)
  @Target({FIELD, METHOD, PARAMETER, LOCAL_VARIABLE, TYPE_USE})
  @IntDef({
    TYPE_UNSET,
    TEXT_SIZE_TYPE_FRACTIONAL,
    TEXT_SIZE_TYPE_FRACTIONAL_IGNORE_PADDING,
    TEXT_SIZE_TYPE_ABSOLUTE
  })
  public @interface TextSizeType {}

  /** Text size is measured as a fraction of the viewport size minus the view padding. */
  public static final int TEXT_SIZE_TYPE_FRACTIONAL = 0;

  /** Text size is measured as a fraction of the viewport size, ignoring the view padding */
  public static final int TEXT_SIZE_TYPE_FRACTIONAL_IGNORE_PADDING = 1;

  /** Text size is measured in number of pixels. */
  public static final int TEXT_SIZE_TYPE_ABSOLUTE = 2;

  /**
   * The type of vertical layout for this cue, which may be unset (i.e. horizontal). One of {@link
   * #TYPE_UNSET}, {@link #VERTICAL_TYPE_RL} or {@link #VERTICAL_TYPE_LR}.
   */
  // @Target list includes both 'default' targets and TYPE_USE, to ensure backwards compatibility
  // with Kotlin usages from before TYPE_USE was added.
  @Documented
  @Retention(RetentionPolicy.SOURCE)
  @Target({FIELD, METHOD, PARAMETER, LOCAL_VARIABLE, TYPE_USE})
  @IntDef({
    TYPE_UNSET,
    VERTICAL_TYPE_RL,
    VERTICAL_TYPE_LR,
  })
  public @interface VerticalType {}

  /** Vertical right-to-left (e.g. for Japanese). */
  public static final int VERTICAL_TYPE_RL = 1;

  /** Vertical left-to-right (e.g. for Mongolian). */
  public static final int VERTICAL_TYPE_LR = 2;

  /**
   * The cue text, or null if this is an image cue. Note the {@link CharSequence} may be decorated
   * with styling spans.
   */
  @Nullable public final CharSequence text;

  /** The alignment of the cue text within the cue box, or null if the alignment is undefined. */
  @Nullable public final Alignment textAlignment;

  /**
   * The alignment of multiple lines of text relative to the longest line, or null if the alignment
   * is undefined.
   */
  @Nullable public final Alignment multiRowAlignment;

  /** The cue image, or null if this is a text cue. */
  @Nullable public final Bitmap bitmap;

  /**
   * The position of the cue box within the viewport in the direction orthogonal to the writing
   * direction (determined by {@link #verticalType}), or {@link #DIMEN_UNSET}. When set, the
   * interpretation of the value depends on the value of {@link #lineType}.
   *
   * <p>The measurement direction depends on {@link #verticalType}:
   *
   * <ul>
   *   <li>For {@link #TYPE_UNSET} (i.e. horizontal), this is the vertical position relative to the
   *       top of the viewport.
   *   <li>For {@link #VERTICAL_TYPE_LR} this is the horizontal position relative to the left of the
   *       viewport.
   *   <li>For {@link #VERTICAL_TYPE_RL} this is the horizontal position relative to the right of
   *       the viewport.
   * </ul>
   */
  public final float line;

  /**
   * The type of the {@link #line} value.
   *
   * <ul>
   *   <li>{@link #LINE_TYPE_FRACTION} indicates that {@link #line} is a fractional position within
   *       the viewport (measured to the part of the cue box determined by {@link #lineAnchor}).
   *   <li>{@link #LINE_TYPE_NUMBER} indicates that {@link #line} is a viewport line number. The
   *       viewport is divided into lines (each equal in size to the first line of the cue box). The
   *       cue box is positioned to align with the viewport lines as follows:
   *       <ul>
   *         <li>{@link #lineAnchor}) is ignored.
   *         <li>When {@code line} is greater than or equal to 0 the first line in the cue box is
   *             aligned with a viewport line, with 0 meaning the first line of the viewport.
   *         <li>When {@code line} is negative the last line in the cue box is aligned with a
   *             viewport line, with -1 meaning the last line of the viewport.
   *         <li>For horizontal text the start and end of the viewport are the top and bottom
   *             respectively.
   *       </ul>
   * </ul>
   */
  public final @LineType int lineType;

  /**
   * The cue box anchor positioned by {@link #line} when {@link #lineType} is {@link
   * #LINE_TYPE_FRACTION}.
   *
   * <p>One of:
   *
   * <ul>
   *   <li>{@link #ANCHOR_TYPE_START}
   *   <li>{@link #ANCHOR_TYPE_MIDDLE}
   *   <li>{@link #ANCHOR_TYPE_END}
   *   <li>{@link #TYPE_UNSET}
   * </ul>
   *
   * <p>For the normal case of horizontal text, {@link #ANCHOR_TYPE_START}, {@link
   * #ANCHOR_TYPE_MIDDLE} and {@link #ANCHOR_TYPE_END} correspond to the top, middle and bottom of
   * the cue box respectively.
   */
  public final @AnchorType int lineAnchor;

  /**
   * The fractional position of the {@link #positionAnchor} of the cue box within the viewport in
   * the direction orthogonal to {@link #line}, or {@link #DIMEN_UNSET}.
   *
   * <p>The measurement direction depends on {@link #verticalType}.
   *
   * <ul>
   *   <li>For {@link #TYPE_UNSET} (i.e. horizontal), this is the horizontal position relative to
   *       the left of the viewport. Note that positioning is relative to the left of the viewport
   *       even in the case of right-to-left text.
   *   <li>For {@link #VERTICAL_TYPE_LR} and {@link #VERTICAL_TYPE_RL} (i.e. vertical), this is the
   *       vertical position relative to the top of the viewport.
   * </ul>
   */
  public final float position;

  /**
   * The cue box anchor positioned by {@link #position}. One of {@link #ANCHOR_TYPE_START}, {@link
   * #ANCHOR_TYPE_MIDDLE}, {@link #ANCHOR_TYPE_END} and {@link #TYPE_UNSET}.
   *
   * <p>For the normal case of horizontal text, {@link #ANCHOR_TYPE_START}, {@link
   * #ANCHOR_TYPE_MIDDLE} and {@link #ANCHOR_TYPE_END} correspond to the left, middle and right of
   * the cue box respectively.
   */
  public final @AnchorType int positionAnchor;

  /**
   * The size of the cue box in the writing direction specified as a fraction of the viewport size
   * in that direction, or {@link #DIMEN_UNSET}.
   */
  public final float size;

  /**
   * The bitmap height as a fraction of the of the viewport size, or {@link #DIMEN_UNSET} if the
   * bitmap should be displayed at its natural height given the bitmap dimensions and the specified
   * {@link #size}.
   */
  public final float bitmapHeight;

  /** Specifies whether or not the {@link #windowColor} property is set. */
  public final boolean windowColorSet;

  /** The fill color of the window. */
  public final int windowColor;

  /**
   * The default text size type for this cue's text, or {@link #TYPE_UNSET} if this cue has no
   * default text size.
   */
  public final @TextSizeType int textSizeType;

  /**
   * The default text size for this cue's text, or {@link #DIMEN_UNSET} if this cue has no default
   * text size.
   */
  public final float textSize;

  /**
   * The vertical formatting of this Cue, or {@link #TYPE_UNSET} if the cue has no vertical setting
   * (and so should be horizontal).
   */
  public final @VerticalType int verticalType;

  /**
   * The shear angle in degrees to be applied to this Cue, expressed in graphics coordinates. This
   * results in a skew transform for the block along the inline progression axis.
   */
  public final float shearDegrees;

  /** The Z index for cue, the larger index will render above the smaller index. May be negative. */
  @UnstableApi public final int zIndex;

  private Cue(
      @Nullable CharSequence text,
      @Nullable Alignment textAlignment,
      @Nullable Alignment multiRowAlignment,
      @Nullable Bitmap bitmap,
      float line,
      @LineType int lineType,
      @AnchorType int lineAnchor,
      float position,
      @AnchorType int positionAnchor,
      @TextSizeType int textSizeType,
      float textSize,
      float size,
      float bitmapHeight,
      boolean windowColorSet,
      int windowColor,
      @VerticalType int verticalType,
      float shearDegrees,
      int zIndex) {
    // Exactly one of text or bitmap should be set.
    if (text == null) {
      Assertions.checkNotNull(bitmap);
    } else {
      Assertions.checkArgument(bitmap == null);
    }
    if (text instanceof Spanned) {
      this.text = SpannedString.valueOf(text);
    } else if (text != null) {
      this.text = text.toString();
    } else {
      this.text = null;
    }
    this.textAlignment = textAlignment;
    this.multiRowAlignment = multiRowAlignment;
    this.bitmap = bitmap;
    this.line = line;
    this.lineType = lineType;
    this.lineAnchor = lineAnchor;
    this.position = position;
    this.positionAnchor = positionAnchor;
    this.size = size;
    this.bitmapHeight = bitmapHeight;
    this.windowColorSet = windowColorSet;
    this.windowColor = windowColor;
    this.textSizeType = textSizeType;
    this.textSize = textSize;
    this.verticalType = verticalType;
    this.shearDegrees = shearDegrees;
    this.zIndex = zIndex;
  }

  /** Returns a new {@link Cue.Builder} initialized with the same values as this Cue. */
  @UnstableApi
  public Builder buildUpon() {
    return new Cue.Builder(this);
  }

  @Override
  public boolean equals(@Nullable Object obj) {
    if (this == obj) {
      return true;
    }
    if (obj == null || getClass() != obj.getClass()) {
      return false;
    }
    Cue that = (Cue) obj;
    return TextUtils.equals(text, that.text)
        && textAlignment == that.textAlignment
        && multiRowAlignment == that.multiRowAlignment
        && (bitmap == null
            ? that.bitmap == null
            : (that.bitmap != null && bitmap.sameAs(that.bitmap)))
        && line == that.line
        && lineType == that.lineType
        && lineAnchor == that.lineAnchor
        && position == that.position
        && positionAnchor == that.positionAnchor
        && size == that.size
        && bitmapHeight == that.bitmapHeight
        && windowColorSet == that.windowColorSet
        && windowColor == that.windowColor
        && textSizeType == that.textSizeType
        && textSize == that.textSize
        && verticalType == that.verticalType
        && shearDegrees == that.shearDegrees
        && zIndex == that.zIndex;
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        text,
        textAlignment,
        multiRowAlignment,
        bitmap,
        line,
        lineType,
        lineAnchor,
        position,
        positionAnchor,
        size,
        bitmapHeight,
        windowColorSet,
        windowColor,
        textSizeType,
        textSize,
        verticalType,
        shearDegrees,
        zIndex);
  }

  /** A builder for {@link Cue} objects. */
  @UnstableApi
  public static final class Builder {
    @Nullable private CharSequence text;
    @Nullable private Bitmap bitmap;
    @Nullable private Alignment textAlignment;
    @Nullable private Alignment multiRowAlignment;
    private float line;
    private @LineType int lineType;
    private @AnchorType int lineAnchor;
    private float position;
    private @AnchorType int positionAnchor;
    private @TextSizeType int textSizeType;
    private float textSize;
    private float size;
    private float bitmapHeight;
    private boolean windowColorSet;
    @ColorInt private int windowColor;
    private @VerticalType int verticalType;
    private float shearDegrees;
    private int zIndex;

    public Builder() {
      text = null;
      bitmap = null;
      textAlignment = null;
      multiRowAlignment = null;
      line = DIMEN_UNSET;
      lineType = TYPE_UNSET;
      lineAnchor = TYPE_UNSET;
      position = DIMEN_UNSET;
      positionAnchor = TYPE_UNSET;
      textSizeType = TYPE_UNSET;
      textSize = DIMEN_UNSET;
      size = DIMEN_UNSET;
      bitmapHeight = DIMEN_UNSET;
      windowColorSet = false;
      windowColor = Color.BLACK;
      verticalType = TYPE_UNSET;
    }

    private Builder(Cue cue) {
      text = cue.text;
      bitmap = cue.bitmap;
      textAlignment = cue.textAlignment;
      multiRowAlignment = cue.multiRowAlignment;
      line = cue.line;
      lineType = cue.lineType;
      lineAnchor = cue.lineAnchor;
      position = cue.position;
      positionAnchor = cue.positionAnchor;
      textSizeType = cue.textSizeType;
      textSize = cue.textSize;
      size = cue.size;
      bitmapHeight = cue.bitmapHeight;
      windowColorSet = cue.windowColorSet;
      windowColor = cue.windowColor;
      verticalType = cue.verticalType;
      shearDegrees = cue.shearDegrees;
      zIndex = cue.zIndex;
    }

    /**
     * Sets the cue text.
     *
     * <p>Note that {@code text} may be decorated with styling spans.
     *
     * <p>Note that this will also set the {@code bitmap} to null.
     *
     * @see Cue#text
     */
    @CanIgnoreReturnValue
    public Builder setText(CharSequence text) {
      this.text = text;
      this.bitmap = null;
      return this;
    }

    /**
     * Gets the cue text.
     *
     * @see Cue#text
     */
    @Pure
    @Nullable
    public CharSequence getText() {
      return text;
    }

    /**
     * Sets the cue image.
     *
     * <p>Note that this will also set the {@code text} to null.
     *
     * @see Cue#bitmap
     */
    @CanIgnoreReturnValue
    public Builder setBitmap(Bitmap bitmap) {
      this.bitmap = bitmap;
      this.text = null;
      return this;
    }

    /**
     * Gets the cue image.
     *
     * @see Cue#bitmap
     */
    @Pure
    @Nullable
    public Bitmap getBitmap() {
      return bitmap;
    }

    /**
     * Sets the alignment of the cue text within the cue box.
     *
     * <p>Passing null means the alignment is undefined.
     *
     * @see Cue#textAlignment
     */
    @CanIgnoreReturnValue
    public Builder setTextAlignment(@Nullable Layout.Alignment textAlignment) {
      this.textAlignment = textAlignment;
      return this;
    }

    /**
     * Gets the alignment of the cue text within the cue box, or null if the alignment is undefined.
     *
     * @see Cue#textAlignment
     */
    @Pure
    @Nullable
    public Alignment getTextAlignment() {
      return textAlignment;
    }

    /**
     * Sets the multi-row alignment of the cue.
     *
     * <p>Passing null means the alignment is undefined.
     *
     * @see Cue#multiRowAlignment
     */
    @CanIgnoreReturnValue
    public Builder setMultiRowAlignment(@Nullable Layout.Alignment multiRowAlignment) {
      this.multiRowAlignment = multiRowAlignment;
      return this;
    }

    /**
     * Sets the position of the cue box within the viewport in the direction orthogonal to the
     * writing direction.
     *
     * @see Cue#line
     * @see Cue#lineType
     */
    @CanIgnoreReturnValue
    public Builder setLine(float line, @LineType int lineType) {
      this.line = line;
      this.lineType = lineType;
      return this;
    }

    /**
     * Gets the position of the {@code lineAnchor} of the cue box within the viewport in the
     * direction orthogonal to the writing direction.
     *
     * @see Cue#line
     */
    @Pure
    public float getLine() {
      return line;
    }

    /**
     * Gets the type of the value of {@link #getLine()}.
     *
     * @see Cue#lineType
     */
    @Pure
    public @LineType int getLineType() {
      return lineType;
    }

    /**
     * Sets the cue box anchor positioned by {@link #setLine(float, int) line}.
     *
     * @see Cue#lineAnchor
     */
    @CanIgnoreReturnValue
    public Builder setLineAnchor(@AnchorType int lineAnchor) {
      this.lineAnchor = lineAnchor;
      return this;
    }

    /**
     * Gets the cue box anchor positioned by {@link #setLine(float, int) line}.
     *
     * @see Cue#lineAnchor
     */
    @Pure
    public @AnchorType int getLineAnchor() {
      return lineAnchor;
    }

    /**
     * Sets the fractional position of the {@link #setPositionAnchor(int) positionAnchor} of the cue
     * box within the viewport in the direction orthogonal to {@link #setLine(float, int) line}.
     *
     * @see Cue#position
     */
    @CanIgnoreReturnValue
    public Builder setPosition(float position) {
      this.position = position;
      return this;
    }

    /**
     * Gets the fractional position of the {@link #setPositionAnchor(int) positionAnchor} of the cue
     * box within the viewport in the direction orthogonal to {@link #setLine(float, int) line}.
     *
     * @see Cue#position
     */
    @Pure
    public float getPosition() {
      return position;
    }

    /**
     * Sets the cue box anchor positioned by {@link #setPosition(float) position}.
     *
     * @see Cue#positionAnchor
     */
    @CanIgnoreReturnValue
    public Builder setPositionAnchor(@AnchorType int positionAnchor) {
      this.positionAnchor = positionAnchor;
      return this;
    }

    /**
     * Gets the cue box anchor positioned by {@link #setPosition(float) position}.
     *
     * @see Cue#positionAnchor
     */
    @Pure
    public @AnchorType int getPositionAnchor() {
      return positionAnchor;
    }

    /**
     * Sets the default text size and type for this cue's text.
     *
     * @see Cue#textSize
     * @see Cue#textSizeType
     */
    @CanIgnoreReturnValue
    public Builder setTextSize(float textSize, @TextSizeType int textSizeType) {
      this.textSize = textSize;
      this.textSizeType = textSizeType;
      return this;
    }

    /**
     * Gets the default text size type for this cue's text.
     *
     * @see Cue#textSizeType
     */
    @Pure
    public @TextSizeType int getTextSizeType() {
      return textSizeType;
    }

    /**
     * Gets the default text size for this cue's text.
     *
     * @see Cue#textSize
     */
    @Pure
    public float getTextSize() {
      return textSize;
    }

    /**
     * Sets the size of the cue box in the writing direction specified as a fraction of the viewport
     * size in that direction.
     *
     * @see Cue#size
     */
    @CanIgnoreReturnValue
    public Builder setSize(float size) {
      this.size = size;
      return this;
    }

    /**
     * Gets the size of the cue box in the writing direction specified as a fraction of the viewport
     * size in that direction.
     *
     * @see Cue#size
     */
    @Pure
    public float getSize() {
      return size;
    }

    /**
     * Sets the bitmap height as a fraction of the viewport size.
     *
     * @see Cue#bitmapHeight
     */
    @CanIgnoreReturnValue
    public Builder setBitmapHeight(float bitmapHeight) {
      this.bitmapHeight = bitmapHeight;
      return this;
    }

    /**
     * Gets the bitmap height as a fraction of the viewport size.
     *
     * @see Cue#bitmapHeight
     */
    @Pure
    public float getBitmapHeight() {
      return bitmapHeight;
    }

    /**
     * Sets the fill color of the window.
     *
     * <p>Also sets {@link Cue#windowColorSet} to true.
     *
     * @see Cue#windowColor
     * @see Cue#windowColorSet
     */
    @CanIgnoreReturnValue
    public Builder setWindowColor(@ColorInt int windowColor) {
      this.windowColor = windowColor;
      this.windowColorSet = true;
      return this;
    }

    /** Sets {@link Cue#windowColorSet} to false. */
    @CanIgnoreReturnValue
    public Builder clearWindowColor() {
      this.windowColorSet = false;
      return this;
    }

    /**
     * Returns true if the fill color of the window is set.
     *
     * @see Cue#windowColorSet
     */
    public boolean isWindowColorSet() {
      return windowColorSet;
    }

    /**
     * Gets the fill color of the window.
     *
     * @see Cue#windowColor
     */
    @Pure
    @ColorInt
    public int getWindowColor() {
      return windowColor;
    }

    /**
     * Sets the vertical formatting for this Cue.
     *
     * @see Cue#verticalType
     */
    @CanIgnoreReturnValue
    public Builder setVerticalType(@VerticalType int verticalType) {
      this.verticalType = verticalType;
      return this;
    }

    /** Sets the shear angle for this Cue. */
    @CanIgnoreReturnValue
    public Builder setShearDegrees(float shearDegrees) {
      this.shearDegrees = shearDegrees;
      return this;
    }

    /**
     * Gets the vertical formatting for this Cue.
     *
     * @see Cue#verticalType
     */
    @Pure
    public @VerticalType int getVerticalType() {
      return verticalType;
    }

    /** Sets the zIndex for this Cue. */
    @CanIgnoreReturnValue
    public Builder setZIndex(int zIndex) {
      this.zIndex = zIndex;
      return this;
    }

    /** Gets the zIndex for this Cue. */
    @Pure
    public int getZIndex() {
      return zIndex;
    }

    /** Build the cue. */
    public Cue build() {
      return new Cue(
          text,
          textAlignment,
          multiRowAlignment,
          bitmap,
          line,
          lineType,
          lineAnchor,
          position,
          positionAnchor,
          textSizeType,
          textSize,
          size,
          bitmapHeight,
          windowColorSet,
          windowColor,
          verticalType,
          shearDegrees,
          zIndex);
    }
  }

  private static final String FIELD_TEXT = Util.intToStringMaxRadix(0);
  private static final String FIELD_CUSTOM_SPANS = Util.intToStringMaxRadix(17);
  private static final String FIELD_TEXT_ALIGNMENT = Util.intToStringMaxRadix(1);
  private static final String FIELD_MULTI_ROW_ALIGNMENT = Util.intToStringMaxRadix(2);
  private static final String FIELD_BITMAP_PARCELABLE = Util.intToStringMaxRadix(3);
  private static final String FIELD_BITMAP_BYTES = Util.intToStringMaxRadix(18);
  private static final String FIELD_LINE = Util.intToStringMaxRadix(4);
  private static final String FIELD_LINE_TYPE = Util.intToStringMaxRadix(5);
  private static final String FIELD_LINE_ANCHOR = Util.intToStringMaxRadix(6);
  private static final String FIELD_POSITION = Util.intToStringMaxRadix(7);
  private static final String FIELD_POSITION_ANCHOR = Util.intToStringMaxRadix(8);
  private static final String FIELD_TEXT_SIZE_TYPE = Util.intToStringMaxRadix(9);
  private static final String FIELD_TEXT_SIZE = Util.intToStringMaxRadix(10);
  private static final String FIELD_SIZE = Util.intToStringMaxRadix(11);
  private static final String FIELD_BITMAP_HEIGHT = Util.intToStringMaxRadix(12);
  private static final String FIELD_WINDOW_COLOR = Util.intToStringMaxRadix(13);
  private static final String FIELD_WINDOW_COLOR_SET = Util.intToStringMaxRadix(14);
  private static final String FIELD_VERTICAL_TYPE = Util.intToStringMaxRadix(15);
  private static final String FIELD_SHEAR_DEGREES = Util.intToStringMaxRadix(16);
  private static final String FIELD_Z_INDEX = Util.intToStringMaxRadix(19);

  /**
   * Returns a {@link Bundle} that can be serialized to bytes.
   *
   * <p>Prefer the more efficient {@link #toBinderBasedBundle()} if the result doesn't need to be
   * serialized.
   *
   * <p>The {@link Bundle} returned from this method must not be passed to other processes that
   * might be using a different version of the media3 library.
   */
  @UnstableApi
  public Bundle toSerializableBundle() {
    Bundle bundle = toBundleWithoutBitmap();
    if (bitmap != null) {
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      // The PNG format is lossless, and the quality parameter is ignored.
      checkState(bitmap.compress(Bitmap.CompressFormat.PNG, /* quality= */ 0, output));
      bundle.putByteArray(FIELD_BITMAP_BYTES, output.toByteArray());
    }
    return bundle;
  }

  /**
   * Returns a {@link Bundle} that may contain {@link Binder} references, meaning it cannot be
   * safely serialized to bytes.
   *
   * <p>The {@link Bundle} returned from this method can be safely sent between processes and parsed
   * by older versions of the media3 library.
   *
   * <p>Use {@link #toSerializableBundle()} to get a {@link Bundle} that can be safely serialized.
   */
  @UnstableApi
  public Bundle toBinderBasedBundle() {
    Bundle bundle = toBundleWithoutBitmap();
    if (bitmap != null) {
      bundle.putParcelable(FIELD_BITMAP_PARCELABLE, bitmap);
    }
    return bundle;
  }

  /**
   * @deprecated Use {@link #toSerializableBundle()} or {@link #toBinderBasedBundle()} instead.
   */
  @UnstableApi
  @Deprecated
  public Bundle toBundle() {
    return toBinderBasedBundle();
  }

  private Bundle toBundleWithoutBitmap() {
    Bundle bundle = new Bundle();
    if (text != null) {
      bundle.putCharSequence(FIELD_TEXT, text);
      if (text instanceof Spanned) {
        ArrayList<Bundle> customSpanBundles = bundleCustomSpans((Spanned) text);
        if (!customSpanBundles.isEmpty()) {
          bundle.putParcelableArrayList(FIELD_CUSTOM_SPANS, customSpanBundles);
        }
      }
    }
    bundle.putSerializable(FIELD_TEXT_ALIGNMENT, textAlignment);
    bundle.putSerializable(FIELD_MULTI_ROW_ALIGNMENT, multiRowAlignment);
    bundle.putFloat(FIELD_LINE, line);
    bundle.putInt(FIELD_LINE_TYPE, lineType);
    bundle.putInt(FIELD_LINE_ANCHOR, lineAnchor);
    bundle.putFloat(FIELD_POSITION, position);
    bundle.putInt(FIELD_POSITION_ANCHOR, positionAnchor);
    bundle.putInt(FIELD_TEXT_SIZE_TYPE, textSizeType);
    bundle.putFloat(FIELD_TEXT_SIZE, textSize);
    bundle.putFloat(FIELD_SIZE, size);
    bundle.putFloat(FIELD_BITMAP_HEIGHT, bitmapHeight);
    bundle.putBoolean(FIELD_WINDOW_COLOR_SET, windowColorSet);
    bundle.putInt(FIELD_WINDOW_COLOR, windowColor);
    bundle.putInt(FIELD_VERTICAL_TYPE, verticalType);
    bundle.putFloat(FIELD_SHEAR_DEGREES, shearDegrees);
    bundle.putInt(FIELD_Z_INDEX, zIndex);
    return bundle;
  }

  /** Restores a cue from a {@link Bundle}. */
  @UnstableApi
  public static Cue fromBundle(Bundle bundle) {
    Builder builder = new Builder();
    @Nullable CharSequence text = bundle.getCharSequence(FIELD_TEXT);
    if (text != null) {
      builder.setText(text);
      @Nullable
      ArrayList<Bundle> customSpanBundles = bundle.getParcelableArrayList(FIELD_CUSTOM_SPANS);
      if (customSpanBundles != null) {
        SpannableString textWithCustomSpans = SpannableString.valueOf(text);
        for (Bundle customSpanBundle : customSpanBundles) {
          CustomSpanBundler.unbundleAndApplyCustomSpan(customSpanBundle, textWithCustomSpans);
        }
        builder.setText(textWithCustomSpans);
      }
    }
    @Nullable Alignment textAlignment = (Alignment) bundle.getSerializable(FIELD_TEXT_ALIGNMENT);
    if (textAlignment != null) {
      builder.setTextAlignment(textAlignment);
    }
    @Nullable
    Alignment multiRowAlignment = (Alignment) bundle.getSerializable(FIELD_MULTI_ROW_ALIGNMENT);
    if (multiRowAlignment != null) {
      builder.setMultiRowAlignment(multiRowAlignment);
    }
    @Nullable Bitmap bitmap = bundle.getParcelable(FIELD_BITMAP_PARCELABLE);
    if (bitmap != null) {
      builder.setBitmap(bitmap);
    } else {
      @Nullable byte[] bitmapBytes = bundle.getByteArray(FIELD_BITMAP_BYTES);
      if (bitmapBytes != null) {
        builder.setBitmap(
            BitmapFactory.decodeByteArray(bitmapBytes, /* offset= */ 0, bitmapBytes.length));
      }
    }
    if (bundle.containsKey(FIELD_LINE) && bundle.containsKey(FIELD_LINE_TYPE)) {
      builder.setLine(bundle.getFloat(FIELD_LINE), bundle.getInt(FIELD_LINE_TYPE));
    }
    if (bundle.containsKey(FIELD_LINE_ANCHOR)) {
      builder.setLineAnchor(bundle.getInt(FIELD_LINE_ANCHOR));
    }
    if (bundle.containsKey(FIELD_POSITION)) {
      builder.setPosition(bundle.getFloat(FIELD_POSITION));
    }
    if (bundle.containsKey(FIELD_POSITION_ANCHOR)) {
      builder.setPositionAnchor(bundle.getInt(FIELD_POSITION_ANCHOR));
    }
    if (bundle.containsKey(FIELD_TEXT_SIZE) && bundle.containsKey(FIELD_TEXT_SIZE_TYPE)) {
      builder.setTextSize(bundle.getFloat(FIELD_TEXT_SIZE), bundle.getInt(FIELD_TEXT_SIZE_TYPE));
    }
    if (bundle.containsKey(FIELD_SIZE)) {
      builder.setSize(bundle.getFloat(FIELD_SIZE));
    }
    if (bundle.containsKey(FIELD_BITMAP_HEIGHT)) {
      builder.setBitmapHeight(bundle.getFloat(FIELD_BITMAP_HEIGHT));
    }
    if (bundle.containsKey(FIELD_WINDOW_COLOR)) {
      builder.setWindowColor(bundle.getInt(FIELD_WINDOW_COLOR));
    }
    if (!bundle.getBoolean(FIELD_WINDOW_COLOR_SET, /* defaultValue= */ false)) {
      builder.clearWindowColor();
    }
    if (bundle.containsKey(FIELD_VERTICAL_TYPE)) {
      builder.setVerticalType(bundle.getInt(FIELD_VERTICAL_TYPE));
    }
    if (bundle.containsKey(FIELD_SHEAR_DEGREES)) {
      builder.setShearDegrees(bundle.getFloat(FIELD_SHEAR_DEGREES));
    }
    if (bundle.containsKey(FIELD_Z_INDEX)) {
      builder.setZIndex(bundle.getInt(FIELD_Z_INDEX));
    }
    return builder.build();
  }
}
