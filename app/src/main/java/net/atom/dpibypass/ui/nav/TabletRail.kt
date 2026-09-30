package net.atom.dpibypass.ui.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.compose.currentBackStackEntryAsState
import net.atom.dpibypass.ui.design.GlassLevel
import net.atom.dpibypass.ui.design.GlassSurface
import net.atom.dpibypass.ui.design.LocalShellBackdrop
import net.atom.dpibypass.ui.design.rememberHaptics

/** Tabletlerde telefon dock'unun yerine sabit ve etiketli gezinme. */
@Composable
fun TabletRail(navController: NavController, modifier: Modifier = Modifier) {
    val backStack by navController.currentBackStackEntryAsState()
    val current = backStack?.destination?.route ?: Dest.Home.route
    val haptics = rememberHaptics()
    val shell = LocalShellBackdrop.current

    GlassSurface(
        modifier = modifier.width(88.dp).fillMaxHeight(),
        shape = RoundedCornerShape(topEnd = 26.dp, bottomEnd = 26.dp),
        level = GlassLevel.Shell,
        backdrop = shell,
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Dest.entries.forEach { dest ->
                val isSelected = current == dest.route
                val shape = RoundedCornerShape(20.dp)
                Column(
                    modifier = Modifier
                        .width(76.dp)
                        .clip(shape)
                        .background(
                            if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                            else MaterialTheme.colorScheme.surface.copy(alpha = 0f)
                        )
                        .clickable {
                            if (isSelected) haptics.tick() else {
                                haptics.select()
                                navController.navigate(dest.route) {
                                    popUpTo(Dest.Home.route) { saveState = false }
                                    launchSingleTop = true
                                    restoreState = false
                                }
                            }
                        }
                        .clearAndSetSemantics {
                            contentDescription = dest.label
                            selected = isSelected
                        }
                        .padding(vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(Modifier.size(30.dp), contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = if (isSelected) dest.iconSelected else dest.icon,
                            contentDescription = null,
                            tint = if (isSelected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = dest.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isSelected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
