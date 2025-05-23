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
package androidx.media3.effect;

import static android.opengl.GLES20.GL_FALSE;
import static android.opengl.GLES20.GL_TRUE;
import static android.os.Build.VERSION.SDK_INT;
import static androidx.media3.common.VideoFrameProcessor.INPUT_TYPE_BITMAP;
import static androidx.media3.common.util.Assertions.checkArgument;
import static androidx.media3.common.util.Assertions.checkState;
import static androidx.media3.effect.DefaultVideoFrameProcessor.WORKING_COLOR_SPACE_LINEAR;
import static java.lang.Math.max;

import android.content.Context;
import android.graphics.Gainmap;
import android.opengl.GLES20;
import android.opengl.Matrix;
import androidx.annotation.RequiresApi;
import androidx.media3.common.C;
import androidx.media3.common.ColorInfo;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.VideoFrameProcessor.InputType;
import androidx.media3.common.util.GlProgram;
import androidx.media3.common.util.GlUtil;
import androidx.media3.common.util.GlUtil.GlException;
import androidx.media3.common.util.Size;
import androidx.media3.effect.DefaultVideoFrameProcessor.WorkingColorSpace;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/**
 * Applies a sequence of {@link MatrixTransformation MatrixTransformations} in the vertex shader and
 * a sequence of {@link RgbMatrix RgbMatrices} in the fragment shader. Copies input pixels into an
 * output frame based on their locations after applying the sequence of transformation matrices.
 *
 * <p>{@link MatrixTransformation} operations are done on normalized device coordinates (-1 to 1 on
 * x, y, and z axes). Transformed vertices that are moved outside of this range after any of the
 * transformation matrices are clipped to the NDC range.
 *
 * <p>After applying all {@link RgbMatrix} instances, color values are clamped to the limits of the
 * color space (e.g. BT.709 for SDR). Intermediate results are not clamped.
 *
 * <p>The background color of the output frame will be (r=0, g=0, b=0, a=0).
 *
 * <p>Can copy frames from an external texture and apply color transformations for HDR if needed.
 */
