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
package androidx.media3.exoplayer.source;

import android.os.Bundle;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.TrackGroup;
import androidx.media3.common.util.BundleCollectionUtil;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import java.util.List;

/**
 * An immutable array of {@link TrackGroup}s.
 *
 * <p>This class is typically used to represent all of the tracks available in a piece of media.
 * Tracks that are known to present the same content are grouped together (e.g., the same video feed
 * provided at different resolutions in an adaptive stream). Tracks that are known to present
 * different content are in separate track groups (e.g., an audio track will not be in the same
 * group as a video track, and an audio track in one language will be in a different group to an
 * audio track in another language).
 */
@UnstableApi
public final class TrackGroupArray {

  private static final String TAG = "TrackGroupArray";

  /** The empty array. */
  public static final TrackGroupArray EMPTY = new TrackGroupArray();

  /** The number of groups in the array. Greater than or equal to zero. */
  public final int length;

  private final ImmutableList<TrackGroup> trackGroups;

  // Lazily initialized hashcode.
  private int hashCode;

  /**
   * Construct a {@code TrackGroupArray} from an array of {@link TrackGroup TrackGroups}.
   *
   * <p>The groups must not contain duplicates.
   */
  public TrackGroupArray(TrackGroup... trackGroups) {
    this.trackGroups = ImmutableList.copyOf(trackGroups);
    this.length = trackGroups.length;
    verifyCorrectness();
  }

  /**
   * Returns the group at a given index.
   *
   * @param index The index of the group.
   * @return The group.
   */
  public TrackGroup get(int index) {
    return trackGroups.get(index);
  }

  /**
   * Returns the index of a group within the array.
   *
   * @param group The group.
   * @return The index of the group, or {@link C#INDEX_UNSET} if no such group exists.
   */
  public int indexOf(TrackGroup group) {
    int index = trackGroups.indexOf(group);
    return index >= 0 ? index : C.INDEX_UNSET;
  }

  /** Returns whether this track group array is empty. */
  public boolean isEmpty() {
    return length == 0;
  }

  /** Returns the {@link TrackGroup#type} of each track group in this array. */
  public ImmutableList<@C.TrackType Integer> getTrackTypes() {
    return ImmutableList.copyOf(Lists.transform(trackGroups, t -> t.type));
  }

  @Override
  public int hashCode() {
    if (hashCode == 0) {
      hashCode = trackGroups.hashCode();
    }
    return hashCode;
  }

  @Override
  public boolean equals(@Nullable Object obj) {
    if (this == obj) {
      return true;
    }
    if (obj == null || getClass() != obj.getClass()) {
      return false;
    }
    TrackGroupArray other = (TrackGroupArray) obj;
    return length == other.length && trackGroups.equals(other.trackGroups);
  }

  @Override
  public String toString() {
    return trackGroups.toString();
  }

  private static final String FIELD_TRACK_GROUPS = Util.intToStringMaxRadix(0);

  public Bundle toBundle() {
    Bundle bundle = new Bundle();
    bundle.putParcelableArrayList(
        FIELD_TRACK_GROUPS,
        BundleCollectionUtil.toBundleArrayList(trackGroups, TrackGroup::toBundle));
    return bundle;
  }

  /** Restores a {@code TrackGroupArray} from a {@link Bundle}. */
  public static TrackGroupArray fromBundle(Bundle bundle) {
    @Nullable List<Bundle> trackGroupBundles = bundle.getParcelableArrayList(FIELD_TRACK_GROUPS);
    if (trackGroupBundles == null) {
      return new TrackGroupArray();
    }
    return new TrackGroupArray(
        BundleCollectionUtil.fromBundleList(TrackGroup::fromBundle, trackGroupBundles)
            .toArray(new TrackGroup[0]));
  }

  private void verifyCorrectness() {
    for (int i = 0; i < trackGroups.size(); i++) {
      for (int j = i + 1; j < trackGroups.size(); j++) {
        if (trackGroups.get(i).equals(trackGroups.get(j))) {
          Log.e(
              TAG,
              "",
              new IllegalArgumentException(
                  "Multiple identical TrackGroups added to one TrackGroupArray."));
        }
      }
    }
  }
}
