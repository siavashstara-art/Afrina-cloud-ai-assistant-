package com.example.afrina.core.designsystem

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.afrina.core.i18n.LocalAfrinaStrings
import com.example.afrina.core.model.ImplementationOrigin
import com.example.afrina.core.model.PipelineStatus
import com.example.afrina.core.model.ProjectHealthStatus

@Composable
fun AfrinaSurfaceCard(
    modifier: Modifier = Modifier,
    highlightColor: Color? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val borderColor = highlightColor?.copy(alpha = 0.35f)
        ?: MaterialTheme.colorScheme.outline.copy(alpha = 0.65f)

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        border = BorderStroke(1.dp, borderColor),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (highlightColor != null) {
                        Modifier.background(
                            Brush.verticalGradient(
                                listOf(
                                    highlightColor.copy(alpha = 0.08f),
                                    Color.Transparent,
                                )
                            )
                        )
                    } else {
                        Modifier
                    }
                )
                .padding(16.dp),
            content = content,
        )
    }
}

@Composable
fun StatusPill(
    label: String,
    color: Color,
    modifier: Modifier = Modifier,
    mono: Boolean = false,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(999.dp),
        color = color.copy(alpha = 0.14f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.35f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(color)
            )
            Text(
                text = label,
                style = if (mono) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium,
                color = color,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun OriginBadge(origin: ImplementationOrigin, modifier: Modifier = Modifier) {
    val strings = LocalAfrinaStrings.current
    val (label, color) = when (origin) {
        ImplementationOrigin.LOCAL_PERSISTED -> strings.badgeLocalRoom to AfrinaPalette.EmeraldHealthy
        ImplementationOrigin.SIMULATED_PHASE_0 -> strings.badgeSimulated to AfrinaPalette.VioletSimulated
        ImplementationOrigin.PLANNED_REMOTE -> strings.badgePlanned to AfrinaPalette.AmberWarning
    }
    StatusPill(label = label, color = color, modifier = modifier)
}

@Composable
fun SectionHeader(
    title: String,
    icon: ImageVector,
    origin: ImplementationOrigin? = null,
    actionLabel: String? = null,
    onActionClick: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (origin != null) {
                OriginBadge(origin = origin)
            }
        }
        if (actionLabel != null && onActionClick != null) {
            TextButton(onClick = onActionClick) {
                Text(
                    text = actionLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
fun KpiMetricTile(
    label: String,
    value: String,
    accent: Color,
    icon: ImageVector,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.28f)),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = value,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun healthColor(status: ProjectHealthStatus): Color =
    when (status) {
        ProjectHealthStatus.HEALTHY -> AfrinaPalette.EmeraldHealthy
        ProjectHealthStatus.WARNING -> AfrinaPalette.AmberWarning
        ProjectHealthStatus.DEGRADED -> AfrinaPalette.RoseAlert
        ProjectHealthStatus.IDLE -> AfrinaPalette.TextSecondaryDark
    }

@Composable
fun pipelineColor(status: PipelineStatus): Color =
    when (status) {
        PipelineStatus.PASSED -> AfrinaPalette.EmeraldHealthy
        PipelineStatus.RUNNING -> AfrinaPalette.ElectricCyan
        PipelineStatus.FAILED -> AfrinaPalette.RoseAlert
        PipelineStatus.QUEUED -> AfrinaPalette.AmberWarning
    }

@Composable
fun MonoChip(text: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
