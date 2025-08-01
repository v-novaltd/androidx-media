/*
 * Copyright 2021 The Android Open Source Project
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
package androidx.media3.common;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit tests for {@link DeviceInfo}. */
@RunWith(AndroidJUnit4.class)
public class DeviceInfoTest {

  @Test
  public void roundTripViaBundle_yieldsEqualInstance() {
    DeviceInfo deviceInfo =
        new DeviceInfo.Builder(DeviceInfo.PLAYBACK_TYPE_REMOTE)
            .setMinVolume(1)
            .setMaxVolume(9)
            .setRoutingControllerId("route")
            .build();

    assertThat(DeviceInfo.fromBundle(deviceInfo.toBundle())).isEqualTo(deviceInfo);
  }

  @Test
  public void build_playbackTypeLocalWithRoutingId_throwsIllegalStateException() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new DeviceInfo.Builder(DeviceInfo.PLAYBACK_TYPE_LOCAL)
                .setRoutingControllerId("route")
                .build());
  }
}
