/*
 * Copyright 2024 The Android Open Source Project
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
package androidx.media3.extractor.avif;

import androidx.media3.test.utils.ExtractorAsserts;
import com.google.common.collect.ImmutableList;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.ParameterizedRobolectricTestRunner;

/** Unit tests for {@link AvifExtractor}. */
@RunWith(ParameterizedRobolectricTestRunner.class)
public final class AvifExtractorTest {

  @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
  public static ImmutableList<ExtractorAsserts.SimulationConfig> params() {
    return ExtractorAsserts.configs();
  }

  @ParameterizedRobolectricTestRunner.Parameter
  public ExtractorAsserts.SimulationConfig simulationConfig;

  @Test
  public void sampleAvif() throws Exception {
    ExtractorAsserts.assertBehavior(
        AvifExtractor::new, "media/avif/white-1x1.avif", simulationConfig);
  }
}
