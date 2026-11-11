/*
 * Adapted from JetStreamCompose VideoPlayerMainFrame.
 * Copyright 2024 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.kkwans.fileway.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Full-width seeker with a genuinely centered transport group and balanced side slots. */
@Composable
internal fun TvPlayerMainFrame(
    seeker: @Composable () -> Unit,
    leftActions: @Composable () -> Unit,
    transport: @Composable () -> Unit,
    rightActions: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        seeker()
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) { leftActions() }
            Box(Modifier.width(168.dp), contentAlignment = Alignment.Center) { transport() }
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) { rightActions() }
        }
    }
}
