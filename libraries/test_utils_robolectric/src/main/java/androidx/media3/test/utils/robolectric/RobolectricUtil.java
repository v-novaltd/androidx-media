/*
 * Copyright (C) 2020 The Android Open Source Project
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
package androidx.media3.test.utils.robolectric;

import static org.robolectric.Shadows.shadowOf;

import android.os.Looper;
import androidx.media3.common.util.Clock;
import androidx.media3.common.util.ConditionVariable;
import androidx.media3.common.util.SystemClock;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.test.utils.ThreadTestUtil;
import com.google.common.base.Supplier;
import java.time.Duration;
import java.util.concurrent.TimeoutException;
import org.robolectric.shadows.ShadowLooper;

/** Utility methods for Robolectric-based tests. */
@UnstableApi
public final class RobolectricUtil {

  private RobolectricUtil() {}

  /**
   * The default timeout applied when calling {@link #runMainLooperUntil(Supplier)}. This timeout
   * should be sufficient for any condition using a Robolectric test.
   */
  public static final long DEFAULT_TIMEOUT_MS = 10_000;

  /**
   * The default maximum allowed time difference between two messages on the {@link Looper} when
   * running {@link #runMainLooperUntil} or {@link #runLooperUntil}.
   */
  public static final long DEFAULT_MAX_TIME_DIFF_MS = 1000;

  /**
   * Creates a {@link ConditionVariable} whose {@link ConditionVariable#block(long)} method times
   * out according to wallclock time when used in Robolectric tests.
   */
  public static ConditionVariable createRobolectricConditionVariable() {
    return new ConditionVariable(
        new SystemClock() {
          @Override
          public long elapsedRealtime() {
            // elapsedRealtime() does not advance during Robolectric test execution, so use
            // currentTimeMillis() instead. This is technically unsafe because this clock is not
            // guaranteed to be monotonic, but in practice it will work provided the clock of the
            // host machine does not change during test execution.
            return Clock.DEFAULT.currentTimeMillis();
          }
        });
  }

  /**
   * Runs tasks of the main Robolectric {@link Looper} until the {@code condition} returns {@code
   * true}.
   *
   * <p>Must be called on the main test thread.
   *
   * <p>Tasks are only run if their target time is within {@link #DEFAULT_MAX_TIME_DIFF_MS} of the
   * current {@link SystemClock} time.
   *
   * <p>Note for {@link androidx.media3.test.utils.FakeClock} users: If the condition changes
   * outside of a main {@link Looper} message, for example because it's checking a volatile variable
   * or shared synchronized state that is updated on a background thread, or because checking the
   * condition itself may cause it to become true, then the remainder of the test method may be
   * executed in parallel with other background thread messages.
   *
   * @param condition The condition.
   * @throws TimeoutException If the {@link #DEFAULT_TIMEOUT_MS} is exceeded.
   */
  public static void runMainLooperUntil(Supplier<Boolean> condition) throws TimeoutException {
    runMainLooperUntil(condition, DEFAULT_TIMEOUT_MS, Clock.DEFAULT);
  }

  /**
   * Runs tasks of the main Robolectric {@link Looper} until the {@code condition} returns {@code
   * true}.
   *
   * <p>Must be called on the main test thread.
   *
   * <p>Note for {@link androidx.media3.test.utils.FakeClock} users: If the condition changes
   * outside of a main {@link Looper} message, for example because it's checking a volatile variable
   * or shared synchronized state that is updated on a background thread, or because checking the
   * condition itself may cause it to become true, then the remainder of the test method may be
   * executed in parallel with other background thread messages.
   *
   * @param maxTimeDiffMs The maximum allowed time differences between two messages on the {@link
   *     Looper}.
   * @param condition The condition.
   * @throws TimeoutException If the {@link #DEFAULT_TIMEOUT_MS} is exceeded.
   */
  public static void runMainLooperUntil(long maxTimeDiffMs, Supplier<Boolean> condition)
      throws TimeoutException {
    runLooperUntil(
        Looper.getMainLooper(), condition, DEFAULT_TIMEOUT_MS, Clock.DEFAULT, maxTimeDiffMs);
  }

  /**
   * Runs tasks of the main Robolectric {@link Looper} until the {@code condition} returns {@code
   * true}.
   *
   * <p>Must be called on the main test thread.
   *
   * <p>Tasks are only run if their target time is within {@link #DEFAULT_MAX_TIME_DIFF_MS} of the
   * current {@link SystemClock} time.
   *
   * <p>Note for {@link androidx.media3.test.utils.FakeClock} users: If the condition changes
   * outside of a main {@link Looper} message, for example because it's checking a volatile variable
   * or shared synchronized state that is updated on a background thread, or because checking the
   * condition itself may cause it to become true, then the remainder of the test method may be
   * executed in parallel with other background thread messages.
   *
   * @param condition The condition.
   * @param timeoutMs The timeout in milliseconds.
   * @param clock The {@link Clock} to measure the timeout.
   * @throws TimeoutException If the {@code timeoutMs timeout} is exceeded.
   */
  public static void runMainLooperUntil(Supplier<Boolean> condition, long timeoutMs, Clock clock)
      throws TimeoutException {
    runLooperUntil(Looper.getMainLooper(), condition, timeoutMs, clock);
  }

