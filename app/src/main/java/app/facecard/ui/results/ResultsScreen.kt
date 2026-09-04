package app.facecard.ui.results

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Face
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.facecard.data.FaceCardApp
import app.facecard.domain.model.Person
import app.facecard.ui.theme.FaceCardTheme
import kotlinx.coroutines.launch
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResultsScreen(
    onViewCollage: () -> Unit,
    onBack: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as FaceCardApp
    val result by app.resultStore.result.collectAsState()
    val thumbs by app.resultStore.thumbs.collectAsState()
    val hints by app.resultStore.hints.collectAsState()
    var expandedId by rememberSaveable { mutableStateOf<Int?>(null) }
    var armedMerge by rememberSaveable { mutableStateOf<String?>(null) }
    val mergeScope = rememberCoroutineScope()

    Scaffold(
        topBar = { TopAppBar(title = { Text("Results") }) },
    ) { pad ->
        val res = result
        if (res == null || res.people.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(pad)
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("No result yet", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Process a video first — people and appearance counts land here.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = onBack) { Text("Back") }
            }
            return@Scaffold
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad)
                .padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(4.dp))
            Text(
                "${res.personCount} people · ${res.totalAppearances} appearances",
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "Every person appears exactly once in the collage.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.weight(1f),
            ) {
                items(res.people, key = { it.id }) { person ->
                    val shared = remember(res, person) {
                        sharesFrameWithSomeone(res.people, person)
                    }
                    val hint = remember(hints, person) {
                        hints.firstOrNull { it.aId == person.id || it.bId == person.id }
                    }
                    val otherId = hint?.let {
                        if (it.aId == person.id) it.bId else it.aId
                    }
                    val otherLabel = res.people.find { it.id == otherId }?.label
                    PersonCard(
                        person = person,
                        thumb = thumbs[person.id],
                        expanded = expandedId == person.id,
                        sharedBadge = shared,
                        hintSim = hint?.sim,
                        otherLabel = otherLabel,
                        armed = armedMerge == "${person.id}->$otherId",
                        onToggle = {
                            expandedId = if (expandedId == person.id) null else person.id
                        },
                        onMerge = {
                            val other = otherId ?: return@PersonCard
                            val key = "${person.id}->$other"
                            if (armedMerge == key) {
                                armedMerge = null
                                if (expandedId == other) expandedId = null
                                mergeScope.launch {
                                    app.resultStore.mergePeople(person.id, other)
                                }
                            } else {
                                armedMerge = key
                            }
                        },
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onViewCollage,
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding(),
            ) {
                Text("View collage")
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

/** True when any of this person's segments overlaps another person's segment. */
private fun sharesFrameWithSomeone(people: List<Person>, me: Person): Boolean {
    for (other in people) {
        if (other.id == me.id) continue
        for (a in me.appearances) for (b in other.appearances) {
            if (a.startMs < b.endMs && b.startMs < a.endMs) return true
        }
    }
    return false
}

@Composable
private fun PersonCard(
    person: Person,
    thumb: android.graphics.Bitmap?,
    expanded: Boolean,
    sharedBadge: Boolean,
    hintSim: Float?,
    otherLabel: String?,
    armed: Boolean,
    onToggle: () -> Unit,
    onMerge: () -> Unit,
) {
    Card(
        onClick = onToggle,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        ),
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Column {
            if (thumb != null && !thumb.isRecycled) {
                Image(
                    bitmap = thumb.asImageBitmap(),
                    contentDescription = "${person.label}, best shot",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(3f / 4f)
                        .padding(8.dp)
                        .clip(MaterialTheme.shapes.large),
                )
            } else {
                Icon(
                    Icons.Rounded.Face,
                    contentDescription = null,
                    modifier = Modifier.padding(24.dp),
                )
            }
            Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                Text(person.label, style = MaterialTheme.typography.titleLarge)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AssistChip(
                        onClick = {},
                        label = { Text("×${person.appearanceCount}") },
                        leadingIcon = {
                            Icon(Icons.Rounded.Groups, contentDescription = null)
                        },
                    )
                    if (sharedBadge) {
                        Icon(
                            Icons.Rounded.CheckCircle,
                            contentDescription = "Shares a frame with someone",
                            tint = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                }
                QualityBadges(person)
                if (hintSim != null && otherLabel != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Looks like $otherLabel · ${"%.2f".format(hintSim)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                    TextButton(onClick = onMerge) {
                        Text(if (armed) "Tap again to merge" else "Merge into one")
                    }
                }
            }
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(),
                exit = shrinkVertically(),
            ) {
                Column(
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    person.appearances.forEachIndexed { i, seg ->
                        Text(
                            "${i + 1}. ${fmtTs(seg.startMs)}–${fmtTs(seg.endMs)} · " +
                                "${seg.frames} frames",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QualityBadges(person: Person) {
    val best = person.best
    val badges = buildList {
        if (abs(best.eulerY) < 15f) add("Frontal")
        if (best.eyeOpen > 0.5f) add("Eyes open")
        if (best.smiling > 0.5f) add("Smiling")
        if (!best.edgeClipped) add("Full face")
    }.take(3)
    if (badges.isEmpty()) return
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        badges.forEach {
            Text(
                it,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.tertiary,
                maxLines = 1,
            )
        }
    }
}

private fun fmtTs(ms: Long): String {
    val s = ms / 1000
    return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
}

@Preview(showBackground = true)
@Composable
private fun ResultsPreviewEmpty() {
    FaceCardTheme(darkTheme = false, dynamicColor = false) {
        Text("Results preview needs a store — see device builds.")
    }
}
