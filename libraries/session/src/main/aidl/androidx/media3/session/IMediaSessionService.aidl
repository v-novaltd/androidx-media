/*
 * Copyright 2018 The Android Open Source Project
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
package androidx.media3.session;

import androidx.media3.session.IMediaController;

/**
 * Interface from MediaController to MediaSessionService.
 *
 * <p>It's for internal use only, not intended to be used by library users.
 */
// Note: Keep this interface oneway. Otherwise a malicious app may make a blocking call to make
// session service frozen.
@JavaPassthrough(annotation="@androidx.annotation.RestrictTo(androidx.annotation.RestrictTo.Scope.LIBRARY)")
oneway interface IMediaSessionService {

  // Id < 3000 is reserved to avoid potential collision with media2 1.x.

  void connect(IMediaController caller, in Bundle connectionRequest) = 3000;
  // Next Id : 3001
}