  /**
   * Runs tasks of the {@code looper} until the {@code condition} returns {@code true}.
   *
   * <p>Must be called on the thread corresponding to the {@code looper}.
   *
   * <p>Tasks are only run if their target time is within {@link #DEFAULT_MAX_TIME_DIFF_MS} of the
   * current {@link SystemClock} time.
   *
   * <p>Note for {@link androidx.media3.test.utils.FakeClock} users: If the condition changes
   * outside of a message on this {@code Looper}, for example because it's checking a volatile
   * variable or shared synchronized state that is updated on a background thread, or because
   * checking the condition itself may cause it to become true, then the remainder of the test
   * method may be executed in parallel with other background thread messages.
   *
   * @param looper The {@link Looper}.
   * @param condition The condition.
   * @throws TimeoutException If the {@link #DEFAULT_TIMEOUT_MS} is exceeded.
   */
  public static void runLooperUntil(Looper looper, Supplier<Boolean> condition)
      throws TimeoutException {
    runLooperUntil(looper, condition, DEFAULT_TIMEOUT_MS, Clock.DEFAULT);
  }

  /**
   * Runs tasks of the {@code looper} until the {@code condition} returns {@code true}.
   *
   * <p>Must be called on the thread corresponding to the {@code looper}.
   *
   * <p>Tasks are only run if their target time is within {@link #DEFAULT_MAX_TIME_DIFF_MS} of the
   * current {@link SystemClock} time.
   *
   * <p>Note for {@link androidx.media3.test.utils.FakeClock} users: If the condition changes
   * outside of a message on this {@code Looper}, for example because it's checking a volatile
   * variable or shared synchronized state that is updated on a background thread, or because
   * checking the condition itself may cause it to become true, then the remainder of the test
   * method may be executed in parallel with other background thread messages.
   *
   * @param looper The {@link Looper}.
   * @param condition The condition.
   * @param timeoutMs The timeout in milliseconds.
   * @param clock The {@link Clock} to measure the timeout.
   * @throws TimeoutException If the {@code timeoutMs timeout} is exceeded.
   */
  public static void runLooperUntil(
      Looper looper, Supplier<Boolean> condition, long timeoutMs, Clock clock)
      throws TimeoutException {
    runLooperUntil(looper, condition, timeoutMs, clock, DEFAULT_MAX_TIME_DIFF_MS);
  }

  /**
   * Runs tasks of the {@code looper} until the {@code condition} returns {@code true}.
   *
   * <p>Must be called on the thread corresponding to the {@code looper}.
   *
   * <p>Note for {@link androidx.media3.test.utils.FakeClock} users: If the condition changes
   * outside of a message on this {@code Looper}, for example because it's checking a volatile
   * variable or shared synchronized state that is updated on a background thread, or because
   * checking the condition itself may cause it to become true, then the remainder of the test
   * method may be executed in parallel with other background thread messages.
   *
   * @param looper The {@link Looper}.
   * @param condition The condition.
   * @param timeoutMs The timeout in milliseconds.
   * @param clock The {@link Clock} to measure the timeout.
   * @param maxTimeDiffMs The maximum allowed time differences between two messages on the {@link
   *     Looper}.
   * @throws TimeoutException If the {@code timeoutMs timeout} is exceeded.
   */
  public static void runLooperUntil(
      Looper looper, Supplier<Boolean> condition, long timeoutMs, Clock clock, long maxTimeDiffMs)
      throws TimeoutException {
    if (Looper.myLooper() != looper) {
      throw new IllegalStateException();
    }
    ThreadTestUtil.unblockThreadsWaitingForProgressOnCurrentLooper();
    ShadowLooper shadowLooper = shadowOf(looper);
    long timeoutTimeMs = clock.currentTimeMillis() + timeoutMs;
    while (!condition.get()) {
      if (clock.currentTimeMillis() >= timeoutTimeMs) {
        throw new TimeoutException();
      }
      Duration nextTaskTime = shadowLooper.getNextScheduledTaskTime();
      if (!nextTaskTime.isZero()
          && nextTaskTime.toMillis() <= android.os.SystemClock.elapsedRealtime() + maxTimeDiffMs) {
        shadowLooper.runOneTask();
      }
    }
  }
}