@SuppressWarnings("FunctionalInterfaceClash") // b/228192298
/* package */ final class DefaultShaderProgram extends BaseGlShaderProgram
    implements ExternalShaderProgram, RepeatingGainmapShaderProgram {

  private static final String VERTEX_SHADER_TRANSFORMATION_PATH =
      "shaders/vertex_shader_transformation_es2.glsl";
  private static final String VERTEX_SHADER_TRANSFORMATION_ES3_PATH =
      "shaders/vertex_shader_transformation_es3.glsl";
  private static final String FRAGMENT_SHADER_TRANSFORMATION_PATH =
      "shaders/fragment_shader_transformation_es2.glsl";
  private static final String FRAGMENT_SHADER_COPY_PATH = "shaders/fragment_shader_copy_es2.glsl";
  private static final String FRAGMENT_SHADER_OETF_ES3_PATH =
      "shaders/fragment_shader_oetf_es3.glsl";
  private static final String FRAGMENT_SHADER_TRANSFORMATION_SDR_OETF_ES2_PATH =
      "shaders/fragment_shader_transformation_sdr_oetf_es2.glsl";
  private static final String FRAGMENT_SHADER_TRANSFORMATION_EXTERNAL_YUV_ES3_PATH =
      "shaders/fragment_shader_transformation_external_yuv_es3.glsl";
  private static final String FRAGMENT_SHADER_TRANSFORMATION_SDR_EXTERNAL_PATH =
      "shaders/fragment_shader_transformation_sdr_external_es2.glsl";
  private static final String FRAGMENT_SHADER_TRANSFORMATION_HDR_INTERNAL_ES3_PATH =
      "shaders/fragment_shader_transformation_hdr_internal_es3.glsl";
  private static final String FRAGMENT_SHADER_TRANSFORMATION_ULTRA_HDR_ES3_PATH =
      "shaders/fragment_shader_transformation_ultra_hdr_es3.glsl";
  private static final String FRAGMENT_SHADER_TRANSFORMATION_SDR_INTERNAL_PATH =
      "shaders/fragment_shader_transformation_sdr_internal_es2.glsl";
  private static final ImmutableList<float[]> NDC_SQUARE =
      ImmutableList.of(
          new float[] {-1, -1, 0, 1},
          new float[] {-1, 1, 0, 1},
          new float[] {1, 1, 0, 1},
          new float[] {1, -1, 0, 1});

  // YUV to RGB color transform coefficients can be calculated from the BT.2020 specification, by
  // inverting the RGB to YUV equations, and scaling for limited range.
  // https://www.itu.int/dms_pubrec/itu-r/rec/bt/R-REC-BT.2020-2-201510-I!!PDF-E.pdf
  private static final float[] BT2020_FULL_RANGE_YUV_TO_RGB_COLOR_TRANSFORM_MATRIX = {
    1.0000f, 1.0000f, 1.0000f,
    0.0000f, -0.1646f, 1.8814f,
    1.4746f, -0.5714f, 0.0000f
  };
  private static final float[] BT2020_LIMITED_RANGE_YUV_TO_RGB_COLOR_TRANSFORM_MATRIX = {
    1.1689f, 1.1689f, 1.1689f,
    0.0000f, -0.1881f, 2.1502f,
    1.6853f, -0.6530f, 0.0000f,
  };

  private final GlProgram glProgram;

  /** The {@link MatrixTransformation MatrixTransformations} to apply. */
  private final ImmutableList<GlMatrixTransformation> matrixTransformations;

  /** The {@link RgbMatrix RgbMatrices} to apply. */
  private final ImmutableList<RgbMatrix> rgbMatrices;

  /** Whether the frame is in HDR or not. */
  private final boolean useHdr;

  /**
   * The transformation matrices provided by the {@link MatrixTransformation MatrixTransformations}
   * for the most recent frame.
   */
  private final float[][] transformationMatrixCache;

  /** The RGB matrices provided by the {@link RgbMatrix RgbMatrices} for the most recent frame. */
  private final float[][] rgbMatrixCache;

  /**
   * The product of the {@link #transformationMatrixCache} for the most recent frame, to be applied
   * in the vertex shader.
   */
  private final float[] compositeTransformationMatrixArray;

  /**
   * The product of the {@link #rgbMatrixCache} for the most recent frame, to be applied in the
   * fragment shader.
   */
  private final float[] compositeRgbMatrixArray;

  /** Matrix for storing an intermediate calculation result. */
  private final float[] tempResultMatrix;

  /** The texture minification filter to use when sampling from the input texture. */
  private final @C.TextureMinFilter int textureMinFilter;

  /**
   * A polygon in the input space chosen such that no additional clipping is needed to keep vertices
   * inside the NDC range when applying each of the {@link #matrixTransformations}.
   *
   * <p>This means that this polygon and {@link #compositeTransformationMatrixArray} can be used
   * instead of applying each of the {@link #matrixTransformations} to {@link #NDC_SQUARE} in
   * separate shaders.
   */
  private ImmutableList<float[]> visiblePolygon;

  private @MonotonicNonNull Gainmap lastGainmap;
  private int gainmapTexId;
  private @C.ColorTransfer int outputColorTransfer;
  private boolean shouldRepeatLastFrame;
  private boolean isRepeatingFrameDrawn;

  /**
   * Creates a new instance.
   *
   * <p>Input and output are both intermediate optical/linear colors, and RGB BT.2020 if {@code
   * useHdr} is {@code true} and RGB BT.709 if not.
   *
   * @param context The {@link Context}.
   * @param matrixTransformations The {@link GlMatrixTransformation GlMatrixTransformations} to
   *     apply to each frame in order. Can be empty to apply no vertex transformations.
   * @param rgbMatrices The {@link RgbMatrix RgbMatrices} to apply to each frame in order. Can be
   *     empty to apply no color transformations.
   * @param useHdr Whether input and output colors are HDR.
   * @throws VideoFrameProcessingException If a problem occurs while reading shader files or an
   *     OpenGL operation fails or is unsupported.
   */
  public static DefaultShaderProgram create(
      Context context,
      List<GlMatrixTransformation> matrixTransformations,
      List<RgbMatrix> rgbMatrices,
      boolean useHdr)
      throws VideoFrameProcessingException {
    String fragmentShaderFilePath =
        rgbMatrices.isEmpty()
            // Ensure colors not multiplied by a uRgbMatrix (even the identity) as it can create
            // color shifts on electrical pq tonemapped content.
            ? FRAGMENT_SHADER_COPY_PATH
            : FRAGMENT_SHADER_TRANSFORMATION_PATH;
    GlProgram glProgram =
        createGlProgram(context, VERTEX_SHADER_TRANSFORMATION_PATH, fragmentShaderFilePath);

    // No transfer functions needed/applied, because input and output are in the same color space.
    return new DefaultShaderProgram(
        glProgram,
        ImmutableList.copyOf(matrixTransformations),
        ImmutableList.copyOf(rgbMatrices),
        /* outputColorTransfer= */ C.COLOR_TRANSFER_LINEAR,
        useHdr);
  }

  /**
   * Creates a new instance.
   *
   * <p>Input will be sampled from an internal (i.e. regular) texture.
   *
   * <p>Applies the {@linkplain ColorInfo#colorTransfer inputColorInfo EOTF} to convert from
   * electrical color input, to intermediate optical {@link GlShaderProgram} color output. Also
   * applies the {@linkplain ColorInfo#colorTransfer outputColorInfo OETF}, if needed, to convert
   * back to an electrical color output.
   *
   * @param context The {@link Context}.
   * @param inputColorInfo The input electrical (nonlinear) {@link ColorInfo}.
   * @param outputColorInfo The output electrical (nonlinear) or optical (linear) {@link ColorInfo}.
   *     If this is an optical color, it must be BT.2020 if {@code inputColorInfo} is {@linkplain
   *     ColorInfo#isTransferHdr(ColorInfo) HDR}, and RGB BT.709 if not.
   * @param sdrWorkingColorSpace The {@link WorkingColorSpace} to apply effects in.
   * @throws VideoFrameProcessingException If a problem occurs while reading shader files or an
   *     OpenGL operation fails or is unsupported.
   */
  public static DefaultShaderProgram createWithInternalSampler(
      Context context,
      ColorInfo inputColorInfo,
      ColorInfo outputColorInfo,
      @WorkingColorSpace int sdrWorkingColorSpace,
      @InputType int inputType)
      throws VideoFrameProcessingException {
    checkState(
        inputColorInfo.colorTransfer != C.COLOR_TRANSFER_SRGB || inputType == INPUT_TYPE_BITMAP);
    boolean isInputTransferHdr = ColorInfo.isTransferHdr(inputColorInfo);
    boolean isUsingUltraHdr =
        inputType == INPUT_TYPE_BITMAP && outputColorInfo.colorSpace == C.COLOR_SPACE_BT2020;
    String vertexShaderFilePath =
        isInputTransferHdr || isUsingUltraHdr
            ? VERTEX_SHADER_TRANSFORMATION_ES3_PATH
            : VERTEX_SHADER_TRANSFORMATION_PATH;
    String fragmentShaderFilePath =
        isUsingUltraHdr
            ? FRAGMENT_SHADER_TRANSFORMATION_ULTRA_HDR_ES3_PATH
            : isInputTransferHdr
                ? FRAGMENT_SHADER_TRANSFORMATION_HDR_INTERNAL_ES3_PATH
                : FRAGMENT_SHADER_TRANSFORMATION_SDR_INTERNAL_PATH;
    GlProgram glProgram = createGlProgram(context, vertexShaderFilePath, fragmentShaderFilePath);
    if (!isUsingUltraHdr) {
      checkArgument(
          isInputTransferHdr
              || inputColorInfo.colorTransfer == C.COLOR_TRANSFER_SRGB
              || inputColorInfo.colorTransfer == C.COLOR_TRANSFER_SDR);
      glProgram.setIntUniform("uInputColorTransfer", inputColorInfo.colorTransfer);
    }
    if (isInputTransferHdr) {
      glProgram.setIntUniform(
          "uApplyHdrToSdrToneMapping",
          outputColorInfo.colorSpace != C.COLOR_SPACE_BT2020 ? GL_TRUE : GL_FALSE);
    }
    ImmutableList<GlMatrixTransformation> matrixTransformations = ImmutableList.of();
    if (inputType == INPUT_TYPE_BITMAP) {
      matrixTransformations =
          ImmutableList.of(
              (MatrixTransformation)
                  presentationTimeUs -> {
                    android.graphics.Matrix mirrorY = new android.graphics.Matrix();
                    mirrorY.setScale(/* sx= */ 1, /* sy= */ -1);
                    return mirrorY;
                  });
    }
    return createWithSampler(
        glProgram, inputColorInfo, outputColorInfo, sdrWorkingColorSpace, matrixTransformations);
  }

  /**
   * Creates a new instance.
   *
   * <p>Input will be sampled from an external texture. The caller should use {@link
   * #setTextureTransformMatrix(float[])} to provide the transformation matrix associated with the
   * external texture.
   *
   * <p>Applies the {@linkplain ColorInfo#colorTransfer inputColorInfo EOTF} to convert from
   * electrical color input, to intermediate optical {@link GlShaderProgram} color output. Also
   * applies the {@linkplain ColorInfo#colorTransfer outputColorInfo OETF}, if needed, to convert
   * back to an electrical color output.
   *
   * @param context The {@link Context}.
   * @param inputColorInfo The input electrical (nonlinear) {@link ColorInfo}.
   * @param outputColorInfo The output electrical (nonlinear) or optical (linear) {@link ColorInfo}.
   *     If this is an optical color, it must be BT.2020 if {@code inputColorInfo} is {@linkplain
   *     ColorInfo#isTransferHdr(ColorInfo) HDR}, and RGB BT.709 if not.
   * @param sdrWorkingColorSpace The {@link WorkingColorSpace} to apply effects in.
   * @param sampleWithNearest Whether external textures require GL_NEAREST sampling.
   * @throws VideoFrameProcessingException If a problem occurs while reading shader files or an
   *     OpenGL operation fails or is unsupported.
   */
  public static DefaultShaderProgram createWithExternalSampler(
      Context context,
      ColorInfo inputColorInfo,
      ColorInfo outputColorInfo,
      @WorkingColorSpace int sdrWorkingColorSpace,
      boolean sampleWithNearest)
      throws VideoFrameProcessingException {
    boolean isInputTransferHdr = ColorInfo.isTransferHdr(inputColorInfo);
    String vertexShaderFilePath =
        isInputTransferHdr
            ? VERTEX_SHADER_TRANSFORMATION_ES3_PATH
            : VERTEX_SHADER_TRANSFORMATION_PATH;
    String fragmentShaderFilePath =
        isInputTransferHdr
            ? FRAGMENT_SHADER_TRANSFORMATION_EXTERNAL_YUV_ES3_PATH
            : FRAGMENT_SHADER_TRANSFORMATION_SDR_EXTERNAL_PATH;
    GlProgram glProgram = createGlProgram(context, vertexShaderFilePath, fragmentShaderFilePath);
    if (isInputTransferHdr) {
      // In HDR editing mode the decoder output is sampled in YUV.
      if (!GlUtil.isYuvTargetExtensionSupported()) {
        throw new VideoFrameProcessingException(
            "The EXT_YUV_target extension is required for HDR editing input.");
      }
      glProgram.setFloatsUniform(
          "uYuvToRgbColorTransform",
          inputColorInfo.colorRange == C.COLOR_RANGE_FULL
              ? BT2020_FULL_RANGE_YUV_TO_RGB_COLOR_TRANSFORM_MATRIX
              : BT2020_LIMITED_RANGE_YUV_TO_RGB_COLOR_TRANSFORM_MATRIX);
      glProgram.setIntUniform("uInputColorTransfer", inputColorInfo.colorTransfer);
      glProgram.setIntUniform(
          "uApplyHdrToSdrToneMapping",
          outputColorInfo.colorSpace != C.COLOR_SPACE_BT2020 ? GL_TRUE : GL_FALSE);
    }
    glProgram.setExternalTexturesRequireNearestSampling(sampleWithNearest);

    return createWithSampler(
        glProgram,
        inputColorInfo,
        outputColorInfo,
        sdrWorkingColorSpace,
        /* matrixTransformations= */ ImmutableList.of());
  }

  /**
   * Creates a new instance.
   *
   * <p>Applies the {@linkplain ColorInfo#colorTransfer outputColorInfo OETF} to convert from
   * intermediate optical {@link GlShaderProgram} color input, to electrical color output, after
   * {@code matrixTransformations} and {@code rgbMatrices} are applied.
   *
   * <p>Intermediate optical/linear colors are RGB BT.2020 if {@code outputColorInfo} is {@linkplain
   * ColorInfo#isTransferHdr(ColorInfo) HDR}, and RGB BT.709 if not.
   *
   * @param context The {@link Context}.
   * @param matrixTransformations The {@link GlMatrixTransformation GlMatrixTransformations} to
   *     apply to each frame in order. Can be empty to apply no vertex transformations.
   * @param rgbMatrices The {@link RgbMatrix RgbMatrices} to apply to each frame in order. Can be
   *     empty to apply no color transformations.
   * @param outputColorInfo The electrical (non-linear) {@link ColorInfo} describing output colors.
   * @param sdrWorkingColorSpace The {@link WorkingColorSpace} to apply effects in.
   * @throws VideoFrameProcessingException If a problem occurs while reading shader files or an
   *     OpenGL operation fails or is unsupported.
   */
  public static DefaultShaderProgram createApplyingOetf(
      Context context,
      List<GlMatrixTransformation> matrixTransformations,
      List<RgbMatrix> rgbMatrices,
      ColorInfo outputColorInfo,
      @WorkingColorSpace int sdrWorkingColorSpace)
      throws VideoFrameProcessingException {
    boolean outputIsHdr = ColorInfo.isTransferHdr(outputColorInfo);
    boolean shouldApplyOetf = sdrWorkingColorSpace == WORKING_COLOR_SPACE_LINEAR;
    String vertexShaderFilePath =
        outputIsHdr ? VERTEX_SHADER_TRANSFORMATION_ES3_PATH : VERTEX_SHADER_TRANSFORMATION_PATH;
    String fragmentShaderFilePath =
        outputIsHdr
            ? FRAGMENT_SHADER_OETF_ES3_PATH
            : shouldApplyOetf
                ? FRAGMENT_SHADER_TRANSFORMATION_SDR_OETF_ES2_PATH
                : rgbMatrices.isEmpty()
                    // Ensure colors not multiplied by a uRgbMatrix (even the identity) as it can
                    // create color shifts on electrical pq tonemapped content.
                    ? FRAGMENT_SHADER_COPY_PATH
                    : FRAGMENT_SHADER_TRANSFORMATION_PATH;
    GlProgram glProgram = createGlProgram(context, vertexShaderFilePath, fragmentShaderFilePath);

    @C.ColorTransfer int outputColorTransfer = outputColorInfo.colorTransfer;
    if (outputIsHdr) {
      checkArgument(
          outputColorTransfer == C.COLOR_TRANSFER_HLG
              || outputColorTransfer == C.COLOR_TRANSFER_ST2084);
      glProgram.setIntUniform("uOutputColorTransfer", outputColorTransfer);
    } else if (shouldApplyOetf) {
      checkArgument(
          outputColorTransfer == C.COLOR_TRANSFER_SDR
              || outputColorTransfer == C.COLOR_TRANSFER_GAMMA_2_2);
      glProgram.setIntUniform("uOutputColorTransfer", outputColorTransfer);
    }

    return new DefaultShaderProgram(
        glProgram,
        ImmutableList.copyOf(matrixTransformations),
        ImmutableList.copyOf(rgbMatrices),
        outputColorInfo.colorTransfer,
        outputIsHdr);
  }

  private static DefaultShaderProgram createWithSampler(
      GlProgram glProgram,
      ColorInfo inputColorInfo,
      ColorInfo outputColorInfo,
      @WorkingColorSpace int sdrWorkingColorSpace,
      ImmutableList<GlMatrixTransformation> matrixTransformations) {
    boolean isInputTransferHdr = ColorInfo.isTransferHdr(inputColorInfo);
    boolean isExpandingColorGamut =
        (inputColorInfo.colorSpace == C.COLOR_SPACE_BT709
                || inputColorInfo.colorSpace == C.COLOR_SPACE_BT601)
            && outputColorInfo.colorSpace == C.COLOR_SPACE_BT2020;
    @C.ColorTransfer int outputColorTransfer = outputColorInfo.colorTransfer;
    if (isInputTransferHdr) {
      // TODO(b/239735341): Add a setBooleanUniform method to GlProgram.
      if (outputColorTransfer == C.COLOR_TRANSFER_SDR) {
        // When tone-mapping from HDR to SDR, COLOR_TRANSFER_SDR is interpreted as
        // COLOR_TRANSFER_GAMMA_2_2.
        outputColorTransfer = C.COLOR_TRANSFER_GAMMA_2_2;
      }
      checkArgument(
          outputColorTransfer == C.COLOR_TRANSFER_LINEAR
              || outputColorTransfer == C.COLOR_TRANSFER_GAMMA_2_2
              || outputColorTransfer == C.COLOR_TRANSFER_ST2084
              || outputColorTransfer == C.COLOR_TRANSFER_HLG);
      glProgram.setIntUniform("uOutputColorTransfer", outputColorTransfer);
    } else if (isExpandingColorGamut) {
      checkArgument(
          outputColorTransfer == C.COLOR_TRANSFER_LINEAR
              || outputColorTransfer == C.COLOR_TRANSFER_ST2084
              || outputColorTransfer == C.COLOR_TRANSFER_HLG);
      glProgram.setIntUniform("uOutputColorTransfer", outputColorTransfer);
    } else {
      glProgram.setIntUniform("uSdrWorkingColorSpace", sdrWorkingColorSpace);
      checkArgument(
          outputColorTransfer == C.COLOR_TRANSFER_SDR
              || outputColorTransfer == C.COLOR_TRANSFER_LINEAR);
      // The SDR shader automatically applies a COLOR_TRANSFER_SDR EOTF.
      glProgram.setIntUniform("uOutputColorTransfer", outputColorTransfer);
    }

    return new DefaultShaderProgram(
        glProgram,
        matrixTransformations,
        /* rgbMatrices= */ ImmutableList.of(),
        outputColorInfo.colorTransfer,
        /* useHdr= */ isInputTransferHdr || isExpandingColorGamut);
  }

  /**
   * Creates a new instance.
   *
   * @param glProgram The {@link GlProgram}.
   * @param matrixTransformations The {@link GlMatrixTransformation GlMatrixTransformations} to
   *     apply to each frame in order. Can be empty to apply no vertex transformations.
   * @param rgbMatrices The {@link RgbMatrix RgbMatrices} to apply to each frame in order. Can be
   *     empty to apply no color transformations.
   * @param outputColorTransfer The output {@link C.ColorTransfer}.
   * @param useHdr Whether to process the input as an HDR signal. Using HDR requires the {@code
   *     EXT_YUV_target} OpenGL extension.
   */
  private DefaultShaderProgram(
      GlProgram glProgram,
      ImmutableList<GlMatrixTransformation> matrixTransformations,
      ImmutableList<RgbMatrix> rgbMatrices,
      int outputColorTransfer,
      boolean useHdr) {
    super(/* useHighPrecisionColorComponents= */ useHdr, /* texturePoolCapacity= */ 1);
    this.glProgram = glProgram;
    this.outputColorTransfer = outputColorTransfer;
    this.matrixTransformations = matrixTransformations;
    this.rgbMatrices = rgbMatrices;
    this.useHdr = useHdr;

    transformationMatrixCache = new float[matrixTransformations.size()][16];
    rgbMatrixCache = new float[rgbMatrices.size()][16];
    compositeTransformationMatrixArray = GlUtil.create4x4IdentityMatrix();
    compositeRgbMatrixArray = GlUtil.create4x4IdentityMatrix();
    tempResultMatrix = new float[16];
    visiblePolygon = NDC_SQUARE;
    gainmapTexId = C.INDEX_UNSET;

    // When multiple matrix transformations are applied in a single shader program, use the highest
    // quality resampling algorithm requested.
    @C.TextureMinFilter int textureMinFilter = C.TEXTURE_MIN_FILTER_LINEAR;
    for (int i = 0; i < matrixTransformations.size(); i++) {
      textureMinFilter =
          max(textureMinFilter, matrixTransformations.get(i).getGlTextureMinFilter());
    }
    this.textureMinFilter = textureMinFilter;
  }

  private static GlProgram createGlProgram(
      Context context, String vertexShaderFilePath, String fragmentShaderFilePath)
      throws VideoFrameProcessingException {

    GlProgram glProgram;
    try {
      glProgram = new GlProgram(context, vertexShaderFilePath, fragmentShaderFilePath);
    } catch (IOException | GlUtil.GlException e) {
      throw new VideoFrameProcessingException(e);
    }

    glProgram.setFloatsUniform("uTexTransformationMatrix", GlUtil.create4x4IdentityMatrix());
    return glProgram;
  }

  @Override
  public void setTextureTransformMatrix(float[] textureTransformMatrix) {
    glProgram.setFloatsUniform("uTexTransformationMatrix", textureTransformMatrix);
  }

  @Override
  public Size configure(int inputWidth, int inputHeight) {
    return MatrixUtils.configureAndGetOutputSize(inputWidth, inputHeight, matrixTransformations);
  }

  @Override
  public void drawFrame(int inputTexId, long presentationTimeUs)
      throws VideoFrameProcessingException {
    boolean compositeRgbMatrixArrayChanged = updateCompositeRgbMatrixArray(presentationTimeUs);
    boolean compositeTransformationMatrixAndVisiblePolygonChanged =
        updateCompositeTransformationMatrixAndVisiblePolygon(presentationTimeUs);
    boolean uniformsChanged =
        compositeRgbMatrixArrayChanged || compositeTransformationMatrixAndVisiblePolygonChanged;
    if (visiblePolygon.size() < 3) {
      return; // Need at least three visible vertices for a triangle.
    }

    if (shouldRepeatLastFrame && !uniformsChanged && isRepeatingFrameDrawn) {
      return;
    }
    try {
      glProgram.use();
      setGainmapSamplerAndUniforms();
      glProgram.setSamplerTexIdUniform(
          "uTexSampler", inputTexId, /* texUnitIndex= */ 0, textureMinFilter);
      glProgram.setFloatsUniform("uTransformationMatrix", compositeTransformationMatrixArray);
      glProgram.setFloatsUniformIfPresent("uRgbMatrix", compositeRgbMatrixArray);
      glProgram.setBufferAttribute(
          "aFramePosition",
          GlUtil.createVertexBuffer(visiblePolygon),
          GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE);
      glProgram.bindAttributesAndUniforms();
      GLES20.glDrawArrays(
          GLES20.GL_TRIANGLE_FAN, /* first= */ 0, /* count= */ visiblePolygon.size());
      GlUtil.checkGlError();
    } catch (GlUtil.GlException e) {
      throw new VideoFrameProcessingException(e, presentationTimeUs);
    }
    isRepeatingFrameDrawn = true;
  }

  @Override
  public void release() throws VideoFrameProcessingException {
    super.release();
    try {
      glProgram.delete();
      if (gainmapTexId != C.INDEX_UNSET) {
        GlUtil.deleteTexture(gainmapTexId);
      }
    } catch (GlUtil.GlException e) {
      throw new VideoFrameProcessingException(e);
    }
  }

  /**
   * Sets the {@link Gainmap} applied to the input frame to create a HDR output frame.
   *
   * <p>The gainmap is ignored if {@code useHdr} is {@code false}.
   */
  @Override
  @RequiresApi(34) // getGainmapContents() added in API level 34.
  public void setGainmap(Gainmap gainmap) throws GlException {
    if (!useHdr) {
      return;
    }
    if (lastGainmap != null && GainmapUtil.equals(this.lastGainmap, gainmap)) {
      return;
    }
    isRepeatingFrameDrawn = false;
    this.lastGainmap = gainmap;
    if (gainmapTexId == C.INDEX_UNSET) {
      gainmapTexId = GlUtil.createTexture(gainmap.getGainmapContents());
    } else {
      GlUtil.setTexture(gainmapTexId, gainmap.getGainmapContents());
    }
  }

  @Override
  public void signalNewRepeatingFrameSequence() {
    // Skipping drawFrame() is only allowed if there's only one possible output texture.
    checkState(outputTexturePool.capacity() == 1);
    shouldRepeatLastFrame = true;
    isRepeatingFrameDrawn = false;
  }

  @Override
  public boolean shouldClearTextureBuffer() {
    return !(isRepeatingFrameDrawn && shouldRepeatLastFrame);
  }

  /**
   * Sets the output {@link C.ColorTransfer}.
   *
   * <p>This method must not be called on {@code DefaultShaderProgram} instances that output
   * {@linkplain C#COLOR_TRANSFER_LINEAR linear colors}.
   */
  public void setOutputColorTransfer(@C.ColorTransfer int colorTransfer) {
    checkState(outputColorTransfer != C.COLOR_TRANSFER_LINEAR);
    outputColorTransfer = colorTransfer;
    glProgram.setIntUniform("uOutputColorTransfer", colorTransfer);
  }

  /** Returns the output {@link C.ColorTransfer}. */
  public @C.ColorTransfer int getOutputColorTransfer() {
    return outputColorTransfer;
  }

  /**
   * Updates {@link #compositeTransformationMatrixArray} and {@link #visiblePolygon} based on the
   * given frame timestamp.
   *
   * <p>Returns whether the transformation matrix or visible polygon has changed.
   */
  private boolean updateCompositeTransformationMatrixAndVisiblePolygon(long presentationTimeUs) {
    float[][] matricesAtPresentationTime = new float[matrixTransformations.size()][16];
    for (int i = 0; i < matrixTransformations.size(); i++) {
      matricesAtPresentationTime[i] =
          matrixTransformations.get(i).getGlMatrixArray(presentationTimeUs);
    }

    if (!updateMatrixCache(transformationMatrixCache, matricesAtPresentationTime)) {
      return false;
    }

    // Compute the compositeTransformationMatrix and transform and clip the visiblePolygon for each
    // MatrixTransformation's matrix.
    GlUtil.setToIdentity(compositeTransformationMatrixArray);
    visiblePolygon = NDC_SQUARE;
    for (float[] transformationMatrix : transformationMatrixCache) {
      Matrix.multiplyMM(
          /* result= */ tempResultMatrix,
          /* resultOffset= */ 0,
          /* lhs= */ transformationMatrix,
          /* lhsOffset= */ 0,
          /* rhs= */ compositeTransformationMatrixArray,
          /* rhsOffset= */ 0);
      System.arraycopy(
          /* src= */ tempResultMatrix,
          /* srcPos= */ 0,
          /* dest= */ compositeTransformationMatrixArray,
          /* destPost= */ 0,
          /* length= */ tempResultMatrix.length);
      visiblePolygon =
          MatrixUtils.clipConvexPolygonToNdcRange(
              MatrixUtils.transformPoints(transformationMatrix, visiblePolygon));
      if (visiblePolygon.size() < 3) {
        // Can ignore remaining matrices as there are not enough vertices left to form a polygon.
        return true;
      }
    }
    // Calculate the input frame vertices corresponding to the output frame's visible polygon.
    Matrix.invertM(
        tempResultMatrix,
        /* mInvOffset= */ 0,
        compositeTransformationMatrixArray,
        /* mOffset= */ 0);
    visiblePolygon = MatrixUtils.transformPoints(tempResultMatrix, visiblePolygon);
    return true;
  }

  /**
   * Updates {@link #compositeRgbMatrixArray} based on the given frame timestamp.
   *
   * <p>Returns whether the {@link #compositeRgbMatrixArray} has changed.
   */
  private boolean updateCompositeRgbMatrixArray(long presentationTimeUs) {
    float[][] matricesCurrTimestamp = new float[rgbMatrices.size()][16];
    for (int i = 0; i < rgbMatrices.size(); i++) {
      matricesCurrTimestamp[i] = rgbMatrices.get(i).getMatrix(presentationTimeUs, useHdr);
    }

    if (!updateMatrixCache(rgbMatrixCache, matricesCurrTimestamp)) {
      return false;
    }

    GlUtil.setToIdentity(compositeRgbMatrixArray);

    for (int i = 0; i < rgbMatrices.size(); i++) {
      Matrix.multiplyMM(
          /* result= */ tempResultMatrix,
          /* resultOffset= */ 0,
          /* lhs= */ rgbMatrices.get(i).getMatrix(presentationTimeUs, useHdr),
          /* lhsOffset= */ 0,
          /* rhs= */ compositeRgbMatrixArray,
          /* rhsOffset= */ 0);
      System.arraycopy(
          /* src= */ tempResultMatrix,
          /* srcPos= */ 0,
          /* dest= */ compositeRgbMatrixArray,
          /* destPost= */ 0,
          /* length= */ tempResultMatrix.length);
    }
    return true;
  }

  /**
   * Updates the {@code cachedMatrices} with the {@code newMatrices}. Returns whether a matrix has
   * changed inside the cache.
   *
   * @param cachedMatrices The existing cached matrices. Gets updated if it is out of date.
   * @param newMatrices The new matrices to compare the cached matrices against.
   */
  private static boolean updateMatrixCache(float[][] cachedMatrices, float[][] newMatrices) {
    boolean matrixChanged = false;
    for (int i = 0; i < cachedMatrices.length; i++) {
      float[] cachedMatrix = cachedMatrices[i];
      float[] newMatrix = newMatrices[i];
      if (!Arrays.equals(cachedMatrix, newMatrix)) {
        checkState(newMatrix.length == 16, "A 4x4 transformation matrix must have 16 elements");
        System.arraycopy(
            /* src= */ newMatrix,
            /* srcPos= */ 0,
            /* dest= */ cachedMatrix,
            /* destPost= */ 0,
            /* length= */ newMatrix.length);
        matrixChanged = true;
      }
    }
    return matrixChanged;
  }

  private void setGainmapSamplerAndUniforms() throws GlUtil.GlException {
    if (lastGainmap == null) {
      return;
    }
    if (SDK_INT < 34) {
      throw new IllegalStateException("Gainmaps not supported under API 34.");
    }
    glProgram.setSamplerTexIdUniform("uGainmapTexSampler", gainmapTexId, /* texUnitIndex= */ 1);
    GainmapUtil.setGainmapUniforms(glProgram, lastGainmap, C.INDEX_UNSET);
  }
}
